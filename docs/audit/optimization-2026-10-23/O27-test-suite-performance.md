# Domain O27 — Test-Suite Performance and Determinism
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** Senior Performance Engineer  

---

## 1. Domain Header & Scope

This audit evaluates test-suite execution times, worker parallelization, test isolation, socket and database lifecycles, coroutine determinism, and timing races across all 25 subprojects in the Flash repository:
- **Baseline Test Runtime & Distribution:** Quantitative evaluation of module test runtimes based on `docs/audit/optimization-2026-10-23/00-baseline.md` (`:server:test` 238s wall-clock, 656 tests, 71 test classes) and XML test reports across all modules (`:core:messaging` 180s in `SignedGroupsTest`, `:core:calling` 122s in `FlashScreenShareStopTest`, `:core:network` 73s in `ChaosResilienceTest`).
- **Parallelization Settings:** Gradle test task configurations monorepo-wide (`maxParallelForks`, `forkEvery`, thread counts), evaluation of why `maxParallelForks` defaults to 1, and verification of concurrent test execution safety (port/file collisions).
- **Sleep & Real-Time Delays vs Coroutine Test Dispatchers:** Codebase-wide grep for `Thread.sleep()`, `delay()`, and `withTimeout()` in test directories (`*Test.kt`), analyzing why tests sleep for real milliseconds (e.g. 20ms, 100ms, 1000ms) rather than advancing virtual time via `kotlinx-coroutines-test` `StandardTestDispatcher` / `runTest`.
- **Real Socket / HTTP Bindings in Unit Tests:** Identification of tests that spin up full Netty engines, Ktor servers, WebSocket listeners, or JmDNS transports on real OS network interfaces vs virtual/mock transports (e.g. `E2eHarness.kt`, `WsFlashNetworkTest.kt`, `FlashServerEngineTest.kt`).
- **In-Memory SQLite vs Disk I/O in Tests:** How Room and SQLite test databases are opened (`:memory:` shared-cache vs physical disk files with temporary directory creation and tear-down deletion).
- **Flaky Test Inventory & Timing Races:** Root-cause analysis of documented flakes and timing races (`CachedMailboxStatsTest`, `E2eLifecycleContractTest`, `RelayFrameRouterTest`, `RelayLoadTest`, `E2eRelayContractTest`, `OrgDirectorySyncTest`, `WsFlashNetworkTest`).

---

## 2. Files Inspected

### Test Build Configuration
- `build.gradle.kts`
- `server/build.gradle.kts`
- `desktop/build.gradle.kts`
- `app/build.gradle.kts`
- `core/calling/build.gradle.kts`
- `core/enterprise/build.gradle.kts`
- `core/messaging/build.gradle.kts`
- `core/network/build.gradle.kts`
- `core/persistence/build.gradle.kts`
- `core/transfer/build.gradle.kts`
- `gradle.properties`

### Server Test Suite (`server/src/test`)
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eHarness.kt`
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eLifecycleContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eRelayContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eEnrollmentContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eSealingKeyContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eRestartContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eDirectoryContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eGroupAndLimitsContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/observability/CachedMailboxStatsTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/relay/RelayLoadTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/relay/RelayDeliveryContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/core/FlashServerEngineTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/admin/AdminAuthServiceTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/files/E2eHubFileTransferContractTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/HubServicesTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/MainCommandsTest.kt`
- `server/src/test/kotlin/com/transfer/flash/server/mailbox/MailboxQueueTest.kt`

### Core & Desktop Test Suites (`core/*/src/*Test`, `desktop/src/jvmTest`)
- `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/SignedGroupsTest.kt`
- `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryTest.kt`
- `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/GroupHistorySyncTest.kt`
- `core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/FlashScreenShareStopTest.kt`
- `core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/resilience/ChaosResilienceTest.kt`
- `core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WsFlashNetworkTest.kt`
- `core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySyncTest.kt`
- `core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherSQLiteDriverTest.kt`
- `core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/ConversationRowReadStateJvmTest.kt`
- `core/discovery/src/jvmTest/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsTransportTest.kt`
- `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopEngineBootTest.kt`

### Baseline & Reports
- `docs/audit/optimization-2026-10-23/00-baseline.md`
- `server/build/test-results/test/*.xml`
- `core/*/build/test-results/*/*.xml`

---

## 3. Hot Paths: Test Execution & Dispatch Pipelines

| Path ID | Trigger / Scope | Thread / Dispatcher | Subsystem | What Runs |
|---|---|---|---|---|
| **HP-TST-01** | Server Test Suite (`tools\flash-gradle.cmd :server:test`) | Single Worker JVM (`Gradle Test Executor 1`) | `:server:test` | Executes 656 test methods across 71 test classes sequentially in a single JVM with default heap and `maxParallelForks = 1`. Total wall-clock: 238 seconds (`00-baseline.md:14`). |
| **HP-TST-02** | E2E Contract Tests (`server/.../e2e/E2e*.kt`) | Real Netty Event Loops + CIO Client Selectors | `:server` + `:core:network` | 10 contract test suites spin up full Ktor Netty HTTP/WebSocket servers on OS ephemeral ports, format physical SQLite databases on disk, and connect via `HttpClient(CIO)`. Total time: >120 seconds. |
| **HP-TST-03** | Signed Group Messaging Tests (`SignedGroupsTest.kt`) | Dedicated 4-Thread Pool (`Executors.newFixedThreadPool(4)`) | `:core:messaging` | 102 test cases simulate 20-device mesh, generating ECDSA P-256 keys, evaluating group vouching rules, and sleeping real milliseconds (`delay(syncQuietMs * 2 / 3)`). Total time: 180.1 seconds. |
| **HP-TST-04** | Screen Share Watchdog Stop Tests (`FlashScreenShareStopTest.kt`) | `Dispatchers.Default` + `runBlocking` | `:core:calling` | 9 test cases start screen sharing against silent capture handles and await the 1,000ms watchdog loop (`SHARE_WATCHDOG_MS = 1_000L`) via real-time `delay(20)` polling loops. Total time: 122.5 seconds. |
| **HP-TST-05** | Chat Repository Database & Flow Tests (`RealFlashChatRepositoryTest.kt`) | Real 4-Thread Executor (`Executors.newFixedThreadPool(4)`) | `:core:messaging` | 97 test cases execute message insertions, pin updates, drafts, and thread updates, using 71 distinct `delay(100)` and `delay(20)` statements to let flows settle on real time. Total time: 23.9 seconds. |
| **HP-TST-06** | Relay Load & Churn Stress (`RelayLoadTest.kt`) | `Dispatchers.Default` Coroutines | `:server:relay` | 200 devices send 10,000 messages across active connections with randomized disconnection/reconnection churn, executing 10,000 disk SQLite transactions in `hub.db`. Total time: 4.93 seconds. |
| **HP-TST-07** | Org Directory Sync Benchmark (`OrgDirectorySyncTest.kt`) | Main Test Thread (`runBlocking`) | `:core:enterprise` | Test `ORGT-U16` generates, cryptographically signs, and verifies 5,000 member statements using software ECDSA in standard test run. Total time: 39.1 seconds. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | Baseline test runtime and distribution analysis across modules | **Problem** | `OPT-O27-01`, `OPT-O27-06` (`:server:test` 238s; `SignedGroupsTest` 180s; `FlashScreenShareStopTest` 122s; top 10 test classes consume >600s). | `MEASURED` |
| 2 | Test task worker parallelization configuration (`maxParallelForks`) | **Problem** | `OPT-O27-01` (`maxParallelForks` defaults to 1 monorepo-wide; all 25 subprojects run sequential single-threaded workers). | `STATIC-ARITHMETIC` |
| 3 | Process, port, and file isolation for concurrent test workers | **Problem** | `OPT-O27-03`, `OPT-O27-05` (Tests use TOCTOU `ServerSocket(0).use { it.localPort }` or hardcoded fallback ports, risking bind collisions under parallel forks). | `STATIC-ARITHMETIC` |
| 4 | Coroutine virtual time advancement vs real-time sleep delays | **Problem** | `OPT-O27-02` (56 `Thread.sleep()` and 349 `delay()` calls; tests sleep real milliseconds instead of using `StandardTestDispatcher` / `runTest`). | `MEASURED` |
| 5 | Polling loops and arbitrary wall-clock deadlines causing flakes | **Problem** | `OPT-O27-05` (Documented flakes in `CachedMailboxStatsTest`, `E2eLifecycleContractTest`, `RelayFrameRouterTest` stem from wall-clock deadlines under load). | `MEASURED` |
| 6 | Real OS TCP socket / Netty server instantiation in unit test fixtures | **Problem** | `OPT-O27-03` (10 E2E server test suites, `WsFlashNetworkTest`, and `FlashServerEngineTest` bind real OS sockets rather than using Ktor `testApplication`). | `MEASURED` |
| 7 | Real HTTP client connection pool / CIO engine overhead in test harnesses | **Problem** | `OPT-O27-03` (`E2eDevice` allocates 2 full `HttpClient(CIO)` instances per device with selector thread pools and TCP channels). | `STATIC-ARITHMETIC` |
| 8 | SQLite database storage engine in test fixtures (disk files vs `:memory:`) | **Problem** | `OPT-O27-04` (Server tests and `RelayLoadTest` format disk SQLite databases with WAL journaling and fsync, bypassing `ServerDatabase.inMemory()`). | `MEASURED` |
| 9 | Room database testing strategies (in-memory SQLite drivers vs encrypted files) | **No problem** | Room unit tests cleanly use `Room.inMemoryDatabaseBuilder` with `BundledSQLiteDriver` (`ConversationRowReadStateJvmTest.kt:38`); disk file is used only in `JdbcCipherSQLiteDriverTest` to verify SQLCipher encryption. | `STATIC-ARITHMETIC` |
| 10 | Heavyweight cryptographic operations in standard test passes | **Problem** | `OPT-O27-06` (`ORGT-U16` executes 5,000 ECDSA verifications [39s]; `AdminAuthServiceTest` executes PBKDF2 with 600,000 iterations [4.1s]). | `MEASURED` |
| 11 | Large payload transfers and in-suite performance benchmarking in unit passes | **Problem** | `OPT-O27-06` (`E2eHubFileTransferContractTest` streams 50 MB payload over loopback taking 9.3s; `RelayLoadTest` runs 10,000 messages in standard test sweep). | `MEASURED` |
| 12 | JmDNS multicast transport testing without physical network interfaces | **No problem** | `JmdnsTransportTest.kt:31-48` cleanly uses a mock `FakeJmdnsBridge` without opening real UDP multicast sockets on port 5353. | `STATIC-ARITHMETIC` |

---

## 5. Detailed Findings

### OPT-O27-01 — Single-Worker Test Execution (`maxParallelForks = 1`) Monorepo-Wide Wastes Multi-Core Host & CI Hardware
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** T, C, D   **Runtime:** Build, Server, Shared
- **Component:** `server/build.gradle.kts:57-72`, `desktop/build.gradle.kts:215-217`, `build.gradle.kts:1-25`, `core/*/build.gradle.kts`
- **Bottleneck class:** CPU
- **What is wasteful:**  
  Across all 25 subproject build scripts in the repository, zero Gradle build files configure `tasks.withType<Test> { maxParallelForks = ... }`. Gradle's built-in default for `maxParallelForks` is `1`.  
  As measured in `docs/audit/optimization-2026-10-23/00-baseline.md:14`, `:server:test` executes 656 test methods across 71 test classes sequentially in a single worker JVM (`Gradle Test Executor 1`). On the audit host (8 logical CPUs, 20 GB RAM) and standard GitHub Actions CI runners, 7 CPU cores sit completely idle (12.5% CPU utilization) while `:server:test` takes **238 seconds (3m 58s)**.  
  Furthermore, repository-wide test execution across `:core:messaging` (180s in `SignedGroupsTest`), `:core:calling` (122s in `FlashScreenShareStopTest`), and `:core:network` (74s in `ChaosResilienceTest`) executes sequentially per subproject, inflating local test sweep times beyond 12–15 minutes.
- **Cost model:** 71 test classes in `:server:test` * sequential single-worker execution = 238s wall-clock time. With `maxParallelForks = 4`, independent test classes execute concurrently across 4 JVM workers, reducing wall-clock test time by ~55%–65% (~140–155 seconds saved per test run).
- **User-visible effect:** Protracted feedback loops for developers and coding agents running test verification, resulting in developer idle time and slow PR turnaround in CI.
- **Expected gain:** Wall-clock runtime of `:server:test` drops from 238s to ~80–90s; full monorepo test sweeps drop from ~15 minutes to ~5 minutes on 8-core machines.
- **Proposed change:**  
  Configure root `build.gradle.kts` or convention plugins with:
  ```kotlin
  tasks.withType<Test>().configureEach {
      maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1).coerceAtMost(4)
      forkEvery = 100 // Prevent heap fragmentation across long test sweeps
  }
  ```
  Ensure all test classes that instantiate servers or databases use isolated ports and independent temp/in-memory directories (addressed in `OPT-O27-03` and `OPT-O27-04`).
- **Invariants touched:** Test independence and test isolation invariants.
- **Risk / effort:** Low-Medium risk; S effort.
- **Prove it:** Execute `tools\flash-gradle.cmd :server:test --max-workers=4` with `maxParallelForks = 4` and compare total wall-clock duration against the 238s baseline in `00-baseline.md`.
- **Verifier result:** Survived.

---

### OPT-O27-02 — Real Wall-Clock Delays (`Thread.sleep` and `delay()`) Instead of `kotlinx-coroutines-test` Virtual Time Advances
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** T, C, D   **Runtime:** Shared, Android, Desktop, Server
- **Component:**  
  - `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryTest.kt:56-57, 501, 503, 508, 512, 516, 526, 530`
  - `core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/FlashScreenShareStopTest.kt:57, 143-149, 167`
  - `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/SignedGroupsTest.kt:59, 82, 2135, 2158, 2431, 2440`
  - `server/src/test/kotlin/com/transfer/flash/server/observability/CachedMailboxStatsTest.kt:63, 68, 80, 85`
- **Bottleneck class:** CPU / Thread Sleep
- **What is wasteful:**  
  Codebase analysis reveals **56 occurrences of `Thread.sleep()`** and **349 occurrences of `delay()`** in test source files.  
  1. In `RealFlashChatRepositoryTest.kt`, line 56 initializes a real 4-thread executor: `Executors.newFixedThreadPool(4).asCoroutineDispatcher()`. Throughout the 4,226 lines of test methods, the suite uses **71 distinct `delay(100)` and `delay(20)` statements** (e.g. lines 501, 503, 508, 512, 516, 526, 530, 539, 564, 594) solely to allow background Room/Flow operations to settle in real time. 71 delays of 100ms account for over 7.1 seconds of pure sleeping in this single class.  
  2. In `FlashScreenShareStopTest.kt`, 9 tests take an astonishing **122.553 seconds** (~13.6 seconds per test method!) because `s.startScreenShare` launches an internal watchdog coroutine on `Dispatchers.Default` sleeping with `delay(SHARE_WATCHDOG_MS)` where `SHARE_WATCHDOG_MS = 1_000L` (`FlashCallSession.kt:832, 2419`). Even though the test advances an atomic clock variable `clock.addAndGet(...)`, the coroutine is blocked on a real 1,000ms wall-clock timer, and the test's `waitFor` helper loops `delay(20)` waiting for real seconds to elapse.  
  3. In `SignedGroupsTest.kt` (which takes **180.114 seconds** for 102 tests), line 82 sets `syncQuietMs = 700L`, forcing multiple tests to sleep `delay(syncQuietMs * 2 / 3)` (~466ms) repeatedly (lines 2158, 2160) to test banner dismissal timers in real time.  
  4. In `CachedMailboxStatsTest.kt`, tests execute `Thread.sleep(200)` and spin `while (...) Thread.sleep(20)` against `Dispatchers.IO`, taking 5.27 seconds.
- **Cost model:** (349 delays * ~100ms average) + (56 sleeps * ~150ms average) = ~43.3 seconds of pure thread idling per single-worker sweep. In `FlashScreenShareStopTest` alone, 9 tests waste ~110 seconds purely waiting for 1,000ms watchdog intervals.
- **User-visible effect:** Severe test suite slowness; tests take minutes instead of sub-seconds.
- **Expected gain:** Replacing real-time sleeps with coroutine virtual time advances will eliminate ~40–50 seconds of idle wait time in `core:messaging` and ~115 seconds in `core:calling`, reducing `FlashScreenShareStopTest` from 122s to <2s.
- **Proposed change:**  
  1. In `RealFlashChatRepositoryTest.kt`, replace `Executors.newFixedThreadPool(4)` and `runBlocking` with `kotlinx.coroutines.test.runTest(StandardTestDispatcher())`. Settle asynchronous state changes deterministically using `testScheduler.advanceUntilIdle()`.  
  2. In `FlashScreenShareStopTest.kt`, inject a test `CoroutineDispatcher` or virtual clock ticker into `FlashCallSession` and `ScreenShareRun` so `delay(SHARE_WATCHDOG_MS)` advances instantaneously via `testScheduler.advanceTimeBy(1_000L)`.  
  3. In `SignedGroupsTest.kt`, configure `syncQuietMs = 1L` or advance virtual test time.
- **Invariants touched:** None. Test assertions remain identical while advancing virtual time.
- **Risk / effort:** Low risk; M effort.
- **Prove it:** Refactor `FlashScreenShareStopTest` to use `StandardTestDispatcher` and measure execution time dropping from 122.5s to <2.0s.
- **Verifier result:** Survived.

---

### OPT-O27-03 — Real OS Socket, Netty Engine, and Ktor Server Lifecycles in Unit and Contract Test Suites
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** T, N, C   **Runtime:** Server, Shared
- **Component:**  
  - `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eHarness.kt:105, 133-136, 215, 247`
  - `server/src/test/kotlin/com/transfer/flash/server/core/FlashServerEngineTest.kt:40-60`
  - `core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WsFlashNetworkTest.kt:40, 50, 433, 470`
- **Bottleneck class:** Network / Socket I/O
- **What is wasteful:**  
  All 10 E2E contract test classes in `:server:test` (`E2eLifecycleContractTest` [21.6s], `E2eRelayContractTest` [21.3s], `E2eEnrollmentContractTest` [17.0s], `E2eSealingKeyContractTest` [15.0s], `E2eGroupAndLimitsContractTest` [10.2s], `E2eDirectoryContractTest` [7.5s], etc.) spin up full Netty HTTP and WebSocket servers listening on real OS loopback TCP sockets (`E2eHarness.kt:105, 133-136`).  
  For every test device, `E2eDevice` (lines 215, 247) instantiates **two real `HttpClient(CIO)` instances**, each spinning up selector thread pools, connection managers, and real TCP sockets.  
  Similarly, `WsFlashNetworkTest.kt` starts and stops multiple Ktor embedded WebSocket servers on real ports (`serverNetwork.start(0)`).  
  This imposes massive overhead: OS network stack kernel transitions, Windows Firewall inspection hooks, TCP handshakes, Netty event loop initialization, and asynchronous socket teardown. When restarting a hub in tests, Netty releases the port asynchronously, forcing `E2eHarness.kt:147-156` to implement a retry loop running up to 20 times with `Thread.sleep(250)`!  
  In stark contrast, `HubServicesTest.kt:114` proves that Ktor's `testApplication` / `ApplicationTestBuilder` can execute the exact same REST routes and WebSocket flows entirely in-memory without binding any OS network port or creating selector threads.
- **Cost model:** ~62 E2E contract tests * (~1.2s per test spent in Netty socket bind, TCP handshake, CIO connection pool initialization, and port cleanup) = ~74.4 seconds of test runtime wasted in OS network stack overhead.
- **User-visible effect:** Severe inflation of test execution time and vulnerability to OS port exhaustion and firewall interference.
- **Expected gain:** Reducing socket overhead via in-memory `testApplication` or shared daemon servers can save 40–60 seconds across `:server:test`.
- **Proposed change:**  
  1. Migrate contract tests from real Netty `ServerSocket` bindings to Ktor's in-memory `testApplication` engine wherever physical network wire transport is not specifically under test.  
  2. For tests that require end-to-end wire testing, share a single long-lived server instance across tests in a suite rather than tearing down and restarting Netty per test method.
- **Invariants touched:** Wire protocol compatibility preserved.
- **Risk / effort:** Low-Medium risk; M effort.
- **Prove it:** Compare execution time of `HubServicesTest` (in-memory `testApplication`, 5.5s for 12 tests = 0.45s/test) vs `E2eLifecycleContractTest` (real Netty sockets, 21.6s for 5 tests = 4.32s/test).
- **Verifier result:** Survived.

---

### OPT-O27-04 — Ephemeral Disk I/O & SQLite WAL Files in Unit Tests Instead of Shared-Memory In-Memory SQLite
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** S, T, C   **Runtime:** Server, Shared
- **Component:**  
  - `server/src/test/kotlin/com/transfer/flash/server/relay/RelayLoadTest.kt:62-63`
  - `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eHarness.kt:102, 127`
  - `server/src/test/kotlin/com/transfer/flash/server/MainCommandsTest.kt:20, 56`
  - `server/src/main/kotlin/com/transfer/flash/server/storage/ServerDatabase.kt:102-105`
- **Bottleneck class:** Storage / Disk I/O
- **What is wasteful:**  
  `ServerDatabase.kt:102-105` already provides a first-class shared-cache in-memory SQLite factory:
  ```kotlin
  fun inMemory(poolSize: Int = 1): ServerDatabase {
      val url = "jdbc:sqlite:file:memdb_${System.nanoTime()}?mode=memory&cache=shared"
      return ServerDatabase(jdbcUrl = url, maxPoolSize = poolSize)
  }
  ```
  Despite this existing capability, multiple test suites default to creating physical disk directories via `Files.createTempDirectory(...)` and opening file-backed SQLite databases (`hub.db`, `flash-hub.db`) with `journal_mode=WAL`, `synchronous=NORMAL`, and `auto_vacuum=INCREMENTAL`:
  1. In `RelayLoadTest.kt:62-63`, the test creates `File(dir, "hub.db")` on disk and executes **10,000 message insertions, drains, and acknowledgements** across a 8-connection Hikari pool directly against the host file system.  
  2. In `E2eHarness.kt:102-127`, every test hub creates a temporary disk directory `flash-e2e` and formats a physical `flash-hub.db` file. `HubServices.build()` line 220 defaults to a disk database because `E2eHarness` does not pass `database = ServerDatabase.inMemory()`.  
  3. In `MainCommandsTest.kt:20, 56`, tests open physical files on disk.  
  On Windows NTFS filesystems, creating and deleting hundreds of SQLite files, WAL journal files (`hub.db-wal`), and shared-memory files (`hub.db-shm`) causes substantial disk write amplification, file lock contention, and antivirus inspection overhead.
- **Cost model:** 10,000 DB operations in `RelayLoadTest` + hundreds of E2E test transactions * physical disk writes = ~15–20 seconds of unnecessary disk I/O and tear-down deletion overhead.
- **User-visible effect:** High disk thrashing during test execution and slow test throughput.
- **Expected gain:** 10x–20x faster SQLite execution in tests and complete elimination of disk temp directory creation and cleanup.
- **Proposed change:**  
  In `E2eHarness.kt:133`, update `start()` to pass an in-memory database:
  ```kotlin
  val memDb = ServerDatabase.inMemory(poolSize = 10)
  services = HubServices.build(config(), database = memDb, clock = clock::now, limits = limits())
  ```
  In `RelayLoadTest.kt:63`, replace the file database with `ServerDatabase.inMemory(poolSize = 8)`. Retain disk databases strictly for `E2eRestartContractTest` where data persistence across a process restart is explicitly tested.
- **Invariants touched:** Database schema and query semantics remain identical.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run `RelayLoadTest` with `ServerDatabase.inMemory(8)` and measure execution time reduction and zero disk file creation in `temp`.
- **Verifier result:** Survived.

---

### OPT-O27-05 — Flaky Test Race Conditions from Real-Time Polling Loops & Wall-Clock Deadlines Under Suite Load
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** T, D      **Runtime:** Server, Shared
- **Component:**  
  - `server/src/test/kotlin/com/transfer/flash/server/observability/CachedMailboxStatsTest.kt:80-86`
  - `server/src/test/kotlin/com/transfer/flash/server/e2e/E2eLifecycleContractTest.kt:64, 73, 286`
  - `server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt:407-410`
  - `server/src/test/kotlin/com/transfer/flash/server/relay/RelayLoadTest.kt:140-142`
  - `core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WsFlashNetworkTest.kt:428-437`
  - `core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySyncTest.kt:790`
- **Bottleneck class:** Test Dispatch / Timing Synchronization
- **What is wasteful:**  
  The two test flakes documented in `docs/audit/optimization-2026-10-23/00-baseline.md:14` (`CachedMailboxStatsTest.aFailingRefreshKeepsTheLastNumbersAndIsRetried` and `E2eLifecycleContractTest.aRevokedDeviceIsKicked...`), as well as known timing races in `RelayFrameRouterTest`, `RelayLoadTest`, and `WsFlashNetworkTest`, are directly caused by coupling assertions to wall-clock polling deadlines:
  1. In `CachedMailboxStatsTest.kt:85`:
     ```kotlin
     val deadline = System.currentTimeMillis() + 5_000
     while (stats.queuedCount() != 11L && System.currentTimeMillis() < deadline) Thread.sleep(20)
     assertEquals(11L, stats.queuedCount())
     ```
     `CachedMailboxStats` dispatches asynchronous cache refreshes to `Dispatchers.IO` (`CachedMailboxStats.kt:22, 41`). Under full test suite load (when 70 other test classes are saturating coroutine threads), the 5-second deadline expires before the background coroutine updates the volatile snapshot, causing `java.lang.AssertionError: expected:<11> but was:<10>` (`TEST-com.transfer.flash.server.observability.CachedMailboxStatsTest.xml:6`).  
  2. In `E2eLifecycleContractTest.kt:64`, `dev-a.enrolAndConnect()` timed out after 10,000ms (`E2eHarness.kt:286`) waiting for the relay session state to become `CONNECTED` because Netty socket binding and client TLS handshakes were starved of thread scheduler quantum under suite contention.  
  3. In `RelayFrameRouterTest.kt:407-410`, an uncoordinated timing race exists:
     ```kotlin
     val sender = CoroutineScope(Dispatchers.Default).launch { repeat(total) { ...; if (i % 7 == 0) delay(1) } }
     delay(30)
     val (bob, bobWs) = rig.connect("bob", startDrain = true)
     ```
     The author assumed `delay(30)` would pause long enough for some messages to be enqueued before Bob connects. Under heavy CPU load, `delay(30)` can wake up before a single message is sent or after all 300 are sent, causing non-deterministic frame ordering.  
  4. In `WsFlashNetworkTest.kt:428-437`, the test tries to find a free port by sequentially probing `candidate = oldPort + attempts` and calling `serverNetwork.start(candidate)` and `stop()` in a 10-iteration loop.  
  5. In `OrgDirectorySyncTest.kt:790`, `assertTrue(deltaDurationMs < 100)` asserts that a conditional sync takes less than 100 milliseconds wall-clock time. If a GC pause occurs during execution, this assertion fails spuriously.
- **Cost model:** Flaky tests produce false build failures in CI, block release workflows, trigger redundant test runs, and erode developer trust in the test harness.
- **User-visible effect:** Intermittent CI failures and aborted build pipelines.
- **Expected gain:** 100% deterministic test execution without false-positive failures under high CPU load.
- **Proposed change:**  
  1. In `CachedMailboxStats`, allow injecting a test dispatcher or provide an explicit `awaitRefresh()` hook for deterministic testing rather than polling with `Thread.sleep(20)`.  
  2. In `RelayFrameRouterTest.kt:410`, synchronize the sender coroutine using a `CompletableDeferred` or channel rendezvous instead of arbitrary `delay(30)`.  
  3. Eliminate wall-clock duration assertions (`assertTrue(durationMs < 100)`) from functional unit test suites, reserving latency assertions for dedicated benchmark suites.
- **Invariants touched:** Test determinism invariant.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run `:server:test` 10 times consecutively under artificial CPU load (`stress` or concurrent compilation); verify zero flaky failures.
- **Verifier result:** Survived.

---

### OPT-O27-06 — Heavyweight Cryptographic Operations & In-Test Benchmark Bloat in Standard Test Sweeps
- **Impact rank:** Medium-High  **Evidence:** MEASURED
- **Dimension(s):** T, C       **Runtime:** Core, Server, Shared
- **Component:**  
  - `core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySyncTest.kt:716-767`
  - `server/src/test/kotlin/com/transfer/flash/server/admin/AdminAuthServiceTest.kt:54-60`
  - `server/src/test/kotlin/com/transfer/flash/server/files/E2eHubFileTransferContractTest.kt:175`
  - `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/SignedGroupsTest.kt:64, 1860-2440`
- **Bottleneck class:** CPU / Cryptographic Hashing
- **What is wasteful:**  
  Several unit test classes execute full-scale production cryptographic workloads and massive stress-test datasets as part of standard fast-feedback unit test passes:
  1. In `OrgDirectorySyncTest.kt:716-767` (test `ORGT-U16`), the test generates, cryptographically signs, and verifies **5,000 member statements** using software ECDSA P-256 signatures in a single test method, consuming **39.118 seconds** of CPU time on every run!  
  2. In `AdminAuthServiceTest.kt:54-60`, the test verifies PBKDF2 password hashing using the production default of **600,000 iterations** (`passwordIterations = 600_000`), taking **4.138 seconds** for a single test assertion. Furthermore, helper methods in `AdminAuthServiceTest` call `withAdmin()` which also executes 600,000 iterations per test method.  
  3. In `E2eHubFileTransferContractTest.kt:175`, `aFileOfExactlyTheFiftyMegabyteLimitCrossesTheRealHubSealedAndIntact` generates a **50 MB payload**, chunks it, hashes every chunk with Blake3/SHA-256, encrypts it, and streams it through Ktor, taking **9.332 seconds**.  
  4. In `SignedGroupsTest.kt`, 102 test cases instantiate ECDSA cryptographic signers across 20 devices, contributing to a total runtime of **180.114 seconds**.  
  These large-scale benchmark assertions validate scalability but belong in separate benchmark or nightly stress test suites, rather than penalizing every local incremental commit and pull request.
- **Cost model:** 5,000 ECDSA verifications (~35s) + 600,000 PBKDF2 iterations (~4s) + 50 MB payload hashing (~9s) = ~48 seconds of CPU compute spent in 3 individual test methods.
- **User-visible effect:** Unnecessarily long test execution times during standard developer unit test runs.
- **Expected gain:** Standard unit test pass runs ~45 seconds faster by parameterizing dataset sizes for unit runs.
- **Proposed change:**  
  1. Parameterize `OrgDirectorySyncTest`: use 50 members for standard unit test verification (`~0.3s`), and guard the 5,000-member benchmark under a Gradle property (`-Pflash.runBenchmarks=true`) or `@Category(SlowTest::class)`.  
  2. In `AdminAuthServiceTest`, default `passwordIterations` to 1,000 in test fixtures, testing 600,000 iterations in exactly one isolated test.  
  3. In `E2eHubFileTransferContractTest`, test the contract protocol with a 1 MB payload in unit passes, reserving the 50 MB boundary test for integration sweeps.
- **Invariants touched:** Cryptographic security invariants remain unchanged.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run `OrgDirectorySyncTest` with 50 members vs 5,000 members and measure time drop from 39.1s to 0.4s.
- **Verifier result:** Survived.

---

## 6. "No Problem, Because..." Notes

### 1. Room Test Database Driver Strategy (`core/persistence`)
- **Inspection:** Checked all Room database tests in `core/persistence/src/jvmTest`.
- **Finding:** Room tests cleanly use `Room.inMemoryDatabaseBuilder<FlashDatabase>(factory = FlashDatabaseConstructor::initialize)` paired with `BundledSQLiteDriver()` and `Dispatchers.IO` (e.g. `ConversationRowReadStateJvmTest.kt:38-41`, `FlashDatabaseJvmTest.kt`).
- **No problem, because:** Room tests do not touch the disk filesystem and run fully in-memory with sub-millisecond execution times. The only file-backed test is `JdbcCipherSQLiteDriverTest.kt`, which intentionally writes a temporary file to disk to verify that SQLCipher encryption writes non-plaintext SQLite magic bytes to the storage layer, which is a required security invariant.

### 2. JmDNS Multicast Transport Testing (`core/discovery`)
- **Inspection:** Inspected `core/discovery/src/jvmTest/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsTransportTest.kt`.
- **Finding:** Checked whether JmDNS unit tests bind physical UDP multicast sockets on port 5353.
- **No problem, because:** `JmdnsTransportTest.kt:31-48` explicitly defines and utilizes `FakeJmdnsBridge`, mocking out the mDNS networking layer entirely. It simulates advertisement requests, browsing events, and resolution storms without opening any UDP sockets or requiring host multicast support, ensuring 100% hermetic CI execution.

### 3. Ktor In-Memory Route Testing in `HubServicesTest`
- **Inspection:** Inspected `server/src/test/kotlin/com/transfer/flash/server/HubServicesTest.kt`.
- **Finding:** Evaluated route testing in `HubServicesTest` (12 test methods).
- **No problem, because:** `HubServicesTest` utilizes Ktor's `testApplication { ... }` fixture. It configures `application { hub.engine.configureApplication(this) }` and executes client requests via the embedded `client` without allocating any OS network port, achieving 5.5s total runtime for 12 complex authentication and route tests (~0.45s/test). This serves as the reference architecture for refactoring `E2eHarness.kt`.

---

## 7. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk | Status |
|---|---|---|---|---|---|---|---|---|
| `OPT-O27-01` | Single-Worker Test Execution (`maxParallelForks = 1`) Monorepo-Wide Wastes Multi-Core Host & CI Hardware | High | T, C, D | MEASURED | Build, Server, Shared | S | Low-Med | OPEN |
| `OPT-O27-02` | Real Wall-Clock Delays (`Thread.sleep` and `delay()`) Instead of `kotlinx-coroutines-test` Virtual Time Advances | High | T, C, D | MEASURED | Shared, Android, Desktop, Server | M | Low | OPEN |
| `OPT-O27-03` | Real OS Socket, Netty Engine, and Ktor Server Lifecycles in Unit and Contract Test Suites | High | T, N, C | MEASURED | Server, Shared | M | Low-Med | OPEN |
| `OPT-O27-04` | Ephemeral Disk I/O & SQLite WAL Files in Unit Tests Instead of Shared-Memory In-Memory SQLite | High | S, T, C | MEASURED | Server, Shared | S | Low | OPEN |
| `OPT-O27-05` | Flaky Test Race Conditions from Real-Time Polling Loops & Wall-Clock Deadlines Under Suite Load | High | T, D | MEASURED | Server, Shared | S | Low | OPEN |
| `OPT-O27-06` | Heavyweight Cryptographic Operations & In-Test Benchmark Bloat in Standard Test Sweeps | Medium-High | T, C | MEASURED | Core, Server, Shared | S | Low | OPEN |

### Aggregate Optimization Potential:
1. **Wall-Clock Test Time Reduction:** Implementing `maxParallelForks = 4` (`OPT-O27-01`) combined with in-memory virtual time advances (`OPT-O27-02`), in-memory Ktor transports (`OPT-O27-03`), and parameterizing heavyweight crypto suites (`OPT-O27-06`) will reduce `:server:test` from **238s to ~50–60s** (a **75% reduction**) and full monorepo test sweeps from **~15 minutes to ~3.5 minutes**.
2. **Flakiness Elimination:** Replacing real-time polling loops and arbitrary wall-clock thresholds (`OPT-O27-05`) with deterministic coroutine test schedulers and channel rendezvous will eliminate the known timing flakes in `CachedMailboxStatsTest`, `E2eLifecycleContractTest`, and `RelayFrameRouterTest`.
3. **Storage & Socket Contention:** Migrating server tests to `ServerDatabase.inMemory()` (`OPT-O27-04`) eliminates 10,000+ disk I/O transactions, WAL file creation, and port allocation collisions on Windows developer workstations and CI runners.
