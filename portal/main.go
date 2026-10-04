// Command portal is the lab's captive-portal server: the pages that openNDS
// on the routers redirects new WiFi clients to (openNDS "FAS", Forward
// Authentication Service). One process serves one role on one IP of the
// laptop's lab adapter - "genuine" (router A's operator) on 192.168.137.10,
// later "attacker" (router B) on 192.168.137.20 - so both can use port 443.
//
// What GECKO verifies is each portal domain's TLS key, so every domain gets
// its own key pair (keygen) whose SPKI hash goes into the GeoCertificate.
//
// Usage:
//
//	portal serve  -config config/genuine.json
//	portal keygen -host portal.gecko-a.lab [-out keys] [-force]
//	portal spki   -cert keys/portal.gecko-a.lab.crt
//	portal ca-init                           (once: lab CA to install on test devices)
//	portal sign   -host portal.gecko-a.lab   (new CA-signed cert for the same key = same pin)
package main

import (
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "serve":
		err = serveCmd(os.Args[2:])
	case "keygen":
		err = keygenCmd(os.Args[2:])
	case "spki":
		err = spkiCmd(os.Args[2:])
	case "ca-init":
		err = caInitCmd(os.Args[2:])
	case "sign":
		err = signCmd(os.Args[2:])
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `usage:
  portal serve  -config config/genuine.json
  portal keygen -host <domain> [-out keys] [-force]
  portal spki   -cert <file.crt>
  portal ca-init [-out keys]
  portal sign   -host <domain> [-out keys]`)
	os.Exit(2)
}
