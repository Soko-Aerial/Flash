# Radio on Windows: Bluetooth facts and measurements

Written 2026-10-23 for the desktop radio gateway (`WindowsRadioDiscovery`, `WindowsBluetoothApi`). Per AGENTS.md §13, every platform fact has its source and date;
"measured" means observed on the owner's PC (Windows 11 Pro 10.0.26300, Intel Wireless Bluetooth adapter), with a VR-N76 handheld.

## Microsoft documentation (read 2026-10-23)

| Fact | Source |
|---|---|
| `fAuthenticated` = "authenticated, paired, or bonded". `fRemembered` = Windows remembers the device; "Not all remembered devices are authenticated." | learn.microsoft.com/en-us/windows/win32/api/bluetoothapis/ns-bluetoothapis-bluetooth_device_info_struct |
| `BluetoothAuthenticateDevice` with no passkey starts the **Wizard**: a user interface; with a passkey it runs **Transparent** mode. Returns 0, `ERROR_CANCELLED` (1223), `ERROR_NO_MORE_ITEMS` (259, already authenticated); in Transparent mode `ERROR_NOT_AUTHENTICATED` (1244) is an authentication failure. | .../nf-bluetoothapis-bluetoothauthenticatedevice |
| `BluetoothAuthenticateDeviceEx` is the recommended call; with a callback registered through `BluetoothRegisterForAuthenticationEx` no UI is shown, and the caller answers with `BluetoothSendAuthenticationResponseEx`. | .../nf-bluetoothapis-bluetoothauthenticatedeviceex, ...registerforauthenticationex |
| Only the LEGACY (1), NUMERIC_COMPARISON (3) and PASSKEY_NOTIFICATION (4) response types are valid; `negativeResponse` TRUE rejects. | .../nf-bluetoothapis-bluetoothsendauthenticationresponseex, ns-bluetoothapis-bluetooth_authenticate_response |
| `BluetoothSetServiceState` enable installs the driver for that service (for SPP: the virtual COM port); `E_INVALIDARG` if it is already in that state. | .../nf-bluetoothapis-bluetoothsetservicestate |
| `BluetoothRemoveDevice` removes the authentication and clears cached service info; fails if the device is not a remembered device. | .../nf-bluetoothapis-bluetoothremovedevice |
| `BluetoothFindFirstDevice` does not find LE devices. | .../nf-bluetoothapis-bluetoothfindfirstdevice |

## Measured on the owner's PC (2026-10-23)

- **The inquiry does not stream.** `BluetoothFindFirstDevice` with an inquiry of 5 x 1.28 s returned after 6.5 s and then handed out all three devices within 50 ms. A scan that shows devices "as they come" therefore needs several short inquiries (2 x 1.28 s rounds) plus an instant first pass over what Windows already knows.
- **One handheld, two entries.** `38:D2:00:01:34:FD` owns the outgoing SPP port (COM15); `38:D2:00:01:35:B5` is the audio endpoint, no outgoing port. Both are called VR-N76.
- **After an unpair Windows keeps the device remembered** (not authenticated) and its registry entries under `BTHPORT\Parameters\Devices`; the COM port goes away. `BluetoothSetServiceState(SPP, enable)` brings the COM port back even without a bond, and opening it then fails with "The network location cannot be reached".
- **Pairing a radio that is not in pairing mode** (or still holds an old bond): `BluetoothAuthenticateDeviceEx(MITMProtectionNotRequiredBonding)` returns 1244 after about 1.2 s with the callback registered and never called; the System log gets `BTHUSB 16`, "The mutual authentication between the local Bluetooth adapter and a device ... failed". This is the radio refusing, not a code path Flash can fix from the PC.
- **Not verified:** a successful pairing through the callback flow (which method the radio uses, whether answering numeric comparison with value 1 is accepted). That is `RADVOICE-11`.
