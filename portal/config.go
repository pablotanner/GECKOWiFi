package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// Config describes one portal role (one process, one listen IP).
type Config struct {
	// "genuine" or "attacker"; recorded in every log line.
	Role string `json:"role"`
	// Laptop address on the lab segment this role listens on (ports 80 and 443).
	ListenIP string `json:"listen_ip"`
	// Login page host: where plain-HTTP and unknown-host requests are sent.
	PortalHost string `json:"portal_host"`
	// openNDS auth endpoints the portal may send clients to after login
	// (prefix match), so the portal can't be abused as an open redirect.
	AuthPrefixes []string `json:"auth_prefixes"`
	LogDir       string   `json:"log_dir"`
	// Display name on the pages.
	VenueName string `json:"venue_name"`
	Sites     []Site `json:"sites"`

	// Optional experiment behaviour (see docs/experiments.md). Absent = a
	// plain portal that serves its own sites over TLS, which is the genuine
	// router A role and the simplest attacker (S1/S2: own key, own domain).
	Scenario *Scenario `json:"scenario,omitempty"`
}

// Site is one HTTPS domain with its own key pair (= its own GeoCert pin).
type Site struct {
	Host string `json:"host"`
	// "portal" (login page, GeoCert role primary) or "payment" (delegate).
	Kind string `json:"kind"`
	Cert string `json:"cert"`
	Key  string `json:"key"`
}

// Scenario tweaks one role's behaviour to stand in for a specific attack (B)
// or for a genuine portal whose own flow a relay attacker can exploit (A).
// Every field is optional; the zero value changes nothing. The active
// scenario name is written into every log line as the ground truth for a run.
type Scenario struct {
	// Recorded in every log line; free-form (e.g. "S3-redirect").
	Name string `json:"name"`

	// --- TLS relay (attacker) ---------------------------------------------
	// SNI hostnames whose TLS connection is passed through byte-for-byte to
	// Upstream instead of being terminated here, so the client sees the
	// genuine key. Everything else is terminated locally with this role's
	// own key. Empty = terminate everything (the S1/S2 clone attacker).
	RelayHosts []string `json:"relay_hosts"`
	// Where RelayHosts are forwarded, "ip" or "ip:port" (default :443).
	Upstream string `json:"upstream"`
	// S5a: relay each client for this many seconds (measured from its first
	// connection), then start terminating locally with the attacker's key.
	// 0 = no timer.
	RelayForSeconds int `json:"relay_for_seconds"`

	// --- the switch away from the genuine flow (attacker) -----------------
	// How /continue bounces the client to SwitchTo once the genuine primary
	// has been shown: "" (off), "redirect" (302), "meta_refresh",
	// "refresh_header" or "js". The genuine side reaches /continue only when
	// its own portal is configured with HTTPBounce (see below).
	Switch string `json:"switch"`
	// Absolute URL the switch sends the client to (an attacker host).
	SwitchTo string `json:"switch_to"`
	// Delay in seconds for "meta_refresh" / "refresh_header" (0 = immediate).
	SwitchDelaySeconds int `json:"switch_delay_seconds"`
	// S5c: send the client to SwitchTo after it POSTs /accept, instead of
	// completing the login.
	SwitchAfterAccept bool `json:"switch_after_accept"`

	// S5b: requests whose User-Agent contains this substring are treated as
	// the detection probe and sent down the genuine (relayed) path; everyone
	// else is sent to the attacker. Empty = no User-Agent branching.
	ProbeUserAgent string `json:"probe_user_agent"`

	// --- plain HTTP (attacker) --------------------------------------------
	// S6: serve the login page on port 80 without upgrading to HTTPS.
	HTTPOnly bool `json:"http_only"`

	// --- genuine-side options (A) -----------------------------------------
	// The genuine login page sends the client to the payment delegate with a
	// 302 instead of a button, so the detection probe (which follows only
	// redirects) reaches it. Lets S4a be driven end to end.
	PaymentViaRedirect bool `json:"payment_via_redirect"`
	// The genuine flow dips back to plain HTTP at /continue (http://host/continue
	// -> https://host/). A relay attacker in front of it can rewrite that one
	// unencrypted hop, which is what makes S3 reachable without the genuine key.
	HTTPBounce bool `json:"http_bounce"`
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	dec := json.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	if cfg.Role == "" || cfg.ListenIP == "" || cfg.PortalHost == "" || len(cfg.Sites) == 0 {
		return nil, fmt.Errorf("%s: role, listen_ip, portal_host and sites are required", path)
	}
	if cfg.LogDir == "" {
		cfg.LogDir = "logs"
	}
	if cfg.VenueName == "" {
		cfg.VenueName = "GeckoTest WiFi"
	}
	hasPortal := false
	for _, s := range cfg.Sites {
		switch s.Kind {
		case "portal":
			hasPortal = hasPortal || strings.EqualFold(s.Host, cfg.PortalHost)
		case "payment":
		default:
			return nil, fmt.Errorf("site %s: unknown kind %q", s.Host, s.Kind)
		}
	}
	if !hasPortal {
		return nil, fmt.Errorf("portal_host %s must be one of the sites with kind \"portal\"", cfg.PortalHost)
	}
	if err := cfg.Scenario.validate(); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return &cfg, nil
}

var switchKinds = map[string]bool{
	"": true, "redirect": true, "meta_refresh": true, "refresh_header": true, "js": true,
}

// validate checks the combinations that don't make sense together, so a
// mistyped scenario file fails at startup rather than behaving oddly mid-run.
// A nil scenario (the common case) is always valid.
func (sc *Scenario) validate() error {
	if sc == nil {
		return nil
	}
	if sc.Name == "" {
		return fmt.Errorf("scenario.name is required")
	}
	if !switchKinds[sc.Switch] {
		return fmt.Errorf("scenario.switch %q is not one of redirect, meta_refresh, refresh_header, js", sc.Switch)
	}
	if sc.Switch != "" && sc.SwitchTo == "" {
		return fmt.Errorf("scenario.switch %q needs switch_to", sc.Switch)
	}
	if sc.SwitchAfterAccept && sc.SwitchTo == "" {
		return fmt.Errorf("scenario.switch_after_accept needs switch_to")
	}
	if len(sc.RelayHosts) > 0 && sc.Upstream == "" {
		return fmt.Errorf("scenario.relay_hosts needs upstream")
	}
	if sc.RelayForSeconds > 0 && sc.Upstream == "" {
		return fmt.Errorf("scenario.relay_for_seconds needs upstream (the host to relay to while under the timer)")
	}
	if sc.ProbeUserAgent != "" && sc.SwitchTo == "" {
		return fmt.Errorf("scenario.probe_user_agent needs switch_to (where non-probe clients go)")
	}
	return nil
}

// relayTarget returns the "ip:port" RelayHosts are forwarded to (:443 default).
func (sc *Scenario) relayTarget() string {
	if strings.Contains(sc.Upstream, ":") {
		return sc.Upstream
	}
	return sc.Upstream + ":443"
}

// relays reports whether host's TLS should be passed through to Upstream.
func (sc *Scenario) relays(host string) bool {
	if sc == nil {
		return false
	}
	for _, h := range sc.RelayHosts {
		if strings.EqualFold(h, host) {
			return true
		}
	}
	return false
}
