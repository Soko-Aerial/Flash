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

---

## ERROR-681 - Android gateway lived in the Activity's composition scope (integrator audit, 2026-10-16)

### Area
Android lifetime / foreground service

### Symptoms (by reading the code, not observed on a device)
`MainActivity` built `AndroidRadioGatewayController` with `scope = rememberCoroutineScope()`. When the Activity was destroyed (rotation, back out) that scope was cancelled: the manager, the state collector that stops the foreground service and the scan countdown all died, while `RadioLinkService` kept its "Connected" notification for ever. A new Activity then built a second controller and manager and dialled the same radio again. A failure inside the controller's coroutines also cancelled the shared `persistScope` used for settings writes.

### Root cause
The controller's lifetime was tied to the UI instead of the process, which defeats the purpose of the foreground service.

### Working fix
`RadioGatewayHolder` (process singleton, `SupervisorJob + Dispatchers.Main.immediate` plus a logging exception handler) owns the controller. `MainActivity` gets it from the holder and only installs/removes the permission requester (`permissionRequester` is now a mutable property set in a `DisposableEffect`).

### Verification
`:app:compileDebugKotlin` passes. No Android unit test exists for the controller (it needs a Context); device check RADWIRE-D06.

### Status
RESOLVED in code, UNVERIFIED on a device

---

## ERROR-682 - Foreground-service calls unguarded and repeated on every state emission

### Root cause
`handleManagerStateChange` called `RadioLinkService.start` or `stop` on every manager state (each scan result, each sentence change). `stop` was `startService(ACTION_STOP)`, which creates the service just to stop it and throws `IllegalStateException` from the background on Android 8+. `startForegroundService` throws `ForegroundServiceStartNotAllowedException` from the background on Android 12+. Neither was caught, so one refusal ended the state collector.

### Working fix
`RadioServiceTracker` (`:core:radio`, tests RADWIRE-U13..U17) returns an action only when the service must start, when the notification text changes, or when it must stop after having run. Calls are wrapped in try/catch (logged, tracker reset, the gateway keeps working without the service). `RadioLinkService.stop` uses `stopService`. Mutation-checked: removing the tracker's dedupe fails U14 and U15.

### Status
RESOLVED in code, UNVERIFIED on a device (RADWIRE-D06)

---

## ERROR-683 - Desktop switch file shared with the radio store

### Root cause
The controller wrote `gateway.enabled` into `~/.flash/radio.properties` with its own writer and lock, but `DesktopPropertiesRadioStore` load-modify-saves that whole file under a different lock and uses the same `radio.properties.tmp` name. A switch write and a store write could lose each other or collide on the temp file.

### Working fix
The switch has its own file `~/.flash/radio-gateway.properties`. Test RADWIRE-U18; mutation-checked (putting the switch back in `radio.properties` fails U18). Nothing shipped with the old location, so there is no migration.

### Status
RESOLVED

---

## ERROR-684 - Test message is sent with source callsign NOCALL (OPEN, owner decision)

### Area
Radio gateway / regulatory

### Symptoms
`AuthorisationConfirmation.operatorCallsign` is always null because neither host asks for a callsign, so `requestTestMessage` falls back to source `NOCALL`. On an amateur band an unidentified transmission is not compliant station identification. The transmit warning dialog warns about keying the transmitter but not about this.

### Not fixed on purpose
Adding a callsign field is a UI and storage decision (where it is entered, whether Profile M needs it). Until then RADWIRE-D03 states the limit and only the owner runs it.

### Status
OPEN - owner decision: add a callsign field to the authorisation dialog before the first on-air test? (recommended: yes)

