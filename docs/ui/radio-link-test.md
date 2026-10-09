# Radio link test ("Experimental: Radio link test")

Status: IMPLEMENTED 2026-10-09 (desktop window + headless CLI), simulation-tested, **never run against a real radio**.
This is a developer/hardware-spike tool for test BT-00 (`docs/testing/TEST-BACKLOG.md`), not a product screen. It is not in the
normal navigation; it is a separate window started from Gradle. It is therefore outside the UI-0xx component sequence of
`docs/ui/ui-research-index.md` (AGENTS.md section 34); if it ever becomes a product screen it needs the usual component doc.

## Purpose

Answer, with the owner's VGC VR-N76 (or any KISS TNC) and a Windows laptop, the questions of
`docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md` section 0.2 that a program can answer:

1. Does the Bluetooth virtual COM port open and carry bytes both ways?
2. Does the TNC pass the KISS special bytes (0xC0 FEND, 0xDB FESC) intact?
3. Does a 220-byte Flash frame go out in one AX.25 UI frame, and does a second station decode it?
4. What are the real round trip and goodput numbers for a burst (feeds EXP-023)?

It cannot answer: the radio's LCD/menu behaviour, 9600 baud, the one-app-at-a-time limit, or RF range (a person must observe those).

## How to start

```text
./gradlew :desktop:radioLinkTest                       # the window
./gradlew :desktop:radioLinkTestCli -PradioArgs="--list"
./gradlew :desktop:radioLinkTestCli -PradioArgs="--simulated --kiss --ax25 --flash --burst 3"   # no hardware
./gradlew :desktop:radioLinkTestCli -PradioArgs="--port COM5 --baud 115200 --kiss --ax25 --flash --burst 5 --role a"
./gradlew :desktop:radioLinkTestCli -PradioArgs="--port COM5 --role b --listen 300"              # second station
```

(Gradle environment on this machine: see the report `docs/reports/2026-10-09-bluetooth-radio.md`.)

## Window layout

```text
Experimental: Radio link test
[disclaimer: test key is public, nothing verified on a real radio]
Serial ports [Refresh]            | Port [____] Baud [115200] [Role: A] [Pacing: RADIO]
  COM15 [BLUETOOTH] ...           | [Connect] [Simulated radio] [Disconnect]
  COM6  [OTHER]     ...           | status line (green running / red failed / grey idle)
  (or "no ports")
[KISS test frame] [AX.25 UI test frame] [Flash 220-byte payload]
Burst frames [5] Body bytes [192] [Run timed burst] [Responder: ON] [Export log]
result line (burst summary: accepted / acked / lost / RTT min-med-max / goodput)
Log: <path> (send this file back)
+-------------------------------+-------------------------------+
| Decoded (KISS / AX.25 / Flash)| Raw hex (TX / RX / EVT)       |
+-------------------------------+-------------------------------+
```

Design notes:

- Uses `FlashTheme` tokens, `FlashText`, `FlashSpacing`, `FlashShapes`; buttons are Flash-styled pills (no Material buttons).
  The two numeric entry fields use `BasicTextField` (this is a diagnostics window, not the chat composer; the
  "no default TextField as the final composer" rule of section 34 is about the chat composer).
- Dark theme only (a bench tool).
- Both panes poll every 400 ms and keep the last 60 raw lines / 300 decoded lines on screen. The full history is in the log file.
- Role A/B only decides the two test station names (`bt00-a`, `bt00-b`). Both machines derive the same public test key, so
  station A can talk to station B without any pairing. The key is public on purpose (`RadioLinkTester.TEST_PASSPHRASE`): these
  frames prove the format and the link, they do not demonstrate confidentiality.
- "Responder" answers every received PING with an ACK that names the counter; this is how RTT is measured with two stations.

## What each test button does

| Button | Sends | Pass looks like |
|---|---|---|
| KISS test frame | One plaintext AX.25 UI frame to `FLTEST`, source `BT00A`/`BT00B`, information field = bytes 0..219 (contains C0 and DB) | On a monitoring receiver the 220 info bytes arrive unchanged (compare the hex of the 220 info bytes with 00 01 02 ... DB ... C0 ... DB) |
| AX.25 UI test frame | A readable text `FLASH BT-00 AX25 UI TEST #n at <ms>` | Readable on any APRS/packet monitor; PID shows F0 |
| Flash 220-byte payload | One real Flash radio frame (type PING, full body), AX.25 info exactly 220 bytes | The other station's feed shows `flash-rx kind=PING ... body=192B` |
| Run timed burst | N PINGs with B body bytes, paced by the driver's policy, then waits up to 60 s for ACKs | Result line with `acked=N lost=0`, RTT and goodput numbers |
| Export log | Writes `flash-radio-test-<time>.txt` in the working directory | File exists; first lines are `# ...` header lines |

## Log format

`RadioEvidenceLog`: header lines prefixed `# `, then one line per event: `<epoch ms> TX|RX <n>B [label] <hex>`, or
`<epoch ms> EVT <name> key=value ...`. Frame content appears **only** in this log (it is a test key and test data); the Android
logcat PROBE lines (`radio.link.up`, `radio.link.down`, `radio.tnc.params`) carry no content (docs/testing/PROBES.md).

## Failure behaviour

- No ports: the list shows "no ports"; Connect with an empty port reports `Failed: cannot open ...` and the log keeps the reason.
- Port busy (another program, e.g. the radio's own app, owns it): `Failed: cannot open COMn (busy, missing, or access denied; error code N)`.
- Link drops mid-run: the driver goes to "waiting N ms to retry" and reconnects with capped backoff; sends return `LinkDown`.
- A radio that accepts bytes but never answers looks like "Running" with only TX lines: a KISS TNC is silent by design.

## Files

- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/RadioLinkTestMain.kt` (window, `RadioLinkTestApp`)
- `core/network/src/jvmMain/.../radio/diag/RadioLinkTestHarness.kt`, `RadioLinkTestCli.kt`
- `core/network/src/commonMain/.../radio/diag/RadioLinkTester.kt` (logic, unit-tested)
- `desktop/build.gradle.kts` (tasks `radioLinkTest`, `radioLinkTestCli`)
