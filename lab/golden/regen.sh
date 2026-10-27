#!/usr/bin/env bash
# Regenerates the app's golden vectors (app/src/test/resources/golden/golden.json)
# against a throwaway geopki server (lab/density/stack.sh), never the demo map.
# Run inside WSL, from the repository root:
#
#   GEOPKI_DIR=~/Thesis/geopki-typed bash lab/golden/regen.sh
#
# GEOPKI_DIR is the geopki checkout to build the server and golden-dump from.
# The certificates are the six of geopki's former regen-golden.sh (overlap,
# altitude stack, two areas), now with types (map version 2), plus one already
# expired certificate that the release must remove again.
set -euo pipefail
export PATH="$PATH:/usr/local/go/bin"
export GEOPKI_DIR=${GEOPKI_DIR:-$HOME/Thesis/geopki-ext}
REPO=$(pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

python3 "$REPO/lab/golden/golden_fixtures.py" "$OUT"

bash lab/density/stack.sh up
bash lab/density/stack.sh load "$OUT/certs.json"

(cd "$GEOPKI_DIR" && go run ./cmd/golden-dump -address http://127.0.0.1:1236 \
    -points "$OUT/points.json" -out "$REPO/app/src/test/resources/golden/golden.json")
bash lab/density/stack.sh down
echo "done: app/src/test/resources/golden/golden.json"
