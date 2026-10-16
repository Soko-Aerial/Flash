# Stream R-spike Errors & Fixes Log

## ERROR-620 — `jSerialComm` classes not visible on `:desktop` compile classpath

### Date
2026-10-16

### Area
Desktop module / Serial port enumeration

### Symptoms
Compilation of `:desktop:compileKotlinJvm` failed with:
`Unresolved reference 'fazecast'` and `Unresolved reference 'SerialPort'` in `RadioSpikeCli.kt`.

### Root cause
`jserialcomm` was declared as `implementation(libs.jserialcomm)` in `:core:network`'s `jvmMain`. In Gradle, an `implementation` dependency is not exported to downstream consumers' compile classpaths; it is only present on the runtime classpath.

### Working fix
Rather than adding compile-time coupling or altering module dependencies during parallel development (which could conflict with stream `R-move`), implemented a zero-overhead reflection wrapper in `RadioSpikeCli.kt` (`Class.forName("com.fazecast.jSerialComm.SerialPort")`). If `jSerialComm` is present at runtime, it invokes `getCommPorts()`, `getSystemPortName()`, `getDescriptivePortName()`, and `getPortDescription()`; if missing, it gracefully reports that port enumeration is unavailable without crashing.

### Verification
`:desktop:compileKotlinJvm` and `:desktop:radioSpike` compiled and executed cleanly, listing all 4 system COM ports (`COM15`, `COM16`, `COM6`, `COM7`).

### Related files
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeCli.kt`

### Status
RESOLVED

---

## ERROR-621 — Naive 12-hex regex matched Bluetooth Base UUID suffix instead of device MAC address in Registry UniqueId

### Date
2026-10-16

### Area
Windows Bluetooth Registry Mapping (`WindowsComPortMapper`)

### Symptoms
Registry device mapping for `COM16` extracted `00:80:5F:9B:34:FB` instead of the radio's real address `38:D2:00:01:35:B5`.

### Root cause
The Windows registry value `Bluetooth_UniqueID` contains:
`{00001101-0000-1000-8000-00805f9b34fb}#38D2000135B5_C00000000`
The Bluetooth Base UUID suffix `00805f9b34fb` is exactly 12 hexadecimal characters. A naive regex search for `[0-9A-F]{12}` matched the UUID suffix at the front of the string before reaching the actual device address after the `#` delimiter.

### Working fix
Updated `RealWindowsRegistryPortReader` to specifically isolate the substring after `#` (or after `&` in the device instance key) before searching for the 12-hex device address. Added tests covering registry parsing with UUID strings.

### Verification
`:desktop:radioSpike` executed live on Windows 11 and correctly mapped `COM16` to `**:**:**:01:35:B5` (`38D2000135B5`), matching the physical hardware address of the VR-N76.

### Related files
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/WindowsComPortMapper.kt`
- `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeTest.kt`

### Status
RESOLVED
