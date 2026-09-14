# Prototype decisions and final-version TODOs

Living document tracking what's a deliberate prototype shortcut right now
versus what has to change before this is a real app. Update this alongside
the code, not after the fact. See `docs/app-design.md` for the target
design this prototype is implementing pieces of.

## Current state: SSID-gated, silent-by-default flow (this pass)

`MainActivity` shows 4 network entries: 2 real (from
`NetworkObserver.scanResults()`) and 2 fake pairs (`FakeDemoNetworks.kt`)
standing in for scenarios that can't be physically set up right now. Per
the advisor's rule (`docs/app-design.md`), every check now applies an SSID
pre-filter (`VerificationEngine`'s `ssid` parameter) before ever calling
`PortalSession`/`evaluateEnterpriseNetwork`, and the UI only actively
interrupts the user (an `AlertDialog`) for `CONFLICT` - everything else,
including an unregistered SSID, is silent (logged in the small status line
for this prototype's own debugging, not surfaced as a warning).

| Entry | Source | What happens on tap |
|---|---|---|
| Real network | `WifiManager.scanResults` (cached, no active `startScan()`) | Selects it (remembers its SSID + cache key); you type the observed domain manually and press Verify |
| Fake captive portal (benign/evil-twin) | Hardcoded in `FakeDemoNetworks.kt` | Immediately calls `VerificationEngine.verifyPresentedDomain(..., ssid = ...)` with a pre-baked domain + SPKI hash |
| Fake eduroam (benign/evil-twin) | Hardcoded in `FakeDemoNetworks.kt` | Immediately calls `VerificationEngine.verifyEnterprise(..., ssid = ...)` with a pre-baked auth server name + CA fingerprint |

**Important change from the previous pass**: each fake benign/evil-twin pair
now shares the *same* `ScannedNetwork.ssid` (e.g. both "Cafe Free WiFi"
entries), with a separate `label` field ("benign demo" / "evil-twin demo")
just for telling them apart in this UI. That's the actual definition of an
evil twin - identical visible network identity, different backend - and
it's required for the SSID pre-filter to find the *same* registered
certificate for both halves of the pair. The demo certificates therefore
need `wifi.ssid` set, which they didn't before - **re-insert them** (server
was unreachable while writing this, so this hasn't been re-verified live -
see "Still needs live verification" below):

```bash
curl -X POST "http://localhost:1234/v1/insert?key=YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "certificate_id": "demo-captive-portal-002",
      "wifi": { "auth_mode": "open", "ssid": "Cafe Free WiFi" },
      "portal": {
        "domains": ["cafe-guest.test.local"],
        "pinned_spki_sha256": ["E4EXJeHy/eWlVhbLQ/74MneFJh7V1FWTO/UTzCTx4XM="]
      },
      "areas": [{
        "type": "MultiPolygon",
        "coordinates": [[[[8.5407,47.3759],[8.5427,47.3759],[8.5427,47.3779],[8.5407,47.3779],[8.5407,47.3759]]],[]]
      }],
      "areas_altitude": [[-10,50]],
      "not_valid_after": "2028-01-01T00:00:00Z"
    },
    {
      "certificate_id": "demo-eduroam-002",
      "wifi": {
        "auth_mode": "wpa2-enterprise",
        "ssid": "eduroam",
        "eap": "eap-tls",
        "auth_server_names": ["radius.eduroam-demo.test.local"],
        "trusted_ca_fingerprints": ["txb5FiQ2zAo/SIpWa0UN7n2jfmsYQDZIER2KIPxbmiU="]
      },
      "areas": [{
        "type": "MultiPolygon",
        "coordinates": [[[[8.5407,47.3759],[8.5427,47.3759],[8.5427,47.3779],[8.5407,47.3779],[8.5407,47.3759]]],[]]
      }],
      "areas_altitude": [[-10,50]],
      "not_valid_after": "2028-01-01T00:00:00Z"
    }
  ]'

curl -X POST "http://localhost:1234/v1/release?key=YOUR_KEY"
```

New certificate IDs (`-002`) rather than overwriting `-001`, since `/v1/insert`
only adds and it's unclear whether the server treats `certificate_id` as a
unique key - safer to leave the old ones in place than assume upsert
semantics. The old `-001` pair (no `ssid` set) will now fail the SSID filter
and stay silent, which is harmless.

None of the hash/fingerprint values are derived from real certificates -
they're `sha256("<descriptive string>")`, chosen only so benign/evil-twin
pairs match or mismatch consistently.

## What's actually simulated (be precise about this in the thesis writeup)

**For the fake entries, everything is simulated, not just the RADIUS server:**
- No AP, no RADIUS/AAA server, no real network exists for any fake entry.
- No real EAP/802.1X handshake ever happens - `SupplicantCertSource` is
  never invoked. The "presumed CA fingerprint" values are hardcoded strings
  standing in for what that class *would* report from a real handshake.
- No real TLS connection happens for the fake captive-portal entries either
  - `CertProbe` is bypassed via `verifyPresentedDomain`'s `presentedSpkiHash`
    parameter.
  - The point of faking a network is to skip *detection* and test only the
    *verification logic that consumes detection's output*. Don't read a
    fake entry's "VERIFIED" result as "we detected and verified a real
    network" - it's "we verified the comparison logic against a
    hypothetical detection result."

**For the real entries:**
- `WifiManager.scanResults()` genuinely lists nearby networks - real.
- The domain is **not** auto-detected. You still type it manually.

## Decisions made this pass, and why

1. **SSID pre-filter lives in `VerificationEngine`, not `PortalSession`.**
   `filterBySsid()` narrows GECKO's returned certificates to those whose
   `wifi.ssid` matches *before* `PortalSession`/`evaluateEnterpriseNetwork`
   ever sees them; when nothing matches, the result is `UNVERIFIED` without
   calling either at all. `PortalSession`'s own comparison logic is
   completely untouched - it just gets called with a smaller candidate set,
   or not at all. See `docs/app-design.md` for the advisor's rule this
   implements.
2. **`GeckoClient` can bind to a specific `Network`** (e.g. cellular,
   `NetworkObserver.cellularNetwork()`), via `Network.socketFactory` handed
   to OkHttp. `MainActivity` requests this for every check. Rationale: the
   WiFi network being evaluated must not be able to block or interfere with
   its own verification query - see `docs/app-design.md`'s "GECKO
   connectivity channel" section. Falls back to the default route if no
   cellular network is available (no SIM, or an emulator without a
   simulated radio).
3. **`ensureConsistency()` is now called from `GeckoClient.queryLocation()`**,
   alongside `verifyResponse()` - both failures map to `ProofFailure`. This
   was built and proven against a live server in an earlier pass but never
   actually wired into the query path until now. Flagging for attention: this
   is the least exhaustively-tested piece of the crypto path (confirmed
   against one real captured response, not a wide variety) - if unexpected
   `CONFLICT`s start appearing that weren't there before, check here first.
4. **`EnterpriseConfigurator.buildFrom()` fixed**: no longer requires
   `authServerCAs` (a field that never existed server-side); relies on
   `setDomainSuffixMatch` alone, validated against the device's normal
   trusted-root store instead of a pinned CA certificate. This is a real,
   meaningful weakening versus true CA pinning, but it's what's actually
   achievable from GECKO's real (fingerprint-only, not full-certificate)
   data. Root-based fingerprint comparison (`SupplicantCertSource` +
   `evaluateEnterpriseNetwork`) remains the stronger, evaluation-side check.
   **Not verified with a real test** - `WifiNetworkSuggestion.Builder()`'s
   chained calls return `null` under the plain-JVM test stub even with
   `isReturnDefaultValues = true` (confirmed via a real NullPointerException
   while writing this); testing the success path for real needs Robolectric
   or an instrumented test, neither set up in this project yet.
5. **Root-based traffic capture - and a real duplication mistake found and
   partially corrected.** `capability/PcapSniCapture.kt` (pure pcap-stream
   parsing, fully unit-tested with synthetic frames) was written without
   first checking whether something like it already existed - it did:
   `capability/PcapTrafficObserver.kt` already implements this same idea as
   a live, continuous, root-`tcpdump` capture integrated with the pre-existing
   `TrafficObserver`/`TrafficBus`/`Capabilities` architecture (`Capabilities.kt`
   picks `SupplicantCertSource`/`IptablesEnforcer` when rooted,
   `InferredCertSource`/`VpnDropEnforcer` otherwise - a whole fallback design
   this session hadn't noticed until now). `PcapTrafficObserver` is more
   complete than the redundant wrapper originally written here (which has
   been deleted) - streaming rather than fixed-duration-then-parse-once, and
   already wired into the existing capability-selection pattern - but its own
   `extractSni()`/header-parsing is NOT unit-tested. `PcapSniCapture.kt` was
   kept (not deleted) specifically because it has that test coverage;
   **`PcapTrafficObserver` should be reconciled with/refactored to delegate
   to it rather than left as a second, divergent, untested implementation -
   not done yet, flagged below.** `RootShell.execBytes()` was added alongside
   `exec()` for reading binary output (needed by pcap capture in general -
   binary data must not be read through a text `Reader`, which would corrupt
   it, the way `exec()`'s existing text-output commands are read).
6. **`PortalSessionTest.kt` fixed** - rewritten against the current data
   model (flat `PortalTLSIdentity.domains: List<String>` + one shared
   `pinnedSPKIHashes`, not the old per-domain `PortalDomain`/`PortalRole`
   types). `PortalSession`'s own logic was not touched.
7. `testOptions.unitTests.isReturnDefaultValues = true` added - needed once
   tests started touching `android.net.wifi.*` classes at all (see #4).

## What has to change for a real (non-prototype) version

### Real WiFi connection wiring
Tapping a real network still only selects it as a cache key - it doesn't
connect to it. A real version needs to actually join the network (WifiManager
connect APIs / `WifiNetworkSuggestion`), handle the associated permission and
UX flow, and only then run detection.

### Captive-portal domain detection - still not wired to the live flow

**Correction to an earlier version of this doc**: it previously suggested
`vpn/VpnGatekeeper.kt`/`VpnTrafficObserver` were superseded by the root path
and candidates for deletion. That was wrong - written before noticing
`capability/Capabilities.kt` already implements exactly the root/non-root
fallback pattern this project wants: `certificateSource()`/`enforcer()`
pick `SupplicantCertSource`/`IptablesEnforcer` when rooted,
`InferredCertSource`/`VpnDropEnforcer` otherwise. `PcapTrafficObserver`
(root, `TrafficObserver`) and `VpnTrafficObserver`/`VpnGatekeeper` (non-root,
same interface) are clearly meant to be that same pair for traffic
observation specifically - **`Capabilities` just doesn't have a
`trafficObserver()` factory method yet** (only `certificateSource()`/
`enforcer()`) to select between them the same way. Small, real gap - add
`fun trafficObserver(context): TrafficObserver = if (hasRoot) PcapTrafficObserver() else VpnTrafficObserver(context)`
rather than deleting either side.

What's actually still open:
- Neither `PcapTrafficObserver` nor `VpnTrafficObserver` is wired into
  `MainActivity`/`VerificationEngine` yet.
- `PcapTrafficObserver.extractSni()` isn't unit-tested; `PcapSniCapture.kt`
  (this pass) is the tested reference it should be reconciled against - see
  decision #5 above.
- Once wired: run capture while the "just joined, portal not yet resolved"
  window is open (see `docs/app-design.md`'s flow diagram), feeding each
  observed hostname into `verifyPresentedDomain(..., ssid = ...)` as it's
  captured via the existing `TrafficObserver.start(onHost)` callback shape.

### Enterprise/eduroam path
- `EnterpriseConfigurator.buildFrom()` is fixed (see decision #4 above) but
  unverified by a real test - needs Robolectric/instrumented testing or a
  real device.
- `SupplicantCertSource` has still never been run against a real handshake -
  needs a real rooted device on a real (test-controlled) 802.1X network.
- Once both are validated, `verifyEnterprise()`'s
  `observedAuthServerName`/`observedCaFingerprint` parameters should be fed
  from `SupplicantCertSource.presentedCertificate()`'s real output instead
  of `FakeDemoNetworks`.

### Still needs live verification against the server (couldn't do this pass - server was unreachable)
- Re-insert the `-002` demo certificates above (with `ssid` set) and confirm
  the fake network pairs actually produce VERIFIED/CONFLICT as designed.
- Recapture `geopki-fixtures/cert0.json` against a fully-populated real
  certificate (still the old minimal one missing `wifi`/`portal` - fine for
  what it tests, just not representative).
- Confirm `ensureConsistency()` being wired into `queryLocation()` (decision
  #3) doesn't produce unexpected `ProofFailure`s against real live traffic
  beyond the one response it was originally proven against.

### Smaller known gaps
- `GeckoClient.GeckoResponse.Success.unparsedCount` still isn't surfaced in
  the UI, only logged to stderr.
- Payment-portal handling (proposal task 6) - see `docs/app-design.md`.
