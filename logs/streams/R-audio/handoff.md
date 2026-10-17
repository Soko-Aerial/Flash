# Current Handoff — Stream R-audio

## Current branch
`agent/R-audio` (worktree `E:\FlashWork\R-audio`)

## Last verified build
`881092bc`

## Current phase
Phase R4 — Hardware Radio Audio Bridge Spike BT-R10 (Plan R, `docs/plans/RADIO-GATEWAY-PLAN.md` §9 and §10)

## Working features
- **Windows Radio Audio Endpoint Probe (`:desktop:radioAudioProbe`):**
  - Read-only enumeration of JDK Sound mixers (`javax.sound.sampled`) and Windows Core Audio endpoints (`MMDevices` Render/Capture and `BTHHFENUM` registry) via JNA `Advapi32Util`.
  - Accurately identifies `VR-N76 Hands-Free HF Audio` and classifies its state as `DEVICE_STATE_UNPLUGGED` (0x8), empirically proving why the Windows Bluetooth audio connection failed in `EXP-023` when no telephony call was active.
  - JSON machine-readable export (`--json`, `--out report-audio.json`) and human-readable console tables with all Bluetooth addresses masked to the last 3 bytes (`**:**:**:01:35:B5`).
  - STRICT SAFETY: Zero playback lines opened, zero mic audio captured, zero PTT keyed.
  - Excluded completely from distribution desktop jar (`tasks.named<Jar>("jvmJar")`).
- **Android Diagnostic Screen (`RadioAudioActivity` in `app/src/debug`):**
  - Permission requests strictly at point of use (`BLUETOOTH_CONNECT`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`).
  - Read-only status monitoring of `BluetoothHeadset` proxy and system `AudioDeviceInfo` devices.
  - Safe, explicit owner action button to test routing to `AudioDeviceInfo.TYPE_BLUETOOTH_SCO` via `AudioManager.setCommunicationDevice` (on Android 12+) or `startBluetoothSco` (on legacy Android).
  - Clear Route button and automatic lifecycle cleanup in `onStop()` / `onDestroy()` restoring `MODE_NORMAL`.
  - Zero mic recording, zero tone playback, zero RF keying.
  - Excluded completely from release APK (verified via `:app:compileReleaseKotlin`).
- **Owner Runbook (`runbook-BT-R10.md`):**
  - Numbered manual test procedure with regulatory cautions, Windows and Android steps, turn-taking evaluation, results table for Questions 1..4, and owner decision template.
- **Architectural Proposals & Platform Notes:**
  - `ADR-210` (PROPOSED): Recommends **DROPPING** Modes 3 and 5 from Plan R due to HFP role asymmetry, absence of software PTT, VOX syllabic clipping, narrowband 8 kHz CVSD voice quality, unresolvable simplex RF collisions, and compromise of Profile M encryption.
  - `ADR-211` (PROPOSED): Minimal architecture and half-duplex arbitration model if Modes 3 & 5 are rescoped or built.
  - `ADR-212` (PROPOSED): Standardized Windows Core Audio and Android SCO routing abstractions for radio peripherals.
  - `ERROR-730`: Diagnoses Windows Core Audio idle HFP "Unplugged" state.
  - `EXP-070`: Empirical registry and endpoint topology for VR-N76 on Windows 11.
  - `platform-notes-additions.md`: Bluetooth HFP roles, Android 12+ communication device APIs, Windows WASAPI endpoint model, and VR-N76 manufacturer facts.

## Verification & Gate Commands Run
1. `:desktop:jvmTest --tests com.transfer.flash.desktop.radioaudio.RadioAudioProbeTest --rerun-tasks`
   - Result: BUILD SUCCESSFUL (9/9 tests passed).
   - Last line: `[flash-gradle] finished with exit code 0 after 352s of running`
2. `:desktop:radioAudioProbe -PradioArgs="--json --out report-audio.json"`
   - Result: BUILD SUCCESSFUL (Generated `report-audio.json` mapping `VR-N76 Hands-Free HF Audio` in state `UNPLUGGED` 0x8 from host registry).
   - Last line: `[flash-gradle] finished with exit code 0 after 56s of running`
3. `:app:testDebugUnitTest --tests com.transfer.flash.debug.radioaudio.AndroidAudioRoutingProbeTest --rerun-tasks`
   - Result: BUILD SUCCESSFUL (6/6 tests passed).
   - Last line: `[flash-gradle] finished with exit code 0 after 404s of running`
4. `:app:compileReleaseKotlin`
   - Result: BUILD SUCCESSFUL.
   - Last line: `[flash-gradle] finished with exit code 0 after 157s of running`
   - Verified that `RadioAudioActivity` is completely omitted from all release class outputs.

## Shared Files Touched & Hunk Sizes
1. `desktop/build.gradle.kts`: +1 line registering `radioAudioProbe` task, 1 line edit adding exclude for `com/transfer/flash/desktop/radioaudio/*` to `jvmJar`.
2. `app/src/debug/AndroidManifest.xml`: +6 lines registering `RadioAudioActivity`.
*Zero files in `core/network`, `core/ptt`, `core/engine`, or canonical logs were touched.*

## What is NOT Done and Why
- **Automatic Audio Playback / RF Transmission:** Deliberately NOT implemented in software to protect radio hardware and adhere to telecommunications regulations. Transmit tests are manual owner steps in `runbook-BT-R10.md`.
- **Production Audio Bridge Feature Code:** No feature code written; this spike concludes with the architectural recommendation to DROP Modes 3 and 5.
- **Physical Device Verification:** Awaiting owner manual run of `runbook-BT-R10.md` on physical hardware (owed tests recorded in `backlog-additions.md`).

## Recommended Next Task
The owner runs `logs/streams/R-audio/runbook-BT-R10.md` with the physical VR-N76 to empirically confirm the four questions, decides on ADR-210 (recommendation: DROP Modes 3 and 5), and updates `TEST-BACKLOG.md` with the findings.

## Files Most Relevant to Next Task
- `logs/streams/R-audio/runbook-BT-R10.md`
- `logs/streams/R-audio/decisions-additions.md`
- `logs/streams/R-audio/platform-notes-additions.md`
- `logs/streams/R-audio/backlog-additions.md`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/RadioAudioProbe.kt`
- `app/src/debug/java/com/transfer/flash/debug/radioaudio/RadioAudioActivity.kt`
