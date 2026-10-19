# Acceptance criteria

What the app must do, and the test that shows it. "Expected" is the resulting
`VerificationState`. Tests are JVM unit tests or golden tests (checked against
the geopki Go code); "HW" names a run from [experiments.md](experiments.md).

**Pass**: an automated test covers it. **HW**: also confirmed on the testbed.
**Pending**: the hardware run is still to do.

## A. Map server

| # | Criterion | Expected | Verified by | Status  |
|---|---|---|---|---------|
| AC1 | The app sends a location query to the map server and reads the answer | certificates parsed | `GeoQueryEncoderGoldenTest`, `GeckoClientCertificateParsingTest`; HW: any successful check | HW      |
| AC2 | No certificate is trusted before the answer's signature and proofs are checked | proofs checked, else rejected | `VerificationTest`, `Rfc6962MerkleTest`, `SmhTest`, `NodeTest`, `HashTest`, `ProofVerifierGoldenTest`, `LiveFixtureTest` | Pass    |
| AC3 | A tampered answer (bad proof, withheld certificate) or a wrong pinned server key counts as an attack, not as "nothing registered" | `CONFLICT` | `verify_mapsProofFailureToConflict`, `r1_proofFailure_isConflictAndNothingJudged`, `strippedResponse_isDetectedAsWithholding`; HW: R1, R3 | Pending |
| AC4 | An unreachable map server gives no warning | `UNREACHABLE` | `verify_mapsUnreachableToUnreachableState`, `unreachableServer_isUnreachableAndNothingJudged`, `verify_doesNotCacheUnreachableResults`; HW: S7 | HW      |

## B. Judging a network

| # | Criterion | Expected | Verified by | Status |
|---|---|---|---|---|
| AC5 | A network showing the key registered for its SSID here | `VERIFIED` | `genuineChain_isVerified_andOnlyHttpsHopIsJudged`, `verify_delegatesSuccessToPortalSession`, `genuineDelegateAfterPrimary_isVerified`; HW: P0, S0 | HW |
| AC6 | A registered domain showing a different key | `CONFLICT` | `s1_clonedPortalWithAttackerKey_isConflict`, `verifyPresentedDomain_mismatchedSpkiHashIsConflict`, `s4_delegateWithAttackerKey_isConflict`; HW: S1, S4a | HW |
| AC7 | On a registered SSID, a domain that matches nothing registered here (see [design decision 1](design-decisions.md)) | `CONFLICT` | `s2_lookalikeDomainOnRegisteredSsid_isConflictUnderExclusivity`; HW: S2 | HW |
| AC8 | The same, but the SSID is marked `ssid_shared` or no SSID was given | `UNRECOGNIZED` (silent) | `sharedSsid_keepsUnknownNetworkUnrecognized`, `unregisteredDomain_withoutSsidScope_staysUnrecognized` | Pass |
| AC9 | Nothing registered for this SSID here, even if other networks are registered nearby | `UNVERIFIED` (silent) | `unregisteredSsid_isUnverified`, `finalPlainHopNotJudgedWhenAsked_noJudgedHops_isUnverifiedWithoutQuery`; HW: N3 | Pending |
| AC10 | A registered portal domain served without TLS | `CONFLICT` | `s6_registeredPortalServedOverPlainHttp_isConflict`; HW: S6 | HW |
| AC11 | A certificate outside its validity period is ignored | treated as absent | `verify_ignoresExpiredCertificates`, `certificateBeforeNotValidBefore_isIgnored` | Pass |
| AC12 | A certificate whose area doesn't reach the device is ignored | treated as absent | `g2_certificateRegisteredForAnotherArea_isDroppedAndRegisteredSsidGoesSilent`, `GeoAreaTest`; HW: L2 | Pending |
| AC13 | A domain or server without pinned keys never verifies (the app does no Web PKI) | not `VERIFIED` | `s9_domainWithoutPins_neverVerifies`, `serverWithoutPins_neverVerifies` | Pass |
| AC14 | A network advertising a security type that isn't registered for its SSID (e.g. an open copy of a WPA2 network) | `CONFLICT` | `a1_openCloneOfEnterpriseSsid_isConflict`, `authModeMismatch_isConflictEvenIfThePortalVerifies`, `AuthModesTest`; HW: N2, E0o | Pending |
| AC15 | A registered secured network with nothing else to compare (no portal, no server checked) | `REGISTERED` | `genuineEduroamAdvertisement_isRegistered`, `wpa3TransitionAdvertisement_isRegistered`; HW: N1, E0 | Pending |
| AC16 | A certificate with a `critical` feature the app doesn't support gives neither `VERIFIED` nor `CONFLICT` | `UNVERIFIED` (silent) | `onlyUnknownCriticalFeature_isSilentUnverified`, `conflictIsWithheldWhileAnotherCertificateForTheSsidIsUnevaluable` | Pass |

## C. Enterprise (eduroam)

| # | Criterion | Expected | Verified by | Status |
|---|---|---|---|---|
| AC17 | The RADIUS server from the handshake is compared with the registered one: genuine verifies, a rogue server (own CA, or the genuine CA appended to its chain) is flagged | `VERIFIED` / `CONFLICT` | `localUser_genuineServerViaCaPin_isVerified`, `localUser_rogueCaSameName_isConflict`, `capturedRogueChainWithTheGenuineCaAppended_isConflict`; HW: E1 | Pending |
| AC18 | A visitor, or a user whose realm is unknown, gets no warning when the server doesn't match | `REGISTERED` | `visitorInFederation_isRegisteredNotConflict`, `capturedRogueChain_withoutRealm_isOnlyRegistered` | Pass |

## D. Captive-portal chain

| # | Criterion | Expected | Verified by | Status |
|---|---|---|---|---|
| AC19 | A chain that starts on the genuine portal and then moves to the attacker is flagged at the switch | `CONFLICT` at the switch | `s3_relayToGenuineThenSwitch_isConflictAtTheSwitch`; HW: S3, S3m | HW |
| AC20 | Hops are judged in order; only HTTPS hops and a final plain-HTTP hop count | first `CONFLICT`, else the last judged hop | `genuineChain_isVerified_andOnlyHttpsHopIsJudged`, `PortalSessionTest` | Pass |
| AC21 | A failed TLS connection to a registered portal domain counts as a wrong key; so does one to an unregistered domain on a registered SSID | `CONFLICT` | `failedTlsOnRegisteredPortalDomain_isConflict`, `failedTlsOnUnregisteredDomainOnRegisteredSsid_isConflict`; HW: T1 | Pending |
| AC22 | Every check starts fresh: an earlier result never carries over, also not after rejoining | no stale result | `repeatedChecks_startFreshSessions`, `onNetworkChanged_resetsSessionAndCache` | Pass |

## E. Known limits

Correct results that show where GECKO's protection ends.

| # | Criterion | Expected | Verified by | Status |
|---|---|---|---|---|
| AC23 | An attacker relaying the genuine portal unchanged is verified: GECKO checks the portal, not the connection ([design decision 3](design-decisions.md)) | `VERIFIED` | HW: S0 | HW |

## Still to do

- **Hardware runs** for AC3, AC4, AC9, AC12, AC14, AC15, AC17, AC21. See
  [experiments.md](experiments.md).
- **Portal server tests.** The Go code in `portal/` (relay, switch, plain HTTP)
  has only smoke checks, no automated tests.
