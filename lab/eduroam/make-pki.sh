#!/usr/bin/env bash
# Test PKI for the eduroam onboarding demo (docs/eduroam-demo.md).
#
# Writes everything secret to lab-secrets/ (gitignored, back it up separately)
# and copies only public material into the app. Refuses to run if the PKI
# already exists: regenerating it changes the CA and the realm-signing key,
# which breaks every pin (the app's bundled CA, the pinned realm key, the
# signed realm records, the manually configured profile of scene 4).
#
# Runs in Git Bash (OpenSSL 1.1.1) or WSL. Usage: lab/eduroam/make-pki.sh
set -euo pipefail
export MSYS2_ARG_CONV_EXCL="/O=" # Git Bash would otherwise rewrite "/O=.../CN=..." into a Windows path

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/lab-secrets"
PKI="$OUT/pki"
RAW="$ROOT/app/src/main/res/raw"

if [ -e "$PKI" ]; then
    echo "lab-secrets/pki already exists. Not regenerating: it would break every pin." >&2
    echo "Delete lab-secrets/ by hand (after backing it up) if you really want a new PKI." >&2
    exit 1
fi
mkdir -p "$PKI" "$OUT/realm" "$OUT/radius" "$RAW"
cd "$PKI"

# Our own minimal config, so OpenSSL's default one adds no extensions of its
# own (it would duplicate subjectKeyIdentifier, which makes the CA invalid).
cat > req.cnf <<'CNF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = unused
[ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
CNF

ca() { # name, CN
    openssl req -config req.cnf -extensions ca -x509 -newkey rsa:2048 -nodes -sha256 -days 1825 \
        -keyout "$1.key" -out "$1.pem" -subj "/O=GECKO Lab/CN=$2" 2>/dev/null
}

leaf() { # name, issuer, CN, SAN list
    openssl req -config req.cnf -newkey rsa:2048 -nodes -sha256 -keyout "$1.key" -out "$1.csr" \
        -subj "/O=GECKO Lab/CN=$3" 2>/dev/null
    cat > "$1.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$4
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid
EOF
    openssl x509 -req -in "$1.csr" -CA "$2.pem" -CAkey "$2.key" -CAcreateserial \
        -days 730 -sha256 -extfile "$1.ext" -out "$1.pem" 2>/dev/null
    rm "$1.csr" "$1.ext"
}

# Genuine side. One FreeRADIUS EAP module serves both the onboarding identity
# and homeuni.test, so one certificate carries both names (demo shortcut).
ca gecko-lab-ca "GECKO-Lab-CA"
leaf radius-legit gecko-lab-ca "radius.homeuni.test" "DNS:radius.homeuni.test,DNS:gecko-onboard.sp.test"

# Attacker B1: its own CA, claiming the same names. Fails both the onboarding
# pin and the realm pin.
ca rogue-ca "Rogue-CA"
leaf radius-rogue rogue-ca "radius.homeuni.test" "DNS:radius.homeuni.test,DNS:gecko-onboard.sp.test"

# Attacker B2: models "the attacker can get a valid onboarding certificate".
# Signed by the genuine CA, but only for the onboarding name, so it passes the
# onboarding session and still fails the realm pin (radius.homeuni.test).
leaf radius-b2 gecko-lab-ca "gecko-onboard.sp.test" "DNS:gecko-onboard.sp.test"

openssl dhparam -out dh.pem 2048 2>/dev/null
rm req.cnf ./*.srl

# Realm-record signing key: ECDSA P-256, the same scheme as the map head.
cd "$OUT/realm"
openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null | openssl pkcs8 -topk8 -nocrypt -out realm-signing.key
openssl ec -in realm-signing.key -pubout -outform DER -out realm-signing.pub.der 2>/dev/null

# RADIUS shared secrets and the test account's password.
cat > "$OUT/radius/secrets.env" <<EOF
RADIUS_SECRET_A=$(openssl rand -hex 16)
RADIUS_SECRET_B=$(openssl rand -hex 16)
ALICE_PASSWORD=$(openssl rand -hex 6)
EOF

# Public material for the app: the onboarding CA (Android requires server
# validation even for the untrusted onboarding session).
openssl x509 -in "$PKI/gecko-lab-ca.pem" -outform DER -out "$RAW/gecko_onboarding_ca.der"

echo "PKI written to lab-secrets/. Back it up now."
echo "Onboarding CA copied to app/src/main/res/raw/gecko_onboarding_ca.der"
echo "Realm-signing public key (pin it in app/.../eduroam/PinnedRealmKey.kt):"
base64 -w0 "$OUT/realm/realm-signing.pub.der" 2>/dev/null || base64 "$OUT/realm/realm-signing.pub.der" | tr -d '\n'
echo
