# Domain O07 — File-Transfer Throughput and Per-Chunk Cost Performance Audit
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Domain:** O07 — File-transfer throughput and per-chunk cost  
**Target Runtimes:** Android (ART), Desktop (JVM 21 / Skia)

---

## 1. Files Read & Audited

The following implementation files across `:core:transfer`, `:core:common`, `:core:network`, `:core:security`, `:core:persistence`, `:app`, and `:desktop` were read and audited in detail:

### Transfer Engine Core & Chunking
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkFrame.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkBufferPool.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Sha256.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/WholeFileVerifier.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ResumeBitVector.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/SendPipeline.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkSource.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkSink.kt`

### Multi-Stream Dispatcher & Session Management
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamReceiver.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamProgress.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/StreamChannel.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/RollingRateMeter.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/FlashTransferRepository.kt`

### Storage, Sinks & Framing
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessChunkSink.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessSinkHandle.kt`
- `core/transfer/src/androidMain/kotlin/com/transfer/flash/core/transfer/policy/DestinationPolicy.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/store/TransferStore.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/RoomTransferStore.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferChunkDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/TransferEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/TransferChunkEntity.kt`

### Network, Security & Platform Shims
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/SecureBinaryFrameCodec.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/datachannel/DataChannelServer.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/datachannel/DataChannelClient.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashTransferProfile.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceClassifier.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/MemoryGovernor.kt`

---

## 2. Hot Paths with Execution Frequency & Dispatchers

The performance-critical hot paths for file transfer throughput are:

1. **Outbound Chunk Materialization & Framing (`Dispatchers.Default` / Materializer Coroutine)**
   - *Path:* `ChunkStream.next()` -> `stream.read(data)` -> `fileDigest.update(data)` -> `Sha256.digest(data)` -> `ChunkFrame.serialize(frame)` -> `feeds[i].send(prepared)`.
   - *Frequency:* 150 Hz to 1,500 Hz during active transfers (e.g. 800 chunks/s for 50 MB/s at 64 KB chunks).
   - *Cost:* Per-chunk reads from source, copies payload into okio `Buffer` segments twice for SHA-256 hashing, creates a third segment copy during `ChunkFrame.serialize`, and allocates a fresh 64 KB–256 KB `ByteArray` via `out.readByteArray()`.

2. **Outbound Wire Encryption & Socket Transmission (`Dispatchers.Default` / Worker Coroutines)**
   - *Path:* `runWorker` -> `wire.sendFrame(prepared.frameBytes)` -> `SecureBinaryFrameCodec.encrypt` -> `wsSession.connection.sendBinary` (or TCP DataChannel socket write).
   - *Frequency:* 150 Hz to 1,500 Hz.
   - *Cost:* Double encryption per chunk: allocates CSPRNG nonce, computes AES-256-GCM encryption over frame bytes, copies into `FSEC` envelope `ByteArray`, then writes into TLS engine (`SSLEngine.wrap`) which performs a second AES-GCM or ChaCha20 encryption.

3. **Inbound Wire Decryption, Deserialization & Verification (`Dispatchers.IO` / Network Reader Thread)**
   - *Path:* Socket read -> TLS `SSLEngine.unwrap` -> `SecureBinaryFrameCodec.decrypt` -> `ReceivePipeline.onFrame` (under `PlatformLock`) -> `ChunkFrame.parse` -> `Sha256.digest` -> `session.resolvedSink.write`.
   - *Frequency:* 150 Hz to 1,500 Hz.
   - *Cost:* Decrypts AES-GCM, allocates unpooled `ByteArray` via `buf.copyOfRange` in `ChunkFrame.parse`, computes SHA-256 digest in-place under the pipeline lock, and writes synchronously to storage handle (`RandomAccessSinkHandle.writeAt`).

4. **Progress Telemetry Publishing & Invalidation (`Dispatchers.Default` / Watcher Coroutine)**
   - *Path:* `MultiStreamDispatcher.publishProgress()` -> `_progress.value = ...` -> `RealFlashTransferRepository.collector` -> `updateTransferState` -> `_activeTransfers.value = merged`.
   - *Frequency:* 100 Hz (every 10 ms via `WATCH_POLL_MS`; 4 Hz / 250 ms during active voice calls).
   - *Cost:* Recalculates `RollingRateMeter`, checks monotonic atomic `confirmedCount`, allocates new `FlashTransfer` instance and copies the entire transfer list for the UI state flow 100 times per second.

5. **Done-Set ACK Persistence (`Dispatchers.IO` / Repository Scope)**
   - *Path:* `RealFlashTransferRepository.onIncomingChunkConfirmed` (receiver) / `progress.collect` (sender) -> `activeStore.markChunksDone` -> `RoomTransferStore.markChunksDone` -> `TransferChunkDao.insertAll`.
   - *Frequency:* 5 Hz to 50 Hz (every 32 chunks / `ACK_BATCH`).
   - *Cost:* Maps 32 `Int` indexes into 32 `TransferChunkEntity` objects, executes `INSERT OR IGNORE` transaction into SQLCipher encrypted SQLite database. For a 10 GB transfer, executes 5,120 distinct database transactions.

6. **Post-Transfer Whole-File Hash Verification (`Dispatchers.IO` / Worker Dispatcher)**
   - *Path:* `ReceivePipeline.buildComplete` -> `RealFlashTransferRepository.verifyIncomingFile` -> `WholeFileVerifier.check`.
   - *Frequency:* Once per received file on completion.
   - *Cost:* Re-reads the entire file sequentially from disk using 64 KB buffers, piping bytes into `IncrementalSha256`, taking tens to hundreds of seconds on slow mobile flash storage (eMMC 5.1).

---

## 3. Checklist Verification Table

| # | Checklist Item | Status | Verification Summary & Evidence |
|---|---|---|---|
| 1 | Chunk size selection across tiers (64 KB vs 256 KB vs 1 MB) and reason | **Problem** (`OPT-O07-07`) | `Chunker.kt:85` sets `MAX_CHUNK_SIZE_BYTES = 256 * 1024`, hard-capping any requested chunk size. However, `FlashTransferProfile.HIGH` declares `maxAdaptiveChunkSizeBytes = 1024 * 1024` (1 MB) and `ChunkFrame.MAX_CHUNK_DATA_BYTES` accepts up to 1 MB. High-performance links are artificially capped to 256 KB. Measured: 256 KB is 44% faster than 64 KB (48.7 MB/s vs 33.8 MB/s). |
| 2 | Buffer allocation per chunk vs ChunkBufferPool utilization (send side vs receive side) | **Problem** (`OPT-O02-01` / `OPT-O07-01`) | Sender borrows arrays from `ChunkBufferPool.acquire` and releases them. Receiver (`ChunkFrame.parse:330` / `ReceivePipeline.handleChunk`) completely bypasses `ChunkBufferPool`, using `buf.copyOfRange` to allocate a fresh unpooled `ByteArray` for every chunk. Measured: 200 MB transfer with 256 KB chunks allocated 1,018 MB of heap arrays (5.1× amplification). |
| 3 | Number of memory copies between disk and socket | **Problem** (`OPT-O07-01`) | **17 distinct memory copies** end-to-end (9 on sender, 6 on receiver, plus 2 on whole-file verify). Disk -> `ChunkBufferPool` -> Okio segment (whole hash) -> Okio segment (chunk hash) -> Okio segment (frame writer) -> `readByteArray()` -> AES-GCM ciphertext -> FSEC frame -> TLS engine -> Socket. |
| 4 | SHA-256 implementation: hardware acceleration, MessageDigest allocation / reuse | **Problem** (`OPT-O07-05`) | Android utilizes ARMv8 Crypto Extensions via Conscrypt. However, `Sha256.digest()` constructs a new `IncrementalSha256` + `HashingSink` on every chunk, performing a JCA `MessageDigest.getInstance("SHA-256")` provider lookup under security lock and buffering bytes into okio segments. Measured: Direct reusable `MessageDigest` is 1.19× faster with 0 MB allocation vs 1.5 MB churn. |
| 5 | Hashing on same thread as disk/socket I/O vs separate pipeline stage | **Problem** (`OPT-O07-04`) | `ReceivePipeline.onFrame` runs on the network reader thread and acquires `PlatformLock`. Under this lock, it parses the frame, computes SHA-256, and executes `session.resolvedSink.write` (disk write). A storage stall or fsync blocks the network thread and fills the TCP window. |
| 6 | ACK and window design: stop-and-wait vs sliding window, in-flight byte budget | **Problem** (`OPT-O07-06`) | Pipelined sliding window with bounded feed queues (8–16 frames). Receiver ACKs every 32 chunks (2–8 MB). However, `MultiStreamDispatcher` lacks an in-flight chunk retransmission queue. If a chunk is corrupted in transit, the sender finishes sending all chunks and waits out a 15-second `ACK_DRAIN_GRACE_MS` before failing the entire transfer and restarting via reconnect. |
| 7 | Per-chunk persistence of done-set (batching frequency, SQLite write amplification) | **Problem** (`OPT-O07-03`) | Persists chunks in batches of 32 (`insertAll`). However, every chunk is a row in relational table `transfer_chunks` (`TransferChunkEntity`). A 10 GB file generates 163,840 row inserts across 5,120 separate SQLite transactions + SQLCipher page encryptions, causing severe write amplification and flash wear. |
| 8 | Progress callback frequency and throttling (UiPacing.throttleLatest 100ms) | **Problem** (`OPT-O07-08`) | UI consumes progress via `throttleLatest(100ms)`. However, `MultiStreamDispatcher.WATCH_POLL_MS = 10L` publishes progress at 100 Hz. `RealFlashTransferRepository` collects at 100 Hz and allocates a new `FlashTransfer` and copies the entire transfer list 100 times/sec on the worker dispatcher. |
| 9 | Resume bookkeeping complexity and BitVector / done-set operations | **Problem** (`OPT-O07-09`) | Bit arithmetic in `ResumeBitVector` uses `LongArray` efficiently (EXP-008). However, when growing `receiverDone` geometrically in `RealFlashTransferRepository.kt:1203`, it calls `grown.reconcile(existing.doneIndexes())`, allocating an `ArrayList<Int>` of boxed integers instead of performing a word-level array copy `existing.words.copyInto(grown.words)`. |
| 10 | Whole-file verification second pass: re-reading full file after receive on slow mobile storage | **Problem** (`OPT-O07-01`) | `WholeFileVerifier.check` re-reads the entire file from disk in 64 KB buffers after receive completion. On low-end mobile storage (eMMC 5.1 @ 40 MB/s), a 4 GB video takes 100 seconds to re-verify after the transfer has finished. Combined with sender `hashOnly` pre-pass, files are read 3 times and written once. |
| 11 | Temp file and atomic rename strategy | **Problem** (`OPT-O07-02`) | Neither Android nor Desktop uses a staging `.tmp` file. Destination is opened directly at final path (`dest = File(destDir, safeRelativePath)`). Interrupted or failed transfers leave partial/corrupt files visible to the user and indexed by MediaStore. On Windows, antivirus scanners lock active files, stalling chunk writes. |
| 12 | Bottleneck classification: storage vs network vs TLS vs hashing | **Analyzed** (§4 & §5) | 2.4 GHz Hotspot: network-limited (2.0 MB/s, EXP-001). 5 GHz / LAN: CPU and hashing-limited on budget mobile (5 passes of SHA-256), storage-limited on eMMC. Gigabit LAN / Desktop: TLS and double-encryption limited (AES-GCM inside TLS). |
| 13 | Benchmark matrix (AGENTS.md §23) | **Analyzed** (§6) | Loopback 200 MB benchmark executed on PC (64 KB: 33.8 MB/s; 256 KB: 48.7 MB/s; direct SHA-256: 270 MB/s). Matrix documented in §6 showing existing cells (EXP-001, EXP-075), current measurements, and owed device tests. |

---

## 4. Local Loopback Benchmark Measurements

A deterministic, zero-allocation synthetic stream benchmark was constructed and executed locally via `:core:transfer:jvmTest` (OpenCode/JVM 25, Windows 11, 8 CPUs) transferring 200 MB (209,715,200 bytes) end-to-end through `SendPipeline`, in-memory channel, `ReceivePipeline`, `ChunkFrame` serialization/deserialization, SHA-256 verify-before-write, and ACK/COMPLETE feedback.

### 4.1 Loopback Transfer Results (200 MB, Median of 3 Runs)

| Scenario | Chunk Size | Chunks | Wall Time (ms) | CPU Time (ms) | Throughput (MB/s) | Thread Heap Alloc (MB) |
|---|---|---|---|---|---|---|
| **Pipeline 64 KB** | 64 KiB | 3,200 | 5,917.3 ms | 5,656.3 ms | **33.8 MB/s** | **456 MB** |
| **Pipeline 256 KB** | 256 KiB | 800 | 4,105.2 ms | 3,937.5 ms | **48.7 MB/s** | **1,018 MB** |

**Observations:**
- **Chunk size impact:** Moving from 64 KB to 256 KB chunks increases throughput by **+44.1%** (33.8 MB/s -> 48.7 MB/s) and cuts wall time from 5.92 s to 4.11 s by reducing framing, channel context-switching, and SHA-256 initialization overhead by 75% (800 vs 3,200 chunks).
- **Allocation amplification:** 256 KB chunks generated **1,018 MB** of heap allocations for a 200 MB file transfer (**5.09× allocation amplification**)! This occurs because `ReceivePipeline` allocates unpooled arrays in `ChunkFrame.parse` via `buf.copyOfRange` instead of acquiring from `ChunkBufferPool`.

### 4.2 SHA-256 Microbenchmark (200 MB, 3,200 × 64 KB blocks)

| Implementation | Wall Time (ms) | Heap Alloc (MB) | Throughput (MB/s) | Speedup |
|---|---|---|---|---|
| **Current `Sha256.digest` (Okio HashingSink + JCA lookup)** | 877.4 ms | 1.50 MB | 228.0 MB/s | Baseline (1.00×) |
| **Direct Reusable `MessageDigest` (In-Place)** | 739.7 ms | **0.00 MB** | **270.4 MB/s** | **1.19× faster** |

**Observations:**
- Direct in-place `MessageDigest` update and digest reduces execution time by 16% and completely eliminates 1.5 MB of transient wrapper allocations. On mobile Android under multi-threaded lock contention, avoiding JCA provider lookups prevents thread stalls.

---

## 5. Detailed Optimization Findings

### OPT-O07-01 — Triple Disk Read and Five SHA-256 Passes per Transfer Cause Massive I/O and CPU Bottlenecks
- **Impact rank:** High        **Evidence:** MEASURED & STATIC-ARITHMETIC
- **Dimension(s):** T / C / S / B   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:545, 1469` -> `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt:205` -> `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt:168, 279` -> `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/WholeFileVerifier.kt:48`
- **Bottleneck class (transfer/media only):** storage | CPU | hashing
- **What is wasteful:** Every 1:1 file transfer performs **three full sequential disk reads** and computes **five full passes of SHA-256** across sender and receiver:
  1. *Sender Pass 1 (Disk Read #1 & Hash #1):* `RealFlashTransferRepository.executeSend` passes `fileSha256Hex = null`, causing `MultiStreamDispatcher.send()` to run `chunker.hashOnly(source)`. This reads the entire file sequentially from disk into memory to compute `FILE_START.fileSha256Hex`.
  2. *Sender Pass 2 (Disk Read #2, Hash #2 & Hash #3):* `openChunkStream` re-reads the entire file from disk to slice chunks. For every chunk, it updates a running `fileDigest` (Hash #2) AND computes an independent per-chunk `Sha256.digest(chunk)` (Hash #3).
  3. *Receiver Pass 1 (Disk Write #1 & Hash #4):* `ReceivePipeline.handleChunk` computes `Sha256.digest(frame.data)` (Hash #4) to verify before writing to disk.
  4. *Receiver Pass 2 (Disk Read #3 & Hash #5):* After all chunks are written, `WholeFileVerifier.check` re-opens the file from disk and reads all bytes a third time in 64 KB buffers to verify against `FILE_START.fileSha256Hex` (Hash #5).
- **Cost model:**
  - File size: 4 GB video transfer.
  - Disk I/O: 3 reads × 4 GB = 12 GB read + 4 GB write = 16 GB disk traffic. On budget eMMC 5.1 storage (sequential read ~40 MB/s), 12 GB read takes **300 seconds (5 minutes)** of pure storage I/O!
  - SHA-256 compute: 5 passes × 4 GB = 20 GB of data digested. On ARM Cortex-A55 software SHA-256 (~60 MB/s), takes **333 seconds** of single-core CPU.
- **User-visible effect:** Severe delay before transfer starts (sender reads file once before sending first byte) and after transfer reaches 100% (receiver sits on "Verifying..." for minutes). High battery drain and phone thermal throttling.
- **Expected gain:** Eliminating Pass 1 on sender and streaming WholeFileVerifier on receiver cuts disk reads from 3 to 1 and SHA-256 passes from 5 to 2. Reduces transfer completion wall time by 40%–60% on mobile storage.
- **Proposed change:**
  1. On sender: Cache or pre-compute `fileSha256Hex` if known (e.g. from chat attachment staging or SAF metadata); or compute chunk hashes and running whole-file hash in the single streaming pass, emitting `fileSha256Hex` in a final `FILE_END` / completion frame rather than requiring it up-front in `FILE_START` (with wire version negotiation).
  2. On receiver: If transfer is single-stream or chunks arrive in sequential order, maintain an incremental `WholeFileDigestProvider` during chunk ingestion. Only fall back to `WholeFileVerifier.check` second pass when out-of-order chunks or resume holes were present.
  3. Increase `WholeFileVerifier.BUFFER_BYTES` from 64 KB to 256 KB or 1 MB to maximize sequential read bandwidth.
- **Invariants touched (5.3):** 5.3.1 (Security: per-chunk and whole-file integrity verification preserved).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Transfer 200 MB file measuring total disk read bytes via OS performance counters and wall time. Pass threshold: total disk read bytes drops from 600 MB to 200 MB; wall time drops >35%.
- **Verifier result:** Survived.
- **Related:** `EXP-001`, `EXP-075`, `ADR-068`.

---

### OPT-O07-02 — Missing Staging File (.tmp) and Atomic Rename Strategy Causes Corrupt Destination Exposure and Antivirus Contention
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / T   **Runtime:** Android | Desktop
- **Component:** `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:941-955` -> `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1808-1822` -> `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1476`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:** When receiving an incoming file, `sinkFactory` resolves the destination directly to its final user-visible path:
  `val dest = File(destDir, safeRelativePath).canonicalFile`
  `val handle = OkioRandomAccessSinkHandle(dest.absolutePath.toPath(), start.totalBytes)`
  Chunks are written directly into `dest`. There is no `.tmp` staging file or atomic rename. If a transfer is cancelled, interrupted by network drop, crashed, or fails hash verification:
  1. A half-written, corrupt file remains sitting in the destination directory under its final file name.
  2. On Android, MediaStore scanner or file managers attempt to index incomplete video/image files, producing corrupted thumbnails or playback errors.
  3. On Windows Desktop, real-time antivirus scanners (e.g. Windows Defender) detect known extensions (`.exe`, `.zip`, `.mp4`) and lock the file while `RandomAccessSinkHandle` is actively writing chunks, causing `IOException` or 200–500 ms write latency spikes.
  4. If `WholeFileVerifier` detects a mismatch (`RealFlashTransferRepository.kt:1476`), it executes `fileSystem.delete(localPath.toPath())`, but if the process dies before verification, the corrupted file remains permanently.
- **Cost model:**
  - Every incoming file transfer on Android and Desktop.
  - Exposure window: 100% of the transfer duration (minutes/hours).
  - Risk: Silent data corruption exposure, antivirus lock contention on Windows.
- **User-visible effect:** Incomplete corrupt files visible in Downloads folder; Windows Defender freezing transfers of large `.zip` or `.exe` files; thumbnail generation crashes on Android.
- **Expected gain:** Eliminates antivirus lock contention during active writing; guarantees atomic file presentation (the file only appears at its final path once 100% verified).
- **Proposed change:**
  1. In `DiscoveryEngineHolder` and `DesktopEngine`'s `sinkFactory`, create the file as `File(destDir, ".$safeRelativePath.${start.transferId}.tmp")`.
  2. In `RealFlashTransferRepository.onIncomingCompleted`, once `WholeFileCheck.MATCH` passes, atomically rename `destTmp.renameTo(destFinal)` (using `java.nio.file.Files.move(..., StandardCopyOption.ATOMIC_MOVE)` on desktop and `File.renameTo` on Android).
  3. Clean up `.tmp` files on transfer rejection, cancellation, or startup sweep.
- **Invariants touched (5.3):** 5.3.3 (Durability and ordering: atomic file completion).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test asserting that during transfer `destFile.exists() == false`, `tmpFile.exists() == true`, and only upon `onIncomingCompleted(verified = true)` does `destFile.exists() == true`.
- **Verifier result:** Survived.
- **Related:** `ERROR-770`, `ADR-068`.

---

### OPT-O07-03 — Relational Write Amplification: 5,120 SQLite Transactions Inserting 163k Rows per 10 GB Transfer
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / C / B / T   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:587, 1214` -> `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/RoomTransferStore.kt:113` -> `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferChunkDao.kt:18` -> `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/TransferChunkEntity.kt:10`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:** Transfer progress persistence is modeled relationally as one database row per chunk in `transfer_chunks` (`TransferChunkEntity(transferId, chunkIndex, done)`).
  On every ACK batch (32 chunks), `RealFlashTransferRepository` calls `store.markChunksDone(transferId, fresh)` on both send and receive sides.
  In `RoomTransferStore`, this executes:
  `transferChunkDao.insertAll(indexes.map { TransferChunkEntity(transferId, it, done = true) })`.
  For a 10 GB file at 64 KB chunks (163,840 chunks):
  - 163,840 rows are mapped and inserted into SQLite.
  - 5,120 distinct `INSERT OR IGNORE` transactions are committed.
  - In SQLCipher, each transaction involves encrypted B-tree modifications, page allocation, and WAL file syncing.
  - At startup, `TransferChunkDao.allDoneChunks()` performs a full scan over all historical chunk rows.
  All of this relational overhead exists solely to persist a bit-vector! A 163,840-chunk bitset requires **only 20,480 bytes (20 KB)** of binary data.
- **Cost model:**
  - 10 GB file with 64 KB chunks = 163,840 chunks.
  - Multiplier: 5,120 SQLite transactions per transfer.
  - Storage cost: ~10 MB database growth per 10 GB transferred (50 bytes/row × 163,840 + primary key B-trees).
  - Write amplification: 5,120 fsync/WAL writes on mobile eMMC/UFS storage.
- **User-visible effect:** Heavy flash write amplification, battery drain, UI stutter in message list (due to database lock contention on SQLCipher WAL), and slow cold startup while `allDoneChunks()` reads hundreds of thousands of chunk rows.
- **Expected gain:** Reduces database transactions from 5,120 to <10 per transfer (e.g. periodic 5-second checkpoint or only on pause/exit). Shrinks database storage for transfer state by 99.8% (from 10 MB to 20 KB per transfer).
- **Proposed change:**
  1. Deprecate individual `transfer_chunks` rows. Add a `resumeBits: ByteArray?` BLOB column to `transfers` table.
  2. Periodically (e.g. every 5 seconds) or on transfer state transitions (pause, interrupt, disconnect), serialize `ResumeBitVector.toSerialized()` (compact little-endian words) and update `transfers.resumeBits`.
  3. On resume, read `transfers.resumeBits` in a single O(1) query and deserialize via `ResumeBitVector.fromSerialized()`.
- **Invariants touched (5.3):** 5.3.3 (Durability: resume state remains fully durable across process death).
- **Risk / effort:** Medium risk; M effort; requires Room schema migration (step 21+).
- **Prove it:** Measure SQLite write transactions and database file growth during a 1 GB transfer. Pass threshold: transactions drop from 512 to <= 5; database growth drops from 1 MB to < 10 KB.
- **Verifier result:** Survived.
- **Related:** `EXP-008`, `EXP-009`, `OPT-O02-06`.

---

### OPT-O07-04 — Synchronous Lock-Bound Pipeline: Network Reader Thread Computes SHA-256 and Writes Disk Under PlatformLock
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T / J / C   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt:129, 286-316`
- **Bottleneck class (transfer/media only):** protocol | storage
- **What is wasteful:** In `ReceivePipeline.kt`, all frame processing is synchronized under a single private `PlatformLock`:
  `public fun onFrame(bytes: ByteArray): List<ReceiveEvent> = lock.withLock { ... }`
  When an incoming chunk arrives from the WebSocket or DataChannel socket reader:
  1. The socket reader thread enters `lock.withLock`.
  2. Inside the lock, it calls `Sha256.digest(frame.data)` to compute the chunk hash.
  3. Inside the lock, it calls `session.resolvedSink?.write(frame.index, frame.data)` which performs a synchronous seek and write to disk via `RandomAccessSinkHandle.writeAt`.
  If multiple parallel data channels feed the receive pipeline (multi-stream), ALL streams contend on this single lock. More critically: if disk I/O blocks or experiences a flash write latency spike (common on eMMC 5.1), the network reader thread is completely blocked while holding the lock. As a result, TCP socket receive buffers fill up, TCP window shrinks to zero, and the sender's workers stall waiting on socket write buffers.
- **Cost model:**
  - Parallel streams: 2–4 streams concurrently receiving chunks.
  - Unit cost: SHA-256 computation (0.5–2 ms) + Disk write (1–10 ms, up to 50 ms during flush).
  - Multiplier: 150 to 1,500 chunks/s.
  - Contention: Lock held for 100% of hashing + disk I/O duration.
- **User-visible effect:** Severe throughput degradation on multi-stream transfers; network stalls; bursty transfer speed graphs; high latency jitter.
- **Expected gain:** Unblocks network reader threads immediately; decouples network ingestion from disk write latency; improves multi-stream throughput by 20%–40% on multi-core systems.
- **Proposed change:**
  1. Move `Sha256.digest(frame.data)` and `session.resolvedSink?.write` outside the global pipeline lock.
  2. The pipeline lock should only protect session state, bit-vector marking, and ACK list generation (which take < 1 microsecond).
  3. Offload chunk writes to a dedicated disk writer actor / channel or execute them asynchronously on `Dispatchers.IO` before passing completion to the session vector.
- **Invariants touched (5.3):** 5.3.1 (Security: verify-before-write preserved), 5.3.3 (Durability: chunks marked done only after write succeeds).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Multi-stream transfer benchmark simulating 10 ms storage write latency spikes. Pass threshold: throughput does not drop below 80% of baseline.
- **Verifier result:** Survived.
- **Related:** `EXP-001`, `ERROR-013`.

---

### OPT-O07-05 — JCA Provider Lookup and Okio Segment Buffering Overhead in Per-Chunk Sha256.digest
- **Impact rank:** Medium        **Evidence:** MEASURED
- **Dimension(s):** C / M / T   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Sha256.kt:49-54, 153-163`
- **Bottleneck class (transfer/media only):** hashing | CPU
- **What is wasteful:** Every single call to `Sha256.digest(bytes, offset, length)`:
  ```kotlin
  public fun digest(bytes: ByteArray, offset: Int, length: Int): ByteArray {
      val accumulator = IncrementalSha256()
      accumulator.update(bytes, offset, length)
      return accumulator.digestRaw()
  }
  ```
  `IncrementalSha256()` instantiates `HashingSink.sha256(blackholeSink())`. On JVM/Android:
  1. `HashingSink.sha256` invokes `MessageDigest.getInstance("SHA-256")`. This queries the security provider registry (`Security.getProviders()`) under a synchronized JVM lock on every chunk!
  2. `sink: BufferedSink = hashing.buffer()` allocates an okio `RealBufferedSink`.
  3. `sink.write(bytes, offset, length)` copies the entire chunk into okio 8 KB `Segment` buffers before hashing!
  4. `hashing.hash.toByteArray()` allocates a 32-byte copy of the digest.
  5. The accumulator, hashing sink, buffer, and segments are discarded to the garbage collector.
- **Cost model:**
  - 10 GB transfer with 64 KB chunks = 163,840 chunks.
  - Frequency: 163,840 allocations and JCA provider lookups per transfer on sender AND receiver (327,680 total).
  - Measured (Local Benchmark on 200 MB, 3,200 chunks):
    * Current `Sha256.digest`: 877.4 ms, 1.50 MB allocations, 228.0 MB/s.
    * Reusable in-place `MessageDigest`: 739.7 ms, 0.00 MB allocations, 270.4 MB/s.
- **User-visible effect:** 16%–25% extra CPU time spent in JCA provider lookups, segment copying, and GC allocation churn during file transfers.
- **Expected gain:** 16%–20% faster SHA-256 hashing throughput; completely eliminates wrapper object allocations.
- **Proposed change:**
  1. On JVM/Android, use a `ThreadLocal<MessageDigest>` initialized once via `MessageDigest.getInstance("SHA-256")`.
  2. In `Sha256.digest()`, call `md.reset()`, `md.update(bytes, offset, length)`, and `md.digest(out, 0, 32)` directly into a reusable or newly allocated 32-byte array without intermediate okio `Segment` copies.
- **Invariants touched (5.3):** 5.3.1 (Security: identical SHA-256 output, verified by `Sha256Test`).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run `TransferLoopbackBenchmarkTest.benchmark200MbTransfer()`. Pass threshold: SHA-256 rate exceeds 260 MB/s; heap allocations drop to 0 MB.
- **Verifier result:** Survived.
- **Related:** `EXP-022`, `OPT-O02-01`.

---

### OPT-O07-06 — Lack of In-Flight Chunk Retransmission Forces 15-Second Timeout and Full Restart on Single Corrupt Chunk
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T / N   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt:426-451, 714`
- **Bottleneck class (transfer/media only):** protocol
- **What is wasteful:** In `MultiStreamDispatcher.kt`, chunk dispatch is strictly a single-pass pipelined push. Chunks are sliced, enqueued into feed channels, and sent over the wire once.
  If a single chunk experiences bit-flip corruption on the Wi-Fi hop or is rejected by the receiver with `HASH_MISMATCH` or `WRITE_FAILED`:
  1. The receiver drops the chunk and does not include its index in `ACK_BATCH` (implicit NACK).
  2. The sender does NOT notice or retransmit the missing chunk while streaming. It continues sending all remaining chunks until the source is exhausted.
  3. Once all workers finish sending and exit, `aliveWorkers` drops to 0.
  4. The sender enters lines 440–445:
     `val deadline = ... + ACK_DRAIN_GRACE_MS` (`ACK_DRAIN_GRACE_MS = 15_000L`).
     The sender sits idle, doing nothing, for **15 full seconds**!
  5. When the 15-second timer expires, `MultiStreamDispatcher` fails the transfer with:
     `"ack drain timeout: 1 chunk(s) unconfirmed after all streams exited"`.
  6. The transfer repository marks the transfer `Failed`, requiring a complete reconnect/resume cycle to re-send that single missing 64 KB chunk!
- **Cost model:**
  - One corrupted chunk out of 10,000 chunks (e.g. 0.01% packet error rate on congested 2.4 GHz Wi-Fi).
  - Delay: 15,000 ms (15 seconds) idle drain wait + 2–5 seconds reconnect and handshake = 17–20 seconds wasted per lost chunk.
- **User-visible effect:** File transfers stall near 99% for 15 seconds, report failure, and then resume, confusing the user and increasing overall transfer time dramatically.
- **Expected gain:** Eliminates the 15-second freeze on dropped chunks; repairs missing chunks within 1 RTT (< 50 ms).
- **Proposed change:**
  1. Implement an end-game repair phase in `MultiStreamDispatcher`: when the materializer finishes reading the source, if `confirmedCount < totalChunks`, query `confirmedVector.missingIndexes()`.
  2. Re-slice and re-transmit only the missing indexes across live workers before exiting.
  3. Reduce `ACK_DRAIN_GRACE_MS` to 2,000 ms once active retransmission is present.
- **Invariants touched (5.3):** 5.3.3 (Durability: exact chunk convergence).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Unit test simulating a dropped chunk; assert transfer completes successfully without triggering `failedLocked` or waiting 15 seconds.
- **Verifier result:** Survived.
- **Related:** `ERROR-013`, `EXP-001`.

---

### OPT-O07-07 — High Profile 1 MB Chunk Plan Silently Clamped to 256 KB by Chunker Hard Bounds
- **Impact rank:** Medium        **Evidence:** MEASURED & STATIC-ARITHMETIC
- **Dimension(s):** T / C / N   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt:85, 98` -> `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashTransferProfile.kt:84`
- **Bottleneck class (transfer/media only):** protocol
- **What is wasteful:** In `FlashTransferProfile.kt:84`, the `HIGH` performance profile (for modern workstations, flagship mobile, and high-speed Wi-Fi 6 / LAN) specifies:
  `maxAdaptiveChunkSizeBytes = 1024 * 1024` (1 MB base/adaptive chunk size).
  However, in `Chunker.kt:85`:
  `public const val MAX_CHUNK_SIZE_BYTES: Int = 256 * 1024` (256 KB).
  And `Chunker.clamp()` enforces:
  `requestedChunkSize.coerceIn(MIN_CHUNK_SIZE_BYTES, MAX_CHUNK_SIZE_BYTES)`.
  As a result, even when running on desktop or flagship phones connected via Gigabit LAN or 5 GHz Wi-Fi, any transfer requested with 1 MB chunks is silently clamped down to 256 KB!
  Meanwhile, `ChunkFrame.MAX_CHUNK_DATA_BYTES` already permits `1024 * 1024` (1 MB), and the server file API (`protocol.md:1670`) permits up to 1 MB chunks.
- **Cost model:**
  - 10 GB transfer at 256 KB = 40,960 chunks.
  - 10 GB transfer at 1 MB = 10,240 chunks.
  - Multiplier: 4× more frames, 4× more channel hops, 4× more ACK bookkeeping, 4× more SHA-256 invocations.
  - Measured throughput improvement from chunk size: 256 KB was **44% faster** than 64 KB in our local benchmark (48.7 MB/s vs 33.8 MB/s). On high-bandwidth desktop connections, 1 MB chunks further reduce per-chunk CPU overhead by an estimated 20%–30%.
- **User-visible effect:** Lower maximum transfer throughput on fast LAN / desktop connections than hardware can support.
- **Expected gain:** 20%–35% throughput increase on multi-gigabit LAN and desktop-to-desktop transfers.
- **Proposed change:**
  1. Increase `Chunker.MAX_CHUNK_SIZE_BYTES` to `1024 * 1024` (1 MB).
  2. Ensure `ChunkBufferPool` pools 1 MB buffers on `HIGH` performance profiles (guarded by `MemoryGovernor`).
- **Invariants touched (5.3):** 5.3.2 (Wire compatibility: `ChunkFrame.MAX_CHUNK_DATA_BYTES` is already 1 MB).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Benchmark transfer with 1 MB chunks on loopback; assert chunk plan resolves to 1 MB and throughput increases.
- **Verifier result:** Survived.
- **Related:** `EXP-001`, `PHASE-HARDENING-AIRTIME-THERMALS.md`.

---

### OPT-O07-08 — Unthrottled 100 Hz Transfer Repository StateFlow Mutations and List Copying
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C / M / J   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:572-596, 135-156` -> `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt:700`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In `MultiStreamDispatcher.kt:700`, `WATCH_POLL_MS = 10L`. The watcher coroutine publishes progress at **100 Hz (every 10 ms)** for the entire duration of the transfer.
  In `RealFlashTransferRepository.kt:572`, the repository scope collects `dispatcher.progress`:
  ```kotlin
  dispatcher.progress.collect { progress ->
      updateTransferState(transferId) { ... }
  ```
  Every 10 ms, `updateTransferState` calls:
  `updateInternalTransfers { list -> ... }` which mutates `_internalTransfers.value`, which in turn calls:
  `updateMergedTransfers()` which combines internal and external transfers and creates a new immutable `List<FlashTransfer>` assigned to `_activeTransfers.value`.
  This means 100 times per second, the transfer repository allocates a new `FlashTransfer` instance and copies the entire transfer list!
  While the UI layer applies `throttleLatest(100ms)` downstream, the repository worker coroutine wastes CPU cycles and generates heap churn at 100 Hz continuously.
- **Cost model:**
  - 100 emissions per second × process duration (e.g. 5 minutes for a large transfer = 30,000 emissions).
  - Unit cost: 1 `FlashTransfer` allocation + 2 `List` copies per emission.
  - Multiplier: 30,000 list transformations per 5-minute transfer.
- **User-visible effect:** Background CPU usage and battery drain during active transfers; minor frame drops on lower-tier Android devices.
- **Expected gain:** Reduces repository list mutations from 100 Hz to 10 Hz (90% reduction in progress collection overhead).
- **Proposed change:**
  1. Change `MultiStreamDispatcher.WATCH_POLL_MS` from `10L` to `100L` (10 Hz) for standard transfers. (The existing `quietWatcherHint` already uses `250L` during calls).
  2. 10 Hz is more than fast enough for UI progress bars (100 ms updates).
  3. Ensure terminal resolution still fires immediately upon receipt of `COMPLETE` or coverage completion rather than waiting for the watcher tick (which `ingestComplete` already does via `maybeResolveFromState`).
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Count `_activeTransfers` emissions during a 10-second transfer. Pass threshold: emission count drops from ~1,000 to ~100.
- **Verifier result:** Survived.
- **Related:** `EXP-010`, `EXP-011`, `OPT-O04-01`.

---

### OPT-O07-09 — Geometric Vector Resizing in Receiver Done-Set Reconciles via Boxed ArrayList<Int> Allocation
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M / C   **Runtime:** Android | Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1198-1206`
- **Bottleneck class (transfer/media only):** CPU / memory
- **What is wasteful:** When receiving chunks on an incoming transfer whose total chunk count is not known in advance, `receiverDone[transferId]` expands its `ResumeBitVector` geometrically:
  ```kotlin
  val bits = if (existing == null || existing.totalChunks < requiredChunks) {
      val size = maxOf(requiredChunks, minOf((existing?.totalChunks ?: 0) * 2, ReceivePipeline.MAX_TOTAL_CHUNKS))
      ResumeBitVector(size).also { grown ->
          if (existing != null) grown.reconcile(existing.doneIndexes())
          receiverDone[transferId] = grown
      }
  }
  ```
  To copy progress from `existing` to `grown`, it calls `existing.doneIndexes()`!
  As established in `OPT-O02-10`, `doneIndexes()` allocates an `ArrayList<Int>(receivedCount)` and boxes every chunk index into a `java.lang.Integer`. Then `grown.reconcile` iterates that list and does bit manipulation to re-insert them.
  Both vectors are backed by a `LongArray` of 64-bit words!
- **Cost model:**
  - Transfer with 50,000 chunks received so far.
  - Array resizing: Allocates 50,000 boxed integers (~1.6 MB heap) and iterates 50,000 times just to copy bits.
- **User-visible effect:** Transient heap spike and GC churn during receiver bit-vector expansion.
- **Expected gain:** Zero allocation and instantaneous bitset expansion via direct memory copy.
- **Proposed change:**
  Add `fun copyWordsFrom(source: ResumeBitVector)` to `ResumeBitVector` that uses `source.words.copyInto(this.words)` and sets `this.received = source.received`. Replace `grown.reconcile(existing.doneIndexes())` with `grown.copyWordsFrom(existing)`.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test asserting `grown.copyWordsFrom(existing)` produces identical `isReceived` results and bit counts with zero `doneIndexes()` calls.
- **Verifier result:** Survived.
- **Related:** `OPT-O02-10`, `EXP-009`.

---

### OPT-O07-10 — Redundant Double Encryption: Application-Level AES-256-GCM Over TLS 1.3 Transport
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C / B / T   **Runtime:** Android | Desktop
- **Component:** `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:1026-1031` -> `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/SecureBinaryFrameCodec.kt:74` -> `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt`
- **Bottleneck class (transfer/media only):** TLS | CPU
- **What is wasteful:** When transferring files over the mesh WebSocket, the connection is ALREADY encrypted with TLS 1.3 (`TLS_AES_256_GCM_SHA384` or `TLS_CHACHA20_POLY1305_SHA256`) with mutual certificate authentication and TOFU public key pinning.
  However, in `DiscoveryEngineHolder.kt:1026-1031`:
  ```kotlin
  val toSend = if (sessionKey != null) {
      SecureBinaryFrameCodec.encrypt(frameBytes, sessionKey)
  } else {
      frameBytes
  }
  return wsSession.connection.sendBinary(toSend)
  ```
  `SecureBinaryFrameCodec.encrypt` wraps the chunk in an `FSEC` frame and encrypts it with AES-256-GCM using the ECDH pairing session key.
  Then `wsSession.connection.sendBinary(toSend)` passes `toSend` to the WebSocket / TLS stack, which encrypts it a **second time** with TLS AES-GCM!
  On receive:
  The TLS stack decrypts the payload. Then `DataChannelRouter` / `onFrame` runs `SecureBinaryFrameCodec.decryptOrNull` which decrypts it a second time.
  For plain TCP data channels (`DataChannelServer`), application-level encryption (`FSEC`) is necessary because raw TCP sockets lack TLS. But over the TLS WebSocket (`wsSession.connection`), chunks are encrypted and decrypted twice!
- **Cost model:**
  - 100 MB/s transfer = 200 MB/s of AES-GCM encryption and decryption.
  - On mobile hardware (ARM Cortex-A55), AES-GCM consumes ~1.5–2.5 cycles/byte. Double encryption doubles the cryptographic CPU cycles from 200M to 400M cycles/sec.
- **User-visible effect:** Higher CPU utilization, device thermal throttling, and battery drain during transfers over TLS WebSocket connections.
- **Expected gain:** 40%–50% reduction in cryptographic CPU usage on TLS-protected streams.
- **Proposed change:**
  Only apply `SecureBinaryFrameCodec.encrypt` when transmitting over unencrypted transports (plain TCP data channels). When transmitting over authenticated TLS connections where the TLS channel is already bound to the peer's pinned identity, transmit raw `FLSH` chunk frames directly without the outer `FSEC` wrapper.
- **Invariants touched (5.3):** 5.3.1 (Security: peer authentication and channel confidentiality preserved via TLS 1.3 pinning).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Profile CPU cycles spent in crypto routines during 200 MB transfer over TLS WebSocket. Pass threshold: AES-GCM CPU time cut by >40%.
- **Verifier result:** Survived.
- **Related:** `EXP-001`, `ADR-016`.

---

## 6. Benchmark Matrix Status (AGENTS.md §23)

Per `AGENTS.md` §23 and checklist item 13, the following matrix tracks benchmark coverage across file sizes, chunk sizes, stream counts, network transports, frequency bands, hardware tiers, and storage media:

| Cell ID | File Size | Chunk Size | Streams | Transport & Band | Hardware / Platform | Storage Media | Status | Result / Citation |
|---|---|---|---|---|---|---|---|---|
| **BM-01** | 10 MB | 64 KiB | 1 (WS mux) | 2.4 GHz Hotspot | Samsung SM-G986U1 -> Infinix X6882B | eMMC 5.1 | **Recorded** | 2.0 MB/s wire throughput (`EXP-001`) |
| **BM-02** | 50 MiB | 1 MiB | 1 (HTTP) | Local Ktor Host | Windows 11 PC (OpenJDK 21) | NVMe TempDir | **Recorded** | 4–5 MB/s end-to-end (`EXP-075`) |
| **BM-03** | 200 MB | 64 KiB | 1 (Channel) | Loopback In-Memory | Windows 11 PC (OpenJDK 25, 8 CPUs) | MemorySink | **Measured (This Audit)** | 33.8 MB/s, 456 MB alloc, 5.92s wall |
| **BM-04** | 200 MB | 256 KiB | 1 (Channel) | Loopback In-Memory | Windows 11 PC (OpenJDK 25, 8 CPUs) | MemorySink | **Measured (This Audit)** | 48.7 MB/s, 1018 MB alloc, 4.11s wall (+44.1%) |
| **BM-05** | 200 MB | 64 KiB | 1 | SHA-256 Microbench | Windows 11 PC (OpenJDK 25, 8 CPUs) | Memory | **Measured (This Audit)** | Direct `MessageDigest` 270 MB/s vs Okio 228 MB/s (+19%) |
| **BM-06** | 1 GB | 64 KiB | 1 vs 2 vs 4 | 5 GHz Wi-Fi LAN | Infinix X6882B <-> Samsung SM-G986U1 | eMMC vs UFS 3.0 | **Owed Device Test** | `EXP-007` / `OPT-TRANS-01` |
| **BM-07** | 1 GB | 256 KiB | 2 vs 4 | 5 GHz Wi-Fi LAN | Samsung SM-G986U1 -> PC Desktop | UFS 3.0 -> NVMe | **Owed Device Test** | `OPT-TRANS-02` |
| **BM-08** | 4 GB | 256 KiB | 2 | Wi-Fi Direct P2P | Infinix X6882B <-> Samsung SM-G986U1 | eMMC vs UFS 3.0 | **Owed Device Test** | `OPT-TRANS-03` |
| **BM-09** | 100 MB | 16 KiB | 1 | 2.4 GHz Congested | Low-RAM phone (< 512MB ART heap) | eMMC 5.1 | **Owed Device Test** | `OPT-TRANS-04` (LOS GC churn verification) |
| **BM-10** | 10 GB | 1 MB | 4 (TCP DC) | Gigabit Ethernet | Windows 11 PC <-> Linux Desktop | NVMe SSD | **Owed Device Test** | `OPT-TRANS-05` (Saturate 1 Gbps wire) |

---

## 7. "No Problem, Because..." Notes

1. **Monotonic Resume BitVector Arithmetic:**
   *No problem.* `ResumeBitVector` uses a compact `LongArray` where each 64-bit word represents 64 chunks. Cardinality (`receivedCount`) is maintained in O(1) via atomic updates, trailing zeros are skipped via `countTrailingZeroBits()` and `w and (w - 1L)`, and set difference (`receivedIndexesNotIn`) performs bitwise `words[w] and other.words[w].inv()`. This completely satisfies the fix established in EXP-008.

2. **Send-Side Feed Queue Memory Bounds:**
   *No problem.* `MultiStreamDispatcher` explicitly bounds worker feed channels (`Channel<PreparedFrame>(feedBufferFrames)`) to 8–16 frames. The materializer suspends when queues are full, preventing large files from being serialized entirely into heap memory (conforming strictly to Invariant 5.3.4 and AGENTS.md §18).

3. **Receiver Out-of-Order Chunk Assembly:**
   *No problem.* `RandomAccessChunkSink` computes byte offset `index * chunkSize` and delegates directly to seekable `OkioRandomAccessSinkHandle.writeAt`. Chunks received out-of-order from multiple parallel streams land directly at their destination offsets without intermediate reordering buffers.

4. **Sender Rate Meter Memory & Accuracy:**
   *No problem.* `RollingRateMeter` maintains a fixed-size ring of timestamp-byte samples over a sliding 2,000 ms window. It does not allocate memory on progress updates and properly returns 0.0 MB/s during pauses rather than decaying rolling averages.

5. **Transfer Cancellation & Resource Teardown:**
   *No problem.* In `MultiStreamDispatcher.kt:267-276`, `stream?.closeQuietly()` and `feeds.forEach { it.close() }` run inside `finally` blocks, and `OkioRandomAccessSinkHandle` implements `AutoCloseable` safely releasing underlying file descriptors.

---

## 8. Summary of Findings

- **Total Findings:** 10 (3 High, 6 Medium, 1 Low)
- **Breakdown by Dimension:**
  - **T (Time / Latency):** 8 findings (`OPT-O07-01`, `OPT-O07-02`, `OPT-O07-03`, `OPT-O07-04`, `OPT-O07-05`, `OPT-O07-06`, `OPT-O07-07`, `OPT-O07-10`)
  - **S (Storage / I/O):** 3 findings (`OPT-O07-01`, `OPT-O07-02`, `OPT-O07-03`)
  - **C (CPU):** 7 findings (`OPT-O07-01`, `OPT-O07-03`, `OPT-O07-04`, `OPT-O07-05`, `OPT-O07-07`, `OPT-O07-08`, `OPT-O07-10`)
  - **M (Memory):** 3 findings (`OPT-O07-05`, `OPT-O07-08`, `OPT-O07-09`)
  - **B (Battery / Wake-ups):** 3 findings (`OPT-O07-01`, `OPT-O07-03`, `OPT-O07-10`)
  - **N (Network Bytes / Protocol):** 2 findings (`OPT-O07-06`, `OPT-O07-07`)
  - **J (Jank / Frame Time):** 2 findings (`OPT-O07-04`, `OPT-O07-08`)
- **Breakdown by Bottleneck Class:**
  - `storage`: 3 (`OPT-O07-01`, `OPT-O07-02`, `OPT-O07-03`)
  - `CPU`: 1 (`OPT-O07-08`)
  - `hashing`: 2 (`OPT-O07-01`, `OPT-O07-05`)
  - `protocol`: 3 (`OPT-O07-04`, `OPT-O07-06`, `OPT-O07-07`)
  - `TLS`: 1 (`OPT-O07-10`)
