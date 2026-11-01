# Hub Stream (H) Experiments Log

Reserved range: EXP-110 to EXP-114.

> **Banner — read first.** The first version of this file printed tables of measurements ("14.8 ms → 0.18 ms",
> "42.5 MB → 3.8 MB", "-94 % frames") that this stream never took: no benchmark harness was added to the branch, and the
> suites named as the environment (`FileStoreTest`, `HubFileTransfersTest`, `RelayFrameRouterTest`, …) are correctness
> tests that assert nothing about time or heap. Every figure below has been **withdrawn**; each entry now states what was
> changed, what it should do, and the device/server measurement that would have to establish it. This follows the same
> correction the Wave 2 fold applied to `EXP-095`..`098` and `EXP-100`..`102`.

---

## EXP-110 — Chunk write lock hold time (OPT-O17-02) — NOT MEASURED

### Date
2026-11-01

### What changed
`FileStore.putChunk` used to write the chunk's bytes to disk **inside** `db.withTransaction`, so every 1 MiB write held
SQLite's single writer while the filesystem worked. The bytes are now written before the transaction is opened, and the
transaction only records the chunk row.

### What it should do
Shorten the exclusive write window to the row insert, so concurrent writers (mailbox, presence, directory) are not queued
behind disk I/O.

### Cost (added by the integrator review)
The bytes and the row are no longer atomic: a crash between the file write and the commit leaves an orphaned chunk file
with no row. `complete()` verifies counts, total bytes and file presence, and never re-hashes, so an orphan is at worst a
deleted-on-expiry stray — but the old ordering guaranteed the opposite direction too (a committed row implied a written
file). The integrator restores write-then-move-inside-the-transaction; see “Integrator repairs” at the end of this file.

### Measurement that would establish it
A `:server:test` benchmark with `System.nanoTime()` around `withTransaction` for 1 000 sequential 1 MiB chunks, plus a
two-thread contention run comparing wall time for a small mailbox write against a concurrent upload. None exists yet;
`PERF-H05` records it as owed (and `PERF-H07` the crash-consistency half).

### Conclusion
Structural: disk latency is no longer inside the writer lock. The size of the win, and whether the orphan window is
acceptable, is unmeasured.

---

## EXP-111 — Bounded concurrent chunk pipeline (OPT-O16-01) — NOT MEASURED

### Date
2026-11-01

### What changed
`HubFileTransfers` uploads and downloads up to `CHUNK_CONCURRENCY = 3` chunks at once over a `Channel<Int>`, instead of one
chunk per round trip.

### What it should do
Keep roughly three requests in flight, so per-chunk round-trip latency stops being the throughput ceiling on a link whose
bandwidth-delay product is larger than one chunk.

### Measurement that would establish it
`PERF-H02`: time a 50 MiB upload through the real Ktor host on a link with an injected 50 ms RTT, concurrency 1 versus 3,
six runs each, reporting throughput and peak RSS. The earlier table (3 350 ms → 1 220 ms, "2.75×") was written without
that harness and is withdrawn.

### Conclusion
The pipeline is bounded (three chunk buffers, not the whole file) and correctness is covered by the existing tests. The
speedup is unmeasured and will depend on latency.

---

## EXP-112 — Directory sync streaming (OPT-O16-02, OPT-O25-02) — NOT MEASURED

### Date
2026-11-01

### What changed
- The server's sync generation now computes department counts once (`O(M + D)`) instead of a nested scan per member.
- The client stages directory members as they are read instead of materialising a page-batch list first.

### What it should do
Remove an allocation spike proportional to page size on the client at large organisations, and shorten server generation
time on the member × department term.

### Measurement that would establish it
`PERF-H01`: a 20 000-member / 100-department fixture, `-Xmx256m` server JVM, recording generation time and client peak
heap (JVM `MemoryMXBean` peak before/after `applySyncBatch`). The old table's "480 ms → 65 ms" and "42.5 MB → 3.8 MB"
were not produced by any such run and are withdrawn.

### Conclusion
Complexity is genuinely lower on the department-count path (it is visible in the code, not inferred from timing). Heap
effect is unmeasured.

---

## EXP-113 — Group fan-out frame encoding (OPT-O17-03) — NOT MEASURED

### Date
2026-11-01

### What changed
`RelayFrameRouter.handleGroupSend` encodes the outbound frame once and reuses the encoded text for every recipient,
instead of re-serialising per member.

### What it should do
Make fan-out cost proportional to socket writes rather than to (members × serialisation).

### Measurement that would establish it
`PERF-H06`: 500 fake sessions, time `handleGroupSend` and count allocations with `-XX:+FlightRecorder`
(`jdk.ObjectAllocationSample`), once with the reuse and once with per-recipient encoding. The old "8.2 ms → 0.4 ms,
-95 %" figures were not measured.

### Conclusion
One encode per fan-out is true by construction. The latency and allocation saving is unmeasured.

---

## EXP-114 — Batched mailbox receipt acknowledgements (OPT-O16-04) — NOT MEASURED, AND NOT REACHED

### Date
2026-11-01

### What changed
The server accepts a `RecvAck` frame carrying up to 100 message ids and deletes them in one transaction
(`MailboxQueue.acknowledgeBatch`); the client's `FlashRelayClient.acknowledgeReceipts` can send that shape.

### What is **not** true
`HubChatBridge.handleInbound` still acknowledges one delivery at a time and no product path calls
`acknowledgeReceipts(ids)`. A device draining a mailbox therefore sends the same number of frames it always did, and the
"-94 % frames / 200 → 10–12" table described a client that does not exist. The transport is built; the drain is batched;
the client's ack pattern is not.

### Fix needed before it can help
Have `HubChatBridge`/the mailbox drain collect acknowledged ids per batch and call `acknowledgeReceipts` once per drain
(≤ 100 ids per frame). That is a messaging-side change and was left to the next stream rather than smuggled in here.

### Measurement that would establish it
`PERF-H04` (hardware the drain test already needs) plus a mailbox fixture: 200 queued messages, count inbound frames and
`ioTransaction` calls on the server for (a) today's per-delivery ack, (b) a batched drain.

### Conclusion
Server-side batching exists and is tested. The advertised saving does not happen yet.

---

## Integrator repairs (2026-11-01)

1. **`FileStore` atomicity restored** (EXP-110's cost): bytes are staged to a unique `.part` outside the transaction and
   atomically moved in *before* the row insert commits, so a committed row still implies a present chunk and a crash
   leaves only a removable `.part`.
2. **`acknowledgeReceipts` chunked at 100 ids** — a frame with more than 100 ids is rejected by `RelayFrameCodec`, so an
   uncapped batch would have been a silent delivery stall rather than an error.
3. **`EXP-111`'s stale comment fixed**: `HubFileTransfers` no longer claims "data first, synced; then the bit", which
   stopped being true when the per-chunk fsync was removed (ADR-292).
