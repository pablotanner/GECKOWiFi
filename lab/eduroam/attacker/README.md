# Attacker toolkit (Router B data-plane) — lab only

Gives the attacker eduroam victim **real connectivity through Router B** and the
tooling to sniff, intercept, and phish them. Data-plane counterpart to the
credential harvest in `lab/eduroam/radius/` (attacker-b3 keep-online / GTC capture).

Every script runs from the laptop and drives **Router B only** over SSH
(`mango-b` host alias, ProxyJump via `mango-a`). Router A (genuine) is never
touched, so the two paths stay independent. All actions are reversible; a
pre-change snapshot is in `lab-secrets/router-backups/`.

> **Always check what's live first:** `lab/eduroam/attacker/status.sh`

## Which "attacks" exist, and the one switch for each

| Tier | Attack / setting | Script | ON means |
|------|------------------|--------|----------|
| 0 | **Connectivity** (edu bridge vs. withholding) | `edu-path.sh on\|off` | victim gets real internet via `br-edu`; `off` = captive-portal/withholding on `lan` |
| 0 | **DNS isolation** from Router A | (part of `edu-path.sh on`) | B resolves via public DNS, A out of the path |
| 1 | **Sniffing** (DNS log + pcap) | `sniff.sh dns\|pcap\|http` | live domains / full pcap to `evidence/` |
| 2 | **Plain-HTTP interception** | `http-intercept.sh on\|off` | edu `:80` → attacker page (HTTPS untouched) |
| 2.5 | **Portal credential capture** | `portal.sh on\|off\|creds` | fake `uni-wipkingen` sign-in; typed creds logged |
| — | RADIUS keep-online + GTC plaintext | `../radius/` (laptop side) | see `../radius/extract-plain.sh attacker-b3` |

`status.sh` prints all of the above (ON/off), the associated victims, and capture
counts in one view.

## Two worlds on Router B

| SSID | Bridge | Demonstrates |
|------|--------|--------------|
| `GeckoTest` (open) | `br-lan` + openNDS | captive-portal / withholding (never changed by this toolkit) |
| `eduroam-lab` (attacker, `eduroam_b3`) | `br-edu` → NAT out WAN | **connectivity + sniff/intercept/phish surface** |

`edu-path.sh` only moves the attacker eduroam SSID; the `GeckoTest` openNDS demo is
untouched. See decision **D29** in `docs/eduroam-decisions.md`.

## Typical run

```bash
lab/eduroam/attacker/status.sh                 # what's live right now
lab/eduroam/attacker/edu-path.sh on            # give the victim internet via B
#   -> on the phone: forget + rejoin eduroam-lab (to land on the edu bridge)

lab/eduroam/attacker/sniff.sh dns              # watch domains the victim visits
lab/eduroam/attacker/http-intercept.sh on      # force plain-HTTP to the attacker page
lab/eduroam/attacker/portal.sh on              # upgrade that page to a credential-capturing sign-in
lab/eduroam/attacker/portal.sh creds           # dump captured creds -> evidence/

# tear down
lab/eduroam/attacker/portal.sh off
lab/eduroam/attacker/http-intercept.sh off
lab/eduroam/attacker/edu-path.sh off           # back to withholding
```

## Why this doesn't contaminate the genuine path

- Attacker SSID on its own bridge/zone (`edu`), NAT'd straight out B's WAN.
- B runs its own DNS, so Router A is out of the resolver path.
- Only Router B is reconfigured.

Residual caveat: B's *upstream* is still daisy-chained A → laptop → home line
(`docs/lab-setup.md`), so the victim's egress IP is your home line's. That doesn't
change B's power over the victim (gateway/DNS/DHCP are B's); it only matters if you
run A and B online simultaneously and need to tell the egress paths apart. In the
evil-twin scenario (genuine jammed, victim on attacker) only B is live, so it's a
non-issue. A phone tether on B's WAN is the fix if you ever need simultaneous A+B.

## Scope / ethics

Lab only, against your own test device. `uni-wipkingen` is a **fictional**
institution — nothing here impersonates a real organisation. **HTTPS is
intentionally not intercepted**: transparent HTTPS needs a MITM CA the victim
trusts, which is exactly the trust GECKO denies (a separate Tier 3 step). Don't
point interception at traffic/accounts you don't own — B's upstream is your real
home line.

## Dashboard (monitor + control)

The lab dashboard (`lab/dashboard/`) shows and drives all of the above under
**Router B**, so you don't need the CLI during a demo:

- **Monitor:** four cards — *victim connectivity*, *DNS sniffing*, *plain-HTTP
  intercept*, *credential-capture portal* — each amber when the attack is live,
  grey when off, with victim count and captured-credential count.
- **Control:** buttons for every toggle (give internet / withhold, intercept HTTP
  on/off, deploy/remove portal), plus **Show captured creds** (pulls to
  `evidence/`) and **Clear captures**. Destructive/attack actions ask to confirm.

The dashboard calls these same scripts, so dashboard and CLI never drift. The
RADIUS keep-online/GTC harvest stays under the WSL cards (it's laptop-side).

## Prereqs on B (already installed)

`tcpdump-mini` (sniff pcap), `uhttpd` + CGI (intercept/portal), `nft`/fw4,
`dnsmasq`. Captures: pcaps and creds land in the git-ignored `evidence/` tree.

## Revert everything

`portal.sh off` + `http-intercept.sh off` + `edu-path.sh off`, or restore
`lab-secrets/router-backups/mango-b-*.uci`.
