# Periodic Work Inventory — Flash Project Performance Audit
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  

---

## 1. Inventory Table

| Component / Owner | File:Line | Mechanism | Period / Cadence | Action When Idle | Stops When Idle / Unneeded? | Impact Assessment |
|---|---|---|---|---|---|---|
| **WorkManager Keepalive** | `FlashKeepaliveWorker.kt:133` | `WorkManager.enqueueUniquePeriodicWork` | 15 minutes (OS minimum) | Wakes process; checks `DiscoveryEngineHolder.isRunning()`; runs swarm retention & reevaluate; if not running, brings up engine for 45s to flush outbox | Yes: no-op if engine already running; tears down if started headless | **Low-Medium**: Essential safety net for Doze/task kill; stops promptly |
| **Transsion Hiber Loop** | `TranssionHiberKeepAlive.kt:389` | `Handler.postDelayed` | 750 ms | Re-verifies tier state (BT / Overlay / AudioTrack) on Transsion devices (Infinix/Tecno) | Runs only on Transsion devices; stopped when engine teardown occurs | **Medium**: 1.33 wake-ups/sec on main thread; needed to avoid OEM freezing within 4-5s |
| **Pairing Coordinator Ticker** | `PairingCoordinator.kt:404` | Coroutine `delay(TICK_MS)` | 1,000 ms (1s) | Updates countdown timer for pairing confirmation screen | **Yes**: Active **only** while pairing UI is displayed; fixed in EXP-016 | **Minimal**: No idle cost when not actively pairing |
| **Pairing Fingerprint Poll** | `PairingCoordinator.kt:278` | Coroutine `delay(FINGERPRINT_POLL_MS)` | 500 ms | Polls local fingerprint store until available | **Yes**: Active only during initialization of pairing dialog | **Minimal**: Terminates on success or error |
| **Outbox Drain Loop** | `OutboxRepository.kt` | Event-driven + wake trigger | Event-driven (previously 1s grid fixed in EXP-015) | Drains pending outgoing messages when session connects or new message arrives | **Yes**: Woken only on new outbox write or session connect; no idle polling | **Minimal**: High-efficiency event-driven pattern |
| **UI Telemetry Pacing** | `UiPacing.kt:39` | `delay(windowMs)` | 50 ms - 100 ms | Throttles high-frequency flow updates (transfer progress, audio meter) | **Yes**: Runs only when upstream emits; collapses intermediate updates | **Low**: Bounds UI recomposition rate |
| **Connection Planner Sweep** | `ConnectionPlanner.kt` | Coroutine loop `delay(sweepIntervalMs)` | 15s (BOOST), 30s (STANDARD), 60s (ECO) | Iterates remembered routes and unverified peers to trigger connection attempts | Continues while engine is active; scales down to 60s in ECO | **Medium**: Generates periodic Wi-Fi socket connection attempts and airtime |
| **JmDNS Discovery Ticker** | `JmdnsTransport.kt:142` | Coroutine `delay(presenceSleep)` | 30s | Checks active mDNS service instances and restarts query if stalled | Active while discovery is enabled on desktop | **Low-Medium**: Wake-up every 30s |
| **WebRTC Stats Monitor** | `CallHealthMonitor.kt` | Scheduled coroutine `delay(intervalMs)` | 1,000 ms - 2,000 ms | Polls `PeerConnection.getStats()` to measure RTT, packet loss, and jitter | **Yes**: Runs strictly during active voice/video calls | **Low**: CPU cost bounded to call duration |
| **Group Call Speaking Detector** | `FlashGroupCallSession.kt` | Coroutine `delay(callStatsIntervalMs)` | 1,000 ms - 2,000 ms | Samples audioLevel from remote participants to update `isSpeaking` | **Yes**: Active only during group calls | **Low**: 0.5–1 Hz polling during calls |
| **PTT Floor Machine Tick** | `PttFloorMachine.kt` / `PttSessionEngine.kt` | Coroutine `delay(TICK_MS)` | 100 ms | Evaluates floor timeout, talker silence timeout, and state machine transitions | **Yes**: Active only while PTT session is ongoing | **Low**: Active only when PTT screen / session open |
| **Enterprise Hub Presence Poll** | `EnterpriseHub.kt:236` | Coroutine `delay(hubPresencePollMs)` | 30s - 60s | Fetches presence delta from Enterprise Hub REST API | Continues while enterprise hub feature is configured and network available | **Low-Medium**: HTTP round-trip on cellular/Wi-Fi |
| **Enterprise Hub Heartbeat** | `FlashRelayClient.kt:336` | Coroutine `delay(PING_INTERVAL_MS)` | 30,000 ms (30s) | Sends WebSocket ping to keep server relay tunnel alive | Active while relay client connected | **Low**: Standard keep-alive |
| **Org Snapshot Refresh** | `OrgSnapshotCoordinator.kt:61` | Coroutine `delay(periodMs)` | 60,000 ms (1 min) | Re-evaluates org directory cache and refreshes membership snapshot | Active while org enrolled | **Low**: SQLite read once per minute |
| **Server Mailbox Sweeper** | `Server.kt` / `MailboxQueue.kt` | Scheduled timer / coroutine `delay(sweepInterval)` | 60,000 ms (60s) | Purges expired mailbox entries, cleans quarantined records past TTL | Runs server-side in background | **Low-Medium**: Periodic SQLite delete query |
| **Admin UI Polling Timers** | `app.js` (admin console) | JavaScript `setInterval()` | 5s / 10s / 30s | Refreshes device table, active connections, audit log events | **No**: Runs continuously in browser tab while page is open | **Medium**: Can flood server with REST requests if tab left open |
