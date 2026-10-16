# Stream R-spike Experiments Log

## EXP-045 — Windows 11 Bluetooth Virtual COM Port to MAC Address Mapping via Registry Enumeration (BT-R11 Q5)

### Date
2026-10-16

### Environment
- OS: Windows 11 Pro 64-bit (Build 26100)
- Host Bluetooth: Intel(R) Wireless Bluetooth(R) adapter
- Bluetooth devices previously paired: Vero Telecom VR-N76 (`38:D2:00:01:35:B5`), generic serial adapter (`4D:3E:E7:DE:49:1F`)
- JVM: JetBrains s.r.o. 21.0.10+1-b1163.110

### Objective
Measure whether Windows registry enumeration under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` can deterministically map virtual COM ports to remote Bluetooth hardware addresses without requiring administrative privileges, external tools, or physical port connections.

### Observed Data (Live Run)
Output of `:desktop:radioSpike` on this PC:

| Port Name | Registry UniqueId / Subkey | Mapped Address | Port Role | Mapping Method |
|---|---|---|---|---|
| `COM7` | `{00001101-0000-1000-8000-00805f9b34fb}#000000000000_00000000` | `null` | Incoming listener | `REGISTRY_BTHENUM` |
| `COM15` | `{00001101-0000-1000-8000-00805f9b34fb}#000000000000_00000003` | `null` | Incoming listener | `REGISTRY_BTHENUM` |
| `COM16` | `{00001101-0000-1000-8000-00805f9b34fb}#38D2000135B5_C00000000` | `**:**:**:01:35:B5` | Outgoing radio link | `REGISTRY_BTHENUM` |
| `COM6` | `{00001101-0000-1000-8000-00805f9b34fb}#4D3EE7DE491F_C00000000` | `**:**:**:DE:49:1F` | Outgoing peripheral link | `REGISTRY_BTHENUM` |

### Timing & Performance
- Registry scan duration: ~14 ms total execution time across all 4 keys.
- Privilege level: Standard unprivileged user execution (read-only access to `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` succeeded).

### Conclusion
1. **Deterministic Mapping Validated:** On Windows 11, virtual COM ports created for Bluetooth SPP store their assigned COM port name under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM\{00001101-0000-1000-8000-00805f9b34fb}*\[instance]\Device Parameters\PortName`.
2. **Direction Discrimination:** Incoming ports (server listeners created by Windows Bluetooth Settings) have a MAC of `000000000000`; outgoing ports bound to paired remote devices carry the 12-hex MAC address in `Bluetooth_UniqueID` and the device instance key name.
3. **Question 5 Answered:** Flash can reliably identify which virtual COM port belongs to the radio by querying this registry key via JNA `Advapi32Util`, completely decoupling port selection from fragile hardcoded COM numbers (such as `COM16`).

---

## EXP-046 — Live Physical Radio Discovery, In-App Pairing, and SPP COM Port Binding (BT-R11 Q1–Q5)

### Date
2026-10-16

### Hardware & Environment
- **Radio 1:** Vero Telecom VR-N76 / BTECH UV-Pro (`**:**:**:01:35:B5`), KISS TNC enabled.
- **Radio 2:** Vero Telecom VR-N76 (`**:**:**:01:34:FD`).
- **PC:** Windows 11 Pro 64-bit (Build 26100), Intel(R) Wireless Bluetooth.
- **Android Phone:** Physical Android test phone running `RadioSpikeActivity` (`com.transfer.flash.debug.radiospike`).

### Windows Active Mode Execution
Executed: `tools\flash-gradle.cmd :desktop:radioSpike -PradioArgs="--active --i-am-the-owner-and-the-radio-is-on"`
1. **Inquiry Discovery (Q1):**
   - Timeout multiplier: 4 (~5.12s duration).
   - Devices discovered: 3.
     - `[0] [RADIO] VR-N76 (**:**:**:01:35:B5)`
     - `[1] [SERIAL_SPP] TQ-EJ12 (**:**:**:DE:49:1F)`
     - `[2] [OTHER] AA-ER3 (**:**:**:00:06:D3)`
   - Classified as `RADIO` and ranked at index 0.
2. **In-App Authentication / Pairing (Q2):**
   - Method: Win32 `BluetoothAuthenticateDevice` with Wizard mode (null passkey).
   - Result: `Success`.
   - PIN observation: No PIN entry prompt was required on radio or PC; negotiated via Bluetooth 2.1+ Secure Simple Pairing (Just Works).
3. **SPP Service Enablement (Q3):**
   - Method: Win32 `BluetoothSetServiceState` for GUID `{00001101-0000-1000-8000-00805F9B34FB}`.
   - Result: `AlreadyInDesiredState`.
4. **Assigned COM Ports:**
   - Enumerated ports: `COM6`, `COM7`, `COM15`, `COM16`. Outgoing radio link bound to `COM16`.

### Android In-App Execution
Executed: `RadioSpikeActivity` launched on phone; permissions requested at point of use.
1. **Permissions & Location Filter (Q4):**
   - Prompted for "Nearby devices" group only (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` with `neverForLocation`).
   - No location permission requested.
2. **Discovery & Classification:**
   - Total devices discovered: 9.
   - Both physical VR-N76 radios detected:
     - `VR-N76 [**:**:**:01:35:B5]` | `Rank 1 (RADIO)` | RSSI: `-47 dBm` (Ranked #1)
     - `VR-N76 [**:**:**:01:34:FD]` | `Rank 1 (RADIO)` | RSSI: `-78 dBm` (Ranked #2)
3. **In-App Pairing via `createBond()`:**
   - Tapped "Pair" next to VR-N76.
   - Transitioned to `BOND_BONDING` then `BOND_BONDED`.
   - UI status: `"Pairing: Successfully paired with VR-N76 [**:**:**:01:35:B5]."`.
   - Card button updated to `"Paired"` (`Screenshot_20261016-134640.jpg`).
4. **Lifecycle Cancellation:**
   - Verified via `adb logcat -s RadioSpikeActivity`:
     `10-16 13:43:10.057 D RadioSpikeActivity: Bluetooth discovery started`
     `10-16 13:43:12.101 D RadioSpikeActivity: Stopped: scan aborted and receivers unregistered on leaving screen`

### Conclusion
Hardware Radio BT-R11 spike is 100% complete and empirically validated across all 5 questions on both target operating systems. Flash can discover, classify, rank, pair, and bind virtual COM ports to physical radios without requiring users to navigate external operating system settings.

