# GECKOWiFi

Android prototype for verifying WiFi networks with GECKO, a geographic PKI.
Part of a Master's thesis at ETH Zürich.

Evil-twin access points are easy to set up: anyone can broadcast the same SSID
as a café or a campus network. TLS doesn't help much here, because a valid
certificate only proves who owns a domain, not that the domain belongs to the
network you're standing next to. [GECKO](https://github.com/netsec-ethz/geopki)
lets a network operator register, for a physical area, which identities their
network presents (captive-portal domains and their keys, or the RADIUS server
for 802.1X). A client can then ask the map server what is registered at its
location and check the network against it.

## How a check works

1. The app joins the selected access point itself (pinned to its BSSID).
2. It follows the network's captive-portal redirects and records the TLS key of
   every HTTPS page on the way.
3. It queries the GECKO map server for certificates registered at the device's
   location and verifies the response (signature, inclusion and consistency
   proofs) against a pinned server key.
4. Certificates for the network's SSID are compared with what the network
   presented: domain names, their roles (login page or a delegate such as a
   payment page) and pinned keys.

Results:

| State | Meaning |
|---|---|
| `VERIFIED` | The network presented what is registered for it here |
| `CONFLICT` | Something is registered for this network, but it presented something else, or the server's proof failed |
| `UNRECOGNIZED` | Certificates exist here, but none matches the domain that was presented |
| `UNVERIFIED` | Nothing is registered for this network at this location |
| `UNREACHABLE` | The map server couldn't be reached |

Only `CONFLICT` is meant to warn the user. Most networks won't be registered,
and warning about those would just be noise.

## Repository

```
app/            Android app (Kotlin, Jetpack Compose)
  geopki/         port of the client side of netsec-ethz/geopki (encoding, proofs, protobuf)
  discovery/      captive-portal detection
  verification/   decision logic (PortalSession, VerificationEngine)
  capability/     root-only parts: traffic capture, iptables (not wired up yet)
portal/         captive-portal server used in the lab (Go)
lab/certs/      GeoCertificates registered for the lab networks
docs/           lab setup, portal detection, experiments
```

## Building

The app has two build flavors that differ only in the map server address:
`emulator` (`10.0.2.2:1234`, includes some simulated networks) and `device`
(`192.168.137.1:1234`, the lab network).

```
./gradlew installDeviceDebug
./gradlew testDeviceDebugUnitTest
```

The map server is a local [geopki](https://github.com/netsec-ethz/geopki)
instance. Its public key is pinned in `PinnedServerKey.kt`, so a new server
means updating that key.

## Useful

To check which Router the tablet is connected to, run:
```powershell
adb shell "dumpsys wifi | grep -m1 mWifiInfo"
```
This prints something like:
```
mWifiInfo SSID: "GeckoTest", BSSID: 94:83:c4:97:c2:3a, MAC: 0a:61:9b:16:99:b7, IP: /192.168.8.194, Security type: 0, Supplicant state: COMPLETED, Wi-Fi standard: 11n, RSSI: -30,
```
Looking at the BSSID tells us the router (Router A ends with :3a, Router B ends with :47)

## Documentation

- [docs/lab-setup.md](docs/lab-setup.md) – the hardware testbed: routers,
  addresses, captive portal, running the app
- [docs/portal-detection.md](docs/portal-detection.md) – how portals are
  detected and judged, and how to extend it
- [docs/experiments.md](docs/experiments.md) – attack scenarios and results
- [portal/README.md](portal/README.md) – the portal server

## Status

This is a research prototype. Working on real hardware: joining a specific
access point, detecting the captive portal, verifying the map server's
response, and telling a genuine portal from a cloned one. Not done yet:
continuous monitoring after the initial check (needs root), real 802.1X /
eduroam verification, and persistent history.

The prototype assumes GECKO would eventually be part of the operating system,
so the parts that need system privileges are written against root rather than
Android's VPN API.

## Acknowledgements

Builds on [netsec-ethz/geopki](https://github.com/netsec-ethz/geopki). The
`geopki` package is a Kotlin port of its client-side Go code.
