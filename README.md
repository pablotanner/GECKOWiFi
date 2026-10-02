# GECKOWiFi

An Android client for **GECKO**, a geographic PKI, implementing WiFi network
verification against evil-twin and captive-portal attacks. Built as part of
a Master's thesis at ETH Zürich.

> **Status: research prototype.** This verifies the core cryptographic and
> decision-logic pipeline end-to-end against a live GECKO server. It is not
> a finished app - see [What needs to be done](#what-needs-to-be-done) before
> assuming any given feature works.

## The problem

A CA (or a captive portal's own HTTPS identity) can prove *who* a
certificate belongs to, but nothing ties that identity to *this specific
physical network, at this specific location*. Two concrete failure modes:

- **Eduroam-style (802.1X/EAP)**: the RADIUS/AAA server's certificate is
  often self-signed, or CA-issued in a way the device can't validate as
  "correct for this location" even when the chain itself is well-formed.
- **Captive portal**: the portal's HTTPS identity can be validated by normal
  Web PKI, but that only proves the domain owns that TLS certificate - not
  that the domain is the legitimate portal for *this* physical network.

[GECKO](https://github.com/netsec-ethz/geopki) fixes this by binding a
physical region (a polygon + altitude range) to a set of registered
certificates in a map server backed by a Sparse Merkle Tree, so a client can
cryptographically ask "what's authorized to present itself here?" and get a
signed, provable answer.

## How verification works

On a check, the client:

1. Queries the GECKO map server for certificates registered at the device's
   current location (lat/lng/altitude + radius).
2. Cryptographically verifies the response - server signature, Merkle
   inclusion, and append-only consistency proof - before trusting anything
   in it.
3. Filters the verified candidates down to ones whose registered `wifi.ssid`
   matches the network actually being evaluated. **SSID is a lookup key
   here, not a trust anchor** - it selects which registered claim is
   relevant; the actual trust decision is still the cryptographic
   domain/key comparison that follows.
4. Compares what the network *actually presents* (captive-portal domain +
   pinned key, or EAP auth-server name + CA fingerprint) against the
   SSID-matched certificate(s).

This resolves to one of:

| State | Meaning |
|---|---|
| `VERIFIED` | Presented identity matches a GeoCert registered here |
| `UNVERIFIED` | Nothing registered at this location (or for this SSID) |
| `UNRECOGNIZED` | Certificates exist here, but none names this domain/server |
| `CONFLICT` | A registered identity presented something unexpected - the evil-twin signature, or a failed cryptographic proof |
| `UNREACHABLE` | Couldn't query GECKO at all (distinct from `UNVERIFIED` - never conflate "couldn't check" with "checked, nothing there") |

### The decision rule

> The app should warn only when a network's SSID matches a GeoCert
> registered at this location, **and** what that network actually presents
> doesn't match what's registered. That's the evil-twin signature.
> Everything else stays silent.

Concretely: **only `CONFLICT` is user-facing.** Every other state is
computed and logged internally (useful for a history view, and for future
heuristic refinement) but never interrupts the user. Given the assumption
that most networks encountered day-to-day simply won't be GECKO-registered,
a design that alerted on every non-`VERIFIED` state would be constant noise.

There's also session-aware logic in `PortalSession`: once a domain verifies
and becomes the session's "anchor," a different, unlisted domain appearing
later in the *same* session is `CONFLICT`, not `UNVERIFIED` - this catches
bait-and-switch redirects mid-session. This is a core piece of logic and is
treated as protected (see [Protected invariants](#protected-invariants)).

### GECKO connectivity channel

Verification queries prefer **cellular data**, not the WiFi network being
evaluated. This isn't just a fallback for convenience - it closes a real
attack: if the query went out over the WiFi itself, an evil twin (or any
real captive portal, which routinely firewalls everything except its own
login server) could simply block or interfere with the verification query.
A blocked query correctly surfaces as `UNREACHABLE`, which never warns - so
an attacker able to prevent the phone from reaching GECKO at all would
otherwise defeat the whole mechanism without breaking any cryptography.
Falls back to the default route (logged, not silent) when no cellular
network is available.

### Trust anchor: the map server's key is pinned, not fetched

Every cryptographic check in this app - Signed Map Head, Signed Consistency
Head, the whole Merkle proof chain - is only as trustworthy as the public
key it's verified against. That key is **pinned at build time** (or on setup)
(`PinnedServerKey.kt`), not fetched at runtime from `/v1/public-key`.

Why: fetching it fresh on every check meant that whenever a query fell back
to the WiFi network under evaluation (see the fallback note above), that
same untrusted network could hand back *its own* key instead of the real
one - and every downstream signature/proof check would then pass, because
it'd be internally consistent with the substituted key. Pinning closes that
specific gap for this single, developer-controlled demo server.

In a real deployment we would probably need an actual key-rotation system, 
so rotation doesn't require every client to update first.

## Architecture

```
com.thesis.geckowifi/
├── geopki/               Kotlin port of netsec-ethz/geopki's client-relevant Go code
│   ├── bitstring/         coordinate <-> discretized bit-string encoding (26/25/15-bit XY/Z)
│   ├── crypto/             signature verification, SMT node/root-hash rebuild, consistency proofs
│   ├── comm/               (reference .go kept alongside for parity checking)
│   └── proto/              request.proto / response.proto (Wire-generated)
├── data/
│   ├── remote/GeckoClient.kt      HTTP + protobuf + crypto verification against the map server
│   ├── model/                      GeoCertificate and friends (mirrors the server's real Go schema)
│   └── local/                      DecisionCache
├── verification/
│   ├── VerificationEngine.kt       orchestrates query -> SSID filter -> comparison
│   ├── PortalSession.kt            captive-portal anchor/delegate comparison (protected, see below)
│   ├── EnterpriseVerification.kt   802.1X auth-server-name / CA-fingerprint comparison
│   ├── GeoQueryEncoder.kt          lat/lng/radius -> XY bit strings, altitude -> Z bounds
│   └── CertProbe.kt                real TLS handshake -> presented SPKI hash
├── network/                NetworkObserver (real WiFi scan/cellular binding), fake demo networks
├── enterprise/              EnterpriseConfigurator - builds a WifiNetworkSuggestion from a GeoCert
├── capability/              root capability implementations (see below) - not yet wired into the app
├── location/                LocationProvider
├── di/AppModule.kt          manual dependency wiring
└── ui/                      Jetpack Compose UI - see below
    ├── MainActivity.kt          thin shell: permission handling, hands off to GeckoWifiApp
    ├── GeckoWifiApp.kt          NavHost + bottom-bar Scaffold tying the screens together
    ├── VerificationViewModel.kt one long-lived VerificationEngine + all UI state
    ├── theme/                   Color/Type/Theme.kt - Material3 approximation of the design system
    ├── components/              StateBadge, DetailRow - shared across screens
    └── {networks,activity,status,settings,networkdetail,conflict,connected,checkdetail,technicaldetails}/
                                  one package per screen
```

### The UI

Built with Jetpack Compose against a set of screens from a Claude Design
project, filtered to what real logic actually backs - see
[What's real vs. simulated](#whats-real-vs-simulated) and
[What needs to be done](#what-needs-to-be-done) for exactly what was
included, adapted, or left out and why. Three of the original design's
screens were skipped entirely: **Networks Protection Off** (depends on an
enforcement on/off toggle that isn't wired anywhere - `Enforcer`/
`IptablesEnforcer` are unused today), and **Portal**/
**Portal Connected** (simulate a WebView-embedded captive-portal browser
with a live status bar - no WebView integration or live-monitoring loop
exists). **Status** keeps its bottom-nav slot but shows an honest "not
available yet" placeholder rather than the design's live-monitoring/
protection-toggle mockup. **Settings** has no design reference at all (none
was provided) - it's plain Material3 exposing the two preferences that
already have real backing (`TrustPreferenceStore`).

### `capability/` - root mechanisms

GECKO is assumed to be implemented at system level, so the prototype uses
**root** as the stand-in for system privileges (agreed with the advisor).
There is no non-root/`VpnService` path - an earlier VPN skeleton was removed.

| Concern | Implementation |
|---|---|
| Certificate source | `SupplicantCertSource` (reads `wpa_supplicant`'s `CTRL-EVENT-EAP-PEER-CERT`); `InferredCertSource` remains as a no-root stand-in for development |
| Network enforcement | `IptablesEnforcer` |
| Traffic observation | `PcapTrafficObserver` (root `tcpdump`, streamed; SNI via `TlsSniParser`) | **None of this is currently called from `MainActivity` or
`VerificationEngine`** - see [What needs to be done](#what-needs-to-be-done).

## Current state

What's genuinely working, proven against a live geopki server (not just
unit tests):

- Full crypto pipeline: protobuf request/response, SMT node/root-hash
  rebuild, ECDSA signature verification (`NONEwithECDSA` - the server signs
  raw bytes, it does not hash internally), Merkle consistency proof.
- Coordinate encoding (`GeoQueryEncoder` + the ported `bitstring` package)
  round-trips correctly against real server data at real coordinates.
- SSID pre-filtering, cellular-bound queries (with a logged fallback), and
  the "only `CONFLICT` interrupts the user" UI policy are all implemented.
- A working, if manual, demo flow: four hardcoded network entries (two
  benign/evil-twin pairs, one captive-portal-style and one eduroam-style)
  that exercise the full comparison logic without needing real APs.
- A real Jetpack Compose UI with bottom-nav navigation (Networks/Status/
  Activity/Settings) and detail screens (Conflict, Connected, Network
  Detail, Check Detail, Technical Details), all reading real app state via
  one long-lived `VerificationViewModel` - see [The UI](#the-ui).
- `VerificationEngine`'s `PortalSession`s now actually persist across
  checks of the same network (a bug found and fixed during the Compose
  rewrite: the old `MainActivity` rebuilt a whole new `VerificationEngine`,
  and therefore a fresh empty session map, on every single tap).

None of the above required root. Root-dependent pieces
(`SupplicantCertSource`, `PcapTrafficObserver`, `EnterpriseConfigurator`'s
real-world success path) exist as code but are **unverified against real
hardware** - see below.

## What's real vs. simulated 

- **The four demo network entries are entirely simulated.** No AP, no
  RADIUS/AAA server, no real handshake, no real TLS connection. Tapping one
  calls `VerificationEngine` directly with hardcoded "as if observed"
  values. This tests the **comparison/decision logic**, not detection -
  reading a demo entry's `VERIFIED` result as "we detected and verified a
  real network" would be a mischaracterization.
- **Real WiFi scanning is real** - `NetworkObserver.scanResults()` reads the
  device's actual cached scan results. But the observed domain for a real
  network still has to be **typed in manually** - there's no automatic
  captive-portal domain detection wired up yet.
- **The crypto/query pipeline is entirely real** for both the demo and real
  paths - every query in this app actually round-trips to a live server and
  is actually cryptographically verified, regardless of which flow
  triggered it.

### Current State: What App can do

Tapping a real network does **not** mean the app
detected or verified anything about it. The app has no captive-portal
awareness at all - no `NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL`
check, no connectivity-check probe, nothing. It doesn't even know which
scanned networks are captive portals versus open internet. Selecting one
from the list just sets it as a cache key for the check that follows. The
actual sequence, today, is:

1. You join the network yourself, through Android's own system WiFi
   settings, the app never initiates a connection.
2. The captive portal opens in your browser, via the OS's own detection -
   independent of this app.
3. You read the redirect domain with your own eyes.
4. You come back to this app, tap that network in the Networks list, and
   **type that domain in manually** on the Network Detail screen, then
   press Check.
5. *Only now* does anything real happen: `CertProbe.fetchCertificate()`
   does a genuine TLS handshake against that host (reachable only because
   you're already on that network through some other means) and the real
   comparison logic runs against the real fetched key.

So the one genuinely "real" thing the real-network path adds over a fake
demo entry is step 5 - an actual TLS probe against an actual host, instead
of a hardcoded SPKI hash. Everything upstream of that (knowing a captive
portal exists, knowing its domain, timing the check to the redirect) is
you, standing in for detection - exactly the role the hardcoded string
plays in `FakeDemoNetworks.kt`. Closing this gap is `PcapTrafficObserver`'s job: watch traffic during the "just associated,
portal not yet resolved" window, extract the SNI hostname from the
ClientHello automatically, and feed it into `verifyPresentedDomain()` the
moment it's observed - no human reading a browser redirect required.

## Emulator vs. physical device

| | Emulator | Physical (rooted) device |
|---|---|---|
| Server reachability | `10.0.2.2:1234` (QEMU host-loopback alias) works out of the box | `10.0.2.2` resolves to nothing - the server address is a fixed constant (`SERVER_URL` in `VerificationViewModel.kt`, matching `PinnedServerKey`'s pinned key - see [Trust anchor](#trust-anchor-the-map-servers-key-is-pinned-not-fetched)), so it needs editing in source for a real deployment, not a runtime field |
| Cellular binding | No real radio; needs the logged OkHttp fallback to reach the server at all over a simulated secondary network | Genuine cellular radio - the anti-interference property is real, but the server must then be reachable *from cellular data* (a LAN-only address won't be, and you'll silently hit the fallback instead) |
| WiFi scan / GPS | Fully simulated (Extended Controls) | Real `wifi.scanResults` / real GPS fix |
| TLS probing (`CertProbe`) | Works against any real reachable host | Same - works identically |
| Root-based capture (`SupplicantCertSource`, `PcapTrafficObserver`, `RootShell`) | N/A - not wired into the app regardless | Exists in code but **unwired and unverified** - rooted or not, the running app doesn't currently call any of it |
| Real WiFi connection | N/A | The app never actually joins a network on your behalf, on either target - selecting a real entry only sets it as the comparison's cache key |

## Setup

1. Run a geopki server (see [netsec-ethz/geopki](https://github.com/netsec-ethz/geopki)) and insert at least one certificate.
2. Point `VerificationViewModel.kt`'s `SERVER_URL` constant at that server's
   address, and `PinnedServerKey.kt`'s pinned key at its actual public key
   (`curl <server>/v1/public-key | base64 -w0`) - rebuild after either
   changes. Both are fixed at build time now, not runtime fields - see
   [Trust anchor](#trust-anchor-the-map-servers-key-is-pinned-not-fetched).
3. Grant location permission when the app asks - it queries GECKO at the
   device's actual last-known location (`LocationProvider.lastKnown()`), no
   manual lat/lng entry any more. Make sure wherever you registered
   certificates is where the device (or emulator - set one via Extended
   Controls > Location) actually is: **a location a few hundred meters off
   from anything registered silently returns zero matches, indistinguishable
   from "unregistered network."** This bit while debugging once already -
   a stale prefilled default pointed ~530m from the actual demo certs; if
   every check reports `UNVERIFIED` with no error at all, check location
   agreement first before suspecting the crypto or the server.

Example insert payload matching the current demo entries (the SSID-gated
flow - see [How verification works](#how-verification-works) - expects
`wifi.ssid` to be set):

```bash
curl -X POST "http://localhost:1234/v1/insert?key=YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "certificate_id": "eduroam-ethz-campus-001",
      "wifi": {
        "auth_mode": "wpa2-enterprise",
        "ssid": "eduroam",
        "eap": "peap",
        "auth_server_names": ["radius.ethz.ch"],
        "trusted_ca_fingerprints": ["NLEWAnfdeAYUmQvPuzEdZA980jz6RORmyEERVADEFHA="]
      },
      "areas": [{
        "type": "MultiPolygon",
        "coordinates": [[[[8.5467,47.3759],[8.5507,47.3759],[8.5507,47.3799],[8.5467,47.3799],[8.5467,47.3759]]],[]]
      }],
      "areas_altitude": [[350,450]],
      "not_valid_after": "2027-06-01T00:00:00Z"
    },
    {
      "certificate_id": "campus-guest-portal-001",
      "wifi": { "auth_mode": "open", "ssid": "Campus Guest" },
      "portal": {
        "domains": ["portal.wifi.example.edu", "wifi.example.edu"],
        "pinned_spki_sha256": [
          "vBjsKk7VPT860WzRB5O7LdJh1KP2fX0S7uMyDQw+sD4=",
          "wS7SsCsyJi/0DSxMB2sPj4RMWdwjJc1I3eoyMorMIzs="
        ],
        "certificate_sha256": ["2zOM3wS3T3XBwpt2Ebi1m+bm7NgFsBsPiyR8Upbjs1I="]
      },
      "areas": [{
        "type": "MultiPolygon",
        "coordinates": [[[[8.5467,47.3759],[8.5507,47.3759],[8.5507,47.3799],[8.5467,47.3799],[8.5467,47.3759]]],[]]
      }],
      "areas_altitude": [[350,450]],
      "not_valid_after": "2027-06-01T00:00:00Z"
    }
  ]'

curl -X POST "http://localhost:1234/v1/release?key=YOUR_KEY"
```

Fingerprint/hash fields are exact-string-compared against what
`CertProbe`/a real hash computation produces (plain base64, no scheme
prefix) - a stored `"sha256:..."`-prefixed value will never match anything
real.

Run tests: `./gradlew testDebugUnitTest`

## What needs to be done

Roughly in order of what unblocks the most:

- **Wire root-based capture into the running app.** `PcapTrafficObserver`
  exists but nothing calls it; `Capabilities` doesn't yet have a
  `trafficObserver()` factory alongside `certificateSource()`/`enforcer()`. `PcapTrafficObserver`'s
  own SNI-extraction is also untested - `PcapSniCapture.kt`'s (unit-tested)
  logic should be reconciled with it rather than left as a second,
  divergent implementation.
- **Real captive-portal domain detection.** Once traffic observation is
  wired, feed each observed hostname into `verifyPresentedDomain()` as it's
  captured, during the "just joined, portal not yet resolved" window.
- **Real enterprise/eduroam path.** `verifyEnterprise()` is currently only
  ever called with hardcoded demo values. `SupplicantCertSource` has never
  been run against a real 802.1X handshake - needs a rooted device on a
  real, test-controlled enterprise network.
- **`EnterpriseConfigurator.buildFrom()` validation.** Builds a
  `WifiNetworkSuggestion` pinned by domain-suffix (CA-fingerprint pinning
  isn't possible - GECKO only has fingerprints, not full certificates, and
  `WifiEnterpriseConfig` wants a certificate). Untested beyond compiling -
  `WifiNetworkSuggestion.Builder()` returns null under the plain JVM test
  stub even with `isReturnDefaultValues = true`; needs Robolectric or an
  instrumented test.
- **Real WiFi connection wiring.** Selecting a real network currently only
  sets a cache key - the app never actually joins it.
- **Payment-portal handling** (thesis proposal task 6). A legitimate
  mid-session redirect to a payment processor (Stripe, a hotel chain's own
  gateway) currently looks identical to a bait-and-switch `CONFLICT`. Needs
  a design decision - possibly an allow-listed-processor field on
  `GeoCertificate`.
- **System WiFi settings integration** (proposal task 7).
  `WifiNetworkSuggestion` is the realistic vehicle - suggestions surface in
  the system's own "Connect to network" flow; there's no API for a
  third-party app to inject UI into Settings directly.
- **GUI design + HCI validation** (proposal tasks 2-3). Not started beyond
  "most of the UI's job is being quiet correctly."
- **Pre-populated offline GECKO map** - a third connectivity-channel option
  floated by the advisor, deferred in favor of the cellular-first design
  above.
- **Multi-server trust model** (explicitly out of scope for this single-demo-
  server proof of concept, see [Trust anchor](#trust-anchor-the-map-servers-key-is-pinned-not-fetched)):
  CA-signed `GeoCertificate`s instead of trusting the map server's own key
  for authenticity, querying a pool of independent map servers, and
  gossip-based split-view detection between them.
- **Pre-connection "Mismatch" badge on the Networks screen** - the design
  shows one (a scanned network's auth mode disagreeing with what's
  registered, before ever connecting), deliberately not implemented:
  would need heuristic parsing of `WifiManager`'s capability strings into
  a comparable auth mode, which felt too fuzzy to call "real" for a first
  pass. Today, Networks only shows Registered/Not-registered presence.
- History (`HistoryStore`) is real but in-memory only - restart the app
  and Activity/Network Detail's history is gone. A real deployment would
  want this persisted (Room is already a build dependency; a Room-backed
  draft once existed at `data/local/HistoryStore.kt.ignore`, since scaled
  back to the simpler in-memory `Stores.kt` version this app actually uses).

## Protected invariants

`PortalSession`'s comparison/state logic (`verification/PortalSession.kt`)
is treated as settled and should not be changed casually - it's a core
piece of the thesis's contribution (the anchor/delegate session logic that
catches bait-and-switch mid-session redirects). New logic (like the SSID
pre-filter in `VerificationEngine`, or `EnterpriseVerification`, which is
deliberately a separate function rather than an addition to
`PortalSession`) is layered *around* it instead of inside it.

## Acknowledgements

Built on top of [netsec-ethz/geopki](https://github.com/netsec-ethz/geopki).
The `geopki/` package is a Kotlin port of that project's client-relevant Go
code (bitstring discretization, SMT verification, protobuf schema).
