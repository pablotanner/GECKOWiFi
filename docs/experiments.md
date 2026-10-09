# Experiments

Each scenario is run several times. For every run, keep the app's check log
(`adb pull …/portal-checks/`) and the portal server's log (`portal/logs/`).
The setup is described in [lab-setup.md](lab-setup.md).

The target device is rooted; the current test tablet (Galaxy Tab A11) is not
and is WiFi-only, so every GECKO query goes over the network being checked.

**Testable**

| | |
|---|---|
| ✅ | Testable now on the non-root tablet |
| 🛠 | Possible without root, but needs code or lab setup first |
| 🔒 | Needs root |
| 📝 | Analysis or server-side demonstration only |

Expected results are what the current code produces. Where that differs from
the desired behaviour, both are given.

## Without a captive portal
After extensively testing captive portal scenarios, we should also look at setups without captive portal;
fully open network; password protected (simulate private router); eduroam; etc. 

Some old notes:

| # | Scenario | Expected | Testable | Result |
|---|---|---|---|---|
| 1 | Router A | `VERIFIED` | ✅ | ✅ |
| 2 | B relays to A (no DNS override on B) | `VERIFIED` | ✅ | ✅ |
| 3 | B answers for `gecko-a.lab` with its own key | `CONFLICT` | ✅ | ✅ |
| 4 | B blocks the map server | `UNREACHABLE` | ✅ | |
| 5 | Domain not in any certificate | `UNRECOGNIZED` | ✅ | ✅ |
| 6 | SSID without a certificate | `UNVERIFIED` | ✅ | |
| 7a | Tampered proof (mitmproxy between tablet and map server) | `CONFLICT`; a response that no longer parses as protobuf gives `UNREACHABLE` instead | ✅ | |
| 7b | Wrong pinned map-server key in the app (use another valid key; a corrupted one fails to load → `UNREACHABLE`) | `CONFLICT` | ✅ | |

## With a captive portal

The app detects the portal itself (`RedirectProbeSource`). Router A runs the
genuine portal; router B runs the attacker scenarios.

### Portal chain

| # | Scenario | Expected | Testable | Result |
|---|---|---|---|--------|
| P0 | Genuine portal on A | `VERIFIED` | ✅ | ✅      |
| P1 | A, already logged in (204 → registered domain probed directly) | `VERIFIED` | ✅ | ✅      |
| P1-B | B, already logged in on B, registered domain probed | `CONFLICT` if B intercepts, `VERIFIED` if B relays | ✅ |        |
| S0 | B transparently relays A's whole portal | `VERIFIED` (true about the portal, nothing about the link) | ✅ |    ✅   |
| S1 | Clone: same domain, attacker's key | `CONFLICT` | ✅ |   ✅    |
| S2 | Lookalike domain (`gecko-a-login.lab`), same SSID | `CONFLICT` — SSID exclusivity: `GeckoTest` is registered here, so a network on it serving an unregistered domain is impersonation (see [design-decisions.md](design-decisions.md)) | ✅ |   ✅    |
| S3 | Genuine portal first, then a 3xx redirect to the attacker | `CONFLICT` at the switch (off-anchor domain mid-session) | ✅ | ✅ (hop 4) |
| S3m | As S3, via delayed `<meta refresh content="10;url=…">` | `CONFLICT` (delay is ignored, target followed) | ✅ |    ✅ (hop 4)    |
| S3h | As S3, via `Refresh: 10; url=…` response header | Switch not followed (header not parsed), but the plain-HTTP `/continue` hop is itself a downgrade → `CONFLICT` at hop 3 | ✅ | ✅ (hop 3) |
| S3j | As S3, via JavaScript redirect | Switch not followed (no JS engine), same plain-HTTP `/continue` downgrade → `CONFLICT` at hop 3 | ✅ | ✅ (hop 3) |
| S4a | Payment page (delegate) with attacker's key, reached by redirect | `CONFLICT` | ✅ |    ✅   |
| S4b | Same, but reached by a button/link | Missed; caught with link extraction from the login page, WebView, or root | 🛠 / 🔒 |    ✅   |
| P2 | Benign redirect to a payment processor the certificate doesn't list | `CONFLICT` (false positive); avoided only if the operator lists it as a delegate | ✅ |        |
| S6 | Login page over plain HTTP only | `CONFLICT` | ✅ |    ✅    |
| S9 | GeoCert without pins; B serves a self-signed cert for the registered domain | `CONFLICT`: a domain without pins never verifies. The server rejects such certificates on insert, so unit test only | 📝 |        |
| F1 | Attacker registers its own GeoCert at A's location with the same SSID, own domain and key | `VERIFIED` (false) | ✅ |        |
| F1s | As F1, but the switch to the attacker domain happens mid-chain after A's anchor | Re-anchors → `VERIFIED` (false); should be `CONFLICT` | ✅ |        |
| T1 | TLS to the registered portal domain fails (timeout, refused) | `CONFLICT` (no key seen counts as a wrong key); a flaky genuine portal gives a false positive | ✅ |        |
| S8 | Spoofed Captive Portal API (RFC 8908, DHCP option 114 on B) | Not implemented | 🛠 |        |

> **S3 setup note.** Router A must run `genuine-bounce.json` (not `genuine.json`)
> so its flow emits a plain-HTTP `/continue` hop for the relay attacker to
> rewrite; a plain genuine portal ends at a 200 login page and the probe never
> reaches the switch (`VERIFIED`). The attacker's http→https upgrade must also
> preserve a relay host's name (`serveHTTP` in `portal/`), or the probe skips
> the genuine portal entirely and the result was an `UNRECOGNIZED` (silent before
> SSID exclusivity) instead of `CONFLICT`. Confirmed on hardware 2026-10-07: genuine `portal.gecko-a.lab`
> anchors `VERIFIED` at hop 2, `login.evil.lab` is `CONFLICT` at hop 4.
>
> **S3h/S3j caveat.** The probe follows 3xx and meta refresh (S3, S3m) but not a
> `Refresh:` header or JavaScript, so in S3h/S3j it never reaches the attacker's
> page. The run is still `CONFLICT`, but at hop 3 (`/continue`), because that hop
> is the registered primary domain over plain HTTP - a downgrade, same rule as
> S6 - not because the switch was detected. This is not a gap: an attacker can
> only inject a header/JS redirect on a hop it controls, which is either plain
> HTTP (downgrade, caught) or its own TLS-terminated page (own key, caught); it
> cannot inject into the genuine HTTPS page. The only switch the probe truly
> can't see is the post-interaction one (S5c).

### Timing and probe evasion

| # | Scenario | Expected | Testable | Result |
|---|---|---|---|---|
| S5a | B switches every request of this client to the attacker after a per-client timer (10 s) | Missed with one probe; `CONFLICT` with repeated probing until the portal resolves | ✅ (single) / 🛠 (repeated) | |
| S5b | B serves the genuine chain only to the probe (matches its User-Agent), the attacker's to everyone else | Missed by any probe; caught only by observing real traffic | ✅ (shows limitation) / 🔒 | |
| S5c | B switches only after the user clicks "Accept" | Missed; caught by WebView login or root observation | ✅ (shows limitation) / 🛠 / 🔒 | |
| S5d | S5b with Android's own probe User-Agent mimicked by the app | Shows how much harder fingerprinting gets (timing, flow) | 🛠 | |

### Map server and data integrity

| # | Scenario | Expected | Testable | Result |
|---|---|---|---|---|
| S7 | B blocks the map server | `UNREACHABLE` | ✅ | |
| X3 | Map server down (no attacker) | `UNREACHABLE` | ✅ | |
| R1 | Tampered proof, portal flow | `CONFLICT` | ✅ | |
| R2 | Replay of an older, validly signed response | Accepted: the signed timestamp isn't checked for freshness | ✅ | |
| R3 | Expired GeoCert (`notValidAfter` in the past) | Not used as a candidate | ✅ | |
| R4 | Revoked GeoCert queried before the merge | `VERIFIED` (stale) | ✅ | |

## Location

Coordinates come from the device; spoofed with a developer-options mock
location app (no root needed).

| # | Scenario | Expected | Testable | Result |
|---|---|---|---|---|
| L1 | S1 plus a spoof to an empty area | `UNVERIFIED`, silent | ✅ | |
| L2 | S1 plus a spoof to an unrelated registered area | `UNVERIFIED`, silent (the SSID filter drops the other area's certificates); `CONFLICT` (SSID exclusivity) if that area has a `GeckoTest` certificate for a different identity | ✅ | |
| L3 | Attacker GeoCert at X, victim spoofed to X | `VERIFIED` (false) | ✅ | |
| L4 | Two adjacent GeoCerts, position moved a few metres across the boundary | Result shifts to the neighbour | ✅ | |
| L5 | Altitude spoof between floor-level GeoCerts | No effect: the app passes `null` altitude and queries the full range, so both floors are returned | 🛠 | |
| L6 | Query radius vs. position accuracy (vary radius at a fixed spoof offset) | Smallest offset that changes the result | ✅ | |
| L7 | Mock location detected (e.g. `Location.isMock()`) | LOW confidence | 🛠 (not implemented) | |
| E5 | S0 plus a spoof to A's coordinates from elsewhere (relay, Case E) | `VERIFIED` | ✅ | |

## Authentication class and enterprise

| # | Scenario | Expected | Testable | Result |
|---|---|---|---|---|
| A1 | SSID registered as WPA2; B clones it as open | Should be `CONFLICT`; `authMode` is not compared yet | 🛠 | |
| E1 | Genuine RADIUS (hostapd WPA2-Enterprise + FreeRADIUS), GeoCert-provisioned `WifiNetworkSuggestion` | Connects | 🛠 | |
| E2 | Rogue RADIUS with its own CA, same profile | OS refuses the connection | 🛠 | |
| E2r | Server certificate hash from `wpa_supplicant` compared with the GeoCert | `CONFLICT` | 🔒 | |
| E3 | Roaming eduroam user at a visited site | No server can be named for the visitor | 📝 | |
| E4 | EAP inner-method downgrade | Profile fixes the method → connection fails (🛠); observing the negotiated method (🔒) | 🛠 / 🔒 | |

## Other threat-model cases

| # | Scenario | Expected | Testable | Result |
|---|---|---|---|---|
| K1 | B advertises an SSID saved on the tablet (KARMA), no GeoCert there | OS auto-joins outside the app; app check gives `UNVERIFIED` | ✅ | |
| D1 | Genuine A; A's DNS hijacks a non-portal domain (CaptiveCrunch-style) | `VERIFIED`, attack undetected | ✅ | |
| D1p | Genuine A; the OS connectivity probe is hijacked | Not visible to the app | 🔒 | |
| X1 | Poisoned registration: second GeoCert for A's SSID, different domain, same location | `VERIFIED`: A's primary still matches A's certificate, so no false positive | ✅ | |
| X2 | Deauth loop / BSSID cycling against the tablet | Measure re-checks, latency, battery; deauth frames themselves invisible | ✅ | |

## Unit tests

`PortalHopsVerificationTest` covers S1–S4, S6, R1, P2, S9, F1, F1s, X1 and T1.
S3m (meta refresh with a delay) is covered by `RedirectParsingTest`. S3h needs
the `Refresh` header parsed first.

