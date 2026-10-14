<#
.SYNOPSIS
  Runs Gradle (or any command) under a machine-wide lock so parallel agents never build at the same time.

.DESCRIPTION
  Developer tool, not product code. Docs: docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3.
  The dev PC has 8 logical CPUs and about 20 GB RAM; every Gradle run starts a 2 GB daemon plus a Kotlin daemon plus test
  workers, so two builds at once swap and fail with OutOfMemory. All agents therefore call Gradle ONLY through this script.
  It sets the two environment values this machine needs (JBR 21 and the AF_UNIX loopback fix, see logs/handoff.md and the
  gradle-build-env note), waits for the lock, runs the command in the CURRENT directory (your own worktree), and releases the
  lock even when the command fails or the script is stopped.

.EXAMPLE
  powershell -File tools\flash-gradle.ps1 :core:enterprise:jvmTest
  powershell -File tools\flash-gradle.ps1 :server:test --tests "*OrganizationDirectorySyncTest*"

.NOTES
  -WaitMinutes  how long to wait for the lock before giving up with exit code 75 (default 45).
  -Runner       command to run instead of .\gradlew.bat (used by the self test only).
  A holder that dies leaves an "abandoned" mutex; Windows hands it to the next waiter, which is correct here.
#>
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs,
    [int]$WaitMinutes = 45,
    [string]$Runner = ""
)

$ErrorActionPreference = "Stop"
$lockName = "Global\FlashGradleBuild"
$mutex = New-Object System.Threading.Mutex($false, $lockName)
$started = Get-Date
$held = $false

try {
    Write-Host ("[flash-gradle] waiting for the build lock ({0}) as {1} in {2}" -f $lockName, $env:USERNAME, (Get-Location))
    try {
        $held = $mutex.WaitOne([TimeSpan]::FromMinutes($WaitMinutes))
    } catch [System.Threading.AbandonedMutexException] {
        # The previous holder was killed. We own the lock now; that is the intended recovery.
        $held = $true
        Write-Host "[flash-gradle] previous holder died; lock taken over"
    }
    if (-not $held) {
        Write-Host ("[flash-gradle] gave up after {0} minutes: another agent is still building. Try again later." -f $WaitMinutes)
        exit 75
    }
    $waited = [int]((Get-Date) - $started).TotalSeconds
    Write-Host ("[flash-gradle] lock acquired after {0}s" -f $waited)

    $jbr = "C:\Users\KaliOxygen\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"
    if ($Runner -eq "") {
        if (-not (Test-Path $jbr)) { throw "JBR 21 not found at $jbr (is the drive holding ~/.gradle attached?)" }
        $afunix = "C:\Users\KaliOxygen\.gradle\afunix"
        if (-not (Test-Path $afunix)) { New-Item -ItemType Directory -Path $afunix | Out-Null }
        $env:JAVA_HOME = $jbr
        $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$afunix"
        if (-not (Test-Path ".\gradlew.bat")) { throw "no gradlew.bat in $(Get-Location): run this from your own worktree root" }
        & ".\gradlew.bat" @GradleArgs
    } else {
        & $Runner @GradleArgs
    }
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    $ran = [int]((Get-Date) - $started).TotalSeconds - $waited
    Write-Host ("[flash-gradle] finished with exit code {0} after {1}s of running" -f $code, $ran)
    exit $code
}
finally {
    if ($held) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
