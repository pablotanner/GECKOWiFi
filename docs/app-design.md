# App design

Target design for the real app, synthesized from `docs/Proposal.pdf` (the
official MSc thesis brief) and the advisor conversations that followed it.
This is the *intended* design - `docs/prototype-decisions.md` tracks what the
current rough prototype actually does and where it deliberately falls short
of this. Update this file when the design changes; update
`prototype-decisions.md` when the prototype's shortcuts change.

## The problem, per the proposal

Two distinct WiFi trust failures, both stemming from the same root cause: a
CA (or a captive portal's own domain/HTTPS identity) can prove *who* a
certificate belongs to, but nothing ties that identity to *this specific
physical network, at this specific location*. GECKO fixes that by linking
geographic position to registered certificates.

- **Eduroam-style (802.1X/EAP)**: the RADIUS/AAA server's certificate is
  often self-signed or CA-issued in a way the device can't validate as
  "correct for this location," even if the chain itself is technically
  well-formed.
- **Captive portal**: the portal's HTTPS identity can be validated by normal
  Web PKI, but that only proves the domain owns that TLS certificate - not
  that the domain is the legitimate portal for *this* physical network.

## The decision rule (per advisor, confirmed 2026-09)

> The app should warn only when the user tries to connect to a network whose
> SSID has a GeoCert registered at this location, and what that network
> actually presents (captive portal domain, or EAP auth server identity)
> doesn't match what's registered for that SSID. That's the evil-twin
> signature. Everything else stays silent.

Two important consequences:

1. **SSID is a lookup key, not a trust anchor.** It's used to select *which*
   registered certificate is relevant to check against - the actual trust
   decision remains the cryptographically-verified domain/key match. This
   does not contradict "SSID/BSSID are never trust anchors": we never
   conclude "this SSID appeared, therefore trust it," only "this SSID
   matches a registered claim, so hold whatever's presented to that claim's
   standard."
2. **Most verification outcomes should never reach the user.** An
   unregistered network (the large majority, per the PARTIAL-adoption
   assumption) produces no warning at all - not `UNVERIFIED`, not
   `UNRECOGNIZED`, nothing. Those states still get computed internally
   (useful for logs and for the "refine later with other heuristics, e.g.
   encryption type" the advisor left open), they just aren't surfaced.

**Only `CONFLICT` is user-facing.** Nothing else interrupts the user.

## GECKO connectivity channel (per advisor: focus on 1 and 2 for now; 3 later if time permits)

1. **Cellular, out-of-band (primary).** Query GECKO over cellular data, not
   over the WiFi network being evaluated. This isn't just a fallback for
   convenience - it closes a real attack: if the query went out over the
   WiFi itself, an evil twin (or any real captive portal, which routinely
   firewalls everything except its own login server by design) could simply
   block or interfere with the verification query itself. A blocked query
   comes back `UNREACHABLE`, which correctly does not warn - so an attacker
   who can prevent the phone from asking GECKO anything defeats the whole
   mechanism without needing to break any cryptography. Querying over
   cellular means the network under evaluation has no path to interfere.
2. **Over the WiFi itself (fallback).** Relevant once a portal login has
   already succeeded and general internet access resumes, or when no
   cellular radio/data is available.
3. **Pre-populated offline map of GECKO-protected APs (deferred).** Explore
   later if time permits; not part of the current design.

## The two mechanisms, and where root fits

Root access is assumed available (confirmed by advisor) - this resolves
`capability/`'s root-based classes (`SupplicantCertSource`, `RootShell`,
`IptablesEnforcer`) from "speculative extras" to "the intended mechanism,"
not a fallback.

### Eduroam / enterprise (802.1X)

Two complementary mechanisms, not competing ones:

- **Prevention (no root)**: fetch the location's registered enterprise
  certificate(s) proactively (before/independent of any connection attempt)
  and use `EnterpriseConfigurator.buildFrom()` to build a
  `WifiNetworkSuggestion` with the CA/domain-suffix pinned from GECKO data.
  Android's own supplicant then refuses a bad handshake natively - the
  connection to an evil twin simply never completes. Currently broken
  against the real server schema (`authServerCAs` field doesn't exist
  server-side - see `prototype-decisions.md`); needs fixing before this can
  do anything.
- **Evaluation (root)**: `SupplicantCertSource` reads what was *actually*
  presented during a real EAP-TLS handshake, for logging/comparison/thesis
  evaluation purposes - useful even when prevention worked, and essential
  when it didn't (e.g. the suggestion wasn't adopted, or prevention isn't
  built yet for a given case).

### Captive portal

No equivalent native "refuse the handshake" mechanism exists for a web-layer
identity, so this path is necessarily observe-then-compare:

- Root-based traffic capture (`tcpdump`/raw capture on the WiFi interface,
  scoped to TCP:443, reusing `TlsSniParser` to extract the requested
  hostname) is the primary mechanism, now that root is confirmed assumed.
  See the chat history around 2026-09-12 for the mechanics (pcap stream ->
  strip Ethernet/IP/TCP headers -> `TlsSniParser.extractSni()`) and for why
  `VpnService`- and `AccessibilityService`-based alternatives were
  considered and set aside as secondary/comparison material rather than the
  primary path.

## App flow, end to end

```
Device associates with a WiFi network (SSID S)
        │
        ▼
Get current location (LocationProvider)
        │
        ▼
Query GECKO for this location - OVER CELLULAR (see channel design above)
        │
        ▼
Filter returned certificates to those whose wifi.ssid == S
        │
        ├─ none match ──────────────────────────► stop. No warning, ever,
        │                                          regardless of what else
        │                                          is registered nearby.
        │
        └─ some match
                │
                ▼
        Which case is this network?
                │
    ┌───────────┴────────────┐
    │                         │
Enterprise (802.1X)      Captive portal (open + web login)
    │                         │
    ▼                         ▼
SupplicantCertSource      Root-based traffic capture, scoped to this
reads the actual          network's session, observing TLS SNI as the
presented RADIUS cert     portal login flow proceeds (may cross several
    │                     domains - anchor/delegate handles that)
    │                         │
    └───────────┬─────────────┘
                ▼
    Compare observed identity against the SSID-matched
    certificate(s) - PortalSession (portal case) or
    evaluateEnterpriseNetwork (enterprise case), unchanged
    internally, just now scoped to a pre-filtered candidate set
                │
    ┌───────────┴────────────┐
    │                         │
Matches                  Doesn't match
    │                         │
    ▼                         ▼
Silent (log VERIFIED     WARN - this is the evil-twin signature.
internally; no user           Interrupt the user, ideally before any
interruption)                 credentials/payment info is entered.
```

## States: computed vs. user-facing

`VerificationState` keeps its full taxonomy (`VERIFIED`, `UNVERIFIED`,
`UNRECOGNIZED`, `CONFLICT`, `UNREACHABLE`) - none of this needs to shrink,
since the richer internal state remains useful for logging and for future
heuristic refinement (advisor's own words: "we can refine that later with
other heuristics, such as type of encryption"). What changes is purely a UI
policy: **only `CONFLICT` (post-SSID-filter) actively interrupts the user.**
Everything else is silent by default, inspectable on demand (e.g. a history
screen) but never an alert.

## Where this is implemented without touching protected files

The SSID filter is a pre-filter added in `VerificationEngine` (editable),
running *before* candidates reach `PortalSession`/`evaluateEnterpriseNetwork`
- `PortalSession`'s own comparison/state logic is untouched, it just gets
called with a smaller, SSID-scoped candidate list, or not called at all when
nothing matches. The "only warn on CONFLICT" behavior is a UI-layer policy
decision, not a change to verification logic.

## Explicitly deferred / open per the proposal

- **Payment portals** (proposal task 6): a legitimate payment-processor
  redirect mid-session (Stripe, a hotel chain's own gateway) looks identical
  to the bait-and-switch CONFLICT signature under today's logic. Needs a
  design decision - possibly an allow-listed-payment-processor field on the
  GeoCertificate model - before this stops producing false positives at any
  paid portal.
- **System WiFi settings integration** (proposal task 7): `WifiNetworkSuggestion`
  is the realistic vehicle (suggestions surface in the system's own "Connect
  to network" flow with an attribution) - there's no API for a third-party
  app to inject UI into Settings directly.
- **GUI mockups + HCI validation with the Bonn group** (proposal tasks 2-3):
  not started. The "only warn on CONFLICT" rule above at least gives a
  concrete starting point: most of the UI's job is being quiet correctly,
  not displaying rich state.
- **Pre-populated offline GECKO map** (advisor's option 3): deferred.

## Next steps available now (no device needed)

See the chat history for full rationale on each:

1. Wire cellular-bound GECKO queries (`NetworkObserver.cellularNetwork()` +
   binding `GeckoClient`'s OkHttp calls to that `Network`'s socket factory).
2. Add the SSID pre-filter to `VerificationEngine` (requires threading SSID
   through the query call, and querying GeoCertificate by `wifi.ssid`).
3. Fix `EnterpriseConfigurator`'s `authServerCAs` bug.
4. Write and unit-test the pcap-stream parsing logic for root-based traffic
   capture (sample bytes in, extracted SNI out) - defer only the live
   root-shell execution until the device arrives.
5. Apply the "only warn on CONFLICT" policy in the UI.
6. Smaller carried-over items from `prototype-decisions.md`: recapture a
   fully-representative `LiveFixtureTest` fixture, fix `PortalSessionTest.kt`,
   wire `ensureConsistency()` into `GeckoClient`.
