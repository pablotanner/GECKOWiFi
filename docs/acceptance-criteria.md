# Acceptance criteria

What the GECKO WiFi client must do, as testable criteria, each mapped to the
evidence that verifies it. "State" is the resulting [VerificationState].
Evidence is a unit test (JVM), a golden test (cross-checked against the geopki
Go code), or a hardware scenario from [experiments.md](experiments.md).

Status: **Pass** = covered by an automated test; **HW** = also confirmed on the
testbed; **Pending** = not yet run on hardware; **Limitation** = documented
behaviour that is correct within GECKO's scope but does not stop the attacker.

## A. Reaching and trusting the map server

| # | Criterion | Expected | Verified by | Status |
|---|---|---|---|---|
| AC1 | The client encodes a location query, reaches the map server, and decodes the protobuf response | query sent, certificates parsed | `GeoQueryEncoderGoldenTest`, `GeckoClientCertificateParsingTest`; HW: `curl` 404 + a successful check | HW |
| AC2 | A response is cryptographically verified (signature, inclusion proof, consistency) before any certificate is trusted; an unverified response is never returned as success | proofs checked, else rejected | `VerificationTest`, `Rfc6962MerkleTest`, `SmhTest`, `NodeTest`, `HashTest`, `ProofVerifierGoldenTest`, `LiveFixtureTest` | Pass |
| AC3 | A tampered proof or wrong pinned server key is treated as hostile, not as "nothing registered" | `CONFLICT` | `VerificationEngineTest.verify_mapsProofFailureToConflict`, `PortalHopsVerificationTest.r1_proofFailure_isConflictAndNothingJudged`; HW: R1, 7b | Pending (HW) |
| AC4 | An unreachable map server does not raise a warning (fail-open) | `UNREACHABLE` | `verify_mapsUnreachableToUnreachableState`, `unreachableServer_isUnreachableAndNothingJudged`, `verify_doesNotCacheUnreachableResults`; HW: S7 | Pending (HW) |

## B. Deciding a network (the five states)

| # | Criterion                                                                                                         | Expected | Verified by | Status |
|---|-------------------------------------------------------------------------------------------------------------------|---|---|---|
| AC5 | A network presenting the key registered for its SSID at this location verifies                                    | `VERIFIED` | `genuineChain_isVerified_andOnlyHttpsHopIsJudged`, `verify_delegatesSuccessToPortalSession`, `genuineDelegateAfterPrimary_isVerified`; HW: P0, S0 | HW |
| AC6 | A registered domain presenting the wrong key is a conflict                                                        | `CONFLICT` | `s1_clonedPortalWithAttackerKey_isConflict`, `verifyPresentedDomain_mismatchedSpkiHashIsConflict`, `s4_delegateWithAttackerKey_isConflict`; HW: S1, S4a | HW |
| AC7 | On a *registered* SSID, a network serving a domain that matches nothing registered there is impersonation, not merely unknown (SSID exclusivity, see [design-decisions.md](design-decisions.md)) | `CONFLICT` | `s2_lookalikeDomainOnRegisteredSsid_isConflictUnderExclusivity`; HW: S2 | Pending (HW) |
| AC7b | Without an SSID assertion (a typed-domain lookup), the additive semantics hold: an unknown domain where certs exist for other SSIDs is unknown, not a warning | `UNRECOGNIZED` (silent) | `unregisteredDomain_withoutSsidScope_staysUnrecognized` | Pass |
| AC8 | Nothing registered for this SSID/location raises no warning                                                       | `UNVERIFIED` (silent) | `unregisteredSsid_isUnverified`, `finalPlainHopNotJudgedWhenAsked_noJudgedHops_isUnverifiedWithoutQuery`; HW: N6 | Pending (HW) |
| AC9 | A registered portal domain served without TLS is a downgrade                                                      | `CONFLICT` | `s6_registeredPortalServedOverPlainHttp_isConflict`; HW: S6 | Pending (HW) |
| AC10 | An expired certificate is ignored (not used to anchor or verify)                                                  | treated as absent | `verify_ignoresExpiredCertificates` | Pass |
| AC11 | A domain with no pinned keys never verifies (fail closed; the app does no Web PKI)                                | not `VERIFIED` | `s9_domainWithoutPins_neverVerifies` | Pass |
| AC12 | An unregistered SSID never surfaces a warning even if other certificates exist at this location (SSID pre-filter) | `UNVERIFIED` (silent) | `unregisteredSsid_isUnverified` | Pass |

## C. Captive-portal chain and bait-and-switch

A portal that looks genuine and then redirects to a malicious page.

| # | Criterion | Expected | Verified by | Status |
|---|---|---|---|---|
| AC13 | A chain that starts on the genuine portal and then moves to an attacker domain is flagged at the switch | `CONFLICT` at the switching hop | `s3_relayToGenuineThenSwitch_isConflictAtTheSwitch`; HW: S3 (hop 4), S3m | HW |
| AC14 | The chain is judged hop by hop; only HTTPS hops (and a final plain-HTTP hop) are judged, in order | first `CONFLICT` wins, else last judged hop | `genuineChain_..._andOnlyHttpsHopIsJudged`, `PortalSessionTest` | Pass |
| AC15 | A failed TLS handshake on a registered portal domain counts as a wrong key; on a registered SSID, a failed hop to an unregistered domain is also impersonation (exclusivity) | both → `CONFLICT` | `failedTlsOnRegisteredPortalDomain_isConflict`, `failedTlsOnUnregisteredDomainOnRegisteredSsid_isConflict` | Pass |
| AC16 | Each check starts a fresh session; a previous check's anchor never changes a later verdict | no stale anchor | `repeatedChecks_startFreshSessions`, `onNetworkChanged_resetsSessionAndCache` | Pass |

## D. Documented limitations (correct verdict, attacker not stopped)

These are expected results that show the boundary of what GECKO guarantees, not
failures. They belong in the limitations chapter.

| # | Criterion | Expected | Verified by | Status |
|---|---|---|---|---|
| AC17 | A transparent relay of the genuine portal verifies — GECKO authenticates the portal, not the link | `VERIFIED` | HW: S0 | HW |
| AC18 | An attacker with its own certificate registered for the same SSID at this location is indistinguishable from a second genuine operator | `VERIFIED` | `f1_attackerWithOwnCertificateForSameSsid_isVerified`, `f1s_switchToAnotherRegisteredPrimaryMidChain_reanchorsAndIsVerified` | Pass |
| AC19 | A second certificate for the same SSID does not create a false positive against the genuine one | `VERIFIED` | `x1_secondCertificateForSameSsid_doesNotBreakGenuineChain` | Pass |
| AC20 | A switch timed after the one-shot check (timer or User-Agent) is not caught by a single probe | single probe `VERIFIED`; re-probe catches the timer case | HW: S5a, S5b | Pending (HW) |
| AC21 | A benign redirect to a processor the operator did not register is a false positive | `CONFLICT` | `p2_redirectToUnlistedPaymentProcessor_isConflict` | Pass |

## Gaps to close

- **Hardware runs** still pending for AC3, AC4, AC7, AC8, AC9, AC20 (unit-tested,
  not yet run on the testbed). See [experiments.md](experiments.md).
- **Portal-server scenario tests** (the `portal/` Go code for relay, switch,
  http-only) have no automated tests yet — only the smoke checks. These would
  back AC13/AC15 at the server level.
