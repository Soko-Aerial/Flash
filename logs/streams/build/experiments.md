# Experiment Log Additions — Stream A (Build)

## EXP-80 — Build Performance, Concurrency, and Binary Footprint Benchmark

### Date
2026-10-30

### Environment
- Host: Windows 11 (build 26100), 8 logical CPUs, 20 GB RAM
- Gradle: 9.5.0, Kotlin: 2.2.10, AGP: 9.3.1
- Daemon JVM: 21.0.10 JBR

### Baseline vs Optimized Measurements

#### 1. Server Test Suite (`:server:test --rerun-tasks`)
- **Baseline (Pre-Optimization):**
  - Config: Single worker JVM (`maxParallelForks = 1`), 2 GB daemon heap, `org.gradle.parallel` disabled.
  - Wall-clock time: **7m 10s (431s running)** (661 tests executed).
- **Optimized:**
  - Config: 4 parallel test workers (`maxParallelForks = 4`), 4 GB G1GC daemon heap, 1 GB test worker heap, `org.gradle.parallel=true`.
  - Wall-clock time: **5m 22s (322s running)** (661 tests executed, 0 failures).
  - **Improvement:** 1m 48s reduction (**25.3% faster** clean run with 100% test pass).

#### 2. Android APK Packaging & Size per ABI (`:app:assembleRelease`)
- **Baseline (Fat APK):**
  - Monolithic Fat APK: **54,610,068 bytes (52.08 MB)** bundling dead x86 and redundant 32/64-bit binaries.
- **Optimized (ABI Splits enabled for arm64-v8a, armeabi-v7a, x86_64):**
  - `app-arm64-v8a-release-unsigned.apk`: **17,551,823 bytes (16.74 MB)** — **67.8% size reduction (37.06 MB saved)**.
  - `app-armeabi-v7a-release-unsigned.apk`: **11,648,531 bytes (11.11 MB)** — **78.7% size reduction (42.96 MB saved)**.
  - `app-x86_64-release-unsigned.apk`: **19,026,675 bytes (18.15 MB)** — **65.2% size reduction (35.58 MB saved)**.
  - `app-universal-release-unsigned.apk`: **54,630,120 bytes (52.10 MB)**.
  - Completely pruned obsolete 32-bit x86 native payload (14.4 MB) from split targets.

#### 3. Desktop Native Installer Size (`:desktop:packageMsi`)
- **Baseline MSI:** **123,299,861 bytes (117.59 MB)**.
- **Optimized MSI:** **103,556,625 bytes (98.76 MB)**.
- **Improvement:** **19.74 MB reduction (16.0% smaller installer payload)**.
- **Component breakdown:**
  - Repackaged `sqlite-jdbc-crypt-stripped.jar`: **976,038 bytes (0.93 MB)** vs 17.72 MB original (**94.5% jar size reduction**, stripping 23 foreign native libraries).
  - Excluded unused Material 2 runtime: saved 1.81 MB uncompressed / 1.63 MB compressed.
  - Pruned dead JRE modules (`java.naming`, `java.management`, `java.instrument`, `jdk.crypto.cryptoki`).

#### 4. Cold Compilation (`:core:messaging:compileKotlinJvm --rerun-tasks`)
- Wall-clock time: **3m 12s (193s running)**.

#### 5. Integration Gate Benchmarks (Section 6.3)
- **Command 1 (Ten `jvmTest` suites + compile targets):**
  - Baseline: 4m 43s
  - Optimized: **4m 06s** (37s faster, 100% green, 0 failures, 0 errors).
- **Command 2 (Three `testAndroidHostTest` suites + `:app:testDebugUnitTest`):**
  - Baseline: 8m 55s
  - Optimized: **6m 56s** (1m 59s faster, exactly the 2 pre-existing known expected failures in `TransfersUiMapperTest`, 0 new failures).
