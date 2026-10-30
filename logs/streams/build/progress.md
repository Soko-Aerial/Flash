# Progress Log — Stream A (Build)

## 2026-10-30 — Build, CI, Distribution Packaging, and Test Suite Optimization

### Worked on
Implemented full optimization programme for Stream A (Build) covering all Batch 1, Batch 2, Batch 3 items and documenting the gated finding per `docs/plans/OPTIMIZATION-PARALLEL-PLAN.md` §9 and `AGENTS.md`.

### Changed

#### Batch 1 (Build Configuration, Daemon, CI, Packaging)
- `gradle.properties`:
  - `OPT-O21-01`: Enabled Gradle parallel execution (`org.gradle.parallel=true`).
  - `OPT-O21-02`: Enabled monorepo-wide Gradle build cache (`org.gradle.caching=true`).
  - `OPT-O21-03`: Increased daemon heap to 4GB with G1GC and soft reference LRU policy (`-Xmx4096m -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=50`).
- `server/build.gradle.kts`:
  - `OPT-O21-04`: Configured parallel test forks (`maxParallelForks = availableProcessors / 2`) and 1GB G1GC test JVM heap for `:server:test`.
- `.github/workflows/ci.yml`:
  - `OPT-O21-05`: Added `test` task to CI test step so standalone JVM module tests (`:server:test`) run in GitHub Actions.
  - `OPT-O21-06`: Upgraded CI JDK setup to JDK 21 and removed `--no-daemon` to preserve JIT and daemon state across steps.
- `gradle/libs.versions.toml` & `build.gradle.kts`:
  - `OPT-O21-07`: Pinned transitive BouncyCastle dependency `bcutil-jdk18on` to 1.81.1, eliminating the 24-hour dynamic version cache eviction.
- `app/build.gradle.kts`:
  - `OPT-O21-08`: Scoped third-party notice generation strictly to release variants (`withBuildType("release")`), saving 3–8s on local debug assemblies.
  - `OPT-O21-10`: Removed redundant Room KSP compiler from `:app` (Room is localized to `:core:persistence`), saving 2–4s per compile.
  - `OPT-O22-01`: Configured ABI splits (`arm64-v8a`, `armeabi-v7a`, `x86_64`) and universal APK.
  - `OPT-O20-05`: Configured `proguard-rules.pro` with R8 log stripping rules for release builds.
- `desktop/build.gradle.kts`:
  - `OPT-O22-04`: Pruned unreferenced JRE modules (`java.naming`, `java.management`, `java.instrument`, `jdk.crypto.cryptoki`) from native distributions.
  - `OPT-O22-05`: Excluded unused Material 2 runtime (`material`, `material-desktop`) from desktop classpath.
- `core/calling/consumer-rules.pro`:
  - `OPT-O22-06`: Narrowed WebRTC R8 keep rules from blanket `-keep class org.webrtc.** { *; }` to explicit `@CalledByNative` entry points and active classes.
- `ui/chat/build.gradle.kts`:
  - `OPT-O22-07`: Removed 4 parked unused dependencies (`ui-tooling-preview`, `activity-compose`, `core-ktx`, `lifecycle-runtime-ktx`).

#### Batch 2 (Composite Build, Desktop Tuning, Benchmark Module)
- `build-logic/`:
  - `OPT-O21-09`: Extracted monolithic `buildSrc` into `build-logic` composite build (`pluginManagement { includeBuild("build-logic") }`), eliminating monorepo-wide script classpath invalidation on build utility changes.
- `desktop/build.gradle.kts`:
  - `OPT-O19-04`: Added desktop JVM flags (`-Xss512k`, `-XX:MaxGCPauseMillis=20`, `-XX:+UseStringDeduplication`, `-XX:+ExitOnOutOfMemoryError`).
  - `OPT-O19-05` (closes `OPT-O01-06` as duplicate): Added `-Xshare:auto` for AppCDS.
  - `OPT-O22-02`: Created configuration-cache-safe `StripSqliteTask` that strips 23 foreign native libraries from `sqlite-jdbc.jar`, reducing JAR size from 17.7 MB to 0.98 MB.
- `:benchmark` module:
  - `OPT-O01-03` / `OPT-O28-01`: Created new `:benchmark` module with Macrobenchmark JUnit4 and UI Automator (`StartupBenchmark.kt`, `BaselineProfileGenerator.kt`).
  - Added initial startup baseline profile rules in `app/src/main/baseline-prof.txt`.

#### Batch 3 (Test Suite Concurrency & Determinism)
- Root `build.gradle.kts`:
  - `OPT-O27-01`: Configured test worker parallelization monorepo-wide (`maxParallelForks = (availableProcessors / 2).coerceAtMost(4)` and `forkEvery = 100`).
  - `OPT-O27-02`: Configured `kotlinx.coroutines.debug=off` for test JVM executions.
  - `OPT-O27-05`: Configured full test failure logging infrastructure (`showStackTraces = true`, `showCauses = true`, `events("failed")`).

#### Gated
- `OPT-O22-03`: BouncyCastle removal recorded as **BLOCKED** in `logs/streams/build/decisions-additions.md` (ADR-230) per plan §8.

### Verification Results
1. `:server:test --rerun-tasks`:
   - Before: 7m 10s (431s running)
   - After: **5m 22s (322s running)** (661 tests executed, 0 failures, 4 parallel test workers).
2. `:app:assembleRelease`:
   - Baseline Fat APK: 54.61 MB.
   - `app-arm64-v8a-release-unsigned.apk`: **17.55 MB** (**67.8% size reduction**).
   - `app-armeabi-v7a-release-unsigned.apk`: **11.65 MB** (**78.7% size reduction**).
   - `app-x86_64-release-unsigned.apk`: **19.03 MB** (**65.2% size reduction**).
3. `:desktop:packageMsi`:
   - Baseline MSI: 123.3 MB.
   - Optimized MSI: **103.55 MB** (**19.74 MB reduction**).
4. Cold `:core:messaging:compileKotlinJvm`:
   - 3m 12s (193s running).
5. Integration Gate (Section 6.3):
   - Command 1 (ten `jvmTest` suites + compile targets): **BUILD SUCCESSFUL in 4m 06s** (down from 4m 43s baseline; 0 failures).
   - Command 2 (three `testAndroidHostTest` suites + `:app:testDebugUnitTest`): **6m 56s** (down from 8m 55s baseline; exactly the 2 pre-existing known baseline failures in `TransfersUiMapperTest`, 0 new failures).

### Remaining
- None for Stream A. Work is fully implemented, verified, and committed on `agent/opt-build`.
- Backlog device checks `PERF-A01` and `PERF-A02` owed to physical device sessions.
