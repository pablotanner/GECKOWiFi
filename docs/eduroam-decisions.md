# eduroam onboarding demo: decision log

Decisions taken while implementing [eduroam-brief.md](../eduroam-brief.md)
("Design A"), newest last. Each says what was decided, why, and what it costs.
Demo shortcuts are marked **shortcut**; they are also listed in
[eduroam-demo.md](eduroam-demo.md).

## D1. Root: non-root now, root-ready
The thesis assumes system-level GECKO (root stands in). The Tab can't be
rooted yet, so the eduroam path uses public APIs only (`WifiNetworkSpecifier`
for onboarding, `WifiNetworkSuggestion` for the real profile). The two
platform-dependent steps sit behind interfaces (`OnboardingTransport`,
`EnterpriseProfileInstaller`) so a root implementation (wpa_supplicant control
socket) can replace them without touching verification or the credential
invariant. Approval dialogs are a cost of the prototype, not of the design.

## D2. When credentials may be sent
Pablo: send credentials only when we know they can't end up at an attacker,
which is what eduroam CAT achieves. That is not the same as the network being
`VERIFIED`. Credentials are safe exactly when the supplicant enforces a CA pin
and a server-name pin for the user's realm that came from an **authenticated**
source; then a server that can't prove that identity never sees the inner
exchange, whichever AP the device talks to. So the gate is:

- **Authenticated pins for the user's realm**, from either
  - a `SignedRealmRecord` whose signature verifies against the pinned realm key,
    for the requested realm **and the site's federation** (D19), inside its validity window; or
  - the site GeoCert's `wifi.enterprise.aaa[]` entry covering the realm (local
    user), which arrived with a verified Merkle proof, provided it carries
    `ca_certificates` (Android can only pin a CA certificate, not a hash) and
    server `names`.
- **and** the site check is not `CONFLICT`. With authenticated pins the
  credentials would still be safe, but a network that contradicts its
  registration is an impersonation and there is nothing to gain by joining it.

Everything else blocks: no realm record (withheld → map/realm server
unreachable), bad signature, expired record, wrong realm, unsupported method,
no CA certificate. The site state (`REGISTERED`, `UNVERIFIED`) is shown as
plausibility, not used as the gate. `UNVERIFIED` site + verified realm
record still proceeds: that is exactly CAT's behaviour at an unregistered
eduroam site, and the pins protect the password. **Changed by D19:** realm
records are scoped to a federation, which only the site registration names, so
an unregistered site now blocks visitors (local realms need the registration
anyway).

## D3. EAP method: PEAP-MSCHAPv2
What most eduroam IdPs use; scene 4 then shows a captured, crackable
MSCHAPv2 exchange. TTLS-PAP stays enabled on the server for experiments.

## D4. RADIUS reachability: UDP relay on Windows
Windows 10 has no WSL mirrored networking and `netsh portproxy` is TCP only.
A small Python relay (`lab/eduroam/udp-relay.py`, stdlib only) listens on the
ICS address and forwards to the current WSL IP. No new installs. All RADIUS
traffic reaches FreeRADIUS from the WSL gateway address, so the relay enforces
the source allowlist (Router A's WAN, through which B's traffic is NATed too)
and the shared secrets tell the instances apart.

## D5. RealmRecord signatures: ECDSA P-256 (not Ed25519)
Same scheme as the map head (`PinnedServerKey`): standard JCA on every Android
version (minSdk 29), no new dependency. Ed25519 has no built-in support on
older Android versions. The signature is ASN.1 DER over the exact `record` bytes;
the client verifies first and decodes after.

## D6. Pins for visitors come from a realm record registered by the home institution
Two sources of pins, one internal type (`AuthenticatedEapPins`):
- **Local users / closed networks:** the site GeoCert lists its own realms in
  `wifi.enterprise.aaa[]` with server names and CA certificates. Enough on its
  own; nothing else needed.
- **Visitors in a federation:** a visitor's password goes to their *home*
  institution's server, so the pins must describe that server. The site can't
  provide them (thousands of institutions) and **must not**: a site able to
  register pins for other realms could enter its own server and collect
  visitors' passwords. So the home institution registers a **realm record**.

Assumption, phrased like D2's GeoCert assumption (design-decisions.md, 2):
**every participating institution registers a realm record for its realm; the
registration authority only issues it to whoever controls the realm** (a realm
is a domain, so domain validation as in the Web PKI). Same trust model as a
GeoCert, indexed by (federation, realm) instead of location (D19). In a real
deployment it lives in the map with inclusion proofs. **Shortcut:** a signed
static endpoint with one lab key stands in for that.

## D7. One CA for onboarding and homeuni.test (**shortcut**, as briefed)
One FreeRADIUS EAP module serves both identities with one certificate
(SANs `radius.homeuni.test`, `gecko-onboard.sp.test`), so both pins share
`GECKO-Lab-CA`. The B2 attacker gets a certificate from the same CA for the
onboarding name only: it passes onboarding (the threat model assumes it can)
and fails the realm pin on the name. In production the onboarding CA would be
a separate, OS-bundled one.

## D8. Onboarding convention: one global identity, no certificate check in the system model
A device can't look anything up before joining, so the onboarding login must
be known in advance, and every participating SP must handle it locally.
- **Identity:** one reserved, global GECKO identity, the same at every network
  and in every federation (not per site: the device doesn't know the site
  before joining; not per federation: the SSID doesn't reliably say which). A
  site that doesn't participate forwards it to its federation, which rejects
  it, so the app learns "not supported here". Who reserves the realm is a
  standardisation question.
- **Certificate check:** none in the system-level model. The onboarding session
  isn't trusted anyway; its answers protect themselves (proofs, signatures). The
  prototype uses a bundled onboarding CA only because Android's public API
  refuses enterprise configs without server validation (an artefact of the
  non-root prototype, like the approval dialogs).
- Precedent: Passpoint/Hotspot 2.0 Online Sign-Up joins a dedicated sign-up
  network with an anonymous login to fetch credentials; Design A follows the
  pattern with verifiable answers.

**Shortcut:** `gecko@gecko.sp.test` / `gecko` / `gecko-onboard.sp.test` and the
lab CA are app constants.

## D9. Cleartext HTTP stays app-wide
The brief suggests a `network_security_config` scoped to the lab IP. The app
already allows cleartext app-wide, and portal detection must probe arbitrary
`http://` hosts, so scoping would break it. Integrity of map and realm answers
comes from proofs and signatures, not transport.

## D10. Realm endpoint runs on Windows, not in WSL
Go is installed on Windows and the portal server already runs there, so the
realm server listens directly on `192.168.137.1:1235`: no portproxy needed,
only a firewall rule scoped to `192.168.137.0/24`. Records are signed
offline (`go run . sign`) into `lab-secrets/realm/signed/`; the server only
serves bytes and never holds the key.

## D11. Withholding shows as `UNREACHABLE`
The app's state model has `UNREACHABLE` for "no trustworthy answer"; the brief's
four-state model calls this `UNVERIFIED`. Scene 3 therefore shows
`UNREACHABLE` (site) and "no realm record" (realm), and blocks.

## D12. Separate attacker instances for B1 and B2
B1 (rogue CA) and B2 (valid onboarding certificate) are two FreeRADIUS
instances on their own ports (1814, 1816), so switching modes on Router B is
only a matter of which `wifi-iface` is enabled, without touching the laptop.

## D13. FreeRADIUS instances get their own ports in their config
`radiusd -i/-p` looked simpler but hands packets to the empty top-level
`server {}` instead of the `default` virtual server (every request rejected
with "No Auth-Type found"). `setup-instances.sh` instead rewrites each copy's
`listen` sections (auth = port, acct = port+1) and moves the inner tunnel's
local test listener (18120, 18122, 18124). Logs go straight to a file
(`-sfxx -l`): through a pipe, `-X` output is block-buffered.

## D14. The Mangos can't be enterprise APs as they are (phase 3 blocked)
Both run GL's MediaTek driver, not hostapd. GL's configurator would start
`/usr/bin/8021xd` for `encryption 'wpa2'`, but it is not installed and not in
GL's feed. No dynamic VLAN support either, so the walled garden (3b) is out
regardless (**shortcut**: GECKO identity gets normal access; the attributes
are still sent). Options are in [eduroam-demo.md](eduroam-demo.md#routers);
no router was changed.

## D15. Lab guard: onboarding only on `eduroam-lab`
Real `eduroam` and `eth` (enterprise) are visible everywhere in the lab
building. Tapping them opens the setup screen, but the flow refuses to start
the onboarding session for any SSID but `eduroam-lab`, so not even the public
GECKO identity reaches a production RADIUS server.

## D16. Site GeoCert has no local realms
`eduroam-lab-sp` (lab/certs/eduroam-lab.json) registers the SSID, WPA2-Enterprise
and federation `eduroam-lab` (D22) with an empty `aaa`: everybody is a visitor, so the
demo exercises the RealmRecord path. A local realm with `ca_certificates` would
make the app take the pins from the site certificate instead (also implemented
and unit-tested). Same area as `mango-a` (central Zürich), so lab work works anywhere there.

## D17. Router B runs vanilla OpenWrt 25.12.6
Flashed 2026-10-20 (Pablo's go) with a custom image (`wpad-mbedtls` for
RADIUS/EAP/dynamic VLAN, LuCI, openNDS), first-boot config restored by
`sysupgrade -f`. Details, checks and lessons in
[flashing-openwrt.md](flashing-openwrt.md). A stays on GL firmware until B has
shown WPA2-Enterprise with the tablet.

## D18. Final lab roles: A legit, B attacker
After proving the legit SP on B, it moved to Router A (flashed to vanilla
OpenWrt too), matching the lab's existing roles (A genuine, B evil twin; B
hangs behind A, so it can block the map server for its own clients in scene 3).
B keeps its attacker sections, disabled while idle.

## D19. Generic enterprise support: federations, accounts per realm
Nothing is eduroam-specific; eduroam is one value of `federation`.
- **Realm records are scoped to a federation:** a realm is a domain, but its
  binding to servers, and who may vouch for it, is per federation. The record
  carries `federation` (signed); the endpoint is `/v1/realm/<federation>/<realm>`;
  the app only accepts a record whose federation equals the site's.
- **Accounts** (identity + password) are stored per realm, any number. No
  federation is stored on an account: which federation it serves is decided on
  every connection from verified data.
- **Matching** against the site registration: an account whose realm the site's
  `aaa` lists is *local* (pins from the GeoCert); otherwise, if the site names a
  federation, a *visitor* (pins from the realm record (federation, realm));
  otherwise it doesn't fit. One account with verified pins: connect. Several:
  ask once and remember the choice per network. None: sign-in sheet.
- **Wording from the registration:** the sign-in sheet names the federation
  (`federation`) or the operator (`subject.name`) and its realms, never "eduroam".
- Because a WiFi-only device only learns the registration over the onboarding
  session, matching happens after onboarding. A newly entered account needs its
  realm record, so it costs one more onboarding (one more approval dialog in the
  prototype).
- Limits: password methods (PEAP, TTLS) only; no EAP-TLS client certificates, no
  SIM-based methods.

## D20. App flow: tap a network = connect
Replaces the separate eduroam setup screen and its Settings entry. Tapping any
network connects; the method follows from the network (portal check, GECKO
onboarding, or Android's own dialog for password networks, which GECKO doesn't
cover); an info button shows the registration. Accounts live in Settings. The
status card shows the device's real Wi-Fi connection with GECKO's last verdict
for it (persisted). Enterprise networks GECKO can't onboard on (in the
prototype: everything but the lab SSID, D15) open Android's Wi-Fi settings with
a note.

## D21. A saved registration replaces onboarding
Onboarding only exists so a WiFi-only device can fetch the site's registration
and the realm record before it has credentials. If GECKO already holds a
verified answer for the place, the session is not needed.
- **What is saved:** the last verified map answer (certificates, position and
  radius, when it arrived, over which connection, the signed map-head time),
  and every realm record fetched over an onboarding session as its signed
  bytes. Records are re-verified (signature, federation, realm, expiry) every
  time they are used, so a tampered store yields nothing.
- **When it is used:** the answer is at most 24 h old and was fetched for an
  area containing the current position (D26), and among its certificates for
  the SSID, valid now and covering the current position, one registers an
  enterprise network. All applicable certificates for the SSID
  are used, so a mismatch in advertised security still gives CONFLICT. If a
  visitor's record is missing, GECKO falls back to onboarding where the network
  supports it, and blocks otherwise.
- **Why 24 h:** against an active attacker a saved answer is hardly weaker than
  a fresh one (map-head freshness is open either way). The limit covers honest
  changes such as a rotated server certificate or a revoked registration.
- **Effect:** enterprise networks without GECKO onboarding (a real eduroam AP)
  become joinable with pinned credentials, as long as GECKO looked up the place
  recently over another connection. If the map server can't be reached, the
  home list shows the saved answer, marked as saved. A failed proof is never
  replaced by saved data.
- **Transparency:** every verdict's details say where its data came from and
  when ("Data from"). Settings > Saved GECKO data lists the saved answer and
  records and deletes them, together with verdicts and History. Accounts and
  installed profiles stay.

## D22. The lab has its own federation, `eduroam-lab`
The lab site certificate and the Home University realm record used to name the
federation `eduroam`. GECKO then treated the made-up `homeuni.test` as a member
of real eduroam: at ETH (registered with federation `eduroam`), the lab account
`alice@homeuni.test` matched as a visitor, and GECKO installed a profile for
the real `eduroam` pinned to the lab server. Nothing leaked, since the pin
would have stopped the handshake, but a lab account must never be offered at a
production network. The lab now uses `eduroam-lab` everywhere: the site
certificate `eduroam-lab-sp` (re-registered), the record
`/v1/realm/eduroam-lab/homeuni.test` (re-signed; the `eduroam/` copy was
deleted). The `govroam` record stays as the second federation for the scoping
tests. A record the app saved before the rename is still validly signed for
`eduroam`, so delete the saved data on devices that used the lab before.

## D23. Questions are sheets, results are verdicts
An enterprise attempt ends in one of three forms, each with its own UI:
- **A result** (verdict on the status card, saved in History): connected,
  a warning on signs of an attack, or a technical failure ("Couldn't check",
  with Try again): onboarding failed, record server unreachable.
- **A question** (bottom sheet, no verdict, nothing saved): no saved account
  can be used. The sign-in sheet lists each saved account with why it can't
  be used here (realm not accepted, home institution has no record in this
  federation, record not saved and not fetchable here, registration without
  the CA certificate) and offers Android's settings as the way out.
- **A choice** (dialog): several accounts can be used.
"Your password was not sent" only appears in warnings, where it reassures; in
neutral outcomes it alarmed without helping. A missing record splits into
`NO_RECORD` and `NOT_SAVED` (no retry helps, so ask for an account) and
`UNREACHABLE` (retry may help, so a neutral result).

## D24. Map server keeps `ca_certificates`
The geopki server decodes certificates into its Go struct and stores the
re-encoded struct, so fields it doesn't know are dropped. `AuthServer` lacked
`ca_certificates`, so ETH's registration arrived without CA certificates and
GECKO rightly refused to pin it. Added the field in geopki
(`pkg/crypto/certificate.go`, one line) and re-registered
`ethz-eduroam-2026a`. A comparison of every lab certificate with its stored
version shows nothing else is lost.

## D25. Network checks fall back to the saved answer
A captive portal blocks the internet until login, so a check over the joined
network can't reach the map server; a device without mobile data then can't
check the network at all. When the live query is unreachable, every judging
query (portal hops, portal domain, login server, advertised security) uses the
saved answer (D21) instead, if it is at most 24 h old and its area contains
the whole query circle, so "nothing registered" holds as it did then. (It used
to apply also outside that area when it held a certificate for the SSID; D26
drops that.) Results
say "Data from: saved registration from …" and are not put in the decision
cache, so a live answer replaces them as soon as the server is reachable. A
failed proof is never replaced. This also defeats withholding: a network that
blocks the map server no longer hides its registration. Staleness and
revocation (shorter age, revocation lists, map-head freshness) are left to a
real deployment.

`eth-visitors` was re-registered as `eth-visitors-2026a`
(lab/certs/eth-visitors.json): the earlier registration named a placeholder
portal (`guest.ethz.ch`, key `testXYZ=`). The new one pins the portal the
tablet observed on 2026-10-21: `enter-guest-net.ethz.ch` (primary) and
`login.enter.ethz.ch` (delegate), ETH campus areas. The pins are TLS leaf keys
from one observation; when ETH renews its certificates with new keys the
registration has to follow.

## D26. A saved answer only speaks for the area it was fetched for
The map server returns the certificates whose area overlaps the query circle,
so a saved answer says two things: these registrations exist, and nothing else
is registered *inside that circle*. The first stays true wherever a
certificate's own area reaches; the second does not. Before, the saved answer
was used anywhere for 24 h (D21, the home list) or wherever it held a
certificate for the SSID (D25). A kilometre away, networks registered only
there looked unregistered, and a second registration of the same SSID (another
eduroam operator) went unseen, so a contradiction could be missed or a portal
check could warn falsely. Nothing leaked, since passwords only go to pinned
servers, but the app showed things it couldn't know.
- **One rule** (`SavedLookup.appliesAt`): the answer is at most 24 h old and
  `distance(fetched at, here) + query radius <= saved radius`. It decides the
  home list's fallback, enterprise sign-in without onboarding (D21) and network
  checks (D25) alike.
- **A wider saved area:** the home list's lookup now asks for the encoder's
  maximum radius (253 m) instead of the device's accuracy (tens of metres), and
  the list shows only what reaches the device. One query serves both, and the
  saved answer still applies after a walk across the building. Checks keep
  querying with the accuracy radius.
- **Expired means deleted; elsewhere means unused.** An answer older than 24 h
  is deleted. One that only doesn't apply here is kept and used again on
  return, until a fresh lookup replaces it; deleting it would only lose data
  that is correct where it was fetched. Settings > Saved GECKO data says
  whether it is used here and how far away it was fetched. Realm records
  aren't tied to a place and keep their own signed expiry.
- **Kept fresh while the app is open:** a lookup every 10 minutes and a few
  seconds after the device joins a Wi-Fi network (when a WiFi-only device can
  usually reach the map server again), not while a check holds a network. On
  the Tab, a lookup 3 s after joining `GeckoTest` failed over the default
  route: Android hadn't made the new network (a captive portal) the default
  yet. Lookups without a join of the app's own are now bound to the device's
  Wi-Fi network, and after a join GECKO tries again after 10 and 30 s until an
  answer is saved; a request during a running lookup is queued, not dropped.
  Measured after the fix: answer saved 5 s after the join, on the first try. Tied
  to the started lifecycle, so nothing runs and no location is asked for in the
  background. A real deployment could do this in the system, with background
  location and a freshness policy for the map head.

## D27. Android doesn't leave a working network for a suggestion
Seen on the Tab: connected to `eduroam` through GECKO, a sign-in to
`eduroam-lab` waited 60 s and ended "Set up", and the same the other way
round; after forgetting the first network it worked. It looked like the
saved-registration path (D21, no GECKO identity), but that path did its job:
pins verified, profile installed. The cause is the platform. A
`WifiNetworkSuggestion` is a candidate for Android's own network selection,
which doesn't switch away from a connected network that works, and an app
can't make it (`WifiManager.disconnect()` is a no-op for apps since API 29).
Forgetting the other network worked because removing a suggestion disconnects
from it. The saved path makes it visible: real `eduroam` always takes it, and
it never touches the radio, while onboarding briefly takes the radio over
with its own request.
- **Fix in the prototype:** while waiting, the status card says the device is
  on another network and offers Android's Wi-Fi panel, where tapping the
  network joins it with GECKO's profile; GECKO is still waiting then, so the
  result becomes Verified. If the wait ends first, the result is "Set up"
  with `ANDROID_STAYED` and the same button, instead of the generic wording.
- **Not done:** removing the other network's profile to force the switch (it
  would delete a working setup), or a root command (untested on the Tab). A
  system-level GECKO (D1) would select the network in wpa_supplicant directly,
  and the problem disappears.
