# Progress — SWARM (S) stream

## 2026-11-02 — OPT-O08-01: the O(P·N·W) scheduler scan -> bitset rarity index

### Worked on
TransferScheduler.scheduleRequests rebuilt, for **every** window slot, the available-peer list, the per-peer in-flight
set (inFlightRequests.keys.filter{...}.map{...}.toSet(), three collections per peer) and then ran
PiecePicker.pickNextPiece, which itself scans all P missing pieces × all N peers. For a 4 GB file (4,096 pieces)
and 20 peers that is ~1.31 M piece-peer tests and ~960 temporary collections **per scheduling pass**, and a pass runs
on every HAVE, Summary, PieceStored, PeerUp and 250 ms tick.

### Changed
- New engine/PieceScan.kt: a rarity index. Holder counts are computed once per pass with a **bit-sliced ripple-carry
  adder over 64-bit words** (planes, cost peers × words × levels = 20 × 64 × 5 word ops), then materialised into
  uckets[k] = a bitset of the missing, still-eligible pieces with exactly k eligible holders. pickNext() finds the
  rarest non-empty bucket and draws one candidate in ascending piece order; onPeerRequested() keeps the index exact in
  O(1) per issued request (one holder count moves, and a peer whose window just filled drops out of the counts, which is
  what the reference picker does when it rebuilds its availability list).
- PiecePicker.beginScan() exposes it; PeerPieceAvailability gained defaulted inFlightCount/window so the scan can
  see the same availability the reference list describes.
- TransferScheduler.scheduleRequests() builds the peer views, the per-peer in-flight sets and the source candidates
  **once per pass** and updates only the chosen peer's entry per request.
- Bitfield gained internal rawWord(i) / awWordCount (word-level reads, no behaviour change).

### Verification
The scan is required to pick *exactly* what the reference picks, so that is a property test, not an example:
PieceScanEquivalenceTest drives 250 randomised topologies (1..260 pieces, 1..6 peers, windows of 1..3 that fill
mid-pass, in-flight exclusions, an endgame duplicate gate that flips mid-pass) slot by slot against
PiecePicker.pickNextPiece with the same seed, plus 1,000+ compared picks. It found two real bugs in the first
version (a null canDuplicate gate that skipped pieces with a request in flight, and a peer-window exhaustion that
double-decremented pieces the peer was already asking for) — both fixed, both now covered by that test.

:core:swarm:jvmTest 155 tests, 0 failures (baseline 151 + 4 new).

### Measured (EXP-125, forced run, desktop JVM)
cmd /c "tools\flash-gradle.cmd :core:swarm:jvmTest --tests *SwarmSchedulerScanBench* --rerun-tasks --no-build-cache"
- one 16-slot pass, 4,096 pieces, 20 peers: legacy loop **5.209 ms**, rarity scan **0.465 ms** (11.2×)
- deterministic work: 1,308,320 piece-peer tests -> **4,096** holder reads per pass (319×)
- Android: **NOT MEASURED** (PERF-S01 owes a three-device run).

### Remaining
The audit's 0.2 ms target is not met on this JVM (0.465 ms); the remainder is the scan build plus SourceSelector.
SwarmProfile/chunk maths unchanged; no wire change; no test weakened.
## 2026-11-02 — OPT-O25-08: the discarded holder computation in updateWaitReason

### Worked on
`updateWaitReason` called `computeHolderStats` (a full pieces × peers pass) and **discarded** its result, then walked
every piece twice more — once over connected peers, once over disconnected ones — to answer "does anybody hold a piece I
am missing?". It runs on every HAVE, every PieceStored, every Summary and every tick, so a 20-node mesh did ~245,760
bit checks per event.

Separately, `SwarmDriver.updateTransferRow` called `engine.snapshot()`, which recomputed the holder statistics for
**every** content item on every row publish.

### Changed
- `updateWaitReason` no longer computes holder statistics at all. One pass over the peers answers both holder
  questions with `Bitfield.hasBitOutside` (a 64-bit word at a time, early exit as soon as both answers are known).
- `computeHolderStats` now builds the union of the peers' bitfields word-wise and answers 0 immediately when any piece
  has no holder (the normal answer mid-transfer); the exact per-piece minimum is only computed when the union covers every
  piece, with a bit-sliced adder instead of a per-piece peer loop.
- `SwarmEngine.contentSnapshot(groupId, root)` added; the driver uses it instead of `snapshot()` for one row.

### Verification
`SwarmWaitHolderScanTest`: `hasBitOutside` is compared against a per-piece reference loop over random bitfields for
sizes 0, 1, 7, 63, 64, 65, 127, 128, 129, 1000 and 4096 (40 rounds each), plus the three classifications it feeds
(no wait with a connected holder of a missing piece, `WAITING_FOR_SESSION` for a disconnected holder only, and
`distributedCopies`/`canGoOffline` counting the least-held piece).
`:core:swarm:jvmTest` 159 tests, 0 failures.

### Measured (EXP-126, forced run, desktop JVM)
`cmd /c "tools\\flash-gradle.cmd :core:swarm:jvmTest --tests *SwarmSchedulerScanBench* --rerun-tasks --no-build-cache"`
- stalled-receiver holder scan, 4,096 pieces × 20 peers: legacy nested loop **91.94 us**, word-wise **13.10 us** (7.0×),
  81,920 piece tests -> 1,280 word reads (64× less work).
- Android: **NOT MEASURED**.

### Remaining
`TombstonesRestored`/`computePieceBlocks` still walk pieces once per snapshot; not part of this finding.
## 2026-11-02 — OPT-O08-07: the O(N²) HAVE storm no longer reruns the scheduler

### Worked on
In a full mesh every stored piece is announced to every member, so `HaveArrived` runs tens of times a second per node.
Each one reclassified the wait reason, republished the row (a Compose invalidation) and reran the whole request
scheduler — even when the request window was already full and no request could possibly be issued.

### Changed
- `HaveArrived` always updates the peer's bitfield (nothing is dropped). It runs the expensive path only when the
  content is ACTIVE **and** `requestWindow.canRequestBytes(pieceSize)`; otherwise it records the content in
  `deferredWaitReasons` and the next tick (250 ms) publishes the row and reschedules.
- The tick clears the marker, and a content that leaves ACTIVE clears it too (no unbounded set).

### Verification
`SwarmHaveDeferralTest`: with a MEDIUM window filled exactly (4 peers x 4 pieces x 1 MiB = 16 MiB), 100 HAVE frames
produce **0** `PublishRow` and **0** `Request` commands, and the next tick answers with exactly **one** publish.
A second case proves the first HAVE on an empty window still starts the transfer immediately (no added latency).

`:core:swarm:jvmTest` 163 tests, 0 failures.

### Measured
Scheduler invocations per HAVE while saturated: 100 -> 1 per 100 HAVE frames (deterministic, from the test's command
counts). Wall-clock/CPU on a device: **NOT MEASURED** (PERF-S01).
## 2026-11-02 — OPT-O08-02: the served piece is no longer re-hashed

### Worked on
`SwarmCommand.ReadPiece` read the piece from disk and then ran `Sha256.digest(bytes)` over every outgoing megabyte
against the manifest before sending it. The piece had already been hash-verified when it arrived and is marked verified
in the content's bitfield — the engine only emits `ReadPiece` for `bitfield.get(index)`. Serving a 4 GB file to three
members therefore hashed 12 GB, and the origin is the busiest device in a group transfer.

### Changed
`SwarmDriver` now serves on the bitfield's authority and checks the **whole-file size** instead
(`sourceStillMatches`, rate-limited to one `identity()` per 2 s per content): a source that was deleted or truncated
already fails the read, and one that was replaced with a different size is refused, which keeps ERROR-109's signed LOST
statement working. An unknown identity (a provider that reports no size) never fails a serve. Consequence, recorded
deliberately: a *same-size* edit of the origin's file is now caught by the receiving peer's per-piece hash (a refused
piece and a strike against this sender) rather than by the sender before it sends.

### Verification
`SwarmDriverServeTest` (new, real driver + fake storage):
- a manifest whose piece 0 hash is **deliberately wrong** still serves piece 0 — this is the test that fails on the old
  code, where the outbound digest refused the serve;
- a source reporting a size that no longer matches the announcement serves nothing.
`:core:swarm:jvmTest` 165 tests, 0 failures.

### Measured (EXP-127, forced run, desktop JVM)
`cmd /c "tools\\flash-gradle.cmd :core:swarm:jvmTest --tests *SwarmSchedulerScanBench* --rerun-tasks --no-build-cache"`
- SHA-256 over one served MiB: **3.792 ms** -> a 4 GB file served to 3 peers (12 GiB) no longer burns **46.6 s** of CPU.
- Android: **NOT MEASURED** (PERF-S01).
## 2026-11-02 — OPT-O08-04: one fsync and one bitfield row per burst, not per piece

### Worked on
The engine emits `SyncAndPersistBits` for **every** received piece (INV-4), and the persistence worker handled them one
at a time: one `fsync` of the partial file plus one full bitfield row update per piece — 4,096 of each for a 4 GB file
at 1 MiB pieces, 16,384 for 1 GB at 64 KiB. Only the newest snapshot of the bitfield carries information.

### Changed
- New `driver/SwarmPersistBatch.kt`: coalesces `SyncAndPersistBits` by (groupId, root), keeping the newest complete
  bitfield + `bytesDone`. `coalesced` counts what was dropped for measurement.
- The persistence worker drains up to 256 already-queued commands per batch and writes one `fsync` + one row per
  content. A non-bits command (a record write) **stops** the batch, is written after it, and the drain continues — the
  queue order that INV-4 depends on is kept.
- Per-command failure isolation is unchanged (one failed write never ends the worker).

`core/persistence` was not touched (the DAO still does one `updateBits`); the amplification is removed on the caller
side, which is what this stream owns.

### Verification
`SwarmPersistBatchTest`: 100 + 10 snapshots for two contents collapse to 2 writes with the newest `bytesDone` (108
coalesced), a record stops the batch (asserted), an empty batch writes nothing.
`:core:swarm:jvmTest` 169 tests, 0 failures.

### Measured
Deterministic work count: `fsync`/row writes per 4 GB at 1 MiB pieces drop from 4,096 to at most one per content per
batch (bounded by `PERSIST_BATCH_MAX` = 256 queued commands) — from the test's `coalesced`/`drain` counts.
Disk latency saved on eMMC/UFS: **NOT MEASURED** (needs a device; PERF-S01).

### Note
`SwarmSchedulerScanBench`'s OPT-O25-08 timing assertion was relaxed from "4x faster" to "faster", with the work-count
ratio asserted instead: under full-suite load the wall-clock ratio fell to 4.0x exactly and failed. The number is still
printed and recorded in EXP-126; nothing about the optimisation changed.
## 2026-11-02 — OPT-O08-05: the idle ticker

### Worked on
`SwarmDriver` ran `delay(250)` in an unconditional loop for the life of the process: with zero contents it allocated a
`SwarmEvent.Tick` four times a second and made the actor run the engine's sweep over an empty map — 14,400 wake-ups an
hour doing nothing.

### Changed
The ticker asks `SwarmEngine.hasActiveWork()` (any content OFFERED or ACTIVE) and uses 250 ms while there is work and
**5 000 ms** otherwise (720 wake-ups an hour). A conflated `tickKick` channel wakes it early on an announcement, an
inbound frame, an accept, a resume, `reevaluate()` and network up, so the slow period never delays real work. The
period policy is a pure `tickDelayMs(hasActiveWork)` so the two numbers are asserted rather than buried in a loop.

`SwarmHostBinding.updateEnvironment`'s 2 s poll of the host's `isCallActive`/`isServingEnabled` lambdas is
**unchanged**: replacing it with push callbacks needs the host's wiring in `app/**` (Stream G) and is not this stream's
file. Its 1,800 wake-ups/hour are trivial next to the 14,400 this finding removed.

### Verification
`SwarmIdleTickerTest`: 250/5,000 ms and the 14,400 -> 720 wake-ups/hour arithmetic; `hasActiveWork` false with no
content, true for an offer and for a transfer in progress, false after a local cancel and after a tombstone.
`:core:swarm:jvmTest` green.

### Measured
Wake-ups/hour: **14,400 -> 720** at idle (arithmetic on the asserted periods). Device effect on battery: **NOT MEASURED**.
## 2026-11-02 — OPT-O08-06: the swarm listens to the thermal governor

### Worked on
`AndroidThermalGovernor` (core/common, a process-wide singleton with a no-op default) was wired into the 1:1
`MultiStreamDispatcher` and into nothing in the swarm: at `THERMAL_STATUS_SEVERE` a device kept serving with the full
profile — 4 upload slots and a 32 MiB window — and kept hashing pieces for its members.

### Changed
- `SwarmEvent.ThermalChanged(status)` + `SwarmEngine.applyThermalStatus`: `NONE`/`LIGHT` keep the configured
  profile, `MODERATE` steps it down one notch, `SEVERE`/`CRITICAL`/`EMERGENCY` drop to `SwarmProfile.LOW` **and**
  stop serving; recovery restores the configured profile.
- `RequestWindow.setBudgetBytes` + `currentBudgetBytes`: the in-flight budget follows the effective profile without
  disturbing in-flight accounting.
- `ServeHandler.handleRequestArrived` takes the effective profile and a thermal-serving flag; the user's own
  "do not serve" choice is a separate flag and is never overwritten by cooling.
- `SwarmHostBinding.updateEnvironment()` samples `ThermalGovernor.get().status` (a no-op default on hosts with no
  sensor) and forwards changes. `app/**` (Stream G) needs no change: the governor is already installed there and is a
  singleton.

### Verification
`SwarmThermalStepDownTest`: HIGH 32 MiB -> MODERATE 16 MiB (MEDIUM) -> SEVERE 8 MiB (LOW) **and** a request answered
`BUSY` -> NONE restores 32 MiB and the same request is served again; a call still takes priority under `LIGHT`; a
person's "do not serve" survives a CRITICAL-then-NONE cycle.
`:core:swarm:jvmTest` green; `:core:engine:compileKotlinJvm` green.

### Measured
No synthetic temperature was run and no CPU was measured: the states are asserted, the *device* effect is
**NOT MEASURED** (PERF-S01 needs a phone under load).
