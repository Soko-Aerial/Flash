# Transfer Stream (T) Experiments Log

Reserved range: EXP-090 to EXP-094.

## EXP-090 — Transfer Loopback Pipeline Baseline (Before Optimization)

### Date
2026-10-30

### Environment
- OS: Windows 11 (10.0 amd64)
- CPU: 8 logical processors
- JVM: OpenJDK 25.0.3 (Launcher 21.0.10)
- Commit: `6f8fe180` (`dev` baseline)

### Method
Deterministic synthetic stream benchmark `TransferLoopbackBenchmarkTest.benchmark200MbTransfer` (200 MB transfer through SendPipeline -> in-memory channel -> ReceivePipeline with SHA-256 validation).

### Baseline Measurements (Before)
| Metric | 200MB @ 64 KB Chunks | 200MB @ 256 KB Chunks |
|---|---|---|
| Throughput | 57.6 MB/s | 58.3 MB/s |
| Wall Time | 3,472.8 ms | 3,431.5 ms |
| CPU Time | 3,328.1 ms | 3,281.3 ms |
| Thread Heap Alloc | **456 MB** | **1,018 MB** (5.09x amplification) |

### SHA-256 Microbenchmark (200 MB, 3,200 x 64 KB blocks)
- Baseline `Sha256.digest` (Okio HashingSink + JCA lookup): 895.6 ms, Alloc: 1.50 MB, Rate: 223.3 MB/s
- Reusable `MessageDigest` (In-place direct): 828.9 ms, Alloc: 0.00 MB, Rate: 241.3 MB/s
- Potential Speedup: 1.08x, Heap reduction: 1.50 MB (100% elimination of transient wrapper churn).

---

## EXP-091 — Post-Optimization Transfer Loopback Pipeline Measurements (After Batch 1, 2, 3)

### Date
2026-10-31

### Environment
- OS: Windows 11 (10.0 amd64)
- CPU: 8 logical processors
- JVM: OpenJDK 25.0.3 (Launcher 21.0.10)
- Branch: `agent/opt-transfer`

### Method
Re-run of `TransferLoopbackBenchmarkTest.benchmark200MbTransfer` under identical hardware and test conditions after integrating Batch 1, 2, and 3 optimizations.

### Post-Optimization Measurements (After)
| Metric | 200MB @ 64 KB Chunks | 200MB @ 256 KB Chunks |
|---|---|---|
| Throughput (Before -> After) | 57.6 MB/s -> **65.3 MB/s** (+13.4%) | 58.3 MB/s -> **62.4 MB/s** (+7.0%) |
| Wall Time (Before -> After) | 3,472.8 ms -> **3,060.8 ms** (-11.9%) | 3,431.5 ms -> **3,204.1 ms** (-6.6%) |
| CPU Time (Before -> After) | 3,328.1 ms -> **2,953.1 ms** (-11.3%) | 3,281.3 ms -> **3,109.4 ms** (-5.2%) |
| Thread Heap Alloc (Before -> After) | 456 MB -> **253 MB** (**-44.5%**) | 1,018 MB -> **515 MB** (**-49.4% / Halved**) |

### SHA-256 Microbenchmark (200 MB, 3,200 x 64 KB blocks)
- Baseline `Sha256.digest`: 895.6 ms (223.3 MB/s)
- Optimized `Sha256.digest`: 644.6 ms (310.3 MB/s, **+39.0%**)
- In-place `MessageDigest.reset`: 582.7 ms (**343.3 MB/s**, **+53.7%**)

### Key Observations
1. **Heap Allocation Cut in Half:** Thread allocations for 200 MB at 256 KB chunks dropped from 1,018 MB to 515 MB (-49.4%), directly proving the elimination of redundant intermediate buffers in `ChunkFrame`, `ChunkBufferPool`, and zero-allocation bitvector traversal.
2. **Reduced CPU Burn:** CPU time dropped by 11.3% due to thread-local SHA-256 digests and eliminating boxed integer allocations.

---

## EXP-092 — 90% Mid-File Interruption and Seekable Resume Benchmark

### Date
2026-10-31

### Environment
- OS: Windows 11 (10.0 amd64)
- Test Suite: `PipelineEndToEndTest`

### Method
Simulate a 100-chunk file transfer (1,638,400 bytes, 16 KB chunks). Abort connection after transmitting exactly 90 chunks (90% completion). Resume the transfer using `SeekableChunkSource` against the receiver's persisted done-set.

### Results
- `chunksSkippedResume`: **90**
- `chunksSent`: **10** (only remaining 10 chunks transmitted over wire)
- `resumeSource.openedOffsets`: Contains `1474560` (90 * 16,384 bytes).
- Disk/Source Read Footprint: 0 bytes read from prefix (0..90% chunk offset).
- Whole-File Verification: Verified = `true`, receiver whole-file SHA-256 match = `true`.

### Conclusion
`SeekableChunkSource` and prefix fast-forwarding in `SendPipeline` and `MultiStreamDispatcher` avoid reading 90% of file data from disk upon resume, eliminating flash read wear and CPU hashing overhead.
