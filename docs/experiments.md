# Experiments

Each scenario is run several times. For every run, keep the app's check log
(`adb pull …/portal-checks/`) and the portal server's log (`portal/logs/`).
The setup is described in [lab-setup.md](lab-setup.md).

## Without a captive portal

Baseline before the portal existed: the routers' own HTTPS admin pages stood in
for the portal, and the domain (`gecko-a.lab`) was entered by hand.

| # | Scenario | Expected | Result |
|---|---|---|---|
| 1 | Router A | `VERIFIED` | ✅ |
| 2 | B relays to A (no DNS override on B) | `VERIFIED` | ✅ |
| 3 | B answers for `gecko-a.lab` with its own key | `CONFLICT` | ✅ |
| 4 | B blocks the map server | `UNREACHABLE` | |
| 5 | Domain not in any certificate | `UNRECOGNIZED` | ✅ |
| 6 | SSID without a certificate | `UNVERIFIED` | |
| 7 | Tampered proof / wrong server key | `CONFLICT` | |

## With a captive portal

The app detects the portal itself. Router A runs the genuine portal; router B
will run the attacker scenarios.

| # | Scenario | Expected | Result |
|---|---|---|---|
| P0 | Genuine portal on A | `VERIFIED` | ✅ |
| P1 | A, already logged in (registered domain checked directly) | `VERIFIED` | |
| S1 | Clone: same domain, attacker's key | `CONFLICT` | |
| S2 | Lookalike domain (`gecko-a-login.lab`) | `UNRECOGNIZED` | |
| S3 | Relay to the genuine portal, then redirect to the attacker's page | `CONFLICT` at the switch | |
| S4 | Payment page (delegate) with the attacker's key | `CONFLICT` | |
| S5 | Switch only after the check | not detected (needs continuous monitoring) | |
| S6 | Login page over plain HTTP only | `CONFLICT` | |
| S7 | Block the map server | `UNREACHABLE` | |
| S8 | Spoofed Captive Portal API (RFC 8908) | not implemented | |

S1–S4 and S6 are also covered by unit tests (`PortalHopsVerificationTest`).

## Next steps

1. Attacker portal on router B, with configurable scenarios.
2. An independent uplink for B, needed for S7.
3. A rootable device for continuous monitoring (S5).
