# Stream R-audio Progress Log

## Task 1: Explain-Back (2026-10-17)

### Role and Scope
I am stream **R-audio**, operating strictly within `E:\FlashWork\R-audio` on branch `agent/R-audio`. This stream is an investigative and diagnostic spike corresponding to Phase R4 (`BT-R10`) of `docs/plans/RADIO-GATEWAY-PLAN.md` (Revision 3) and ADR-173. My sole objective is to research, build diagnostic exploration tools, draft an empirical runbook for the repository owner, answer the four core questions regarding the feasibility of Bluetooth audio bridging with the Vero Telecom VR-N76 / BTECH UV-Pro handheld radio, and submit Architecture Decision Record (ADR) recommendations for Modes 3 (Walkie-Talkie Bridge) and 5 (All-in-One Dual Mode). **I build no production feature, commit no release-bound audio code, and alter no existing network or chat engine behavior.**

### The Four Questions of Plan R §9 (BT-R10)
1. **Bluetooth Hands-Free Profile (HFP) Connection & Roles:** Does the phone or PC establish a working bidirectional Bluetooth Hands-Free audio connection to the radio, and in which role (Audio Gateway [AG] vs. Hands-Free unit [HF])? If the radio operates as an AG (expecting a wireless headset), a phone or PC (also acting as an AG) cannot natively interoperate without role conflict. Furthermore, on Windows, why did audio connection fail during the `BT-00` spike (`EXP-023`) when no active telephony call was present?
2. **Transmitter RF Keying Mechanism:** How can the transceiver be keyed into transmit (TX) from Flash? Is it triggered by radio-side voice activation (VOX) on Bluetooth audio input, an in-band or AT command, a proprietary Bluetooth LE GATT command (as used by the BS-PTT button), or is software keying impossible via standard HFP, requiring manual hardware PTT?
3. **Voice Quality and Inbound Audio Routing:** What is the real audio quality of the narrow Bluetooth voice channel (8 kHz CVSD / 16 kHz mSBC wideband speech), and does incoming analog RF audio received by the radio's discriminator actually route back to the phone/PC capture endpoint over the SCO/eSCO link?
4. **Turn-Taking and Floor Arbitration:** How does unstructured, uncoordinated analog simplex radio traffic (where any third-party station can key up unannounced) interact with Flash's strict, distributed, cryptographic half-duplex Push-to-Talk (PTT) floor control (ADR-032, `FlashPtt`, `PttFloorMachine`)?

### What Must Not Change
- **No Production Code Alterations:** Zero edits to `:core:engine`, `RealFlashChatRepository`, `:core:network`, `:core:ptt`, Room persistence schemas, or the connection planner.
- **Strictly No Automatic RF Transmission or Audio Emission:** The tools must NEVER play tones, transmit audio into the radio, send KISS data frames, or key PTT. Any action capable of causing physical RF transmission is strictly relegated to an owner runbook with explicit legal frequency precautions.
- **Isolation of Diagnostic Tools:** Windows diagnostic tools are registered as standalone Gradle tasks excluded from distribution jars (`jvmJar`). Android diagnostic tools are placed strictly under `app/src/debug/` so they are completely omitted from release APKs.
- **Preservation of Repository State:** No editing of canonical logs (`logs/progress.md`, `logs/handoff.md`, `logs/errors.md`, `logs/experiments.md`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`, `AGENTS.md`) or files belonging to other parallel streams. All outputs remain inside `logs/streams/R-audio/` with reserved numbers (ADR 210–214, ERROR 730–749, EXP 070–074, test prefix `RADAUD-`).

---

## Task 2: Official Documentation & Platform Research (2026-10-17)

Comprehensive platform and hardware facts were researched and recorded in `logs/streams/R-audio/platform-notes-additions.md`:
1. **Bluetooth HFP Roles:** Bluetooth SIG HFP v1.8 defines strictly one Audio Gateway (AG) and one Hands-Free (HF) unit. Smartphones and Windows PCs operate as AGs; if the VR-N76 operates as an AG (to talk to headsets), it cannot establish an HFP link to another AG.
2. **Android Audio Routing APIs:** Android 12+ (API 31+) mandates `AudioManager.setCommunicationDevice(device)` where `device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO` with `MODE_IN_COMMUNICATION`. Replaces deprecated `startBluetoothSco()`. Standard Android builds support only the AG role.
3. **Windows Audio Endpoint Model & EXP-023 Diagnosis:** Windows Core Audio (`MMDevices` and `BthHfenum.sys`) holds Hands-Free endpoints in state `DEVICE_STATE_UNPLUGGED` (0x8) while idle. Windows strictly refuses idle audio connection and only establishes the physical SCO bearer when an application opens a WASAPI communications stream (`eCommunications`).
4. **VR-N76 / UV-Pro Manufacturer Material:** App-defined radio architecture with Bluetooth SPP/BLE for programming/TNC and HFP for external accessories (BHM-88 speaker-mic, BS-PTT ring button). Firmware v0.8.0+ added "Bluetooth VOX". No standard Bluetooth HFP PTT command exists.

---

## Task 3: Diagnostic Tools Implementation (2026-10-17)

### Windows Audio Endpoint Probe (`:desktop:radioAudioProbe`)
- **Package:** `com.transfer.flash.desktop.radioaudio`
- **Classes Added:**
  - `AudioModels.kt`: Data models for audio endpoints, mixers, states, classifications, and probe report.
  - `AudioEndpointClassifier.kt`: Pure-Kotlin classifier distinguishing `RADIO_HANDS_FREE`, `BLUETOOTH_HANDS_FREE`, `BLUETOOTH_STEREO`, and `SYSTEM_AUDIO`.
  - `WindowsAudioEndpointEnumerator.kt`: Enumerates JDK Sound mixers (`javax.sound.sampled`) and Windows Core Audio endpoints (`MMDevices` and `BTHHFENUM`) via JNA `Advapi32Util`.
  - `RadioAudioProbe.kt`: CLI runner producing human-readable and JSON diagnostic reports (`--json`, `--out`).
- **Safety Contract:** Strictly read-only; zero playback lines opened, zero mic audio captured, zero PTT keyed.
- **Distribution Isolation:** Excluded from desktop jar via `tasks.named<Jar>("jvmJar") { exclude("com/transfer/flash/desktop/radioaudio/*") }`.

### Android Audio Routing Probe (`RadioAudioActivity`)
- **Package:** `com.transfer.flash.debug.radioaudio`
- **Location:** Strictly in `app/src/debug/` (omitted from release APKs, verified by `:app:compileReleaseKotlin`).
- **Features:**
  - Runtime permissions check at point of use (`BLUETOOTH_CONNECT`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`).
  - Read-only enumeration of `BluetoothHeadset` proxy state and system `AudioDeviceInfo` outputs.
  - Safe, explicit owner button: "Route SCO" initiates `setCommunicationDevice` (API 31+) or `startBluetoothSco` (legacy).
  - "Clear Route" cleanly restores `MODE_NORMAL`.
  - Mandatory lifecycle cleanup: `onStop()` / `onDestroy()` resets audio route to normal and unregisters receivers.
  - Zero mic recording, zero tone playback, zero RF keying.

---

## Task 4: Owner Runbook (`runbook-BT-R10.md`) (2026-10-17)

Documented in `logs/streams/R-audio/runbook-BT-R10.md`:
- Safety and regulatory precautions (dummy load, legal frequency, no Profile M on amateur bands).
- Numbered manual steps for Windows (Part A, Steps A1–A4) and Android (Part B, Steps B1–B6).
- Part C turn-taking evaluation (RF doubling, squelch tail, turn-around delay).
- Results table for Questions 1..4.
- Decision template for the owner (Option 1 Build, Option 2 Rescope, Option 3 Drop).

---

## Task 5: Architecture Decision Records & Tests (2026-10-17)

Documented in `logs/streams/R-audio/decisions-additions.md`:
- **ADR-210 (PROPOSED):** Recommendation to DROP Modes 3 and 5 from Plan R due to HFP role asymmetry, absence of software PTT, VOX syllabic clipping, narrowband 8 kHz CVSD voice quality, unresolvable simplex RF collisions, and compromise of Profile M encryption.
- **ADR-211 (PROPOSED):** Minimal architecture and half-duplex arbitration model if Modes 3 & 5 are rescoped or built (seam in `:core:radio.audio`, preemption rules, unencrypted badge).
- **ADR-212 (PROPOSED):** Standardized Windows Core Audio and Android SCO routing abstractions for radio peripherals.

---

## Evidence Table (HUB-QUALITY-CONTRACT §4)

| Step | Files Changed (`path:line`) | Tests (Full Names) | Mutation / Validation Check | Gate Commands & Result |
|---|---|---|---|---|
| Task 1 | `logs/streams/R-audio/progress.md:1` | N/A (Doc) | N/A | Written before code |
| Task 2 | `logs/streams/R-audio/platform-notes-additions.md:1` | N/A (Doc) | Fact-checked with official docs & EXP-023 | N/A |
| Task 3a Desktop | `desktop/.../radioaudio/AudioModels.kt:1`<br>`desktop/.../radioaudio/AudioEndpointClassifier.kt:1`<br>`desktop/.../radioaudio/WindowsAudioEndpointEnumerator.kt:1`<br>`desktop/.../radioaudio/RadioAudioProbe.kt:1` | `com.transfer.flash.desktop.radioaudio.RadioAudioProbeTest`: `testRADAUD_U01_classifyKnownRadioEndpoints`<br>`testRADAUD_U02_classifyGenericBluetoothHandsFreeEndpoints`<br>`testRADAUD_U03_classifyBluetoothStereoEndpoints`<br>`testRADAUD_U04_classifySystemAudioEndpoints`<br>`testRADAUD_U05_connectabilityEvaluationBasedOnState`<br>`testRADAUD_U06_generateReportWithRadioFoundAndActive`<br>`testRADAUD_U07_generateReportWithRadioUnpluggedTelephonyRestriction`<br>`testRADAUD_U08_generateReportWithoutRadio`<br>`testRADAUD_U09_runnerExecutesReadOnlyAndProducesOutputs` | Mutated classifier to ignore "VR-N76" -> `testRADAUD_U01` failed; restored.<br>Mutated state 0x8 connectable to true -> `testRADAUD_U05` failed; restored. | `tools\flash-gradle.cmd :desktop:jvmTest --tests com.transfer.flash.desktop.radioaudio.RadioAudioProbeTest --rerun-tasks`<br>Result: **BUILD SUCCESSFUL (9/9 tests passed)** |
| Task 3b Empirical Desktop Probe | `desktop/build.gradle.kts:273,282` | `tools\flash-gradle.cmd :desktop:radioAudioProbe -PradioArgs="--json --out report-audio.json"` | Read-only execution against host machine Windows Registry: successfully discovered `VR-N76 Hands-Free HF Audio` in `BTHHFENUM` in state `UNPLUGGED` (0x8). | Result: **BUILD SUCCESSFUL (Wrote report-audio.json in 55s)** |
| Task 3c Android | `app/src/debug/.../AndroidAudioRoutingProbe.kt:1`<br>`app/src/debug/.../RadioAudioActivity.kt:1`<br>`app/src/debug/AndroidManifest.xml:33`<br>`app/src/test/.../AndroidAudioRoutingProbeTest.kt:1` | `com.transfer.flash.debug.radioaudio.AndroidAudioRoutingProbeTest`: `testRADAUD_U10_resolveDeviceTypeNames`<br>`testRADAUD_U11_findBluetoothScoDeviceInList`<br>`testRADAUD_U12_evaluateScoPreconditionsMissingPermissions`<br>`testRADAUD_U13_evaluateScoPreconditionsMissingDevice`<br>`testRADAUD_U14_evaluateScoPreconditionsSuccess`<br>`testRADAUD_U15_mapHeadsetConnectionStates` | Mutated `findBluetoothScoDevice` to return null -> `testRADAUD_U11` failed; restored.<br>Mutated permission logic -> `testRADAUD_U12` failed; restored. | `tools\flash-gradle.cmd :app:testDebugUnitTest --tests com.transfer.flash.debug.radioaudio.AndroidAudioRoutingProbeTest --rerun-tasks`<br>Result: **BUILD SUCCESSFUL (6/6 tests passed)** |
| Task 3d Release Isolation | `app/src/debug/AndroidManifest.xml`<br>`desktop/build.gradle.kts` | Compilation of release variant: `tools\flash-gradle.cmd :app:compileReleaseKotlin` | Inspected `app/build/intermediates/`: `RadioAudioActivity` is completely absent from all release class directories. | Result: **BUILD SUCCESSFUL (157s)** |
| Task 4 | `logs/streams/R-audio/runbook-BT-R10.md:1` | N/A (Doc) | Numbered owner runbook with legal cautions | Complete |
| Task 5 | `logs/streams/R-audio/decisions-additions.md:1`<br>`logs/streams/R-audio/backlog-additions.md:1`<br>`logs/streams/R-audio/errors.md:1`<br>`logs/streams/R-audio/experiments.md:1` | N/A (Docs) | Proposes ADR-210, 211, 212; adds ERROR-730, EXP-070, `BT-R10`, `RADAUD-01`..`04` | Complete |

---

## Adversarial Self-Review (10 Findings & 5 Extra Situations)

### Ten Specific Findings
1. **`desktop/.../WindowsAudioEndpointEnumerator.kt:83`**:
   - *Failure Scenario:* On Windows machines where Bluetooth is disabled or registry keys under `HKLM\...\MMDevices\Audio\Render` are corrupted or restricted by group policy, `Advapi32Util.registryKeyExists` could throw a SecurityException.
   - *Fix:* Wrapped registry enumeration in defensive try-catch with automatic fallback to `fallbackJdkToEndpoints()`, ensuring the tool still reports JDK mixer audio devices instead of crashing.
2. **`desktop/.../WindowsAudioEndpointEnumerator.kt:158`**:
   - *Failure Scenario:* In Windows 11, the `FriendlyName` property in `BTHHFENUM` contains raw INF resource references like `@System32\drivers\bthhfenum.sys,#4;%1 Hands-Free HF Audio%0\n;(VR-N76)`.
   - *Fix:* Implemented parsing logic extracting the radio model from `;(` / `)` to present clean, readable names (`VR-N76 Hands-Free HF Audio`).
3. **`desktop/.../AudioEndpointClassifier.kt:25`**:
   - *Failure Scenario:* Device names in Windows may have mixed casing or trailing COM port suffixes (e.g. `vr-n76 hands-free (COM16)`).
   - *Fix:* Normalized strings to uppercase and integrated `KnownRadioNames.isKnownRadio()` and `KNOWN_PATTERNS`.
4. **`app/.../RadioAudioActivity.kt:138`**:
   - *Failure Scenario:* If a user opens `RadioAudioActivity`, taps "Route SCO", and navigates away or receives a phone call, `AudioManager` could remain stuck in `MODE_IN_COMMUNICATION` with SCO active, breaking ringtones and normal phone speakerphone.
   - *Fix:* Implemented mandatory cleanup in `onStop()` and `onDestroy()` calling `clearAudioRoute()`, resetting `audioManager.mode = AudioManager.MODE_NORMAL`.
5. **`app/.../RadioAudioActivity.kt:195`**:
   - *Failure Scenario:* On Android 12+ (API 31+), calling `setCommunicationDevice()` without `BLUETOOTH_CONNECT` permission throws a fatal `SecurityException`.
   - *Fix:* Guarded with runtime permission validation and API level branching (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`), preventing crashes and displaying user-facing error banners.
6. **`app/.../RadioAudioActivity.kt:202`**:
   - *Failure Scenario:* `audioManager.getDevices(GET_DEVICES_OUTPUTS)` returns audio devices, but no Bluetooth SCO device is currently connected. Calling `setCommunicationDevice(null)` would throw an exception.
   - *Fix:* Safely queries `firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }` and sets a clear error message `"No AudioDeviceInfo.TYPE_BLUETOOTH_SCO found"` without attempting to set a null device.
7. **`desktop/.../RadioAudioProbe.kt:140`**:
   - *Failure Scenario:* Emitting JSON with `--json` could fail with invalid JSON syntax if device names contain unescaped backslashes or quotation marks.
   - *Fix:* Added `escapeJson(s: String)` escaping backslashes, quotes, and newlines.
8. **`desktop/build.gradle.kts:281`**:
   - *Failure Scenario:* If diagnostic classes `com.transfer.flash.desktop.radioaudio.*` were packaged into the distribution desktop jar, release builds would ship diagnostic tools.
   - *Fix:* Excluded `"com/transfer/flash/desktop/radioaudio/*"` in `tasks.named<Jar>("jvmJar")`.
9. **`app/src/debug/AndroidManifest.xml:33`**:
   - *Failure Scenario:* If `RadioAudioActivity` were placed in `app/src/main/`, release APKs would bundle a debug diagnostic activity.
   - *Fix:* Registered strictly in `app/src/debug/AndroidManifest.xml` and validated via `:app:compileReleaseKotlin`.
10. **`app/.../RadioAudioActivity.kt:145`**:
    - *Failure Scenario:* Failing to close `BluetoothHeadset` profile proxy or unregister broadcast receiver causes Android `ServiceConnection` memory leaks.
    - *Fix:* Explicitly called `bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)` and `unregisterReceiver(scoStateReceiver)` in `onDestroy()`.

### Five Extra Situations Handled
1. **Multi-Endpoint Bluetooth Devices:** Recognizes dual-endpoint Bluetooth devices where a device exposes both A2DP Stereo (high-quality playback) and HFP Hands-Free (narrowband communications), classifying them into `BLUETOOTH_STEREO` vs `RADIO_HANDS_FREE`.
2. **Windows 10/11 On-Demand Telephony SCO Idle State:** Identifies `UNPLUGGED` (0x8) state as normal Windows HFP behavior rather than an error or missing device, directly solving the riddle of `EXP-023`.
3. **Asynchronous Headset Proxy Connection on Android:** `getProfileProxy` is asynchronous; the UI gracefully handles the unready state and updates via Compose state when `onServiceConnected` fires.
4. **SCO State Broadcast Updates:** Handles legacy `ACTION_SCO_AUDIO_STATE_UPDATED` transitions (`CONNECTING`, `CONNECTED`, `DISCONNECTED`, `ERROR`) and displays them live on screen.
5. **MAC Address Privacy Masking:** Ensures Bluetooth MAC addresses are truncated to the last 3 bytes (`maskBluetoothAddress`) in all console, text, and JSON outputs.

---

## Gate Commands Run & Last Lines

1. **Desktop Unit Tests:**
   - `tools\flash-gradle.cmd :desktop:jvmTest --tests com.transfer.flash.desktop.radioaudio.RadioAudioProbeTest --rerun-tasks`
   - *Last line:* `[flash-gradle] finished with exit code 0 after 352s of running` (BUILD SUCCESSFUL, 9/9 tests passed).
2. **Desktop Tool Execution:**
   - `tools\flash-gradle.cmd :desktop:radioAudioProbe -PradioArgs="--json --out report-audio.json"`
   - *Last line:* `[flash-gradle] finished with exit code 0 after 56s of running` (BUILD SUCCESSFUL, `report-audio.json` written).
3. **Android Unit Tests:**
   - `tools\flash-gradle.cmd :app:testDebugUnitTest --tests com.transfer.flash.debug.radioaudio.AndroidAudioRoutingProbeTest --rerun-tasks`
   - *Last line:* `[flash-gradle] finished with exit code 0 after 404s of running` (BUILD SUCCESSFUL, 6/6 tests passed).
4. **Android Release Variant Compilation:**
   - `tools\flash-gradle.cmd :app:compileReleaseKotlin`
   - *Last line:* `[flash-gradle] finished with exit code 0 after 157s of running` (BUILD SUCCESSFUL, verified zero debug classes in release output).

---

## Shared Files Touched & Hunk Sizes

1. `desktop/build.gradle.kts`:
   - Hunk size: +1 line (registers `radioAudioProbe` task), 1 line edit (adds exclude `"com/transfer/flash/desktop/radioaudio/*"` to `jvmJar`).
2. `app/src/debug/AndroidManifest.xml`:
   - Hunk size: +6 lines (registers `RadioAudioActivity`).

*Zero files in `core/network`, `core/ptt`, `core/engine`, or canonical logs were touched.*

---

## What is NOT Done and Why

1. **Live Radio Transmission / Tone Emission:** Deliberately NOT performed by software tools to uphold non-negotiable safety rules (THE RADIO TRANSMITS) and legal operating requirements. All transmitter testing is relegated to the owner runbook `runbook-BT-R10.md`.
2. **Production Audio Bridge Feature Code:** No feature code was written. This phase is strictly a feasibility spike (`BT-R10`) to answer the four questions of Plan R §9. The architectural recommendation in ADR-210 is to **DROP** Modes 3 and 5.
3. **Physical Device Verification:** Awaiting owner manual run of `runbook-BT-R10.md` on physical hardware (owed tests recorded in `backlog-additions.md`).
