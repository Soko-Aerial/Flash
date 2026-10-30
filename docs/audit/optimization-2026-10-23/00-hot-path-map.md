# Hot-Path Map — Flash Project Performance Audit
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  

---

## 1. Executive Summary of Runtimes

The Flash project comprises four primary execution environments:
1. **Android Client (`:app`, `:core:*`, `:ui:*`)**: Mobile runtime executing on ART (ARM64/ARM32), constrained by strict OS background execution limits, thermal throttling, Doze/Hiber process freezing, and low-RAM (2GB) configurations.
2. **Desktop Client (`:desktop`, `:core:*`, `:ui:*`)**: Desktop runtime on JVM (OpenJDK 21) via Compose Desktop (Skia / DirectX / OpenGL / Metal), running in a packaged jpackage image.
3. **Hub Server (`:server`, `:core:hub-protocol`)**: Headless Ktor/Netty or CIO server on JVM 21, managing persistent SQLite databases via WAL, handling authenticated TLS WebSocket relay sessions, mailbox store-and-forward, org directory synchronization, and sealed file chunk staging.
4. **Admin Console (`server/src/main/resources/admin-ui`)**: Browser-based vanilla JavaScript client executing on V8/SpiderMonkey/WebKit.

---

## 2. Hot Paths by Execution Cadence

### 2.1 Per-Frame Paths (60 Hz / 120 Hz; ~8.3 ms - 16.6 ms budget)
- **Compose Recomposition & Draw:**
  - `FlashShell.kt` (Android) / `DesktopShell.kt` (Desktop): Root UI layout, window insets, top-level snackbars, active dialog/sheet state.
  - `FlashConversationScreen.kt` & `FlashMessageList.kt`: LazyColumn rendering of message bubbles. Hot path reads `FlashConversationState`, message bubble list layouts, and day divider headers.
  - `FlashCallScreen.kt` & `FlashGroupVideoGrid.kt`: Active call HUD, avatar pulse animations, participant speaking border rings. *(Ref: F69, F70 in 2026-10-22 review fixed by moving reads to draw phase).*
  - `FlashSwarmPieceMap.kt`: Custom `Canvas` draw phase rendering multi-thousand piece bitmaps/blocks during active swarm transfers.
  - `FlashVideoSurface` (Android: `SurfaceView`/`TextureView`, Desktop: `Skia` bitmap blit): Decoded WebRTC video frame rendering (30 fps or 60 fps).

### 2.2 Per-Audio-Packet Paths (50 Hz / 20 ms budget)
- **Push-to-Talk (`:core:ptt`):**
  - **Capture:** `AudioRecord` (Android) / `TargetDataLine` (Desktop) -> PCM16 / Opus 16 kHz mono -> `PttPacket` wire frame -> `PttSessionEngine.sendAudioPacket` -> WebSocket frame dispatch on `Dispatchers.IO`.
  - **Playout:** Inbound `PttPacket` -> Jitter buffer insertion (`PttPlayoutCore`) -> PLC (packet loss concealment) on drop -> `AudioTrack` / `SourceDataLine` streaming write.
  - *Note:* Fixed in 2026-10-22 comprehensive review (F71): removed 50 coroutines/sec launch and StateFlow mutations on audio packet arrival; now updates `@Volatile var lastAudioPacketMs`.

### 2.3 Per-Chunk Paths (10 Hz – 1,000 Hz during active transfers)
- **File Transfer Pipeline (`:core:transfer`, `:core:enterprise:files`):**
  - **Sender:** `ContentResolver.openInputStream` / `FileChannel` -> Buffer read (chunk size: 64 KB in LOW mode, 256 KB–1 MB in STANDARD/BOOST) via `ChunkBufferPool` -> In-place SHA-256 chunk hash (`MessageDigest`) -> Framing (`ChunkFrame.serialize`) -> TLS encryption (`SSLSocket` / Conscrypt) -> WebSocket wire write.
  - **Receiver:** Socket read -> TLS decrypt -> Framing parse -> SHA-256 integrity check -> `FileChannel.write` (positional) -> Outbox/ACK emission -> Done-set bitmap / SQLite update -> Progress callback throttling (`UiPacing.throttleLatest(100ms)`).
- **Group Swarm (`:core:swarm`):**
  - Sans-IO piece scheduling: Rarest-first piece selection, bitfield updates, SHA-256 block verification, peer request budget tracking.

### 2.4 Per-Message Paths (Interactive Burst: 1–50 msgs/sec in active groups)
- **Chat Send:** Optimistic UI insert -> Room transaction (`messages` + `conversations` + `outbox` in single atomic `runInWriteTransaction`) -> Wake outbox drain -> JSON serialization (`kotlinx.serialization`) -> Sign frame (Ed25519 if group/org) -> WebSocket frame dispatch -> ACK receipt handling -> Room status update (`DELIVERED`/`READ`).
- **Chat Ingest:** Socket frame arrival -> TLS decrypt -> JSON parse -> Deduplication check on `localId` -> Cryptographic signature verification (Ed25519) -> Room insertion -> Read cursor update -> Receipt generation -> Combine flow emission (`FlashChatRepository.conversationState`).

### 2.5 Per-Tick / Periodic Timer Paths
- **Background Keepalive:** `FlashKeepaliveWorker` (WorkManager 15 min periodic).
- **Hiber Keepalive:** `TranssionHiberKeepAlive` (750 ms runnable on Main Looper).
- **Pairing Coordinator:** 1 Hz ticker (`TICK_MS = 1000L`) active only during pairing flow.
- **Connection Planner:** Auto-connect sweep (15s–60s depending on mode ECO/STANDARD/BOOST).
- **Server Mailbox & Sweep:** Background sweeper purging expired mailbox rows and TTL cleanup (60s).
- **Admin Console:** Polling timers (5s–30s `setInterval`) for devices, people, and audit events.

### 2.6 Per-Connection Paths
- **Peer & Server Mesh:**
  - TLS 1.3 Handshake (X.509 cert validation, TOFU public key pinning).
  - WebSocket Upgrade: `FLASH_WS_HELLO` header parsing, identity exchange, peer capability negotiation (`sw1`, `ptt`, etc.).
  - Session Registration: `activeSessions` map update, session cap enforcement (limit 24), glare resolution.

### 2.7 Per-Request / Per-Query Paths (Hub Server & Room DAOs)
- **Hub Server REST API:**
  - Ktor routing -> Bearer token validation / cache lookup -> SQLite query / transaction (WAL mode) -> JSON response serialization.
- **Client Room Database:**
  - Flow re-query on write: Room invalidation tracker fires on table mutations, triggering re-evaluation of active query flows (`conversationState`, `observeLatestPreviews`, unread counts).

### 2.8 Start-Up / Cold Start Paths
- **Android App Launch:**
  - `FlashApplication.onCreate`: Init `MemoryGovernor`, `AndroidThermalGovernor`, logging sinks, WorkManager worker registration.
  - Keystore key retrieval & SQLCipher database decryption / Room migration verification.
  - `DiscoveryEngineHolder.ensureStarted`: NSD initialization, mDNS browsing, TLS keypair load/generate, WebSocket server bind (port 45822).
  - `MainActivity.onCreate`: Compose `setContent`, Splash animation (UI-056: ~3.45s animation), initial screen composition, conversation preview loading.
- **Desktop Launch:**
  - `main()`: DPAPI unwrapping of database key, Room/SQLite open, JmDNS service start, Compose Desktop window creation and Skia surface initialization.
- **Server Startup:**
  - Ktor embeddedServer startup, Netty worker pool init, SQLite DB migrations (`ServerDatabase`), device certificate delegation load, route installation.
