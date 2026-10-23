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

## Documentation

- [docs/geocert-schema.md](docs/geocert-schema.md) – the GeoCertificate format (v3)
- [docs/lab-setup.md](docs/lab-setup.md) – the hardware testbed: routers,
  addresses, captive portal, running the app
- [docs/portal-detection.md](docs/portal-detection.md) – how portals are
  detected and judged, and how to extend it
- [docs/experiments.md](docs/experiments.md) – attack scenarios and results
- [portal/README.md](portal/README.md) – the portal server

## Cheat Sheet
### Tablet Related
To check which Router the tablet is connected to, run:
```powershell
adb shell "dumpsys wifi | grep -m1 mWifiInfo"
```
This prints something like (BSSID of Router A ends with :3a, Router B ends with :47):
```powershell
mWifiInfo SSID: "GeckoTest", BSSID: 94:83:c4:97:c2:3a, MAC: 0a:61:9b:16:99:b7, IP: /192.168.8.194, Security type: 0, Supplicant state: COMPLETED, Wi-Fi standard: 11n, RSSI: -30,
```

Does the tablet have internet / reach the server?
```powershell
adb shell ping -c3 1.1.1.1          # raw (works only once authenticated)
adb shell ping -c3 example.com      # DNS working?
adb shell ip addr show wlan0        # tablet IP
adb shell ip route                  # gateway = which router
```
### Router Related
To check if router has internet
```powershell
ssh mango-a "ping -c3 1.1.1.1"                          # raw reachability
ssh mango-a "nslookup connectivitycheck.gstatic.com"    # public DNS
```
If it doesn't, disable and re-enable the Sharing of WiFi adapter, then run this:
```powershell
# portal addresses: genuine and attacker
New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.137.10 -PrefixLength 24 -SkipAsSource $true
New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.137.20 -PrefixLength 24 -SkipAsSource $true
```

Can the router reach the map server? (404 = reachable)
```powershell
ssh mango-a "curl -s -o /dev/null -w '%{http_code}\n' http://192.168.137.1:1234/ || echo FAIL" 
```

Laptop can't even reach Routers (ssh mango-a)? 
- Turn off Sharing ("Allow other network users to connect…") and then on again in 
- Check ```Get-NetIPAddress -InterfaceAlias 'Ethernet 4' -AddressFamily IPv4 | ft IPAddress,PrefixLength ```
- If .1 is missing above: ```New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.137.1 -PrefixLength 24```

Finally, re-add addresses so portal can still run:
```powershell
New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.137.10 -PrefixLength 24
New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.137.20 -PrefixLength 24 
```


### GECKO App
```powershell
./gradlew installDeviceDebug                        # (re)install the app
adb logcat -c                                       # clear, then run a check
adb logcat -s PortalDiscovery PortalCheckLog CertProbe NetworkObserver GeckoClient VerificationViewModel
adb pull /sdcard/Android/data/com.thesis.geckowifi/files/portal-checks/ # per-check JSON logs
```

### openNDS (the gate)
```powershell
ssh mango-a "ndsctl status"                 # running? client counts
ssh mango-a "ndsctl json"                   # clients + MAC + auth state
ssh mango-a "ndsctl auth <tablet-mac>"      # force-authenticate (skip portal) — for P1 tests
ssh mango-a "ndsctl deauth <tablet-mac>"    # log the client out (do before each check)
ssh mango-a "/etc/init.d/opennds restart"   # restart if it's wedged
ssh mango-a "uci show opennds | grep -E 'fas|preauth|trusted'"   # FAS target + whitelist
```
### DNS / SSID on router B (scenario setup)
```powershell
ssh mango-b "uci show dhcp | grep address"         # B's DNS spoofing entries
ssh mango-b "uci get wireless.wifi2g.ssid"         # current SSID
ssh mango-b "iwinfo"                               # BSSID, channel, associated stations
ssh mango-b "nft list ruleset | grep -i gecko"     # S7 firewall block present?
```
### Scenario Control
```powershell
.\scenario.ps1 S3      # configure B (DNS/openNDS/firewall) + print the laptop portals to start
.\reset.ps1            # return B to the evil-twin baseline (also clears S7 rule / N6 SSID)
```

### eduroam demo: start the laptop side (every session)
How the pieces fit together: [docs/eduroam-demo.md](docs/eduroam-demo.md). After a
laptop restart, in this order (Docker + map server as in docs/lab-setup.md first):
```powershell
cd lab\realm; go run . serve -addr 192.168.137.1:1235 -dir ..\..\lab-secrets\realm\signed   # realm server (own window)
python lab\eduroam\udp-relay.py                                                             # RADIUS relay to WSL (own window; restart after WSL restarts)
```
```bash
# in WSL, from /mnt/c/Users/41763/AndroidStudioProjects/GECKOWiFi
bash lab/eduroam/radius/run.sh legit          # legit RADIUS, live log in the terminal (Ctrl-C stops it)
bash lab/eduroam/radius/run.sh legit bg       # ... or in the background
bash lab/eduroam/radius/stop.sh all           # stop every instance
```
Is everything up? (`404`/`200` = reachable)
```powershell
curl.exe -s -o NUL -w "%{http_code}`n" http://192.168.137.1:1234/                        # map server
curl.exe -s -o NUL -w "%{http_code}`n" http://192.168.137.1:1235/v1/realm/homeuni.test   # realm server
wsl -e pgrep -ax freeradius                                                               # RADIUS instances
```

### eduroam demo: RADIUS
```bash
tail -f $(ls -t ~/gecko-radius/logs/legit-*.log | head -1) | grep --line-buffered -E 'Received Access-Request|User-Name|Tunnel-Private|Sent Access'   # who logs in, which VLAN
bash lab/eduroam/radius/count-inner.sh $(ls -t ~/gecko-radius/logs/attacker-b1-*.log | head -1)   # what an attacker captured
bash lab/eduroam/radius/test-radius.sh        # all instances up: 11 checks with eapol_test
```
Log files: `~/gecko-radius/logs/<instance>-<time>.log` in WSL. Test password for
`alice@homeuni.test`: `ALICE_PASSWORD` in `lab-secrets/radius/secrets.env`.

### eduroam demo: Router A (legit SP)
```powershell
ssh mango-a "iw dev | grep -E 'Interface|ssid'"                   # GeckoTest + eduroam-lab (+ phy0-ap1-onb = VLAN 99)
ssh mango-a "logread -f | grep -E 'phy0-ap1|RADIUS'"              # live: logins, 'RADIUS: VLAN ID 99', session timeout
ssh mango-a "cat /tmp/dhcp.leases"                                # 10.99.0.x = walled garden, 10.98.0.x = normal eduroam
ssh mango-a "nft list ruleset | grep -E 'GECKO-onboard|reject onboard'"   # walled-garden counters (allowed / rejected)
ssh mango-a "dmesg | grep 'link down' | tail"                     # port 0 = laptop, port 1 = Router B
```

### eduroam demo: Router B (attacker modes)
```powershell
ssh mango-b "uci show wireless | grep -E 'eduroam_(b1|b2|legit)\.(ssid|disabled|auth_port)'"   # which mode is configured/active
ssh mango-b "uci set wireless.eduroam_b1.disabled=0; uci commit wireless; wifi reload"        # B1 on (radio on only while testing!)
ssh mango-b "uci set wireless.eduroam_b1.disabled=1; uci commit wireless; wifi reload"        # B1 off
```
Never enable an attacker section without its RADIUS instance and the relay running,
and never name anything `eduroam`.

### eduroam demo: tablet
```powershell
adb logcat -s OnboardingSession EduroamOnboarding EduroamInstaller GeckoClient   # live, or afterwards (the buffer keeps it)
adb logcat -d -s EduroamOnboarding | grep "outcome for"                          # the last result in one line
adb shell "dumpsys wifi | grep -m1 mWifiInfo"                                    # which AP? see BSSIDs below
adb shell cmd location providers set-test-provider-location gps --location 47.37643,8.54674 --accuracy 15   # fake lab location (see docs/eduroam-demo.md)
```
A manually saved `eduroam-lab` on the tablet wins over the app's profile: forget it after scene 4 / manual tests.

| BSSID | Network |
|---|---|
| `94:83:c4:97:c2:3a` | A, `GeckoTest` (genuine portal) |
| `96:83:c4:97:c2:3a` | A, `eduroam-lab` (legit SP) |
| `94:83:c4:97:c2:47` | B, `GeckoTest` (attacker portal) |
| `96:83:c4:97:c2:47` | B, `eduroam-lab` (attacker, only while testing) |

### Routers: snapshots and recovery
```bash
bash lab/eduroam/pull-router-configs.sh            # before/after every router change; then commit lab-configs/
```
Full backups: `lab-secrets/router-backups/`. SSH to the routers needs the RSA key
(`~/.ssh/id_mango_rsa`, OpenWrt's dropbear has no Ed25519). Flashing and recovery:
[docs/flashing-openwrt.md](docs/flashing-openwrt.md).
## Status

This is a research prototype. Working on real hardware: joining a specific
access point, detecting the captive portal, verifying the map server's
response, and telling a genuine portal from a cloned one. For eduroam-style
networks, the app sets up a pinned enterprise profile itself, from data it
fetched and verified over a public onboarding session (no root needed;
[docs/eduroam-demo.md](docs/eduroam-demo.md)). Not done yet: continuous
monitoring after the initial check (needs root) and persistent history.

The prototype assumes GECKO would eventually be part of the operating system,
so the parts that need system privileges are written against root rather than
Android's VPN API.

## Acknowledgements

Builds on [netsec-ethz/geopki](https://github.com/netsec-ethz/geopki). The
`geopki` package is a Kotlin port of its client-side Go code.
