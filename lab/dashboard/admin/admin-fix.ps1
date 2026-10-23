<#
.SYNOPSIS
  The lab fixes that need administrator rights, for lab/dashboard. The
  dashboard starts this elevated (one UAC prompt) and shows the transcript.

.DESCRIPTION
  -Task, comma-separated, run in the given order:
    ics        Internet sharing off and on again: WLAN shared (public), lab
               adapter as the home network (private). Then 'ips'.
    ips        Lab addresses on the lab adapter: .1 (ICS), .10 genuine portal,
               .20 attacker portal (the portal ones with SkipAsSource).
    portproxy  Forward laptop:1234 to the map server at the current WSL address.
    firewall   The inbound rules for map server, portal, RADIUS relay and realm
               server, from 192.168.137.0/24 only.
#>
param(
  # comma-separated, since powershell -File passes it as one string
  [Parameter(Mandatory)][string]$Task,
  [string]$LabAdapter = 'Ethernet 4',
  [string]$UplinkAdapter = 'WLAN',
  [string]$Transcript
)
$ErrorActionPreference = 'Continue'
if ($Transcript) { Start-Transcript -Path $Transcript -Force | Out-Null }

$subnet = '192.168.137.0/24'

function Add-LabAddresses {
  $have = @(Get-NetIPAddress -InterfaceAlias $LabAdapter -AddressFamily IPv4 -ErrorAction SilentlyContinue | ForEach-Object IPAddress)
  foreach ($a in @(
      @{ ip = '192.168.137.1'; skip = $false },
      @{ ip = '192.168.137.10'; skip = $true },
      @{ ip = '192.168.137.20'; skip = $true })) {
    if ($have -contains $a.ip) { Write-Output "$($a.ip) already on $LabAdapter"; continue }
    New-NetIPAddress -InterfaceAlias $LabAdapter -IPAddress $a.ip -PrefixLength 24 -SkipAsSource $a.skip -ErrorAction Continue | Out-Null
    Write-Output "added $($a.ip) to $LabAdapter"
  }
  Get-NetIPAddress -InterfaceAlias $LabAdapter -AddressFamily IPv4 | Format-Table IPAddress, PrefixLength, SkipAsSource -AutoSize | Out-String | Write-Output
}

function Reset-Sharing {
  $share = New-Object -ComObject HNetCfg.HNetShare
  $public = $null; $private = $null
  foreach ($c in $share.EnumEveryConnection) {
    $props = $share.NetConnectionProps.Invoke($c)
    $cfg = $share.INetSharingConfigurationForINetConnection.Invoke($c)
    if ($cfg.SharingEnabled) { Write-Output "sharing off: $($props.Name)"; $cfg.DisableSharing() }
    if ($props.Name -eq $UplinkAdapter) { $public = $cfg }
    if ($props.Name -eq $LabAdapter) { $private = $cfg }
  }
  if (-not $public -or -not $private) { Write-Output "adapter '$UplinkAdapter' or '$LabAdapter' not found"; return }
  Start-Sleep -Seconds 2
  $public.EnableSharing(0); Write-Output "sharing on: $UplinkAdapter (public)"
  $private.EnableSharing(1); Write-Output "sharing on: $LabAdapter (home network)"
  Start-Sleep -Seconds 4
}

function Set-PortProxy {
  $ip = ((wsl hostname -I) -join ' ').Trim().Split(' ')[0]
  if (-not $ip) { Write-Output 'WSL did not answer, nothing changed'; return }
  netsh interface portproxy delete v4tov4 listenaddress=0.0.0.0 listenport=1234 2>$null | Out-Null
  netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=1234 connectaddress=$ip connectport=1234
  Write-Output "portproxy 0.0.0.0:1234 -> ${ip}:1234"
  netsh interface portproxy show v4tov4
}

function Set-FirewallRules {
  $rules = @(
    @{ n = 'GeoPKI 1234'; p = 'TCP'; port = '1234'; local = 'Any' },
    @{ n = 'GECKO portal 80/443'; p = 'TCP'; port = '80,443'; local = '192.168.137.10,192.168.137.20' },
    @{ n = 'GECKO eduroam RADIUS relay'; p = 'UDP'; port = '1812,1814,1816'; local = 'Any' },
    @{ n = 'GECKO eduroam realm server'; p = 'TCP'; port = '1235'; local = 'Any' })
  foreach ($r in $rules) {
    if (Get-NetFirewallRule -DisplayName $r.n -ErrorAction SilentlyContinue) { Write-Output "rule '$($r.n)' present"; continue }
    New-NetFirewallRule -DisplayName $r.n -Direction Inbound -Action Allow -Protocol $r.p -LocalPort ($r.port -split ',') `
      -LocalAddress ($r.local -split ',') -RemoteAddress $subnet -Profile Any | Out-Null
    Write-Output "rule '$($r.n)' added"
  }
}

$known = 'ics', 'ips', 'portproxy', 'firewall'
foreach ($t in $Task -split ',') {
  if ($known -notcontains $t) { Write-Output "unknown task '$t'"; continue }
  Write-Output "== $t"
  switch ($t) {
    'ics' { Reset-Sharing; Add-LabAddresses }
    'ips' { Add-LabAddresses }
    'portproxy' { Set-PortProxy }
    'firewall' { Set-FirewallRules }
  }
}
Write-Output '== done'
if ($Transcript) { Stop-Transcript | Out-Null }
