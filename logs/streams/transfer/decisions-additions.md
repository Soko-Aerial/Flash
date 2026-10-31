# Transfer Stream (T) Decisions Additions

Reserved range: ADR-250 to ADR-259.

## ADR-250 — Single-Pass Streaming Hash for Whole-File and Chunk Integrity (O05-01 / O07-01)

### Decision
Do not run an unconditional pre-pass `chunker.hashOnly` reading the entire file from start to finish before transfer begins. Instead, calculate the whole-file SHA-256 hash incrementally in `ChunkStream` / `MultiStreamDispatcher` while slicing chunks for the network, and forward pre-calculated hashes from chat/media staging where available.

### Context
Audits O05-01 and O07-01 identified that 1:1 and group transfers read every outgoing file at least twice (and up to 3x when unknown file sizes required an initial byte-counting pass). For large video transfers (e.g. 2 GB), this resulted in 4–6 GB of disk reads, introducing 20–40 seconds of flash storage stalls before the first chunk reached the socket.

### Alternatives considered
- Removing whole-file verification entirely (REJ-02): Rejected because whole-file hashing protects against disk corruption, multi-session truncation, and sparse reassembly defects.
- Caching hashes in database: Partially effective, but does not help first-time user transfers.

### Why selected
Maintains 100% cryptographic integrity verification (REJ-02 invariant preserved) while cutting file disk I/O in half (~5.8 GB -> ~1.9 GB on a 2 GB file).

---

## ADR-251 — Lock-Free Chunk Hashing and Disk Streaming in ReceivePipeline (O07-04)

### Decision
Execute per-chunk SHA-256 integrity verification (`Sha256.digest`) and storage disk writes (`sink.write`) outside of the pipeline's `PlatformLock`.

### Context
`ReceivePipeline.onFrame` held a monitor lock across parsing, session lookup, SHA-256 verification, and blocking disk writes. In multi-stream environments (4–8 concurrent TCP streams), all receiver worker threads were forced to serialize their disk and hashing work behind a single global lock.

### Why selected
`Sha256.digest(frame.data)` and `sink.write(index, data)` are stateless with respect to the pipeline session map. Decoupling them allows parallel receiver threads to hash and stream chunks concurrently, taking the global lock only briefly to validate metadata and update the resume bit vector.

---

## ADR-252 — Seekable Chunk Source and Prefix Fast-Forwarding for Transfer Resume (O23-02)

### Decision
Introduce `SeekableChunkSource` extending `ChunkSource` with `fun openAt(byteOffset: Long): Source`, and allow `ChunkStream` to skip directly to `startIndex` without reading or hashing discarded prefix chunks.

### Context
When resuming an interrupted transfer (e.g. at 90% completion of a 10 GB file), previous implementations read and computed SHA-256 for all 9 GB of already-transferred chunks in order to drop them in user space. This created gigabytes of redundant flash I/O and wasted hundreds of seconds of CPU time.

### Why selected
Direct seeking via `openAt(startIndex * chunkSize)` and Okio `stream.skip(bytes)` eliminates 90%+ of disk I/O and CPU hashing on resumed transfers, achieving instant resume turnaround.

---

## ADR-253 — In-Flight Chunk Retransmission Endgame and Grace Period Reduction (O07-06)

### Decision
Add an active endgame retransmission phase in `MultiStreamDispatcher` that re-checks missing chunk indexes and re-dispatches unacknowledged chunks before closing feed channels, while reducing `ACK_DRAIN_GRACE_MS` from 15,000 ms to 3,000 ms.

### Context
Under packet loss or Wi-Fi congestion, if a single chunk dropped in flight, the sender stalled waiting for the full 15-second drain grace period before tearing down the session and forcing a full reconnect cycle.

### Why selected
Actively retransmitting unacknowledged chunks during the endgame eliminates the 15-second hang and prevents unnecessary session tear-downs.

---

## ADR-254 — Staging Transfers in .part Files with Atomic Rename (O07-02)

### Decision
Stage all incoming file assembly in a `.part` temporary file (e.g. `<destination>.part`), and atomically rename (`FileSystem.SYSTEM.atomicMove`) to the final file name only after whole-file SHA-256 verification passes.

### Context
Streaming directly into the target file name caused antivirus scanners (such as Windows Defender) and system indexers to lock or scan partial files with executable or media extensions mid-transfer. Additionally, aborted transfers left incomplete, corrupt files in user-visible folders.

### Why selected
Shields active transfers from antivirus lockouts and guarantees that incomplete or corrupted files never appear in the destination directory.

---

## ADR-255 — Gated Finding O07-10 (REJ-01) Recorded as BLOCKED

### Decision
Do NOT implement O07-10 ("Bypassing TLS / Disabling Ephemeral ECDH in Local LAN Mode"). Finding is marked as **BLOCKED — REJECTED BY INVARIANT REJ-01**.

### Context
Proposal O07-10 suggested disabling TLS 1.3 encryption on local Wi-Fi networks to save CPU cycles. This was evaluated in audit document `rejected.md` (REJ-01) and strictly rejected.

### Invariant
AGENTS.md §19 ("Never disable TLS or certificate/identity validation permanently just because development is easier without it") and Security Invariant §5.3.1 dictate that all LAN peer communications must remain encrypted and authenticated with TOFU certificates against eavesdropping and ARP spoofing in shared environments.
