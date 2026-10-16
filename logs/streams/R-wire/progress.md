# Progress Log — Stream R-wire

## 2026-10-16 — Task 1: Explain-Back

### Context and Objectives
Stream R-wire is the integration and host-wiring stream for the Hardware Radio KISS TNC Gateway (`:core:radio`) in the Flash application (Android + Windows Desktop Kotlin Multiplatform).

Earlier streams completed the underlying foundations:
- **Stream R-move (ADR-177/178):** Separated KISS/AX.25 codecs, TNC driver, and wire framing into `:core:radio`.
- **Stream R-spike (ADR-182/183, BT-R11):** Empirically verified Bluetooth discovery, Win32 JNA pairing, registry `BTHENUM` dynamic port mapping, and Android `neverForLocation` scanning on real hardware (VR-N76).
- **Stream R-link (ADR-187..191, RADLNK-U01..U17):** Implemented production `RadioGatewayManager`, `RadioDeviceDiscovery` (Windows + Android), atomic persistence (`DesktopPropertiesRadioStore`, `AndroidSharedPreferencesRadioStore`), `RadioServicePolicy`, and `RadioLinkService`.
- **Stream R-ui (UI-070..UI-074, RADUI-U01..U10):** Implemented `FlashRadioGatewayScreen`, `RadioGatewayController` interface, and `RadioGatewayUiState` in `:ui:chat`. Integrator fix `b389d365` gated the Settings entry behind real controller availability (`FlashRadioMath.isGatewayEntryAvailable`).

The mission of Stream R-wire is to provide the concrete host glue binding `:ui:chat`'s `RadioGatewayController` port to `:core:radio`'s `RadioGatewayManager`:
1. Build `AndroidRadioGatewayController` in `:app`.
2. Build `DesktopRadioGatewayController` in `:desktop`.
3. Wire the real controllers into `MainActivity` (Android) and `DesktopShell` (Desktop), activating the Settings row in release builds while preserving fake controllers for standalone demos and tests.
4. Enforce strict manager lifetime: lazy creation on demand, never started at boot if switch is off, fully closed/stopped when switch is off.
5. Provide point-of-use Android nearby devices runtime permission flow (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) and Bluetooth settings redirect.
6. Connect `RadioServicePolicy` to `RadioLinkService` on Android to maintain process liveness honestly with foreground notifications during active radio connections.
7. Implement JVM unit/adapter tests (`RADWIRE-U01..`) covering end-to-end flows, failure sentence mappings, lifetime invariants, and mutation checks.
8. Document owed physical device tests (`RADWIRE-D01..`) for owner verification in `backlog-additions.md`.

### What Stream R-wire Will Build
- **Task 2: Android Host Adapter (`AndroidRadioGatewayController`):**
  - New file `app/src/main/java/com/transfer/flash/radio/AndroidRadioGatewayController.kt` implementing `RadioGatewayController`.
  - Bridges `RadioGatewayState` -> `RadioGatewayUiState`.
  - Manages lazy lifecycle of `RadioGatewayManager` with `AndroidRadioDiscovery` and `AndroidSharedPreferencesRadioStore`.
  - Persists the master switch in `flash_radio_settings` (`gateway_enabled`).
  - Dispatches `RadioLinkService.start` / `stop` according to `RadioServicePolicy.shouldServiceRun`.
  - Handles runtime "Nearby devices" permissions hook and Bluetooth settings intent.
- **Task 3: Desktop Host Adapter (`DesktopRadioGatewayController`):**
  - New file `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radio/DesktopRadioGatewayController.kt` implementing `RadioGatewayController`.
  - Bridges `RadioGatewayState` -> `RadioGatewayUiState`.
  - Manages lazy lifecycle of `RadioGatewayManager` with `WindowsRadioDiscovery` and `DesktopPropertiesRadioStore`.
  - Persists the master switch in `~/.flash/radio.properties` (`gateway.enabled`).
  - Handles opening Windows Bluetooth Settings (`ms-settings:bluetooth`).
- **Task 4: Normal Build Wiring:**
  - Pass real controllers to `FlashSettingsScreen` in `MainActivity.kt` and `DesktopShell.kt`.
  - Reflect real `enabled` status into `FlashSettingsModel.radioGatewayEnabled`.
  - Keep fake controllers exclusively in debug demos (`RadioGatewayDemoActivity`, `DesktopRadioGatewayDemoApp`) and UI tests.
- **Task 5: Test Suite (`RADWIRE-U01..`):**
  - Unit and adapter tests covering lifecycle invariants, state mapping, failure sentences, permission handling, and mutation-checks.
- **Task 6: Owed Device Tests:**
  - Document complete end-to-end device verification scenarios for owner testing with physical VR-N76 transceivers.

### What Must Not Change
- Working directory is strictly `E:\FlashWork\R-wire` on branch `agent/R-wire`. Never touch `C:\Users\KaliOxygen\Downloads\Flash`.
- Never commit to `dev` or `main`. Do not merge, rebase, or switch branches.
- Never edit canonical log, decision, or doc files (`logs/*.md`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`, `AGENTS.md`, `docs/plans/RADIO-GATEWAY-PLAN.md`). All entries go exclusively to `logs/streams/R-wire/`.
- Never edit `RealFlashChatRepository.kt`, `:core:engine`, the connection planner, `FlashTransportType`, Room schemas, or the server.
- Shared host files (`MainActivity.kt`, `DesktopShell.kt`) get minimal, surgical hunks with zero reformatting or unrelated code movement.
- Never touch the connected physical phone, adb, the owner's running desktop app, `~/.flash`, `keystore.properties`, `.jks`, or `local.properties`.
- Rule 4 strictly enforced: NEVER emit RF or open real serial/Bluetooth ports during tests. All automated tests use in-memory links and fakes.

### Files Touched or Created
- **New Files:**
  - `app/src/main/java/com/transfer/flash/radio/AndroidRadioGatewayController.kt`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radio/DesktopRadioGatewayController.kt`
  - `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/radio/DesktopRadioGatewayControllerTest.kt`
  - `app/src/test/java/com/transfer/flash/radio/AndroidRadioGatewayControllerTest.kt`
  - `logs/streams/R-wire/progress.md`
  - `logs/streams/R-wire/handoff.md`
  - `logs/streams/R-wire/errors.md`
  - `logs/streams/R-wire/experiments.md`
  - `logs/streams/R-wire/backlog-additions.md`
  - `logs/streams/R-wire/decisions-additions.md`
- **Minimal Modifications to Shared Files:**
  - `app/src/main/java/com/transfer/flash/MainActivity.kt` (register ActivityResult launcher for Nearby devices permission, hold lazy controller, pass controller to `FlashSettingsScreen`)
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt` (hold lazy controller, pass controller to `FlashSettingsScreen`)
