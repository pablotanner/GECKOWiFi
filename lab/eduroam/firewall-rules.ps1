<#
.SYNOPSIS
  Windows Defender Firewall rules for the eduroam demo (docs/eduroam-demo.md).
  Run once, as administrator. Re-running replaces the rules.

.DESCRIPTION
  Inbound from the ICS subnet only (192.168.137.0/24), like the existing
  "GeoPKI 1234" rule:
    UDP 1812, 1814, 1816  RADIUS via lab/eduroam/udp-relay.py (legit, attacker B1, attacker B2)
    TCP 1235              realm-record server (lab/realm)
  Remove with: Get-NetFirewallRule -DisplayName 'GECKO eduroam*' | Remove-NetFirewallRule
#>
$ErrorActionPreference = 'Stop'
Get-NetFirewallRule -DisplayName 'GECKO eduroam*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName 'GECKO eduroam RADIUS relay' -Direction Inbound -Action Allow `
  -Protocol UDP -LocalPort 1812,1814,1816 -RemoteAddress 192.168.137.0/24 -Profile Any | Out-Null
New-NetFirewallRule -DisplayName 'GECKO eduroam realm server' -Direction Inbound -Action Allow `
  -Protocol TCP -LocalPort 1235 -RemoteAddress 192.168.137.0/24 -Profile Any | Out-Null
Get-NetFirewallRule -DisplayName 'GECKO eduroam*' | Format-Table DisplayName, Enabled, Direction, Action
