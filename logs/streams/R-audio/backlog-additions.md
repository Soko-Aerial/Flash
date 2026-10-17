# Backlog Additions — Stream R-audio

These test entries document device tests owed for Phase R4 (`BT-R10`) of `docs/plans/RADIO-GATEWAY-PLAN.md`.
Format complies with `AGENTS.md` §35 and `docs/testing/TEST-BACKLOG.md`.
Reserved test prefix: `RADAUD-` and `BT-R10`.

---

### `BT-R10` — Hardware Radio Audio Bridge Spike on Physical VR-N76
- **Setup:** Vero Telecom VR-N76 handheld transceiver (firmware with Bluetooth HFP and VOX), Windows 11 PC with Intel Bluetooth adapter, Android test phone (API 31+), optional secondary receiver / SDR on test frequency (e.g. 144.625 MHz into 50-ohm dummy load).
- **Steps:**
  1. Complete numbered execution steps in `logs/streams/R-audio/runbook-BT-R10.md` (Part A on Windows, Part B on Android, Part C for turn-taking).
  2. Collect diagnostic outputs (`report-audio-probe.json`, Logcat, and monitor audio recording).
  3. Fill the results table in runbook §5 and sign off the owner decision template (§6).
- **Pass:** The four core questions of Plan R §9 are answered in writing with empirical device evidence; owner selects Option 1, 2, or 3.
- **Source:** `docs/plans/RADIO-GATEWAY-PLAN.md` §9 & §11, ADR-210, `logs/streams/R-audio/runbook-BT-R10.md`.
- **Status:** TODO

---

### `RADAUD-01` — Windows Audio Endpoint Enumeration of Radio Hands-Free Device
- **Setup:** VR-N76 paired to Windows 11 PC via Bluetooth with Hands-Free Telephony enabled.
- **Steps:**
  1. Run `tools\flash-gradle.cmd :desktop:radioAudioProbe -PradioArgs="--json --out report.json"`.
  2. Inspect generated report and console table.
- **Pass:** `VR-N76 Hands-Free HF` is enumerated under `windowsEndpoints`, classified as `RADIO_HANDS_FREE`, and reported as `UNPLUGGED (0x8)` with the explanation that Windows opens SCO only on active communications streams.
- **Source:** Phase R4 / BT-R10 Step A1, ADR-212.
- **Status:** TODO

---

### `RADAUD-02` — Android In-App Bluetooth SCO Routing via `RadioAudioActivity`
- **Setup:** VR-N76 paired to Android test phone; debug build of `:app` installed.
- **Steps:**
  1. Launch `adb shell am start -n com.transfer.flash/com.transfer.flash.debug.radioaudio.RadioAudioActivity`.
  2. Tap "Grant Perms" and accept runtime permissions.
  3. Tap "Route SCO".
- **Pass:** Status card transitions to `"Active: Routed via setCommunicationDevice(VR-N76)"` on Android 12+ (or `"SCO Broadcast State: CONNECTED"` on legacy Android); `audioDeviceList` marks VR-N76 with green `[SCO READY]` badge. Tapping "Clear Route" cleanly returns to `"Idle"`.
- **Source:** Phase R4 / BT-R10 Step B4, ADR-212.
- **Status:** TODO

---

### `RADAUD-03` — VR-N76 Inbound Analog RF Discriminator Audio Routing
- **Setup:** VR-N76 routed to Android phone via SCO (`RADAUD-02`); secondary transmitter tuned to test frequency.
- **Steps:**
  1. Transmit voice audio from secondary transmitter to VR-N76.
  2. Open sound recorder app on phone while SCO route is held.
- **Pass:** Incoming analog RF audio is successfully captured by the phone's audio input via Bluetooth SCO uplink without silence or distortion.
- **Source:** Phase R4 / BT-R10 Step B5, ADR-210.
- **Status:** TODO

---

### `RADAUD-04` — VR-N76 Bluetooth VOX Syllabic Attack and Unkey Latency
- **Setup:** VR-N76 VOX enabled (*Menu -> Settings -> VOX -> ON*); secondary receiver/monitor on test frequency.
- **Steps:**
  1. Stream spoken test audio through the VR-N76 Hands-Free playback endpoint.
  2. Record on secondary monitor receiver.
  3. Measure time delta between first spoken syllable and RF carrier activation, and time between speech end and transmitter unkey.
- **Pass:** Attack delay and unkey delay are measured and recorded in milliseconds; first syllable clipping is evaluated for speech intelligibility.
- **Source:** Phase R4 / BT-R10 Step A4 & B6, ADR-210.
- **Status:** TODO
