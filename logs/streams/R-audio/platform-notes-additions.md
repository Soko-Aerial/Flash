# Platform Notes Additions — Stream R-audio (Phase R4 / BT-R10)

These notes document empirical, operating system, and hardware protocol facts researched for Phase R4 (`BT-R10`) of `docs/plans/RADIO-GATEWAY-PLAN.md` (Walkie-talkie audio bridge spike).
In accordance with AGENTS.md §13 and the stream rules, every fact is labeled with its source URL, date checked (2026-10-17), and evidence rating:
- **Verified:** Directly validated against official documentation (Android Developers / Microsoft Learn / Bluetooth SIG) or empirical test runs.
- **Reported:** Published in official manufacturer manuals, datasheets, or firmware release notes.
- **Community-claim:** Observed in amateur radio forums, APRS user groups, or third-party write-ups.
- **UNPROVEN:** Hypothesized or asserted in earlier plan drafts without authoritative verification; must remain unproven until tested on hardware.

---

## 1. Bluetooth Hands-Free Profile (HFP) Roles and Protocols

### Facts
1. **Dual HFP Roles (Audio Gateway vs. Hands-Free Unit):**
   - *Status:* **Verified** (Bluetooth SIG HFP Specification v1.8 / v1.9, checked 2026-10-17; https://www.bluetooth.com/specifications/specs/hands-free-profile-1-9/).
   - **Audio Gateway (AG):** The device that acts as the gateway of the audio, both for input and output (typically a cellular phone or PC). Service Class UUID: `0x111F` (`0000111F-0000-1000-8000-00805F9B34FB` - `HandsfreeAudioGateway`) or `0x1112` (`HeadsetAudioGateway`).
   - **Hands-Free Unit (HF):** The device that acts as the remote audio input/output mechanism (typically a wireless car kit, headset, or speaker-mic). Service Class UUID: `0x111E` (`0000111E-0000-1000-8000-00805F9B34FB` - `Handsfree`).
   - **Interoperability Constraint:** An HFP connection requires strictly one AG and one HF. Two AG devices cannot establish an HFP link to each other; two HF devices cannot establish an HFP link to each other.

2. **HFP Signaling and Audio Bearers:**
   - *Status:* **Verified** (Bluetooth SIG HFP Specification v1.8, checked 2026-10-17).
   - Control signaling operates over an RFCOMM channel using AT commands (e.g., `AT+BRSF` for supported features, `AT+CIND` for call indicators, `AT+BCS` for codec selection).
   - Voice audio is transmitted strictly over a synchronous SCO (Synchronous Connection-Oriented) or eSCO (Extended SCO) physical link, negotiated after the RFCOMM control connection is established.
   - Codecs: Narrowband speech uses CVSD at 8 kHz sampling rate (~64 kbps). Wideband speech (HFP 1.6+) uses mSBC at 16 kHz sampling rate.

3. **VR-N76 Advertised Bluetooth Audio UUIDs:**
   - *Status:* **Verified** (Empirical observation in `EXP-023` on Windows 11 host, 2026-10-16).
   - The VR-N76 advertises a service with UUID `0000111F-0000-1000-8000-00805F9B34FB` under the name `VR-N76 Hands-Free HF`.
   - Note on role naming vs. UUID: Although the device name includes "HF", UUID `0x111F` is the standard Bluetooth SIG identifier for `HandsfreeAudioGateway`. Whether the radio firmware acts as an AG (expecting to connect outward to wireless headsets) or as an HF (expecting to connect inward to smartphones) when audio is routed remains **UNPROVEN**.

---

## 2. Android Audio Routing and Bluetooth SCO APIs

### Facts
1. **Modern Communication Device Routing (Android 12+, API 31+):**
   - *Status:* **Verified** (developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo), checked 2026-10-17; https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
   - `AudioManager.setCommunicationDevice(AudioDeviceInfo device)` is the official API on API 31+ for routing VoIP and communication audio.
   - Target device must have `device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO`.
   - Replaces deprecated legacy APIs: `startBluetoothSco()`, `stopBluetoothSco()`, and `setBluetoothScoOn()`.
   - Requires `AudioManager.mode = AudioManager.MODE_IN_COMMUNICATION`.
   - Requires runtime permissions: `RECORD_AUDIO` and `BLUETOOTH_CONNECT` (on API 31+), plus `MODIFY_AUDIO_SETTINGS`.
   - Audio routing is released via `AudioManager.clearCommunicationDevice()`.

2. **Bluetooth Headset Profile Proxy:**
   - *Status:* **Verified** (developer.android.com/reference/android/bluetooth/BluetoothHeadset, checked 2026-10-17).
   - Obtained via `BluetoothAdapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)`.
   - Provides `getConnectionState(BluetoothDevice)` returning `STATE_CONNECTED`, `STATE_CONNECTING`, `STATE_DISCONNECTED`.
   - `isAudioConnected(BluetoothDevice)` indicates whether the synchronous SCO audio channel is physically established.

3. **Android Hands-Free Client Role Limitation:**
   - *Status:* **Verified** (Android Open Source Project / developer.android.com, checked 2026-10-17).
   - Standard Android smartphone builds (AOSP / Google Pixel / Samsung OneUI) support only the **Audio Gateway (AG)** role for HFP.
   - The `BluetoothHeadsetClient` (HF role) profile is restricted to Android Automotive OS (AAOS) for car head units. A standard Android smartphone cannot connect to another AG as a client headset.

---

## 3. Windows Core Audio (WASAPI) Endpoint Model for Bluetooth Hands-Free

### Facts
1. **Windows Audio Endpoint Enumeration via Core Audio / COM:**
   - *Status:* **Verified** (learn.microsoft.com/en-us/windows/win32/coreaudio/mmdevice-api, checked 2026-10-17; https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nn-mmdeviceapi-immdeviceenumerator).
   - Endpoints are enumerated via `IMMDeviceEnumerator::EnumAudioEndpoints(EDataFlow, DWORD dwStateMask, IMMDeviceCollection**)`.
   - Data flows: `eRender` (playback/speaker) and `eCapture` (recording/microphone).
   - State masks: `DEVICE_STATE_ACTIVE` (0x1), `DEVICE_STATE_DISABLED` (0x2), `DEVICE_STATE_NOTPRESENT` (0x4), `DEVICE_STATE_UNPLUGGED` (0x8).
   - Roles: `eConsole` (games/system), `eMultimedia` (music), `eCommunications` (telephony, VoIP).

2. **Dual-Endpoint Architecture for Bluetooth Devices in Windows:**
   - *Status:* **Verified** (learn.microsoft.com/en-us/windows-hardware/drivers/audio/bluetooth-bypass-audio-streaming, checked 2026-10-17).
   - Windows exposes Bluetooth audio devices through two distinct endpoint pairs:
     1. **Stereo Playback (A2DP):** High-definition render endpoint (e.g., 44.1/48 kHz, 16/24-bit stereo). Playback only; no mic capture.
     2. **Hands-Free AG Audio (HFP):** Bidirectional communications endpoint managed by `BthHfenum.sys` (Microsoft Bluetooth Hands-Free Audio driver). Render and capture are both narrowband 8 kHz CVSD or 16 kHz mSBC mono.

3. **Windows Hands-Free Endpoint Connection Behavior & EXP-023 Diagnosis:**
   - *Status:* **Verified** (Empirical validation in `EXP-023` and Windows Audio Architecture docs, checked 2026-10-17).
   - On Windows 10 and 11, the Windows Bluetooth Audio stack does NOT keep the Bluetooth SCO voice channel open in an idle state.
   - In `EXP-023`, clicking "Connect" in Windows Settings / Sound Control Panel failed with "failed because no call was active" because Windows treats the HFP audio endpoint as an on-demand telephony bearer.
   - A Hands-Free endpoint will only open its SCO bearer when an active WASAPI audio stream is initialized (`IAudioClient::Initialize`) with `eCommunications` or via Windows Call/Telephony APIs (`Windows.ApplicationModel.Calls`).
   - If the paired device is not ready to negotiate the HFP RFCOMM service level connection or if it advertises an unexpected role, Windows marks the audio endpoint as `DEVICE_STATE_UNPLUGGED` or refuses to transition to `DEVICE_STATE_ACTIVE`.

---

## 4. Vero Telecom VGC VR-N76 / BTECH UV-Pro Manufacturer Material

### Facts
1. **App-Defined Radio Architecture:**
   - *Status:* **Reported** (Vero Telecom VR-N76 User Manual; https://www.verotelecom.com/, checked 2026-10-17).
   - The VR-N76 is designed to connect to the "HT App" (Android/iOS) over Bluetooth SPP / BLE for frequency programming, APRS messaging, and network radio cross-linking.

2. **Supported Bluetooth Accessories:**
   - *Status:* **Reported** (Vero Telecom Accessories Datasheet: BHM-79, BHM-88, BS-PTT; checked 2026-10-17).
   - The radio natively pairs with wireless speaker-microphones (BHM-79, BHM-88) and wireless Bluetooth PTT buttons (BS-PTT).
   - In this mode, the radio's internal firmware acts as the **Audio Gateway / Master Host** communicating with the peripheral mic and speaker.

3. **Bluetooth VOX (Voice-Operated Exchange):**
   - *Status:* **Reported** (BTECH UV-Pro Firmware v0.8.0 Release Notes and VR-N76 manual, checked 2026-10-17; https://baofengtech.com/).
   - Firmware v0.8.0 added "Bluetooth VOX", enabling the radio to detect incoming voice audio from a paired Bluetooth audio peripheral and automatically trigger transmitter PTT without pressing a physical button.
   - Syllabic delay caveat: Voice-activated transmission inherently incurs a 100–300 ms attack delay (clipping the beginning of speech) and an unkey hold delay (transmitting dead air/tail).

4. **Software PTT via Bluetooth Command:**
   - *Status:* **UNPROVEN**.
   - Standard Bluetooth HFP has no AT command for RF PTT transmission (HFP is a telephone call profile, not a two-way radio profile).
   - The BS-PTT wireless button uses custom BLE GATT packets (`39144315-32FA-40DB-85ED-FBFEBA2D86E6`).
   - Whether Flash can issue a software command over BLE or SPP to key the analog RF transmitter without relying on VOX is **UNPROVEN**.

5. **Inbound RF Audio Discriminator Routing over Bluetooth:**
   - *Status:* **UNPROVEN**.
   - When an analog FM signal breaks squelch on the radio receiver, does the radio route discriminator audio into the Bluetooth SCO uplink (acting as a microphone stream to a connected smartphone/PC)?
   - When paired to a wireless headset (radio as AG), it routes audio to the headset speaker. But when paired to a phone, whether the radio functions as an audio source for received RF is **UNPROVEN**.
