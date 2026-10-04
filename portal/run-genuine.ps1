# Starts the genuine (router A) captive portal on 192.168.137.10:80/443.
# Needs the lab addresses on 'Ethernet 4' (see LAB_SETUP.md, "Captive portal").
Set-Location $PSScriptRoot
if (-not (Test-Path .\portal.exe)) { go build -o portal.exe . }
.\portal.exe serve -config config\genuine.json
