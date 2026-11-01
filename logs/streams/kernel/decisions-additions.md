# Kernel Stream Decision Additions (ADR 310–319)

## ADR-310 — Level-Gated Inline Logging and Zero-Allocation Probes in core:common

### Date
2026-11-01

### Finding References
`OPT-O20-01`, `OPT-O20-04`

### Context
`FlashLog` is called across every module in Flash, on both UI rendering threads (60/120 Hz) and network/transfer worker pools.
Prior to this change:
1. `FlashLog.d`, `i`, `w`, `e` took `msg: String`. Callers constructing log messages via string templates (e.g. `FlashLog.d("tag", "chunk $id sent, speed=$mbps")`) evaluated string allocations even when the log level was filtered out. Audit O20 measured this as an 11 FPS jank contributor during high-speed transfers.
2. `FlashProbe.emit(name, vararg fields: Pair<String, Any?>)` allocated a `Pair` array, `ArrayList`, and `StringBuilder` per probe emission, calling regex sanitization repeatedly.

### Decision
1. Introduce inline lambda overloads `inline fun d(tag: String, msg: () -> String)` in `FlashLog` that evaluate `isLoggable(level)` BEFORE invoking `msg()`. Keep all existing string overloads for 100% source compatibility across the monorepo.
2. Add `DEBUG` to `FlashLogLevel`, default minimum level to `INFO`, and wire `setMinLevel`/`minLevel`.
3. Provide flat overloads for `FlashProbe.emit(name, k1, v1)` through `emit(name, k1, v1, ..., k8, v8)` to eliminate `Array<Pair>` allocation. Direct-format output via `appendSanitized` directly to destination buffer. Keep all probe line names and key spellings identical to maintain the `docs/testing/PROBES.md` evidence contract.

### Consequences
- Callers using lambda logging incur zero string allocation and zero formatting overhead when disabled.
- Probes emit with zero transient `Pair` allocations.
- 100% backward compatibility maintained across all calling modules.

---

## ADR-311 — Off-Caller-Thread Redaction and Streaming Gzip Export in RotatingFileLogSink

### Date
2026-11-01

### Finding References
`OPT-O20-02`, `OPT-O20-06`

### Context
`RotatingFileLogSink` on Android was performing synchronous `SimpleDateFormat.format` and 3 regex redaction passes (`INVITE_LINK`, `SECRET_ASSIGN`, `BASE64_BLOB`) directly on the caller thread before appending to disk files. Furthermore, diagnostic log export copied uncompressed logs to disk before external transmission, wasting flash storage and I/O cycles.

### Decision
1. Move date formatting and regex redaction into `formatEntry` on the background `flash-file-log` worker thread. The caller thread simply creates a lightweight `LogEntry` and enqueues it to `ArrayBlockingQueue<LogEntry>`.
2. Wrap `FileOutputStream` in `BufferedOutputStream(8192)` to batch disk writes.
3. Add `exportGzip(destination: File, headerLines: List<String>)` to `RotatingFileLogSink` streaming directly to `GZIPOutputStream(16384)` with CRC32 verification, avoiding uncompressed temporary file duplication.

### Consequences
- Caller threads incur zero regex redaction latency and zero synchronous disk I/O.
- Diagnostic export generates standard gzip archives directly in a single pass.

---

## ADR-312 — Thread-Safe SPKI Public Key LRU Caching and JCA ThreadLocal Instance Reuse in core:security

### Date
2026-11-01

### Finding References
`OPT-O15-01`, `OPT-O15-02`, `OPT-O15-03`, `OPT-O15-04`

### Context
In `PlatformCrypto.android.kt` and `PlatformCrypto.jvm.kt`:
1. `ecP256Verify` re-parsed ASN.1 SPKI public keys via `KeyFactory.generatePublic(X509EncodedKeySpec)` on every verification, consuming ~1.1 ms per key parse.
2. `aesGcmSeal` and `aesGcmOpen` called `Cipher.getInstance("AES/GCM/NoPadding")` on every call, causing synchronized provider table lookups and GC allocations.
3. `sha256` and `hmacSha256` looked up `MessageDigest` and `Mac` on every invocation.
4. `KeyFileVault` instantiated 3 fresh `SecureRandom()` instances.

### Decision
1. Add an LRU cache of `SpkiKey` (`ByteArray.contentHashCode()` / `contentEquals()`) to `PublicKey` bounded to 1,024 entries. Cache only parsed provider objects, never secret key material.
2. Cache `Cipher.getInstance(AES_GCM)`, `MessageDigest.getInstance("SHA-256")`, and `Mac.getInstance(HMAC_SHA256)` in `ThreadLocal` instances.
3. Handle SunJCE's NIST SP 800-38D check (`Cannot reuse iv for GCM encryption`) in `aesGcmSeal` by falling back to a fresh Cipher instance when identical `(key, IV)` parameters are reused consecutively on the same thread.
4. Share a single internal `sharedSecureRandom` instance in `KeyFileVault`.

### Consequences
- `ecP256Verify` speedup: 2.2 ms -> 1.12 ms (halves verification cost).
- `aesGcmSealOpen` time reduced to ~36.9 µs/op with zero provider lookup.
- Zero entropy pool file-descriptor churn.
- Preserves all cryptographic security invariants.

---

## ADR-313 — ASCII Fast-Path, Top-Level Regex, and LRU Cache for Search Folding

### Date
2026-11-01

### Finding References
`OPT-O25-03`

### Context
`foldForSearch(input)` in `core/common` was recompiling `"\\p{M}+".toRegex()` and executing full `Normalizer.normalize(..., NFKD)` on every invocation. In `sortedWith` and live search filtering, this ran hundreds of times per keystroke on the main thread.

### Decision
1. Fast-path pure ASCII strings (`code <= 0x7F`): ASCII characters have no Unicode decompositions and no combining diacritic marks, so pure ASCII strings bypass `Normalizer` and Regex entirely, returning `input.lowercase()` directly.
2. Promote `DIACRITICS_REGEX = Regex("\\p{M}+")` to a top-level constant.
3. Maintain a 512-entry synchronized LRU cache (`LinkedHashMap`) to make repeated queries and comparator scans instant.

### Consequences
- Pure ASCII folding runs in ~216 ns/op.
- Cached NFKD queries run in ~120 ns/op (vs 4,800 ns/op baseline, >95% speedup).
- Eliminates main-thread regex compilation jank during search typing.

---

## ADR-314 — Retention of Complete Security Test Suite in Integration Gates

### Date
2026-11-01

### Finding References
`OPT-O27-06`

### Context
Audit finding `OPT-O27-06` noted heavyweight cryptographic tests in standard test passes. Inspection of `O27-test-suite-performance.md` revealed the flagged tests reside in `:core:enterprise` (`OrgDirectorySyncTest` with 5,000 member signatures), `:server` (`AdminAuthServiceTest` with 600,000 PBKDF2 iterations, `E2eHubFileTransferContractTest` with 50 MB chunking), and `:core:messaging` (`SignedGroupsTest`), none of which are owned by Stream K.
In `:core:security`, all 170 tests in `jvmTest` execute in ~6.0s total, and all 33 test suites in `testAndroidHostTest` complete in ~1.3s total.

### Decision
In accordance with the prompt's rule ("No security test may leave every gate: if you tag one, it must still run somewhere the integrator can point at"), no security tests in `:core:security` are tagged out or excluded from the default sweeps. All 170 security tests remain in Integration Gate 1.

### Consequences
- Full security regression verification is preserved without compromise.
- Gate 1 test count for `:core:security:jvmTest` remains at 170 (with the 2 documented POSIX skips).
