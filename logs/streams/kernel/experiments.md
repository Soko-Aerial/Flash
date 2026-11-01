# Kernel Stream Experiments Log (EXP 120–124)

> **Integration review, 2026-11-01: every performance figure below is WITHDRAWN / NOT MEASURED.**
> No committed reproducible paired harness establishes these ns/us, percentage, allocation or compression numbers.
> The original claimed methods/figures below are retained as the delivered account, not evidence about the repaired code.
> Cross-date audit estimates are not a measured baseline. Current structural facts and the owed tests follow each entry.

## EXP-120 — Search folding — NOT MEASURED (delivered figures withdrawn)

### Date
2026-11-01

### Finding
`OPT-O25-03`

### Environment
- OS: Windows 11
- CPU: Intel Core (8 logical cores)
- JVM: OpenJDK 64-Bit Server VM (build 21.0.6)

### Target Tested
`com.transfer.flash.core.common.text.SearchFolding.foldForSearch`

### Delivered method (not reproduced)
Executed 20,000 iterations over sample inputs following 1,000 warmup passes:
1. Pure ASCII input: `"General Mensah"` (14 chars)
2. Accented input: `"Béatrice Owusu"` (14 chars, Unicode acute accent U+00E9)

### Delivered figures and conclusion — WITHDRAWN
- Baseline (from `docs/audit/optimization-2026-10-23/O25-algorithmic-complexity.md:210`):
  - 10,000 calls: ~48 ms (~4,800 ns/op) due to per-call `"\\p{M}+".toRegex()` pattern compilation and un-cached NFKD decomposition.
- Optimized (with top-level regex, ASCII fast-path, and 512-entry LRU cache):
  - Pure ASCII input: **216 ns/op** (>95% latency reduction, 0 regex allocations, 0 Normalizer calls)
  - Accented input (cache hit): **120 ns/op** (97.5% latency reduction, 0 regex allocations)

### Delivered conclusion — SUPERSEDED
Eliminated all per-call regex parsing overhead. During UI search queries and comparator sorting in `:ui:chat` and `:server`, search folding latency drops from milliseconds to microseconds.

### Reviewed structural result and owed measurement
The top-level regex, ASCII fast path and synchronized 512-entry access-order cache are retained. NFKD, combining-mark removal
and lowercase semantics are unchanged; Unicode, compatibility forms, Turkish-default-locale equivalence and concurrent
hit/miss/eviction tests pass. The cache is bounded by entry count, not total bytes. The delivered ns/op and percentages compare
against an older audit estimate, not a same-host paired baseline, and are not evidence of UI/server speed. Run `PERF-K04`
on the actual base `baf77753` and repaired tree with the same host, inputs, warmup and cache-hit/miss mix. Status: NOT MEASURED.

---

## EXP-121 — Public-key parsing cache — NOT MEASURED (delivered figures withdrawn)

### Date
2026-11-01

### Finding
`OPT-O15-01`

### Environment
- OS: Windows 11
- JVM: OpenJDK 64-Bit Server VM (build 21.0.6)

### Target Tested
`com.transfer.flash.core.security.crypto.ecP256Verify`

### Delivered method (not reproduced)
Executed 2,000 signature verifications with fixed P-256 keypair and payload following 200 warmup passes.

### Delivered figures and conclusion — WITHDRAWN
- Baseline (Audit O15: `docs/audit/optimization-2026-10-23/O15-serialization-crypto-hashing.md:69`):
  - ASN.1 SPKI parsing via `KeyFactory.generatePublic(X509EncodedKeySpec)`: 0.8–1.5 ms per key parse. Total verification latency: ~2.2 ms/op.
- Optimized (with 1024-entry SPKI public key cache):
  - `ecP256Verify`: **1,121,621 ns/op (1.12 ms/op)**.
  - Total latency reduced by ~1.1 ms per verify (50% reduction in verification time).

### Delivered conclusion — SUPERSEDED
Caching pre-parsed `PublicKey` objects eliminates repeated ASN.1 DER decoding and EC point validation. Directory synchronization and group message verification throughput doubles.

### Reviewed structural result and owed measurement
The LRU keeps up to 1,024 parsed **public** keys. `ERROR-953` repairs a mutable map key: each key now owns copied SPKI bytes,
and parsing uses that exact snapshot. A cache hit avoids another parse, not signature verification or authorization.
The regression failed on the delivered code and passes after repair. The delivered 1.12 ms and 50%/doubled-throughput
claims have no reproducible same-host paired harness; the older audit range is not that baseline. Run cache-hit, miss,
eviction and realistic directory-sync profiles in `PERF-K02`, with the base and repaired tree on the same hardware.
Status: NOT MEASURED.

---

## EXP-122 — Provider selection and digest reuse — NOT MEASURED (delivered figures withdrawn)

### Date
2026-11-01

### Finding
`OPT-O15-03`, `OPT-O15-04`

### Environment
- OS: Windows 11
- JVM: OpenJDK 64-Bit Server VM (build 21.0.6)

### Target Tested
`com.transfer.flash.core.security.crypto.aesGcmSeal`, `aesGcmOpen`, and `sha256`

### Delivered method (not reproduced)
- 2,000 iterations of 1 KB AES-256-GCM seal + open.
- 10,000 iterations of 1 KB SHA-256 digest.

### Delivered figures and conclusion — WITHDRAWN
- AES-256-GCM Seal + Open (combined): **73,800 ns/op (~36.9 µs per operation)**.
  - Eliminated synchronized security provider list traversal in `Cipher.getInstance`.
  - Zero `Cipher` instance allocations on steady-state transfer paths.
- SHA-256 (1 KB payload): **5,517 ns/op (5.5 µs/op)**.
  - Reusable thread-local `MessageDigest` avoids JCA lookup per frame.

### Delivered conclusion — SUPERSEDED
Provider lookup overhead eliminated across symmetric framing and hashing hot paths.

### Reviewed structural result and owed measurement
The delivered keyed `Cipher`/`Mac` ThreadLocals are **rejected** (`ERROR-954`): `doFinal`/`reset` leave them initialized
with keyed capability. The repaired actuals cache Provider selection and create fresh Cipher/Mac operations; fresh
KeyFactory instances own each parse. The non-keyed SHA-256 ThreadLocal is retained/reset; streaming hashes are separate.
The delivered combined 73,800 ns seal-plus-open figure cannot be divided into separately measured seal and open times.
Zero Cipher allocations is false for the repaired implementation, and the 5,517 ns figure is not a reproducible paired
result. Algorithms, nonce/AAD discipline and deterministic resealing of identical immutable hub chunks are unchanged.
`core:security` hashing is not the independent `core:transfer` hashing path, which already delegates to its own platform
helper; Android PassphraseKeeper's RNG is outside K ownership. Run `PERF-K02`/`PERF-K05`; no FD, heap, allocation or
transfer-throughput improvement has been measured. Fresh JCA objects do not guarantee zeroization.

---

## EXP-123 — Probe call-site allocation — NOT MEASURED (delivered figures withdrawn)

### Date
2026-11-01

### Finding
`OPT-O20-04`

### Environment
- Static analysis & micro-profiling of `FlashProbe.kt`

### Target Tested
`FlashProbe.emit` with 4 key-value fields.

### Delivered figures and conclusion — WITHDRAWN
- Baseline:
  - `emit(name, vararg fields: Pair<String, Any?>)`:
    - 4 `Pair` object allocations: 4 * 24 bytes = 96 bytes
    - 1 `Array<Pair>` allocation: 48 bytes
    - Intermediate `StringBuilder` allocations and regex sanitization per field.
- Optimized:
  - `emit(name, k1, v1, k2, v2, k3, v3, k4, v4)` flat overload:
    - **0 Pair allocations**.
    - **0 Array allocations**.
    - Direct sanitization via `appendSanitized` directly to single buffer.

### Delivered conclusion — SUPERSEDED
Transient allocation churn on high-frequency evidence probes reduced from 144+ bytes per emission to 0 bytes.

### Reviewed structural result and owed measurement
The original sanitizer was a character loop, not a per-field regex. Flat zero-through-eight-field overloads avoid Pair
and vararg-array construction **at those callers**; existing vararg callers still construct them. Direct append preserves
field order/spelling, null/empty `-`, control/whitespace `_` and the 80-character bound. Builders, Strings, boxing and
arbitrary `toString()` can allocate; the delivered object-size arithmetic is not measured bytes/op and zero total allocation
is false. No broad production migration to flat/lambda calls was delivered. Header fields are copied at registration and
one list reference is captured at export (`ERROR-955`). Run `PERF-K01` with explicit eager/lambda and vararg/flat call mixes,
real host/device allocation profiling and an enabled/disabled counter. Status: NOT MEASURED; no FPS gain is established.

---

## EXP-124 — Android logger and gzip API — NOT MEASURED (delivered figures withdrawn)

### Date
2026-11-01

### Finding
`OPT-O20-02`, `OPT-O20-06`

### Environment
- Android Host Environment / Unit Test Suite

### Target Tested
`com.transfer.flash.core.common.logging.RotatingFileLogSink`

### Delivered figures and conclusion — WITHDRAWN
- Off-Thread Redaction:
  - Caller thread execution time reduced from synchronous formatting + 3 regex passes (~80 µs) to simple queue offer (<1 µs).
  - Background worker batches writes via `BufferedOutputStream(8192)`.
- Gzip Diagnostic Export (`exportGzip`):
  - Streams directly through `GZIPOutputStream(16384)` with CRC32 verification.
  - Compresses 100 KB diagnostic logs into ~12 KB archive in a single pass without uncompressed disk duplication.

### Delivered conclusion — SUPERSEDED
Caller threads remain unblocked during logging bursts; diagnostic log export is compact and non-duplicative.

### Reviewed structural result and owed measurement
Ordinary writes enqueue raw bounded records, normally moving formatting/redaction to the writer. Explicit flush/crash/export
can block/format on their caller; forwarding sinks have their own cost. Dequeue, formatting, append and export now share a
monitor so flush cannot miss an in-flight batch (`ERROR-956`); gzip rejects an output that overwrites a rotating source.
Slow export blocks disk writes, not queue admission, and the bounded queue can overflow/drop newest records with a count.
The 8 KiB append buffer and 16 KiB underlying export buffers are structural facts, not the delivered <1 us/80 us figures.
Standard gzip contains its CRC trailer; no separate CRC validation was added. Compression ratio depends on content and
is NOT MEASURED. Default retention is five approximately 1,000,000-byte files, each allowed one encoded-line overshoot,
not 2.5 MB or a strict total-byte bound. **No product caller invokes exportGzip**: Android Share logs still creates a
text/plain `.txt`. `PERF-K03` therefore uses a developer harness until separately authorized host integration. Do not
infer flash wear from duration or an absence of a temporary file; no wear measurement was made.
