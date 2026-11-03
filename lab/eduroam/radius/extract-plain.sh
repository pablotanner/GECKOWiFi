#!/usr/bin/env bash
# Reads attacker B3's captured-plain.log (written by the capture_plain overlay in
# setup-instances.sh) and prints, per unique capture, the CLEARTEXT password the
# client handed over via EAP-GTC. Lab only; run it against your own test account.
#
#   lab/eduroam/radius/extract-plain.sh [attacker-b3]
#
# Unlike the MSCHAPv2 hash (extract-hashes.sh), there is nothing to crack here:
# B3 offers EAP-GTC, and a client that accepts the downgrade sends the password
# in the clear inside the tunnel. A capture only appears if the client actually
# performed GTC - i.e. trusted the rogue certificate AND let the inner method be
# downgraded. If instead you see lines from extract-hashes.sh attacker-b3, the
# client refused GTC and fell back to MSCHAPv2. Empty both ways = the client
# aborted (e.g. a GECKO-pinned profile, which never trusts the cert).
set -euo pipefail

name=${1:-attacker-b3}
f="$HOME/gecko-radius/$name/captured-plain.log"

if [ ! -s "$f" ]; then
    echo "No cleartext captures in $f"
    echo "(empty = no client sent a GTC password. Either the client refused the"
    echo " downgrade and fell back to MSCHAPv2 - check extract-hashes.sh $name -"
    echo " or it never trusted the certificate at all, e.g. a GECKO-pinned profile.)"
    exit 0
fi

echo "== cleartext captures in $f =="
sort -u "$f" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    user=${line%%:*}
    pass=${line#*:}
    [ "$user" = "$line" ] && continue        # not our format
    [ -z "$pass" ] && continue
    echo
    echo "  user:     $user"
    echo "  password: $pass      (sent in cleartext via EAP-GTC - no cracking needed)"
done
echo
echo "Reset before a fresh run:  rm -f $f"
