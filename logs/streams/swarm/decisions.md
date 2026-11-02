# Architecture Decision Records — SWARM (S) stream

Reserved ADR numbers for this stream: **ADR-320..329**. These are the decisions made *inside* the findings; the canonical
`docs/decisions.md` is the integrator's.

---

## ADR-320 — The piece scan keeps its rarity semantics exactly, indexed instead of rescanned

### Context
`OPT-O08-01` asked for a rarity index in `ContentState` and an incremental update on HAVE/PIECE_STORED. That makes every
mutation path (HAVE, HAVE_ALL, SUMMARY, PeerUp, PeerDown, membership change, restore) responsible for keeping a parallel
counter array correct, and the engine is property-tested for determinism and for 7 wire invariants — a counter that
drifts silently would change which piece is requested, not crash.

### Decision
Compute the holder counts **once per scheduling pass** with a bit-sliced ripple-carry adder over 64-bit words, materialise
them into `buckets[k]` (a bitset of missing, still-eligible pieces with exactly `k` eligible holders), and keep the index
exact for the rest of that pass only: `onPeerRequested` moves one piece's bucket and drops a peer whose window just filled.
Across passes the index is rebuilt. `PiecePicker.pickNextPiece` (the original scan) stays as the semantic oracle and is
compared slot by slot by `PieceScanEquivalenceTest` over 250 randomised topologies.

### Consequences
- One pass costs `peers × words × levels` word ops plus one read per missing piece instead of `P × N` per requested piece:
  measured 1,308,320 -> 4,096 holder reads for a 16-slot pass (EXP-125).
- No counters to keep in sync across events, no risk of a drift that only shows up as "it picked a slightly different
  piece".
- The index is stale between passes, which is correct: the pass rebuilds it from the live bitfields.

### Revisit when
A profile shows the per-pass rebuild itself dominating (it is ~26k operations for 4,096 pieces); then an incremental index
becomes worth the invariant risk, and `PieceScanEquivalenceTest` is the safety net to extend.

---

## ADR-321 — A saturated swarm sends control frames and drops pieces, never the other way round

### Context
`OPT-O02-04` asked for bounded queues. A single bounded queue cannot be trivially safe: a dropped `Piece` frame is
recovered by the requester's timeout, but a dropped `CANCEL` is a cancelled file that keeps downloading, and a dropped
`SOURCE_STATUS(LOST)` leaves members waiting on an origin whose file is gone (ERROR-109).

### Decision
`SwarmSendQueue` is bounded at 16 frames per peer. When it is full a **piece** frame is dropped and counted
(`swarm.send.dropped`); a **control** frame is never dropped — it waits on the queue in a background coroutine. The queue
is per peer and per direction, so a slow member cannot starve a healthy one.

### Consequences
- Outbound memory per peer is capped instead of unbounded (a stalled link used to accumulate megabyte frames).
- Under saturation a peer loses piece frames and re-requests them after its 5 s timeout: throughput degrades, correctness
  does not.
- Control frames can overtake a dropped piece frame; the protocol already tolerates that (frames are independent and the
  piece is re-asked).

---

## ADR-322 — Serve-slot fairness is deficit round robin with an age gate, inside the existing BUSY answer

### Context
ADR-232 approved `OPT-O08-03` as "age-weighted / deficit round-robin serve slots, no wire change". The slot set is also
the admission control that keeps `READ` concurrency inside the profile budget, and the wire has exactly one way to say
"not now": `REJECT(BUSY, retryAfterMs)`.

### Decision
- A peer keeps its slot until it has been served `YIELD_AFTER_PIECES` (2) pieces in the current tenure.
- A member that has been refused for `ARRIVAL_MIN_WAIT_MS` (1 s) may take the slot of the peer with the **most** served
  bytes in its tenure (ties by id, deterministic).
- The displaced peer is simply not admitted to its next request — the same `BUSY` a newcomer used to receive. Its
  in-flight reads still complete; no request is cancelled.
- A first attempt never displaces anybody, so the policy cannot thrash on a brand-new member.

### Consequences
- Every member gets a turn: the pipelining member that used to hold a slot for a whole transfer now yields after two
  pieces (`SwarmServeFairnessTest`).
- A displaced member pays one congestion-window halving on its next BUSY — the cost the starved members already paid.
- No wire change, no new frame, no new rejection reason.

### Revisit when
`PERF-S01` (three devices) measures whether the rotation costs total throughput; if it does, raise `YIELD_AFTER_PIECES`
rather than removing the rotation.

---

## ADR-323 — The thermal governor is sampled by the host binding, not pushed by the app

### Context
`OPT-O08-06` wants the swarm to follow `AndroidThermalGovernor`. That class lives in `:core:common` and is installed as a
process-wide `ThermalGovernor` singleton by the Android host (`app/**`, Stream G's files).

### Decision
`SwarmHostBinding.updateEnvironment()` (the existing 0.5 Hz environment poll) samples `ThermalGovernor.get().status` and
forwards changes to `SwarmDriver.onThermalStatus`. The engine maps the status to a serve profile: `MODERATE` one notch
down, `SEVERE`+ to `LOW` **and** no serving; recovery restores the configured profile.

### Consequences
- No `app/**` edit and no new host wiring; desktop and tests get the no-op default and are unaffected.
- The governor's own hysteresis (dwell + delta) is what prevents flapping, so 0.5 Hz sampling adds at most 2 s of latency
  to a 15 s dwell decision.
- A person's "do not serve" is a separate flag: cooling cannot opt a device back in.

---

## ADR-324 — Served pieces are trusted; the source's whole-file identity is checked instead

### Context
`OPT-O08-02`: the serve path re-hashed every outgoing piece. Removing the hash entirely loses the detection that makes
ERROR-109 work (the origin notices its source changed and signs `LOST`).

### Decision
The serve path trusts the content bitfield (a piece is only ever served when it is marked verified) and compares the
source file's **whole-file size** with the announced size, at most once every two seconds per content. A missing or
truncated source already fails the read; a replaced one is caught by the size.

### Consequences
- 46.6 s of CPU removed when serving a 4 GB file to three members (EXP-127).
- A *same-size* edit of an origin's file is no longer caught by the sender: the receiving peer's per-piece hash rejects
  the piece and strikes the sender. Recorded deliberately; the alternative was keeping 3.8 ms of hashing per served MiB.
- A provider that reports no size (identity unknown) never fails a serve.
