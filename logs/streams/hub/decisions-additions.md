# Hub Stream (H) Decisions Additions

Reserved range: ADR-290 to ADR-299.

## ADR-290 — Chunk bytes staged outside the SQLite writer, published inside it (OPT-O17-02)

### Decision
In `FileStore.putChunk`, the validation pass and the megabyte write happen outside `db.withTransaction`: the bytes are
staged to a uniquely named `chunk-*.part` file in the file's own directory. The transaction then re-validates, atomically
**renames** the staged file into place (a metadata operation in the same directory, not a second copy) and inserts the
`file_chunk` row. If the transaction throws, the published file is deleted again and the staging file removed.

**Integrator repair.** The branch as delivered wrote the chunk file *before* opening the transaction and never renamed
inside it, which broke the invariant "a committed `file_chunk` row implies a present chunk file" (a crash in between left a
row whose download would 500, and `complete()` verifies counts and byte totals, not file presence). Staging-then-rename
keeps the expensive I/O outside the writer lock and the visibility switch inside the transaction.

### Context
`FileStore.putChunk` used to call `writeChunkFile(fileId, index, bytes)` inside `db.withTransaction`. SQLite in WAL mode
has one writer at a time, so every chunk upload held the writer for the duration of a 1 MiB disk write and queued every
other write on the server (relay, mailbox, directory, device registration) behind it.

### Alternatives considered
- Keeping the write inside the transaction with faster storage — fails on loaded cloud volumes.
- Writing the file completely outside and only recording it afterwards — the branch's first attempt; loses the row⇒file
  invariant (see above).
- Asynchronous detached chunk writes — same orphan problem, plus unbounded queues.

### Why selected
The large, unpredictable cost (disk write) leaves the lock; the cheap, must-be-atomic part (rename + row) stays inside it.
The lock hold time should drop from "one disk write" to "one rename plus an insert". **The size of that drop is
unmeasured** — the "14.8 ms → 0.18 ms, -98.8 %" of `EXP-110` was written without a benchmark; `PERF-H05` is the owed
measurement.

---

## ADR-291 — Bounded Concurrent Chunk Pipeline in HubFileTransfers (OPT-O16-01)

### Decision
Dispatch hub file chunk uploads and downloads using a bounded worker pool (`CHUNK_CONCURRENCY = 3`) with sliding window dispatch, while strictly synchronizing source reads and tracking completed chunk bitsets safely.

### Context
`HubFileTransfers` previously uploaded and downloaded 1 MiB chunks strictly one at a time (`stop-and-wait`). On a 50 ms RTT WAN link, each 100 MB file required 100 sequential round-trips (5.0 seconds of pure idle network latency), severely capping throughput.

### Alternatives considered
- Single HTTP multipart stream: Does not allow resuming individual chunks or chunk-level parallel encryption.
- Unbounded coroutine launch: Floods network buffers and risks out-of-memory errors.

### Why selected
Three chunks in flight keep a latency-bound link busy while bounding heap to three chunk buffers instead of the whole file. Correctness is unchanged: each chunk is sealed and verified before it is written, and the done bitset is updated under one mutex per chunk.

**The throughput claim is unmeasured.** “2.5×–3.5× over WAN” and the `EXP-111` table (3 350 ms → 1 220 ms) were written with no benchmark harness; `PERF-H02` is the owed measurement. The 50 ms RTT example above is arithmetic on a hypothetical link, not a run.

---

## ADR-292 — Persistent handle and deferred fsync in HubFileDisk (OPT-O23-01)

### Decision
Keep one `RandomAccessFile` handle per part file open across chunk writes in `HubFileDisk` (jvm and android actuals) and
perform a single `fd.sync()` when the transfer finishes (`finishPart` → `syncAndClose`). Delete, move and `close` evict the
handle first.

### Context
Every chunk write used to open the part file, seek, write, `fsync` and close it; `replaceAtomically` (the state-file
rewrite that follows each chunk) also fsynced. For a 50 MB sealed file at 32 KiB chunks that is ~1 600 fsyncs of the data
file plus ~1 600 of the state file. **How long each fsync costs is unmeasured** — the "1–5 ms / 3.2 s" first written here
came from no run (`EXP-110`'s banner).

### Cost, stated honestly (integrator review)
Removing fsync changes what a power loss can do, in two ways:

1. **The last chunk bytes may not be on the platter** while the done bit for them is. `verify()` re-reads and re-hashes
   every chunk from offset 0 before a download counts as finished, so this surfaces as `HubFileError.Integrity` → one full
   refetch (`verifyFailures++`), never as a silently accepted corrupt file. The comment at the download worker says so.
2. **The state file itself is no longer synced** (`replaceAtomically` lost its `out.fd.sync()`), so after a power loss the
   done set can also lag *behind* the data — the cheaper direction: a few chunks are fetched again.

Neither direction produces a file the app calls complete and then fails to open; both cost re-download. A reader looking for
"the write is durable at chunk N" will not find it, and should not assume it.

Residual limit: an interrupted (paused) transfer keeps its handle open until it resumes, finishes or is deleted, so the
cache can hold one descriptor per paused transfer. Bounded in practice, but not capped — worth revisiting if a user can
queue many paused hub downloads.

### Why selected
Intermediate writes go to the open descriptor and the OS writeback batches them; one sync at the end is enough for the
only durability boundary the design actually needs (the finished file).

---

## ADR-293 — Indexed delegation revocation history join (OPT-O25-10)

### Decision
`SqliteRevocationService.revokeDelegation` inspects the certificate history of the revoked serials with one `INNER JOIN
hub_delegations d ON c.delegation_serial = d.serial` instead of one query per serial.

### Context
Revoking a delegation walked every revoked certificate serial and queried its chain individually — an N+1 loop holding the
write transaction for the whole walk.

### Why selected
One index-backed join instead of N queries inside the same transaction, so the writer is held for one statement rather than
N round trips. **How much that shortens the hold is unmeasured**; the “~2 100 ms → ~8 ms” first written here came from no run.

---

## ADR-294 — Single pre-encoded frame for group fan-out (OPT-O17-03)

### Decision
`RelayFrameRouter.handleGroupSend` encodes the `Deliver` frame to JSON once and reuses the text for every recipient.

### Context
Fan-out called `offerFrame(session, deliver)` per member, re-serialising the identical payload for each one on the relay
dispatcher thread.

### Why selected
One encode per message instead of one per member — true by construction. The latency and allocation saving (“8.2 ms → 0.4 ms”,
“-95 %”) was **not measured** (`PERF-H06` is the owed measurement). The write itself still happens per recipient; only the
serialisation is shared.

---

## ADR-295 — O(M+D) directory sync and O(1) group roster checks (OPT-O25-02, OPT-O25-06, OPT-O25-09)

### Decision
1. `OrganizationDirectory.getSyncChanges` computes department member counts once (one aggregate over the department table)
   instead of scanning the member list per department.
2. `GroupCharter` and `Department` cache `memberSet`/`adminSet` (lazy) so membership checks are `Set.contains` rather than
   `List.contains`.
3. Collections whose size is known are pre-sized (`ArrayList(pageSize)`, `ArrayList(limit)`).
4. UTF-8 byte budgets are counted without building intermediate strings (`utf8ByteCount`).

### Context
At 20 000 members and 100 departments the nested count was O(D·M), and roster checks were linear scans run per frame.

### Why selected
Two complexity classes genuinely drop, and that is visible in the code rather than inferred from timing: O(D·M) → O(M+D),
and O(n) → O(1) per membership check. The “over 80 % faster generation” headline is **unmeasured** and withdrawn;
`PERF-H01` is the owed measurement.

---

## ADR-296 — Batched mailbox receipt acknowledgements (OPT-O16-04)

### Decision
`RelayFrame.RecvAck` gains an additive optional `ids: List<String>? = null` (the codec caps the list at 100 ids).
`MailboxStore`/`MailboxQueue` gain `acknowledgeBatch`, deleting the ids in one transaction with JDBC batch deletes, and
`RelayFrameRouter.handleRecvAck` applies one batch under the same per-device mailbox budget. The client's
`FlashRelayClient.acknowledgeReceipts` splits a list into frames of at most 100 ids.

### Context
A reconnecting client acknowledged each delivered message with its own `FLASH_RECV_ACK` frame, each causing its own delete
transaction on the server.

### Why selected
The server can now retire a mailbox drain in a handful of transactions instead of one per message, and the wire change is
additive: an old client's single-id ack is still valid, and an old server reads `msgId`.

**What is not true yet.** No product path sends a batch — `HubChatBridge.handleInbound` still acks one delivery at a time.
Until the drain collects ids and calls `acknowledgeReceipts(ids)`, the advertised saving does not happen and the change is
neutral at best. The integrator also had to cap the batch at 100 ids: an uncapped list would have been rejected whole by the
codec, silently turning every ack into a redelivery.

---

## ADR-297 — Streaming member projection during directory sync (OPT-O16-02)

### Decision
`OrgDirectorySync` stages `StagedMember(member, delegationSerial)` for each page without pre-serialising `statementBytes` or
computing SPKI fingerprints, and projects `OrgDirectoryMemberEntity` lazily as Room inserts the rows.

### Context
A full sync staged every member of every page with its serialised statement and fingerprint already built.

### Why selected
The per-member derived data is produced one row at a time instead of for a whole page up front, so peak heap should scale
with the page rather than with the directory. Whether that removes a dangerous spike on a 2 GB device is **unmeasured** —
the “35–45 MB → < 4 MB” figures were not produced by any run (`PERF-H01` covers it). Failure atomicity is preserved:
the batch is still applied in one Room transaction.

---

## ADR-298 — REJECTED: relay-active presence REST poll suppression (OPT-O16-03)

### Status
**REJECTED by the integrator and reverted (`8b622a9b`).** The entry is kept because the reasoning is instructive, not
because the decision stands.

### What was proposed
In `EnterpriseHub`, downscale HTTP `GET /directory/presence` polling to 10 minutes
(`RELAY_ONLINE_PRESENCE_POLL_MS = 600_000L`) whenever the relay WebSocket is connected (`relay.state.value.isOnline`), on
the claim that the connected tunnel delivers presence another way.

### Why it was rejected
The claim is false in this codebase, and the change makes presence *worse* exactly where it matters:

- `EnterpriseHub.hubOnline` has exactly two writers: the poll itself, and the clear-on-unenrolled path. Nothing else sets it.
- `HubPresence.kt` says in its own KDoc that the hub serves presence over REST and “has no presence push”, and `RelayFrame`
  carries no presence frame — the tunnel delivers chat, mail and receipts, not who-is-online.
- So with the relay up (the ordinary case), the only source of presence was slowed from 30 s to 10 min. `HubFacade` picks
  the `HUB` path from `hubOnline` (ADR-229's ordering), so a colleague appearing or disappearing would be routed wrongly for
  up to ten minutes.

This is the same failure mode as Wave 2's `O10-05`/`O10-07`: an unmeasured saving bought by removing an invariant the code
documents. A correct version needs the hub to push presence over the relay first; that is a wire change and an owner
decision, not a stream's optimisation.

### What would make it revisitable
A hub→client presence event on the relay (roster member connect/disconnect) with `hubOnline` updated from it. Then the poll
becomes a reconcile at minutes scale and this optimisation is safe.

### Superseded detail (for the record)
- Context as written: a poll every 30 s while the tunnel was connected, “triggering 120 cellular radio state promotions per
  hour” — itself unmeasured.
- Claimed effect as written: “eliminates 95 % of background presence radio activations” — withdrawn.

---

## ADR-299 — Hub server capacity profile and deployment baseline (OPT-O02-07, OPT-O17-01)

### Decision
Treat the hub's memory behaviour under large connection counts as a deployment profile rather than a code defect: the
deployment templates and documentation mandate `-Xmx2g`, `-XX:+UseG1GC` and `-XX:+ExitOnOutOfMemoryError`, and say what each
one is for.

### Context
Audits OPT-O02-07 and OPT-O17-01 flagged that a hub started with a small heap hits a connection-count ceiling long before the
code stops working, and that the failure mode (OOM under load) is opaque.

### Why selected
The three flags are cheap, standard and make the failure legible: G1 keeps pause times tolerable with many sockets,
`ExitOnOutOfMemoryError` turns a wedged half-alive process into a restart, and 2 GB is the size the templates document.

**The numbers are estimates, not measurements.** “~350 KB per connection”, “~1.75 GB at 5 000 connections” and “a cliff
at ~1 200 with `-Xmx512m`” were never produced by a load run — `RelayLoadTest` exists but was not driven to those counts.
`PERF-H03` records the owed measurement. The configuration baseline stands regardless of the exact figures.

---

## Integrator note

Every percentage in this file that is not backed by a command in `logs/streams/hub/` was withdrawn on 2026-11-01 and
replaced by the structural claim plus the test that would establish the number. Two changes were **reverted or corrected**:
`OPT-O16-03` (ADR-298, rejected) and the `FileStore` ordering in `OPT-O17-02` (ADR-290, repaired).

---

## Owed measurements

`PERF-H01`..`PERF-H07` in `logs/streams/hub/backlog-additions.md` (folded into `docs/testing/TEST-BACKLOG.md`). The ones
that would replace an estimate in this file: `PERF-H05` (writer lock hold, ADR-290), `PERF-H02` (WAN throughput, ADR-291),
`PERF-H01` (directory sync memory, ADR-295/297), `PERF-H06` (fan-out encode, ADR-294) and `PERF-H03` (connection scale,
ADR-299). `PERF-H07` covers the crash consistency `ADR-290`'s repair and `ADR-292`'s deferred fsync both touch.
