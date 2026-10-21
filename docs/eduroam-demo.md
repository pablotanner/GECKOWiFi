# eduroam onboarding demo (Design A)

GECKO replaces eduroam CAT: the app joins the target SSID with a public GECKO
identity, fetches the site registration and the user's signed realm record
over that session, verifies both, and only then installs a profile that
pins the realm's RADIUS server (CA + server name) and carries the user's
password. The brief is [eduroam-brief.md](../eduroam-brief.md); every choice
made while implementing it is in [eduroam-decisions.md](eduroam-decisions.md).

## Status (2026-10-20)

| Phase | State |
|---|---|
| 0 Backups, inventory | Done. WSL distro exported before installing FreeRADIUS; router archives in `lab-secrets/router-backups/`, configs in `lab-configs/` |
| 1 Test PKI | Done, `lab/eduroam/make-pki.sh` → `lab-secrets/` |
| 2 FreeRADIUS | Done: legit + attacker B1 + attacker B2 in WSL, all 11 `eapol_test` checks pass, relay path tested |
| 3 Legit SP | **Done: Router A** (vanilla OpenWrt, `sp-legit.sh`): WPA2-Enterprise + dynamic VLAN walled garden. Scene 1 confirmed on A (2026-10-21); first developed and fully tested on B, including the walled-garden negative test (below) |
| 4 Map server side | Done: `eduroam-lab-sp` inserted, realm endpoint serving, live JVM tests pass |
| 5 App | Done and unit-tested; on the Tab: onboarding request and pinned suggestion accepted by One UI 8.5. End-to-end needs phase 3 |
| 6 Router B | Vanilla OpenWrt, now only `GeckoTest` (attacker portal); `eduroam_b1` kept disabled. Scenes 2 and 4 confirmed earlier against B1 (2026-10-20); to repeat with A as the legit SP. B2 (scene 3) still to set up |
| 7 Demo script, evidence | This document; B1 evidence reproducible without hardware (`b1-evidence.sh`) |

## Components

```
Tablet ── eduroam-lab ── Router A (WAN 192.168.137.50) ── laptop 192.168.137.1
                                                            ├─ udp-relay.py  UDP 1812/1814/1816 → WSL
                                                            ├─ WSL: FreeRADIUS legit :1812, B1 :1814, B2 :1816
                                                            ├─ WSL: geopki map server :1234 (portproxy)
                                                            └─ realm server :1235 (lab/realm, Windows)
```

| Piece | Where |
|---|---|
| Test PKI, secrets, signed records, router backups | `lab-secrets/` (gitignored; back it up separately) |
| PKI script | `lab/eduroam/make-pki.sh` (refuses to overwrite an existing PKI) |
| FreeRADIUS instances | `lab/eduroam/radius/setup-instances.sh` → `~/gecko-radius/{legit,attacker-b1,attacker-b2}` in WSL |
| UDP relay | `lab/eduroam/udp-relay.py` |
| Realm signer + server | `lab/realm` (`go run . sign`, `go run . serve`) |
| Site GeoCert | `lab/certs/eduroam-lab.json` (`eduroam-lab-sp`) |
| App | `app/.../eduroam/`, UI in `app/.../ui/eduroam/` |

### RADIUS instances

| Instance | Port | Server certificate | Accepts |
|---|---|---|---|
| legit | 1812 | `GECKO-Lab-CA` → `radius.homeuni.test`, `gecko-onboard.sp.test` | `alice@homeuni.test` (normal access); `gecko@gecko.sp.test` / `gecko` → VLAN 99, Session-Timeout 60, only if outer = inner |
| attacker-b1 | 1814 | `Rogue-CA` → same names | `gecko` (but no client validates the cert) |
| attacker-b2 | 1816 | `GECKO-Lab-CA` → `gecko-onboard.sp.test` only | `gecko` (valid onboarding cert: the threat model) |

Shared secrets: `RADIUS_SECRET_A` (Router A → legit), `RADIUS_SECRET_B` (Router B →
attackers), in `lab-secrets/radius/secrets.env`, together with `ALICE_PASSWORD`.

### App

| Class | Role |
|---|---|
| `OnboardingTransport` / `SpecifierOnboardingSession` | Phase 1 session (`WifiNetworkSpecifier`, PEAP-MSCHAPv2 as `gecko@gecko.sp.test`, onboarding CA from `res/raw`, `gecko-onboard.sp.test`); always released |
| `VerificationEngine.checkAdvertisedSite` | Site query over the onboarding network, no fallback route |
| `RealmRecordClient` | `GET /v1/realm/<realm>` over the onboarding network |
| `AuthenticatedEapPins` | The only source of pins: verifies a `SignedRealmRecord` (ECDSA P-256, pinned key) or reads a site GeoCert's `aaa` entry; private constructor |
| `EduroamCredentials.EnterpriseProfileBuilder` | The only code that can read the password; releases it only with a plan made from `AuthenticatedEapPins` for the account's realm |
| `EnterpriseProfileInstaller` / `SuggestionProfileInstaller` | Installs the pinned profile (`WifiNetworkSuggestion`) |
| `decide()` | Proceed only with authenticated pins and no site `CONFLICT` (D2) |
| `KeystoreCredentialStore` | The one test account, AES-GCM key in Android Keystore |

Root seam (D1): a root build would provide another `OnboardingTransport`
(wpa_supplicant directly, no approval dialog) and another
`EnterpriseProfileInstaller` (wpa_supplicant network block); nothing else changes.

Lab guard: onboarding only runs for `eduroam-lab`; real `eduroam` and `eth`
are on every floor of the lab building.

## Bring-up (laptop)

After a reboot, in this order:

1. **Docker Desktop** (Trillian runs there). Without it the map server fails
   with `unable to create consistency client: failed to connect to 127.0.0.1:8090`.
2. **Map server** in WSL, as in [lab-setup.md](lab-setup.md#map-server).
3. **RADIUS** in WSL (one terminal each, or `bg`):
   ```bash
   cd /mnt/c/Users/41763/AndroidStudioProjects/GECKOWiFi
   bash lab/eduroam/radius/run.sh legit           # Router A
   bash lab/eduroam/radius/run.sh attacker-b1     # only while testing scene 2/4
   bash lab/eduroam/radius/run.sh attacker-b2     # only while testing scene 3
   bash lab/eduroam/radius/test-radius.sh         # all instances up: 11 checks
   bash lab/eduroam/radius/stop.sh all
   ```
   `setup-instances.sh` rebuilds the instances from the stock config (e.g.
   after changing it or regenerating secrets).
4. **Relay** (Windows; restart it after WSL restarts, it looks up the WSL IP at start):
   ```powershell
   python lab\eduroam\udp-relay.py
   ```
   Check from the laptop: `python lab\eduroam\radius-ping.py 192.168.137.1 1812 <RADIUS_SECRET_A>`
   (needs `--allow ... 192.168.137.1` on the relay for a local test).
5. **Realm server** (Windows):
   ```powershell
   cd lab\realm; go run . serve -addr 192.168.137.1:1235 -dir ..\..\lab-secrets\realm\signed
   ```
   Re-sign after changing a record or before it expires (365 days):
   `go run . sign -key ..\..\lab-secrets\realm\realm-signing.key -in records\homeuni.test.json -out ..\..\lab-secrets\realm\signed`
6. **Firewall** (once, as administrator): `lab\eduroam\firewall-rules.ps1`
   (UDP 1812/1814/1816, TCP 1235, from `192.168.137.0/24` only).

## Routers

Both Mangos (OpenWrt 22.03.4, GL 4.3.28) run GL's proprietary MediaTek driver
(`wireless.mt7628.type='mtk'`), not hostapd. GL's configurator maps
`encryption 'wpa2'` + `server`/`port`/`key` to the driver's `RADIUS_*` settings
and starts MediaTek's 802.1X daemon **`/usr/bin/8021xd`, which is not
installed** on either router. Dynamic VLAN: no sign of support in the driver
or configurator, so 3b (walled garden) is not feasible without replacing the
Wi-Fi stack. Demo shortcut: the GECKO identity gets normal access (the
VLAN 99 / Session-Timeout attributes are still sent and tested in FreeRADIUS;
enforcement is operator-side and orthogonal to the client flow).

**GL's package feed has no `8021xd`** (checked 2026-10-20 with `opkg update;
opkg find '*8021*'`: only `ieee8021xclient`, a wired client). So with GL
firmware 4.3.28 the Mangos can't be WPA2-Enterprise APs by installing a
package. **Decision needed (Pablo)**, roughly from least to most invasive:

1. **A different AP for `eduroam-lab`** that can do WPA2-Enterprise with an
   external RADIUS server (any OpenWrt device with `wpad`, a Raspberry Pi with
   `hostapd`, most consumer APs with "WPA2-Enterprise"). The Mangos stay as
   they are; the laptop side works unchanged (the relay allowlist takes the
   new AP's address).
2. **Check whether a newer GL firmware for the MT300N-V2 ships `8021xd`**
   (the configurator already has the code path). Firmware update = flashing,
   needs your explicit OK.
3. **Vanilla OpenWrt** on the Mangos (mt76 driver + a `wpad` variant with
   802.1X authenticator support; check flash fit). Most invasive: loses GL's
   UI and openNDS setup, needs the portal setup redone.

**Chosen direction: option 3, Router B first.** Image, checks, backups, preseed
and the step-by-step are in [flashing-openwrt.md](flashing-openwrt.md). Under
vanilla OpenWrt the UCI names below become `auth_server`/`auth_port`/`auth_secret`
and the radio is `radio0`; the flashing guide has the adjusted commands.

### Proposed changes (not applied; each needs your OK)

Phase 3a, Router A, after `8021xd` is in place. A **new** section, disabled
first, so `GeckoTest` stays untouched:

```sh
uci set wireless.eduroam_lab=wifi-iface
uci set wireless.eduroam_lab.device='mt7628'
uci set wireless.eduroam_lab.mode='ap'
uci set wireless.eduroam_lab.network='lan'
uci set wireless.eduroam_lab.ifname='ra2'
uci set wireless.eduroam_lab.ssid='eduroam-lab'      # never 'eduroam'
uci set wireless.eduroam_lab.encryption='wpa2'
uci set wireless.eduroam_lab.server='192.168.137.1'
uci set wireless.eduroam_lab.port='1812'
uci set wireless.eduroam_lab.key='<RADIUS_SECRET_A>'
uci set wireless.eduroam_lab.disabled='1'
uci commit wireless
# then, separately: uci set wireless.eduroam_lab.disabled='0'; uci commit wireless; wifi reload
```

Risk: the driver currently runs `BssidNum=2` (ra0 + the disabled guest ra1);
whether the GL configurator handles a third interface is unknown. Fallback:
use the disabled `guest2g` slot (ra1) instead, which edits an existing (unused)
section.

Low TX power is a radio setting (`wireless.mt7628.txpower`, now `30`) and
also applies to `GeckoTest`; proposed `uci set wireless.mt7628.txpower='5'`.

Router B (phase 6): the same section with `server '192.168.137.1'`,
`port '1814'` (B1) or `'1816'` (B2) and `RADIUS_SECRET_B`, as two sections
toggled with `disabled`. B's RADIUS traffic leaves through A's WAN, so the
relay sees it from `192.168.137.50`, which is already allowed. B2 additionally
needs a firewall rule on B that blocks forwarding to `192.168.137.1:1234`
and `:1235`. **Radio off when idle.**

Snapshots before and after every change: `bash lab/eduroam/pull-router-configs.sh`,
then commit `lab-configs/` and add a line to `lab-configs/CHANGELOG.md`.

## Demo scenes

Before scene 1, remove any manually saved `eduroam-lab` network on the Tab
(Android prefers a user-saved network over a suggestion), and enter alice's
password once in the app (Settings → lab mode → "Set up eduroam-lab", or tap
`eduroam-lab` in the list).

| Scene | Running | Expected in the app | Evidence |
|---|---|---|---|
| 1 Happy path | Router A (`eduroam-lab`), legit RADIUS, map + realm server | All steps ✓, "Connected with pinned profile", pins from "signed realm record" | legit log: `alice@homeuni.test` accepted (`count-inner.sh` on the legit log) |
| 2 Rogue cert (B1) | Router B in B1 only, attacker-b1 | Step 1 fails ("server's certificate isn't valid"), "password not sent". If the profile from scene 1 is still installed, Android also tries it against B1 and aborts in TLS | `count-inner.sh <attacker-b1 log>`: no inner credentials |
| 3 Withholding (B2) | Router B in B2 only, attacker-b2, B blocks 1234/1235 | Onboarding ✓, site "Map server not reachable", realm record missing, "password not sent" | attacker-b2 log: only `gecko@gecko.sp.test` |
| 4 Baseline | Router B in B1, a **manually** configured `eduroam-lab` profile without CA/domain | Android sends the inner exchange to B1 | `count-inner.sh`: `alice@homeuni.test` + MSCHAPv2 exchange |

Scenes 2 and 4 against the attacker's RADIUS, without radios:
`bash lab/eduroam/radius/b1-evidence.sh` (pinned → no inner credentials;
unpinned → alice's MSCHAPv2 exchange captured). Verified 2026-10-20.

Logs: `adb logcat -s OnboardingSession EduroamOnboarding EduroamInstaller GeckoClient`.

## Hardware results

**Current lab roles (2026-10-21):** Router A = legit eduroam SP (`eduroam-lab`) +
genuine `GeckoTest` portal; Router B = `GeckoTest` attacker portal, attacker eduroam
sections disabled. Both on vanilla OpenWrt 25.12.6.

**2026-10-21, Router A as the legit SP:** scene 1 again, all steps, connected.
RADIUS: `gecko@gecko.sp.test` → VLAN 99 / Session-Timeout 60, then
`anonymous@homeuni.test` (alice) without VLAN. A: `RADIUS: VLAN ID 99`,
`AP-STA-CONNECTED ... vlanid=99`, walled garden 2 connections to map/realm
allowed, 2 packets rejected, alice in `edu` (10.98.0.14) 6 s after onboarding.
Before the test the tablet had already joined A on its own with the profile
installed on B the day before: one pinned profile works on every AP of the
network. Evidence: `lab-secrets/evidence/scene1-routerA-legit-20261021.log`.

Lab issues found on the way (fixed): openNDS 10.3.1 on A rejected replies to
A's own connections into its LAN, so `ssh mango-b` failed
(`/usr/sbin/gecko-opennds-fix`); routers and laptop on one USB hub caused link
drops on A's ports (routers now on the hub alone). The laptop-A link still
drops about once a minute (cable or USB adapter, under investigation); the
demo tolerates it, SSH sometimes needs a retry.


**2026-10-20, Router B as the legit SP** (`lab/eduroam/openwrt/sp-legit.sh`, legit RADIUS :1812):

Scene 1, GECKO app, alice@homeuni.test: all six steps, "connected". App outcome:
site `REGISTERED` (`eduroam-lab-sp`, queried over the onboarding session), no site
servers for `homeuni.test` (visitor), realm record signature verified (pins
PEAP/MSCHAPV2, `radius.homeuni.test`), decision Proceed, suggestion installed,
`connected=true`. Onboarding join 3.7 s, map query + realm record 0.2 s, about 10 s
to connected. RADIUS: `gecko@gecko.sp.test` accepted with VLAN 99 /
Session-Timeout 60, then `anonymous@homeuni.test` (alice) accepted without VLAN;
the tablet ended up in `br-edu` (10.98.0.14).

Walled garden, negative test (tablet joined by hand with the GECKO identity,
landed at 10.99.0.105, B logged `RADIUS: VLAN ID 99`, `AP-STA-CONNECTED ... vlanid=99`):

| From the onboarding session | Result |
|---|---|
| map server 192.168.137.1:1234, realm endpoint :1235 | HTTP answers |
| internet (1.1.1.1, example.com's IP), ICMP, DNS | refused / no answer / unresolvable |
| other laptop ports, portal server, Router A, Router B (LAN, walled-garden gateway, `edu` gateway) | refused |

B's firewall: 102 packets rejected in the onboard zone, 8 connections to the map/realm
ports allowed. Session-Timeout enforced: `deauthenticated due to session timeout`
exactly 60 s after the login.

Evidence: `lab-secrets/evidence/scene1-legit-20261020.log`, `scene1-app-20261020.log`.

Open UI point: reopening the setup screen loses the last result (the
`EduroamViewModel` is scoped to the navigation entry).


**2026-10-20, Router B in mode B1** (OpenWrt, `eduroam-lab` → attacker-b1 via relay), Galaxy Tab A11:

| Scene | Tablet | Attacker log (`count-inner.sh`) |
|---|---|---|
| 2: GECKO app | Step 1 fails, "Not set up: password not sent" | only `gecko@gecko.sp.test`; **0 inner-tunnel requests**; client sent TLS alert `unknown_ca` (4 attempts) |
| 4: manual profile, CA "Trust on first use", user taps "Trust" | Connection fails silently | `alice@homeuni.test` in the **inner tunnel** (outer only `anonymous@homeuni.test`), **2 MSCHAPv2 exchanges** captured |

Evidence: `lab-secrets/evidence/scene2+4-attacker-b1-20261020.{log,summary.txt}`.

Things that got in the way, for next time:
- **The laptop's Thunderbolt controller went into power saving (RTD3)** every few
  minutes and took the USB Ethernet adapter with it: A's WAN link dropped,
  RADIUS packets were lost (attempts failed without reaching the attacker,
  scene 4 got no certificate prompt). Fix: adapter on a non-Thunderbolt port,
  or USB selective suspend off, laptop on mains. Check: `ssh mango-a "dmesg | grep 'port 0 link down'"`.
- The tablet has no location indoors without internet; the app now times
  out instead of hanging, and a mock location via adb gives the lab location:
  `adb shell appops set com.android.shell android:mock_location allow`, then
  `adb shell cmd location providers add-test-provider gps`,
  `... set-test-provider-enabled gps true`,
  `... set-test-provider-location gps --location 47.37643,8.54674 --accuracy 15`
  (same for `network`; remove with `remove-test-provider`).
- Android 16 has no "Don't validate" any more; scene 4 uses "Trust on first use".
  Forget the network afterwards.

## Demo shortcuts (all documented in code too)

- RealmRecord from a signed static endpoint instead of a realm keyspace in the map (D6).
- One RADIUS certificate with both names, one CA for onboarding and the realm (D7).
- Both realms handled locally by one FreeRADIUS; in production `homeuni.test` is proxied to the federation.
- Onboarding identity, password, server name and CA are app constants (D8).
- No walled-garden enforcement on the Mangos (no dynamic VLAN); the attributes are still sent.
- Plain HTTP to map and realm server; integrity from proofs and signatures (D9).
- One hard-coded test account; FreeRADIUS runs as root from user-owned copies.
- Lab guard: onboarding only on `eduroam-lab`.

## Open points

- Map-head freshness and RealmRecord replay within `expires_at`: still open (brief, section 1).
- Where the onboarding identity and CA would come from in a real deployment (D8).
- Realm records in the map with inclusion proofs (D6), to settle with the supervisor.
- `Session-Timeout` and VLAN handling by MediaTek's `8021xd`: untested.
