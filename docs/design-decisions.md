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
