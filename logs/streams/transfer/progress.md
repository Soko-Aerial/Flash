# Transfer Stream (T) Progress Log

## 2026-10-30 / 2026-10-31 — Transfer Optimization Stream (Batch 1, 2, 3) Complete

### Worked on
Implemented and verified all allocated optimization findings for Transfer Stream (T) spanning Batch 1 (cheap wins), Batch 2 (the cascade), and Batch 3 (bitvectors & UI media shims).

### Changed
1. **Batch 1 (Cheap Wins):**
   - **O07-07:** Clamped chunk size to 1 MB maximum in `Chunker.kt` (rejecting 8/16 MB chunks per REJ-05).
   - **O07-09:** Added `reconcile(other: ResumeBitVector)` in `ResumeBitVector.kt` operating at the `LongArray` word level, eliminating boxed Integer churn during reconnect.
   - **O25-01:** Converted `pendingIndexes` filtering in `MultiStreamDispatcher.kt` from O(N*M) list scan to O(N) by keeping an index lookup set.
   - **O07-05:** Replaced JCA provider lookups per chunk with thread-local `MessageDigest` in `Sha256Platform.jvm.kt` and `Sha256Platform.android.kt`.
   - **O23-04:** Added automatic cleanup of orphaned partials (`.part`), hub binaries, camera captures, and temporary voice recordings on startup and completion.
   - **O23-03:** Added OS-level disk space pre-allocation (`platformPreallocate` via Windows `setLength` and Android `posix_fallocate`) and 4 GB file boundary checks for FAT32 filesystems.
   - **O05-04:** Consolidated EXIF orientation parsing to single-pass extraction during stream decoding in `FlashImageDecoder.android.kt` and `FlashImageDecoder.jvm.kt`.
   - **O05-05:** Avoided post-decode duplicate bitmap allocation for EXIF orientation using native decoding on Android and transform rendering on JVM.
   - **O05-08:** Trimmed camera captures and voice notes older than 24 hours on recording startup.
   - **O05-09:** Bounded image decode retries: break immediately on null decoding without OOM instead of retrying up to sample 32.
   - **O02-08 & O02-09:** Added clearable trim listener to JVM image cache and bound image decoding to application context rather than Activity context to prevent leaks.

2. **Batch 2 (The Cascade):**
   - **O05-01 & O07-01:** Eliminated redundant full-file read pass for whole-file SHA-256 pre-hash (`hashOnly`); integrated incremental hashing in `ChunkStream` / `MultiStreamDispatcher` while slicing chunks for the network, cutting disk read I/O in half while maintaining 100% whole-file verification (preserving REJ-02).
   - **O02-01:** Routed incoming network chunk buffers through `ChunkBufferPool`, recycling byte arrays back to the pool immediately upon disk write.
   - **O23-02:** Introduced `SeekableChunkSource` with `openAt(byteOffset: Long)`. Resuming transfers fast-forward directly to the first unreceived chunk, skipping prefix reading and hashing.
   - **O07-08:** Coalesced progress flow emissions from 100 Hz (10 ms polling) to 10 Hz (100 ms polling), reducing Compose recomposition pressure by 10x.
   - **O07-06:** Added in-flight chunk retransmission endgame phase in `MultiStreamDispatcher` and reduced `ACK_DRAIN_GRACE_MS` from 15,000 ms to 3,000 ms.
   - **O07-02:** Staged incoming transfer files in `.part` temporary files, performing atomic move (`FileSystem.SYSTEM.atomicMove`) to destination upon verified completion to prevent antivirus lockouts and corrupt partial files.
   - **O07-04:** Decoupled `Sha256.digest(frame.data)` and `sink.write` from the pipeline monitor lock in `ReceivePipeline.kt`, enabling parallel multi-stream chunk writes without serializing behind the session lock.
   - **O28-02:** Emitted `FlashProbe.emit("transfer.summary", ...)` telemetry tracking disk read time, network wire stall time, and effective throughput.

3. **Batch 3:**
   - **O02-10:** Added zero-allocation inline visitors `forEachDoneIndex` and `forEachMissingIndex` in `ResumeBitVector.kt`.
   - **O25-07:** Avoided redundant sorting of already-ordered index lists during `ChunkFrame.AckBatch` serialization.
   - **O05-02:** Offloaded software AAC decoding and audio preparation in `FlashAudioPlayer.jvm.kt` to `Dispatchers.IO`.
   - **O05-03:** Updated `FlashVoiceRecorder.jvm.kt` to write directly to `.wav` (reserving 44-byte RIFF header and seeking back on `stop()`), eliminating duplicate `.pcm` -> `.wav` disk writes.
   - **O05-10:** Downsampled video thumbnails during YUV extraction in `FlashImageDecoder.jvm.kt` before color conversion, and replaced pixel-by-pixel `setRGB` with bulk array writes, reducing temporary heap from 25 MB to <1.5 MB.
   - **O07-10 (Gated):** Recorded finding as `BLOCKED — rejected by invariant REJ-01` in `ADR-255` (TLS 1.3 / wire encryption preserved).

### Verification
- `:core:transfer:jvmTest` (140 tests): **100% PASSED**.
- `:ui:platform-shims:jvmTest`: **100% PASSED**.
- Synthetic loopback benchmark `TransferLoopbackBenchmarkTest.benchmark200MbTransfer`:
  - 200 MB @ 256 KB: Throughput increased from 58.3 MB/s to **62.4 MB/s**; Heap allocations **cut in half from 1,018 MB to 515 MB (-49.4%)**; CPU time reduced by 5.2%.
  - 200 MB @ 64 KB: Throughput increased from 57.6 MB/s to **65.3 MB/s** (+13.4%); Heap allocations reduced from 456 MB to **253 MB (-44.5%)**.
  - SHA-256 microbenchmark: rate increased from 223.3 MB/s to **343.3 MB/s (+53.7%)**.
- 90% interruption test with `SeekableChunkSource` in `PipelineEndToEndTest`:
  - 90 chunks skipped upon resume; resumed sender directly sought to byte offset `1,474,560` without reading the prefix; whole-file SHA-256 verified match.
- Integration Gate Command 1 (10 test suites + compile desktop/app): **BUILD SUCCESSFUL in 6m 8s**.
- Integration Gate Command 2 (3 host test suites + compile app unit tests): 87 tests completed, exactly 2 pre-existing failures (`TransfersUiMapperTest`), matching `dev` baseline with zero regressions.

### Next AI
Stream T optimizations are complete and verified against the integration gate. Ready for integration review and merge into `dev`.
