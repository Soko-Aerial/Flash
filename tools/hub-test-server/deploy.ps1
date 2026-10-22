<#
.SYNOPSIS
  Build the hub on this PC and (re)deploy it to the test hub on `myserver` (~/flash-hub, docker compose project flash-hub).

.DESCRIPTION
  Written for Windows PowerShell 5.1 (no && / ||). Every remote command is the one that was proven on 2026-10-22.
  Steps: build (:server:installDist through tools\flash-gradle.cmd) -> pack one .tgz -> scp -> unpack to app.new
  (hub keeps running) -> stop hub, back up data as root, swap app, recreate hub -> wait for "healthy".
  Rollback copies are kept on the server: app.prev (code, the build that was running before this deploy) and data.bak (data).

  Needs: ssh/scp/tar on PATH, the `myserver` ssh alias, sudo on the server (step 5 uses `ssh -t`, so a password prompt works).
  Never builds on the server. Never touches ./data except to COPY it to data.bak.

.EXAMPLE
  powershell -File tools\hub-test-server\deploy.ps1              # build + deploy
  powershell -File tools\hub-test-server\deploy.ps1 -SkipBuild   # deploy the build already in server\build\install\server
  powershell -File tools\hub-test-server\deploy.ps1 -Rollback    # put app.prev back (code only)
  powershell -File tools\hub-test-server\deploy.ps1 -Rollback -RestoreData   # ... and data.bak over data
#>
param(
    [string]$HostAlias = 'myserver',
    [switch]$SkipBuild,
    [switch]$Rollback,
    [switch]$RestoreData
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$install = Join-Path $repo 'server\build\install\server'
$archive = Join-Path $env:TEMP 'hub.tgz'

function Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }

function Native([string]$what, [scriptblock]$command) {
    & $command
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)" }
}

if ($Rollback) {
    Step 'Rollback: put app.prev back and recreate the hub'
    if ($RestoreData) {
        Native 'rollback (code + data)' {
            ssh -t $HostAlias 'cd ~/flash-hub && docker compose stop hub && rm -rf app && mv app.prev app && sudo rm -rf data && sudo cp -a data.bak data && docker compose up -d --force-recreate hub'
        }
    } else {
        Native 'rollback (code)' {
            ssh -t $HostAlias 'cd ~/flash-hub && docker compose stop hub && rm -rf app && mv app.prev app && docker compose up -d --force-recreate hub'
        }
    }
} else {
    Push-Location $repo
    try {
        $commit = (git rev-parse --short HEAD)
        $dirty = (git status --porcelain)
        Write-Host "Deploying commit $commit" -NoNewline
        if ($dirty) { Write-Host '  (WORKING TREE HAS UNCOMMITTED CHANGES)' -ForegroundColor Yellow } else { Write-Host '' }

        if (-not $SkipBuild) {
            Step '1/6 Build :server:installDist'
            & cmd /c "tools\flash-gradle.cmd :server:installDist"
            if ($LASTEXITCODE -eq 75) { throw 'Another agent holds the Gradle lock (exit 75). Wait and run again.' }
            if ($LASTEXITCODE -ne 0) { throw "build failed (exit code $LASTEXITCODE)" }
        } else {
            Step '1/6 Build skipped (-SkipBuild)'
        }
        if (-not (Test-Path (Join-Path $install 'lib'))) { throw "no build at $install" }

        Step '2/6 Pack the build into one file'
        Native 'tar' { tar -czf $archive -C $install . }

        Step '3/6 Prepare app.prev and app.new on the server (hub keeps running)'
        Native 'prepare' {
            ssh $HostAlias 'cd ~/flash-hub && rm -rf app.prev app.new hub.tgz && cp -a app app.prev && mkdir app.new && echo ready'
        }

        Step '4/6 Upload and unpack (hub keeps running)'
        Native 'scp' { scp $archive "${HostAlias}:flash-hub/hub.tgz" }
        Native 'unpack' {
            ssh $HostAlias 'cd ~/flash-hub && tar -xzf hub.tgz -C app.new && rm hub.tgz && ls app.new && echo "jars: $(ls app.new/lib | wc -l)"'
        }

        Step '5/6 Stop hub, back up data as root, swap, recreate'
        Native 'swap' {
            ssh -t $HostAlias 'cd ~/flash-hub && docker compose stop hub && sudo rm -rf data.bak && sudo cp -a data data.bak && rm -rf app && mv app.new app && docker compose up -d --force-recreate hub'
        }
    } finally {
        Pop-Location
        Remove-Item $archive -ErrorAction SilentlyContinue
    }
}

Step 'Wait for the hub to report healthy, then show status and the log tail'
$state = ssh $HostAlias 'cd ~/flash-hub && for i in $(seq 1 40); do s=$(docker inspect -f ''{{.State.Health.Status}}'' flash-hub-hub-1); [ $s = healthy ] && break; sleep 3; done; echo $s'
$state = ($state | Select-Object -Last 1)
ssh $HostAlias 'cd ~/flash-hub && curl -fsS http://127.0.0.1:18443/health; echo; docker compose ps; docker compose logs --no-color --tail 25 hub'

Write-Host ''
if ($state -eq 'healthy') {
    Write-Host 'PASS: hub is healthy. Check the log tail above for ERROR lines other than the dev-mode root-key banner.' -ForegroundColor Green
    if (-not $Rollback) { Write-Host 'Rollback if needed:  powershell -File tools\hub-test-server\deploy.ps1 -Rollback' }
} else {
    Write-Host "FAIL: hub state is '$state'. Run with -Rollback (add -RestoreData only if the old build cannot read the database)." -ForegroundColor Red
    exit 1
}
