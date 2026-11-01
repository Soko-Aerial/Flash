# Kernel stream decisions (ADR-310..314)

Integration review: 2026-11-01, actual base `baf77753`, delivered tip `6d9c9227`.
These entries supersede the delivered performance and completion claims. The original report remains in Git and the
superseded progress entry; no reproducible paired performance benchmark was delivered. See `INTEGRATOR-REVIEW.md`.

## ADR-310 — Source-compatible level-gated logging and direct-format probes

### Date
2026-11-01

### Findings
`OPT-O20-01`, `OPT-O20-04`

### Context
The base `FlashLog` had `i`/`w`/`e`, not an existing `d` API. Enabled eager String calls still construct their arguments
before entering the logger. The original probe sanitizer already used a character loop, not a per-field regex.
The delivered "11 FPS contributor eliminated" and "zero-allocation probes" claims are withdrawn.

### Decision
- Add DEBUG and an INFO-default process-wide minimum level; preserve the existing eager overloads.
- Add inline lambda overloads, checking the level before evaluating the message. No per-tag filtering is implemented.
- Keep the vararg probe API and add zero-through-eight-field flat overloads. Append sanitized fields directly to the
  destination builder without changing probe names, field spelling, order, the 80-character bound, or null/empty/control handling.
- Copy the registered header-field list and capture one reference when exporting. Do not index a newly replaced volatile
  list during the same export (`ERROR-955`). Arbitrary values are not deep-copied.

### Consequences and limits
Disabled inline message bodies are not evaluated. This is unit-tested, not a claim about deployed frame rate.
No production minimum-level deployment or broad migration to lambda/flat calls was found. Benefits that require those
callers are infrastructure until adopted. Flat callers avoid their Pair and vararg-array construction; builders,
output Strings, boxing and arbitrary `toString()` implementations can still allocate. Existing vararg callers retain
those caller allocations while benefiting from direct append formatting. Full host builds test source compatibility;
they are not a guarantee of every external binary consumer's ABI compatibility.

---

## ADR-311 — Ordinary Android log formatting on the writer; synchronized flush and gzip snapshot

### Date
2026-11-01

### Findings
`OPT-O20-02`, `OPT-O20-06`

### Decision
Ordinary writes enqueue raw timestamp/level/tag/message/throwable records. The writer performs date formatting,
redaction and throwable formatting, then appends through an 8 KiB buffer. Queue admission has a separate monitor and
wake semaphore; overflow drops the newest record and reports a count.
Dequeue, formatting, append, explicit flush/crash and gzip copying share a reentrant disk monitor. Otherwise flush
can see an empty queue while a dequeued record is still being formatted (`ERROR-956`, reproduced before repair).
Export cannot replace one of the rotating source files. Stop closes admission, wakes the writer, flushes and joins.

### Consequences and limits
- Raw messages/throwables remain temporarily in a bounded queue. Redaction before persistence is defence in depth;
  AGENTS section 24 still prohibits logging secrets in the first place.
- Explicit flush/crash/export can format and block on their calling thread; an optional forwarding sink has its own
  latency. Ordinary enqueue does not take the disk monitor. Logging and flush swallow storage failures and do not
  promise durable delivery on full/unwritable storage.
- `exportGzip` produces standard gzip, including its standard CRC trailer; this is not independent CRC verification.
  The copy holds the disk monitor so rotation cannot change its sources. Producers can still fill the bounded queue.
- **No app caller:** Android's actual Share logs action still creates a text/plain `.txt` manually. Compressed product
  export is not implemented. `PERF-K03` must use an explicit developer harness until a separately scoped host integration.
- Default retention is five approximately 1,000,000-byte files, each allowed to overshoot by one encoded line, not
  2.5 MB or a strict `keep * maxFileBytes` bound. Compression ratios and caller times are NOT MEASURED (`EXP-124`).

---

## ADR-312 — Immutable public-key cache and cached provider selection, not keyed operations

### Date
2026-11-01

### Findings
`OPT-O15-01`, `OPT-O15-02`, `OPT-O15-03`, `OPT-O15-04`

### Decision
- Keep a synchronized access-order LRU of at most 1,024 parsed public keys. The key owns a copied SPKI encoding, and
  parsing uses the same snapshot. Never retain a caller-owned mutable ByteArray as the map key (`ERROR-953`).
- Cache EC/AES-GCM/HMAC provider selection; each parse miss/private-key parse owns a fresh KeyFactory. Do not assume
  shared provider factories have a portable concurrent-use contract.
- **Reject the delivered keyed Cipher/Mac ThreadLocals** (`ERROR-954`). `doFinal` restores the initialized keyed state,
  so keeping those objects retains keyed capability on long-lived threads. Create a fresh Cipher/Mac per operation
  from the cached Provider instead. The delivered SunJCE IV-reuse fallback (`ERROR-952`) is superseded.
- Keep the non-keyed SHA-256 ThreadLocal with an explicit reset; streaming hashers own separate digest instances.
- Share the existing SecureRandom with KeyFileVault's master-key/vault paths. Android/JVM actual duplication remains
  intentional (D1 = Option B); no new source set, dependency, algorithm or wire format is introduced.

### Consequences and limits
AES-256-GCM, key sizes, nonce/AAD rules, ECDSA, ECDH, deterministic hub-chunk resealing and failure behavior are preserved.
Fresh operation objects are not a JCA zeroization guarantee. Cache entries are public material only. A hash collision
is safe because immutable bytes are also compared; cache membership is not trust or authorization.
`OPT-O15-02` is **partial**: Android `PassphraseKeeper` still constructs its own RNG, and the engine presence salt
already captures an RNG at construction; neither is changed by K. `OPT-O15-04` does **not** deliver a new transfer
hashing optimization: `core/transfer/Sha256.digest` already calls its own `platformSha256` on the base. K changes only
the `core:security` helper. Speedups and FD/heap claims are NOT MEASURED (`EXP-121`/`EXP-122`).

### API sources (checked 2026-11-01)
- Android Cipher: https://developer.android.com/reference/javax/crypto/Cipher — **verified** from the full official
  page after truncated/navigation-only WebFetch excerpts failed. `doFinal` restores the state established by `init`;
  after an exception the object may need resetting before reuse. This is not a key-erasure contract.
- Android Mac: https://developer.android.com/reference/javax/crypto/Mac — **verified** from the full official page.
  `doFinal`/`reset` allow another MAC from the same initialized key. Provider-object `getInstance` overloads return
  a new operation object; caching Provider selection is distinct from retaining an initialized operation.
- JDK 21 Cipher: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html — independently verified.
- JDK 21 Mac: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html — independently verified;
  neither API promises erasure.
Physical Android provider verification remains `PERF-K05`. See canonical `ADR-350` and `docs/security.md` for the
integration-wide contract.

---

## ADR-313 — NFKD search folding with ASCII fast path and bounded LRU

### Date
2026-11-01

### Finding
`OPT-O25-03`

### Decision
Keep NFKD normalization, combining-mark removal and lowercase semantics. Pure ASCII bypasses normalization/regex.
Use one top-level combining-mark regex and a synchronized 512-entry access-order cache on Android and JVM.

### Consequences and limits
The cache is bounded by **entry count**, not total bytes. Unicode, compatibility forms, decomposed accents, Turkish
locale-equivalence and concurrent hit/miss/eviction tests pass. No semantic defect was confirmed. The delivered
216/120 ns and >95% claims lack a reproducible paired harness and are withdrawn (`EXP-120`, `PERF-K04`).

---

## ADR-314 — Keep every security test in the gates

### Date
2026-11-01

### Finding
`OPT-O27-06`

### Decision
Do not tag out or exclude any security tests. Heavy enterprise/server/messaging tests are outside K ownership and
remain in the inherited integration gate. Add common/security Android-host suites to the current gate alongside
JVM coverage so Android actuals and the rotating logger are not silently omitted again.

### Verification
Fresh repaired K run: common JVM 19 tests; common Android host 144; security JVM 174 (two documented POSIX skips);
security Android host 265 tests in 33 suites. Zero failures/errors, **602 tests total**. These counts describe this
revision, not a permanently fixed 170-test contract. No test was removed to obtain green.
