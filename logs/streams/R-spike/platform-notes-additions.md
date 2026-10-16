# Android Platform Notes (Stream R-spike Additions)

## 2026-10-16 — Bluetooth Permissions, Discovery Scanning, In-App Bonding, and Companion Device Manager

### Android version / API level
- Android 12 (API level 31) and higher: Runtime permissions `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` required.
- Android 11 (API level 30) and lower: Install-time `BLUETOOTH` and `BLUETOOTH_ADMIN`, plus runtime `ACCESS_FINE_LOCATION` for discovery scanning.
- Android 8.0 (API level 26) and higher: `CompanionDeviceManager` available for companion device association without runtime location permissions.

### APIs / permissions involved
- Manifest permissions:
  - `<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />`
  - `<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />`
  - `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />`
  - `<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />`
  - `<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />`
- APIs:
  - `BluetoothAdapter.startDiscovery()` and `BluetoothAdapter.cancelDiscovery()`
  - Broadcast intents: `BluetoothDevice.ACTION_FOUND`, `BluetoothAdapter.ACTION_DISCOVERY_STARTED`, `BluetoothAdapter.ACTION_DISCOVERY_FINISHED`, `BluetoothDevice.ACTION_BOND_STATE_CHANGED`
  - `BluetoothDevice.createBond()`
  - `android.companion.CompanionDeviceManager`, `BluetoothDeviceFilter`, `AssociationRequest`

### Official sources
- Official guide: Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions (checked 2026-10-16; verified)
- Official guide: Find Bluetooth devices: https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices (checked 2026-10-16; verified)
- Official reference: BluetoothDevice.createBond(): https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createBond() (checked 2026-10-16; verified)
- Official guide: Companion device pairing: https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing (checked 2026-10-16; verified)

### Discovery 1 — `BLUETOOTH_SCAN` with `neverForLocation` avoids location prompts but requires verification on physical radios
On Android 12+, declaring `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` allows apps to discover nearby Bluetooth devices without prompting the user for location access (`ACCESS_FINE_LOCATION`). This places the permission prompt into the user-friendly "Nearby devices" group rather than the sensitive location group.
However, official documentation specifically notes that the operating system filters out certain Bluetooth LE advertisements/beacons when this flag is present. Because classic Bluetooth (BR/EDR) inquiry discovery is used for Bluetooth KISS TNCs, physical device verification (`BT-R11`) is required to confirm whether the VR-N76 / UV-Pro handheld transceivers are received via `ACTION_FOUND` across major OEM implementations.

### Discovery 2 — `createBond()` provides in-app pairing without forcing users to system Bluetooth settings
Prior groundwork in 2026-10-09 relied strictly on querying already-paired devices (`BluetoothAdapter.getBondedDevices()`), requiring the operator to leave Flash and pair via system settings.
Calling `BluetoothDevice.createBond()` directly initiates pairing from within Flash. The OS manages the necessary PIN or numeric comparison overlay on top of Flash's screen and notifies completion via `ACTION_BOND_STATE_CHANGED` (`BOND_BONDING` -> `BOND_BONDED` or `BOND_NONE`). This keeps pairing within the app while respecting platform security boundaries.

### Discovery 3 — Discovery is an active radio hog; canceling before connecting and bounding duration is mandatory
`BluetoothAdapter.startDiscovery()` significantly throttles radio bandwidth and increases latency for any active Bluetooth connection. Furthermore, continuous background discovery rapidly drains battery.
Project rules established in R-spike:
1. Active discovery scans must be bounded by an automatic timeout (12 seconds in `RadioSpikeActivity`).
2. Scans must cancel immediately on activity lifecycle stop/pause (`onStop`).
3. Active discovery must be cancelled before initiating bonding or opening RFCOMM sockets.

### Discovery 4 — Companion Device Manager provides zero-location pairing but enforces an uncustomizable system dialog
`CompanionDeviceManager` (API 26+) provides an association workflow where the OS displays a system chooser dialog matching a `BluetoothDeviceFilter` without requiring location or scan permissions.
However, the CDM chooser cannot be customized or themed using `FlashTheme` tokens, and requires strict filter criteria (matching specific device names or service UUIDs). If the radio firmware advertises an unpredicted name or omits SDP records prior to pairing, CDM fails to present the device to the user.
Therefore, Flash's custom Jetpack Compose list is the primary recommended design, with CDM kept as an architectural fallback if `neverForLocation` encounters OEM filtering.
