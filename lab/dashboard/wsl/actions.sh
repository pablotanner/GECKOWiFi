#!/usr/bin/env bash
# WSL side of the dashboard's actions (lab/dashboard/dashboard.py):
#
#   actions.sh map-start | map-stop | map-log
#   actions.sh radius-start <instance> | radius-stop <instance> | radius-log <instance>
#   actions.sh radius-fix-clients | radius-test | postgres-start
#
# Commands that need root read the sudo password from the first line of stdin
# and exit 3 with SUDO_FAILED if it is wrong. Nothing is stored.
set -uo pipefail
export PATH="$PATH:/usr/local/go/bin"
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
GEOPKI=${GEOPKI_DIR:-$HOME/Thesis/geopki-typed}
RADIUS="$HOME/gecko-radius"
INSTANCES="legit attacker-b1 attacker-b2"

cmd=${1:?usage: actions.sh <command> [instance]}
arg=${2:-}

# The lab scripts (run.sh, stop.sh) call sudo themselves; an exported function
# gives them the password without a terminal.
with_sudo() {
    IFS= read -r SUDO_PW || true
    printf '%s\n' "$SUDO_PW" | command sudo -S -p '' -v 2>/dev/null || { echo "SUDO_FAILED"; exit 3; }
    export SUDO_PW
    sudo() { command sudo -S -p '' "$@" <<< "$SUDO_PW"; }
    export -f sudo
}

instance() {
    case " $INSTANCES " in *" $arg "*) ;; *) echo "unknown instance '$arg'"; exit 2;; esac
}

listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

case $cmd in
map-start)
    if pgrep -x geopki-server >/dev/null; then echo "map server already running"; exit 0; fi
    listening 8090 || { echo "Trillian (127.0.0.1:8090) is down: start Docker Desktop first"; exit 1; }
    cd "$GEOPKI" || { echo "no $GEOPKI"; exit 1; }
    set -a; source .envrc; set +a
    [ -n "${CLOG_ID:-}" ] || { echo "CLOG_ID missing in $GEOPKI/.envrc"; exit 1; }
    for attempt in 1 2; do
        echo "--- $(date '+%F %T') started by the dashboard" >> server.log
        setsid nohup go run ./cmd/geopki-server --address=0.0.0.0 --port=1234 \
            --trillian-address=127.0.0.1:8090 --clog-id="$CLOG_ID" >> server.log 2>&1 < /dev/null &
        for i in $(seq 1 90); do
            sleep 1
            if listening 1234; then echo "map server listening after ${i}s (tree $CLOG_ID)"; exit 0; fi
            pgrep -f 'cmd/geopki-server' >/dev/null || break
        done
        if pgrep -f 'cmd/geopki-server' >/dev/null; then
            echo "not listening after 90 s, last log lines:"; tail -n 15 server.log; exit 1
        fi
        # After Docker starts, the Trillian log server crash-loops until MySQL is
        # up, and Docker can keep forwarding 8090 to the dead instance: the
        # server then gets "unknown service trillian.TrillianLog". Restarting
        # the container fixes the forwarding.
        if [ $attempt = 1 ] && tail -n 5 server.log | grep -q "unknown service trillian.TrillianLog"; then
            echo "Trillian's port forwarding is stale, restarting its log server container"
            docker restart deployment-trillian-log-server-1 >/dev/null && sleep 5 && continue
        fi
        echo "map server exited:"; tail -n 15 server.log; exit 1
    done
    ;;
map-stop)
    pkill -f 'go run ./cmd/geopki-server'
    if pkill -x geopki-server; then echo "map server stopped"; else echo "map server was not running"; fi
    ;;
map-log)
    tail -n "${3:-120}" "$GEOPKI/server.log" 2>/dev/null || echo "no $GEOPKI/server.log"
    ;;
radius-start)
    instance
    for pid in $(pgrep -x freeradius); do
        if tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "/gecko-radius/$arg "; then echo "$arg already running (pid $pid)"; exit 0; fi
    done
    with_sudo
    bash "$REPO/lab/eduroam/radius/run.sh" "$arg" bg
    ;;
radius-stop)
    instance; with_sudo
    out=$(bash "$REPO/lab/eduroam/radius/stop.sh" "$arg")
    echo "${out:-$arg was not running}"
    ;;
radius-log)
    instance
    log=$(ls -t "$RADIUS/logs/$arg-"*.log 2>/dev/null | head -1)
    if [ -n "$log" ]; then echo "== $log"; tail -n "${3:-120}" "$log"; else echo "no log for $arg yet"; fi
    ;;
radius-fix-clients)
    # The relay's packets come from the WSL gateway, which changes on every
    # WSL restart: accept the whole WSL range, as setup-instances.sh writes it.
    with_sudo
    for i in $INSTANCES; do
        f="$RADIUS/$i/clients.conf"
        [ -f "$f" ] || continue
        sed -i -E '/client wsl-relay/,/}/ s#^(\s*ipaddr = ).*#\1172.16.0.0/12#' "$f"
        echo "$i: $(grep -A1 'client wsl-relay' "$f" | grep ipaddr | xargs)"
        for pid in $(pgrep -x freeradius); do
            if tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "/gecko-radius/$i "; then
                bash "$REPO/lab/eduroam/radius/stop.sh" "$i" >/dev/null
                bash "$REPO/lab/eduroam/radius/run.sh" "$i" bg
            fi
        done
    done
    ;;
radius-test)
    bash "$REPO/lab/eduroam/radius/test-radius.sh"
    ;;
postgres-start)
    with_sudo
    sudo service postgresql start
    sleep 2
    listening 5432 && echo "PostgreSQL is up" || { echo "PostgreSQL still down"; exit 1; }
    ;;
*)
    echo "unknown command '$cmd'"; exit 2
    ;;
esac
