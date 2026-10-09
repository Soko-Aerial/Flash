# Bluetooth / serial-port radio link - overnight build report (2026-10-09)

Stream: "BLUETOOTH / SERIAL-PORT RADIO LINK" (one of three parallel agents; the owner was away, so every decision below was taken
and recorded by the agent). Nothing was committed or pushed. The shared logs (`logs/*`, `docs/decisions.md`,
`docs/testing/TEST-BACKLOG.md`, `docs/protocol.md`, `AGENTS.md`) were NOT edited; their paste-ready text is in sections 9 to 14.

**Honest summary.** Everything that does not need the radio is built and unit-tested on the JVM and the Android host. **No
radio, no phone and no Bluetooth link was used. Nothing here is hardware-verified.** The only thing the machine did
reveal is that this Windows laptop currently reports four serial ports (see 8, step 1); they were listed, never opened.

## 1. What was built

| Layer | What | Where (under `core/network/src/`) |
|---|---|---|
| KISS | Streaming encoder/decoder, parameter commands, stats; tolerant of any fragmentation, garbage, bad escapes, oversize | `commonMain/.../kiss/KissFrameCodec.kt` |
| AX.25 | v2.2 UI frame encode/decode, address shifting, SSID byte, digipeaters, H bit; **PID `F0` only (`CC`/`CD` refused)** | `commonMain/.../kiss/Ax25FrameCodec.kt` |
| Flash radio frame (Profile M) | 12-byte header, AES-256-GCM, counter nonce, HKDF per-direction keys, 64-wide replay window (persisted via a store), TTL clamp, rotating 4-byte tag + station label (15-min epoch, +-1), per-segment AEAD segmentation (max 16), signed clear-text mode (Profile A only, raw 64-byte r\|\|s) | `commonMain/.../radio/RadioWire.kt`, `RadioSession.kt`, `RadioState.kt`, `RadioCrypto.kt` |
| Crypto seam | `RadioCrypto` / `RadioSignatureScheme` interfaces; `JdkRadioCrypto` in `jvmMain` and `androidMain` | `radio/RadioCrypto.kt`, `jvmMain/.../JdkRadioCrypto.kt`, `androidMain/.../JdkRadioCrypto.kt` |
| Link seam | `ByteLink`, `SerialPortCatalog`, `PortInfo`, `SerialSettings`, `LinkException` | `commonMain/.../radio/ByteLink.kt` |
| Driver | `KissTncDriver`: connect, optional TXDELAY/P/SlotTime/TXtail/FullDuplex, bounded TX queue, pacing (`TxPacingPolicy`: airtime estimate + min gap + jitter), reconnect with capped backoff, `radio.link.up/down`, `radio.tnc.params` PROBE lines, hex evidence log | `radio/KissTncDriver.kt`, `TxPacing.kt`, `RadioEvidence.kt`, `RadioLock.kt` (+ jvm/android actuals) |
| Simulation | `InMemoryLink`, `FakeTnc`, `FakeAirChannel` (loss, airtime, echo) | `commonMain/.../radio/sim/RadioSimulation.kt` |
| Desktop link | `JvmSerialPortCatalog` on jSerialComm 2.11.4 | `jvmMain/.../radio/JvmSerialPortCatalog.kt` |
| Android link | `AndroidBluetoothCatalog` + `RfcommLink` over `BluetoothSocket` (SPP UUID, Flash UUID, paired devices only, Android 12+ permission handling), `BluetoothPermissions` (pure, tested) | `androidMain/.../radio/AndroidBluetoothCatalog.kt`, `commonMain/.../radio/BluetoothPermissions.kt` |
| Tester logic | `RadioLinkTester`: KISS transparency frame, AX.25 text frame, full 220-byte Flash frame, timed burst with RTT/goodput, responder mode, feed | `commonMain/.../radio/diag/RadioLinkTester.kt` |
| Tester harness + CLI | `RadioLinkTestHarness` (port or simulated radio, log export with header), `RadioLinkTestCli` | `jvmMain/.../radio/diag/` |
| Tester window | "Experimental: Radio link test" (Flash theme tokens) | `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/RadioLinkTestMain.kt` |
| Gradle | tasks `:desktop:radioLinkTest`, `:desktop:radioLinkTestCli` | `desktop/build.gradle.kts` (appended) |
| Flash-to-Flash scaffolding | `StreamFramer` / `StreamFrameDecoder` (u16 length framing for RFCOMM) | `commonMain/.../radio/StreamFramer.kt` |
| Manifest | `BLUETOOTH` (maxSdk 30), `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (neverForLocation), optional bluetooth feature | `app/src/main/AndroidManifest.xml` |
| Dependency | `jserialcomm` 2.11.4 in the version catalog; `implementation` in `core:network` jvmMain | `gradle/libs.versions.toml`, `core/network/build.gradle.kts` |
| Docs | `docs/network/RADIO-WIRE-FORMAT.md` (exact bytes + golden vector), `docs/ui/radio-link-test.md`, status section in the plan, 3 probe rows in `docs/testing/PROBES.md`, this report | |

Tests (new): `KissFrameCodecTest`, `Ax25FrameCodecTest`, `RadioStateTest`, `KissTncDriverTest`, `BluetoothPermissionsTest`,
`StreamFramerTest` (common); `RadioSessionTest`, `RadioCryptoVectorsTest` (RFC 5869, RFC 4231, NIST GCM case 16),
`RadioGoldenFrameTest`, `RadioLinkTesterTest`, `RadioLinkTestHarnessTest` (jvm).

## 2. Verification (commands and honest results)

Environment: `export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2"; export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix'; cd /c/Users/KaliOxygen/Downloads/Flash`

| Command | Result 2026-10-09 |
|---|---|
| `./gradlew :core:network:jvmTest :core:network:testAndroidHostTest :desktop:compileKotlinJvm :app:compileDebugKotlin` | BUILD SUCCESSFUL after two retries: the first attempts failed because another agent's build held a jar lock and was rewriting `core/calling` (not my files). Final: `:core:network:jvmTest` 424 tests, `:core:network:testAndroidHostTest` 473 tests, 0 failures; `:desktop:compileKotlinJvm` and `:app:compileDebugKotlin` BUILD SUCCESSFUL |
| `./gradlew :desktop:radioLinkTestCli -PradioArgs="--list"` | listed 4 ports on this laptop (see 8); nothing opened |
| `./gradlew :desktop:radioLinkTestCli -PradioArgs="--simulated --kiss --ax25 --flash --burst 3 --body 100 --ack-wait 10"` | ran end to end against the fake radio: 3 of 3 acked, RTT 17/30/48 ms (simulation, meaningless as a radio number), log file written |
| `./gradlew :desktop:radioLinkTest` (60 s in background) | process stayed up with no exception. I could not look at the window, so **the layout is unseen**. |

Mutation checks (each break was applied, the radio/kiss tests run, the file restored and diffed identical):

| Mutation | Result |
|---|---|
| replay window never reports DUPLICATE | 6 tests failed (RadioSessionTest x3, RadioStateTest x3) |
| TTL byte added to the AEAD AAD | 3 failed (`ttlIsEditableByRelaysButClamped`, `ttlIsNotPartOfTheAuthenticatedBytes`, golden frame) |
| FEND escaped as `DB DD` instead of `DB DC` | 8 failed (KissFrameCodecTest x7, Ax25FrameCodecTest) |

A bug found by the tests in my own new code: the TX queue was not actually bounded (ERROR-130, fixed, section 13).

## 3. Decisions (each with alternatives)

1. **`RadioCrypto` seam instead of reusing `core:security` or FSEC.** The core:security primitives are `internal`; FSEC uses a random
   nonce and a 22-byte header, which costs about 10 more bytes of a 220-byte budget than the 12-byte header with a counter nonce.
   Alternatives: reuse FSEC unchanged (rejected: budget), make core:security primitives public (rejected: shared module owned by other work).
   Consequence: an adapter from the real pairing key to `RadioSession.addPeer(peerId, key32)` is still to write.
2. **Per-segment AEAD instead of seal-then-split.** A forged segment is rejected on arrival and cannot poison a reassembly.
   Cost: 16 + 4 bytes per segment (188 B body per segment instead of 204).
3. **4-byte rotating tag, 15-minute epoch, +-1 skew.** Plan corrections E2/E7. A tag is a lookup hint, not security.
4. **TTL unauthenticated, clamped by the receiver** (relays must decrement without a key).
5. **Counter reservation (64 ahead) plus a time floor** (seconds since 2026-01-01): nonce reuse needs both the store and the clock to fail.
6. **KISS parameters are sent only if configured.** Whether the radio's menu or the KISS command wins is unverified (BT-00 item).
7. **No compression** (to be measured on real messages).
8. **Signed broadcast implemented for Profile A only, never segmented, off unless enabled.**
9. **Hex dumps only in the evidence log; PROBE lines carry no content.** The test key is public so test frames in the log are not secret.
10. **`JdkRadioCrypto` duplicated in `androidMain`** (the `jvmMain` file is not visible to Android; moving it to a shared `jvmAndAndroid` source set was rejected as the NET-1 refactor ADR-058 rejects).
11. **jSerialComm 2.11.4** for desktop serial. Licence Apache-2.0 OR LGPL-3.0 (dual); we use it under Apache-2.0. Alternatives: JSSC (older, less maintained), purejavacomm (JNA, unmaintained), raw `java.io` on `COMn` (no baud control). It loads a native library at runtime; the Gradle tasks pass `--enable-native-access=ALL-UNNAMED`.
12. **Android: paired devices only, no discovery.** Fewer permissions and no location. `BLUETOOTH_SCAN` is declared (neverForLocation) only to allow `cancelDiscovery()`; the app never scans. Alternative: Companion Device Manager (more streamlined, but a bigger UI change) kept as a later option.
13. **No change to `FlashTransportType` or the connection planner.** The enum is used in many files, several dirty from other streams, and an added value risks exhaustive-`when` breaks. The seam is documented in ADR-101 and `StreamFramer`.
14. **Tester window is a separate Gradle-launched `main`, not in app navigation**, so it cannot ship to users by accident.
15. **Test frames use a public key** (`RadioLinkTester.TEST_PASSPHRASE`), so two bench machines need no pairing. This proves the frame format, not secrecy.

## 4. Dependencies and licences

| Dependency | Version | Licence | Why | Alternatives rejected |
|---|---|---|---|---|
| `com.fazecast:jSerialComm` | 2.11.4 | Apache-2.0 OR LGPL-3.0 | desktop serial/Bluetooth COM ports, jvmMain of `:core:network` only | JSSC, purejavacomm, raw `java.io` |
| Android `android.bluetooth.*` | platform | n/a | RFCOMM sockets | none (no library needed) |

No other dependency was added. The aboutlibraries/third-party notices for the desktop installer will pick jSerialComm up from the dependency graph; the owner should confirm it appears in the generated notices (not checked).

## 5. Android platform facts (checked 2026-10-09; for `docs/android-platform-notes.md`)

- Android 12 (API 31)+: `BLUETOOTH_CONNECT` is a runtime permission needed to list bonded devices and connect; `BLUETOOTH_SCAN` is needed for discovery and `cancelDiscovery()`; both are in the "Nearby devices" group. Android 11 and lower: install-time `BLUETOOTH`/`BLUETOOTH_ADMIN`. Source: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions (official, verified via search excerpt).
- `BluetoothDevice.createRfcommSocketToServiceRecord(UUID)`: performs an SDP lookup; the link is authenticated and encrypted by the OS; use the SPP UUID `00001101-0000-1000-8000-00805F9B34FB` for serial boards, and a generated unique UUID between two Android apps. `connect()` blocks. Source: https://developer.android.com/reference/android/bluetooth/BluetoothDevice and https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices (official).
- `BluetoothAdapter.cancelDiscovery()`: discovery is heavyweight and connecting during it is slow; on API 31+ it requires `BLUETOOTH_SCAN`. Source: BluetoothAdapter reference (official).
- Project implication: Flash's Bluetooth link is Classic RFCOMM to paired devices; OS pairing is NOT Flash trust (ADR-101).
- NOT verified: that the VR-N76 exposes an SDP SPP record that `createRfcommSocketToServiceRecord` finds, or that it is Classic and not BLE-only (this is BT-00 item 1).

## 6. Hardware-dependent / UNVERIFIED items

Everything about a real radio: Classic SPP vs BLE, whether the COM port carries KISS at all in the radio's current menu mode, C0/DB transparency, parameter commands, maximum accepted frame length, TXDELAY meaning, airtime and goodput, RTT, 9600 baud, one-app-at-a-time, reconnect after radio power-cycle, LCD behaviour, Android SDP lookup, Android permission flow. Also unverified: the window layout (never seen), `JvmSerialPortCatalog.open` against a real port, and `AndroidBluetoothCatalog` (compiled only).

## 7. Not done

- No adapter from the real pairing key (`core:security`) to `RadioSession`.
- No `FlashTransportType.BLUETOOTH`, no planner hook, no RFCOMM session for Flash-to-Flash (only `StreamFramer`, the UUID constants and ADR-101).
- No Android UI or debug entry point to exercise `AndroidBluetoothCatalog` (so BT-03/BT-04 on a phone need that first).
- No chat/messaging integration, relay/gateway logic, retransmission, compression, voice, 9600 baud.
- No exposure of KISS parameters in the window (the CLI/harness accept a `KissTncConfig`, the screen uses defaults = nothing sent).
- Nobody has read the wire format doc except the author.

## 8. Tomorrow's test checklist (BT-00, about 45 minutes)

Have: the radio, the Windows laptop, a second device or radio that can monitor 144/430 MHz packet (a second radio with an APRS app,
or the phone with a TNC app) if you want over-the-air checks, and a stopwatch. Use a legal frequency and a low-power setting; follow your licence.

1. **Pair and find the port.** Pair the radio in Windows settings. Open Settings > Bluetooth > More Bluetooth settings > COM Ports and write down the *Outgoing* COM port for the radio. Then run (from the Flash folder, with the environment of section 2):
   `./gradlew :desktop:radioLinkTestCli -PradioArgs="--list"`
   Today this laptop already lists: `COM15` and `COM7` ("Bluetooth Peripheral Device"), `COM16` ("SPP Dev"), `COM6` ("JL_SPP"); I did not open them and do not know which, if any, is the radio. Look for: your radio's port in the list. Send back: the list.
2. **Window.** `./gradlew :desktop:radioLinkTest`. Click "Simulated radio", then KISS test, AX.25 test, Flash 220-byte and a burst of 3. Look for: the window opens and the simulated burst shows `acked=3`. Send back: a screenshot (this is the first time anyone sees the layout).
3. **Open the real port.** Set Port to the radio's COM port, Baud 115200 (the baud is probably irrelevant on a Bluetooth virtual port; if nothing works try 9600 and 57600), Pacing RADIO, Connect. Look for: status "connected". If "Failed: cannot open", close every other app that might own the port (the radio's own app, APRS software) and retry; note the error text.
4. **KISS on the radio.** In the radio's menu put the data/TNC mode into KISS (the exact menu name is part of what we want to learn: write it down). With "Digital Mode" try both on and off.
5. **KISS test frame** (bytes 0 to 219, contains C0 and DB). Look for: the TX LED / transmit indicator on the radio, and on the monitor receiver an AX.25 UI frame from `BT00A` to `FLTEST` with 220 information bytes. Send back: what the monitor shows (hex if available) and whether the radio LCD changed.
6. **AX.25 UI test frame.** Look for: a readable text `FLASH BT-00 AX25 UI TEST #n` on the monitor. Note whether the LCD shows it (this answers the plan's LCD question; do not assume).
7. **Flash 220-byte payload.** Look for: the monitor sees 220 information bytes starting `F1 11 ...` and PID `F0`. If the radio or monitor refuses a long frame, note the length at which it fails (retry by editing nothing: just report).
8. **Two stations (RF round trip).** On the second PC run `--role b --listen 300` against the second radio (CLI: `./gradlew :desktop:radioLinkTestCli -PradioArgs="--port COMx --role b --listen 300"`); on the first run a burst of 5 frames with 192 bytes (`--burst 5 --body 192` or the window). Look for: `acked=5`, the RTT and goodput in the result line. Send back: the result line and the two exported log files. (Expect roughly 3 to 6 s per frame at 1200 baud with the default pacing; this is a guess, the measurement is the point.)
9. **Two apps at once.** Leave the tool connected and open the radio's own app or an APRS app on the same radio. Look for: whether either side loses the link. Write down which app wins.
10. **Power cycle.** With the tool connected, turn the radio off and on. Look for: the status line shows "waiting ... to retry" and returns to "connected" without restarting the tool. Send back: the log.
11. **9600 baud (optional).** If the radio has a 9600 baud UHF data mode, enable it and repeat step 8 with `TxPacingPolicy` unchanged; note that the estimate used for pacing assumes 1200 and will be too slow.
12. **Export and send back.** Click "Export log" (file `flash-radio-test-<time>.txt` in the Flash folder) on each station. Send: both logs, the radio model + firmware version, Windows version, and which menu settings you used. Do not send anything else; the logs contain only test traffic with a public test key.

If anything crashes: copy the console output. If the Bluetooth link works but KISS bytes are not accepted, step 5 still tells us whether the port carries any data.

## 9. Paste-ready: `logs/progress.md` entry

```markdown
## 2026-10-09 - Bluetooth/serial radio link groundwork (BT-0, BT-1 codecs, drivers, spike tool)

### Worked on
Everything for the radio link that does not need the radio (stream "Bluetooth / serial-port radio link", owner away).

### Changed
- `:core:network`: KISS streaming codec, AX.25 UI codec (PID F0, CC/CD refused), Flash radio frame Profile M (`RadioSession`: AES-256-GCM, counter nonce, 64 replay window, rotating tag/label, segmentation, TTL clamp, signed Profile A mode), `ByteLink` seam, `KissTncDriver` (pacing, reconnect, probes), sim (`InMemoryLink`, `FakeTnc`, `FakeAirChannel`), `JvmSerialPortCatalog` (jSerialComm 2.11.4), `AndroidBluetoothCatalog` (RFCOMM), `StreamFramer`.
- Spike tool for BT-00: `:desktop:radioLinkTest` window and `:desktop:radioLinkTestCli`.
- Manifest: BLUETOOTH (<=30), BLUETOOTH_CONNECT, BLUETOOTH_SCAN (neverForLocation).
- Docs: `docs/network/RADIO-WIRE-FORMAT.md`, `docs/ui/radio-link-test.md`, plan status section, 3 rows in `docs/testing/PROBES.md`, report `docs/reports/2026-10-09-bluetooth-radio.md`.

### Verification
JVM and Android host unit tests green (jvmTest 424, Android host 473, 0 failures); mutation checks on replay window, AAD and KISS escaping killed; CLI run against the built-in simulated radio end to end; `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin` BUILD SUCCESSFUL. NOT verified on any radio, phone or Bluetooth link.

### Problems
ERROR-130: the TX queue bound was not enforced (found by a test, fixed).

### Remaining
BT-00 on the radio, then adapter from the pairing key, Flash-to-Flash RFCOMM session (ADR-101), Android debug entry, chat integration.

### Next AI
Do not wire into the engine until BT-00 results exist; read the report's section 8 results first.
```

## 10. Paste-ready: `logs/handoff.md` entry

```markdown
## Radio link (2026-10-09, not committed)
- Built and unit-tested, not hardware-verified: KISS/AX.25/Flash radio frame codecs, `RadioSession`, `KissTncDriver`, desktop serial + Android RFCOMM links, BT-00 spike tool (`./gradlew :desktop:radioLinkTest` / `:radioLinkTestCli`).
- Exact wire bytes: `docs/network/RADIO-WIRE-FORMAT.md` (golden vector asserted by `RadioGoldenFrameTest`).
- Files: `core/network/src/*/.../kiss`, `.../radio`, `.../radio/diag`, `desktop/.../RadioLinkTestMain.kt`, `desktop/build.gradle.kts` (tasks appended), `app/src/main/AndroidManifest.xml` (Bluetooth permissions), `gradle/libs.versions.toml` (jserialcomm).
- Owner next step: run the checklist in `docs/reports/2026-10-09-bluetooth-radio.md` section 8 and send back the exported logs; then record EXP-023 and the BT-* results.
- Not built: pairing-key adapter, `FlashTransportType.BLUETOOTH`/planner hook, Android debug entry, chat integration.
```

## 11. Paste-ready: `docs/decisions.md` ADR-101

```markdown
## ADR-101 - Bluetooth/radio link: layers, seam and session-layer recommendation (2026-10-09) - PROPOSED

### Decision
1. The radio path is `ByteLink` (serial COM port or RFCOMM) -> `KissTncDriver` -> AX.25 UI (PID F0) -> Flash radio frame (Profile M). It is a separate stack in `:core:network` (`...network.kiss`, `...network.radio`) that does not touch `PeerTransport`-style engine code (no such type exists) or `FlashTransportType`.
2. The radio frame has its own compact AEAD (12-byte header, counter nonce, per-direction HKDF keys from the pairing session key, 64-wide replay window, per-segment AEAD), not FSEC, because FSEC's random nonce and 22-byte header leave too little of a 220-byte information field.
3. For a future Flash-to-Flash session over Bluetooth RFCOMM (phone to phone, no radio): reuse the pairing-derived FSEC-style AEAD over a minimal length-prefixed framer (`StreamFramer`, u16 length), authenticated by the pinned Flash identity. Bluetooth OS pairing alone is NOT Flash trust: it is a link-layer convenience and an unknown peer must still pass Flash's own pairing/TOFU rules.
4. Android: paired devices only (no discovery), permissions BLUETOOTH_CONNECT (+ BLUETOOTH_SCAN neverForLocation for cancelDiscovery), SPP UUID for serial radios, a Flash-specific UUID (`5f1a5c3e-9b24-4d7e-8a61-0c2f4e7b9a13`) for Flash-to-Flash.
5. The test tool uses a public test key and is launched from Gradle only.

### Context
Owner reports the VR-N76 / UV-Pro class radio carries bytes over a Bluetooth virtual COM port. The plan `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md` had known errors (E1, E2, E3, E7, E9, E11) now corrected in `docs/network/RADIO-WIRE-FORMAT.md`.

### Alternatives considered
- Reuse FSEC for the radio frame (rejected: budget). Reuse `core:security` internals (rejected: internal visibility, shared module).
- TLS over RFCOMM for Flash-to-Flash (rejected for now: handshake cost and two security layers; the existing AEAD session design already authenticates by identity).
- A raw stream with no framing (rejected: RFCOMM has no message boundaries).
- Add `FlashTransportType.BLUETOOTH` immediately (deferred: touches shared files; do it with the planner hook once BT-03 has a measured need).
- Discovery-based pairing in-app / Companion Device Manager (deferred).

### Consequences
Radio frames and LAN sessions are separate trust paths until an adapter derives radio keys from the real pairing key. All radio numbers (airtime, goodput, RTT) are estimates until EXP-023.

### Revisit when
BT-00 shows the radio does not behave as assumed (BLE-only, no KISS, frame limit < 236 B); or when Flash-to-Flash Bluetooth is scheduled.
```

## 12. Paste-ready: `docs/testing/TEST-BACKLOG.md` entries

```markdown
### BT-00 - Radio hardware spike (UPDATED 2026-10-09: tooling now exists)
- **Setup:** radio paired in Windows (Outgoing COM port noted), the Flash checkout, optionally a second radio/PC and a monitor receiver. Commands: `./gradlew :desktop:radioLinkTest`, `./gradlew :desktop:radioLinkTestCli -PradioArgs="--list"` (full list in `docs/reports/2026-10-09-bluetooth-radio.md` section 8).
- **Steps:** report section 8 steps 1 to 12 (list ports, simulated run, open the real port, KISS on, KISS/AX.25/Flash frames, two-station burst, two apps, power cycle, optional 9600, export logs).
- **Pass:** the exported logs plus the answers to the plan's open questions (Classic SPP vs BLE, KISS accepted, LCD behaviour with Digital Mode on/off, 220-byte frame accepted, goodput) written to `logs/experiments.md` as EXP-023; each UNVERIFIED marker in the radio plan (sections 0.2, 4.1, 4.5, 5.2) updated to measured fact or struck.
- **Source:** radio plan sections 0.2 and 8; ADR-101. **Status:** TODO

### BT-09 - Spike window opens and the simulated radio runs (no hardware)
- **Setup:** the dev laptop. **Steps:** `./gradlew :desktop:radioLinkTest`; click Simulated radio, the three test buttons, a burst of 3. **Pass:** window readable (no clipped controls at 1100x820 and at a smaller window), feed and raw panes fill, the burst result shows `acked=3`, Export log writes a file that starts with `# Flash radio link test`. **Source:** `docs/ui/radio-link-test.md`. **Status:** TODO

### BT-10 - KISS transparency through the real TNC
- **Setup:** BT-00 steps 3 and 4. **Steps:** send the KISS test frame (bytes 00..DB..C0..DB, 220 B). **Pass:** a monitor receiver decodes an AX.25 UI frame from BT00A with exactly the 220 information bytes in order (C0 and DB intact). **Source:** `RADIO-WIRE-FORMAT.md` section 3. **Status:** TODO

### BT-11 - A full 220-byte Flash frame is accepted and heard
- **Steps:** send the Flash 220-byte payload. **Pass:** the monitor shows a 236-byte AX.25 frame, PID F0, information starting `F1 11`; the second station's feed shows `flash-rx kind=PING body=192B` when both use the test key. Record the largest frame the radio accepts if 236 fails. **Source:** plan 6.0 item 1. **Status:** TODO

### BT-12 - Burst goodput and round trip at 1200 baud (feeds EXP-023)
- **Setup:** two stations (role A burst, role B `--listen 300`). **Steps:** burst 5 x 192 B, then 5 x 50 B. **Pass:** `acked` equals sent (or the loss is explained), and RTT min/median/max and goodput B/s are recorded in EXP-023 next to the pacing defaults; the defaults (300 ms TXDELAY, 500 ms gap, 1500 ms jitter) are kept or changed with the number as the reason. **Status:** TODO

### BT-13 - Link loss and reconnect
- **Steps:** BT-00 step 10 (radio power cycle) and a second variant: walk out of Bluetooth range and back. **Pass:** the status shows waiting then connected without restarting the tool, sends during the outage return LinkDown (feed), log has `radio.link.down reason=...` then `radio.link.up`. **Status:** TODO

### BT-14 - One app at a time
- **Steps:** BT-00 step 9. **Pass:** which app keeps the radio is written down; whether the radio's own app can coexist is recorded. **Status:** TODO

### BT-15 - Station label rotation (privacy)
- **Steps:** send the Flash 220-byte payload, wait 16 minutes, send again, compare the AX.25 source callsign on the monitor. **Pass:** the callsign differs between the two frames and is never a real callsign; (and with the other station: both frames decode). **Source:** plan E7. **Status:** TODO

### BT-16 - Android RFCOMM to the radio (BLOCKED: needs a debug entry)
- **Why blocked:** `AndroidBluetoothCatalog` exists but nothing in the app calls it. Build a debug screen first. **Pass when built:** on Android 12+ the permission prompt appears once ("Nearby devices"), the radio is listed as a paired device, `openRfcomm` connects with the SPP UUID and bytes flow; on Android 11 or lower there is no runtime prompt. **Status:** TODO

### BT-17 - Flash-to-Flash over RFCOMM (BLOCKED: not built)
- **Why blocked:** only `StreamFramer`, the UUID constants and ADR-101 exist. Replaces plan test BT-03 once built. **Status:** TODO
```

## 13. Errors found

```markdown
## ERROR-130 - KissTncDriver TX queue was not bounded (found in unit test, own new code)

### Date
2026-10-09
### Area
Radio link / `KissTncDriver.sendAx25`
### Symptoms
`KissTncDriverTest.aFullQueueRefusesInsteadOfGrowing`: with `maxQueue = 2` six concurrent sends were all accepted; none returned `QueueFull`.
### Root cause
The send queue was a `Channel` with capacity `maxQueue + 8`; `trySend` only failed after that many, so the advertised bound was not the real one.
### Failed attempts
1. Reducing the channel capacity: the in-flight frame being transmitted is not in the channel, so the bound was off by one and racy.
### Working fix
A separate `pendingFrames` counter, incremented under the radio lock before `trySend` and decremented in a `finally` after the result is awaited; admission fails with `QueueFull` at `maxQueue`.
### Verification
The test passes; 101 radio/kiss tests green at that point.
### Related files
`core/network/src/commonMain/kotlin/com/transfer/flash/core/network/radio/KissTncDriver.kt`, `.../KissTncDriverTest.kt`
### Status
RESOLVED (unit test only; no radio)
```

ERROR-131 to ERROR-133 were not used.

## 14. Paste-ready: EXP-023 template for `logs/experiments.md`

```markdown
## EXP-023 - KISS TNC over Bluetooth: link, frame limit and goodput (TEMPLATE, fill from BT-00/BT-12)

### Devices
Radio: ____ (model, firmware). Second station/monitor: ____. PC: Windows ____, laptop ____, Bluetooth adapter ____.
Flash build: ____ (commit). Tool: `:desktop:radioLinkTest` / CLI. jSerialComm 2.11.4.

### Link
Bluetooth Classic SPP or BLE: ____. COM port: ____ (outgoing). Baud setting used: ____. Radio data mode / KISS menu path: ____. Digital Mode on/off effect: ____.

### Frames
KISS transparency (C0/DB intact): yes / no. Largest accepted AX.25 frame: ____ B (236 tried). LCD behaviour: ____.

### Pacing used
TXDELAY ____ ms, min gap ____ ms, jitter ____ ms (tool defaults 300/500/1500 unless changed).

### Burst results (copy the result line)
5 x 192 B: accepted __ acked __ lost __ wall __ ms, RTT min/med/max __/__/__ ms, goodput __ B/s.
5 x 50 B: ...
9600 baud (if tried): ...

### Other
Two apps at once: ____. Power-cycle reconnect: ____ s. Range / RF conditions: ____. Battery/thermal: ____.

### Conclusion
(Pacing defaults kept or changed because ____. No universal assumption from one radio, per AGENTS.md section 9.)
```
