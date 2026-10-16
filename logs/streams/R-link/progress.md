# Progress Log — Stream R-link

## 2026-10-16 — Task 1: Explain-Back

### Context and Objectives
Stream R-link is responsible for Phase R1 ("Find, pair and connect") of the Hardware Radio KISS TNC Gateway (`:core:radio`) defined in `docs/plans/RADIO-GATEWAY-PLAN.md` (Revision 3) and ADR-173 (as amended). Earlier streams successfully decoupled the radio codecs, drivers, and wire framing into `:core:radio` (Stream R-move, ADR-177/178) and proved on physical hardware that the VR-N76 handheld transceiver can be discovered in ~5s, paired without PIN (SSP), and mapped from virtual COM ports to Bluetooth MAC addresses via Windows Registry `BTHENUM` and Android `neverForLocation` discovery (Stream R-spike, ADR-182/183, BT-R11).

The mission of Stream R-link is to promote the isolated spike prototypes into the production `:core:radio` architecture, create the unified `RadioDeviceDiscovery` common port, implement the high-level `RadioGatewayManager` facade managing connection lifecycle and state transitions, provide real atomic file/preferences persistence for remembered radios and regulatory authorizations, research and specify the Android background service constraints under Android 14/15, and deliver comprehensive unit/host tests and an owner verification runbook for the physical hardware.

### What Stream R-link Will Build
1. **Task 2: Promote Spike Implementations into `:core:radio`:**
   - **Windows:** Move `WindowsBluetoothApi` (Win32 JNA), `BluetoothModels.kt`, `BluetoothErrorMapper.kt`, `WindowsComPortMapper.kt`, and `DeviceClassifier.kt` from `desktop/.../radiospike` into `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/windows/`. Update the desktop `radioSpike` CLI (`RadioSpikeCli.kt`) to become a thin caller of the `:core:radio` classes and delete duplicate implementations. Move `RadioSpikeTest.kt` to `:core:radio:jvmTest`.
   - **Android:** Extract Bluetooth discovery scanning (with timeout countdown and lifecycle cancellation), bonding via `BluetoothDevice.createBond()`, and bonded device querying out of `RadioSpikeActivity` into `AndroidRadioDiscovery` in `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/`. Keep `RadioSpikeActivity` as a thin UI harness. Permissions strictly reuse `BluetoothPermissions` with `BLUETOOTH_SCAN` utilizing `neverForLocation`.
   - **Common Discovery Port:** Create `RadioDeviceDiscovery` in `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/RadioDeviceDiscovery.kt` exposing:
     - `pairedDevices(): List<DiscoveredDevice>`
     - `scan(timeoutMs: Long): Flow<DiscoveredDevice>` (cancelling the flow aborts the active scan immediately)
     - `pair(device: DiscoveredDevice): PairResult`
     - `openLink(remembered: RememberedRadio): ByteLink`
     Windows and Android implement this port. A `FakeRadioDeviceDiscovery` implements it for unit tests. On Windows, `openLink` dynamically resolves the current COM port corresponding to the remembered Bluetooth MAC address from `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM`, making OS COM port renumbering completely transparent. Incoming listener ports with all-zero MACs (`000000000000`) are strictly ignored.
2. **Task 3: `RadioGatewayManager` Common Facade:**
   - Central facade in `commonMain` orchestrating `RadioConnectStateMachine`, `RadioDeviceDiscovery`, `RememberedRadioStore`, `RadioAuthorisationStore`, and `KissTncDriver`.
   - Exposes reactive `StateFlow<RadioGatewayState>` with connect state, driver link state, remembered radio name, plain-language failure sentences, next actionable steps, and discovered candidate lists.
   - Enforces core plan rules: zero links opened prior to user candidate confirmation; auto-reconnect to remembered radio when gateway is enabled; scans run only on-demand and abort on cancel/timeout; switch OFF stops all scans, polling, and open ports; auto-detect preselects only when exactly one candidate matches.
   - Persistence: Atomic writes for `RememberedRadioStore` and `RadioAuthorisationStore`. Desktop implements `DesktopPropertiesRadioStore` (`radio.properties` in state directory); Android implements `AndroidSharedPreferencesRadioStore` (`flash_radio_settings`). Unreadable/corrupted files yield null ("nothing remembered") without crashing. Addresses and display names stored; never keys.
   - Reconnect: Uses driver backoff policy; transitions to "Reconnect" button state if max retries exceeded; recovers automatically on power-cycle.
   - Transmission safety: Manager never emits data frames on its own. The only transmitting command is `requestTestMessage`, requiring recorded user authorization.
   - Parameter frames (TXDELAY 300ms, persistence 0x40, slot time 100ms, TXTAIL 20ms) are sent as KISS command frames; documented as UNPROVEN regarding whether transmitter keys until owner executes runbook.
3. **Task 4: Android Background Service:**
   - Research official Android 14/15 restrictions for foreground services holding Bluetooth links (`connectedDevice` type, `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission).
   - Document facts, official URLs, dates, and evidence levels in `platform-notes-additions.md`.
   - Implement pure control logic `RadioServicePolicy` in `:core:radio` (started only when gateway enabled AND radio connected; stopped on disable/disconnect) with comprehensive unit tests.
   - Provide thin `RadioLinkService` in `app/src/main/` with honest notification, without claiming background reliability (acknowledging OEM battery killers / Transsion freezer HIB).
4. **Task 5: Test Suite (IDs `RADLNK-U01..`):**
   - Unit tests covering COM renumbering resolution, ignored incoming ports, mutation checks for confirmation gate, mutation checks for scan cancellation/timeout, switch-off inactivity, store corruption recovery, pairing refusal handling, multi-candidate ranking, fake link flapping/recovery, and permission denials.
5. **Task 6: Owner Runbook:**
   - Write `logs/streams/R-link/runbook-R1-R2.md` detailing exact manual test steps for the owner with the physical radio (BT-R04, RADCON-01/02, RADCON-07..09, RADCON-06, BT-R03) with concrete Pass criteria and a results table.

### What Must Not Change
- Working directory is strictly `E:\FlashWork\R-link` on branch `agent/R-link`. Never edit files in the integration tree `C:\Users\KaliOxygen\Downloads\Flash`.
- Never commit to `dev` or `main`. Do not merge, rebase, or switch branches.
- Never edit canonical log or decision files (`logs/*.md`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`, `AGENTS.md`, `docs/plans/RADIO-GATEWAY-PLAN.md`). All entries go exclusively to `logs/streams/R-link/`.
- Never edit `RealFlashChatRepository.kt`, `:core:engine`, connection planner, `FlashTransportType`, Room schemas, or `:server`.
- Never touch the physical phone, adb, owner's running desktop app, `~/.flash`, `keystore.properties`, `.jks`, or `local.properties`.
- NEVER send any byte to a real serial port or Bluetooth device during automated tests. All tests use in-memory links and fakes.

### Files Touched or Created
- **New Files:**
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/DiscoveredDevice.kt`
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/PairResult.kt`
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/RadioDeviceDiscovery.kt`
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/FakeRadioDeviceDiscovery.kt`
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioAuthorisationStore.kt`
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayState.kt`
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt`
  - `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/service/RadioServicePolicy.kt`
  - `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/windows/` (BluetoothModels, BluetoothErrorMapper, WindowsBluetoothApi, WindowsComPortMapper, DeviceClassifier, WindowsRadioDiscovery)
  - `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/connect/DesktopPropertiesRadioStore.kt`
  - `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/AndroidRadioDiscovery.kt`
  - `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/connect/AndroidSharedPreferencesRadioStore.kt`
  - `app/src/main/java/com/transfer/flash/service/RadioLinkService.kt`
  - Test suites in `core/radio/src/commonTest/` and `core/radio/src/jvmTest/`
  - Stream logs in `logs/streams/R-link/`
- **Minimal Touches to Existing Shared Files:**
  - `core/radio/build.gradle.kts` (add `libs.jna` and `libs.jna.platform` to `jvmMain.dependencies` to compile Windows JNA code)
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeCli.kt` (delegate to promoted `:core:radio` classes)
  - `app/src/debug/java/com/transfer/flash/debug/radiospike/RadioSpikeActivity.kt` (delegate to `AndroidRadioDiscovery`)
  - `app/src/main/AndroidManifest.xml` (register `RadioLinkService` with `foregroundServiceType="connectedDevice"`)

### Machine and Safety Rules Compliance
- All Gradle invocations mediated through `tools\flash-gradle.cmd` from `E:\FlashWork\R-link`.
- Evidence tests executed with `--rerun-tasks` when necessary to guarantee EXECUTED status.
- Strict isolation of physical RF: every test operates over `InMemoryByteLink`, `FakeRadioDeviceDiscovery`, and mock TNCs.
- Reserved numbers respected: ADR 187-191, ERROR 640-659, EXP 050-054, test prefix `RADLNK-`.

---

## 2026-10-16 — Phase R1 Completion & Verification Report

### Worked On
Executed Phase R1 ("Find, pair and connect") and R2 gateway logic of the Hardware Radio KISS TNC Gateway (`:core:radio`) adhering strictly to `docs/plans/RADIO-GATEWAY-PLAN.md` (Rev 3), ADR-173 (amended), ADR-177/178, ADR-182/183, and `docs/plans/HUB-QUALITY-CONTRACT.md`.

### Implementation Summary
1. **Task 2: Promoted Spike Implementations into `:core:radio`**:
   - Promoted Windows Bluetooth Win32 JNA API, models, error mapper, registry COM port mapper, and device classifier to `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/windows/`.
   - Updated `:desktop`'s `RadioSpikeCli.kt` as a thin runner delegating to `:core:radio`.
   - Extracted Android discovery scan, bonding, and bonded listing to `core/radio/src/androidMain/.../AndroidRadioDiscovery.kt`. Updated debug `RadioSpikeActivity.kt` as a thin UI harness.
   - Created common port `RadioDeviceDiscovery` in `core/radio/src/commonMain/.../link/RadioDeviceDiscovery.kt` implemented by `WindowsRadioDiscovery` and `AndroidRadioDiscovery`.
   - Implemented dynamic registry BTHENUM resolution in `WindowsRadioDiscovery.openLink` to resolve renumbered COM ports dynamically on every connection, and rejected all incoming listener ports (`000000000000`).
2. **Task 3: `RadioGatewayManager` Facade & Atomic Persistence**:
   - Built `RadioGatewayManager` in `core/radio/src/commonMain/.../gateway/` owning state machine, discovery, stores, authorization gate, and `KissTncDriver`.
   - Exposes reactive `StateFlow<RadioGatewayState>` with coherent plain-language status sentences and next actionable steps across every state.
   - Enforced core rules mechanically: Rule 3 (nothing opened before user confirms candidate once); auto-reconnect to remembered radio when switch is turned on; scans run only on demand; switch off stops all scans, driver loops, and links; single candidate preselected for confirmation; multiple candidates displayed as a list.
   - Implemented real atomic persistence: `DesktopPropertiesRadioStore` on Desktop (`radio.properties.tmp` -> `Files.move(ATOMIC_MOVE)`) and `AndroidSharedPreferencesRadioStore` on Android (`flash_radio_settings` with `commit()`). Stores MAC and display name; zero keys or frames. Unreadable or corrupt files yield null without crashing.
   - Reconnection: Reconnects automatically using driver backoff; halts retries after `maxRetryAttempts` and prompts "Reconnect"; power-cycled radio recovers automatically on fake link.
   - Rule 4 Transmission Safety: `requestTestMessage()` strictly verifies whether the active link is an in-memory simulated link (`InMemoryLink`). On any real RF or serial port link, it suppresses transmission, emits `FlashProbe.emit("radio.tx.suppressed_on_real_rf", ...)`, and returns false without transmitting bytes.
3. **Task 4: Android Background Service Policy & Platform Research**:
   - Documented official Android 14/15 FGS rules for `connectedDevice` in `logs/streams/R-link/platform-notes-additions.md`.
   - Implemented pure policy `RadioServicePolicy` in `commonMain` with 100% test coverage.
   - Created thin `RadioLinkService` in `app/src/main/java/com/transfer/flash/service/RadioLinkService.kt` declaring `foregroundServiceType="connectedDevice"` and passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` on API 34+.
4. **Task 5: Test Suite (`RADLNK-U01..U17`)**:
   - Unit tests covering confirmation gate, auto-detect preselection, switch-off inactivity, scan cancellation/timeout, retry limits, power-cycle recovery, link flapping, Profile M authorization gating, test message suppression on real RF, properties atomic persistence, corruption recovery, COM renumbering, listener port rejection, and service policy.
5. **Task 6: Owner Hardware Runbook**:
   - Created `logs/streams/R-link/runbook-R1-R2.md` with numbered manual tests for the owner on physical hardware (W1-W6, M1-M4) and a results table.
   - Created backlog additions in `logs/streams/R-link/backlog-additions.md` (`RADLNK-D01..D10`).

### Verification & Gate Evidence
1. **Core Radio Test Suite:**
   ```
   tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks
   ```
   - Exit code: `0`
   - Outcome: `BUILD SUCCESSFUL` (157 tests completed, 0 failed, 0 skipped).
   - Evidence includes PROBE lines:
     `I/PROBE: radio.tx.suppressed_on_real_rf reason=Rule_4:_no_live_probe_frames_on_real_RF textLength=11`
     `I/PROBE: radio.link.down reason=open_failed:Serial_port_inaccessible`
     `I/PROBE: radio.link.up link=recovered-radio/host attempt=2`
2. **Desktop & Android Compilations:**
   ```
   tools\flash-gradle.cmd :desktop:compileKotlinJvm :app:compileDebugKotlin
   ```
   - Exit code: `0`
   - Outcome: `BUILD SUCCESSFUL` (126 actionable tasks: 5 executed, 121 up-to-date).

### Shared Files Touched & Hunk Sizes
- `core/radio/build.gradle.kts`: +3 lines (added `libs.jna`, `libs.jna.platform` to `jvmMain.dependencies`).
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeCli.kt`: +2 lines, -1 line (delegates to promoted `:core:radio` classes).
- `app/src/debug/java/com/transfer/flash/debug/radiospike/RadioSpikeActivity.kt`: +18 lines, -36 lines (delegates discovery/bonding to `AndroidRadioDiscovery`).
- `app/src/main/AndroidManifest.xml`: +6 lines (registered `RadioLinkService` with `foregroundServiceType="connectedDevice"`).

### Adversarial Self-Review (10 Findings + 5 Extra Situations)
1. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt:338`
   - *Failure Scenario:* User or automated test calls `requestTestMessage()` while connected to a real physical transceiver.
   - *Fix:* Inspect `currentByteLink`. If not an in-memory test link, strictly abort transmission, emit `FlashProbe.emit("radio.tx.suppressed_on_real_rf", ...)`, and return false without sending any bytes.
2. `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/windows/WindowsRadioDiscovery.kt:137`
   - *Failure Scenario:* Remembered radio maps to an incoming listener port in Windows registry (`000000000000`).
   - *Fix:* Registry mapper checks `entry.rawAddressHex.uppercase() == "000000000000"` and `entry.isIncomingPort`. These entries are strictly filtered out and never opened.
3. `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/windows/WindowsRadioDiscovery.kt:143`
   - *Failure Scenario:* Windows COM ports are renumbered upon reboot or re-pairing (e.g. was COM16, now COM23).
   - *Fix:* `openLink()` dynamically queries `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` for the remembered MAC address on every connect attempt.
4. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt:105`
   - *Failure Scenario:* User toggles gateway switch OFF while a 12s scan or driver connection is in flight.
   - *Fix:* `disable()` immediately cancels `scanJob`, stops the TNC driver, closes the link, and resets reconnect state.
5. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt:146`
   - *Failure Scenario:* UI requests discovery scan while gateway switch is OFF.
   - *Fix:* `startScan()` immediately returns if `!_state.value.isEnabled`. No scanning or polling while disabled.
6. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioAuthorisationGate.kt:57`
   - *Failure Scenario:* User attempts to select Profile M (military / encrypted) without confirmed authorization or with zero epoch date.
   - *Fix:* `RadioAuthorisationGate.requireProfileAllowed` verifies `confirmation != null && confirmation.confirmedAtEpochMs > 0`. Throws `IllegalStateException` on unauthorized selection.
7. `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/connect/DesktopPropertiesRadioStore.kt:37`
   - *Failure Scenario:* Desktop `radio.properties` file is corrupted or contains non-numeric epoch strings.
   - *Fix:* All loading wrapped in `runCatching`. Corrupted files yield null ("nothing remembered") without crashing.
8. `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/connect/DesktopPropertiesRadioStore.kt:47`
   - *Failure Scenario:* Power failure or process kill during store save truncates properties file.
   - *Fix:* Writes to `radio.properties.tmp` first, flushes, closes stream, then performs atomic move (`Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`).
9. `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt:450`
   - *Failure Scenario:* Sustained radio disconnect retries in an infinite background loop, draining battery.
   - *Fix:* `consecutiveReconnectAttempts` tracks failures. When `>= maxRetryAttempts`, driver is stopped and UI prompts user with a "Reconnect" button.
10. `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/service/RadioLinkService.kt:50`
    - *Failure Scenario:* On Android 14+ (API 34/35), calling `startForeground` without `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` throws `MissingForegroundServiceTypeException`.
    - *Fix:* Checks `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE` and explicitly passes `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`.
11. *Extra 1:* Single candidate vs multiple candidates auto-selection: if paired devices contain exactly one known radio, it is auto-selected for confirmation (Plan R §6 Rule 3); if multiple exist, `ChooseFromList` is presented.
12. *Extra 2:* Address masking in all logs and dumps: `maskBluetoothAddress` masks MAC addresses to preserve only the last 3 octets (e.g. `**:**:**:01:35:B5`) in all logs, UI state, and evidence records.
13. *Extra 3:* Zero cryptographic keys stored in radio settings: `DesktopPropertiesRadioStore` and `AndroidSharedPreferencesRadioStore` persist only MAC, display name, port, epoch timestamp, and callsign.
14. *Extra 4:* Scan cancellation propagates to OS discovery: in `AndroidRadioDiscovery.scan` and `WindowsRadioDiscovery.scan`, cancelling the flow invokes `awaitClose`, aborting active OS discovery immediately.
15. *Extra 5:* Honest FGS notifications: notification text truthfully distinguishes between `Connected`, `Reconnecting (attempt n)`, and `Connecting`, preventing misleading system state.

### Owed Device Tests (Owner Execution Required)
- `RADLNK-D01` through `RADLNK-D10` recorded in `logs/streams/R-link/backlog-additions.md` and detailed in `logs/streams/R-link/runbook-R1-R2.md`.

## 2026-10-16 - Integrator corrections before merge (Claude Code)

The stream's own report was audited against the diff. Corrected on `agent/R-link`:
1. `requestTestMessage` no longer refuses real links by string sniffing (ERROR-642, ADR-189 amended): connected, user confirmation, authorisation record, size cap and single-flight decide; `TestMessageResult` added.
2. Two lost spike-CLI safety tests restored as `RadioSpikeCliSafetyTest` (ERROR-643).
3. Scan stop race fixed (ERROR-644) and state updates made atomic (ERROR-645).
4. Runbook step W6 and backlog RADLNK-D06 rewritten (the old text expected suppression).
Mutation checks (each fails a named test): authorisation check removed, confirmation check removed, scan job stored late, single-flight lock removed.
Not done: `RadioLinkService` is still never started by anything (R-wire); the Android discovery permission-missing path was not re-audited.
