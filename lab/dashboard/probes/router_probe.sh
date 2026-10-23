#!/bin/sh
# Runs on a router (OpenWrt, busybox), fed through `ssh <router> sh -s`.
# Prints key=value lines for lab/dashboard/dashboard.py. Read-only: these are
# the router checks from the README cheat sheet in one go.
LAPTOP=192.168.137.1

echo "now=$(date +%s)"
echo "uptime=$(cut -d. -f1 /proc/uptime)"
echo "hostname=$(uci -q get system.@system[0].hostname)"
echo "wan=$(ip -4 addr show eth0.2 2>/dev/null | awk '/inet /{print $2; exit}')"
echo "gateway=$(ip route show default | awk '{print $3; exit}')"

ping -c1 -W2 1.1.1.1 >/dev/null 2>&1 && echo "internet=1" || echo "internet=0"
nslookup connectivitycheck.gstatic.com >/dev/null 2>&1 && echo "dns=1" || echo "dns=0"

# wget: 0 = 2xx, 8 = HTTP error status (the server answered), anything else = no answer
wget -q -O /dev/null -T 4 "http://$LAPTOP:1234/" >/dev/null 2>&1; echo "map_rc=$?"
wget -q -O /dev/null -T 4 "http://$LAPTOP:1235/v1/realm/eduroam-lab/homeuni.test" >/dev/null 2>&1; echo "realm_rc=$?"

uci show wireless 2>/dev/null | grep -E "^wireless\.[^.]+\.(ssid|disabled|auth_server|auth_port|encryption|network|mode)=" | sed 's/^/uci:/'
iw dev 2>/dev/null | awk '/Interface/{i=$2} /ssid/{print "iface=" i "," $2}'
for i in $(iw dev 2>/dev/null | awk '/Interface/{print $2}'); do
    echo "stations=$i,$(iw dev "$i" station dump 2>/dev/null | grep -c '^Station')"
done

# openNDS (the captive-portal gate)
pgrep -f opennds >/dev/null 2>&1 && echo "opennds=1" || echo "opennds=0"
echo "fas_ip=$(uci -q get opennds.@opennds[0].fasremoteip)"
echo "fas_fqdn=$(uci -q get opennds.@opennds[0].fasremotefqdn)"
ndsctl json 2>/dev/null | awk -F'"' '/"mac":/{m=$4} /"ip":/{ip=$4} /"state":/{print "nds_client=" m "," ip "," $4}'

# DNS overrides (router B: the scenario's spoofing entries) and the S7 block rule
for a in $(uci -q get dhcp.@dnsmasq[0].address); do echo "dns_override=$a"; done
nft list ruleset 2>/dev/null | grep -qi geckoblock && echo "s7_block=1" || echo "s7_block=0"

# DHCP leases: mac,ip,name (10.99.0.x = onboarding walled garden, 10.98.0.x = eduroam users)
awk '{print "lease=" $2 "," $3 "," $4}' /tmp/dhcp.leases 2>/dev/null

# Walled garden counters: packets allowed to the GECKO servers / rejected
nft list ruleset 2>/dev/null | awk '/GECKO-onboard-to-map/{for(i=1;i<=NF;i++) if($i=="packets") print "garden_allowed=" $(i+1)}
                                    /reject onboard/ && /iifname/{for(i=1;i<=NF;i++) if($i=="packets") print "garden_rejected=" $(i+1)}'

# Switch port drops (port 0 = upstream cable, port 1 = the other router on A), in seconds since boot
dmesg 2>/dev/null | awk -F'[][]' '/port [0-9] link down/{match($3,/port [0-9]/); print "link_down=" substr($3,RSTART+5,1) "," int($2)}'

logread 2>/dev/null | grep "No response from Authentication server" | tail -1 | sed 's/^/radius_error=/'
logread 2>/dev/null | grep -E "CTRL-EVENT-EAP-(SUCCESS|FAILURE|TIMEOUT-FAILURE)" | tail -1 | sed 's/^/eap_last=/'
exit 0
