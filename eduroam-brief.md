# Brief for Claude Code — GECKO eduroam-style onboarding demo ("Design A")

You are helping Pablo (ETH Zurich MSc thesis, NetSec group) build a **prototype and live demo** in his GECKOWifi Android project and his two-router lab. Read this whole document before doing anything. When in doubt, stop and ask Pablo rather than guessing — especially for anything that touches the routers.

---

## 1. Background

**Thesis:** Android-based secure WiFi verification against evil-twin attacks using **GECKO / GeoPKI** (`github.com/netsec-ethz/geopki`). GECKO is a geographic PKI: a map server holds location-bound certificates (GeoCerts) in a Merkle tree; clients query by location (S2-style bitstrings + altitude bounds) and verify Merkle inclusion proofs against a signed map head.

**Thesis assumptions (always respect these):**
- GECKO is **widely deployed** (operators register GeoCerts and are willing to do some configuration for it).
- The GECKO app is treated **as if it were part of the phone OS** (system level). In the thesis, root/system privileges are assumed. **However, this demo must run on a non-rooted device** (see §5), so it uses only public Android APIs. Where the non-root path costs something (e.g. extra approval dialogs), note it as an artifact of the prototype, not of the design.
- The adversary does **not** hold any valid GeoCert.
- Map-head freshness is a known open TODO; don't solve it here, but don't hide it either.
- Four-state verification model: `VERIFIED / UNVERIFIED / UNRECOGNIZED / CONFLICT`. `UNVERIFIED` is the default for unregistered networks, not rejection.

**The eduroam problem:** In eduroam (WPA2/3-Enterprise, PEAP/TTLS), users' passwords leak to evil twins when the client does not pin the RADIUS server's CA and server name. Today eduroam CAT / geteduroam fixes this by installing a pinned profile, downloaded out of band. **The goal of this work is for the GECKO app to replace CAT:** the app holds the user's credentials and builds the pinned profile itself, from data it obtained and verified via GECKO.

**Key design facts (don't re-litigate these):**
1. In 802.1X there is **no IP path before EAP-Success**, so a classic "walled garden whitelist" is impossible before credentials are sent.
2. The EAP config (CA, server name, method, outer identity) is a property of the user's **home IdP (realm)**, not of the location. In eduroam a visitor at ETH authenticates against *their own* university's RADIUS server through the federation. So we need **two record types**:
   - **Site GeoCert** (spatial, already supported): "an eduroam SP legitimately operates `eduroam-lab` here."
   - **RealmRecord** (keyed by realm, NEW): the CAT payload for the home IdP — EAP method, phase-2, outer identity, CA cert(s), server name(s).
3. Credential protection comes from **pinning**; GECKO's contribution is an authenticated, in-band way to get the pins at first contact (plus site plausibility). The thesis framing must stay precise about this.

---

## 2. The design to build: "Design A" (anonymous GECKO identity → walled-garden VLAN)

Two sessions on the **same SSID**:

```
Phase 1 — onboarding session (no secrets involved)
  App ─WifiNetworkSpecifier(SSID+BSSID, identity gecko@gecko.sp.test, public dummy password,
                            onboarding CA + domain gecko-onboard.sp.test)──▶ AP (Router A)
  AP ─RADIUS─▶ FreeRADIUS: identity gecko@gecko.sp.test is handled LOCALLY (never proxied)
  FreeRADIUS ─Access-Accept + EAP-Success + VLAN 99 + Session-Timeout 60─▶ AP
  4-way handshake → DHCP on VLAN 99 (walled garden: only the map server is reachable)
  App ─HTTP(S) over that Network object─▶ map server: spatial query + realm-record fetch
  App releases the network, verifies proofs/signatures → one of the four states

Phase 2 — real session
  App builds WifiEnterpriseConfig from the VERIFIED RealmRecord (CA, server name, outer id, phase 2)
  App ─WifiNetworkSuggestion(real creds + pins)─▶ AP ─RADIUS─▶ (home IdP realm homeuni.test)
  Android enforces the pins → if the server cert is wrong, the password is never sent
```

**Why this is safe even though the onboarding session itself is untrusted:** an evil twin can also accept the gecko identity, but GECKO responses are self-authenticating (Merkle proof + signed map head; RealmRecord signature). The attacker can only **withhold** (→ `UNVERIFIED`) or **replay** (freshness TODO). The onboarding session uses only public dummy values, so nothing secret is exposed. **Assume the attacker can obtain a valid onboarding TLS cert** — its validation exists only because Android requires it, not for security.

**Core security invariant (enforce structurally in code):** user credentials may only ever be attached to a `WifiEnterpriseConfig` whose CA and server-name pins come from a **verified** RealmRecord. There must be exactly one code path that can do this (`EnterpriseProfileBuilder`).

---

## 3. Goal: what the demo must show

| Scene | Setup | Expected result |
|---|---|---|
| 1. Happy path | Router A only | App: onboarding → `VERIFIED` → connected with pinned profile. FreeRADIUS log: `alice@homeuni.test` accepted. |
| 2. Evil twin (rogue cert) | Router B in mode B1 only | Onboarding gives `UNVERIFIED`/`UNRECOGNIZED`; real session never attempted, or aborted at cert check. **Attacker RADIUS log shows zero inner (phase-2) credentials.** This is the key result. |
| 3. Evil twin withholding | Router B in mode B2 only | Onboarding succeeds at L2 but map server unreachable → `UNVERIFIED`, no credentials sent. |
| 4. Baseline (optional) | Router B1 + a manually configured, *unpinned* profile in Android Settings | Attacker log *does* capture the inner identity/MSCHAPv2 attempt — the thing the app prevents. |

---

## 4. Ground rules — READ BEFORE TOUCHING ANYTHING

### 4.1 Radio safety (non-negotiable)
- **Never broadcast the SSID `eduroam`.** The lab is on ETH campus; real devices would auto-connect and an attacker AP would harvest real credentials. Use **`eduroam-lab`**.
- Set low TX power on both routers (`option txpower` in `/etc/config/wireless`, e.g. a few dBm).
- Router B (attacker) radio is **disabled when not actively testing**. Never leave it running unattended.
- Attacker logs must only ever contain test identities (`alice@homeuni.test`, `gecko@gecko.sp.test`). If anything else ever shows up, stop, disable B, delete those log entries, and tell Pablo.

### 4.2 Backups (do this first, every time a phase starts)
Pablo's biggest worry is breaking router configs by stacking prototypes. Therefore:
1. **Before any router change**, take a full config archive and copy it to the laptop.
   (Restore: LuCI → System → Backup/Flash Firmware → Restore, or `sysupgrade -r <file>` on the router.)
2. Also save `opkg list-installed` per router (archives don't include packages).
3. **Git-track `/etc/config`** of both routers. After every change: pull the files, commit with a message naming the prototype/phase. Keep a human-readable `lab-configs/CHANGELOG.md`.
4. Back up the WSL distro before installing FreeRADIUS.
5. Keep all test key material (CA keys, RADIUS certs, RealmRecord signing key)  — **gitignored**, backed up separately. Regenerating them breaks pins.

### 4.3 How to work with the routers
- **Ask Pablo before every command that modifies a router** (UCI changes, `wifi reload`, firewall restarts, package installs). Show the exact diff/commands first.
- One change at a time; verify; commit; then the next.
- **Never** flash firmware, factory-reset, or change Router A's WAN/LAN addressing without explicit approval.
- Before installing packages (e.g. a fuller `wpad` variant), check `df -h` / free flash — the GL.iNet Mangos have very little flash. Prefer solutions that need **no router package changes**.
- Firewall changes can lock you out of SSH. Make sure LAN-side SSH/LuCI access stays allowed; prefer changes that can be rolled back by `/etc/init.d/firewall restart` after restoring the file.
- Prefer adding **new** `wifi-iface` sections (own SSID, `option disabled`) over editing existing ones, so prototypes can be toggled.

### 4.4 Code
- Don't modify upstream `geopki` server code unless needed; if you must, keep changes in a clearly separated patch/branch and tell Pablo. Pablo's own setup guide is authoritative over the upstream README (known upstream SQL bugs exist and are already worked around — don't "fix" his workarounds).
- Follow existing project conventions (Kotlin, Wire/protobuf-lite from shared `.proto` files, binary protobuf on the wire — there is no JSON shim).
- Write unit tests for verification logic and for the credential invariant.

---

## 5. Current lab state & environment constraints

- **Laptop:** Windows **10**, WSL2 (Ubuntu 22.04) in **NAT mode**. GeoPKI Go server (+ Postgres 16 + Trillian) runs inside WSL on **port 1234**. Previously used only from the Android emulator via `10.0.2.2`.
- **Wiring:** laptop is on ETH WiFi and shares internet (Windows ICS) over its only USB-C Ethernet adapter to **Router A WAN**. Router B is daisy-chained off Router A's LAN. Router A LAN/admin `192.168.8.1`; Router B LAN/admin `192.168.247.1`. Confirm the laptop's ICS-side IP (typically `192.168.137.1`) with Pablo/`ipconfig`.
- **Routers:** 2× GL.iNet Mango (OpenWrt-based GL firmware, small flash, 2.4 GHz). Discover exact versions first: `cat /etc/openwrt_release`, `opkg list-installed | grep -E 'wpad|hostapd'`, `df -h`.
- **Test device:** Samsung Galaxy Tab A11 (SM-X130), One UI 8.5, **not rootable** (bootloader can't be unlocked). WiFi-only (no cellular fallback). So: public APIs only; no `wpa_supplicant` control socket, no iptables.
- **Android project:** GECKOWifi (AGP 9.3.2, compileSdk 37, Kotlin, Wire). Existing components: `GeoQueryEncoder` (full S2 port, golden-vector tested), `ProofVerifier` (Merkle inclusion), `GeckoClient`, `VerificationEngine`. Server is **spatial lookup only** (`POST /v1/query` with protobuf bitstrings) — use `queryLocation()`, not `queryDomain()`.

**Important networking constraint (Windows 10):** WSL2 *mirrored* networking is not available on Windows 10. `netsh interface portproxy` forwards **TCP only**, but RADIUS is **UDP 1812/1813**. Options, in order of preference — evaluate and propose one to Pablo:
1. Small UDP relay on the Windows side (e.g. a ~30-line Python asyncio script listening on `0.0.0.0:1812/1813`, forwarding to the current WSL IP, which changes on reboot), plus `netsh portproxy` for TCP 1234 (map server) and any realm-record port.
2. Run FreeRADIUS natively on Windows-side tooling or in Docker Desktop (which publishes UDP ports) — only if Pablo agrees to install it.
Either way: add Windows Defender Firewall inbound rules for the ports, scoped to the ICS subnet.

---

## 6. Work plan (phases, with "done" criteria)

Create a task list and tick it off. Snapshot router configs (§4.2) at the start of each phase that touches them.

### Phase 0 — Baseline & backups
- Take backups (§4.2), set up `lab-configs/` + `lab-secrets/` (gitignored).
- Inventory router firmware/packages/flash; report to Pablo.
- **Done:** backups exist and are restorable in principle; inventory reported.

### Phase 1 — Test PKI
Using `openssl` (script it: `lab-secrets/make-pki.sh`):
- `GECKO-Lab-CA` (test root).
- One RADIUS server cert signed by it with SANs **`radius.homeuni.test`** and **`gecko-onboard.sp.test`** (one FreeRADIUS EAP module serves both identities).
- A **separate** self-signed `Rogue-CA` + rogue RADIUS cert for attacker mode B1.
- For B2 (withholding attacker), give the attacker a cert that *does* validate for `gecko-onboard.sp.test` (signed by `GECKO-Lab-CA` or a separate "onboarding CA") — this models "the attacker can obtain an onboarding cert", which is the threat model.
- Ed25519 keypair for signing RealmRecords (public key will be pinned in the app).
- **Done:** script reproducibly generates everything; private keys only in `lab-secrets/`.

### Phase 2 — FreeRADIUS (legit) on the laptop
- Install FreeRADIUS 3 in WSL; configure EAP (PEAP-MSCHAPv2 and/or TTLS-PAP; pick one primary — PEAP-MSCHAPv2 is what most eduroam IdPs use and best shows the leak in scene 4).
- `users`/authorize:
  - `alice@homeuni.test` (test password) → normal access.
  - `gecko@gecko.sp.test` with a **public dummy password** (e.g. `gecko`) → reply `Tunnel-Type = VLAN`, `Tunnel-Medium-Type = IEEE-802`, `Tunnel-Private-Group-Id = 99`, `Session-Timeout = 60`. Check that the **inner** identity inside the PEAP/TTLS tunnel is also the gecko identity (don't let a gecko outer identity carry a real inner identity).
- Both realms handled locally (no proxying). Add a short comment that in production `homeuni.test` would be proxied to the federation and only `gecko.<sp-domain>` is local.
- Solve UDP reachability from Router A (§5). RADIUS client = Router A's WAN IP, with a shared secret stored in `lab-secrets/`.
- Second FreeRADIUS instance/virtual server on a **different port** (e.g. 1814/1815) as the **attacker RADIUS** for Router B, using the rogue cert, with verbose logging of everything (`-X` style), so scene 2 can show "zero inner credentials".
- **Done:** `radtest`/`eapol_test` from WSL succeeds for both identities; attacker instance logs attempts.

### Phase 3 — Router A as legit SP (ask before each change)
- New `wifi-iface`: SSID `eduroam-lab`, WPA2-Enterprise (`encryption 'wpa2'`), `server`/`port`/`key` → laptop RADIUS, low txpower.
- **Done (3a):** the Tab connects with a manually entered, properly pinned `alice@homeuni.test` profile (CA = `GECKO-Lab-CA`, domain `radius.homeuni.test`).
- **3b — walled-garden VLAN (only if feasible without risky package changes):** dynamic VLAN (`dynamic_vlan`), VLAN 99 bridge/network with its own subnet + DHCP, firewall zone that forwards **only** to the laptop's map-server IP:port (and the realm-record port, if separate), client isolation. Check whether the installed wpad/hostapd build supports dynamic VLAN before proposing any package swap.
- **Demo fallback if 3b is infeasible:** skip enforcement; the gecko identity gets normal access. The client flow is identical; enforcement is operator-side and orthogonal. Document this clearly as a demo shortcut.
- **Done (3b):** with `wpa_supplicant`/`wpa_cli` on a Linux client *or* via the app, the gecko identity lands in VLAN 99 and reaches only the map server; alice gets normal access.

### Phase 4 — Map server side
- Make the GeoPKI server reachable from the router network (`netsh portproxy` TCP 1234 → WSL; firewall rule).
- Issue a **site GeoCert** for the lab location for SSID `eduroam-lab` (using Pablo's existing tooling; ask him for the lab coordinates/area).
- **RealmRecord — demo shortcut, no upstream server changes:** define `RealmRecord` in a shared `.proto`:
  ```proto
  message RealmRecord {
    string realm = 1;               // "homeuni.test"
    string eap_method = 2;          // "PEAP" | "TTLS" | "TLS"
    string phase2 = 3;              // "MSCHAPV2" | "PAP" | ...
    string outer_identity = 4;      // "anonymous@homeuni.test"
    repeated bytes ca_der = 5;      // raw DER CA certs
    repeated string server_names = 6; // for setDomainSuffixMatch
    string rcoi = 7;                // optional, eduroam uses 5A03BA0000
    string support_url = 8;
    int64 issued_at = 9;
    int64 expires_at = 10;
  }
  message SignedRealmRecord { bytes record = 1; bytes signature = 2; } // Ed25519 over `record`
  ```
  Serve it from a tiny separate endpoint on the laptop (e.g. `GET /v1/realm/homeuni.test`), not inside upstream geopki. Mark it in code/README as a **stand-in for a future realm keyspace in the map** (with inclusion proofs) — an open design question Pablo will settle with his supervisor.
- If the map server is plain HTTP, that's acceptable for the demo (integrity comes from proofs/signatures); add an Android `network_security_config` cleartext exception scoped to the lab IP, and note it.
- **Done:** from the laptop, a spatial query for the lab location returns the site GeoCert with a valid proof; the realm endpoint returns a correctly signed record.

### Phase 5 — Android app
New/changed components (keep the existing root/non-root interface split; this whole path is non-root):

| Component | Responsibility |
|---|---|
| `OnboardingSession` (new) | Build `WifiNetworkSpecifier` (SSID + scanned BSSID, `WifiEnterpriseConfig`: gecko identity, dummy password, onboarding CA bundled in `res/raw`, `setDomainSuffixMatch("gecko-onboard.sp.test")`). `NetworkRequest` with `TRANSPORT_WIFI`, **remove** `NET_CAPABILITY_INTERNET`. `requestNetwork` with timeout; on `onAvailable(network)` run the fetches; always release (`unregisterNetworkCallback`) afterwards, including on errors/timeouts. |
| `GeckoClient` (change) | Accept an injected `SocketFactory` (`network.socketFactory`) so all traffic is bound to the onboarding network. No protocol changes. |
| `RealmRecordClient` + verifier (new) | Fetch `SignedRealmRecord`, verify Ed25519 signature with pinned public key, check `expires_at`, parse. |
| `EnterpriseProfileBuilder` (new) | The **only** place where credentials meet an enterprise config. Input: verified RealmRecord + credentials. Output: `WifiNetworkSuggestion` with CA(s), domain suffix match, anonymous identity, phase 2, identity/password. Must refuse (throw) without a verified record. Unit-test the invariant. |
| `CredentialStore` (new) | Minimal Android Keystore-backed storage; for the demo a single test account is fine. |
| `VerificationEngine` (change) | Wire the eduroam path: spatial result + realm result → state → proceed only on `VERIFIED` (decide `UNVERIFIED` policy with Pablo; default for the demo: do **not** send credentials). |
| UI | One screen showing per-step state: scanning → onboarding → verifying → `STATE` → connecting → connected / blocked, with a short reason. Make it demo-readable. |

Android notes:
- Since Android 11, enterprise configs in suggestions/specifiers must have server-cert validation (CA + domain), which is why the onboarding session needs a validatable cert even though it isn't trusted.
- `WifiNetworkSpecifier` triggers a system approval dialog each time; suggestions need one-time approval. Note this as a non-root-prototype cost (a system component would skip it).
- Expect possible One UI quirks with specifiers/suggestions — test on the Tab early, not only on an emulator.
- **Done:** on the Tab, scene 1 works end to end with only system approval dialogs as user interaction.

### Phase 6 — Router B attacker modes (ask before each change; radio off when idle)
- **B1:** SSID `eduroam-lab`, WPA2-Enterprise, RADIUS → **attacker** FreeRADIUS instance (rogue cert). No router package changes needed.
- **B2:** same SSID; RADIUS → an attacker instance that accepts the gecko identity with a cert valid for `gecko-onboard.sp.test`, gives internet, but B's firewall **blocks** the map-server IP/port (and realm endpoint).
- Keep B1 and B2 as separate `wifi-iface` sections toggled via `option disabled`.
- **Done:** scenes 2 and 3 reproduce reliably; scene 4 (optional) shows the leak with an unpinned manual profile.

### Phase 7 — Demo script & evidence
- `DEMO.md`: exact steps per scene (which router/mode on, what to tap, which log to show).
- Capture evidence: app screenshots, FreeRADIUS log excerpts (legit + attacker), timings (cold onboarding → connected).

---

## 7. Explicit demo shortcuts and non-goals

**Shortcuts (allowed, but document each one in code comments and DEMO.md):**
- RealmRecord via signed static endpoint instead of Merkle-map keyspace.
- Walled-garden VLAN may be skipped if the router can't do dynamic VLAN cheaply.
- Single RADIUS server cert with two SANs; both realms local.
- Plain HTTP to the map server if TLS isn't already set up.
- Single hardcoded test account.

**Non-goals (don't build now):** caching/TTL, CA-rollover handling, Passpoint/RCOI-based matching, realm Merkle proofs, overlapping-SP `CONFLICT` semantics, VPN-based continuous detection, root-mode enforcement (wpa_supplicant socket / iptables), freshness of map heads, metrics beyond basic timings, "EAP-GECKO" (Design B — a custom EAP method; that would require a modified wpa_supplicant and is thesis-only for now).

---

## 8. Open questions — ask Pablo before deciding
1. UDP-to-WSL approach for RADIUS on Windows 10 (relay script vs Docker Desktop).
2. PEAP-MSCHAPv2 vs TTLS-PAP as the primary method.
3. Lab location coordinates/area for the site GeoCert.
4. Policy for `UNVERIFIED` on the eduroam path (default proposal: block).
5. Whether the map server already serves TLS (and its cert) or plain HTTP.
6. Any router package installs (only if unavoidable).

## 9. Definition of done
- Scenes 1–3 reproduce reliably on the Galaxy Tab with the two routers; scene 4 optional.
- Attacker logs demonstrably contain zero inner credentials in scenes 2–3.
- All router configs are git-tracked with a changelog; pre-change backups exist for every phase.
- Credential invariant enforced in a single code path, with unit tests.
- `DEMO.md` lists every shortcut taken.