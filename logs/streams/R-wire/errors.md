# Stream R-wire Errors and Fixes

Reserved error range: ERROR-680 through ERROR-689.

## ERROR-680: Unresolved `kotlinx.coroutines.test` in `:desktop` Test Source Set

### Date
2026-10-16

### Area
Desktop Test / Gradle Dependencies

### Symptoms
`Execution failed for task ':desktop:compileTestKotlinJvm'` with:
```text
Unresolved reference 'runTest'
Unresolved reference 'backgroundScope'
Unresolved reference 'runCurrent'
```

### Environment
- Gradle 8.11 / Kotlin 2.2.10
- Module: `:desktop`
- Target: `jvmTest`

### Root Cause
`desktop/build.gradle.kts` `jvmTest` dependencies block intentionally only includes `implementation(libs.kotlinx.coroutines.core)` and `implementation(libs.junit)`. `kotlinx-coroutines-test` is not declared as a direct test dependency for `:desktop` (unlike multiplatform library modules).

### Failed Attempts
1. Adding `import kotlinx.coroutines.test.*` in `DesktopRadioGatewayControllerTest.kt` failed compilation because the test artifact is not on the desktop test classpath.

### Working Fix
Adhered to the pattern established across all other `:desktop` unit tests (e.g. `DesktopCallingTest`, `FlashDesktopEngineTest`):
1. Replaced `runTest` with `kotlinx.coroutines.runBlocking`.
2. Created a dedicated test `CoroutineScope(SupervisorJob() + Dispatchers.Default)` initialized in `@Before` and cancelled in `@After`.
3. Allowed real coroutine dispatch and short delays (`delay(50..150ms)`), resulting in deterministic, fully executed test verification without modifying module dependencies.

### Verification
`:desktop:compileTestKotlinJvm` compiled with exit code 0, and all 12 `RADWIRE-U01..U12` tests passed under `:desktop:jvmTest`.

### Related files
- `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/radio/DesktopRadioGatewayControllerTest.kt`
- `desktop/build.gradle.kts`

### Status
RESOLVED
