#!/usr/bin/env bash
# Checks the three RADIUS instances with eapol_test, from inside WSL, against
# what the demo relies on (docs/eduroam-demo.md). Start the instances first
# (run.sh). Uses the localhost client (secret testing123), not the relay.
#
#   bash lab/eduroam/radius/test-radius.sh
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
# shellcheck disable=SC1091
. <(tr -d '\r' < "$REPO/lab-secrets/radius/secrets.env")
CA="$REPO/lab-secrets/pki/gecko-lab-ca.pem"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
fails=0

# case name, port, expected SUCCESS|FAILURE, outer, inner, password, server name to pin ("" = unpinned),
# regex the output must match, regex it must not match (output flattened to one line)
check() {
    local name=$1 port=$2 want=$3 outer=$4 inner=$5 pass=$6 domain=$7 expect=${8:-} reject=${9:-}
    local pin=""
    [ -n "$domain" ] && pin="ca_cert=\"$CA\"
	domain_match=\"$domain\""
    cat > "$TMP/$name.conf" <<EOF
network={
	key_mgmt=WPA-EAP
	eap=PEAP
	identity="$inner"
	anonymous_identity="$outer"
	password="$pass"
	phase2="auth=MSCHAPV2"
	$pin
}
EOF
    eapol_test -c "$TMP/$name.conf" -a 127.0.0.1 -p "$port" -s testing123 -t 10 > "$TMP/$name.out" 2>&1
    local got
    got=$(tail -1 "$TMP/$name.out")
    local ok=yes
    [ "$got" = "$want" ] || ok=no
    local flat
    flat=$(tr '\n' ' ' < "$TMP/$name.out")
    if [ -n "$expect" ] && ! grep -qE "$expect" <<< "$flat"; then ok=no; fi
    if [ -n "$reject" ] && grep -qE "$reject" <<< "$flat"; then ok=no; fi
    if [ $ok = yes ]; then echo "ok   $name: $got"; else echo "FAIL $name: got '$got', want $want${expect:+ with /$expect/}"; fails=$((fails+1)); fi
}

G=gecko@gecko.sp.test
A=alice@homeuni.test
echo "-- legit (1812)"
# alice: normal access, no walled garden
check alice-pinned 1812 SUCCESS anonymous@homeuni.test $A "$ALICE_PASSWORD" radius.homeuni.test "" 'Tunnel-Private-Group-Id'
check alice-wrong-password 1812 FAILURE anonymous@homeuni.test $A wrong radius.homeuni.test
# eapol_test prints values in hex: VLAN = 13 (0x0d), IEEE-802 = 6, "99" = 0x3939
check gecko-vlan-99 1812 SUCCESS $G $G gecko gecko-onboard.sp.test 'Tunnel-Type\) length=6 +Value: 0000000d .*Tunnel-Private-Group-Id\) length=4 +Value: 3939'
check gecko-session-timeout 1812 SUCCESS $G $G gecko gecko-onboard.sp.test 'Session-Timeout\) length=6 +Value: 60 '
check gecko-outer-alice-inner 1812 FAILURE $G $A "$ALICE_PASSWORD" gecko-onboard.sp.test
check alice-outer-gecko-inner 1812 FAILURE anonymous@homeuni.test $G gecko gecko-onboard.sp.test
echo "-- attacker B1, rogue CA (1814)"
check b1-alice-pinned 1814 FAILURE anonymous@homeuni.test $A "$ALICE_PASSWORD" radius.homeuni.test
check b1-gecko-onboarding 1814 FAILURE $G $G gecko gecko-onboard.sp.test
echo "-- attacker B2, valid onboarding cert (1816)"
check b2-gecko-onboarding 1816 SUCCESS $G $G gecko gecko-onboard.sp.test
check b2-alice-pinned 1816 FAILURE anonymous@homeuni.test $A "$ALICE_PASSWORD" radius.homeuni.test
echo "-- scene 4 baseline: unpinned profile against B1 (fails, but the inner exchange reaches the attacker)"
check b1-alice-unpinned 1814 FAILURE anonymous@homeuni.test $A "$ALICE_PASSWORD" ""

echo
[ $fails -eq 0 ] && echo "all checks passed" || { echo "$fails check(s) failed; outputs were in $TMP"; trap - EXIT; }
exit $fails
