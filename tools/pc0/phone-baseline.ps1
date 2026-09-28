<#
.SYNOPSIS
  PC0 phone-side measurement (docs/network/PC0-RUNBOOK.md): battery drain, CPU and Wi-Fi cost of
  holding N idle Flash sessions with the screen off, plus a Wi-Fi toggle for the reconnect test.

.DESCRIPTION
  -Action start    Resets batterystats, enlarges the logcat buffer, records the starting battery level.
                   Then UNPLUG the phone (USB charging invalidates the numbers), turn the screen off and
                   leave it for the run length.
  -Action collect  Plug the phone back in and run immediately. Saves the raw dumps and prints a summary:
                   drain, Flash's CPU time and Wi-Fi traffic, and how often the phone dialed / refused
                   peers (the session-cap churn).
  -Action toggle-wifi  Turns Wi-Fi off for -OffSeconds, then on. Watch the peer farm's [recover] lines.

  Output: measurements\pc0\<Label>\ (git-ignored). Copy the summary numbers into logs/experiments.md.

.EXAMPLE
  .\tools\pc0\phone-baseline.ps1 -Action start   -Label belfone-n4 -Serial ABC123
  .\tools\pc0\phone-baseline.ps1 -Action collect -Label belfone-n4 -Serial ABC123
  .\tools\pc0\phone-baseline.ps1 -Action toggle-wifi -Serial ABC123 -OffSeconds 10
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('start', 'collect', 'toggle-wifi')][string]$Action,
    [string]$Label,
    [string]$Serial,
    [int]$OffSeconds = 10,
    [string]$Package = 'com.transfer.flash'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $props = Join-Path $repo 'local.properties'
    if (Test-Path $props) {
        $line = Select-String -Path $props -Pattern '^sdk\.dir=(.*)$' | Select-Object -First 1
        if ($line) {
            $sdk = $line.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\'
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    throw 'adb not found: put platform-tools on PATH or set sdk.dir in local.properties.'
}

$adb = Find-Adb

function Invoke-Adb([string[]]$AdbArgs) {
    $all = @()
    if ($Serial) { $all += @('-s', $Serial) }
    $all += $AdbArgs
    $out = & $adb @all 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' ') failed: $out" }
    return ($out | Out-String)
}

function Assert-Device {
    $all = @()
    if ($Serial) { $all += @('-s', $Serial) }
    $state = (& $adb @($all + 'get-state') 2>$null | Out-String).Trim()
    if ($state -ne 'device') {
        $list = (& $adb devices 2>$null | Out-String).Trim()
        throw "No usable phone$(if ($Serial) { " with serial $Serial" }) (adb get-state: '$state'). adb devices:`n$list"
    }
}

Assert-Device

function Get-OutDir {
    if (-not $Label) { throw '-Label is required for start/collect (e.g. belfone-n4).' }
    $dir = Join-Path $repo "measurements\pc0\$Label"
    New-Item -ItemType Directory -Force $dir | Out-Null
    return $dir
}

function Get-BatteryLevel {
    $b = Invoke-Adb @('shell', 'dumpsys', 'battery')
    if ($b -match 'level:\s*(\d+)') { return [int]$Matches[1] }
    return $null
}

function Get-AppUidTag {
    # 10123 -> u0a123, the name batterystats uses for the app's uid. Older builds print `userId=`,
    # newer ones `appId=` (Android 14 on the Infinix X6882B prints only `appId=`/`uid=`).
    $p = Invoke-Adb @('shell', 'dumpsys', 'package', $Package)
    if ($p -notmatch '(?:userId|appId)=(\d+)') { throw "$Package is not installed on the device." }
    $uid = [int]$Matches[1]
    return @{ Uid = $uid; Tag = ('u0a{0}' -f ($uid - 10000)) }
}

switch ($Action) {
    'start' {
        $dir = Get-OutDir
        $app = Get-AppUidTag
        $model = (Invoke-Adb @('shell', 'getprop', 'ro.product.model')).Trim()
        $release = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.release')).Trim()
        $version = ''
        $pkg = Invoke-Adb @('shell', 'dumpsys', 'package', $Package)
        if ($pkg -match 'versionName=(\S+)') { $version = $Matches[1] }
        Invoke-Adb @('shell', 'dumpsys', 'batterystats', '--reset') | Out-Null
        # A 1-hour run overflows the default ring buffer; not every device accepts -G, so it is best-effort.
        try { Invoke-Adb @('logcat', '-G', '16M') | Out-Null } catch { Write-Warning "logcat -G 16M refused: $_" }
        Invoke-Adb @('logcat', '-c') | Out-Null
        $level = Get-BatteryLevel
        $started = Get-Date
        @(
            "label=$Label", "serial=$Serial", "model=$model", "android=$release", "flashVersion=$version",
            "uid=$($app.Uid) ($($app.Tag))", "startLevel=$level", "startedAt=$($started.ToString('o'))"
        ) | Set-Content -Encoding utf8 (Join-Path $dir 'start.txt')
        Write-Host "[pc0] $model (Android $release, Flash $version), battery $level%, stats reset at $($started.ToString('HH:mm:ss'))."
        Write-Host '[pc0] Now: 1) unplug USB  2) screen off  3) do not touch it for the whole run.'
        Write-Host "[pc0] Then plug back in and run: -Action collect -Label $Label$(if ($Serial) { " -Serial $Serial" })"
    }

    'collect' {
        $dir = Get-OutDir
        $startFile = Join-Path $dir 'start.txt'
        if (-not (Test-Path $startFile)) { throw "No start.txt in $dir. Run -Action start first." }
        $start = @{}
        Get-Content $startFile | ForEach-Object { $k, $v = $_ -split '=', 2; $start[$k] = $v }
        $endLevel = Get-BatteryLevel
        $app = Get-AppUidTag

        $stats = Invoke-Adb @('shell', 'dumpsys', 'batterystats', $Package)
        $stats | Set-Content -Encoding utf8 (Join-Path $dir 'batterystats.txt')
        $log = Invoke-Adb @('logcat', '-d', '-v', 'threadtime')
        $log | Set-Content -Encoding utf8 (Join-Path $dir 'logcat.txt')

        $minutes = [math]::Round(((Get-Date) - [datetime]$start['startedAt']).TotalMinutes, 1)
        $lines = $stats -split "`r?`n"
        $tag = [regex]::Escape($app.Tag)
        # Printed as found rather than parsed: the batterystats layout differs between Android versions,
        # and a wrong parse is worse than a raw line a human can read.
        $general = $lines | Where-Object {
            $_ -match 'Estimated battery capacity:|Time on battery:|Screen off discharge:|^\s*Discharge:|Computed drain:'
        } | Select-Object -First 6
        $uidPower = $lines | Where-Object { $_ -match "^\s*(Uid )?$tag[: ]" -and $_ -match 'mAh|\(' } | Select-Object -First 3
        $inUid = $false
        $uidDetail = foreach ($l in $lines) {
            if ($l -match "^\s{2}$tag`:") { $inUid = $true; continue }
            if ($inUid -and $l -match '^\s{0,2}\S') { break }
            # `Total cpu time per freq` rows are 40 numbers each; the plain total is the useful one.
            if ($inUid -and $l -match 'Wi-Fi|Wifi|Total cpu time|Wake lock|TOTAL wake|Wakeup alarm|CPU:' -and $l -notmatch 'per freq') { $l.Trim() }
        }
        $dials = ([regex]::Matches($log, 'Auto-connect dialing peer=')).Count
        $dialOk = ([regex]::Matches($log, 'Auto-connect result peer=\S+ success=true')).Count
        $capRefusals = ([regex]::Matches($log, 'session cap reached')).Count
        $gatewayDials = ([regex]::Matches($log, 'Auto-connect dialing gateway')).Count

        $summary = @(
            "# PC0 $Label",
            "model=$($start['model']) android=$($start['android']) flash=$($start['flashVersion'])",
            "run=$minutes min, battery $($start['startLevel'])% -> $endLevel%",
            "",
            "## Device (batterystats)",
            $general,
            "",
            "## Flash uid $($app.Tag)",
            $uidPower,
            $uidDetail,
            "",
            "## Phone-side connection churn (logcat; only as complete as the log buffer)",
            "auto-connect dials=$dials succeeded=$dialOk session-cap refusals=$capRefusals gateway-probe dials=$gatewayDials"
        )
        $summary | Set-Content -Encoding utf8 (Join-Path $dir 'summary.txt')
        $summary | ForEach-Object { Write-Host $_ }
        Write-Host "[pc0] raw dumps in $dir"
        if ($minutes -lt 30) { Write-Warning 'Run was under 30 min: drain in % is too coarse to compare.' }
    }

    'toggle-wifi' {
        Write-Host "[pc0] Wi-Fi off for $OffSeconds s at $((Get-Date).ToString('HH:mm:ss')); watch the farm's [degrade]/[recover] lines."
        Invoke-Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
        Start-Sleep -Seconds $OffSeconds
        Invoke-Adb @('shell', 'svc', 'wifi', 'enable') | Out-Null
        Write-Host "[pc0] Wi-Fi on at $((Get-Date).ToString('HH:mm:ss'))."
    }
}
