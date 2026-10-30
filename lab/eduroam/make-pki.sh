#!/usr/bin/env bash
# Test PKI for the eduroam onboarding demo (docs/eduroam-demo.md).
#
# All names come from names.env (the one place to change them). Writes secret
# material to lab-secrets/ (gitignored, back it up separately) and copies only
# public material into the app.
#
# Regenerating the CERTIFICATES is safe to repeat (e.g. after a rename): the
# realm-signing key and the RADIUS/password secrets are PRESERVED if they
# already exist, so the app's pinned realm key does not change. Only the CAs
# change, so afterwards re-copy the onboarding CA into the app (done here) and
# re-sign the realm records (docs/eduroam-reprovision.md).
#
# Runs in Git Bash (OpenSSL 1.1.1) or WSL. Usage: lab/eduroam/make-pki.sh
set -euo pipefail
export MSYS2_ARG_CONV_EXCL="/O=" # Git Bash would otherwise rewrite "/O=.../CN=..." into a Windows path

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/lab-secrets"
PKI="$OUT/pki"
RAW="$ROOT/app/src/main/res/raw"

# Canonical names.
. <(tr -d '\r' < "$HERE/names.env")

# Regenerating certs is the point of re-running this; wipe only the certs.
# Keep the realm-signing key and secrets so pins/passwords/secrets survive.
rm -rf "$PKI"
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
        -keyout "$1.key" -out "$1.pem" -subj "/O=$PKI_ORG/CN=$2" 2>/dev/null
}

leaf() { # name, issuer, CN, SAN list
    openssl req -config req.cnf -newkey rsa:2048 -nodes -sha256 -keyout "$1.key" -out "$1.csr" \
        -subj "/O=$PKI_ORG/CN=$3" 2>/dev/null
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
# and the home realm, so one certificate carries both names (demo shortcut).
ca "$LEGIT_CA_FILE" "$LEGIT_CA_CN"
leaf "$LEGIT_CERT_FILE" "$LEGIT_CA_FILE" "$RADIUS_SERVER_NAME" "DNS:$RADIUS_SERVER_NAME,DNS:$ONBOARD_SERVER_NAME"

# Attacker B1: its own CA, claiming the same names. Fails both the onboarding
# pin and the realm pin.
ca "$ROGUE_CA_FILE" "$ROGUE_CA_CN"
leaf "$ROGUE_CERT_FILE" "$ROGUE_CA_FILE" "$RADIUS_SERVER_NAME" "DNS:$RADIUS_SERVER_NAME,DNS:$ONBOARD_SERVER_NAME"

# Attacker B2: models "the attacker can get a valid onboarding certificate".
# Signed by the genuine CA, but only for the onboarding name, so it passes the
# onboarding session and still fails the realm pin.
leaf "$B2_CERT_FILE" "$LEGIT_CA_FILE" "$ONBOARD_SERVER_NAME" "DNS:$ONBOARD_SERVER_NAME"

openssl dhparam -out dh.pem 2048 2>/dev/null
rm -f req.cnf ./*.srl

# Realm-record signing key: ECDSA P-256, the same scheme as the map head.
# Preserved across runs so the app's pinned realm key (PinnedRealmKey.kt) stays
# valid; records are re-signed with it after a rename.
if [ ! -f "$OUT/realm/realm-signing.key" ]; then
    (cd "$OUT/realm"
     openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null | openssl pkcs8 -topk8 -nocrypt -out realm-signing.key
     openssl ec -in realm-signing.key -pubout -outform DER -out realm-signing.pub.der 2>/dev/null)
    echo "NEW realm-signing key; pin it in app/.../eduroam/PinnedRealmKey.kt:"
    base64 -w0 "$OUT/realm/realm-signing.pub.der" 2>/dev/null || base64 "$OUT/realm/realm-signing.pub.der" | tr -d '\n'
    echo
else
    echo "Kept existing realm-signing key (PinnedRealmKey.kt unchanged)."
fi

# RADIUS shared secrets and the test account's password. Preserved if present.
if [ ! -f "$OUT/radius/secrets.env" ]; then
    cat > "$OUT/radius/secrets.env" <<EOF
RADIUS_SECRET_A=$(openssl rand -hex 16)
RADIUS_SECRET_B=$(openssl rand -hex 16)
TEST_PASSWORD=$(openssl rand -hex 6)
EOF
    echo "NEW lab-secrets/radius/secrets.env (random TEST_PASSWORD; edit if you want a chosen one)."
else
    echo "Kept existing lab-secrets/radius/secrets.env."
fi

# Public material for the app: the onboarding CA (Android requires server
# validation even for the untrusted onboarding session).
openssl x509 -in "$PKI/$LEGIT_CA_FILE.pem" -outform DER -out "$RAW/gecko_onboarding_ca.der"

echo "PKI written to lab-secrets/pki ($LEGIT_CA_CN, $ROGUE_CA_CN). Back it up now."
echo "Onboarding CA copied to app/src/main/res/raw/gecko_onboarding_ca.der"
