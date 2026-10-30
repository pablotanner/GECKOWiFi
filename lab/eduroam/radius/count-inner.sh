#!/usr/bin/env bash
# Evidence for scenes 2-4: what a RADIUS log saw of each identity, and what
# reached the inner tunnel (where the password exchange happens).
# "Zero inner credentials" = no inner-tunnel request with a non-GECKO identity.
#
#   lab/eduroam/radius/count-inner.sh ~/gecko-radius/logs/attacker-b1-<time>.log
set -euo pipefail
log=${1:?usage: count-inner.sh <freeradius debug log>}

# Request lines look like "(11) server inner-tunnel {" ... "(11) } # server inner-tunnel";
# the unnumbered ones are the config dump at startup.
START='^[(][0-9]+[)] server inner-tunnel [{]'
END='^[(][0-9]+[)] [}] # server inner-tunnel'

echo "== identities seen (outer and inner) =="
grep -oE 'User-Name = "[^"]*"' "$log" | sort | uniq -c || echo "  none"

echo "== requests processed by the inner tunnel =="
echo "  $(grep -cE "$START" "$log" || true)"

echo "== inner-tunnel identities other than the public GECKO one =="
leaked=$(awk -v s="$START" -v e="$END" '$0 ~ s {inner=1} $0 ~ e {inner=0} inner && /User-Name = "/' "$log" \
    | grep -oE 'User-Name = "[^"]*"' | grep -v 'fetch@gecko.ch' | sort | uniq -c || true)
if [ -z "$leaked" ]; then echo "  none: no inner credentials"; else echo "$leaked"; fi

echo "== inner MSCHAPv2 exchanges of identities other than the public GECKO one (crackable) =="
awk -v s="$START" -v e="$END" '
    $0 ~ s { inner = 1; user = "" }
    $0 ~ e { inner = 0 }
    inner && match($0, /User-Name = "[^"]*"/) { user = substr($0, RSTART + 13, RLENGTH - 14) }
    inner && /mschap: Creating challenge hash/ && user != "fetch@gecko.ch" { n[user]++ }
    END { for (u in n) printf "  %d  %s\n", n[u], u; if (length(n) == 0) print "  none" }' "$log"
