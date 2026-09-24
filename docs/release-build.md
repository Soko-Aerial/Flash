# Building release APKs and installers

One script builds everything a release needs: `tools/build-release.ps1` (Windows PowerShell 5.1 or later).
`tools/build-release.cmd` is a wrapper for cmd or double-clicking, and passes the same arguments through.

```powershell
.\tools\build-release.ps1                            # unsigned + signed APK, MSI, EXE
.\tools\build-release.ps1 -Target android            # both APKs only
.\tools\build-release.ps1 -Target android -Apk unsigned
.\tools\build-release.ps1 -Target android -Apk signed
.\tools\build-release.ps1 -Target desktop            # MSI + EXE only
.\tools\build-release.ps1 -Target desktop -UberJar   # plus the portable runnable .jar
.\tools\build-release.ps1 -Clean                     # Gradle clean first
.\tools\build-release.ps1 -NoDaemon                  # when output is piped or redirected (CI, a log file)
.\tools\build-release.ps1 -NewKeystore               # one-time: create a release signing key
```

Run it in a normal terminal window. If its output is piped or redirected (CI, `> build.log`, another program),
add `-NoDaemon`. Otherwise the Gradle daemon, which stays alive after the build to speed up the next one, keeps the
pipe open, and the caller waits even though the build finished. Observed 2026-09-24: without it, the build finished
in 12 min but the caller was still waiting 25 min later. With `-NoDaemon`, the build finished in 1 min 11 s and the
caller got control back about 4 min later, once Gradle's helper processes exited.

If PowerShell refuses to run scripts ("running scripts is disabled on this system"), use the `.cmd`
wrapper, or `powershell -ExecutionPolicy Bypass -File tools\build-release.ps1 ...`.

## Output

Everything goes to `dist\<versionName>\` (git-ignored). For a desktop-only run it goes to
`dist\desktop-<packageVersion>\`:

| File | What it is |
|---|---|
| `Flash-<ver>-unsigned.apk` | R8-optimised release APK with no signature. Android will not install it until it's signed. It's for signing elsewhere, or for stores that sign for you. |
| `Flash-<ver>.apk` | The same APK, zip-aligned (16 KB pages) and signed with APK Signature Scheme v2 + v3. apksigner leaves out v1 because `minSdk` is 24. This is the one to install or share. |
| `Flash-<ver>.msi`, `Flash-<ver>.exe` | Windows installers with a bundled Java runtime. Per-user install; no admin rights needed. |
| `Flash-windows-x64-<ver>.jar` | Only with `-UberJar`. It needs Java 21 installed to run. |
| `SHA256SUMS.txt` | Checksums for every file in the folder. |

The versions come from `versionName` in `app/build.gradle.kts` and `packageVersion` in `desktop/build.gradle.kts`.
Bump them there before a release. `versionCode` must also go up for Android to accept the APK as an update.

## Signing

Gradle doesn't sign anything: `app/build.gradle.kts` has no `signingConfig`, so `assembleRelease` gives
`app-release-unsigned.apk`. The script copies that file as the unsigned APK, then runs `zipalign -P 16` and
`apksigner` on a copy to make the signed one. One build gives both, and the key never enters the Gradle build.
If a `signingConfig` is ever added to Gradle, the script stops and says so, because the unsigned file disappears.

The key comes from `keystore.properties` in the repo root (git-ignored), or from environment variables that
override it:

```properties
storeFile=C:\Users\<you>\.flash-signing\flash-release.jks
keyAlias=flash
# optional; any password left out is asked for when the build starts
storePassword=...
keyPassword=...
```

| Environment variable | Overrides |
|---|---|
| `FLASH_KEYSTORE` | `storeFile` |
| `FLASH_KEYSTORE_PASSWORD` | `storePassword` |
| `FLASH_KEY_ALIAS` | `keyAlias` |
| `FLASH_KEY_PASSWORD` | `keyPassword` (defaults to the store password, as in PKCS12) |

Passwords reach `apksigner` through `env:` variables, never on a command line. If no key is configured, the
default `-Apk both` still builds the unsigned APK and prints how to set one up. `-Apk signed` fails instead.

`-NewKeystore` creates `%USERPROFILE%\.flash-signing\flash-release.jks` (RSA 4096, valid 10 000 days, alias
`flash`) with `keytool`, which asks for the password and a name. It then writes `keystore.properties` pointing at
the key. It refuses to overwrite an existing key or config.

**Back up the `.jks` file and its password somewhere other than this PC.** Android only installs an update if it's
signed with the same key as the installed app. If the key or password is lost, every existing install must be
uninstalled, which deletes its chats, trust list and identity, before a new build can be installed.

### The v2.0.0-beta APK was signed with the debug key

Checked with `apksigner verify --print-certs` on 2026-09-24: `app-release-signed-beta.apk`, the one uploaded to
the v2.0.0-beta release, is signed by `CN=Android Debug`, SHA-256
`a7a4e60d4023c9329d2af6f24b1f91e4363e44c3c447fdd8f0c83574e340d01a`. That is Android Studio's per-machine
`%USERPROFILE%\.android\debug.keystore`. Consequences:

- Phones with the beta installed only accept updates signed with that same debug key. An APK signed with a new
  release key is refused as an update ("App not installed" / signature conflict). The beta must be uninstalled
  first, which loses its data.
- The debug key isn't secret (well-known passwords, and it's regenerated if that folder is deleted). It's fine
  for testing, not for public releases.

Two ways forward. This is the owner's call:

1. **Switch to a real release key now** (`-NewKeystore`), while the install base is small. Testers uninstall the
   beta once.
2. **Keep the debug key for the rest of the beta**, so testers can update in place. Point `keystore.properties`
   at it (`storeFile=C:\Users\<you>\.android\debug.keystore`, `keyAlias=androiddebugkey`,
   `storePassword=android`), and move to a real key before 1.0. Back that `debug.keystore` up: if it's
   regenerated, the in-place update path is gone anyway.

## Windows installers

`:desktop:packageMsi` and `:desktop:packageExe` use Compose Multiplatform's jpackage and WiX support. The first
run downloads WiX into `desktop/build`, so it needs the internet once. The installers aren't Authenticode-signed,
so SmartScreen shows "unknown publisher". Signing them needs a code-signing certificate and `signtool`, which the
script doesn't do yet. The Linux `.deb` target in `desktop/build.gradle.kts` can only be built on Linux, so the
script doesn't attempt it.

These are the non-ProGuard `package*` tasks, the same ones the v2.0.0 installers were built with.
`packageRelease*` (ProGuard) has never been verified for this app.

## Environment the script sets up

- **JDK 21.** This Gradle/AGP pair needs 21. Android Studio's bundled JBR 25 is too new. Search order:
  `FLASH_JDK`, then `%USERPROFILE%\.gradle\jdks\*21*` (the Gradle-provisioned JBR 21), then `JAVA_HOME` if it's
  a 21.
- **`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%USERPROFILE%\.gradle\afunix`**, only if `JAVA_TOOL_OPTIONS` isn't
  already set. It works around ERROR-017 ("Unable to establish loopback connection"). Every JVM then prints a
  harmless `Picked up JAVA_TOOL_OPTIONS` line.
- **Android SDK** comes from `sdk.dir` in `local.properties`, else `ANDROID_HOME` or `ANDROID_SDK_ROOT`.
  `apksigner` and `zipalign` come from the newest `build-tools` there. They're only needed for signing.

No tests run: this is a packaging script, not a verification gate. Run the test sweep from
`logs/handoff.md` before cutting a release.
