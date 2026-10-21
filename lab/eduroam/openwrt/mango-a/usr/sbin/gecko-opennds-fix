#!/bin/sh
# openNDS 10.3.1 builds its client-to-router chain (ndsRTR) without a rule for
# return traffic, so replies to connections the router itself opened towards a
# client on the portal LAN hit its final `reject`: on Router A that breaks
# `ssh mango-b` (ProxyJump through A) and pings from A to B. openNDS 9 (GL
# firmware) didn't have this. The fix accepts only established/related packets,
# the standard first rule of any firewall; it opens nothing new.
#
# openNDS rebuilds ndsRTR whenever it (re)starts, so cron re-applies this every
# minute; it is idempotent. Install on the router:
#
#   ssh mango-a "cat > /usr/sbin/gecko-opennds-fix" < lab/eduroam/openwrt/opennds-established-fix.sh
#   ssh mango-a 'chmod +x /usr/sbin/gecko-opennds-fix; /usr/sbin/gecko-opennds-fix --install'

if [ "$1" = "--install" ]; then
    grep -q gecko-opennds-fix /etc/crontabs/root 2>/dev/null \
        || echo '* * * * * /usr/sbin/gecko-opennds-fix' >> /etc/crontabs/root
    /etc/init.d/cron enable
    /etc/init.d/cron restart
    grep -q gecko-opennds-fix /etc/sysupgrade.conf 2>/dev/null || echo /usr/sbin/gecko-opennds-fix >> /etc/sysupgrade.conf
fi

nft list chain ip nds_filter ndsRTR >/dev/null 2>&1 || exit 0   # openNDS not running (yet)
nft list chain ip nds_filter ndsRTR | grep -q "ct state established,related accept" \
    || nft insert rule ip nds_filter ndsRTR ct state established,related accept
