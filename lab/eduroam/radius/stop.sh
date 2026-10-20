#!/usr/bin/env bash
# Stops one FreeRADIUS instance of the eduroam demo, or all of them.
#   lab/eduroam/radius/stop.sh attacker-b1
#   lab/eduroam/radius/stop.sh all
set -euo pipefail
name=${1:?usage: stop.sh legit|attacker-b1|attacker-b2|all}
# Only real freeradius processes (pgrep -x), matched on their -d directory;
# "pkill -f" would also hit any shell whose command line mentions the name.
for pid in $(pgrep -x freeradius || true); do
    args=$(tr '\0' ' ' < "/proc/$pid/cmdline")
    if [ "$name" = all ] || [[ "$args" == *"/gecko-radius/$name "* ]]; then
        sudo kill "$pid" && echo "stopped $pid: $args"
    fi
done
