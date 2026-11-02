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
