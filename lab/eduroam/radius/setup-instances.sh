#!/usr/bin/env bash
# Builds the three FreeRADIUS instances of the eduroam demo in ~/gecko-radius
# (docs/eduroam-demo.md), each a copy of the stock /etc/freeradius/3.0 with a
# small overlay. Never touches /etc/freeradius itself; re-run it any time to
# rebuild the copies (e.g. after changing this script).
#
#   legit        port 1812  GECKO-Lab-CA cert, alice + gecko (VLAN 99, 60 s), both realms local
#   attacker-b1  port 1814  Rogue-CA cert (scene 2)
#   attacker-b2  port 1816  GECKO-Lab-CA cert for gecko-onboard.sp.test only (scene 3)
#
# Run inside WSL from the repo: bash lab/eduroam/radius/setup-instances.sh
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
SECRETS="$REPO/lab-secrets"
BASE="$HOME/gecko-radius"
STOCK=/etc/freeradius/3.0

sudo test -d "$STOCK" || { echo "FreeRADIUS 3 not installed ($STOCK missing)" >&2; exit 1; }
[ -f "$SECRETS/radius/secrets.env" ] || { echo "run lab/eduroam/make-pki.sh first" >&2; exit 1; }
# shellcheck disable=SC1091
. <(tr -d '\r' < "$SECRETS/radius/secrets.env")

build() { # name, cert basename, ca basename, shared secret, authorize file content, auth port
    local name=$1 cert=$2 ca=$3 secret=$4 users=$5 port=$6
    local dir="$BASE/$name"
    sudo rm -rf "$dir"
    sudo cp -a "$STOCK" "$dir"
    sudo chown -R "$USER:" "$dir"
    chmod -R u+rwX "$dir"

    # Runs as root from a user-owned copy: drop the switch to freerad, which
    # couldn't read the copied keys. Lab only.
    sed -i -E 's/^(\s*)(user|group) = freerad/\1# \2 = freerad  (gecko: runs as root, lab only)/' "$dir/radiusd.conf"

    mkdir -p "$dir/gecko-certs"
    cp "$SECRETS/pki/$cert.pem" "$dir/gecko-certs/server.pem"
    cp "$SECRETS/pki/$cert.key" "$dir/gecko-certs/server.key"
    cp "$SECRETS/pki/$ca.pem" "$dir/gecko-certs/ca.pem"
    cp "$SECRETS/pki/dh.pem" "$dir/gecko-certs/dh.pem"
    chmod 600 "$dir/gecko-certs/server.key"

    # Clients: everything arrives through lab/eduroam/udp-relay.py, so the
    # source is the WSL gateway (some 172.16/12 address that changes on
    # reboot). The relay enforces who may send; the secret does the rest.
    cat > "$dir/clients.conf" <<EOF
client localhost {
	ipaddr = 127.0.0.1
	secret = testing123
}
client wsl-relay {
	ipaddr = 172.16.0.0/12
	secret = $secret
	require_message_authenticator = yes
}
EOF

    # EAP: PEAP-MSCHAPv2 primary (docs/eduroam-decisions.md, D3), TTLS too.
    rm -f "$dir/mods-enabled/eap"
    cat > "$dir/mods-enabled/eap" <<'EOF'
eap {
	default_eap_type = peap
	timer_expire = 60
	ignore_unknown_eap_types = no
	cisco_accounting_username_bug = no
	max_sessions = ${max_requests}

	tls-config tls-common {
		private_key_file = ${confdir}/gecko-certs/server.key
		certificate_file = ${confdir}/gecko-certs/server.pem
		ca_file = ${confdir}/gecko-certs/ca.pem
		dh_file = ${confdir}/gecko-certs/dh.pem
		cipher_list = "DEFAULT"
		cipher_server_preference = no
		ecdh_curve = "prime256v1"
		tls_min_version = "1.2"
		tls_max_version = "1.2"
		cache {
			enable = no
		}
		verify {
		}
	}

	peap {
		tls = tls-common
		default_eap_type = mschapv2
		copy_request_to_tunnel = no
		use_tunneled_reply = no
		virtual_server = "inner-tunnel"
	}

	ttls {
		tls = tls-common
		default_eap_type = md5
		copy_request_to_tunnel = no
		use_tunneled_reply = no
		virtual_server = "inner-tunnel"
	}

	mschapv2 {
	}

	md5 {
	}

	gtc {
		auth_type = PAP
	}
}
EOF

    # Own ports, so the three instances run side by side: auth on $port, acct
    # on $port+1 (the stock listen sections say 0 = the default 1812/1813),
    # and the inner tunnel's local test listener (stock 18120) moved along.
    # (radiusd -i/-p would be simpler, but it bypasses the "default" virtual server.)
    # Whole listen{} blocks are buffered: in the acct block "port" comes before "type".
    awk -v auth="$port" -v acct="$((port + 1))" '
        function flush() { for (i = 1; i <= n; i++) { l = buf[i]; if (t && l ~ /^[ \t]*port = 0$/) sub(/port = 0/, "port = " t, l); print l }; n = 0; t = "" }
        !depth && /^[ \t]*listen \{/ { depth = 1; n = 0; t = ""; buf[++n] = $0; next }
        depth {
            buf[++n] = $0
            if ($0 ~ /^[ \t]*type = auth/) t = auth
            if ($0 ~ /^[ \t]*type = acct/) t = acct
            o = gsub(/\{/, "{"); c = gsub(/\}/, "}"); depth += o - c
            if (!depth) flush()
            next
        }
        { print }' "$dir/sites-enabled/default" > "$dir/default.tmp" && mv "$dir/default.tmp" "$dir/sites-enabled/default"
    sed -i -E "s/port = 18120/port = $((18120 + port - 1812))/" "$dir/sites-enabled/inner-tunnel"

    # Users are looked up inside the tunnel only, so outer identities never
    # pick up reply attributes of their own.
    sed -i -E 's/^(\t)files$/\1# files  (gecko: users are looked up inside the tunnel only)/' "$dir/sites-enabled/default"
    printf '%s\n' "$users" > "$dir/mods-config/files/authorize"

    # Both realms are handled locally, never proxied. In production only
    # gecko.<sp-domain> would be local; homeuni.test would be proxied to the
    # federation (the visitor's home IdP). nostrip: the users file keys on the
    # full user@realm.
    cat >> "$dir/proxy.conf" <<'EOF'

# --- gecko eduroam demo ---
realm gecko.sp.test {
	nostrip
}
realm homeuni.test {
	nostrip
}
EOF
}

mkdir -p "$BASE/logs"

# --- legit ---
LEGIT_USERS=$(cat <<EOF
# Onboarding identity (Design A, phase 1): public credentials, walled-garden
# VLAN 99, at most 60 s. Only accepted when it is the outer identity too
# (policy gecko_inner_check).
gecko@gecko.sp.test	Cleartext-Password := "gecko"
	Tunnel-Type = VLAN,
	Tunnel-Medium-Type = IEEE-802,
	Tunnel-Private-Group-Id = "99",
	Session-Timeout = 60

# Test account of the home IdP homeuni.test (normal access).
alice@homeuni.test	Cleartext-Password := "$ALICE_PASSWORD"
EOF
)
build legit radius-legit gecko-lab-ca "$RADIUS_SECRET_A" "$LEGIT_USERS" 1812

cat > "$BASE/legit/policy.d/gecko" <<'EOF'
# The onboarding identity must be the same outside and inside the tunnel. A
# GECKO outer identity is handled locally and never proxied, so it must never
# carry a real inner identity (that would hand the inner exchange to the SP),
# and the walled garden must not be reachable with a real outer identity.
gecko_inner_check {
	if (&outer.request:User-Name == "gecko@gecko.sp.test" && &User-Name != "gecko@gecko.sp.test") {
		reject
	}
	if (&User-Name == "gecko@gecko.sp.test" && &outer.request:User-Name != "gecko@gecko.sp.test") {
		reject
	}
}

# Inner reply attributes for the AP (VLAN, session timeout) go to the outer
# Access-Accept via session-state; the stock outer post-auth copies it.
gecko_copy_reply {
	if (&reply:Tunnel-Private-Group-Id) {
		update outer.session-state {
			&Tunnel-Type := &reply:Tunnel-Type
			&Tunnel-Medium-Type := &reply:Tunnel-Medium-Type
			&Tunnel-Private-Group-Id := &reply:Tunnel-Private-Group-Id
		}
	}
	if (&reply:Session-Timeout) {
		update outer.session-state {
			&Session-Timeout := &reply:Session-Timeout
		}
	}
}
EOF
sed -i -E '0,/^authorize \{/s//authorize {\n\tgecko_inner_check/' "$BASE/legit/sites-enabled/inner-tunnel"
sed -i -E '0,/^post-auth \{/s//post-auth {\n\tgecko_copy_reply/' "$BASE/legit/sites-enabled/inner-tunnel"

# --- attackers: accept the public GECKO identity, log everything ---
ATTACKER_USERS='gecko@gecko.sp.test	Cleartext-Password := "gecko"'
build attacker-b1 radius-rogue rogue-ca "$RADIUS_SECRET_B" "$ATTACKER_USERS" 1814
build attacker-b2 radius-b2 gecko-lab-ca "$RADIUS_SECRET_B" "$ATTACKER_USERS" 1816

for name in legit attacker-b1 attacker-b2; do
    sudo freeradius -C -d "$BASE/$name" >/dev/null 2>&1 \
        && echo "$name: config OK" \
        || { echo "$name: config check FAILED; see: sudo freeradius -XC -d $BASE/$name" >&2; exit 1; }
done
echo "Instances in $BASE. Start one with lab/eduroam/radius/run.sh <legit|attacker-b1|attacker-b2>"
