<#
 Starts a LOCAL, INSECURE demo Flash Hub (development mode only: http, organisation root key kept on the server).
 NEVER use this for a real organisation. Data lives in server\data\demo (git-ignored) and is wiped by -Fresh.

   powershell -File tools\demo-server.ps1            # build (through the Gradle lock) and run
   powershell -File tools\demo-server.ps1 -Fresh     # delete the demo data first
   powershell -File tools\demo-server.ps1 -NoBuild   # reuse the last build

 The console is on http://127.0.0.1:8444 (management port, loopback only). Phones/laptops enrol against the LAN address printed below.
#>
param([switch]$Fresh, [switch]$NoBuild, [int]$Port = 8443, [string]$SetupToken = "demo-setup-token", [string]$HubName = "Flash Hub")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not $NoBuild) {
    & "$root\tools\flash-gradle.cmd" ":server:installDist"
    if ($LASTEXITCODE -ne 0) { throw "build failed or the Gradle lock is busy (exit $LASTEXITCODE)" }
}
$data = Join-Path $root "server\data\demo"
if ($Fresh -and (Test-Path $data)) { Remove-Item -Recurse -Force $data }
New-Item -ItemType Directory -Force $data | Out-Null
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" } | Select-Object -First 1).IPAddress
if (-not $ip) { $ip = "127.0.0.1" }
$env:FLASH_ALLOW_INSECURE = "true"
$env:FLASH_TLS_MODE = "none"
$env:FLASH_DEV_EMBEDDED_ROOT = "true"
$env:FLASH_DATA_DIR = $data
$env:FLASH_PORT = "$Port"
$env:FLASH_HOST = "0.0.0.0"
$env:FLASH_EXTERNAL_URL = "http://${ip}:$Port"
$env:FLASH_SETUP_TOKEN = $SetupToken
$env:FLASH_HUB_NAME = $HubName
Write-Host ""
Write-Host "Flash demo hub  (INSECURE, development only)"
Write-Host "  Hub (devices enrol here):  $($env:FLASH_EXTERNAL_URL)"
Write-Host "  Console (this PC only):    http://127.0.0.1:8444"
Write-Host "  Setup token:               $SetupToken   (first-run admin creation)"
Write-Host ""
& "$root\server\build\install\server\bin\server.bat" serve
