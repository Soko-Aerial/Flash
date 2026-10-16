# Experiment Log — Stream R-move

Reserved EXP range: EXP-040 through EXP-044.

---

## EXP-040 — JvmSerialPortCatalog Non-Opening Port Enumeration Verification

### Date
2026-10-16

### Objective
Verify that `JvmSerialPortCatalog.listAvailablePorts()` and the CLI task `:desktop:radioLinkTestCli -PradioArgs="--list"` accurately discover system serial and Bluetooth peripheral COM ports on Windows without opening or locking any serial port handles.

### Environment
- OS: Windows 11
- Device: DESKTOP-J4LOG6G
- JDK: 21 (Temurin)
- Serial Library: jSerialComm 2.11.4

### Execution
Run command:
`tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"`

### Observations
- Command executed successfully in 14s.
- Discovered active ports:
  - `COM15` (BLUETOOTH, Bluetooth Peripheral Device)
  - `COM7` (BLUETOOTH, Bluetooth Peripheral Device)
  - `COM16` (OTHER, SPP Dev)
  - `COM6` (OTHER, JL_SPP)
- Zero port handles were opened. The OS reported no serial bus contention.

### Conclusion
`JvmSerialPortCatalog` complies with Plan R §4.4 requirement: enumerates system serial ports purely via OS descriptor queries without asserting RTS/DTR or opening RFCOMM/UART data streams.
