# STREAM C (calling / push-to-talk) — progress

Branch `agent/rvw22-c-calling-ptt`, worktree `E:\FlashWork\rvw22-c-calling-ptt`, based on `dev` @ `93212c7f`.
Source: `docs/audit/2026-10-22-comprehensive-brutal-code-review.md`, STREAM C (F42, F43, F44, F45, F46, F57, F58,
F59, F80). Every finding was verified against the code before anything was changed; where the audit's suggested fix
was wrong, the audit's text is corrected here and in `errors.md`.

Ordering: F42, F43, F45, F46, F57 first; F44, F58, F59 last (they touch the owner's `PttSessionEngine` /
`PttFloorMachine` high-severity work, so they are rebased onto the latest owner work first). F80 is investigation
only.

## 2026-10-22 — batch 1 (F42, F43, F45, F46, F57)

### Worked on
The five "safe" STREAM C findings: two in call teardown/cooldown/reactions, two in the PTT jitter buffer, one in the
PTT packet ordering.

### Changed
| Finding | File | Change |
|---|---|---|
| F42 | `core/calling/.../FlashCallSession.kt` | `hangUp()`/`decline()` run `end(...)` in a `finally`; new `sendTeardownFrame` rethrows `CancellationException` and logs other throwables; `end()`'s `releaseMedia()` launch logs a throwable instead of letting it reach the scope handler. |
| F43 | `core/calling/.../CallQualityGovernor.kt` | `inCooldown` also true when `elapsed < 0`; KDoc says the only clock is the injected `nowMs`. |
| F45 | `core/ptt/.../floor/PttJitterBuffer.kt` | Unwrapped 64-bit sequence domain (`newestSeq` + `unwrap`), documented; `reset()` clears it. |
| F46 | `core/ptt/.../floor/PttJitterBuffer.kt` | Overflow drops `queue.lastKey()` (the newest), not the packet the next `poll` must return. |
| F57 | `core/calling/.../CallStatusBook.kt` | Receiver reaction guard allows a negative gap (`now < last`) and re-anchors the window. |

Tests added: `FlashCallSessionTest` ×3 (F42), `CallQualityGovernorTest` ×2 (F43), `CallStatusBookTest` ×1 (F57),
`PttAudioTest`/`PttJitterBufferTest` ×3 new + 1 rewritten (F45, F46).

### Verified
- `tools\flash-gradle.cmd :core:calling:jvmTest :core:calling:testAndroidHostTest :core:ptt:jvmTest
  :core:ptt:testAndroidHostTest` → **BUILD SUCCESSFUL in 2m 47s** (229 + 386 + 121 + 105 tests, 0 failed).
- Mutation checks, each: revert only the production change, run the one test class, confirm the new test fails,
  restore from a `%TEMP%` copy, re-run to green.
  - F42: `FlashCallSessionTest > decline_stopsRingingWhenTheFrameSendThrows FAILED`,
    `> hangUp_endsTheCallWhenTheFrameSendThrows FAILED` (20 tests, 2 failed).
  - F43: `CallQualityGovernorTest[jvm] > a backwards clock step does not bypass the recovery cooldown[jvm] FAILED`
    (15 tests, 1 failed).
  - F45: `PttJitterBufferTest[jvm] > aSequenceThatWrapsIsTheNextPacketNotAnOldOne[jvm] FAILED` (10 tests, 1 failed).
  - F46: `PttJitterBufferTest[jvm] > overflowDropsTheNewestSoTheNextPollStillPlays[jvm] FAILED` (10 tests, 1 failed).
  - F57: `CallStatusBookTest[jvm] > a backwards clock step does not freeze a peer's reactions[jvm] FAILED`
    (15 tests, 1 failed).
- Nothing was run on a phone and nothing was deployed. No performance claim is made anywhere.

### Where the audit was wrong (recorded so it is not copied forward)
- **F43**'s "guard with `elapsed in 0 until recoveryCooldownMs`" would treat `elapsed == 0` as a cooldown, which
  freezes recovery for a clock that does not move (and broke an existing test). Only the negative branch was added.
  The "use monotonic time" half was not taken — see `decisions-additions.md` C-D1.
- **F45**'s second half ("initial packets out of order are permanently dropped") is **NOT-A-BUG**: playout starts at
  `queue.firstKey()`, and a packet whose slot passed is late by definition. A passing test now pins that.
  Its suggested expression `(seq - nextSeq).toInt() < 0` was not used (wrong boundary, and `nextSeq` is what `poll`
  advances) — see C-D2.
- **F46**'s alternative fix ("advance `nextSeq = queue.firstKey()`") would teleport playout forward and desynchronise
  the accounting — see C-D3.

### Remaining
- F44, F58, F59 (PTT engine / floor machine) and F80 (investigation) — see the next entries.
- Device checks `R22C-42`, `R22C-43`, `R22C-45`, `R22C-46`, `R22C-57` in `backlog-additions.md` are owed.
- A jitter-buffer test discriminates one way only: `aDuplicateAcrossTheWrapIsStillADuplicate` passes with `unwrap`
  removed, so it documents the contract rather than catching regressions.

## 2026-10-22/23 — batch 2 (F44, F58, F59) and F80

`git fetch origin` first, as the owner's PTT work was expected to land: `origin/dev` is at `dde95a17` and is an ancestor
of this branch's base `93212c7f`, so **there was nothing to rebase** — the owner's PTT commits (`afa132d5` for F7/F8/F9/F28/F71
and `9d427c3e` for F26/F27) are already in the base. `PttSessionEngine.kt` and `PttFloorMachine.kt` were edited locally
only, minimally, as the rules for owner-hot files require.

### Worked on
F44 (a press queued behind a stop), F58 (`Stop` for an unannounced session), F59 (a press with no identity), and the F80
investigation. Each was verified against the code before a change; the audit's suggested fix was corrected where it was
wrong (F44's "debounce").

### Changed
| Finding | File | Change |
|---|---|---|
| F44 | `core/ptt/.../PttSessionEngine.kt` | `hardPressRefusal()` + `startRefusal()` are the one definition of both refusal sets; `processLocked()` re-runs them for a `LocalPress` that lands on Idle (`refuseQueuedPressLocked()`), dropping the press with the same sentence. New `commandLane` constructor seam (default `Dispatchers.IO`). |
| F58 | `core/ptt/.../floor/PttFloorMachine.kt` | `MicDenied` sends `SendStop` only when `Talking.announced`; KDoc rule added. |
| F59 | `core/ptt/.../PttSessionEngine.kt` | `onPttButton()` refuses with `NO_PEERS` while `localId()` is null/blank (`PTT press refused: no local device id yet`); the same condition in `startRefusal()`; `sendPing()`/`startTalk()` treat blank as none. |
| F80 | — | No production change. |

Tests: `PttSessionEngineSessionTest` +3 (`a press queued behind a stop cannot open a session with the microphone switched
off`, `a press without a local device id is refused`, `a blank local device id is refused too`); `PttFloorMachineTest`
`micDeniedReleasesFloor` split into `micDeniedBeforeAnnounceReleasesTheFloorWithoutSendingStop` +
`micDeniedAfterAnnounceTellsTheMembers`; `a capture that cannot open…` now also asserts no `Stop` frame;
`ManualDispatcher` added to `FakePttAudio.kt` (common fakes).

### Verified
- `tools\flash-gradle.cmd :core:calling:jvmTest :core:calling:testAndroidHostTest :core:ptt:jvmTest
  :core:ptt:testAndroidHostTest` → **BUILD SUCCESSFUL**, **849 tests, 0 failures**
  (229 / 386 / 125 / 109).
- Mutation checks (each: revert only the production change, run, confirm, restore, re-run to green):
  - F44 disabling the locked re-check → `a press queued behind a stop cannot open a session with the microphone
    switched off FAILED` (21 tests, 1 failed).
  - F58 always emitting `SendStop` → `micDeniedBeforeAnnounceReleasesTheFloorWithoutSendingStop FAILED` and
    `a capture that cannot open ends the session with a notice and sends nothing FAILED` (52 tests, 2 failed).
  - F59 `hasLocalId()` forced true → both identity tests FAILED (23 tests, 2 failed).
- One build was interrupted while it was finishing (tool aborted, not a failure); the results were re-read from the test
  XMLs and then re-run clean.

### Where the audit was wrong (F44)
"Debounce button presses" is not a fix: a press while Talking is the deliberate toggle-to-stop, and debouncing it takes
away the only button that can stop a session. Only the "check permissions inside `processLocked`" half is correct. The
same class of defect in the *reverse* direction — a queued *stop* being debounced — was deliberately avoided by running
the guard only against Idle.

### F80 — CANNOT CONFIRM (no code changed)
Claim: `releaseMedia()` runs asynchronously on `callMediaDispatcher` while `onEnded` clears the session, so a rapid
hangup and re-dial can let the *new* call's media acquisition race the *old* call's teardown (Camera2/AudioRecord).

What the code does show:
- `CallCoordinator.newSession` builds a **new** `FlashCallSession` per call, each with its **own**
  `mediaLifecycleMutex` — so the lock never spans two calls, and the new `startMedia()` can genuinely begin before the old
  `releaseMedia()` has finished.
- `FlashCallSession.end()` launches `releaseMedia()` (`scope.launch(callMediaDispatcher)`) and returns; `onEnded` clears
  `currentSession` and publishes ENDED immediately. Nothing waits for the release.
- `callMediaDispatcher` is `Dispatchers.Default` on Android (a pool), and one dedicated thread on the JVM. On **desktop**
  the two therefore queue behind each other, so the suspected race would be **Android-only**.
- `MediaDevices.getUserMedia` is called directly (no media seam), so no unit test can force the order; and no device is
  available to this stream.

Why no fix was written: a fix would have to make the *next* call wait for the *previous* call's release (cross-session
coordination in `CallCoordinator`/`FlashCallSession`, both owner-hot files), on evidence this stream cannot produce. If it
is real, the existing camera/microphone failure banners (ERROR-105 / ADR-078) already give the user a Try again. Physical
check `R22C-80` records exactly what to capture.

### Remaining
- Device checks `R22C-42`, `R22C-43`, `R22C-44`, `R22C-45`, `R22C-46`, `R22C-57`, `R22C-58`, `R22C-59` and the `R22C-80`
  investigation are owed (`backlog-additions.md`).
- The canonical logs (`logs/progress.md`, `logs/errors.md`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`) are
  untouched on purpose; this directory is the fragment the integrator folds in. `errors.md` here holds ERROR-780..787
  (ERROR-788 is reserved and unused because F80 is not a defect).
- Nothing was pushed, merged, or run on a device. Commits: one per finding, see `git log --oneline 93212c7f..HEAD`.

## Commits (branch `agent/rvw22-c-calling-ptt`, base `dev` @ `93212c7f`)

| Finding | Commit |
|---|---|
| F42 | `b0966ac3` fix(calling): hanging up no longer depends on the frame getting out (R22C-42) |
| F43 | `9640c679` fix(calling): a backwards clock step keeps the video recovery cooldown (R22C-43) |
| F45 | `673efd42` fix(ptt): playout keeps its place across the u32 sequence wrap (R22C-45) |
| F46 | `a6ecb959` fix(ptt): an over-full playout buffer sheds the newest packet, not the next one (R22C-46) |
| F57 | `3b32e6c8` fix(calling): a backwards clock step no longer freezes a peer's reactions (R22C-57) |
| F44 | `b06170bb` fix(ptt): a press queued behind a stop cannot start an unchecked session (R22C-44) |
| F58 | `b4ac60e7` fix(ptt): a session nobody was told about is not ended on the wire (R22C-58) |
| F59 | `456a3db0` fix(ptt): a press with no local device id is refused, not left without a tie-break (R22C-59) |
| docs | `350e649b` docs(streams): record STREAM C of the 2026-10-22 comprehensive review (R22C-42..80) |

F45 and F46 touch the same two files, so F45 was committed from an intermediate state (wrap domain only, overflow still
drop-oldest) and the F46 change landed on top — the history stays one commit per finding, not one per file.

## Finding status summary

| Finding | Status |
|---|---|
| F42 | FIXED (unit-tested, mutation-checked, not device-verified) |
| F43 | FIXED (audit's `elapsed == 0` and monotonic-clock suggestions corrected) |
| F44 | FIXED (audit's "debounce" rejected; locked re-check implemented) |
| F45 | FIXED (wrap half); second half NOT-A-BUG with a passing regression test |
| F46 | FIXED (audit's `nextSeq` alternative rejected) |
| F57 | FIXED |
| F58 | FIXED |
| F59 | FIXED (uses the existing `NO_PEERS` outcome) |
| F80 | CANNOT-CONFIRM (no production change; `R22C-80` records what to capture) |
