#!/usr/bin/env bash
# Router snapshot before/after each change (eduroam-brief.md, 4.2): a full
# `sysupgrade -b` archive into lab-secrets/router-backups/ (gitignored,
# restorable via LuCI or `sysupgrade -r`), and /etc/config + the driver's
# .dat + the package list into lab-configs/<router>/ for git, with keys and
# passwords redacted. Read-only on the routers apart from a temporary file
# in /tmp (RAM), which is deleted again.
#
#   bash lab/eduroam/pull-router-configs.sh            # both routers
#   bash lab/eduroam/pull-router-configs.sh mango-a
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
stamp=$(date +%Y%m%d-%H%M)
mkdir -p "$ROOT/lab-secrets/router-backups"
for r in "${@:-mango-a mango-b}"; do
    for router in $r; do
        backup="$ROOT/lab-secrets/router-backups/$router-$stamp.tar.gz"
        ssh -o BatchMode=yes "$router" "sysupgrade -b /tmp/gecko-backup.tar.gz >/dev/null 2>&1; cat /tmp/gecko-backup.tar.gz; rm -f /tmp/gecko-backup.tar.gz" > "$backup"
        tar -tzf "$backup" >/dev/null || { echo "$router: backup archive is broken" >&2; exit 1; }

        out="$ROOT/lab-configs/$router"
        rm -rf "$out/config"
        mkdir -p "$out"
        ssh -o BatchMode=yes "$router" "tar -C /etc -cf - config" | tar -C "$out" -xf -
        # GL firmware only: the MediaTek driver's settings. Vanilla OpenWrt has none.
        rm -f "$out/mt7628.dat" "$out/opkg-list-installed.txt" "$out/packages-installed.txt"
        ssh -o BatchMode=yes "$router" "cat /etc/wireless/mt7628/mt7628.dat 2>/dev/null" > "$out/mt7628.dat" || true
        [ -s "$out/mt7628.dat" ] || rm -f "$out/mt7628.dat"
        # opkg under GL / OpenWrt <= 24.10, apk from 25.12 on.
        ssh -o BatchMode=yes "$router" "if command -v apk >/dev/null; then apk list --installed; else opkg list-installed; fi" > "$out/packages-installed.txt"
        # Secrets stay in the archive only.
        sed -i -E "s/^(\s*option (key|password|auth_secret|acct_secret) ).*/\1'<redacted, see lab-secrets\/router-backups>'/" "$out"/config/*
        if [ -f "$out/mt7628.dat" ]; then
            sed -i -E "s/^(WPAPSK[0-9]*|RADIUS_Key|RADIUS_Acct_Key)=.+/\1=<redacted>/" "$out/mt7628.dat"
        fi
        echo "$router: $backup, configs in lab-configs/$router"
    done
done
