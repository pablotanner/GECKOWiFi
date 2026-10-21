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
    for the requested realm, inside its validity window; or
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
eduroam site, and the pins protect the password.

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

## D6. RealmRecord reuses the site schema's shape
Schema v3 already has `aaa[]` (realms, EAP methods, server names, CA pins, CA
certificates) for **local** users; the RealmRecord covers the **visitor** case,
where the home realm can't appear in the site's GeoCert. Both are reduced to
one internal type (`AuthenticatedEapPins`), so the profile builder has a single
input and a single invariant. **Shortcut:** the RealmRecord comes from a
signed static endpoint, a stand-in for a future realm keyspace in the map
(with inclusion proofs). Open question for Pablo and his supervisor.

## D7. One CA for onboarding and homeuni.test (**shortcut**, as briefed)
One FreeRADIUS EAP module serves both identities with one certificate
(SANs `radius.homeuni.test`, `gecko-onboard.sp.test`), so both pins share
`GECKO-Lab-CA`. The B2 attacker gets a certificate from the same CA for the
onboarding name only: it passes onboarding (the threat model assumes it can)
and fails the realm pin on the name. In production the onboarding CA would be
a separate, OS-bundled one.

## D8. Onboarding identity is a fixed convention (**shortcut**)
`gecko@gecko.sp.test` / `gecko` and `gecko-onboard.sp.test` are constants in the
app. A WiFi-only device can't look anything up before joining, so in a real
deployment these would have to be a federation-wide convention (e.g. a
well-known realm every SP handles locally) plus an OS-bundled onboarding CA.
Open design question.

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
and federation `eduroam` with an empty `aaa`: everybody is a visitor, so the
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
