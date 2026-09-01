
# Error Log

## ERROR-020 - Backgrounded mesh went offline (REOPENED: real root cause found; RESOLVED — verified on Samsung 2026-09-01; Infinix failure re-attributed to low-battery power policy, see EXP-002)

### Date
2026-08-31 (reopened), 2026-09-01 (physical verification results)

### Area
App lifecycle / `FlashBackgroundService` sticky restart / process death

### Symptoms
Owner report after the first fix: "it still goes offline after a few seconds if I leave the
app and also if I turn screen off". Peers showed this device offline within seconds of
backgrounding; it never came back on its own.

### Environment
- Target SDK: 36
- Device: Infinix X6882B (Transsion), Android 15/16, Android 12+/API 31+ FGS rules apply
- Service type: `connectedDevice`, `START_STICKY`

### Error (captured via `adb logcat`)
```text
08-31 18:43:14.880 E AndroidRuntime: FATAL EXCEPTION: main
08-31 18:43:14.880 E AndroidRuntime: Process: com.transfer.flash, PID: 1880
java.lang.RuntimeException: Unable to create service com.transfer.flash.debug.FlashBackgroundService
Caused by: android.app.ForegroundServiceStartNotAllowedException:
  Service.startForeground() not allowed due to mAllowStartForeground false
  at FlashBackgroundService.startAsForeground(FlashBackgroundService.kt:153)
  at FlashBackgroundService.onCreate(FlashBackgroundService.kt:74)
```
Seven occurrences across 08-29→08-31 (fresh PIDs each time), incl. after the 18:54 reinstall.

### Root cause (actual)
The first fix moved the FGS *launch site* to `MainActivity.onStart` — necessary but not
sufficient. The killer is the **sticky-restart path**: the OEM/Android kills the backgrounded
Flash process → the system restarts the `START_STICKY` service with a null intent while the
app is NOT TOP → `onCreate` called `startForeground()` **unconditionally and uncaught** →
`ForegroundServiceStartNotAllowedException` → FATAL → process death → system restarts the
sticky service again → **crash loop**. The mesh never recovers because every restart dies.
Supporting evidence: `dumpsys wifi` showed the `WIFI_MODE_FULL_LOW_LATENCY` lock held but
`isFg=false, isScreenExempt=false, is_low_latency_activated=false` — confirming the earlier
theory that the WifiLock was doing nothing in the background was right, but that was a
symptom-level concern, not the process-death cause.

### Failed attempts
1. Moving the FGS launch into `MainActivity.onStart` (previous fix) — correct for the
   user-launch path but did nothing for the system's sticky-restart re-entry via `onCreate`,
   which crashed before reaching any other code.
2. WifiLock `WIFI_MODE_FULL_LOW_LATENCY` (and its HIGH_PERF fallback) — retained, but from
   API 34 HIGH_PERF is remapped to LOW_LATENCY, and LOW_LATENCY is only active
   foreground+screen-on. No WifiLock mode keeps the radio powered while backgrounded on
   modern Android; the lock is not part of the fix.

### Working fix (2026-08-31, Bug 6 final)
1. `FlashBackgroundService.startAsForeground()` now returns Boolean and **catches all**
   exceptions (broad catch: OEM framework variants throw more than the documented exception).
2. `onCreate` order changed: `acquireLocks()` + screen receiver + **engine start** run FIRST,
   then the foreground promotion is attempted; on refusal it logs and **`stopSelf()`** — the
   mesh engine keeps running in-process (no crash, no crash-restart loop, and the 5-second
   startForeground follow-up obligation is discharged by stopping).
3. User-initiated `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (new permission
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) fired from the Settings "Background transfers"
   toggle so the system stops killing the process in the first place.

### Verification (2026-09-01 — physical, two phones)
- **Samsung SM-G986U1 (~90% battery): PASS.** Screen off / leave app → peer stays online,
  messages arrive, no FGS exceptions. The Bug 6 fix is **physically verified working**.
- **Infinix X6882B (~4% battery): FAIL.** Still goes offline within seconds.
- Differential conclusion: the Infinix failure is **not the fixed bug** — at 4% battery the
  Transsion power manager and/or AOSP battery-saver kills background processes regardless of
  FGS status (battery-saver restrictions supersede app standby buckets and FGS priority per
  official power-management docs). Decisive follow-up = EXP-003: re-test the Infinix charged
  (>20%) with the battery-optimization exemption granted.

### Related files
- `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt` (battery-exemption wiring)
- `app/src/main/AndroidManifest.xml`
- `docs/android-platform-notes.md` (2026-08-31 (b) entry — full dumpsys evidence)
- `logs/experiments.md` (EXP-002 — differential test record)

### Status
RESOLVED (verified on Samsung 2026-09-01; Infinix low-battery behavior tracked in EXP-002/EXP-003)

## ERROR-021 - Uncaught NPE killed the process from the outbox drain loop (drainMutex init order)

### Date
2026-08-31 (captured on device at 10:22; fixed same day)

### Area
`core/messaging` — `RealFlashChatRepository` construction vs coroutine startup race

### Symptoms
```text
E AndroidRuntime: FATAL EXCEPTION: DefaultDispatcher-worker-2
java.lang.NullPointerException: Attempt to invoke interface method
  'kotlinx.coroutines.sync.Mutex.lock(...)' on a null object reference
  at RealFlashChatRepository.drainOutboxOnce(RealFlashChatRepository.kt:1057)
  at RealFlashChatRepository.drainOutboxLoop(RealFlashChatRepository.kt:648)
```

### Root cause
Kotlin initializes properties and `init` blocks in **source order**. The class's `init`
block launched `drainOutboxLoop()` (which reaches `drainMutex.withLock`), while `drainMutex`
was declared ~500 lines BELOW that init block. The coroutine could begin executing on
`Dispatchers.IO` before the constructor finished initializing `drainMutex` → null receiver →
NPE → uncaught coroutine exception → **whole process death** (a second, independent Bug-6
offline path).

### Working fix
Moved the `drainMutex` declaration above the `init` block, with a comment documenting the
ordering constraint so nobody "tidies" it back down the file. All other constructor params
with defaults keep existing call sites source-compatible.

### Verification
`RealFlashChatRepositoryTest` full class → `failures="0"`, including the outbox-drain tests
and the two new inbound-callback tests added for Bug 7.

### Related files
- `core/messaging/src/main/java/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`

### Status
RESOLVED (code-level; covered by the same physical re-test as ERROR-020)

## ERROR-015 - WS mesh transfer: receiver assembles files by append order; silent frame drops; handshake/glare races (RESOLVED)

### Date
2026-08-24

### Area
`:core:network/ws` + `:core:transfer/chunked|multistream` + Dev Console wiring (`DiscoveryEngineHolder`)

### Symptoms (sender log, physical devices)
- Sender streamed all ~160 chunks of the 10MB test payload, ACKs returned ("consumed by sender dispatcher"), yet the received file on the receiver was unusable.
- Trailing `Receiver rejected chunk frame: reason=UNEXPECTED_DIRECTION` warnings for late ACK/COMPLETE frames after sender resolution.

### Root causes (found by code review of the WS swap, ADR-016)
1. **Corrupt assembly:** `DiscoveryEngineHolder`'s `ChunkSink` appended every verified chunk sequentially to one shared `received_payload.bin`, ignoring `transferId/fileId/index`. ADR-015 multi-stream arrival is out-of-order by design â†’ scrambled bytes. `RandomAccessChunkSink`/`FileRandomAccessSinkHandle` existed but were never wired.
2. **Silent frame drops:** `WsSession.incomingBinary/incomingText` were SharedFlows with capacity 64 + `DROP_OLDEST`; a disk-slower-than-network consumer silently discarded CHUNK frames. Dropped chunks are never ACKed and `MultiStreamDispatcher` has no retransmit for sent-but-unconfirmed chunks â†’ permanent end-of-transfer stall. Chat MSG/ACK frames could drop the same way while the durable outbox believed them sent.
3. **Early-frame race:** binary/text arriving between peer registration and local `registerSession` hit a null `sessionByConnection[connection]` lookup and were dropped.
4. **Connect glare:** simultaneous dialing created two sessions per deviceId; the replaced session was never closed (leaked socket/read loop), and per-session collectors in the holder were never cancelled (zombie collectors double-handled frames).
5. **Wrong resume source:** `resumeTransfer` passed `transfer.fileName` (display label) as the openable URI.
6. **Arbitrary-peer sends:** `StreamChannelFactory.open(channelId)` had no peer identity; the holder picked `firstOrNull()` from live sessions â€” with multiple peers connected, files went to a random peer.
7. **No keepalive:** nothing scheduled WS pings and post-handshake `soTimeout = 0` meant half-open hotspot NAT connections blocked read loops forever.
8. **Lifecycle races/noise:** `ensureStarted` check-then-act outside sync could leak a duplicate NSD engine; `stop()` forgot pending-handshake sockets; HELLO version parsed but unenforced; late-ACK rejections logged as warnings.

### Working fix
1. `ReceivePipeline` gained an opt-in `sinkFactory: ((FileStart) -> ChunkSink)` + `emitSessionStarted` flag (+ `ReceiveEvent.SessionStarted`). Default behavior unchanged for legacy callers/tests.
2. Holder wires each FILE_START to its own `FileRandomAccessSinkHandle` at `FlashReceived/<transferId>/<safeName>` bridged via `RandomAccessChunkSink` (`index * chunkSize`); handles flushed+closed on COMPLETE; filename sanitized against path traversal.
3. `WsSession` inbound delivery switched to bounded `Channel`s with `trySendBlocking` on the WS read-loop thread â†’ TCP backpressure instead of drops (128 binary / 512 text frames).
4. `WsFlashNetwork` buffers early frames per connection and flushes into the session at registration; glare closes the replaced session; disconnect removal is identity-safe (`remove(key, value)`); pending handshakes closed in `stop()`; HELLO version mismatch fails the handshake both directions.
5. `FlashTransfer.sourceUri` added; resume re-reads it. `MultiStreamDispatcher`/factory now thread `peerDeviceId` so channels target the intended recipient (fallback: any live session).
6. `WsConnection` schedules 15 s PINGs with 45 s SO_TIMEOUT â€” silence beyond 3 missed pings closes half-open connections.
7. Holder: start/stop serialized behind a Mutex; per-session collector jobs cancelled when sessions leave the map; late `UNEXPECTED_DIRECTION` demoted to debug; chat framing moved to colon-safe `FlashTextFraming.encodeFields` (`FLASH_MSG`/`FLASH_RCPT`); Room DB persisted (`flash-dev.db`, destructive migration); content-source open failures now throw (the `file:///dummy/test_payload.bin` test path intentionally streams deterministic generated bytes).

### Verification
- Full suite: `testDebugUnitTest assembleDebug` â†’ BUILD SUCCESSFUL; 644 tests / 0 failures / 0 skipped across all modules.
- Physical two-device verification still pending (owner device run).

### Related files
- `core/network/src/main/java/com/transfer/flash/core/network/ws/{WsSession,WsConnection,WsFlashNetwork}.kt`
- `core/transfer/src/main/java/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt`
- `core/transfer/src/main/java/com/transfer/flash/core/transfer/multistream/{StreamChannel,MultiStreamDispatcher,MultiStreamReceiver}.kt`
- `core/transfer/src/main/java/com/transfer/flash/core/transfer/{RealFlashTransferRepository,model/FlashTransfer}.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`

### Status
RESOLVED (code-level; device verification pending)

## ERROR-014 - LanSession idle timeout kill & RealFlashChatRepository duplicate outbox drain (RESOLVED)

### Date
2026-08-24

### Area
:core:network (`LanSession`), :core:messaging (`RealFlashChatRepository`)

### Symptoms
1. Device session established ("connected"), but within 4 seconds closed immediately and reverted to "connecting". Logcat showed `LAN session read tick ...` followed immediately by peer disconnect.
2. Unit tests in `:core:messaging` failed with `expected:<1> but was:<2>` in `RealFlashChatRepositoryTest.sendText writes message to Room and enqueues in outbox`.

### Environment
Android physical devices (LAN / Hotspot), JVM unit tests (`testDebugUnitTest`).

### Root Cause
1. `LanConnectionProbe` initializes the socket with `soTimeout = 4000` (4s) for the initial handshake. When `LanSession.readLoop()` started, it inherited this 4s timeout. In the previous implementation, when `SocketTimeoutException` was thrown after 4 seconds of idle time, the catch block was outside the `while` loop, exiting the loop and falling through to `finally { close() }`. The 10s heartbeat ping loop never got a chance to fire before the session was terminated.
2. In `RealFlashChatRepository`, both the background `drainOutboxLoop()` and the manual call `drainOutboxOnce()` inside `sendText()` executed concurrently without a mutex. Under `testDispatcher` / concurrent execution, both routines read the un-deleted outbox items and dispatched duplicate `MessageWireFrame.TextMessage` instances.

### Working Fix
1. In `LanSession.kt`:
   - Moved `try { reader.readLine() } catch (_: SocketTimeoutException)` **inside** the `while` loop so that a timeout merely continues the read loop rather than exiting and tearing down the session.
   - Raised post-handshake `socket.soTimeout` to `IDLE_READ_TIMEOUT_MS = 30_000` (30s) to give the 10s heartbeat tracker ample headroom while retaining periodic unblocking.
2. In `RealFlashChatRepository.kt`:
   - Guarded `drainOutboxOnce()` with a `Mutex.withLock` to guarantee atomic outbox processing.

### Verification
- `testDebugUnitTest` across all modules: 411 tasks, 0 failures (100% green).
- Deployed APK to physical device: LAN sessions remain stably connected.

### Status
RESOLVED

## ERROR-013 - Multi-stream dispatcher concurrency family (RESOLVED)

### Date
2026-08-23 (diagnosed/resolved 2026-08-24)

### Area
:core:transfer multistream (C5.7) -- dispatcher/receiver concurrency & testing

### Symptoms
MultiStreamDispatcherTest scenarios failed/hung: gated-channel stall, resume-seeding zero progress / failure, progress-monotonic timeout, channel-death survivor, E2E failures. PipelineEndToEnd resume assertNotNull(completedFrame) failure.

### Environment
Pure-JVM unit tests, Dispatchers.Default workers, loopback in-memory channels.

### Root Cause Analysis & Fixes
1. **sendFrame return value handling:** `runCatching { channel.sendFrame(...) }.isSuccess` always returned `true` because `sendFrame` returns `Boolean` (so `Result.success(false)` is still a success). Fixed all 3 occurrences to `.getOrDefault(false)`.
2. **Materializer pos=index skip bug:** When opening `ChunkStream` the materializer set `pos = index` instead of leaving `pos = 0`, so the `while (pos < index)` skip loop never ran and chunk 0 was re-sent even when in `doneIndexes`. Fixed by removing `pos = index` from the `.also` block.
3. **Test event-loop starvation:** Tests used `launch { send() }` inside `runBlocking` then polled with `Thread.sleep`, blocking the single event-loop thread. Fixed by dispatching to `Dispatchers.Default`.
4. **PipelineEndToEndTest receiver reuse:** Resume test created a fresh `ReceivePipeline` instead of reusing `firstReceiver` (which held chunks 0..11), so session was unknown and no COMPLETE was ever emitted. Fixed to reuse `firstReceiver`.
5. **COMPLETE frame caching:** Added `@Volatile completeFrameBytesHolder` so the generated COMPLETE frame is reliably available even if emitted during `ingestComplete`.

### Verification
- `MultiStreamDispatcherTest`: 8/8 green (all scenarios un-@Ignore'd).
- `PipelineEndToEndTest`: 2/2 green.
- Full `testDebugUnitTest` suite: BUILD SUCCESSFUL, 0 failures.

### Status
RESOLVED

## ERROR-012 - PowerShell 5.1 Get-Content/Set-Content corrupts UTF-8 repo files (mojibake)

### Date
2026-08-22

### Area
Tooling / documentation workflow (not app code)

### Symptoms
After a PowerShell round-trip of `logs/handoff.md`, every em-dash/ellipsis/smart-quote in the file displayed as mojibake (`Ã¢â‚¬"`, `Ã¢â‚¬â€œ`, etc.). File content was semantically intact but encoding-damaged across the entire document, including historical sections.

### Environment
Windows PowerShell 5.1 (default shell), file = UTF-8 without BOM.

### Error
```text
`main` Ã¢â‚¬" remote: ... / UI-025Ã¢â‚¬"027 ...  (E2 80 94 read as ANSI "Ã¢â‚¬"", then re-encoded as UTF-8)
```

### Root cause
PS 5.1 `Get-Content` without `-Encoding utf8` decodes BOM-less UTF-8 using the legacy ANSI codepage; `Set-Content -Encoding utf8` then re-encodes the already-corrupted strings. One pass destroys all non-ASCII characters.

### Failed attempts
1. In-place string replacement on the mangled text â€” abandoned: too many distinct mojibake sequences to reverse reliably.

### Working fix
`git checkout -- logs/handoff.md` (last commit held a clean copy), then redo all edits with the editor tooling that writes UTF-8 natively.

### Verification
Post-restore diff clean; subsequent edits verified rendering correctly.

### Related files
- `logs/handoff.md`
- Rule going forward: never round-trip repo text files through PS 5.1 Get-/Set-Content; use native edit tools or `-Encoding utf8` on BOTH sides.

### Status
RESOLVED

## ERROR-007 - Edge-to-Edge System Bar Overlap (Status Bar Cutout & Navigation Bar)

### Date
2026-08-20

### Area
Compose UI / Window Insets / Edge-to-Edge (`FlashComposer`, `FlashChatHeader`, `FlashChatListTopBar`, `FlashSelectionToolbar`)

### Symptoms
1. In chat list and conversation view, top headers drew at y=0 directly behind the status bar clock, battery, and front camera punch-hole cutout, making tabs/header buttons difficult to tap.
2. In conversation view, the message input text area and bottom buttons drew directly behind the 3-button system navigation bar or gesture pill.

### Environment
- Android physical devices (API 30â€“36) with `enableEdgeToEdge()` enabled in `MainActivity.kt`.

### Root cause
With `enableEdgeToEdge()` active in `MainActivity`, Android draws composables under system bars by default:
- Custom headers (`FlashChatListTopBar`, `FlashChatHeader`, `FlashSelectionToolbar`) lacked `statusBarsPadding()`, causing their interactive action buttons to be obscured by the status bar and camera notch.
- The custom composer (`FlashComposer`) only applied `imePadding()` (which is 0 when the software keyboard is closed) without `navigationBarsPadding()`, causing the composer to sit directly under the navigation bar icons.
- `FlashConversationScreen` Scaffold was setting `contentWindowInsets = WindowInsets.navigationBars`, causing double or mismatched insets calculation against the bottom bar.

### Failed attempts
None. Systematic Compose insets hierarchy applied.

### Working fix
1. Updated `FlashChatListTopBar`, `FlashChatHeader`, and `FlashSelectionToolbar` to wrap header content in a root container with `.fillMaxWidth().background(colors.backgroundSurface).statusBarsPadding()`. This draws the surface color up behind the status bar while safely positioning all text, avatars, and action icons below the camera cutout and status bar.
2. Updated `FlashComposer` to apply `.navigationBarsPadding().imePadding()`, ensuring proper clearance above the system navigation bar when closed and above the software keyboard when open.
3. Updated `FlashConversationScreen` Scaffold to use `contentWindowInsets = WindowInsets(0, 0, 0, 0)` so that `Scaffold` correctly uses measured `topBar` and `bottomBar` heights for `innerPadding`.
4. Added `statusBarsPadding().navigationBarsPadding()` to QA sheets (`FlashIconSheet`, `FlashMotionSheet`).

### Verification
- Ran full multi-module build and unit test suite.
- Validated that status bar, camera cutout, navigation bar, and keyboard insets are correctly respected.

### Related files
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashChatListTopBar.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashChatHeader.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashSelectionToolbar.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashComposer.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashConversationScreen.kt`
- `ui/theme/src/main/java/com/transfer/flash/ui/icons/FlashIconSheet.kt`
- `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashMotionSheet.kt`

### Status
RESOLVED

---

## ERROR-006 - StackOverflowError during NSD service resolution

### Date
2026-08-20

### Area
NSD Service Resolution / Core Discovery (`NsdResolveQueue.kt`)

### Symptoms
App crash on startup on `ConnectivityThread`:

```text
FATAL EXCEPTION: ConnectivityThread
Process: com.transfer.flash, PID: 7882
java.lang.StackOverflowError: stack size 1039KB
	at com.transfer.flash.core.discovery.nsd.NsdFlashDiscovery.access$isCurrent(NsdFlashDiscovery.kt:27)
	at com.transfer.flash.core.discovery.nsd.NsdFlashDiscovery$startDiscovery$listener$1.onServiceFound$lambda$2(NsdFlashDiscovery.kt:95)
	at com.transfer.flash.core.discovery.nsd.NsdResolveQueue$resolve$listener$1.onServiceResolved(NsdResolveQueue.kt:61)
	at com.transfer.flash.core.discovery.nsd.NsdResolveQueue$resolve$listener$1.onServiceResolved(NsdResolveQueue.kt:62)
```

### Environment
- Android physical device (Samsung Galaxy / Snapdragon)
- Target SDK: 36

### Root cause
Inside `NsdResolveQueue.kt`, the anonymous `NsdManager.ResolveListener` overrides `onServiceResolved(info: NsdServiceInfo)`. On line 62, it called `onServiceResolved(info)` intending to invoke the constructor lambda `onServiceResolved: (NsdServiceInfo) -> Unit`. However, because the method name in the anonymous class identical to the parameter name, the invocation resolved to the anonymous listener's own `onServiceResolved(info)` method recursively, causing an immediate `StackOverflowError` when resolving discovered mDNS services.

### Working fix
Renamed constructor parameter in `NsdResolveQueue` from `onServiceResolved` to `onResolvedCallback` and updated invocation site to `onResolvedCallback(info)`.

### Verification
- Code inspected and validated to eliminate shadowed recursion.
- Build and unit tests verified via `testDebugUnitTest assembleDebug`.

### Related files
- `core/discovery/src/main/java/com/transfer/flash/core/discovery/nsd/NsdResolveQueue.kt`
- `core/discovery/src/main/java/com/transfer/flash/core/discovery/nsd/NsdFlashDiscovery.kt`

### Status
RESOLVED

---

## ERROR-003 - LAN probe routed through wrong local network

### Date
2026-08-18

### Area
LAN connection / Android network routing

### Symptoms
Manual/discovered connection timed out even though NSD discovered the peer:

```text
NSD service resolved deviceId=d844e50b-8d38-4e77-a74c-68ef833dfea7 address=10.1.97.57:46589
LAN probe connecting address=10.1.97.57:46589 deviceId=d844e50b-8d38-4e77-a74c-68ef833dfea7
java.net.SocketTimeoutException: failed to connect to /10.1.97.57 (port 46589) from /10.177.173.19 (port 48052) after 4000ms
```

### Environment
- Device log from package `com.transfer.flash`
- Target SDK: 36

### Root cause
The peer address was on `10.1.97.x`, but Android opened the outbound socket from local address `10.177.173.19`. That means the socket used Android's default network instead of the Wi-Fi/LAN network that can reach the discovered peer. This commonly happens when the phone has mobile data, hotspot, VPN, or another active network and Wi-Fi is not the default internet network.

### Failed attempts
The previous probe used a plain `Socket()`, which lets Android choose the default network.

### Working fix
`LanConnectionProbe` now uses `ConnectivityManager` to find a Wi-Fi/Ethernet `Network` and creates the socket through `network.socketFactory`. This should force LAN probes to use the local network path.

### Verification
Not yet built or retested. The user explicitly requested not to run a build after this change.

### Related files
- `app/src/main/java/com/transfer/flash/network/LanConnectionProbe.kt`
- `app/src/main/java/com/transfer/flash/lan/LanController.kt`

### Status
OPEN

## ERROR-004 - LAN probe reaches peer but port refuses connection

### Date
2026-08-18

### Area
LAN connection / NSD advertised port

### Symptoms
After routing LAN sockets through the Wi-Fi network, the connection no longer timed out from a different subnet. Instead it failed quickly with `ECONNREFUSED`:

```text
LAN probe connecting address=10.1.97.67:45331 deviceId=ad74c205-ef18-4971-8a6d-377994ed5462 network=462967197709
java.net.ConnectException: failed to connect to /10.1.97.67 (port 45331) from /10.1.97.57 (port 44968) after 4000ms: isConnected failed: ECONNREFUSED (Connection refused)
```

### Root cause
`ECONNREFUSED` means the target device was reachable, but nothing was listening on the advertised port at that moment. The LAN MVP used a random dynamic port each time `Start LAN` ran. Because mDNS/NSD records can briefly outlive app restarts or Start/Stop toggles, another device can try a stale random port after the peer has moved to a new port.

### Failed attempts
- Previous code used `ServerSocket(0)`, causing a different port after each LAN start.

### Working fix
`LanProbeServer` now prefers stable TCP port `45821` and falls back to a dynamic port only if `45821` is unavailable.

### Verification
Not built or retested. The user explicitly requested not to run a build after changes.

### Related files
- `app/src/main/java/com/transfer/flash/network/LanProbeServer.kt`
- `docs/protocol.md`

### Status
OPEN

## ERROR-002 - Pixel 7 not reliably discoverable with SDK 37 local-network permission path

### Date
2026-08-18

### Area
LAN discovery / Android local-network permission

### Symptoms
- Pixel 7 could discover another phone, but the other phone did not reliably show the Pixel 7.
- Pixel 7 displayed a system local-network device prompt that was not part of the app UI.
- Logs repeatedly showed:

```text
AppOps system_server E Operation not found: uid=10315 pkg=com.transfer.flash(null) op=ACCESS_LOCAL_NETWORK
```

### Environment
- App package: `com.transfer.flash`
- Device involved: Pixel 7
- Previous app target SDK: 37
- Compile SDK: 37

### Error
```text
Operation not found: uid=10315 pkg=com.transfer.flash(null) op=ACCESS_LOCAL_NETWORK
```

### Root cause
The MVP targeted SDK 37 and declared `ACCESS_LOCAL_NETWORK` without implementing the SDK 37 runtime permission/system-device-picker flow. Android documentation says this permission is required for SDK 37+ but should not be declared for SDK 36 or lower, where `INTERNET` provides implicit local-network access.

The discovery implementation also allowed late NSD resolve callbacks after Stop and did not serialize resolve requests, both of which could make multi-device discovery inconsistent.

### Failed attempts
No code-level retry was attempted before this fix. The issue was diagnosed from physical-device logs supplied by the project owner.

### Working fix
- Target SDK changed to 36 for the LAN MVP.
- Removed `ACCESS_LOCAL_NETWORK` from the manifest.
- NSD resolve requests are now queued and processed one at a time.
- Stale callbacks are ignored using discovery generation checks.

### Verification
`testDebugUnitTest assembleDebug` passed. Physical Pixel 7 retest is pending.

### Related files
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/transfer/flash/discovery/LanDiscovery.kt`
- `docs/android-platform-notes.md`

### Status
OPEN

## ERROR-001 - Gradle assemble blocked by loopback restriction

### Date
2026-08-18

### Area
Build verification / local environment

### Symptoms
`assembleDebug` fails before app compilation output with:

```text
java.io.IOException: Unable to establish loopback connection
```

### Environment
- OS shell: PowerShell
- Workspace: `E:\Flash`
- Java runtime used for verification: `E:\AndroidDev\AndroidStudio\android-studio\jbr`
- Gradle user home used for verification: `E:\Flash\.gradle-user-home`

### Error
```text
FAILURE: Build failed with an exception.

* What went wrong:
java.io.IOException: Unable to establish loopback connection
```

### Root cause
The restricted shell blocks Gradle's local loopback connection used for the daemon or single-use daemon process. This appears environmental rather than caused by the Android source changes, because `testDebugUnitTest` completed successfully in the elevated Gradle run and compiled the debug Kotlin sources.

### Failed attempts
1. Ran `assembleDebug` from the restricted shell after unit tests; it failed with the loopback error.
2. Ran `assembleDebug` with `--no-daemon --offline`; Gradle still forked a single-use daemon and failed with the same loopback error.
3. Requested another elevated Gradle run; the environment rejected the escalation.

### Working fix
Run Gradle from an unrestricted shell.

### Verification
After the environment switched to unrestricted filesystem/network access, `testDebugUnitTest assembleDebug` passed.

### Related files
- `gradle.properties`
- `gradle/gradle-daemon-jvm.properties`

### Status
RESOLVED

## ERROR-011 - Focus overlay required multiple taps to dismiss (Dialog window-focus quirk)

### Date
2026-08-21

### Area
UI-007/UI-008 Message focus overlay (`FlashMessageFocusOverlay`, `FlashMessageContextMenu.kt`)

### Symptoms
On device (Samsung SM_G986U1), after long-press opening the context menu, tapping the dimmed area often did nothing on first contact; dismissal needed several taps.

### Root cause
The overlay was rendered in a separate Compose `Dialog` window. On some OEM builds the first pointer event after a dialog window gains focus is consumed by the window-focus transition (visible in logcat as `MSG_WINDOW_FOCUS_CHANGED 0â†’1` at dialog open), so the scrim's click handler misses it.

### Working fix
Replaced the `Dialog` with an in-screen overlay: `FlashMessageFocusOverlay` now renders as the last child of the conversation layout â€” a full-size scrim Box with clickable dismiss, a `BackHandler`, and an explicit top-end close button. Same visuals; taps land in the activity's own window with no focus-consumption loss.

### Status
RESOLVED (code); pending re-verification on device

---

## ERROR-010 - Voice recording started then immediately lost gesture control

### Date
2026-08-21

### Area
UI-020 Voice recording (`FlashComposer.kt` / `FlashMicButton`)

### Symptoms
Hold-to-record appeared to start then "stop"; slide-to-cancel and release-to-send never fired.

### Root cause
The composer used `AnimatedContent(recordingPhase)` around the whole input row. Pressing the mic flipped phase Idleâ†’Holding, which swapped content and **disposed the exact `FlashMicButton` node whose `awaitEachGesture` owned the active touch stream**. The replacement mic composed fresh but never receives an in-progress stream (Compose hit-tests at touch-down), so all subsequent move/up events were lost.

### Working fix
Hoisted `FlashMicButton` out of the swapped region: the AnimatedContent now swaps only the leading/center content (input pill â†” recording bar), while one persistent mic node occupies the trailing slot across Idle/Holding/CancelArmed. Layout swap to the full-width Locked panel happens only after finger-up, which is safe.

### Related files
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashComposer.kt`

### Status
RESOLVED (code); pending re-verification on device

---

## ERROR-009 - Blank band above composer covering conversation when keyboard opened

### Date
2026-08-21

### Area
Conversation screen insets (`FlashConversationScreen.kt` / `FlashComposer.kt` / `FlashMessageList.kt`)

### Symptoms
Tapping the message text field made the keyboard push up a blank strip that covered part of the conversation.

### Root cause
IME inset applied twice: the composer lives in Scaffold's `bottomBar` and applies `.imePadding()` itself (so bottomBar height grows with the keyboard), but `FlashMessageList`'s modifier ALSO applied `.imePadding()` on top of `innerPadding` (which already includes the grown bottomBar). Net effect: list bottom inset = 2Ã— keyboard height.

### Working fix
Removed `.imePadding()` from the `FlashMessageList` call site; keyboard clearance now flows solely through Scaffold `innerPadding`.

### Status
RESOLVED (code); pending re-verification on device

---

## ERROR-008 - Intermittent "The device is not ready" during Gradle cache writes (E: drive)

### Date
2026-08-21

### Area
Build environment / Gradle daemon caches on E: drive (GRADLE_USER_HOME=E:\Flash\.gradle-user-home and project .gradle)

### Symptoms
- Build fails within seconds with java.io.IOException: The device is not ready thrown from DefaultFileLockManager / AsyncCacheAccessDecoratedCache cache-lock writes.
- Intermittent: direct file writes to E:\ succeed, then fail minutes later; retrying sometimes passes.
- A Gradle daemon can survive in a half-dead state ("Unable to stop one of the daemons") after these failures.
- Recurs across multiple sessions and on the owner's own terminal.

### Environment
- Windows, project + Gradle user home on E: drive (NTFS, reports Healthy, ~170 GB free).
- Machine has a Realtek PCIE Card Reader + Netac SSD - E: is believed to be removable/hot-plug storage; reader power-saving can drop the device handle mid-write.

### Error
`	ext
java.io.IOException: The device is not ready
  at java.base/sun.nio.ch.FileDispatcherImpl.write0(Native Method)
  at org.gradle.cache.internal.filelock.LockStateAccess.writeState(LockStateAccess.java:61)
`

### Root cause
The physical device hosting E: intermittently drops its I/O handle (power-saving or hot-plug behavior). Any write touching that handle fails. Gradle's multi-process safe caches are write-heavy at startup/settle, so they surface the failure first.

### Working fix / workaround
1. Kill all daemons: .\gradlew.bat --stop then 	askkill /PID <stuck java pid> /F.
2. Verify no stale locks (optional): all *.lock files under .gradle-user-home\caches and .gradle should open read/write without error.
3. Start a fresh daemon and rebuild - succeeded immediately (20 s, 357 tasks).
4. If it recurs mid-session: add --no-daemon --no-configuration-cache --no-build-cache.

### Real fix (owner decision)
Move GRADLE_USER_HOME and/or the project to an internally powered fixed disk, or disable power management on the E: device (Device Manager -> disk/reader -> Power Management / "turn off device" unchecked). Hardware-side; cannot be fixed from the repo.

### Status
MITIGATED (workaround reliable; hardware follow-up recommended)

## ERROR-016 - :core:transfer testDebugUnitTest HANGS after bounded-channel dispatcher changes (RESOLVED)

### Date
2026-08-24

### Area
`core/transfer/.../multistream/MultiStreamDispatcher.kt`

### Symptoms
`:core:transfer:testDebugUnitTest` never completes (task starts, no result, no failure output). Started immediately after these same-session changes:
1. Feed channels bounded: `feeds = Channel(FEED_BUFFER_FRAMES=8)`, `shared = Channel(SHARED_BUFFER_FRAMES=32)` (were UNLIMITED).
2. Cooperative pause gate added (`setPaused`/`awaitUnpause`, PAUSE_POLL_MS=25) called in worker Phase1 loop top, Phase2 loop top, and materializer per-chunk.
3. New `shouldRedistribute(deferred)` guard around dead-worker redistribution into `shared` (returns false when deferred completed OR all plannedStreams channels are in deadIds -> drops frame instead of blocking).

### Prime suspect (initial hypothesis - correct in outline, incomplete)
Bounded `shared` channel deadlock in the channel-death test scenarios: when MULTIPLE workers die near-simultaneously, each redistributes its remaining feed into `shared`. With cap 32 and nobody consuming, workers block inside `shared.send()`; `aliveWorkers` never reaches 0 because blocked workers haven't decremented yet, so `failIfAllChannelsDead` never arms and nothing resolves. The `shouldRedistribute` all-dead check uses `plannedStreams` vs deadIds but does NOT account for workers that are dead-but-still-inside-the-loop (deadIds already contains them while they still hold frames to redistribute). Also possible: materializer blocks sending to a feed whose worker exited early via the new `return` paths without draining/closing its feed (ownFeedsOpen never decremented on those early returns? verify: the `return` inside the failure branch skips the `if (ownFeedsOpen.decrementAndGet()==0) shared.close()` line -> shared NEVER closes -> survivors' Phase2 `for (prepared in shared)` never terminates -> hang).

### Root cause (confirmed)
Three defects compounded; the structural one (3) would also have deadlocked real transfers on device, not just tests.

1. **Early `return` skipped BOTH exit bookkeeping steps.** The Phase-1 drop path was
   `if (shouldRedistribute(deferred)) { shared.send(prepared) } else { return }`.
   That `return` bypassed
   - `if (ownFeedsOpen.decrementAndGet() == 0) shared.close()` -> `shared` never closed -> every surviving worker's Phase-2 `for (prepared in shared)` looped forever, and
   - `aliveWorkers.decrementAndGet()` -> `maybeResolveFromState`'s `aliveWorkers.get() == 0` arm was unreachable -> the ACK-drain deadline was never armed -> `deferred` never completed -> `deferred.await()` hung even once `workers.joinAll()` had returned.
2. **Double decrement of `aliveWorkers`.** Phase 2's failure branch decremented `aliveWorkers` inside a `try` whose `finally` decremented it again, driving the counter negative so the `== 0` all-dead test could never match.
3. **Phase-separated consumers vs a bounded `shared` queue (the real deadlock).** A dead worker blocked in `shared.send()` stops draining its own feed -> the materializer blocks on that bounded feed -> survivors never leave Phase 1 -> nobody ever drains `shared` -> permanent deadlock. Unbounded channels hid this; bounding exposed it. On a large file this is device-fatal, not a test artifact.

### Working fix
`MultiStreamDispatcher.runWorker` rewritten as a SINGLE merged loop (no Phase 1 / Phase 2 split), so own feed and `shared` are consumed concurrently:
- `select { ownFeed.onReceiveCatching {...}; shared.onReceiveCatching {...} }` while both are open and the worker is alive; single-channel `receiveCatching()` once one closes; loop exits when both are drained. Dead workers never consume `shared` (they cannot send it onward), they only drain their own feed and hand it back.
- Exit bookkeeping moved into `finally` behind idempotent `releaseOwnFeed()` / `releaseAlive()` closures, so every path - normal, early, or cancellation - decrements `ownFeedsOpen` (last one closes `shared`) and `aliveWorkers` exactly once.
- `redistribute()` replaces the blocking `shared.send()` with a `trySend` + `delay(REDISTRIBUTE_POLL_MS = 5)` poll that gives up when the transfer resolved, `shared` closed, or every channel is dead - so a full queue can never pin a worker.
- Materializer short-circuits with `if (deferred.isCompleted) break`, instead of serializing the rest of the file into queues nobody will drain.
- `maybeResolveFromState` fails fast when `aliveWorkers <= 0 && chunksSentTotal == 0` (nothing ever reached a wire, so no ACK can be in flight) instead of waiting out the 15 s ACK-drain grace.

Bounded queues were KEPT (feeds=8, shared=32); the time-boxed revert-to-UNLIMITED fallback was not needed.

### Verification
- `:core:transfer:testDebugUnitTest` BUILD SUCCESSFUL - 70 tests, 0 failures (previously never terminated).
- `MultiStreamDispatcherTest` re-run 8x standalone (JUnitCore, real threads): 8/8 green, ~1.3 s per run - no flakiness in the death/redistribution races.
- Full `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL, 411 actionable tasks, 644 tests / 0 failures / 0 skipped across all 11 test modules.

### Status
RESOLVED (2026-08-24)


## ERROR-017 - Gradle cannot start at all: "Unable to establish loopback connection" (RESOLVED)

### Date
2026-08-24

### Area
Build environment (Windows 11, JBR 25 / JDK 21, Gradle 9.5.0) - not repo code.

### Symptoms
- EVERY Gradle invocation dies before any task runs, including `gradlew.bat --version`:
  `java.io.IOException: Unable to establish loopback connection`.
- `gradlew --stop` reports no daemons; `tasklist` shows no java processes. Killing daemons, `--no-daemon`,
  `-Djava.io.tmpdir=...`, and long-path TMP/TEMP overrides all change nothing.
- Happens with BOTH available JVMs (Android Studio JBR 25.0.2 and the Gradle-provisioned JBR 21.0.10),
  so it is not a toolchain-version problem.

### Root cause
`--stacktrace` points at `Selector.open()` -> `WEPollSelectorProvider.openSelector` -> `PipeImpl$Initializer.init`
-> `sun.nio.ch.UnixDomainSockets.connect0` -> `java.net.SocketException: Invalid argument: connect`.

Since JDK 19+, `PipeImpl` (used for every `Selector`) prefers an AF_UNIX socket pair on Windows. On this machine
AF_UNIX **bind succeeds but connect always fails EINVAL** (reproduced with a 20-line probe under plain `java`;
the bound socket file cannot even be deleted afterwards - "The file cannot be accessed by the system"). Something
in the OS/security stack blocks AF_UNIX connects for this process tree. Plain TCP loopback bind+connect works fine.

`PipeImpl.createListener` only falls back to TCP loopback when the AF_UNIX **bind** throws - a failing connect is
not caught - so no Selector can ever be created, and Gradle (launcher AND daemon) cannot run.

### Working fix / workaround
Force the AF_UNIX path to fail at BIND time so the JDK falls back to TCP loopback, by pointing the AF_UNIX
implicit-bind temp dir (`jdk.net.unixdomain.tmpdir`, read by `sun.nio.ch.UnixDomainSocketsUtil.getTempDir`)
at a nonexistent path. Exporting it via `JAVA_TOOL_OPTIONS` covers the launcher, the daemon, and all worker/
Kotlin-compiler JVMs in one shot:

```bash
export JAVA_HOME="E:\AndroidDev\AndroidStudio\android-studio\jbr"
export GRADLE_USER_HOME="E:\Flash\.gradle-user-home"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"   # Z: does not exist -> AF_UNIX bind fails -> TCP loopback
./gradlew.bat testDebugUnitTest assembleDebug --console=plain
```

PowerShell equivalent: `$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"`.
Each JVM prints one `Picked up JAVA_TOOL_OPTIONS:` line - harmless.
Side effect: nothing in this build uses AF_UNIX for real, so the forced TCP fallback is behaviour-neutral.

### Failed attempts (do not repeat)
- `gradlew --stop` + kill java + fresh daemon (this is ERROR-008 medicine; wrong disease).
- `--no-daemon`, `--no-configuration-cache`, sandbox on/off.
- `-Djava.io.tmpdir=<short path>` and TMP/TEMP exports: `PipeImpl` ignores `java.io.tmpdir` for AF_UNIX;
  it uses `jdk.net.unixdomain.tmpdir` / `TEMP` via its own helper, and the failure is at connect, not path length.
- Switching to the Gradle-provisioned JDK 21: same failure (also AF_UNIX-preferring).

### Fallback if the workaround ever stops working
The pure-JVM packages `core/transfer/.../chunked` and `.../multistream` have ZERO android/androidx imports, so they
can be compiled and tested without Gradle using cached jars: `java -cp kotlin-compiler-embeddable-2.2.10.jar;
kotlin-stdlib;kotlinx-coroutines-core-jvm-1.10.2;annotations-23.0.0 org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`
then `java org.junit.runner.JUnitCore <test classes>` (junit-4.13.2 + hamcrest-core-1.3). The compiler needs stdlib,
coroutines AND `org.jetbrains:annotations` on its OWN classpath, not just on `-classpath`. This ran 56 chunked+
multistream tests in 1.3 s and is how ERROR-016 was first verified.

### Status
RESOLVED (workaround is reliable and one-line; root cause is OS-side AF_UNIX blocking, outside the repo)

## ERROR-018 - Sending device could not pause; paused transfers hung, failed, or deadlocked (RESOLVED)

### Date
2026-08-25

### Area
`core/transfer/.../RealFlashTransferRepository.kt`, `core/transfer/.../multistream/MultiStreamDispatcher.kt`,
`core/transfer/.../multistream/MultiStreamProgress.kt`, `app/.../debug/DiscoveryEngineHolder.kt`

### Symptoms
Owner report: "the transferring device cannot pause". Observed/derived from a full audit of the
pause/resume/cancel surface:
- Tapping Pause on the SENDER did nothing - the row flipped to Paused for a moment and then went back to
  Transferring while bytes kept flowing.
- A transfer paused for more than ~15 s died with `ack drain timeout: N chunk(s) unconfirmed`.
- Pausing the receiver, then resuming it, left the transfer at 0 B/s forever.
- Speed/ETA kept counting down while paused; `-1` occasionally surfaced as the speed.
- With two inbound transfers, resuming one un-gated the socket for both.

### Root cause (nine distinct defects; 1 is the reported one)
1. **Registration race - the reported bug.** `sendFile` returns as soon as the send coroutine is *launched*,
   but `executeSend` only puts the dispatcher into `runningDispatchers` AFTER the resume-chunk DAO query and
   dispatcher construction. `pauseTransfer` inside that window found no dispatcher, took a state-only branch,
   and `executeSend` then wrote `Transferring` unconditionally - the pause was erased and no wire control
   frame was sent either, so the peer never learned about it.
2. **`send()` could hang forever.** The materializer and every worker polled `awaitUnpause()` unconditionally.
   `send()` is a `coroutineScope` that joins all of them, so a terminal outcome reached DURING a pause (the
   receiver's COMPLETE frame, or a cancel) left `send()` suspended with no exit but cancellation.
3. **The 15 s ACK-drain grace killed paused transfers.** A paused receiver deliberately stops draining and
   ACKing, so missing ACKs are expected - but `failIfAllChannelsDead`/`maybeResolveFromState` armed and
   expired the deadline anyway and reported `ack drain timeout`.
4. **Receiver-resume deadlock.** `onRemoteTransferControl` RESUME on the RECEIVING side never emitted
   `IncomingControl(RESUME)`, so a receiver that had paused locally stayed intake-gated forever while its UI
   claimed Transferring and the resumed sender blocked on backpressure at 0 B/s.
5. **Session-wide intake gate was a boolean.** `DiscoveryEngineHolder.pausedIntake` gated the whole socket,
   so resuming ONE inbound transfer un-gated every other paused one.
6. **`resumeTransfer` no-oped on a state mismatch** (`state != Paused/Failed`) even when the dispatcher was
   demonstrably paused - the wire stayed parked with no way back.
7. **Negative speed reached the UI.** `RollingRateMeter` returns `-1.0` until two samples exist; that was
   written straight into `speedBytesPerSec`.
8. **Rate meter straddled the pause gap.** After resume, the window divided real bytes by pause wall-clock
   and reported a bogus near-zero rate (plus an absurd ETA).
9. **Silent control drops and asymmetric remote cancel.** `MutableSharedFlow.tryEmit` failures were
   discarded unlogged; remote CANCEL on the sending side did not unpause before cancelling, and a `finally`
   in `executeSend` could retire a *relaunched* transfer's registrations (orphaning the live transfer so
   pause/resume/cancel stopped reaching it).

### Working fix
- **`pauseIntents: ConcurrentHashMap.newKeySet()`** in the repository, written BEFORE the dispatcher lookup.
  `executeSend` calls `applyPendingPauseOrStart`, which checks the intent, writes `Transferring`, then
  re-checks - the two orderings interleave so one side always observes the other and the pause cannot be
  lost. `pauseTransfer` now has ONE outbound branch (intent + best-effort `setPaused` + state + wire frame),
  so the peer is always told, dispatcher or not.
- **`awaitUnpause()` returns early once `terminalDeferred` completes**, and workers `continue` (never `break`)
  when the transfer is already resolved - they keep draining feeds to closure so the materializer is never
  stranded, but never touch the wire again. `send()` now always returns.
- **Paused transfers are exempt from the ACK-drain grace**: `failIfAllChannelsDead` skips arming while paused
  and `maybeResolveFromState` DISARMS (`ackDrainDeadlineMs = null`) so resume starts a fresh window.
- **`onRemoteTransferControl` RESUME (Receiving) emits `IncomingControl(RESUME)`**; PAUSE deliberately does
  NOT gate (the gate is session-wide and the peer has already stopped, so gating would only stall unrelated
  transfers' ACKs). Un-gating can never block anything, so it is safe and idempotent.
- **`pausedIntakeIds: MutableStateFlow<Set<String>>`** replaces the boolean gate; the binary read loop waits
  on `first { it.isEmpty() }`, so per-transfer resume only un-gates when nothing is left paused.
- **`resumeTransfer` resumes on wire truth**, not tracked state: a live sender whose dispatcher `isPaused`
  (or that still holds a pause intent) is always resumable; RESUME is sent to the peer BEFORE any relaunch.
- **Telemetry honesty**: paused progress publishes a hard `0.0` rate and `-1` ETA; `speedBytesPerSec` is
  `coerceAtLeast(0.0)`; new `RollingRateMeter.reset()` is called on resume to drop samples spanning the gap;
  `setPaused` publishes immediately so the UI reflects the pause within one frame.
- **`emitOutgoing`/`emitIncoming` log dropped intents**; remote CANCEL unpauses before cancelling; the
  `executeSend` `finally` uses ownership-checked `runningDispatchers.remove(transferId, dispatcher)`.

### Verification
- 6 new regression tests, all green:
  - `MultiStreamDispatcherTest`: pause-before-`send()` keeps the wire silent (0 chunk frames, 0 B, rate 0.0,
    ETA -1) and resume delivers all 19 chunks byte-identical; a COMPLETE frame arriving WHILE paused returns
    from `send()` instead of parking (would have hung forever pre-fix); a paused sender survives repeated
    60 s fake-clock jumps past the drain grace, and only after resume fails with `ack drain timeout`.
  - `RealFlashTransferRepositoryTest`: a pause issued while the resume-chunk DAO query is still parked (i.e.
    before the dispatcher is registered) leaves the transfer Paused with 0 chunks on the wire and completes
    all 8 chunks after resume; a remote PAUSE parks a live sender after only the in-flight chunk and remote
    RESUME finishes it with `errorMessage` cleared; cancelling a PAUSED sender settles on Cancelled and the
    wire goes quiet.
- `:core:transfer:testDebugUnitTest --rerun` BUILD SUCCESSFUL - 76 tests, 0 failures.
- Full `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL, 411 tasks, 668 tests / 0 failures / 0 skipped
  across all test modules; `app-debug.apk` produced.
- NOT device-verified: the two-phone pause/resume/cancel round trip stays on the owner backlog (EXP-002).

### Status
RESOLVED (2026-08-25) - code-level; device confirmation pending


## ERROR-019 - Two timing tests flake under CPU-saturated full builds (RESOLVED / mitigated)
`FlashStressLogicTest."2000-message generation completes well under one second"` (:ui:chat) and
`RealFlashTransferRepositoryTest."pause issued before the dispatcher is registered is applied, not silently
lost"` (:core:transfer) both FAILED during a combined `testDebugUnitTest assembleDebug` run, then both
PASSED on `--rerun-tasks` in isolation. Not regressions — load flakes.

### Root cause
Both are wall-clock assertions with no relation to the correctness of the code under test:
- FlashStressLogic asserts pure in-memory generation finishes in `< 1000 ms` (`FlashStressLogicTest.kt:89`;
  the comment notes the real device target is <100 ms and 1 s is a deliberately generous CI guard). Under a
  concurrent `assembleDebug` (dexing/packaging saturating all cores) it measured 1798 ms.
- RealFlashTransferRepository uses `awaitUntil` — a `Thread.sleep(5)` busy-wait with a 20 s deadline
  (`RealFlashTransferRepositoryTest.kt:54`) — driven by a real dispatcher. CPU starvation stalled the
  transfer coroutine past 20 s (`state=Transferring chunks=8`).
Neither touches the Android SDK, SQLCipher, or NSD, so the compileSdk-35 / sqlcipher-4.17.0 changes in this
session cannot be the cause.

### Mitigation
If either fails during a full combined build, RE-RUN THE TASK IN ISOLATION before suspecting a regression:
`./gradlew.bat :ui:chat:testDebugUnitTest :core:transfer:testDebugUnitTest --rerun-tasks`. Both go green on
an unsaturated machine. Durable fixes if it becomes chronic: raise/remove the stress-test time bound (it is
already a CI-only guard), and/or make the repository test drive a virtual-time dispatcher instead of the
sleep-based `awaitUntil`. Not done now — the tests are correct on idle hardware and the thresholds document
intent.

### Status
RESOLVED (2026-08-26) - load-induced flake; both tests verified green on isolated `--rerun-tasks`.

## ERROR-022 - webrtc-kmp onTrack Flow: tuple destructuring + track kind check compile risk

### Date
2026-09-02

### Area
core:calling / WebRTC integration

### Symptoms
First FlashCallSession draft used `pc.onTrack.collect { (track, stream) -> }` destructuring
and a locally re-declared `MediaStreamTrackKind` enum, risking API-shape mismatch with
webrtc-kmp 0.125.11 (onTrack's emission type and MediaStreamTrack.kind typing were written
from memory of the sample, not verified against the artifact source).

### Root cause
Drafted against remembered sample code instead of the published commonMain sources.

### Working fix
Verified against webrtc-kmp 0.125.11 sources before build: `MediaStreamTrack.kind` is
`MediaStreamTrackKind` (Audio/Video) from the library; onTrack emits track+stream. Removed
the local enum; kept the onTrack collector minimal (remote stream capture only) since
connection-state drives the ACTIVE transition.

### Verification
`:core:calling:compileDebugKotlin` + `:core:calling:testDebugUnitTest` pass (12 tests, 0 failures).
`:ui:callui:compileDebugKotlin` and `:app:compileDebugKotlin` also pass.

### Status
RESOLVED (2026-09-02, verified build)
