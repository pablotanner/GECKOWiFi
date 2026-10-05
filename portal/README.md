# Portal server

The captive-portal pages for the lab. openNDS on the router blocks new clients
and redirects them here; after the user accepts the terms, the server sends
them back to openNDS, which lets them through.

It runs on the laptop, one process per role, each on its own address so both
can use port 443:

| Role | Address | Config |
|---|---|---|
| genuine (router A) | `192.168.137.10` | `config/genuine.json` |
| attacker (router B) | `192.168.137.20` | not yet |

Each domain has its own key. The SHA-256 hash of the public key is what goes
into the GeoCertificate, and the server prints it on startup.

```powershell
go build -o portal.exe .
.\portal.exe ca-init                         # once: lab CA
.\portal.exe keygen -host portal.gecko-a.lab # once per domain
.\run-genuine.ps1                            # start the genuine portal
```

`keygen` won't replace an existing key, because a new key would no longer match
the registered certificate. `sign -host <domain>` issues a new certificate for
the existing key, which keeps the hash the same. Certificates are valid for
about 13 months.

The certificates are signed by a lab CA (`keys/gecko-lab-ca.crt`). Install it
on test devices so that browsers and Android's sign-in screen accept the
portal. The GECKO app doesn't need it.

Every request is logged as a JSON line in `logs/<role>-<date>.jsonl`. Keys,
logs and the binary are not committed.
