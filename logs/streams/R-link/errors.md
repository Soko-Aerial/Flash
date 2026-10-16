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
