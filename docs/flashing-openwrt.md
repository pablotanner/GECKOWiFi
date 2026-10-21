# Flashing vanilla OpenWrt on the Mangos

Why: GL firmware can't run an enterprise AP on these routers (no `8021xd`;
[eduroam-decisions.md](eduroam-decisions.md), D14). Vanilla OpenWrt uses
hostapd, and its full variant (`wpad-mbedtls`) has the RADIUS client, EAP and
dynamic VLAN, so the walled garden (phase 3b) becomes possible too.

Plan: **Router B first** (attacker, least to lose), prove WPA2-Enterprise +
dynamic VLAN + stable Wi-Fi on it, only then Router A. Nothing here has been
run yet; every step on a router needs your go.

## What was checked (2026-10-20)

| Fact | Value | Source |
|---|---|---|
| Model / board name | GL-MT300N-V2, `glinet,gl-mt300n-v2` (both routers) | `/tmp/sysinfo/board_name` |
| Flash / RAM | 16 MB / 128 MB | OpenWrt ToH, `/proc/mtd` |
| Supported by current OpenWrt | yes: 24.10.4 and 25.12.6 have official images | `downloads.openwrt.org/releases/<v>/targets/ramips/mt76x8/profiles.json` |
| Default image's wpad | `wpad-basic-mbedtls`: built with `CONFIG_NO_RADIUS=y`, **no** enterprise AP | `package/network/services/hostapd/files/hostapd-basic.config` |
| Full wpad | `wpad-mbedtls`: `CONFIG_EAP`, RADIUS, `CONFIG_FULL_DYNAMIC_VLAN` | `hostapd-full.config` |
| Wi-Fi driver | `kmod-mt7603` (mt76) instead of GL's MediaTek driver | image manifest |
| GL's sysupgrade accepts the image | yes without `-F`: board in `supported_devices`, `compat_version` 1.0 on both sides, GL's `fwtool.sh` has no vendor lock | `/lib/upgrade/fwtool.sh` on B, image metadata |
| `sysupgrade -f <archive>` | restores that archive after flashing instead of the current config (also in GL's copy) | `/sbin/sysupgrade` |

## The image

Built with OpenWrt's official image builder service (ASU), 25.12.6, default
packages with `-wpad-basic-mbedtls +wpad-mbedtls +luci +opennds`:

- `lab-secrets/firmware/openwrt-25.12.6-c768b82616fd-ramips-mt76x8-glinet_gl-mt300n-v2-squashfs-sysupgrade.bin`
- SHA-256 `eb5c72acd759b5c5c6313ff4ceb58cb959e145e75e1857025603df9782ee050b`, 6.5 MB (about 9 MB of flash left)
- Contains `wpad-mbedtls 2025.08.26`, `opennds 10.3.1`, `kmod-mt7603`, LuCI
- Request in `lab-secrets/firmware/asu-request.json`; rebuild the same image at
  <https://firmware-selector.openwrt.org> (GL-MT300N V2, 25.12.6, "Customize
  installed packages") or `POST https://sysupgrade.openwrt.org/api/v1/build`.

25.12 uses `apk` instead of `opkg` (`apk add`, `apk update`).

## Already backed up

- Full config archives: `lab-secrets/router-backups/mango-{a,b}-20261020-1244.tar.gz`
- Redacted configs in git: `lab-configs/`
- u-boot, u-boot-env and **factory** (Wi-Fi calibration, unique per unit; sysupgrade doesn't
  touch it, but if it were ever lost the radio would be uncalibrated):
  `lab-secrets/router-backups/mtd/mango-{a,b}-mtd{0,1,2}.bin`, checksums verified against the routers.

**Still to get (browser):** GL's stock firmware for reverting, from
<https://dl.gl-inet.com/?model=mt300n-v2> (4.3.x; for U-Boot recovery take the
file marked for U-Boot / "uboot"). Put it in `lab-secrets/firmware/`.

## Router B, step by step

B's first boot is preseeded (`lab/eduroam/openwrt/mango-b/etc/uci-defaults/99-gecko-lab`):
LAN `192.168.247.1`; WAN DHCP from A (same MAC, so A's reservation `192.168.8.115` still applies);
SSH from A's LAN, keys only, with the current `authorized_keys`; open
`GeckoTest` on channel 11 in a section named `wifi2g` (as the scenario
scripts expect); the DNS overrides; the current openNDS config; a random root
password (`lab-secrets/firmware/root-passwords.env`), because B's LAN is an open Wi-Fi.
So `ssh mango-b` should work again right after the flash.

```bash
# 0. Fresh snapshot and preseed (Git Bash, repo root)
bash lab/eduroam/pull-router-configs.sh mango-b
bash lab/eduroam/openwrt/make-preseed.sh mango-b

# 1. Copy both files to B's RAM. -O: dropbear has no SFTP, Windows' scp uses SFTP by default.
IMG=lab-secrets/firmware/openwrt-25.12.6-c768b82616fd-ramips-mt76x8-glinet_gl-mt300n-v2-squashfs-sysupgrade.bin
scp -O "$IMG" lab-secrets/firmware/mango-b-preseed.tar.gz mango-b:/tmp/

# 2. On B: check the hash, then let sysupgrade test the image (-T: test only, nothing is written)
ssh mango-b 'sha256sum /tmp/openwrt-*.bin; sysupgrade -T -f /tmp/mango-b-preseed.tar.gz /tmp/openwrt-*.bin && echo TEST-OK'

# 3. Flash. The SSH session drops; do not power off. Wait ~4 minutes.
ssh mango-b 'sysupgrade -v -f /tmp/mango-b-preseed.tar.gz /tmp/openwrt-*.bin'

# 4. B has a new SSH host key now
ssh-keygen -R 192.168.8.115
ssh mango-b 'cat /etc/openwrt_release; ls /etc/uci-defaults; wifi status | grep -E "\"up\"|ssid"; uci show wireless.wifi2g'
```

`ls /etc/uci-defaults` should be empty (the script ran and was removed).
Then check from the laptop: `GeckoTest` from B visible, `lab/scenarios/reset.ps1`
runs, the tablet gets `192.168.247.x` on B.

**If `ssh mango-b` doesn't come back:** B runs, but the preseed didn't apply.
Plug the laptop's Ethernet into **B's LAN port** (this interrupts ICS to A),
add an address in B's subnet (admin PowerShell:
`New-NetIPAddress -InterfaceAlias 'Ethernet 4' -IPAddress 192.168.247.2 -PrefixLength 24`,
or `192.168.1.2` if B came up with OpenWrt's default `192.168.1.1`), then `ssh root@192.168.247.1`
(or `.1.1`) and look at `logread`. Remove the extra address afterwards.

**If B doesn't boot (bricked):** U-Boot web recovery, which survives any
sysupgrade (GL docs, "Debrick via U-Boot"):
1. Power off, laptop Ethernet into B's LAN port (only that port).
2. Hold Reset, power on, release when the LED has **flashed 5 times**.
3. Laptop to `192.168.1.2/255.255.255.0`, browse `http://192.168.1.1`.
4. Upload GL's U-Boot firmware (or the OpenWrt sysupgrade image), wait ~3 minutes.
5. GL firmware comes back with defaults at `192.168.8.1`: restore
   `lab-secrets/router-backups/mango-b-20261020-1244.tar.gz` in its admin panel / LuCI.

**Going back to GL on purpose:** the same U-Boot procedure with GL's firmware, then restore the archive.

## Router B: done (2026-10-20)

Flashed with the steps above; B runs OpenWrt 25.12.6, reachable as `ssh mango-b`,
`GeckoTest` on channel 11, openNDS 10.3.1, DNS overrides, SSH firewall rule.
Lessons (all fixed in the tooling, so A won't hit them):

- **Dropbear has no Ed25519 here** (small-flash build): `id_mango` is
  rejected. Added `~/.ssh/id_mango_rsa` (RSA 4096) to B and to the
  `mango-b`/`mango-b-ui` entries in `~/.ssh/config`; `make-preseed.sh` now
  always adds it.
- **25.12 disables the generated Wi-Fi interface section**, not the radio:
  the first-boot script now sets `wireless.wifi2g.disabled='0'` too.
- `sysupgrade` printed `Command failed: ubus call system sysupgrade ... (Connection failed)`
  and LED errors; harmless, the flash went through.
- **Direct cable to B's LAN:** openNDS treats the laptop as an unauthenticated
  portal client and blocks IPv4 (ping, LuCI). IPv6 isn't filtered: B's LAN
  answers at `fd7c:1fd1:884b::1` (`ssh -i ~/.ssh/id_mango_rsa root@fd7c:1fd1:884b::1`).
- openNDS warns it wants `dnsmasq-full` (GL had it) for FQDN-based walled
  gardens/blocklists; B's config only uses IP-based pre-auth rules, and DNS
  overrides work. Open: swap to `dnsmasq-full` (`apk add dnsmasq-full`
  after removing `dnsmasq`), and include it in A's image.
- SSH through A sometimes drops right after replugging the laptop cable
  ("Software caused connection abort"); retry after a few seconds.

## After B is up: proving the point of the exercise

1. **Enterprise AP (B1 mode)**, a new disabled section first (radio power low,
   only while testing):
   ```sh
   uci set wireless.eduroam_b1=wifi-iface
   uci set wireless.eduroam_b1.device='radio0'
   uci set wireless.eduroam_b1.mode='ap'
   uci set wireless.eduroam_b1.network='lan'
   uci set wireless.eduroam_b1.ssid='eduroam-lab'
   uci set wireless.eduroam_b1.encryption='wpa2'
   uci set wireless.eduroam_b1.auth_server='192.168.137.1'
   uci set wireless.eduroam_b1.auth_port='1814'
   uci set wireless.eduroam_b1.auth_secret='<RADIUS_SECRET_B>'
   uci set wireless.eduroam_b1.disabled='1'
   uci commit wireless
   ```
   Note hostapd's option names under vanilla OpenWrt: `auth_server` /
   `auth_port` / `auth_secret` (GL's `server`/`port`/`key` were for its MediaTek configurator).
2. Enable it with `wifi`, start `attacker-b1` and the relay, connect the Tab
   with a manual unpinned profile (scene 4) and with the app (scene 2),
   `count-inner.sh` on the attacker log.
3. **Dynamic VLAN** (for A's walled garden later): `option dynamic_vlan '2'`,
   `option vlan_tagged_interface`/`vlan_bridge` or `vlan_file`, and a VLAN 99
   network + firewall zone. To be designed once B has shown that the attributes
   from the legit RADIUS (`Tunnel-Private-Group-Id = 99`) arrive.
4. Snapshot: `bash lab/eduroam/pull-router-configs.sh mango-b`, commit, `lab-configs/CHANGELOG.md`.

## Router A: done (2026-10-20)

Flashed the same way (image with `dnsmasq-full` in addition; first-boot config
`lab/eduroam/openwrt/mango-a`), back after 282 s with WAN, LAN, B's
reservation, DNS overrides and the genuine portal as under GL. New lesson:
**openNDS 10.3.1 has no established/related rule in its client-to-router chain**,
so A could not reach B (SSH, ping) - fixed by `gecko-opennds-fix` (in A's
first-boot config now). The eduroam SP role came afterwards with `sp-legit.sh`.

## Router A (notes from before the flash)

Same image; the preseed needs more (static WAN `192.168.137.50`, LAN
`192.168.8.1`, DHCP reservation for B, openNDS as the genuine portal with
B as trusted MAC, firewall for map server / portal pre-auth, mwan3 not needed).
Prepared after B is proven. A carries the whole lab's uplink, so it goes last.

## Sources

- OpenWrt Table of Hardware, GL-MT300N V2: <https://openwrt.org/toh/gl.inet/gl-mt300n_v2>
- OpenWrt release metadata: <https://downloads.openwrt.org/releases/25.12.6/targets/ramips/mt76x8/profiles.json>
- hostapd variants: <https://github.com/openwrt/openwrt/tree/openwrt-24.10/package/network/services/hostapd>
- GL.iNet U-Boot recovery: <https://docs.gl-inet.com/router/en/4/faq/debrick/>
- GL.iNet firmware downloads: <https://dl.gl-inet.com/?model=mt300n-v2>
