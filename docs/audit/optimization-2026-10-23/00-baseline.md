# Baseline Performance & Test Execution Timings
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Machine:** Windows 11, 8 CPUs, ~20 GB RAM  

---

## 1. Module Test Suite Wall-Clock Baseline

Executed via `tools\flash-gradle.cmd :server:test` under the build lock (`Global\FlashGradleBuild`).

| Module / Target | Test Count | Passed | Failed | Flakes / Notes | Wall-Clock Time |
|---|---|---|---|---|---|
| `:server:test` | 656 | 654 | 2 | 2 known timing/concurrency flakes when run under full suite load:<br>1. `CachedMailboxStatsTest > aFailingRefreshKeepsTheLastNumbersAndIsRetried` (listed in prompt §4.1)<br>2. `E2eLifecycleContractTest > aRevokedDeviceIsKickedWithinTheBoundSeesRevokedAndCanEnrolAgainOnTheSameInstall` (kick timeout under CPU contention) | **238 seconds (3m 57s)** |

### Build Environment Observations (for O21 / O27)
- **Configuration Cache:** Invalidation occurred on first run due to dynamic version check `cached version information for org.bouncycastle:bcutil-jdk18on:[1.81,1.82) has expired`.
- **Actionable Tasks:** 23 actionable tasks (1 executed, 22 up-to-date). Configuration cache stored successfully at the end of the run.
- **Daemon Memory / Threads:** Execution ran single test worker with JVM `JAVA_TOOL_OPTIONS: -Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix`.
