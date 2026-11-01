# Kernel Stream Error Log

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

### Working fix
In both `PlatformCrypto.jvm.kt` and `PlatformCrypto.android.kt`, wrapped `aesGcmCipher.get()!!.init(...)` in a `try-catch` block catching `InvalidAlgorithmParameterException`. On the rare path where identical parameters are reused consecutively on the same thread, instantiate a fresh `Cipher.getInstance(AES_GCM)`, update the `ThreadLocal`, and initialize it. Normal traffic (monotonic nonces or random IVs) incurs zero provider lookups and zero allocations.

### Verification
Ran `:core:security:jvmTest` and `:core:security:testAndroidHostTest`. 170 tests in `jvmTest` and 33 test suites in `androidHostTest` passed with 0 failures.

### Related files
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.jvm.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.android.kt`

### Status
RESOLVED
