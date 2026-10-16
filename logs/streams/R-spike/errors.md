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

---

## ERROR-622 — JNA appends .dll to non-path library names causing bthprops.cpl to fail with ERROR_MOD_NOT_FOUND

### Date
2026-10-16

### Area
Desktop module / Windows Win32 Bluetooth JNA Loading (`WindowsBluetoothApi`)

### Symptoms
When running `:desktop:radioSpike`, section [2/3] failed with:
`[RealWindowsBluetoothApi] Unable to load bthprops.cpl: Unable to load library 'bthprops.cpl': The specified module could not be found. Native library (win32-x86-64/bthprops.cpl.dll) not found in resource path`
Remembered Bluetooth devices and installed services were not enumerated.

### Root cause
JNA's `Native.load(String name, Class interfaceClass)` invokes Java's `System.mapLibraryName(name)` when the provided library name is not an absolute path. On Windows, `System.mapLibraryName("bthprops.cpl")` appends `.dll`, resulting in `"bthprops.cpl.dll"`. Since Windows Control Panel DLLs in `System32` do not carry a `.dll` suffix on disk (the file is literally `C:\Windows\System32\bthprops.cpl`), the Win32 `LoadLibraryEx` call failed with module not found (error code 126).

### Working fix
Updated `Win32BluetoothNative.INSTANCE` in `WindowsBluetoothApi.kt` to resolve the absolute system path dynamically:
```kotlin
val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
val bthpropsFile = java.io.File(systemRoot, "System32\\bthprops.cpl")
val sysNativeFile = java.io.File(systemRoot, "Sysnative\\bthprops.cpl")
val libraryPath = when {
    bthpropsFile.exists() -> bthpropsFile.absolutePath
    sysNativeFile.exists() -> sysNativeFile.absolutePath
    else -> "bthprops.cpl"
}
Native.load(libraryPath, BthpropsLibrary::class.java)
```
When given an absolute file path, JNA passes the path directly to `LoadLibraryExW` without appending `.dll`.

### Verification
- Unit test `testWin32BluetoothNativeInstanceLoads` in `RadioSpikeTest.kt` passed cleanly.
- Executed `tools\flash-gradle.cmd :desktop:radioSpike -PradioArgs="--json --out report-readonly.json"`.
- Successfully loaded `bthprops.cpl`, enumerated 2 remembered Bluetooth devices via `BluetoothFindFirstDevice`/`Next`, queried their SDP service GUIDs via `BluetoothEnumerateInstalledServices` (including SPP GUID `00001101-...`), classified `TQ-EJ12` as `SERIAL_SPP`, and matched `COM6` to `TQ-EJ12` (`**:**:**:DE:49:1F`).

### Related files
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/WindowsBluetoothApi.kt`
- `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeTest.kt`

### Status
RESOLVED

