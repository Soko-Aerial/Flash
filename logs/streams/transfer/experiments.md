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
