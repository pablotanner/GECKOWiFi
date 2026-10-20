#!/bin/sh
# The access-point side of a GECKO-enabled eduroam SP (Design A,
# docs/eduroam-demo.md): what an operator adds on an OpenWrt AP with full
# wpad. Runs on the router; the RADIUS secret is passed in the environment:
#
#   ssh mango-b "RADIUS_SECRET='<RADIUS_SECRET_A>' sh -s" < lab/eduroam/openwrt/sp-legit.sh
#
# 1. eduroam-lab: WPA2-Enterprise against the SP's RADIUS server, which may
#    assign a VLAN (dynamic_vlan 1 = optional).
# 2. edu: where normal (authenticated) users land, with internet.
# 3. onboard (VLAN 99): where RADIUS puts the public GECKO identity. Clients
#    isolated from each other; the firewall only lets them reach the GECKO map
#    server and the realm endpoint (the walled garden). 5-minute leases, the
#    session itself ends after RADIUS' Session-Timeout (60 s).
#
# Re-running replaces the sections. Nothing else on the router is touched.
set -e
: "${RADIUS_SECRET:?RADIUS_SECRET not set}"
RADIUS_SERVER=192.168.137.1
GECKO_HOST=192.168.137.1   # map server :1234, realm endpoint :1235 (lab: both on the laptop)

for s in wireless.eduroam_legit wireless.onboard_vlan network.edu_dev network.edu network.onboard_dev network.onboard \
         dhcp.edu dhcp.onboard firewall.edu firewall.edu_wan firewall.onboard firewall.onboard_dhcp firewall.onboard_gecko; do
    uci -q delete "$s" || true
done

# --- networks ---
uci set network.edu_dev=device
uci set network.edu_dev.name='br-edu'
uci set network.edu_dev.type='bridge'
uci set network.edu_dev.bridge_empty='1'
uci set network.edu=interface
uci set network.edu.proto='static'
uci set network.edu.device='br-edu'
uci set network.edu.ipaddr='10.98.0.1'
uci set network.edu.netmask='255.255.255.0'

uci set network.onboard_dev=device
uci set network.onboard_dev.name='br-onboard'
uci set network.onboard_dev.type='bridge'
uci set network.onboard_dev.bridge_empty='1'
uci set network.onboard=interface
uci set network.onboard.proto='static'
uci set network.onboard.device='br-onboard'
uci set network.onboard.ipaddr='10.99.0.1'
uci set network.onboard.netmask='255.255.255.0'

uci set dhcp.edu=dhcp
uci set dhcp.edu.interface='edu'
uci set dhcp.edu.start='10'
uci set dhcp.edu.limit='100'
uci set dhcp.edu.leasetime='1h'
uci set dhcp.onboard=dhcp
uci set dhcp.onboard.interface='onboard'
uci set dhcp.onboard.start='10'
uci set dhcp.onboard.limit='100'
uci set dhcp.onboard.leasetime='5m'

# --- firewall ---
uci set firewall.edu=zone
uci set firewall.edu.name='edu'
uci add_list firewall.edu.network='edu'
uci set firewall.edu.input='ACCEPT'
uci set firewall.edu.output='ACCEPT'
uci set firewall.edu.forward='REJECT'
uci set firewall.edu_wan=forwarding
uci set firewall.edu_wan.src='edu'
uci set firewall.edu_wan.dest='wan'

# The walled garden: nothing but DHCP to the router and TCP to the GECKO servers.
uci set firewall.onboard=zone
uci set firewall.onboard.name='onboard'
uci add_list firewall.onboard.network='onboard'
uci set firewall.onboard.input='REJECT'
uci set firewall.onboard.output='ACCEPT'
uci set firewall.onboard.forward='REJECT'
uci set firewall.onboard_dhcp=rule
uci set firewall.onboard_dhcp.name='GECKO-onboard-DHCP'
uci set firewall.onboard_dhcp.src='onboard'
uci set firewall.onboard_dhcp.proto='udp'
uci set firewall.onboard_dhcp.dest_port='67'
uci set firewall.onboard_dhcp.target='ACCEPT'
uci set firewall.onboard_gecko=rule
uci set firewall.onboard_gecko.name='GECKO-onboard-to-map'
uci set firewall.onboard_gecko.src='onboard'
uci set firewall.onboard_gecko.dest='wan'
uci set firewall.onboard_gecko.dest_ip="$GECKO_HOST"
uci set firewall.onboard_gecko.proto='tcp'
uci set firewall.onboard_gecko.dest_port='1234 1235'
uci set firewall.onboard_gecko.target='ACCEPT'

# --- Wi-Fi ---
uci set wireless.eduroam_legit=wifi-iface
uci set wireless.eduroam_legit.device='radio0'
uci set wireless.eduroam_legit.mode='ap'
uci set wireless.eduroam_legit.network='edu'
uci set wireless.eduroam_legit.ssid='eduroam-lab'      # never 'eduroam'
uci set wireless.eduroam_legit.encryption='wpa2'
uci set wireless.eduroam_legit.auth_server="$RADIUS_SERVER"
uci set wireless.eduroam_legit.auth_port='1812'
uci set wireless.eduroam_legit.auth_secret="$RADIUS_SECRET"
uci set wireless.eduroam_legit.dynamic_vlan='1'
uci set wireless.eduroam_legit.disabled='0'

uci set wireless.onboard_vlan=wifi-vlan
uci set wireless.onboard_vlan.name='onb'
uci set wireless.onboard_vlan.vid='99'
uci add_list wireless.onboard_vlan.network='onboard'
uci add_list wireless.onboard_vlan.iface='eduroam_legit'
uci set wireless.onboard_vlan.isolate='1'

# Same SSID as the attacker modes on this router: never both at once.
uci -q set wireless.eduroam_b1.disabled='1' || true
uci -q set wireless.eduroam_b2.disabled='1' || true

uci commit
/etc/init.d/network reload
/etc/init.d/firewall reload
/etc/init.d/dnsmasq reload
wifi reload
echo "sp-legit applied"
