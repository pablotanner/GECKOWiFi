// Realm-record signer and server for the eduroam onboarding demo
// (docs/eduroam-demo.md).
//
// DEMO SHORTCUT: a signed static endpoint stands in for a future realm
// keyspace in the GECKO map (with inclusion proofs). The client trusts a
// record because its signature verifies against a pinned key, not because of
// where it came from; the server never holds the signing key.
//
//	go run . sign  -key ../../lab-secrets/realm/realm-signing.key -in records/homeuni.test.json -out ../../lab-secrets/realm/signed
//	go run . serve -addr 192.168.137.1:1235 -dir ../../lab-secrets/realm/signed
//
// GET /v1/realm/<realm> returns a binary SignedRealmRecord
// (app/src/main/proto/realm_record.proto).
package main

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// recordSpec is the human-written input; ca_files are PEM paths relative to the spec file.
type recordSpec struct {
	Realm         string   `json:"realm"`
	EapMethod     string   `json:"eap_method"`
	Phase2        string   `json:"phase2"`
	OuterIdentity string   `json:"outer_identity"`
	CaFiles       []string `json:"ca_files"`
	ServerNames   []string `json:"server_names"`
	Rcoi          string   `json:"rcoi"`
	SupportURL    string   `json:"support_url"`
	ValidDays     int      `json:"valid_days"`
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: realm sign|serve [flags]")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "sign":
		fs := flag.NewFlagSet("sign", flag.ExitOnError)
		key := fs.String("key", "", "PKCS#8 PEM ECDSA P-256 signing key")
		in := fs.String("in", "", "record spec (JSON)")
		out := fs.String("out", "", "output directory; writes <realm>.bin")
		fs.Parse(os.Args[2:])
		if err := sign(*key, *in, *out); err != nil {
			log.Fatal(err)
		}
	case "serve":
		fs := flag.NewFlagSet("serve", flag.ExitOnError)
		addr := fs.String("addr", "192.168.137.1:1235", "listen address")
		dir := fs.String("dir", "", "directory with signed <realm>.bin files")
		fs.Parse(os.Args[2:])
		serve(*addr, *dir)
	default:
		log.Fatalf("unknown command %q", os.Args[1])
	}
}

func sign(keyPath, specPath, outDir string) error {
	raw, err := os.ReadFile(specPath)
	if err != nil {
		return err
	}
	var spec recordSpec
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&spec); err != nil {
		return fmt.Errorf("%s: %w", specPath, err)
	}
	if spec.Realm == "" || len(spec.CaFiles) == 0 || len(spec.ServerNames) == 0 || spec.ValidDays <= 0 {
		return fmt.Errorf("%s: realm, ca_files, server_names and valid_days are required", specPath)
	}

	var cas [][]byte
	for _, f := range spec.CaFiles {
		der, err := readPEM(filepath.Join(filepath.Dir(specPath), f), "CERTIFICATE")
		if err != nil {
			return err
		}
		if _, err := x509.ParseCertificate(der); err != nil {
			return fmt.Errorf("%s: %w", f, err)
		}
		cas = append(cas, der)
	}

	keyDER, err := readPEM(keyPath, "PRIVATE KEY")
	if err != nil {
		return err
	}
	parsed, err := x509.ParsePKCS8PrivateKey(keyDER)
	if err != nil {
		return err
	}
	priv, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return fmt.Errorf("%s: not an ECDSA key", keyPath)
	}

	now := time.Now().Unix()
	record := encodeRecord(spec, cas, now, now+int64(spec.ValidDays)*86400)
	digest := sha256.Sum256(record)
	sig, err := ecdsa.SignASN1(rand.Reader, priv, digest[:])
	if err != nil {
		return err
	}

	var signed []byte
	signed = appendBytes(signed, 1, record)
	signed = appendBytes(signed, 2, sig)

	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return err
	}
	out := filepath.Join(outDir, spec.Realm+".bin")
	if err := os.WriteFile(out, signed, 0o644); err != nil {
		return err
	}
	log.Printf("signed %s (%d bytes, valid %d days) -> %s", spec.Realm, len(signed), spec.ValidDays, out)
	return nil
}

func readPEM(path, wantType string) ([]byte, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil || block.Type != wantType {
		return nil, fmt.Errorf("%s: no %s PEM block", path, wantType)
	}
	return block.Bytes, nil
}

// encodeRecord writes a RealmRecord in protobuf wire format, field numbers as
// in realm_record.proto. Hand-encoded to keep this tool dependency-free; the
// signature covers these exact bytes, so the encoding needn't be canonical.
func encodeRecord(s recordSpec, cas [][]byte, issuedAt, expiresAt int64) []byte {
	var b []byte
	b = appendString(b, 1, s.Realm)
	b = appendString(b, 2, s.EapMethod)
	b = appendString(b, 3, s.Phase2)
	b = appendString(b, 4, s.OuterIdentity)
	for _, ca := range cas {
		b = appendBytes(b, 5, ca)
	}
	for _, n := range s.ServerNames {
		b = appendString(b, 6, n)
	}
	b = appendString(b, 7, s.Rcoi)
	b = appendString(b, 8, s.SupportURL)
	b = appendVarintField(b, 9, uint64(issuedAt))
	b = appendVarintField(b, 10, uint64(expiresAt))
	return b
}

func appendVarint(b []byte, v uint64) []byte {
	for v >= 0x80 {
		b = append(b, byte(v)|0x80)
		v >>= 7
	}
	return append(b, byte(v))
}

func appendVarintField(b []byte, field int, v uint64) []byte {
	b = appendVarint(b, uint64(field)<<3|0)
	return appendVarint(b, v)
}

func appendBytes(b []byte, field int, v []byte) []byte {
	b = appendVarint(b, uint64(field)<<3|2)
	b = appendVarint(b, uint64(len(v)))
	return append(b, v...)
}

func appendString(b []byte, field int, v string) []byte {
	if v == "" {
		return b // proto3: empty strings are omitted
	}
	return appendBytes(b, field, []byte(v))
}

var realmName = regexp.MustCompile(`^[a-z0-9.-]+$`)

func serve(addr, dir string) {
	http.HandleFunc("/v1/realm/", func(w http.ResponseWriter, r *http.Request) {
		realm := strings.ToLower(strings.TrimPrefix(r.URL.Path, "/v1/realm/"))
		if r.Method != http.MethodGet || !realmName.MatchString(realm) {
			http.NotFound(w, r)
			log.Printf("%s %s %s -> 404", r.RemoteAddr, r.Method, r.URL.Path)
			return
		}
		body, err := os.ReadFile(filepath.Join(dir, realm+".bin"))
		if err != nil {
			http.NotFound(w, r)
			log.Printf("%s GET %s -> 404 (no record)", r.RemoteAddr, realm)
			return
		}
		w.Header().Set("Content-Type", "application/x-protobuf")
		w.Write(body)
		log.Printf("%s GET %s -> 200 (%d bytes)", r.RemoteAddr, realm, len(body))
	})
	log.Printf("realm server on http://%s, records from %s", addr, dir)
	log.Fatal(http.ListenAndServe(addr, nil))
}
