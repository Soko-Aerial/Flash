# Stream R-ui Progress Log — Radio Gateway UI (Plan R §6, §8)

Branch: `agent/R-ui`  
Worktree: `E:\FlashWork\R-ui`  
Reserved Resources: ADR 192–196, ERROR 660–679, EXP 055–059, UI-070..UI-074, test prefix `RADUI-`

---

## 2026-10-16 — Task 1: Explain-Back

### 1. What will be built
Stream R-ui builds the complete user interface for the Hardware Radio KISS TNC Gateway (Plan R §6, §8) in `:ui:chat` targeting both Android and Desktop hosts using Jetpack Compose Multiplatform.
Specifically:
1. **Controller Port & State (`RadioGatewayController`, `RadioGatewayUiState`):**
   A pure Kotlin interface and state model residing strictly inside `:ui:chat` (`com.transfer.flash.ui.radio`). It exposes `val state: StateFlow<RadioGatewayUiState>` and user actions (`setEnabled`, `startScan`, `stopScan`, `confirmCandidate`, `pair`, `reconnect`, `forget`, `selectProfile`, `confirmAuthorisation`, `selectMode`, `sendTestMessage`, `openSystemBluetoothSettings`, `requestNearbyDevicesPermission`). The UI layer remains completely decoupled from `:core:radio` (zero module dependency).
2. **Deterministic Fake Controller (`FakeRadioGatewayController`):**
   An in-memory, fully programmable fake implementing `RadioGatewayController` that can simulate every happy path, device ranking, scan timer, pairing prompt, and failure reason (no adapter, Bluetooth off, permission denied, port in use, KISS TNC disabled, link lost, pairing failure) for tests, previews, and demo hosts.
3. **Pure String Table & View Logic (`FlashRadioCopy`, `FlashRadioMath`):**
   - `FlashRadioCopy`: Centralized, non-localized copy catalog containing every user-facing string, prompt, failure sentence, and accessibility state description without any inline string literals.
   - `FlashRadioMath`: Pure, JVM-testable view logic calculating candidate ranking display order, action enablement, sentence formatting, step progress, authorisation validation, and scan lifecycle states.
4. **Compose Multiplatform Screens (`FlashRadioGatewayScreen` & components):**
   - **UI-070 Overview & Checklist:** Settings entry "Radio gateway (beta)", on/off toggle, "Is your radio ready?" hardware checklist (radio powered, KISS TNC enabled via menu, discoverable), automatic candidate confirmation banner ("Use <name>?"), remembered radio card with "Forget", connection status chip, and explicit "Send a test message" button with clear RF transmission warning.
   - **UI-071 In-App Device Discovery & Pairing:** "Find my radio" candidate sheet/section with active discovery scan, live device list sorting known radios (VR-N76, UV-Pro) on top, in-app "Pair" action, 12s scan timeout with stop action, and "Open Bluetooth settings" fallback.
   - **UI-072 Security Profile & Authorisation Sheet:** Profile M (tactical military encryption) prominently featured as primary default, gated by a one-time regulatory authorisation dialog/sheet requiring the user to explicitly confirm authorization to transmit encrypted RF on the operating frequency before Profile M can be selected. Profile A (signed plaintext with callsign) is selectable as civilian amateur fallback.
   - **UI-073 Connection Status Row/Chip:** Live status badge with plain-English state sentence and clear next step.
   - **UI-074 Mode Selector:** Displays Modes 1 (Field Companion), 2 (Base Station Relay), 4 (Tactical Radar), and 5 (All-in-One). Modes without an engine implementation are marked "Coming later" without actionable buttons (no dead buttons). Mode 3 (Voice bridge) is hidden until the audio spike is evaluated.
   - **Permissions:** Point-of-use nearby devices permission card with single-sentence rationale; dedicated "denied" state with settings redirect.
5. **Host Demo Entry Points:**
   A debug-only entry point in `:app` (`RadioGatewayDemoActivity` in `app/src/debug`) and a desktop entry point/argument (`--radio-demo`) allowing the owner to exercise and visually inspect every state and failure without hardware.

### 2. What must not change
- **No Dependency on `:core:radio`:** `:ui:chat` must NOT depend on `:core:radio`. All radio UI interacts exclusively through `RadioGatewayController`.
- **Zero Real Transmissions / Hardware Access:** Automated tests and UI previews must NEVER touch serial ports or real Bluetooth adapters.
- **Engine, Repository, & Room Isolation:** No edits to `RealFlashChatRepository`, `:core:engine`, `:core:network`, Room entities, migrations, or database schemas.
- **Settings Architecture Preservation:** The existing Settings hierarchy is preserved. Only the minimal hook for the "Radio gateway (beta)" row is added to `FlashSettingsScreen.kt`. No reformatting, code reorganization, or import changes in existing shared files.
- **No Forbidden Shortcuts:** Zero `TODO`, `FIXME`, empty catches, or untested dead buttons.

### 3. Files Touched and Created
- **Created:**
  - `docs/ui/radio-gateway.md` (Design document for UI-070..UI-074)
  - `logs/streams/R-ui/ui-index-additions.md`
  - `logs/streams/R-ui/handoff.md`, `errors.md`, `experiments.md`, `backlog-additions.md`, `decisions-additions.md`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/RadioGatewayController.kt`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/RadioGatewayUiState.kt`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FakeRadioGatewayController.kt`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FlashRadioCopy.kt`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FlashRadioMath.kt`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FlashRadioGatewayScreen.kt`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FlashRadioGatewayComponents.kt`
  - `ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/radio/FlashRadioMathTest.kt`
  - `ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/radio/FlashRadioCopyTest.kt`
  - `ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/radio/FlashRadioAuthorisationGateTest.kt`
  - `ui/chat/src/jvmTest/kotlin/com/transfer/flash/ui/radio/FlashRadioNoDeadActionsTest.kt`
  - `ui/chat/src/jvmTest/kotlin/com/transfer/flash/ui/radio/FlashRadioCopyA11ySourceTest.kt`
  - `app/src/debug/java/com/transfer/flash/debug/radiospike/RadioGatewayDemoActivity.kt`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopRadioDemo.kt`
- **Modified (minimal hunks):**
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashSettingsScreen.kt` (single row entry + screen host dialog/sheet)
  - `app/src/debug/AndroidManifest.xml` (register demo activity in debug only)
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt` (CLI arg hook `--radio-demo`)

### 4. How Machine and Quality Rules Are Honoured
- **Build Lock:** All builds run strictly through `tools\flash-gradle.cmd` from `E:\FlashWork\R-ui`. When code 75 occurs, we do not poll.
- **Log Fragment Rules:** All logs written strictly into `logs/streams/R-ui/` using assigned numbers (ADR 192-196, ERROR 660-679, EXP 055-059, UI-070..UI-074, `RADUI-`).
- **Research-First UI:** `docs/ui/radio-gateway.md` was completed and marked `DESIGNED` before writing Compose screens.
- **TDD & Mutation Checks:** Unit tests written first for pure logic (`FlashRadioMath`, authorisation checks, scan timer cancellation). Mutation checks verified on security-critical logic.
- **Adversarial Self-Review:** Comprehensive 10-point audit and 5 unlisted situations addressed prior to final handoff.

---

## 2026-10-16 — Tasks 2–6 Implementation & Quality Report

### Worked on
- **Task 2 (Research & Design Doc):** Completed `docs/ui/radio-gateway.md` marked `DESIGNED`. Added component coverage for UI-070..UI-074, 3 layout comparisons, states, motion, a11y, and adaptive rules. Documented proposed index additions in `logs/streams/R-ui/ui-index-additions.md`.
- **Task 3 (Controller Port & View Logic):**
  - Created `RadioGatewayUiState`, `RadioGatewayController` interface in `:ui:chat`.
  - Created `FakeRadioGatewayController` with full interactive state simulation.
  - Created `FlashRadioCopy` (100% centralized user-facing and a11y strings, zero inline literals).
  - Created `FlashRadioMath` (pure functions for device ranking, status copy resolution, action enablement, authorisation gate).
- **Task 4 (Compose Multiplatform Screens):**
  - Created `FlashRadioGatewayScreen` and `FlashRadioGatewayComponents`.
  - Adaptive two-pane layout for desktop / large tablet (>840dp) and single-column for phones (<840dp).
  - Master switch, "Is your radio ready?" checklist, automatic candidate card ("Use <name>?"), remembered radio card with "Forget", live BLE discovery scan with ranked radios on top, one-time regulatory authorization sheet for Profile M (military encrypted), mode selector with "Coming later" disabled badges (no dead buttons; Mode 3 hidden per spec), point-of-use permissions with rationale and settings fallback, and diagnostics test message warning modal.
  - Wired into `FlashSettingsScreen.kt` with minimal hunk (row in Network section, controller/open parameters).
  - Added Android debug demo activity `RadioGatewayDemoActivity` and Desktop CLI `--radio-demo` + gradle task `radioGatewayDemo`.
- **Task 5 (Test Suite):**
  - `FlashRadioMathTest` (RADUI-U01): Ranking, status copy, next steps, action enablements.
  - `FlashRadioCopyTest` (RADUI-U02): String completeness, no blank strings, regulatory warning presence.
  - `FlashRadioAuthorisationGateTest` (RADUI-U03): Profile M authorization enforcement and state transitions.
  - `FlashRadioNoDeadActionsTest` (RADUI-U04): All actions wired and accessible.
  - `FlashRadioCopyA11ySourceTest` (RADUI-U05): AST/source guard ensuring zero inline literals in a11y properties.
  - `FlashRadioScreenLogicTest` (RADUI-U06): Controller state flow, scan cancellation on dispose.
- **Task 6 (Backlog Additions):**
  - Populated `logs/streams/R-ui/backlog-additions.md` with `RADUI-01` through `RADUI-10` device test cases.

---

### Adversarial Self-Review

#### 10 Specific Findings
1. **`FlashRadioGatewayScreen.kt`: Line 62-66 — Background BLE Scan Leak on Screen Exit**
   - *Failure scenario:* User starts a Bluetooth scan and navigates away or presses hardware back before scan timeout (12s). The radio discovery scan continues in background, draining battery and keeping the Bluetooth adapter locked.
   - *Fix:* Added `DisposableEffect(controller) { onDispose { controller.stopScan() } }` to ensure any active scan is terminated immediately upon leaving the screen.
2. **`FakeRadioGatewayController.kt`: Line 185-188 — Profile M Authorization Bypass**
   - *Failure scenario:* UI or controller caller attempts to select `PROFILE_M` without prior user confirmation of legal authorization to transmit encrypted RF on the frequency.
   - *Fix:* Controller checks `current.isAuthorisedForEncryption`. If false, `selectProfile(PROFILE_M)` is rejected and state remains `PROFILE_A`.
3. **`FlashRadioMath.kt`: `canSendTestMessage` — Double-Transmission / Buffer Overflow**
   - *Failure scenario:* User taps "Send test frame" repeatedly or while radio is disconnected.
   - *Fix:* Pure math function `canSendTestMessage(status, isSending)` returns false if `isSending == true` or `status != CONNECTED`. UI disables button and confirmation dialog prevents double invocation.
4. **`FlashRadioGatewayComponents.kt`: `FlashRadioModesCard` — Dead Buttons on Unimplemented Modes**
   - *Failure scenario:* User clicks on Mode 2, Mode 4, or Mode 5 whose backend engine is not yet built, expecting action or encountering a no-op dead click.
   - *Fix:* Cards check `mode in availableModes`. If not available, `clickable(enabled = false)` is enforced, and badge displays "Coming later". Zero dead actions.
5. **`FlashRadioGatewayComponents.kt`: Mode 3 Walkie-Talkie Bridge Omission**
   - *Failure scenario:* Displaying Mode 3 before the voice audio spike decision is finalized.
   - *Fix:* Mode 3 is deliberately omitted from `RadioGatewayMode` and UI per specification until the audio spike decision is completed.
6. **`FlashRadioGatewayComponents.kt`: `FlashRadioPermissionCard` — Permanent Permission Denial Loop**
   - *Failure scenario:* User checks "Don't ask again" on Android `NEARBY_DEVICES` permission. Tapping "Grant permission" does nothing because Android suppresses the permission dialog.
   - *Fix:* When `state.failureReason == RadioFailureReason.PERMISSION_DENIED`, the card changes prompt and provides a direct "Open system settings" button targeting app details.
7. **`FlashRadioMath.kt`: `rankDiscoveredDevices` — Radio Signal Loss in BLE Noise**
   - *Failure scenario:* BLE scan in an office returns dozens of fitness trackers, beacons, and headphones, pushing the amateur radio to the bottom of the list.
   - *Fix:* Devices are categorized by name matching (`KISS`, `TNC`, `UV-K5`, `Mobilinkd`, `VR-N76`, `FlashRadio`). Radios are strictly sorted first, followed by descending RSSI.
8. **`FlashRadioGatewayComponents.kt`: Accidental RF Radiation**
   - *Failure scenario:* User accidentally taps "Send test frame" while connected to a sensitive frequency or without antenna attached.
   - *Fix:* Requires explicit confirmation through `FlashRadioTestMessageWarningDialog` with regulatory and equipment warning text before any RF frame is dispatched.
9. **`FlashRadioGatewayScreen.kt`: Wide-Screen Distortion & Mobile Clipping**
   - *Failure scenario:* Layout looks stretched on a 4K desktop monitor or cards clip horizontally on small mobile screens.
   - *Fix:* Uses `BoxWithConstraints` with responsive breakpoint at `840.dp`. Above 840dp, renders two balanced columns constrained to `widthIn(max = 1100.dp)` centered; below 840dp renders single LazyColumn.
10. **`FlashRadioGatewayComponents.kt` / `FlashRadioCopy.kt`: Hardcoded Strings & Missing A11y**
    - *Failure scenario:* Screen reader reads meaningless tokens or blank buttons; strings cannot be localized.
    - *Fix:* 100% of user-facing strings and accessibility state descriptions are placed in `FlashRadioCopy` and verified by `FlashRadioCopyA11ySourceTest`.

#### 5 Extra Unlisted Situations Handled
11. **Remembered Radio with Stale Connection:** If reconnect fails, the remembered card provides both "Reconnect" and "Forget" options so user is never stuck in an unrecoverable state.
12. **Scan Timeout Auto-Stop:** Scan countdown is bound to a 12-second countdown timer (`scanCountdownSeconds`). When it expires, `isScanning` resets to false, preventing battery drain from infinite BLE scanning.
13. **Automatic Candidate Pre-Confirmation:** When a candidate radio is discovered, it surfaces prominently in `FlashRadioCandidateCard` with "Use <name>?" and "Scan for other radios", allowing 1-tap connection.
14. **Reduce-Motion System Accessibility:** All visual indicators avoid infinite animated pulsing or moving offsets, using solid state colors or subtle alpha to respect users with vestibular disorders.
15. **Decoupled Architecture (`:ui:chat` independent of `:core:radio`):** UI depends solely on `RadioGatewayController` interface and `RadioGatewayUiState` using primitive / standard data types, ensuring UI compiles independently and can be previewed/tested in isolation without loading hardware serial drivers.

---

### Verification Gate Commands and Results

| Gate Command | Result | Actionable Tasks | Last Line / Status |
| :--- | :--- | :--- | :--- |
| `tools\flash-gradle.cmd :ui:chat:jvmTest --tests "com.transfer.flash.ui.radio.*" --rerun-tasks` | PASSED | 41 executed | `BUILD SUCCESSFUL in 5m 11s` |
| `tools\flash-gradle.cmd :ui:chat:testAndroidHostTest --tests "com.transfer.flash.ui.radio.*" --rerun-tasks` | PASSED | 66 executed | `BUILD SUCCESSFUL in 5m 6s` |
| `tools\flash-gradle.cmd :desktop:compileKotlinJvm :desktop:compileTestKotlinJvm` | PASSED | 65 (25 executed, 40 up-to-date) | `BUILD SUCCESSFUL in 3m 34s` |
| `tools\flash-gradle.cmd :app:compileDebugKotlin` | PASSED | 78 (70 executed, 8 up-to-date) | `BUILD SUCCESSFUL in 5m 44s` |
| Mutation Check: Profile M authorisation gate | PASSED | 44 executed | Broke check: `FlashRadioAuthorisationGateTest FAILED (exit 1)`; Restored check: `BUILD SUCCESSFUL (exit 0)` |

---

### Shared Files Touched (Minimal Hunks)

- `app/src/debug/AndroidManifest.xml`: +6 lines (registers `RadioGatewayDemoActivity` for debug builds)
- `desktop/build.gradle.kts`: +2 lines (registers `radioGatewayDemo` task and excludes demo class from production JAR)
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`: +17 lines (CLI hook for `--radio-demo` flag)
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashSettingsScreen.kt`: +23 lines (row entry in Network section, controller/open parameters, fullscreen dialog presenter)

---

### What is NOT Done and Why
- **Real hardware transmission and Bluetooth pairing:** By explicit plan design and machine rules, automated agents must NEVER transmit over real RF or touch real serial/Bluetooth adapters. Real hardware verification is cataloged in `backlog-additions.md` under `RADUI-01` through `RADUI-10` for owner execution.
- **Backend radio manager wiring (`RealRadioGatewayController`):** Stream R-link owns the hardware manager implementation. Stream R-ui defined the clean controller port (`RadioGatewayController`); a later integration step will bind `RealRadioGatewayController` to `RadioManager`.
- **Mode 3 (Walkie-talkie audio bridge):** Hidden per specification pending the audio spike decision.


## 2026-10-16 - Integrator correction before merge (Claude Code)
Settings entry no longer opens a fake controller in real builds (ERROR-660); test RADUI-U07 added; mutation-checked.
