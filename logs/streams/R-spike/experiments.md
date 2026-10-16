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
