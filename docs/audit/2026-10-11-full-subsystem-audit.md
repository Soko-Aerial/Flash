# Comprehensive Full-Subsystem Bug Audit Report

**Date:** 2026-10-11  
**Status:** AUDIT COMPLETE  
**Modules Audited:** `:app`, `:desktop`, `:core:common`, `:core:security`, `:core:discovery`, `:core:network`, `:core:transfer`, `:core:messaging`, `:core:calling`, `:core:ptt`, `:core:swarm`, `:core:engine`, `:core:persistence`, `:ui:theme`, `:ui:platform-shims`, `:ui:chat`, `:ui:callui`

---

## 1. Executive Summary & Findings Matrix

This report documents an exhaustive, multi-pass audit across all modules in the Flash workspace. It categorizes 12 verified runtime defects, concurrency hazards, memory/resource leaks, and platform API traps, with exact root causes and remediation recommendations.

| ID | Severity | Module | Area | Description |
|---|---|---|---|---|
| **BUG-01** | **Critical** | [`:app`](file:///c:/Users/KaliOxygen/Downloads/Flash/app) | Transsion Hiber Keep-Alive | `canUseBluetoothTier` checks `bluetooth.active.applist` before BLE scan starts; chicken-and-egg causes dead code fallback to Audio. |
| **BUG-02** | **High** | [`:core:calling`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/calling) | Calling Threading | A-B / B-A lock inversion deadlock between `mediaLifecycleMutex` and `callMediaDispatcher` in **both** group calls and 1:1 calls. |
| **BUG-03** | **High** | [`:core:network`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network) | WebSocket Read Loop | `binaryChannel.trySendBlocking` blocks read loop under disk backpressure; watchdog silence timeout aborts transfers. |
| **BUG-04** | **High** | [`:app`](file:///c:/Users/KaliOxygen/Downloads/Flash/app) | Android 14/15 FGS | Idle mesh discovery service claims `FOREGROUND_SERVICE_TYPE_DATA_SYNC` unconditionally, exhausting 6-hour daily quota. |
| **BUG-05** | **High** | [`:core:messaging`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/messaging) | Coroutine Lifecycle | `RealFlashChatRepository` uses an unmanaged scope not cancelled on engine restart, leaking background jobs & DAOs. |
| **BUG-06** | **Medium** | [`:ui:platform-shims`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims), [`:desktop`](file:///c:/Users/KaliOxygen/Downloads/Flash/desktop) | Path Resolution | Stripping `file:/{1,3}` breaks relative paths on POSIX (Linux/macOS) for URIs with unencoded special characters. |
| **BUG-07** | **Medium** | [`:core:network`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network) | Reconnection Concurrency | Non-atomic `reconnectJobs` replace race and unqualified `remove(deviceId)` leaks reconnect coroutines. |
| **BUG-08** | **Medium** | [`:core:swarm`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/swarm) | Swarm Storage | `SwarmCommand.WritePiece` opens and closes `PartialHandle` on every 64 KB piece without caching. |
| **BUG-09** | **Medium** | [`:ui:chat`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/chat) | UI Lifecycle | Compose `LazyRow` throws `IllegalArgumentException` on duplicate attachment item keys. |
| **BUG-10** | **Medium** | [`:core:ptt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/ptt) | Audio Lifecycle | Native race: `AudioTrack.release()` called on Thread A while worker Thread B is blocking in `AudioTrack.write()`. |
| **BUG-11** | **Low** | [`:core:persistence`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/persistence) | Database Lifecycle | `DatabaseTrimRegistration` never unregisters from `MemoryGovernor` on database close. |
| **BUG-12** | **Low** | [`:desktop`](file:///c:/Users/KaliOxygen/Downloads/Flash/desktop) | Linux Subprocess | `notifySend` blocks caller for 5s, doesn't drain stream, and leaks child process on timeout. |

---

## 2. Detailed Bug Analysis & Remediation Specs

### BUG-01: [Critical] Transsion Hiber Keep-Alive Chicken-and-Egg Dead Code
- **Module**: [`:app`](file:///c:/Users/KaliOxygen/Downloads/Flash/app)
- **File**: [`app/src/main/java/com/transfer/flash/debug/TranssionHiberKeepAlive.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/TranssionHiberKeepAlive.kt#L276-L305) (Lines 276–305, 359–367)
- **Symptom**: On Transsion OEM hardware (Infinix, Tecno, itel), the keep-alive manager unconditionally demotes Tier 1 (`BLUETOOTH`) to Tier 2 (`AUDIO`), initiating silent audio focus playback even when Bluetooth is enabled and fully capable of maintaining screen-off exemption.
- **Root Cause**:
  1. In `canUseBluetoothTier()` (line 300), `activeList.contains(context.packageName)` is checked before any BLE scan is started. Because Android's Bluetooth stack only adds the package to `Settings.Global.getString("bluetooth.active.applist")` *after* a scan is actively running, this check evaluates to `false` 100% of the time on first run.
  2. In `startBluetooth()` (line 360), `scanner.startScan(...)` is invoked, and then `activeList.contains(context.packageName)` is queried *immediately and synchronously*. Because BLE scanner registration is an asynchronous IPC Binder call to `BluetoothManagerService`, `Settings.Global` is not updated instantaneously. Thus, `startBluetooth()` returns `false` and triggers an immediate cascade fallback to `KeepAliveTier.AUDIO`.
  3. `TranssionHiberKeepAliveTest.kt` passed in CI because it injected `bluetoothChecker = { true }`, masking the production defect.
- **Remediation**:
  - In `canUseBluetoothTier()`, check only adapter readiness and scan permissions (`adapter.isEnabled && hasPermission`).
  - In `startBluetooth()`, start the low-power scan and return `true` optimistically.
  - Schedule an asynchronous verification check (500–1000ms later) to verify that `Settings.Global` reflects the package name, falling back to Audio focus only if the package fails to appear after the timeout.

---

### BUG-02: [High] WebRTC Media Thread A-B / B-A Deadlock in 1:1 and Group Calls
- **Module**: [`:core:calling`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/calling)
- **Files**:
  - [`core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt#L2273) (Lines 2273, 1409, 561, 676, 938)
  - [`core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt#L1591-L1600) (Lines 1591–1600, 2144, 2567)
  - [`core/calling/src/jvmMain/kotlin/com/transfer/flash/core/calling/CallThreading.jvm.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/calling/src/jvmMain/kotlin/com/transfer/flash/core/calling/CallThreading.jvm.kt#L20-L25)
- **Symptom**: 1:1 calls or group calls freeze permanently when hangup/teardown races with media start, camera flip, or screen share.
- **Root Cause**:
  - **Acquisition Path A**: `releaseMedia` (in `FlashCallSession.kt:2273` and `FlashGroupCallSession.kt:1591`) acquires `mediaLifecycleMutex.withLock` on a coroutine worker thread, and inside invokes `releaseMediaLocked()`, which calls `onMediaThread { ... }`.
  - **Acquisition Path B**: `startMedia` (line 1409), `openShare` (line 561), `closeShare` (line 676), and `startPreview` (line 938) dispatch to `onMediaThread { ... }` *first*, and inside the media thread block attempt to acquire `mediaLifecycleMutex.withLock`.
  - On Desktop JVM, `callMediaDispatcher` is backed by a single-threaded executor (`Executors.newSingleThreadExecutor`). When Path A holds `mediaLifecycleMutex` while blocked waiting for the media thread, and Path B runs on the media thread blocked waiting for `mediaLifecycleMutex`, the application reaches a fatal deadlock.
- **Remediation**:
  - Standardize lock acquisition order: operations must ALWAYS acquire `mediaLifecycleMutex` *before* entering `onMediaThread`, never from within `onMediaThread`.
  - Alternatively, make `releaseMediaLocked()` execute cleanups on the media thread without blocking the calling thread synchronously while holding `mediaLifecycleMutex`.

---

### BUG-03: [High] WebSocket Read Loop Backpressure Watchdog Silence Timeout Trap
- **Module**: [`:core:network`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network)
- **Files**:
  - [`core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt#L123-L128)
  - [`core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt#L356-L390)
- **Symptom**: During large file transfers over local Wi-Fi, transfers fail with `"watchdog silence timeout"` on low-end devices, slow flash storage, or micro-SD cards.
- **Root Cause**:
  - In `WsSession.onBinaryReceived`, binary data is fed into `binaryChannel` via `binaryChannel.trySendBlocking(data)`.
  - When storage I/O lags behind network ingress, `binaryChannel` (`capacity = BINARY_BUFFER_FRAMES = 128`, ~8 MB) becomes saturated.
  - `trySendBlocking(data)` blocks the `Dispatchers.IO` thread that executes `WsConnection.readLoop()`.
  - Because `readLoop()` is blocked, it stops reading TCP packets, which halts calls to `keepalive.onInbound(nowMs())`.
  - Concurrently, `keepaliveTick()` on the network clock notices `nowMs() - lastInboundAtMs > livenessTimeoutMs` and forcibly tears down the connection.
- **Remediation**:
  - Update keepalive liveness timestamps immediately upon reading frames from the TCP stream before pushing to `binaryChannel`.
  - If `binaryChannel` is full, apply transport-level flow control rather than blocking the read loop, or suspend with timeouts that reset the keepalive watchdog.

---

### BUG-04: [High] Android 14/15 FGS Type `dataSync` 6-Hour Timeout Clock Trap
- **Module**: [`:app`](file:///c:/Users/KaliOxygen/Downloads/Flash/app)
- **File**: [`app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt#L259-L271) (Lines 259–271, 143–164)
- **Symptom**: On Android 14 and 15 (API 34/35), the background mesh discovery service is killed by the OS after 6 hours even when completely idle, exhausting the user's daily data transfer foreground service quota.
- **Root Cause**:
  - `resolveForegroundServiceType()` unconditionally includes `FOREGROUND_SERVICE_TYPE_DATA_SYNC` at startup.
  - Under Android 15 platform rules, `dataSync` FGS is subject to a strict 6-hour cumulative execution limit per 24-hour window.
  - Because Flash claims `dataSync` while idling as a discovery/mesh beacon, the OS consumes the 6-hour quota while 0 transfers are occurring. Once 6 hours elapse, Android invokes `Service.onTimeout()`, killing the background discovery service.
- **Remediation**:
  - Start the idle service with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` only.
  - Dynamically upgrade to `CONNECTED_DEVICE | DATA_SYNC` via `startForeground()` only when `transfers.any { it.state == Transferring || it.state == Verifying }`.
  - Downgrade back to `CONNECTED_DEVICE` once transfers complete or pause.

---

### BUG-05: [High] Unbounded Coroutine Scope & Job Leak in `RealFlashChatRepository` across Engine Restarts
- **Module**: [`:core:messaging`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/messaging), [`:app`](file:///c:/Users/KaliOxygen/Downloads/Flash/app)
- **Files**:
  - [`core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L191)
  - [`app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1117-L1318,L2830-L2850)
- **Symptom**: Network transitions and Wi-Fi disconnect/reconnect cycles cause progressive memory consumption, thread pool exhaustion, and duplicate database operations.
- **Root Cause**:
  - `RealFlashChatRepository` declares `private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())`.
  - In `DiscoveryEngineHolder.kt` and `Flash.kt`, it is instantiated without passing a lifecycle scope.
  - When `DiscoveryEngineHolder.stop()` is invoked, `appScope.cancel()` runs, but `chatImpl` is simply discarded without cancellation.
  - The internal coroutines (outbox drainers, typing indicator expiry timers, presence collection) continue executing in the background indefinitely, leaking Room DAOs and database connections.
- **Remediation**:
  - Implement `AutoCloseable` on `RealFlashChatRepository` with `fun close() { scope.cancel() }`.
  - Pass `appScope` explicitly into `RealFlashChatRepository(..., scope = appScope)` during engine initialization.

---

### BUG-06: [Medium] POSIX / Linux Path Truncation in Desktop URI Resolvers
- **Module**: [`:ui:platform-shims`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims), [`:desktop`](file:///c:/Users/KaliOxygen/Downloads/Flash/desktop)
- **Files**:
  - [`ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.jvm.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.jvm.kt#L170)
  - [`ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt#L295)
  - [`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt#L38)
- **Symptom**: Voice message playback and image thumbnail generation fail on Linux and macOS if the file path contains unencoded characters (such as `[`, `]`, `{`, or spaces).
- **Root Cause**:
  - When `File(URI(uri))` throws a syntax exception, the fallback code executes:
    ```kotlin
    val clean = uri.replaceFirst(Regex("^file:/{1,3}", RegexOption.IGNORE_CASE), "")
    File(clean)
    ```
  - On POSIX, stripping 3 slashes turns `/home/user/audio.wav` into `home/user/audio.wav`, resolving to an invalid relative path. (On Windows, `file:///C:/...` strips 3 slashes to `C:/...`, masking the bug).
- **Remediation**:
  - Preserve the root slash on POSIX:
    ```kotlin
    val clean = if (uri.startsWith("file:///") && !uri.matches(Regex("^file:///[A-Za-z]:.*"))) {
        uri.removePrefix("file://")
    } else {
        uri.replaceFirst(Regex("^file:/{1,3}", RegexOption.IGNORE_CASE), "")
    }
    ```

---

### BUG-07: [Medium] Reconnect Job Leak Race Condition in `WsFlashNetwork`
- **Module**: [`:core:network`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network)
- **Files**:
  - [`core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt#L944-L993)
  - [`core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt#L739-L788)
- **Symptom**: Uncancelable reconnect loops continue attempting connections after a peer is disconnected.
- **Root Cause**:
  - In `scheduleReconnect`, `reconnectJobs.remove(deviceId)` is called non-atomically before `reconnectJobs.put(deviceId, job)`.
  - When the reconnect loop terminates, it invokes `reconnectJobs.remove(deviceId)` unconditionally.
  - If Job A is finishing at the exact moment Job B is launched for the same peer, Job A's completion removes Job B from the map, leaving Job B unreferenced and uncancelable by `disconnect()`.
- **Remediation**:
  - Use `reconnectJobs.remove(deviceId, thisJob)` to ensure only the matching job instance removes itself upon completion.

---

### BUG-08: [Medium] Swarm Driver Storage Open/Close Syscall Churn on Writes
- **Module**: [`:core:swarm`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/swarm)
- **File**: [`core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriver.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriver.kt#L473-L481)
- **Symptom**: High CPU overhead and I/O disk thrashing during swarm file downloads.
- **Root Cause**:
  - In `SwarmCommand.WritePiece`, the driver executes:
    ```kotlin
    storage.openPartial(key, totalSize)?.use { handle ->
        handle.writeAt(offset, cmd.bytes)
    }
    ```
  - For a 1 GB file, this opens and closes `PartialHandle` 16,384 times.
- **Remediation**:
  - Cache open `PartialHandle` instances in `openPartialHandles = SyncMap<String, PartialHandle>()`, mirroring the `openSourceHandles` pattern implemented in `SWM-PERF-07`.

---

### BUG-09: [Medium] Compose `LazyRow` Crash on Duplicate Attachment URIs
- **Module**: [`:ui:chat`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/chat)
- **Files**:
  - [`ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashAttachmentStagingTray.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashAttachmentStagingTray.kt#L93)
  - [`ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt#L349)
- **Symptom**: App crashes with `IllegalArgumentException: Key was already used` if a user selects the same file twice in the composer attachment staging tray.
- **Root Cause**:
  - `items(stagedAttachments, key = { it.uri })` requires keys to be globally unique within the `LazyRow`.
  - If duplicate URIs are staged, Compose throws an exception. Furthermore, `onRemove` filters by `it.uri != item.uri`, unintentionally removing all duplicates when the user deletes one.
- **Remediation**:
  - Use a composite key: `key = { index, item -> "${item.uri}_$index" }` or generate a unique UUID per `FlashShareItemUi`.

---

### BUG-10: [Medium] Native `AudioTrack` Release Race Condition in `PttPlayout.stop()`
- **Module**: [`:core:ptt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/ptt)
- **File**: [`core/ptt/src/androidMain/kotlin/com/transfer/flash/core/ptt/PttPlayout.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/ptt/src/androidMain/kotlin/com/transfer/flash/core/ptt/PttPlayout.kt#L66-L76)
- **Symptom**: Intermittent native crash or `IllegalStateException` in `android_media_AudioTrack.cpp` when stopping Push-To-Talk playback.
- **Root Cause**:
  - `PttPlayout.stop()` calls `track?.release()` on Thread A *before* calling `worker?.join(1000L)`.
  - Meanwhile, Thread B (`worker`) is blocking inside `track.write(pcm, offset, length)`.
  - Releasing a native `AudioTrack` while another thread is executing a blocking native write is an unsafe race condition.
- **Remediation**:
  - Set `running.set(false)`, join the worker thread first (`worker?.join(1000L)`), and only release the track after the worker has exited cleanly.

---

### BUG-11: [Low] DatabaseTrimRegistration Unregistration Gap on Database Close
- **Module**: [`:core:persistence`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/persistence)
- **File**: [`core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt#L115-L139)
- **Symptom**: Subtle memory leak of closed database handles captured in `MemoryGovernor.listeners`.
- **Root Cause**:
  - `DatabaseTrimRegistration.attach()` registers an `isOpen: () -> Boolean` closure with `MemoryGovernor`, but provides no unregistration callback when the database closes.
- **Remediation**:
  - Return a cleanup handle from `attach()` and invoke `MemoryGovernor.listeners.remove(listener)` when the database is closed.

---

### BUG-12: [Low] Subprocess Pipe Hang & Leak in Desktop `LinuxNotifier`
- **Module**: [`:desktop`](file:///c:/Users/KaliOxygen/Downloads/Flash/desktop)
- **File**: [`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/linux/LinuxNotifier.kt`](file:///c:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/linux/LinuxNotifier.kt#L71-L77)
- **Symptom**: Thread stall up to 5 seconds and orphaned `notify-send` child processes on Linux desktop.
- **Root Cause**:
  - `notifySend()` executes `process.waitFor(5, TimeUnit.SECONDS)`. It redirects error stream to input stream but never drains the pipe; if output fills the buffer, `notify-send` hangs. If `waitFor` times out, `destroyForcibly()` is never called.
- **Remediation**:
  - Discard streams via `ProcessBuilder.Redirect.DISCARD`, dispatch CLI notification asynchronously, and call `destroyForcibly()` on timeout.

---

## 3. Recommended Remediation Roadmap

1. **Sprint 1 (System Deadlocks & OEM Defenses)**:
   - BUG-01: Fix `canUseBluetoothTier` chicken-and-egg check in `TranssionHiberKeepAlive.kt`.
   - BUG-02: Fix A-B / B-A lock inversion in `FlashCallSession.kt` & `FlashGroupCallSession.kt`.
   - BUG-03: Decouple WebSocket read loop keepalive from storage backpressure in `WsSession.kt`.
   - BUG-04: Make `FOREGROUND_SERVICE_TYPE_DATA_SYNC` dynamically claimed in `FlashBackgroundService.kt`.
2. **Sprint 2 (Lifecycle Leaks & UI Stability)**:
   - BUG-05: Implement scope lifecycle management on `RealFlashChatRepository`.
   - BUG-06: Fix POSIX path resolution in `FlashAudioPlayer.jvm.kt` & `DesktopHelpers.kt`.
   - BUG-07: Fix `reconnectJobs` race condition in `WsFlashNetwork.kt`.
   - BUG-08: Add write-side `PartialHandle` caching in `SwarmDriver.kt`.
   - BUG-09: Implement composite keys for attachment staging tray in `FlashAttachmentStagingTray.kt`.
   - BUG-10: Join worker thread before releasing track in `PttPlayout.stop()`.
   - BUG-11: Add unregistration callback to `DatabaseTrimRegistration`.
   - BUG-12: Drain streams and clean up timeouts in `LinuxNotifier.kt`.
