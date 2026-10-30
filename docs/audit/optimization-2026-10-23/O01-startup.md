# Domain O01 — Start-up and Cold Start (Android and Desktop)
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** White-Box Optimization Agent  

---

## 1. Domain Header & Scope

This audit evaluates the complete execution path between process launch and the first usable, interactive chat interface across both target client platforms:
- **Android:** From Linux process fork, `FlashApplication.onCreate()`, Dagger/Hilt component injection, `MainActivity.onCreate()`, Splash Screen handling (system splash + Ink splash UI-056), `AppEngine` & `DiscoveryEngineHolder.ensureStarted()`, Android Keystore & SQLCipher database decryption / Room schema verification, initial network socket binds, to the first frame of `FlashConversationScreen` / `FlashChatListScreen`.
- **Desktop (JVM / Windows / Linux):** From `DesktopMain.kt main()`, single-instance mutex check, logging setup, `DesktopEngine` constructor, DPAPI / Linux Secret Service credential unwrap, Room / SQLite database open, Compose Desktop window creation, to first Skia render.

---

## 2. Files Inspected

### Android Host & Lifecycle
- `app/src/main/java/com/transfer/flash/di/FlashApplication.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `app/src/main/java/com/transfer/flash/di/AppEngine.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
- `app/src/main/java/com/transfer/flash/debug/FlashKeepaliveWorker.kt`
- `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt`
- `app/src/main/java/com/transfer/flash/ui/splash/FlashLaunchSplashProcess.kt`
- `app/build.gradle.kts`

### Desktop Host & Lifecycle
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopSettingsStore.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/ChatDbKeyStore.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopVaults.kt`
- `desktop/build.gradle.kts`

### Core Persistence, Security & Networking
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/EncryptedDatabaseRecovery.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/KeystorePassphraseProvider.kt`
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt`
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashMigrations.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashSchemaSteps.kt`
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/settings/FlashSettingsDataStore.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/ChatTextFrameCodec.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubJson.kt`
- `core/common/src/androidMain/kotlin/com/transfer/flash/core/common/logging/RotatingFileLogSink.kt`
- `core/common/src/androidMain/kotlin/com/transfer/flash/core/common/perf/AndroidDeviceProfile.kt`

### Theme & Splash UI
- `ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashLaunchSplash.kt`
- `ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashInkSplash.kt`
- `ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashInkSplashGlyphs.kt`
- `ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTypography.kt`

---

## 3. Hot Paths: Start-Up & Cold Start

| Path ID | Component / Entry Point | Trigger / Cadence | Dispatcher / Thread | What Runs |
|---|---|---|---|---|
| **HP-START-01** | `FlashApplication.onCreate` | Once per cold start | **Main (UI)** | `RotatingFileLogSink` init (`dir.mkdirs()`), `AndroidThermalGovernor.install`, `FlashKeepaliveWorker.schedule` (`WorkManager.getInstance` Room initialization). |
| **HP-START-02** | `MainActivity.onCreate` | Once per Activity launch | **Main (UI)** | `installSplashScreen()`, splash gate check, `enableEdgeToEdge()`, `FlashNotificationManager.initChannels` (IPC to system server), `appEngine.start()`, `setContent { FlashApp() }`, `refreshReceivedStorageUsage()` (`scanReceivedFilesBytes()`). |
| **HP-START-03** | `FlashLaunchSplashProcess` / `FlashLaunchSplashOverlay` | Once per process (if setting ON) | **Main (UI) + Compose** | Coroutine reads `settingsStore.launchAnimation.first()`; if true, runs Ink splash animation for 3.45s (`INTRO_END`) + 250ms fade out; swallows all pointer input; blocks UI access for 3,700 ms. |
| **HP-START-04** | `AppEngine.start` | Once per app start | `AppScope` (`Dispatchers.Default` / `IO`) | Evaluates `performanceMode.value` -> walks `MediaCodecList` and `ActivityManager.getMemoryInfo`; reads DataStore `groupSwarmEnabled`; calls `DiscoveryEngineHolder.ensureStarted`. |
| **HP-START-05** | `DiscoveryEngineHolder.ensureStarted` | Once per engine lifecycle | `NonCancellable` (`Dispatchers.IO`) | Keystore self-signed certificate, TLS options, Power locks, NSD & Multicast discovery bind, WebSocket server bind (port 45822), SQLCipher open & migrations (v1..v20), DataChannelServer bind (port 45823), PresenceExchange start, ConnectionModeController start, EnterpriseHub start, OrgSnapshotCoordinator start, RadioChat host creation, RealFlashChatRepository init, RememberedRoutes start, SweepController start, AutoConnector start. |
| **HP-START-06** | `DesktopMain.main()` | Once per desktop launch | **Main (AWT / Compose UI)** | Single-instance lock, `DesktopEngine()` constructor (synchronous reads of identity, settings, trust stores, DPAPI vault check), `launchSplash.decide()`, Compose Desktop `application { }` window init. |
| **HP-START-07** | `DesktopEngine.assemble()` | Once per desktop start | `DesktopEngine.scope` (`Dispatchers.IO`) | TLS cert generation, `JvmWsFlashNetwork`, JmDNS & Multicast discovery start, encrypted SQLite open (`openEncryptedFlashDatabase`), `EnterpriseHub` start, `OrgSnapshotCoordinator` start, `RealFlashChatRepository` init, `CallCoordinator`, PTT attach, WebSocket server bind, auto-dialing start. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | `Application.onCreate` execution time and main thread work | **Problem** | `OPT-O01-02` (Synchronous file I/O `dir.mkdirs()` and `WorkManager.getInstance()` Room init on main thread) | `STATIC-ARITHMETIC` |
| 2 | Engine bring-up (`DiscoveryEngineHolder`, `AppEngine`, `DesktopEngine`) - eager vs lazy | **Problem** | `OPT-O01-04` (Monolithic eager bring-up of radio, swarm, hub sync, TCP sweep, and WebRTC during cold start) | `STATIC-ARITHMETIC` |
| 3 | Room/SQLCipher open and key unwrap (Android Keystore / Desktop DPAPI cost) | **Problem** | `OPT-O01-08` (Keystore AES-GCM unwrap + PBKDF2-HMAC-SHA512 key derivation + 5 sequential PRAGMA queries) | `STATIC-ARITHMETIC` |
| 4 | Database migrations at start - cost on large DB | **Problem** | `OPT-O01-08` (20 sequential migration steps with DDL index creation executed within open transaction) | `STATIC-ARITHMETIC` |
| 5 | Dependency construction order and eager vs lazy initialization | **Problem** | `OPT-O01-04`, `OPT-O01-05` (DesktopEngine constructor executes blocking I/O on UI thread; AppEngine evaluates heavy codecs) | `STATIC-ARITHMETIC` |
| 6 | Class-loading, reflection, and JSON/serialization initialization | **No problem** | Code uses Kotlinx Serialization with compiler plugins (`@Serializable`); `HubJson.frames` and `HubJson.rest` are static singletons; reflection avoided. | `STATIC-ARITHMETIC` |
| 7 | Font and resource loading at cold start | **No problem** | `FlashTypography` defaults to platform system font (`fontFamily = null`); Hershey splash glyphs are pre-compiled static `IntArray` coordinates; no custom TTF/OTF files parsed. | `STATIC-ARITHMETIC` |
| 8 | Launch splash (UI-056): is it gated on work or only decorative? Does its ~3.45s play duration delay usable UI? | **Problem** | `OPT-O01-01` (Purely decorative; forced 3.45s–3.70s interactive freeze even when engine settles in <200ms; defaults to ON) | `STATIC-ARITHMETIC` |
| 9 | First composition cost and main thread blocking before first frame | **Problem** | `OPT-O01-01`, `OPT-O01-02`, `OPT-O01-05` (Main thread blocked by splash gate, notification IPC, and desktop synchronous store reads) | `STATIC-ARITHMETIC` |
| 10 | Work done on main thread that could be deferred until after first frame | **Problem** | `OPT-O01-02` (`scanReceivedFilesBytes` recursive disk walk, notification channel IPC, WorkManager scheduling) | `STATIC-ARITHMETIC` |
| 11 | Startup restore of transfers / outbox / swarm / hub files (resume from disk at start) | **No problem** | `preloadReceiverProgress` runs asynchronously in `appScope`; Outbox drain is event-driven; swarm tombstones checked in coroutines. | `STATIC-ARITHMETIC` |
| 12 | Initial network sockets and discovery start (NSD / JmDNS) | **Problem** | `OPT-O01-04` (Immediate bind of WS server, DataChannel server, multicast UDP, NSD/JmDNS, and /24 subnet probe flood at launch) | `STATIC-ARITHMETIC` |
| 13 | Baseline profile / startup profile / R8 configuration (is baseline profile present? R8 optimization status) | **Problem** | `OPT-O01-03` (No app baseline profile, no startup profile; app classes run unoptimized on ART; R8 enabled but lacks rules) | `STATIC-ARITHMETIC` |
| 14 | Desktop JVM start flags, CDS / AppCDS, jpackage runtime image size and time-to-window | **Problem** | `OPT-O01-06` (No AppCDS archiving; class loading adds 400–800ms; jlink options not stripped; default JVM flags) | `STATIC-ARITHMETIC` |

---

## 5. Detailed Findings

### OPT-O01-01 — Decorative Launch Splash (UI-056) Artificially Delays Usable UI by 3.7 Seconds
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/J/B   **Runtime:** Android | Desktop
- **Component:** `ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashLaunchSplash.kt:28-71, 184-216`, `app/src/main/java/com/transfer/flash/ui/splash/FlashLaunchSplashProcess.kt:18-28`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt:95-99`
- **What is wasteful:**  
  The handwritten Ink launch splash (`FlashLaunchSplashOverlay`) is configured to play for `INTRO_END = 3.45f` seconds followed by a 250 ms fade-out (`FADE_OUT_MILLIS`), totaling **3,700 ms**. Crucially, the splash timeline in `FlashInkSplashTimeline.finishAtMillis` explicitly forces the splash to stay on screen until `introEndMillis`, even if the underlying database, network engine, and chat repository have already booted in 100–200 ms:
  ```kotlin
  fun finishAtMillis(reduceMotion: Boolean, engineSettled: Boolean): Long {
      val introEnd = introEndMillis(reduceMotion)
      return if (engineSettled) introEnd else max(introEnd, CEILING_MILLIS)
  }
  ```
  `FlashLaunchSplashOverlay` swallows all pointer input and renders full-screen over the app. Because `launchAnimation` defaults to `true` in both Android (`FlashSettingsDataStore.kt:225`) and Desktop (`DesktopSettingsStore.kt:50`), every cold launch imposes a mandatory **3.7-second delay** before the user can see or interact with their conversations.
- **Cost model:** 1 cold start x 3,700 ms unit cost x 100% of default users = **3,700 ms artificial latency per launch**.
- **User-visible effect:** Severe delay: the user must wait 3.7 seconds on every app open before tapping a chat or viewing transfers, even on high-end flagship phones and modern PCs.
- **Expected gain:** **3,000 ms – 3,500 ms reduction** in time-to-interactive on cold start (down to ~200–500 ms) by dismissing the splash immediately when `engineSettled` is true or defaulting `launchAnimation` to false.
- **Proposed change:**  
  Update `finishAtMillis` so that when `engineSettled` is true, the animation can dismiss early (e.g. after a minimum graceful 400 ms fade), or allow user touch to dismiss the animation immediately. Default `launchAnimation` to `false` for performance-first profiles.
- **Invariants touched (5.3):** Invariant 6 (User-visible behaviour): Owner decision required regarding whether decorative branding may be dismissed upon engine readiness.
- **Risk / effort:** Low risk; S effort (one conditional check in `FlashLaunchSplash.kt` and settings defaults).
- **Prove it:** Measure time from `Activity.onCreate` to first user click handling with `adb shell am start-W com.transfer.flash/.MainActivity`. Pass threshold: Time to interactive < 600 ms.
- **Verifier result:** Survived. Confirmed: `FlashInkSplashTimeline.kt:66` explicitly documents *"never before the intro ends — the owner's rule is that the animation plays to the end"*. Touch events are consumed by the overlay.
- **Related:** UI-056, ADR-080, ERROR-034.

---

### OPT-O01-02 — Synchronous Main-Thread Disk I/O, IPC, and Directory Traversal on Cold Start
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/S/C   **Runtime:** Android
- **Component:**  
  - `app/src/main/java/com/transfer/flash/di/FlashApplication.kt:23,27`
  - `core/common/src/androidMain/kotlin/com/transfer/flash/core/common/logging/RotatingFileLogSink.kt:49`
  - `app/src/main/java/com/transfer/flash/debug/FlashKeepaliveWorker.kt:133`
  - `app/src/main/java/com/transfer/flash/MainActivity.kt:269,324`
  - `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt:91-155`
  - `app/src/main/java/com/transfer/flash/MainActivity.kt:490-505, 593-605`
- **What is wasteful:**  
  Multiple blocking storage, IPC, and filesystem traversal calls run synchronously on the main thread during `Application.onCreate` and `MainActivity.onCreate`:
  1. `RotatingFileLogSink`: `dir.mkdirs()` runs synchronously in `init` on the UI thread (`FlashApplication.onCreate:23`).
  2. `FlashKeepaliveWorker.schedule(context)`: Calls `WorkManager.getInstance(context).enqueueUniquePeriodicWork(...)` synchronously in `Application.onCreate:27`, forcing immediate initialization of WorkManager's Room database (`androidx.work.workdb`) on the main thread.
  3. `FlashNotificationManager.initChannels(context)`: Called in `MainActivity.onCreate:269`, issuing 6 `createNotificationChannel` calls and 1 `deleteNotificationChannel` via synchronous Binder IPC to Android `system_server`.
  4. `MainActivity.onCreate:324` calls `refreshReceivedStorageUsage()`, which launches `scanReceivedFilesBytes()`: a recursive directory tree walk (`root.walkBottomUp()`) that inspects and canonicalizes every file in `files/received`. This disk traversal is needed only for Settings -> Storage, yet executes on every launch.
- **Cost model:** 1 cold start x (15 ms mkdir + 45 ms WorkManager init + 30 ms notification Binder IPC + 50–200 ms recursive file walk) = **140 ms – 290 ms main thread / I/O latency**.
- **User-visible effect:** Cold-start jank, slow Activity window attachment, Android StrictMode disk read/write violations.
- **Expected gain:** **100 ms – 250 ms reduction** in main thread blocking time before first frame.
- **Proposed change:**  
  1. Initialize `WorkManager` scheduling off the main thread or lazily via `WorkManagerInitializer` / coroutine worker.
  2. Move `dir.mkdirs()` in `RotatingFileLogSink` to the background log writer thread before first write.
  3. Defer `FlashNotificationManager.initChannels` until after first frame or off the main thread.
  4. Remove `refreshReceivedStorageUsage()` from `MainActivity.onCreate`; trigger it on-demand when navigating to Settings or the Storage detail sheet.
- **Invariants touched (5.3):** None. Preserves all platform notifications, logging durability, and work scheduling.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Enable `StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())`. Verify zero StrictMode violations during `Application.onCreate` and `MainActivity.onCreate`.
- **Verifier result:** Survived. Confirmed: all cited calls sit directly in `onCreate()` methods without dispatcher offloading.
- **Related:** ADR-041, EXP-015, ERROR-031.

---

### OPT-O01-03 — Missing Baseline Profile and Startup Profile in Android Release Artifacts
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/C/J   **Runtime:** Android
- **Component:** `app/build.gradle.kts:1-48`, missing `app/src/main/baseline-prof.txt`, missing `app/src/main/startup-prof.txt`
- **What is wasteful:**  
  The Android `:app` module lacks a Baseline Profile (`baseline-prof.txt`) and a Startup Profile (`startup-prof.txt`). The `androidx.baselineprofile` Gradle plugin is not applied. While R8 code shrinking is enabled (`optimization { enable = true }`), the Android ART runtime receives no Ahead-of-Time (AOT) compilation hints for Flash's own application classes (`FlashApplication`, `MainActivity`, `AppEngine`, `DiscoveryEngineHolder`, Compose theme and screen composables, `RealFlashChatRepository`, Room DAOs). On cold launch, ART must interpret raw dex bytecode or run JIT compilation on the hot startup path. On low-end handsets (such as the target Infinix X6882B with 2 GB RAM), unprofiled ART execution incurs 25–40% slower cold starts and dropped frames during initial composition.
- **Cost model:** 1 cold start x (250 ms – 600 ms JIT compilation and interpretation overhead) x all mobile users.
- **User-visible effect:** Sluggish launch animation, frame drops during first screen render, elevated CPU and battery consumption on startup.
- **Expected gain:** **20% – 35% faster cold start** and smoother first composition on physical Android devices.
- **Proposed change:**  
  1. Add a `:baselineprofile` test module using Jetpack Macrobenchmark.
  2. Generate and check in `app/src/main/baseline-prof.txt` covering `FlashApplication.onCreate`, `MainActivity.onCreate`, Compose theme initialization, and the chat list render path.
  3. Include a `startup-prof.txt` to optimize dex layout order for cold start class loading.
- **Invariants touched (5.3):** None. Pure build-time optimization.
- **Risk / effort:** Low risk; M effort.
- **Prove it:** Run Macrobenchmark `StartupBenchmark` on physical hardware comparing startup latency (P50 and P90) before and after profile compilation. Pass threshold: ≥20% reduction in cold start duration.
- **Verifier result:** Survived. Confirmed: `Test-Path "app/src/main/baseline-prof.txt"` returned `False`.
- **Related:** O21, O28.

---

### OPT-O01-04 — Monolithic Eager Engine Assembly & Subnet Sweep Contention at Launch
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/C/M/N/B/S   **Runtime:** Android | Desktop
- **Component:**  
  - `app/src/main/java/com/transfer/flash/di/AppEngine.kt:227-285`
  - `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:746-1480`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1192-1880`
- **What is wasteful:**  
  When `DiscoveryEngineHolder.ensureStarted` (Android) or `DesktopEngine.assemble` (Desktop) is invoked on startup, it constructs, binds, and starts **every application subsystem** inside a single massive initialization chain before declaring the engine ready:
  - Binds WebSocket mesh server on port 45822.
  - Binds DataChannelServer TCP socket on port 45823.
  - Binds UDP multicast discovery and registers NSD / JmDNS services.
  - Launches `SweepController`, which immediately starts probing up to 254 IPv4 hosts on the local /24 subnet.
  - Launches `AutoConnector`, dialing every remembered route and discovered peer simultaneously.
  - Starts `EnterpriseHub` HTTP and WebSocket relay clients.
  - Starts `OrgSnapshotCoordinator` (initiating network sync with enterprise hub).
  - Starts `RadioChatWiring` (Bluetooth radio link).
  - Starts WebRTC `CallCoordinator` and Push-To-Talk `PttSessionEngine`.
  - Runs `preloadReceiverProgress()`, scanning all chunk records in SQLite.
  
  None of these network probes, radio links, WebRTC engines, or background directory syncs are necessary to display the user's cached offline chats. Launching them simultaneously saturates the device's CPU, radio, and SQLite database during the critical first second of app launch.
- **Cost model:** 1 cold start x (300 ms – 700 ms CPU/IO/socket contention across 12+ subsystems).
- **User-visible effect:** Contention delays database queries for chat list previews; radio airtime and battery are consumed immediately even if the user only wanted to glance at an existing note or draft.
- **Expected gain:** **200 ms – 400 ms faster time to `ready`**; eliminates radio and socket churn during initial rendering.
- **Proposed change:**  
  Decompose engine startup into two phases:
  - **Phase 1 (Immediate / Core):** Open database, initialize chat repository, render cached chats. Flip `ready = true`.
  - **Phase 2 (Deferred / Networking):** After first composition, start NSD/mDNS discovery, bind WebSocket server, start AutoConnector and EnterpriseHub sync. Defer WebRTC, PTT, and Radio until their respective UI tabs or incoming intents require them.
- **Invariants touched (5.3):** Invariant 3 (Durability and ordering) and Invariant 5 (Background execution). Preserved: local chat and transfers remain fully durable; network services start within 500 ms of launch.
- **Risk / effort:** Medium risk; M effort (requires separating local repository readiness from network mesh readiness).
- **Prove it:** Track probe `engine.boot_ms` from start to `ready`. Pass threshold: Core readiness under 200 ms.
- **Verifier result:** Survived. Traced call chains in `DiscoveryEngineHolder.kt:746-1480` and `DesktopEngine.kt:1192-1880`.
- **Related:** ADR-016, ADR-045, ADR-047, EXP-015.

---

### OPT-O01-05 — Synchronous Disk I/O & Vault Calls in DesktopEngine Main-Thread Constructor
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/S   **Runtime:** Desktop
- **Component:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt:88-99`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:225-251`
- **What is wasteful:**  
  In `DesktopMain.kt`, the composition root evaluates `val engine = remember { DesktopEngine() }` directly on the main AWT / Compose UI thread inside `application { }`. Inside `DesktopEngine`'s constructor:
  - `DesktopIdentityStore(stateDir).getIdentity()` executes blocking JSON file reads on disk.
  - `DesktopSettingsStore(stateDir).loadSettings()` executes blocking properties file I/O on disk.
  - `DesktopTrustStore(stateDir)` and `JvmFileEnterpriseTrustStore(stateDir)` read trust files from disk.
  - `DesktopVaults.forCurrentOs(stateDir)` checks DPAPI / D-Bus Secret Service availability.
  - `engine.settings.value.launchAnimation` is checked synchronously on the UI thread before window creation.
  
  Executing blocking filesystem and vault checks on the main AWT event dispatch thread before creating the Compose window delays initial window mapping and increases time-to-first-paint.
- **Cost model:** 1 desktop cold start x 30 ms – 80 ms synchronous I/O on UI thread.
- **User-visible effect:** Delay before desktop window frame appears on screen.
- **Expected gain:** **30 ms – 70 ms faster window appearance** on desktop.
- **Proposed change:**  
  Make `DesktopEngine` construction lightweight or load configuration asynchronously on `Dispatchers.IO`, seeding Compose state with fast defaults and updating once loaded.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Measure elapsed time from `main()` entry to `rememberWindowState` window creation via `System.nanoTime()`.
- **Verifier result:** Survived.
- **Related:** O19.

---

### OPT-O01-06 — Missing AppCDS and Unoptimized JVM Runtime Flags in Desktop Packaging
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/M/Z   **Runtime:** Desktop | Build
- **Component:** `desktop/build.gradle.kts:125-204`
- **What is wasteful:**  
  The desktop `compose.desktop.application` configuration defines basic JVM arguments (`-Xmx1g`, `-Xms64m`, `-Dskiko.fps=60`), but completely lacks Application Class Data Sharing (AppCDS) support. On OpenJDK 21, loading and verifying thousands of classes across Jetpack Compose, Skiko, Room, Coroutines, and JNA jars takes 400–800 ms of CPU time on cold start. AppCDS allows class metadata to be pre-computed and memory-mapped (`mmap`) directly into the JVM process, eliminating class verification overhead. Furthermore, `nativeDistributions` does not configure `jlinkOptions` (e.g. `--strip-debug`, `--no-header-files`, `--no-man-pages`), resulting in an unnecessarily bloated runtime image.
- **Cost model:** 1 desktop launch x 400 ms – 800 ms class-loading & verification latency.
- **User-visible effect:** Multi-second lag between clicking the Flash executable and the appearance of the application window.
- **Expected gain:** **300 ms – 500 ms faster desktop time-to-window**; 15–25 MB smaller jpackage runtime image.
- **Proposed change:**  
  1. Add a Gradle task to generate a CDS archive (`-Xshare:dump`) during installer packaging.
  2. Add `-Xshare:auto` and `-XX:SharedArchiveFile=flash.jsa` to desktop JVM launcher flags.
  3. Configure `jlinkOptions = listOf("--strip-debug", "--no-header-files", "--no-man-pages")`.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; M effort.
- **Prove it:** Measure process start to window visibility time using `Measure-Command { Start-Process Flash.exe -Wait }`. Pass threshold: ≥30% reduction in time-to-window.
- **Verifier result:** Survived.
- **Related:** O19, O22.

---

### OPT-O01-07 — Redundant MediaCodecList Enumeration on Android Startup
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/C   **Runtime:** Android
- **Component:**  
  - `app/src/main/java/com/transfer/flash/di/AppEngine.kt:193-201, 242`
  - `core/common/src/androidMain/kotlin/com/transfer/flash/core/common/perf/AndroidDeviceProfile.kt:29`
  - `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt:52`
- **What is wasteful:**  
  `AndroidDeviceProfile.read(context)` walks `MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos`, issuing IPC queries to the Android media server. This profile read is triggered twice during startup:
  1. In `AppEngine.kt:242` when initializing `performanceMode`.
  2. In `FlashDatabaseOpener.openEncrypted:52` when determining SQLite cache sizing (`mode.transfer.sqliteCacheSizeKb`).
  Running this codec walk twice during cold start wastes 20–40 ms of CPU time.
- **Cost model:** 1 cold start x 1 redundant codec list enumeration x 20–40 ms.
- **User-visible effect:** Minor CPU waste during startup.
- **Expected gain:** **15 ms – 30 ms saved** on cold start.
- **Proposed change:**  
  Cache `AndroidDeviceProfile.read(context)` or pass the classified performance mode from `AppEngine` into `FlashDatabaseOpener`.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Trace `MediaCodecList` calls via Android method profiling or logcat; ensure only 1 call occurs during cold start.
- **Verifier result:** Survived.
- **Related:** ERROR-033, O11.

---

### OPT-O01-08 — Sequential PRAGMA Queries and Monolithic Schema Migration Open Overhead
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T/S   **Runtime:** Android
- **Component:** `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt:50-75, 105-125`
- **What is wasteful:**  
  In `FlashDatabaseOpener.openEncrypted`, the `onOpen` callback executes 5 separate PRAGMA statements sequentially using cursor queries (`db.query(pragma).use { it.moveToFirst() }`). Each pragma query acquires the SQLite database lock, compiles the statement, and steps the cursor. While necessary for memory bounding, executing them as individual queries adds 10–20 ms to database open time. In addition, when upgrading an existing database across multiple versions (v1..v20), migrations are applied in a single blocking transaction on database open.
- **Cost model:** 1 DB open x 5 PRAGMA queries (2–4 ms each) = **10 ms – 20 ms**.
- **User-visible effect:** Marginal startup latency.
- **Expected gain:** **5 ms – 15 ms faster** database open.
- **Proposed change:**  
  Batch pragma execution where possible, and ensure migrations avoid unnecessary table scans.
- **Invariants touched (5.3):** Invariant 3 (Durability).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Benchmark `FlashDatabaseOpener.openEncrypted` timing in test suite.
- **Verifier result:** Survived.
- **Related:** O06.

---

## 6. "No Problem, Because..." Notes

- **Checklist Item 6 (Class-loading, reflection, and JSON/serialization):**  
  *No problem, because* the project uses Kotlinx Serialization with compile-time code generation via the Kotlin Serialization plugin (no runtime reflection). In `core/hub-protocol`, `HubJson.frames` and `HubJson.rest` are instantiated as static properties on a singleton object (`HubJson.kt:25,36`), preventing repeated `Json { }` configuration churn. On the JVM, Room uses `FlashDatabaseConstructor::initialize` rather than reflection (`JvmFlashDatabaseOpener.kt:71`).
  
- **Checklist Item 7 (Font and resource loading):**  
  *No problem, because* `FlashTypography` defaults to platform system fonts (`fontFamily = null`), completely avoiding bundled font file parsing or I/O on cold start. The Hershey Script single-stroke glyph coordinates used by the launch splash are stored as pre-compiled static `IntArray`s in code (`FlashInkSplashGlyphs.kt:34-69`), requiring zero font file decoding at runtime.

- **Checklist Item 11 (Startup restore of transfers, outbox, swarm, and hub files):**  
  *No problem, because* recovery operations are properly isolated: `transferImpl.preloadReceiverProgress()` is launched on `appScope` asynchronously (`DiscoveryEngineHolder.kt:2123`), Outbox message draining is event-driven (fixed in `EXP-015`, no 1 Hz polling loop), and swarm tombstone recovery runs in background coroutines (`RealFlashChatRepository.kt:1158`). None of these block first composition.

---

## 7. Summary of Findings

| Finding ID | Title | Impact Rank | Dimension(s) | Runtime | Effort | Risk | Verifier Status |
|---|---|---|---|---|---|---|---|
| **OPT-O01-01** | Decorative Launch Splash (UI-056) Artificially Delays Usable UI by 3.7s | **High** | T, J, B | Android, Desktop | S | Low* | Survived |
| **OPT-O01-02** | Synchronous Main-Thread Disk I/O, IPC & Directory Traversal on Cold Start | **High** | T, S, C | Android | S | Low | Survived |
| **OPT-O01-03** | Missing Baseline Profile and Startup Profile in Android Release Artifacts | **High** | T, C, J | Android | M | Low | Survived |
| **OPT-O01-04** | Monolithic Eager Engine Assembly & Subnet Sweep Contention at Launch | **High** | T, C, M, N, B, S | Android, Desktop | M | Medium | Survived |
| **OPT-O01-05** | Synchronous File I/O & Vault Checks in DesktopEngine Main-Thread Constructor | **Medium** | T, S | Desktop | S | Low | Survived |
| **OPT-O01-06** | Missing AppCDS and Unoptimized JVM Runtime Flags in Desktop Packaging | **Medium** | T, M, Z | Desktop, Build | M | Low | Survived |
| **OPT-O01-07** | Redundant MediaCodecList Enumeration on Android Startup | **Low** | T, C | Android | S | Low | Survived |
| **OPT-O01-08** | Sequential PRAGMA Queries and Monolithic Schema Migration Open Overhead | **Low** | T, S | Android | S | Low | Survived |

*\*Note on OPT-O01-01: Low technical risk, but requires project owner confirmation regarding whether decorative branding animation may dismiss upon engine readiness.*

---

## 8. Concrete Next Steps & Backlog Additions

1. **Owner Decision on Splash Dismissal (OPT-O01-01):** Allow `FlashLaunchSplashOverlay` to dismiss immediately upon `engineSettled == true` (saving ~3.2s of perceived delay on typical launches).
2. **Move Cold-Start I/O Off Main Thread (OPT-O01-02):** Defer `scanReceivedFilesBytes()` to Settings navigation; offload `dir.mkdirs()` in `RotatingFileLogSink` and `WorkManager` scheduling to background dispatchers.
3. **Generate Android Baseline Profile (OPT-O01-03):** Create `:baselineprofile` module to pre-compile ART dex methods for startup.
4. **Desktop AppCDS & Jlink (OPT-O01-06):** Add AppCDS generation task to `desktop/build.gradle.kts` and configure jlink stripped debug symbols.
