# Radio voice: hearing and talking through a Bluetooth handheld

Status: **built and unit-tested 2026-10-23, NOT device-verified** (ADR-225). Android and Windows desktop. On Windows the connection to the audio channel was tried once on the owner's PC (receive only): see "Desktop (Windows)" below.

## What this is

The VR-N76 / BTECH UV-Pro class handhelds expose **two** Bluetooth services to a phone:

| Service | UUID | Carries | Flash code |
|---|---|---|---|
| Serial Port Profile | `00001101-0000-1000-8000-00805F9B34FB` | control (GAIA) and, in KISS mode, KISS data | `KissTncDriver`, `AndroidBluetoothCatalog.openRadioLink` |
| Vendor audio ("BS AOC", "Generic Audio") | `39144315-32fa-40db-85ed-fbfeba2d86e6` | the radio audio as framed SBC | `RadioAudioSession`, `AndroidBluetoothCatalog.openRadioAudioLink` |

Source of the audio facts: HTCommander (Apache-2.0, `src/lib/radio/audio_engine.dart`, `radio_audio.dart`, `docs/Voice.md`), read
on 2026-10-23. Everything below marked *HTCommander* is that project's behaviour, not something we measured. **Nothing here has run
against a radio yet**; the owed checks are `RADVOICE-01..` in `docs/testing/TEST-BACKLOG.md`.

## Wire format (audio channel)

```text
frame   = 0x7E  cmd  escaped(payload)  0x7E          escape: 0x7D, then byte ^ 0x20 (for 0x7E and 0x7D)
cmd 0x00 / 0x03   received audio; payload = one or more concatenated SBC frames (each starts 0x9C)
cmd 0x01          end of a transmission (either direction)
cmd 0x02          ack (ignored)
cmd 0x09          echo of our own transmission (ignored; HTCommander only meters it)
```

SBC shape: 32 kHz, mono, 16 blocks, 8 subbands, loudness allocation, **bitpool 18** (the radio default; HTCommander transmits at 40,
124 was rejected on the air). One frame = 44 bytes at bitpool 18 and carries 128 samples (4 ms); header bytes `9C 71 12`.

**Transmit:** pack SBC frames into one `cmd 0x00` packet until the payload reaches about 300 bytes; when the user lets go send the
END frame `7E 01 00 01 00 00 00 00 00 00 7E`. Sending audio frames **keys the radio by itself**: no VOX and no Hands-Free profile are
involved (this is the fact that reverses ADR-210's reasoning).

## Code map

`core/radio/.../audio/`: `sbc/Sbc.kt`, `sbc/SbcTables.kt` (port of HTCommander's Dart SBC, Apache-2.0; see `NOTICE`),
`RadioAudioFraming.kt` (frame encoder and streaming decoder), `RadioAudioCodec.kt` (`RadioAudioReceiver`, `RadioAudioTransmitter`,
`LinearResampler`), `RadioAudioSession.kt` (public: link + speaker + microphone + state). Android: `AndroidRadioAudio.kt`
(`AudioTrack` 32 kHz, non-blocking, 800 ms cap, 60 ms pre-roll; `AudioRecord` source MIC, rates 32/16/44.1/48 kHz resampled to 32 kHz).
App: `radio/AndroidRadioVoice.kt`; UI: `FlashRadioVoiceCard`, `RadioGatewayController.voice / setVoiceListening / voicePushToTalk`.

## Behaviour rules

- A received run starts with the first audio frame and ends on END or after 700 ms without audio; the SBC decoder is reset per run.
- **Hold-to-talk is refused while the radio is receiving** (no doubling), and closing the session while transmitting sends the END frame first (1 s limit).
- The audio link is **foreground only** and closes when the radio screen is left or the gateway is switched off.
- The microphone source is plain `MIC`, not `VOICE_COMMUNICATION` (silent-mic history on Transsion handsets).
- The audio channel is found by SDP only. Fixed-channel probing is off for it because channel 1 is the serial channel; the channel cache
  is keyed by address **and** service, so the two services of one radio can no longer poison each other.
- Voice leaves the radio as ordinary analog audio, not encrypted. The card says so and reminds the operator to say their callsign. Flash
  does not key the radio from chat or Flash push-to-talk: only this card does.

## Unknowns that only hardware answers

1. Does the audio RFCOMM channel open while the KISS serial link is up, and the other way round (HTCommander uses GAIA, not KISS)?
   **Partly answered on the owner's hardware:** on Windows the serial channel was up (`radio.link.up link=COM15_@115200`) while the
   voice card ran (2026-10-23/28), and on the phone both have been used on the same install. Not measured: opening them at the same
   instant on the phone, and voice while a KISS frame is on the air (half-duplex). `docs/network/RADIO-BLUETOOTH-CHANNELS.md` §1.
2. Does `createInsecureRfcommSocketToServiceRecord` find the vendor service, or is a fixed channel needed (HTCommander falls back to probing)?
3. Does the radio stay keyed correctly with 300-byte packets from a live microphone (HTCommander sends ahead of real time)?
4. Is bitpool 18 intelligible enough for speech on the air, or should the default move to 40?
5. Which microphone rate does each handset give?

## Desktop (Windows)

The vendor audio service has no virtual COM port (only Serial Port Profile does), so the desktop opens it as a Bluetooth RFCOMM client socket:
Winsock `socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)` + `connect` with a `SOCKADDR_BTH` (30 bytes, packed: family, address, service class GUID,
port 0 = look the channel up by SDP). Source: learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-socket and
.../win32/api/ws2bth/ns-ws2bth-sockaddr_bth (read 2026-10-23). Code: `WindowsRfcomm` / `WinsockRfcommLink` (`:core:radio` jvmMain),
`WindowsRadioDiscovery.openAudioLink`, `JvmRadioAudio.kt` (Java Sound, 32 kHz or 48 kHz upsampled), `DesktopRadioVoice` (desktop app).

**Measured on the owner's PC, 2026-10-23, receive only (nothing sent, radio not keyed):**
- Control entry `38:D2:00:01:34:FD` (the one with the serial port): the connection opened in **2 s**; 4 s of listening gave 0 bytes (quiet channel).
- Audio entry `38:D2:00:01:35:B5`: refused after 5 s. So `openAudioLink` tries the entry that owns the serial port first, then same-name twins.
- Open questions this does not answer: is the audio actually decoded and heard, does talking key the radio, do the KISS link and the audio link coexist (RADVOICE-04 / 12).
