# The radio's two Bluetooth channels, and why Android and Windows open them differently

Written 2026-10-28 at the owner's request ("how does it change from the audio mode to the KISS mode, and why is Android different
from Windows"). It answers that with the code in this repository plus the documentation cited in §6. Nothing here is a change to the
app; the defect this investigation did find (the desktop's texts never entering the radio path at all) is ADR-229 and ERROR-869
round 3.

Read together with:

- `docs/network/RADIO-WIRE-FORMAT.md` — what travels on the serial channel (KISS frames, chat frame v1).
- `docs/network/RADIO-AUDIO.md` — what travels on the audio channel (framed SBC) and the voice card.
- `docs/decisions.md` ADR-214 (link acquisition), ADR-225 (voice), ADR-227/228 (data over radio), ADR-229 (path order).

## 1. What the handheld actually exposes

The VR-N76 / BTECH UV-Pro class handheld presents **one Bluetooth address and two RFCOMM services at the same time**:

| Service | UUID | What a host can do with it |
|---|---|---|
| Serial Port Profile (SPP) | `00001101-0000-1000-8000-00805F9B34FB` | a byte stream: the vendor command protocol, **or** KISS TNC frames when the radio's KISS TNC setting is on |
| Vendor audio ("BS AOC"), fallback "Generic Audio" | `39144315-32fa-40db-85ed-fbfeba2d86e6`, `00001203-…` | framed SBC audio in both directions; sending audio keys the radio |

### There is no "audio mode" and no "KISS mode" to switch

The app never toggles the radio between the two. Both channels exist at once, and each is its own RFCOMM channel:

- *Reported* by HTCommander (Apache-2.0) and by `f4dvk/benshi-esp32-sim`, whose ESP32 radio simulator serves the control **and**
  audio RFCOMM servers side by side because the Android app opens both together (§6).
- *Reported* by `SarahRoseLives/SVXLink-BenshiModule`, which talks to the same radio over SPP for control and the vendor UUID for
  audio (§6).
- *Measured on our own hardware*: on 2026-10-28 the desktop held `radio.link.up link=COM15_@115200` (the serial channel, over SPP)
  while the voice card worked, and text was received over the same COM port at 12:19:06 and 12:19:51.

The only real "mode" lives **on the serial channel and is set on the radio**: with *KISS TNC → Enable* in the radio's menu, the SPP
channel carries KISS frames; with it off, the same channel speaks the vendor command protocol. The host does not choose; it reads and
writes bytes and gets whichever one the radio was told to speak. That is why our settings card says "enable KISS TNC on the radio" and
the app has no audio/KISS switch.

Consequence worth remembering: a radio left in command mode still connects, still accepts writes, and simply never produces KISS —
which looks like "the link is up but nothing arrives".

## 2. Android: the app owns the socket

Android gives an app the RFCOMM socket itself, so Flash decides every detail (`AndroidBluetoothCatalog.openRadioLink`, ADR-214):

1. SDP lookup, then `createRfcommSocketToServiceRecord(UUID)` (secure).
2. If the radio refuses the secure channel: `createInsecureRfcommSocketToServiceRecord(UUID)`.
3. If SDP is useless (some firmware advertises a channel it then refuses): fixed channels 1..12, insecure first, then channel 1
   secure.
4. The last good channel is cached **per address *and* per service**, so the serial channel and the audio channel of one radio cannot
   poison each other any more.

Because the socket is ours, both channels can be open at the same time (control/KISS + audio), and the audio channel is found by SDP
only — no fixed-channel probing there, because channel 1 is the serial channel on these radios.

Android facts used: `BluetoothDevice.createRfcommSocketToServiceRecord` / `createInsecureRfcommSocketToServiceRecord`,
`fetchUuidsWithSdp` (§6).

## 3. Windows: the OS owns the serial link, the app owns the audio socket

This is the asymmetry the owner noticed. Windows does not hand an app a Bluetooth socket for a serial service; **pairing installs a
serial-port driver** and the app opens a COM port (`WindowsComPortMapper` → `JvmSerialPortCatalog`):

1. Pairing creates an **outgoing** Bluetooth serial port for SPP. Windows records it under
   `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM\{00001101-…}_LOCALMFG&…\<device>` with a `PortName` (`COM15`) and a
   `Bluetooth_UniqueID` of the shape `{00001101-…}#<radio MAC>_C<…>` — the `C` form is the outgoing (client) port.
2. `RealWindowsRegistryPortReader` reads that mapping; `WindowsComPortMapper` resolves "the port for radio `38:D2:00:01:34:FD`".
3. `JvmSerialPortCatalog` opens the port. That is the `radio.link.up link=COM15_@115200` line in the desktop log.

Two consequences that matter in practice:

- **Only one process can hold the port.** If the radio's own Windows software (or a second Flash instance) has COM15 open, our open
  fails and the desktop retries — look for repeated `radio.link.attempt` with a port name and no `radio.link.up`.
- **The baud number is a formality.** RFCOMM is a byte stream with no baud rate; 115200 is what a serial API demands, and the radio's
  own serial speed lives inside the radio. *Reported* (the Bluetooth FAQ answer repeated by Microsoft's documentation and Bluetooth
  serial-port tooling) — it is not something we measured, and it does not need measuring: the link works at that number.

The **audio** channel has no COM port at all (only SPP gets one), so `JvmRadioAudio` opens it as a Bluetooth RFCOMM client socket
through Winsock (`WindowsRfcomm` / `WinsockRfcommLink`, `SOCKADDR_BTH`, port 0 = resolve by SDP). So on one PC the two channels are
opened by two completely different mechanisms: a **COM port** for data, a **Winsock RFCOMM socket** for voice.

## 4. Side by side

| | Android | Windows |
|---|---|---|
| Serial channel opened by | the app, `BluetoothSocket` (secure → insecure → channels 1..12) | Windows, as an outgoing COM port created at pairing; the app opens `COM15` |
| Audio channel opened by | the app, `BluetoothSocket` to the vendor UUID (SDP only) | the app, Winsock RFCOMM socket to the vendor UUID |
| Who can block it | another app holding the socket | another app holding the COM port (the vendor's software is the usual one) |
| "Baud" | not applicable | nominal `115200`; ignored over RFCOMM |
| Service discovery | SDP, re-read per connect, cached per address+service | cached by Windows at pairing; a firmware change means remove and re-pair |
| What a "Radio only" text rides on | the same RFCOMM socket the control protocol uses | the COM port, not the audio socket |
| Failure looks like | socket refused → `radio.link.attempt` variants, then `radio.fail` | port missing / busy → attempts with a port name, no `radio.link.up` |

## 5. What the 2026-10-28 desktop log proves (and what it did not)

From `C:\Users\KaliOxygen\.flash\desktop.log`, 12:15–12:20, both radios with KISS TNC enabled:

```text
12:15:52.709 I/PROBE: radio.link.up link=COM15_@115200 attempt=1
12:19:06.919 I/PROBE: radio.rx.text peer=adb2d87d bytes=2 duplicate=false signed=false
12:19:06.923 I/PROBE: radio.tx.ack  peer=adb2d87d
12:19:27.757 I/PROBE: chat.path peer=adb2d87d message=e624c8e2 path=hub
12:19:51.802 I/PROBE: radio.rx.text peer=adb2d87d bytes=4 duplicate=false signed=false
12:19:51.803 I/PROBE: radio.tx.ack  peer=adb2d87d
```

So: the Windows data path is healthy in **both** directions at the frame level (it receives texts and its ACKs reach the phone, which
is why the phone shows *Delivered*), and it is the **COM port**, not a Winsock socket. The desktop's *own texts*, however, never
entered the radio path: `chat.path path=hub` twice per message. That was not a Bluetooth or KISS problem at all — the chat outbox sent
them down the hub path and stopped there (ADR-229, ERROR-869 round 3).

## 6. Sources

| Fact | Source | Read | Class |
|---|---|---|---|
| RFCOMM service discovery on Windows happens through `WSALookupServiceBegin`/`Next`/`End` against a `WSAQUERYSET` naming the service class GUID | https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-wsalookupservicebegin-for-service-discovery | 2026-10-28 | verified (official) |
| Bluetooth service queries and device enumeration on Windows use the same Winsock BTH namespace | https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-wsalookupservicebegin | 2026-10-28 | verified (official) |
| The vendor audio service must be opened as a Winsock RFCOMM socket; there is no COM port for it | https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-socket and https://learn.microsoft.com/en-us/windows/win32/api/ws2bth/ns-ws2bth-sockaddr_bth | 2026-10-23 | verified (official) |
| Android RFCOMM: `createRfcommSocketToServiceRecord` (authenticated/encrypted) and `createInsecureRfcommSocketToServiceRecord` (no link-level security), `fetchUuidsWithSdp` | https://developer.android.com/reference/android/bluetooth/BluetoothDevice | 2026-10-28 | verified (official) |
| Android Bluetooth data transfer guide (RFCOMM as the SPP path, sockets owned by the app) | https://developer.android.com/develop/connectivity/bluetooth/transfer-data | 2026-10-28 | verified (official) |
| The radio's two services, and that a host opens control and audio at the same time | `f4dvk/benshi-esp32-sim` README (ESP32 simulator of this radio for HTCommander; two RFCOMM servers, control + audio) | 2026-10-28 | reported by that project |
| SPP carries the control/bridge protocol, the vendor UUID carries audio | `SarahRoseLives/SVXLink-BenshiModule` (`INSTALL.md`) | 2026-10-28 | reported by that project |
| Audio framing, SBC parameters, "sending audio keys the radio" | HTCommander (`docs/Voice.md`, `src/lib/radio/audio_engine.dart`) | 2026-10-23 | reported by that project |
| Windows creates an outgoing COM port for SPP at pairing, with the `Bluetooth_UniqueID`/`PortName` registry layout | our own `WindowsComPortMapper` + the registry of the owner's PC (COM15 for `38:D2:00:01:34:FD`) | 2026-10-20 | verified here (read from the registry) |
| The baud rate of a Bluetooth COM port is ignored | Bluetooth FAQ answer repeated by Microsoft tooling; not re-read here | — | **community claim / reported** — needs no action, the link works at 115200 |

Nothing in the table contradicts our code; the one thing no source can settle is which channel a *particular* firmware refuses, which
is why ADR-214 probes channels instead of trusting SDP.

## 7. Reading the next log

| Line | Means |
|---|---|
| `radio.link.attempt … step=insecure_sdp / channel=N` | the earlier attempt was refused; the radio is being probed |
| `radio.link.up link=COM15_@115200` | Windows SPP link open; KISS bytes can flow |
| `radio.link.up link=RFCOMM@<addr>/ch<N>` | Android socket open |
| `radio.rx.text`, `radio.tx.ack` | frames actually crossed the air in each direction |
| `radio.path result=not_reachable gateway=true connected=true` | the link is fine; read the fields: `policy=NEVER` is the per-chat choice, `pairKey=false` is a missing 32-byte key, `gateway=false`/`connected=false` is the link (ADR-354) |
| `radio.path result=refused` + `radio.send.fail reason=…` | the radio step was reached and the bridge said no (see the reason) |
| `chat.path path=hub` | the message went to the organisation hub, **not** the radio |
| `chat.path path=hub_mailbox` | the radio refused (temporarily) and the hub is holding it |
| `chat.path path=radio` | it went over the air |
| `hub.online count=0` | the hub reports nobody online: a hub send would be a mailbox deposit, so ADR-229 prefers the radio |
| `hub.ack kind=HELD` / `DELIVERED` | what the hub actually did with a message it took |
