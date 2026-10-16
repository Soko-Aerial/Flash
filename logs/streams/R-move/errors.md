# Error Log — Stream R-move

Reserved ERROR range: ERROR-600 through ERROR-619.

---

## ERROR-600 — Kotlin Test dependency missing in `:core:common` commonTest

### Date
2026-10-16

### Area
Gradle Build / `:core:common` Multiplatform Test Configuration

### Symptoms
When moving `ReconnectPolicyTest.kt` from `:core:network` to `:core:common` `commonTest`, compilation of `:core:common:jvmTest` failed with `Unresolved reference 'kotlin.test'`.

### Environment
- Kotlin 2.2.0 / Kotlin Multiplatform
- Gradle 9.5
- Host: Windows 11

### Root cause
`:core:common` build file (`core/common/build.gradle.kts`) declared only implementation dependencies for `commonMain` and did not configure `commonTest.dependencies { implementation(kotlin("test")) }`. Previously, tests in `:core:common` resided only in JVM-specific source sets or did not use `kotlin.test`.

### Working fix
Added `commonTest.dependencies { implementation(kotlin("test")) }` to `core/common/build.gradle.kts`.

### Verification
Ran `tools\flash-gradle.cmd :core:common:jvmTest` and `:core:common:testAndroidHostTest`. Both passed cleanly with 100% test execution.

### Related files
- `core/common/build.gradle.kts`
- `core/common/src/commonTest/kotlin/com/transfer/flash/core/common/resilience/ReconnectPolicyTest.kt`

### Status
RESOLVED
