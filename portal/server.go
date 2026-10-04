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
	cfg    *Config
	sites  map[string]*site // lower-case host -> site
	portal *site
	events *eventLog
}

func serveCmd(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	configPath := fs.String("config", "config/genuine.json", "role config file")
	fs.Parse(args)

	cfg, err := loadConfig(*configPath)
	if err != nil {
		return err
	}
	s := &server{cfg: cfg, sites: map[string]*site{}}
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

	httpSrv := &http.Server{
		Addr:              net.JoinHostPort(cfg.ListenIP, "80"),
		Handler:           http.HandlerFunc(s.serveHTTP),
		ReadHeaderTimeout: 10 * time.Second,
	}
	httpsSrv := &http.Server{
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
	go func() { errs <- httpsSrv.ListenAndServeTLS("", "") }()
	log.Printf("[%s] listening on %s:80 (redirect) and %s:443 (TLS)", cfg.Role, cfg.ListenIP, cfg.ListenIP)
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
	host := hostOnly(r.Host)
	if _, ok := s.sites[host]; !ok {
		host = s.cfg.PortalHost
	}
	target := "https://" + host + r.URL.RequestURI()
	s.events.record(r, "http", "redirect_https", map[string]string{"location": target})
	http.Redirect(w, r, target, http.StatusFound)
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
	switch {
	case r.URL.Path == "/" && r.Method == http.MethodGet:
		sess := sessionFrom(r.URL.Query())
		s.events.record(r, "https", "login_page", nil)
		payHost := ""
		for _, other := range s.cfg.Sites {
			if other.Kind == "payment" {
				payHost = other.Host
				break
			}
		}
		checkout := ""
		if payHost != "" {
			ret := url.Values{"return": {"https://" + st.Host + "/complete"}}
			checkout = "https://" + payHost + "/checkout?" + sess.query() + "&" + ret.Encode()
		}
		render(w, http.StatusOK, loginPage, map[string]any{
			"Venue": s.cfg.VenueName, "Host": st.Host, "Session": sess,
			"HasSession": sess.Tok != "", "Checkout": checkout,
		})
	case r.URL.Path == "/accept" && r.Method == http.MethodPost:
		r.ParseForm()
		s.finishLogin(w, r, sessionFrom(r.PostForm), "accept")
	case r.URL.Path == "/complete" && r.Method == http.MethodGet:
		s.finishLogin(w, r, sessionFrom(r.URL.Query()), "complete_after_payment")
	default:
		s.events.record(r, "https", "not_found", nil)
		http.NotFound(w, r)
	}
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
	mu   sync.Mutex
	dir  string
	role string
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
	for k, v := range extra {
		if v != "" {
			entry[k] = v
		}
	}
	line, _ := json.Marshal(entry)
	log.Printf("[%s] %s %s%s -> %s", l.role, r.Method, hostOnly(r.Host), r.URL.Path, event)

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
