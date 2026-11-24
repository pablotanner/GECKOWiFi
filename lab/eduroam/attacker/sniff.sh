#!/usr/bin/env bash
# Tier 1 - sniff the attacker path (lab only).
#
#   sniff.sh pcap [bpf...]   stream a live pcap off B's edu bridge into
#                            evidence/eduroam-attacker/ (Ctrl-C to stop). Open the
#                            file in Wireshark. Optional trailing BPF filter, e.g.
#                            sniff.sh pcap tcp port 80
#   sniff.sh dns             follow the victim's DNS lookups live (domains only;
#                            needs edu-path.sh on, which enables logqueries)
#   sniff.sh http            follow hits on the attacker HTTP page (interception)
#
# tcpdump runs ON B (tcpdump-mini) and streams over SSH; nothing is stored on B's
# tiny flash. The pcap lands in the git-ignored evidence/ tree.
#
# Requires an `mango-b` host alias in your ssh config (ProxyJump via mango-a).
set -euo pipefail

MB=${MANGO_B:-mango-b}
SSH=(ssh -o BatchMode=yes "$MB")
IFACE=${EDU_IFACE:-br-edu}
OUT="evidence/eduroam-attacker"
usage() { echo "usage: $0 pcap [bpf...] | dns | http" >&2; exit 2; }
[ $# -ge 1 ] || usage

case "$1" in
pcap)
	shift
	mkdir -p "$OUT"
	f="$OUT/edu-$(date +%Y%m%d-%H%M%S).pcap"
	echo "capturing $IFACE -> $f  (Ctrl-C to stop)"
	# -U unbuffered, -s0 full frames, write raw pcap to stdout -> local file
	"${SSH[@]}" "tcpdump -i $IFACE -U -s0 -nn -w - ${*:-}" > "$f"
	;;
dns)
	echo "following DNS lookups (needs edu-path.sh on). Ctrl-C to stop."
	"${SSH[@]}" 'logread -f' | grep --line-buffered -i "query\["
	;;
http)
	echo "following attacker HTTP-intercept hits. Ctrl-C to stop."
	"${SSH[@]}" 'logread -f' | grep --line-buffered -iE "uhttpd|attacker"
	;;
*) usage ;;
esac
