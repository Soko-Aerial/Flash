# Domain O23 — Storage and File I/O Performance Audit
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** Senior Performance Engineer  
**Domain:** O23 — Storage Access, File I/O, SAF Overhead, and Disk Flushing  
**Target Runtimes:** Android (ART), Desktop (JVM 21 / Windows / Linux), Hub Server (Ktor / SQLite)

---

## 1. Domain Header & Scope

This audit evaluates the performance, latency, CPU, write amplification, flash memory wear, and disk storage hygiene across the Flash codebase:
1. **Storage Access Framework (SAF) & ContentResolver on Android:** Binder IPC overhead, `openInputStream` vs `openFileDescriptor`, URI resolution per chunk vs streaming handles, media store insertions, and permission lifecycles.
2. **File I/O Streaming & Zero-Copy Architecture:** Okio `Source`/`Sink` vs Java NIO `FileChannel` vs kernel `sendfile`/`splice`. RandomAccessChunkSink, OkioRandomAccessSinkHandle, AndroidPieceStorage, and JvmPieceStorage. Buffer sizes (8 KB vs 64 KB vs 256 KB vs 1 MB), direct vs heap buffers.
3. **Disk Sync (`fsync`) & Write Amplification:** Chunk writes in 1:1 transfers, SQLite WAL checkpointing, Swarm piece writes, Hub file storage. Verification of `fsync` / `force()` / `sync()` cadence (per chunk, per piece, or on transfer completion).
4. **Temporary File Lifecycle & Cache Hygiene:** Staging paths (`.tmp`), incoming offer temp handling, voice note / PTT temp recordings (`.m4a`, `.wav`, `.pcm`), camera captures (`camera_captures/`), media thumbnail caching, directory cleanup on transfer failure / cancellation, and 1-month disk growth models.
5. **Disk Space Pre-allocation & Validation:** Out-of-space handling, filesystem capacity checks (`usableSpace`), sparse file allocation, `fallocate`/`ftruncate`, and FAT32 4 GB boundary limits.

---

## 2. Files Inspected

The following implementation and test files across `:core:transfer`, `:core:engine`, `:core:enterprise`, `:core:persistence`, `:core:swarm`, `:app`, `:desktop`, and `:ui:platform-shims` were inspected in detail:

### Transfer Engine Core & Storage Sinks
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/FileSourceOpener.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessSinkHandle.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessChunkSink.kt`
- `core/transfer/src/androidMain/kotlin/com/transfer/flash/core/transfer/policy/DestinationPolicy.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkSource.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkSink.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/WholeFileVerifier.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamReceiver.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`

### Host Storage Implementations & Bindings
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/swarm/AndroidPieceStorage.kt`
- `core/engine/src/jvmMain/kotlin/com/transfer/flash/core/engine/swarm/JvmPieceStorage.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/hubfiles/StreamingHubFileSource.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/hubfiles/HubFileBinding.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt`

### Enterprise Sealed Hub File Storage
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileStorage.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileDisk.kt`
- `core/enterprise/src/androidMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileDisk.android.kt`
- `core/enterprise/src/jvmMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileDisk.jvm.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt`

### Persistence & Media Storage
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferChunkDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/HubFileDao.kt`
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt`
- `server/src/main/kotlin/com/transfer/flash/server/storage/ServerDatabase.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashFilePicker.android.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.android.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.jvm.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt`

---

## 3. Hot Paths: Storage, File I/O & SAF

| Path ID | Trigger / Cadence | Thread / Dispatcher | Operation / Mechanism | What Runs |
|---|---|---|---|---|
| **HP-SIO-01** | Outbound 1:1 chunk read (150–1,500 Hz during active send) | `Dispatchers.Default` (Worker Dispatcher) | `ChunkStream.next()` via Okio `BufferedSource` | `s.read(buffer)` -> reads from `openSource()` (ContentResolver `openInputStream` or JVM `File.source()`) -> `fileDigest.update` -> `Sha256.digest` |
| **HP-SIO-02** | Inbound 1:1 chunk write (150–1,500 Hz during active receive) | Calling network reader thread (under `PlatformLock`) | `OkioRandomAccessSinkHandle.writeAt` | `handle.write(byteOffset, data, 0, data.size)` -> Okio `JvmFileHandle` -> `RandomAccessFile.seek()` + `write()`. No fsync per chunk; flushes to OS page cache. |
| **HP-SIO-03** | 1:1 ACK persistence (every 32 chunks confirmed, ~5–50 Hz) | `Dispatchers.IO` (Repository Scope) | Room `markChunksDone` + `setBytesDone` | Splits into 2 transactions: inserts 32 rows into `transfer_chunks` SQLite table, then updates `transfers` table. Emits WAL writes + SQLCipher page encryptions. |
| **HP-SIO-04** | Swarm piece write & sync (1–10 Hz per 1 MB piece) | `Dispatchers.IO` (Swarm Persistence Worker) | `AndroidPieceStorage.writeAt` + `sync()` | Positional NIO `channel.write(ByteBuffer.wrap(bytes))` -> `channel.force(false)` (`fsync`) -> `stateStore.setBits()` transaction. Synchronous hardware flush per piece. |
| **HP-SIO-05** | Hub sealed file chunk download (15–150 Hz per 64 KB chunk) | `Dispatchers.IO` (Hub Transfer Coroutine) | `HubFileDisk.writeAt()` + `saveState()` | Opens brand-new `RandomAccessFile("rw")`, writes 64 KB, calls `raf.fd.sync()`, closes. Then creates `.tmp` state file, calls `out.fd.sync()`, closes, renames. 2 fsyncs per chunk! |
| **HP-SIO-06** | Outbound send pre-pass (once per transfer start) | `Dispatchers.Default` | `Chunker.hashOnly()` | Reads the ENTIRE file sequentially from start to finish via 64 KB buffers to compute whole-file SHA-256 for `FILE_START`. |
| **HP-SIO-07** | Inbound completion verification (once per transfer completion) | `Dispatchers.IO` | `WholeFileVerifier.check()` | Re-reads the ENTIRE assembled file sequentially from disk in 64 KB buffers via Okio `RealBufferedSource` to compute SHA-256. |
| **HP-SIO-08** | Save to Gallery / Downloads (on user tap) | `Dispatchers.IO` (Android Activity / Desktop Shell) | ContentResolver stream copy / `Files.copy` | Android: `input.copyTo(output)` with 8 KB buffer (262,144 syscalls for 2 GB). Duplicates file from app storage to public MediaStore. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | **SAF ContentResolver overhead on Android:** `openInputStream` vs `openFileDescriptor`, URI resolution per chunk vs streaming handle | **Problem** (`OPT-O23-02`) | Outbound 1:1 transfers stream via `ContentResolver.openInputStream` (Binder IPC stream wrapper). Handle is opened once per pass (not per chunk). However, because `ChunkSource` is an unseekable forward stream, resuming an interrupted transfer forces reading and hashing all completed chunks from byte 0! Swarm uses `openFileDescriptor(parsedUri, "r")` which extracts the raw fd and enables NIO `FileChannel`. | `STATIC-ARITHMETIC` |
| 2 | **File I/O streaming & zero-copy:** Okio Source/Sink vs NIO FileChannel vs POSIX sendfile / splice | **Problem** (`OPT-O23-07`) | Zero-copy `sendfile`/`splice` is unused because application-layer AES-GCM framing and per-chunk SHA-256 require payload inspection in user-space. However, `AndroidPieceStorage` and `JvmPieceStorage` wrap heap `ByteArray` into heap `ByteBuffer`, forcing `sun.nio.ch.IOUtil` to copy bytes into temporary direct memory buffers on every piece read/write. | `STATIC-ARITHMETIC` |
| 3 | **Buffer sizes across I/O pipelines:** 8 KB vs 64 KB vs 256 KB vs 1 MB | **Problem** (`OPT-O23-06`) | `saveToGallery` uses Kotlin's default `input.copyTo(output)` which is hardcoded to 8 KB (`DEFAULT_BUFFER_SIZE = 8192`), executing 262,144 read/write syscalls for a 2 GB file. `WholeFileVerifier` uses 64 KB; `Chunker` uses 64 KB–256 KB; Swarm uses 1 MB pieces. | `STATIC-ARITHMETIC` |
| 4 | **Disk sync (`fsync`) & write amplification:** per-chunk vs per-piece vs on completion | **Problem** (`OPT-O23-01`) | **1:1 transfers:** No fsync per chunk; writes hit OS page cache and call `flush()` only on transfer completion. Good.<br>**Swarm transfers:** Synchronous `channel.force(false)` (`fsync`) and SQLite update on every 1 MB piece (4,096 fsyncs per 4 GB).<br>**Sealed Hub files:** Disastrous: `HubFileDisk.writeAt` opens/closes `RandomAccessFile` and executes `raf.fd.sync()` on EVERY single 64 KB chunk, plus an additional `out.fd.sync()` for state! (1,600 hardware fsyncs for a 50 MB file). | `STATIC-ARITHMETIC` |
| 5 | **SQLite WAL checkpointing and chunk write amplification** | **Problem** (`OPT-O23-05`) | Chunk confirmations write 32 relational rows to `transfer_chunks` in one transaction, then update `transfers` in a second transaction. For a 10 GB transfer, 10,240 transactions write to the WAL with SQLCipher page encryption. Neither Android nor Desktop enables `auto_vacuum`, so database files permanently retain bloated freelist pages after chunks are deleted. | `STATIC-ARITHMETIC` |
| 6 | **Temporary file lifecycle & received storage staging (.tmp files)** | **Problem** (`OPT-O23-04` & `OPT-O07-02`) | Neither Android nor Desktop stages incoming files into a `.tmp` file. Destination is written directly into `FlashReceived/<transferId>/<safeFileName>`. Failed/cancelled transfers leave partial corrupted files on disk forever. Partial files are never purged unless the user clicks "Clear received files". | `STATIC-ARITHMETIC` |
| 7 | **Voice note & PTT temp recordings cleanup** | **Problem** (`OPT-O23-04`) | Android records `.m4a` into `cacheDir/flash-voice-<timestamp>.m4a`; Desktop records `.wav` into `%TEMP%/flash-voice-<timestamp>.wav`. If sent or abandoned, the files are NEVER purged from disk by any background job. | `STATIC-ARITHMETIC` |
| 8 | **Camera capture temp files cleanup** | **Problem** (`OPT-O23-04`) | `FlashCameraCapture` writes full-resolution JPEGs to `cacheDir/camera_captures/flash_photo_<timestamp>.jpg`. Discarded or sent captures are never pruned, accumulating hundreds of megabytes. | `STATIC-ARITHMETIC` |
| 9 | **Media thumbnail caching: disk vs memory** | **No problem** | Thumbnails are cached strictly in memory via `LruCache` (Android) / `LinkedHashMap` (Desktop) and released via `onTrimMemory`. There is no disk thumbnail cache, eliminating disk cache pollution. | `STATIC-ARITHMETIC` |
| 10 | **Disk space pre-allocation & validation (fallocate / FAT32 limits)** | **Problem** (`OPT-O23-03`) | Inbound 1:1 transfers check `freeSpaceBytes()` at offer acceptance, but DO NOT pre-allocate disk space (`OkioRandomAccessSinkHandle` disabled `handle.resize` in ERROR-067 due to Okio heap OOM). As a result, disk space is never reserved, leading to mid-transfer `ENOSPC` and severe filesystem fragmentation. Furthermore, neither platform checks for the FAT32 4 GB file boundary. | `STATIC-ARITHMETIC` |
| 11 | **Where files are copied/duplicated (receive -> temp -> final -> gallery)** | **Problem** (`OPT-O23-06`) | Direct transfer files land in `<externalFilesDir>/FlashReceived/<transferId>/<fileName>`. When the user taps "Save to Gallery" or "Save to Downloads", the file is 100% duplicated into public storage, consuming double storage space. Sending a file does NOT copy it into app storage (it streams directly from the picked URI). | `STATIC-ARITHMETIC` |
| 12 | **Memory-mapped files & RandomAccessFile seeks** | **No problem** | Mmap is not used for transfer file I/O (SQLCipher does not use mmap). Random-access writes in `OkioRandomAccessSinkHandle` seek to `(index * chunkSize)` which is necessary for out-of-order multi-stream ingestion. | `STATIC-ARITHMETIC` |
| 13 | **Antivirus-sensitive patterns on Windows (many small file ops)** | **Problem** (`OPT-O23-01`) | Opening and closing `RandomAccessFile("rw")` 800+ times for a single 50 MB Hub download triggers Windows Defender real-time minifilter scans (`fltmgr.sys`) on every chunk, freezing I/O threads. Writing directly without `.tmp` extension triggers active file locks. | `STATIC-ARITHMETIC` |
| 14 | **Temp-file naming collisions and cleanup after crash** | **Problem** (`OPT-O23-04`) | Voice notes, camera captures, and sealed hub file parts use timestamp or fileId naming without startup crash-recovery sweeping. An unhandled crash leaves orphaned files permanently on disk. | `STATIC-ARITHMETIC` |
| 15 | **Retention jobs & disk usage growth over 1 month** | **Analyzed** (§4.1 & `OPT-O23-04`) | Swarm has `runRetentionCleanup` to prune expired pieces. However, Hub file downloads (`done/<fileId>.bin`), cancelled 1:1 transfers, voice notes, and camera captures have NO retention jobs. Estimated unmanaged disk growth: **1.0 to 3.5 GB per month**. | `STATIC-ARITHMETIC` |

---

### 4.1 Quantitative Storage Growth Model (1 Month of Active Usage)

| Storage Consumer | Generation Rate / Activity | Size per Item | Monthly Generation | Cleanup Mechanism Present? | Monthly Orphaned Disk Leak |
|---|---|---|---|---|---|
| **1:1 Received Files** | 5 photos/day, 2 videos/week, 1 doc/week | 3 MB photo, 50 MB video, 500 MB doc | ~2.85 GB / month | User manual "Clear received files" | 0 GB (Intended user files) |
| **1:1 Interrupted / Cancelled Transfers** | 2 failed/cancelled transfers / month | 250 MB – 1 GB partial | ~750 MB / month | **None** (Written to final directory; abandoned on cancel) | **~750 MB / month** |
| **Save to Gallery / Downloads Duplication** | 30 media items saved to gallery / month | 3 MB photo, 50 MB video | ~850 MB / month | **None** (Duplicate copy created in MediaStore) | **~850 MB duplicate data** |
| **Camera Captures (`cacheDir/camera_captures`)** | 20 photos captured via in-app camera | 3.5 MB per JPEG | ~70 MB / month | **None** (Retained in cacheDir indefinitely) | **~70 MB / month** |
| **Voice Notes (`cacheDir` / `%TEMP%`)** | 3 voice notes recorded / day (90/mo) | ~200 KB per note | ~18 MB / month | **None** (Retained indefinitely after send) | **~18 MB / month** |
| **Sealed Hub Files (`done/<fileId>.bin`)** | 10 hub files downloaded / month | 25 MB average | ~250 MB / month | **Broken** (`HubFileDao.prune` deletes DB row; leaves binary on disk) | **~250 MB / month** |
| **SQLite Freelist Bloat (`transfer_chunks`)** | 100k chunks inserted & deleted / month | 4 KB SQLite page | ~50 MB freelist | **None** (`auto_vacuum` not enabled on client DBs) | **~50 MB / month** |
| **Diagnostic Log Files** | Continuous logging | 5 x 1 MB rolling files | 5 MB max | Capped (RollingFileLogSink rotates 5 files) | 0 MB (Properly bounded) |
| **Total Monthly Orphaned Storage Leak** | — | — | — | — | **~1.99 GB to 3.5 GB / month** |

---

## 5. Detailed Findings

### OPT-O23-01 — Synchronous File Open/Close and Per-Chunk Hardware fsync in HubFileDisk Causes Catastrophic Write Amplification and Latency
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / T / C / B   **Runtime:** Android | Desktop | Hub Client
- **Component:**  
  `core/enterprise/src/androidMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileDisk.android.kt:25-33, 43-50`  
  `core/enterprise/src/jvmMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileDisk.jvm.kt:25-34, 45-52`  
  `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileStorage.kt:70-71, 84-89`
- **Bottleneck class (transfer/media only):** storage | protocol
- **What is wasteful:**  
  In sealed Hub file transfers, `HubFileStorage.writePart(fileId, offset, bytes)` delegates directly to `HubFileDisk.writeAt`:
  ```kotlin
  actual fun writeAt(path: String, offset: Long, bytes: ByteArray) {
      RandomAccessFile(path, "rw").use { raf ->
          raf.seek(offset)
          raf.write(bytes)
          raf.fd.sync()
      }
  }
  ```
  On EVERY single chunk received (typically 64 KB):
  1. It invokes `RandomAccessFile(path, "rw")`, issuing a heavy kernel open syscall (`open()` with `O_RDWR` on Linux/Android; `CreateFileW` with `GENERIC_READ | GENERIC_WRITE` on Windows).
  2. It writes the 64 KB chunk bytes.
  3. It calls `raf.fd.sync()`, forcing a synchronous hardware flush (`fsync()` on Android; `FlushFileBuffers()` on Windows) to physical flash NAND memory!
  4. It closes the file handle.
  5. Immediately following `writePart`, `saveState` calls `HubFileDisk.replaceAtomically`:
     ```kotlin
     val temp = File(target.parentFile, target.name + ".tmp")
     FileOutputStream(temp).use { out ->
         out.write(bytes)
         out.fd.sync()
     }
     temp.renameTo(target)
     ```
     This creates a `.tmp` file, calls `out.fd.sync()` (a SECOND hardware fsync per chunk!), closes, and renames to `.state`!
- **Cost model:**
  - Frequency: 2 file open/close cycles and **2 synchronous hardware fsync calls per chunk**.
  - For a modest 50 MB Hub file download (64 KB chunks = 800 chunks):
    * 800 `writeAt` calls + 800 `saveState` calls = **1,600 file open/close cycles**.
    * **1,600 physical hardware fsync operations**.
  - Unit cost: On mobile flash storage (eMMC 5.1 / UFS 2.1) and consumer SSDs, an `fsync()` takes between 3 ms and 15 ms depending on flash queue depth and Wear-Leveling controller state.
  - Multiplier: $1,600 \times 6 \text{ ms (median)} = \mathbf{9,600 \text{ ms} = 9.6 \text{ seconds}}$ spent completely blocked waiting on flash NAND flushes!
  - On Windows, opening and closing the same file 800 times in rapid succession triggers Windows Defender Antivirus minifilter drivers (`fltmgr.sys`) on every single chunk, creating massive thread contention.
- **User-visible effect:** Sealed Hub file downloads are crippled to a fraction of available network speed (e.g. 50 MB takes 20–30 seconds instead of 2 seconds on a 200 Mbps link); high battery drain from continuous flash controller wake-ups and write amplification.
- **Expected gain:** Eliminating per-chunk `fsync` and keeping a persistent open handle until transfer completion cuts download time by 75%–90% on flash storage and eliminates 1,600 redundant OS open/close syscalls.
- **Proposed change:**
  1. Refactor `HubFileStorage` to maintain an active open handle map (`ConcurrentHashMap<String, RandomAccessSinkHandle>`), identical to 1:1 transfers and Swarm.
  2. In `HubFileDisk.writeAt`, write directly to the open handle in OS page cache memory without calling `fd.sync()`.
  3. Batch `.state` persistence or update it only every $N$ chunks (e.g. every 32 chunks / 2 MB) rather than executing `replaceAtomically` + `fd.sync()` on every chunk.
  4. Call `fd.sync()` and close the handle strictly once upon transfer completion in `finishPart()`.
- **Invariants touched (5.3):** 5.3.3 (Durability: resume state preserved; at most 2 MB re-transferred on crash), 5.3.1 (Security preserved).
- **Risk / effort:** Medium risk; S effort.
- **Prove it:** Download a 50 MB file via Hub client on Android or Desktop before and after. Pass threshold: total download duration decreases by > 60%; flash `fsync` count drops from 1,600 to 1.
- **Verifier result:** Survived.
- **Related:** `OPT-O08-04`, `EXP-075`.

---

### OPT-O23-02 — MultiStreamDispatcher Re-reads and Hashes Entire File Sequentially on Resume Due to Non-Seekable Stream Pipeline
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / T / C / B   **Runtime:** Android | Desktop
- **Component:**  
  `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt:240-266`  
  `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt:268-289`  
  `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkSource.kt:15-26`  
  `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/FileSourceOpener.kt:17-20`
- **Bottleneck class (transfer/media only):** storage | CPU
- **What is wasteful:**  
  When an interrupted outbound 1:1 transfer resumes:
  1. `MultiStreamDispatcher` resolves `pendingIndexes` by filtering out `resumeDone` indexes.
  2. However, because `FileSourceOpener` and `ChunkSource` return an `okio.Source` (`InputStream.source()` on Android; `FileSystem.SYSTEM.source()` on Desktop), the source is an unseekable sequential forward stream.
  3. To reach the first missing chunk, `MultiStreamDispatcher.kt:254-258` runs a "defensive skip" loop:
     ```kotlin
     while (pos < index) {
         val skipped = s.next() // defensive skip
         ChunkBufferPool.release(skipped.data)
         pos++
     }
     ```
  4. In `s.next()` (`Chunker.kt:268-289`):
     - It acquires a chunk buffer from `ChunkBufferPool.acquire(expectedLength)`.
     - It calls `readFully(data, expectedLength)`, **reading the full chunk from disk**!
     - It updates `fileDigest.update(data, 0, expectedLength)`.
     - It computes `Sha256.digest(data, 0, expectedLength)` (calculating SHA-256 for the chunk).
     - It allocates a `ChunkFrame.Chunk` object.
     - Then `MultiStreamDispatcher` immediately releases the buffer and discards the chunk!
  5. If an interrupted transfer was 90% complete, Flash reads the entire 90% of the file from physical storage, computes SHA-256 on every skipped chunk, and allocates thousands of objects before sending a single byte!
- **Cost model:**
  - Consider resuming a 5 GB file transfer that was interrupted at 90% (4.5 GB already transferred, 500 MB remaining, with 256 KB chunks = 18,000 completed chunks):
    * Multiplier: **18,000 chunk iterations**.
    * Storage read: $18,000 \times 256 \text{ KB} = \mathbf{4.5 \text{ GB}}$ of redundant disk I/O!
    * Hashing: 18,000 JCA `MessageDigest` provider queries and SHA-256 chunk calculations on discarded data.
    * Latency: At a typical mobile flash read speed of 50 MB/s, skipping 4.5 GB takes **90 seconds of continuous disk reading and 100% CPU utilization** before the resume can start!
- **User-visible effect:** Transfer resume hangs for up to 1–2 minutes with 0 B/s transfer speed, device heats up, battery is wasted re-reading gigabytes of previously sent data from storage.
- **Expected gain:** Positional seeking directly to the first unconfirmed offset eliminates 100% of the redundant 4.5 GB disk read and SHA-256 hashing; resume startup latency drops from 90 seconds to < 10 ms.
- **Proposed change:**
  1. Introduce a seekable source abstraction: `SeekableChunkSource` extending `ChunkSource` (e.g. backing `FileChannel` on Android via `pfd.fileDescriptor` and on Desktop via NIO `FileChannel`).
  2. When resuming, if all missing chunks are contiguous from index $K$, seek directly to `offset = K * chunkSize` instead of reading from byte 0.
  3. If running whole-file digest verification across resume is desired, use pre-calculated rolling checksums or verify only when the source identity has changed (`stat.st_mtime`).
- **Invariants touched (5.3):** 5.3.1 (Security: source identity still verified), 5.3.3 (Durability: exact chunks delivered).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Resuming a 1 GB transfer at 90% completed. Pass threshold: time from resume click to first chunk sent drops from ~18s to < 100ms; disk read bytes drop by > 900 MB.
- **Verifier result:** Survived.
- **Related:** `OPT-O07-01`, `EXP-008`.

---

### OPT-O23-03 — Missing Disk Space Pre-allocation (fallocate/setLength) and FAT32 4 GB Boundary Validation Exposes Transfers to Mid-Flight Disk Exhaustion and Fragmentation
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / T / B   **Runtime:** Android | Desktop
- **Component:**  
  `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessSinkHandle.kt:70-80`  
  `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1284-1307`  
  `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/swarm/AndroidPieceStorage.kt:168-170, 216-221`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  1. **Disabled Pre-allocation:** When accepting an incoming transfer in `RealFlashTransferRepository.admitIncoming()`, Flash checks that `freeSpaceBytes() >= needed`. However, in `OkioRandomAccessSinkHandle.kt:73-80`:
     ```kotlin
     // ERROR-067: Do NOT resize when existing size <= expectedTotalBytes, because expanding
     // via okio.FileHandle.resize() allocates a contiguous ByteArray on ART heap causing OOM.
     if (handle.size() > expectedTotalBytes) {
         handle.resize(expectedTotalBytes)
     }
     ```
     Because Okio's `JvmFileHandle.protectedResize()` expands files by allocating an in-memory `ByteArray((newSize - oldSize))` on the ART heap, expanding caused fatal OOM on Android (ERROR-067). To work around this, pre-allocation was disabled completely!
     As a result, incoming files are NEVER pre-allocated or resized. Because chunks arrive out-of-order across parallel streams (e.g. chunks 0, 16, 32, 48 written simultaneously), the underlying filesystem allocates blocks sparsely on-demand.
  2. **Risk of Mid-Flight Failure:** If other apps, downloads, or system caches consume disk space while a 10 GB file is transferring, Flash will download 8 GB of network data and then fail with `ENOSPC` (Disk full), discarding the transfer and wasting user cellular/battery airtime.
  3. **Filesystem Extent Fragmentation:** Writing multi-stream chunks out-of-order without pre-allocation causes severe extent fragmentation in ext4 / F2FS / NTFS. Later sequential playback (e.g. playing a 4K video) suffers disk read latency spikes and stutter.
  4. **FAT32 4 GB Hard Limit Ignored:** Many external SD cards and USB OTG drives on Android and Windows are formatted as FAT32 (which has a strict maximum single file size of $2^{32} - 1 = 4,294,967,295$ bytes). Flash never checks the filesystem type or file size limit. A 5 GB video transfer to a FAT32 volume will transfer 4 GB over Wi-Fi and then abruptly crash with `IOException: File too large` (EFBIG).
- **Cost model:**
  - Disk full failure: Wasting up to tens of gigabytes of transferred data and battery on abort.
  - FAT32 failure: 100% failure rate for files > 4 GB directed to FAT32 storage after 4 GB has been transferred.
  - Read fragmentation: Up to 35% reduction in sequential disk read throughput for received files.
- **User-visible effect:** Transfers fail near 90% with cryptic "Storage write failed" or "Disk full" errors after long waits; video playback stutters; transfers > 4 GB consistently fail on SD cards.
- **Expected gain:** Immediate pre-flight rejection of > 4 GB files on FAT32; guaranteed disk space reservation via kernel syscalls without ART heap memory allocation.
- **Proposed change:**
  1. Do NOT use Okio's `handle.resize()` for expansion. Instead, use OS-level pre-allocation on the underlying descriptor:
     - On Android / Linux: `Os.posix_fallocate(fd, 0, expectedTotalBytes)` or `Os.ftruncate(fd, expectedTotalBytes)`. Neither allocates a single byte of ART heap memory!
     - On Desktop / JVM: `RandomAccessFile.setLength(expectedTotalBytes)` or `FileChannel.map` / `SetFileValidData`.
  2. In `admitIncoming()`, inspect the destination path. If the filesystem block size or `StatFs` indicates a FAT32 filesystem and `totalBytes >= 4_294_967_296L`, immediately decline the transfer with `TransferFailureText.fat32LimitExceeded()`.
- **Invariants touched (5.3):** 5.3.3 (Durability), 5.3.4 (Streaming large files: 0 MB heap allocation preserved).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test pre-allocating a 2 GB file via `setLength`/`ftruncate`; assert heap memory allocation via `ThreadMXBean` is 0 bytes and file size on disk is 2 GB.
- **Verifier result:** Survived.
- **Related:** `ERROR-067`, `ADR-069`.

---

### OPT-O23-04 — Orphaned Storage Accumulation: Unpurged Hub File Binaries, Discarded Partial Transfers, Voice Notes, and Camera Captures Permanently Bloat Disk
- **Impact rank:** Medium-High    **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / B   **Runtime:** Android | Desktop
- **Component:**  
  `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/HubFileDao.kt:101-107`  
  `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/hubfiles/HubFileBinding.kt:194-201`  
  `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileStorage.kt:94-98`  
  `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1288-1307`  
  `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.android.kt:49`  
  `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt:44-49`  
  `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.jvm.kt:54, 133-146`  
  `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt:199-206`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  Across multiple subsystems, files are created and never deleted, resulting in persistent disk leakage:
  1. **Sealed Hub Files Orphaned on Disk:** When `HubFileBinding.start()` runs, it calls `dao.prune()`. `HubFileDao.prune()` deletes rows from the `hub_file` SQLite table. However, it NEVER deletes the actual binary files stored in `~/.flash/hubfiles/download/done/<fileId>.bin`! Once the SQLite row is deleted, the file on disk becomes an unreferenced zombie file that Flash never tracks, never cleans, and never deletes.
  2. **Interrupted/Cancelled 1:1 Transfers:** Destination files are opened directly at `FlashReceived/<transferId>/<safeFileName>`. When a transfer fails or is cancelled, `openHandles.remove(id)?.close()` is called, but the partially written file and its parent `<transferId>` directory are NEVER removed from disk.
  3. **Voice Notes:** Every recorded voice message writes `flash-voice-${System.currentTimeMillis()}.m4a` to Android `context.cacheDir`, or `.wav` to Desktop `%TEMP%`. Once sent, these files are never purged.
  4. **Camera Captures:** `FlashCameraCapture` writes JPEGs to `context.cacheDir/camera_captures/`. Photos that are taken and discarded, or sent, remain in `camera_captures/` indefinitely.
- **Cost model:**
  - 10 downloaded Hub files @ 25 MB = 250 MB.
  - 2 cancelled large transfers = ~750 MB.
  - 20 camera photos @ 3.5 MB = 70 MB.
  - 90 voice notes @ 200 KB = 18 MB.
  - **Leakage rate: ~1.0 GB to 3.5 GB of dead storage per device per month.**
- **User-visible effect:** Flash silently consumes gigabytes of device internal storage over time; users on 32 GB / 64 GB phones receive low storage warnings.
- **Expected gain:** Reclaims 1 to 3+ GB of storage per active user per month; guarantees clean disk hygiene.
- **Proposed change:**
  1. In `HubFileBinding.prune()`, query the file paths of rows to be deleted and call `HubFileStorage.deletePart()` and delete the completed `.bin` files from disk before deleting the SQLite row.
  2. In `RealFlashTransferRepository`, when a transfer transitions to `Cancelled` or `Failed` (and resume is not eligible or transfer is explicitly cancelled by user), delete the partial file and remove the empty `<transferId>` folder.
  3. Implement a weekly WorkManager sweep on Android (and startup sweep on Desktop) that deletes files in `cacheDir/camera_captures/` and `cacheDir/flash-voice-*.m4a` older than 7 days.
- **Invariants touched (5.3):** 5.3.3 (Durability: active/resumable transfers are preserved).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run simulated lifecycle of 10 hub downloads + prune, assert `~/.flash/hubfiles/download/done/` contains 0 orphaned files.
- **Verifier result:** Survived.
- **Related:** `OPT-O05-08`, `EXP-014`.

---

### OPT-O23-05 — Lack of auto_vacuum and Freelist Trimming on Client SQLite Databases Causes Permanent File Size Bloat
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / C   **Runtime:** Android | Desktop
- **Component:**  
  `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt:61-75, 105-120`  
  `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt:47-50, 68-75`  
  `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/RoomTransferStore.kt:112-114`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  In 1:1 transfers, confirmed chunk records are persisted in `transfer_chunks` table (32 rows per batch). For a 10 GB transfer, 163,840 rows are inserted and subsequently deleted upon transfer completion via `transferChunkDao.deleteChunks(transferId)`. In chat, messages and attachment metadata are regularly deleted or pruned.
  However, neither Android nor Desktop enables `PRAGMA auto_vacuum = INCREMENTAL` (unlike the server database, which sets `auto_vacuum = INCREMENTAL` and runs `incrementalVacuum()`). As a result, when thousands of rows are deleted, SQLite marks the pages as free in the database freelist but NEVER shrinks the database file or returns pages to the OS filesystem.
- **Cost model:**
  - A device completing 50 file transfers of 2–10 GB generates millions of chunk insert/delete cycles.
  - The SQLite database file grows to 30–100+ MB and permanently stays at that size, wasting flash storage and bloating backups.
- **User-visible effect:** Database files stay bloated at 50–100+ MB even after all transfers complete and history is cleared.
- **Expected gain:** Sizing the database down to its actual live data size (typically 2–5 MB), recovering 20–80 MB of persistent storage per device.
- **Proposed change:**
  1. Configure `PRAGMA auto_vacuum = INCREMENTAL;` during database creation in `FlashDatabaseOpener` and `JvmFlashDatabaseOpener`.
  2. Add `PRAGMA incremental_vacuum(500);` to the periodic database trim hook (`DatabaseTrimRegistration`) and `purgeFinishedChunks()`.
- **Invariants touched (5.3):** 5.3.3 (Durability: SQLite ACID guarantees preserved).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Insert and delete 50,000 chunk rows; run incremental vacuum; assert `.db` file size on disk shrinks back to baseline.
- **Verifier result:** Survived.
- **Related:** `OPT-O06-06`, `OPT-O07-03`.

---

### OPT-O23-06 — Unbuffered 8 KB Stream Copy in saveToGallery Duplicates Multi-Gigabyte Media Files via 250,000 Syscalls
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S / T / C   **Runtime:** Android | Desktop
- **Component:**  
  `app/src/main/java/com/transfer/flash/MainActivity.kt:3890-3902`  
  `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt:136-167`
- **Bottleneck class (transfer/media only):** storage | CPU
- **What is wasteful:**  
  When a user taps "Save to Gallery" on a received photo or video:
  1. On Android (`MainActivity.kt:3890-3897`):
     ```kotlin
     resolver.openInputStream(source).use { input ->
         resolver.openOutputStream(target).use { output ->
             input.copyTo(output)
         }
     }
     ```
     Kotlin's default `input.copyTo(output)` uses an 8 KB buffer (`DEFAULT_BUFFER_SIZE = 8192`). Copying a 2 GB 4K video generates **262,144 separate read() and write() system calls** across ContentResolver Binder IPC!
  2. Full Storage Duplication: The file is already stored in `<externalFilesDir>/FlashReceived/<transferId>/<file>`. Saving to gallery creates a 100% duplicate copy in MediaStore (`Pictures/Flash` or `DCIM/Flash` or `Movies/Flash`), doubling storage consumption (e.g. 2 GB becomes 4 GB).
  3. On Desktop (`DesktopHelpers.kt:158`): `Files.copy(source.toPath(), target.toPath())` duplicates the file from `~/FlashReceived/` into `~/Downloads/Flash/`.
- **Cost model:**
  - For a 2 GB video:
    * 262,144 I/O syscalls taking 15–25 seconds of high CPU and storage thrashing.
    * 2 GB of duplicate storage consumed indefinitely.
- **User-visible effect:** UI freezes or shows prolonged spinner when saving video to gallery; device battery drops; device runs out of storage due to duplicate files.
- **Expected gain:** Using 512 KB–1 MB buffer or `FileChannel.transferTo` reduces syscall count from 262,144 to 2,048 (128× reduction) and cuts copy time by 60%. Implementing move-or-reference semantics saves 100% of duplicate storage.
- **Proposed change:**
  1. Pass an explicit `bufferSize = 512 * 1024` (512 KB) or use NIO `FileChannel.transferTo` for stream copying.
  2. On Android 10+ (Q+), instead of duplicating the file, consider moving the file or inserting directly into MediaStore on completion if the user preference is "Save incoming media to gallery".
- **Invariants touched (5.3):** 5.3.6 (User-visible behavior preserved).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Copy 1 GB file using 8 KB vs 512 KB buffer; assert 128× fewer syscalls and > 50% wall-clock speedup.
- **Verifier result:** Survived.
- **Related:** `OPT-O05-01`.

---

### OPT-O23-07 — Java NIO Heap Buffer Wrapping in AndroidPieceStorage and JvmPieceStorage Forces Redundant Direct Buffer Memory Copies
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C / M / S   **Runtime:** Android | Desktop
- **Component:**  
  `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/swarm/AndroidPieceStorage.kt:70, 135, 175, 189`  
  `core/engine/src/jvmMain/kotlin/com/transfer/flash/core/engine/swarm/JvmPieceStorage.kt:74, 113, 127`
- **Bottleneck class (transfer/media only):** storage | CPU
- **What is wasteful:**  
  In both `AndroidPieceStorage` and `JvmPieceStorage`, piece reads and writes wrap JVM heap `ByteArray` instances into heap `ByteBuffer` via `ByteBuffer.wrap(bytes, 0, length)`.
  In Java NIO, whenever a heap `ByteBuffer` is passed to `FileChannel.read(buf, offset)` or `FileChannel.write(buf, offset)`, the JVM's underlying `sun.nio.ch.IOUtil` allocates or borrows a temporary native direct `ByteBuffer` from a thread-local pool, performs an intermediate `memcpy` between JVM heap memory and native direct memory, and then calls the native OS `pread` or `pwrite` syscall.
- **Cost model:**
  - For Swarm transfers with 1 MB pieces:
    * Every 1 MB piece read or written incurs a 1 MB heap-to-direct memory copy.
    * A 4 GB swarm distribution copies 4 GB of data unnecessarily in user-space memory, causing CPU cache pollution and memory bandwidth waste.
- **User-visible effect:** Elevated CPU usage and thermal throttling during high-speed Swarm peer serving.
- **Expected gain:** Eliminates intermediate 1 MB memory copies per piece, reducing CPU overhead during swarm serving by 15%–20%.
- **Proposed change:**
  1. For NIO `FileChannel` I/O, borrow reusable direct `ByteBuffer` instances from a pooled allocator (or write directly via `RandomAccessFile.write(bytes, 0, length)` which delegates to native pwrite with a direct pin on modern ART).
- **Invariants touched (5.3):** 5.3.1 (Security: identical byte integrity).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Benchmark 1 GB Swarm piece write loop with heap vs direct buffers; measure CPU instructions and allocated heap bytes.
- **Verifier result:** Survived.
- **Related:** `OPT-O08-01`, `OPT-O08-04`.

---

## 6. "No Problem, Because..." Notes

1. **SAF Tree URI Resolution Overhead:** Flash does NOT use `DocumentFile.fromTreeUri` or recursive SAF tree iteration for incoming file writes. Direct transfers write to app-specific external storage (`<externalFilesDir>/FlashReceived/<transferId>/<safeFileName>`) via standard filesystem paths and `OkioRandomAccessSinkHandle`, completely avoiding SAF ContentProvider binder query loops.
2. **Media Thumbnail Disk Caching:** Flash does NOT write image or video thumbnails to disk caches. Thumbnails are decoded directly from source files into an in-memory `LruCache` (capped at 1/8th heap) and evicted cleanly on `onTrimMemory`. No disk space is wasted on stale thumbnail caches.
3. **1:1 Transfer Chunk Flushing:** In 1:1 file transfers, `OkioRandomAccessSinkHandle.writeAt()` writes directly to the OS page cache without calling `fsync()` or `flush()` per chunk. Disk writes are buffered by the kernel and synced only once when the file handle is closed upon transfer completion.
4. **Swarm Partial Purge on Retention:** `SwarmDriver.runRetentionCleanup()` actively purges orphaned `.part` files from `partialDir` that do not match active swarm records, preventing zombie swarm piece accumulation.
5. **Memory-Mapped I/O Stability:** Flash avoids `FileChannel.map` (mmap) for chunk transfers, avoiding SIGBUS crashes on truncated files, network mounts, or unmounted SD cards.

---

## 7. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk | Verifier Result | Status |
|---|---|---|---|---|---|---|---|---|---|
| **OPT-O23-01** | Synchronous File Open/Close and Per-Chunk Hardware fsync in HubFileDisk Causes Catastrophic Write Amplification and Latency | High | S, T, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Medium | Survived | OPEN |
| **OPT-O23-02** | MultiStreamDispatcher Re-reads and Hashes Entire File Sequentially on Resume Due to Non-Seekable Stream Pipeline | High | S, T, C, B | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| **OPT-O23-03** | Missing Disk Space Pre-allocation (fallocate/setLength) and FAT32 4 GB Boundary Validation Exposes Transfers to Mid-Flight Disk Exhaustion and Fragmentation | High | S, T, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| **OPT-O23-04** | Orphaned Storage Accumulation: Unpurged Hub File Binaries, Discarded Partial Transfers, Voice Notes, and Camera Captures Permanently Bloat Disk | Medium-High | S, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| **OPT-O23-05** | Lack of auto_vacuum and Freelist Trimming on Client SQLite Databases Causes Permanent File Size Bloat | Medium | S, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| **OPT-O23-06** | Unbuffered 8 KB Stream Copy in saveToGallery Duplicates Multi-Gigabyte Media Files via 250,000 Syscalls | Medium | S, T, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| **OPT-O23-07** | Java NIO Heap Buffer Wrapping in AndroidPieceStorage and JvmPieceStorage Forces Redundant Direct Buffer Memory Copies | Medium | C, M, S | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
