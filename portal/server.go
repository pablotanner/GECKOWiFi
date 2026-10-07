package main

import (
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type site struct {
	Site
	cert tls.Certificate
	spki string
}

type server struct {
	cfg      *Config
	sites    map[string]*site // lower-case host -> site
	portal   *site
	events   *eventLog
	httpsSrv *http.Server

	// S5a relay timer: client IP -> first time it connected.
	firstSeenMu sync.Mutex
	firstSeen   map[string]time.Time
}

func serveCmd(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	configPath := fs.String("config", "config/genuine.json", "role config file")
	fs.Parse(args)

	cfg, err := loadConfig(*configPath)
	if err != nil {
		return err
	}
	s := &server{cfg: cfg, sites: map[string]*site{}, firstSeen: map[string]time.Time{}}
	for _, sc := range cfg.Sites {
		pair, err := tls.LoadX509KeyPair(sc.Cert, sc.Key)
		if err != nil {
			return fmt.Errorf("site %s: %w (run: portal keygen -host %s)", sc.Host, err, sc.Host)
		}
		parsed, err := readCert(sc.Cert)
		if err != nil {
			return err
		}
		st := &site{Site: sc, cert: pair, spki: spkiHash(parsed)}
		s.sites[strings.ToLower(sc.Host)] = st
		if strings.EqualFold(sc.Host, cfg.PortalHost) {
			s.portal = st
		}
		log.Printf("[%s] %-22s %-8s SPKI %s", cfg.Role, sc.Host, sc.Kind, st.spki)
	}
	if s.events, err = openEventLog(cfg.LogDir, cfg.Role); err != nil {
		return err
	}
	if cfg.Scenario != nil {
		s.events.scenario = cfg.Scenario.Name
		log.Printf("[%s] scenario %q active", cfg.Role, cfg.Scenario.Name)
	}

	httpSrv := &http.Server{
		Addr:              net.JoinHostPort(cfg.ListenIP, "80"),
		Handler:           http.HandlerFunc(s.serveHTTP),
		ReadHeaderTimeout: 10 * time.Second,
	}
	s.httpsSrv = &http.Server{
		Addr:              net.JoinHostPort(cfg.ListenIP, "443"),
		Handler:           http.HandlerFunc(s.serveHTTPS),
		ReadHeaderTimeout: 10 * time.Second,
		TLSConfig: &tls.Config{
			MinVersion:     tls.VersionTLS12,
			GetCertificate: s.certificateFor,
		},
	}
	errs := make(chan error, 2)
	go func() { errs <- httpSrv.ListenAndServe() }()
	httpsAddr := net.JoinHostPort(cfg.ListenIP, "443")
	if sc := cfg.Scenario; sc != nil && (len(sc.RelayHosts) > 0 || sc.RelayForSeconds > 0) {
		// Some hostnames are passed through to the genuine portal, so :443 is
		// an SNI-routing listener rather than a plain TLS server.
		go func() { errs <- s.serveTLSWithRelay(httpsAddr) }()
		log.Printf("[%s] listening on %s:80 and %s:443 (TLS, relaying %v to %s)",
			cfg.Role, cfg.ListenIP, cfg.ListenIP, sc.RelayHosts, sc.relayTarget())
	} else {
		go func() { errs <- s.httpsSrv.ListenAndServeTLS("", "") }()
		log.Printf("[%s] listening on %s:80 (redirect) and %s:443 (TLS)", cfg.Role, cfg.ListenIP, cfg.ListenIP)
	}
	return <-errs
}

// certificateFor picks the key by SNI. Without SNI (or for an unknown name)
// the portal's own certificate is presented.
func (s *server) certificateFor(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
	if st, ok := s.sites[strings.ToLower(hello.ServerName)]; ok {
		return &st.cert, nil
	}
	return &s.portal.cert, nil
}

func hostOnly(hostport string) string {
	if h, _, err := net.SplitHostPort(hostport); err == nil {
		return strings.ToLower(h)
	}
	return strings.ToLower(hostport)
}

// serveHTTP (port 80) is where openNDS sends clients (FAS URL); it upgrades to
// HTTPS on the same host, keeping openNDS's query (tok, authaction, redir).
func (s *server) serveHTTP(w http.ResponseWriter, r *http.Request) {
	sc := s.cfg.Scenario
	host := hostOnly(r.Host)
	// Keep the requested host when we either serve it locally or relay it to
	// the genuine portal; only an unknown name falls back to the login host.
	// A relay host (e.g. portal.gecko-a.lab in S3) is not a local site, but
	// its http->https upgrade must still point back at itself so the HTTPS
	// hop is relayed and the probe sees the genuine key before any switch.
	if _, ok := s.sites[host]; !ok && !sc.relays(host) {
		host = s.cfg.PortalHost
	}

	// S6: the login page is served over plain HTTP, with no upgrade to TLS.
	// The probe's last hop is then plain HTTP on a registered domain, which
	// the app judges as a downgrade (CONFLICT).
	if sc != nil && sc.HTTPOnly {
		s.servePortalPage(w, r, s.portal, "http")
		return
	}

	// The genuine flow's plain-HTTP /continue hop (HTTPBounce). On the genuine
	// portal it just loops back to the login page; on a relay attacker in
	// front of it, this same hop is where the switch happens (S3).
	if sc != nil && r.URL.Path == "/continue" {
		switch {
		case sc.Switch != "":
			s.writeSwitch(w, r, sc)
			return
		case sc.HTTPBounce:
			target := "https://" + host + "/login"
			if r.URL.RawQuery != "" {
				target += "?" + r.URL.RawQuery
			}
			s.events.record(r, "http", "bounce_continue", map[string]string{"location": target})
			http.Redirect(w, r, target, http.StatusFound)
			return
		}
	}

	// S5b: the detection probe (matched by User-Agent) is sent down the
	// genuine path; every other client is sent to the attacker. The decision
	// is made here because the User-Agent is only visible on this plain hop.
	if sc != nil && sc.ProbeUserAgent != "" && !strings.Contains(r.UserAgent(), sc.ProbeUserAgent) {
		s.events.record(r, "http", "ua_switch",
			map[string]string{"location": sc.SwitchTo, "user_agent": r.UserAgent()})
		http.Redirect(w, r, sc.SwitchTo, http.StatusFound)
		return
	}

	target := "https://" + host + r.URL.RequestURI()
	s.events.record(r, "http", "redirect_https", map[string]string{"location": target})
	http.Redirect(w, r, target, http.StatusFound)
}

// writeSwitch bounces the client to the attacker host by one of the redirect
// mechanisms, so each can be tested against the probe (which follows 3xx and
// meta refresh, but not a Refresh header or JavaScript).
func (s *server) writeSwitch(w http.ResponseWriter, r *http.Request, sc *Scenario) {
	s.events.record(r, "http", "switch_"+sc.Switch, map[string]string{"location": sc.SwitchTo})
	switch sc.Switch {
	case "redirect":
		http.Redirect(w, r, sc.SwitchTo, http.StatusFound)
	case "meta_refresh":
		render(w, http.StatusOK, switchMetaPage, map[string]any{"Delay": sc.SwitchDelaySeconds, "URL": sc.SwitchTo})
	case "refresh_header":
		w.Header().Set("Refresh", fmt.Sprintf("%d; url=%s", sc.SwitchDelaySeconds, sc.SwitchTo))
		render(w, http.StatusOK, switchHeaderPage, map[string]any{"URL": sc.SwitchTo})
	case "js":
		render(w, http.StatusOK, switchJSPage, map[string]any{"URL": sc.SwitchTo})
	}
}

func (s *server) serveHTTPS(w http.ResponseWriter, r *http.Request) {
	st, ok := s.sites[hostOnly(r.Host)]
	if !ok {
		st = s.portal
	}
	switch st.Kind {
	case "portal":
		s.servePortal(w, r, st)
	case "payment":
		s.servePayment(w, r, st)
	}
}

// ndsSession is what openNDS (fas_secure_enabled 0) passes to the FAS and what
// the portal needs to hand the client back to openNDS after login.
type ndsSession struct {
	Tok, AuthAction, Redir, ClientIP, ClientMAC string
}

func sessionFrom(v url.Values) ndsSession {
	return ndsSession{
		Tok:        v.Get("tok"),
		AuthAction: v.Get("authaction"),
		Redir:      v.Get("redir"),
		ClientIP:   v.Get("clientip"),
		ClientMAC:  v.Get("clientmac"),
	}
}

func (n ndsSession) query() string {
	v := url.Values{}
	for k, val := range map[string]string{"tok": n.Tok, "authaction": n.AuthAction, "redir": n.Redir,
		"clientip": n.ClientIP, "clientmac": n.ClientMAC} {
		if val != "" {
			v.Set(k, val)
		}
	}
	return v.Encode()
}

func (s *server) servePortal(w http.ResponseWriter, r *http.Request, st *site) {
	sc := s.cfg.Scenario
	switch {
	case (r.URL.Path == "/" || r.URL.Path == "/login") && r.Method == http.MethodGet:
		// HTTPBounce (S3, genuine side): the first HTTPS hit redirects down to
		// a plain-HTTP /continue hop before the login page. The genuine key is
		// presented on this 302, so the probe still anchors on the genuine
		// primary - a relay attacker rewrites the plain hop that follows.
		if sc != nil && sc.HTTPBounce && r.URL.Path == "/" {
			target := "http://" + st.Host + "/continue"
			if r.URL.RawQuery != "" {
				target += "?" + r.URL.RawQuery
			}
			s.events.record(r, "https", "bounce_http", map[string]string{"location": target})
			http.Redirect(w, r, target, http.StatusFound)
			return
		}
		// PaymentViaRedirect (S4a, genuine side): go straight to the payment
		// delegate with a 302 instead of offering a button, so the
		// redirect-only probe reaches it.
		if sc != nil && sc.PaymentViaRedirect && r.URL.Path == "/" {
			if co := s.checkoutURL(r, st); co != "" {
				s.events.record(r, "https", "redirect_payment", map[string]string{"location": co})
				http.Redirect(w, r, co, http.StatusFound)
				return
			}
		}
		s.servePortalPage(w, r, st, "https")
	case r.URL.Path == "/accept" && r.Method == http.MethodPost:
		r.ParseForm()
		// S5c: flip to the attacker only after the user clicks Accept - after
		// any one-shot check has already passed.
		if sc != nil && sc.SwitchAfterAccept {
			s.events.record(r, "https", "switch_after_accept", map[string]string{"location": sc.SwitchTo})
			http.Redirect(w, r, sc.SwitchTo, http.StatusFound)
			return
		}
		s.finishLogin(w, r, sessionFrom(r.PostForm), "accept")
	case r.URL.Path == "/complete" && r.Method == http.MethodGet:
		s.finishLogin(w, r, sessionFrom(r.URL.Query()), "complete_after_payment")
	default:
		s.events.record(r, "https", "not_found", nil)
		http.NotFound(w, r)
	}
}

// servePortalPage renders the login page (the chain's terminal 200). [scheme]
// is only for the log line - "http" when served over plain HTTP for S6.
func (s *server) servePortalPage(w http.ResponseWriter, r *http.Request, st *site, scheme string) {
	sess := sessionFrom(r.URL.Query())
	s.events.record(r, scheme, "login_page", nil)
	render(w, http.StatusOK, loginPage, map[string]any{
		"Venue": s.cfg.VenueName, "Host": st.Host, "Session": sess,
		"HasSession": sess.Tok != "", "Checkout": s.checkoutURL(r, st),
	})
}

// checkoutURL builds the payment-delegate URL for the current session, or ""
// if this role has no payment site.
func (s *server) checkoutURL(r *http.Request, st *site) string {
	payHost := ""
	for _, other := range s.cfg.Sites {
		if other.Kind == "payment" {
			payHost = other.Host
			break
		}
	}
	if payHost == "" {
		return ""
	}
	sess := sessionFrom(r.URL.Query())
	ret := url.Values{"return": {"https://" + st.Host + "/complete"}}
	return "https://" + payHost + "/checkout?" + sess.query() + "&" + ret.Encode()
}

func (s *server) servePayment(w http.ResponseWriter, r *http.Request, st *site) {
	switch {
	case r.URL.Path == "/checkout" && r.Method == http.MethodGet:
		ret := r.URL.Query().Get("return")
		if !s.isOwnPortalURL(ret) {
			s.events.record(r, "https", "checkout_bad_return", map[string]string{"return": ret})
			http.Error(w, "invalid return address", http.StatusBadRequest)
			return
		}
		s.events.record(r, "https", "checkout_page", nil)
		sess := sessionFrom(r.URL.Query())
		render(w, http.StatusOK, paymentPage, map[string]any{
			"Venue": s.cfg.VenueName, "Host": st.Host, "Return": ret + "?" + sess.query(),
		})
	default:
		s.events.record(r, "https", "not_found", nil)
		http.NotFound(w, r)
	}
}

func (s *server) isOwnPortalURL(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" {
		return false
	}
	st, ok := s.sites[strings.ToLower(u.Hostname())]
	return ok && st.Kind == "portal"
}

// finishLogin sends the client to openNDS's auth endpoint with its token,
// which opens the firewall for it ("internet after login").
func (s *server) finishLogin(w http.ResponseWriter, r *http.Request, sess ndsSession, event string) {
	if sess.Tok == "" || sess.AuthAction == "" {
		s.events.record(r, "https", event+"_no_session", nil)
		render(w, http.StatusBadRequest, noSessionPage, map[string]any{"Venue": s.cfg.VenueName})
		return
	}
	allowed := false
	for _, p := range s.cfg.AuthPrefixes {
		allowed = allowed || strings.HasPrefix(sess.AuthAction, p)
	}
	if !allowed {
		s.events.record(r, "https", event+"_bad_authaction", map[string]string{"authaction": sess.AuthAction})
		http.Error(w, "unexpected gateway address", http.StatusBadRequest)
		return
	}
	sep := "?"
	if strings.Contains(sess.AuthAction, "?") {
		sep = "&"
	}
	target := sess.AuthAction + sep + url.Values{"tok": {sess.Tok}, "redir": {sess.Redir}}.Encode()
	s.events.record(r, "https", event, map[string]string{"client_ip": sess.ClientIP, "client_mac": sess.ClientMAC, "location": target})
	http.Redirect(w, r, target, http.StatusFound)
}

// eventLog writes one JSON object per request to <log_dir>/<role>-<date>.jsonl:
// the server-side ground truth for experiments.
type eventLog struct {
	mu       sync.Mutex
	dir      string
	role     string
	scenario string // active scenario name, added to every line when set
}

func openEventLog(dir, role string) (*eventLog, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return &eventLog{dir: dir, role: role}, nil
}

func (l *eventLog) record(r *http.Request, scheme, event string, extra map[string]string) {
	entry := map[string]any{
		"time": time.Now().Format(time.RFC3339Nano), "role": l.role, "event": event,
		"scheme": scheme, "host": hostOnly(r.Host), "method": r.Method, "path": r.URL.Path,
		"query": r.URL.RawQuery, "remote": r.RemoteAddr, "user_agent": r.UserAgent(),
	}
	if r.TLS != nil {
		entry["sni"] = r.TLS.ServerName
	}
	if l.scenario != "" {
		entry["scenario"] = l.scenario
	}
	for k, v := range extra {
		if v != "" {
			entry[k] = v
		}
	}
	line, _ := json.Marshal(entry)
	log.Printf("[%s] %s %s%s -> %s", l.role, r.Method, hostOnly(r.Host), r.URL.Path, event)
	l.write(line)
}

// write appends one JSON line to today's log file.
func (l *eventLog) write(line []byte) {
	l.mu.Lock()
	defer l.mu.Unlock()
	name := filepath.Join(l.dir, fmt.Sprintf("%s-%s.jsonl", l.role, time.Now().Format("2006-01-02")))
	f, err := os.OpenFile(name, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		log.Printf("event log: %v", err)
		return
	}
	defer f.Close()
	f.Write(append(line, '\n'))
}

// logEvent records an event that has no *http.Request behind it - the TLS
// relay's accept-time decisions (relay vs terminate), which happen before any
// HTTP layer exists.
func (s *server) logEvent(fields map[string]string) {
	entry := map[string]any{"time": time.Now().Format(time.RFC3339Nano), "role": s.cfg.Role}
	for k, v := range fields {
		if v != "" {
			entry[k] = v
		}
	}
	line, _ := json.Marshal(entry)
	log.Printf("[%s] %s sni=%s", s.cfg.Role, fields["event"], fields["sni"])
	s.events.write(line)
}
