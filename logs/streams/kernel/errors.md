# Kernel Stream Error Log

## ERROR-953 — Caller-mutable SPKI cache key can retrieve a valid key for invalid bytes

### Date / area / environment
2026-11-01; `core:security` Android/JVM actuals; delivered `6d9c9227`, base `baf77753`, Windows/JBR 21 host tests.

### Symptoms / evidence
Review found `SpkiKey` retained the caller's ByteArray but cached its initial content hash. The regression
`cached_public_key_is_a_snapshot_not_the_callers_mutable_array` failed against delivered behavior: after a valid
verify populated the cache, a hash-preserving mutation made invalid SPKI bytes still retrieve the valid PublicKey.
This was reproduced with test-only keys; no claim of a device exploit or stolen key is made.

### Root cause / failed approach
Mutable equality data was used as a map key. A cached hash does not make caller-owned bytes immutable.

### Working fix
Copy SPKI bytes into the cache key and parse that exact snapshot; compare immutable content for equality. Keep a
synchronized 1,024-entry public-key LRU. Cache membership remains unrelated to signature verification/trust.

### Verification / related files / status
Regression passes on repaired JVM and Android-host; full K run 602 tests, zero failures/errors, two POSIX skips.
`PlatformCrypto.android.kt`, `PlatformCrypto.jvm.kt`, `PlatformCryptoParityTest.kt`; ADR-312, PERF-K05.
**RESOLVED (host regression verified); physical provider verification remains TODO.**

## ERROR-954 — Keyed Cipher/Mac ThreadLocals retain initialized keyed capability

### Date / area / environment
2026-11-01; JCA operation lifetime, Android/JVM actuals, delivered `6d9c9227`, Windows/JBR 21.

### Symptoms / evidence
Static review found initialized AES-GCM Cipher and HMAC Mac objects kept in long-lived ThreadLocals after operations.
JDK 21 `doFinal`/`reset` contracts restore the initialized state, not key erasure. This contradicts the stream's
"cache the provider, not the key material" rule. Correct output alone does not prove absence of retained capability.

### Root cause / failed attempts
Caching keyed operations was mistaken for caching provider selection. ERROR-952's replacement-on-IV-reuse workaround
still retained the new initialized Cipher; its zero-allocation claim was unsupported.

### Working fix
Cache Provider selection only; create fresh Cipher/Mac per keyed operation and fresh KeyFactory per parse. Retain/reset
only the non-keyed SHA-256 ThreadLocal; streaming hashes own independent digests. Fresh objects are not a JCA zeroization
guarantee. No algorithm, key size, nonce/AAD, pin or wire change; deterministic identical immutable chunk reseal preserved.

### Verification / related files / status
AES failure/key-change, HMAC key-change, immutable-chunk reseal, parity and concurrent tests pass in the 602-test K run.
The tests verify behavior, not erasure; object lifetime is verified against source/API contracts. Both PlatformCrypto
actuals, PlatformCryptoParityTest/ConcurrencyTest; ADR-312/350, PERF-K05. Official Android and JDK Cipher/Mac URLs
in ADR-312, checked 2026-11-01. Initial Android WebFetch excerpts were navigation-only/truncated; a subsequent full
HTML extraction independently verified the reset/retained-state and Provider-object contracts. Neither promises erasure.
**RESOLVED (keyed caches removed and host-tested); physical provider check TODO.**

## ERROR-955 — Probe header export can mix replaced field lists

### Date / area / environment
2026-11-01; `FlashProbe`, common code/Android-host tests; delivered `6d9c9227`, Windows.

### Symptoms / evidence
Export repeatedly indexed a volatile list. A value's `toString()` replacing the list during formatting could mix
snapshots or index beyond the new list. Registration also retained mutable caller membership.

### Root cause / failed approach
Volatile visibility was treated as whole-export snapshot consistency; copying arbitrary values was not required.

### Working fix
Copy membership on registration and capture one list reference per export. Preserve names, order, sanitizer bytes and
bounds. Arbitrary values are not deep-copied. Do not change evidence-probe spelling to get a passing test.

### Verification / related files / status
Replacing-toString and caller-cleared-list regressions plus flat/vararg byte equivalence pass; full K run green.
`FlashProbe.kt`, `FlashProbeTest.kt`; ADR-310, PERF-K01. **RESOLVED (host-tested).**

## ERROR-956 — Flush can miss a logger record already dequeued by the writer

### Date / area / environment
2026-11-01; Android rotating logger tested on Windows Android-host; delivered `6d9c9227`.

### Symptoms / evidence
The latch regression blocks Throwable formatting after dequeue. Delivered flush saw an empty queue and returned
before that record was persisted; the regression failed. A path-only export snapshot also cannot survive rotation.

### Root cause / failed approach
Dequeue/format happened outside flush's storage monitor. Queue emptiness was mistaken for completed persistence.

### Working fix
Use the same reentrant monitor for dequeue, formatting, append, explicit flush/crash and gzip copy. Reject exports
whose destination replaces a rotating source. Producers use a separate admission monitor; export may fill/overflow
that bounded queue. Storage failures remain best-effort, not durable delivery; explicit calls can block.

### Verification / related files / status
In-flight flush and source-overwrite regressions pass; full K run 602 tests, zero failures/errors, two skips.
`RotatingFileLogSink.kt`/Test; ADR-311, EXP-124, PERF-K03. **RESOLVED (host-tested); physical storage checks TODO.**

## Preserved delivered error history

## ERROR-951 — FlashPerformanceClassifierTest belfone_is_low failed after ULTRA_LOW mode addition

### Date
2026-11-01

### Area
Performance Classification / Device Tiers (`core:common`)

### Symptoms
`:core:common:testAndroidHostTest` failed:
```text
FlashPerformanceClassifierTest > belfone_is_low FAILED
    java.lang.AssertionError: expected:<LOW> but was:<ULTRA_LOW>
        at org.junit.Assert.fail(Assert.java:89)
        at org.junit.Assert.failNotEquals(Assert.java:835)
        at org.junit.Assert.assertEquals(Assert.java:120)
        at org.junit.Assert.assertEquals(Assert.java:146)
        at com.transfer.flash.core.common.perf.FlashPerformanceClassifierTest.belfone_is_low(FlashPerformanceClassifierTest.kt:58)
```

### Environment
- Branch: `agent/opt-kernel`
- Commit compared against: `44eed960` (and `dev` at `baf77753`)
- Root commit: `051504a9d6cdccaeba0fdff0609cc41d826ef4f6`

### Root cause
In commit `051504a9d6cdccaeba0fdff0609cc41d826ef4f6` (2026-10-11), `ultraLowGate` was added to `FlashPerformanceClassifier.kt` specifically targeting BelFone SCP810 hardware (`RAM <= 2048 & API <= 27`) to map to `FlashPerformanceMode.ULTRA_LOW`. However, `FlashPerformanceClassifierTest.kt` (which resides in `core/common/src/androidHostTest`, not included in integration Gate 1 or Gate 2) was not updated and still expected `FlashPerformanceMode.LOW`.

### Failed attempts
None.

### Working fix
1. Updated `FlashPerformanceClassifierTest.kt` to rename `belfone_is_low` to `belfone_is_ultra_low` and assert `FlashPerformanceMode.ULTRA_LOW` with `"BelFone-class"` reason.
2. Added `low_ram_gate_without_ultra_low_is_low` testing an API 28 device with 1800 MB RAM to ensure the standard `LOW` gate remains tested and verified.

### Verification
Ran `:core:common:testAndroidHostTest` via `tools\flash-gradle.cmd`. 124 tests completed, 0 failed.

### Related files
- `core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceClassifierTest.kt`

### Status
RESOLVED


## ERROR-952 — SunJCE InvalidAlgorithmParameterException: Cannot reuse iv for GCM encryption on cached ThreadLocal Cipher

### Date
2026-11-01

### Area
Cryptographic Primitives / AES-GCM (`core:security`)

### Symptoms
`:core:security:jvmTest` failed during `HubFileSealingTest`:
```text
HubFileSealingTest[jvm] > resealingIsDeterministicSoAResumedUploadRepeatsItsBytes[jvm] FAILED
    java.security.InvalidAlgorithmParameterException: Cannot reuse iv for GCM encryption
        at java.base/com.sun.crypto.provider.GaloisCounterMode.init(GaloisCounterMode.java:147)
        at java.base/com.sun.crypto.provider.GaloisCounterMode.engineInit(GaloisCounterMode.java:289)
        at java.base/javax.crypto.Cipher.init(Cipher.java:1493)
        at java.base/javax.crypto.Cipher.init(Cipher.java:1422)
        at com.transfer.flash.core.security.crypto.PlatformCrypto_jvmKt.aesGcmSeal(PlatformCrypto.jvm.kt:119)
        at com.transfer.flash.core.security.enterprise.HubFileSealing.sealChunk(HubFileSealing.kt:160)
        at com.transfer.flash.core.security.enterprise.HubFileSealingTest.resealingIsDeterministicSoAResumedUploadRepeatsItsBytes(HubFileSealingTest.kt:110)
```

### Environment
- Branch: `agent/opt-kernel`
- Commit compared against: `44eed960`
- JDK: OpenJDK / JBR 21 on Windows

### Root cause
In Java 8u60+, Oracle SunJCE's `GaloisCounterMode.init(ENCRYPT_MODE, ...)` tracks the previous key and IV used by that specific `Cipher` instance. If `init` in `ENCRYPT_MODE` is called on the *same* `Cipher` instance with the identical `(key, IV)` pair consecutively, SunJCE raises `InvalidAlgorithmParameterException("Cannot reuse iv for GCM encryption")` to enforce NIST SP 800-38D. When `PlatformCrypto.aesGcmSeal` was optimized to cache `Cipher.getInstance(AES_GCM)` in a `ThreadLocal`, consecutive calls sealing the same deterministic chunk (such as chunk 0 during upload resume verification in `resealingIsDeterministicSoAResumedUploadRepeatsItsBytes`) re-initialized the same Cipher object with identical key and nonce, triggering the exception.

### Failed attempts
None.

### Delivered working fix — SUPERSEDED by ERROR-954
In both `PlatformCrypto.jvm.kt` and `PlatformCrypto.android.kt`, wrapped `aesGcmCipher.get()!!.init(...)` in a `try-catch` block catching `InvalidAlgorithmParameterException`. On the rare path where identical parameters are reused consecutively on the same thread, instantiate a fresh `Cipher.getInstance(AES_GCM)`, update the `ThreadLocal`, and initialize it. Normal traffic (monotonic nonces or random IVs) incurs zero provider lookups and zero allocations.

Integration correction (2026-11-01): the zero-allocation/provider headline is withdrawn. Replacing a cached Cipher on
one failure path still retains keyed capability afterward. The repair caches Provider selection and creates a fresh
keyed operation per call; deterministic resealing applies only to identical immutable hub chunks, not nonce reuse
with changed plaintext. No portable key-zeroization guarantee is claimed.

### Verification
Ran `:core:security:jvmTest` and `:core:security:testAndroidHostTest`. 170 tests in `jvmTest` and 33 test suites in `androidHostTest` passed with 0 failures.

### Related files
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.jvm.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.android.kt`

### Status
SUPERSEDED by ERROR-954 — the cached-Cipher workaround is removed; fresh keyed operations pass reseal/parity tests.
