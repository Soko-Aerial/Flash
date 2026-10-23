# STREAM C (calling / push-to-talk) stream errors — fragment, integrator folds into `logs/errors.md`

Reserved numbers for this stream: **ERROR-780..789**.
Audit: `docs/audit/2026-10-22-comprehensive-brutal-code-review.md` (2026-10-22 comprehensive brutal review), STREAM C.
Every entry below is **fixed in code and unit-tested, NOT device-verified**, so status is OPEN until the device checks in
`backlog-additions.md` (`R22C-*`) pass.

---

## ERROR-780 — a hangup whose signalling frame throws never hangs up (audit F42)

### Date
2026-10-22

### Area
Calling / `FlashCallSession` teardown (Android host test, common code)

### Symptoms
Not observed on a phone — found by reading the code. If the signalling send fails, the user stays on the call
screen for good: `hangUp()` and `decline()` awaited `sendFrame(...)` before `end(...)`, so a throw skipped the local
teardown. By that point the dial/connect timeouts are gone (they are cancelled once the call is up) and hangup is the
only button the user has left.

### Environment
- Worktree `E:\FlashWork\rvw22-c-calling-ptt`, branch `agent/rvw22-c-calling-ptt`, base `dev` @ `93212c7f`.
- Unit test: `:core:calling:testAndroidHostTest` (JVM/Robolectric host), Gradle 8.x via `tools\flash-gradle.cmd`.

### Error
No stack trace available (defect found by inspection). The shape that would happen:

```text
java.lang.IllegalStateException: socket closed      (from the host's sendFrame)
    at com.transfer.flash.debug.DiscoveryEngineHolder$sendFrame$1...
    at com.transfer.flash.core.calling.FlashCallSession.hangUp(FlashCallSession.kt:462)
```

### Root cause
`core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt` — `hangUp()` and
`decline()` sent the teardown frame and then called `end(...)`, with no `try`/`finally`. A transport that throws
therefore skipped `end(...)`, the only thing that moves this device out of the call. The audit's suggested fix was
right; nothing about it needed correcting.

### Failed attempts
None: the fix was the audit's own suggestion, applied as written.

### Working fix
- New `private suspend fun sendTeardownFrame(frame)` rethrows `CancellationException` and logs (not throws) every
  other `Throwable` with `FlashLog.w("CALL", "teardown frame <name> not sent call=...")`.
- `hangUp()` / `decline()` now `try { sendTeardownFrame(...) } finally { end(NORMAL, notifyPeer = false) }`.
- Same session, same class of bug on the other release path: `end()`'s
  `scope.launch(callMediaDispatcher) { releaseMedia() }` now catches and logs a non-cancellation throwable instead
  of handing it to the scope's uncaught-exception handler (an uncaught `launch` failure kills the process).

### Verification
`core/calling/src/androidHostTest/.../FlashCallSessionTest.kt` gained three tests (`newSession` now takes a
`sendFrame` parameter):
`hangUp_endsTheCallWhenTheFrameSendThrows`, `decline_stopsRingingWhenTheFrameSendThrows`,
`hangUp_stillSendsTheHangupFrameWhenTheTransportWorks`.

Mutation check (reverted only the `try`/`finally`, restored afterwards):

```text
FlashCallSessionTest > decline_stopsRingingWhenTheFrameSendThrows FAILED
FlashCallSessionTest > hangUp_endsTheCallWhenTheFrameSendThrows FAILED
20 tests completed, 2 failed
```

Green afterwards: `:core:calling:jvmTest` 229 tests / 0 failed, `:core:calling:testAndroidHostTest` 386 / 0.
**Not device-verified** — see `R22C-42`.

### Related files
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt`
- `core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/FlashCallSessionTest.kt`

### Status
OPEN (fixed in code, unit-tested, device check `R22C-42` owed)

---

## ERROR-781 — one backwards clock step removed the video recovery cooldown (audit F43)

### Date
2026-10-22

### Area
Calling / `CallQualityGovernor` (video concession policy)

### Symptoms
Not observed on a phone. After the wall clock stepped backwards (NTP correction, user setting the time), the
governor treated the cooldown it had just entered as long expired, so the picture could step back up and down again
in a strobe — the exact behaviour the cooldown exists to prevent.

### Environment
As ERROR-780. Unit test: `:core:calling:jvmTest`.

### Error
No exception; a policy inversion.

### Root cause
`inCooldown = lastStepAtMs != 0L && elapsed in 1 until recoveryCooldownMs`. `elapsed = nowMs() - lastStepAtMs` is
negative after a backwards step, and `negative in 1 until 8_000` is `false`, i.e. "not in cooldown". The audit's
diagnosis was correct.

The audit's *suggested* fix (`elapsed in 0 until recoveryCooldownMs`) is **not** what was applied: `elapsed == 0`
means "the injected clock did not move", and several existing tests drive the governor with a fixed clock. Treating
that as a cooldown would freeze recovery for callers (and tests) that legitimately do not advance time. The
published fix keeps `0` as before and only adds the negative branch.

### Failed attempts
1. `elapsed in 0 until recoveryCooldownMs` — broke `FlashGroupCallVideoAuditTest >
   aLegGivesItsVideoBackAfterFiveCleanSamples`, which does not advance its clock. Rejected.

### Working fix
`inCooldown = lastStepAtMs != 0L && (elapsed < 0L || elapsed in 1 until recoveryCooldownMs)`, plus a class KDoc
line saying the only clock it sees is the injected `nowMs`. The monotonic-clock half of the audit's fix was not
taken: there is no monotonic source in `core:common` and both callers pass `SystemTimeSource.nowMs()`; adding a
platform seam for this one comparison is not worth it (recorded in `decisions-additions.md`).

### Verification
`CallQualityGovernorTest` gained `a step down holds recovery off for the cooldown` and
`a backwards clock step does not bypass the recovery cooldown`.

Mutation check (reverted the guard to the pre-fix expression):

```text
CallQualityGovernorTest[jvm] > a backwards clock step does not bypass the recovery cooldown[jvm] FAILED
15 tests completed, 1 failed
```

### Related files
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallQualityGovernor.kt`
- `core/calling/src/commonTest/kotlin/com/transfer/flash/core/calling/CallQualityGovernorTest.kt`

### Status
OPEN (fixed in code, unit-tested, device check `R22C-43` owed)

---

## ERROR-782 — PTT audio dropped as "late" after the u32 sequence wrapped (audit F45, wrap half)

### Date
2026-10-22

### Area
Push-to-talk / `PttJitterBuffer` (playout)

### Symptoms
On a very long PTT session (or any session whose sender's counter is already past `0x7FFFFFFF`, e.g. after the
sender process restarted with a high start value), playout stops taking packets: `seq < nextSeq` compares u32 wire
values ordinally, so `0` after `0xFFFFFFFF` looks like an ancient packet and is counted `late` for ever.

### Environment
As ERROR-780. Unit test: `:core:ptt:jvmTest`.

### Error
No exception: `lost` climbs, `late` climbs, the listener hears nothing.

### Root cause
`PttJitterBuffer.push` compared raw wire sequence numbers. `PttAudioFrame.seq` is a u32
(`core/ptt/.../protocol/PttAudioFrame.kt`), documented as wrapping and ordered ordinally, so the comparison needs a
modulo/unwrapped domain.

The audit's *other* half — "out-of-order initial packets are permanently dropped, drop packet 0 if packet 1 arrives
first" — is **NOT-A-BUG** and needed no code change: playout starts at `queue.firstKey()`, so a first packet that
arrives second is played first; a packet whose slot has already passed is late *by definition* and is documented as
such. A regression test (`theFirstPacketsMayArriveInTheWrongOrder`) now pins that behaviour.

### Failed attempts
None.

### Working fix
- `newestSeq: Long?` is the unwrapped reference; `unwrap(seq) = reference + (seq - reference).toInt()` (the signed
  32-bit difference, so `0` after `0xFFFFFFFF` is `+1`).
- Everything inside the class — the queue keys, `nextSeq`, the late and duplicate tests, and the `Poll.seq` values
  it reports — lives in that unwrapped domain; `reset()` clears `newestSeq`.
- The audit's suggested expression `(seq - nextSeq).toInt() < 0` was not used: it tests the wrong thing at the
  boundary (a difference of exactly `0x80000000` reads as negative) and it does not fix `nextSeq`, which is what
  `poll` advances. Anchoring on the highest sequence seen is the standard unwrap and keeps `poll` unchanged.

Note the honest scope: `reset()` is never called in production and buffers are created per session, so wrapping
only matters for a session long enough to cross the u32 boundary at 20 ms packets (~2.7 years of continuous talk
at one packet per 20 ms). It is a contract defence, not a field bug — which is why the test drives the boundary
directly instead of waiting.

### Verification
`PttAudioTest.kt` (`PttJitterBufferTest`) gained `theFirstPacketsMayArriveInTheWrongOrder`,
`aSequenceThatWrapsIsTheNextPacketNotAnOldOne`, `aDuplicateAcrossTheWrapIsStillADuplicate`.

Mutation check (made `unwrap` return the raw value):

```text
PttJitterBufferTest[jvm] > aSequenceThatWrapsIsTheNextPacketNotAnOldOne[jvm] FAILED
10 tests completed, 1 failed
```

(`aDuplicateAcrossTheWrapIsStillADuplicate` still passes under that mutation because both copies unwrap the same
way, so it does not discriminate — recorded here rather than claimed as a catching test.)

### Related files
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBuffer.kt`
- `core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttAudioTest.kt`

### Status
OPEN (fixed in code, unit-tested, device check `R22C-45` owed)

---

## ERROR-783 — a full PTT buffer deleted the packet the next tick had to play (audit F46)

### Date
2026-10-22

### Area
Push-to-talk / `PttJitterBuffer` (overflow)

### Symptoms
Not reported on a phone. When the buffer exceeded `maxPackets`, it removed `queue.firstKey()` — the exact packet the
next `poll` was going to return — so the very next tick concealed a slot and the listener heard a gap at the head of
the stream, in addition to the packet that really was shed.

### Environment
As ERROR-780. Unit test: `:core:ptt:jvmTest`.

### Error
No exception: one extra `Poll.Concealed`, `lost` counts a packet that was never missing.

### Root cause
`if (queue.size > maxPackets) queue.remove(queue.firstKey())`. Overflow must shed the least valuable packet, which
is the newest arrival (nothing has been promised to any tick about it), not the oldest. The audit's diagnosis and its
first suggested fix were both right.

### Failed attempts
The audit's alternative fix — "or advance `nextSeq = queue.firstKey()`" — was rejected: that silently re-bases the
playout clock to the newest surviving packet, which teleports the stream forwards and desynchronises the
concealment/late accounting from what was actually played.

### Working fix
`queue.remove(queue.lastKey())` — drop the newest arrival; the head of the stream is untouched.

### Verification
`overflowDropsOldest` was replaced by `overflowDropsTheNewestSoTheNextPollStillPlays`, which asserts the dropped
packet is the newest (depth stays at the cap), that `0, 1, 2` still play in order, and that `lost == 0`.

Mutation check (restored the drop-oldest line):

```text
PttJitterBufferTest[jvm] > overflowDropsTheNewestSoTheNextPollStillPlays[jvm] FAILED
10 tests completed, 1 failed
```

### Related files
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBuffer.kt`
- `core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttAudioTest.kt`

### Status
OPEN (fixed in code, unit-tested, device check `R22C-46` owed)

---

## ERROR-784 — a backwards clock step froze a peer's call reactions (audit F57)

### Date
2026-10-22

### Area
Calling / `CallStatusBook` (peer reaction rate limit)

### Symptoms
Not observed on a phone. After the clock stepped backwards, `now - last` was negative, the receiver's
`MIN_REACTION_GAP_MS` check read it as "less than a gap", and that peer's reactions were dropped until the clock
caught up — a frozen reaction, which is the failure the check is not supposed to have.

### Environment
As ERROR-780. Unit test: `:core:calling:jvmTest`.

### Error
No exception; `apply(...)` returns null and nothing is shown.

### Root cause
`if (last == null || now - last >= MIN_REACTION_GAP_MS)`: a negative difference can never satisfy the gap, so a
backwards step reads as a permanent flood. (The local half of the same file, `nextLocalSeq`, already had the
`now >= cur.localAt` guard — this receiver half did not.)

### Failed attempts
None.

### Working fix
`if (last == null || now < last || now - last >= MIN_REACTION_GAP_MS)` — a negative gap is "no evidence of a
flood", and the window re-anchors at the new time so the flood rule applies again from there.

### Verification
`CallStatusBookTest` gained `a backwards clock step does not freeze a peer's reactions`, which also checks the
window re-anchors (one reaction one millisecond later is still dropped; one after `MIN_REACTION_GAP_MS` is shown).

Mutation check (removed `now < last`):

```text
CallStatusBookTest[jvm] > a backwards clock step does not freeze a peer's reactions[jvm] FAILED
15 tests completed, 1 failed
```

### Related files
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallStatusBook.kt`
- `core/calling/src/commonTest/kotlin/com/transfer/flash/core/calling/CallStatusBookTest.kt`

### Status
OPEN (fixed in code, unit-tested, device check `R22C-57` owed)

---

## ERROR-785 — two taps while talking started a second session with unchecked permissions (audit F44)

### Date
2026-10-22 / 2026-10-23

### Area
Push-to-talk / `PttSessionEngine` press path

### Symptoms
Not observed on a phone (found by reading the code and now proven by a test). The microphone and peer
checks run in `onPttButton()` only when the floor is Idle, but the press is applied later, under the
command lock, behind every press already queued. Two quick taps while the floor is up both look like
"stop" at press time: the first toggles the floor back to Idle, and the second then lands on Idle and
opened a new capture and announced a new session with the microphone permission and the peer list never
re-checked.

### Root cause
Check-and-act split across two places: the guards in `onPttButton()` (press time, unsynchronised
`floor` read) and the reduction in `processLocked()` (locked, later). Confirmed with the new
`commandLane` seam.

### Failed attempts
1. The audit's first suggested fix — "debounce button presses" — was **not** applied: a press while
   Talking is the deliberate toggle-to-stop, and debouncing it would swallow the only way to stop
   talking with the button.
2. Only the "check permissions inside `processLocked`" half was correct, and that is what was done.

### Working fix
- `hardPressRefusal()` (call active / voice note) and `startRefusal()` (no peers / no mic / no id) are
  the single definitions of the two refusal sets; `onPttButton()` uses them.
- `processLocked()` re-runs them for a `LocalPress` that lands on Idle and drops the press with the
  same sentence the press path shows (`refuseQueuedPressLocked()`), so the user is not left wondering.
- New `commandLane: CoroutineDispatcher = Dispatchers.IO` constructor parameter: a seam so a test can
  release the lane one step at a time (production behaviour unchanged).

### Verification
`PttSessionEngineSessionTest > a press queued behind a stop cannot open a session with the microphone
switched off`, plus `ManualDispatcher` in the common test fakes.

Mutation check (disabled the locked re-check only):

```text
PttSessionEngineSessionTest[jvm] > a press queued behind a stop cannot open a session with the
microphone switched off[jvm] FAILED
21 tests completed, 1 failed
```

### Related files
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt`
- `core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt`
- `core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/FakePttAudio.kt` (`ManualDispatcher`)

### Status
OPEN (fixed in code, unit-tested, device check `R22C-44` owed)

---

## ERROR-786 — a session nobody was told about was ended on the wire (audit F58)

### Date
2026-10-23

### Area
Push-to-talk / `PttFloorMachine` (`MicDenied`)

### Symptoms
Not user-visible: when the capture device failed before the session was announced, the teardown still
sent a `Stop` frame for a session no member had ever received a `Start` for. Wire noise on every failed
capture start, and an "unknown session" log line on every member.

### Root cause
`MicDenied` emitted `SendStop` unconditionally, while `Talking.announced` is exactly "some member
received the Start".

### Working fix
`if (state.announced) add(SendStop(...))` in the `MicDenied` branch; `StopCapture` and the
"Microphone unavailable — session ended" notice are unchanged. `CallStarted` and the collision yield do
not need the same guard: `StartAnnounced` is reduced under the same command lock, so by the time either
runs the session is announced or already gone.

### Verification
Machine: `micDeniedBeforeAnnounceReleasesTheFloorWithoutSendingStop` (new),
`micDeniedAfterAnnounceTellsTheMembers` (the announced case, replacing `micDeniedReleasesFloor`).
Engine: `a capture that cannot open ends the session with a notice and sends nothing` now also asserts
that no `Stop` frame left.

Mutation check (always emit `SendStop`):

```text
PttSessionEngineSessionTest[jvm] > a capture that cannot open ends the session with a notice and sends nothing[jvm] FAILED
PttFloorMachineTest[jvm] > micDeniedBeforeAnnounceReleasesTheFloorWithoutSendingStop[jvm] FAILED
52 tests completed, 2 failed
```

### Status
OPEN (fixed in code, unit-tested, device check `R22C-58` owed)

---

## ERROR-787 — a press with no local device id could not resolve a collision (audit F59)

### Date
2026-10-23

### Area
Push-to-talk / `PttSessionEngine` press path and floor collision tie-break

### Symptoms
Not observed in the field; found by reading the code. `LocalPress.holderId = localId().orEmpty()`, and
`PttFloorMachine.reduceTalking` only yields to a lower id when its own `holderId.isNotBlank()`. A host
that has not loaded its identity yet (the Android holder's `localDeviceId` is null until the identity
arrives) pressed with a blank id: no tie-break existed, so two talkers both kept the floor and
transmitted over each other; the `Start` frame also carried an empty sender (which every receiver
rejects).

### Root cause
A required precondition (identity) was optional at the entry point.

### Working fix
- `hasLocalId()` (`localId()` non-null and not blank) gates `onPttButton()`; the refusal uses the
  existing `PttPressOutcome.NO_PEERS` (no new enum value, no new UI string) and the log says the real
  reason: `PTT press refused: no local device id yet`.
- The same condition is part of `startRefusal()`, so a press queued behind a stop is refused too.
- `sendPing()` and `startTalk()` treat a blank id as no id.

### Verification
`PttSessionEngineSessionTest > a press without a local device id is refused` and
`> a blank local device id is refused too` (no capture opened, nothing on the wire, `sendPing()` false).

Mutation check (`hasLocalId()` forced to true):

```text
PttSessionEngineSessionTest[jvm] > a blank local device id is refused too[jvm] FAILED
PttSessionEngineSessionTest[jvm] > a press without a local device id is refused[jvm] FAILED
23 tests completed, 2 failed
```

### Related files
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt`
- `core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt`

### Status
OPEN (fixed in code, unit-tested, device check `R22C-59` owed)

---

## ERROR-788 — NOT USED

Reserved and deliberately left empty: F80 is an investigation, not a confirmed defect (see
`progress.md` "F80"). Do not reuse this number for a defect; the stream's report records why no code
changed.

