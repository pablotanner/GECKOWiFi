#!/usr/bin/env bash
# Builds the config archive that `sysupgrade -f` restores on the first boot
# of vanilla OpenWrt (docs/flashing-openwrt.md): this directory's files for
# the router, its current authorized_keys and openNDS config (pulled now),
# and a root password generated once into lab-secrets/firmware/root-passwords.env.
#
#   bash lab/eduroam/openwrt/make-preseed.sh mango-b
#   -> lab-secrets/firmware/mango-b-preseed.tar.gz
set -euo pipefail
router=${1:?usage: make-preseed.sh mango-b}
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/lab/eduroam/openwrt/$router"
OUT="$ROOT/lab-secrets/firmware"
[ -d "$SRC" ] || { echo "no preseed files for $router in $SRC" >&2; exit 2; }
mkdir -p "$OUT"

pwfile="$OUT/root-passwords.env"
touch "$pwfile"
var="ROOT_PASSWORD_$(echo "$router" | tr 'a-z-' 'A-Z_')"
grep -q "^$var=" "$pwfile" || echo "$var=$(openssl rand -hex 8)" >> "$pwfile"
password=$(grep "^$var=" "$pwfile" | cut -d= -f2 | tr -d '\r')

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -r "$SRC/." "$stage/"
sed -i "s/@ROOT_PASSWORD@/$password/g" "$stage/etc/uci-defaults/99-gecko-lab"
# A Windows checkout (.gitattributes text=auto) may have CRLF; ash would choke on it.
find "$stage" -type f -exec sed -i 's/\r$//' {} +
chmod 755 "$stage/etc/uci-defaults/99-gecko-lab"
[ -d "$stage/usr/sbin" ] && chmod 755 "$stage"/usr/sbin/*

mkdir -p "$stage/etc/dropbear" "$stage/etc/config"
ssh -o BatchMode=yes "$router" "cat /etc/dropbear/authorized_keys" > "$stage/etc/dropbear/authorized_keys"
# Vanilla OpenWrt's dropbear on the Mango is built without Ed25519 (small-flash
# default), so an Ed25519 key alone locks you out. Always add the RSA key too.
RSA_PUB="$HOME/.ssh/id_mango_rsa.pub"
[ -f "$RSA_PUB" ] || { echo "missing $RSA_PUB (ssh-keygen -t rsa -b 4096 -f ~/.ssh/id_mango_rsa)" >&2; exit 1; }
grep -qF "$(awk '{print $2}' "$RSA_PUB")" "$stage/etc/dropbear/authorized_keys" || cat "$RSA_PUB" >> "$stage/etc/dropbear/authorized_keys"
chmod 600 "$stage/etc/dropbear/authorized_keys"
[ -s "$stage/etc/dropbear/authorized_keys" ] || { echo "no authorized_keys from $router" >&2; exit 1; }
# openNDS config as it is now (9.10 under GL; its options are the same in 10.x).
ssh -o BatchMode=yes "$router" "cat /etc/config/opennds" > "$stage/etc/config/opennds"

tar -C "$stage" --owner=0 --group=0 -czf "$OUT/$router-preseed.tar.gz" etc
echo "$OUT/$router-preseed.tar.gz:"
tar -tzvf "$OUT/$router-preseed.tar.gz"
echo "root password for $router: $pwfile ($var)"
