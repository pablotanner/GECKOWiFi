# Design decisions

The choices that decide how the app judges a network, and what they assume.

## 1. One SSID per area belongs to one operator

**Question.** A GeoCertificate is registered for an SSID in an area. Another
network with the same SSID shows up there and matches nothing registered. Is
that just unknown, or an impersonation?

**Decision.** An impersonation: `CONFLICT`. An SSID registered in an area is
claimed there. A certificate can opt out with `ssid_shared: true` for a generic
name used by several operators in one place (a mall where many shops use
`FreeWiFi`); then an unknown network stays `UNRECOGNIZED`.

**Why.** In practice an SSID is unique within one place. GECKO only needs that
local uniqueness: `eduroam` at ETH and `eduroam` at MIT are in different areas
and never collide. Without this rule, an evil twin on the same SSID with its
own lookalike domain (S2) would go unnoticed.

**What it assumes.**
- A large deployment (campus, hotel, mesh network) is one operator with one
  certificate: a wide area and many access points. A rogue access point on
  the same SSID with a different identity is caught.
- The operator registers **all** its portal domains and an area covering its
  **whole** site. Otherwise its own network is flagged.
- An eduroam visitor checks their home institution's server, which the visited
  site can't register. The app shows `REGISTERED` (site and auth mode match)
  instead of a warning.

**Where it is enforced.**
- App: `VerificationEngine.applyExclusivity` turns `UNRECOGNIZED` into
  `CONFLICT` when the check was made for an SSID and no certificate sets
  `ssid_shared`. A lookup without an SSID (a typed domain) stays `UNRECOGNIZED`.
- Map server (geopki, not this repo): must refuse a certificate whose SSID and
  area overlap one from a different `subject.id`, and allow it for the same
  subject (key rotation). Not implemented there yet.

**Open.** How to test area overlap (partial, nested, shared borders).

## 2. Only the rightful operator can register an area

**Question.** An attacker registers its own valid certificate for the victim's
SSID and area. The app trusts any validly signed certificate, so the evil twin
would show `VERIFIED`. What prevents this?

**Assumption.** The registration authority only issues a certificate for an
area to someone who owns it or is authorized there, and that operator can be
held accountable. This is the Web PKI model (a CA checks domain ownership)
applied to physical places. The attacker can't register the victim's area, and
a wrong registration can be traced and revoked.

**Why this belongs to registration, not the app.** The app's checks prove a
certificate is in the signed map. They can't tell whether it should have been
admitted. The app is only as trustworthy as the registration policy.

**Remaining risk.** A mistaken or compromised authority, the same risk as a
rogue CA. Handled by revocation and accountability, not by the app.

**Open.**
- How the first claim for an area is checked (domain ownership, location proof,
  manual vetting, or first come first served with its race).
- Binding to an operator, not to BSSIDs: BSSIDs can be spoofed and break
  deployments with many access points.

## 3. GECKO checks the portal, not the connection

GECKO checks the identity of the captive portal (or the RADIUS server), not the
data path. If an attacker relays the genuine portal unchanged (S0), the check
correctly says `VERIFIED`, although the attacker still sees all other traffic.
Protecting that traffic is the job of TLS, a VPN and so on. The app says this
next to every verified result.

## 4. Root stands in for system privileges

GECKO is meant to be part of the operating system's WiFi stack. The prototype
is an app, so root takes the place of system privileges (for example reading
the RADIUS server's certificates from `wpa_supplicant`). There is no fallback
for unrooted devices, such as a VPN-based one.
