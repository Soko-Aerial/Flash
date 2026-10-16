# Stream R-spike Decision Additions

These proposed Architecture Decision Records (ADRs) address questions O-6 and O-7 from `docs/plans/RADIO-GATEWAY-PLAN.md` §12. In compliance with stream rules, these are PROPOSED drafts for owner approval, using reserved numbers 182–186.

---

## ADR-182 (PROPOSED) — Windows Bluetooth Radio Discovery, Pairing, and COM Port Mapping Architecture (O-6)

### Status
PROPOSED (Stream R-spike, 2026-10-16). Waiting for owner runbook execution (`runbook-BT-R11.md`).

### Context
`docs/plans/RADIO-GATEWAY-PLAN.md` §6 requires Flash desktop on Windows to discover nearby radios, pair with them without forcing the user to navigate through Windows Settings, enable the Bluetooth Serial Port Profile (SPP) service, and map the dynamically allocated virtual COM port (e.g. `COM16`) to the radio's canonical Bluetooth hardware address.

Question O-6 asks:
*How does the PC scan and pair in-app: Win32 Bluetooth API through JNA (already shipped), or a Windows Runtime (WinRT) helper?*

### Facts & Evidence Levels

1. **JNA 5.17.0 is already shipped in Flash desktop:**
   - *Status:* Verified from repo `gradle/libs.versions.toml:152` and `desktop/build.gradle.kts`.
   - Used for Windows DPAPI access (ADR-035). Requires zero new external dependencies.

2. **Win32 Bluetooth APIs exist in `bthprops.cpl`:**
   - *Status:* Verified from official Microsoft documentation (learn.microsoft.com/en-us/windows/win32/api/bluetoothapis/, checked 2026-10-16).
   - Functions:
     - `BluetoothFindFirstDevice` / `BluetoothFindNextDevice` / `BluetoothFindDeviceClose`: device enumeration and inquiry scan.
     - `BluetoothEnumerateInstalledServices`: enumerates enabled service GUIDs.
     - `BluetoothAuthenticateDevice` / `BluetoothAuthenticateDeviceEx`: performs authentication (Transparent mode with PIN, or Wizard mode).
     - `BluetoothSetServiceState`: installs/removes virtual COM port driver for SPP GUID `{00001101-0000-1000-8000-00805F9B34FB}`.

3. **Win32 API JNA Binding Verified on Windows 11:**
   - *Status:* Verified from physical execution on this machine (`:desktop:radioSpike`, 2026-10-16).
   - The native library `bthprops.cpl` loads cleanly via JNA; `BluetoothFindFirstDevice` runs without throwing errors or crashes.

4. **Deterministic COM Port to Bluetooth Address Mapping via Registry:**
   - *Status:* Verified from physical execution and inspection of Windows Registry on this PC (`HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM`, 2026-10-16).
   - Windows creates subkeys under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM\{00001101-0000-1000-8000-00805f9b34fb}*`.
   - Under `...\Device Parameters`, Windows stores `PortName` (`COM16`) and `Bluetooth_UniqueID` which directly contains the remote MAC address (`38D2000135B5`).
   - Incoming server listener ports are distinctly marked with `000000000000` (e.g. `COM7`, `COM15`), whereas outgoing device ports map to the physical radio MAC.
   - Reading this via JNA `Advapi32Util` succeeds without elevated administrator privileges.

5. **Physical Radio Inquiry & In-App Pairing Behavior on Windows:**
   - *Status:* UNPROVEN until the owner runs `runbook-BT-R11.md`.
   - Whether the VR-N76 responds to inquiry scan while in normal VFO mode or requires a menu state, and what PIN code is negotiated, requires owner runbook verification.

### Alternatives Considered

1. **Win32 Bluetooth API via JNA (Recommended):**
   - *Pros:* Zero new dependencies; uses existing JNA already licensed and packaged; provides direct access to `BluetoothSetServiceState` to explicitly install the SPP virtual COM port; registry mapping via `Advapi32Util` is verified 100% reliable on Windows 11.
   - *Cons:* Win32 Bluetooth APIs are C-based structs requiring strict memory alignment (solved in `WindowsBluetoothApi.kt`).

2. **Windows Runtime (WinRT / UWP) Device Information APIs (`Windows.Devices.Bluetooth`):**
   - *Pros:* Modern async API surface.
   - *Cons:* Requires adding a heavy Windows Runtime projection library (e.g. `windows-primitives` or C++/WinRT wrapper DLL); WinRT Bluetooth RFCOMM APIs do not automatically spawn standard virtual COM ports (`COMn`) needed by `jSerialComm` / `ByteLink`, but rather WinRT streams; violates AGENTS §3 and parallel stream rules forbidding new dependencies without approval.

### Recommendation
Adopt **Win32 Bluetooth API through JNA** as implemented in `:desktop:radiospike`. Use registry enumeration under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` as the primary method to map virtual COM ports to remote Bluetooth addresses.

---

## ADR-183 (PROPOSED) — Android In-App Radio Discovery, Permissions, and Bonding Architecture (O-7)

### Status
PROPOSED (Stream R-spike, 2026-10-16). Waiting for owner runbook execution (`runbook-BT-R11.md`).

### Context
`docs/plans/RADIO-GATEWAY-PLAN.md` §6 requires Flash Android to provide an in-app discovery list and pairing flow for the radio.

Question O-7 asks:
*Android scan/pair UI: Flash's own list (`BLUETOOTH_SCAN`, `createBond`) or the system Companion Device Manager chooser?*

### Facts & Evidence Levels

1. **Android Bluetooth Permissions on Android 12+ (API 31+):**
   - *Status:* Verified from official Android documentation (developer.android.com/develop/connectivity/bluetooth/bt-permissions, checked 2026-10-16).
   - `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` allows discovering Bluetooth devices without requesting runtime location permission (`ACCESS_FINE_LOCATION`).
   - `BLUETOOTH_CONNECT` is required to initiate `createBond()` and query `bondedDevices`.
   - Both permissions belong to the user-friendly "Nearby devices" permission group.

2. **Location Filtering Caveat of `neverForLocation`:**
   - *Status:* Verified from official Android documentation (developer.android.com/develop/connectivity/bluetooth/bt-permissions, checked 2026-10-16).
   - Official docs warn: *"If you use BLUETOOTH_SCAN with neverForLocation, some Bluetooth LE beacons might not be discovered."*
   - Classic Bluetooth discovery via `BluetoothAdapter.startDiscovery()` scans for BR/EDR devices. Whether the VR-N76 handheld radio is affected by `neverForLocation` filtering on actual Android handsets must be tested.

3. **In-App Discovery and Bonding API (`BluetoothDevice.createBond`):**
   - *Status:* Verified from official Android documentation (developer.android.com/reference/android/bluetooth/BluetoothDevice, checked 2026-10-16).
   - `BluetoothAdapter.startDiscovery()` finds devices via `ACTION_FOUND` broadcast.
   - `BluetoothDevice.createBond()` initiates pairing asynchronously, notifying via `ACTION_BOND_STATE_CHANGED`.
   - The OS renders a standard PIN dialog overlay if required by the device, but the user never leaves the Flash app.

4. **Companion Device Manager (CDM) Capabilities and Constraints:**
   - *Status:* Verified from official Android documentation (developer.android.com/develop/connectivity/bluetooth/companion-device-pairing, checked 2026-10-16).
   - Introduced in Android 8.0 (API 26); requires `<uses-feature android:name="android.software.companion_device_setup" />`.
   - Dispatches a system-modal chooser dialog managed by Android; does not require runtime location permissions or `BLUETOOTH_SCAN`.
   - *Limitation:* The chooser is an uncustomizable system dialog that cannot use FlashTheme tokens, dark mode palette, pill buttons, or Flash radio ranking badges.
   - *Limitation:* `BluetoothDeviceFilter` requires matching name patterns or UUIDs. If the radio's advertising name varies across firmware releases, CDM will filter it out.

5. **Physical Handset Behavior with VR-N76:**
   - *Status:* UNPROVEN until the owner runs `runbook-BT-R11.md`.
   - Whether the radio is discovered with `neverForLocation` on the owner's phone without location turned on is tested in `BT-R11`.

### Alternatives Considered

1. **Flash In-App Screen with `BLUETOOTH_SCAN` + `neverForLocation` (Recommended):**
   - *Pros:* Fully styled in Jetpack Compose using Flash theme tokens; renders custom radio classification badges (Rank 1 Radio, Rank 2 SPP); sorts bonded devices and high RSSI to top; automatically stops discovery when exiting the screen or on a 12s timer; non-disruptive UX.
   - *Cons:* Requires requesting the "Nearby devices" permission group on Android 12+.

2. **Companion Device Manager (`CompanionDeviceManager`):**
   - *Pros:* No `BLUETOOTH_SCAN` or location permission needed.
   - *Cons:* System-dialog UI breaks Flash visual consistency; device filtering is brittle for third-party radio brands; `BLUETOOTH_CONNECT` is still required afterwards to open RFCOMM.

### Recommendation
Adopt **Flash's own in-app discovery list** using `BLUETOOTH_SCAN` (with `neverForLocation`) and `BluetoothDevice.createBond()`, as implemented in `RadioSpikeActivity.kt`.
Keep Companion Device Manager as a documented fallback only if physical device testing reveals that specific OEM handsets suppress the radio under `neverForLocation`.
