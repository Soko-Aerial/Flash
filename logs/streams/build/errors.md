# Error Log Additions — Stream A (Build)

## ERROR-871 — Dependency verification failures on dynamic ranges and new plugins

### Date
2026-10-30

### Area
Gradle Toolchain / Dependency Verification (`gradle/verification-metadata.xml`)

### Symptoms
Build failed with `Dependency verification failed for configuration ... One artifact failed verification` during configuration phase when requesting `bcutil-jdk18on:1.81` and introducing `com.android.test` plugin.

### Environment
- Gradle: 9.5.0
- JDK: 21.0.10 (JetBrains s.r.o.)
- OS: Windows 11 amd64

### Error
```text
Dependency verification failed for configuration ':server:detachedConfiguration1'
  One artifact failed verification: bcutil-jdk18on-1.81.pom (org.bouncycastle:bcutil-jdk18on:1.81) from repository MavenRepo
```
```text
Error resolving plugin [id: 'com.android.test', version: '9.3.1', apply: false]
> Dependency verification failed for configuration 'detachedConfiguration3'
  One artifact failed verification: com.android.test.gradle.plugin-9.3.1.pom
```

### Root cause
`gradle/verification-metadata.xml` strictly validates SHA-256 checksums for all POM and JAR artifacts.
1. `bcpkix-jdk18on:1.81` declared dynamic range `[1.81, 1.82)`. Pinning `1.81` downloaded `bcutil-jdk18on-1.81.pom`, which was missing from the verification file (the verification file already contained verified checksums for `1.81.1`).
2. Adding `com.android.test` plugin and Macrobenchmark dependencies introduced new artifacts whose checksums were not yet recorded.

### Working fix
1. Pinned `bcutil-jdk18on` specifically to `1.81.1` to match existing verification metadata.
2. Ran `--write-verification-metadata sha256` for `:benchmark` and desktop packaging tasks to record verified checksums.

### Verification
`tools\flash-gradle.cmd :benchmark:compileDebugKotlin` and `:desktop:packageMsi` succeeded with exit code 0.

### Status
RESOLVED
