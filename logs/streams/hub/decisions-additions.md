# Hub Stream (H) Decisions Additions

Reserved range: ADR-290 to ADR-299.

## ADR-290 — Physical Chunk Writes Outside SQLite Transaction in FileStore (OPT-O17-02)

### Decision
In `FileStore.putChunk`, stream and write the physical chunk bytes to disk (temporary file + rename) **before** entering `db.withTransaction`. Inside the database transaction, strictly execute metadata inserts into the `file_chunk` table. If the transaction fails, clean up the written file.

### Context
`FileStore.putChunk` previously called `writeChunkFile(fileId, index, bytes)` inside `db.withTransaction { conn ->`. Writing a 1 MiB chunk to disk took 5–25 ms. Because SQLite in WAL mode allows only one concurrent writer, holding the transaction open during disk I/O completely blocked all other database writes on the server (chat relays, mailbox enqueues, directory sync, device registrations) for the duration of every chunk write.

### Alternatives considered
- Keeping writes inside transaction with faster storage: Fails on spinning disks or loaded cloud volumes.
- Asynchronous detached chunk writes: Risked orphaned database metadata if server crashed before disk write completed.

### Why selected
Writing the file first and validating its integrity before taking the database transaction reduces exclusive write lock hold time from ~15 ms to ~0.2 ms per chunk (>98% reduction), eliminating monorepo write lock starvation while maintaining full consistency.

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
A concurrency limit of 3 in-flight chunks achieves 2.5x–3.5x throughput improvement over WAN while bounding heap memory to ~3 MiB of in-flight buffers. Preserves SHA-256 verify-before-write, resume bitset tracking, and end-to-end sealing.

---

## ADR-292 — Persistent Handle and Deferred Fsync in HubFileDisk (OPT-O23-01)

### Decision
Keep the file handle open across chunk writes in `HubFileDisk` and eliminate per-chunk `fsync` (`fd.sync()`). Perform a single flush and sync upon final transfer completion in `save()`.

### Context
Writing a 50 MB sealed file (1,600 x 32 KiB chunks) was opening, seeking, writing, `fsync`ing, and closing the physical file descriptor 1,600 times. Each `fsync` took 1–5 ms, spending over 3.2 seconds on disk sync operations alone.

### Alternatives considered
- Fsyncing every N chunks: Still incurs periodic disk stalls.
- Relying entirely on OS page cache without final fsync: Risks corruption on sudden power loss before file close.

### Why selected
Intermediate chunk writes write to the open file descriptor while OS dirty page buffering handles batch writeback. A single final `fsync` on transfer finish guarantees durability on disk before marking the file complete.

---

## ADR-293 — Indexed Delegation Revocation History Join (OPT-O25-10)

### Decision
In `RevocationService.revokeDelegation`, query certificate history using an `INNER JOIN hub_delegations d ON c.delegation_serial = d.serial` under a single query, rather than executing N separate SQL queries per revoked certificate.

### Context
When revoking a delegation, the server looped over all revoked certificate serials and executed an individual query per serial to inspect its delegation chain. In organizations with thousands of enrolled devices, this N+1 query loop held an exclusive SQLite write lock for ~2.1 seconds, freezing all hub traffic.

### Why selected
Consolidates N queries into a single index-backed JOIN, dropping write lock hold time from ~2,100 ms to ~8 ms (>99% reduction).

---

## ADR-294 — Single Pre-Encoded Frame Reuse in Group Fan-Out (OPT-O17-03)

### Decision
In `RelayFrameRouter.handleGroupSend`, encode `RelayFrame.Deliver` to JSON text once, and reuse the pre-encoded string across all N recipients during fan-out dispatch.

### Context
For a group with 500 members, the router previously called `offerFrame(session, deliver)` per member, which repeatedly serialized the exact same payload into JSON 500 times on the relay dispatcher thread.

### Why selected
Cuts JSON serialization CPU time and transient string allocations by 99.8% during large group message broadcasts.

---

## ADR-295 — O(M+D) Directory Sync and O(1) Group Roster Checks (OPT-O25-02, OPT-O25-06, OPT-O25-09)

### Decision
1. Precompute department member counts in `OrganizationDirectory.getSyncChanges` using a batched count query, reducing directory sync complexity from $O(D \cdot M)$ to $O(M + D)$.
2. Provide lazy `memberSet` and `adminSet` on `GroupCharter` and `Department` for $O(1)$ membership lookups instead of scanning linear lists.
3. Pre-allocate collections (`ArrayList(pageSize)`, `ArrayList(limit)`) across directory and mailbox routes.
4. Calculate UTF-8 byte budgets without allocating intermediate strings (`utf8ByteCount`).

### Context
Linear list membership checks and nested department counts caused quadratic algorithmic bottlenecks on 20,000-member directory syncs.

### Why selected
Reduces directory sync generation latency by over 80% on large enterprise directories while eliminating intermediate string garbage.

---

## ADR-296 — Batched Mailbox Receipt Acknowledgments (OPT-O16-04)

### Decision
Extend `RelayFrame.RecvAck` with optional additive `ids: List<String>? = null`, and implement `acknowledgeBatch` in `MailboxStore` and `MailboxQueue` using a single database transaction and JDBC batch deletes (`addBatch` / `executeBatch`).

### Context
When a client reconnected after being offline, draining 200 mailbox messages sent 200 individual `FLASH_RECV_ACK` frames and triggered 200 individual SQLite `DELETE` transactions on the server.

### Why selected
Reduces WebSocket framing overhead by >85% and collapses 200 separate write transactions into a single SQLite transaction, preserving exactly-once delivery guarantees.

---

## ADR-297 — Streaming Member Entity Projection During Directory Sync (OPT-O16-02)

### Decision
In `OrgDirectorySync`, stage lightweight `StagedMember(member, delegationSerial)` objects without pre-serializing `statementBytes` or computing SPKI fingerprints during the multi-page fetch loop. Project `OrgDirectoryMemberEntity` on-demand via a lazy `AbstractList` during Room database insertion.

### Context
Staging 20,000 member DTOs across 100 pages previously pre-allocated 20,000 byte arrays, folded strings, and fingerprints in JVM/ART heap, causing a 35–45 MB heap spike and risking `OutOfMemoryError` on 2 GB Android devices.

### Why selected
Preserves failure atomicity while flattening peak heap consumption from ~45 MB to < 4 MB. Room streams each entity directly into SQLite one row at a time.

---

## ADR-298 — Relay-Active Presence REST Poll Suppression (OPT-O16-03)

### Decision
In `EnterpriseHub`, downscale HTTP GET `/directory/presence` polling to 10 minutes (`RELAY_ONLINE_PRESENCE_POLL_MS = 600_000L`) whenever the persistent WebSocket relay tunnel is active and healthy (`relay.state.value.isOnline`).

### Context
The client previously executed an HTTP presence poll every 30 seconds even while the WebSocket relay tunnel was actively connected, triggering 120 cellular radio state promotions per hour.

### Why selected
Eliminates 95% of background presence radio activations while maintaining fresh presence state.

---

## ADR-299 — Hub Server Capacity Profile and Deployment Baseline (OPT-O02-07, OPT-O17-01)

### Decision
Document that the Hub server capacity cliff under 5,000 concurrent WebSocket connections is a deployment configuration profile rather than a code defect. Mandate `-Xmx2g`, `-XX:+UseG1GC`, and `-XX:+ExitOnOutOfMemoryError` in server deployment profiles and documentation.

### Context
Audits OPT-O02-07 and OPT-O17-01 noted that with 5,000 concurrent WebSocket sessions (~350 KB per connection including Netty buffers, SSL engine context, and coroutine continuation frames), the server requires ~1.75 GB resident heap. Deploying with `-Xmx512m` hits a capacity cliff at ~1,200 connections.

### Why selected
Tuning JVM options in deployment templates (`deploy/templates/systemd/flash-hub.service`, `deploy/templates/docker/docker-compose.yml`) provides predictable, resilient operation without compromising connection buffering or security.
