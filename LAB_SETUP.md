# Lab Setup & Device Integration 


## Current Goal

Get a first end-to-end `VERIFIED` verdict on physical hardware: tablet scans → joins
Router A (benign AP) via the app → obtains location → encodes query → calls
`POST /v1/query` on the GeoPKI server → Merkle proof verifies. Then extend toward the
attacker AP (Router B) and portals.

## Hardware

| Device                          | Role                                                       |
|---------------------------------|------------------------------------------------------------|
| Laptop (Windows 10)             | ICS gateway + GeoPKI server host (in WSL2) + build machine |
| GL.iNet Mango A (MT300N-V2-23a) | Benign AP                                                  |
| GL.iNet Mango B (MT300N-V2-247) | Attacker AP (evil twin)                                    |
| Anker USB-C hub                 | Powers the routers and laptop                              |
| Samsung Galaxy Tab (WiFi-only)  | Android test device                                        |

Tablet: adb serial `R8YL41D4GEH`, GNSS present (`location.gps` confirmed).
Model: TODO, fill in `adb shell getprop ro.product.model`

## Network topology

```
ETH WiFi ──(laptop WiFi)── Laptop ──ICS──> USB-C Ethernet (192.168.137.1)
                             │                    │
                          WSL2 (NAT)              └── cable ──> A WAN (192.168.137.50)
                          geopki-server                          A LAN (192.168.8.1)
                          0.0.0.0:1234                             │
                                                                   └── cable ──> B WAN (192.168.8.115)
                                                                                 B LAN (192.168.247.1)
```

- Laptop shares its ETH-WiFi internet via Windows ICS out the USB-C Ethernet adapter.
  ICS owns `192.168.137.1` and runs DHCP/DNS on that segment.
- A's WAN is **static** `192.168.137.50` (ICS can't reserve; static avoids drift).
- B is daisy-chained off A's LAN; B's WAN `192.168.8.115` is **DHCP-reserved on A**.
- Powering A off takes B offline (single uplink). See [Testbed limitations](#testbed-limitations).
- The tablet is WiFi-only: its only route to the GeoPKI server is through the AP it is
  currently testing.

## IP / port reference

| Thing | Address |
|---|---|
| ICS gateway (laptop USB-C Eth) | `192.168.137.1` |
| **GeoPKI server (from tablet/routers)** | `http://192.168.137.1:1234` |
| GeoPKI server (from Windows host) | `http://192.168.137.1:1234` (via portproxy) or `localhost:1234` |
| GeoPKI server (inside WSL) | `0.0.0.0:1234`, process `geopki-server` |
| Trillian (inside WSL, Docker) | log server `127.0.0.1:8090`, signer `:8092` |
| Router A WAN / LAN / admin | `192.168.137.50` / `192.168.8.1` |
| Router B WAN / LAN / admin | `192.168.8.115` / `192.168.247.1` |
| Tablet on A (DHCP, observed) | `192.168.8.151` |
| A dashboard via SSH tunnel | `http://localhost:8081` |
| B dashboard via SSH tunnel | `http://localhost:8082` |

Reachability is confirmed: `curl` to `192.168.137.1:1234` returns HTTP 404 (server up,
route works) from Windows, Router A, and Router B shells.

## WiFi

- Both routers broadcast the **same open SSID `GeckoTest`** (no auth) — this is Case A
  (co-located, no crypto identity).
- A on channel 1, B on channel 11 (so they're distinguishable in scans).
- Two BSSIDs share the SSID. The app joins a specific AP via
  `WifiNetworkSpecifier.Builder().setBssid(...)`; pick it under "Access points" on the
  network's detail screen.

| AP | BSSID | Channel |
|---|---|---|
| A | `94:83:C4:97:C2:3A` | 1 (2412 MHz) |
| B | `94:83:C4:97:C2:47` | 11 (2462 MHz) |

## AP identity (portal stand-in)

There is no captive portal yet, so each router's built-in HTTPS admin server (port 443)
stands in for the portal's TLS endpoint. Its self-signed certificate is fine: the trust
anchor is the SPKI pinned in the GeoCert, not WebPKI (`CertProbe` deliberately skips CA and
hostname checks).

| Router | Certificate | SPKI SHA-256 (base64) |
|---|---|---|
| A | `CN=console.gl-inet.com, O=GLiNet` | `QwsHK0yXsUHme1g9/rqOHixTb8cCuCMBxYdaglLs1Q8=` |
| B | `CN=console.gl-inet.com, O=GLiNet` | `gXJrtQhtAVVHv4hdWmBj98jbdXVwFLRm6QiPxXCsvrI=` |

- **Keys are unique per device** despite the identical generic certificate name (checked
  2026-10-02). A factory reset or reflash regenerates the key → re-pin.
- **DNS on A:** `gecko-a.lab → 192.168.8.1`
  ```
  uci add_list dhcp.@dnsmasq[0].address='/gecko-a.lab/192.168.8.1'
  uci commit dhcp; /etc/init.d/dnsmasq restart
  ```
- **DNS on B** decides B's behaviour:
  - *Evil-twin mode (current):* `gecko-a.lab → 192.168.247.1` — B presents its own key →
    `CONFLICT`.
    ```
    uci add_list dhcp.@dnsmasq[0].address='/gecko-a.lab/192.168.247.1'
    uci commit dhcp; /etc/init.d/dnsmasq restart
    ```
  - *Relay mode:* no entry — B forwards DNS to A, the tablet reaches **A's real server
    through B**, and gets `VERIFIED` (see [Testbed limitations](#testbed-limitations)).
    Remove with `uci del_list dhcp.@dnsmasq[0].address='/gecko-a.lab/192.168.247.1'`.
- **Reading a router's SPKI hash** (must match what `CertProbe` computes):
  ```powershell
  ssh -N -L 8443:127.0.0.1:443 mango-a        # B: mango-b, use 8444
  ```
  ```bash
  # Git Bash, second terminal
  openssl s_client -connect 127.0.0.1:8443 </dev/null 2>/dev/null \
    | openssl x509 -pubkey -noout \
    | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64
  ```

## Registered GeoCerts

Certificates use **schema v2** (since 2026-10-04): per-domain roles and pins, validated
by the server on insert (`GeoCertificate.Validate()` in `geopki/pkg/crypto/certificate.go`).

A's GeoCert `mango-a` (produces `VERIFIED` on A, `CONFLICT` on B in evil-twin mode):

```json
{
  "schema_version": 2,
  "certificate_id": "mango-a",
  "wifi": { "auth_mode": "open", "ssid": "GeckoTest" },
  "portal": { "domains": [
    { "name": "gecko-a.lab", "role": "primary",
      "pinned_spki_sha256": ["QwsHK0yXsUHme1g9/rqOHixTb8cCuCMBxYdaglLs1Q8="] }
  ] },
  "areas": [ ... ],
  "areas_altitude": [[200, 700]],
  "not_valid_after": "2031-09-26T15:28:00Z"
}
```

Insert (in WSL; the file is a JSON **array** of certificates):

```bash
cd ~/Thesis/geopki
go run ./cmd/geopki-client-ingestion --address=http://127.0.0.1:1234 \
  --insertion-key=$CERT_INSERT_KEY --certificates=/tmp/mango-a.json
```

Write the file with a heredoc or copy it in; pasting long JSON into the terminal has broken
lines inside strings before (`invalid character '\n' in string literal`).

**Schema v2 rules** (server validation + app behaviour):

- `schema_version: 2` required; `wifi.auth_mode` must be a valid mode; enterprise modes need
  `auth_server_names`; one altitude range per area; `not_valid_after` in RFC 3339.
- `portal` is optional (omitted for non-portal networks). If present: at least one
  `primary` domain, and every domain has its own non-empty `pinned_spki_sha256`. Roles:
  `primary` (entry point, the only role that starts a session), `delegate` (only valid
  after its primary, e.g. a payment page), `api` (RFC 8908 Captive Portal API host).
- Pins are the trust anchor; the app does no Web PKI validation. A domain without pins
  never verifies.
- The app ignores certificates past `not_valid_after`.
- A delegate seen before its primary: genuine key → `UNRECOGNIZED` (no anchor is set);
  any other key → `CONFLICT`.

Area covers general ETH (central Zürich) area, so I can work from different ETH locations or maybe even home
**Legacy certificates:** the golden-vector certificates (`eth-a`, `eth-b`, `hb`,
`stack-low`, `stack-high`, `multi`) predate the `wifi` section and are served with
`"auth_mode": ""`. The app parses them with an unknown auth mode (they have no SSID, so the
SSID filter ignores them). Pre-v2 certificates with string portal domains (e.g. the old
`mango-new`) are dropped as unparsable; the log line names the certificate ID. The golden
tests use frozen snapshots and don't depend on what's on the server, but **regenerating**
`golden.json` needs the six golden certificates present, so keep their insert payloads
backed up.

## Captive portal

Router A runs **openNDS** (the gate) and sends new clients to the **portal server** on the
laptop (`portal/` in this repo, see `portal/README.md`).

```
client → any http page → openNDS on A (192.168.8.1:2050)
       → http://portal.gecko-a.lab/?tok=…&authaction=…&redir=…      (FAS, laptop 192.168.137.10:80)
       → https://portal.gecko-a.lab/                                 (primary, login page)
       → [optional] https://pay.gecko-pay.lab/checkout → https://portal.gecko-a.lab/complete   (delegate)
       → http://192.168.8.1:2050/opennds_auth/?tok=…                 (openNDS opens the firewall)
```

**Portal server** (Windows, not WSL): `portal
un-genuine.ps1` — must be running for logins.
Laptop addresses on `Ethernet 4`: `192.168.137.10` (genuine), `192.168.137.20` (attacker,
reserved), both persistent, `SkipAsSource`; firewall rule `GECKO portal 80/443` allows them
from `192.168.137.0/24` only. Requests are logged to `portal/logs/<role>-<date>.jsonl`.

| Domain | Role | IP | SPKI SHA-256 |
|---|---|---|---|
| `portal.gecko-a.lab` | primary | `192.168.137.10` | `dAJnHfG0hpOWSRhQOeWz1NBoiXHnUnB2y/40h9i2exk=` |
| `pay.gecko-pay.lab` | delegate | `192.168.137.10` | `u9H7ovOnPGAi3ptBn2262bEEeXNcndvZCmnr8gmG3+c=` |

Keys are in `portal/keys/` (not committed; `portal.exe keygen` won't overwrite them —
a new key invalidates the registered pin). The server picks the key by SNI.

**openNDS on A** (`/etc/config/opennds`, backup of the original in
`/root/opennds.config.backup`):

- FAS: `fasremoteip 192.168.137.10`, `fasremotefqdn portal.gecko-a.lab`, `fasport 80`,
  `faspath /`, `fas_secure_enabled 0` (token in clear text — fine for the lab, documented
  as insecure by openNDS; not what GECKO evaluates).
- **Reachable before login** (`preauthenticated_users`): GeoPKI server
  `tcp 1234 to 192.168.137.1`, portal HTTPS `tcp 443 to 192.168.137.10` (FAS port 80 is
  allowed by openNDS itself). DNS and DHCP to the router are allowed by default. So the app
  can query GeoPKI and probe the portal's keys while still logged out.
- **Captive Portal API (RFC 8908/8910) is off** (`dhcp_default_url_enable 0`). By default
  openNDS sends DHCP option 114 = `http://status.client`; that name doesn't resolve on A, so
  Android (which prefers the API) showed "null is unreachable" instead of the login page.
  With it off, clients use classic detection (HTTP probe → redirect). The API mode is a
  later, separate experiment (scenario S8). Clients keep option 114 until their DHCP lease
  renews — reconnect the WiFi after changing this.
- **Lab CA:** the portal certificates are signed by `portal/keys/gecko-lab-ca.crt`
  (valid until 2036; leaf certificates until 2027-11-05, renew with `portal.exe sign`, pins
  unchanged). Install it on the tablet as a CA certificate, otherwise Android's
  "Sign in to network" screen rejects the portal (`tls: unknown certificate` in the portal
  log) and Chrome warns. GECKO doesn't depend on the CA — the app only compares pins.
- **Router B is trusted** (`trustedmac 94:83:c4:97:c2:47`): B hangs off A's LAN, and its
  uplink must not sit behind A's portal (B models an independent attacker network).
- DNS on A: `portal.gecko-a.lab` and `pay.gecko-pay.lab` → `192.168.137.10` (the more
  specific entries override `gecko-a.lab → 192.168.8.1`).
- On B, the evil-twin entry `/gecko-a.lab/192.168.247.1` also matches
  `portal.gecko-a.lab` (dnsmasq matches subdomains), so B answers for the portal domain with
  its own admin-UI key → `CONFLICT`.

Useful commands on A:

```bash
ndsctl status                     # clients, FAS URL, trusted MACs
ndsctl json                       # client list incl. state (Preauthenticated/Authenticated)
ndsctl deauth <client-mac>        # log a client out again (repeat a test)
/etc/init.d/opennds stop          # portal off (all clients get through)
/etc/init.d/opennds start
```

Android randomises the WiFi MAC per network; `ndsctl json` shows the tablet's current one.

## Access (SSH)

`~/.ssh/config` (Windows: `C:\Users\41763\.ssh\config`):

```
Host mango-a
  HostName 192.168.137.50
  User root
  IdentityFile ~/.ssh/id_mango
  IdentitiesOnly yes
Host mango-b
  HostName 192.168.8.115
  User root
  ProxyJump mango-a
  IdentityFile ~/.ssh/id_mango
  IdentitiesOnly yes
Host mango-a-ui
  HostName 192.168.137.50
  User root
  LocalForward 8081 127.0.0.1:80
  IdentityFile ~/.ssh/id_mango
  IdentitiesOnly yes
Host mango-b-ui
  HostName 192.168.8.115
  User root
  ProxyJump mango-a
  LocalForward 8082 127.0.0.1:80
  IdentityFile ~/.ssh/id_mango
  IdentitiesOnly yes
```

- Key auth with a dedicated, passphrase-less lab key `~/.ssh/id_mango` (comment
  `mango-lab`), installed in `/etc/dropbear/authorized_keys` on both routers (Dropbear
  keeps keys there, not in `~/.ssh`). `IdentitiesOnly yes` stops SSH from also offering the
  other keys (e.g. the passphrase-protected `id_ed25519`). Test:
  `ssh -o BatchMode=yes mango-a echo ok` / `mango-b`.
- **Installing a key from Windows PowerShell:** don't pipe the `.pub` file into `ssh`
  (`type key.pub | ssh ...`) — the conda PowerShell adds a UTF-8 BOM and CRLF, and Dropbear
  silently ignores the corrupted line. Pass it as an argument instead:
  ```powershell
  $k = (Get-Content $HOME\.ssh\id_mango.pub -Raw).Trim()
  ssh mango-a "echo '$k' > /etc/dropbear/authorized_keys; chmod 600 /etc/dropbear/authorized_keys"
  ```
- Routers: OpenWrt 22.03.4 (GL.iNet firmware 4.3.28), firewall4/nftables, nginx serves the
  admin UI. Only ~1.5 MB free on `/overlay` — check before installing packages.
- **mwan3 ping tracking is disabled on A** (2026-10-04). GL.iNet's multi-WAN manager pings
  `1.1.1.1`/`8.8.8.8`/OpenDNS to decide whether the WAN is up; when those pings fail (e.g.
  while ICS is broken) it marks the WAN offline and blocks the **router's own** traffic
  (`wget: Operation not permitted`, `opkg update` fails) even though forwarded client
  traffic may still pass. Removed `mwan3.wan.track_ip`, set `initial_state=online`; backup
  in `/root/mwan3-wan.backup` on A. B still has tracking enabled. Restore on A with
  `uci add_list mwan3.wan.track_ip=1.1.1.1; uci add_list mwan3.wan.track_ip=8.8.8.8;
  uci commit mwan3; /etc/init.d/mwan3 restart` (the SSH session drops during the restart).
- **openNDS 9.10.0 is active on A** (since 2026-10-04) — see
  [Captive portal](#captive-portal). Note: OpenWrt starts packages on install — install
  with `opkg install X; /etc/init.d/X stop; /etc/init.d/X disable` in one command.
- Dashboards: `ssh -N mango-a-ui` / `ssh -N mango-b-ui`, then `localhost:8081/8082`.
- `uci` for router config (section names vary by firmware — run `uci show wireless` first).

## GeoPKI server (reference impl)

- `netsec-ethz/geopki` (Juan's upstream), Go, runs in WSL2 (Ubuntu 22.04.5) at
  `~/Thesis/geopki`. Database: **PostgreSQL 14**. `direnv` handles Go version pinning and
  loads `DATABASE_URL`, `CERT_INSERT_KEY` and `PRIVATE_KEY` from `.envrc` (secrets — keep
  them out of this file).
- **Trillian** runs separately in Docker (`~/Thesis/trillian/examples/deployment`:
  `trillian-log-server`, `trillian-log-signer`, `mysql`). The containers restart on their
  own after a WSL restart.
- Server implements **spatial lookup only**: `POST /v1/query` with protobuf bitstrings.
  There is **no domain lookup and no JSON shim** — client must use `queryLocation()`
  (bitstrings + altitude bounds), never `queryDomain()`.
- Wire format is binary protobuf from the shared `.proto` files.
- Known upstream fixes already applied locally (hash-propagation SQL bug, missing SQL
  functions, GDAL/Go 1.20 pinning). Pablo's own setup guide is authoritative over the
  upstream README.
- The server's public key is pinned in the app (`PinnedServerKey.kt`). If the server is
  ever re-initialised with a new key, every query fails as `CONFLICT` until it is
  re-pinned.

### Starting the server

In a WSL terminal:

```bash
cd ~/Thesis/geopki
go run ./cmd/geopki-server --address=0.0.0.0 --port=1234 \
  --trillian-address=127.0.0.1:8090 --clog-id=5656256576233247638
```

Use clog-id `5656256576233247638` (the current log tree), not older IDs from shell history.

### Windows 10 ↔ WSL plumbing (NAT mode — mirrored unavailable on Win10)

- `netsh portproxy` forwards `0.0.0.0:1234` → `<wsl-ip>:1234`.
- WSL IP changes on every reboot / `wsl --shutdown`, so the proxy is re-applied by
  `~/geopki-proxy.ps1` (admin PowerShell), which reads the current WSL IP.
- Firewall rule `GeoPKI 1234` (inbound TCP 1234, RemoteAddress `192.168.137.0/24`) is
  one-time. Scoped to the ICS subnet so it stays closed to ETH.
- ICS reboot-persistence registry fix applied (`EnableRebootPersistConnection=1`).

### Restart procedure (after laptop reboot or `wsl --shutdown`)

Order matters:

1. Start `geopki-server` in WSL (above).
2. **Then** run `& "$HOME\geopki-proxy.ps1"` in an admin PowerShell.
3. Check: `curl.exe http://192.168.137.1:1234/` → `404`.

### ICS troubleshooting

Windows ICS is fragile: it often stops working after the laptop's WLAN switches networks
(ETH WiFi ↔ phone hotspot), after sleep, or after sharing was toggled off and on. The
checkbox still looks ticked while NAT/DNS silently stop.

| Symptom | Meaning |
|---|---|
| `Ethernet 4` has `169.254.x.x` instead of `192.168.137.1`; `ssh mango-a` times out | ICS not applied to the adapter — no route to the lab at all |
| `ssh mango-a` works, A reaches `http://192.168.137.1:1234/` (404) but not `http://example.com` | ICS NAT not forwarding — no internet behind the routers |
| Tablet "connected" to `GeckoTest` but `adb shell ping 1.1.1.1` fails | Same; the WiFi icon only means "connected to the router" |

Lab traffic (tablet ↔ routers ↔ GeoPKI server on the laptop) does **not** need ICS NAT —
only `192.168.137.1` on `Ethernet 4`. Internet behind the routers is needed for `opkg` and
the portal's "internet after login".

Diagnose (admin PowerShell):

```powershell
Get-NetIPAddress -InterfaceAlias 'Ethernet 4' -AddressFamily IPv4   # expect 192.168.137.1
$m = New-Object -ComObject HNetCfg.HNetShare
foreach ($c in @($m.EnumEveryConnection)) { $p = $m.NetConnectionProps($c); $x = $m.INetSharingConfigurationForINetConnection($c)
  "{0,-30} sharing={1} type={2}" -f $p.Name, $x.SharingEnabled, $x.SharingConnectionType }   # WLAN: True/0, Ethernet 4: True/1
```

Fix, in order:

1. Re-enable sharing (admin PowerShell; same `$m` as above):
   ```powershell
   $conns = @($m.EnumEveryConnection)
   $wlan = $conns | ? { $m.NetConnectionProps($_).Name -eq 'WLAN' }
   $eth  = $conns | ? { $m.NetConnectionProps($_).Name -eq 'Ethernet 4' }
   $m.INetSharingConfigurationForINetConnection($wlan).EnableSharing(0)   # shares its internet
   $m.INetSharingConfigurationForINetConnection($eth).EnableSharing(1)    # receives it
   ```
   It can take a minute until `Ethernet 4` shows `192.168.137.1` again.
2. `Restart-Service SharedAccess -Force` often fails ("Fehler beim Beenden des Diensts") —
   the service hangs. Then **reboot**; that is why a reboot has fixed ICS before.
3. After any reboot: [restart procedure](#restart-procedure-after-laptop-reboot-or-wsl---shutdown).

Don't run `DisableSharing()` unless you re-enable right after: with sharing off, the
adapter falls back to `169.254.x.x` and the lab is unreachable.

**Fallback address (applied 2026-10-04):** even with sharing enabled, `Ethernet 4` lost
`192.168.137.1` twice (after a reboot, and while A reconfigured its network). The address
was re-added by hand and persists across reboots; it is the same address ICS uses, so
they don't conflict:

```powershell
New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.137.1 -PrefixLength 24
```

If `Ethernet 4` shows `169.254.x.x` again, run that line (admin). Lab access comes back
immediately; internet behind the routers additionally needs ICS itself to work.

On a phone hotspot, some carriers/phones drop traffic from devices behind the hotspot
(extra hops). If the laptop has internet but the routers don't even with ICS healthy,
that's the likely cause — it doesn't affect lab traffic.


## Android app — locked architecture decisions

(Do not redesign these without checking with Pablo.)

- Four-state result model: `VERIFIED / UNVERIFIED / UNRECOGNIZED / CONFLICT`.
  `UNVERIFIED` is the default for unregistered networks, **not** rejection.
- The **app initiates the WiFi connection** and drives the whole flow; the user picks the
  network in-app, not in Android settings.
- `GeoQueryEncoder`: WGS84→bitstring, full S2 port of the Go encoder — **done**, with
  golden-vector conformance tests passing.
- `ProofVerifier`: Merkle inclusion proof verification — non-optional for a faithful
  prototype.
- `GeoCertificate` schema v2: `portal.domains` are `PortalDomain` entries with `role`
  (`primary`/`delegate`/`api`) and **per-domain** pins; `schema_version`; altitude is a
  separate `areas_altitude` list (one `[min, max]` per area); `auth_server_cas` carries raw
  base64 DER CA bytes for `WifiEnterpriseConfig.setCaCertificate()` (EAP-TLS path,
  deferred). Details under [Registered GeoCerts](#registered-geocerts).
- **Root approach (agreed with advisor).** GECKO is assumed to be implemented at system
  level (part of the OS / WiFi stack), so the prototype runs with **root** as the
  stand-in for system privileges. There is **no VPN-based (`VpnService`) path** — it was
  a non-root workaround and is dropped. Root provides `wpa_supplicant` control-socket
  access (`SupplicantCertSource`), traffic observation (`PcapTrafficObserver` /
  `PcapSniCapture`) and enforcement (`IptablesEnforcer`) for continuous detection against
  timed bait-and-switch (a single self-probe is defeatable).
- Build order: `GeoQueryEncoder` + protobuf → `ProofVerifier` → `GeckoClient` rewrite →
  `VerificationEngine` rewire → root capability packages. Currently at the last step.

## Android integration (phase 1) — done

1. **Server URL per target** — build flavors `emulator` (`http://10.0.2.2:1234`, shows
   `FakeDemoNetworks`) and `device` (`http://192.168.137.1:1234`, real scans only), via
   `BuildConfig.GEOPKI_URL` / `SHOW_DEMO_NETWORKS`.
2. **Cleartext HTTP** — allowed app-wide via `android:usesCleartextTraffic="true"` (no
   `network_security_config` yet; scope it to the server host, or move to pinned TLS,
   before anything beyond the prototype).
3. **Permissions** — `ACCESS_FINE_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
   `CHANGE_NETWORK_STATE`, `NEARBY_WIFI_DEVICES` (Android 13+); location and nearby-devices
   requested at runtime. Real `startScan()` on refresh (Android throttles to 4 scans /
   2 min — falls back to cached results).
4. **Root capability packages** — *not done, next phase* (needs a rooted tablet). The
   phase-1 results were obtained without root; the verification logic is the same.
5. **App-initiated join** — `NetworkObserver.join()` via `WifiNetworkSpecifier`, BSSID-pinned,
   open networks only. GeoPKI queries go over cellular if present, else over the joined
   WiFi; the TLS probe (`CertProbe`) always goes over the joined WiFi.

## Running the app on the tablet

- Build/install: `.\gradlew.bat installDeviceDebug` (tablet on USB), or select the
  `deviceDebug` variant in Android Studio and Run.
- Check flow: Networks → `GeckoTest` → pick the access point (A or B) → leave the domain
  empty (the app checks the first **primary** domain registered for the SSID once joined;
  pre-filled after the first check) or type one to test something else → **Connect & check** → approve
  Android's connection dialog (every time).
- Badges: `VERIFIED` = "Verified", `CONFLICT` = "Mismatch", `UNRECOGNIZED` =
  "Unrecognized", `UNVERIFIED` = "Not registered", `UNREACHABLE` = "Can't check".
- **Results are cached in memory** per BSSID + domain: force-close and reopen the app
  between attempts, or a retry just shows the previous verdict.
- USB is only needed for installing and logs; the app itself works unplugged.
- Logs:
  ```
  adb logcat -s GeckoClient VerificationEngine ProofVerifier NetworkObserver CertProbe
  ```
  `NetworkObserver` logs joins (`joined GeckoTest (<bssid>)` / `could not join`);
  `CertProbe` logs the presented certificate and SPKI hash, or why the probe failed.

## Location / GeoCert notes

- GNSS is present on the tablet. Indoor fixes can be slow/imprecise; use mock locations
  for controlled tests (Developer options → Select mock location app).
- Register GeoCerts with an area covering the room + a few tens of metres margin, and
  **generous altitude bounds** (indoor GNSS altitude off by 10–30 m). The app queries
  with the full altitude range anyway (`altitude = null`), but too-tight areas still
  produce false `UNVERIFIED`.

## Testbed limitations

Document these in the thesis; they bound what the results show.

- **Relay through B:** in relay mode B forwards the tablet to A's genuine server, and the
  check correctly returns `VERIFIED`. GECKO authenticates the *endpoint*, not the radio;
  traffic outside the probed TLS session (plain HTTP, DNS) still passes through B.
- **B is daisy-chained behind A:** a real attacker has an independent uplink. Here B's
  DNS defaults to A (which is why relay mode is the default) and B depends on A for
  reachability. Blocking/availability experiments need B on its own uplink (e.g. a second
  USB-C Ethernet adapter).
- **WiFi-only tablet:** the GeoPKI query goes through the AP under test, so the AP can
  block it → `UNREACHABLE` (no warning). Real for WiFi-only devices; measure it, don't hide
  it.
- **Domain typed by hand** stands in for portal detection — results test the verification
  logic, not automatic detection.
- **App chooses the BSSID** — sidesteps the OS's signal-based AP selection.
- **Admin UI as "portal"** with a generic self-signed certificate; a real portal would
  use a public domain.
- **Root as a stand-in for system-level integration:** the prototype is an app with root,
  not part of Android itself. Results show what a system-level implementation could do,
  not what an ordinary (unprivileged) app can.
- **One tablet, one router model, indoor GNSS.**

## Experiment matrix

Repeat each case several times; keep raw logs.

| # | Scenario | Expected | Status |
|---|---|---|---|
| 1 | A, registered domain | `VERIFIED` | ✅ observed |
| 2 | B relay mode (no DNS override) | `VERIFIED` (relay limitation) | ✅ observed |
| 3 | B evil-twin mode (own key for `gecko-a.lab`) | `CONFLICT` | ✅ observed |
| 4 | B blocks the GeoPKI server (firewall rule on B) | `UNREACHABLE` (blocking limitation) | |
| 5 | Domain not on any GeoCert | `UNRECOGNIZED` | ✅ observed (typos) |
| 6 | SSID with no GeoCert | `UNVERIFIED` | |
| 7 | Tampered proof / wrong pinned server key | `CONFLICT` | |

## Next phases

1. Experiment matrix above (clean baseline before the testbed changes).
2. Root the tablet (prerequisite for everything below).
3. Root-based continuous detection against timed bait-and-switch: traffic observation
   (pcap / SNI capture) + `IptablesEnforcer`, `wpa_supplicant` control socket.
4. Portals: openNDS on A, cloned portal on B — replaces the typed domain with a real
   redirect (with root, the portal domain can come from observed traffic).
5. Independent uplink for B (blocking experiments).

## Out of scope for now (don't start unless asked)

- EAP-TLS / `authServerCAs`.
- Non-root / `VpnService` approach (dropped — see locked decisions).
- GPS spoofing (out of scope for the thesis; Cases C/C.1 analyzed but assumption-based).

## adb quick reference

- adb isn't on PATH: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"` or add
  that folder to user PATH.
- Tablet USB mode: **Transferring files**; **Samsung Auto Blocker OFF** (else adb blocked);
  USB debugging on.
- `adb shell getprop ro.product.model` — record the model.
- `adb shell cmd wifi list-scan-results` — raw scan with BSSID, frequency, RSSI.
- `adb shell cmd wifi status` — which BSSID the tablet is on.
