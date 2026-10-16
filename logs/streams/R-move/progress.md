# Progress Log — Stream R-move (Hardware Radio KISS TNC Gateway & Connect Logic)

## 2026-10-16 — Task 1: Explain-Back (Plan R Revision 3, Sections 3, 4, 6, 8)

### What moves
All hardware radio framing, codecs, drivers, pacing, platform serial/Bluetooth catalogs, diagnostics, simulation, and tests currently residing in `:core:network` (`com.transfer.flash.core.network.kiss` and `com.transfer.flash.core.network.radio`) move into a newly created Kotlin Multiplatform library module: `:core:radio` (`com.transfer.flash.core.radio` and subpackages `kiss`, `link`, `driver`, `wire`, `sim`, `diag`, and new `connect`). Specifically:
- KISS and AX.25 codecs: `KissFrameCodec.kt`, `Ax25FrameCodec.kt` and tests.
- Link abstraction & permissions: `ByteLink.kt`, `BluetoothPermissions.kt`, `classifyPort` and tests.
- Driver, pacing & state: `KissTncDriver.kt`, `TxPacing.kt`, `RadioState.kt` and tests.
- Flash radio wire frame & crypto: `RadioWire.kt`, `RadioCrypto.kt`, `RadioSession.kt`, `StreamFramer.kt` and golden/crypto vector tests.
- Platform serial/Bluetooth links: `JvmSerialPortCatalog.kt` (desktop jSerialComm), `AndroidBluetoothCatalog.kt` (Android RFCOMM sockets), platform actuals for `RadioLock` and `JdkRadioCrypto`.
- Test harness & simulation: `RadioSimulation.kt`, `RadioLinkTester.kt`, `RadioLinkTestCli.kt`, `RadioLinkTestHarness.kt` and tests.
- Dependency & desktop task wiring: `jSerialComm` 2.11.4 dependency moves from `:core:network` jvmMain to `:core:radio` jvmMain; desktop Gradle tasks `radioLinkTest` and `radioLinkTestCli` point to `com.transfer.flash.core.radio.diag`.

### What must not change
- Wire formats, frame structures, cryptographic contracts, and golden vectors remain byte-identical: no change to AX.25 UI headers, 12-byte Flash radio frame headers, AES-256-GCM ciphertexts/tags, or KISS FEND/FESC framing.
- Public behavior, API contracts, and tests: moved tests remain unmodified except for necessary package and import updates.
- No changes to `:core:engine`, `RealFlashChatRepository`, `FlashTransportType`, Room database schemas, or server modules.
- Strict hardware safety: never touch real serial ports, never open Bluetooth sockets against real radios in automated tests; tests strictly execute against in-memory link pairs (`InMemoryLink`) and simulated TNCs (`FakeTnc`).
- No new external dependencies beyond moving `jSerialComm` (with its ADR-101 license note).

### Dependency direction
- Strict unidirectional separation: `:core:network` must NOT depend on `:core:radio`, and `:core:radio` must NOT depend on `:core:network`.
- `:core:radio` depends only on `:core:common` (and later `:core:security` only if session key derivation requires it).
- Only host modules (`:app` and `:desktop`) depend on `:core:radio`. `:core:engine` remains completely unaware of `:core:radio`.
- Guard: A Gradle/test architectural guard will verify that neither module imports or references the other.

### Acceptance in Plan Section 4.4
1. Test parity: Exactly the same number of radio/kiss tests run and pass on JVM (`:core:radio:jvmTest`) and Android host (`:core:radio:testAndroidHostTest`) as in the baseline.
2. Golden vectors: `RadioGoldenFrameTest` and `RadioCryptoVectorsTest` remain 100% byte-identical.
3. Cleanliness: `core/network` contains zero radio/kiss source files and zero references to `jSerialComm`.
4. Dependency check: `:core:radio` has no dependency on `:core:network`, and `:core:network` has no dependency on `:core:radio`.
5. Host compilation: `:app:compileDebugKotlin`, `:desktop:compileKotlinJvm`, and `:desktop:compileTestKotlinJvm` compile cleanly.
6. Desktop CLI: `tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"` executes successfully and enumerates ports without opening any.
7. Publishing verification: The Maven publication list in `jitpack.yml` builds without failure (release dry-run).

### Handling of ReconnectPolicy
Inspection of `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/resilience/ReconnectPolicy.kt` confirms that it contains ZERO network types or imports. It is a pure Kotlin mathematical implementation of floored exponential backoff with full jitter.
Per Plan R §4.2 and prompt instruction Task 2.b:
- `ReconnectPolicy` and its companion unit test `ReconnectPolicyTest` will be moved to `:core:common` under package `com.transfer.flash.core.common.resilience`.
- Callers in `:core:network` (`DefaultFlashNetwork`, `WsFlashNetwork`, `JvmWsFlashNetwork`, `ChaosNetworkHarness`, `ChaosResilienceTest`) and `:core:radio` (`KissTncDriver`) will update their imports to `com.transfer.flash.core.common.resilience.ReconnectPolicy`.
- This cleanly preserves single-source logic without duplicating code or creating an undesirable cross-dependency between `:core:radio` and `:core:network`.

---

## 2026-10-16 — Task 2: Baseline (Pre-Move Verification)

### Executed Baseline Test Counts in `:core:network`

**Command 1 (JVM):**
`tools\flash-gradle.cmd :core:network:jvmTest --tests "com.transfer.flash.core.network.radio.*" --tests "com.transfer.flash.core.network.kiss.*" --rerun-tasks`
Result: 15 actionable tasks executed, 0 failures.
JVM test execution breakdown by class:
- `Ax25FrameCodecTest[jvm]`: 11 tests (0 failures, 0 skipped)
- `KissFrameCodecTest[jvm]`: 22 tests (0 failures, 0 skipped)
- `BluetoothPermissionsTest[jvm]`: 3 tests (0 failures, 0 skipped)
- `RadioLinkTestHarnessTest[jvm]`: 4 tests (0 failures, 0 skipped)
- `KissTncDriverTest[jvm]`: 17 tests (0 failures, 0 skipped)
- `RadioCryptoVectorsTest[jvm]`: 6 tests (0 failures, 0 skipped)
- `RadioGoldenFrameTest[jvm]`: 1 test (0 failures, 0 skipped)
- `RadioLinkTesterTest[jvm]`: 6 tests (0 failures, 0 skipped)
- `RadioSessionTest[jvm]`: 26 tests (0 failures, 0 skipped)
- `RadioStateTest[jvm]`: 25 tests (0 failures, 0 skipped)
- `StreamFramerTest[jvm]`: 3 tests (0 failures, 0 skipped)
**Total JVM Baseline Tests Executed: 124 tests, 0 failures.**

**Command 2 (Android Host):**
`tools\flash-gradle.cmd :core:network:testAndroidHostTest --tests "com.transfer.flash.core.network.radio.*" --tests "com.transfer.flash.core.network.kiss.*" --rerun-tasks`
Result: 26 actionable tasks executed, 0 failures.
Android host test execution breakdown by class:
- `Ax25FrameCodecTest`: 11 tests (0 failures, 0 skipped)
- `KissFrameCodecTest`: 22 tests (0 failures, 0 skipped)
- `BluetoothPermissionsTest`: 3 tests (0 failures, 0 skipped)
- `KissTncDriverTest`: 17 tests (0 failures, 0 skipped)
- `RadioStateTest`: 25 tests (0 failures, 0 skipped)
- `StreamFramerTest`: 3 tests (0 failures, 0 skipped)
**Total Android Host Baseline Tests Executed: 81 tests, 0 failures.**

### Golden Vector Hashes
Golden vectors are declared in test sources (no external resource files):
- `core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/radio/RadioGoldenFrameTest.kt`:
  SHA-256: `70C3FFEE62E0021338343E93F40E173D7E4F04279B3FA0E326692FF165F8089E`
- `core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/radio/RadioCryptoVectorsTest.kt`:
  SHA-256: `26DB059F36AECD47EDB949625EBE1D5B05B1BEB5F9C6BA22590C78CCFE60D02C`


---

## 2026-10-16 — Task 2: R0 Move Execution & Acceptance Verification (Commit `3dced8fe`)

### Worked on
Created dedicated Kotlin Multiplatform module `:core:radio` and migrated all hardware radio codecs, AX.25, KISS, TNC drivers, pacing, serial/Bluetooth catalogs, diagnostics, simulation, and tests from `:core:network`. Migrated `ReconnectPolicy` to `:core:common.resilience`.

### Changed
1. `core/radio/build.gradle.kts`: Configured KMP targeting JVM and Android library, with `explicitApi()`, compiler flag `-Xexpect-actual-classes`, and Maven publishing.
2. `core/common/build.gradle.kts`: Added `commonTest.dependencies { implementation(kotlin("test")) }`.
3. `core/common/src/commonMain/.../ReconnectPolicy.kt`: Moved `ReconnectPolicy` from `:core:network` to `com.transfer.flash.core.common.resilience`, applied `explicitApi()`. Updated all callers in `:core:network` and `:core:radio`.
4. `core/radio/src/...`: Relocated 23 source files and 11 test files using `git mv` into package `com.transfer.flash.core.radio` and subpackages (`kiss`, `link`, `driver`, `wire`, `sim`, `diag`).
5. `desktop/build.gradle.kts`: Updated tasks `radioLinkTest` and `radioLinkTestCli` main class references to `com.transfer.flash.core.radio.diag.RadioLinkTestCliKt` and `RadioLinkTesterKt`.
6. `desktop/src/jvmMain/.../RadioLinkTestMain.kt`: Updated imports to `com.transfer.flash.core.radio.diag`.
7. `settings.gradle.kts`, `jitpack.yml`, `.github/workflows/ci.yml`: Added `:core:radio` to project include, publish list, and CI build matrix.
8. Documentation: Updated path references in `docs/ui/radio-link-test.md`, `docs/network/RADIO-WIRE-FORMAT.md`, `docs/reports/2026-10-09-bluetooth-radio.md`, and `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md` with relocation annotations.
9. Added `RadioDependencyDirectionContractTest.kt` in `:core:radio` asserting bidirectional isolation between `:core:radio` and `:core:network`.

### Verification (Plan R §4.4 Acceptance)
1. **JVM Test Parity**:
   `tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks`
   Executed: 124 baseline tests + 3 contract tests = 127 tests executed, 0 failures.
   - `Ax25FrameCodecTest[jvm]`: 11 tests
   - `KissFrameCodecTest[jvm]`: 22 tests
   - `BluetoothPermissionsTest[jvm]`: 3 tests
   - `RadioLinkTestHarnessTest[jvm]`: 4 tests
   - `KissTncDriverTest[jvm]`: 17 tests
   - `RadioCryptoVectorsTest[jvm]`: 6 tests
   - `RadioGoldenFrameTest[jvm]`: 1 test
   - `RadioLinkTesterTest[jvm]`: 6 tests
   - `RadioSessionTest[jvm]`: 26 tests
   - `RadioStateTest[jvm]`: 25 tests
   - `StreamFramerTest[jvm]`: 3 tests
   - `RadioDependencyDirectionContractTest[jvm]`: 3 tests
2. **Android Host Test Parity**:
   `tools\flash-gradle.cmd :core:radio:testAndroidHostTest --rerun-tasks`
   Executed: 81 tests executed, 0 failures.
   - `Ax25FrameCodecTest`: 11 tests
   - `KissFrameCodecTest`: 22 tests
   - `BluetoothPermissionsTest`: 3 tests
   - `KissTncDriverTest`: 17 tests
   - `RadioStateTest`: 25 tests
   - `StreamFramerTest`: 3 tests
3. **Golden Vector Byte Identity**:
   Verified byte-identical literals in `RadioGoldenFrameTest.kt` and `RadioCryptoVectorsTest.kt`.
4. **Source Cleanliness**:
   Verified zero radio/kiss/ax25 files remaining in `core/network/src/`. Verified no `jSerialComm` in `core/network/build.gradle.kts`.
5. **Dependency Direction & Mutation Check**:
   - `RadioDependencyDirectionContractTest` asserts `:core:radio` has no reference to `:core:network` and `:core:network` has no reference to `:core:radio`.
   - Mutation check: injected `implementation(project(":core:network"))` into `core/radio/build.gradle.kts`. Test immediately failed with `AssertionError: :core:radio must not depend on :core:network`. Restored and verified pass.
6. **Host Compilation**:
   - `tools\flash-gradle.cmd :desktop:compileKotlinJvm :desktop:compileTestKotlinJvm :app:compileDebugKotlin` -> BUILD SUCCESSFUL.
7. **Desktop CLI Non-Opening Listing**:
   - `tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"` -> BUILD SUCCESSFUL. Listed ports (COM15, COM7, COM16, COM6) without opening any port handle.
8. **Publish Dry-Run**:
   - `tools\flash-gradle.cmd :core:radio:publishToMavenLocal` -> BUILD SUCCESSFUL.

---

## 2026-10-16 — Task 3: Platform-Independent Connect Logic & State Machine (Commit `e05a4a8f`)

### Worked on
Implemented pure Kotlin multiplatform connect logic in `com.transfer.flash.core.radio.connect` per Plan R §6 and §8: candidate ranking, auto-detection, remembered radio identity store, connection state machine, authorization gating for encrypted profiles, and comprehensive unit tests `RADCONL-U01..U07`.

### Changed
1. `KnownRadioNames.kt`: Data-driven catalog of known radio models (VR-N76, UV-Pro, GA-5WB, etc.), case-insensitive, tolerant of trailing parenthesized `(COM16)` suffixes.
2. `RadioCandidateRanker.kt`: Candidate model `RadioCandidateDevice`, ranking categories (`KNOWN_RADIO`, `SERIAL_CAPABLE`, `OTHER`). Enforces rule that auto-detect preselects ONLY when exactly one candidate matches; never selects an `OTHER` device.
3. `RememberedRadio.kt`: Data model and `RememberedRadioStore` interface. Radio identity is strictly the Bluetooth MAC address (`bluetoothAddress`); port names (`lastKnownPortName`) are preserved solely as Windows fallback hints. Includes `InMemoryRememberedRadioStore`.
4. `RadioConnectStateMachine.kt`: Implements the Plan R §6 connection state machine with states: `Off`, `ChecklistShown`, `Detecting`, `ConfirmCandidate`, `ChooseFromList`, `Scanning`, `Pairing`, `Connecting`, `Connected`, `Failed(reason)`.
   - Enforces Rule 3: Never emits `OpenLink` before explicit user confirmation.
   - Enforces Rule 4: Never emits data frames as liveness probes. Transmit commands are strictly confined to `UserRequestedTestMessage`, which requires `isAuthorised == true`.
   - 9 failure reasons with exactly 1 plain English sentence and 1 next step each.
5. `RadioAuthorisationGate.kt`: Enforces Plan R §8 security profile selection. Profile A (plaintext signed) is always selectable. Profile M (encrypted) strictly requires recorded authorization confirmation with a positive epoch timestamp (`confirmedAtEpochMs > 0`).
6. `RadioConnectLogicTest.kt`: Added unit tests `RADCONL-U01..U07` verifying ranking, auto-detect ambiguity handling, confirmation enforcement, hardware address identity, failure reasons, liveness transmit prohibition, and authorization gating.

### Verification & Mutation Checks
- **JVM Tests**: `tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks`
  Executed: 134 tests (127 baseline + 7 connect logic), 0 failures.
  `RadioConnectLogicTest`: 7/7 passed.
- **Android Host Tests**: `tools\flash-gradle.cmd :core:radio:testAndroidHostTest --rerun-tasks`
  Executed: 88 tests (81 baseline + 7 connect logic), 0 failures.
  `RadioConnectLogicTest`: 7/7 passed.
- **Mutation Check 1 (Auto-detect ambiguous)**:
  Mutated `RadioCandidateRanker.autoDetectCandidate` to return the first known radio when multiple match (`knownRadios.isNotEmpty()`).
  Result: `radconlU02AutoDetectSelectsOnlyWhenExactlyOneCandidateMatches` FAILED with `AssertionError`. Restored and verified pass.
- **Mutation Check 2 (Open link before confirm)**:
  Mutated `RadioConnectStateMachine.transition` to emit `RadioConnectCommand.OpenLink` immediately upon `DetectionResult`.
  Result: `radconlU03NeverOpenLinkBeforeExplicitConfirmation` FAILED with `AssertionError`. Restored and verified pass.
- **Mutation Check 3 (Authorisation gate date bypass)**:
  Mutated `RadioAuthorisationGate.isProfileAllowed` to allow `PROFILE_M_ENCRYPTED` unconditionally without confirmation.
  Result: `radconlU07AuthorisationGateEnforcesConfirmationAndDate` FAILED with `AssertionError`. Restored and verified pass.

---

## Shared Files Touched & Hunk Sizes

### Commit `3dced8fe` (R0 Move)
- `.github/workflows/ci.yml`: 2 hunks (+4, -2) — added `:core:radio` to test steps.
- `app/build.gradle.kts`: 1 hunk (+1, -0) — added dependency on `:core:radio`.
- `core/common/build.gradle.kts`: 1 hunk (+4, -0) — added `kotlin("test")` to `commonTest`.
- `core/network/build.gradle.kts`: 1 hunk (+0, -2) — removed `libs.jserialcomm`.
- `core/network/src/.../DefaultFlashNetwork.kt`, `WsFlashNetwork.kt`, `JvmWsFlashNetwork.kt`, `ChaosNetworkHarness.kt`, `ChaosResilienceTest.kt`: 1 hunk each (+1, -1) — updated `ReconnectPolicy` imports to `:core:common`.
- `desktop/build.gradle.kts`: 2 hunks (+7, -2) — updated radioLinkTest classpath and added dependency on `:core:radio`.
- `desktop/src/.../RadioLinkTestMain.kt`: 2 hunks (+5, -5) — updated imports.
- `docs/ui/radio-link-test.md`, `docs/network/RADIO-WIRE-FORMAT.md`, `docs/reports/2026-10-09-bluetooth-radio.md`, `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md`: 1 hunk each (+2, -2) — package/path move annotations.
- `jitpack.yml`: 1 hunk (+1, -1) — added `:core:radio` to publish list.
- `settings.gradle.kts`: 1 hunk (+1, -0) — included `:core:radio`.

### Commit `e05a4a8f` (Task 3 Connect Logic)
- Zero shared existing files touched. Strictly added new pure Kotlin files in `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/` and test file in `core/radio/src/commonTest/kotlin/...`.

---

## Adversarial Self-Review

### Specific Findings (Path:Line, Scenario, Fix)
1. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/KnownRadioNames.kt:25`:
   - *Failure scenario*: Windows Bluetooth serial drivers frequently append COM port strings in parentheses to device names, e.g. "Standard Serial over Bluetooth link (COM16)" or "VR-N76 (COM7)". If regex was matched directly against the full string, suffix tokens might fail word boundary anchors `\b`.
   - *Fix*: `cleanName = rawName.substringBefore('(').trim()` strips parenthesized COM designations before tokenizing, ensuring `\bVR-?N76\b` reliably matches "VR-N76 (COM7)".
2. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioCandidateRanker.kt:75`:
   - *Failure scenario*: Bluetooth scans report candidates with missing RSSI (null) alongside candidates with measured dBm (e.g. -85 dBm). If null mapped to 0, devices with unknown signal would rank above devices with weak but known signal.
   - *Fix*: `thenByDescending { it.device.rssi ?: Int.MIN_VALUE }` ensures any device with an actual measured RSSI ranks above candidates with unknown (null) RSSI.
3. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioCandidateRanker.kt:77`:
   - *Failure scenario*: Discovered devices with identical display name and RSSI resulting in non-deterministic UI candidate ordering across discovery passes.
   - *Fix*: `thenBy { it.device.addressOrPort }` provides strict deterministic tie-breaking based on hardware address.
4. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioCandidateRanker.kt:93-113`:
   - *Failure scenario*: Auto-detect on first run encounters two paired VR-N76 radios. If the ranker simply took the first result, it would arbitrarily connect to the wrong radio without user consent.
   - *Fix*: `autoDetectCandidate` strictly requires `knownRadios.size == 1`. If `knownRadios.size > 1`, it returns `null`, forcing the state machine to present `ChooseFromList`.
5. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioConnectStateMachine.kt:181`:
   - *Failure scenario*: Premature link opening upon auto-detect match grabs the RFCOMM socket or serial port before user confirmation.
   - *Fix*: Enforces Plan R Rule 3. Emits `RadioConnectState.ConfirmCandidate(candidate)` with ZERO link commands. Only after `RadioConnectEvent.UserConfirmedCandidate` is received does it transition to `Connecting` and emit `OpenLink`.
6. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioConnectStateMachine.kt:140`:
   - *Failure scenario*: User toggles Radio Gateway switch OFF while state machine is in `Scanning`, `Pairing`, `Connecting`, or `Connected`. If off toggle was handled state-by-state, background scans or sockets could leak.
   - *Fix*: Global intercept in `transition()` for `event is RadioConnectEvent.ToggleGateway && !event.enable`: immediately transitions to `RadioConnectState.Off`, stops scans, and emits `RadioConnectCommand.CloseLink`.
7. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioConnectStateMachine.kt:238`:
   - *Failure scenario*: OS Bluetooth pairing prompt fails or is rejected. If unhandled, the state machine would hang forever in `Pairing` state.
   - *Fix*: Handles `RadioConnectEvent.PairingResult(success = false)` by transitioning to `RadioConnectState.Failed(WRONG_PIN or PAIRING_REFUSED)`, providing clear remediation and allowing retry.
8. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioConnectStateMachine.kt:323`:
   - *Failure scenario*: Malicious or accidental emission of test frames or liveness probes transmitting RF without operator authorization.
   - *Fix*: Plan R Rule 4 enforcement. `RadioConnectCommand.UserRequestedTestMessage` validates `require(isAuthorised)` at construction, preventing unauthorized RF transmission at the type level.
9. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RememberedRadio.kt:18`:
   - *Failure scenario*: Treats dynamic Windows port names ("COM16") as persistent identity. When USB dongles or Bluetooth pairings are re-enumerated, COM numbers change and reconnect fails.
   - *Fix*: `RememberedRadio` enforces that `bluetoothAddress` is the sole immutable primary key (`require(bluetoothAddress.isNotBlank())`). `lastKnownPortName` is strictly an optional fallback hint.
10. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioAuthorisationGate.kt:33`:
    - *Failure scenario*: Bypass of amateur radio encryption prohibition via dummy confirmation records with timestamp `0L` or negative epoch milliseconds.
    - *Fix*: `AuthorisationConfirmation` enforces `require(confirmedAtEpochMs > 0)` at construction, and `RadioAuthorisationGate.isProfileAllowed` verifies that timestamp is strictly positive.

### Extra Situations Handled
1. **First-Run Checklist**: When gateway switch is enabled and no remembered radio exists, transitions to `ChecklistShown`, enforcing user acknowledgment of hardware/regulatory prerequisites before scanning or querying paired devices.
2. **Startup Reconnect**: When gateway switch is enabled and a remembered radio exists, directly connects to that radio's hardware address, bypassing discovery and confirmation modals.
3. **Mid-Connection Link Loss**: When an active Bluetooth connection drops (`LinkLost`), transitions to `Failed(LINK_LOST)` and emits `CloseLink` to cleanly release socket resources.
4. **Serial Port Contention**: When another application holds an exclusive lock on the COM/serial port, transitions to `Failed(PORT_IN_USE)` with explicit next-step guidance: "Close other serial programs or terminals and tap Retry."
5. **Plaintext Profile Fallback**: When user cancels or refuses Profile M authorization, `RadioAuthorisationGate.selectableProfiles` guarantees `PROFILE_A_PLAINTEXT_SIGNED` remains selectable for legal plaintext communication.

---

## What is NOT Done & Why

1. **Android / Desktop UI Screens**: Plan R §6 UI screens (Checklist composable, Device Picker modal, Confirmation dialog, Gateway status card) belong to the UI integration stream, not stream `R-move`. `RadioConnectStateMachine` provides pure multiplatform business logic with zero Android/Desktop UI dependencies.
2. **Platform Persistent Storage for RememberedRadio**: Real persistence (via `SettingsKeys` / Room / DataStore) is deferred to the integration layer. Stream `R-move` provides the `RememberedRadioStore` interface and `InMemoryRememberedRadioStore` for testing.
3. **Physical Hardware Verification**: No physical radios or Bluetooth TNCs were opened or transmitted to in this stream. All tests executed against in-memory link abstractions. Device tests are queued in `logs/streams/R-move/backlog-additions.md` (`RADMOVE-01..03` and `RADCONL-*`).
