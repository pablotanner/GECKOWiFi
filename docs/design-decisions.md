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
and never collide.

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
- App turns `UNRECOGNIZED` into
  `CONFLICT` when the check was made for an SSID and no certificate sets
  `ssid_shared`. 
- Map server must refuse a certificate whose SSID and area overlap one from a different subject and allow it for same subject.

**Open.** How to test area overlap (partial, nested, shared borders).

## 2. Only the rightful operator can register an area

**Question.** An attacker registers its own valid certificate for the victim's
SSID and area. The app trusts any validly signed certificate, so the evil twin
would show `VERIFIED`. What prevents this?

**Assumption.** The registration authority only issues a certificate for an
area to someone who owns it or is authorized there, and that operator can be 
held accountable. Attacker can't trivially register victim's area and violations can be detected, traced and punished. 


## 3. GECKO checks the portal, not the connection

GECKO checks the identity of the captive portal (or the RADIUS server), not the
data path. If an attacker relays the genuine portal unchanged (S0), the check
correctly says `VERIFIED`, although the attacker still sees all other traffic.
Protecting that traffic is the job of TLS, a VPN and so on. 

## 4. Root stands in for system privileges
GECKO is meant to be part of the operating system's WiFi stack. The prototype
is an app, so root takes the place of system privileges (for example reading
the RADIUS server's certificates from `wpa_supplicant`). 
## 5. Password networks: GECKO checks the portal, not the password

**Question.** WPA2/WPA3-Personal networks can't be verified like an
enterprise network: everyone who knows the password (every café guest) can run
an identical twin. What can GECKO do for them?

**Decision.** Two checks, neither needs the password:
- **Before joining:** the advertised security type is compared with
  `auth_modes` (as for every network). A mismatch is flagged in the list, and
  tapping it opens the registration sheet instead of connecting. For a
  transition network (`wpa2-personal` and `wpa3-personal` registered), an
  access point offering only WPA2 is a mismatch: that is the downgrade used to
  capture a crackable handshake.
- **After the user joins through Android:** if the SSID is registered here,
  GECKO runs the portal probe over that connection and judges the hops as for
  open networks. A fake network steals data through its login page, so that is
  what is compared. Without a portal the result is at most `REGISTERED`.

Unregistered SSIDs (a travel router, a home network) are left alone.

**Prototype limits (no root).**
- Android's own "Sign in to network" notification can appear before GECKO's
  verdict. A system-level GECKO would hold the portal until its check is done.
- GECKO can't leave a network the device joined; the warning opens Android's
  Wi-Fi settings instead.
- Checks only run while the app is open.

## 6. Security type: claim, expectation, proof

**Question.** An access point says in its beacon which security it offers. How
far can GECKO trust that, and which type should it request when connecting?

**Three trust levels.**
1. **Beacon (claim).** RSN/WPA elements are unauthenticated: anyone can
   advertise anything. Used to compare with the registration and to build the
   connection, never as proof.
2. **GeoCert (expectation).** The signed `auth_modes` (and `pmf`) say what the
   operator deploys. The beacon is judged against it.
3. **Negotiated state (proof).** What the device actually negotiated after
   association: the only authenticated signal. Read after connecting.

**Decision.**
- **One classifier** (`network/SecurityProfile.kt`). The raw elements are
  parsed into an `RsnProfile` (AKMs, ciphers, MFPC/MFPR, WPA1, WEP, OWE
  transition); the capability string is only a fallback (API 29, demo networks).
  A BSS maps to the **set** of modes a client could end up in, after the WFA
  WPA3 definitions: EAP with MFPR is WPA3-Enterprise only, AKM 1 + 5 with MFPC
  is the WPA2/WPA3 transition, any other EAP is WPA2-Enterprise; the same for
  PSK/SAE and OWE. Unknown AKMs (FILS, DPP) add nothing. `floor` is the weakest
  mode of the set.
- **Verdict** (`WiFiIdentity.permits`): same kind of network; floor no weaker
  than registered; the strongest registered mode offered (the WPA3 / OWE
  downgrade); a registered `pmf: required` matched by MFPR. Stronger than
  registered is accepted and noted. Enterprise is exempt from the
  "strongest mode offered" rule only: the pinned server protects the password,
  and federations mix older access points.
- **Connect type** (`eduroam/EnterpriseSecurity.kt`): registered modes
  intersected with the observed ones, not the beacon alone. The onboarding
  specifier, bound to the tapped BSSID, uses the strongest mode; the pinned
  suggestions, bound to the SSID only, are one per registered type, so access
  points of the SSID elsewhere match too. 192-bit is refused up front: the
  PEAP/TTLS profile can't do it.
- **After connecting**: the BSSID and mode actually negotiated are read
  (`WifiInfo` without root, `wpa_cli status` with root). Weaker than
  registered: the profile is removed and the result is `CONFLICT`. The result
  is recorded for the access point actually joined, and the status card shows
  that connection.

**Why the platform matters (AOSP, API 31+, see evidence/wifi-security-model).**
- A specifier or suggestion must name a security type matching Android's own
  label for the access point, or Android never matches it. Android's labels
  differ from the WFA definitions: AKM 1 + MFPR is plain EAP to Android, so it
  gets the WPA2 builder (PMF is still negotiated, the AP requires it).
- Suggestions are keyed by SSID and security type, so a WPA2 and a WPA3
  suggestion coexist. Android adds WPA3-Enterprise to every WPA2 suggestion as
  an auto-upgrade.
- Android picks which access point of a suggested SSID to join. In the lab an
  evil twin advertising WPA3-Enterprise next to the genuine WPA2 network drew
  the device to itself; only the pinned server stopped it. Hence results are
  recorded for the access point actually joined, not the one tapped.

**What it assumes.** Without root Android doesn't report negotiated PMF; plain
EAP on an access point with MFPR counts as WPA3-Enterprise. The root reader is
untested on hardware.
