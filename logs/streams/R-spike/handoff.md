# Current Handoff — Stream R-spike

## Current branch
`agent/R-spike` (worktree `E:\FlashWork\R-spike`)

## Last verified build
`3f181fd1`

## Current phase
Phase R1a — Hardware Radio Spike BT-R11 (Plan R, `docs/plans/RADIO-GATEWAY-PLAN.md`)

## Working features
- **Windows BT-R11 Spike Tool (`:desktop:radioSpike`):**
  - Read-only enumeration of serial COM ports via reflection on `jSerialComm`.
  - Read-only Win32 Bluetooth enumeration of remembered devices and installed service GUIDs via JNA (`bthprops.cpl`).
  - Deterministic mapping of Windows virtual COM ports (e.g. `COM16`) to remote Bluetooth MAC addresses via `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` Device Parameters (empirically validated on this PC with `COM16` -> `**:**:**:01:35:B5`).
  - Active inquiry scan, pairing (`BluetoothAuthenticateDevice`), and serial service activation (`BluetoothSetServiceState`) implemented behind interface `WindowsBluetoothApi`, strictly guarded by `--i-am-the-owner-and-the-radio-is-on` with interactive step-by-step confirmation.
  - JSON machine-readable report with all Bluetooth addresses truncated to last 3 bytes (`**:**:**:01:35:B5`).
  - Excluded from shipped desktop distribution jar (`tasks.named<Jar>("jvmJar")`).
- **Android Debug Spike Screen (`RadioSpikeActivity` in `app/src/debug`):**
  - Permission requests strictly at point of use (`BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` on API 31+; location on API <=30).
  - Listing bonded Bluetooth devices with their service UUIDs.
  - Timed active discovery scan with automatic 12-second countdown timer and immediate lifecycle cancellation when leaving the screen (`onStop`).
  - In-app pairing button using `BluetoothDevice.createBond()` and `ACTION_BOND_STATE_CHANGED` receiver.
  - Pure-Kotlin `AndroidRadioDeviceRanker` prioritizing radios over serial SPP over other peripherals.
  - STRICT SAFETY: Zero RFCOMM connects, zero data/byte transmission. Excluded completely from release APK (`compileReleaseKotlin` passed, release classes verified clean).
- **Owner Runbook (`runbook-BT-R11.md`):**
  - Complete numbered manual testing procedure for the owner with the physical VR-N76 radio on PC and Android.
- **Architectural Proposals & Platform Notes:**
  - `ADR-182` proposed for O-6 (Win32 JNA + registry mapping).
  - `ADR-183` proposed for O-7 (Flash custom in-app discovery list vs Companion Device Manager).
  - `platform-notes-additions.md` recording Bluetooth permissions, discovery, bonding, and CDM facts.

## Verification & Gate Commands Run
1. `:desktop:jvmTest --tests com.transfer.flash.desktop.radiospike.RadioSpikeTest`
   - Result: BUILD SUCCESSFUL (6/6 tests passed).
   - Last line: `[flash-gradle] finished with exit code 0 after 71s of running`
2. `:desktop:radioSpike` (Read-only execution on this PC)
   - Result: BUILD SUCCESSFUL. 4 COM ports enumerated, `COM16` and `COM6` mapped via `REGISTRY_BTHENUM` to masked MACs.
   - Last line: `[flash-gradle] finished with exit code 0 after 22s of running`
3. `:desktop:radioSpike -PradioArgs="--json"`
   - Result: BUILD SUCCESSFUL. Clean JSON emitted with masked MAC addresses.
   - Last line: `[flash-gradle] finished with exit code 0 after 18s of running`
4. `:app:compileDebugKotlin`
   - Result: BUILD SUCCESSFUL.
   - Last line: `[flash-gradle] finished with exit code 0 after 301s of running`
5. `:app:testDebugUnitTest --tests com.transfer.flash.debug.radiospike.AndroidRadioDeviceRankerTest`
   - Result: BUILD SUCCESSFUL (5/5 tests passed).
   - Last line: `[flash-gradle] finished with exit code 0 after 52s of running`
6. `:app:compileReleaseKotlin`
   - Result: BUILD SUCCESSFUL.
   - Verified that `RadioSpikeActivity` and spike classes are completely absent from release build classes.
   - Last line: `[flash-gradle] finished with exit code 0 after 55s of running`

## Shared Files Touched & Hunk Sizes
1. `app/src/debug/AndroidManifest.xml` (Hunk: +6 lines registering `RadioSpikeActivity`)
2. `desktop/build.gradle.kts` (Hunk: +7 lines, -2 lines adding JNA dependencies, registering `radioSpike` task, updating `jvmJar` excludes)
*Zero files in `core/network` or canonical logs touched.*

## What is NOT Done and Why
- **Physical radio live testing (Inquiry scan, pairing PIN, on-air tests):** Per machine safety rules, the agent never pairs, transmits, or alters OS Bluetooth state on this PC. These steps are encapsulated into `runbook-BT-R11.md` for the owner.
- **RFCOMM connection & KISS driver integration:** Stay in later Phase R1 (R-spike scope was strictly the discovery/pairing spike BT-R11).
- **Move from `:core:network` to `:core:radio`:** Handled by parallel stream `R-move`.

## Stop-and-Ask List
1. Sending KISS data frames or serial bytes (never done; safety rule).
2. Pairing/unpairing Bluetooth devices on owner's PC (deferred to owner runbook).
3. Adding new external dependencies (none added; JNA already in `libs.versions.toml`).
4. Modifying files under `core/network` (none modified).

## Recommended Next Task
The owner performs the manual runbook `logs/streams/R-spike/runbook-BT-R11.md` with the physical VR-N76 radio, records the results in the results table, and confirms ADR-182 and ADR-183.
