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
