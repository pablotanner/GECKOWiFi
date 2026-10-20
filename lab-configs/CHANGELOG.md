# Router config changelog

Snapshots come from `lab/eduroam/pull-router-configs.sh`: `/etc/config`, the
MediaTek driver's `mt7628.dat` and `opkg list-installed` per router, with keys
and passwords redacted. The full archives (restorable with LuCI → System →
Backup/Flash Firmware → Restore, or `sysupgrade -r <file>`) are in
`lab-secrets/router-backups/`.

| Date | Router | Change | Archive |
|---|---|---|---|
| 2026-10-20 | A, B | Baseline before the eduroam demo (no changes) | `mango-{a,b}-20261020-1244.tar.gz` |
| 2026-10-20 | B | Flashed vanilla OpenWrt 25.12.6 (custom image with `wpad-mbedtls`, LuCI, openNDS 10.3.1; docs/flashing-openwrt.md), first-boot config `lab/eduroam/openwrt/mango-b`. Fixed by hand afterwards: RSA key added to `authorized_keys`, `wireless.wifi2g.disabled=0` | before: `mango-b-20261020-1317.tar.gz`, after: `mango-b-20261020-1346.tar.gz` |
