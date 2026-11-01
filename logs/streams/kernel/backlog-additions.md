# Kernel Stream Backlog Additions (PERF-K*)

## Test Backlog Items

### PERF-K01 — Logging/probe call-site behavior, allocations and frame impact
- **Setup:** physical supported Android handset and desktop developer harness; actual LAN mesh, representative eager/lambda logs and vararg/flat probes. Same sink, rate and workload on base `baf77753` and repaired tree; isolated data, authorized device session only.
- **Steps:** compare disabled DEBUG lambda with an incrementing body counter, enabled DEBUG and eager String calls. Compare vararg with flat zero-through-eight-field probes, including null/control/Unicode/long values. Capture allocations, gfxinfo/frame timing and latency; profile current product callers separately from synthetic migrated callers.
- **Pass:** disabled lambda counter remains zero; enabled/eager and flat/vararg outputs preserve level, field bytes/order/bounds. Flat calls avoid Pair/vararg-array construction, not builders/Strings/boxing. Report measured latency/jank/allocation and actual caller mix; no predetermined FPS gain.
- **Superseded delivered requirement:** 100 MB/s Wi-Fi Direct (unimplemented), <1 FPS loss and "11 FPS eliminated" were unsupported. Suggested BelFone/Transsion hardware is optional, not measured evidence.
- **Source:** ADR-310, ERROR-955, EXP-123; `FlashLogTest`, `FlashProbeTest`, `docs/testing/PROBES.md`.
- **Status:** TODO

---

### PERF-K02 — Public-key cache and realistic crypto/directory profiling
- **Setup:** same Android/desktop hardware for paired base `baf77753` and repaired runs; provider/JDK/API recorded. Isolated 5,000-member directory plus cache hit/miss/eviction fixtures.
- **Steps:** time parsing, verification and whole sync separately with cold/warm caches and churn beyond 1,024 keys. Exercise copied/mutated invalid SPKI, bad signatures and concurrency. Profile AES seal/open separately, HMAC key changes, SHA and KeyFileVault RNG/FD behavior with test-only material.
- **Pass:** cache membership never bypasses signatures/trust; mutated invalid bytes are rejected and sync/output bytes match base. Report distributions, CPU/thermal/allocations/FDs without assuming 50%, doubled throughput or <20 s. Distinguish security hashing from transfer hashing and unchanged Android PassphraseKeeper RNG.
- **Superseded delivered requirement:** Pixel/Samsung suggestions and <20 s were not measured hardware-independent requirements; combined seal-plus-open does not establish individual timings.
- **Source:** ADR-312, ERROR-953/954, EXP-121/122, ERROR-938; `PlatformCryptoParityTest`, concurrency tests.
- **Status:** TODO

---

### PERF-K03 — Android logger flush, rotation and explicit developer gzip export
- **Setup:** physical supported Android and a developer harness explicitly calling `exportGzip` (no product caller). Isolated log directory, writable/slow/failing output fixtures and a standard gzip decoder. Retention defaults to five ~1,000,000-byte files, each with at most one encoded-line overshoot.
- **Steps:** populate sanitized logs/header, rotate, enqueue during export, and block a dequeued throwable formatter before flush. Reject export to a rotating source. Decode archive and compare stable header/chronological snapshot; inspect temporary files, latency/size/drop counts. Exercise crash/stop/full storage without logging secrets.
- **Pass:** flush accounts for in-flight dequeued entries; export sources cannot rotate during copy or be overwritten by destination. Decoder checks standard gzip CRC and expected bytes; no extra independent CRC mechanism is inferred. No temporary uncompressed export file appears in the harness. Slow export may overflow/drop newest with a count; explicit calls may block. Record failures/content/size/time without durability or flash-wear guarantees.
- **Superseded delivered requirement:** ~2.5 MB, fixed compression/flash-wear claims and product gzip export were inaccurate; normal Android Share logs still creates a text/plain `.txt`.
- **Source:** ADR-311, ERROR-956, EXP-124; `RotatingFileLogSinkTest`; AGENTS.md section 24.
- **Status:** TODO

---

### PERF-K04 — Paired NFKD search folding and bounded-cache profiling
- **Setup:** same Android/desktop hardware on base `baf77753` and repaired tree; ASCII, accented/decomposed, compatibility, Turkish-I, Greek/Cyrillic, CJK/emoji and long strings. Record locale, warmup, hit/miss ratio, concurrency and >512 unique entries.
- **Steps:** compare every result to NFKD + combining-mark removal + lowercase baseline. Profile cold misses/warm hits/ASCII/concurrent eviction separately; measure retained bytes, not just entry count.
- **Pass:** semantics remain identical including locale changes; concurrent cache churn is safe. Publish reproducible timings/allocations, not cross-date audit comparisons or >95% guarantees. A 512-entry limit is not a total-String-byte limit.
- **Source:** ADR-313, EXP-120; `SearchFoldingTest`, `SearchFoldingConcurrencyTest`.
- **Status:** TODO (Unicode/locale/concurrency host checks PASS; physical/performance not run)

### PERF-K05 — Physical Android JCA parity and failure recovery
- **Setup:** authorized physical Android session on supported API/provider combinations, test-only vectors, repaired JVM outputs, all security suites retained. Record provider/algorithm; never export keys/copy keystore.properties/.jks.
- **Steps:** AES-256-GCM vectors, bad tag/AAD, changed-key recovery, identical immutable hub-chunk reseal, HMAC key changes, ECDSA/ECDH and mutated-SPKI rejection; repeat concurrently. Compare one-shot/independent streaming SHA. Inspect operation lifetime separately: correctness tests do not prove zeroization.
- **Pass:** vectors/failures match reference; failure cannot contaminate later operations; invalid SPKI cannot retrieve cached valid keys; immutable-chunk reseal stays deterministic. No keyed Cipher/Mac ThreadLocal/global cache, no algorithm/key-size/nonce/AAD/pin/wire downgrade and no security suite excluded from every gate. Report provider-specific outcomes honestly.
- **Source:** ADR-312/314/350, ERROR-952 (superseded), ERROR-953/954; official Android/JDK Cipher/Mac references.
- **Status:** TODO (JVM/Android-host parity/concurrency PASS; physical providers not run)
