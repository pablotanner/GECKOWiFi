# Experiments

Hardware runs on the testbed described in [lab-setup.md](lab-setup.md). Keep the app's check log
(`adb pull …/portal-checks/`) and the portal server's log (`portal/logs/`).

Router A is the genuine network, router B the attacker. The test tablet
(Galaxy Tab A11) has WiFi only, so every map query goes over the network being
checked. It isn't rooted; scenarios marked 🔒 need a rooted device.

"Expected" is what the current code gives. The acceptance criteria these runs
back are in [acceptance-criteria.md](acceptance-criteria.md).

## Without a captive portal

| # | Scenario | Expected | Result |
|---|---|---|---|
| N1 | Router A with WPA2, registered with `auth_modes: ["wpa2-personal"]` and no portal | `REGISTERED` | |
| N2 | B copies A's SSID as an open network | `CONFLICT` (security type not registered) | |
| N3 | An SSID with no certificate | `UNVERIFIED`, no warning | |
| N4 | A in WPA2/WPA3 transition mode, registered with `["wpa2-personal", "wpa3-personal"]`; B copies the SSID with WPA2 only | `CONFLICT` before joining (downgrade) | |

## With a captive portal

The app finds the portal itself (see [portal-detection.md](portal-detection.md)).

| # | Scenario | Expected | Result |
|---|---|---|---|
| P0 | Genuine portal on A | `VERIFIED` | ✅ |
| P1 | A, already logged in (no portal shown; the registered domain is checked directly) | `VERIFIED` | ✅ |
| S0 | B relays A's whole portal unchanged | `VERIFIED` (true for the portal; says nothing about the connection) | ✅ |
| S1 | Copied portal: same domain, attacker's key | `CONFLICT` | ✅ |
| S2 | Lookalike domain (`gecko-a-login.lab`) on the same SSID | `CONFLICT` (SSID exclusivity) | ✅ |
| S3 | Genuine portal first, then a redirect to the attacker | `CONFLICT` at the switch | ✅ (hop 4) |
| S3m | As S3, with a delayed `<meta refresh>` | `CONFLICT` at the switch | ✅ (hop 4) |
| S3h | As S3, with a `Refresh:` header | `CONFLICT` at hop 3 (see note) | ✅ (hop 3) |
| S3j | As S3, with a JavaScript redirect | `CONFLICT` at hop 3 (see note) | ✅ (hop 3) |
| S4a | Payment page with the attacker's key, reached by a redirect | `CONFLICT` | ✅ |
| S6 | Login page over plain HTTP only | `CONFLICT` | ✅ |
| T1 | TLS to the registered portal domain fails (timeout, refused) | `CONFLICT` (no key counts as a wrong key; a flaky genuine portal is a false alarm) | |

**S3 setup.** Router A must run `genuine-bounce.json`, not `genuine.json`, so
that its flow has a plain-HTTP `/continue` hop the attacker can rewrite.
Otherwise the chain ends on the login page and never reaches the switch. The
attacker's HTTP-to-HTTPS upgrade must keep the relayed host name (`serveHTTP`
in `portal/`), or the probe skips the genuine portal.

## Limits of the one-time probe

The app checks once, right after joining, by following redirects. These runs
show what that misses; they are expected misses, not bugs.

| # | Scenario | Expected | Result |
|---|---|---|---|
| S4b | Payment page with the attacker's key, reached by a button | Missed (the probe doesn't click) | ✅ |
| S5a | B switches this client to the attacker after 10 s | Missed | |
| S5b | B shows the genuine chain only to the probe (by User-Agent) | Missed | |
| S5c | B switches only after the user taps "Accept" | Missed | |
| D1 | A's DNS redirects a domain other than the portal | `VERIFIED`, attack not seen (design decision 3) | |

## Map server

| # | Scenario | Expected | Result |
|---|---|---|---|
| S7 | B blocks the map server | `UNREACHABLE` | |
| R1 | Proof changed in transit (mitmproxy between tablet and map server) | `CONFLICT`; an answer that no longer parses gives `UNREACHABLE` | |
| R2 | B replays an older, validly signed answer | Accepted. The age check (`maxMapHeadAge`) is off, because the geopki server only signs when the map changes | |
| R3 | Wrong pinned map-server key in the app (another valid key) | `CONFLICT` | |

## Location

The location comes from the device. Spoof it with a mock-location app in the
developer options (no root needed).

| # | Scenario | Expected | Result |
|---|---|---|---|
| L1 | S1, with the location spoofed to an empty area | `UNVERIFIED`, no warning (the attack is missed) | |
| L2 | S1, with the location spoofed just outside A's area (farther than the query radius) | `UNVERIFIED`, no warning: the map server still returns A's certificate, but its area doesn't reach the device, so it is dropped | |
| L3 | Location accuracy worse than 253 m (the largest query radius) | Check refused ("Your location isn't precise enough"), not a silent `UNVERIFIED` | |
| L4 | Two neighbouring certificates; move a few metres across the border | The result switches to the neighbour | |

## Enterprise (eduroam)

| # | Scenario | Expected | Result |
|---|---|---|---|
| E0 | Real eduroam at ETH, `ethz-eduroam.json` registered | List: "Registered with GECKO by ETH Zürich", no warning. Tapping opens the registration sheet | |
| E0o | Open "eduroam" (router B, low power, briefly) on the ETH campus | List: "Doesn't match" before connecting; tapping warns instead of joining. "Connect anyway and check" gives `CONFLICT` | |
| E1 🔒 | Device on (lab) eduroam with its own profile, realm set in Settings; "Check login server", then reconnect | Genuine server: `VERIFIED`; rogue server: `CONFLICT`; realm not set or visitor: `REGISTERED`. Read-only: reports after the handshake, doesn't stop it | |
