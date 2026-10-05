# Portal detection

How the app finds a network's captive portal and decides whether it is the
registered one.

## Detection

After joining the network, the app requests
`http://connectivitycheck.gstatic.com/generate_204` (the same URL Android uses)
and follows the redirects one at a time. It follows HTTP redirects and
`<meta http-equiv="refresh">`, but not JavaScript. For every HTTPS page it
records the public key the server presented.

- A `204` answer means there is no portal. This happens on open networks or
  when the device is already logged in. The app then checks the domain that is
  registered for the SSID directly.
- A page that doesn't redirect any further is the login page, and the chain
  ends there.
- Errors, loops and more than 10 redirects end the check.

The probe accepts any certificate, because the decision is made by comparing
keys with the pins in the GeoCertificate. It only sends plain GET requests.

On router A the chain looks like this:

| # | URL | Response | Judged |
|---|---|---|---|
| 0 | `http://connectivitycheck.gstatic.com/generate_204` | 302 from openNDS | no |
| 1 | `http://portal.gecko-a.lab/?tok=…` | 302 to HTTPS | no |
| 2 | `https://portal.gecko-a.lab/?tok=…` | 200, login page | yes |

## Judging the chain

`VerificationEngine.verifyPortalHops` makes one query to the map server and then
walks the hops in order with a fresh `PortalSession`:

- Every HTTPS hop is judged, and so is the last hop if it is plain HTTP (a login
  page without TLS). Plain HTTP hops that only redirect onwards are not.
- A primary domain with a matching key starts the session. After that, the
  certificate's other domains are accepted with their own keys. An unknown
  domain after the start is a `CONFLICT`.
- The overall result is the first `CONFLICT`, or otherwise the result of the
  last judged hop, which is the page the user ends up on.

This catches the following attacks:

| Attack | Result |
|---|---|
| Cloned portal with the attacker's key | `CONFLICT` |
| Lookalike domain | `UNRECOGNIZED` |
| Genuine portal first, then a redirect to the attacker | `CONFLICT` at the redirect |
| Payment page with the attacker's key | `CONFLICT` |
| Registered login page served over plain HTTP | `CONFLICT` |

## Limits

- Only redirects are seen. Pages reached through a click (the payment page, or
  an attacker who switches after "Accept") are not.
- The check runs once, right after joining. A network that switches later is
  not caught.
- An attacker could recognise the probe (user agent, timing) and answer it
  differently than a browser.
- The probe host has to resolve over the network being checked.

## Code

| | |
|---|---|
| `discovery/HopSource.kt` | `ObservedHop` and the `HopSource` interface |
| `discovery/RedirectProbeSource.kt` | the redirect probe |
| `verification/VerificationEngine.kt` | `verifyPortalHops` |
| `verification/PortalSession.kt` | per-domain rules (roles, keys, session start) |
| `data/local/PortalCheckLog.kt` | the check log |

## Extending it

Hop sources are separate from the judging logic, so a new way of finding hops
only needs to produce `ObservedHop`s.

**Traffic observation (root).** Watch the device's real traffic and emit a hop
for every new host name (DNS or TLS SNI). The server certificate is encrypted
in TLS 1.3, so the key has to be fetched with a separate connection
(`CertProbe`), and an attacker could answer that connection differently than
the browser. Because this runs continuously, the session should not be reset
for every hop. `capability/PcapTrafficObserver` and `PcapSniCapture` are a
starting point; their two SNI parsers should be merged first.

**Captive Portal API (RFC 8908).** Read the API URL from DHCP option 114, fetch
it over HTTPS and treat both the API host (role `api`) and the login page it
names as hops.

A few things to keep consistent:

- The key hash is base64(SHA-256(SubjectPublicKeyInfo)) everywhere: in the app,
  in the portal server and in the certificates.
- Read certificates from the TLS session itself, not from OkHttp's
  `Response.handshake`. OkHttp drops certificates it can't chain to a system CA
  and returns an empty list, which looks exactly like "no key".
- Roles come from the certificate. The app never guesses them from URLs.
