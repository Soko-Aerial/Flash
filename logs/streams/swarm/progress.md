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
