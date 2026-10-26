#!/usr/bin/env bash
# Throwaway geopki stack for the density experiment (lab/density/README.md),
# separate from the lab's demo server. Run inside WSL:
#
#   bash lab/density/stack.sh up            # start it
#   bash lab/density/stack.sh load <file>   # insert a make_certs.py file, release
#   bash lab/density/stack.sh status
#   bash lab/density/stack.sh down          # remove all of it again
#
# What it uses, and what it leaves alone:
# - PostgreSQL in its own Docker container (geopki-density-pg, 127.0.0.1:5433).
#   The WSL PostgreSQL and its databases (geopki_ext, geopki) are not touched.
# - one new tree in the lab's Trillian (deleted again by `down`).
# - a geopki server built from ~/Thesis/geopki-ext into ~/Thesis/density-exp,
#   with its own keys, on port 1235. The demo server on 1234 keeps running.
# - state (keys, tree id, logs) in ~/Thesis/density-exp, removed by `down`.
set -euo pipefail
export PATH="$PATH:/usr/local/go/bin"

GEOPKI=${GEOPKI_DIR:-$HOME/Thesis/geopki-ext}
TRILLIAN=${TRILLIAN_DIR:-$HOME/Thesis/trillian}
STATE=$HOME/Thesis/density-exp
PG=geopki-density-pg
PG_PORT=5433
PORT=1235
TRILLIAN_ADMIN=127.0.0.1:8090

cmd=${1:?usage: stack.sh up | load <file> | status | down}

psql_exp() { docker exec -i "$PG" psql -q -v ON_ERROR_STOP=1 -U density -d geopki_density "$@"; }

up() {
    [ -e "$STATE" ] && { echo "already up (or not cleaned up): $STATE exists; run 'down' first"; exit 1; }
    mkdir -p "$STATE" && chmod 700 "$STATE"

    echo "== PostgreSQL in Docker ($PG, 127.0.0.1:$PG_PORT)"
    docker run -d --name "$PG" -e POSTGRES_USER=density -e POSTGRES_PASSWORD=density \
        -e POSTGRES_DB=geopki_density -p 127.0.0.1:$PG_PORT:5432 postgres:14 >/dev/null
    until docker exec "$PG" pg_isready -q -U density -d geopki_density; do sleep 1; done
    sleep 2  # the image restarts once after its init scripts
    until docker exec "$PG" pg_isready -q -U density -d geopki_density; do sleep 1; done

    echo "== schema from $GEOPKI/database"
    for f in min_altitude_of_bit_string max_altitude_of_bit_string array_difference \
             array_intersect array_union smt_hash update_children_hashes; do
        psql_exp < "$GEOPKI/database/functions/$f.sql"
    done
    psql_exp < "$GEOPKI/database/tables/certificates.sql"
    psql_exp < "$GEOPKI/database/tables/nodes.sql"

    echo "== Trillian tree"
    local tree
    tree=$(cd "$TRILLIAN" && go run ./cmd/createtree --admin_server=$TRILLIAN_ADMIN \
        --display_name=density-experiment 2>"$STATE/createtree.log" | tail -1)
    [[ "$tree" =~ ^[0-9]+$ ]] || { echo "createtree failed, see $STATE/createtree.log"; exit 1; }
    echo "tree $tree"

    echo "== keys and server (port $PORT)"
    cat > "$STATE/env" <<EOF
DATABASE_URL=postgresql://density:density@127.0.0.1:$PG_PORT/geopki_density
PRIVATE_KEY=$(openssl ecparam -name prime256v1 -genkey -noout -outform DER | base64 -w0)
CERT_INSERT_KEY=$(openssl rand -hex 16)
CLOG_ID=$tree
EOF
    chmod 600 "$STATE/env"
    (cd "$GEOPKI" && go build -o "$STATE/geopki-server" ./cmd/geopki-server)
    (
        cd "$STATE"  # the server's working directory: nothing lands in the geopki checkout
        set -a; . ./env; set +a
        nohup ./geopki-server --address=0.0.0.0 --port=$PORT \
            --trillian-address=$TRILLIAN_ADMIN --clog-id="$CLOG_ID" > server.log 2>&1 &
        echo $! > server.pid
    )
    for _ in $(seq 60); do
        curl -s -o /dev/null "http://127.0.0.1:$PORT/" && { echo "up: http://localhost:$PORT"; return; }
        sleep 1
    done
    echo "server didn't start, see $STATE/server.log"; exit 1
}

load() {
    local file=${1:?usage: stack.sh load <file written by make_certs.py>}
    [ -f "$STATE/env" ] || { echo "not up"; exit 1; }
    set -a; . "$STATE/env"; set +a
    # insert in batches of 500, refusing anything that isn't synthetic
    python3 - "$file" "$PORT" "$CERT_INSERT_KEY" <<'EOF'
import gzip, json, sys, time, urllib.request
path, port, key = sys.argv[1], sys.argv[2], sys.argv[3]
certs = json.load(open(path, encoding="utf-8"))["certificates"]
bad = [c["certificate_id"] for c in certs if not c["certificate_id"].startswith("synth-")]
if bad:
    sys.exit(f"refusing: {len(bad)} certificate(s) aren't synthetic, e.g. {bad[0]}")
start = time.time()
for i in range(0, len(certs), 500):
    body = gzip.compress(json.dumps(certs[i:i + 500], separators=(",", ":")).encode())
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/insert?key={key}", data=body, method="POST")
    urllib.request.urlopen(req, timeout=3600).read()
    print(f"  inserted {min(i + 500, len(certs))}/{len(certs)} ({time.time() - start:.0f} s)", flush=True)
req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/release?key={key}", data=b"", method="POST")
urllib.request.urlopen(req, timeout=3600).read()
print(f"released, {time.time() - start:.0f} s in total")
EOF
}

status() {
    docker ps --filter "name=$PG" --format '{{.Names}}: {{.Status}}' | grep . || echo "$PG: not running"
    if [ -f "$STATE/server.pid" ] && kill -0 "$(cat "$STATE/server.pid")" 2>/dev/null; then
        echo "server: running on port $PORT (log: $STATE/server.log)"
    else
        echo "server: not running"
    fi
    [ -f "$STATE/env" ] && grep '^CLOG_ID' "$STATE/env" || true
    docker ps -a --filter "name=$PG" -q | grep -q . &&
        echo "certificates: $(psql_exp -Atc 'select count(*) from certificates')" || true
}

down() {
    if [ -f "$STATE/server.pid" ]; then
        kill "$(cat "$STATE/server.pid")" 2>/dev/null || true
        echo "server stopped"
    fi
    if [ -f "$STATE/env" ]; then
        local tree
        tree=$(grep '^CLOG_ID=' "$STATE/env" | cut -d= -f2)
        (cd "$TRILLIAN" && go run ./cmd/deletetree --admin_server=$TRILLIAN_ADMIN --log_id="$tree") &&
            echo "Trillian tree $tree deleted"
    fi
    docker rm -f "$PG" >/dev/null 2>&1 && echo "$PG removed" || true
    rm -rf "$STATE" && echo "$STATE removed"
}

case "$cmd" in
    up) up ;;
    load) load "${2:-}" ;;
    status) status ;;
    down) down ;;
    *) echo "usage: stack.sh up | load <file> | status | down"; exit 1 ;;
esac
