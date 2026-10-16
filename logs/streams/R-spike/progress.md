# Stream R-spike Progress Log

## 2026-10-16 — Task 1: BT-R11 Spike Protocol & Safety Contract (Explain-Back)

### Explain-back: The Five Questions of BT-R11
1. **Radio Discovery & State (Inquiry):** Can the handheld radio (specifically the Vero Telecom VR-N76 / BTECH UV-Pro) be found during an active Bluetooth discovery/inquiry scan, and what specific physical procedure or menu state puts the radio into a discoverable state (e.g. general settings, APRS settings, pairing mode button combo, or automatic timeout)?
2. **Pairing Mechanism & Authentication:** What authentication workflow does the radio expect when pairing: legacy fixed 4-digit PIN (such as "1234" or "0000"), Bluetooth 2.1+ Secure Simple Pairing (SSP) with "Just Works" or numeric comparison, or passkey entry?
3. **Windows JNA Bluetooth API Feasibility:** Can Windows desktop Flash query `bthprops.cpl` via JNA (`BluetoothFindFirstDevice` / `BluetoothFindNextDevice`, `BluetoothAuthenticateDevice` / `BluetoothAuthenticateDeviceEx`, `BluetoothSetServiceState`) to discover nearby radios, pair them programmatically or via system wizard, and enable the Bluetooth Serial Port profile GUID (`00001101-0000-1000-8000-00805F9B34FB`) so that a virtual COM port is spawned by Windows?
4. **Android Discovery & In-App Bonding:** In the Android client, does the radio appear during discovery when `BLUETOOTH_SCAN` is declared with `android:usesPermissionFlags="neverForLocation"` (or does OS location-filtering hide it)? Does initiating bonding via `BluetoothDevice.createBond()` successfully complete pairing via `ACTION_BOND_STATE_CHANGED`, and how does the alternative Companion Device Manager (`CompanionDeviceManager`) compare in terms of permissions, system dialog UX, and suitability for O-7?
5. **Windows COM Port to Bluetooth Address Mapping:** Because Windows assigns virtual COM numbers dynamically (e.g., `COM16`) which can change upon re-pairing or across PCs, how can Flash reliably map a physical COM port to the radio's canonical Bluetooth hardware address? Does registry enumeration under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` reliably link `PortName` to the remote MAC address, or what fallback is required?

### What Each Tool Will and Will Not Do
- **Windows Tool (`desktop` radiospike package / `:desktop:radioSpike` task):**
  - **WILL (Read-Only Mode, Default):** Enumerate serial ports using `jSerialComm` and display system descriptions; enumerate remembered/paired Bluetooth devices and their installed service GUIDs using Win32 Bluetooth APIs via JNA; query the Windows registry to map COM ports to remote Bluetooth addresses; output both human-readable text and structured JSON report with Bluetooth MAC addresses masked to the last 3 bytes (e.g., `**:**:**:01:35:B5`).
  - **WILL (Active Mode, Owner Only):** When explicitly supplied with `--i-am-the-owner-and-the-radio-is-on`, issue Win32 Bluetooth inquiry scans, call `BluetoothAuthenticateDevice`, and call `BluetoothSetServiceState` to enable SPP, prompting for Enter before every step.
  - **WILL NOT:** NEVER execute active mode during agent automated runs; NEVER send any KISS data frame (`CMD 0x00`) or any bytes to any serial port (which would key the radio's RF transmitter and emit on air); NEVER pair, unpair, or modify OS Bluetooth configuration.
- **Android Debug Spike (`app/src/debug`):**
  - **WILL:** Display a dedicated "Radio spike" screen requesting Bluetooth runtime permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` on Android 12+; location on Android 11-) strictly at point of use; list already bonded devices with their SDP UUIDs; run a timed discovery scan with automatic cancellation when leaving the screen or on timeout; provide a "Pair" button that triggers `createBond()` and tracks `ACTION_BOND_STATE_CHANGED`; provide pure-Kotlin device classification ranking radios first; display plain-language status messages.
  - **WILL NOT:** NEVER open an RFCOMM socket, connect, or transmit any data bytes to the radio; NEVER ship in release builds (resides entirely in `app/src/debug`); NEVER execute live on the phone during agent sessions without owner authorization.

### How the Spike is Kept Read-Only
1. **Default Read-Only Execution:** The CLI tool executes in read-only mode by default. Active mode is blocked by an explicit command-line guard `--i-am-the-owner-and-the-radio-is-on` and step-by-step interactive confirmations.
2. **Interface Abstraction & Mocking:** All Win32 Bluetooth calls are mediated through a `WindowsBluetoothApi` interface. Active pairing and service state mutations are unit-tested exclusively using `FakeWindowsBluetoothApi`, which records invocations and verifies safety constraints without touching system DLLs.
3. **Zero RF Transmission:** No serial port writing methods exist in the spike execution path. No KISS data frames are sent.
4. **Owner Separation:** All live hardware testing on physical radios is encapsulated into the owner runbook (`runbook-BT-R11.md`).

---

## 2026-10-16 — Tasks 2 to 5 Implementation & Verification Summary

### Task 2: Windows Tool (`:desktop:radioSpike`)
- Implemented `WindowsBluetoothApi` interface and `RealWindowsBluetoothApi` binding Win32 `bthprops.cpl` via JNA.
- Implemented `FakeWindowsBluetoothApi` recording calls and asserting that read-only runs execute zero mutating functions (`verifyNoMutationsCalled()`).
- Implemented `WindowsComPortMapper` querying `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` via JNA `Advapi32Util` to map COM ports to Bluetooth MAC addresses.
- Implemented `DeviceClassifier` ranking radios first (Rank 1), serial SPP modems second (Rank 2), and other peripherals third (Rank 3).
- Implemented `BluetoothErrorMapper` mapping Win32 error codes (5, 87, 170, 259, 1058, 1167, 1223, 1244) to plain-language descriptions and next steps.
- Implemented `RadioSpikeRunner` CLI:
  - Read-only mode by default.
  - Active mode strictly guarded by `--i-am-the-owner-and-the-radio-is-on`.
  - Masked MAC addresses (`**:**:**:01:35:B5`) in all outputs.
  - Generates human-readable console text and structured JSON reports.
- Registered Gradle task `radioSpike` in `desktop/build.gradle.kts`. Excluded from `jvmJar`.
- Unit tests: 6/6 passed in `RadioSpikeTest.kt`.
- Live read-only test run on Windows 11 host machine: mapped `COM16` and `COM6` to remote device addresses via `REGISTRY_BTHENUM`.

### Task 3: Android Debug Spike (`app/src/debug`)
- Implemented `AndroidRadioDeviceRanker` in pure Kotlin (JVM-testable): classifies candidate devices and ranks radios first, bonded devices top, and higher RSSI first.
- Implemented `RadioSpikeActivity` in `app/src/debug`:
  - Point-of-use permissions: `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` on API 31+; location on API <=30.
  - Lists bonded devices with SDP service UUIDs.
  - 12-second timed discovery scan with countdown; automatically aborts on `onStop()`.
  - In-app pairing button calling `BluetoothDevice.createBond()` and monitoring `ACTION_BOND_STATE_CHANGED`.
  - STRICT SAFETY: Zero RFCOMM connects, zero data/byte transmission.
- Registered debug-only activity in `app/src/debug/AndroidManifest.xml`.
- Unit tests: 5/5 passed in `AndroidRadioDeviceRankerTest.kt`.
- Verified compilation and release isolation: `:app:compileDebugKotlin` passed; `:app:compileReleaseKotlin` passed and release outputs contain zero spike classes.
- Researched Companion Device Manager (CDM) vs custom in-app discovery: documented in `platform-notes-additions.md` and `decisions-additions.md`.

### Task 4: Documentation & Facts
- Proposed draft `ADR-182` for O-6 (Win32 JNA + registry mapping) in `logs/streams/R-spike/decisions-additions.md`.
- Proposed draft `ADR-183` for O-7 (In-app discovery vs CDM) in `logs/streams/R-spike/decisions-additions.md`.
- Sourced Bluetooth platform facts in `logs/streams/R-spike/platform-notes-additions.md` (checked 2026-10-16 against official developer.android.com documentation, verified).
- Recorded `ERROR-620`, `ERROR-621`, and `ERROR-622` in `logs/streams/R-spike/errors.md`.
- Recorded `EXP-045` in `logs/streams/R-spike/experiments.md`.

### Task 5: Owner Runbook & Test Backlog
- Created complete manual test procedure in `logs/streams/R-spike/runbook-BT-R11.md`.
- Added test cases `BT-R11` and `RADSPK-01..06` to `logs/streams/R-spike/backlog-additions.md` in standard backlog format.

---

## Adversarial Self-Review

### 10 Specific Findings (Path:Line, Failure Scenario, Fix)

1. **`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/WindowsComPortMapper.kt:83-88`**
   - *Failure scenario:* In Windows registry `Bluetooth_UniqueID`, the string is `{00001101-0000-1000-8000-00805f9b34fb}#38D2000135B5_C00000000`. The Bluetooth base UUID suffix `00805f9b34fb` is exactly 12 hexadecimal characters. A naive regex `[0-9A-F]{12}` matched `00805f9b34fb` instead of the device address `38D2000135B5`, falsely reporting the Bluetooth base UUID as the remote device MAC address.
   - *Fix:* Extracted the substring strictly after `#` (or after `&` in the instance key) prior to running the MAC address regex, ensuring the base UUID in the class GUID is discarded before MAC extraction.

2. **`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeCli.kt:42-50`**
   - *Failure scenario:* A user or script runs `:desktop:radioSpike -PradioArgs="--active"` intending an active inquiry or pairing scan, but fails to provide the explicit `--i-am-the-owner-and-the-radio-is-on` safety flag. If active mode proceeded automatically, it would emit active inquiry RF pulses and attempt OS bonding on the host PC.
   - *Fix:* Added an unconditional safety check in `RadioSpikeRunner.run()` that verifies `argList.contains("--i-am-the-owner-and-the-radio-is-on")` before calling `runActiveMode()`. If absent, execution immediately aborts with error exit code 1 and logs a loud safety rejection to `System.err`.

3. **`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/WindowsBluetoothApi.kt:203-210`**
   - *Failure scenario:* `BluetoothFindFirstDevice` returns `null` when no Bluetooth devices are remembered or when Bluetooth is disabled. Checking `Native.getLastError()` yields 259 (`ERROR_NO_MORE_ITEMS`). Treating any null handle as an unhandled error would spam console with error alerts during ordinary scans on empty or newly paired machines.
   - *Fix:* Explicitly filtered out Win32 error code 259 from being reported as an error, cleanly returning an `emptyList()` while logging actual failure codes (like error 5 Access Denied or 1058 Service Disabled).

4. **`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/WindowsBluetoothApi.kt:249-260`**
   - *Failure scenario:* Win32 `BluetoothEnumerateInstalledServices` requires a two-step query: first call with `pGuidServices = null` to populate `pcServiceInout` with the service count, followed by a second call with an allocated memory buffer. If `pcServiceInout` is passed with an uninitialized or 0 value without probing first, Windows returns `ERROR_MORE_DATA` (234) or `ERROR_INVALID_PARAMETER` (87) and fails to return any GUIDs.
   - *Fix:* Implemented the two-pass probe pattern: first query count into `IntByReference(0)`, allocate native `Memory((count * 16).toLong())`, then call `BluetoothEnumerateInstalledServices` again with the allocated buffer.

5. **`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeCli.kt:251-276`**
   - *Failure scenario:* Direct compile-time references to `com.fazecast.jSerialComm.SerialPort` inside `:desktop` failed to compile (`ERROR-620`) because `:core:network` declares `implementation(libs.jserialcomm)`, which Gradle does not expose to the consumer's compile classpath. Adding `implementation(libs.jserialcomm)` to `:desktop` could risk build configuration merge conflicts with parallel stream `R-move`.
   - *Fix:* Wrapped serial port enumeration in reflection via `Class.forName("com.fazecast.jSerialComm.SerialPort")`. If present at runtime, ports and descriptions are invoked dynamically; if absent, it catches `Throwable` and returns `emptyList()` with a clean warning.

6. **`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/RadioSpikeCli.kt:318-325`**
   - *Failure scenario:* When generating the machine-readable JSON report via `serializeReportToJson`, device names or serial port descriptions containing quotation marks (`"`), backslashes (`\`), or control characters would create malformed JSON, breaking downstream automated JSON parsers.
   - *Fix:* Created an internal `escapeJson()` helper replacing backslashes, double quotes, newlines, carriage returns, and tabs with standard JSON escape sequences (`\\`, `\"`, `\n`, `\r`, `\t`).

7. **`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/BluetoothModels.kt:47-53`**
   - *Failure scenario:* Logging or outputting raw 48-bit Bluetooth MAC addresses (e.g. `38:D2:00:01:35:B5`) in progress logs, terminal dumps, or JSON files violates privacy and project rules requiring Bluetooth addresses to be truncated/masked to the last 3 bytes.
   - *Fix:* Created `maskBluetoothAddress()` and `maskedAddress` properties across both desktop and Android models (`BluetoothModels.kt` and `DiscoveredRadioCandidate`), formatting addresses as `**:**:**:01:35:B5` and ensuring all saved JSON files and console displays use masked addresses.

8. **`app/src/debug/java/com/transfer/flash/debug/radiospike/RadioSpikeActivity.kt:230-240`**
   - *Failure scenario:* The operator starts an active discovery scan (`startDiscovery()`), and then presses the phone's Back or Home button to leave the screen. If discovery is not cancelled in `onStop()`, the Android Bluetooth adapter continues scanning in the background, consuming radio bandwidth, degrading 2.4 GHz Wi-Fi performance, and causing battery drain.
   - *Fix:* In `RadioSpikeActivity.onStop()`, invoked `stopDiscoveryScan()` to immediately call `bluetoothAdapter.cancelDiscovery()`, cancelled the 12-second `CountDownTimer`, and unregistered the broadcast receiver.

9. **`app/src/debug/java/com/transfer/flash/debug/radiospike/RadioSpikeActivity.kt:313-316`**
   - *Failure scenario:* User taps "Scan Radios" multiple times rapidly. If `adapter.startDiscovery()` is called while a discovery scan is already active, Android returns `false` or drops incoming broadcast intents.
   - *Fix:* Checked `if (adapter.isDiscovering) adapter.cancelDiscovery()` immediately prior to issuing `adapter.startDiscovery()`, ensuring the adapter is in a clean idle state before initiating a new discovery pass.

10. **`app/src/debug/java/com/transfer/flash/debug/radiospike/RadioSpikeActivity.kt:130-136`**
    - *Failure scenario:* During active Bluetooth inquiry, multiple `ACTION_FOUND` broadcasts are delivered for the same device as advertisement packets or inquiry responses arrive. Appending blindly would produce duplicate rows in the Compose `LazyColumn`.
    - *Fix:* Checked `discoveredDevices.indexOfFirst { it.address == addr }`. If existing, the candidate is updated in-place with the latest RSSI and bond state; if new, it is appended.

### 5 Extra Situations Handled

1. **Windows Bluetooth Incoming Server Listeners (COM7/COM15):** When Windows Bluetooth settings sets up incoming COM ports, the registry records the device address as `000000000000`. `WindowsComPortMapper.kt:90-95` detects this and classifies the port as `isIncomingPort = true`, reporting `"Windows incoming Bluetooth serial port listener (000000000000)"` instead of trying to map it to a remote physical radio.
2. **Android 12+ Permission Boundary vs Legacy Android:** `RadioSpikeActivity.kt:242-265` dynamically switches permission checks: on API >= 31, it requests `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (Nearby devices group); on API <= 30, it requests `ACCESS_FINE_LOCATION`, `BLUETOOTH`, and `BLUETOOTH_ADMIN`, preventing crashes and permission rejections across OS versions.
3. **Android Parcelable Extra Deprecation on Android 13+ (API 33 Tiramisu):** `RadioSpikeActivity.kt:101-113` conditionally uses type-safe `intent.getParcelableExtra(..., BluetoothDevice::class.java)` on API 33+ while suppressing the deprecation warning for the legacy call on older APIs, avoiding compile warnings and runtime ClassCastExceptions.
4. **Desktop Jar Packaging Isolation:** In `desktop/build.gradle.kts`, `tasks.named<Jar>("jvmJar")` excludes `"com/transfer/flash/desktop/radiospike/*"`. None of the spike CLI or native Win32 JNA classes are bundled into the production desktop distribution jar.
5. **Android Release APK Source Set Isolation:** `RadioSpikeActivity` is placed in `app/src/debug/java/` and its manifest entry in `app/src/debug/AndroidManifest.xml`. Executed `:app:compileReleaseKotlin` and verified that zero spike classes are present in release compile outputs.

---

## Gate Commands Run & Verification Evidence

1. **`:desktop:jvmTest --tests com.transfer.flash.desktop.radiospike.RadioSpikeTest`**
   - Output: `BUILD SUCCESSFUL in 13s`, 7/7 tests passed (including `testWin32BluetoothNativeInstanceLoads`).
   - Last line: `[flash-gradle] finished with exit code 0 after 14s of running`
2. **`:desktop:radioSpike` (Read-only live run)**
   - Output: `BUILD SUCCESSFUL in 22s`. 4 COM ports enumerated, `COM16` and `COM6` mapped via `REGISTRY_BTHENUM`.
   - Last line: `[flash-gradle] finished with exit code 0 after 22s of running`
3. **`:desktop:radioSpike -PradioArgs="--json --out report-readonly.json"`**
   - Output: `BUILD SUCCESSFUL in 9s`. Loaded `bthprops.cpl` via resolved path, enumerated 2 Bluetooth devices (`TQ-EJ12`, `AA-ER3`), listed service GUIDs (including SPP), mapped `COM6` to `TQ-EJ12` (`**:**:**:DE:49:1F`) via `REGISTRY_BTHENUM`, identified `COM7` as incoming listener, wrote `report-readonly.json`.
   - Last line: `[flash-gradle] finished with exit code 0 after 10s of running`
4. **`:app:compileDebugKotlin`**
   - Output: `BUILD SUCCESSFUL in 5m 1s`.
   - Last line: `[flash-gradle] finished with exit code 0 after 301s of running`
5. **`:app:testDebugUnitTest --tests com.transfer.flash.debug.radiospike.AndroidRadioDeviceRankerTest`**
   - Output: `BUILD SUCCESSFUL in 52s`, 5/5 tests passed.
   - Last line: `[flash-gradle] finished with exit code 0 after 52s of running`
6. **`:app:compileReleaseKotlin`**
   - Output: `BUILD SUCCESSFUL in 55s`.
   - Verified that `RadioSpikeActivity` and spike classes are completely absent from release build classes.
   - Last line: `[flash-gradle] finished with exit code 0 after 55s of running`

---

## Shared Files Touched & Hunk Sizes

1. `app/src/debug/AndroidManifest.xml` (Hunk: +6 lines registering `RadioSpikeActivity` in debug manifest only)
2. `desktop/build.gradle.kts` (Hunk: +7 lines, -2 lines adding JNA dependencies, registering `radioSpike` task, updating `jvmJar` excludes)
*Zero files touched under `core/network`, canonical logs, `docs/decisions.md`, or `AGENTS.md`.*

---

## What is NOT Done and Why

- **Physical radio live testing (Inquiry scan, pairing PIN, on-air tests):** Per machine safety rules, the agent never pairs, transmits, or alters OS Bluetooth state on this PC. These steps are encapsulated into `runbook-BT-R11.md` for the owner.
- **RFCOMM connection & KISS driver integration:** Stay in later Phase R1 (R-spike scope was strictly the discovery/pairing spike BT-R11).
- **Move from `:core:network` to `:core:radio`:** Handled by parallel stream `R-move`.

*Notice: Nothing here has talked to the physical radio; all radio-dependent facts remain UNPROVEN until the owner executes the runbook.*

---

## Stop-and-Ask List

1. Transmitting KISS data frames, AX.25 packets, or any bytes to serial ports (never done; safety rule).
2. Pairing, unpairing, or changing OS Bluetooth state on the owner's PC (deferred to owner runbook).
3. Adding new external dependencies (none added; JNA 5.17.0 was already in `libs.versions.toml`).
4. Modifying files under `core/network` (parallel stream `R-move` territory; zero touched).

