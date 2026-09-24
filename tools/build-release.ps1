<#
.SYNOPSIS
    Builds Flash release outputs: the Android APK (unsigned and/or signed) and the Windows
    installers (MSI + EXE), and collects them in dist\<version>\ with SHA-256 checksums.

.DESCRIPTION
    Run from anywhere; the script finds the repository from its own location.

      .\tools\build-release.ps1                          # everything: unsigned + signed APK, MSI, EXE
      .\tools\build-release.ps1 -Target android          # APKs only
      .\tools\build-release.ps1 -Target android -Apk unsigned
      .\tools\build-release.ps1 -Target desktop          # MSI + EXE only
      .\tools\build-release.ps1 -Target desktop -UberJar # also the portable runnable .jar
      .\tools\build-release.ps1 -NewKeystore             # one-time: create a release signing key

    Signing is read from keystore.properties in the repo root (git-ignored), or from the
    environment variables FLASH_KEYSTORE, FLASH_KEYSTORE_PASSWORD, FLASH_KEY_ALIAS and
    FLASH_KEY_PASSWORD. Passwords that are not set are asked for when signing. Nothing is
    signed inside Gradle: Gradle builds the unsigned APK and this script signs a copy with
    apksigner, so one build gives both files. See docs/release-build.md.

.PARAMETER Target
    all (default), android or desktop.

.PARAMETER Apk
    both (default), signed or unsigned. With "both" and no signing key configured, the
    unsigned APK is still produced and the script explains how to set up signing.

.PARAMETER UberJar
    Also build the single runnable desktop .jar (needs Java installed to run it).

.PARAMETER Clean
    Run Gradle "clean" first.

.PARAMETER NoDaemon
    Build without leaving a Gradle daemon running. Use this when the output is piped or
    redirected (CI, a log file, another program): a daemon that stays alive keeps the pipe
    open, so the caller waits until the daemon exits hours later. Not needed in a normal
    terminal window.

.PARAMETER NewKeystore
    Create a new release keystore with keytool and write keystore.properties, then stop.
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'android', 'desktop')]
    [string]$Target = 'all',

    [ValidateSet('both', 'signed', 'unsigned')]
    [string]$Apk = 'both',

    [switch]$UberJar,
    [switch]$Clean,
    [switch]$NoDaemon,
    [switch]$NewKeystore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$KeystoreProps = Join-Path $RepoRoot 'keystore.properties'

function Write-Step([string]$Text) { Write-Host ""; Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Note([string]$Text) { Write-Host "    $Text" -ForegroundColor Gray }
function Write-Warn([string]$Text) { Write-Host "    WARNING: $Text" -ForegroundColor Yellow }
function Fail([string]$Text) { Write-Host ""; Write-Host "BUILD FAILED: $Text" -ForegroundColor Red; exit 1 }

# Runs a native program and fails the build on a non-zero exit code. $ErrorActionPreference is
# relaxed around the call because Windows PowerShell 5.1 turns a native program's stderr output
# (Gradle and the JDK write informational lines there) into terminating errors under 'Stop'.
function Invoke-Native([string]$Exe, [string[]]$Arguments, [string]$What) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Exe @Arguments } finally { $ErrorActionPreference = $saved }
    if ($LASTEXITCODE -ne 0) { Fail "$What (exit code $LASTEXITCODE)" }
}

# Reads a Java-style .properties file literally: backslashes in Windows paths are kept as typed.
function Read-Properties([string]$Path) {
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $map
}

function Get-VersionFrom([string]$File, [string]$Pattern) {
    $m = Select-String -LiteralPath $File -Pattern $Pattern | Select-Object -First 1
    if ($null -eq $m) { Fail "could not read the version from $File" }
    return $m.Matches[0].Groups[1].Value
}

# --- JDK -------------------------------------------------------------------------------------
# This Gradle/AGP pair needs JDK 21. Android Studio's bundled JBR 25 is too new, so it is not
# used even when JAVA_HOME points at it. Order: FLASH_JDK, the Gradle-provisioned JBR 21,
# then JAVA_HOME if it is a 21.
function Find-Jdk {
    $candidates = @()
    if ($env:FLASH_JDK) { $candidates += $env:FLASH_JDK }
    $jdks = Join-Path $env:USERPROFILE '.gradle\jdks'
    if (Test-Path $jdks) {
        $candidates += Get-ChildItem -LiteralPath $jdks -Directory |
            Where-Object { $_.Name -match '21' } |
            Sort-Object Name -Descending |
            ForEach-Object { $_.FullName }
    }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($c in $candidates) {
        $java = Join-Path $c 'bin\java.exe'
        if (-not (Test-Path $java)) { continue }
        $release = Join-Path $c 'release'
        if ((Test-Path $release) -and -not (Select-String -LiteralPath $release -Pattern 'JAVA_VERSION="21' -Quiet)) {
            if ($c -ne $env:FLASH_JDK) { continue }   # FLASH_JDK is trusted as given
        }
        return $c
    }
    Fail "no JDK 21 found. Install one (Android Studio > Settings > Build Tools > Gradle > Gradle JDK can download 'jbr-21'), or set FLASH_JDK to its folder."
}

# --- Android SDK tools -----------------------------------------------------------------------
function Find-BuildTools {
    $sdk = $null
    $lp = Read-Properties (Join-Path $RepoRoot 'local.properties')
    if ($lp.ContainsKey('sdk.dir')) { $sdk = $lp['sdk.dir'] -replace '\\:', ':' -replace '\\\\', '\' }
    if (-not $sdk -and $env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk -and $env:ANDROID_SDK_ROOT) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk -or -not (Test-Path $sdk)) { Fail "Android SDK not found (set sdk.dir in local.properties, or ANDROID_HOME)." }
    $bt = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
        Select-Object -First 1
    if ($null -eq $bt) { Fail "no build-tools with apksigner under $sdk\build-tools. Install one with the SDK Manager." }
    return $bt.FullName
}

# --- Signing config --------------------------------------------------------------------------
function Get-SigningConfig {
    $p = Read-Properties $KeystoreProps
    $cfg = @{
        StoreFile     = $(if ($env:FLASH_KEYSTORE) { $env:FLASH_KEYSTORE } elseif ($p.ContainsKey('storeFile')) { $p['storeFile'] } else { $null })
        StorePassword = $(if ($env:FLASH_KEYSTORE_PASSWORD) { $env:FLASH_KEYSTORE_PASSWORD } elseif ($p.ContainsKey('storePassword')) { $p['storePassword'] } else { $null })
        KeyAlias      = $(if ($env:FLASH_KEY_ALIAS) { $env:FLASH_KEY_ALIAS } elseif ($p.ContainsKey('keyAlias')) { $p['keyAlias'] } else { $null })
        KeyPassword   = $(if ($env:FLASH_KEY_PASSWORD) { $env:FLASH_KEY_PASSWORD } elseif ($p.ContainsKey('keyPassword')) { $p['keyPassword'] } else { $null })
    }
    if (-not $cfg.StoreFile) { return $null }
    if (-not [System.IO.Path]::IsPathRooted($cfg.StoreFile)) { $cfg.StoreFile = Join-Path $RepoRoot $cfg.StoreFile }
    if (-not (Test-Path -LiteralPath $cfg.StoreFile)) { Fail "keystore not found: $($cfg.StoreFile) (from keystore.properties / FLASH_KEYSTORE)" }
    return $cfg
}

function Read-Secret([string]$Prompt) {
    $s = Read-Host -Prompt $Prompt -AsSecureString
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}

function Show-SigningHelp {
    Write-Note "To produce a signed APK, set up a key once:"
    Write-Note "  .\tools\build-release.ps1 -NewKeystore"
    Write-Note "or write keystore.properties in the repo root (see docs/release-build.md)."
}

# --- -NewKeystore ----------------------------------------------------------------------------
if ($NewKeystore) {
    $jdk = Find-Jdk
    if (Test-Path $KeystoreProps) { Fail "keystore.properties already exists. Delete or rename it first if you really want a new key." }
    $dir = Join-Path $env:USERPROFILE '.flash-signing'
    $ks = Join-Path $dir 'flash-release.jks'
    if (Test-Path $ks) { Fail "$ks already exists. Refusing to overwrite a signing key." }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    Write-Step "Creating a release signing key at $ks"
    Write-Note "keytool will ask for a password and your name/organisation. Choose a strong password"
    Write-Note "and store it in a password manager: without it you can never publish an update."
    Invoke-Native (Join-Path $jdk 'bin\keytool.exe') @('-genkeypair', '-v', '-keystore', $ks, '-storetype', 'PKCS12',
        '-alias', 'flash', '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000') 'keytool'

    @(
        '# Flash release signing. NOT committed (see .gitignore). Back up the .jks file AND its password:'
        '# losing either means existing installs can never be updated.'
        "storeFile=$ks"
        'keyAlias=flash'
        '# Optional, for unattended builds. Leave commented out to be asked each time.'
        '# storePassword='
        '# keyPassword='
    ) | Set-Content -LiteralPath $KeystoreProps -Encoding ASCII

    Write-Step "Done"
    Write-Note "Key:        $ks"
    Write-Note "Config:     $KeystoreProps"
    Write-Note "BACK UP the .jks file somewhere other than this PC, together with its password."
    exit 0
}

# --- Build -----------------------------------------------------------------------------------
$doAndroid = $Target -in @('all', 'android')
$doDesktop = $Target -in @('all', 'desktop')
$wantSigned = $doAndroid -and ($Apk -in @('both', 'signed'))
$wantUnsigned = $doAndroid -and ($Apk -in @('both', 'unsigned'))

$appVersion = Get-VersionFrom (Join-Path $RepoRoot 'app\build.gradle.kts') 'versionName\s*=\s*"([^"]+)"'
$deskVersion = Get-VersionFrom (Join-Path $RepoRoot 'desktop\build.gradle.kts') 'packageVersion\s*=\s*"([^"]+)"'

# Resolve everything that can fail BEFORE the long Gradle run, and ask for passwords up front so
# the build can then run unattended.
$signing = $null
$buildTools = $null
if ($wantSigned) {
    $signing = Get-SigningConfig
    if ($null -eq $signing) {
        if ($Apk -eq 'signed') { Show-SigningHelp; Fail "no signing key configured." }
        Write-Warn "no signing key configured: only the unsigned APK will be built."
        Show-SigningHelp
        $wantSigned = $false
    } else {
        $buildTools = Find-BuildTools
        if (-not $signing.KeyAlias) { Fail "keyAlias is missing in keystore.properties." }
        if (-not $signing.StorePassword) { $signing.StorePassword = Read-Secret "Keystore password for $(Split-Path -Leaf $signing.StoreFile)" }
        if (-not $signing.KeyPassword) { $signing.KeyPassword = $signing.StorePassword }   # PKCS12: same password
    }
}

$jdk = Find-Jdk
$env:JAVA_HOME = $jdk
# Moves the JDK's AF_UNIX socket folder out of %TEMP%. Some Windows setups fail Gradle with
# "Unable to establish loopback connection" without it (logs/errors.md ERROR-017); harmless otherwise.
if (-not $env:JAVA_TOOL_OPTIONS) {
    $afunix = Join-Path $env:USERPROFILE '.gradle\afunix'
    New-Item -ItemType Directory -Force -Path $afunix | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$afunix"
}

$tasks = @()
if ($Clean) { $tasks += 'clean' }
$gradleFlags = @('--console=plain')
if ($NoDaemon) { $gradleFlags += '--no-daemon' }
if ($doAndroid) { $tasks += ':app:assembleRelease' }
if ($doDesktop) {
    $tasks += ':desktop:packageMsi', ':desktop:packageExe'
    if ($UberJar) { $tasks += ':desktop:packageUberJarForCurrentOS' }
}

Write-Step "Building Flash (Android $appVersion, desktop $deskVersion)"
Write-Note "JDK:   $jdk"
Write-Note "Tasks: $($tasks -join ' ')"
$started = Get-Date
Push-Location $RepoRoot
try {
    Invoke-Native (Join-Path $RepoRoot 'gradlew.bat') ($tasks + $gradleFlags) 'Gradle build'
} finally { Pop-Location }

# --- Collect outputs -------------------------------------------------------------------------
$dist = Join-Path $RepoRoot "dist\$appVersion"
if ($doDesktop -and -not $doAndroid) { $dist = Join-Path $RepoRoot "dist\desktop-$deskVersion" }
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$produced = @()

if ($doAndroid) {
    $unsignedSrc = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if (-not (Test-Path $unsignedSrc)) {
        Fail "expected $unsignedSrc. If a signingConfig was added to app/build.gradle.kts, remove it: this script does the signing."
    }
    if ($wantUnsigned) {
        $out = Join-Path $dist "Flash-$appVersion-unsigned.apk"
        Copy-Item -LiteralPath $unsignedSrc -Destination $out -Force
        $produced += $out
    }
    if ($wantSigned) {
        Write-Step "Signing the APK"
        $aligned = Join-Path $dist 'aligned.tmp.apk'
        $signedOut = Join-Path $dist "Flash-$appVersion.apk"
        # -P 16: 16 KB page alignment for the uncompressed native libraries (WebRTC, SQLCipher),
        # which Android 15+ devices with 16 KB pages require.
        Invoke-Native (Join-Path $buildTools 'zipalign.exe') @('-f', '-P', '16', '4', $unsignedSrc, $aligned) 'zipalign'
        $env:FLASH_SIGN_KS_PASS = $signing.StorePassword
        $env:FLASH_SIGN_KEY_PASS = $signing.KeyPassword
        try {
            # Passwords go through env: so they never appear on a command line.
            Invoke-Native (Join-Path $buildTools 'apksigner.bat') @('sign', '--ks', $signing.StoreFile,
                '--ks-key-alias', $signing.KeyAlias, '--ks-pass', 'env:FLASH_SIGN_KS_PASS',
                '--key-pass', 'env:FLASH_SIGN_KEY_PASS', '--v4-signing-enabled', 'false',
                '--out', $signedOut, $aligned) 'apksigner sign'
        } finally {
            Remove-Item Env:FLASH_SIGN_KS_PASS, Env:FLASH_SIGN_KEY_PASS -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $aligned -Force -ErrorAction SilentlyContinue
        }
        Invoke-Native (Join-Path $buildTools 'apksigner.bat') @('verify', '--print-certs', $signedOut) 'apksigner verify'
        $produced += $signedOut
    }
}

if ($doDesktop) {
    $bin = Join-Path $RepoRoot 'desktop\build\compose\binaries\main'
    foreach ($f in @("msi\Flash-$deskVersion.msi", "exe\Flash-$deskVersion.exe")) {
        $src = Join-Path $bin $f
        if (-not (Test-Path $src)) { Fail "expected installer $src" }
        Copy-Item -LiteralPath $src -Destination $dist -Force
        $produced += (Join-Path $dist (Split-Path -Leaf $src))
    }
    if ($UberJar) {
        $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'desktop\build\compose\jars') -Filter "*-$deskVersion.jar" |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $jar) { Fail "expected the runnable jar under desktop\build\compose\jars" }
        Copy-Item -LiteralPath $jar.FullName -Destination $dist -Force
        $produced += (Join-Path $dist $jar.Name)
    }
}

# Checksums for everything in the folder, so a re-run for one target keeps the others listed.
$sums = Get-ChildItem -LiteralPath $dist -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | Sort-Object Name |
    ForEach-Object { "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLower(), $_.Name }
$sums | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding ASCII

Write-Step ("Done in {0:mm\:ss}" -f ((Get-Date) - $started))
foreach ($f in $produced) {
    Write-Note ("{0,-45} {1,8:N1} MB" -f (Split-Path -Leaf $f), ((Get-Item -LiteralPath $f).Length / 1MB))
}
Write-Note "Folder: $dist"
if ($doDesktop) { Write-Note "The Windows installers are not code-signed: SmartScreen will say 'unknown publisher'." }
