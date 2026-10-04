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
}

// Site is one HTTPS domain with its own key pair (= its own GeoCert pin).
type Site struct {
	Host string `json:"host"`
	// "portal" (login page, GeoCert role primary) or "payment" (delegate).
	Kind string `json:"kind"`
	Cert string `json:"cert"`
	Key  string `json:"key"`
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
	return &cfg, nil
}
