# Radio stream (R) — device checks owed

Fragment for `docs/testing/TEST-BACKLOG.md` (AGENTS §35). The integrator folds these into the canonical file with the
`PERF-R*` series reserved for this stream (plan §7).

A check is owed whenever this stream changed behaviour that can only be judged on hardware, and whenever a number in
`experiments.md` says NOT MEASURED.

---

## Section: PERF-R (radio optimisation, 2026-11-02)

### PERF-R01 — Idle radio link no longer wakes the phone four times a second

**Source:** `OPT-O26-01` / ADR-330 / EXP-130.
**Setup:** One Android phone, one paired handheld radio with KISS TNC enabled and the radio gateway connected (Radio
screen shows the link up), the app in the foreground with the Radio screen open, then the phone idle with the screen off
but the app not killed.
**Steps:**
1. Connect the radio, then leave the channel completely quiet for 60 minutes (nobody transmits).
2. Before the fix this is the control case: check out the parent commit (`7adcf4f5`), repeat, and record the same two
   readings, so the comparison is on the same handset.
3. Read `dumpsys batterystats --charged <package>` before and after the 60 minutes; also read
   `adb shell dumpsys power | Select-String "Wake Locks|mWakefulness"`.
4. Read the app's own probe/log output: there must be **no** new `radio.*` lines during the quiet hour.
**Pass:** With the fix, the phone's `batterystats` wake-up count for the app does not grow by ~14 400 in the hour, and
the app's own probe log gains no periodic entries. The radio link still receives a text sent from the other station
immediately after the hour (the read was still waiting on the socket).
**Fail signs:** A text sent after a quiet hour arrives late, or the link shows "Waiting/Connecting" after the hour, or
`radio.link.down` appears without a real disconnect. That would mean a heartbeat of 60 s is too long for this radio.
**Status:** TODO

### PERF-R02 — Text still arrives promptly with the longer read timeout

**Source:** `OPT-O26-01`, `OPT-O26-02`.
**Setup:** Two Android phones (or a phone and the desktop) each with a radio, both gateways connected, both on Profile M,
both with the callsign confirmed.
**Steps:** Send a 1-character text, then a 200-character text, then a 1 000-character text (6 segments). Repeat three
times.
**Pass:** Every text arrives at the other station, the ACK arrives (bubble goes from "sent by radio" to "delivered"), and
the observed delay for the 1-character text is not worse than on `7adcf4f5`.
**Status:** TODO

### PERF-R03 — Reconnect probing no longer freezes other work (O26-05)

**Source:** `OPT-O26-05`.
**Setup:** One Android phone, a remembered radio that is **switched off** (or out of range), Radio screen open, a chat
transfer or a large photo download running in the background so the I/O threads are busy.
**Steps:** Tap Connect on the radio card and, while it is probing, scroll the chat list and open a conversation.
**Pass:** The UI stays responsive and the background transfer keeps making progress while the radio probe runs (before
the fix the probe held `Dispatchers.IO` threads in `Thread.sleep` for up to 4 s at a time). The probe still gives up
after ~25 s with the existing sentence.
**Status:** TODO

### PERF-R04 — Radio send in a lossy channel: ACK bookkeeping stays bounded and delivery still completes

**Source:** `OPT-O26-03`.
**Setup:** Two stations over the air with a weak signal or a dummy load so that ACKs are sometimes lost (or one station's
antenna removed briefly). Send at least 300 short texts in a session (script or repeated taps).
**Steps:** Send the texts, let the outbox retry, and watch `radio.rx.ack`, `radio.rx.retransmit` and `radio.tx.text`
probes. Then leave the app open for an hour.
**Pass:** Every text that was received by the other station eventually shows "delivered" (a lost ACK costs a
retransmission, never a lost message), no message is reported delivered without having arrived, and the app's heap
(`adb shell dumpsys meminfo <pkg>`) does not grow monotonically with the number of unacknowledged texts.
**Status:** TODO

### PERF-R05 — `:core:radio` suite and the golden frame on the merged tree

**Source:** this stream's gate.
**Setup:** Any tree after the merge, no hardware needed.
**Steps:** `cmd /c "tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks --no-build-cache"`.
**Pass:** All tests green, `RadioGoldenFrameTest.goldenFrameForFixedInputs` unchanged (the on-air bytes must be
byte-identical), and the `RadioSession` anti-replay tests (ERROR-870) unchanged and green.
**Status:** PASS in this stream's worktree (see `progress.md` for the exact run); re-run by the integrator at merge.

### Re-runs owed from earlier radio rounds (unchanged by this stream, still TODO)

`RADCHAT-DEV03`, `RADCHAT-DEV04`, `RADCHAT-DEV05`, `RADCHAT-DEV06`, `RADCHAT-DEV07` — the per-chat radio path,
"Radio only" presentation state, and the mailbox-vs-delivery ordering. This stream changed no routing decision, so these
are re-runs to confirm that the read-loop heartbeat and the bounded ACK table did not disturb delivery, not new tests.
