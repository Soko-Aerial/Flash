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
