# Current Handoff — Stream R-move

## Current branch
`agent/R-move` (in worktree `E:\FlashWork\R-move`)

## Last verified build
`e05a4a8f12f798045560e0abcf521efe38c07d59`

## Current phase
Plan R (Revision 3, `docs/plans/RADIO-GATEWAY-PLAN.md`):
- Task 1: Explain-Back (COMPLETE)
- Task 2: R0 Move — creation of `:core:radio`, migration from `:core:network`, relocation of `ReconnectPolicy` to `:core:common.resilience`, build acceptance, mutation check (COMPLETE, commit `3dced8fe`)
- Task 3: Platform-independent connect logic in `com.transfer.flash.core.radio.connect`, candidate ranking, state machine, authorization gating, unit tests `RADCONL-U01..U07`, 3 mutation checks (COMPLETE, commit `e05a4a8f`)

## Working features
- `:core:radio` Kotlin Multiplatform module (JVM + Android library) with `explicitApi()` and `-Xexpect-actual-classes`.
- Hardware radio framing, AX.25, KISS codecs (`KissFrameCodec`, `Ax25FrameCodec`).
- TNC driver, transmit pacing, link state (`KissTncDriver`, `TxPacing`, `RadioState`).
- Wire format framing, encryption, session handshake (`RadioWire`, `RadioCrypto`, `RadioSession`, `StreamFramer`).
- Platform serial/Bluetooth catalogs: `JvmSerialPortCatalog` (desktop jSerialComm), `AndroidBluetoothCatalog` (Android RFCOMM).
- Desktop diagnostics and CLI tasks (`radioLinkTest`, `radioLinkTestCli`).
- Candidate ranking and auto-detection (`KnownRadioNames`, `RadioCandidateRanker`).
- Radio connection state machine (`RadioConnectStateMachine`) with Plan R Rule 3 (never open before confirm) and Rule 4 (no liveness RF transmission probes) enforced.
- Profile authorization gate (`RadioAuthorisationGate`) enforcing positive confirmation timestamp for Profile M (encrypted).
- Bidirectional dependency guard: `:core:radio` does not depend on `:core:network`, and `:core:network` does not depend on `:core:radio` (`RadioDependencyDirectionContractTest`).

## In progress
None. Tasks 1, 2, and 3 are complete.

## Broken
None. All 134 JVM tests and 88 Android host tests pass.

## Last change
`feat(radio): implement platform-independent connect logic and state machine (Plan R §6, §8)` (commit `e05a4a8f`).

## Last test
- `tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks` (134 tests executed, 0 failures).
- `tools\flash-gradle.cmd :core:radio:testAndroidHostTest --rerun-tasks` (88 tests executed, 0 failures).
- `tools\flash-gradle.cmd :desktop:compileKotlinJvm :desktop:compileTestKotlinJvm :app:compileDebugKotlin` (BUILD SUCCESSFUL).
- `tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"` (BUILD SUCCESSFUL, listed COM ports without opening).
- 3 mutation checks verified on JVM tests.

## Known blockers
None.

## Recommended next task
Integrate `RadioConnectStateMachine` and `RememberedRadioStore` with the UI layer (Android Compose settings/gateway screens and Desktop Compose screens) and real persistent preferences (Plan R §6 UI and integration stream).

## Files most relevant to next task
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioConnectStateMachine.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioCandidateRanker.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioAuthorisationGate.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RememberedRadio.kt`
- `core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/connect/RadioConnectLogicTest.kt`
- `logs/streams/R-move/backlog-additions.md`
