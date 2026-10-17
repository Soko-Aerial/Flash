# Error Log — Stream R-audio

Reserved error range: ERROR-730 through ERROR-749.

---

## ERROR-730 — Windows Core Audio Bluetooth Hands-Free Endpoint Idle State "Unplugged" (0x8)

### Date
2026-10-17

### Area
Desktop Audio / Windows Core Audio (WASAPI) / Bluetooth HFP

### Symptoms
During `BT-00` hardware spike (`EXP-023`) and initial Windows audio probe runs, the VR-N76 was recognized in Windows Bluetooth settings as an "Audio Device", but clicking "Connect" in the audio UI failed. In Windows Core Audio (`MMDevices`), the endpoint state is flagged as `DEVICE_STATE_UNPLUGGED` (`0x00000008`) rather than `DEVICE_STATE_ACTIVE` (`0x00000001`).

### Environment
- OS: Windows 11
- Device: Host PC with Intel Wireless Bluetooth adapter
- Radio: Vero Telecom VGC VR-N76 (Bluetooth HFP UUID `0000111F-0000-1000-8000-00805F9B34FB`)

### Root Cause
Unlike standard wired headphones or A2DP stereo Bluetooth devices that maintain continuous active audio endpoints once paired, Windows 10 and 11 treat Bluetooth Hands-Free Profile (HFP) devices through `BthHfenum.sys` as on-demand telephony bearers. The physical Bluetooth SCO (Synchronous Connection-Oriented) audio bearer is held in a disconnected/dormant state while idle to conserve battery and RF spectrum. Attempting to force connection through the standard Windows Settings sound panel fails with "failed because no call was active" because Windows expects an application to initiate an active communications audio client stream (`IAudioClient::Initialize`) targeting `eCommunications` before it commands the Bluetooth stack to establish the SCO bearer.

### Failed Attempts
1. Attempting to click "Connect" in Windows Settings / Devices -> Fails immediately because no telephony call is active.
2. Treating the endpoint as `DEVICE_STATE_NOT_PRESENT` -> Incorrect, the endpoint driver is present in registry `BTHHFENUM` and `MMDevices`.

### Working Fix
1. In `AudioEndpointClassifier.kt`, state `0x8` (`UNPLUGGED`) for Bluetooth Hands-Free endpoints is recognized as normal on-demand behavior rather than a missing hardware error.
2. In `RadioAudioProbeRunner`, the diagnosis report explains that establishing an audio link requires initializing a WASAPI communications stream.
3. In ADR-212, the architecture documents that any future Windows audio bridging must use `AudioCategory_Communications` WASAPI stream initialization.

### Verification
- Tested via `RadioAudioProbeTest.testRADAUD_U07_generateReportWithRadioUnpluggedTelephonyRestriction`.
- Validated against Windows Registry `HKLM\SYSTEM\CurrentControlSet\Enum\BTHHFENUM\BthHFPAudio` on this machine.

### Related Files
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/AudioEndpointClassifier.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/RadioAudioProbe.kt`
- `logs/streams/R-audio/decisions-additions.md` (ADR-210, ADR-212)

### Status
RESOLVED (Diagnostic classification verified; physical communications stream test owed under `RADAUD-01`).
