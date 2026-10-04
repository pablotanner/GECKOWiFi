# Lab captive portal

The pages openNDS on the routers sends new WiFi clients to (openNDS "FAS",
Forward Authentication Service). Runs natively on the Windows laptop; one
process per role, each on its own lab address so both can use port 443:

| Role | Address | Config |
|---|---|---|
| genuine (router A) | `192.168.137.10` | `config/genuine.json` |
| attacker (router B, later) | `192.168.137.20` | — |

Each domain has its own key pair; the pins in the GeoCertificate are the SPKI
hashes printed by `keygen` / at startup.

```powershell
go build -o portal.exe .
.\portal.exe keygen -host portal.gecko-a.lab     # once per domain; won't overwrite without -force
.\portal.exe keygen -host pay.gecko-pay.lab
.\run-genuine.ps1                                  # serve; prints each domain's SPKI hash
.\portal.exe spki -cert keys\portal.gecko-a.lab.crt
```

**Lab CA** (`keys/gecko-lab-ca.crt`): the domain certificates are signed by a
lab CA so browsers accept them once the CA is installed on the device
(Android: Settings → Security → Install certificate → CA certificate; needs a
screen lock). GECKO doesn't depend on it — the app only compares pinned SPKI
hashes. `portal.exe sign -host <domain>` re-issues a certificate for the
**existing** key (same pin); certificates are valid 397 days (until 2027-11-05),
renew them with `sign`. Restart the server after signing.

```powershell
.\portal.exe ca-init                          # once
.\portal.exe sign -host portal.gecko-a.lab    # re-issue, pin unchanged
```

Flow (genuine): client opens any http page → openNDS on A redirects to
`http://portal.gecko-a.lab/` (FAS, with `tok`/`authaction`/`redir`) → 302 to
`https://portal.gecko-a.lab/` (primary) → "Accept and connect", or "Premium
access" via `https://pay.gecko-pay.lab/checkout` (delegate) and back to
`https://portal.gecko-a.lab/complete` → 302 to openNDS `…/opennds_auth/?tok=…`
→ client has internet.

Every request is logged as one JSON line in `logs/<role>-<date>.jsonl`
(ground truth for experiments). `keys/`, `logs/` and `portal.exe` are not committed.
