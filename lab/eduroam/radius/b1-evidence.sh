#!/usr/bin/env bash
# Scene 2 vs scene 4 without hardware: restarts attacker B1 with a fresh log,
# then (1) alice with the pinned profile the app installs, (2) alice with an
# unpinned profile as configured by hand. Prints what the attacker's log holds
# after each. Expected: (1) no inner credentials, (2) alice's MSCHAPv2 exchange.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
# shellcheck disable=SC1091
. <(tr -d '\r' < "$REPO/lab-secrets/radius/secrets.env")
bash "$REPO/lab/eduroam/radius/stop.sh" attacker-b1 >/dev/null
sleep 1
bash "$REPO/lab/eduroam/radius/run.sh" attacker-b1 bg >/dev/null
sleep 4
log=$(ls -t "$HOME"/gecko-radius/logs/attacker-b1-*.log | head -1)
conf=$(mktemp)
trap 'rm -f "$conf"' EXIT

attempt() { # extra network lines
    cat > "$conf" <<EOF
network={
	key_mgmt=WPA-EAP
	eap=PEAP
	identity="alice@homeuni.test"
	anonymous_identity="anonymous@homeuni.test"
	password="$ALICE_PASSWORD"
	phase2="auth=MSCHAPV2"
$1
}
EOF
    eapol_test -c "$conf" -a 127.0.0.1 -p 1814 -s testing123 -t 10 2>&1 | tail -1
}

echo "### (1) pinned profile (what the app installs): $(attempt "	ca_cert=\"$REPO/lab-secrets/pki/gecko-lab-ca.pem\"
	domain_match=\"radius.homeuni.test\"")"
bash "$REPO/lab/eduroam/radius/count-inner.sh" "$log"
echo
echo "### (2) unpinned profile (scene 4 baseline): $(attempt "")"
bash "$REPO/lab/eduroam/radius/count-inner.sh" "$log"
echo
echo "attacker log: $log"
