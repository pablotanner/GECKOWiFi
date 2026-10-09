# Design decisions

Decisions that shape how the client judges a network, and the assumptions they
rest on. Each entry: the question, the options, the decision, and what it costs.
Open sub-questions are called out explicitly.

## 1. SSID exclusivity

**Question.** When a GeoCertificate is registered for an SSID at a location,
what does another network claiming the same SSID there mean? Is registration
*additive* (it only asserts the registered identity; anything else is merely
"unknown") or *exclusive* (the SSID at that area is claimed, so a non-matching
network is impersonation)?

**Decision.** Exclusive, by default. If an SSID is registered at a location, a
network on that SSID that matches nothing registered there is a `CONFLICT`, not
a silent `UNRECOGNIZED`. A later `ssid_exclusive: false` flag could let a
registration opt back into additive semantics for genuinely shared generic
SSIDs (a mall where many tenants use `FreeWiFi`); not implemented yet.

**Why.** SSIDs are treated as locally unique in practice — within one place you
do not see two venues sharing an SSID. GECKO already scopes by location, so it
needs only *local* uniqueness, not global: `eduroam` at ETH and `eduroam` at
MIT are different `(SSID, area)` tuples and never collide. Exclusivity closes
the gap where an evil twin using its *own* domain (a lookalike, S2) was silent.

**Cost / assumptions.**
- A large single-operator deployment (campus, hotel, mesh, eduroam within one
  institution) is **one identity over many radios** — one GeoCert with a wide
  polygon, many BSSIDs — so exclusivity does not break it; it even catches a
  rogue same-SSID AP presenting a different identity.
- An operator must register **all** their portal domains and a polygon covering
  their **whole** footprint, or they false-positive their own network. Exclusive
  and accurate-area go together.
- Genuinely shared generic SSIDs by distinct operators in one area are the one
  case exclusivity gets wrong; that is what the future `ssid_exclusive: false`
  opt-out is for.
- eduroam **roaming** visitors are out of scope: a visitor validates their home
  institution's RADIUS identity, which the visited area's GeoCert cannot name.

**Where enforced.**
- Client: `VerificationEngine.applyExclusivity` escalates `UNRECOGNIZED` to
  `CONFLICT` in the SSID-scoped path (candidates were pre-filtered to the SSID,
  so reaching the judge proves the SSID is registered here). The non-SSID path
  (a typed-domain lookup, `ssid = null`) keeps additive `UNRECOGNIZED`.
- **Registration (geopki, not this repo) — open requirement:** the map server
  must refuse a new GeoCert whose `(SSID, area)` **polygon overlaps** an
  existing one. Without this the client rule and the registry disagree.

**Open sub-questions.**
- Geometric overlap at registration: partial overlaps, nested areas, boundaries
  — a polygon-intersection test, not a string compare.
- The `ssid_exclusive` opt-out flag: schema and client handling.

## 2. Registration authority and accountability (resolves F1)

**Question.** An attacker who can register its *own* valid GeoCert for the
victim's SSID+area gets `VERIFIED` on its evil twin (scenario F1), because the
client trusts any validly-signed cert. What stops that?

**Decision / assumption.** GECKO assumes the registration authority binds each
`(SSID, area)` claim to an **authorized, accountable real-world entity** — you
can only obtain a GeoCert for an area you legally own or are authorized at. This
is the same trust model as Web PKI (a CA vouches for domain ownership), applied
to physical areas. With it, F1 cannot happen: the attacker cannot register the
victim's area, and any mis-registration is attributable and revocable.

**Why it is a registration decision, not a client one.** The client's crypto
only proves a cert is really in the signed map; it says nothing about whether
that cert *should* have been admitted. So the trust root is the authorization
policy, and the client can only be as trustworthy as that policy.

**Cost / residual risk.** Authority mis-issuance or compromise — exactly the
rogue-CA risk — handled by revocation and the accountability trail, not by the
client. Belongs in the limitations chapter.

**Open sub-questions.**
- How the **first** claim is authorized (domain-ownership proof, location proof,
  administrative vetting, first-come-first-served and its race risk).
- Binding to an operator identity vs. BSSID: BSSID binding is weak (spoofable,
  and it breaks multi-AP deployments); operator-identity binding just moves
  trust to the authority, which is the right place for it.

## 3. Scope: portal identity, not link security (context for S0)

**Decision.** GECKO authenticates the captive portal's (or RADIUS server's)
identity, **not** the data path. A transparent relay of the genuine portal (S0)
correctly verifies, even though the attacker still man-in-the-middles all other
traffic. Post-authentication traffic security remains the job of TLS on the
actual services, a VPN, etc. State this scope explicitly; do not claim more.
