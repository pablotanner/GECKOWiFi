# Project: GECKO WiFi Verification — Android Client

## What this is
Master's thesis (ETH Zurich) implementing an Android app that verifies WiFi
networks against evil-twin and captive-portal attacks using GECKO, a
geographic PKI. The idea: when connecting to WiFi, query a GECKO map server
for the certificates authorized at the user's physical location, and check
whether the network/portal presenting itself matches. Location-bound trust,
on top of ordinary certificate validation.

## Background you need
- GECKO is a geographic PKI (netsec-ethz). A GeoCertificate binds a physical
  space (a frustum: polygon + altitude range) to a domain-style identifier and
  a public key. The map server answers SPATIAL queries: "what certificates
  claim this location?" — NOT domain lookups.
- The reference Go implementation is netsec-ethz/geopki. I've copied its
  reusable `.go` files into this project for porting (see below).
- Scope decision: we implement the SPATIAL path only (geo map server). Domain
  lookup (domain map server) is explicitly out of scope / future work.
- Adoption assumption: PARTIAL. Most networks won't be registered. So the
  default posture is ADVISORY (warn, don't block); strict/enforcement mode is
  opt-in.

## Verification states (the core logic)
The client resolves each check to one of:
- VERIFIED — presented key matches a GeoCert authorized here
- UNVERIFIED — nothing registered at this location
- UNRECOGNIZED — certs exist here but none names this domain
- CONFLICT — a registered domain presented an unexpected key (positive evidence
  of attack); also a failed cryptographic proof
- UNREACHABLE — couldn't query GECKO (distinct from UNVERIFIED — do not conflate)

There is also sequence-aware logic (PortalSession): once a "primary" domain
verifies and becomes the session anchor, an unlisted domain appearing later in
the same session is CONFLICT, not UNVERIFIED. This catches bait-and-switch
redirects. This is a key contribution — preserve it.

## Current state
- Kotlin data models exist: GeoCertificate, WiFiIdentity, PortalTLSIdentity
  (with PortalDomain + role: primary/delegate), GeoCertArea, VerificationState,
  VerificationResult.
- Working (unit-tested, pure JVM): PortalSession, DecisionCache, SpoofSignals.
- Stubbed/partial: GeckoClient (was built against a JSON shim that doesn't
  exist), VerificationEngine.
- Not started: the protobuf integration and the ported crypto/bitstring logic.
- Platform components exist but untested on hardware: LocationProvider (uses
  LocationManager, NOT fused — must work on AOSP emulator), NetworkObserver,
  EnterpriseConfigurator, in-memory Stores.
- No UI yet beyond a stub MainActivity. No VpnService yet (deferred).
- Root access is assumed available (supervisor's call), but the current work
  doesn't depend on it.

## The server
- netsec-ethz/geopki Go server, running locally, reachable from the emulator at
  http://10.0.2.2:1234.
- Endpoints: `POST /v1/query` (and `/v1/query?c` to include full certs) takes a
  binary protobuf Request (XYBitStrings + min/max altitude), returns binary
  protobuf Response (nodes, signed map head, inclusion proof, optional certs).
  `GET /v1/public-key` returns the server's DER public key.
- There is NO JSON endpoint. Everything is protobuf. Earlier client code
  wrongly assumed JSON — that needs fixing.

## Ported Go files (in this repo, to be transcribed to Kotlin under geopki/)
From netsec-ethz/geopki `pkg/`:
- bitstring/bit_string_pair.go, raw_bit_string_pair.go → coordinate→bitstring
- comm/query.go → client-side query build + response parse
- crypto/verification.go → verify server response (THE critical one)
- crypto/smh.go, node.go, hash.go, certificate.go → supporting crypto
- proto/request.proto, response.proto → protobuf schema
  Reference client flow: cmd/geopki-client. Also cmd/geopki-client-wasm — it uses
  Google S2 instead of GDAL, which matters because GDAL is native C++ and CANNOT
  run on Android. Prefer the S2 approach (S2 has a Java port).

## What I want done now (in order)
1. Get protobuf codegen working: place request.proto/response.proto in
   app/src/main/proto/, add Wire (or protobuf-lite), round-trip a raw
   request/response against the live server and log it.
2. Port pkg/bitstring → geopki/bitstring/ (Kotlin). Test against Go outputs for
   known coordinates — byte-for-byte parity is the bar.
3. Port pkg/crypto (hash → node → smh → verification) → geopki/crypto/. Test
   against a known-good captured server response.
4. Rewrite GeckoClient: queryLocation(bitStrings, minAlt, maxAlt) over protobuf,
   fetch+cache /v1/public-key, verify the proof before returning success. Add a
   ProofFailure outcome distinct from Unreachable and empty-success.
5. Rewrite VerificationEngine.verify() to be location-driven (lat/lng/alt/radius
   in), map ProofFailure→CONFLICT, keep PortalSession usage unchanged.
6. Wire everything in di/AppModule.kt.

## Flow
    MainActivity.runVerification()
        → VerificationEngine.verify()                    [verification/VerificationEngine.kt]
            → DecisionCache.get()                         (cache check first)
            → GeoQueryEncoder.encodeQuery() / altitudeBounds()   (lat/lng/radius → bitstrings)
            → GeckoClient.queryLocation()                 [data/remote/GeckoClient.kt]
                → fetches server's public key (/v1/public-key)
                → builds a geopki.Request (Wire-generated protobuf type)
                → POSTs to /v1/query?c
                → decodes the geopki.Response
                → geopki.crypto.verifyResponse()          [geopki/crypto/Verification.kt]
                    → SignedMapHead.verify()               [geopki/crypto/Smh.kt]      (signature check)
                    → Node tree rebuild + root hash check   [geopki/crypto/Node.kt]
                    → per-certificate JSON decode          (→ GeoCertificate)
            → CertProbe.fetchCertificate() + spkiHash()    [verification/CertProbe.kt]  (only on Success)
            → PortalSession.observe()                      [verification/PortalSession.kt]  (the actual decision)
            → DecisionCache.put()
        → MainActivity displays the VerificationResult