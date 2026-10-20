#!/usr/bin/env bash
# Runs one FreeRADIUS instance of the eduroam demo with full debug output,
# written straight to ~/gecko-radius/logs/<instance>-<time>.log (the evidence
# for the demo scenes; count-inner.sh reads it) and followed on screen.
# Ctrl-C stops both. Ports are set per instance by setup-instances.sh:
#
#   lab/eduroam/radius/run.sh legit         auth 1812
#   lab/eduroam/radius/run.sh attacker-b1   auth 1814
#   lab/eduroam/radius/run.sh attacker-b2   auth 1816
#
# Add "bg" as a second argument to start it in the background instead.
set -euo pipefail
name=${1:?usage: run.sh legit|attacker-b1|attacker-b2 [bg]}
BASE="$HOME/gecko-radius"
[ -d "$BASE/$name" ] || { echo "no instance $name; run setup-instances.sh" >&2; exit 2; }
log="$BASE/logs/$name-$(date +%Y%m%d-%H%M%S).log"
touch "$log" # user-owned, so it stays readable; root appends to it
echo "logging to $log"
# -sfxx -l <file> is -X with the log in a file: through a pipe, -X output is
# block-buffered and shows up late.
if [ "${2:-}" = bg ]; then
    # setsid: detached from this shell's session, so it survives the shell
    # exiting (e.g. `wsl -e bash -c ...` returning right away).
    sudo setsid freeradius -sfxx -l "$log" -d "$BASE/$name" > /dev/null 2>&1 < /dev/null &
    sleep 3
    pgrep -f "freeradius .*-d $BASE/$name\$" > /dev/null || { echo "$name did not start, see $log" >&2; exit 1; }
else
    sudo freeradius -sfxx -l "$log" -d "$BASE/$name" &
    pid=$!
    trap 'sudo kill $pid 2>/dev/null' EXIT INT TERM
    tail -f "$log" --pid=$pid
fi
