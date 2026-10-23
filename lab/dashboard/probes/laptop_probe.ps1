# Laptop side of lab/dashboard/dashboard.py: adapters, addresses, network
# profiles, services and the lab's Windows processes, as one JSON object.
# Read-only, no administrator rights needed.
param(
  [string]$LabAdapter = 'Ethernet 4',
  [string]$UplinkAdapter = 'WLAN'
)
$ErrorActionPreference = 'SilentlyContinue'

function Adapter($name) {
  $a = Get-NetAdapter -Name $name
  if (-not $a) { return $null }
  $profile = Get-NetConnectionProfile -InterfaceAlias $name
  [ordered]@{
    name         = $name
    status       = "$($a.Status)"
    description  = $a.InterfaceDescription
    ipv4         = @(Get-NetIPAddress -InterfaceAlias $name -AddressFamily IPv4 | ForEach-Object { "$($_.IPAddress)/$($_.PrefixLength)" })
    category     = "$($profile.NetworkCategory)"
    connectivity = "$($profile.IPv4Connectivity)"
    ssid         = $profile.Name
  }
}

$names = 'python.exe', 'pythonw.exe', 'go.exe', 'realm.exe', 'portal.exe', 'Docker Desktop.exe', 'com.docker.backend.exe', 'adb.exe'
$procs = @(Get-CimInstance Win32_Process | Where-Object { $names -contains $_.Name } | ForEach-Object {
  [ordered]@{
    pid     = $_.ProcessId
    parent  = $_.ParentProcessId
    name    = $_.Name
    command = $_.CommandLine
    started = if ($_.CreationDate) { $_.CreationDate.ToUniversalTime().ToString('o') } else { $null }
  }
})

[ordered]@{
  lab       = Adapter $LabAdapter
  uplink    = Adapter $UplinkAdapter
  services  = [ordered]@{
    SharedAccess = "$((Get-Service SharedAccess).Status)"
    iphlpsvc     = "$((Get-Service iphlpsvc).Status)"
  }
  boot      = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')
  processes = $procs
} | ConvertTo-Json -Depth 5 -Compress
