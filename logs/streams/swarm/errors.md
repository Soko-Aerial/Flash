# Errors and defects — SWARM (S) stream

Numbering reserved for this stream: **ERROR-961..970** (AGENTS §29 block). These are the defects found *while doing*
the findings — several were bugs the change would have introduced, which is why the property tests exist.

---

## ERROR-961 — the fast piece scan dropped a null `canDuplicate` gate and double-decremented a peer's holders

### Date
2026-11-02

### Area
`:core:swarm` scheduler — `PieceScan` (OPT-O08-01)

### Symptoms
`PieceScanEquivalenceTest` failed on seed 110, slot 1: the reference picker returned piece 244, the fast scan returned
null. After the first fix a second class appeared: pieces with two holders were lost when one of them filled its window.

### Root cause
Two independent mistakes in the first draft of the scan:
1. `val gate = canDuplicate ?: continue` — with a **null** gate the code skipped every piece that already had a request in
   flight, where the reference treats a null gate as "no gate". The scheduler always passes a non-null gate, so only the
   property test could catch it.
2. `exhaustPeer()` decremented the holder count of **every** piece the peer held, including pieces the peer was already
   asking for — those had never been counted for it (an in-flight peer is excluded from the holder count), so pieces
   with another holder dropped to zero and disappeared from the candidate set.

### Working fix
`if (inFlightSize > 0 && canDuplicate != null && !canDuplicate(piece)) continue`, and `exhaustPeer()` skips pieces whose
in-flight set already contains that peer.

### Verification
`PieceScanEquivalenceTest` (250 randomised topologies, slot-by-slot comparison against `PiecePicker.pickNextPiece`) green;
`:core:swarm:jvmTest` 191 tests green.

### Status
RESOLVED (unit-tested; the device-level behaviour is PERF-S01).

---

## ERROR-962 — the timed/rate-limited probes never fired on their first event

### Date
2026-11-02

### Area
`:core:swarm` telemetry (OPT-O28-04), `TransferScheduler`

### Symptoms
`SwarmTelemetryTest` saw no `swarm.sched.stall` at all, then (after a partial fix) no second report.

### Root cause
The rate limiter compared `nowMs - lastProbeMs >= interval` with `lastProbeMs` initialised to **0**, so the first probe
was suppressed for the first five seconds of a content's life, and later probes were suppressed whenever the engine clock
started above zero. A second, unrelated effect confused the test: the 5 s request timeout fires before the probe interval
in the same tick and frees the window, so the "still stalled" state has to be observed inside a tick where the timeout has
not yet fired.

### Working fix
`lastProbeMs == 0L` means "never reported"; the interval is 2 s for the stall probe so a slow request timeout cannot mask
it, and the test reports the second time at 4.5 s (before the 5 s timeout).

### Verification
`SwarmTelemetryTest` (4 cases) green; `:core:swarm:jvmTest` 191 tests green.

### Status
RESOLVED.

---

## ERROR-963 — `SwarmFrameCodec` refuses a Summary with more than 64 tombstones, not 256

### Date
2026-11-02

### Area
`:core:swarm` `PeerHandler.sendSummary` / `SwarmFrameCodec.MAX_SUMMARY_TOMBSTONES` (OPT-O08-08)

### Symptoms
None observed in the field yet: the audit predicted a crash at 256 tombstones.

### Root cause
The audit read `MAX_SUMMARY_TOMBSTONES` as 256; the code says **64**. So the reachable failure is a group whose members
cancelled 65 files: `encodeSummary` throws `IllegalArgumentException`, the Summary is never sent, and members stop learning
who holds what.

### Working fix
`sendSummary` caps the list at `SwarmFrameCodec.MAX_SUMMARY_TOMBSTONES` and keeps the **newest** tombstones; in-memory
tombstones expire after 37 days, the same window the store persists.

### Verification
`SwarmTombstoneRetentionTest` (192 restored tombstones -> a Summary of exactly 64 that still encodes) green.

### Status
RESOLVED (unit-tested).

---

## ERROR-964 — a load-sensitive timing assertion in my own benchmark failed the full suite

### Date
2026-11-02

### Area
`:core:swarm:jvmTest` `SwarmSchedulerScanBench` (measurement harness for OPT-O25-08)

### Symptoms
`BUILD FAILED` on the full suite: `the word-wise test must be several times faster (legacy=216360.5ns word=54112.0ns)` —
a ratio of exactly 4.0x against a `> 4x` assertion, under full-suite load.

### Root cause
The assertion encoded a machine-dependent timing ratio. The work-count ratio is 64x and load-independent; the wall-clock
ratio on this shared 8-core machine ranged from 4.0x to 8.8x across runs.

### Working fix
The test asserts the **work count** (`pieces × peers` vs `words × peers`) and only the direction of the timing; the
numbers are printed and recorded in EXP-126. No timeout was enlarged and no case was skipped.

### Verification
Full `:core:swarm:jvmTest` green (191 tests); the benchmark alone re-measured at 7.0x.

### Status
RESOLVED — recorded because the same mistake (a timing assertion in a benchmark) is easy to repeat.

---

## ERROR-965 — number reserved, unused

2026-11-02. Reserved while the stream ran; no defect was recorded under it. Left in place rather than renumbering the
stream's fragment.
