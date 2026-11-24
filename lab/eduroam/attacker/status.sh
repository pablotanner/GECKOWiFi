#!/usr/bin/env bash
# One-glance view of EVERY attacker setting currently live on Router B (lab only).
# Shows what is ON/OFF so you always know which "attacks" are running before a run.
#
#   lab/eduroam/attacker/status.sh
#
# Note: the RADIUS keep-online harvest (attacker-b3 force_accept_gtc / GTC plaintext
# capture) runs on the LAPTOP side, not Router B - check it with
# lab/eduroam/radius/extract-plain.sh attacker-b3. This view covers the B data-plane.
set -euo pipefail
MB=${MANGO_B:-mango-b}
SSH=(ssh -o BatchMode=yes "$MB")

echo "===================== Router B attacker status ====================="
"${SSH[@]}" '
	net=$(uci get wireless.eduroam_b3.network 2>/dev/null)
	noresolv=$(uci -q get dhcp.@dnsmasq[0].noresolv || echo 0)
	dnssrv=$(uci -q get dhcp.@dnsmasq[0].server || echo "")
	logq=$(uci -q get dhcp.@dnsmasq[0].logqueries || echo 0)
	httpdnat=$(nft list table inet fw4 2>/dev/null | grep -c "attacker-http-intercept")
	cgi=$(uci -q get uhttpd.attacker.cgi_prefix || echo "")
	form=$([ -f /www-attacker/cgi-bin/login ] && echo yes || echo no)
	creds=$(wc -l < /tmp/portal-creds.log 2>/dev/null || echo 0)
	onoff() { [ "$1" = "$2" ] && echo "ON " || echo "off"; }

	printf "%-34s %s\n" "Tier 0  connectivity (edu bridge)" "$([ "$net" = edu ] && echo "ON  (victim gets internet)" || echo "off (withholding / lan+openNDS)")"
	printf "%-34s %s\n" "Tier 0  DNS isolated from Router A" "$([ "$noresolv" = 1 ] && echo "ON  (upstream: $dnssrv)" || echo "off (forwards to A)")"
	printf "%-34s %s\n" "Tier 1  DNS query logging (sniff)" "$(onoff "$logq" 1)"
	printf "%-34s %s\n" "Tier 2  plain-HTTP intercept" "$([ "$httpdnat" -ge 1 ] && echo "ON  (:80 -> attacker page)" || echo off)"
	printf "%-34s %s\n" "Tier 2.5 portal credential capture" "$([ -n "$cgi" ] && [ "$form" = yes ] && echo "ON  ($creds captured)" || echo off)"
	echo "-------------------------------------------------------------------"
	echo "Associated eduroam victims:"
	iw dev phy0-ap1 station dump 2>/dev/null | awk "/Station/{print \"  \" \$2}" || true
	echo "edu DHCP leases:"
	grep "10.98." /tmp/dhcp.leases 2>/dev/null | awk "{print \"  \" \$3 \"  \" \$4}" || echo "  (none)"
'
echo "===================================================================="
echo "Laptop-side (not shown above):"
echo "  RADIUS keep-online + GTC plaintext harvest -> lab/eduroam/radius/extract-plain.sh attacker-b3"
echo "  Full pcap / live domains -> lab/eduroam/attacker/sniff.sh pcap|dns"
