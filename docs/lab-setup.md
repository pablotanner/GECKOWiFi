# Lab setup

The testbed has two access points with the same SSID: router A is the genuine
network, router B the evil twin. A laptop runs the GECKO map server and the
captive-portal server, and an Android tablet runs the app.

## Hardware

| Device                                      | Role |
|---------------------------------------------|---|
| Laptop (Windows 10)                         | Map server (in WSL2), portal server, internet sharing, builds |
| GL.iNet Mango (MT300N-V2-23a), "A"          | Genuine access point |
| GL.iNet Mango (MT300N-V2-247), "B"           | Evil twin |
| Samsung Galaxy Tab A11 (SM-X130, WiFi only) | Test device |


## Network

```
internet ── laptop WiFi ── laptop ── USB Ethernet 192.168.137.1
                              │                 │
                     WSL2: map server     A  WAN 192.168.137.50, LAN 192.168.8.1
                     portal server             │
                     (.10 genuine, .20 attacker)
                                          B  WAN 192.168.8.115, LAN 192.168.247.1
```

The laptop shares its internet connection with A through Windows Internet
Connection Sharing (ICS). B hangs off A's LAN, so it goes offline when A does.
Both routers broadcast the open SSID `GeckoTest`, A on channel 1 and B on
channel 11.

| | Address |
|---|---|
| Map server | `http://192.168.137.1:1234` |
| Portal server, genuine / attacker | `192.168.137.10` / `192.168.137.20` (ports 80, 443) |
| Router A | WAN `192.168.137.50`, LAN `192.168.8.1`, BSSID `94:83:c4:97:c2:3a` |
| Router B | WAN `192.168.8.115` (reserved on A), LAN `192.168.247.1`, BSSID `94:83:c4:97:c2:47` |

The tablet is WiFi-only, so its queries to the map server always go through the
access point it is checking.

## Map server

The server is [geopki](https://github.com/netsec-ethz/geopki) running in WSL2
(Ubuntu 22.04, PostgreSQL 14). Trillian runs in Docker from
`trillian/examples/deployment` and restarts on its own. `direnv` loads
`DATABASE_URL`, `CERT_INSERT_KEY` and `PRIVATE_KEY` from `.envrc`.

```bash
cd ~/Thesis/geopki
go run ./cmd/geopki-server --address=0.0.0.0 --port=1234 \
  --trillian-address=127.0.0.1:8090 --clog-id=5656256576233247638
```

WSL runs behind NAT on Windows 10, so `netsh portproxy` forwards port 1234 from
the laptop to WSL. WSL gets a new address on every restart. After a reboot,
start the server first, then run `geopki-proxy.ps1` as administrator:

```powershell
$ip = (wsl hostname -I).Trim().Split()[0]
netsh interface portproxy delete v4tov4 listenaddress=0.0.0.0 listenport=1234 2>$null
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=1234 connectaddress=$ip connectport=1234
netsh interface portproxy show v4tov4
```
Then to confirm it work, check:
```powershell
curl.exe http://192.168.137.1:1234/      # 404 means the server is reachable
```

A firewall rule (`GeoPKI 1234`) allows the port from `192.168.137.0/24` only.
The server's public key is pinned in the app; if the server is ever set up
with a new key, every check fails with `CONFLICT` until the pin is updated.

## GeoCertificates
We use a modified GeoCertificate schema, a captive portal is a list of domains, each
with a role and its own pinned keys:

- `primary`: the login page, the only valid entry point
- `delegate`: only valid after the primary, e.g. a payment page
- `api`: host of the Captive Portal API (RFC 8908), not used yet

The pins are SHA-256 hashes of the certificate's public key (SPKI, base64). The
app doesn't use Web PKI at all, so a domain without pins never verifies.
Expired certificates are ignored.

The certificate for router A looks like this:
```json
[
  {
    "schema_version": 2,
    "certificate_id": "mango-a",
    "wifi": { "auth_mode": "open", "ssid": "GeckoTest" },
    "portal": {
      "domains": [
        {
          "name": "gecko-a.lab",
          "role": "primary",
          "pinned_spki_sha256": ["QwsHK0yXsUHme1g9/rqOHixTb8cCuCMBxYdaglLs1Q8="]
        }
      ]
    },
    "areas": [{
      "type": "MultiPolygon",
      "coordinates": [[[[8.54795,47.37715],[8.54895,47.37715],[8.54895,47.37815],[8.54795,47.37815],[8.54795,47.37715]]],[]]
    }],
    "areas_altitude": [[200,700]],
    "not_valid_after": "2031-09-26T15:28:00Z"
  }
]
```
For testing I modified the area so it contains the central Zürich area, so I can work from anywhere in the city. 
The app always queries the full altitude range, but keep generous altitude bounds anyway; indoor GPS
altitude is often off by tens of metres.

The server used to hold six older test certificates (`eth-a`, `eth-b`, `hb`,
`stack-low`, `stack-high`, `multi`). The golden tests
in the app were generated from them. 

## Captive portal

Router A runs [openNDS](https://github.com/openNDS/openNDS), which blocks new
clients and redirects them to the portal server on the laptop:

```
any http page → openNDS on A → http://portal.gecko-a.lab/?tok=…
              → https://portal.gecko-a.lab/        login page (primary)
              → https://pay.gecko-pay.lab/checkout payment page (delegate, optional)
              → openNDS lets the client through
```

| Domain | Role | Pinned key |
|---|---|---|
| `portal.gecko-a.lab` | primary | `dAJnHfG0hpOWSRhQOeWz1NBoiXHnUnB2y/40h9i2exk=` |
| `pay.gecko-pay.lab` | delegate | `u9H7ovOnPGAi3ptBn2262bEEeXNcndvZCmnr8gmG3+c=` |

Both domains point to `192.168.137.10` in A's DNS. The portal server has to be
running for logins to work (`portal/run-genuine.ps1`); see
[portal/README.md](../portal/README.md).

openNDS settings that matter (`/etc/config/opennds` on A):

- The portal is an external FAS (`fasremoteip 192.168.137.10`,
  `fasremotefqdn portal.gecko-a.lab`, `fasport 80`, `fas_secure_enabled 0`).
- The map server and the portal's HTTPS port are reachable before login
  (`preauthenticated_users`), so the app can check the network while logged out.
- Router B is a trusted client (`trustedmac 94:83:c4:97:c2:47`); otherwise B's
  uplink would sit behind A's portal.
- DHCP option 114 (Captive Portal API) is off. With it on, Android asked an API
  at `status.client`, which doesn't resolve, and showed "null is unreachable".

To see the portal again after logging in:

```bash
ssh mango-a "ndsctl json"             # find the client's MAC
ssh mango-a "ndsctl deauth <mac>"
```

The portal certificates are signed by a lab CA (`portal/keys/gecko-lab-ca.crt`).
For it to work on the tablet, you have to manually install it as a CA certificate, otherwise Android's sign-in screen refuses the portal.
The app itself doesn't need it.

### Router B

B has no portal yet. Its DNS decides what it does with A's domains:

- No entry: B forwards DNS to A, so the tablet reaches A's real portal through
  B (a relay; the check passes, which is correct).
- `uci add_list dhcp.@dnsmasq[0].address='/gecko-a.lab/192.168.247.1'`: B
  answers for `gecko-a.lab` and its subdomains itself, with its own key
  (evil twin).

## Routers

The routers run OpenWrt 22.03 (GL.iNet firmware 4.3). SSH uses a dedicated key:

```
Host mango-a
  HostName 192.168.137.50
  User root
  IdentityFile ~/.ssh/id_mango
  IdentitiesOnly yes
Host mango-b
  HostName 192.168.8.115
  User root
  ProxyJump mango-a
  IdentityFile ~/.ssh/id_mango
  IdentitiesOnly yes
```

Dropbear reads keys from `/etc/dropbear/authorized_keys`. When installing a key
from PowerShell, pass it as an argument instead of piping the file; the pipe
adds a byte order mark and Dropbear silently ignores the key.

Notes:
- There is ~1 MB of free flash. OpenWrt starts services right after
  `opkg install`, so stop and disable them in the same command if needed.
- On A, mwan3 no longer pings public servers to decide whether the WAN is up.
  When those pings failed, it blocked the router's own traffic (`opkg update`
  failed with "Operation not permitted"). The old config is in
  `/root/mwan3-wan.backup`.

## Running the app

Build the `device` flavor and install it with `./gradlew installDeviceDebug`.

In the app, open `GeckoTest`, pick an access point (A or B), leave the domain
field empty and press "Connect & check". Android might ask for permission to
connect time. The app then detects the portal and checks it; the check
details list every hop. If the tablet is already logged in, there is no portal
to detect, and the app checks the registered domain directly instead. Typing a
domain skips detection and checks only that domain.

The connection the app makes is only visible to the app. To use the browser on
`GeckoTest`, force-stop the app first and connect through Android's settings.

Logs:

```
adb logcat -s PortalDiscovery PortalCheckLog CertProbe NetworkObserver GeckoClient
adb pull /sdcard/Android/data/com.thesis.geckowifi/files/portal-checks/
```

The second command fetches one JSON line per check, including all hops. The
portal server writes its own log to `portal/logs/`.

For location, the tablet has GPS. Indoors a mock location app (developer
options) gives more repeatable results.

## Troubleshooting

**No map server / no SSH to the routers.** Check that `Ethernet 4` (the USB
Ethernet adapter) has `192.168.137.1`. ICS sometimes drops the address,
especially after the laptop switches WiFi networks, and the adapter falls back
to `169.254.x.x`. The address was added permanently as a workaround; if it's
gone, try re-adding it as administrator:

```powershell
New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.137.1 -PrefixLength 24
```
If this doesn't work, the service probably froze and a device (laptop) restart is necessary. 

**Routers have no internet** (SSH and the map server work, `example.com`
doesn't). ICS stopped forwarding. Re-enable sharing on the WLAN adapter
(Properties --> Sharing, home network `Ethernet 4`); if that doesn't help,
reboot. Lab traffic doesn't depend on this, only package installs and the
portal's "internet after login" do. On a phone hotspot, some carriers drop
traffic from devices behind the laptop altogether.

**"Connected" but no internet on the tablet.** The WiFi icon only means the
tablet is associated with the router. Check with `adb shell ping 1.1.1.1`.

## Limitations of Setup

- B is daisy-chained behind A. A real attacker has an independent uplink; here
  B's DNS defaults to A, and B can't be tested for blocking the map server
  independently.
- GECKO authenticates the portal server, not the radio. If B relays traffic to
  A's real portal, the check passes; traffic outside that TLS connection still
  goes through B.
- The app only sees redirects, checks once after joining, and its probe could
  be told apart from a browser (see [portal-detection.md](portal-detection.md)).
- The portal runs on the laptop rather than on the access point, which is
  common for real hotspots (cloud-hosted portals).
- The app picks the BSSID; normally Android picks the access point by signal
  strength.
- Root stands in for system-level integration. Results show what a
  system-level implementation could do, not what a normal app can.
- One tablet, one router model.
