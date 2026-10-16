# Stream R-spike Test Backlog Additions

Test cases owed for hardware verification on devices (per AGENTS.md §35 and `docs/plans/RADIO-GATEWAY-PLAN.md` §11).

---

### BT-R11 — Bluetooth Hardware Discovery, Pairing, and COM Port Mapping Spike (Plan R1a)
- **Setup:** VR-N76 radio powered on, KISS TNC enabled in General Settings, tuned to legal frequency or dummy load. Windows 11 PC with Bluetooth enabled; Android test phone with Bluetooth enabled.
- **Steps:** Follow `logs/streams/R-spike/runbook-BT-R11.md`:
  1. Run `:desktop:radioSpike` in read-only mode; verify port-to-MAC mapping in JSON.
  2. Run `:desktop:radioSpike --active --i-am-the-owner-and-the-radio-is-on`; perform active inquiry scan, in-app pairing, and SPP service enablement.
  3. Launch `RadioSpikeActivity` on Android; grant Nearby devices permissions at point of use.
  4. Perform 12s active discovery scan with `neverForLocation`; verify VR-N76 appears with Rank 1 badge.
  5. Tap "Pair" in `RadioSpikeActivity`; observe OS pairing dialog, verify transition to `BOND_BONDED` and SPP service UUID listing.
- **Pass:** The five questions in `runbook-BT-R11.md` results table are answered with empirical evidence; default PIN recorded.
- **Source:** `docs/plans/RADIO-GATEWAY-PLAN.md` §10 (Phase R1a), §11 table row `BT-R11`.
- **Status:** PASSED (2026-10-16 by owner runbook execution on PC and Android test phone)

### RADSPK-01 — Windows Virtual COM Port Mapping via BTHENUM Registry
- **Setup:** Windows 11 PC with at least one Bluetooth serial device paired (e.g. VR-N76).
- **Steps:** Run `tools\flash-gradle.cmd :desktop:radioSpike -PradioArgs="--json"`.
- **Pass:**
  - Standard Serial over Bluetooth ports (e.g. `COM16`) map to remote Bluetooth address via `REGISTRY_BTHENUM`.
  - Incoming listener ports (e.g. `COM7`, `COM15`) are identified as incoming listeners (`000000000000`).
  - Output report masks all MAC addresses to preserve only the last 3 bytes (`**:**:**:XX:XX:XX`).
- **Source:** Task 2, ADR-182, BT-R11 Q5.
- **Status:** PASSED (2026-10-16 on Windows 11 host)

### RADSPK-02 — Windows Active Inquiry Discovery via Win32 JNA API
- **Setup:** Windows 11 PC, VR-N76 powered on in discoverable state.
- **Steps:** Run `tools\flash-gradle.cmd :desktop:radioSpike -PradioArgs="--active --i-am-the-owner-and-the-radio-is-on"`. Press ENTER at Step 1 prompt.
- **Pass:**
  - Win32 `BluetoothFindFirstDevice` with `fIssueInquiry=1` discovers VR-N76 within 5.12 seconds.
  - The radio is classified as Category `RADIO` and ranked at index 0.
- **Source:** Task 2, ADR-182, BT-R11 Q1.
- **Status:** PASSED (2026-10-16 on Windows 11 host with physical VR-N76)

### RADSPK-03 — Windows In-App Pairing & Virtual COM Port Creation via JNA
- **Setup:** Windows 11 PC, VR-N76 unpaired from Windows.
- **Steps:** Run active `:desktop:radioSpike`, select discovered radio index, confirm authentication prompt, confirm SPP service enablement prompt.
- **Pass:**
  - `BluetoothAuthenticateDevice` returns `Success`.
  - `BluetoothSetServiceState` with SPP GUID `{00001101-0000-1000-8000-00805F9B34FB}` returns `Success`.
  - Windows assigns a new virtual COM port (e.g. `COM16`) visible in the final port list.
- **Source:** Task 2, ADR-182, BT-R11 Q2 & Q3.
- **Status:** PASSED (2026-10-16 on Windows 11 host with physical VR-N76)

### RADSPK-04 — Android Discovery Scan with `neverForLocation`
- **Setup:** Physical Android test phone (Android 12+), VR-N76 powered on in discoverable mode.
- **Steps:**
  1. Open `RadioSpikeActivity`.
  2. Tap "Permissions"; confirm only "Nearby devices" permission is requested (no location dialog).
  3. Tap "Scan Radios".
- **Pass:**
  - VR-N76 appears in discovered devices list with Rank 1 `[RADIO]` badge and masked address.
  - Signal RSSI is displayed.
  - Radio is not dropped/filtered by OS location filter.
- **Source:** Task 3, ADR-183, BT-R11 Q4.
- **Status:** PASSED (2026-10-16 on Android phone, Screenshot_20261016-134640.jpg)

### RADSPK-05 — Android In-App Pairing via `BluetoothDevice.createBond()`
- **Setup:** Android test phone, VR-N76 discovered in `RadioSpikeActivity` but unbonded.
- **Steps:** Tap "Pair" button next to VR-N76 in discovered list. Follow system PIN prompt if requested.
- **Pass:**
  - Status card displays `"Pairing: In progress with VR-N76..."` followed by `"Pairing: Successfully paired with VR-N76."`.
  - The radio moves to Bonded Devices section and displays SPP UUID `00001101-0000-1000-8000-00805F9B34FB [SPP]`.
- **Source:** Task 3, ADR-183, BT-R11 Q4.
- **Status:** PASSED (2026-10-16 on Android phone, Screenshot_20261016-134640.jpg)

### RADSPK-06 — Android Discovery Lifecycle Cancellation
- **Setup:** Android test phone running `RadioSpikeActivity`.
- **Steps:**
  1. Tap "Scan Radios" and verify 12-second countdown timer decrements. Allow to reach 0; verify scan completes and status shows "Completed".
  2. Tap "Scan Radios" again and immediately press Home/Back button to exit screen.
- **Pass:**
  - `BluetoothAdapter.isDiscovering` becomes false immediately.
  - Logcat shows `"Stopped: scan aborted and receivers unregistered on leaving screen"`.
  - No background scan leakage.
- **Source:** Task 3, AGENTS.md §21.
- **Status:** PASSED (2026-10-16 on Android phone, adb logcat verified)

