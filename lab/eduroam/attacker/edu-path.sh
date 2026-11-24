#!/usr/bin/env bash
# Tier 0 - attacker connectivity path (lab only).
#
# Switches Router B's attacker eduroam SSID (eduroam_b3) between two worlds:
#
#   on   victim lands on the `edu` bridge (10.98.0.0/24): NAT straight out B's
#        WAN, B resolves DNS itself (public upstream, NOT Router A), and openNDS
#        never touches it -> the victim gets REAL internet with no captive sheet.
#        This is the foundation for sniffing / interception / MITM.
#
#   off  victim lands back on `lan` (192.168.247.0/24) under openNDS -> the
#        withholding / captive-portal demo. DNS goes back to forwarding upstream.
#
# Why two bridges: `GeckoTest` (open SSID) stays on `lan` under openNDS no matter
# what, so the withholding demo is untouched. Only the eduroam attacker SSID
# moves. See docs/eduroam-decisions.md (attacker data-plane) and
# lab/eduroam/attacker/README.md.
#
#   lab/eduroam/attacker/edu-path.sh on|off|status
#
# Requires an `mango-b` host alias in your ssh config (ProxyJump via mango-a).
set -euo pipefail

MB=${MANGO_B:-mango-b}
SSH=(ssh -o BatchMode=yes "$MB")

usage() { echo "usage: $0 on|off|status" >&2; exit 2; }
[ $# -eq 1 ] || usage

case "$1" in
on)
	"${SSH[@]}" '
		uci set wireless.eduroam_b3.network="edu"
		uci set dhcp.@dnsmasq[0].noresolv="1"
		uci -q delete dhcp.@dnsmasq[0].server || true
		uci add_list dhcp.@dnsmasq[0].server="1.1.1.1"
		uci add_list dhcp.@dnsmasq[0].server="1.0.0.1"
		uci set dhcp.@dnsmasq[0].logqueries="1"
		uci commit wireless; uci commit dhcp
		/etc/init.d/dnsmasq restart >/dev/null 2>&1
		wifi reload >/dev/null 2>&1
	'
	echo "edu-path ON: eduroam attacker clients now get internet via edu (10.98.0.x), B resolves DNS itself."
	echo "Victim must forget+rejoin eduroam-lab to land on the edu bridge."
	;;
off)
	"${SSH[@]}" '
		uci set wireless.eduroam_b3.network="lan"
		uci -q delete dhcp.@dnsmasq[0].noresolv || true
		uci -q delete dhcp.@dnsmasq[0].server || true
		uci set dhcp.@dnsmasq[0].logqueries="0"
		uci commit wireless; uci commit dhcp
		/etc/init.d/dnsmasq restart >/dev/null 2>&1
		wifi reload >/dev/null 2>&1
	'
	echo "edu-path OFF: attacker clients back on lan under openNDS (withholding/captive-portal demo)."
	;;
status)
	"${SSH[@]}" '
		echo "eduroam_b3.network = $(uci get wireless.eduroam_b3.network)"
		echo "dnsmasq noresolv   = $(uci -q get dhcp.@dnsmasq[0].noresolv || echo unset)"
		echo "dnsmasq server     = $(uci -q get dhcp.@dnsmasq[0].server || echo "(upstream = resolv.conf.auto, i.e. Router A)")"
		echo "dnsmasq logqueries = $(uci -q get dhcp.@dnsmasq[0].logqueries || echo 0)"
		echo "br-edu members     = $(ls /sys/class/net/br-edu/brif/ 2>/dev/null | tr "\n" " ")"
		echo "edu leases:"; grep "10.98." /tmp/dhcp.leases 2>/dev/null || echo "  (none)"
	'
	;;
*) usage ;;
esac
