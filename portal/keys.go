package main

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

// spkiHash is what GeoCertificates pin: base64(SHA-256(SubjectPublicKeyInfo DER)).
// Identical to the app's CertProbe.spkiHash and to
// `openssl x509 -pubkey | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64`.
func spkiHash(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return base64.StdEncoding.EncodeToString(sum[:])
}

func keyPaths(outDir, host string) (certPath, keyPath string) {
	return filepath.Join(outDir, host+".crt"), filepath.Join(outDir, host+".key")
}

// The lab CA lets browsers (and possibly Android's sign-in screen) accept the
// portal without warnings once its certificate is installed on the device.
// GECKO itself doesn't rely on it: the app compares pinned SPKI hashes, and
// re-issuing a certificate for the same key (sign) keeps the pin unchanged.
const caName = "gecko-lab-ca"

type labCA struct {
	cert *x509.Certificate
	key  crypto.Signer
}

func serialNumber() (*big.Int, error) {
	return rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
}

func writePEM(path, blockType string, der []byte, mode os.FileMode) error {
	return os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: blockType, Bytes: der}), mode)
}

func readCert(path string) (*x509.Certificate, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, fmt.Errorf("%s: no PEM certificate", path)
	}
	return x509.ParseCertificate(block.Bytes)
}

func readKey(path string) (crypto.Signer, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil || block.Type != "PRIVATE KEY" {
		return nil, fmt.Errorf("%s: no PEM private key", path)
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	signer, ok := key.(crypto.Signer)
	if !ok {
		return nil, fmt.Errorf("%s: unsupported key type", path)
	}
	return signer, nil
}

// loadCA returns the lab CA from dir, or nil if it hasn't been created yet.
func loadCA(dir string) (*labCA, error) {
	certPath, keyPath := keyPaths(dir, caName)
	if _, err := os.Stat(certPath); errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	cert, err := readCert(certPath)
	if err != nil {
		return nil, err
	}
	key, err := readKey(keyPath)
	if err != nil {
		return nil, err
	}
	return &labCA{cert: cert, key: key}, nil
}

// issueCert writes host's certificate for key: signed by ca, or self-signed if
// ca is nil. Validity stays under 398 days (browser limit for server certs).
func issueCert(host, outDir string, key crypto.Signer, ca *labCA) (*x509.Certificate, error) {
	serial, err := serialNumber()
	if err != nil {
		return nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: host, Organization: []string{"GECKO lab"}},
		DNSNames:     []string{host},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(0, 0, 397),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	parent, signer := tmpl, key
	if ca != nil {
		parent, signer = ca.cert, ca.key
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, parent, key.Public(), signer)
	if err != nil {
		return nil, err
	}
	certPath, _ := keyPaths(outDir, host)
	if err := writePEM(certPath, "CERTIFICATE", der, 0o644); err != nil {
		return nil, err
	}
	return x509.ParseCertificate(der)
}

// generateKeyPair writes a fresh ECDSA P-256 key for host plus its certificate
// (signed by the lab CA if it exists, else self-signed).
func generateKeyPair(host, outDir string) (*x509.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(outDir, 0o700); err != nil {
		return nil, err
	}
	_, keyPath := keyPaths(outDir, host)
	if err := writePEM(keyPath, "PRIVATE KEY", keyDER, 0o600); err != nil {
		return nil, err
	}
	ca, err := loadCA(outDir)
	if err != nil {
		return nil, err
	}
	return issueCert(host, outDir, key, ca)
}

func keygenCmd(args []string) error {
	fs := flag.NewFlagSet("keygen", flag.ExitOnError)
	host := fs.String("host", "", "domain to create a key pair for")
	out := fs.String("out", "keys", "output directory")
	force := fs.Bool("force", false, "overwrite an existing key pair (changes the pin!)")
	fs.Parse(args)
	if *host == "" {
		return errors.New("-host is required")
	}
	certPath, _ := keyPaths(*out, *host)
	if _, err := os.Stat(certPath); err == nil && !*force {
		// Keeping keys stable matters: a new key invalidates the registered pin.
		cert, err := readCert(certPath)
		if err != nil {
			return err
		}
		fmt.Printf("%s already exists (use -force to replace)\n%s  %s\n", certPath, *host, spkiHash(cert))
		return nil
	}
	cert, err := generateKeyPair(*host, *out)
	if err != nil {
		return err
	}
	fmt.Printf("%s  %s\n", *host, spkiHash(cert))
	return nil
}

func caInitCmd(args []string) error {
	fs := flag.NewFlagSet("ca-init", flag.ExitOnError)
	out := fs.String("out", "keys", "output directory")
	fs.Parse(args)
	existing, err := loadCA(*out)
	if err != nil {
		return err
	}
	if existing != nil {
		return fmt.Errorf("lab CA already exists in %s", *out)
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, err := serialNumber()
	if err != nil {
		return err
	}
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "GECKO lab CA", Organization: []string{"GECKO lab"}},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(*out, 0o700); err != nil {
		return err
	}
	certPath, keyPath := keyPaths(*out, caName)
	if err := writePEM(keyPath, "PRIVATE KEY", keyDER, 0o600); err != nil {
		return err
	}
	if err := writePEM(certPath, "CERTIFICATE", der, 0o644); err != nil {
		return err
	}
	fmt.Printf("lab CA written to %s - install it on test devices as a CA certificate\n", certPath)
	return nil
}

// signCmd re-issues host's certificate for its EXISTING key with the lab CA,
// so the SPKI pin in the GeoCertificate stays valid.
func signCmd(args []string) error {
	fs := flag.NewFlagSet("sign", flag.ExitOnError)
	host := fs.String("host", "", "domain whose existing key gets a new certificate")
	out := fs.String("out", "keys", "key directory")
	fs.Parse(args)
	if *host == "" {
		return errors.New("-host is required")
	}
	ca, err := loadCA(*out)
	if err != nil {
		return err
	}
	if ca == nil {
		return errors.New("no lab CA yet - run: portal ca-init")
	}
	_, keyPath := keyPaths(*out, *host)
	key, err := readKey(keyPath)
	if err != nil {
		return err
	}
	cert, err := issueCert(*host, *out, key, ca)
	if err != nil {
		return err
	}
	fmt.Printf("%s  %s  (signed by lab CA, valid until %s)\n", *host, spkiHash(cert), cert.NotAfter.Format("2006-01-02"))
	return nil
}

func spkiCmd(args []string) error {
	fs := flag.NewFlagSet("spki", flag.ExitOnError)
	path := fs.String("cert", "", "PEM certificate file")
	fs.Parse(args)
	if *path == "" {
		return errors.New("-cert is required")
	}
	cert, err := readCert(*path)
	if err != nil {
		return err
	}
	fmt.Println(spkiHash(cert))
	return nil
}
