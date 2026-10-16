# Error Log Additions — Stream R-link

## ERROR-640 — UncompletedCoroutinesError in RadioGatewayManagerTest under TestScope

### Date
2026-10-16

### Area
RadioGatewayManager unit tests / Coroutines

### Symptoms
`kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, there were active child jobs ... Use TestScope.backgroundScope to launch the coroutines that need to be cancelled when the test body finishes` occurred in `RadioGatewayManagerTest`.

### Environment
- Kotlin Coroutines Test 1.9.0
- JVM 17
- Windows 11

### Root cause
`RadioGatewayManager` was initialized with `scope = this` (`TestScope`). `KissTncDriver.start()` launches a continuous reconnect loop (`while (coroutineContext.isActive)`), and flow collection jobs on `driver.state` remain active until cancelled. When `runTest` finished, `TestScope` waited 60s for all child jobs to complete, causing timeout failures.

### Working fix
1. Passed `scope = backgroundScope` to `RadioGatewayManager` and `KissTncDriver` in test rigs so that background listener/driver loops are automatically cancelled when the test body completes.
2. In `RealMockByteLink`, added `delay(timeoutMs)` to prevent busy-wait 0-byte read loops.
3. Called `manager.disable()` at the end of tests to verify clean resource teardown.

### Verification
All 10 test cases in `RadioGatewayManagerTest` passed in 0.42s without timeouts.

### Related files
- `core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt`

### Status
RESOLVED

---

## ERROR-641 — Nullability Mismatch Between RadioCandidateDevice and DiscoveredDevice Name

### Date
2026-10-16

### Area
KMP Type Safety / Radio models

### Symptoms
Compilation failure: `Argument type mismatch: actual type is 'String?', but 'String' was expected` in `RadioGatewayManager.kt`.

### Root cause
`RadioCandidateDevice.name` is nullable (`String?`) to allow unnamed raw serial ports (e.g. COM16 before OS identification), whereas `DiscoveredDevice.name` and `RememberedRadio.displayName` require non-null `String`.

### Working fix
Used explicit fallback: `cmd.candidate.name ?: cmd.candidate.addressOrPort` and `target.name ?: target.addressOrPort`.

### Verification
`:core:radio:compileKotlinJvm` and `:core:radio:compileAndroidMain` compiled with zero errors.

### Related files
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt`

### Status
RESOLVED

## ERROR-642 — "Send a test message" suppressed on every real link, by string sniffing

### Date
2026-10-16

### Area
Radio gateway / transmit safety

### Symptoms
`RadioGatewayManager.requestTestMessage` returned false for any link whose class name did not contain "InMemory" and whose description did not contain "mem" or "fake". On the owner's real VR-N76 the plan's user action could never work; the authorisation record was never consulted.

### Root cause
The agent applied the rule "agents and tests never transmit" inside product code and detected test links by name.

### Working fix
New contract `requestTestMessage(text, userConfirmedTransmit): TestMessageResult` (connected, user confirmation, authorisation record, 1..64 bytes, single-flight); no link-type inspection (ADR-189 amendment). Tests RADLNK-U09a..c; mutation-checked.

### Related files
- `core/radio/.../gateway/RadioGatewayManager.kt`, `TestMessageResult.kt`, `RadioGatewayManagerTest.kt`

### Status
RESOLVED in code (device check RADLNK-D06 owed; it transmits).

---

## ERROR-643 — Two spike-CLI safety tests lost in the move to `:core:radio`

### Date
2026-10-16

### Area
Testing / BT-R11 spike CLI

### Symptoms
`desktop/.../RadioSpikeTest` had 8 tests, the moved copy 6. `testActiveModeRefusesToRunWithoutOwnerFlag` and `testActiveModeRunsWithFlagAndFakeApi` were gone; the recording fake went with the old file. Nothing tested the `--i-am-the-owner-and-the-radio-is-on` gate any more.

### Working fix
`desktop/src/jvmTest/.../radiospike/RadioSpikeCliSafetyTest.kt` (3 tests, local recording fake): active mode without the flag makes zero Bluetooth calls; read-only mode makes no mutating call and no inquiry; active mode with the flag pairs only the chosen device.

### Status
RESOLVED

---

## ERROR-644 — stopScan right after startScan could leave a scan running

### Date
2026-10-16

### Area
Radio gateway / scan lifecycle

### Root cause
`startScan` stored the scan job inside a coroutine launched later, so a `stopScan()` issued before that coroutine ran found no job to cancel.

### Working fix
The job handle is assigned synchronously (`scanJob = scope.launch {...}`); a previous scan is cancelled without a StopScanning transition. Test RADLNK-U09d; mutation-checked (storing the job late fails it).

### Status
RESOLVED in code

---

## ERROR-645 — Non-atomic state updates in `RadioGatewayManager`

### Date
2026-10-16

### Area
Radio gateway / concurrency

### Root cause
`updateState` read the state, transformed it and wrote it back; the driver collector and user commands run in different coroutines, so one update could overwrite the other.

### Working fix
`MutableStateFlow.update { }` (atomic compare-and-set). Not reproduced by a failing test (UNPROVEN as an observed bug; fixed as a latent race).

### Status
RESOLVED in code
