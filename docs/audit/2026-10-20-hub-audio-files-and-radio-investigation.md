# Hub audio, hub file size, and radio connection — investigation (2026-10-20)

Owner request (2026-10-20), four questions in one message:

1. Why audio through the hub does not work.
2. The hub should allow at least 50 MB files, adjustable by an admin on the dashboard.
3. The radio page in Settings pairs but does not stay connected and offers no way to test a transfer.
4. Get the HTCommander repository to see how they keep the radio connected and send audio through the
   phone to the radio.

**Status: INVESTIGATION ONLY. No product code was changed, no wire shape was touched, nothing was
built, and no device test was run.** Each finding below cites the code or documentation that proves it.

---

## F1 — Audio through the hub: not broken, never built

### What the hub actually carries

The hub relay is a JSON frame relay for **chat only**. A frame is decoded and routed only if:

- it is a `CHAT_FRAME` (1:1 text) or a `GROUP_FRAME` whose inner type is allowed
  (`GroupWireFrame.Message`, `Receipt`, …), plus typing-style metadata;
- its payload fits `HubContract.MAX_PAYLOAD_BYTES` (**64 KiB**), inside a frame of at most
  `MAX_FRAME_BYTES` (**256 KiB**).

Evidence:

- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubChatBridge.kt`
  — `canSignal` (metadata only), the "readable content not allowed via hub, not sending" refusal
  (`:298`), and the group-frame allow-list (`:426`).
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubContract.kt`
  — `MAX_PAYLOAD_BYTES = 64 * 1024`, `MAX_FRAME_BYTES = 256 * 1024`, `MAX_REST_BODY_BYTES = 64 * 1024`.
- `docs/protocol.md` "Hub relay wire v2" — frame kinds are Hello/Frame/Ack/Ping/Pong, no media frame.

### Why that means no calls and no voice notes

- There is **no call frame kind and no media frame kind** anywhere in the relay contract, and no
  calling code references the relay: a search for `hubRelay|HubRelayPort` across
  `core/messaging`, `core/network`, `core/calling` finds no call or audio use.
- Calls ride the direct WebSocket/TLS session. `docs/network/PRESENCE-CONNECTIONS-PLAN.md:45`
  states it plainly: "The online dot is the session set (`activeSessions`). **Sends and calls need a
  live session**." Its 2026-10-19 update (`:3-8`) adds that a hub-only colleague now shows as
  `Online` with the relay as transport, but "**Group headers and the call screens still count LAN
  sessions only**."
- Attachments have no hub path at all: `RealFlashChatRepository.sendAttachment` (`:3006`) only writes
  the local row; the bytes travel over the transfer engine, which needs a direct peer session. The
  radio branch refuses attachments (`RadioChatBridge.kt:164-167`), and the hub bridge is never
  consulted for a file.
- The server even documents a `SMALL_FILE` payload kind (`SealedEnvelope.kt:14`), but nothing in any
  client ever produces it — a dead constant, not a feature.

**Consequence:** two devices that can only reach each other through the hub can exchange text and
announcements, and nothing else. A call to a hub-only colleague cannot ring (no session to send the
call frame on), and a voice note or file attached in that chat has no route and ends as a failed
transfer.

### What "fixing audio through the hub" requires

Three different features are hiding behind that one sentence, and they cost very different amounts:

| Option | What it gives | Cost / risk |
|---|---|---|
| **A. TURN server (coturn) + call signalling over the hub** | real 1:1 and group calls between hub-only devices, using today's WebRTC stack | new hub frame kind for call signalling (wire change), TURN credentials/config, NAT cases, new device tests. The standard, correct answer for live calls. |
| **B. Hub file path (see F2)** | voice notes and files arrive through the hub; async audio | shares all of F2. Cheapest way to make "audio through the hub" true in the sense most users mean. |
| **C. Hub-relayed push-to-talk (Opus frames in relay frames)** | live low-rate walkie-talkie between hub-only devices | new media protocol on a relay not built for media; latency and fairness unproven; overlaps ADR-210/FO-28. Not recommended first. |

---

## F2 — "At least 50 MB, adjustable by an admin": there is no hub file path to size

The request cannot be satisfied by raising a constant, because no file ever crosses the hub today
(F1). What exists, and what it is:

| Limit | Value | Where | What it governs |
|---|---|---|---|
| One relay payload | 64 KiB | `HubContract.MAX_PAYLOAD_BYTES` | a single chat message body |
| One relay frame | 256 KiB | `HubContract.MAX_FRAME_BYTES` | envelope + payload |
| Mailbox per recipient | 50 MB | `MailboxModels.kt:22` (`perRecipientBytes`) | **stored** chat rows, not one file |
| Mailbox per sender | 100 MB | `MailboxModels.kt:24` | stored rows |
| Mailbox total | 5 GB | `MailboxModels.kt:26` | stored rows |
| Mailbox retention | 7 days | `MailboxModels.kt:19`, `DEFAULT_TTL_MS` | undelivered rows |
| Console settings | invite TTL, invite max uses **only** | `AdminApi.kt:752-756` | the only keys `PUT /admin/api/v1/settings` accepts |

So the "50 MB" in the server today is a *mailbox storage* cap; a single message is capped at 64 KiB
long before it.

### Two ways to build it

- **REST file transfer (recommended).** `POST /api/v1/files` (streaming, device bearer, sealed or
  server-stored per the operator's choice), `GET /api/v1/files/{id}` with Range for resume, a server
  quota and expiry, and a **console setting** `maxFileBytes` (default 50 MB) plus a per-device quota.
  Fits `docs/plans/HUB-QUALITY-CONTRACT.md`, reuses the existing auth and audit, and later serves
  FO-21 (announcement attachments) and FO-25 (department file drop).
- **Chunked through the existing relay.** 50 MB at 64 KiB is ~800 mailbox rows per file; no resume, no
  streaming, and it would blow through the 50 MB/recipient and 100 MB/sender caps immediately. Only
  attractive because it needs no new endpoint. **Not recommended.**

Either way it is a **new feature phase**: protocol section, ADR, server, client, console, tests, and
the device checks. It is not a settings toggle.

---

## F3 — The radio pairs, then does not stay connected: three concrete defects

Owner symptom: "after pairing … it doesn't stay connected, it just pairs and there is no way to test
transfer."

### F3.1 — Android opens the link with the *secure* RFCOMM socket; these radios accept only null auth

- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt:95`
  — `openRfcomm(address, uuid = SPP_UUID_TEXT, secure = true)`, and `:118` picks
  `createRfcommSocketToServiceRecord` when `secure`.
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/AndroidRadioDiscovery.kt:263`
  — `openLink` calls `openRfcomm(remembered.bluetoothAddress)`, i.e. the secure default.
- HTCommander's Android plugin documents the opposite requirement
  (`HTCommander/src/android/app/src/main/kotlin/com/meshcentral/htcommander/BluetoothClassicPlugin.kt:287-300`):
  "The radios accept the connection only with **null authentication** … On Android that maps to the
  *insecure* RFCOMM socket, so we try the insecure sockets first; **the secure variants force
  authentication the radio rejects**." The same comment records that the radio's SDP server channel is
  unstable: it can advertise channel 1 and then refuse it with an RFCOMM DM frame
  (`read failed … read ret: -1`), so they also probe fixed channels 1..12 and cache the last-good one
  (`:331-375`).

### F3.2 — The radio is *two* Bluetooth devices; Flash picks by name and can bond the audio endpoint

- HTCommander, `docs/Paring.md:3`: "The UV-Pro, GA-5WB, VR-N76, VR-N7500 radios actually show as **two
  Bluetooth devices**: it's both an audio device and a control device for the radio" — on Windows you
  must pair the **control** device, and the audio device normally pairs first.
- HTCommander identifies the control device by its advertised vendor service
  `39144315-32fa-40db-85ed-fbfeba2d86e6` (`BluetoothClassicPlugin.kt:50-60`, `:207-219`) and uses that
  same UUID to open the **audio** channel (`:221-222`).
- Flash decides what is a radio by **name only**:
  `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioCandidateRanker.kt`
  and `KnownRadioNames.kt`, fed by `AndroidRadioDiscovery.kt:60-77` (bonded list) and
  `WindowsRadioDiscovery.kt:40-97`. Both devices carry the same name, so the ranking cannot tell them
  apart, and nothing checks the SDP UUIDs.
- This is **proven for Windows** by the two-device behaviour above. On Android the two profiles may
  collapse into one `BluetoothDevice` (same MAC) or appear separately; either way the reliable test is
  the vendor UUID in the SDP cache, which is what HTCommander does — so the same filter fixes both
  hosts and stops non-radio bonded devices from being offered as radios.
- On Windows, `WindowsRadioDiscovery.pair` (`:100-125`) authenticates the chosen device and then calls
  `setServiceState(device, SPP_GUID, enable = true)`. Against the audio endpoint that cannot create an
  outgoing COM port, so `openLink` (`:127-169`) later throws `RADIO_NOT_FOUND` and the driver retries
  a link that can never exist. Flash also passes plain `0x01`
  (`WindowsBluetoothApi.kt:353-357`), whereas HTCommander's Windows app connects with
  `BluetoothEncryptionAllowNullAuthentication` — a lead to verify on the PC, not yet proven here.

### F3.3 — "Connected" means "the socket opened", so a refused channel looks like a link that drops

- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/KissTncDriver.kt:233-242`
  sets `TncLinkState.Connected` immediately after `openLink()` returns, **before any byte moves**, then
  runs `serve()`.
- `readLoop` (`:305-322`) treats a timeout as "keep waiting", so silence is *not* the cause. What drops
  the link is `n < 0` — the radio closing the RFCOMM channel right after accepting it, exactly the
  `read ret: -1` failure HTCommander describes.
- `RadioGatewayManager.handleDriverLinkState` (`:465-499`) then counts a retry; after
  `maxRetryAttempts = 5` (`:66`) it stops the driver and shows the "Reconnect" state. From the outside
  that reads as "pairs, connects for a second, then never stays connected".
- HTCommander instead treats the link as established only after the radio **answers** its init
  commands: 200 ms delay, batch of GAIA reads, retry every 2 s up to N (`radio.dart:1272-1426`), plus a
  60 s battery poll (`:1344-1350`) and auto-reconnect of remembered radios
  (`services/bluetooth_service.dart:114, 938`). Flash cannot copy that handshake literally, because in
  KISS TNC mode the radio does not answer a probe and Plan R deliberately forbids transmitting as a
  liveness probe (`RadioConnectStateMachine.kt:72-74`) — so the fix is to make the socket genuinely
  stay up (F3.1, F3.2), not to invent a probe.

### F3.4 — There *is* a test action, but it is unreachable while the link is down

`FlashRadioGatewayComponents.kt:913-975` builds the "Send a test message" card (UI-070) and
`FlashRadioGatewayScreen.kt:238` enables it only via
`FlashRadioMath.canSendTestMessage(status, isSending) = status == CONNECTED && !isSending`.
So when the link never settles, the only transfer test in the app is greyed out inside the collapsed
"Radio diagnostics" section — which is exactly the "there is no way to test" report. It also needs a
transmit warning acknowledgement and an operator callsign before it will key the radio.

### Fix list (small, independently testable)

1. Android link open: **insecure SDP first, then secure, then insecure fixed channels 1..12**, with a
   last-good-channel cache per address (mirror of HTCommander `connectSocket`).
2. Candidate detection: mark a device as a radio only when its SDP contains
   `39144315-32fa-40db-85ed-fbfeba2d86e6` (or the SPP UUID), and refuse to pair/mark the audio
   endpoint; on Windows, treat a failed SPP `setServiceState` as "this is the audio device — pair the
   other one".
3. Show the drop reason (`radio.link.down reason=eof` is already emitted) and keep "Reconnect" visible;
   make the diagnostics card explain *why* it is disabled (link down / no callsign).
4. Verify on the Windows PC whether the COM port needs the null-authentication flag when the SPP
   service is enabled.

---

## F4 — HTCommander reference implementation

Cloned read-only to `C:\Users\KaliOxygen\Downloads\HTCommander` (outside this repository) with
`git clone --depth 1 https://github.com/Ylianst/HTCommander.git`. Apache-2.0, Dart/Flutter plus native
Android/macOS plugins, last upstream commit 2026-10-20. `HTCommanderLegacy` (C#, 125 stars) is the
older Windows tool.

### How it keeps the radio connected

| Concern | Where | What it does |
|---|---|---|
| Socket strategy | `android/.../BluetoothClassicPlugin.kt:287-375` | insecure-first, SDP, then channels 1..12, last-good channel cached, 250 ms between attempts |
| Device identity | same `:50-60, :200-219` | control device recognised by vendor UUID `39144315-…`; audio endpoint ignored |
| Handshake / liveness | `radio.dart:1272-1426` | init commands 200 ms after connect, retry every 2 s up to N, then "radio may not be responding" |
| Keep-alive | `radio.dart:1344-1350` | battery poll every 60 s while connected |
| Reconnect | `services/bluetooth_service.dart:114, 938` | remembered radios auto-reconnect on next launch and after a drop |
| Power-cycle recovery | `radio_audio.dart` (`_audioPendingPowerOn`) | audio restarts when the radio reports it is powered on again |

### How it sends audio through the phone to the radio

- **Separate RFCOMM channel** on the vendor UUID `39144315-32fa-40db-85ed-fbfeba2d86e6`
  (`BluetoothClassicPlugin.kt:221-222` → `connectAudio`).
- Audio format **32 kHz, 16-bit, mono SBC**, matching the radio's own stream
  (`radio_audio.dart:36-42`); encode/decode and the transmit pacing loop run in a background isolate
  (`audio_engine.dart`, ~40 KB), PCM plays through the host's audio device (`pcm_player.dart`).
- Push-to-talk: the host captures the mic, the engine encodes and paces TX, and a `txHold` signal ends
  the burst (`radio_audio.dart:461-474`); received audio can be recorded to WAV
  (`docs/Bluetooth.md`).
- Over the internet it has a second, unrelated audio path: **AllStar / EchoLink (IAX2)** with PING/PONG
  keepalives, retransmission and auto-reconnect (`src/lib/allstar/iax2_call.dart`,
  `src/lib/echolink/`).

### What this means for Flash

- The connection fixes (F3) are directly applicable and cheap.
- **Radio audio is a real project, not a port**: vendor UUID + SBC codec + GAIA control + audio I/O +
  PTT plumbing. ADR-210 dropped Plan R Modes 3 and 5 (Bluetooth Hands-Free voice bridge) on
  2026-10-18 and FO-28 says "do not start unasked". HTCommander shows the shape a revived feature would
  take — but note it is **not** Hands-Free/SCO: it is a vendor SBC stream, which is why their audio
  works and our BT-R10 Hands-Free spike did not.
- **Audio cannot go over the radio link itself** in Flash's current design: KISS AX.25 on a 144.625 MHz
  handheld at 1200/9600 baud is a data channel. A 3-second voice clip is orders of magnitude beyond it.
  Radio *voice* means the Bluetooth audio channel (HTCommander-style) or an IP link (AllStar/EchoLink),
  not AX.25 packets.
- Licence: Apache-2.0 permits reuse with attribution. The safer route is to reimplement the behaviour
  from their documented findings and add the attribution only if code is copied; either way a `NOTICE`
  entry and an ADR are required before any of it lands.

---

## Recommendation and order

One at a time, smallest first:

1. **Radio connection (F3.1–F3.3).** Small, unit-testable, unblocks all the owed radio device tests
   (`RADWIRE-*`, `RADLNK-*`, `RADCHAT-*`). No wire change and no new feature.
2. **Hub file transfer (F2, option "REST").** One planned phase: protocol + ADR + server + client +
   console setting `maxFileBytes` (default 50 MB) + quota. Voice notes then work hub-only, which also
   settles the most common reading of "audio through the hub".
3. **Hub call audio (F1, option A: TURN + hub signalling).** Needs an ADR and an owner decision on
   operating a TURN server; it is the only way live calls reach hub-only devices.
4. **Radio audio (F4).** Only after 1–3, and only with an explicit owner decision reversing the
   ADR-210/FO-28 position; start from a design doc, not from code.

## Open questions for the owner

- For F2: should files through the hub be **sealed end to end** (the hub stores ciphertext it cannot
  read, like 1:1 chat) or server-stored for the operator (searchable/scanable, like announcements)?
  The choice changes the protocol and the console.
- For F3: was the radio paired on **Windows** or on a **phone** when it "paired but did not stay
  connected"? The two hosts fail for different reasons above, and the fix order differs.
- For F4: is the goal (a) talking to ordinary walkie-talkies from Flash, or (b) using the handheld as
  Flash's radio modem only? (a) needs the vendor audio channel; (b) needs only F3.

## Not done in this investigation

No code changed, no Gradle build, no device test, no `adb`, no transmission. `docs/testing/TEST-BACKLOG.md`
is unchanged because the findings produce design decisions, not owed device checks yet.
