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

A's GeoCert (registered, produces `VERIFIED` on A):

```json
"wifi":   { "ssid": "GeckoTest", "auth_mode": "open" },
"portal": { "domains": ["gecko-a.lab"],
            "pinned_spki_sha256": ["QwsHK0yXsUHme1g9/rqOHixTb8cCuCMBxYdaglLs1Q8="] }
```

Area covers general ETH (central Zürich) area, so I can work from different ETH locations or maybe even home
**Known issue:** two certificates on the server have an empty `wifi.auth_mode`. The app
drops them on every query (`GeckoClient: dropping unparsable certificate: WiFiAuthMode
does not contain element with name ''`). Set `auth_mode` to `"open"` or remove them.

## Access (SSH)

`~/.ssh/config` (Windows: `C:\Users\41763\.ssh\config`):

```
Host mango-a
  HostName 192.168.137.50
  User root
Host mango-b
  HostName 192.168.8.115
  User root
  ProxyJump mango-a
Host mango-a-ui
  HostName 192.168.137.50
  User root
  LocalForward 8081 127.0.0.1:80
Host mango-b-ui
  HostName 192.168.8.115
  User root
  ProxyJump mango-a
  LocalForward 8082 127.0.0.1:80
```

- Key auth was set up (ed25519 pubkey in `/etc/dropbear/authorized_keys` on both). Routers
  run Dropbear, so keys live there, not `~/.ssh`.
- **As of 2026-10-02 key auth is not working:** `ssh mango-a` prompts for a password, and
  key logins from other shells are rejected (`Permission denied (publickey,password)`).
  Check the key is loaded in the shell you use, and that `authorized_keys` survived on
  the routers.
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
3Check: `curl.exe http://192.168.137.1:1234/` → `404`.


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
- `GeoCertificate` schema: `PortalDomain` entries with `role` (`primary`/`delegate`);
  altitude folded into `GeoCertArea`; `authServerCAs` carries raw base64 DER CA bytes for
  `WifiEnterpriseConfig.setCaCertificate()` (EAP-TLS path, deferred).
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
- Check flow: Networks → `GeckoTest` → pick the access point (A or B) → type the domain
  (`gecko-a.lab`) → **Connect & check** → approve Android's connection dialog (every time).
- Badges: `VERIFIED` = "Checked", `CONFLICT` = "Mismatch", `UNRECOGNIZED` =
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
