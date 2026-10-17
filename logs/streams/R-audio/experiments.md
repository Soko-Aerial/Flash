# Experiment Log — Stream R-audio

Reserved experiment range: EXP-070 through EXP-074.

---

## EXP-070 — Empirical Windows Registry and Audio Endpoint Topology for VR-N76

### Date
2026-10-17

### Objective
Inspect and map the Windows 11 audio driver and registry hierarchy for the Vero Telecom VR-N76 handheld radio to answer Question 1 of Plan R §9 (`BT-R10`): In what role and driver stack does Windows recognize the radio's Bluetooth audio profile?

### Environment
- **Host PC:** Windows 11 Pro, Intel(R) Wireless Bluetooth(R) adapter (`USB\VID_8087&PID_0A2B`).
- **Radio:** Vero Telecom VGC VR-N76 (Bluetooth MAC `38:D2:00:01:35:B5`), paired during previous `BT-00` hardware spike.
- **Tools:** Windows Registry inspection via JNA / PowerShell (`HKLM\SYSTEM\CurrentControlSet\Enum\BTHHFENUM`).

### Observations & Measurements

1. **Driver Service & Enumeration:**
   - Registry path: `HKLM:\SYSTEM\CurrentControlSet\Enum\BTHHFENUM\BthHFPAudio\8&33cd51b6&2&97`
   - `DeviceDesc`: `@microsoft_bluetooth_hfp.inf,%hfaudinstallation%;Microsoft Bluetooth Hands-Free Audio device`
   - `FriendlyName`: `@System32\drivers\bthhfenum.sys,#4;%1 Hands-Free HF Audio%0\n;(VR-N76)`
   - `Service`: `BthHFAud` (Microsoft Bluetooth Hands-Free Audio Driver)
   - `HardwareID`: `BTHHFENUM\BthHFPAudio`
   - `ContainerID`: `{6738be20-8872-51d4-bfa6-097d3a908211}`
   - `LocationInformation`: `HFP Bus 0`

2. **Core Audio Endpoint State:**
   - In `HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render` and `Capture`, the endpoint is registered but its `DeviceState` value is `0x00000008` (`DEVICE_STATE_UNPLUGGED`).
   - The endpoint is NOT in `DEVICE_STATE_ACTIVE` (`0x00000001`).

3. **Behavioral Analysis:**
   - This provides direct empirical proof of the mechanism behind the `BT-00` (`EXP-023`) observation: "Attempting to connect via the Windows audio endpoint UI fails because no call was active".
   - The Microsoft `BthHFAud` driver intentionally holds the Bluetooth SCO link closed while idle.
   - It only opens an active SCO bearer upon request from a telephony application or WASAPI communications stream.

### Conclusion & Plan Impact
1. **Question 1 Answered for Windows:** Windows installs the radio under `BthHFPAudio` as an HFP Hands-Free audio device. The endpoint is valid, but dormant (`UNPLUGGED` 0x8) during idle.
2. **Architecture Validation:** Any future implementation of Mode 3 or Mode 5 on Windows must use WASAPI `eCommunications` stream initialization to trigger the SCO bearer.
