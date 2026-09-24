# Decisions

## ADR-022 - Transfer profile tiering: FlashTransferProfile in FlashPerformanceMode, bounded buffer queues, and single-stream low mode

### Decision
1. **Extend `FlashPerformanceMode` with `FlashTransferProfile`:** Governs file-transfer concurrency (`streamCount`), base chunk sizes (`chunkSizeBytes`), per-worker queue depths (`feedBufferFrames`), shared redistribution queue depths (`sharedBufferFrames`), adaptive ceiling (`maxAdaptiveChunkSizeBytes`), video thumbnail permission (`allowVideoThumbnails`), image preview dimension cap (`maxImagePreviewDimension`), and target SQLite memory cache (`sqliteCacheSizeKb`).
2. **Low & Ultra-Low Mode Constraints:** On `LOW` hardware (RAM < 512MB, single/dual-core, 2.4 GHz radio, IoT/POS/wearables):
   - `streamCount = 1`: Eliminates multi-socket overhead and context switching.
   - `feedBufferFrames = 2`, `sharedBufferFrames = 4`: Caps in-flight queue memory to <250 KB heap.
   - `chunkSizeBytes = 32 KB` (or 64 KB baseline).
   - `allowVideoThumbnails = false`: Skips heavy JCodec video keyframe demuxing/decoding.
   - `sqliteCacheSizeKb = 1024`: Constrains SQLite cache to 1MB.
3. **High Mode Throughput:**
   - `streamCount = 4`, `feedBufferFrames = 16`, `sharedBufferFrames = 64`, `maxAdaptiveChunkSizeBytes = 1 MB`, saturating multi-gigabit and 5GHz LAN connections.
4. **Configurable Constructor Invariants:** `MultiStreamDispatcher` accepts `feedBufferFrames` and `sharedBufferFrames` as constructor parameters with fallback defaults, and `RealFlashTransferRepository` passes `performanceMode().transfer` into dispatcher construction.

### Context
User requested extensive documentation and multi-mode performance optimization, specifically addressing extreme resource-constrained devices below the baseline phone tiers.

---

## ADR-021 - Pause lifecycle rules: intent outlives the dispatcher, paused transfers are never failed, resume always un-gates

Amends ADR-018 §2 (cooperative dispatcher pause). ADR-018 stays valid; these are the invariants it was
missing, all found by auditing the reported "the transferring device cannot pause" defect (ERROR-018).

### Decision
1. **Pause is an INTENT, not a dispatcher call.** `RealFlashTransferRepository` records `pauseIntents`
   (a `ConcurrentHashMap.newKeySet()`) BEFORE it looks up `runningDispatchers`, and `executeSend` applies any
   pending intent when it registers its dispatcher (`applyPendingPauseOrStart`: check intent, else write
   `Transferring`, then re-check). `sendFile` returns before the dispatcher exists, so any design that
   requires a live dispatcher to accept a pause has a lossy window by construction.
2. **A paused transfer is never failed by a timeout.** A paused receiver deliberately stops ACKing, so the
   ACK-drain grace is not armed while paused and is DISARMED if a pause begins after it was armed; resume
   starts a fresh window. Pause duration is therefore unbounded, which is what users expect.
3. **A terminal outcome always wins over a pause.** `awaitUnpause()` returns as soon as the session's
   terminal deferred completes, and workers that observe a resolved transfer keep draining their feeds to
   closure without touching the wire. `send()` must be able to return while still paused.
4. **Resume un-gates unconditionally; remote pause does not gate.** The receive-side intake gate is
   session-wide, so gating on a *remote* pause would stall unrelated transfers' ACKs on the same socket while
   the peer has already stopped sending. Un-gating can never block anything, so RESUME always emits
   `IncomingControl(RESUME)` (idempotent). The gate itself is a SET of paused transfer ids, not a boolean.
5. **Resume trusts the wire, not the tracked state.** A live dispatcher that is `isPaused` (or still carries a
   pause intent) is resumable regardless of the `FlashTransferState` the UI shows.
6. **Paused telemetry is a hard zero.** While paused, published rate is `0.0` and ETA is `-1`; the rolling
   rate meter is `reset()` on resume so no window straddles the paused gap. Negative rates never leave the
   repository (`coerceAtLeast(0.0)`).

### Context
Pause was implemented as "flip the dispatcher flag if one exists". Because `sendFile` returns before
registration, the common Dev-Console/UI sequence (send, then pause) hit the window where no dispatcher
existed: the state flipped to Paused, `executeSend` overwrote it with Transferring, no wire frame was sent,
and bytes kept flowing - the reported symptom. Fixing only that exposed the rest: the drain grace killing
long pauses, `send()` parking forever when COMPLETE landed during a pause, and a receiver that stayed
intake-gated after resume (0 B/s with both UIs claiming Transferring). Full defect list in ERROR-018.

### Alternatives considered
- **Block `sendFile` until the dispatcher is registered** (so pause always finds one): rejected - it turns a
  fire-and-forget call into one that waits on a DAO query and a whole-file hash, and the race returns as soon
  as anything else is added before registration.
- **Cancel the job on pause and re-plan on resume:** rejected again here for the ADR-018 reason (loses
  receiver-authoritative ACK state) and because it makes "paused" indistinguishable from "failed" in the DAO.
- **Gate receive intake on remote pause too (symmetry):** rejected - the gate is session-wide, so it would
  stall ACKs for unrelated transfers sharing the socket. Asymmetry here is deliberate and documented.
- **Suspend the ACK-drain deadline by *extending* it instead of disarming:** rejected - any finite extension
  is a guess about how long a human pauses.

### Revisit when
The intake gate becomes per-transfer at the transport layer (then remote pause CAN gate symmetrically), or
pause must survive process death / a session reconnect (which needs the intent persisted in the DAO, not just
in memory - today a paused sender that is killed resumes as Queued and re-plans from the persisted done-set).

## ADR-019 - Multi-stream sender workers: one merged select loop over bounded queues

### Decision
`MultiStreamDispatcher` worker coroutines consume their own feed channel and the shared redistribution queue in a
SINGLE loop via `select { ownFeed.onReceiveCatching; shared.onReceiveCatching }`, instead of two sequential phases
(drain own feed, then drain shared). Queues stay bounded (`FEED_BUFFER_FRAMES = 8`, `SHARED_BUFFER_FRAMES = 32`).
Handing work back to `shared` is non-blocking (`trySend` + 5 ms poll) and gives up when the transfer resolved,
`shared` closed, or every channel is dead. All exit bookkeeping (`ownFeedsOpen`, `aliveWorkers`) lives in `finally`
behind idempotent release closures. Dead workers drain their own feed but never consume `shared`.

### Context
Bounding the queues (to cap memory on large files) deadlocked the dispatcher: with phase-separated consumers, a
worker blocked in `shared.send()` stops draining its own feed, so the materializer blocks on that feed, so survivors
never reach the phase that drains `shared`. Early `return` paths also skipped the `ownFeedsOpen`/`aliveWorkers`
decrements, so `shared` never closed and the terminal-resolution check never armed (ERROR-016 - the unit suite hung
forever; on device this would have stalled any transfer with a mid-flight channel death).

### Alternatives considered
- Revert to `Channel.UNLIMITED` (the pre-existing behavior): rejected - it only hides the deadlock and serializes an
  entire file into memory when the wire is slower than the disk.
- Keep phases but have dying workers drain their feed into `shared` before exiting: still deadlocks, since `shared`
  can be full while its only consumers are the workers still stuck in phase 1.
- Unbounded `shared` with bounded feeds: bounds the common case but leaves redistribution memory unbounded exactly
  in the failure scenario where frames pile up.

### Revisit when
Retransmit of sent-but-unACKed chunks is added (a dead worker would then requeue by index rather than by frame), or
profiling shows the 5 ms redistribution poll is material versus a dedicated redistribution consumer.

## ADR-018 - Transfer control plane on the wire: FLASH_XFER text frames + cooperative dispatcher pause

### Decision
1. Pause/resume/cancel are peer-visible: `RealFlashTransferRepository` emits `outgoingControl` intents that the host
   encodes as `FLASH_XFER` text frames (`FlashTextFraming.encodeFields` with `action` + `transferId`) on the peer's
   session; the receiving side maps them back through `onRemoteTransferControl(transferId, action)` and applies them
   to its own local transfer. `incomingControl` stays the LOCAL intake gate (receive-side backpressure).
2. Sender pause is COOPERATIVE, not job cancellation: `MultiStreamDispatcher.setPaused()` flips a `@Volatile` flag
   and `awaitUnpause()` (polled every `PAUSE_POLL_MS = 25`) is checked by the materializer per chunk and by each
   worker per frame. A paused transfer keeps its dispatcher, sockets, plan, and ACK bookkeeping alive.

### Context
Pausing only throttled the local side: the counterpart kept streaming (or kept waiting) with no idea the transfer
had been paused or cancelled, and cancelling a sender by cancelling its coroutine tore down channel state that
resume then had to rebuild from scratch, losing in-flight ACK accounting.

### Alternatives considered
- Binary control opcodes on the chunk channel: rejected - control must survive a saturated/paused data path, and the
  text channel is already the session's out-of-band lane (chat MSG/RCPT).
- Job cancel + full re-plan on resume: rejected - resume then re-sends confirmed chunks and cannot preserve the
  receiver-authoritative completion state.

### Revisit when
Control frames need acknowledgement/retry (currently fire-and-forget over a live session; a peer that reconnects
mid-pause is re-synced by the next progress/ACK exchange rather than by a replayed control frame).

## ADR-017 - Per-transfer random-access receive sinks + peer-routed stream channels (WS mesh hardening)

### Decision
1. `ReceivePipeline` accepts an optional `sinkFactory: (FileStart) -> ChunkSink` resolved once per accepted FILE_START; hosts bind each transferId to its own destination handle (`FileRandomAccessSinkHandle` via `RandomAccessChunkSink`, offset `index * chunkSize`). A new opt-in `ReceiveEvent.SessionStarted` surfaces session opens. Default behavior (single shared sequential sink, no event) is unchanged.
2. Inbound WS frames are delivered through bounded channels with **blocking sends on the read-loop thread** (TCP backpressure) instead of lossy `DROP_OLDEST` SharedFlows — dropped CHUNKs are un-ACKable and permanently stall multi-stream dispatch.
3. `StreamChannelFactory.open(channelId, peerDeviceId)` carries the intended recipient so every channel of a send routes to the correct peer; fallback to any live session only when peer is unknown.
4. WsConnection keepalive = 15 s application PINGs + 45 s SO_TIMEOUT: any 45 s inbound-silence window (half-open NAT) closes the connection.

### Context
First physical two-phone run of the ADR-016 swap produced unusable received files: the Dev Console sink appended chunks in arrival order while ADR-015 arrival is out-of-order by design (ERROR-015). The existing C5.9 policy types already provided offset-correct handles — they simply were not wired. Simultaneously, SharedFlow frame drops and the missing keepalive could hang transfers and mask dead peers.

### Alternatives considered
- Extending `ChunkSink.write(index, data)` with transferId/chunkSize: rejected — breaks every existing sink/test for information the pipeline already scopes per-session via a factory.
- Unbounded frame buffers: rejected — unbounded memory under sustained disk-behind-network load; backpressure belongs at TCP.
- Retransmit/NACK for lost chunks: unnecessary once drops are impossible at delivery level (loss now equals connection death).

### Revisit when
Multi-peer concurrent transfers need per-peer fairness across shared WebSockets, or EXP benchmarks show single-socket multiplexing bottlenecks (then: real N-socket streams per session).

## ADR-016 - Unified WebSocket Mesh Transport over Router and Hotspot (WsFlashNetwork + WsSession)

### Decision
1. Adopt full-duplex RFC 6455 WebSockets (`WsSession` / `WsConnection`) as the unified mesh transport for both instant chat messaging (UTF-8 text wire frames) and high-speed chunked file transfers (binary `ChunkFrame` payloads).
2. Operate symmetrically across both standard Wi-Fi Routers (via mDNS discovery on `_flash._tcp`) and Mobile Hotspots (via gateway/probe on `192.168.43.1`).
3. Wire inbound ACK and COMPLETE frames directly to active `MultiStreamDispatcher` instances, and inbound chunk data directly to `ReceivePipeline` with auto-flush to disk sink.
4. Provide structured diagnostic logging (`TAG_WS`, `TAG_TRANSFER`, `TAG_CHAT`, `TAG_DISCOVERY`, `TAG_DEV`) for real-time visibility in Android Studio and `adb logcat`.

### Context
Ad-hoc raw TCP sockets were prone to socket timeouts and single-direction bottlenecks. RFC 6455 WebSockets provide standardized framing, built-in keepalive ping/pong, immediate disconnect notifications (FIN/RST), and simultaneous multiplexing of text and binary channels without head-of-line blocking.

### Alternatives considered
- Raw TCP socket probes: rejected - separate sockets for discovery, chat, and files increased connection overhead and dropped on idle timeouts.
- HTTP REST + multipart upload: rejected - high overhead, no full-duplex signaling for real-time chat.
- WebRTC Data Channels: deferred - requires STUN/turn/signaling setup; WebSockets over LAN/Hotspot IP provide zero-dependency simplicity.

### Revisit when
Physical device multi-phone benchmarks on Wi-Fi Direct (P2P Group Owner) are compared against Hotspot/Router WebSocket mesh.

## ADR-015 - Multi-stream dispatch: dynamic claim loop (MPSCP-style), first-free end-game tail, one shared ACK mirror answered per arrival channel (C5.7)

### Decision
1. **Work distribution = dynamic on-demand claiming**, not static range/round-robin partitioning: each idle channel worker claims the next unsent chunk from a shared cursor (+ retry pool of chunks returned by dead channels). End-game: when remaining work <= K=8 chunks, the FIRST free alive channel becomes sole owner and drains the tail one chunk at a time; others park and take over only if the owner dies.
2. **One shared sender-side confirmed mirror** (`ResumeBitVector`, monotonic-union) guarded by a single state lock - never per-channel vectors, because receiver ACK batches may arrive on ANY of the N channels. Dedup: already-marked indexes are never re-counted; duplicate/overlapping batches idempotent.
3. **Receiver replies (ACK_BATCH/COMPLETE) travel down the ARRIVING channel** (liveness symmetry, per-path congestion honesty, no routing table). Terminal COMPLETE coordination frame is emitted EXACTLY ONCE by whichever thread first observes full coverage (CAS).
4. Stream count configurable 1..4, default 2 (plan C5.7); defaults stay provisional until EXP benchmarks on physical devices.

### Context
LocalSend v2 parallelizes only across FILES (`POST /upload` per fileId, called in parallel - https://github.com/localsend/protocol §4.2); within-one-file striping needs GridFTP/PFTP/MPSCP prior art (https://www.osti.gov/servlets/purl/1143126): PFTP's static round-robin lets a slow stream head-of-line block its whole share, while MPSCP's "next block to the first available stream" naturally load-balances. BitTorrent keeps end-game request depth minimal so the tail cannot strand behind slow peers (https://blog.libtorrent.org/2011/11/writing-a-fast-piece-picker/). Aggregate throughput is computed as ONE rolling 2 s window over TOTAL confirmed bytes (never summed per-stream rates).

### Alternatives considered
- Static range partitioning per stream: rejected - head-of-line blocking on slow streams, measured worse in PDT studies above.
- Round-robin pre-assignment: rejected - same slow-stream pathology without death-reclaim flexibility.
- Per-channel confirmed vectors merged later: rejected - fragmented truth; late/duplicate ACKs across channels become ambiguous.
- Broadcasting every ACK batch to all N channels: rejected - wasted writes and double-count risk; arrival-channel reply + any-channel ingestion is sufficient.
- Spreading the last K chunks across all channels (BitTorrent duplicate-request style): rejected for SENDER-side dispatch - duplicates waste upload bytes; single-owner tail gives deterministic drain with owner-failover.

### Revisit when
EXP-0XX device benchmarks (1 vs 2 vs 4 streams) land; K=8 and default streamCount may be retuned. Pause/cancel and stall timeouts are engine-layer concerns around `MultiStreamDispatcher.send`.

## ADR-014 - Chunked transfer framing v2: self-contained binary frames, per-chunk SHA-256 verify-before-write, monotonic-union resume vectors (C5.3-C5.6)

### Decision
1. Framing v2 is a **self-contained binary format** (FLSH magic + version byte 2 + type byte + u32 LE payload length; LE scalars; u16-length-prefixed UTF-8 strings), documented in full in ChunkFrame.kt KDoc and traveling as FlashEnvelope payloads. Types: FILE_START{transferId,fileId,fileName,totalBytes,totalChunks,chunkSize,fileSha256Hex} / CHUNK{transferId,fileId,index,data,chunkSha256raw32} / ACK_BATCH{transferId,fileId,indexes asc} / COMPLETE{transferId,fileId,verified}.
2. Per-chunk SHA-256 carried **raw 32 B** (not hex); receiver verifies BEFORE sink write (C5.5). Mismatch = Rejected(HASH_MISMATCH), never written/marked/ACKed - absence from ACK batches is the implicit NACK driving targeted single-chunk repair.
3. Resume state is a BitSet-packed bit vector (ResumeBitVector: wordCount LE + LE words) with **monotonic-union** 
econcile merge rule on both receiver and sender mirrors.
4. ACK batching fixed at every 32 distinct verified chunks (ReceivePipeline.DEFAULT_ACK_EVERY); COMPLETE emitted only when the vector completes, optionally gated by an injected whole-file digest re-check.

### Context
LocalSend v2 supplies file-level sha256 at prepare-upload and answers 422 on mismatch; BitTorrent pins piece-level independent hashes so corruption localizes to one re-requestable unit and resume state is a grow-only bitfield. Binary framing chosen because CHUNK carries up to 256 KB opaque bytes - JSON/base64 would inflate wire volume ~33%+ per chunk.

### Alternatives considered
- Single running whole-file digest only: rejected - cannot localize corruption or validate partial resume state without a second pass.
- Hex-encoded per-chunk hashes: rejected - doubles hash overhead (64 B vs 32 B) per chunk.
- Sequence-number-only ACKs (cumulative): rejected - cannot express holes for out-of-order/multi-stream arrival (C5.7).
- Sender-side random-access seek on resume: deferred - linear read-and-discard skip kept for v1 (flash read >> LAN throughput); SeekableSource reserved.

### Revisit when
Rust/desktop client implements the layout (compat test vectors then mandatory), or C5.7 multi-stream needs windowed/selective ACK semantics beyond the batch set.

## ADR-012 - CompositeDiscovery cross-radio dedup priority + presence grace window (P3/C3.9)

### Decision
1. Cross-transport endpoint dedup keeps the highest-priority radio's endpoint, fixed order LAN > WIFI_DIRECT > WIFI_AWARE > BLE (unknown names last). Loss of the top sighting falls back to the lower radio with an Updated event; Lost is emitted only when the LAST sighting disappears (hysteresis).
2. Presence sweeper default grace window = 30 s (`CompositeDiscovery.DEFAULT_GRACE_MS`), boundary `now - lastSeenAt >= grace` (exactly-at-window expires).

### Context
Same peer is visible on multiple radios simultaneously (e.g., LAN + BLE presence). UI needs ONE endpoint reporting the richest connectable path. Radios routinely miss mDNS goodbyes: RFC 6762 sec 10.1 goodbyes are TTL=0 records often not sent on crash/kill; record TTLs are 120 s (SRV/A/AAAA) to 75 min (PTR/TXT) per sec 10 - far too slow for chat-style presence.

### Alternatives considered
- Most-recent-sighting-wins dedup (recency over priority): rejected - would flap between paths as radios re-announce at different cadences.
- Grace = 120 s (mDNS SRV TTL): rejected - departure convergence up to 2 min unacceptable for presence UI.
- Emit Lost immediately on high-priority loss while lower radio alive: rejected - factually wrong (peer reachable) and causes Lost/Found flapping in UI.

### Why selected
Priority order mirrors transport-bandwidth reality and Android's own ranked-transport model (AOSP NetworkRanker policy flags, NetworkCapabilities transports, Nearby Connections Strategy tradeoffs). 30 s matches plan C3.5 example, rides out single missed announcements without long stale-presence windows.

### Revisit when
Real-device benchmarks (C3.11) show 30 s grace causing stale rows on networks with aggressive multicast filtering, or LAN/WFD throughput ranking flips on measured hardware.


## ADR-011 - D1 = Hilt; D6 = opt-in subtle sounds (default off); Phase P0 executed

### Decision
Owner approved (2026-08-22):
- **D1:** Hilt (2.60.1, KSP 2.3.11) as the DI framework. Graph lives in `:app` (`:core:*` modules stay DI-agnostic, constructor-injected), per plan C0.5.
- **D6:** Sound feedback = subtle synthesized procedural tones, **opt-in with default OFF** (settings toggle persists via DataStore in C1.5). Unblocks UI-040.
- Phase P0 executed same session: `FlashProtocol`/`FlashEnvelope`, `FlashLogger` ring buffer, `FlashTimeSource`/`FlashIdGenerator` (:core:common), Hilt graph skeleton + `FlashApplication`, GitHub Actions CI (C0.6), UI-040 sound system (`FlashSounds`) implemented in :ui:theme.

### Context
Hilt chosen over Koin (compile-time safety, standard tooling) and manual DI (brittle at scale); verified compatible with AGP 9.3.1/Kotlin 2.2.10 via research (Dagger â‰¥2.59 requires AGP â‰¥9 â€” satisfied). Sounds chosen opt-in/off to match reduce-motion philosophy (motion/a11y-first app) until owner opts in.

### Alternatives considered
Koin (runtime-only error detection), manual AppContainer (fine now, brittle later); asset-based sounds (ships binaries for what synthesis covers), default-on tones (rejected by a11y philosophy).

### Revisit when
Capability-flag version negotiation if a second protocol consumer appears (Windows/Linux client); sound call-site wiring when real messaging engine lands (C6).

## ADR-010 - Core upgrade decisions D2/D3/D4/D5 approved; plan v2 adopted

### Decision
Owner approved (2026-08-22) during the core-plan iteration session:
- **D2:** SQLCipher full-database at-rest encryption, key wrapped in AndroidKeyStore.
- **D3:** SHA-256 (java.security, zero deps) for chunk/message hashes and fingerprints.
- **D4:** Frame-level E2E implemented in C2 â€” ECDH P-256 â†’ HKDF â†’ AES-GCM per paired peer, layered on TLS.
- **D5:** Mesh relay is post-v1; v1 = direct P2P only (hop-count seams reserved).
- Plan `docs/core-upgrade-plan.md` rewritten to **v2**: fine-grained research-first steps, UI-dependency inventory, continuous identity-aware discovery, resilient network upgrades, multi-stream transfer, exhaustive messaging API surface.

### Context
UI roadmap complete on sample data; the finished screens define exact required inputs (`isVerified`, presence, typing names, transfer telemetry, pairing events). Core must be a reusable library (no app/UI deps) and every implementation step must begin with cited web research.

### Alternatives considered
Keystore-wrapped field encryption only (rejected â€” weaker than owner-approved full-database option); BLAKE3 (deferred â€” zero-dep SHA-256 sufficient until benchmarks say otherwise); mesh relay in v1 (rejected â€” scope).

### Revisit when
D1 (Hilt vs Koin vs manual) still needs explicit sign-off before C0.5; D6 (sound feedback) blocks UI-040. Benchmarks may revisit hash choice after EXP entries exist.

## ADR-009 - FlashText primitive: chat text renders through the design system, not material3.Text

### Decision
All Flash chat UI text renders through the new com.transfer.flash.ui.theme.FlashText composable, built on foundation-level androidx.compose.foundation.text.BasicText (the same non-Material tier as the composer's BasicTextField) and styled exclusively through FlashTypography tokens. Components implemented from UI-018 onward use FlashText; bare material3.Text must not appear in new chat UI code.

### Context
AGENTS.md 34 requires M3 as infrastructure only and every visible identity element Flash-owned. An audit of UI-018-022 + UI-025/026/027 found all icons, buttons, chrome, gestures, and shapes custom, but text was rendered via material3.Text (styled with Flash tokens). Text is user-visible identity, so it belongs behind a design-system primitive like color/shape/motion already are.

### Alternatives considered
- Keep material3.Text with tokenized styles - rejected for new code: leaves visible identity on an M3 component.
- Custom Canvas text rendering - rejected: unjustifiable cost; BasicText already provides non-Material text layout.
- Big-bang migration of existing components - deferred: accepted components (UI-003-016) migrate opportunistically when next touched.

### Consequences
- ui:theme gains FlashText.kt (no new dependencies).
- Pre-existing Material usages flagged for later cleanup: material3.IconButton in FlashReplyDock, CircularProgressIndicator in FlashFileIconBadge (both predate this ADR), HorizontalDivider, Scaffold.


## ADR-008 â€” Modular Multi-Library Architecture & Standalone Component Hosting

### Decision
Transition the Flash codebase from a single `:app` monolithic module into a suite of decoupled, standalone Android/Kotlin library modules (`:core:common`, `:core:discovery`, `:core:network`, `:core:transfer`, `:ui:theme`, `:ui:chat`, `:ui:transfer`) with `:app` serving as the runnable showcase application. Each library module will be independently buildable, testable, and publishable to Maven repositories via the Gradle `maven-publish` plugin under the group `com.transfer.flash`.

### Context
The owner requested that Flash's components be usable individually by third-party developers:
- Core networking & transfer libraries (headless discovery, socket sessions, WebSocket mesh, SAF file streaming) can be consumed without any Jetpack Compose or UI dependencies.
- UI components (Flash Pulse design system tokens, custom icons, message bubbles, chat list, composer) can be consumed independently with pluggable backend repositories.

### Alternatives considered
- Single-module architecture with package-level separation â€” rejected (cannot publish individual artifacts; risks accidental coupling between UI and low-level networking).
- Monolithic single SDK library (`flash-sdk`) â€” rejected (forces UI consumers to pull in networking/sockets, and forces headless users to pull in Compose runtime).
- Fine-grained multi-module library suite (`:core:*`, `:ui:*`, `:app`) â€” selected.

### Why this was selected
- **Independent Consumption**: Developers can pull `com.transfer.flash:core-transfer` for headless file transfer or `com.transfer.flash:ui-chat` for custom messaging UI.
- **Strict Layer Isolation**: Compile-time enforcement prevents UI components from referencing socket connections directly.
- **Hosting & Publishing Ready**: Standardized `maven-publish` configuration across all library modules enables automated releases to MavenCentral, JitPack, or GitHub Packages.
- **Scalability**: Allows future multiplatform targets (e.g. Kotlin Multiplatform / Desktop / CLI) for `:core:common` and `:core:network`.

### Revisit when
When preparing the first public Maven release or when extracting pure JVM/KMP modules for non-Android targets (Desktop/CLI).

---

## ADR-007 â€” Experimental WebSocket transfer side track (hand-rolled RFC 6455, multi-peer)

### Decision
An experimental WebSocket-based transfer path lives in `wstransfer/` (`WebSocketCodec`, `WsConnection`, `WsTransferServer`, `WsTransferClient`, `WsTransferManager`) plus `ui/transfer/WsTransferScreen.kt`. It is a **side track at the owner's request** and does NOT replace the main LAN/TCP+TLS protocol plan. The RFC 6455 codec (upgrade handshake, masking, frame parse/serialize, fragmentation reassembly) is hand-rolled in pure Kotlin; no new Gradle dependency was added.

### Context
The owner asked for WebSocket-based transfer that lets 3+ devices pair and connect to each other with simple file transfer, explicitly "not part of our main design". OkHttp 4.12.0 exists in the Gradle cache only as a leftover of the reverted Stream SDK experiment, and OkHttp's WebSocket is client-only â€” every Flash device must be both server and client, so OkHttp alone could not satisfy the requirement.

### Alternatives considered
- OkHttp WebSocket client + separate WS server library â€” rejected (two dependencies, client/server split, and OkHttp has no server).
- `org.java_websocket` (TooTallNate) â€” rejected for now (new dependency; keep zero-dep until this track proves useful).
- Extend line-based `LanSession` â€” rejected (owner explicitly requested WebSocket framing).

### Why this was selected
- Zero new dependencies (AGENTS.md dependency rule); codec is pure JVM and unit-tested (RFC 6455 reference accept-key vector, masked/unmasked round trips, 16/64-bit lengths, fragmentation, close/ping).
- Server (port 45822 preferred) + client on every device â†’ any device can pair with any number of peers; peers keyed by device ID with outbound-preferred primary connection and inbound fallback, so a 3-device full mesh works.
- File bytes ride ordered binary frames between `FLASH_FILE_START` / `FLASH_FILE_END` text frames; receiver writes to `filesDir/ws-received/` and answers `FLASH_FILE_ACK` with byte-count verification.

### Revisit when
If this track graduates to the main design: add TLS (wss://), real pairing/trust UX, resume, hash verification, and reconcile with ADR-001's TCP session path. Also revisit the hand-rolled codec if extension support (compression) is ever needed.

---

## ADR-006 â€” Custom `FlashBubbleShape` concave tail geometry (UI-005)

### Decision
Message bubbles use a Flash-owned `Shape` (`FlashBubbleShape` in `ui/theme/FlashShapes.kt`) producing `Outline.Generic(Path)`: three circular corners plus one concave cubic-BÃ©zier "pulse scoop" (8dp) on the sender-facing bottom corner, mirrored in RTL via `LayoutDirection`. Grouped messages (`TOP`/`MIDDLE`) are fully rounded 20dp. No third-party bubble library.

### Context
The master plan forbids generic `RoundedCornerShape` rectangles as final bubbles, and the provisional zero-radius-corner tail read as a broken rectangle. UI-005 required real grouped geometry with a distinct silhouette.

### Alternatives considered
- Zero-radius corner tail (provisional) â€” rejected (accidental look).
- SmartToolFactory/Compose-Bubble library â€” rejected (dependency for one path, canvas-shadow style conflicts with Flash no-shadow policy).
- `graphics-shapes` morphing â€” rejected for now; revisit only if UI-006 research justifies it.

### Why this was selected
Zero new dependencies; clips/borders follow the path; RTL-correct; one path per measure; gives Flash a silhouette detail not used by reference apps.

### Revisit when
UI-006 insertion animation research or UI-045 quality gate suggests morphing shapes.

---

## ADR-005 â€” Flash Pulse visual identity (UI-001)

### Decision
Flash premium chat UI uses the **Flash Pulse** design system: teal pulse accent (`#0D9488` light / `#1FB8A6` dark), graphite neutrals, spark amber for transfer/status only, layered dark surfaces (void + surface0â€“3). Tokens live in `ui/theme/`. Chat UI must not use `MaterialTheme.colorScheme` for visible styling.

### Context
UI-001 research compared Material You-as-primary, Stream-look scaffold (ADR-003 era), and an original palette. Owner requires distinct identity per ADR-004.

### Alternatives considered
- Material dynamic color as primary â€” rejected (brand loss).
- Retain Stream-look `#005FFF` blue â€” rejected (clone risk, wrong P2P story).
- Flash Pulse teal + graphite â€” selected.

### Why this was selected
Distinct from major messaging apps; supports local/P2P semantics; documented light + dark palettes; optional `dynamicAccent` tints accent only (UI-036 foundation).

### Revisit when
UI-045 quality gate or owner requests rebrand; UI-036 adds user-facing dynamic accent toggle.

---

## ADR-004 â€” Premium chat UI: research-first, custom Flash design system

### Decision
Flash premium messaging UI will be built component-by-component using a **research-first** workflow documented in `docs/ui/`. Material 3 is infrastructure only; the visible chat experience must use Flash-owned design, motion, icons, and interactions. No proprietary chat SDK/source (Stream etc.).

### Context
The product requires Telegram/Signal/WhatsApp-level polish with Flash's own visual identity and P2P-aware UX. Prior exploratory conversation UI exists but is provisional and must not bypass per-component research.

### Alternatives considered
- Continue Stream-look clean-room scaffold as final UI â€” rejected (does not meet originality/premium component bar).
- Copy Telegram/Stream visuals â€” rejected (legal and product identity).
- Single-pass Material 3 chat screen â€” rejected (generic, not premium).
- Research-first custom system with documented UI-001â€“UI-045 sequence â€” selected.

### Why this was selected
- Matches owner requirement for documented research per component.
- Keeps networking independent of UI.
- Enables continuity across AI sessions via `docs/ui/` and AGENTS.md Â§34.

### Revisit when
UI-045 quality gate passes and owner accepts premium chat UI for release; or if a licensed third-party UI kit is explicitly approved in writing.

## ADR-003 â€” Clean-room Stream visual parity; no Stream SDK or source incorporation

### Decision
Flash chat UI will match Stream Chat Android's premium conversation appearance through a Flash-owned design system and clean-room Compose components. Flash will **not** copy Stream source code, vendor Stream modules, or depend on Stream Maven artifacts.

### Context
The reference repo at `E:\Flash-reference-repos\stream-chat-android` is publicly visible but licensed under Stream.io's proprietary **Stream License**, not Apache/MIT. That license requires a Stream customer relationship, forbids sublicensing or distributing Stream source, and explicitly prohibits using Stream software to develop products that compete with Stream Chat (Section 6). Flash is a P2P LAN/Wiâ€‘Fi Direct chat product and therefore falls under the competitive-use restriction.

### Alternatives considered
- Copy `stream-chat-android-compose` sources into Flash and adapt models â€” rejected (license + competitive-use).
- Add `io.getstream:stream-chat-android-compose` as a Gradle dependency â€” rejected (same license on published artifacts).
- Use Stream SDK with a Flash network adapter â€” rejected unless Stream grants a written competitive carve-out.
- Generic Material 3 dynamic theming â€” rejected (does not match Stream's fixed brand/chrome design system).
- Clean-room UI with side-by-side visual verification against the compose sample â€” selected.

### Why this was selected
- Keeps Flash legally independent while still targeting Stream-level visual polish.
- Separates presentation (`FlashChatTheme`, chat composables) from transport (`FlashChatRepository`, LAN protocol).
- Aligns with the project rule that the transfer/chat engine must not depend on third-party chat backends.

### Revisit when
- Stream provides explicit written permission for competitive incorporation, or
- Flash pivots to being a Stream customer app that uses Stream's hosted backend (unlikely for P2P goals).

## ADR-002 - Target SDK 36 for LAN MVP while local-network permission flow is unfinished

### Decision
Target SDK 36 for the current LAN MVP and remove `ACCESS_LOCAL_NETWORK` from the manifest.

### Context
Pixel 7 testing showed repeated `AppOps` errors for `ACCESS_LOCAL_NETWORK` and a system local-network device prompt while the app targeted SDK 37. Android documentation says `ACCESS_LOCAL_NETWORK` is required for target SDK 37+, while target SDK 36 and lower receive local-network access through `INTERNET` and should not declare the new permission.

### Alternatives considered
- Keep target SDK 37 and implement the runtime local-network permission immediately.
- Keep target SDK 37 and rely on the system-mediated local-network picker.
- Lower target SDK for the LAN MVP and revisit SDK 37 after the LAN path is stable.

### Why this was selected
- The current milestone is still validating LAN discovery and TCP reachability, not final platform permission UX.
- Removing the SDK 37 local-network permission path eliminates the Pixel 7 prompt/confusion during MVP testing.
- It keeps the app testable on current devices while preserving a documented revisit point.

### Revisit when
Before release or when bumping target SDK back to 37, implement and test the official local-network permission flow or system-mediated picker flow on Android 17+ devices.

## ADR-001 - Start LAN with NSD plus a TCP reachability probe

### Decision
Use Android NSD for LAN service advertisement/discovery and a small TCP probe before implementing full file transfer.

### Context
The project needs a reliable LAN baseline before Wi-Fi Direct. NSD proves that devices can find each other, while the TCP probe proves the advertised endpoint is connectable.

### Alternatives considered
- Implement full file transfer immediately.
- Add Wi-Fi Direct before LAN is stable.
- Use manual IP entry for the first milestone.

### Why this was selected
- It follows the project plan's LAN-first sequence.
- It creates a testable discovery and connection foundation without mixing in file I/O, TLS, pairing, or resume state too early.
- It keeps the transfer engine free from NSD-specific types by converting discoveries to `DiscoveredDevice`.

### Revisit when
After two physical devices repeatedly discover and probe each other on the same LAN, implement TLS handshake and one-file transfer.
## ADR-013 - Discovery mode wiring: interface default setMode, policy-scaled BOOST backoff, caps as informational TXT (P3.5-A/B)

### Date
2026-08-23

### Decision
1. FlashRadioTransport.setMode(DiscoveryModePolicy) added to the seam with a **no-op default body**; NsdTransport overrides it. CompositeDiscovery fans out blindly, so future radios (C3.6-C3.8) compile unchanged until they implement modes.
2. BOOST lowers the restart-backoff base by **scaling the injected delay provider** (provider(attempt) * policy.restartBackoffBaseMs / DEFAULT_BACKOFF_BASE_MS) rather than replacing it: injected test/production provider shape is preserved and the cap/maxAttempts stay untouched.
3. ECO duty cycle lives INSIDE the transport's own browse loop (scan burst -> stopBrowse -> idle -> repeat), knobs re-read per iteration; a conflated channel wakes an in-flight idle gap immediately on setMode. A maxDutyCycles constructor bound (default unbounded) exists purely for JVM-test determinism (module has no coroutines-test).
4. TXT caps is **informational only**: mDNS/DNS-SD is unauthenticated (RFC 6762), so advertised capability flags are never an access decision; enforcement is deferred to connect time (C3.10 seam). Inbound rule stays VERSION-only pre-directory.
5. GHOST advertise suppression returns Success(Unit) from startAdvertising as a documented no-op; the composite's isAdvertising consults the policy so state never claims visibility in GHOST.

### Context
Plan P3.5 workstream A (identity hardening) + B2/B3 (mode wiring); contracts FlashDiscoveryMode/DiscoveryModePolicy already existed.

### Consequences
- NsdTxtCodec encode now delegates to core TxtCodec (partial TODO(unify) closure; decode stays tolerant/local).
- statusMessage gains a [MODE] prefix (additive; suffix consumers unaffected).

### Revisit when
Multiple transports implement modes (fan-out semantics may need per-transport acks), or when pairing lands (fp8 becomes a pinning cross-check at C3.10, not just a hint).


## ADR-020 - Phase 8 app shell: dependency-free tab state, custom bottom nav, demo-state substitution contract

### Decision
1. Tab selection is SHELL state implemented as a stack reset: `FlashNavigationState.selectTab(destination)`
   replaces the whole UI-033 stack with one root entry. Tabs never push; Conversation remains the only
   pushed screen. No androidx.navigation adoption change (UI-033 deferral stands; revisit triggers unchanged).
2. Bottom chrome is `FlashBottomNav` (UI-046) � fully custom docked bar (spring indicator pill, icon pop,
   pulse-ring reselect, haptics via choke point). Material NavigationBar is permanently rejected for the
   final UI per AGENTS.md 34; glassmorphism/shader variants documented as rejected in bottom-nav.md
   (dependency cost / API 33+ only).
3. Pages consume DEMO STATE OBJECTS (`TransfersUiState`, `NearbyUiState`, `FlashSettingsModel`) whose shapes
   are declared FINAL now: engine wiring (C5/C3/C2/C1.4) must substitute data sources without changing page
   APIs. This inverts the usual order (engine first) deliberately so Phase-8 UI lands reviewable and the
   engine team gets frozen targets.

### Context
ui-page-plan PART 2 (owner-approved) ordered: shell -> pages -> engine substitution -> device verification.
Subagent outage forced direct implementation; research was still completed per-component before code
(bottom-nav/transfers/nearby/settings docs).

### Alternatives considered
- androidx.navigation + NavigationBar: rejected (34 prohibition on generic M3 chrome; dependency rule).
- Engine-flow-first wiring before any UI: rejected � blocks all UI verification on two-phone availability.

### Consequences
- Back from Conversation always lands on its tab root (predictable); cross-tab conversation continuity is
  intentionally lost until multi-root stacks are proven necessary.
- Demo states may drift if C5/C3 models change shape � changes then REQUIRE updating page-plan P3/P4/P5
  model declarations in the same commit.

### Revisit when
Two-pane expanded layout (UI-034 pass), deep links (notification -> conversation), or >6 destinations.


## ADR-022 - Publishing baseline: Apache-2.0 license + core compileSdk 35 (widest consumer reach)

### Decision
1. **License = Apache-2.0**, copyright "The Flash Project" (`LICENSE` + `NOTICE` at repo root). Resolves the
   Phase 1.1 owner decision.
2. **The published `core:*` modules compile at `compileSdk = 35`** (was 37). The app module and
   `targetSdk = 36` are unchanged; `minSdk = 24` (Android 7.0) is unchanged and already covers the owner's
   "down to Android 8 / API 26" goal. Resolves the Phase 1.3 owner decision.
3. **`net.zetetic:sqlcipher-android` pinned to 4.17.0** (from 4.18.0). 4.18.0 raised its AAR
   `minCompileSdk` to 37; 4.9.0-4.17.0 declare `minCompileSdk=1`. This is the only dependency that blocked 35.
4. **`NsdTransport` `onServiceLost` forward-compat pattern**: keep the API-34 no-arg `override`, demote the
   API-37 `onServiceLost(NsdServiceInfo)` to a non-`override` method so it compiles at 35 yet still binds the
   Android-17 framework method at runtime by JVM signature.

### Context
Owner wants the LAN-transfer engine published as a free, reusable library (GitHub -> JitPack -> Gradle) that
any developer can consume. compileSdk 37 (Android 17) + AGP 9.3.1 forced consumers onto bleeding-edge build
tooling; lowering the library's compileSdk to 35 widens the consumable toolchain to the AGP 8.7 era without
touching runtime behavior (compileSdk is a compile-time API ceiling, not a runtime floor). "Free" was the
owner's explicit goal - Apache-2.0 gives unrestricted commercial/derivative use plus a patent grant, unlike
the copyleft (GPL/LGPL/MPL) options that would deter embedding the library.

### Alternatives considered
- **MIT license**: equally permissive and shorter, but no patent grant and not the plan's assumed standard -
  rejected in favor of Apache-2.0's patent protection and ecosystem alignment.
- **GPL/LGPL/MPL**: copyleft obligations kill library adoption - rejected outright.
- **Keep compileSdk 37**: narrowest reach (AGP 9.3+/Gradle 9.5/Kotlin 2.2 required of every consumer) -
  rejected; the whole point of publishing is external consumption.
- **compileSdk 36**: viable fallback if a 35-incompatible dep had appeared. Only sqlcipher blocked 35 and a
  one-patch downgrade cleared it, so 35 (wider reach) stands. 36 is the fallback if a future dep floors at 36.
- **Lower minSdk/targetSdk too**: unnecessary - minSdk 24 already exceeds the Android-8 goal, and lowering
  targetSdk weakens the app's behavior contract for no consumer benefit.

### Consequences
- SQLCipher stays a patch behind latest; revisit if 4.18+ ships a needed fix. core:persistence only.
- compileSdk 35 means new Android-16/17 compile-time APIs are unavailable to core modules until a consumer
  base justifies raising it; none are currently used (highest runtime gates are API 33/34 with legacy paths).
- The NsdTransport method is intentionally not marked `override` - a future compileSdk bump to 37 should
  restore `override` and delete the no-arg variant only after confirming API 34-36 consumers are dropped.

### Revisit when
Phase 4 decides the final published module set (persistence may leave the transfer path entirely, removing
the SQLCipher constraint), or a consumer needs an Android 16/17 compile-time API, or the AGP/Gradle floor is
raised deliberately.

## ADR-023 - Published-ABI enforcement is `explicitApi()` (strict), not binary-compatibility-validator

### Decision
The kotlinx **binary-compatibility-validator** (BCV) plugin is **removed** from the build. The published
`core:*` ABI is instead enforced at the compiler by Kotlin **`explicitApi()` in strict mode**, enabled in
every `core/*` module. Phase 3 Task 3.1 (a checked-in `.api` dump per module) is therefore **withdrawn**;
Tasks 3.2/3.3 (explicit-visibility classification) fully deliver the phase goal on their own.

### Context
Task 3.1 planned to apply BCV at the root and commit `core/*/api/*.api` dumps as the reviewable source of
truth for the public ABI (feeding Phase 2.2 leak-detection). On execution the plugin (v0.18.1) applied
without error but registered **no tasks**: `./gradlew apiDump` and `apiCheck` both fail with "Task not
found". BCV wires its per-project tasks off the classic `org.jetbrains.kotlin.{jvm,multiplatform}` /
`kotlin-android` plugin's source sets. This project uses **AGP 9.3.1 with built-in Kotlin** and no classic
Kotlin Gradle plugin, so BCV finds no source sets to snapshot on the Android library variants and stays
inert. Its Android support has never targeted AGP's built-in-Kotlin variant model.

The phase's actual goal — "stop shipping the entire implementation as public API" — is achieved by
`explicitApi()` strict, which the compiler enforces on every declaration: no symbol reaches the ABI without
a deliberate `public` / `internal` / `@FlashInternalApi` decision, or the module fails to compile. That is a
stronger, always-on guarantee than a dump that can drift until someone reruns `apiCheck`.

### Alternatives considered
- **Keep BCV applied but inert**: dead plugin + `apiValidation {}` block implying ABI tracking that does not
  exist — misleading. Rejected; removed alias, `apiValidation` block, and the `libs.versions.toml` entry.
- **Add the classic `kotlin-android` plugin alongside AGP built-in Kotlin just to feed BCV**: two Kotlin
  toolchains in one build is fragile and risks version skew against AGP 9.3.1. Not worth a text dump.
- **Hand-maintain `.api` files**: no tooling to diff them against reality — worse than nothing.

### Consequences
- No committed `.api` baseline and no `apiCheck` gate. ABI regressions are caught at compile time
  (explicitApi errors) and in review, not by a mechanical diff. Acceptable for a single-owner library.
- Phase 3 acceptance is restated: **`explicitApi()` strict active and green in all 8 `core/*` modules**
  (common, messaging, engine, discovery, persistence, security, transfer, network — all verified green).
  The `apiDump`/`apiCheck` acceptance lines in PHASE-03 are superseded by this ADR.
- Phase 2.2 leak-detection loses its automated dump input; leaks are instead surfaced by explicitApi's
  "public-exposes-internal" (`EXPOSED_*`) compile errors, which force the promote-to-`api`-dep decision at
  the point of the leak.

### Revisit when
The build migrates to a classic Kotlin Gradle plugin (JVM/MPP/kotlin-android) — BCV would then register its
tasks and a committed `.api` baseline becomes worthwhile — or a maintainer team larger than one makes a
mechanical ABI-diff gate worth the tooling.

## ADR-024 - Persistence decoupling: transfer & security own storage ports; Room adapters live in core:engine

### Decision
`core:transfer` and `core:security` **no longer depend on `core:persistence`** (and therefore no longer
drag Room / SQLCipher onto their classpaths). Storage is inverted behind ports:
- `core:transfer` owns `TransferStore` (a plain `suspend` interface, zero Room types). `core:engine`'s new
  `RoomTransferStore` adapts `TransferDao`/`TransferChunkDao` to it. The repository takes a nullable
  `store: TransferStore?` — `null` means "run without persistence" (resume-across-restart disabled), the
  pre-existing DB-less behavior.
- `core:security` dropped persistence entirely by **deleting the unused `RoomTrustedStore`** adapter. It was
  `internal`, had no construction site anywhere, and was superseded by the SharedPreferences-backed
  `AndroidPreferencesTrustStore` that the app actually wires. The pin-decision logic (`TofuPolicy`) and the
  legacy-migration logic (`LegacyTrustMigration`, with `FlashTrustedPeer` relocated beside it) stay in
  security — they are pure, Room-free, and still tested.

### Context
The publishing goal is a lightweight `core-transfer` a LAN-only consumer can adopt without shipping four
SQLCipher native ABIs. Transfer's *direct* `implementation(project(":core:persistence"))` was the obvious
coupling, but removing it alone was insufficient: `./gradlew :core:transfer:dependencies` still showed
`androidx.room` + `net.zetetic:sqlcipher-android` because **`core:transfer → core:security → core:persistence`**.
Security's only persistence use was the dead `RoomTrustedStore`, so deleting it (plus security's direct
`libs.androidx.room.runtime`) severed the last edge.

Placing `RoomTransferStore` in `core:persistence` was impossible: `security → persistence` and
`transfer → security` mean a persistence-side adapter that touches transfer would form the cycle
`persistence → transfer → security → persistence`. `core:engine` already `api`s both transfer and
persistence and nothing depends back on it, so it is the correct home for both Room adapters.

### Consequences
- `./gradlew :core:transfer:dependencies` shows **no room / sqlcipher** on any configuration
  (releaseCompileClasspath and debugRuntimeClasspath both verified clean). Transfer's `.api` exposes only
  `TransferStore`, not DAO types.
- Removing persistence from security also removed the transitively-provided `kotlinx-coroutines`. Security
  now declares `libs.androidx.lifecycle.runtime.ktx` directly (same source the other core modules use for
  `Flow`/`StateFlow`) — no behavior change, just an explicit edge that was previously leaking in via Room.
- The app wires `store = RoomTransferStore(db.transferDao(), db.transferChunkDao())` in
  `DiscoveryEngineHolder`; the trust store there was already `AndroidPreferencesTrustStore`, so the sample
  app's behavior is unchanged (assembleDebug green).
- The C2.4 Room-pinning store is gone from the tree but recoverable from git history if that feature is
  ever wired; the reusable pieces (`TofuPolicy`, `LegacyTrustMigration`) were kept.

### Revisit when
A future feature genuinely needs a Room-backed trust store: reintroduce it as an adapter in `core:engine`
(implementing a security-owned port), never by re-adding `persistence` to `core:security`.



## ADR-025 - Voice/video calling: WebRTC media via shepeliev/webrtc-kmp, signaling over the WS mesh

### Decision
1. Add 1:1 voice/video calling as two new modules: `:core:calling` (headless call engine,
   `explicitApi()`, compileSdk 35 per ADR-022) and `:ui:callui` (Compose call screen,
   UI-050, see `docs/ui/calling-ui.md`). Both publish, as `core-calling` and `ui-callui`. The
   surface is two interfaces - `FlashCalling` (control plus the two signaling seams) and
   `FlashCallMedia` (read-only tracks and live quality metrics) - enumerated in
   `docs/architecture/public-api.md` SS7 and SS13.
2. `:core:calling` sits **outside** the `:core:engine` facade: `FlashEngine` has no `calls`
   property and `:core:engine` has no dependency on calling. A call needs a signaling channel
   the host already owns, runtime mic/camera grants, and a `microphone|camera` foreground
   service only an app's own manifest can declare - none of which `Flash.create` can supply.
   It also keeps ~30 MB of native WebRTC per ABI out of every app that never calls.
   **SUPERSEDED IN PART by ADR-033 (2026-09-11):** `FlashEngine` now has `calls` and `:core:engine`
   now depends on `:core:calling` — as `compileOnly`, so the "outside the facade" outcome this point
   was protecting (the host owns the engine, permissions and foreground service; WebRTC stays out of
   the umbrella's published metadata) is preserved without keeping calling unreachable.
3. Media transport: WebRTC via `com.shepeliev:webrtc-kmp:0.125.11` (M125, MIT; wraps
   `io.github.webrtc-sdk:android:125.6422.06.1`, BSD-3). Audio + video tracks over a
   `PeerConnection` with **empty `iceServers`** - Flash is LAN/hotspot-only, so host
   candidates suffice; no STUN/TURN is deployed or required.
4. Signaling: SDP offers/answers and ICE candidates ride the existing WebSocket mesh as
   text frames under a new `FLASH_CALL` prefix (see `docs/protocol.md` Calling section),
   encoded with `FlashTextFraming` exactly like chat/pairing frames, with the SDP body
   **base64-encoded** (RFC 4648) so no escaping or trimming artifact can corrupt it
   (ERROR-024); decode accepts raw text too, for builds that predate the change. ICE
   candidates are trickled with buffering until the remote description is set (webrtc-kmp
   sample pattern).
5. Call lifecycle: `CallCoordinator`, in `:core:calling`, is the `FlashCalling`
   implementation - process-level, mirroring the `DiscoveryEngineHolder` holder pattern - and
   owns one `FlashCallSession` at a time. States are DIALING -> RINGING -> CONNECTING ->
   ACTIVE -> ENDED, where a failure is an `endReason` on ENDED rather than a separate state,
   so the UI has one terminal branch to render. A second invite arriving while a call is live
   is auto-declined "busy" rather than queued, so the other caller's UI never hangs on DIALING.
6. Android compliance: the call runs inside a dedicated foreground service with
   `microphone|camera` types, started **while the app is foreground** (user taps call /
   answers from the incoming-call notification) - the only legal way to start a
   microphone/camera FGS under the while-in-use restrictions. `Notification.CallStyle`
   (API 31+) styles incoming/ongoing call notifications; pre-31 falls back to a standard
   FGS notification. CAMERA + RECORD_AUDIO runtime permissions are requested at call time
   (webrtc-kmp throws `CameraPermissionException`/`RecordAudioPermissionException` from
   `getUserMedia` if missing).
7. Audio routing is the app's job, not the module's (webrtc-kmp ships no `AudioManager`
   policy), and it is load-bearing rather than cosmetic: `FlashCallAudioRouter` in `:app`
   takes voice-communication focus, then sets `MODE_IN_COMMUNICATION`. Without the mode the
   platform treats the call as media playback - no hardware AEC on capture, a long playout
   buffer, and the earpiece is not even a routing candidate. Focus is requested *before* the
   mode because from Android 12 an app owning neither focus nor a telecom call may not set it.
   Routing priority with the speaker off is Bluetooth SCO -> BLE headset -> hearing aid -> USB
   -> wired -> earpiece, re-applied from an `AudioDeviceCallback` so a mid-call hot-plug moves
   the audio. Bluetooth is version-split: API 31+ uses `setCommunicationDevice` (which brings
   SCO up as a side effect), below 31 SCO is started by hand and `setBluetoothScoOn(true)` is
   deferred until the headset broadcasts CONNECTED - setting it early is the classic silent-
   Bluetooth bug. The router is attached for every state except RINGING (exclusive focus would
   silence the incoming-call ringtone) and ENDED, and every platform call is best-effort:
   `MODIFY_AUDIO_SETTINGS` is required and OEM HALs refuse mode changes in undocumented states,
   so a call with mediocre routing must still beat a crash.
8. Latency and quality knobs, all of them chosen because the wrapper exposes no API for them:
   the low-latency audio device module is configured once before any `PeerConnectionFactory`
   exists (after that the default ADM is permanent for the process); SDP is rewritten
   symmetrically on local *and* remote descriptions for Opus `ptime=10` + `minptime=10`, pinned
   inband FEC and DTX off, plus `x-google-start/min/max-bitrate` at 2500/600/8000 kbps; capture
   is requested at 1920x1080@30 with `DegradationPreference.MAINTAIN_FRAMERATE` and an explicit
   sender bitrate window, so a constrained link sheds *resolution* (1080p -> 720p -> 540p) and
   keeps 30 fps; `getStats()` is sampled once a second and published through
   `FlashCallMedia.stats` for the in-call quality badge.
9. Call log rows: when a session terminates the coordinator emits a `FlashCallLogEntry` through
   an `onCallLog` callback and the host writes the chat row itself. Both devices already hold
   every field when a call ends, so each derives its own row - no new wire frame, and no
   `core:calling` -> `core:messaging` dependency (the ADR-024 inversion).

### Context
Flash's chat and file transfer already run over the WS mesh (ADR-016). Calling is the
last major real-time feature. WebRTC is the only practical way to get Opus audio + VP8/H264
video with jitter buffers, echo cancellation, and hardware codecs on Android without
writing a media stack. ADR-016 deferred "WebRTC Data Channels" for *file transfer* because
WS already covers it - that deferral stands; this ADR is about *media*, a different use
case where WebRTC is the right tool and WS is only the signaling channel.

### Alternatives considered
- Raw audio over WS (PCM/G.711 chunks): rejected - no echo cancellation, no jitter
  buffer, no video path, 10x the bitrate of Opus; would need a media engine anyway.
- `webrtc-sdk:android` (prebuilt Google artifacts) directly: rejected - Java API only,
  verbose SDP/callback plumbing; webrtc-kmp wraps the same native stack with suspend +
  Flow APIs and multiplatform surface, MIT-licensed, actively maintained (M125, 2025-09).
- `stream-io`/proprietary calling SDKs: rejected - ADR-003 clean-room rule; cloud
  dependency contradicts Flash's serverless P2P premise.
- SIP/RTP stacks (e.g. pjsip): rejected - far heavier, telephony-oriented, no video
  story as clean as WebRTC's.

### Consequences
- New dependency `com.shepeliev:webrtc-kmp:0.125.11` (+ transitive
  `io.github.webrtc-sdk:android:125.6422.06.1`, ~30 MB native ABIs). App-only consumers
  of `:core:calling` pay this cost; the other core modules stay WebRTC-free.
- webrtc-kmp auto-initializes via androidx.startup (`WebRtcInitializer`); no manual init
  call needed. `WebRtc.rootEglBase` backs the video renderers.
- Known dexing hazard with the WebRTC AAR (Egl14 `NoSuchMethodError`, Google issue
  265195801): if `:app` dexing fails, add `android.useFullClasspathForDexingTransform=true`
  to `gradle.properties`.
- SDP offers are ~4-8 KB text frames - fits the WS text frame path fine (chat already
  sends multi-KB messages), and base64 grows them by a third with no protocol change.
- Only `:app` routes `FLASH_CALL` frames: `DiscoveryEngineHolder.handleInboundText` tries
  `CallFrameCodec.decode` first (calling is the most latency-sensitive frame class) and hands
  the text to `CallCoordinator.onInboundText`. `:core:engine`'s own `handleInboundText` has
  **no** call branch and cannot have one - it does not depend on calling - so a library consumer
  wiring calling on top of `Flash.create` must chain `onInboundText` itself, which is exactly
  what that method's boolean return is for.
- `FlashCallMedia` exposes webrtc-kmp's `VideoTrack` directly. This is the one place Flash
  lets a third-party type through a published boundary: a renderer has to be handed the real
  track, and any wrapper would have to expose it again to be useful. `:core:calling` therefore
  `api()`s webrtc-kmp and `:ui:callui` `api()`s `:core:calling`, so both the type and
  `SurfaceViewRenderer` resolve for a downstream consumer.
- **Not implemented: the trust gate.** This ADR originally required calls only to
  paired/trusted peers. Nothing in the shipped path checks trust - the call buttons live in the
  conversation header, and inbound `FLASH_CALL` frames are routed for any peer with a live WS
  session. The practical bound today is "reachable on the LAN and connected", not "paired".
  Adding it means gating `startCall` and the inbound invite on `FlashTrustStore`, which
  `:core:calling` cannot reach without a new port; until then the gap is real and stated here
  rather than implied to be closed.

### Revisit when
- Wi-Fi Direct transport lands: verify host-candidate ICE still connects over the P2P
  group interface (expected yes; both peers are on-link).
- The trust gate is closed: decide whether `:core:calling` takes a trust port (a
  `(peerId) -> Boolean` predicate consulted by `startCall` and the inbound invite) or whether
  gating stays the host's job. A port keeps the policy testable on the JVM; leaving it to the
  host keeps the module free of a security dependency.
- Remote-relay or internet calling is ever considered: STUN/TURN and a rendezvous server
  become mandatory; this ADR's LAN-only ICE assumption breaks.
- Group calls: multi-peer topology (mesh vs SFU) needs its own ADR.

## ADR-026 - Duplicate-session tiebreaker: deterministic originator-id comparison resolves connect glare

### Decision
When `WsFlashNetwork.registerSession` finds a duplicate session for the same peer with
**equal** transport rank, the incumbent is no longer chosen by arbitrary arrival order (a
coin flip from the two phones' perspective). Instead both ends of the same TCP pair apply
the same deterministic rule:

> Keep the session whose *originator device id* is lexicographically smaller. Originator is
> `localDeviceId` for outbound sessions, `peerDeviceId` for inbound sessions.

`WsSession` carries a new `isOutbound: Boolean = false` flag so the session manager knows
which side originated the socket. Because A's outbound *is* B's inbound (same TCP pair),
both phones observe the same two ids and compute the same winner, so the surviving socket
stays live on both sides.

The auto-connect sweep additionally skips peers with an in-flight reconnect
(`isReconnectInFlight`) so the two dial engines (gated 5s sweep and the #18 reconnect
engine) never race the same peer in the first place.

### Context
After a session drop, both the gated 5s auto-connect sweep and the ungated #18 reconnect
engine dial the same peer; both phones dial each other → connect glare. Each
`registerSession` runs under its own per-process `registryLock` (no cross-device
coordination), so each admits its own outbound dial first; the peer's inbound dial hits
`SessionHardeningPolicy.resolveDuplicate` with equal LAN rank (0=0) → `KeepExisting` → the
inbound socket is closed. The tie was a coin flip: ~50% of the time A keeps its outbound
(TCP pair #1) while B keeps its outbound (pair #2) — but pair #1 is B's inbound (B closed
it) and pair #2 is A's inbound (A closed it). Both surviving "sessions" sat on dead sockets
→ both scheduled reconnect → glare again → infinite ~2s storm ("WS connecting" storms,
"cannot reach" errors, online/offline flicker). Full root cause in ERROR-023.

### Alternatives considered
- **Keep the coin flip (status quo):** rejected — it is the bug. No data existed to break
  the tie deterministically.
- **Prefer the inbound (newer) session unconditionally:** rejected — both devices would
  then keep their *inbound* sockets (each device's inbound is the other's outbound, which
  the other device closed) → the mirror-image dead-socket storm.
- **Prefer the outbound unconditionally:** rejected — symmetric deadlock for the same
  reason in reverse.
- **Compare transport-level tiebreakers (port numbers, connection timestamps):** rejected —
  not shared/consistent across both devices; only device ids are common to both endpoints
  of a TCP pair.
- **Coordinate glare across devices (e.g. a lock frame):** rejected — adds a round trip to
  every connect and a failure mode (lock lost); the pure-deterministic rule needs no
  coordination.

### Why originator-id comparison was selected
Device ids are the only datum both endpoints of a TCP pair share and agree on, and the
comparison is stable across reconnects. Both ends compute the same winner with no extra
wire traffic and no timing dependence. `SessionHardeningPolicy.resolveDuplicate` keeps its
`KeepExisting` on equal-rank behavior (stability wins when there is no glare); the glare
tiebreaker is layered on top for equal-rank duplicates specifically.

### Consequences
- `WsSession` gained `isOutbound`; `registerSession` applies `resolveGlareTie` for
  equal-rank duplicates. `SessionHardeningPolicy` KDoc documents the layered rule.
- The sweep dedup (`isReconnectInFlight` skip) reduces the number of simultaneous dials, so
  glare becomes rarer even before the tiebreaker engages.
- A glare regression test (`testConnectGlareConvergesOnSingleLivePair`) asserts exactly one
  live session per side, A holds outbound (smaller id), B holds inbound, message
  round-trips, no reconnect storm.

### Revisit when
Cross-device session coordination (e.g. a connection-ownership frame) is ever built, or if
a multi-link transport makes "same TCP pair" no longer the unit of comparison.

## ADR-027 — Base64-encode SDP in call frames to harden the text-framing transport

### Decision
`CallFrameCodec` (the `FLASH_CALL` wire codec) base64-encodes the `sdp` field of
Offer/Answer frames on encode and base64-decodes on decode. Encoding uses a new
pure-Kotlin RFC 4648 codec in `core/common` (`Base64.kt`); decode tries base64 first and
falls back to raw text for legacy pre-hardening peers. `FlashCallSession` additionally
wraps every set-SDP flow in try/catch so a native failure ends the call cleanly instead
of crashing the process.

### Context
Both phones crashed with `java.lang.RuntimeException: Setting SDP failed:
SessionDescription is NULL.` the moment a call was accepted. Disassembly of webrtc-kmp
0.125.11 (`PeerConnection$setSdpObserver$1.onSetFailure`) proved the message is
libwebrtc's native JNI error, emitted when the `org.webrtc.SessionDescription`'s
`description` is null/empty at JNI-call time or fails native SDP parse. Our API usage was
correct (verified against the same bytecode). The SDP rides the WS mesh as a
`FLASH_CALL` text frame through `FlashTextFraming`, which escapes only `%`/space/`=`
and does `text.trim().split(' ')` — whitespace/multi-line SDP is precisely the payload
that framing can corrupt (ERROR-024).

### Alternatives considered
- **Fix the framing layer (escape CR/LF, no global trim):** rejected as the primary fix —
  `FlashTextFraming` is shared by chat/pairing frames and its quirks are load-bearing for
  those; changing it risks regressing discovery/chat. Base64 isolates the fix to calling
  with zero framing changes.
- **`android.util.Base64` / `java.util.Base64`:** rejected — `core/common` is pure JVM
  with `minSdk 24` + `explicitApi()`; Android's codec breaks JVM unit tests and
  `java.util.Base64` requires API 26+. Pure-Kotlin base64 is the only option that keeps
  `CallFrameCodec` tests running on the JVM.
- **XML/JSON envelope for SDP:** rejected — far heavier for a LAN-only 4-8 KB payload;
  base64 is whitespace-free by construction and trivially reversible.

### Consequences
- `CallFrameCodec` Offer/Answer frames carry base64 SDP; `decodeSdp` handles both base64
  and legacy raw payloads (real SDP starts with `v=0`, not valid base64, so the fallback
  is unambiguous in practice).
- `FlashCallSession` SDP flows are exception-hardened: `CancellationException` rethrown,
  everything else logged + `end(ERROR, notifyPeer=true)`.
- Round-trip tests assert SDP survives encode→decode **byte-for-byte**.
- Wire format is no longer backward-compatible for Offer/Answer SDP content, but legacy
  peers still decode (raw fallback) — no coordination required to upgrade.

### Revisit when
A native set-SDP failure is reproduced on device with diagnostics and the real
corruptor (if any framing edge case remains) is identified; or if the transfer protocol
ever moves to binary frames (ADR-014-style) where SDP can ride as opaque bytes directly.

## ADR-028 — Three device performance tiers, auto-detected each boot, delivered to every consumer as a lambda

### Decision
`:core:common/perf` owns a single `FlashPerformanceMode` enum — `LOW`, `MEDIUM`, `HIGH` — and each
constant carries the whole envelope for that tier: a `FlashVoiceProfile` (Opus `ptimeMs`, DTX), a
`FlashVideoProfile` (capture size, fps, bitrate seeds), a `FlashTransportProfile` (eight keepalive /
recovery timings) and two UI verdicts, `reduceMotion` and `minimalChrome` (both `this != HIGH`).
`HIGH`'s numbers are the pre-tiering constants verbatim, so that tier is provably a no-op.

Four rules govern how the tier is obtained and consumed:

1. **Auto-detected, never persisted.** `FlashPerformanceClassifier` reads a platform-free
   `FlashDeviceProfile` (RAM, API level, screen pixels, CPU cores, codec support) once per process. Any
   one **hard gate** is conclusive for `LOW`; the weaker signals only demote to `MEDIUM` once **two**
   agree; unknown values never demote. An **unset** preference *is* auto — there is no first-run flag.
2. **The user can pin a tier, and null means auto.** `FlashPerformanceMode?` in DataStore;
   `fromKey` maps `"auto"` and any unrecognised token to null.
3. **Consumers read a lambda, never a stored value.** `() -> FlashPerformanceMode`, defaulted to
   `{ HIGH }`.
4. **For the UI, the tier is a floor, not a vote.** `FlashMotionPolicy.resolveReduceMotion` is
   `mode.reduceMotion || (overrideForcesReduce ?: systemReduceMotion)`.

### Context
Field testing on a BelFone SCP810 (2 GB, API 27, 480x640, 2.4 GHz-only, no 802.11k/v/r) on a mesh
Wi-Fi produced lag, lost connections and large latencies, while a Pixel 7 and an Infinix X6882B on the
same network were fine at long distances (EXP-006, ERROR-033). Voice-only calls still lagged at
25 kbit/s of speech, which rules out bandwidth: the constraint is the **packet rate** against 802.11's
largely fixed per-frame airtime cost. Meanwhile video capture was `1920x1080@30` on every device,
unconditionally — ≈62 Mpixel/s of CPU work on a handset whose own display is 480x640, spent upstream
of the encoder and therefore invisible to both existing adaptive mechanisms. The app had exactly one
performance profile and it was written for the phones in the developer's hand. The owner asked for
three modes that "detect automatically on first run", with animations off and "extreme minimalist" UI
for the lower two, and video capped at "540p and below".

### Alternatives considered
- **Keep one profile and lean harder on the existing adaptive mechanisms.** Rejected: WebRTC's
  `MAINTAIN_FRAMERATE` degradation and Flash's own `CallQualityGovernor` (ERROR-031) both act on the
  **encoder**. The capture-side megapixels and the per-packet header tax are upstream of it and are
  paid whether or not the encoder sends a byte. Reactive control cannot recover a cost already spent.
- **Lower the Opus bitrate again.** Rejected on arithmetic: at 25 kbit/s of speech the RTP+UDP+IP+SRTP
  headers alone were ~40 kbit/s at 100 packets/s. Halving the payload barely moves the airtime bill,
  because the bill is per frame. `ptimeMs` is the knob; bitrate is not.
- **Persist the detected tier on first run behind a first-run flag.** Rejected as a mechanism that must
  be maintained and can go stale. An unset preference already *is* auto and auto is re-resolved every
  boot, so a device that gains a capability — or an OEM update that fixes an under-reported
  `totalMem` — is simply re-read. A persisted verdict would also survive a build whose classifier
  thresholds changed, which is the worst case: silently wrong and invisible.
- **A `FlashPerformanceMode` value injected at construction instead of a lambda.** Rejected: a tier
  change (auto-detect resolving, or the user pinning a mode) must reach the *next* call without
  re-wiring anything, and `:core:calling`/`:core:network` must keep knowing nothing about DataStore
  (ADR-024). A lambda satisfies both; a value satisfies neither.
- **Treating the tier as one more input to the reduce-motion decision.** Rejected: on hardware that
  earns `LOW`, the animation **is** the jank, so the tier must win over both the platform's animator
  setting and the user's own preference. Hence a floor rather than a vote. The inverse — letting a user
  *force* motion on at `LOW` — was considered and dropped: it exists only to let someone make their own
  device worse.
- **A symmetric ratio, e.g. "scale everything by 0.5 on weak devices".** Rejected: the three costs move
  independently. `LOW` drops to 360p **15** fps (pixels/s is the binding constraint where there is no
  usable hardware encoder) while its Opus frame goes *up* to 60 ms (packets/s is the binding constraint
  on the radio). One scalar cannot express that.
- **Demoting to `MEDIUM` on a single weak signal.** Rejected: `MEDIUM` disables animation everywhere,
  and one under-reported figure should not cost every user their UI. Hence the asymmetry — hard gates
  are conclusive alone, weak signals need two.

### Consequences
- `:core:calling` reads the tier for capture size (`getUserMedia`), Opus packetization, stats cadence
  and the ICE-restart floor; `:core:network` reads it for keepalive cadence, the link-change probe
  window and the reconnect ceiling (8 s at `LOW`, down from 30). `:core:persistence` gains a
  `performance_mode` key. None of them gains a dependency.
- The UI half is one change at one place: `MainActivity` resolves the boolean and passes it to the app's
  single `FlashTheme(...)`, which reaches all ~26 existing `FlashTheme.motion` call sites at once.
  `FlashTheme` also gained `minimalChrome`, deliberately **distinct** from reduce-motion because a
  drop-shadow costs the same on a still frame as on a moving one. A `minimalChrome` call site must draw
  the flat equivalent, never nothing: it is a budget for ornament, not for information.
- `:ui:theme` depends on `:core:common` with `implementation`, not `api`, and `FlashMotion`'s
  constructor is `internal`. So the tier cannot cross that seam as a type — only as a resolved
  `Boolean`, through the new `rememberFlashMotion(reduceMotion)` overload. This is why the policy lives
  in `:core:common` as a pure function, which also makes it testable.
- Settings gains a PERFORMANCE section with **four** segments, because "Auto" is not a fourth tier but
  the absence of a pin and has to be reachable again after pinning. Its subtitle is derived from the
  profile values, so the user-facing description of what a tier costs cannot drift from what it does.
- Classification happens on real hardware and can be wrong. `FlashPerformanceVerdict.reason` carries the
  deciding evidence and is logged at boot, and the pin exists as the escape hatch — including for the
  case auto-detect structurally cannot see, which is the **link** rather than the handset.

### Revisit when
A device below `LOW` is actually in hand — the owner named "devices lower than the Belfone, and
possibly an Android watch", and a watch tier would be voice-only by construction rather than a fourth
set of numbers. Also revisit if the classifier is ever observed misclassifying a real device (the
thresholds are the guessable part and should move with evidence, not with taste), or if a runtime signal
worth trusting appears — sustained thermal throttling, or a measured encoder throughput — at which point
the tier could become dynamic rather than boot-time. Do **not** revisit by adding a fourth enum constant
for a device nobody has measured.

## ADR-029 — Per-endpoint SDP asymmetry: `tuneLocal` asserts our tier, `tuneRemote` reconciles the peer's

### Decision
`CallSdp.tune()` is replaced by two functions with different jobs:

- **`tuneLocal(sdp, mode)`** writes *our* tier into the description we are about to send: `a=ptime:`,
  and `minptime`/`usedtx` merged in place into the existing Opus `a=fmtp:` line, plus the tier's
  `x-google-{start,min,max}-bitrate` on each video codec.
- **`tuneRemote(sdp, mode)`** reads the peer's description as a *declaration* and reconciles it against
  ours by taking the **longer** Opus frame and the **smaller** bitrate ceiling of the two.

Non-Opus payload types and the `red`, `rtx` and `ulpfec` lines are left alone by both.

### Context
The pre-tiering `tune()` was applied symmetrically to the local and the remote description, and that
was correct while every device ran identical numbers: wire content then could not depend on which end
had a switch flipped (ERROR-031, rejected item 8). ADR-028 breaks that premise — a `LOW` handset and a
`HIGH` phone now legitimately want different packetization, and asserting our own tier onto the peer's
description would mean each end believed something different about the session.

### Alternatives considered
- **Keep `tune()` symmetric and let each end assert its own numbers.** Rejected: the two endpoints
  would disagree about `ptime`, which is exactly the parameter that decides the packet rate the weaker
  radio cannot afford.
- **Negotiate a tier explicitly in the `FLASH_CALL` protocol.** Rejected as unnecessary: SDP already
  carries `ptime`/`minptime`/`usedtx` and bitrate hints, so the declaration is on the wire already.
  Adding a tier field would be a second source of truth and a wire-format change (R8).
- **Take the *stronger* side's parameters.** Rejected: the constraint is the weaker link, and a call is
  only as good as the endpoint that cannot keep up. Longer frame, smaller ceiling — always.
- **Let the tiers differ and simply accept it.** Rejected: WebRTC would apply whatever each side set,
  and the resulting asymmetry is the hard kind to debug — audio flows, sounds wrong on exactly one
  device, and the SDP looks valid at both ends.

### Consequences
- Both endpoints converge on byte-identical Opus parameters whichever of them offered, and a test pins
  that (`CallSdpTest`, 16 → 24 tests).
- Keepalive cadence is deliberately **not** reconciled: it stays per endpoint. A `LOW` device pings every
  15 s and forgives 40 s of silence while its `HIGH` peer pings every 10 s and forgives 25 s. Each end is
  describing its own tolerance for its own radio, and each end's pings feed the *other* end's watchdog,
  so the asymmetry is correct there — the distinction is that keepalive is local policy while `ptime` is
  shared session state.
- A future debugging session must read `a=ptime` in the **answer**, not the offer: a peer that re-offers
  10 ms framing undoes the packet-rate fix invisibly, and the symptom is indistinguishable from the
  original bug.

### Revisit when
A third endpoint enters a session (any form of conferencing), where pairwise reconciliation stops being
well-defined and the rule has to become "the weakest participant" across a set; or if Flash ever needs
to negotiate something that is genuinely asymmetric by design, such as simulcast layers, in which case
"take the smaller of the two" is no longer the right primitive.

## ADR-026 - Voice-call quiet: ECO discovery, slowed transfer telemetry, isolated stats sampler, tiered playout buffer, coalesced call-screen ticks

### Date
2026-09-07

### Decision
During an ACTIVE call the stack yields the radio and the scheduler to voice:
1. Discovery drops to ECO (advertising continues; browse duty-cycles; auto-connect sweep skipped) and
   is restored to STANDARD on the leaving-ACTIVE edge (`DiscoveryEngineHolder.setCallActive`).
2. Live transfer dispatchers slow their progress watcher 10 ms → 250 ms
   (`MultiStreamDispatcher.quietWatcherHint`, driven by public
   `RealFlashTransferRepository.voiceCallActive`). Telemetry only — ACK ingestion and transmission
   are untouched.
3. The `getStats()` sampler runs on a dedicated single daemon thread owned by the call session
   (created on first arm, closed in `releaseMedia`) instead of the shared `Dispatchers.Default` pool.
4. LOW-tier devices skip the low-latency ADM (`FlashWebRtcEngine.configureOnce(..., lowLatencyPlayout)`);
   the default ADM's stable buffering replaces underrun-driven NetEQ stretches.
5. The call-screen clock ticks on wall-clock second boundaries and the status live-region announces
   transitions only (no per-second accessibility event while ACTIVE). No pixel or tier change.

### Context
Field report: unstable, fluctuating voice latency between two low-end devices. Logcat showed a
`LowLatencyAudioBufferManager` underrun with buffer growth — playout starvation, not network loss.
Audit found five compounding in-app sources (radio airtime from discovery/dials, 100 Hz transfer
telemetry, shared-pool stats sampling, forced small playout buffer, uncoalesced 1 Hz tickers).

### Alternatives considered
- **Pause transfers during calls**: rejected — user data must keep moving; slowing telemetry buys
  nearly all of the scheduler relief with none of the UX cost.
- **Keep stats on the shared pool and just sample slower**: rejected — preemption works both ways;
  the sampler both steals quanta and is itself jittered, corrupting the governor's inputs.
- **Drop to GHOST instead of ECO**: rejected — the device must stay visible to its mesh while on
  a call; ECO keeps advertising.
- **Coroutines-only stats isolation (`Dispatchers.IO.limitedParallelism(1)`)**: rejected — still
  shares pool threads with Room/SQLCipher; a single owned thread is the actual isolation.

### Consequences
- First production thread pool in the tree (previously coroutines-only); exactly one thread, one
  job, daemon, closed per call. The held-open-resources inventory in `logs/handoff.md` now lists it.
- `RealFlashTransferRepository` gains one public `var` (non-breaking addition); `configureOnce`
  gains one defaulted param (source-compatible).

### Revisit when
On-device measurements (EXP-007) show which of the five dominates; the 250 ms quiet cadence and
the ECO-during-call policy are the first knobs to retune against that data.

## ADR-030 — Ad-hoc trusted groups: versioned membership log, per-member quorum delivery, holder-coordinated catch-up

### Date
2026-09-08

### Decision
Group chat (Phase 1) lands as an operation-log projection over four new text-frame prefixes
(`FLASH_GROUP`/`FLASH_GMSG`/`FLASH_GRCPT`/`FLASH_GREAD`, plus wire-reserved `FLASH_GSYNC`):
1. **Membership is versioned, not union-merged.** Every membership frame carries
   `(opId, version)`; a row changes only when the candidate compares strictly greater. A leave
   is a tombstone that stale/replayed adds cannot resurrect — resolving the draft plan's
   "set-union vs leave" contradiction in favor of leave-wins-until-re-add (owner-confirmed).
2. **Trust is fail-closed.** Creation and every inbound group frame require the transport peer
   to be paired (`FlashTrustStore`) AND, for chat/sync, an active member. `from` must equal the
   WS session's peer id — a forged sender id cannot borrow a member's identity. The 1:1 call
   trust gap closes in the same change (`CallCoordinator.isTrustedPeer`).
3. **Delivery is per-member quorum.** One message row + one `group_deliveries` row per
   recipient; a socket write flips only that member; the bubble reads DELIVERED only when every
   active recipient acknowledged, which is also the only thing that retires the outbox row
   (ERROR-031's commit rule, generalized from pairwise to quorum).
4. **Catch-up (Phase 1B) is holder-coordinated** with cursor `(sentAt, messageId)`,
   deterministic claim election `(tierRank, hash(deviceId+msgId))`, rank-0 push / rank-1 backup,
   broadcast batch ack, LOW budget 5 msg/s (owner-locked: max 6 members, text-first, 1A live
   path before 1B sync).
5. **Storage moves v3 → v4** with an explicit non-destructive migration (new `group_members`,
   `group_deliveries` tables; `groupCreatedBy`/`groupCreatedAt` provenance columns); existing
   `receipts`/`read_cursors` keep their meanings — no destructive fallback, per invariant 5 of
   the group plan.

### Alternatives considered
- **Pure set-union membership (draft plan):** rejected — any replayed `add` resurrects a member
  who left; the tombstone rule is strictly safer and costs one version comparison.
- **One outbox row per recipient:** rejected — it would fork the durable-outbox invariants
  (ERROR-031, EXP-015) the drain loop already enforces; per-member state lives in
  `group_deliveries` instead and the outbox stays one row per message.
- **Overloading `receipts` for group state:** rejected — its first-state-wins insert is
  idempotency-shaped, not mutable-delivery-state shaped.
- **Trust-gating 1:1 text:** deliberately NOT done in this change; only calls gain the gate
  now (group-join requires it), and direct-chat behavior is preserved byte-for-byte.

### Consequences
- `RealFlashChatRepository` gains an additive group API (`createGroup`/`addGroupMembers`/
  `leaveGroup`/`groupMembers`) and a dedicated `GroupTransportSink`; the direct-message ABI and
  wire bytes are unchanged.
- Both hosts (app holder and `Flash.create`) construct the same repository with the new DAOs,
  the trust predicate, and `GroupFrameCodec` — one codec, two call sites, no third copy.
- Phase 1B (holder sync) ships after the 3-device live path is verified; its wire frames are
  documented and codec-reserved from day one so no format break follows.

### Revisit when
Group size demand exceeds 6, attachments land (Phase 3 lifts the rejection), or E2E
(`keyEpoch` > 0) arrives — at which point per-sender keys replace inherited transport trust.

## ADR-031 — Hardware PTT button fans a fire-and-forget ping to all paired+online peers

### Date
2026-09-09

### Decision
1. Press detection = dynamic `BroadcastReceiver` for `com.zello.ptt.down` ONLY, registered
   for the engine's lifetime in `DiscoveryEngineHolder` (mirroring `registerScreenReceiver`),
   `RECEIVER_NOT_EXPORTED`. No manifest entry, no Activity-owned receiver.
2. Wire frame = standalone `FLASH_PTT action=ping` (`PttPingFrame` + `PttFrameCodec` in
   `:core:messaging` protocol), deliberately NOT a `MessageWireFrame` subtype so both
   hosts' exhaustive `when` expressions over `MessageWireFrame` keep compiling untouched.
3. Fan-out = snapshot `activeSessions` keys ∩ `pairing.trustedPeers` ids, parallel
   `sendTextAsync` (main-safe, same non-blocking path as pairing/call frames). No outbox,
   no retry; offline peers are skipped silently. 800 ms press debounce; one press = one
   event (the up action is not observed in v1).
4. Receiver = fail-closed trust + transport-peer binding (`from` must equal the WS
   session peer, peer must be trusted), `eventId` dedup (capped set), `pttPings` flow +
   system notification (suppressed while the app is foregrounded); no Room write in v1.
   The `core:engine` `Flash` host decodes with the same checks and logs (it has no
   notification path; UI hosts surface the ping).

### Context
Tydtech-firmware clip mics broadcast four intents per press (scanner reuse, two generic
PTT conventions, one Zello hook); the Zello hook is the public one. Owner-locked v1
scope: single-press ping/alert to all paired+online peers, working backgrounded.

### Alternatives considered
- **Manifest-declared receiver:** rejected — Android 8+ blocks implicit broadcasts to
  static receivers.
- **Activity-registered receiver:** rejected — dies with the UI; PTT must work with the
  app backgrounded (the whole point of engine-lifetime ownership).
- **Listening to all four press intents:** rejected — would fan out 4x per click.
- **MessageWireFrame subtype:** rejected — breaks both hosts' exhaustive `when`
  (transportSink encoders) for zero benefit.
- **Durable outbox + retry:** rejected for v1 — a ping is ephemeral; an offline peer
  simply misses it.
- **Chat-row write per ping:** deferred — needs a schema/storage decision; the flow +
  notification carry v1.

### Revisit when
PTT voice streaming (needs mic path + jitter/buffer design, not a notification),
chat-thread logging of pings, or group-scoped PTT targeting.

## ADR-032 — PTT voice session: half-duplex PCM floor, all-paired scope

### Date
2026-09-10

### Status
Owner-locked design. Phases 0–3 landed 2026-09-10 (codec + floor machine, live audio
engine, session foreground service, session UI + press-to-foreground). Remaining: the
physical-device gate. Supersedes the ping-only interaction (ADR-031 ping kept as
transmit fallback).

### Decision
1. **Floor model = strict half-duplex.** One floor holder transmits; all others receive.
   Transmitter never plays, receivers never capture — so no AEC/echo/mixing. Busy floor
   denies new press with toast (no preemption in v1). Simultaneous claims inside a 1.5 s
   collision window resolve by the group-call total order: lexicographically lower device id
   keeps the floor; established talks ignore late/replayed Start frames.
2. **Audio = raw PCM bursts, WS-binary first.** The existing live WS session avoids
   opening a second transport before the first audio packet. This is a provisional
   implementation choice, not a performance conclusion: physical-device benchmarks must
   measure first-packet latency and jitter for 640–960 B payloads before a dedicated TCP
   data-channel optimization is considered. 16 kHz/16-bit/mono @20 ms on MEDIUM/HIGH,
   8 kHz @60 ms on LOW. No Opus/AAC in v1 (OEM variance and added dependencies).
   Distinct `PTT1` magic + first-branch routing before the transfer pipeline.
3. **Scope = all paired+online** (snapshot `activeSessions ∩ trustedPeers`, group-invite
   fan-out pattern). New `FLASH_PTSS` text family: `start/stop/leave/heartbeat/hb-ack`;
   1 Hz heartbeat doubles as latency source (broadcaster echoes measured RTT; receivers
   compute loss% from audio seq gaps). 5 s audio+heartbeat timeout auto-closes orphaned
   receivers. 60 s max burst, warning at 45 s, 2nd press stops early; session ids retired.
4. **Background press surfaces the app** (incoming-call-style) because API 34+ throws on
   background `microphone` FGS creation and background `AudioRecord` yields silence.
   Foreground-only transmit; backgrounded press without surfacing degrades to ADR-031 ping.
5. **Service = new `PttSessionService`** (not `FlashCallService`; CallStyle semantics are
   wrong). Broadcaster claims `microphone`; receivers claim `mediaPlayback` (manifest
   addition required). Ongoing notification: chronometer seconds + `RTT · loss%` +
   Stop/Leave actions. Receiver playout = `AudioTrack` STREAM, media path (loudspeaker
   by default) + speaker/headset handling, 60–200 ms adaptive jitter buffer,
   repeat-last concealment.
6. **Animation on MEDIUM/HIGH only** (LOW = static receiving UI), driven by real
   per-packet RMS, draw-phase reads per EXP-013, reduce-motion respected.
7. **Mutual exclusion with calls and voice-note recording** (mic exclusivity + ADR-026):
   refuse + toast + log both directions.

### Alternatives considered
- **Reuse `FlashGroupCallSession` WebRTC legs:** rejected — 30–45 s telephony timeouts,
  SDP/ICE setup delay hostile to sub-second presses, busy auto-decline wrong for floor
  semantics, single-active-call guard conflicts, CallStyle UX mismatch.
- **Opus via MediaCodec:** rejected — OEM encoder variance (see HAL notes), new failure
  modes; revisit if WAN/interop or bandwidth measurements demand it.
- **Group-scoped sessions:** rejected — owner locked all-paired scope.
- **Unlimited bursts:** rejected — stuck-transmitter risk; 60 s cap + 45 s warning.

### Revisit when
WAN/interop needs (Opus + WebRTC), preemption/floor-priority policy, late-join past
`start`, or chat-thread session logging (schema decision).

---

## ADR-033 — Calling is a `compileOnly` seam on `FlashEngine`: facade routing without WebRTC in the umbrella

### Date
2026-09-11

### Status
Implemented. Supersedes the `:core:engine` half of ADR-025's "calling is not part of the facade"
position (the module's own design — WebRTC media, WS-mesh signaling, host-owned permissions/FGS —
is unchanged). The §4 code constraint ("nothing on an always-executed path names a `:core:calling`
type") now has runtime evidence on a consumer classpath that genuinely lacks the module —
`UmbrellaFacadeContractTest` in `:sample:consumer`, §7 below. It has **never** been exercised on a
device: ART behaviour is still unverified, and the dispatcher itself (`Wiring.handleInboundText`) is
not executed by that test (private class, requires an `android.content.Context`).

### Context
Calling worked only for a host that wires `CallCoordinator` by hand: `:core:engine` had no
dependency on `:core:calling`, `FlashEngine` exposed no `calls`, and the app host therefore
duplicated the facade's inbound routing (`CallFrameCodec.decode` + `onInboundText`) and its
signaling-loss/restore bookkeeping. PTT had just been given the opposite treatment (ADR-032 seam:
`ptt` + `attachPtt` + facade routing) as an `api` dependency, so the two optional subsystems read
as inconsistent for no stated reason.

The obstacle is the dependency itself: `:core:calling` re-exports `libs.webrtc.kmp` with `api`
(≈30 MB per ABI), and the README promises `core-engine` does not drag WebRTC into an app that never
places a call. Declaring it `api` would break that promise for every umbrella consumer; declaring
nothing keeps calling unreachable.

### Decision
1. **`compileOnly(project(":core:calling"))` on `androidMain`** — not `api`, not `implementation`.
   `FlashCalling` is therefore in the public seam (`calls`, `attachCalling`, `onInboundCallText`) and
   on `:core:engine`'s compile classpath, while the published metadata declares neither
   `core-calling` nor `webrtc-kmp`: a consumer adds `core-calling` itself when it places a call.
   `androidMain` (not commonMain) because the module is a plain AGP Android library with no JVM
   variant, exactly like `:core:ptt` (ERROR-049).
2. **The seam is attach-only: `calls` / `attachCalling(engine)` / `detachCalling()`.** No
   `callsFactory` and no lambda overload — a `CallCoordinator` is built from the host's `sendFrame`
   transport, scope and audio policy, and the mic/camera grants, audio route and
   `microphone|camera` foreground service are the host's. The facade cannot construct one, so it
   does not pretend to. Attaching twice keeps the first engine (mirrors `attachPtt`); `close()`
   detaches. Detaching is **not** hang-up: `FlashCalling` exposes no shutdown and the call's media
   and FGS are the host's, so ending a live call stays with the host's `hangUp()`.
3. **The facade routes and drives the recovery window; the host does not.**
   `FlashEngine.onInboundCallText` / `onCallSignalingLost` / `onCallSignalingRestored` are the
   entry points, and `Flash.create` calls them from the inbound-text dispatcher and from the
   `activeSessions` collector it already runs. A recognized `FLASH_CALL` frame is consumed or
   dropped — never handed to the PTT/group/chat/transfer parsers, because `onInboundText`
   legitimately answers false for frames that still belong to calling (stale call id, `gquery` with
   no live call).
4. **Nothing on an always-executed path names a `:core:calling` type.** This is the constraint
   `compileOnly` imposes on the *code*, not the build file: a consumer that never attaches calling
   has no such class at runtime, so resolving one would be a `NoClassDefFoundError`.
   Consequences: (a) call-frame recognition is a plain `FLASH_CALL` prefix test in `Flash.kt`
   (`isCallFrameText`), not `CallFrameCodec.decode`; (b) the three routing methods are typed without
   `FlashCalling` and answer "nothing attached" (`false` / no-op) instead of throwing; (c) those
   methods reach the attached engine through the function-typed fields captured at attach time, not
   by reading the `FlashCalling`-typed field, so `calls` / `attachCalling` / `detachCalling` are the
   only members that mention the type — and the only ones that can throw when a host calls them
   without the dependency.
5. **`compileOnly` for production, `implementation` for the host test only.** The stub `FlashCalling`
   that pins the routing semantics lives in `:core:engine`'s `androidHostTest`, which declares
   `implementation(project(":core:calling"))`. Test classpaths are not published, so the artifact
   shape is unchanged; the alternative was to leave the consumed/dropped contract untested or to
   fake coverage by asserting the prefix helper alone.
6. **The hand-wired app host keeps its own `onSignalingLost`/`onSignalingRestored` calls, and that is
   a deliberate decision — do not "clean them up".** `:app` never calls `Flash.create`
   (`grep -rn "FlashEngine" app/src` returns nothing): the app's wiring is `DiscoveryEngineHolder`
   surfaced through the Hilt `AppEngine`, so the facade's session collector does not run in the app
   at all, and only a wiring that owns a facade can be driven by it. The holder's two calls are
   therefore the app's **only** driver of the ERROR-033 mid-call recovery window — those calls are
   not redundant leftovers — and deleting them as "duplication" would silently regress roaming
   mid-call: a Wi-Fi roam would kill the signaling session and the call would end instead of
   renegotiating. The facade drives the same two edges for `Flash.create` consumers; the two drivers
   exist because there are two wiring paths, not because one of them is a leftover.
   The duplication that WAS removed is a different thing, and a future reader must be able to tell
   them apart: the app's inbound path used to run `CallFrameCodec.decode(text) != null` as a
   pre-check and then hand the same text to `onInboundText`, which decoded it a second time. That
   pre-check is gone — recognition is the shared prefix test (`CALL_PREFIX` +
   `FlashTextFraming.parseFields`) now. So: a `CallFrameCodec.decode` near the app's inbound path is
   the deleted duplicate; an `onSignalingLost`/`onSignalingRestored` pair in
   `DiscoveryEngineHolder`'s session observer is the kept driver.
7. **`:sample:consumer` is the facade's contract test for this seam, and its calling-free classpath
   IS the contract — not an oversight.** `UmbrellaFacadeContractTest` asserts the precondition
   (`FlashCalling` not loadable and not present as a resource), that the classes an inbound frame
   runs (`FlashKt`, `Wiring`, `Flash`) resolve every declared member type and reference no calling
   type in their bytes, that exactly `FlashEngine`, `DefaultFlashEngine` and
   `DefaultFlashEngine$attachCalling$1$1` do, and that the three routing entry points answer
   `false`/no-op on a hand-assembled engine — `Error` included, not just `Exception`.
   **Do not "fix" the sample by adding `core-calling` to it:** that would delete the only
   calling-free consumer classpath in this repository, and every assertion above would stop proving
   anything. The test fails loudly when the dependency appears (the precondition asserts absence) and
   when a new engine class starts referencing the package (the byte scan asserts the exact referrer
   set). The seam is guarded only for as long as that test runs, with `core-calling` still off that
   classpath; the guard is a test, not a build rule.

### Alternatives considered
- **`api(project(":core:calling"))`** (the PTT treatment): rejected — puts ~30 MB/ABI of native
  WebRTC on every `core-engine` consumer's runtime classpath, for a feature most apps never use.
- **No facade integration at all** (status quo): rejected — call frames are a protocol family like
  PTT, and leaving them unrouted means every host re-implements decode recognition, recovery-window
  bookkeeping and ordering, which is exactly what the app host had duplicated.
- **A `callsFactory` lambda like `pttFactory`**: rejected — the factory would need the host's
  transport and media policy anyway, so it would either duplicate `CallCoordinator`'s constructor
  or fabricate a call engine the host cannot make audible. Absence is the honest API.
- **Typed dispatch (`facade?.calls?.onInboundText(...)`)**: rejected — it reads a `FlashCalling`
  getter on the path every inbound text frame takes, which throws `NoClassDefFoundError` on a
  consumer that never added `core-calling`.
- **Reflection / optional-class probing instead of a typed seam**: rejected — the seam exists to be
  compiled against; reflective dispatch would make the API untyped for everyone to serve one case.

### Revisit when
A desktop/JVM calling target exists (the type would have to move to commonMain with a JVM variant),
or the published-metadata contract is reworked so `core-engine` may pull a WebRTC-bearing module —
at which point `api` becomes a one-line change and the routing methods could be simplified to read
`calls` directly.

## ADR-034 — Desktop calling via the vendored webrtc-kmp fork (composite build), backend bumped to webrtc-java 0.17.0

**Date:** 2026-09-13 · **Decides:** D12 (the Phase 25 calling-stack execution shape) · **Status:** Accepted

### Context
`com.shepeliev:webrtc-kmp:0.125.11` publishes no JVM target (F1, measured) — `:core:calling`
cannot declare `jvm()` against Maven Central. The research report's Option B ("adopt a
plain-JVM artifact") needed a concrete artifact. Community fork `aschulz90/webrtc-kmp` adds the
`jvm()` target over `dev.onvoid.webrtc:webrtc-java`, same `com.shepeliev.webrtckmp` API, but:
published nowhere (author's own Sonatype credentials, `version ?: "0.0.0"`), unmaintained
(last push 2024-11-15, 0 stars), pins webrtc-java 0.8.0 (2023) while webrtc-java is now 0.17.0
(Chrome M152), no JVM screenshare in the wrapper, and its README's "tested Windows ↔ Android"
claim is unverifiable.

### Decision
Vendor the fork into `third_party/webrtc-kmp/` as a plain source copy (Apache-2.0, attribution
preserved), trim its iOS/JS/wasmJs targets (keeping Android + `jvm()`), and expose it via
`includeBuild` in `settings.gradle.kts` — Gradle dependency substitution redirects the existing
`com.shepeliev:webrtc-kmp` edges with no version-catalog or publication change. The ONE
authorized version movement (R10 exception): the fork's `webrtc-java-sdk` 0.8.0 → 0.17.0. The
fork's Android/iOS SDK pins (125.6422.05, = today's Android resolution) do not move.
Stability gate after the bump: more-than-mechanical wrapper churn triggers the fallback — a
thin own JVM layer over webrtc-java directly — with the Stage-1 `org.webrtc` abstraction work
carrying over untouched. Desktop screen sharing is a recorded future feature: webrtc-java's
native capture layer exists (`DesktopCapturer`, Wayland/PipeWire since 0.16.0); the KMP wrapper
never exposed it; it needs its own phase file when scheduled, and this conversion keeps its
track seams wide enough.

### Alternatives considered
- **JitPack (`com.github.…`)**: rejected — the fork is unpublished AND a multi-target KMP build
  on JitPack's Linux runners is fragile; we would not control the artifact.
- **Own thin layer over webrtc-java (no wrapper)**: kept as the fallback, not the first move —
  it rewrites the fork's 27 JVM wrapper files that otherwise come free.
- **Wait for upstream** (`shepeliev/webrtc-kmp` added nothing JVM through 2026-09): rejected —
  D11=B commits to desktop calling now, and the wrapper is small enough to own.
- **Vendoring as a git submodule**: rejected — atomic commits in this repo beat a second
  remote to manage on this host.

### Revisit when
Upstream webrtc-kmp ships an official JVM target (then evaluate rebasing and de-vendoring), or
webrtc-java makes a breaking release we choose not to follow.

## ADR-035 — Desktop identity: persisted software keypair, DPAPI-protected at rest (the P2 pick)

**Date:** 2026-09-13 · **Decides:** the desktop pairing gap's P pick (P2 over P1/P3) · **Status:** Accepted

### Context
The pairing math is commonMain and target-free; the ONE blocker for G2/G6 (pairing gates) and
phone→desktop transfers is the desktop `FlashCrypto` identity: `SoftwareFlashCrypto` is
in-memory by design (its KDoc forbids production identity storage), so every desktop restart
mints a fresh identity and TOFU trust cannot survive. The scoped options were P1 (publicize the
software path — identity still lost on restart), P2 (persist a keypair under `~/.flash/`),
P3 (defer to the 09B-2 review).

### Decision
P2, with the at-rest question answered: **Windows DPAPI via JNA**
(`com.sun.jna.platform.win32.Crypt32Util`, no custom JNI) protects the PKCS#8 identity key
stored at `~/.flash/identity/id-key.bin` behind a 1-byte format-version header. A new
`IdentityKeyVault` seam (`protect`/`unprotect`) carries the OS-specific edge — jvmMain actual =
DPAPI, test actual = pass-through, future actuals = macOS Keychain / Linux keyring. A new
`PersistedFlashCrypto` reuses the existing ephemeral-ECDH + HKDF path verbatim and differs from
`SoftwareFlashCrypto` only in identity-key handling; it degrades to an in-memory identity ONLY
on vault-read failure, logged loudly. JNA (5.x, JVM-variant-only scope) is the new dependency
this decision authorizes. Security tier stated plainly: DPAPI-persisted software identity is
strictly better than restart-amnesia, strictly weaker than Android's non-exportable hardware
key — same-user malware can unprotect the blob. `SoftwareFlashCrypto`/`KeystoreFlashCrypto`
are untouched (R8).

### Alternatives considered
- **P1 (publicize the in-memory path)**: rejected — solves nothing; the identity still dies
  with the process, so pairing can never outlive a session.
- **P3 (defer to 09B-2)**: rejected by the human — G2/G6 are the remaining Phase 16 gates and
  the phone→desktop transfer direction is unreachable without trust.
- **Plaintext PKCS#8 with restrictive ACLs**: rejected — same-user malware reads it outright;
  this is the tier P2 exists to improve on.
- **App-local wrapping key beside the file**: rejected — obfuscation, not protection.
- **Passphrase-derived key**: rejected for now — needs a passphrase UX decision that is not
  this phase's call; the vault seam accommodates it later if product changes its mind.
- **BouncyCastle or another crypto provider**: rejected — JCA + the existing `PlatformCrypto`
  jvmMain actuals cover P-256 sign/ECDH/HKDF; a new provider is a new ADR with its own review.

### Revisit when
macOS/Linux desktop targets become real (new vault actuals), 09B-2 revisits the security-tier
model (a hardware-backed desktop option may then exist), or a passphrase UX is productized.

## ADR-036 — RealFlashChatRepository moves androidMain → commonMain for desktop chat

### Date
2026-09-15

### Decision
Move `RealFlashChatRepository` (+ its `PresenceHold` helper) from `:core:messaging`
`androidMain` to `commonMain`, and run it on desktop over the slice-1 encrypted JVM
database. Constructor is unchanged (all new seams defaulted); Android call sites were
not touched.

### Context
Desktop chat showed an empty conversation because `DesktopEngine.chats` bound
`EmptyFlashChatRepository`: the real repository was Room-backed `androidMain`, and the
`FLASH_MSG` codec lived inline in the two Android hosts. Slices 1–3 removed the
blockers one by one (JVM open seam, shared MIME table, shared text codec); this ADR
records the move itself and the substitutions it required.

### Substitutions (behavior-preserving by test)
- `UUID.randomUUID()` → `:core:common` `UuidIdGenerator.newId()` (same canonical v4 form).
- `System.currentTimeMillis()` → injected `FlashTimeSource` (default `SystemTimeSource`).
- `ConcurrentHashMap`/`newKeySet()` → promoted `SyncMap`/`SyncSet` (new
  `:core:common` `concurrent` types, `@FlashInternalApi`; `:core:calling`'s internal copy
  deleted, its one consumer re-imported).
- `SimpleDateFormat`/`Date` labels → `internal expect` time-format functions with
  Android/JVM actuals (same patterns, same default locale).
- `uppercase(Locale.getDefault())` → locale-independent `uppercase()` (also fixes the
  Turkish-I hazard `Flash.kt` documents; initials only).
- `transportPeerId` routing, drop-vs-fallthrough decode rules, receipt/read/react
  semantics: untouched — proven by the unmodified 47-test Android suite.

### Alternatives considered
- **Duplicate the repository for desktop**: rejected — two copies of 2800 lines of chat
  logic is how the pairing codecs drifted apart before.
- **Move the 2600-line test suite to commonTest too**: deferred — `androidHostTest`
  already runs on the JVM host and passes unmodified; moving it buys nothing for the
  desktop and risks the live path.
- **DPAPI/vault-wrapped chat DB key**: deferred — the chat key is a random file beside
  the database (same trust model as the identity/trust stores); a vault seam can adopt
  it later without changing the repository.

### Desktop key note
`<stateDir>/chat/db-key.bin` holds a random 64-hex-char passphrase, generated once.
Losing it orphans the history by design (wrong-key reads fail loudly — never an empty
chat list). No SQLCipher parity (D5 = C, recorded).

### Revisit when
A desktop key-vault UX exists (adopt `db-key.bin`), or Linux/macOS targets need new
time-format actuals.

## ADR-038 — No desktop counterpart to FlashWebRtcEngine (33a bring-up verdict)

### Date
2026-09-15

### Decision
Desktop calling wires `CallCoordinator` directly with no bring-up object. There is no
desktop `FlashWebRtcEngine`, deliberately — not as deferred work.

### Context
`FlashWebRtcEngine.configureOnce` exists for two Android-only reasons: installing a
low-latency `JavaAudioDeviceModule` before libwebrtc's lazy factory init, and probing
OEM-HAL capture breakage (ERROR-032). webrtc-java has no such module class (its own ADM
instead), and the HAL failure mode is an Android audio-stack bug. `DesktopMediaStackSmokeTest`
constructs a working `PeerConnection` with zero configuration, which is the positive
evidence that nothing is needed.

### Revisit when
Desktop capture misbehaves live — but suspect the `preferIPv4Stack` flag's effect on ICE
first (it gates interface binding on this host), not a missing shim. If a desktop-only
audio quirk ever needs one-time setup, that object is where it goes.

## ADR-037 — Desktop scale policy (AD-D1): OS scale baseline + desktop-only UI scale, Android look preserved

### Date
2026-09-15

### Decides
D13 (`docs/migration/DECISIONS.md`); answers AD-D1 in `docs/migration/ADAPTIVE-UI-PLAN.md` §5.

### Decision
Implement option B. The desktop honours the OS display scale as its baseline **and** offers a
**desktop-only** user UI-scale control (0.75–1.5, default **1.00**), applied as a density *multiplier*
at the desktop window root. **`fontScale` is never overridden.** Option C (force `Density(1f)`) is
rejected. Android's look is preserved by default; any Android-facing change must be a listed,
owner-approved improvement.

### Context
`ADAPTIVE-UI-PLAN.md` §1 audit: the desktop window opens at a hard-coded 1200×800 dp with no minimum
size or persistence (`DesktopMain.kt`), no `LocalDensity` provider anywhere in `desktop/src`, `app/src`
or `ui/`, and only phone-shaped metrics (`FlashDimensions`: 48dp touch targets, 72dp chat rows, 320dp
bubble cap). The official Compose Multiplatform window-management guide (checked 2026-09-15,
plan §1.7) documents no density/DPI control, so the cause of "everything looks big" is to be established
by measurement (AD-1 sub-step 1), not assumed. Meanwhile the chat list/conversation two-pane is wired
backwards (`DesktopShell.kt` `listPaneContent` / `detailPaneContent`) and Android has no adaptive layout
at all — those are separate defects (AD-3, AD-6), not consequences of this decision.

### Structural enforcement (not a promise)
- One shared metric type (`FlashMetrics`, `:ui:theme` `commonMain`); its default is the touch set, pinned
  field-by-field to today's `FlashDimensions` values by a regression test.
- The density multiplier, the UI-scale setting, the pointer metric set and the window geometry are wired
  **only** at `:desktop`'s window root — unreachable from `commonMain` and `:app` (plan §2.2 rule 9).
- Any desktop fix that would move an Android pixel must be listed in the phase's "Android-affecting
  changes" section and approved, or it is a defect (plan §2.2 rule 4).

### Alternatives considered
- **(A) metric set only, no UI-scale**: rejected as the full answer — correct and accessible but gives a
  150%-scaled display no relief; it is only the fallback if AD-1's probe shows `density` is already 1.0
  on the owner's machine (recorded in AD-1's log entry, not here).
- **(C) force `Density(1f)`**: rejected — defeats OS low-vision scaling and recouples the app to every
  OS change.
- **Platform-detection seam (`expect`/`actual`)**: rejected — the host already knows which host it is,
  so detection adds R6 risk for nothing.
- **Second desktop typography scale**: out of scope — AD-D1 scales density only; smaller desktop type is
  a future decision with its own record, never a side effect (plan AD-5 Do-NOT).

### Consequences
AD-5's content measure and AD-2's pane math inherit the multiplier for free (both are host-dp geometry).
AD-6 (Android tablet) must **not** consume the UI-scale switch. UI-045's evidence set gains the
Android-before/after screenshot pair and the metric-pin test output as mandatory items.

### Revisit when
A desktop type scale is wanted independently of density, an encrypted cross-platform settings ABI
(09B-3) makes the UI-scale a shared preference, or OS-scaling behaviour changes on Compose Desktop.



## ADR-039 — Desktop voice notes decode AAC via JCodec; desktop video stays external

### Date
2026-09-16 (owner decision, ERROR-063)

### Context
Android records voice as AAC in MP4 (`.m4a`). The desktop JVM's `javax.sound.sampled` opens
WAV/AU/AIFF only, so every voice note failed with `UnsupportedAudioFileException` (live log).
Desktop video has no in-app surface at all (JVM stub). Both gaps reported together by the owner.

### Decision
- Voice: `org.jcodec:jcodec:0.2.5` in `:ui:platform-shims` jvmMain only. MP4 demux + AAC→PCM
  decode feeds the same `Clip`, so play/pause/seek semantics are identical on both tiers.
  Android keeps MediaPlayer (hardware path, untouched).
- Video: NO new player. Badge plays in-app only on Android (the double-player overlay is
  fixed); the desktop stub reports to the error banner, whose button opens the system player
  through the already-wired `onOpenAttachment`. Real desktop video needs JavaFX/VLC/ffmpeg —
  deferred, explicitly.

### Alternatives considered
- JAAD standalone: GPL — incompatible with this repo's Apache-2.0 LICENSE. (JCodec vendors a
  JAAD-derived AAC core inside its FreeBSD-licensed artifact per its published POM; the POM
  license is the operative declaration.)
- JavaCPP-ffmpeg: Apache-2.0 but per-platform natives for a voice-note job — disproportionate.
- JavaFX media: same weight class, module setup, still no test story headless.
- WAV voice notes: zero deps and plays everywhere, but ~10x file size and changes the
  Android product (recorder, MIME, transfer sizes). Rejected for now; revisit if AAC ever
  misbehaves live.

### Verification
- License: `FreeBSD` in the published POM (checked in Gradle cache 2026-09-16). Zero runtime
  transitives; Java 6-era bytecode (no stdlib/metadata risk vs frozen Kotlin 2.2.10).
- API verified against the jar with javap before coding (demux/track/esds/decode calls).
- `:ui:platform-shims:jvmTest` 8/8 incl. fail-closed AAC-garbage test + synthesized-WAV feed
  test. Positive AAC decode of a real `.m4a` is live-only (no fixture, no headless mixer) —
  owner hardware run owed; success prints a decode line in the desktop log.

### Revisit when
Desktop video goes in-app (that decision re-opens the player question wholesale), or a live
voice note decodes wrong (format/endianness/channel edge the fixture-less suite cannot pin).

## ADR-040 — Manual-IP dial defers TOFU pin evaluation to the post-HELLO identity binding

### Decision
When a dial cannot name the peer it expects — "Connect by IP" to a device that was never
discovered, so `peerDeviceId == null` — the TLS handshake no longer fails closed. The trust manager
accepts the presented leaf, reports its SPKI fingerprint, and `connectManual` runs the **same**
`FlashPinVerifier.isPinned(peerDeviceId, leafFingerprint)` check the handshake would have run, the
moment `FLASH_WS_HELLO` names the peer. A failed binding closes the connection before the session is
registered and before any frame is delivered.

Opt-in per dial: `TofuX509TrustManager(deferPinWhenDeviceIdUnknown = …)` defaults to `false`, so
every discovery-driven dial, every redial and both server paths keep failing closed exactly as
before.

### Context
`TofuX509TrustManager.verify` required an `expectedDeviceId` to evaluate a pin against and threw
"no expected device id — pin evaluation impossible" without one. `WsFlashNetwork.connectManual`
resolves that id from `knownEndpoints`, which only holds peers discovery has already seen. A user
typing an IP for a peer that was never discovered therefore could not connect at all: the failure
happened in TLS, before the handshake that would have revealed the peer's identity. The desktop and
Android share sheets both expose "Connect by IP", so the feature was unusable in exactly the case it
exists for (mDNS blocked, AP isolation, a peer on another subnet).

The existing `TofuPinVerifier` is already trust-on-first-use: it records the pin when a device has
none and compares constant-time when it does. Nothing about that policy changes here — only *when*
it runs.

### Alternatives considered
- **Keep failing closed, improve the error.** Honest but leaves the feature broken; the user has no
  way to reach a peer that discovery cannot see.
- **Accept any certificate on manual dial.** Rejected: a peer we have already pinned must still fail
  closed when a different key answers for it, which is precisely the MITM case.
- **Ask the user to confirm the fingerprint in the dialog.** The 6-digit pairing PIN already exists
  for human verification; adding a second hex comparison in the connect dialog duplicates it.

### Security consequences (accepted)
1. The TLS handshake completes with an unverified peer, and our `FLASH_WS_HELLO` (device id +
   friendly name) is sent before the binding check. An attacker on the typed IP therefore learns
   those two fields. Accepted: the user deliberately dialed that address, the fields are the same
   ones broadcast in mDNS on the LAN, and no message, file or key material crosses before binding.
2. First contact over a manual dial pins whatever key answers, exactly as first contact over
   discovery does. The 6-digit pairing PIN remains the human check before any trust is granted.
3. A peer with an existing pin is *not* weakened: a different key answering its id is rejected after
   HELLO, the connection is closed, and the user sees "security key does not match".

### Verification
- `TofuX509TrustManagerTest`: deferral off by default still fails closed; deferral on reports the
  leaf and never consults the verifier; a known device id ignores the deferral and fails closed.
- `SecureWsTransferLoopbackTest`: a real TLS loopback dial with `peerDeviceId = null` completes and
  surfaces the server's leaf fingerprint; a dial that names the peer still fails closed on a bad pin.
- NOT device-verified: the end-to-end "Connect by IP to an undiscovered phone" flow needs two
  devices.

### Revisit when
Pairing moves to a channel that authenticates the peer before the WS handshake (e.g. QR-carried
SPKI), which would let a manual dial name its expected key up front and remove the deferral.

## ADR-041 — A 15-minute WorkManager wake-up flushes the outbox after the process is killed, then stops the engine again

### Decision
Add `androidx.work:work-runtime-ktx` and a periodic `FlashKeepaliveWorker` (unique work
`flash-keepalive`, 15-minute period, constraints: network connected + battery not low), scheduled
from `FlashApplication.onCreate` with `ExistingPeriodicWorkPolicy.KEEP`.

The worker is a **flush-and-announce** job, not a residency mechanism:
1. If the engine is already running (service alive, UI open) it returns immediately and touches
   nothing.
2. Otherwise it starts the engine, waits up to 45 s for a session to come up (the engine's own
   `notifyPeerSessionUp` drains the outbox the moment one does), plus a 10 s drain grace.
3. In a `finally`, it stops the engine again — **unless** `FlashBackgroundService.isActive()` or the
   process is foreground by then, i.e. the user or the service adopted it while the worker ran.

### Context
`FlashBackgroundService` is `START_STICKY`, which covers a process the *system* killed. It does not
cover the user swiping the app away, and on Xiaomi/HyperOS, Transsion and Huawei the OEM killer ends
the process outright. Nothing then wakes Flash: messages already durable in the outbox sit there
until their 30-minute give-up budget (`OUTBOX_GIVE_UP_AFTER_MS`) expires and they go FAILED, and the
peer sees the device offline. JobScheduler, which WorkManager drives, is the one mechanism that
still gets a slot in a Doze maintenance window.

### Why stop the engine again
`DiscoveryEngineHolder` acquires the partial wake lock and the Wi-Fi lock for the engine's lifetime
(the ERROR-025/026 fix — see ADR history and `logs/errors.md`). A worker that started the engine and
walked away would leave those locks held for good, every 15 minutes, on a device whose app the user
has closed. That is precisely the "stuck partial wake lock" battery profile the hardening pass was
worried about — and unlike the reverted §1.3 B change, stopping here costs nothing, because there is
no live session to protect.

### Alternatives considered
- **Shorter interval / expedited work.** 15 minutes is WorkManager's floor for periodic work;
  expedited quota is meant for user-visible work and would be spent immediately.
- **Start the foreground service from the worker.** Refused by Android 12+ background FGS-start
  restrictions in exactly the case that matters.
- **AlarmManager exact alarms.** Needs `SCHEDULE_EXACT_ALARM`, which Play restricts to alarms/clocks
  and which Doze defers anyway.
- **Do nothing (the pre-existing state).** Rejected: silently losing already-composed messages after
  a swipe-away is the worst failure mode a messenger has.

### Honest limits (do not over-trust this)
- Not a delivery-latency mechanism: worst case a message waits ~15 minutes for the wake-up.
- On the OEMs that motivate it, jobs are *also* restricted until the user grants autostart — the
  `OemBatteryOptimizationHelper` deep links added alongside are the other half of the fix. After a
  user-initiated "force stop", nothing runs until the app is launched again, by design.
- A short connect-and-flush is all it does; it is not a mesh resume.

### Verification
- `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green; merged manifest carries
  `androidx.startup.InitializationProvider` and WorkManager's `RescheduleReceiver`.
- NOT unit-tested: `:app` has no Robolectric harness, and `TestListenableWorkerBuilder` needs an
  Android context. Device verification owed:
  `adb shell cmd jobscheduler run -f com.transfer.flash <jobId>` after swiping the app away with a
  message queued, then confirming delivery and that the engine stopped afterwards
  (`FlashKeepalive` log lines).

### Revisit when
Device evidence shows either that the wake-up never fires on the target handsets (then the OEM
autostart prompt is the only lever left), or that the outbox is emptied by other means before it
fires (then this is dead weight and should be removed).

## ADR-042 — Pairing protocol v2: commit-then-reveal code bound to the TLS identities and ephemeral keys

### Decision
Replace the v1 numeric comparison with a Bluetooth-style commitment protocol (shape in
`docs/security.md` §3.1, primitives in `core/security/.../pairing/PairingV2.kt`):
- The initiator commits to a fresh 16-byte nonce in `PAIR_REQUEST`; the responder reveals its nonce in
  a new `PAIR_NONCE` frame; the initiator opens its commitment in a new `PAIR_REVEAL` frame.
- The 6-digit code is `H(fp_I ‖ fp_R ‖ epk_I ‖ epk_R ‖ N_I ‖ N_R) mod 10^6`: role-ordered,
  length-prefixed, domain-separated.
- Each side refuses a pairing fingerprint that is not the key TLS pinned for that peer (new required
  `DefaultFlashPairingProtocol(peerIdentityPin = …)`).
- `PAIRED` must repeat exactly the fingerprint and ephemeral key the code covered.
- One shared `PairingWireCodec` replaces the app's and the desktop's private codecs.

Owner decisions (2026-09-23): existing v1 pairings are **kept but marked unverified** with a
"Verify" action; pairing with a v1 peer is **refused** with "update Flash on the other device".

### Context
Audit S2 found v1's code derived from the two static fingerprints only (grindable in seconds).
Implementing the fix exposed two worse gaps: the code covered neither the ephemeral keys that become
the E2E session key nor the key TLS authenticated, so a man-in-the-middle could relay the real
fingerprints (codes match, users approve) while holding both TLS sessions and the session key.

### Alternatives considered
- **Longer code only** (8–10 digits). Raises the grinding cost but fixes neither the ephemeral-key
  substitution nor the TLS-binding gap.
- **QR code carrying the SPKI.** Stronger (no human comparison) and still the right future option,
  but needs a camera flow on both platforms; not a drop-in fix.
- **Keep v1 for mixed fleets with a warning.** Rejected by the owner: it keeps creating forceable pairings.

### Consequences
- **Wire-protocol break for pairing only:** v2 cannot pair with 2.0.0-beta devices; chat, calls and
  transfers with already-paired v1 peers keep working (their pins are enforced by audit S1).
- `DefaultFlashPairingProtocol` gained a required constructor parameter (public API change).
- `PairingPhase` gained `AwaitingPeerNonce` and `AwaitingPeerReveal`; exhaustive `when`s in hosts updated.
- `FlashTrustStore` gained `markVerified`/`isVerified` (default no-op/false; implemented in both stores).
- The interop fixtures (`DesktopInteropHarness`, `HarnessTestSupport`) now run real TLS, the only way a
  v2 pairing can succeed, which makes `DesktopPairingLoopbackTest` an end-to-end test of S1 + S3 + v2.

### Verification
`PairingV2Test` (7) and `PairingWireCodecTest` (5) on host + JVM; `DefaultFlashPairingProtocolTest` (14,
including v1 refused, identity mismatch on both sides, forged reveal, substituted ephemeral key making
the codes differ, tampered PAIRED); `PairingSessionStateMachineTest` (33); `FlashPairingCoordinatorTest`
(6, including v1 peer refused and a non-TLS-pinned claimant never shown a code);
`DesktopPairingLoopbackTest` over real TLS sockets. **Not device-verified.**

### Revisit when
A QR/NFC out-of-band channel exists; then the SPKI can be authenticated directly and the human comparison
becomes a fallback.

## ADR-043 — Third-party notices are generated at build time, offline, with a missing-text gate

### Decision
`:app` and `:desktop` apply the AboutLibraries Gradle plugin 15.2.0 (Apache-2.0, **build-time only**, no
runtime library). A `ThirdPartyNoticesTask` in `buildSrc` turns its output into
`THIRD_PARTY_NOTICES.txt`, which ships as:
- an Android asset (generated from the release runtime classpath for every variant);
- a desktop classpath resource;
- a file in the installer's app resources (`appResourcesRootDir/common`).

The plugin runs with `offlineMode = true`. Every licence text is checked in under `config/aboutlibraries/`
and refreshed by `tools/licenses/fetch_license_texts.py`. The build **fails** when a component has no
licence text.

### Context
Audit C1–C3 (2026-09-23): the APK and the installers redistributed BSD/Apache code without any notice.

### Why this shape
- **Offline, checked-in texts.** Online, the plugin downloads texts during the build and silently drops
  them on failure, so the notice would depend on the build machine's network.
- **Native code.** libwebrtc (both platforms), Skia inside skiko (desktop), SQLCipher with SQLite and
  LibTomCrypt (Android) and SQLite3MultipleCiphers (desktop) ship no licence metadata. Their texts are
  attached, via regex overrides, to the artifact that carries them, so a desktop-only payload never
  appears in the Android list. libwebrtc's per-component list comes from webrtc-sdk's generated
  `WEBRTC.md` (m92) plus the six components M125 added. Listing extra components is harmless;
  omitting one is not.
- **The vendored `webrtc-kmp` fork** is invisible to the plugin (composite substitution), so it is added
  as a config-only entry.
- **NOTICE files (Apache §4(d)).** A scan of every shipped jar and aar found exactly one
  (`jakarta.inject-api` 2.0.1); webrtc-java's NOTICE lives in its repository. Both are included. A
  build-time jar scan was judged not worth its cost at 1 hit in about 200 artifacts. Re-scan when
  dependencies change.
- **`buildSrc`**: a task class declared in a `.kts` script compiles as an inner class, which Gradle
  can't instantiate and the configuration cache (enabled) can't store.

### Alternatives considered
- Google `oss-licenses-plugin`: Android-only, and it produces no text for native code.
- A hand-written notices file: goes stale on the first dependency bump, and nothing catches it.

### Not done (owner decision, 2026-09-23)
No in-app "Open-source licences" screen. The file ships inside the APK and the installers.

### Revisit when
A new native payload is added, or a POM changes its licence (the gate fails and names it).

## ADR-044 — Groups of up to 20 through vouched introductions (supersedes ADR-030's 6-member limit once implemented)

### Date
2026-09-24

### Status
**ACCEPTED by the owner, NOT IMPLEMENTED.** The threat review (phase V0 below) must finish before any code. Until
V2 lands, `GroupPolicy.MAX_MEMBERS` stays 6 and ADR-030 applies unchanged.

### Context
The owner wants chat groups of 20 or more, video calls of 8 and voice calls of 12 (`docs/calling/GROUP-VIDEO-PLAN.md`
§5). Bandwidth isn't the blocker; ADR-030's trust rule is. Verified in code on 2026-09-24:
- Every inbound group frame requires the transport peer to be paired (`FlashTrustStore`) and an active member.
- `RealFlashChatRepository` drops an inbound `GroupWireFrame.Add` if **any** added member isn't already trusted by
  the receiver. So today a member can only be added if every existing member has paired with them.
- Call legs are gated by `CallCoordinator.isTrustedPeer`, which has the same pairwise requirement.
- Membership frames are **not signed**. They're authenticated only by the TLS session they arrive on. The only
  role is `owner` (the creator); any active trusted member may send an `Add`.

Pairwise pairing costs N(N−1)/2 code checks: 15 for 6 members, 190 for 20, 496 for 32. Above about 8 that doesn't
happen in practice, so the caps would exist only on paper.

### Decision
1. **Vouching.** When a member adds someone, the `Add` carries the new member's identity: their TLS certificate
   fingerprint, as pinned by the adder through their own pairing. Other members accept a connection from that
   device **only if** its TLS certificate matches the vouched fingerprint. The channel stays encrypted and
   authenticated; what changes is *who vouched for the key*, the adder instead of the receiver's own code check.
2. **Signed membership operations.** A vouch must survive relay (catch-up, members who were offline), so `Add`,
   `Leave` and `Remove` gain a signature by the author's identity key over (groupId, opId, version, members and
   fingerprints). A receiver checks the signature against the author's *own* trusted or vouched key. An unsigned
   or wrongly signed operation is dropped, fail-closed as before.
3. **Who may vouch.** Initially, only the group `owner` (the creator) may add members. This keeps the chain of trust
   one hop deep: a member trusts a vouched key because they paired with the owner, never through a chain. Whether
   other members may add is left to V0.
4. **Scope of vouched trust.** A vouched identity is trusted **only** for (a) group frames of the group that vouched
   for it and (b) group-call legs of that group. It never grants direct 1:1 chat, file transfer or 1:1 calls.
   Those still need real pairing. Leaving or being removed from the group revokes it.
5. **The UI is honest.** Vouched members show "Added by [owner]" and a one-tap "Verify" that runs normal pairing
   and upgrades them to paired. A key change on a vouched member is treated like a TOFU mismatch: blocked, and
   the user is told.
6. **Limits.** `MAX_MEMBERS` becomes 20 when V2 lands. 32 requires V3's reconnect-storm and keepalive-battery
   measurements on the Belfone.

### Alternatives considered
- **Raise to 20 and keep pairwise pairing:** rejected by the owner. 190 pairings means large groups never form.
- **Stay at 6 until per-sender E2E keys exist:** rejected by the owner. Too long a wait; it also leaves the 8/12
  call caps unreachable.
- **Transitive trust (anyone vouches, chains allowed):** rejected. One compromised or careless member could admit
  anyone, and revocation becomes a graph problem.
- **Group-wide shared secret / pre-shared key:** rejected. It authenticates "someone in the group", not a device,
  so a removed member keeps access until the secret rotates.

### Consequences
- The wire format changes (signed membership operations with fingerprints). A codec version bump is needed; old
  clients must reject, not misread, the new frames.
- A trust predicate with scope (paired, or vouched-for-group-X) replaces the boolean `isTrustedPeer` at the group and
  group-call gates only. The 1:1 paths keep the boolean.
- The TLS layer must accept a vouched fingerprint as a pin for a peer the user never paired with, scoped to group
  sessions. This touches `TofuX509TrustManager` and must be designed in V0, not patched in.
- Weaker than pairing, by design: members trust the owner's judgment and the owner's own pairing. The owner's
  device is a single point of trust for the group.

### Phases
- **V0 Threat review:** a malicious owner; a compromised member device; a replayed or forged `Add`; a key change; a
  removed member reconnecting; downgrade to unsigned frames; the TLS pinning path for vouched keys. Output: this ADR
  amended with the findings. **No code before V0.**
- **V1 Signed membership:** identity-key signatures on membership operations; the codec version bump; tests for
  forgery, replay and downgrade.
- **V2 Vouched trust:** the scoped trust predicate, vouched TLS pins, call legs within the group, the UI labels and
  "Verify", `MAX_MEMBERS = 20`. Owner device check with at least 4 devices, where 2 have never paired.
- **V3 Scale measurement:** 20 sessions per device: keepalive battery over 1 hour, a reconnect storm after a Wi-Fi
  blip, mDNS load. Logged in `logs/experiments.md`. Decides 32.

### Revisit when
Per-sender E2E keys arrive (`keyEpoch` > 0): these can then replace vouched transport trust. Also revisit if
attachments in groups land, since N−1 uploads from the sender need a relay design at 20.
