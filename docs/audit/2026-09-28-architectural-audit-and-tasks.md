# Architectural Audit, Module Breakdown, and Verified Defect Report

**Date:** 2026-09-28  
**Repository:** [Flash](file:///C:/Users/KaliOxygen/Downloads/Flash) (Android LAN + Wi-Fi Direct Transfer, Messaging & Calling App)  
**Branch:** `dev` @ [`ce7e750`](file:///C:/Users/KaliOxygen/Downloads/Flash)  
**Status:** Audit complete — read-only verification performed across all core libraries, UI libraries, and host apps.

> **2026-09-29 verification:** this audit was written against commit `ce7e750` (mid-PC3). Several claims below are stale
> or wrong at HEAD (`200ef42`+). Read [§6 Verification](#6-verification-2026-09-29) before acting on any task. The desktop
> migration gap (TASK-CORE-PER-1) is fixed (ERROR-080, ADR-055).

---

## 1. Executive Summary & Build State

1. **Broken Desktop Build on `dev` Branch:**
   * **Location:** [`DesktopEngine.kt:603-630`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L603-L630)
   * **Diagnosis:** Executing `./gradlew :desktop:compileKotlinJvm` fails with **11 fatal compilation errors**. Unfinished PC4 presence sharing changes left `DesktopEngine.kt` referencing unimported `java.security.SecureRandom`, unresolved `FlashFingerprint`, and invalid lambda signatures.
2. **Room Database Migration Crash on Desktop:**
   * **Location:** [`JvmFlashDatabaseOpener.kt:68-75`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt#L68-L75)
   * **Diagnosis:** `openFlashDatabase()` builds the Room database with `.build()` **without registering any migrations**. The database schema is currently version 4 (`DATABASE_VERSION` in [`FlashDatabase.kt:101`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabase.kt#L101)). Android registers [`FlashMigrations.ALL`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashMigrations.kt#L18), but the desktop opener registers zero migrations. Any desktop user upgrading with an existing v1/v2/v3 database crashes on startup with `IllegalStateException: A migration from X to 4 was required but not found`.
3. **Hardcoded Windows DPAPI Breaks Linux/macOS Desktop:**
   * **Location:** [`IdentityKeyVault.kt:43`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/IdentityKeyVault.kt#L43) & [`PersistedFlashCrypto.kt:48`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PersistedFlashCrypto.kt#L48)
   * **Diagnosis:** Desktop identity protection defaults unconditionally to `IdentityKeyVault.Dpapi`, which calls JNA `Crypt32Util.cryptProtectData`. On Linux or macOS, JNA fails to load `crypt32.dll`, throwing `UnsatisfiedLinkError` on application startup.
4. **Severe Multiplatform Architecture Leak in `:core:engine`:**
   * **Location:** [`core/engine/build.gradle.kts`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/build.gradle.kts#L84-L125)
   * **Diagnosis:** Although `:core:engine` publishes a JVM artifact, `src/jvmMain` contains only [`PlatformLock.jvm.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/jvmMain/kotlin/com/transfer/flash/core/engine/concurrent/PlatformLock.jvm.kt). The engine facade [`Flash.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt) is trapped in `androidMain`. As a result, Desktop was forced to fork a duplicate 1,410-line engine coordinator ([`DesktopEngine.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt)).
5. **Extreme Code Duplication Across `:core:network` Source Sets:**
   * **Location:** [`core/network/src/androidMain`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws) vs [`core/network/src/jvmMain`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws)
   * **Diagnosis:** 11 files are copied across source sets without a shared `jvmCommon` target (`WsConnection.kt`, `WsTransferClient.kt`, `WsTransferServer.kt`, `WebSocketCodec.kt`, `BoundedSendQueue.kt`, `SecureSocketUpgrader.kt`, `TofuX509TrustManager.kt`, etc.).
6. **Windows Persistence Unit Test Failures:**
   * **Location:** [`FlashSettingsDataStoreTest.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/settings/FlashSettingsDataStoreTest.kt) and [`DiscoveryModeSettingTest.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/settings/DiscoveryModeSettingTest.kt)
   * **Diagnosis:** Executing `./gradlew :core:persistence:allTests` on Windows fails with **12 test failures out of 40 tests** (`Unable to rename ...tmp to ...preferences_pb`) due to Windows file-locking restrictions during DataStore atomic file renames.

---

## 2. Fact-Checking Prior Audit Records

A line-by-line verification against the actual codebase reveals several resolved or inaccurate assertions from older audit documentation:

1. **Claim: "Layout bug AD-3: On desktop, opening a conversation displaces the chat list inside the list pane rather than populating the detail pane."**
   * **Reality:** AD-3 was **already implemented and resolved on 2026-09-18** in [`DesktopShell.kt:1442-1447`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L1442-L1447). `detailPaneContent` explicitly handles rendering `conversationPaneContent()` in the detail pane. The remaining adaptive tasks are AD-2/AD-D3 (draggable splitter), AD-4/AD-D5 (pointer idioms & selection), AD-6/AD-D4 (fold posture), and AD-7/AD-8 (breakpoint resize continuity & verification).
2. **Claim: "S3 — TLS fails open, silently, in all three engines (`app/.../DiscoveryEngineHolder.kt:549`, `core/engine/.../Flash.kt:242`, `desktop/.../DesktopEngine.kt:528`)"**
   * **Reality:** S3 was **resolved in Phase 1 audit remediation** (commits `8e34090` and `14fff33`). In [`Flash.kt:237`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt#L237), [`DiscoveryEngineHolder.kt:489`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L489), and [`DesktopEngine.kt:524`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L524), all engines call [`requireTransportSecurity(...)`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/tls/FlashTlsContextFactory.kt#L12) which throws an unhandled exception if TLS initialization fails, strictly failing closed. Plaintext fallback was completely removed.

---

## 3. Verified Real Vulnerabilities & Architectural Defects

### 3.1. Critical Security Vulnerability: Unauthenticated Desktop Activation IPC (S6)
* **File:** [`SingleInstanceController.kt:137-191`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/SingleInstanceController.kt#L137-L191)
* **Problem:** The activation server listens on `127.0.0.1:<port>` with no session secret or authentication token. Any unprivileged process or malicious script with loopback access can send `SEND <path>` to inject arbitrary files into the user's outgoing Share Target dialog.

### 3.2. Synchronous Blocking TCP Socket Writes Under `writeLock`
* **File:** [`WsConnection.kt:272-286`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt#L272-L286)
* **Problem:** Calls to `WebSocketCodec.writeFrame(output, ...)` block the executing thread inside `synchronized(writeLock)`. When TCP send buffers fill due to network stalls or receiver pause, `writeLock` is held indefinitely. Heartbeat PINGs ([`sendPing`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt#L246)), keepalive ticks ([`keepaliveTick`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt#L198)), and cancellation frames freeze, leading to spurious session timeouts.

### 3.3. Double Disk Read & Redundant SHA-256 Pre-Pass in File Transfers
* **Files:** [`SendPipeline.kt:110`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/SendPipeline.kt#L110) & [`MultiStreamDispatcher.kt:205`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt#L205)
* **Problem:** `resolvedDigest = fileSha256Hex?.let(Sha256::normalizeHex) ?: chunker.hashOnly(source)`. Because `fileSha256Hex` is always passed as null by production callers ([`RealFlashTransferRepository.kt:302`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt#L302)), the entire file is read and hashed before any packet is sent, followed by a second full linear read during chunk streaming.

### 3.4. Broken 2-Stream Transfer Sentinel Value
* **File:** [`RealFlashTransferRepository.kt:58, 301`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt#L58)
* **Problem:** `val resolvedStreams = if (defaultStreams != 2) defaultStreams else transferProfile.streamCount`. The default value is `2`. When a caller explicitly passes `defaultStreams = 2`, the condition evaluates to `false`, overriding the user's setting with `transferProfile.streamCount` (e.g. 4).

### 3.5. Single Video Stream Overwrite in Group Mesh Calling
* **File:** [`FlashGroupCallSession.kt:526`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt#L526)
* **Problem:** `pc.onTrack.collect { ... if (track is VideoStreamTrack) _remoteVideoStreamTrack.value = track }`. Each incoming participant's video track clobbers the previous track in `_remoteVideoStreamTrack`. As a result, the mesh UI can display at most one video stream, despite all peers encoding and decoding video.

### 3.6. Non-Transactional Group Chat & Missing SortOrder Bump
* **File:** [`RealFlashChatRepository.kt:956-994`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L956-L994)
* **Problem:** `sendGroupText` executes raw `messageDao.insert()`, `deliveries.insertAll()`, `draftDao.clear()`, and `outboxDao.enqueue()` without `runInTransaction`. A failure or kill mid-execution leaves orphaned database records. Moreover, unlike direct chat, it neglects to update [`conversationDao.upsert()`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1062) with `sortOrder = now`, so sending group messages fails to re-sort the conversation list.

### 3.7. NSD Registration Leak on Re-Advertise (Root Cause of ERROR-073)
* **File:** [`NsdTransport.kt:302`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt#L302)
* **Problem:** In `advertise()`, `advertiseListener = listener` overwrites the existing listener reference without calling `nsdManager.unregisterService()`. Subsequent unadvertisements only clean up the latest registration, leaving zombie registrations active in system `NsdService`.

### 3.8. `AutoConnector` Monotonic Memory Leak & Stale Deadline Stalls
* **File:** [`AutoConnector.kt:56, 89, 93`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/AutoConnector.kt#L56)
* **Problem:** `waitDeadlines.update { it + (deviceId to until) }` accumulates deadlines without eviction. If an urgent dial was initiated in the past and failed, line 93 evaluates against the stale timestamp from hours ago (`deadline < nowMs()`), skipping the polling loop immediately.

### 3.9. In-Memory Route Eviction Drops Peer Reachability
* **File:** [`DiscoveryRouteBinder.kt:52`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/bridge/DiscoveryRouteBinder.kt#L52)
* **Problem:** `(previousIds - currentIds).forEach { memory.forgetEndpoint(it) }`. If a single mDNS announcement is dropped by Wi-Fi packet loss, Flash immediately wipes the peer's IP and port from memory, disabling on-demand dials until rediscovery.

### 3.10. Non-KMP `:core:ptt` Library
* **File:** [`core/ptt/build.gradle.kts:2`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/ptt/build.gradle.kts#L2)
* **Problem:** `:core:ptt` uses `alias(libs.plugins.android.library)` instead of KMP. It cannot be used on Desktop JVM.

### 3.11. Placebo Hardware Tiering Knobs
* **File:** [`FlashTransferProfile.kt:24-28`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashTransferProfile.kt#L24-L28)
* **Problem:** `maxAdaptiveChunkSizeBytes`, `allowVideoThumbnails`, and `maxImagePreviewDimension` have zero callers or readers outside of unit test assertions.

---

## 4. Module-by-Module Actionable Tasks

### Suite A: Core Libraries (`com.transfer.flash:core-*`)

#### 1. Module [`:core:common`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common)
* **Role:** Multiplatform domain primitives, error models, and performance classification.
* **Tasks:**
  - [ ] **TASK-CORE-COM-1:** Refactor [`FlashPerformanceClassifier.kt:40-88`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceClassifier.kt#L40-L88) to avoid classifying budget 8-core CPUs (e.g. Helio G25/A22) as `HIGH`. Incorporate clock speed, SoC identifiers, or a 100ms startup benchmark.
  - [ ] **TASK-CORE-COM-2:** Clean up placebo knobs in [`FlashTransferProfile.kt:24-28`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashTransferProfile.kt#L24-L28) (`maxAdaptiveChunkSizeBytes`, `allowVideoThumbnails`, `maxImagePreviewDimension`) by either wiring them into codecs or deleting them.

#### 2. Module [`:core:persistence`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence)
* **Role:** Encrypted Room SQLite database, DAOs, outbox, and DataStore settings.
* **Tasks:**
  - [ ] **TASK-CORE-PER-1 (CRITICAL):** Port [`FlashMigrations.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashMigrations.kt) to `commonMain` using Room KMP migration APIs and register them in [`JvmFlashDatabaseOpener.kt:73`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt#L73) to avert fatal desktop migration crashes.
  - [ ] **TASK-CORE-PER-2:** Fix Windows DataStore atomic rename failures in [`FlashSettingsDataStoreTest.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/settings/FlashSettingsDataStoreTest.kt) and [`DiscoveryModeSettingTest.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/settings/DiscoveryModeSettingTest.kt) to make `:core:persistence:allTests` pass on Windows host machines.
  - [ ] **TASK-CORE-PER-3:** Restrict visibility of Room DAOs and entities to `internal`, exposing only repository abstractions.

#### 3. Module [`:core:security`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security)
* **Role:** Identity keys, pairing protocol v2, trust store, and frame cryptography.
* **Tasks:**
  - [ ] **TASK-CORE-SEC-1 (HIGH):** Abstract [`IdentityKeyVault.kt:43`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/IdentityKeyVault.kt#L43) to check OS type dynamically and provide a secret service / fallback keystore on Linux/macOS instead of unconditionally loading Windows DPAPI `Crypt32Util`.
  - [ ] **TASK-CORE-SEC-2:** Bind transfer ID, chunk index, and stream direction into the AAD of [`SecureBinaryFrameCodec.kt:60`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/SecureBinaryFrameCodec.kt#L60) to prevent replay/reordering attacks.
  - [ ] **TASK-CORE-SEC-3:** Eliminate duplicate array copies in [`SecureBinaryFrameCodec.kt:120-121`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/SecureBinaryFrameCodec.kt#L120-L121) using in-place byte buffer offsets.

#### 4. Module [`:core:discovery`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery)
* **Role:** Android NSD and Desktop JmDNS/Multicast discovery.
* **Tasks:**
  - [ ] **TASK-CORE-DISC-1 (HIGH):** In [`NsdTransport.kt:302`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt#L302), ensure any existing `advertiseListener` is unregistered before assigning a new one, eliminating ERROR-073 zombie registrations.
  - [ ] **TASK-CORE-DISC-2:** Filter virtual network interfaces (WSL, Hyper-V, VirtualBox, Tailscale) in [`JmdnsBridge.kt:298`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery/src/jvmMain/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsBridge.kt#L298) to stop advertising unroutable IPs to phone peers.
  - [ ] **TASK-CORE-DISC-3:** Deprecate and remove dead legacy classes [`NsdFlashDiscovery.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdFlashDiscovery.kt) and `LanController.kt`.

#### 5. Module [`:core:network`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network)
* **Role:** WebSocket transport, TLS 1.3 socket upgrade, connection planner, auto-connector.
* **Tasks:**
  - [ ] **TASK-CORE-NET-1 (HIGH):** Unify the 11 duplicate files across `androidMain` and `jvmMain` into a shared `jvmCommon` source set.
  - [ ] **TASK-CORE-NET-2 (HIGH):** Decouple socket writes from callers in [`WsConnection.kt:272-286`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt#L272-L286) using a non-blocking queue (`BoundedSendQueue`) to prevent blocking control frames during bulk data stalls.
  - [ ] **TASK-CORE-NET-3:** Fix leak and stale timestamp evaluation in [`AutoConnector.kt:56, 89, 93`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/AutoConnector.kt#L56).
  - [ ] **TASK-CORE-NET-4:** Implement persistent endpoint caching (DR1) in [`DiscoveryRouteBinder.kt:52`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/bridge/DiscoveryRouteBinder.kt#L52) so temporary discovery dropouts do not immediately drop dialable routes.

#### 6. Module [`:core:transfer`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer)
* **Role:** Multi-stream and single-stream chunked transfer engine, integrity verification.
* **Tasks:**
  - [ ] **TASK-CORE-XFER-1 (HIGH):** Eliminate `chunker.hashOnly` full-file pre-read in [`SendPipeline.kt:110`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/SendPipeline.kt#L110) and [`MultiStreamDispatcher.kt:205`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt#L205). Stream chunks immediately and transmit whole-file checksum upon transfer completion in `FILE_END`.
  - [ ] **TASK-CORE-XFER-2:** Fix sentinel parameter bug in [`RealFlashTransferRepository.kt:58, 301`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt#L58) by changing `defaultStreams: Int? = null`.

#### 7. Module [`:core:messaging`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging)
* **Role:** Direct and group chat repository, message dispatch, outbox scheduler.
* **Tasks:**
  - [ ] **TASK-CORE-MSG-1 (HIGH):** Enclose [`sendGroupText`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L956-L994) in a database transaction (`runInTransaction`) and update [`conversationDao.upsert`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1062) with `sortOrder = now`.
  - [ ] **TASK-CORE-MSG-2:** Decompose monolithic [`RealFlashChatRepository.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt) (2,981 lines) into dedicated managers (`DirectChatManager`, `GroupChatManager`, `OutboxManager`).

#### 8. Module [`:core:calling`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling)
* **Role:** WebRTC 1:1 and mesh audio/video calling.
* **Tasks:**
  - [ ] **TASK-CORE-CALL-1 (HIGH):** Refactor [`FlashGroupCallSession.kt:110, 526`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt#L110) to support `remoteVideoTracks: StateFlow<Map<String, VideoStreamTrack>>` instead of clobbering a single video track.
  - [ ] **TASK-CORE-CALL-2:** Allow H.264 negotiation in [`CallSdp.kt:117`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallSdp.kt#L117) when both peers are Android to leverage hardware acceleration.

#### 9. Module [`:core:ptt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/ptt)
* **Role:** Push-to-talk floor arbitration and voice frames.
* **Tasks:**
  - [ ] **TASK-CORE-PTT-1:** Convert [`core/ptt/build.gradle.kts`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/ptt/build.gradle.kts#L2) to Kotlin Multiplatform, abstracting audio capture to platform shims so desktop can participate in PTT.

#### 10. Module [`:core:engine`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine)
* **Role:** Master facade for third-party consumers.
* **Tasks:**
  - [ ] **TASK-CORE-ENG-1 (CRITICAL):** Move [`Flash.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt) to `commonMain` or provide a JVM implementation so `:core:engine-jvm` provides an actual usable engine facade.
  - [ ] **TASK-CORE-ENG-2:** Wire `FlashPerformanceMode` into [`Flash.kt:233`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt#L233) instead of defaulting to `HIGH`.

---

### Suite B: UI Libraries (`com.transfer.flash:ui-*`)

#### 1. Module [`:ui:theme`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme)
* **Role:** Material 3 design tokens, typography, dark palette, brand animation.
* **Tasks:**
  - [ ] **TASK-UI-THM-1:** Audit Windows high-DPI scaling factors with Compose Multiplatform desktop rendering.

#### 2. Module [`:ui:platform-shims`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims)
* **Role:** Hardware/OS shims for Compose (audio, camera, clipboard, file pickers).
* **Tasks:**
  - [ ] **TASK-UI-SHM-1:** Migrate [`FlashClipboard.kt:39`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashClipboard.kt#L39) to CMP `LocalClipboard` when upgrading past Compose 1.9.

#### 3. Module [`:ui:chat`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat)
* **Role:** Chat screens, message list, bubbles, attachments, reactions, adaptive pane layouts.
* **Tasks:**
  - [ ] **TASK-UI-CHT-1 (AD-2 / AD-D3):** Implement draggable splitter for desktop two-pane layout.
  - [ ] **TASK-UI-CHT-2 (AD-4 / AD-D5):** Implement desktop pointer idioms (right-click menus, hover states, keyboard scroll navigation).
  - [ ] **TASK-UI-CHT-3 (AD-7):** Ensure state continuity across window resize crossing the 840 dp breakpoint.

#### 4. Module [`:ui:callui`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui)
* **Role:** In-call UI and video render surfaces.
* **Tasks:**
  - [ ] **TASK-UI-CAL-1 (HIGH):** Fix thread confinement in [`FlashCallVideoSurface.jvm.kt:181`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui/src/jvmMain/kotlin/com/transfer/flash/ui/calling/FlashCallVideoSurface.jvm.kt#L181) where native WebRTC C++ threads update Compose `frameState.value` directly at 30 fps.

---

### Suite C: Host Applications (`:app` & `:desktop`)

#### 1. Application [`:app`](file:///C:/Users/KaliOxygen/Downloads/Flash/app) (Android)
* **Tasks:**
  - [ ] **TASK-APP-1 (HIGH):** In [`FlashBackgroundService.onDestroy`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt#L95), call `DiscoveryEngineHolder.stopAll()` or tear down network listeners to prevent leaked locks when the service is killed.
  - [ ] **TASK-APP-2:** Decompose monolithic [`MainActivity.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/MainActivity.kt) (2,805 lines) and [`DiscoveryEngineHolder.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt) (2,410 lines).

#### 2. Application [`:desktop`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop) (Desktop JVM)
* **Tasks:**
  - [ ] **TASK-DSK-1 (CRITICAL):** Fix compile errors in [`DesktopEngine.kt:603-630`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L603-L630) to restore desktop buildability.
  - [ ] **TASK-DSK-2 (CRITICAL / S6):** Secure IPC activation server in [`SingleInstanceController.kt:137-191`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/SingleInstanceController.kt#L137-L191) with a random, per-session authentication token written to a user-restricted directory (`0600`).

---

## 5. Unresolved Questions & Testing Gaps

1. **Android Physical Device Multi-Peer Verification (Owner Gate P8):**
   * While unit tests and loopback tests pass, full LAN transfers and mesh calls must be validated on physical Android hardware across distinct Android versions (specifically Android 8.1, 11, 14, and 15).
2. **Aggressive OEM Screen-off Freeze Verification (ERROR-074):**
   * Transsion XOS "Hiber" and Xiaomi MIUI aggressively suspend processes 10 seconds after screen-off despite foreground service state. Further on-device testing is needed to confirm whether TCP keepalives survive deep sleep or if reconnect storms occur.
3. **Cross-Platform Linux Desktop Key Vault:**
   * Because `IdentityKeyVault.Dpapi` is Windows-specific, CI on Linux is blocked. Investigation is required to evaluate whether `libsecret` / Secret Service API via DBus or an encrypted PKCS#12 file with a user passphrase should serve as the Linux desktop credential provider.
4. **Adaptive UI Quality Gate (AD-8):**
   * Owner visual verification of the desktop two-pane layout, phone bottom navigation, and tablet navigation rail remains pending.

---

## 6. Verification (2026-09-29)

Checked by reading the code at HEAD (`200ef42` plus the ERROR-080 change). "Not run" means no device or stress test was
performed: a code-reading verdict says what the code does, not how often it bites.

**Verdicts:** REAL = confirmed, still open. FIXED = confirmed and fixed 2026-09-29. STALE = was true at `ce7e750`, not at
HEAD. OVERSTATED = a true observation with the wrong severity or consequence. BY DESIGN = intentional and recorded.
NOT CONFIRMED = the code does not match the claim.

| Claim | Verdict | Evidence / what the code does |
|---|---|---|
| §1.1 / TASK-DSK-1 desktop does not compile (11 errors) | **STALE** | `SecureRandom`/`FlashFingerprint` are imported in `DesktopEngine.kt`; a **forced** `:desktop:compileKotlinJvm --rerun` succeeds and `:desktop:jvmTest` passes 91/91. Written mid-PC4 edit. |
| §1.2 / TASK-CORE-PER-1 desktop registers no migrations | **FIXED** (was real but latent) | `openFlashDatabase` had no `addMigrations`. Desktop first opened its DB on 2026-09-16 at v4, so no older desktop file exists; the crash would start at the next bump. ERROR-080, ADR-055. |
| §1.3 / TASK-CORE-SEC-1 DPAPI only | **BY DESIGN** | Windows-only desktop, ADR-035; CI on Linux is deferred for this reason. True that Linux/macOS would fail. |
| §1.4 / TASK-CORE-ENG-1 `Flash.kt` is androidMain-only | **REAL, known** | `DesktopEngine` is deliberately not a `FlashEngine` (recorded in the migration notes). Design debt, not a defect. |
| §1.5 / TASK-CORE-NET-1 11 duplicated network files | **BY DESIGN** | 10 are byte-identical except a note ("D1 = Option B forbids a shared JVM tier", CONVENTIONS R5); `WsTransferClient` is intentionally adapted (no `ConnectivityManager`). Drift risk is real. |
| §1.6 / TASK-CORE-PER-2 Windows DataStore test failures | **REAL, known** | Same 12 tests: `Unable to rename …preferences_pb.tmp` (Windows file locking). Unchanged. |
| §3.1 / S6 unauthenticated activation IPC | **REAL, OVERSTATED** | Loopback port, no token. But `SEND <path>` only raises the window and queues paths in the share dialog (`pendingFilesToShare`); the user still picks the recipient. Low–medium (focus steal, social engineering), not critical. Fix: a random token in the port file. |
| §3.2 blocking write under `writeLock` | **REAL (structure), impact not run** | `send()` and `close()` both hold `writeLock` around a blocking `writeFrame`. `close()` writes its CLOSE frame under that lock **before** `socket.close()`, so the call that would unblock a stuck write queues behind it. The listener is notified first, so the session is logically closed. `BoundedSendQueue` is unused. |
| §3.3 double disk read + hash pre-pass | **BY DESIGN** | `FILE_START` carries the whole-file digest, so it must exist first (Chunker KDoc "Whole-file hash strategy"; same as LocalSend). The audit's fix (digest in `FILE_END`) is a wire-protocol change. The cost (one sequential read) is real. |
| §3.4 `defaultStreams != 2` sentinel | **REAL, latent** | Correct reading of line 301. No production caller passes `defaultStreams`; only tests pass 1. Fix (`Int? = null`) is trivial. |
| §3.5 / TASK-CORE-CALL-1 single remote video track | **STALE** | G1 added `remoteVideoTracks` (`PeerTrackTable`); the grid shows one tile per participant. |
| §3.6 / TASK-CORE-MSG-1 `sendGroupText` | **REAL** | No `runInTransaction` (the direct path has one) and no `touchConversation`, which the group media path calls, so a group text neither is atomic nor moves the chat to the top. |
| §3.7 / TASK-CORE-DISC-1 NSD listener overwrite | **PLAUSIBLE, not proven as ERROR-073's cause** | `advertise()` overwrites `advertiseListener` without unregistering. `restartAdvertising` and `stop` do unregister first; only the watchdog re-registration and `startAdvertising` do not. Needs a registration still pending when the watchdog fires. Cheap hardening. |
| §3.8 / TASK-CORE-NET-3 `AutoConnector` leak and stale deadline | **OVERSTATED** | `waitDeadlines` holds one entry per peer id (overwritten, bounded by peers seen). A stale deadline correctly means "no wait in flight": skipping the loop is right. |
| §3.9 / TASK-CORE-NET-4 route eviction | **REAL, known** | Exactly DR1 in `docs/network/DISCOVERY-RESILIENCE-PLAN.md`; planned after group calling. |
| §3.10 / TASK-CORE-PTT-1 `:core:ptt` not KMP | **REAL** | Android-only library plugin. A feature gap (no desktop PTT), not a defect. |
| §3.11 / TASK-CORE-COM-2 placebo knobs | **REAL** | `maxAdaptiveChunkSizeBytes` and `maxImagePreviewDimension` have no readers; `allowVideoThumbnails` is read only by a test. |
| TASK-CORE-SEC-2 constant AAD | **REAL, low** | AAD is the constant `flash-binary-e2e-v<version>`. This is a second layer under TLS, and `ChunkFrame` carries its own indices; changing it is a wire-format break (needs a version bump). |
| TASK-CORE-DISC-2 virtual interfaces in JmDNS | **REAL, effect not run** | Filter is `isUp && !isLoopback && supportsMulticast`; WSL/Hyper-V/VirtualBox adapters pass. |
| TASK-CORE-DISC-3 dead legacy classes | **REAL** | `LanController` (app) has no references; it is the only user of `LanDiscovery`, the only app user of `NsdFlashDiscovery`. `NsdFlashDiscovery` is in a published module: removal changes the surface. |
| TASK-CORE-CALL-2 allow H.264 | **NOT A DEFECT** | VP8-only is deliberate (comment in `CallSdp`; H.264 hit `NullVideoDecoder` on desktop, ERROR-065/066). A proposal, not a bug. |
| TASK-CORE-ENG-2 `Flash.create` defaults to HIGH | **BY DESIGN** | Commented in `Flash.kt` ("no hardware tier"). |
| TASK-APP-1 stop the engine in `onDestroy` | **WRONG advice** | The engine and its locks live in `DiscoveryEngineHolder` on purpose, so that the API 35 six-hour foreground-service timeout does not take the mesh down (see `onTimeout`). Doing this would regress it. |
| TASK-UI-CAL-1 native threads write `frameState` | **NOT CONFIRMED** | No `frameState` exists. Frames go through `frameTick` (`mutableLongStateOf`), written from the frame thread, which Compose supports; the sink guards its buffers with `this`. |
| TASK-CORE-COM-1, SEC-3, PER-3, MSG-2, APP-2, UI-THM-1, UI-SHM-1, UI-CHT-1..3 | **NOT CHECKED** | Subjective (classifier heuristics), performance-only, or refactors/plans. The line counts in MSG-2 and APP-2 were not re-counted. |

### What to do with this list
Worth doing, in this order: §3.6 (small, user-visible), §3.2 (close the socket first), S6 (token), §3.4, §3.7, §3.11.
Do not do: TASK-DSK-1, TASK-APP-1, TASK-CORE-CALL-1, TASK-CORE-CALL-2, TASK-CORE-ENG-2, TASK-CORE-NET-3.
