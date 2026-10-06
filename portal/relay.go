package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"log"
	"net"
	"sync"
	"time"
)

// This file is the attacker role's :443 front door for the relay scenarios
// (S0, S3, S4a, S5a). Plain roles - the genuine portal, and the S1/S2 clone
// attacker that just presents its own key - don't relay and use the standard
// tls.Server path in server.go.
//
// An evil twin that lacks the genuine portal's private key cannot alter its
// TLS traffic; the most it can do is pass that connection through untouched so
// the client sees the genuine key. We model that by peeking the TLS SNI on
// each incoming connection and, for a configured set of hostnames, splicing
// the raw bytes to the genuine portal upstream. Every other hostname is
// terminated locally with the attacker's own key, exactly as before. The
// per-client timer (S5a) relays for a while and then starts terminating.

// serveTLSWithRelay listens on addr and routes each connection either to the
// genuine upstream (byte-for-byte) or to httpsSrv (terminated locally). It
// blocks, mirroring http.Server.ListenAndServeTLS, and is only used when a
// relay scenario is active.
func (s *server) serveTLSWithRelay(addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	// Locally terminated connections are handed to the existing HTTPS server
	// through a channel-backed listener; its handlers and SNI cert selection
	// are unchanged.
	local := newChanListener(ln.Addr())
	go func() {
		if err := s.httpsSrv.ServeTLS(local, "", ""); err != nil && !errors.Is(err, net.ErrClosed) {
			log.Printf("[%s] local TLS server: %v", s.cfg.Role, err)
		}
	}()
	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		go s.routeTLS(conn, local)
	}
}

// routeTLS peeks the ClientHello SNI and sends the connection down the relay
// or the local path. The timer (S5a) decides per client: relay while the
// client is still within relay_for_seconds of its first sighting, otherwise
// terminate locally.
func (s *server) routeTLS(conn net.Conn, local *chanListener) {
	sni, hello, err := peekClientHelloSNI(conn)
	if err != nil {
		conn.Close()
		return
	}
	// Re-prepend the bytes already read off the wire while peeking.
	buffered := &prefixConn{prefix: hello, Conn: conn}

	sc := s.cfg.Scenario
	relay := sc.relays(sni)
	if relay && sc.RelayForSeconds > 0 && s.relayExpired(conn.RemoteAddr()) {
		relay = false // S5a: the client's relay window has passed.
	}

	clientIP := hostOnly(conn.RemoteAddr().String())
	if relay {
		s.logEvent(map[string]string{
			"event": "tls_relay", "sni": sni, "client_ip": clientIP,
			"upstream": sc.relayTarget(), "scenario": sc.Name,
		})
		s.relayTo(sc.relayTarget(), buffered)
		return
	}
	s.logEvent(map[string]string{
		"event": "tls_terminate", "sni": sni, "client_ip": clientIP, "scenario": sc.Name,
	})
	local.push(buffered)
}

// relayExpired reports whether the client first seen at addr is now past its
// relay_for_seconds window. The first call for a client records "now" and
// returns false (still inside the window).
func (s *server) relayExpired(addr net.Addr) bool {
	ip := hostOnly(addr.String())
	window := time.Duration(s.cfg.Scenario.RelayForSeconds) * time.Second

	s.firstSeenMu.Lock()
	defer s.firstSeenMu.Unlock()
	first, ok := s.firstSeen[ip]
	if !ok {
		s.firstSeen[ip] = time.Now()
		return false
	}
	return time.Since(first) > window
}

// relayTo splices client <-> the genuine upstream, writing the already-read
// ClientHello first so the upstream sees an intact handshake.
func (s *server) relayTo(target string, client net.Conn) {
	defer client.Close()
	upstream, err := net.DialTimeout("tcp", target, 10*time.Second)
	if err != nil {
		log.Printf("[%s] relay dial %s: %v", s.cfg.Role, target, err)
		return
	}
	defer upstream.Close()

	done := make(chan struct{}, 2)
	go func() { io.Copy(upstream, client); done <- struct{}{} }()
	go func() { io.Copy(client, upstream); done <- struct{}{} }()
	<-done // one side closed; deferred Close() tears the other down.
}

// peekClientHelloSNI reads a TLS ClientHello off conn, returns its SNI and the
// exact bytes consumed (so they can be replayed). It drives a throwaway
// tls.Server whose only job is to capture ServerName in GetConfigForClient;
// the handshake is aborted right after the ClientHello, before any reply, so
// nothing is written back to the real client.
func peekClientHelloSNI(conn net.Conn) (string, []byte, error) {
	var buf bytes.Buffer
	var sni string
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	err := tls.Server(readOnlyConn{reader: io.TeeReader(conn, &buf)}, &tls.Config{
		GetConfigForClient: func(hi *tls.ClientHelloInfo) (*tls.Config, error) {
			sni = hi.ServerName
			return nil, errStopHandshake
		},
	}).HandshakeContext(context.Background())
	_ = conn.SetReadDeadline(time.Time{})
	// errStopHandshake is our own signal that the ClientHello was read in
	// full; any other error is a genuinely malformed handshake.
	if err != nil && !errors.Is(err, errStopHandshake) {
		return "", buf.Bytes(), err
	}
	return sni, buf.Bytes(), nil
}

var errStopHandshake = errors.New("clienthello captured")

// readOnlyConn feeds the peek handshake from the tee reader and blocks any
// write, so the throwaway tls.Server can parse the ClientHello but never sends
// a ServerHello to the client.
type readOnlyConn struct {
	reader io.Reader
}

func (c readOnlyConn) Read(p []byte) (int, error)  { return c.reader.Read(p) }
func (c readOnlyConn) Write(p []byte) (int, error)  { return 0, io.ErrClosedPipe }
func (readOnlyConn) Close() error                   { return nil }
func (readOnlyConn) LocalAddr() net.Addr            { return nil }
func (readOnlyConn) RemoteAddr() net.Addr           { return nil }
func (readOnlyConn) SetDeadline(time.Time) error      { return nil }
func (readOnlyConn) SetReadDeadline(time.Time) error  { return nil }
func (readOnlyConn) SetWriteDeadline(time.Time) error { return nil }

// prefixConn replays prefix (the peeked ClientHello) before reading more from
// the underlying connection, so the receiver sees the whole handshake.
type prefixConn struct {
	prefix []byte
	net.Conn
}

func (c *prefixConn) Read(p []byte) (int, error) {
	if len(c.prefix) > 0 {
		n := copy(p, c.prefix)
		c.prefix = c.prefix[n:]
		return n, nil
	}
	return c.Conn.Read(p)
}

// chanListener turns a channel of ready connections into a net.Listener, so
// the standard http.Server can serve the locally terminated connections that
// routeTLS hands it.
type chanListener struct {
	conns  chan net.Conn
	addr   net.Addr
	closed chan struct{}
	once   sync.Once
}

func newChanListener(addr net.Addr) *chanListener {
	return &chanListener{conns: make(chan net.Conn), addr: addr, closed: make(chan struct{})}
}

func (l *chanListener) push(c net.Conn) {
	select {
	case l.conns <- c:
	case <-l.closed:
		c.Close()
	}
}

func (l *chanListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.conns:
		return c, nil
	case <-l.closed:
		return nil, net.ErrClosed
	}
}

func (l *chanListener) Close() error {
	l.once.Do(func() { close(l.closed) })
	return nil
}

func (l *chanListener) Addr() net.Addr { return l.addr }
