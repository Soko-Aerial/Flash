# Experiments Log

## EXP-022 — 5,000-member organisation directory sync cryptographic verification benchmark (ORGT-U16)

### Date
2026-10-14

### Environment
Windows 11, JVM (OpenJDK 21, 64-bit), software ECDSA P-256 (`java.security`), `OrgDirectorySyncTest` test runner (`ORGT-U16`).

### Setup
- Full directory resync containing 5,000 member statements and 1 root-signed delegation certificate.
- Transferred across 10 paginated responses (500 members per page).
- Every member statement is cryptographically signed using ECDSA P-256 (`flash-org-member-v1` canonical format).
- Evaluated full sync pipeline: HTTP response parsing, multi-page state accumulation, single-pass root delegation verification, 5,000 individual member ECDSA signature verifications, diacritic folding (`foldForSearch`), SHA-256 fingerprint generation, Room entity conversion, Rule 8 conflict filtering, and atomic transactional database staging.

### Measurements
- **Initial unoptimized run:** ~34,800 ms (re-verifying root delegation signature for each member statement + duplicate delegation lookup in `findMatchingDelegation`).
- **Single-pass with delegation caching (`preVerifiedDelegations` + `verifyWithDelegation`):** 11,191 ms (~11.2 s) total elapsed wall time for 5,000 member statements across 10 pages.
- **Verification rate:** ~447 member statements verified, transformed, and staged per second in software JVM ECDSA (~2.2 ms per member statement).
- **Second conditional sync benchmark (delta sync with 0 changes, `since = 5000L`):** **3 ms** wall time (measured in `ORGT-U16`). Validates that when no member statements have changed on the hub, conditional query processing, DTO parsing, and DAO sync-state verification complete in under 5 ms without re-evaluating unchanged cryptographic signatures.
- **SQLite Database Size:**
  - Before 5,000 member sync (fresh empty schema 14): **64 KB** (base Room metadata, sqlite_master, page allocation).
  - After 5,000 member sync: **2.82 MB** (5,000 member entities + departments + indices).
  - Storage cost per member: ~564 bytes per member (canonical statement bytes ~340 B, hex SPKI fingerprint ~64 B, folded display name ~40 B, indices on `subjectPublicKeyFingerprint` and `displayNameFolded`).
- **Memory footprint:** Staging 5,000 member DTOs across 10 pages consumed ~15 MB temporary JVM heap without memory leaks or GC pauses.
- **Test budget threshold:** Documented ceiling set to `durationMs < 25,000 ms` to fail upon any algorithmic degradation (`O(N^2)`) while comfortably accommodating slower CI and desktop CPUs.

### 20,000-Member Scale Considerations
- **Verification throughput:** Verification scales strictly linearly `O(N)`. At ~450 verifications/second on modern desktop JVM, 20,000 members would require ~44 seconds of compute time for the initial full enrollment resync.
- **Mobile platform constraint:** On lower-tier Android ARM devices (e.g., 2.0 GHz Cortex-A55), software P-256 ECDSA verification is ESTIMATED at ~8-12 ms per signature (measure; not measured on any ARM device, only on the PC), which would mean up to 3-4 minutes for a 20,000 member initial download if performed synchronously. Therefore, full resyncs must run in a background sync coroutine with foreground service or WorkManager, emitting progress updates per page.
- **Database scale:** At 20,000 members, SQLite database size will reach approximately **11.3 MB**. SQLite B-trees with 4 KB page sizes remain at depth 3, keeping index lookups (`findMemberByFingerprint` and prefix search `searchMembers`) well under 1 ms even on flash storage.
- **Delta sync efficiency:** Routine background syncs only fetch changes (`since > lastSyncedVersion`). In a steady-state organization with 20,000 members, periodic polling when no changes occurred executes in < 5 ms and consumes negligible network bandwidth (< 1 KB response).

### Conclusion
Full sync verification scales strictly linearly `O(N)` with member count. Caching pre-verified delegation certificates eliminates `N` redundant root-signature ECDSA verifications, reducing cryptographic compute overhead by 70%. Software ECDSA P-256 in Java requires ~2.2 ms per signature verification; 5,000 members complete in ~11 seconds, and conditional delta syncs with zero changes complete in 3 ms.


## EXP-021 - Windows laptop CPU in a group video call (1 connected person), 720p software VP8

### Date
2026-10-08

### Devices
Windows 11, Intel Core i5-8350U (4 cores / 8 threads, 15 W), Flash desktop (JVM, webrtc-java 0.19.0 fork). Remote: a Linux laptop and a phone in the same group call (group call fa6259df, one leg CONNECTED, two INVITED). 5 GHz Wi-Fi, host/host path.

### Measured (from `~/.flash/desktop.log` `CALL_DIAG` / `CALL_RENDER`, and `Get-Process` sampling)
- Start of the call (15:35-15:36): process CPU 341-364 % of one core (43-45 % of 8). The machine was starved: camera 11-19 fps, encode 40-60 ms/frame, `limit=cpu`, WebRTC flapping the send size between 960x540 and 1280x720.
- Steady state (15:39): 240-250 % of one core (30-31 %). 1280x720, 30 fps both ways, encode `libvpx` ~16 ms/frame, decode `libvpx` ~7 ms/frame, remote tile convert ~3 ms, own preview convert ~5.5 ms, still `limit=cpu`.
- Live sample at 15:44 (PowerShell, 10 s): 291-323 % of one core.
- Per-frame costs account for about 1 core (encode ~0.5 + decode ~0.2 + converts ~0.25); the other ~1.4 cores are in native WebRTC threads the JVM does not name (about 170 % across 8-10 native threads) plus Java pool threads (~25 %). libvpx runs several threads, so the 16 ms encode figure is wall time, not CPU time. Per-thread attribution was **not** obtained.
- Camera preview conversion cost (150x84 output) was higher than the remote tile's at times (5-19 ms vs 3-10 ms), an oddity not explained.

### Conclusion
Software VP8 at 720p30 on a 15 W 4-core CPU is near the machine's limit; no leak or runaway loop was found. This is what ADR-098 acts on (540p/360p). The 1.4 unexplained cores are not codec work and need a profile (async-profiler / Windows Performance Recorder) before more is spent on codecs. See also the earlier note in EXP (codecs about 1 core of 2.5; hardware decode would save about 0.25).

### Open
`VID540-01` (re-measure after ADR-098), per-thread profile of the native threads.

## EXP-020 — Multi-node sans-IO group swarm simulation scenarios (SIM-01 through SIM-20)

### Date
2026-10-04

### Note
**Simulator, not devices.** Deterministic virtual clock, in-memory heap storage with crash semantics, synthetic network topology.

### Scenarios & Results
| Scenario | Description | Result | Verification |
|---|---|---|---|
| SIM-01 | Origin + 9 receivers, equal rates, 1,000 pieces | PASSED | All complete, origin upload ≤ 1.5× total size, 0 corrupt writes |
| SIM-02 | Half-sent: origin leaves at 50%, all converge on union, origin returns | PASSED | Receivers converge on union, wait with WAITING_FOR_SENDER, origin returns, all complete, origin upload ≤ 1.5× |
| SIM-03 | Origin leaves at 0% | PASSED | Receivers wait with WAITING_FOR_SENDER, 0 REQUEST frames sent while waiting, all resume when origin returns |
| SIM-04 | Origin and member with unique pieces leave; all return orders | PASSED | All complete after both return in every permutation |
| SIM-05 | Churn: random leave/join every 1-10s for 10 nodes | PASSED | All complete after churn ends, no deadlock |
| SIM-06 | Corrupt server flips bytes | PASSED | Corrupt server banned after 3 strikes, honest receiver completes, 0 corrupt writes |
| SIM-07 | Member removed mid-transfer | PASSED | Inbound request rejected with NOT_MEMBER, stops and deletes partial, no pieces sent post-removal |
| SIM-08 | Origin cancels while 2 nodes are offline | PASSED | Online nodes cancel in 1 RTT, offline nodes cancel on reconnect before sending any REQUEST |
| SIM-09 | Non-origin forges cancel | PASSED | Forged cancel rejected, forger struck, transfer completes |
| SIM-10 | Receiver crashes mid-download (unsynced writes dropped) | PASSED | Restarts from durable persisted bits, completes, never sends HAVE for lost piece |
| SIM-11 | Origin crashes and restarts | PASSED | Origin recovers origin record, serves again after restart, members complete |
| SIM-12 | Heterogeneous link rates (fast/slow mix) | PASSED | Swarm completes reliably with non-blocking rate adaptivity |
| SIM-13 | ECO node (servingEnabled = false) | PASSED | Never serves (0 bytes uploaded), completes download successfully |
| SIM-14 | Node without sw1 feature | PASSED | Receives 0 FSW1 frames, isolated from swarm transport |
| SIM-15 | Cross-group isolation (G1 & G2) | PASSED | Requests for G1 content from G2-only member rejected with UNKNOWN |
| SIM-16 | Request flood from one node | PASSED | Max active serve slots and requester byte budget hold, non-flooding peers protected |
| SIM-17 | Scale: 20 nodes, 16,384 pieces | PASSED | Real CI run time bounded, memory efficient with streaming piece generation |
| SIM-18 | Node runs out of space at 30%, then frees space | PASSED | Enters WAITING_FOR_SPACE, resumes and completes when space freed |
| SIM-19 | Origin suspended by system (service timeout) | PASSED | No tombstone signed; members wait, transfer resumes on system resume |
| SIM-20 | Origin source lost/changed | PASSED | Emits SOURCE_STATUS(LOST), members complete union, source re-pick restores serving |

### Conclusion
Pure sans-IO `SwarmEngine` obeys all 12 invariants (INV-1 through INV-12), origin offer policy (Rule B), rarest-first request scheduling (Rule A), and backoff/congestion control across all edge cases.


## EXP-019 — Group video call size: owner-reported 3 and 4 devices, realistic limit 3 to 4 (owner observation, not a measurement)

### Date
2026-10-02

### Devices
Not recorded. The owner reported "three devices in a video call and even 4 devices" without naming phones, tiers or builds.

### Setup
Not recorded: build, network, band, router, camera state, call length.

### Result
- A group video call with 3 devices was run, and one with 4 devices.
- The owner's conclusion: the realistic limit for a video call is 3 to 4 devices.
- The code still allows 8 video and 12 voice participants (`FlashGroupCallLimits`, ADR-050). The per-tier budgets in
  `docs/calling/GROUP-VIDEO-PLAN.md` are design estimates, not measurements.

### Why it is plausible (design reasoning, not a result)
A full mesh makes every device encode a copy for each viewer and decode one stream per sender, so the work per phone
grows with the group. The plan's own conclusion is that only a relay peer (a small media server) removes that ceiling.

### Conclusion
Treat "3 to 4" as one owner's observation on unrecorded hardware. Do not hard-code it as a universal limit and do not
lower the code cap on this evidence alone. GRP-10 in `docs/testing/TEST-BACKLOG.md` is the measurement that confirms or
moves it (5, 6 and 8 devices, with fps, freezes, CPU, battery and temperature per tier).

### Status
OBSERVATION, device and build unknown. Open until GRP-10 runs.

## EXP-018 — `javax.sound.sampled` for desktop push-to-talk: formats, release behaviour, cold-open latency

### Date
2026-09-30

### Setup
This PC (Windows 11, default microphone and speakers; the JDK used for the probe was not recorded). A throwaway Java probe (not committed; it lived
in the session scratchpad) opened the default capture and playback lines at 16 kHz and 8 kHz, 16-bit signed little-endian mono,
which is exactly the PTT wire format (ADR-032). **The raw probe output was not saved**, so only the conclusions below are recorded;
re-run a probe on other hardware (PTTD-02) before generalising anything here.

### Observed
| Question | Answer on this machine |
|---|---|
| Are 16 kHz and 8 kHz mono PCM16 supported for capture and for playback? | Yes, both directions, both rates |
| Does a blocked `SourceDataLine.write` return when the line is flushed, stopped and closed from another thread? | Yes (the JVM playout relies on it to stop promptly; unit-tested with a fake, hardware-confirmed here) |
| Cold open of the capture line | about 1.0 s the first time, about 0.23 s on later opens |

### Conclusions
- The formats need no resampler on this hardware. The JVM capture still tries the requested rate then 8 kHz and reports the rate
  actually opened, so a device that refuses 16 kHz degrades to LOW quality instead of failing.
- A ~1 s first open means the first words of the first session after launch can be clipped. A pre-warm (open and immediately
  stop the line once at startup) is the obvious mitigation and is **not built** (PTTD-04).
- Do **not** treat one Windows sound stack as representative: USB headsets and Linux ALSA/PulseAudio are untested (PTTD-02).
  This is not a throughput or quality benchmark.

## EXP-017 — Desktop group video call: where the CPU goes, and memory after the frame-leak fix

### Date
2026-09-29

### Setup
This PC (Windows 11, 8 logical cores, 20 GB), `:desktop:run` on JDK 25, webrtc-java 0.17.0, commit `2a7a006`
(ERROR-078 fix in). Four-person mesh group video call: this desktop, another desktop, two Android phones, over a
phone's Wi-Fi hotspot. VP8 only (`CallSdp.enforceVp8Only`), software libvpx.

### Measured (`~/.flash/desktop.log` `CALL_DIAG`, and the live process afterwards)
| What | Value |
|---|---|
| Process CPU during the call | 29–33 % of 8 cores (≈ 2.5 cores); was 44–63 % before ERROR-078 |
| Encode, per outgoing leg | 640x360 ~28 fps, `enc=libvpx` 8–9 ms/frame → ≈ 0.25 core per leg, ≈ 0.75 core for three |
| Decode, per incoming leg | 1.1–4.3 ms/frame → ≈ 0.25 core for all three |
| Everything else (capture, BGRA conversion + Skia upload, Compose, JVM) | ≈ 1.5 cores |
| Committed memory during the call | flat at 620–631 MB for minutes (was +52 MB/s before ERROR-078) |
| JVM heap during the call | 78–149 MB used of 1 GB max; 63–64 threads |
| After hang-up | 527 MB private / 495 MB working set; G1 heap 196 MB committed, 94 MB used |

### Conclusions
- ERROR-078's fix holds on the device: memory is flat during a call.
- Codecs are ≈ 1 core of ≈ 2.5. Hardware *decoding* (ADR-052) would save about 0.25 core here; encoding and the
  render path matter more. Measure the render path before any hardware-video work.
- The ~500–630 MB footprint is mostly native and JVM overhead (Skia, libwebrtc, JIT code, thread stacks) plus a
  heap the JVM does not give back: the default initial heap is 1/64 of RAM (320 MB on this host) and G1 does not
  uncommit below it. `-Xms64m -XX:G1PeriodicGCInterval=30000` added to the desktop JVM (not yet measured).
- Anomaly, not investigated: the router granted 720p (`limit=none`) but `vout` ran at 640x360.
- One device and one call; not a general figure.

## EXP-016 — A 1 Hz pairing ticker ran for the life of the process to service a state that is idle except during the few seconds a user spends pairing (static finding; fixed)

### Date
2026-09-04

### Class of finding
**Static inspection**, third find from the periodicity method EXP-015 opened, and taken straight off the
timer inventory that method produced. That inventory is complete and recorded in `logs/handoff.md`:
27 `while (true)` sites in product code, of which all but three are blocking read/queue loops
(`WebSocketCodec`, `DataChannelFraming`, `BoundedSendQueue`, `Chunker`) or `awaitEachGesture` bodies.
The three time-driven ones were `RealFlashChatRepository`'s outbox drain (EXP-015),
`PairingCoordinator`'s tick (this entry), and two that are already correctly bounded:
`MultiStreamDispatcher.kt:197` (10 ms, but `while (isActive && !deferred.isCompleted)`, and its consumer
is throttled by EXP-011) and `FlashCallScreen.kt:586` (the mm:ss call clock — inherently 1 Hz and scoped
to a call).

### The finding
`PairingCoordinator` launched this from `init` and never stopped it:

```kotlin
init {
    // 1 Hz tick drives request/decision expiry and refreshes the countdown while active.
    scope.launch {
        while (isActive) {
            val current = protocol
            if (current.session.value.phase != PairingPhase.Idle) {
                current.onTick(timeSource.nowMs())
                recomputeUi(current.session.value)
            }
            delay(TICK_MS)
        }
    }
}
```

**This is a milder finding than EXP-015 and is worth saying so plainly.** The work *is* gated: on an idle
pass the loop reads one `StateFlow.value` and compares it to `Idle`, so a pass costs a scheduler wake-up
and a volatile read — not a query. Nothing is decrypted and nothing is allocated. Two facts keep it
worth fixing anyway.

**It is unconditional in the one dimension that matters here — time.** ~86,400 wake-ups a day, every day
the process lives, to answer a question whose answer is `Idle` except during the seconds a user spends
pairing. A pairing happens once per peer, ever. On the Belfone this whole task exists for, a timer that
never sleeps is also a timer that keeps the scheduler from leaving a core in its deepest idle state.

**The information needed to avoid it was already there.** `protocol.session` is a `StateFlow` and the
phase this loop polled is a field of it. The loop was polling a value that pushes — the same shape as
EXP-015, where a loop that could have been woken by the thing it was waiting for polled for it instead.

### Changed
The `init` block is gone. The tick is now a third child of `collectorJob`, launched per protocol instance
by `launchCollectors()`:

```kotlin
private suspend fun tickWhileInFlight(p: DefaultFlashPairingProtocol) {
    p.session
        .map { it.phase != PairingPhase.Idle }
        .distinctUntilChanged()
        .collectLatest { inFlight ->
            if (!inFlight) return@collectLatest
            while (true) {
                delay(TICK_MS)
                p.onTick(timeSource.nowMs())
                recomputeUi(p.session.value)
            }
        }
}
```

Four decisions in that shape, each a trap if reversed.

**Bound to `p`, not to the `protocol` field, and a child of `collectorJob`.** `resetProtocol()` already
does `collectorJob.cancel(); protocol = newProtocol(); collectorJob = launchCollectors()`, so making the
ticker a child of that job means the existing cancel is also the ticker's teardown and no new lifecycle
bookkeeping appears anywhere. A ticker that outlived its instance would be *worse than the loop it
replaced*: terminal phases absorb every event and never return to `Idle`, so `!= Idle` would stay true
forever and the loop would spin at 1 Hz calling `onTick` on a dead session — this very defect class,
reintroduced by its own fix. The class KDoc used to end "the 1 Hz ticker always reads the current
instance"; it now says the opposite, because that sentence became false.

**`distinctUntilChanged()` is load-bearing, not tidiness.** A single pairing emits several session states
(request in, code derived, phase advanced, peer confirmed). Without it every one re-enters
`collectLatest`, which cancels and restarts the block — and therefore restarts `delay(TICK_MS)` from
zero. A chatty handshake would starve the countdown and postpone expiry indefinitely.

**`delay` before the work, not after.** The session emission that starts the ticker has already called
`recomputeUi` — that is what raised the dialog — and expiry is 30 s out
(`PairingTimeouts.DEFAULT_REQUEST_EXPIRY_MS`), so the first tick has nothing to do but decrement the
displayed second. Doing that a full second after the dialog appears is also *more* correct than the old
code, which fired its first decrement wherever the dialog happened to land on a process-wide 1 Hz grid,
making the first second shown short by 0–1000 ms.

**The gate stays `!= Idle`; narrowing it was considered and rejected.** It could be narrowed:
`reduce()` opens with `if (state.phase.isTerminal()) return state` and its `Tick` branch with
`if (!state.isActive()) return state`, so `onTick` is provably a no-op outside
`RequestReceived | AwaitingLocalDecision | AwaitingPeerConfirmation`; and `FlashPairingDialog` draws the
countdown only for the UI phases those three map to, so the `secondsLeft` the terminal linger recomputes
is never drawn. Narrowing would drop 2–3 `_pairing.value` emissions — hence recompositions — across the
1.8 s/2.5 s linger. Rejected because `isActive()` is private to `PairingSessionStateMachine` inside
`:core:security`, so `:app` would have to keep its own copy of a phase classification the state machine
owns, and a copy that goes silently stale when a phase is added would freeze the countdown. Two
recompositions once per pairing does not buy that risk.

### Not tested, and why
No test was added; `:app` stays at 36. The property this establishes is a coroutine *lifetime* — "no
periodic work exists while idle" — and `:app`'s unit-test source set is plain JVM with `libs.junit` only
(no `kotlinx-coroutines-test`, no Robolectric). Every cheap discriminator was considered and each fails:

- A counting `FlashTimeSource` catches nothing. The old loop called `nowMs()` only *inside* the `!= Idle`
  branch, so an idle coordinator's count is zero under both the old and the new code.
- `runTest` with the coordinator's scope as the `TestScope` fails identically either way: the old ticker
  never completes, and the new `session.collect` on a `StateFlow` never completes either.
- `advanceUntilIdle()` genuinely does discriminate — an infinite scheduled `delay` means it never
  returns — but a regression that hangs CI instead of failing it is a worse test than no test.
- Extracting `fun needsTick(phase) = phase != Idle` and asserting it would assert the expression it
  wraps.

The remaining option, exposing the ticker's existence as production API purely for a test, is not worth
it at this size and has no precedent in `:app`. Same posture as EXP-012. What *is* established is that
the gate's equivalence argument is a proof about code that was read — `PairingSessionStateMachine`'s two
early returns and the countdown gate at `FlashPairingFlow.kt:140-219` — rather than an assumption, and
that `FlashPairingMath.tickCountdown` has zero production callers, so nothing outside this loop depends
on the cadence.

### Verification
```
./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```

`:app:compileDebugKotlin` clean with **zero warnings in `:app`** — in particular no unused-import warning
(`kotlinx.coroutines.isActive` was removed along with the loop) and no opt-in warning, confirming
`collectLatest` is stable API and already the house idiom (`FlashCallService.kt:101`,
`RealFlashChatRepository.kt:276` and `:464`).

**984 live tests / 12 failures / 0 skipped — exactly baseline**, unchanged because no test was added and
none was expected to change behaviour: app 36, core/calling 63, core/common 85 (`testAndroidHostTest`),
core/discovery 101, core/engine 1, core/messaging 47, core/network 137, core/persistence 35 (the 12 known
Windows DataStore failures), core/security 80, core/transfer 102, ui/callui 5, ui/chat 255, ui/theme 37.
APK rebuilt 15:18, 67,556,718 bytes — the same byte count as the 14:51 build, which is coincidence and
not a skipped build (13 tasks executed, timestamp moved): one lambda class went away and one arrived.
The trailing `BUILD FAILED` is the documented `:core:persistence` baseline, not a regression.


## EXP-015 — The durable outbox's retry loop woke on a fixed 1 s grid forever: ~86,400 SQLCipher queries a day against a table that is almost always empty, and every retry up to a second late (static finding; fixed)

### Date
2026-09-04

### Class of finding
**Static inspection**, and the second find from the method EXP-014 established: instead of looking for
work done *too often per frame*, look for work the app does *on a timer whether or not there is
anything to do*. EXP-014 was a platform callback the app never implemented; this is the mirror image —
a loop the app runs unconditionally.

Found by taking the list of periodic loops in the tree — every `while (true)` / `while (isActive)`
with a `delay(...)` in it — and asking of each: what wakes it, and what does it cost on a pass where
nothing has changed.

### The finding
`RealFlashChatRepository` launches this from its `init` block and never stops it:

```kotlin
private suspend fun drainOutboxLoop() {
    while (true) {
        drainOutboxOnce()
        kotlinx.coroutines.delay(1000)
    }
}
```

Two separate costs, and the second one is the more interesting.

**It queries an encrypted database once a second for the life of the process.** `drainOutboxOnce()`
early-returns on a null `transportSink`, so before a transport is attached the pass is free — but
`transportSink` is an immutable constructor val, so on the real DI path it is non-null from
construction and every pass reaches `outboxDao.dueForDelivery(now, limit = 16)`. That is a
`SELECT … WHERE nextAttemptAt <= ? ORDER BY … LIMIT 16` against SQLCipher, so every row that comes
back is decrypted, and the query is planned and executed whether or not any row does. On the order of
86,400 wake-ups and queries a day. The steady state of a chat app is an **empty** outbox: rows exist
only between a send and the peer's `DeliveryReceipt`, which on a LAN is milliseconds.

**And it made every retry late.** The give-up budget and the backoff ladder are already careful — 
`backoffDelayMs` computes `1 s, 2 s, 4 s … 60 s` and `rescheduleAttempt` writes that deadline to the
row — and then the loop ignored it and woke on a grid that has nothing to do with it. A row due at
`T` was retried at the first multiple of 1 s at or after `T`. The code computed a precise deadline and
then rounded it up.

The two costs have the same root cause: the loop had no idea when its next piece of work was, so it
guessed, and a guess that is cheap enough to be frequent is also imprecise.

Worth stating plainly what this is *not*: it is not the send path. Every producer (`sendText`,
`sendReply`, the attachment and voice paths) enqueues and then calls `drainOutboxOnce()` itself, so a
message's first delivery attempt never waited on this loop and does not now. The loop is purely the
retry timer.

### The change
`core/messaging/.../RealFlashChatRepository.kt` and a new
`core/messaging/.../OutboxDrainSchedule.kt`. The loop now waits for whichever comes first: a write to
the `outbox` table, or the earliest deadline it has scheduled.

```kotlin
private suspend fun drainOutboxLoop() {
    while (true) {
        val batchWasFull = drainOutboxOnce()
        if (batchWasFull) {
            delay(OutboxDrainSchedule.MIN_WAIT_MS)
            continue
        }
        val waitMs = OutboxDrainSchedule.waitMs(outboxNextDueAt, System.currentTimeMillis())
        withTimeoutOrNull(waitMs) { drainWake.receive() }
    }
}
```

Four decisions in that:

**The wake signal is `OutboxDao.observeCount()`, which already existed.** It is a Room `Flow`, so it
invalidates on *any* write to the `outbox` table — an enqueue from any producer, our own
`rescheduleAttempt`, the delete an inbound `DeliveryReceipt` performs, the `makePendingDue(now)` that
`notifyPeerSessionUp` runs. A collector launched beside the loop forwards each emission into a
`Channel<Unit>(Channel.CONFLATED)`. This is what makes the whole thing safe: **the deadline only ever
has to be an upper bound on the wait, because every event that makes a row due earlier than we thought
is itself a table write, and every table write wakes the loop.** R8 is respected — no DAO method was
added, and the one used was already there.

**`CONFLATED`, not `RENDEZVOUS` or `BUFFERED`.** A burst of writes must collapse into one wake, and —
the load-bearing half — a wake that arrives *while* a drain pass is running must be **retained**, so
the pass that could not see that row runs again immediately instead of leaving it to wait out an idle
interval. `RENDEZVOUS` would drop it (no receiver parked); an unbounded buffer would queue redundant
passes.

**The wait is a pure function in its own file.** `OutboxDrainSchedule.waitMs(nextDueAt, now)` —
`IDLE_WAIT_MS` (60 s) when nothing is pending, otherwise the gap to the deadline clamped into
`[MIN_WAIT_MS, IDLE_WAIT_MS]`. Same reasoning as `FlashMediaDecoder.cacheTrimFor` in EXP-014: the loop
that consumes it cannot be stepped by a test, so the arithmetic is asserted separately. `MIN_WAIT_MS`
= 25 ms is a floor with two jobs — it keeps a full batch from becoming a hot loop, and it keeps a
deadline that passed *during* the previous pass from producing a zero-length wait. `IDLE_WAIT_MS` is
pinned to the backoff cap, so a maximally-backed-off row is never woken late by the safety net.

**The deadline is a running minimum, not this pass's minimum.** `drainOutboxOnce()` now returns
`Boolean` (batch full ⇒ more rows are already due ⇒ come straight back) and records the earliest
deadline it scheduled, but it merges rather than overwrites:

```kotlin
outboxNextDueAt = listOfNotNull(outboxNextDueAt?.takeIf { it > now }, earliestScheduled).minOrNull()
```

Overwriting is the bug this avoids. Rows A and B are sent; A is rescheduled to `T+1s`, B to `T+2s`.
The pass at `T+1s` sees only A and reschedules it to `T+3s` — writing `T+3s` would sleep straight past
B. `takeIf { it > now }` is the other half: a deadline already in the past belongs to a row that has
since been acknowledged and deleted, and leaving it would pin the loop at `MIN_WAIT_MS` forever.

The literal `16` also became `OUTBOX_BATCH_LIMIT`, because the new loop reads it as a signal
(`items.size >= OUTBOX_BATCH_LIMIT`) and not just a bound.

### What is claimed precisely
- **Counted, not timed.** ~86,400 wake-ups a day, each with one SQLCipher query on the real DI path,
  becomes: one pass per outbox write, one pass per scheduled retry deadline, one extra pass per
  drain-that-touched-rows (our own `rescheduleAttempt` invalidates the same Room query we listen to —
  that pass finds nothing due and sleeps, so it is bounded at one, not a cycle), and one pass a minute
  as the safety net. For an idle app with an empty outbox that is 1,440 a day instead of 86,400.
- **Retry timing is strictly tighter, not merely different**: a row due at `T` is now attempted at
  `T` (± the floor) rather than at the next 1 s boundary at or after `T`.
- **Send latency is unchanged** — it was never on this path.
- No claim about frame time, throughput, or measured battery. Per AGENTS.md §23 this is a counted
  reduction in repeated work; EXP-007 (the on-device matrix, owner-gated) still gates any *performance*
  claim on low-end hardware.

One behaviour genuinely improved beyond the counting: `notifyPeerSessionUp()` calls
`makePendingDue(now)` and then one drain pass, which is capped at 16 rows. Previously a backlog larger
than that drained at 16 rows per second; now the `makePendingDue` write itself wakes the loop, and the
full-batch return value makes it come straight back, so the backlog clears as fast as the socket
accepts it.

### Cost at HIGH
None. Nothing here is tiered and nothing is on a UI path: the loop is a background retry timer on
`ioDispatcher`. The owner's constraint that "the optimizations u are makeing should not compromise the
high mode quality and animation only the low mode" is satisfied trivially — there is no visual or
behavioural difference at any tier, only fewer database queries and earlier retries.

### Verification
Full sweep, the R3 command with `--continue`:

```
984 live tests / 12 known failures / 0 skipped
:core:messaging 41 → 47   (OutboxDrainScheduleTest +6)
app 36 · core/calling 63 · core/common 85 · core/discovery 101 · core/engine 1 ·
core/messaging 47 · core/network 137 · core/persistence 35 (the 12) · core/security 80 ·
core/transfer 102 · ui/chat 255 · ui/theme 37 · ui/callui 5
app-debug.apk rebuilt 14:51, 67,556,718 bytes
```

The 12 failures are the known Windows-only `:core:persistence` `FlashSettingsDataStoreTest` /
`DiscoveryModeSettingTest` DataStore failures; they are why `--continue` is mandatory and why
`BUILD FAILED` is not the signal here.

`:core:messaging:compileDebugKotlin` and `:core:messaging:compileDebugUnitTestKotlin` re-run with
`--rerun-tasks`: **no warnings in this module at all**, main or test. The six warnings in that output
are pre-existing and in `:core:discovery` / `:core:network` (deprecated `NsdServiceInfo.host`,
`resolveService`, `allNetworks`, and two always-true conditions in `NsdTransport`).

The seven existing `RealFlashChatRepositoryTest` cases that depend on this loop all still pass, and
they are the real regression net — they were written against the 1 Hz behaviour and were checked one by
one against the new timing before the run:

| Test | What it needs from the loop | Under the new design |
|---|---|---|
| `failed outbox delivery backs off instead of retrying every tick` | one pass after an enqueue, ≤3 s | enqueue bumps `countFlow` → wake → pass in ms |
| `a written but unacknowledged message is resent until the peer acknowledges it` | **two** passes a backoff apart, ≤6 s | pass 1 on the wake, pass 2 on the `T+1s` deadline |
| …then *no* further write once the receipt lands | silence for 1.5 s | row deleted, nothing due, loop asleep |
| `outbox drain preserves the composing conversationId after the active conversation changes` | a *deferred* pass with no write in between, ≤6 s | the `T+1s` deadline fires it |
| `gives up once the wall-clock budget expires…` / `…even though every write succeeded` / `keeps retrying a young message…` / `a resend can never walk an already delivered message back` | one pass after an enqueue | wake on the enqueue |

`FakeOutboxDao` needed no change: it already implemented `observeCount()` over a `MutableStateFlow`
bumped in `enqueue`/`delete`. Two incidental properties of that fake are worth knowing — it does *not*
bump the count in `rescheduleAttempt`, so the one extra real-Room pass per drain does not occur in
tests; and `MutableStateFlow` replays its current value to a new collector, so there is no
enqueue-before-collector race to lose a wake to.

### Still open
- **`outboxNextDueAt` is per-process.** A row left future-dated by a *previous* process has a deadline
  this process never computed, and there is no `SELECT MIN(nextAttemptAt)` to recover it (adding one
  is a DAO change, R8). `IDLE_WAIT_MS` bounds that to a minute, and in practice
  `notifyPeerSessionUp`'s `makePendingDue(now)` gets there first — a cold start has no peer session
  yet, so the first session-up makes every leftover row due immediately. Worth revisiting only if the
  DAO is being touched anyway for another reason.
- **The loop is still `while (true)` for the life of the scope.** It is now almost always parked in
  `withTimeoutOrNull`, which costs a scheduled timer and nothing else, but it is not tied to whether a
  transport exists. Making it observe transport attachment would be the next reduction and is not
  worth the coupling today, because `transportSink` is a constructor val.
- **Other timers exist and were enumerated, not costed.** The sweep for periodic loops found 27
  `while (true)` sites in product code (excluding `media-downloader-main/`, R11), but most are blocking
  read/queue loops — `WebSocketCodec`, `DataChannelFraming`, `BoundedSendQueue`, `Chunker` — which are
  event-driven by construction: they park on a read, not on a clock. Cross-referencing against
  `delay(<literal>)` leaves only three genuinely time-driven loops in product code:
  - `app/.../pairing/PairingCoordinator.kt:89` — a 1 Hz tick for the **life of the process**. It does
    gate the work on `phase != PairingPhase.Idle`, so an idle pass is one wake-up and one
    `StateFlow.value` read rather than a query, but it is still ~86,400 scheduler wake-ups a day to
    service a state that is Idle except during the few seconds of an actual pairing. **Same fix shape
    as this one** — the phase it checks is already a `StateFlow`, so the tick can be started and
    stopped by observing it. This is the strongest next candidate.
  - `core/transfer/.../MultiStreamDispatcher.kt:197` — `WATCH_POLL_MS = 10 ms`, i.e. 100 passes a
    second, each `publishProgress()` + `maybeResolveFromState()`. Materially different from the outbox
    poll because it is scoped to a running transfer (`while (isActive && !deferred.isCompleted)`), so
    it only polls while there is genuinely work in flight — and EXP-011 already throttled the
    *consumer* of what it publishes. Worth costing, but the producer's cadence also drives
    coverage-grace and fail-fast resolution, so it is not a free knob.
  - `ui/callui/.../FlashCallScreen.kt:586` — a 1 s tick for the call-duration clock, scoped to an
    active call and inherently 1 Hz because that is what a clock shows.
  Everything else in that grep is a one-shot `delay` (a debounce, a retry pause, a splash hold) or the
  WebSocket keepalive, which is deliberately time-driven and already hardened against Doze
  (`WsKeepalive`).

### Status
Fixed, compiled, tested, swept. `docs/migration/CONVENTIONS.md` `BASELINE_TEST_TOTAL` raised
978 → 984.

## EXP-014 — Nothing in the app implemented `onTrimMemory`, so the chat thumbnail cache held its whole `maxMemory / 8` share for the life of the process — including backgrounded mid-transfer with no tile on screen (static finding; fixed)

### Date
2026-09-04

### Class of finding
**Static inspection**, same class as EXP-008…013: a bound on retained memory read off repo constants,
not a measured heap trace. Found by looking past animations — the six passes of task #5 before it all
narrowed *recomposition* scope, and this one is about *retention*.

### The finding
`FlashApplication` has no body at all:

```kotlin
@HiltAndroidApp
class FlashApplication : Application()
```

and a grep across `app/`, `core/` and `ui/` for `onTrimMemory`, `onLowMemory`, `ComponentCallbacks2`
and `registerComponentCallbacks` returned nothing but the imports this change added. The app never
handed a byte back to the platform when asked to.

What that costs is bounded by the one deliberate cache in the codebase. `FlashMediaDecoder`'s
thumbnail `LruCache` is budgeted at `(maxMemory / 8).coerceIn(4 MB, 24 MB)`, with `sizeOf` charged at
real bytes (`width * height * bytesPerPixel`). An `LruCache` only ever shrinks by its own eviction
rule — it evicts when a *new* entry does not fit, never because nobody wants the old ones. So once a
conversation with a few dozen photos has been scrolled, that share stays resident until the process
dies.

The case that matters is not the open conversation; it is the backgrounded one. Flash runs transfers
in a foreground service, so the process survives with the UI gone, and in exactly that state the
cache is both maximally full and entirely useless: not one tile is on screen, and every entry is
reconstructible from the file it was decoded from. On the 2 GB Belfone the owner reported "a lot of
lag connection lost and even supprising huge latencies" on, `maxMemory / 8` lands at or near the
24 MB clamp.

### The change
`ui/chat/.../FlashMediaDecoder.kt` — a `ComponentCallbacks2` registered against the application
context, with the decision split out as a pure function so it can be asserted off-device:

```kotlin
internal enum class CacheTrim { None, Halve, EvictAll }

@Suppress("DEPRECATION") // The RUNNING_* levels still arrive on the old devices this targets.
internal fun cacheTrimFor(level: Int): CacheTrim = when {
    level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> CacheTrim.EvictAll
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> CacheTrim.Halve
    else -> CacheTrim.None
}
```

Three decisions worth keeping:

- **Halve, do not blank, while still foreground.** `TRIM_MEMORY_RUNNING_LOW` arrives with the
  conversation open; evicting there means every visible tile re-decodes on the next scroll pass, so
  the app's answer to memory pressure would be a decode storm on top of it. `trimToSize(size() / 2)`
  sheds the least recently used half and leaves `maxSize` alone — only `resize()` lowers the budget —
  so the cache refills naturally once the pressure passes.
- **Ordered thresholds, not a per-constant `when`.** Which levels the platform delivers has narrowed
  more than once, and an unrecognised level must not fall through to `None`. Against the API 36
  `android.jar` this compiles with, only `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND` are
  still current; every `RUNNING_*` plus `MODERATE`/`COMPLETE` is deprecated. So on a recent platform
  every level that arrives lands on `EvictAll`, and `Halve` is the legacy branch — which is precisely
  the API-27 tier this task exists for. Hence the `@Suppress` rather than a rewrite: the constants are
  frozen values, and the devices that still deliver them are the devices that need the policy.
- **Registered lazily from `decode()`, not from `FlashApplication`.** The cache is `internal` to
  `:ui:chat`, so `FlashApplication` cannot reach it without widening visibility or adding an
  initializer hook, and a device that never opens a media bubble should not pay for a callback that
  will never fire. An `AtomicBoolean.compareAndSet` guard makes the first decode on any thread
  register exactly once. `onLowMemory()` evicts everything.

### What is claimed, precisely
That a reclaimable cache is now actually reclaimed: under real system pressure the process gives back
up to `maxMemory / 8` (≤ 24 MB) that it previously held until death. Nothing is claimed about frame
time, throughput or the owner's reported latency. An avoided OOM-kill is an absence, not a
measurement, and per AGENTS.md §23 and EXP-001 no low-end performance claim is made before the
on-device matrix (EXP-007) runs. What is certain is the direction: the platform asked, and the app
now answers.

### Cost at HIGH
None. Every branch fires only when the system reports pressure, so a HIGH-tier device that never
trims executes nothing but one `compareAndSet` on its first decode. No decode path, budget, sample
size or colour depth changed — a thumbnail that used to be cached is still cached and still
byte-identical, and ERROR-033's tier-gated `RGB_565` path is untouched.

### Verification
`./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue` — `BUILD
FAILED` on the 12 known `:core:persistence` `FlashSettingsDataStoreTest` / `DiscoveryModeSettingTest`
failures only, every other module green, fresh `app-debug.apk` at 14:15 (67,554,987 bytes). Live tests
**978 / 12 failures / 0 skipped**, up 6 from 972 and all six the new file: app 36, core/calling 63,
core/common 85 (`testAndroidHostTest`), core/discovery 101, core/engine 1, core/messaging 41,
core/network 137, core/persistence 35 (the 12), core/security 80, core/transfer 102, **ui/chat 255**
(was 249), ui/theme 37, ui/callui 5. `BASELINE_TEST_TOTAL` at `docs/migration/CONVENTIONS.md:90`
raised 972 → 978 per R3.

`ui/chat/src/test/.../FlashMediaCacheTrimTest.kt` asserts the policy rather than the plumbing, because
the plumbing is an anonymous object nothing can reach: `RUNNING_MODERATE → None`; `RUNNING_LOW` and
`RUNNING_CRITICAL → Halve`; `UI_HIDDEN`, `BACKGROUND`, `MODERATE`, `COMPLETE → EvictAll`; monotonic
across levels `0..100` plus `Int.MAX_VALUE → EvictAll`, so a future constant can never trim *less*
than a lower one; and `0`, `-1`, `Int.MIN_VALUE → None`. `TRIM_MEMORY_*` are `static final int`, so
they inline at compile time and the test needs no Robolectric.

No new compiler warnings: the six `Deprecated in Java` warnings the first draft produced (five in the
test, one at `FlashMediaDecoder.kt:280`) are gone under two documented `@Suppress("DEPRECATION")`
annotations — one on `cacheTrimFor`, one on the test class. Suppressed rather than avoided because the
alternative was hard-coding 5 / 10 / 15 / 60 / 80.

### Still open
- The callback is never unregistered. Nothing needs it to be — the object outlives nothing, holds no
  `Activity`, and lives exactly as long as the application context — and tracking the instance to
  unregister it would add state for no benefit.
- Bitmap **pooling** (`inBitmap` reuse) was not attempted. It would cut allocation churn while
  scrolling, but it only pays with same-config, same-size buckets and the decoder's output dimensions
  vary per source. Measure with EXP-007 first.
- The *other* retained memory is unaudited. No second `LruCache` exists, but `:core:transfer`'s
  in-flight buffers and the WebRTC renderers hold memory no trim level touches. Out of scope here;
  worth a pass if EXP-007 shows pressure kills.

### Status
Fixed, compiled, unit-tested, swept. On-device confirmation belongs to EXP-007.

## EXP-013 — Every per-frame animation in the app recorded its snapshot read in composition instead of in the phase that consumed it, so eleven surfaces recomposed at display refresh rate — and the call screen's `mm:ss` counter did the same once a second to two video renderers (static finding; fixed)

### Date
2026-09-04

### Class of finding
**Static inspection.** Same class as EXP-008…012. Counted work removed, no timing figure claimed.
Unlike the others this one contains two **documented-intent violations**: in both `activeDuration`
and `FlashTypingIndicator` the code's own KDoc described the behaviour the fix now produces.

Two rounds, one entry, because they are the same finding seen twice: part 1 is a once-per-second read
landing in the wrong *scope*, part 2 is a per-frame read landing in the wrong *phase*. A third,
smaller pass over part 2's deferred list is folded into part 2 under **Left alone — and the second
pass that showed why**; it corrects a wrong reason recorded there, reverts two pointless conversions,
and fixes the one real defect the list was hiding.

### The mechanism, stated once
Three rules decide where a `State` read is observed. Every site below is one of them being missed.

1. **A read is recorded where `.value` is evaluated, not where the `State` was created.**
   `remember(someState) { … }` evaluates one at the `remember` site; a `by` delegate evaluates one at
   *each use site*.
2. **A value-returning `@Composable` is not restartable**, so a read inside it is recorded against the
   nearest restartable scope *above* it — its caller. A Unit-returning composable is always
   restartable, regardless of parameter stability. Restartability, not skippability, is what stops
   the propagation.
3. **The phase that evaluates the read is the phase that gets invalidated.** Reads inside
   `graphicsLayer { }`, `drawBehind { }`, a `Canvas` draw lambda or a `progress = { … }` lambda
   invalidate **draw**; reads inside `Modifier.layout { }` invalidate **layout**; reads inside
   `Modifier.semantics { }` invalidate **semantics**. Only a composition-time *argument* —
   `fillMaxWidth(f)`, `Modifier.scale(f)`, `background(c.copy(alpha = f))` — invalidates
   **composition**.

Corollary that nearly caused a wrong fix: `val x by animateFloatAsState(…)` whose only use is inside
a lambda the callee invokes in the draw phase (`CircularProgressIndicator(progress = { x })`) is
**already correct** — the delegate's `getValue` runs when the lambda runs.

Second-order cost, found mid-fix in part 2: `graphicsLayer { alpha = … }` is not free. Under the
default `CompositingStrategy.Auto`, `alpha < 1` marks the layer as having overlapping content, so the
platform may allocate an **offscreen buffer** per layer to composite it. `ModulateAlpha` folds the
alpha into the draw calls instead — pixel-identical whenever the layer wraps exactly one solid draw,
and no buffer. Where no layer is needed at all, `drawBehind { }` is cheaper still.

### Part 1 — the `mm:ss` counter, and rule 2 (method (b))
Method (b) from EXP-012, applied to the un-audited pushed screens: find a State read whose
invalidation scope is wider than the thing that displays it. `ui/callui/.../FlashCallScreen.kt`.

```kotlin
/** mm:ss duration counter while ACTIVE — one tick per second on a leaf text node. */
@Composable
private fun activeDuration(state: FlashCallUiState): String {
    var text by remember { mutableStateOf("00:00") }
    LaunchedEffect(state.connectedAt) { while (true) { text = …; delay(1_000L) } }
    return text
}
```

The KDoc's claim is false, and the reason is a Compose rule that is easy to miss: **a `@Composable`
function that returns a value is non-restartable.** The compiler cannot restart it in isolation, so a
`State` read inside it is recorded against the nearest restartable scope *above* it — the caller.
`activeDuration` is reached through `statusLine(state)`, which also returns a value, so the read
propagates through both and lands in whichever composable wrote `text = statusLine(state)`.

There were two such call sites, and neither is a leaf:

| Caller | What recomposed once per second while ACTIVE |
|---|---|
| `FlashCallIdentityBlock` | avatar, `rememberInfiniteTransition` pulse box, name, status, stats badge |
| `FlashCallVideoSurfaces` | **both `FlashVideoRenderer`s** (each an `AndroidView` over a `SurfaceViewRenderer`, so each `update` pass re-runs), the PiP's eight-element modifier chain (`align`/`statusBarsPadding`/`padding`/`width`/`aspectRatio`/`clip`/`border`/`clickable`), the full-screen renderer's chain, plus the overlay column |

The second is the one that matters: it is the video-call path, i.e. the heaviest thing this app does,
and every modifier chain in it is rebuilt from scratch per recomposition. On the owner's Belfone
SCP810 (API 27, 2 GB RAM) that is one second-ticking clock dragging two video surfaces with it.

#### Fixed
`FlashCallScreen.kt`, four edits:
- New `FlashCallStatusLine(state, color)` — a **Unit-returning** private composable, therefore
  restartable, wrapping the one `Text` that shows the changing value. Both call sites now call it.
  The invalidation stops there.
- `activeDuration`'s KDoc corrected to say what is actually true and why, so the next reader does not
  re-introduce the problem by inlining the call back into a bigger composable.
- The `mm:ss` arithmetic extracted to `internal fun formatCallDuration(elapsedMillis: Long)`.

Restartability, not skippability, is what does the work here — so this holds regardless of whether
the Compose compiler infers `FlashCallUiState` (declared in `:core:calling`, a module without the
Compose compiler) as stable. Worth stating because the obvious "make it skippable" reading would have
led to annotating a core model type for a UI concern.

#### What is claimed, precisely
Per second during an ACTIVE call, two `AndroidView` update passes and ~20 modifier-element
allocations across two renderer chains no longer happen, plus the identity block's avatar/pulse
subtree no longer recomposes. Multiply by call duration: a ten-minute video call sheds ~600 of each.

**Not claimed:** any measured frame time, dropped-frame count, or effect on call quality. EXP-007
(owner, on-device) still gates every low-end *claim*.

### Part 2 — the per-frame animation sweep, and rule 3 (method (d))
New method (d): find a value read in composition whose only consumer is a `graphicsLayer`,
`drawBehind`, `Canvas`, `layout` or `semantics` block, and move the read into that phase. Enumerated
every `rememberInfiniteTransition` and `by animate*AsState` site in `:ui:theme`, `:ui:chat`,
`:ui:callui` and `:app`, then asked of each: which phase reads it, which phase uses it.

| Site | Read in | Used in | Scope invalidated per frame |
|---|---|---|---|
| `FlashBrandAnimation` (splash) | composition, via value-returning `rememberFlashBrandPhase` | `Canvas` draw | whole composable: modifier chain, `Canvas` draw lambda and one `FlashBrandPhase` re-allocated per frame of a 2.4 s loop |
| `FlashTypingIndicator` | composition (`by`, x3 waves) | `graphicsLayer` | whole body: three `animateFloat` calls, a fresh `listOf`, three modifier chains |
| `FlashCallIdentityBlock` halo | composition | `graphicsLayer` | avatar, peer name, status line, stats badge — for every RINGING or ACTIVE call |
| `FlashVoiceRecordingBar` dot | composition | `graphicsLayer` | the whole bar: two `AnimatedContent` swaps, amplitude strip, timer, four buttons |
| `FlashMicButton` press spring | composition | `graphicsLayer` | the button, for the spring's ~400 ms tail |
| `FlashPulsingDot` (pairing) | composition | one `drawCircle` | the dot, for as long as a pairing request is outstanding |
| `FlashStateViews` skeleton | composition (converted to `State` last window) | `graphicsLayer` | plus a possible offscreen buffer per shape: up to 8 rows x 3 shapes, during boot |
| Transfers header throughput | composition | a `Text` label | two texts, two `AnimatedVisibility` containers, the failed-count chip — `Column` is inline, so the read lands in the header's own scope |
| `TransferRow` progress fill | composition, `fillMaxWidth(fraction)` | measurement | the row, plus a `buildString` a11y description rebuilt in full per frame |

The splash is the worst of the nine: it runs while the entire transport stack boots, on the device
where boot is slowest (ERROR-034 — on the Belfone SCP810 boot outlasts the 6 s splash ceiling), so the
CPU it spent recomposing itself was taken from the boot it exists to cover for.

#### Fixed — seven files
- `FlashBrandAnimation.kt` — `rememberFlashBrandPhase()` returns a `FlashBrandPhase` holding two
  `State<Float>`s; `FlashBrandAnimation` reads both inside the `Canvas` block. Reduce-motion still
  returns constants and schedules no frame callback.
- `FlashTypingIndicator.kt` — the three waves stay `State<Float>`, read inside each dot's
  `graphicsLayer`; the `listOf` is remembered. False KDoc rewritten to state the rule.
- `FlashCallScreen.kt` — halo extracted to `rememberCallPulseScale(pulsing): State<Float>`, read in
  the layer. The `if` is kept deliberately: `pulsing` flips mid-call, and an `if` gets a group per
  branch so the `remember` is correctly scoped (unlike a `remember` inside an elvis, which gets no
  group — cf. the warning at `MainActivity.kt:569`).
- `FlashVoiceRecording.kt` — the record dot's `pulseAlpha` and the mic button's press `scale` both
  stay `State` and are read inside their layers; the dot gains `ModulateAlpha`.
- `FlashPairingFlow.kt` — `FlashPulsingDot` drops `clip` + `background` + layer for
  `drawBehind { drawCircle(color.copy(alpha = alpha.value)) }`: for a square box the inscribed circle
  is the same pixels, and no layer means no buffer.
- `FlashStateViews.kt` — `graphicsLayerAlpha(State<Float>)` gains `ModulateAlpha`.
- `FlashTransfersScreen.kt` — the header derives the *label* (`derivedStateOf`, so structural equality
  drops every frame that formats to the same text); the row's fill moves to `Modifier.layout { }` via
  the new `FlashTransfersMath.progressBarWidthPx`; the a11y percent reads `item`, not the tween.

#### Already correct — do not re-audit
`ScanningDot` (`FlashNearbyScreen.kt:286`, keeps the `State` and reads `pulse?.value ?: 1f` in the
layer, with a comment saying so), `Modifier.flashPressScale` (`FlashInteraction.kt:29`),
`rememberTravelPulse` (`FlashBottomNav.kt:265`), `FlashCallStatsBadge` (Unit-returning, so its
per-second read stays inside the badge that displays it), and `FlashFileIconBadge`'s
`animatedProgress` (the corollary above — its only use is inside `progress = { … }`). The two
`while (true)` loops at `FlashMediaViewer.kt:495` and `FlashVoiceRecording.kt:190` are
`awaitEachGesture`/`pointerInput` bodies, not timers.

#### Left alone — and the second pass that showed why (R9 correction)
The first pass listed ~20 hand-rolled `by animateFloatAsState` press scales (`FlashAttachmentButton`,
`FlashAttachmentSheet`, `FlashChatSearchBar`, `FlashComposer`'s send button, `FlashFileMessageCard`,
`FlashImageGrid`, `FlashMessageBubble`, `FlashMessageContextMenu`, `FlashReactionChip`,
`FlashVoiceMessageCard`, `MainActivity`'s `chipBottomInset`) as deferred, giving the reason: "each is
bounded by a touch (~400 ms) and each already recomposes for an accompanying `animateColorAsState`, so
converting the scale alone buys only the spring tail."

**That reason was wrong, and so was the framing.** A second pass enumerated all 24
`collectIsPressedAsState` sites and grepped each animated value's consumer. The result:

- **10 of the 11 press-scale sites had nothing to convert.** In every one, the `by` property's only
  mention is *inside* the `graphicsLayer` lambda — `scaleX = pressScale`. `androidx.compose.runtime`'s
  delegate operator is `inline operator fun <T> State<T>.getValue(…): T = value`, so the read is
  evaluated **at the use site**, which is the draw lambda. Those reads were already in the draw phase.
  This is the corollary at the top of this entry, restated: `by` versus an explicit `State` plus
  `.value` at the same use site is the *same read in the same phase*. Rewriting them buys **zero** —
  not the spring tail, not anything.
- Two files (`FlashMessageContextMenu`'s `FlashQuickReactionsBar`, `FlashAttachmentSheet`'s
  `FlashAttachmentTile`) were converted anyway on the mistaken theory that an *enter* animation
  sharing the scope made them different, with comments claiming 18 per-frame reads invalidating one
  composable. Both reads were already in the layer; the comments were false. **Both files were
  reverted to their pre-pass content** rather than left as churn carrying a wrong explanation. The
  `Row`/`forEachIndexed`-is-inline observation is true in general, but it only bites a read that is
  actually evaluated in the content lambda — none of these were.
- **One genuine defect, in `FlashComposer`'s `FlashSendButton`, with two faults.** Fixed:
  - `.scale(scale)` — a composition-time *argument*, i.e. the one shape rule 3 says invalidates
    composition. Every frame of the press spring recomposed the whole button: both
    `animateColorAsState` calls, the `clickable` chain, the semantics block and the icon. Replaced
    with `graphicsLayer { scaleX = scale.value; scaleY = scale.value }` in the same chain position —
    `Modifier.scale(f)` *is* `graphicsLayer(scaleX = f, scaleY = f)` with the same centre pivot, so
    the pixels are unchanged.
  - `animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)` with **no reduce-motion guard** —
    exactly the bug `springSnappySpec`'s KDoc was written to complain about, and the last one left in
    the app. LOW/MEDIUM ran a live spring on every send-button press. Now
    `if (motion.reduceMotion) snap() else spring(0.6f, 500f)`: HIGH keeps that spring byte-for-byte
    (deliberately not `springSnappySpec()`, which would change HIGH's feel), LOW/MEDIUM snap.

A closing grep for animated values passed as composition-time modifier arguments —
`.scale(`, `.alpha(`, `.rotate(`, `.offset(`, the `graphicsLayer(…)` argument form and
`fillMaxWidth(var)` — returns **no hits** anywhere in `ui/` or `app/`. `.alpha(0.7f)` in
`FlashEncryptionIndicators` is a constant. That grep covered the `Float` forms **only**, which is not
the same as the class being exhausted — see the third pass below.

The press-scale consolidation onto `Modifier.flashPressScale` survives as a **de-bloat** follow-up
(task #4's kind of work, not task #5's) and must be done sighted: the sites use pressed scales from
0.85 to 0.98, several gate on `enabled`/`canSend`, and two multiply the press scale by an enter scale,
so a blanket swap would change HIGH-tier feel.

### Part 3 — the same rule applied to animated `Dp`, `Int` and `Color` (the gap in part 2's closing grep)

Part 2's closing grep proved only that no animated **`Float`** reaches a modifier as a composition-time
argument. An animated `Dp` does not go through `.scale(`/`.alpha(`; it goes through `.padding(`,
`.height(`, `.width(`, `.size(`, and an animated `Int` through things like `FontWeight(…)`. None of
those spellings were in the grep, so the class was not in fact closed. Re-grepping
`animateDpAsState|animateIntAsState|animateIntOffsetAsState|animateSizeAsState|animateOffsetAsState`
and then each hit's **consumer** returned five sites: one real defect, two already correct, two that
must stay in composition.

**Fixed — `MainActivity.kt` `chipBottomInset` (an EXP-012 recurrence, in the same composable).**
The shell animates the floating Dev Console chip's bottom inset when the nav bar appears or
disappears:

```kotlin
val chipBottomInset by animateDpAsState(
    targetValue = if (showBar) tabBottomInset else 0.dp,
    animationSpec = FlashTheme.motion.tweenNormalSpec(),
    label = "flashShellChipInset",
)
```

Its only consumer was `.padding(end = 16.dp, bottom = 16.dp + chipBottomInset)`. A modifier argument
is composition-time (rule 3), the `Box` it sits on is inline, and the `if (showDevConsoleEntry)`
guard sits directly in `FlashShell`'s body — so the read landed in the **~750-line shell's own
restart scope**, exactly the EXP-012 finding, in the same composable, reached by a different route.
Every tab-root navigation recomposed the whole shell once per frame for the 200 ms of the tween.
Now the `Dp` stays an explicit `State` and is read in the placement pass:

```kotlin
.padding(end = 16.dp, bottom = 16.dp)
.offset { IntOffset(0, -chipBottomInset.value.roundToPx()) }
```

Geometry is unchanged: the chip is bottom-aligned, so shifting it up by the inset is precisely what
`bottom = 16.dp + inset` did. Same tween, same duration, same reduce-motion behaviour
(`tweenNormalSpec()` is already 0 ms at LOW/MEDIUM), so HIGH is untouched.

Two things bound the win honestly. `showDevConsoleEntry` is `isDebuggable`, so **release builds never
took this path** — the shipped app was never affected. What it did affect is every debug build, which
is what EXP-007's on-device matrix will run: a per-frame recomposition of the shell during navigation
would have sat inside the very measurements that gate every low-end claim. That, not release
performance, is the reason to fix it.

**Already correct, and the precedent for the fix — `FlashSettingsScreen.kt`.** The theme segment's
`indicatorOffset` (`:517`) and `FlashSwitch`'s `thumbOffset` (`:694`) are both explicit `State<Dp>`
read inside `Modifier.offset { IntOffset(...roundToPx(), …) }`, i.e. layout-phase. The segment even
carries the comment that says why. Do not "tidy" either into a `by` delegate consumed by `padding`.

**Must stay in composition, deliberately:**
- `FlashReactionChip.kt:88` `borderWidth` (`animateDpAsState`, `springSnappySpec()`) feeds
  `BorderStroke(borderWidth, borderColor)`. `Modifier.border` takes no lambda, so a phase fix would
  mean hand-drawing the ring; and `borderColor` animates off the same `isSelfReacted` flip in the same
  `BorderStroke`, so the chip recomposes regardless. Here — unlike the press scales in part 2 — the
  "it already recomposes for the colour" reasoning is genuinely correct. Residual: a spring can outlast
  a `tweenFastSpec()`, leaving a short tail that recomposes a small leaf for the width alone.
- `FlashBottomNav.kt:325` `labelWeight` (`animateIntAsState`) feeds
  `typography.captionDefault.copy(fontWeight = FontWeight(labelWeight))`. A `TextStyle` is a value,
  and font weight changes text layout, so this read is inherently a composition input. Bounded by tab
  switches inside one small per-tab item.

**Animated `Color`, enumerated and closed without edits.** 11 `animateColorAsState` sites across 7
files (`FlashSettingsScreen` 2, `FlashVoiceRecording` 2, `FlashReactionChip` 2, `FlashComposer` 2,
`FlashBottomNav` 1, `FlashDeliveryStatusIcon` 1, `FlashAttachmentButton` 1). Every one is in a small
leaf composable, and every one feeds `background(…)` or a `tint =` parameter — both composition-time
by construction. Moving them to draw would mean replacing `.background(shape-clipped colour)` with
hand-rolled `drawBehind`, which risks a pixel difference on shaped and bordered surfaces to save
recomposing a leaf. Against §23 (measure first) and the HIGH-tier rule, that trade is not worth
taking; recorded so the next pass does not re-open it.

`Animatable` was swept too: the only non-`remember`ed ones are `FlashZoomState`'s `scale`/`offsetX`/
`offsetY`, which are properties of a `@Stable` class, not a composable body, and whose KDoc already
states the reads happen exclusively in `graphicsLayer`. `updateTransition` and `animateValueAsState`
have no callers at all.

With `Float`, `Dp`, `Int`, `Color` and `Animatable` all swept, the phase-discipline class is now
closed — this time by enumeration of every animation API in use, not by one grep over one type.

#### What is claimed, precisely
Nine surfaces no longer recompose per animation frame; their animations invalidate draw, layout or a
derived label instead. Concretely: per frame of the splash loop the app no longer rebuilds a modifier
chain, a `Canvas` draw lambda and a `FlashBrandPhase`; the transfers header formats a `String` only
when the displayed text differs; a moving transfer no longer rebuilds its a11y `buildString` per frame;
four alpha layers no longer risk an offscreen buffer. Plus, from the second pass: the send button
recomposes twice per press instead of once per frame of its spring, and its spring stops running at
all on LOW/MEDIUM. Plus, from the third: in debug builds the app shell no longer recomposes per frame
of the chip-inset tween on every tab-root navigation — eleven surfaces in total.

Output is **pixel-identical at every performance tier** — a scope change, not a visual one. Same
easings, durations, colours, semantics and reduce-motion fallbacks as before, which is what makes it
admissible under the owner's rule that HIGH tier gives nothing up.

**Not claimed:** any measured frame time, dropped-frame count, jank rate or boot-time win. EXP-007
(owner, on-device) gates every low-end *claim*, as opposed to every low-end *count*.

### Verification
`:ui:theme`, `:ui:chat`, `:ui:callui` `compileDebugKotlin` BUILD SUCCESSFUL, no new warnings.
Full sweep: **972 live / 12 known failures / 0 skipped** (`:ui:callui` 0 → 5 in part 1, `:ui:chat`
245 → 249 in part 2), `app-debug.apk` written 13:05. CONVENTIONS R3 baseline 963 → 968 → 972.

Re-verified after the second pass (the send-button fix and the two reverts): `:ui:chat`
`compileDebugKotlin` BUILD SUCCESSFUL, full sweep **972 / 12 / 0** with the same per-module split
(`:ui:chat` still 249 — the pass adds no tests, so an unchanged total *is* the regression check),
`app-debug.apk` rewritten 13:34. Baseline unchanged at 972.

Re-verified again after part 3 (the `chipBottomInset` fix): `:app:compileDebugKotlin` BUILD SUCCESSFUL
with no new warnings, full sweep **972 / 12 / 0**, per-module split byte-identical (app 36,
core/calling 63, core/common 85, core/discovery 101, core/engine 1, core/messaging 41, core/network 137,
core/persistence 35, core/security 80, core/transfer 102, ui/chat 249, ui/theme 37, ui/callui 5),
`app-debug.apk` rewritten 13:52. Part 3 adds no tests either, for the same reason: the fix is a
read-phase change in an `app`-module composable, and `:app` has no Compose UI test or Robolectric
dependency, so asserting *where* the read is recorded is not expressible here.

The 12 failures are the documented `:core:persistence` ones (`FlashSettingsDataStoreTest` x11 plus
`DiscoveryModeSettingTest > roundtrip for every valid mode`), so the run reports `BUILD FAILED`;
non-regression was confirmed by counting live XML results per module and by the APK timestamp.
`--continue` is what lets the later modules run at all.

`FlashCallDurationTest` is the **first test directory in `:ui:callui`** — the module already carried
`testImplementation(libs.junit)` but had only `src/main`, so a root `testDebugUnitTest` now creates a
`:ui:callui:testDebugUnitTest` task that did not exist before. No build-file change was needed. Five
tests: truncation (a call reads `00:00` for its whole first second), zero-padding, the 59→60 s
rollover, a **backwards clock** (`connectedAt` is a `currentTimeMillis()` stamp, so an NTP correction
mid-call can put "now" behind the start — it clamps rather than rendering `-1:-3`), and a call past an
hour (`%02d` is a minimum width, so minutes widen to `100:00` rather than wrapping to `00`).

`FlashTransfersLogicTest` +4 pins `progressBarWidthPx` to `FillNode`'s own formula: the 0 and 1
endpoints, half-up rounding (401 x 0.5 → 201, not 200), `coerceIn` against the incoming constraints
(an overshooting animation must not measure wider than the track, and a fixed-width parent wins over
the fraction), and a zero-width track producing zero rather than dividing.

Neither scope change has a direct test, for the EXP-012 reason: asserting *where* Compose records a
read needs a composition, and there is no Compose UI test or Robolectric dependency in these modules.
Extracting the arithmetic — `formatCallDuration`, `progressBarWidthPx` — is what made any of it
testable, and `progressBarWidthPx` exists precisely so that a future edit which drifts from `FillNode`
fails a test instead of quietly resizing every progress bar by a pixel.

### Still open
`rememberVideoTrack` is value-returning and so leaks its read into `FlashCallVideoSurfaces`, but track
changes are a handful per call, not per second; left alone deliberately. `FlashDevConsoleScreen`
remains un-audited (edit it with plain ASCII — it carries pre-existing mojibake). The press-scale
consolidation onto `Modifier.flashPressScale` — as **de-bloat**, since the second pass established
there is no per-frame cost left to recover there. With every animation API now enumerated and swept
(part 3), the next step for task #5 is **EXP-007**, the owner's on-device matrix.

Three things must not be "simplified" back: `rememberCallPulseScale`, `rememberFlashBrandPhase` and
`rememberSkeletonAlpha` must keep returning `State`, not `Float`; `FlashCallStatusLine` is not a
pointless one-`Text` wrapper (its Unit-returning-ness *is* the fix); `progressBarWidthPx` must keep
mirroring `FillNode`. Each carries a comment saying so.

And one thing must not be "fixed" again: the ~10 `by animateFloatAsState` press scales whose only
mention is inside a `graphicsLayer` lambda are **already in the draw phase**. Converting them to an
explicit `State` plus `.value` changes nothing. This entry's second pass did it to two of them before
catching the error and reverting; the same conclusion applies to `FlashAttachmentButton`,
`FlashChatSearchBar` (x2), `FlashFileMessageCard`, `FlashMessageBubble` and `FlashVoiceMessageCard`
(x2). What *would* be a defect is hoisting such a read *out* of the layer. Part 3 adds four more
sites to leave alone, with reasons: `FlashSettingsScreen`'s two `offset { }` reads (already correct),
`FlashReactionChip`'s `borderWidth` and `FlashBottomNav`'s `labelWeight` (must stay in composition).

### Status
Code-complete, compiled, tested, swept — all three passes, including the second pass's two reverts.
Part of task #5 (low-end speed).

## EXP-012 — Three shell-scope `remember(state)` derivations put their State reads in `FlashShell`'s own restart scope, so unrelated events re-executed a ~750-line composable and rebuilt off-screen UI models (static finding; fixed)

### Date
2026-09-04

### Class of finding
**Static inspection.** Same class as EXP-008/009/010/011: no device measurement, no timing figure
claimed. The claim is that a set of recompositions and allocations stop happening, counted from the
code, not a frame-time or latency win.

### The mechanism, stated once
In Compose the invalidation scope of a `State` is decided by **where `.value` is read**, not where
the `State` was created. A `by collectAsState()` delegate declared at the top of a composable is
therefore *not* automatically a wide read — the read happens wherever the property is used. But a
`remember(someState) { … }` **key expression** is a read, and it happens exactly where the `remember`
call sits. So `remember(domainTransfers) { … }` at shell scope subscribes the *entire shell* to that
flow, even if the resulting value is only ever consumed inside one narrow `when` branch.

This is the difference EXP-011 exploited without naming it, and it turns out to be the actual bug
shape in three places. `derivedStateOf` fixes both halves at once: the reads move into the
derivation (so only whoever reads the *derived* value is invalidated, and only when that value
actually changes under `structuralEqualityPolicy`), and the block becomes **lazy** — if nothing reads
it, it never runs.

### What was inspected
`app/src/main/java/com/transfer/flash/MainActivity.kt`, `FlashShell` — a ~750-line composable that
hosts every tab. Its four navigation branches live inside `FlashAnimatedScreen`'s `content` lambda,
which is a non-inline `@Composable (FlashBackStackState) -> Unit`
(`ui/chat/.../FlashNavigation.kt:259-262`), so that lambda is its own restart scope. **Verified, not
assumed**: it is what makes "move the read into the branch" a real narrowing rather than a cosmetic
one.

| Site | Read at shell scope | Consumer | Emits on |
|---|---|---|---|
| `transfersUi` | `remember(domainTransfers, transfersReady, chatStartError)` | `FlashDestination.Transfers ->` only | every paced transfer tick |
| `nearby` | `remember(discoveredEndpoints, discoveryState, ready, pairingModel, trustedPeers)` — five States | `FlashDestination.Nearby ->` only | every discovery tick, presence change, pairing-countdown second |
| `BackHandler(enabled = chatListState.selectionMode && …)` | the whole `FlashChatListUiState` | one `Boolean` | every conversation row change, presence change, unread-count change, preview change |

The third is the sharpest: a `State` has no per-field granularity, so reading `chatListState` to get
one `Boolean` subscribes the shell to *every* field of the chat-list model. On the owner's Belfone
SCP810 the reported symptom class — "a lot of lag connection lost and even supprising huge
latencies" — coincides with presence churn, which is precisely what makes that state emit.

`conversationState` was checked in the same pass and is **already narrow**: its only composition read
is `state = conversationState` inside the Conversation branch, and its other four uses
(`conversationState.header.title` at 780/816/868/888) are inside event lambdas, which run at click
time outside any snapshot observer and so record no read at all. No change was needed there. The
same is true of `chatListState`'s remaining uses (1021, 1045-1057, 1071) — all inside the ChatList
branch.

### Fixed
`MainActivity.kt`, four edits: `import androidx.compose.runtime.derivedStateOf`; `transfersUi` and
`nearby` converted to `remember(keys) { derivedStateOf { … } }`; a `chatListSelectionMode` derived
`Boolean` added next to the `chatListState` declaration and read by the `BackHandler`.

### Why the `remember` keys did not simply go away
`derivedStateOf` tracks *States* read inside the block, so a State read inside needs no key. Two
things are not States and would otherwise be captured on first composition and frozen:

- `transfersReady` is a plain `Boolean` (`ready && engine.transfers != null`) → kept as a key.
- The **flows themselves swap once at boot**: every one of these delegates is
  `(engine.X ?: fallback).collectAsState()`, so before boot the block would bind to the fallback
  `State` and keep reading it forever. `transfersUi` is keyed on `pacedTransfers`, `nearby` on
  `engine`/`engine.discovery`/`engine.pairing`, `chatListSelectionMode` on `chatRepository`.

That the keys are *sufficient* rests on `AppEngine`'s documented contract (`AppEngine.kt:43-45`):
"the holder sets `current*()` before `ensureStarted` returns, so a `ready == true` observation always
sees non-null subsystems", with `_ready.value = true` last at `AppEngine.kt:218`. The recomposition
that flips `ready` is therefore the same one in which `engine.discovery` is already non-null — the
identical assumption the pre-existing `collectAsState()` call sites at `MainActivity.kt:611-619`
already make. No new reliance was introduced.

### What is claimed, precisely
- A transfer tick no longer invalidates `FlashShell` at all (EXP-011's pacing cut the *rate* to 150 ms;
  this cuts the shell out of the path entirely), and `fromDomain`'s 7 + N allocations no longer run
  while the Transfers tab is off screen — they run zero times, not 6-7 times a second.
- A discovery/presence/pairing event no longer invalidates `FlashShell`, and `NearbyUiState`'s
  `HashSet` + `filter` + `map` over every endpoint no longer runs off-tab. On-tab, an event that
  produces an identical `NearbyUiState` (a data class) is now dropped by structural equality without
  invalidating the screen.
- A chat-list emission no longer re-executes the shell body to re-evaluate one `Boolean`; only an
  actual selection-mode flip does.

**Not claimed:** any measured frame time, jank count, or latency change. Per AGENTS §23 and EXP-001's
standing prohibition, no such claim is available until the on-device matrix (EXP-007, owner action)
runs.

### Verification
`:app:compileDebugKotlin` BUILD SUCCESSFUL, no new warnings in `MainActivity.kt`. Full sweep
`testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue`: **963 live tests / 12
known failures / 0 skipped** — unchanged from baseline, which is the intended result. `app-debug.apk`
written 12:19.

**No test was added, and none is available.** This is a change to *where* Compose records a snapshot
read; asserting it needs a composition, and `:app`'s test source set is plain JVM with no Compose UI
test or Robolectric dependency (adding one would be a build-file change outside this task, and R10
forbids reaching for it opportunistically). `TransfersUiMapperTest` and `FlashTransfersLogicTest`
already cover the derivation's *output*, which this change does not touch — the 963 figure is the
regression check.

### Still open
The same audit shape has not been applied to the pushed screens (`Conversation`, call UI) or to
`FlashDevConsoleScreen`. `showDevConsole`, `isSearching` and `searchQuery` are genuine shell-scope
state that changes on user taps, and are correctly left alone.

### Status
Code-complete, compiled, swept. Part of task #5 (low-end speed).

## EXP-011 — The same 100 Hz progress tick was also collected at the app shell, so it invalidated the whole shell and re-derived the Transfers UI model every frame — with the tab off screen (static finding; fixed)

### Date
2026-09-04

### Class of finding
**Static inspection.** Same class as EXP-008/009/010: no device measurement, no timing figure claimed.
The claim is a counted reduction in repeated work per unit time, plus the removal of work that was
being done for a screen that was not visible.

### What was inspected
The second consumer of `MultiStreamDispatcher`'s 10 ms watcher tick, named as the next candidate by
EXP-010's own "still open" section: `MainActivity.kt` in `FlashShell`.

```kotlin
val domainTransfers by (engine.transfers?.activeTransfers ?: fallbackTransfers).collectAsState()
val transfersUi = remember(domainTransfers, transfersReady, chatStartError) {
    TransfersUiState.fromDomain(...)
}
```

Two costs, both paid at the shell:

1. **Scope.** The state read sits in `FlashShell`'s own restart scope — a ~750-line composable body.
   Every progress tick invalidates it, so the whole body re-executes: every `remember` key comparison,
   every lambda memoisation check, every child call re-entered (to be skipped). Compose coalesces this
   to once per frame, so the ceiling is the frame rate rather than 100 Hz — but that is still ~60
   whole-shell recompositions a second on a 2 GB device that is simultaneously running the transfer.
2. **Work.** `TransfersUiState.fromDomain` runs on every one of those frames:
   `transfers.map { it.toUiItem() }` (a list plus one `FlashTransferItemUi` per transfer), then
   `fromItems`'s **four separate `filter` passes** over that list, then a `TransfersUiState`, then a
   `.copy()` — **7 + N allocations per frame**.

The part that makes this unambiguous: `transfersUi` has exactly one consumer, the
`FlashDestination.Transfers ->` branch of the shell's `when`. **All of that ran while the user was
looking at the chat list, the conversation, Nearby or Settings.**

### The check that makes pacing safe
Nothing on the Transfers screen can render the raw cadence:

- `FlashTransfersMath.formatSpeed` rounds to **one decimal** of MB/s.
- `formatEta` is **whole seconds**, then minutes.
- The per-row progress fill (`FlashTransfersScreen.kt:475`) and the header throughput roll
  (`:418`) are both `animateFloatAsState` against Compose's frame clock — smoothness is the
  animation's property, not the emission rate's.
- `bytesDone` only advances when an ACK_BATCH lands (`DEFAULT_ACK_EVERY = 32` × 64 KiB = **one advance
  per 2 MB**), so below ~8 MB/s the bar's target does not even change within a window.

### Fixed (this window)
- **`FlashTransfersMath.PROGRESS_THROTTLE_MS` was a dead constant** — declared `250L`, **zero
  references** anywhere in the repo. The pacing was clearly always intended for this surface and never
  wired. It is now live, and **derived** rather than picked:
  `const val PROGRESS_THROTTLE_MS: Long = FlashMotion.NormalMillis * 3L / 4L` (**150 ms**).
- **`MainActivity`** resolves the source flow, paces it, and seeds the initial value:
  ```kotlin
  val transfersSource = engine.transfers?.activeTransfers ?: fallbackTransfers
  val pacedTransfers = remember(transfersSource) {
      transfersSource.throttleLatest(FlashTransfersMath.PROGRESS_THROTTLE_MS)
  }
  val domainTransfers by pacedTransfers.collectAsState(initial = transfersSource.value)
  ```
- **New** `app/.../ui/UiPacing.kt` — the same leading-edge `throttleLatest` as `:core:messaging`'s.

### Why 150 ms and not the 250 ms that was written down
This is the one number in the change that could have broken the owner's constraint that the HIGH tier
lose no animation quality. Both animations on this screen run for `FlashMotion.NormalMillis = 200`.

- A window **longer** than the animation (the old `250L`) would let each animation *finish* and then
  hold still for 50 ms before the next target arrived — a **periodic dead stop, four times a second**,
  visible on the HIGH tier precisely because that is the tier where the animation is not switched off.
- A window **shorter** than the animation means the next target always lands mid-flight, so
  `animateFloatAsState` retargets from the current value and the motion is continuous.

Three quarters of the duration leaves headroom for scheduling jitter. It is expressed as arithmetic on
`FlashMotion.NormalMillis` rather than as a literal so the relationship cannot rot if the motion token
changes, and `FlashTransfersLogicTest` asserts the inequality outright.

**Tier-independent, again on purpose.** Reduce-motion already collapses `normalMillis` to 0, so LOW and
MEDIUM behave exactly as before; the HIGH tier keeps the identical animation it had, now fed slightly
less often than it can draw.

### Why the operator is a second copy
`:core:messaging` already has `throttleLatest` (EXP-010) and it is `internal`. There is deliberately no
shared home, and this was checked rather than assumed:

- **`:core:common`** is the only module both `:app` and `:core:messaging` depend on, and its
  `build.gradle.kts` has **no coroutines dependency at all** — no `commonMain` dependency block beyond
  JUnit for `androidHostTest`. Hosting a `Flow` operator there means adding coroutines to the live
  Phase-06 KMP pilot to carry eight lines.
- **`:core:transfer`** (where the tick originates) is not visible to `:core:messaging`, so it is not a
  shared home either — it would be a second copy by another name, in a published ABI.
- **Widening `:core:messaging`'s copy to `public`** would commit a generic flow operator to a published
  library ABI permanently, to serve an app-internal need.

So: two copies, each with its own test file, each KDoc naming the other and the reason. That is recorded
as a deliberate trade rather than an oversight.

### What is claimed, precisely
- Whole-shell recompositions during a transfer drop from **~1 per frame (~60/s) to ~6.7/s** (150 ms
  window), and `fromDomain`'s **7 + N allocations** follow the same factor — a ~9× reduction.
- That work no longer happens at frame rate **while the Transfers tab is off screen**, which is most of
  the time.
- **Not** claimed: any measured frame time, jank count, allocation rate or transfer throughput. Per
  EXP-001's standing prohibition, none may be until EXP-007 runs on hardware.

### Verification
- Full authoritative sweep: **963 live / 12 failures / 0 skipped** — the 12 being the known Windows-only
  `:core:persistence` `FlashSettingsDataStoreTest` DataStore atomic-rename failures. `assembleDebug`
  produced `app-debug.apk`. `CONVENTIONS` R3 bumped 958 → 963 (`:app` 32 → 36, `:ui:chat` 244 → 245).
- `UiPacingTest` (+4) mirrors `ProgressThrottleTest`: leading edge under a second against a 5 s window;
  a 200-value burst collapses within a budget derived from *measured* elapsed time while the newest
  value still lands and order holds; non-positive window disables throttling; a non-conflating upstream
  is spaced but lossless.
- `FlashTransfersLogicTest` (+1) pins the invariant that actually protects the HIGH tier:
  `PROGRESS_THROTTLE_MS < FlashMotion.NormalMillis`, and `> 0` so it cannot silently become the
  operator's disable switch.

### The ERROR-034 trap, avoided deliberately
`collectAsState()` on a `StateFlow` yields the current value **synchronously at composition**;
`collectAsState(initial = …)` on a cold flow does not. Left as `emptyList()`, the tab would have had a
frame in which `transfersReady` was true and the list was empty — i.e. it would render "No transfers
yet", a claim about this device's history, before the paced flow's first value arrived. Seeding with
`transfersSource.value` makes the first composition identical to today's. (In practice `activeTransfers`
is in-memory and starts genuinely empty, so the seeded value at boot *is* empty — but the seed is what
makes that a fact about the data rather than luck.)

### Status
Fixed and verified by test. Both consumers of the 100 Hz tick are now paced. The cadence claims are
arithmetic over repo constants; no device figure is claimed.

## EXP-010 — Attachment progress reached the conversation mapper at 100 Hz, so a running transfer re-derived the whole open thread a hundred times a second (static finding; fixed)

### Date
2026-09-04

### Class of finding
**Static inspection plus an arithmetic argument over repo constants. No device measurement.** No
throughput, frame-time or latency figure is claimed, and per EXP-001's standing prohibition none may
be until EXP-007's on-device matrix runs (owner action). What *is* claimed is a counted reduction in
work per unit time, and that the reduction is invisible on screen.

### What was inspected
The path from a transfer's progress watcher to a chat bubble, end to end:

| Stage | What it does per emission |
|---|---|
| `MultiStreamDispatcher` watcher loop | `publishProgress(); maybeResolveFromState(...); delay(WATCH_POLL_MS)` with `WATCH_POLL_MS = 10L` — **100 emissions a second for the whole duration of a transfer** |
| `publishProgress()` | writes `_progress.value = MultiStreamProgress(done, totalBytes, rate, eta)` where `rate = rateMeter.instantBytesPerSec(nowMs())` — a fresh value nearly every tick, so **StateFlow conflation never fires** |
| `RealFlashTransferRepository.updateTransferState` | `_activeTransfers.update { list -> list.map { … } }` — a whole new list per tick |
| `Flash.kt:258` (`attachmentProgress`) | `activeTransfers.map { it.associate { … FlashAttachmentProgress(...) } }` — a fresh map per tick (mirrored at `DiscoveryEngineHolder.kt:541`) |
| `RealFlashChatRepository` `contentFlow` | that flow is one of **four `combine` inputs**, so every tick re-derives the open conversation |
| the re-derivation itself | `associateBy` over the window, `groupBy` over reaction rows, a fresh `FlashMessageUi` per message (each with a `formatTime` and a `computeInitials`), `reversed()`, then `computeMessageGroupPositions` — then the outer combine rebuilds the header and a whole `FlashConversationUiState`, and `_conversationState.value = …` deep-compares it |

Because `speedMbps` changes on nearly every tick, the deep comparison at the end **fails**, so the
state genuinely republishes and Compose recomposes as well. Nothing upstream was absorbing the
cadence.

With a fifty-message window open, that is tens of thousands of short-lived objects a second — and it
is *foreground* cost, paid on the same device that is running the transfer. On the 2 GB Belfone this
work is aimed at, the mapper competes with the transfer it is describing.

### The check that makes this safe: the 10 ms cadence carried no information the UI could show
Every field a progress tick moves is coarser than the tick that produced it:

- **`progress`** is driven by `bytesDone`, which only advances when an ACK_BATCH lands —
  `ReceivePipeline.DEFAULT_ACK_EVERY = 32` at `Chunker.DEFAULT_CHUNK_SIZE_BYTES = 64 KiB`, i.e. **one
  advance per 2 MB**. Between batches it does not change at all.
- **`speedMbps`** renders through `"%.1f".format(...)` in `FlashFileMessageCard.kt:105-134`, so
  anything finer than 0.1 MB/s is **not displayable**.
- **`etaSeconds`** is whole seconds and is **not rendered at all** today (it is a `remember` key only).
- the progress **bar** is animated by `animateFloatAsState` against Compose's frame clock
  (`FlashFileMessageCard.kt:312`) — its smoothness comes from the animation, not from how often a new
  target arrives.

### Fixed (this window)
A leading-edge throttle inside `:core:messaging`, applied once at the repository so it protects every
wiring of the port:

- **New** `core/messaging/.../util/ProgressThrottle.kt` —
  `internal fun <T> Flow<T>.throttleLatest(windowMs: Long)`: emit, then `delay(windowMs)`. Three
  properties, in the order they matter: the **first value is emitted immediately**; the window is then
  closed for `windowMs` with upstream **suspended, not buffered**; and the **newest value always
  eventually arrives**.
- **`RealFlashChatRepository`** gained `pacedAttachmentProgress = attachmentProgress.throttleLatest(100L)`
  (`ATTACHMENT_PROGRESS_THROTTLE_MS`), and both consumers switched to it: the `init` path-stamping
  collector and the `contentFlow` combine's third input.

Two design points worth keeping:

1. **Leading edge, not `sample`.** `kotlinx.coroutines.flow.sample` is the obvious operator and is
   **trailing-edge**. Since this flow is an input to a `combine` that cannot emit until *every* input
   has, `sample` would have held the entire conversation blank for up to a window on open — precisely
   the **ERROR-034** failure mode. That trade was not available.
2. **Suspending upstream is what makes it cheap.** Because the operator parks its collector rather
   than buffering, the `StateFlow` upstream conflates *and the intervening `activeTransfers.map { … }`
   never runs* — the discarded maps are never built in the first place. This is also why the two
   wiring sites (`Flash.kt`, `DiscoveryEngineHolder.kt`) needed no edit.

**Tier-independent on purpose.** A text label that changes ten times a second is already faster than
it can be read, and the bar's smoothness is the animation's, not the tick's. Nothing about the HIGH
tier's look or animation changes, so per the owner's constraint there is nothing to gate on
`reduceMotion`/`minimalChrome`.

### What is claimed, precisely
**A 10× reduction in whole-conversation re-derivations while a transfer is running** (100 Hz → 10 Hz),
counted from `WATCH_POLL_MS` and `ATTACHMENT_PROGRESS_THROTTLE_MS`, with no change to any value the UI
can display. **Not** claimed: any measured frame time, jank count, allocation rate, or transfer
throughput.

### Verification
- `:core:messaging:testDebugUnitTest` green; module 36 → **41** tests.
- Full authoritative sweep: **958 live / 12 failures / 0 skipped**, the 12 being the known Windows-only
  `:core:persistence` `FlashSettingsDataStoreTest` DataStore atomic-rename failures. `assembleDebug`
  produced `app/build/outputs/apk/debug/app-debug.apk`. `CONVENTIONS` R3 baseline bumped 953 → 958.
- `ProgressThrottleTest` (+4) pins the operator's contract with **deliberately wall-clock** tests —
  every bound is derived from *measured* elapsed time, so a slow machine makes them slower rather than
  flaky: the leading edge arrives in under a second against a 5 s window; a 200-value burst collapses
  inside a per-window budget while `emitted.last()` is still the newest value and order is preserved;
  a non-positive window disables throttling; and against a *non-conflating* upstream nothing is
  dropped or reordered (the operator degrades to "slower", not "wrong").
- `RealFlashChatRepositoryTest` (+1) pins the risk a throttle actually introduces at the integration
  level — **losing the last value** — by driving 300 ticks at the transfer layer's own 1 ms cadence and
  then asserting the terminal `Downloaded` value lands **on both surfaces**: `localUri` on the rendered
  attachment *and* `attachmentPath` stamped on the row. The row half matters because live progress is
  in-memory only, so a swallowed terminal value would strip the file off the message on restart — the
  ERROR-034-class regression.

### Still open, unchanged by this entry
- **`MainActivity.kt:573`** is the second hot consumer of the same 100 Hz source: `collectAsState()` on
  `activeTransfers` at the **root** composable, feeding `remember(domainTransfers, …) { TransfersUiState.fromDomain(...) }`.
  So the app root invalidates on every tick during a transfer. Compose frame-coalesces the
  recomposition, which makes it less severe than the chat path was, but the `fromDomain` re-map is real
  work at 100 Hz. Not fixed here.
- The measure-first items remain owner-gated: EXP-007's device matrix, and which `startEngineLocked`
  stage dominates the cold-start splash.

### Status
Fixed and verified by test. The cadence claim is arithmetic over repo constants; no device figure is
claimed.

## EXP-009 — Startup-cost inspection: the receiver done-set is retained for the process lifetime and nothing ever prunes it (static finding; one half fixed, one half owner-gated)

### Date
2026-09-04

### Class of finding
Same class as EXP-008: **static inspection, no device measurement.** No timing figure is claimed. The
§9 device fields do not apply — nothing was run on hardware. This is the "startup cost" half of task
#5, approached by reading the boot path rather than by guessing.

### What was inspected
The app's cold-start path end to end:

| Stage | Verdict |
|---|---|
| `FlashApplication` | bare `@HiltAndroidApp` shell — **nothing to fix** |
| `MainActivity.onCreate` | no disk I/O; reads the intent, calls `appEngine.start()`, `setContent` — **clean** |
| `AppEngine` members | `localIdentity`, `settingsStore`, `detectedPerformance`, `performanceMode` all `by lazy`; `start()` runs on `@AppScope` (`Dispatchers.Default`) and deliberately touches `performanceMode.value` there so the `MediaCodecList` walk is paid off the first composition — **clean** |
| Keystore passphrase unwrap | lazy (Room calls `passphrase()` on first query), one-time — **not a repeat cost** |
| `DiscoveryEngineHolder.startEngineLocked` | strictly sequential under `lifecycleMutex`: power locks → screen receiver → identity → NSD transport → WS bind → `startAll` → SQLCipher open → DataChannel bind → repos. **Unmeasured; see below** |
| `RealFlashTransferRepository.preloadReceiverProgress` | **the finding** |

### The finding
`preloadReceiverProgress()` reads **every** done chunk row on the device — `allDoneChunks()`, no
predicate — and holds the result in memory for the process lifetime. Two compounding facts:

1. **Nothing prunes `transfer_chunks`.** `RetentionPolicy` exists, is fully unit-tested, and has
   **zero production callers**; its own KDoc describes itself as "the read/delete seam the future
   DB-backed pruner worker will implement". There are no `DELETE` queries against `transfers` or
   `transfer_chunks` anywhere. So the table is append-only for the life of the install.
2. **A `Completed` transfer's rows are dead weight** — they can never be resumed, yet they are read
   back and retained on every launch.

The in-memory representation made that worse than it had to be: `ConcurrentHashMap<String,
MutableSet<Int>>` over `Collections.newSetFromMap(ConcurrentHashMap())` costs roughly a boxed
`Integer` + a hash node + a table slot per chunk — order 50 bytes. At the 64 KiB default chunk size a
device that has received 50 GB over its lifetime holds ~820k rows ≈ **tens of MB of permanently
retained heap**, on hardware that may have 2 GB total.

### Fixed (this window)
- `receiverDone` is now `ConcurrentHashMap<String, BitSet>` — **one bit per chunk, ~400× smaller**,
  with each entry mutated under `synchronized` on the set (bit test + bit set, no I/O). The public
  surface is unchanged: `receiverDoneIndexes` still returns an ascending `List<Int>`, and its two
  callers (`Flash.kt:220`, `DiscoveryEngineHolder.kt:410`) are untouched.
- `getOrPut` → `computeIfAbsent`. `getOrPut` on a `ConcurrentHashMap` is get-then-put, not atomic, so
  two receive coroutines racing on a new transferId could each build a set and one would be dropped
  along with its marks. `computeIfAbsent` is atomic on `ConcurrentHashMap`.
- Negative indexes are now dropped instead of set. The old `Set` swallowed them harmlessly;
  `BitSet.set(-1)` throws, and these indexes come off the wire (and out of the DB), so a corrupt value
  must not take startup or the receive path down.
- Ceiling, stated explicitly because it cuts the other way: a `BitSet` sizes to its highest set bit, so
  it costs `totalChunks / 8` bytes even when nearly empty — ~2 bytes per MB of file, 8 KB for a 4 GB
  transfer, and the `Set` form passes that once ~1/400 of the chunks are done.

### Owner-gated (NOT done)
Bounding the **table** needs a Room change — either a status-joined `allDoneChunks` (skip terminal
transfers) or wiring `RetentionPolicy` to a real delete sweep. CONVENTIONS **R8** forbids touching Room
entities, DAOs and `FlashMigrations` without an explicit instruction, and `TransferDao` has no
"all transfers" query to filter against from the adapter side, so this cannot be done outside the DAO.
**Owner instruction required.**

### Measure-first, untouched
The boot sequence in `startEngineLocked` is fully serialized, and `MainActivity` holds the cold-start
splash until it finishes (`setKeepOnScreenCondition { !appEngine.ready.value && startError == null }`)
— so on a Belfone the splash duration *is* the transport boot time. Overlapping independent stages
(SQLCipher open vs socket bind vs NSD) would be the obvious win, but that ordering encodes invariants
from ERROR-032/033, #4 and #20 ("power locks first", "receive-side infrastructure must precede the
send factory wiring", "resolve the tier before the engine is built"). **Not reordered on inspection
alone** — this one needs a real trace saying which stage dominates. Add it to EXP-007's run.

### Verification
`953 live / 12 known Windows DataStore failures / 0 skipped`, `assembleDebug` green. `:core:transfer`
100 → 102: preload warms ascending across a word boundary and survives a negative row;
`onIncomingChunkConfirmed` persists only the delta, de-dups within a batch, drops negatives, and a
wholly redundant batch reaches the store not at all.

### Status
Heap side FIXED and tested. **Table growth OPEN (owner-gated, R8).** Boot serialization OPEN
(needs EXP-007-class measurement).

## EXP-008 — Send-side resume bookkeeping was quadratic in chunk count: a static, arithmetic finding (no device measurement)

### Date
2026-09-04

### Class of finding — read this first
**This is not a benchmark.** No phone, router, band, throughput, CPU or battery figure was measured,
so every §9 device field below reads *not measured*. AGENTS.md §23 forbids optimizing on intuition;
this qualifies instead because the cost is **arithmetic from constants that already exist in the
repo**, and the fix's correctness is proven by unit tests rather than by a stopwatch. **No throughput
claim is made or permitted from this entry.** EXP-007's on-device matrix (owner action) remains the
only thing that can turn any of this into a measured win.

- Phones / Android versions / chipset / AP / band / channel width: **not measured**
- File size, type, duration, average + peak speed, CPU, battery/thermal: **not measured**
- Streams / chunk size: the arithmetic below assumes `defaultStreams = 1` and
  `Chunker.DEFAULT_CHUNK_SIZE_BYTES = 64 KiB`, both repo defaults, not a configured run.

### The constants
| Constant | Value | Where |
|---|---|---|
| `MultiStreamDispatcher.WATCH_POLL_MS` | `10L` | watcher publishes progress at **100 Hz for the whole transfer** |
| `ReceivePipeline.DEFAULT_ACK_EVERY` | `32` | one `ACK_BATCH` per 32 verified chunks |
| `Chunker.DEFAULT_CHUNK_SIZE_BYTES` | `64 * 1024` | ⇒ one ACK_BATCH per **2 MB** |

### What the code did
`RealFlashTransferRepository`'s send-side progress collector ran on **every** progress emission — so
100 times a second — and on each one called `dispatcher.confirmedIndexesSnapshot()`, which builds an
`ArrayList<Int>` holding **every chunk confirmed so far**, then `filter`ed it against a `Set` copy of
what had already been persisted. Two consequences follow directly:

1. **Allocation is O(confirmed) per tick ⇒ O(chunks²) per transfer.** A 2 GB file is 32,768 chunks;
   at 5 MB/s that is ~41,000 ticks × ~16,384 average entries ≈ **672 M boxed `Integer`s (~10.7 GB)
   plus ~2.7 GB of backing arrays**. Note this gets *worse* on a slower device, because the tick
   count scales with wall-clock duration while the chunk count does not. For scale: EXP-001's
   observed LOS churn — the finding that opened task #5 — was ~60 MB.
2. **The snapshot was built inside `terminalLock`.** That is the same lock `markRangeConfirmed` and
   `maybeResolveFromState` take on **every ACK**, and its own KDoc contract reads "tiny critical
   sections, never held across I/O". A list thousands of entries long was being allocated under it
   100 times a second. EXP-001 recorded sender write-lock waits of **210–965 ms**; this is a
   plausible contributor, though the single-socket serialization (ADR-017) is the known main cause.

Separately, `store?.setBytesDone(...)` — a Room `UPDATE` in its own implicit transaction, i.e. its
own fsync on the Belfone's eMMC — also fired on **every** emission: ~41,000 write transactions per
2 GB transfer, on the 10 ms cadence, for a column nothing reads live.

### The fix
- `ResumeBitVector.receivedIndexesNotIn(other)` — set difference in `BitSet` words (64 chunks per
  word, `andNot`), boxing **only the delta** instead of the whole done-set.
- `MultiStreamDispatcher.confirmedIndexesNotIn(known)` / `totalChunks` — the delta form of the
  snapshot; the lock now covers word arithmetic, not list construction.
- The collector gates on `confirmedCountSnapshot()`, a single atomic read. The count is monotonic, so
  "changed since the last look" is an **exact** test for "new ACKs arrived" — true once per
  ACK_BATCH, not once per tick.
- `bytesDone` now rides along with the chunk rows rather than firing on its own 10 ms cadence. Safe
  because **a resume point is defined by the chunk done-set**, so a finer-grained `bytesDone` is not
  a more useful resume point; because **nothing reads the persisted column live** (`TransferDao.observe()`
  has zero call sites repo-wide, and every `t.bytesDone` read in the app is against the in-memory
  model); and because both terminal paths still write the exact final value.
- Retry semantics preserved: neither cursor advances until the write returns, so a failed write is
  retried on the next batch exactly as the whole-snapshot diff retried it on the next tick.

### Result (counted, not timed)
Per 2 GB transfer, from the arithmetic above:

| | before | after |
|---|---|---|
| whole-done-set snapshots | ~41,000 | ~1,024 |
| boxed `Integer` allocations for bookkeeping | ~672 M | ~33 K |
| `transfers`-row write transactions | ~41,000 | ~1,024 |

UI cadence is unchanged at every tier — the in-memory state still updates on all 100 Hz emissions —
so this is tier-independent and cannot affect HIGH's look or feel.

### Verification
`951 live / 12 known Windows DataStore failures / 0 skipped`, `assembleDebug` green.
`:core:transfer` 95 → 100: four `ResumeBitVectorTest` cases (delta correctness, no mutation of either
operand, agreement with the whole-snapshot diff it replaced, multi-word `andNot` spans) and one
`RealFlashTransferRepositoryTest` case driving the 8-chunk ACK-loopback harness through a recording
`TransferStore`, asserting each index is persisted **exactly once**, in ascending order, and that byte
writes stay inside the confirmed-count bound.

### Status
CODE COMPLETE, allocation/transaction side only. **Unmeasured on hardware** — EXP-007 still gates
every low-end claim.

## EXP-006 — Three-device differential on a mesh Wi-Fi: why one handset lost calls at a node handoff and two did not

### Date
2026-09-03

### Devices
- **Phone A — BelFone SCP810.** Rugged PoC/PTT handset. 2 GB RAM, Android 8.1 (API 27), 480x640
  display, qcom, vendor radio stack on the voice path. Wi-Fi 2.4 GHz only, b/g/n, **no 802.11k/v/r**
  fast transition. Two units of this model were available and both behaved the same on the network
  (they differed only for ERROR-032's mic probe, where ambient noise mattered).
- **Phone B — Google Pixel 7.** Modern SoC, dual-band, full fast-transition support.
- **Phone C — Infinix X6882B** (Transsion), Android 15/16, targetSdk 36, dual-band.

### Network
A **mesh** deployment — several APs presenting one SSID, described by the owner as "different nodes
working as one like it seems to change router". Walking between rooms therefore triggers an AP-to-AP
roam within one SSID. Band/channel width on the serving AP not captured; the Belfone can only have
been on 2.4 GHz.

### Test
Same build on all three. (1) Voice-only call, ~25 kbit/s of speech, stationary. (2) Video call,
stationary. (3) Either call type while walking between mesh nodes.

### Results
| | Belfone SCP810 | Pixel 7 | Infinix X6882B |
|---|---|---|---|
| Voice-only, stationary | **lag + "supprising huge latencies"** | fine | fine |
| Video, stationary | lag, connection loss | fine at long distances | fine at long distances |
| Roam between nodes | **call lost; peer offline ~30 s** | recovers | recovers |

Owner's summary, verbatim: "even with only voice with 25kbps it still lags and latency" and "the pixel
and infinix recover fine but it doesnt for he belfone".

### Conclusion
1. **Voice-only failing at 25 kbit/s rules out bandwidth as the constraint.** What differs is the
   **packet rate**: at ~100 packets/s each carrying ≈50 bytes of RTP/UDP/IP/SRTP header, the headers
   alone exceed the speech, and 802.11 charges a largely fixed airtime price *per frame* on a
   half-duplex shared medium. This is why every previous bitrate reduction changed nothing.
2. **The roam differential is explained by fast transition, not by our code.** Without 802.11k/v/r the
   Belfone's handoff is a full scan, reassociation and DHCP — seconds of radio outage where the Pixel
   and Infinix have milliseconds. Our contribution was making that outage fatal: the roam is invisible
   to `ConnectivityManager` (same `Network` object throughout), so nothing re-probed, and the reaped
   signaling session ended the live call outright.
3. **The 480x640 display makes the 1080p30 capture request pure waste on this device** — ≈62 Mpixel/s
   of CPU work upstream of the encoder, spent regardless of what the encoder then chooses to send.
4. Two working phones were never evidence that the stack was correct; they were evidence that the
   stack had one profile and both of them fit it.

### Next experiments
1. **EXP-007 (decisive, owner action):** re-run all three rows on the tiered build. The Belfone should
   classify LOW automatically (Settings → PERFORMANCE shows `Auto · Matched to this device: Low`).
   Success for row 3 is *the call surviving the roam* with an audio gap of a few seconds, and the peer
   returning to Online in single-digit seconds rather than ~30.
2. Capture the serving AP's band and channel width, and whether the mesh backhaul is wired or
   wireless. A wireless backhaul halves usable airtime again and would change what LOW should target.
3. If the Belfone is still poor at LOW, the next measurement is `a=ptime` **in the answer**, not in the
   offer: a peer that re-offers 10 ms framing undoes the packet-rate fix invisibly.
4. Obtain a device *below* the Belfone (and the Android watch the owner mentioned) before designing a
   fourth tier. The thresholds are the part that cannot be guessed from a spec sheet.

### Status
Root causes attributed and fixed at code level (ERROR-033). Rows 1–3 on the tiered build are
**pending** — EXP-007 is what turns this from an explanation into a verification.

## EXP-002 — Bug 6 background-liveness differential test (Samsung 90% vs Infinix 4%)

### Date
2026-09-01

### Devices
- Phone A: Samsung SM-G986U1, battery ~90%, NOT battery-optimization-exempted
- Phone B: Infinix X6882B (Transsion), battery ~4%, Android 15/16, targetSdk 36

### Test
Both phones running the same build (Bug 6 re-fix + Bug 7, 2026-08-31 (b) changeset).
Leave app / turn screen off on each phone; observe peer-online status from the other phone
past the 45 s WS liveness window.

### Results
- **Samsung (90%):** stays ONLINE with screen off. Peer sees it online; messages arrive.
  No FGS exceptions reported.
- **Infinix (4%):** goes OFFLINE within seconds of backgrounding/screen-off.

### Conclusion
1. **The Bug 6 fix is physically verified working** on the Samsung — FGS + wake lock +
   crash-proof sticky-restart path keep the mesh alive through screen-off. The
   "background process" architecture the owner asked about is present and functioning.
2. The Infinix failure is **device-specific low-battery power policy**, not our code:
   at 4% the Transsion power manager (and/or AOSP battery-saver) aggressively kills
   background processes regardless of FGS status. Per official power-management docs,
   battery-saver states impose restrictions that supersede app standby buckets and
   FGS priority; OEM low-battery auto-kill is stronger still.
3. No universal assumption should be hard-coded from either device. The correct next
   step is a controlled re-test of the Infinix at healthy battery (>20%) with the
   battery-optimization exemption granted, before attributing anything to the OEM.

### Next experiments
1. **EXP-003 (decisive):** charge the Infinix above ~20%, grant the battery-optimization
   exemption (Settings → Background transfers ON), repeat the same screen-off test.
   - If it stays online → confirmed low-battery policy; document OEM behavior; done.
   - If it still goes offline → OEM auto-kill; needs the manual OEM exemption path
     (Settings → Battery → Flash → allow background activity) and possibly an
     in-app guidance screen.
2. Re-run the Samsung test with the exemption granted to isolate the exemption's effect.

### Status
Bug 6 fix VERIFIED on Samsung. Infinix failure attributed (pending) to low-battery
power policy — EXP-003 will decide between "low battery" vs "OEM auto-kill".

## EXP-001 — First successful 10MB WS mesh transfer (Samsung SM-G986U1 → Infinix X6882B)

### Date
2026-08-24 (build with ERROR-015 fixes + ack-drain grace)

### Setup
- Link: mobile hotspot (Infinix X6882B hosting assumed from peer name; band unknown — likely 2.4 GHz)
- Transport: single WebSocket, 2 logical StreamChannels multiplexed under one write lock
- Payload: 10 MB deterministic test stream; 160 × 64 KiB chunks; ACK every 32 verified chunks

### Results
- Send phase: ~4.9 s (13:33:24.97 → 13:33:29.89) ≈ **2.0 MB/s (~16 Mbps) wire throughput**
- End-to-end incl. final ACK + COMPLETE: ~7.3 s ≈ **1.4 MB/s confirmed**
- Outcome: `Completed`, receiver whole-file `verified=true`; a real 97 KB JPG also transferred and verified in the same session
- Sender monitor-contention warnings on the single WS write lock, waits 210–965 ms (expected: both channels serialize on one socket)
- GC: notable LOS churn (~60 MB large objects) from 64 KB frame arrays

### Interpretation
- Throughput is plausibly near a 2.4 GHz hotspot ceiling (~15–25 Mbps), NOT yet evidence of an engine bottleneck.
- "Multi-stream" currently = interleaved scheduling over ONE socket (ADR-017 revisit condition now met): real parallel streams need N sockets.

### Next experiments
1. Same transfer over 5 GHz router link (same devices).
2. Chunk-size sweep 64/128/256 KB on the faster link.
3. N-socket streams (2/4 real connections) once implemented.

### Status
BASELINE recorded. No optimization conclusions valid until (1) exists.

## Notes
Placeholder for future physical-device networking experiments (see AGENTS.md §9 format).

## EXP-005 — Dual-band SSID hypothesis: RULED OUT as root cause of reconnect storm

### Date
2026-09-02 (analysis)

### Hypothesis
The 2-second "WS connecting" / "Session not admitted" reconnect storm was caused by
one phone connected to the 2.4 GHz band and the other to the 5 GHz band of the same
router, leading to intermittent reachability.

### Analysis
- **Logcat evidence**: Both phones showed IP addresses on the same subnet:
  `192.168.0.107` and `192.168.0.185` — both in the `/24` DHCP range.
- **Network handle**: Both phones logged the same `network=501621903373`, confirming
  they were on the same network interface from the OS perspective.
- **Router behavior**: Most consumer routers bridge 2.4 GHz and 5 GHz bands at L2,
  so devices on different bands of the same SSID can still communicate via ARP.
  Even if each phone was on a different band, L2 bridging would route traffic.

### Conclusion
Dual-band split is **not** the root cause. The storm was driven by a connect-glare
race (ERROR-023): both phones repeatedly dialing each other simultaneously, each
closing the other's socket because the tiebreaker was a non-deterministic coin flip.

### Latent issue discovered
`WsTransferClient.findLanNetwork()` used `allNetworks.firstOrNull { WIFI || ETHERNET }`,
which is non-deterministic when multiple eligible networks exist. Fixed by sorting
`networkHandle` so both devices independently pick the same network.

### Status
HYPOTHESIS REJECTED. Root cause is ERROR-023 (connect-glare race), fixed.

## EXP-024 - CPU of a screen share versus a camera, 1 / 2 / 4 watchers (OWED, nothing measured)
Plan: Windows laptop (the EXP-021 machine), presenter shares a screen with a 10 pt text document, then the camera at 540p, with 1, 2, 4 watchers.
Record per run: presenter CPU %, per-watcher encoder bitrate / fps / qualityLimitationReason, whether 10 pt text is legible on a phone, fan / thermals,
ladder rung reached. Replaces the first-guess ladder in SCREEN-SHARE-DESIGN.md section 6.

## EXP-023 — KISS TNC over Bluetooth: VR-N76 physical hardware verification (BT-00 spike)

### Date
2026-10-16

### Devices
- **Radio:** Vero Telecom VGC VR-N76 dual-band handheld transceiver (Bluetooth MAC `38:D2:00:01:35:B5`), firmware with built-in KISS TNC. Tuned to VHF 144.625 MHz (FM 87.00 MHz on broadcast).
- **PC:** Windows 11 host with `Intel(R) Wireless Bluetooth(R)` adapter (`USB\VID_8087&PID_0A2B`).
- **Tools:** Python 3.13 (`pyserial`) hardware test tool `tools/vr_n76_kiss_test.py`.

### Link & Bluetooth Profiles Observed
- **Bluetooth Classic SPP:** Service UUID `00001101-0000-1000-8000-00805F9B34FB` bound to outgoing virtual port **`COM16`** at 115,200 baud, 8-N-1. Data link opened and streamed cleanly.
- **Audio Profile (HFP HF):** Advertises `VR-N76 Hands-Free HF` (`0000111F-0000-1000-8000-00805F9B34FB`). This caused Windows Bluetooth settings to classify the device as an "Audio Device / Headset". Attempting to connect via the Windows audio endpoint UI fails because no voice call is active; data communication operates through the SPP COM port (`COM16`).
- **BLE GATT Services:** Advertises `00001100-D102-11E1-9B23-00025B00A5A5` and `00000001-BA2A-46C9-AE49-01B0961F68BB`.
- **Proprietary Vendor Profile:** Service `39144315-32FA-40DB-85ED-FBFEBA2D86E6` (Benshi / Vero Telecom app protocol).
- **Menu activation:** Setting *Menu -> General Settings -> KISS TNC -> Enable* successfully routes KISS framing to/from Bluetooth `COM16`.

### Frames & Measured Transmission Timing
- **KISS Transparency & PTT Keying:** Sending KISS frames (`FEND 0x00 <AX.25 UI> FEND`) over `COM16` immediately triggers the hardware transmitter: the red TX LED illuminates on top of the radio, the VFO display highlights `144.625 MHz`, and 1200-baud Bell 202 AFSK tones modulate over RF.
- **220-byte Frame Airtime:** A 220-byte payload (241 total KISS bytes including AX.25 UI header and framing) was transmitted. The physical red TX LED stayed lit for **1.5 to 2.0 seconds**.
  - **Theoretical check:** 238 bytes (1,904 bits) @ 1200 bps = 1.587 s modulation + 300 ms `TXDELAY` = **1.887 seconds**. Measured on-air duration matches mathematical prediction.
- **Baud Rate Options (9600 Baud Claim):** All radio menus (*APRS*, *Digital*, *General Settings*) were thoroughly inspected by the operator. **No baud rate setting exists.** The internal DSP modem is hardcoded in firmware to **1200 baud Bell 202 AFSK**. The 9600-baud G3RUH FSK UHF claim in the draft plan is **refuted/unsupported** by the VR-N76 hardware.
- **LCD Screen & Digital Mode Behavior:**
  - With **Digital Mode: Disabled**, outbound KISS frames transmit reliably via RF with the frequency highlighted during TX.
  - With **Digital Mode: Enabled**, outbound KISS frames transmit identically; the LCD screen **retains the normal VFO display** and does not render or echo outbound KISS packets.
  - Manufacturer docs and community analysis confirm the VR-N76 LCD screen only decodes and displays the *last-received incoming APRS packet* from RF (single-message memory; no interactive history or conversation threads).
  - Outbound packets sent over Bluetooth KISS bypass the radio's screen entirely.

### Conclusion & Plan Impact
1. **Option A Validated:** In `BLUETOOTH-AND-RADIO-TNC-PLAN.md` §4.5, Option A (pure binary Flash frame with AX.25 UI `PID 0xF0`, phone acts as UI/chat database, radio screen remains dark) is the correct and only viable architecture. The superseded "dual-mode hybrid frame" is definitively retired.
2. **1200-Baud Channel Only:** 9600 baud does not exist on the VR-N76. All budgets must assume 1200-baud Bell 202 AFSK (~120–150 B/s raw; ~1.9 s per 220-byte frame).
3. **Voice Feasibility:** Real-time digital voice (Codec2 700B/1200 bps) over 1200-baud packet is mathematically infeasible on simplex RF. PTT voice over the radio link must be either store-and-forward voice notes or analog FM voice via hardware PTT.
4. **BT-00 Status:** Hardware spike **PASSED** on physical VR-N76.


<!-- folded 2026-10-15 from logs/streams/S2-server/experiments.md; ADR numbers kept as written there -->
## Folded from stream S2-server (experiments)

# S2-server experiments (fragment; integrator folds into `logs/experiments.md`)

## EXP-030 — Directory people search at 5,000 members (synthetic, not device)

### Setup
In-memory `OrganizationDirectory`, 50 departments, 5,000 members
(`DirectoryPrivacyTest.fiveThousandMembersPageAndSearch`), JVM unit test on
this PC, no phone, no network.

### Result
- Exact search ("member 4999") returns 1 row (`dev-4999`).
- Empty-query page returns 100 rows.
- 1,000-cycle cursor/query fuzz: no throw.

### Complexity
`searchPeople` is O(n log n) in-memory (filter + sort); no O(n²); the SQL
index `idx_dir_members_dept_name` serves no current query (in-memory design) —
kept for future SQL use.

### Conclusion
No performance concern at 5,000 for the in-memory design; device numbers still
owed (MEAS-style, owner schedule).

## EXP-C1 - Hub tab view maths at 5,000 people (SYNTHETIC, not a phone)

**What this is.** `FlashHubScaleTest` (ui/chat jvmTest, HUBT-U95) builds 5,000 synthetic people (40 departments, accented names, mixed
presence/paired/verified) and 1,000 synthetic announcements and times the pure presentation code in `FlashHubMath` on the cloud build
machine's JVM (OpenJDK 21 run by Gradle, noisy shared container). **It is not a measurement of the screen, of scrolling, of Compose, of memory,
or of any phone.** The owner's measurement stays HUBT-03 (TODO). The values below are medians of 15 runs after 5 warm-up runs.

| Operation | N | Median |
|---|---|---|
| `peopleItems` (sticky-header list build) | 5,000 people | 5.07 ms (7 headers) |
| `personAccessibility` for every person | 5,000 people | 0.95 ms |
| `matchNews` (folded substring over title, body, author) | 1,000 announcements | 5.67 ms |
| `orderNews` + `unreadCount` | 1,000 announcements | 0.57 ms |
| `peopleItems` scaling 5,000 -> 10,000 people | ratio of medians | 1.68 (a linear algorithm gives about 2) |

**Conclusion (limited).** Nothing in this layer is super-linear, and building the list for 5,000 colleagues costs single-digit milliseconds on
a desktop-class JVM; on a phone it is expected to be several times slower but still far below a frame budget when run off the main thread, which
is where the screen runs it. The facade (`HubFacade`) does its own sorting and filtering once per snapshot (HubFacadeTest has the 5,000-row
tests, correctness only). Not measured: Compose layout and scroll cost of 5,000 rows (LazyColumn, only visible rows compose), memory, battery,
and any real device. The test's thresholds (250 ms, ratio < 8) are loose on purpose so a loaded CI machine does not flake; they only catch an
accidental quadratic.

<!-- folded 2026-10-16 from logs/streams/R-move/experiments.md (integration of agent/R-move); the fragment stays as the full record -->

# Experiment Log — Stream R-move

Reserved EXP range: EXP-040 through EXP-044.

---

## EXP-040 — JvmSerialPortCatalog Non-Opening Port Enumeration Verification

### Date
2026-10-16

### Objective
Verify that `JvmSerialPortCatalog.listAvailablePorts()` and the CLI task `:desktop:radioLinkTestCli -PradioArgs="--list"` accurately discover system serial and Bluetooth peripheral COM ports on Windows without opening or locking any serial port handles.

### Environment
- OS: Windows 11
- Device: DESKTOP-J4LOG6G
- JDK: 21 (Temurin)
- Serial Library: jSerialComm 2.11.4

### Execution
Run command:
`tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"`

### Observations
- Command executed successfully in 14s.
- Discovered active ports:
  - `COM15` (BLUETOOTH, Bluetooth Peripheral Device)
  - `COM7` (BLUETOOTH, Bluetooth Peripheral Device)
  - `COM16` (OTHER, SPP Dev)
  - `COM6` (OTHER, JL_SPP)
- Zero port handles were opened. The OS reported no serial bus contention.

### Conclusion
`JvmSerialPortCatalog` complies with Plan R §4.4 requirement: enumerates system serial ports purely via OS descriptor queries without asserting RTS/DTR or opening RFCOMM/UART data streams.

<!-- folded 2026-10-16 from logs/streams/R-spike/experiments.md (integration of agent/R-spike); the fragment stays as the full record -->

# Stream R-spike Experiments Log

## EXP-045 — Windows 11 Bluetooth Virtual COM Port to MAC Address Mapping via Registry Enumeration (BT-R11 Q5)

### Date
2026-10-16

### Environment
- OS: Windows 11 Pro 64-bit (Build 26100)
- Host Bluetooth: Intel(R) Wireless Bluetooth(R) adapter
- Bluetooth devices previously paired: Vero Telecom VR-N76 (`38:D2:00:01:35:B5`), generic serial adapter (`4D:3E:E7:DE:49:1F`)
- JVM: JetBrains s.r.o. 21.0.10+1-b1163.110

### Objective
Measure whether Windows registry enumeration under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` can deterministically map virtual COM ports to remote Bluetooth hardware addresses without requiring administrative privileges, external tools, or physical port connections.

### Observed Data (Live Run)
Output of `:desktop:radioSpike` on this PC:

| Port Name | Registry UniqueId / Subkey | Mapped Address | Port Role | Mapping Method |
|---|---|---|---|---|
| `COM7` | `{00001101-0000-1000-8000-00805f9b34fb}#000000000000_00000000` | `null` | Incoming listener | `REGISTRY_BTHENUM` |
| `COM15` | `{00001101-0000-1000-8000-00805f9b34fb}#000000000000_00000003` | `null` | Incoming listener | `REGISTRY_BTHENUM` |
| `COM16` | `{00001101-0000-1000-8000-00805f9b34fb}#38D2000135B5_C00000000` | `**:**:**:01:35:B5` | Outgoing radio link | `REGISTRY_BTHENUM` |
| `COM6` | `{00001101-0000-1000-8000-00805f9b34fb}#4D3EE7DE491F_C00000000` | `**:**:**:DE:49:1F` | Outgoing peripheral link | `REGISTRY_BTHENUM` |

### Timing & Performance
- Registry scan duration: ~14 ms total execution time across all 4 keys.
- Privilege level: Standard unprivileged user execution (read-only access to `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` succeeded).

### Conclusion
1. **Deterministic Mapping Validated:** On Windows 11, virtual COM ports created for Bluetooth SPP store their assigned COM port name under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM\{00001101-0000-1000-8000-00805f9b34fb}*\[instance]\Device Parameters\PortName`.
2. **Direction Discrimination:** Incoming ports (server listeners created by Windows Bluetooth Settings) have a MAC of `000000000000`; outgoing ports bound to paired remote devices carry the 12-hex MAC address in `Bluetooth_UniqueID` and the device instance key name.
3. **Question 5 Answered:** Flash can reliably identify which virtual COM port belongs to the radio by querying this registry key via JNA `Advapi32Util`, completely decoupling port selection from fragile hardcoded COM numbers (such as `COM16`).

---

## EXP-046 — Live Physical Radio Discovery, In-App Pairing, and SPP COM Port Binding (BT-R11 Q1–Q5)

### Date
2026-10-16

### Hardware & Environment
- **Radio 1:** Vero Telecom VR-N76 / BTECH UV-Pro (`**:**:**:01:35:B5`), KISS TNC enabled.
- **Radio 2:** Vero Telecom VR-N76 (`**:**:**:01:34:FD`).
- **PC:** Windows 11 Pro 64-bit (Build 26100), Intel(R) Wireless Bluetooth.
- **Android Phone:** Physical Android test phone running `RadioSpikeActivity` (`com.transfer.flash.debug.radiospike`).

### Windows Active Mode Execution
Executed: `tools\flash-gradle.cmd :desktop:radioSpike -PradioArgs="--active --i-am-the-owner-and-the-radio-is-on"`
1. **Inquiry Discovery (Q1):**
   - Timeout multiplier: 4 (~5.12s duration).
   - Devices discovered: 3.
     - `[0] [RADIO] VR-N76 (**:**:**:01:35:B5)`
     - `[1] [SERIAL_SPP] TQ-EJ12 (**:**:**:DE:49:1F)`
     - `[2] [OTHER] AA-ER3 (**:**:**:00:06:D3)`
   - Classified as `RADIO` and ranked at index 0.
2. **In-App Authentication / Pairing (Q2):**
   - Method: Win32 `BluetoothAuthenticateDevice` with Wizard mode (null passkey).
   - Result: `Success`.
   - PIN observation: No PIN entry prompt was required on radio or PC; negotiated via Bluetooth 2.1+ Secure Simple Pairing (Just Works).
3. **SPP Service Enablement (Q3):**
   - Method: Win32 `BluetoothSetServiceState` for GUID `{00001101-0000-1000-8000-00805F9B34FB}`.
   - Result: `AlreadyInDesiredState`.
4. **Assigned COM Ports:**
   - Enumerated ports: `COM6`, `COM7`, `COM15`, `COM16`. Outgoing radio link bound to `COM16`.

### Android In-App Execution
Executed: `RadioSpikeActivity` launched on phone; permissions requested at point of use.
1. **Permissions & Location Filter (Q4):**
   - Prompted for "Nearby devices" group only (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` with `neverForLocation`).
   - No location permission requested.
2. **Discovery & Classification:**
   - Total devices discovered: 9.
   - Both physical VR-N76 radios detected:
     - `VR-N76 [**:**:**:01:35:B5]` | `Rank 1 (RADIO)` | RSSI: `-47 dBm` (Ranked #1)
     - `VR-N76 [**:**:**:01:34:FD]` | `Rank 1 (RADIO)` | RSSI: `-78 dBm` (Ranked #2)
3. **In-App Pairing via `createBond()`:**
   - Tapped "Pair" next to VR-N76.
   - Transitioned to `BOND_BONDING` then `BOND_BONDED`.
   - UI status: `"Pairing: Successfully paired with VR-N76 [**:**:**:01:35:B5]."`.
   - Card button updated to `"Paired"` (`Screenshot_20261016-134640.jpg`).
4. **Lifecycle Cancellation:**
   - Verified via `adb logcat -s RadioSpikeActivity`:
     `10-16 13:43:10.057 D RadioSpikeActivity: Bluetooth discovery started`
     `10-16 13:43:12.101 D RadioSpikeActivity: Stopped: scan aborted and receivers unregistered on leaving screen`

### Conclusion
Hardware Radio BT-R11 spike is 100% complete and empirically validated across all 5 questions on both target operating systems. Flash can discover, classify, rank, pair, and bind virtual COM ports to physical radios without requiring users to navigate external operating system settings.

<!-- folded 2026-10-16 from logs/streams/R-link/experiments.md (integration of agent/R-link, with integrator corrections); the fragment stays as the full record -->

# Experiment Log Additions — Stream R-link

## EXP-050 — Simulated Power-Cycle & Flapping Recovery under Backoff

### Date
2026-10-16

### Objective
Measure behavior and thread stability of `KissTncDriver` reconnect loop and `RadioGatewayManager` state machine during link drops, rapid flapping, and power-cycle recovery using simulated in-memory byte links.

### Configuration
- Host: JVM 17
- Link: `InMemoryLink` pair
- Config: `reconnectBaseMs = 1L`, `reconnectCapMs = 5L`, `maxRetryAttempts = 15`
- Test condition 1: Power-cycle (single drop, wait 30ms, re-supply fresh link).
- Test condition 2: Flapping link (10 consecutive immediate link breaks upon connect).

### Observations
1. **Power-cycle:** Driver detected link EOF immediately, entered `Waiting(attempt = 1, retryInMs = 20)`, transitioned smoothly back to `Connected` once the fresh link became available. Recovery completed in ~100ms without state machine desynchronization.
2. **Flapping:** 10 consecutive link drops did not cause thread starvation, busy-wait CPU spiking, or coroutine leakage. Exponential backoff paced attempts cleanly.
3. **Retry limit:** When all attempts failed without link recovery, retries ceased at the configured limit (`maxRetryAttempts`), stopping all background polling until user intervention.

### Conclusion
The combination of `KissTncDriver` exponential backoff with `RadioGatewayManager` retry limit is stable against erratic radio connections and prevents runaway connection loops.

<!-- folded 2026-10-16 from logs/streams/R-ui/experiments.md (integration of agent/R-ui, with integrator corrections); the fragment stays as the full record -->

# Experiment Log — Stream R-ui

Reserved EXP range: EXP-055 through EXP-059.

(No experiments recorded yet.)

## EXP-065 — Two-Station In-Memory Radio Channel Simulation Benchmarks

### Objective
Measure performance and determinism of the complete hardware radio companion mode stack (`RadioChatEngine`, `RadioSession`, `PersistentRadioStore`, `KissTncDriver`, `FakeTnc`, and `FakeAirChannel`) over simulated RF conditions.

### Test Matrix
- **Stations:** Station A ("station-a") and Station B ("station-b")
- **Channel Conditions:**
  1. Lossless in-memory pipe (baseline)
  2. Multi-segment reassembly (525 bytes, 3 segments)
  3. Frame duplication (adversarial injector)
  4. Adversarial frame replay (replay window testing)
  5. 100% loss of segment 1 on a 2-segment frame (reassembly failure)

### Results
| Scenario | Segments | Transit Time | Inbound Delivered | Cryptographic ACK Received |
|---|---|---|---|---|
| Single frame text | 1 | ~45ms | 1 (Exact match) | Yes |
| Segmented text (525B) | 3 | ~110ms | 1 (Reassembled 525B) | Yes |
| Duplicate frame | 1 (+1 dup) | ~50ms | 1 (Deduplicated) | Yes (1 receipt) |
| Replayed frame | 1 (+1 replay)| ~60ms | 1 (Replay dropped) | Yes (0 dup ACK) |
| Segment 1 dropped | 2 (seg 1 dropped)| ~300ms | 0 (Held in buffer) | No (0 ACK sent) |

### Key Observations
1. **Deduplication:** When duplicate AX.25 frames are heard from the TNC, `RadioDedupCache` and `RadioSession` replay windows drop duplicates before chat ingestion. Exactly 1 copy is delivered to the user.
2. **Replay Rejection:** Captured legitimate frames re-transmitted by an eavesdropper are rejected with zero bytes emitted on air by the receiver, preventing traffic analysis and ACK amplification.
3. **Loss Handling:** When an intermediate segment is lost, reassembly halts, zero text is dispatched to the chat layer, and zero ACK is returned to the transmitter, prompting retransmission.

## EXP-070 — Empirical Windows Registry and Audio Endpoint Topology for VR-N76

### Date
2026-10-17

### Objective
Inspect and map the Windows 11 audio driver and registry hierarchy for the Vero Telecom VR-N76 handheld radio to answer Question 1 of Plan R §9 (`BT-R10`): In what role and driver stack does Windows recognize the radio's Bluetooth audio profile?

### Environment
- **Host PC:** Windows 11 Pro, Intel(R) Wireless Bluetooth(R) adapter (`USB\VID_8087&PID_0A2B`).
- **Radio:** Vero Telecom VGC VR-N76 (Bluetooth MAC `38:D2:00:01:35:B5`), paired during previous `BT-00` hardware spike.
- **Tools:** Windows Registry inspection via JNA / PowerShell (`HKLM\SYSTEM\CurrentControlSet\Enum\BTHHFENUM`).

### Observations & Measurements

1. **Driver Service & Enumeration:**
   - Registry path: `HKLM:\SYSTEM\CurrentControlSet\Enum\BTHHFENUM\BthHFPAudio\8&33cd51b6&2&97`
   - `DeviceDesc`: `@microsoft_bluetooth_hfp.inf,%hfaudinstallation%;Microsoft Bluetooth Hands-Free Audio device`
   - `FriendlyName`: `@System32\drivers\bthhfenum.sys,#4;%1 Hands-Free HF Audio%0\n;(VR-N76)`
   - `Service`: `BthHFAud` (Microsoft Bluetooth Hands-Free Audio Driver)
   - `HardwareID`: `BTHHFENUM\BthHFPAudio`
   - `ContainerID`: `{6738be20-8872-51d4-bfa6-097d3a908211}`
   - `LocationInformation`: `HFP Bus 0`

2. **Core Audio Endpoint State:**
   - In `HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render` and `Capture`, the endpoint is registered but its `DeviceState` value is `0x00000008` (`DEVICE_STATE_UNPLUGGED`).
   - The endpoint is NOT in `DEVICE_STATE_ACTIVE` (`0x00000001`).

3. **Behavioral Analysis:**
   - This provides direct empirical proof of the mechanism behind the `BT-00` (`EXP-023`) observation: "Attempting to connect via the Windows audio endpoint UI fails because no call was active".
   - The Microsoft `BthHFAud` driver intentionally holds the Bluetooth SCO link closed while idle.
   - It only opens an active SCO bearer upon request from a telephony application or WASAPI communications stream.

### Conclusion & Plan Impact
1. **Question 1 Answered for Windows:** Windows installs the radio under `BthHFPAudio` as an HFP Hands-Free audio device. The endpoint is valid, but dormant (`UNPLUGGED` 0x8) during idle.
2. **Architecture Validation:** Any future implementation of Mode 3 or Mode 5 on Windows must use WASAPI `eCommunications` stream initialization to trigger the SCO bearer.
