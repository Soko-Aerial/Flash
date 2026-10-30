# Test Backlog Additions — Stream A (Build)

### PERF-A01 — Physical Device Release APK Sideload & Native Architecture Verification
- **Area:** Android Release Packaging & ABI Splits
- **Source:** OPT-O22-01, OPT-O20-05
- **Setup:** Physical Android test device (e.g. Infinix X6882B or target ARM64 device).
- **Steps:**
  1. Build release APK: `tools\flash-gradle.cmd :app:assembleRelease`.
  2. Sideload per-architecture APK: `adb install -r app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk` (or signed build).
  3. Launch Flash and verify Logcat for clean initialization.
- **Pass condition:** App launches without `UnsatisfiedLinkError`, WebRTC voice/video calling initializes, SQLCipher database opens encrypted, and verbose/debug logs are absent from Logcat.
- **Status:** TODO

### PERF-A02 — Cold Start Startup Latency Assertion with Macrobenchmark & Baseline Profile
- **Area:** Startup Observability & ART Profile Compilation
- **Source:** OPT-O01-03, OPT-O28-01
- **Setup:** Connect physical test device with USB debugging enabled.
- **Steps:**
  1. Execute benchmark suite: `tools\flash-gradle.cmd :benchmark:connectedCheck`.
  2. Inspect benchmark JSON report under `benchmark/build/outputs/connected_android_test_additional_output/`.
- **Pass condition:** `StartupBenchmark.startupCold` executes successfully across 5 iterations; P50/P90 startup latency is measured and recorded in benchmark metrics JSON.
- **Status:** TODO
