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
**ACCEPTED by the owner, PARTLY IMPLEMENTED. V0 (threat review) COMPLETE 2026-09-29; V1a (hardening of today's groups) BUILT
2026-09-29 (`3f33c61`, device check GT-01 owed); V1 (signed membership and messages) BUILT 2026-09-30 (S1 `e8e6d08`, S2 `029ec26`,
S3 `1e3ad36`, S4 and docs below; device check GT-02 owed); V2 (vouched trust, groups of 20) BUILT 2026-09-30 (S1 `bc4e687`, S2
`e4cc004`, S3 `6684120`, S4 `b67923f`, S5 `9674ab0`; device check GT-03 owed, see "V2 built" at the end of this ADR).** Legacy
groups keep `GroupPolicy.MAX_MEMBERS` = 6 and ADR-030's rules; v2 groups take up to 20 (`MAX_MEMBERS_V2`). The V0 findings and the V1/V2
design are in `docs/group/v0-threat-review.md`; see "V0 findings (2026-09-29)" at the end of this ADR.

**Update 2026-09-29 (ADR-056):** the target is **20**. V3 (= PC6 / MEAS-02) is postponed to `docs/FUTURE-OPTIMIZATION.md`
FO-05, so **32 is parked** until it is measured. The trust model (V0 to V2) and the per-mode session cap are the next
work after DR2/DR3/DR5. Group attachment fan-out (N-1 uploads) is parked as FO-04.

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
- **Found 2026-09-28:** every host caps live sessions at 8 (`SessionHardeningPolicy`). A mesh group above 9
  members cannot connect fully until that cap is raised; the per-mode cap is decided in presence phase PC2 and
  must land before V2 sets `MAX_MEMBERS = 20`. **Done 2026-09-29: ADR-057 (ceiling 24, dial budget 20).**

### Phases
- **V0 Threat review:** a malicious owner; a compromised member device; a replayed or forged `Add`; a key change; a
  removed member reconnecting; downgrade to unsigned frames; the TLS pinning path for vouched keys. Output: this ADR
  amended with the findings. **No code before V0.**
- **V1 Signed membership:** identity-key signatures on membership operations; the codec version bump; tests for
  forgery, replay and downgrade.
- **V2 Vouched trust:** the scoped trust predicate, vouched TLS pins, call legs within the group, the UI labels and
  "Verify", `MAX_MEMBERS = 20`. Owner device check with at least 4 devices, where 2 have never paired.
- **V3 Scale measurement** (executed as PC6 of `docs/network/PRESENCE-CONNECTIONS-PLAN.md`, 2026-09-24): 20 sessions per device: keepalive battery over 1 hour, a reconnect storm after a Wi-Fi
  blip, mDNS load. Logged in `logs/experiments.md`. Decides 32.

### Revisit when
Per-sender E2E keys arrive (`keyEpoch` > 0): these can then replace vouched transport trust. Also revisit if
attachments in groups land, since N−1 uploads from the sender need a relay design at 20.

### V0 findings (2026-09-29)

Full detail: `docs/group/v0-threat-review.md`. Read in code, not exercised on a device. No code changed by V0.

**Bugs in today's groups, exploitable by a paired peer that knows a group id** (cheap to fix without signatures, and they
would grow from 6 to 20 members if vouching were built on top of them, so they are fixed first as V1a):
- **F-1** `Create` overwrites an existing group (no known-id check, `@Upsert`).
- **F-2** `State` is accepted from any paired peer, not only members; the "ids unique" check compares a list with itself.
- **F-3** membership versions are sender-chosen wall-clock ms compared with `>`: one huge value poisons a row for good.
- **F-4** `handleSyncPush` ingests any push (this device recorded no request): an active member can plant history under
  its own id with a chosen display name. *Corrected the same day: the first wording said a member could forge another
  member's authorship; the wire cannot carry that, see F-9.*
- **F-9** (found while fixing F-4) a relayed message loses its author on the wire: the codec drops the pushed message's
  `from` and decodes it as the pusher, so synced history is stored as sent by the relayer and delete-for-everyone authority
  follows the relayer. ERROR-082, OPEN, fixed in V1 by a signed `author` field.
- **F-5** `State` carries only active members, so leave/removal tombstones never converge by reconcile.
- **F-6** names come from the sender; **F-7** any active member may Add; **F-8** unpaired connected peers already reach 1:1
  paths (adjacent debt, unchanged by this ADR).

**Design decisions taken by V0 (they refine, and where noted replace, the Decision section above):**
1. **Per-subject signed `MemberCert`s plus an owner-signed `GroupCharter`**, not one signature per `Add` operation
   (replaces the "signed Add/Leave/Remove operation" wording in Decision 2). A cert is verifiable alone, merges per roster row and
   relays without extra context. The cert carries the subject's **SPKI**, not only its fingerprint, because the trust store keeps
   fingerprints and a relay-only verifier could otherwise not check a signature.
2. **`seq` counters replace wall-clock `membershipVersion`** in v2 groups (closes F-3). Order is `(seq, opId)`, strictly
   greater wins, so `membershipUpdateWins` is reused.
3. **Only the owner adds, removes and renames** in v2 groups; a member may sign their own leave (Decision 3 confirmed; the
   "no admins" 2026-09-07 rule is superseded for v2 groups).
4. **Group messages are signed by their author** (closes F-4 and F-6 for v2 groups). *Owner confirmation requested; see
   below.*
5. **The TLS layer does not change.** `TofuX509TrustManager` only asks `FlashPinVerifier.isPinned`; the vouch lands in the
   trust store as a pin with a **source** (`PAIRED` > `VOUCHED(groupIds)` > `TOFU`). A vouch installs its pin before the peer
   connects, replaces an unverified `TOFU` pin (this defeats a device that connected first as the target id), never overrides
   a `PAIRED` pin (mismatch is shown, member not trusted until re-verified), and is deleted when the last vouching group is gone.
   This corrects the "must touch `TofuX509TrustManager`" line in Consequences.
6. **The predicate `isGroupTrusted(groupId, peer)`** (paired, or an active verified cert for that group) replaces the boolean
   at the group frame handler and the three group frames in `CallCoordinator` only. 1:1 chat/files/calls and push-to-talk keep
   `isTrusted`. Group attachments go only to paired members. Presence and endpoint tips keep their existing "paired or fellow
   group member" eligibility, fed from the verified roster; a vouched pin counts as pinned for tip dialing (this is what lets a
   mesh of 20 form).
7. **Old clients:** v2 frames use new action names (unknown actions are ignored, invariant 1); HELLO gets an additive `gv`
   field; `PROTOCOL_VERSION` does **not** change (a mismatch fails the whole handshake). Legacy groups stay legacy (at most 6)
   and are not upgraded in place; the owner may add to a v2 group only devices that advertised `gv >= 2`.
8. **Downgrade rules:** a known group id is never re-created, a charter replaces a legacy record of the same id, a v2 group
   never accepts unsigned membership frames again.
9. **Resource caps:** at most `MAX_MEMBERS` active certs plus 64 tombstones per bundle, verified certs cached by hash, one
   bundle per group per peer per 30 s.

**Accepted limits:** the owner is a single point of trust and of failure (lost owner device = frozen roster, no ownership
transfer); vouched trust is weaker than pairing and the UI says so; no forward secrecy or per-sender keys, so a removed member keeps
what they received.

**Revised phases (replace the V1/V2 sketch above; V3 unchanged and parked, FO-05):**
- **V1a** hardening of today's groups, no wire change, no signatures: F-1, F-2 (+ duplicate-id rejection), F-4, F-5, each with a
  test that fails on the old code. **BUILT 2026-09-29** (ERROR-081, `docs/protocol.md` "Receiver rules"). Notes: F-4 is
  "solicited pushes only" (`OutgoingSyncRequest`: syncId + asked peer + group, 10 minutes, 256 entries); `State` carries
  tombstones only up to 6 rows in total because every shipped codec rejects a longer roster; a member this device has not
  yet learned about cannot teach it the roster until the owner's next `State`. F-3, F-6, F-9 remain for V1.
- **V1** signed membership and messages for v2 groups, HELLO `gv`, `docs/protocol.md`, golden vectors, persistence migration.
- **V2** vouched trust, pin sources in both trust stores, planner dialing of vouched members (read `ConnectionPlanner` first;
  V0 did not trace it), `MAX_MEMBERS = 20`. **Prerequisite done 2026-09-29 (ADR-057):** the session ceiling is 24 and the dial budget 20; V2 adds a test that
  `MAX_MEMBERS - 1` fits the budget, because `core:network` cannot see `GroupPolicy`.

**Owner decisions (answered 2026-09-29, chat; both the recommended default):** (1) **sign group messages, not only membership:
YES**; (2) **legacy groups are not upgraded in place: YES**, they stay legacy (up to 6, V1a rules). V1 is unblocked.

### V1 built (2026-09-30)

Plan and rationale: `docs/group/v1-signed-membership-plan.md` (D1 to D10). Wire: `docs/protocol.md` "v2 groups". Security
summary: `docs/security.md` section 8. Slices: S1 protocol layer, S2 wire and HELLO `gv`, S3 schema v6, S4 repository and hosts.

**Amendment to the V0 review's rule 4 (D1).** The review proposed "a charter replaces a legacy record of the same id". V1 does not
need that rule: a v2 group id is `g2-` plus a hash of the owner's key and a nonce, so an id names exactly one possible owner and
nobody can pre-create it. The prefix is reserved; a legacy frame for a `g2-` id is dropped, and there is no replace-legacy path.
Legacy groups keep their ids and rules.

**Decision D10, taken while building S4: the owner takes an invitee's key from the live TLS session.** A cert carries the subject's
SPKI, and nothing stored had it (the trust store keeps only the pin, a hash). `WsConnection.peerPublicKeyEncoded` reads the leaf
key after the handshake and it is exposed as `FlashDevice.identityKey`; the TLS certificate key is the identity key, so
`SHA-256(key)` is the pin, and the owner refuses to sign a cert for a key the pin does not vouch for (`keyMatchesPin`). A device that
advertises `gv >= 2` but has no key or a mismatching one fails create/add (`V2_KEY_UNAVAILABLE`); it never silently becomes a legacy
group. Alternatives rejected: a new key-exchange frame (a second channel to keep consistent with TLS, more wire) and storing the
SPKI in the trust store at pairing (schema and both trust stores change, and existing pairings would have no key).

**Also decided while building (recorded so a later reader does not re-derive them):**
- Only a cert that would replace what the receiver holds is verified and counted against the per-peer budget; a stale bundle is free.
- A known group's bundle must come from an active member of the stored roster, or carry a verified active cert for the sender.
  An unknown group's bundle may come from any paired peer but must carry a valid, active cert for the receiver.
- A v2 group never exceeds `MAX_MEMBERS_V2` (6, equal to `MAX_MEMBERS` until V2) active members after a merge.
- The stored label is the owner's signed label; `updateMemberDisplayName` skips rows with a `certSig`, so a peer rename cannot
  change what a v2 roster shows.
- A message row without an author signature is never relayed in a v2 group; a direct message or relayed push needs an *active*
  author, so **sync does not re-deliver a former member's messages** (accepted limit, `docs/security.md` section 8).
- Messages are not budgeted, only bundles.

**Still open before V2:** the device check GT-02, the sign/verify cost measurement MEAS-08, and the V2 prerequisites already
listed above (vouched pins, planner dialing of vouched members, the `MAX_MEMBERS - 1 <= DIAL_BUDGET` test).
*(Update 2026-09-30: the V2 prerequisites are built, see "V2 built" below; GT-02 and MEAS-08 are still owed.)*

### V2 built (2026-09-30)

Plan and rationale: `docs/group/v2-vouched-trust-plan.md` (E1 to E6). Security summary: `docs/security.md` section 9. Wire:
`docs/protocol.md` (no change; the V2 paragraph states it). Slices: S1 trust stores (`bc4e687`), S2 messaging rules, owner remove and
groups of 20 (`e4cc004`), S3 group calls and host wiring (`6684120`), S4 dial-budget/presence/ECO tests (`b67923f`), S5 UI labels and
Verify (`9674ab0`).

**Decisions taken while building (recorded so a later reader does not re-derive them):**
- **`gv` stays 2, no wire change.** V1 and V2 were never released apart, so no field device has V1 without V2. A wire bump would have
  broken nothing but also bought nothing.
- **Vouching goes through the pin store, not the TLS layer.** `FlashTrustStore` gained four abstract methods (`pinSource`,
  `vouchVerdict`, `applyVouch`, `revokeVouch`) with no default body, so the compiler finds every implementer; a default no-op would
  have turned vouching off silently on a host. `PinSource` is derived from existing state plus one new per-device set of vouching
  group ids. Rejected: a second trust list read by `TofuX509TrustManager` (a TLS change with two sources of truth for one decision).
- **The group gate never reads the pin store.** A vouch is honoured when the peer's *live* TLS key equals the cert key, so a first-use
  pin an attacker planted, or a session opened before the vouch replaced the pin, cannot speak as a member. Rejected: gating on
  `PinSource == VOUCHED` (correct only if the pin store and the roster never drift).
- **The port is `GroupVouching`, the adapter is `TrustStoreGroupVouching` in `core:engine`.** `core:messaging` stays free of a
  `core:security` type at its edge; the adapter is wired identically in `DiscoveryEngineHolder`, `Flash.create` and `DesktopEngine`
  (the same shape as `FlashGroupCrypto`).
- **Trust is one hop deep.** The receiver must be paired with the owner; only the owner's cert vouches; a vouched member's certs are
  never a source of trust. Files, 1:1 and push-to-talk are not opened by a vouch (group attachments are paired-only both ways).
- **Vouching is the first side effect of `onBundle`, after every ignore check, and `ensureVouches` self-heals** a trust store that was
  cleared. `leave()` revokes every vouch of the group.
- **Limits:** `MAX_MEMBERS_V2` = 20, `MAX_BUNDLE_CERTS` = 84. `core:engine` holds the test `MAX_MEMBERS_V2 - 1 <= ConnectionModePolicy.DIAL_BUDGET`
  (the only module that sees both); a member of a full group holds 19 sessions, so the ceiling of 24 (ADR-057) leaves 5 for anything else.
- **Owner remove is built without a UI** (an API and tests: `removeGroupMember`, signed tombstone, revokes the vouch on receivers).
  Reason: the member sheet has no destructive per-row action design yet (UI-029 addendum); revisit when the owner asks for it.
  **Superseded 2026-09-30 (`39d8905`): the owner asked, and the UI is built.** UI-029 addendum 2 (DESIGNED first, three approaches):
  a trailing **Remove** action on every row but the owner's own, then `FlashRemoveMemberDialog` (copy says a removed member keeps
  what they already received). Ownership is decided by the repository (`FlashConversationUiState.canRemoveMembers` = v2 group and
  this device is `groupCreatedBy`), never derived in the UI; `removeGroupMember` moved onto the `FlashChatRepository` interface with a
  declining default. Rejected: a long-press menu (no visible affordance, custom accessibility actions, no desktop habit) and a
  per-member detail sheet (a new surface for one action; the likely home if promote/demote ever arrive).
  **Found while building:** the conversation state read the roster inside its combine but never re-ran when the member table
  changed, so a removed member would have stayed in an open sheet (the same staleness applied to a leave or an add made on another
  device). The outer combine now includes `groupMemberDao.observeMembers` for a group conversation, which also refreshes the header
  member count. Unit tests: `SignedGroupsTest` (canRemoveMembers for the owner only, and the roster drops the removed member; both
  fail if the change is reverted), `FlashGroupMembersLogicTest` (who is removable, dialog copy).
- **Removal ripple (2026-09-30, ERROR-083).** Reviewing what removal does to the *other* devices found four gaps, all fixed without a wire
  change: (1) a member removed while offline was never told (reconcile skips inactive peers, members ignore the removed device's own
  roster), so a member now sends an inactive v2 peer the owner's tombstone alone (`removalNoticeFor`); (2) a removed or left device could
  keep sending (rows stayed `PENDING` for good), so sends refuse and `FlashConversationUiState.selfMembership` swaps the composer for a
  notice (UI-029 addendum 3, three approaches: disabled composer, delete the chat, notice bar; the notice bar chosen); (3) a device that
  is out ignores every bundle but an invitation back, withdraws the vouches when it verifies its own tombstone and takes no group
  traffic; (4) the call gate ignored roster activity for a paired peer, so hosts now use `isGroupCallPeer` (active row and local device
  still a member) while `isGroupPeerTrusted` stays as it was. Rejected: sending the removed device the full roster; relying on the
  removed device to forward its own tombstone. **Known limit:** removal is eventually consistent, and a member that has not converged
  still sends to the removed device; only per-sender keys would close that.
- **UI (UI-029 addendum, DESIGNED first):** a third line "Added by <owner> · not verified" and a trailing **Verify** action; Verify
  reuses ordinary pairing and adds no trust logic to the UI. Alternatives considered in `docs/ui/group-ui.md`.

**Accepted limits:** those of `docs/security.md` section 9, chiefly: a lying owner can vouch a key it controls (mitigated by the
label and Verify); a removed member keeps what they received; vouched members get no files; an established call leg is not
re-checked; deleting a chat without leaving keeps its rows and vouches.

**Not verified on a device.** GT-03 (four or more devices, two of them never paired with each other) is owed, as are GT-02, SC-01,
SC-02, MEAS-08. Do not describe groups of 20 as tested until they are.

**Revisit when:** ~~the owner wants remove in the UI~~ (built 2026-09-30); what the removed member sees on their own device is
undesigned; per-sender keys or ownership transfer are requested; a group needs more than 64
tombstones; MEAS-02 / PC6 (FO-05) is run and 32 is reconsidered.

**Superseded in part by ADR-073 (2026-10-04):** the owner reversed the rejection of a group-wide shared secret ("Alternatives considered" above) on 2026-10-03/04 (decision D1). Under ADR-073 the secret is used only to join, to rotate and for an optional discovery beacon; the device key stays the identity and the signed roster stays. Everything else in this ADR stands. ADR-073 is PROPOSED until the owner accepts its text.

## ADR-045 — One connection planner decides who dials; modes will own the connection policy

### Date
2026-09-28

### Status
**IMPLEMENTED (PC2), device check pending** (P8: testing after PC1–PC5 and group calling). Plan:
`docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.5–3.6. The ECO/BOOST rules of §3.4 are PC5 and extend this ADR.

### Context
The auto-connect decision existed three times, and the copies had drifted:
- the app holder's sweep with `app/.../net/AutoConnectGate`, plus the hotspot gateway probe and call-quiet;
- `core:engine`'s `Flash.create` sweep with a ported gate (`core/engine/.../internal/AutoConnectGate`, `PlatformLock`),
  without the `isReconnectInFlight` check;
- the desktop's `dialIfNeeded`, with no suppression window (an unreachable peer was redialed every 5 s) and a
  registry-presence session check where the phones had used the freshness check since ERROR-031.

Both sides of every pair also dialed at the same moment on first sighting. That is the ERROR-023 glare case, where
the losing dial hangs for the full 6 s handshake timeout. `DesktopEngineAutoDialTest` had recorded such a run.

### Decision
1. **`ConnectionPlanner`** (`core/network/.../planner/`, commonMain, no platform types) is the only copy of the
   rules. It keeps the old gate unchanged: skip yourself, a live session clears the peer, never dial while the
   reconnect engine is redialing, one attempt per 15 s, never two at once.
2. **`AutoConnector`** is the shared driver. It sweeps every 5 s, on each discovery edge, on `sweepNow()` (screen-on,
   manual retry) and when a deferred dial falls due. The app holder, `Flash.create` and `DesktopEngine` all use it,
   and the three hand-written loops and both `AutoConnectGate` copies are deleted.
3. **Deterministic first dialer:** in a new no-session episode the lower device id dials at once, and the higher id
   waits 1.5 s and dials only if no session has landed. The plan said "the backup-loop floor" (4 s). The wait is
   shorter because pairing gives up after 3 s without the peer's HELLO, and when discovery is one-sided (a hotspot
   drops mDNS) the higher id is the only side that can dial.
4. **Staggered storms:** after at least 4 unexpected drops within 3 s, the first attempt of every reconnect loop for
   the next 30 s is delayed by `hash(local|peer) mod 2 s`. This includes the Wi-Fi-rejoin "immediate" redial. It is
   in both `WsFlashNetwork` and `JvmWsFlashNetwork` (`ReconnectStagger`). A single drop is never delayed.
5. **Lock-free state:** the planner's state is immutable and replaced by compare-and-set on a `MutableStateFlow`.
   `core:network` deliberately declares no expect/actual classes, so it gets no `PlatformLock` copy.
6. **Modes own the connection policy (PC5).** The planner takes the mode later. Today it implements STANDARD for
   every mode. The **session cap per mode** (`SessionHardeningPolicy`, 8 today) is decided in PC5, not here.

### Alternatives considered
- **Move the gate to core:network and keep three loops:** leaves the drift that produced the desktop gaps.
- **A `PlatformLock` copy in core:network:** needs `-Xexpect-actual-classes`, which the module deliberately avoids.
  Compare-and-set is enough for a state this small.
- **Per-device stagger (`hash(deviceId)`), as the plan wrote it:** it spreads phones but not one phone's own loops.
  A per-pair hash does both and is still deterministic.

### Consequences
- The desktop now has a 15 s suppression window and the ERROR-031 freshness check. `Flash.create` now skips peers the
  reconnect engine is redialing, and logs its dials.
- The higher-id side's first dial comes up to 1.5 s later. If the lower id cannot reach it (ERROR-073's refused
  port), first contact is 1.5 s slower.
- `core:engine`'s `PlatformLock` has no users left. It is kept for now.
- Log text is unchanged (`Auto-connect dialing peer=`, `Auto-connect result peer=… success=`,
  `Auto-connect dialing gateway`), so `tools/pc0/phone-baseline.ps1` still counts dials.

### Revisit when
PC5 adds modes; PC6 measures reconnect storms; or discovery resilience (ADR-047) feeds new sources into the planner.

## ADR-046 — Presence sharing: mutual contacts only, Ghost never shared, tips dialed only against a pin

### Date
2026-09-28

### Status
**IMPLEMENTED (PC4), device check pending** (P8: testing after PC5 and group calling). Plan:
`docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.2. Wire format: `docs/protocol.md` "Presence sharing".

### Context
The owner's idea (plan §3.2): a phone passes on what it knows ("I'm connected to user 2, at 192.168.1.20"), so user 3
sees user 2 as Online through user 1 even when user 3's own discovery misses user 2 (hotspots drop mDNS, OEM
freezers stop advertising). The owner decided: mutual contacts only, Ghost devices never shared, at most 2 hops.

### Decision
1. **One implementation for all hosts.** `PresenceCodec` (wire), `PresenceState` (all rules; deterministic, with
   injected time and view) and `PresenceExchange` (one coroutine; bounded input channel that drops the oldest;
   publishes `reachablePeerIds` and `tips`) live in `core:network` commonMain. The app holder, `Flash.create` and
   `DesktopEngine` only plumb frames, a `PresenceLocalView` snapshot and a send function.
2. **Mutual contacts through salted hashes.** The reporter sends a random salt per session. The asker sends
   `SHA-256(tag || salt || id)` truncated to 8 bytes for each of its contacts, and the reporter answers only for
   matches it knows itself. Group fellows need no hash, because rosters are already shared. SHA-256 is injected
   (`FlashFingerprint.fingerprint`), because `core:network`'s common code has no crypto dependency.
3. **Ghost travels in a presence hello, not in `FLASH_WS_HELLO`.** No change to the session handshake or the
   network classes. Safety comes from **default deny**: a directly observed device is reported only after its own
   hello said `share=1`, so a Ghost device, a pre-PC4 client or a hello still in flight is never reported.
4. **Tips are dialed only for pinned subjects, and with the subject named.** A named dial to an *unpinned* id makes
   `TofuPinVerifier` record whatever key answers (trust on first use), so a forged tip could plant a pin. Restricting
   tips to pinned subjects makes "a forged tip costs one failed dial" strictly true.
5. **Only paired peers and group fellows** send or receive presence. Received entries about non-contacts are dropped.
6. STANDARD numbers: refresh 30 s, max age 45 s, delta gap 1 s, rate burst 10 at 1/s, 3 failed tips before a sender
   is ignored. PC5 adds the ECO and BOOST rows (`PresenceConfig`).

### Alternatives considered
- **`noshare` in `FLASH_WS_HELLO`:** also changes both network classes and the handshake parser, and a default-deny
  rule is still needed for old clients, so it adds nothing.
- **Share on every session:** rejected by the owner ("mutual contacts only").
- **Unsalted hashes:** a third party could precompute hashes of known ids and link want lists across sessions.
- **Tips for any contact:** allows pin planting through trust on first use (point 4).

### Consequences
- A reporter learns which of **its own** contacts are also the asker's contacts. This is inherent in the matching,
  and within the owner's "mutual contacts" rule.
- A device is reported only after it has exchanged hellos with the reporter in the current process, even when the
  reporter's discovery sees it.
- Group headers still count only live sessions. Presence sharing feeds direct chats (the ring) and dialing.
- A device that switches to Ghost sends `share=0` on its open sessions at once, and each reporter withdraws it in its
  next delta (at most about 1 s later). Its hops-2 copies at third devices are replaced by that withdrawal or expire
  within the 45 s max age.

### Revisit when
PC5 (modes); PC6 measurements; or the 3-device check (user 3 sees user 2 through user 1) fails.

## ADR-047 — Discovery resilience: extra sources are dial hints that feed the connection planner

### Date
2026-09-24

### Status
**PROPOSED. DR1, DR2, DR3 and DR5 IMPLEMENTED 2026-09-29 (device checks DR-01 to DR-04 pending); DR4, DR6, DR7
postponed (ADR-056); DR0 is a measure-last task.** Owner order: after group calling (2026-09-24). Plan: `docs/network/DISCOVERY-RESILIENCE-PLAN.md` (DR0–DR7). ADR-045 and ADR-046 are
reserved by the presence plan.

### Context
Discovery today depends on multicast: mDNS (NSD / JmDNS) and the `224.0.0.168:45823` beacon. The hotspot
gateway probe and manual Connect by IP are the only fallbacks. Peer routes are held in memory only and deleted as
soon as discovery stops advertising a peer, so a network that filters multicast makes even a just-connected peer
unreachable after a restart.

### Decision
1. New discovery sources produce **candidate addresses only**. Identity stays with the TLS pin and HELLO binding
   (S1, ADR-040, ADR-042). A wrong address costs one failed dial.
2. Sources feed the PC2 connection planner. No new auto-connect sweep copy is added.
3. Order: remembered endpoints for paired peers (DR1) → directed-broadcast beacon (DR2) → subnet sweep limited to
   /24 or smaller (DR3) → QR first contact through pairing v2 (DR4) → hardening (DR5). BLE (DR6) only if the DR0
   failure matrix justifies it. Wi-Fi Direct (DR7) gets its own plan.
4. Persisted routes live in the encrypted DB, paired peers only (pending owner D1), and are deleted on a pin
   mismatch.

### Alternatives considered
- **Wait for Wi-Fi Direct:** it solves "no shared network", not "shared network that blocks multicast", which is
  the common case.
- **BLE first:** Android only, new permissions, steady scan cost. Deferred to DR0's evidence.
- **mDNS reflector requirement on routers:** not something users can be asked to configure.

### Consequences
- A new Room table and migration (DR1) behind a persistence port (ADR-024).
- New dependencies for DR4 (CameraX, ZXing), to be recorded here before they are added.
- `forgetEndpoint` semantics change: it removes the discovery route only.

### Revisit when
DR0 results are in; the PC2 planner's shape changes; or rotating discovery ids (audit S9) land.

### DR1 implementation notes (2026-09-29)
What was built, and where it departs from the plan's wording:
1. **Separate policy class, not a transport.** `RememberedRoutes` (`core:network` commonMain) keeps the routes and
   feeds the PC2 planner as sightings, appended after discovery's and PC4's tips. The plan's `REMEMBERED`
   `FlashRadioTransport` was **not** built: transports emit into `discoveredEndpoints`, which drives PC3's Online ring,
   so it would have shown every paired peer as Online. A remembered peer with no session stays Offline in the UI until
   a dial succeeds.
2. **`forgetEndpoint` is unchanged.** The consequence "forgetEndpoint semantics change" is met by keeping routes in a
   separate store: `knownEndpoints` is still the discovery route table and still shrinks when discovery drops a peer.
3. **Written only by the dial path.** `WsFlashNetwork` / `JvmWsFlashNetwork` call `RouteObserver.onAuthenticated`
   after TLS and HELLO proved the pinned key (and only when TLS is on, and HELLO agrees with the id the dial named);
   they call `onIdentityMismatch` when a named dial's TLS check rejects the answering key or the post-HELLO pin check
   fails. **An inbound session records nothing**: its socket has the peer's ephemeral source port and HELLO carries no
   listen port. The side that dialed (the lower device id first, PC2) therefore holds the route. Adding the listen port
   to HELLO would let both sides record; it is a wire change and needs its own ADR.
4. **Paired only** (owner decision D1, the plan's recommendation, **assumed and awaiting confirmation**): `isPaired` is
   asked on every write and read; rows of a peer that is no longer paired are deleted at load.
5. **Rules.** At most 4 routes per peer (newest first); a pin mismatch deletes that one route; a route expires only
   after 5 failed dials **and** 7 days since its first failure (the first failure time is persisted, the count is
   not); failed routes back off 30 s doubling to 10 min and the next route is offered meanwhile; `resetBackoff` runs on
   the app's re-arm and the desktop's manual retry. A dial that only lost a glare race (the peer already has a live
   session) is not a failure.
6. **Storage.** Room table `remembered_endpoints` (schema v5, `FlashSchemaSteps.STEP_4_5`, ADR-055), a
   `RememberedEndpointStore` port owned by `core:network`, and two small Room adapters because `core:engine` has no
   persistence dependency on the JVM target: `RoomRememberedEndpointStore` (engine, androidMain) and
   `DesktopRememberedEndpointStore` (desktop). A storage failure costs persistence only, never a dial.
7. **Behaviour changes to know about.** (a) Dial on demand (PC3) can now dial a remembered peer that discovery does
   not list, so a send to it costs one urgent dial (1 s budget, 5 s floor, skipped while it backs off) instead of
   waiting in the outbox. (b) `FlashNetwork.connect(device)` still resolves through the discovery table only.
   (c) ECO has no extra "remembered routes only on a network change" rule yet: it follows the planner's normal cadence
   and the backoff above.
8. **Tests.** `RememberedRoutesTest` (19, including the plan's exit criteria through the real planner),
   `JvmRouteObserverTest` (6) and its Android twin `RouteObserverTest` (5) over real TLS,
   `FlashJvmMigrationsTest` (v4→v5 validated by Room). Device check: `TEST-BACKLOG.md` DR-01.

### DR2 implementation notes (2026-09-29)
1. **Same packet, second destination.** `MulticastTransport.announceNow` sends the announcement to the group and then,
   per interface, to that interface's directed-broadcast address on the announce port (45823). Same bytes, no protocol
   change, so old clients already receive and parse it. It covers the start-up burst and the reply to a new peer too.
2. **Address from the interface, not from `255.255.255.255`.** `DirectedBroadcast.forIpv4` (pure, `commonMain`) derives it
   from each IPv4 address and prefix length and refuses /31, /32, a prefix wider than /8, point-to-point interfaces and
   unset, loopback, multicast or class E addresses. The platform's own `InterfaceAddress.broadcast` is used only when the
   reported prefix length is impossible (a documented bad-Android-release case); **that fallback is untested on a device**.
3. **Receiving needed nothing.** Both factories bind the wildcard address with `SO_REUSEADDR`, so their sockets already
   accept broadcast on the announce port. Checked in code and by a real-socket test on the Windows desktop
   (`JvmDirectedBroadcastSocketTest`). Android is unverified until DR-02.
4. **Send failures are quiet and never fatal.** `sendBroadcast` returns false for "no address" (normal, not logged), and a
   real send error is logged once per binding. The multicast send always comes first. GHOST sends neither.
5. **`broadcastEnabled` (default true) is a constructor parameter, not a user setting.** It doubles the group-addressed
   frames per announcement (one multicast, one broadcast per interface, every ~20 s); at that size it is not worth a
   setting. Revisit if DR0 shows a network where broadcast harms (some APs rate-limit it).
6. **Not distinguishable on receipt.** A `DatagramPacket` does not say whether it arrived by group or broadcast, so the
   transport cannot log which one delivered. DR-02 therefore proves it by elimination (desktop multicast blocked by a
   firewall rule, the phone still finds it) and the bind line names each interface's target.
7. **A test-JVM finding worth keeping:** on Windows a JVM without `-Djava.net.preferIPv4Stack=true` cannot bind a
   multicast socket to an adapter that has no IPv6 address (`SocketException: Invalid argument: setsockopt` in
   `setNetworkInterface`). The app already sets the flag; `:core:discovery:jvmTest` now does too, so a test measures the
   environment the product runs in.
8. **Risk accepted:** a router that forwards directed broadcasts would carry the announcement off the subnet. Modern
   routers do not (it is off by default), the payload is the same one already multicast, and it carries no secret.

### DR3 implementation notes (2026-09-29)
1. **A hit is a dial hint, never an identity.** `SweepController.hostsToDial()` lists the hosts whose port 45822 accepted a
   TCP connection. The planner (new rule 9) dials each one unnamed (`connectManual(host, 0)`, port 0 meaning
   `PREFERRED_PORT`), exactly like a gateway probe (rule 6): TLS and the HELLO binding decide who answered (ADR-040), and the
   pin is bound after HELLO. The key is `sweep:<host>`, the hit is skipped when `Links.hasSessionAtHost(host)`, is never
   deferred, and ignores the ECO dial filter (a hit exists only because the user asked, or because nothing else was
   reachable and the mode allowed a sweep).
2. **Limits (`SubnetSweepPlan`).** RFC 1918 addresses only; a /24 or smaller; own address, network and broadcast addresses
   excluded (kept when they are real hosts of a wider subnet); /31 and /32 refused; nearest hosts first; at most 4 subnets;
   32 probes in flight, 300 ms timeout; one sweep at a time.
3. **Wider than a /24: the two triggers differ.** A manual scan of a /16 or /8 sweeps only this device's own /24 block and
   says so ("Only this device's part of a large network was checked"); the automatic trigger refuses it. 254 probes are a
   scan the user asked for; 65 534 are not.
4. **Automatic trigger (D2), stricter than the plan's wording.** The plan said "paired peers and nothing discovered for
   60 s". Built: paired peers > 0, **no live session at all**, nothing discovered for 60 s, mode not ECO, no call in
   progress, at most once per network per 10 min (a refused network counts as tried). The extra conditions keep a healthy
   phone from probing its home network and keep a call's radio quiet. Manual: 5 s cooldown between scans.
5. **Hits are one-shot.** A host is offered for 60 s and forgotten after one dial (`hitDialed`, reported by the connector's
   finally block), so a host that opens 45822 but is not Flash costs one TLS attempt per sweep, not one per planner pass.
6. **The probe leaves by the same route as the dial (Android).** ERROR-035 (hotspot `ap0`, on-link `Network` binding) means
   an unbound probe could report a host reachable that the dial then cannot reach, or the reverse. `LanRouteChooser` is
   the route logic that lived in `WsTransferClient`, moved out unchanged; both the client and `TcpHostProbe` call it.
   **The move is untested on a device** (the client's behaviour must not change; DR-03 and any ordinary LAN/hotspot
   connect exercise it). Desktop: `JvmLocalSubnets` lists non-loopback IPv4 interfaces that are up and not virtual.
7. **`Links.hasSessionAtHost`** is now implemented by `WsFlashNetwork.hasSessionAtHost` and
   `JvmWsFlashNetwork.hasSessionAtHost` (matches a session's remote address, or the endpoint discovery bound to its peer),
   and used by the app holder, the desktop engine and `Flash.create`. Before this, the desktop and `Flash.create` planners
   used the default `false`, so a gateway probe there was not deduplicated against an existing session either.
8. **`VirtualAdapters`** (Hyper-V, VPN, VirtualBox/VMware host-only, WSL, Docker name/description heuristics, 4 unit tests)
   lives in `core:discovery` jvmMain, not in `core:network`, because DR5 uses it for JmDNS and the beacon as well;
   `core:network` already depends on `core:discovery`.
9. **`Flash.create` (library facade) has no sweep.** It has no Nearby screen, so a manual scan has no home, and building
   the automatic fallback there would add public API for a host that does not exist yet. It gained only
   `hasSessionAtHost`. Revisit if a third host needs discovery-blind networks.
10. **Copy/display rule lives twice on purpose.** `ui:chat` depends on `core:common` and `core:messaging` only, so it cannot
    see `SweepState`; each host maps `SweepState` to `NearbyNetworkScan` (`MainActivity` and `DesktopShell`, `toNearbyScan`,
    identical). An automatic sweep shows while it runs and never shows a result. The desktop mapper is unit-tested.
11. **Known limits.** Misses a peer whose server fell back to an ephemeral port (DR1 covers that peer after one contact). A
    sweep is 254 SYNs on the local link: fine at home, may trip an IDS on a managed network, which is why the automatic
    trigger is conditional and the manual one is a button. Not verified on any device: DR-03.

### DR5 implementation notes (2026-09-29)
1. **Adapter filtering is one rule for three consumers.** `VirtualAdapters.selectInterfaces(candidates, includeVirtual, tag)`
   (`core:discovery` jvmMain) decides which interfaces JmDNS binds, the multicast beacon joins and sends on, and the sweep
   probes (`JvmLocalSubnets`). Before this, JmDNS and the beacon used every up, multicast-capable interface, so a Hyper-V
   `vEthernet`, a VPN tunnel or a VM host-only adapter advertised an address a peer on the real LAN cannot route to.
   Detection is by adapter name and description (Hyper-V, VPN and TAP/TUN, VirtualBox/VMware host-only, WSL, Docker). It is a
   heuristic, so the next two rules exist.
2. **Never empty.** If every candidate looks virtual (a Hyper-V guest whose only NIC is a virtual adapter is a real case),
   the selection falls back to all candidates and logs that it did. Skipping everything would leave the desktop silent, which
   is worse than advertising one unreachable address among the reachable ones.
3. **The Windows hotspot adapter is real.** "Microsoft Wi-Fi Direct Virtual Adapter" is what Mobile Hotspot exposes; a PC
   sharing its connection is a LAN for its clients. It is listed under `REAL_MARKERS`, checked before the virtual markers.
   A first version of the list had it as virtual, which would have hidden the PC's own hotspot network; caught in review,
   pinned by a unit test.
4. **Include setting, no UI row.** `include_virtual_adapters=true` in `~/.flash/settings.properties`
   (`DesktopSettings.includeVirtualAdapters`, default false) turns the filter off. It is read at every bind and rebind, so it
   applies to the next network change; the desktop needs a restart to be sure. There is no settings row on purpose: it is a
   workaround for a misclassified adapter, not a preference, and a row would invite users to switch it on "to fix
   discovery". Android is not affected: it has no adapter list of this kind.
5. **The selection is logged.** Each bind writes one line: `Network adapters: using <names>; skipped virtual/tunnel <names>`
   (tag `MulticastTransport` or `JmDNS`), plus a fell-back note when rule 2 applied. A field report about an unreachable
   desktop therefore names which adapters were used.
6. **Per-source report (plan §3.3 E item 4).** `CompositeDiscovery.sourceReport()` renders one line:
   `Discovery sources: jmdns=['Flash Camel' at 192.168.1.20 4s], multicast=none`, every source listed, silent ones as `none`,
   each sighting with its age in seconds. It is written to the log (tag `WS` in the app and the desktop, the facade's own tag in `Flash.create`) when the set of
   `deviceId@host` any source holds changes, and every 5 min (`DEFAULT_SOURCE_LOG_HEARTBEAT_MS`) otherwise, from the
   existing sweeper tick. No new timer, no per-tick logging. It is a log line, not a debug screen: the plan asked for a
   debug-screen line, and this project has no debug screen for it; a logcat / desktop log line answers the same field question.
7. **Quiet-network hint.** `NearbyUiState.discoveryQuiet` is computed by the hosts with the shared
   `FlashNearbyMath.discoveryQuiet(pairedPeers, discoveredPeers, liveSessions, isDiscovering)`: true only when discovery is
   running, paired peers exist, **nothing is discovered and no session is live**. The live-session term matters: a peer
   connected through a remembered route (DR1) or an inbound dial is reachable even if discovery cannot see it, and telling
   that user "this network may be hiding devices" would be false. The page shows the card after the flag has held for 30 s
   on screen (`QUIET_HINT_DELAY_MS`); leaving the tab restarts the count. Actions: *Scan network* (DR3) and *Connect by IP*.
   No *Show QR* while DR4 is postponed (ADR-056). UI design: `docs/ui/nearby-page.md` addendum.
8. **IPv6 link-local mDNS (plan item 3) is not built.** The plan made it conditional on DR0 finding a network that passes
   IPv6 multicast but not IPv4; DR0 is measure-last (P8) and has not run, so there is no evidence to build against.
   Revisit when MEAS-07 says so.
9. **Known limits.** The name heuristics will misjudge an unusual adapter name in either direction; rule 2 and the include
   setting are the escape hatches. Not verified on any device: DR-04.

## ADR-048 — Connection modes: re-time live sessions, ECO parks only by agreement

### Date
2026-09-28

### Status
**IMPLEMENTED (PC5), device check pending** (P8: testing after group calling). Plan:
`docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.4. Wire format: `docs/protocol.md` "Link control" and the presence
hello's `r=`.

### Context
ADR-045 item 6 moved the per-mode connection policy to PC5. The discovery mode (ECO / STANDARD / BOOST, plus GHOST
and RECEIVE_KIOSK) already existed but changed only discovery; keepalive, redial pacing, presence timing and which
sessions to hold came from the hardware tier alone. Phones in different modes share one mesh, so every rule must be
safe for mixed pairs, and switching mode must not drop anyone.

### Decision
1. **One policy object.** `ConnectionModePolicy.of(mode, tier)` (commonMain) returns the transport profile, the
   presence config and whether sessions are limited. GHOST and RECEIVE_KIOSK use STANDARD. **STANDARD returns the tier's
   profile unchanged**, so today's behaviour is kept exactly.

   | Knob | ECO | STANDARD | BOOST |
   |---|---|---|---|
   | Ping | 30 s | tier | 5 s |
   | Liveness | max(75 s, tier) | tier | 15 s (LOW keeps its floor) |
   | Reconnect base / cap | 2 s / 30 s | 1 s / tier | 250 ms / min(5 s, tier) |
   | Presence refresh / max age | 60 s / 90 s | 30 s / 45 s | 10 s / 20 s, delta gap 250 ms |

   All values are estimates until PC6. `FlashTransportProfile.reconnectBaseMs` is new (default 1 s, the old constant).
2. **A switch re-times live sessions instead of reconnecting.** `WsConnection.retime` sets the new cadence, re-resolves
   which side pings (PC1: the shorter interval pings), rebases the tick clock, and credits the connection with
   half the new liveness window (`lastInboundAtMs = max(last, now − liveness/2)`). Without that credit a switch from
   ECO (30 s of legitimate silence) to BOOST (15 s window) closed healthy sessions at once; the simulation test found
   this. A dead peer is still reaped within the new window. `WsKeepaliveTicker.reschedule()` wakes the shared ticker.
3. **Mixed modes: the side that wants a session keeps it.** ECO keeps ≤ 3 ring neighbours among its contacts (sorted
   ids; +1, −1, +2… so the group stays connected), active peers (user traffic within 10 min), the call peer, and
   unpaired peers while Nearby is open. Its planner dials only those (`ConnectionPlanner.plan(allowed)`; dial on demand
   and gateway probes are unaffected). It closes an unwanted idle session **it dialed** only after the peer agrees
   (`FLASH_LINK park` / `park-ok`), and the peer stops redialing before agreeing. STANDARD, BOOST and old clients never
   agree, so they never see churn.
4. **Presence across modes.** The hello carries the sender's refresh (`r=`); receivers hold each reporter's entries for
   max(own max age, 1.5 × its refresh), plus 90 s for relays. Per-sender rate refill is 250 ms (was 1 s) so BOOST fits.
   This also removes a hop-2 flicker that existed in pure STANDARD (a relayed entry could expire before the relayer's
   next digest).
5. **Staleness is relative.** `hasLiveSession` treats a session as stale after max(45 s, liveness + 5 s), so an ECO
   session (up to ~75 s quiet) is not redialed as dead. STANDARD stays at 45 s.
6. **Session cap stays a uniform 8** until PC6 measures the cost per session.
   *(Update 2026-09-29, ADR-056: PC6 is postponed, FO-05. The per-mode cap will be set by reasoning, from group size and
   mode, with its numbers labelled estimates. It is no longer waiting for a measurement.)*
   *(Update 2026-09-29, ADR-057: done. The ceiling is 24 for every mode; the modes differ in what they dial.)*

### Alternatives considered
- **Reconnect on a mode change:** simple, but drops calls and transfers and causes a handshake storm.
- **ECO closes unwanted sessions unilaterally:** a BOOST or STANDARD peer redials within seconds, and both churn.
- **Refuse inbound sessions in ECO:** breaks delivery to an ECO phone, which must stay reachable (plan §3.4).
- **Per-mode session caps now:** no data yet on what a session costs; PC6 decides.

### Consequences
- A drop that was not a park (Wi-Fi loss, peer crash) still redials through the network's reconnect loop even when
  ECO does not want that peer; the next park cycle (10 min idle) closes it again by agreement. Accepted.
- Hosts wire three extra things: `transportProfile`/presence `config` lambdas, a `ConnectionModeController`, and the
  `FLASH_LINK` route. `Flash.create` has no tier setting and uses HIGH, as before.
- The busy peer comes from calls only; an active transfer counts through its user traffic (active window).

### Revisit when
PC6 measurements; if ECO's ~1 min delivery bound fails; or when groups grow past 20.

## ADR-049 — Group video by request: per-leg encodings switched by request, old clients unchanged

### Date
2026-09-28

### Status
**IMPLEMENTED (G3), device check pending** (P8). Plan: `docs/calling/GROUP-VIDEO-PLAN.md` §4.1–§4.4, §8 G3. Wire
format: `docs/protocol.md` "Group call frames", video by request.

### Context
Before G3 every leg of a group video call sent and decoded video, so a device encoded up to 5 copies of its camera
and decoded up to 5 videos whatever it showed. The owner's R1 is that a device sends video to a peer only after
that peer asks. The mesh has no server, so the rule has to be a protocol between each pair.

### Decision
1. **Four frames** (`vreq`, `vgrant`, `vdeny`, `vrel`) and a capability flag `vr=1` on the join and presence
   frames. Each leg still negotiates a video track; the sender switches that leg's encoding on or off
   (`RTCRtpEncodingParameters.active`), so a request never renegotiates.
2. **Old clients keep today's behaviour.** A peer whose own frame lacks `vr=1` is sent video as before and is never
   sent a new frame. Mixed calls work in both directions.
3. **One pure state machine** (`GroupVideoRouter`) holds both sides for a device; the session only carries out its
   effects, under one lock, in order. It is tested with three and four devices through the real codec.
4. **Wall-clock-based sequence numbers** make stale requests harmless without per-session reset rules.
5. **A rebuilt peer connection is not a release.** The two ends notice a reconnect at different times; releasing on
   it would race the re-request. Grants end only on release, deny, hang-up or the signaling timeout.
6. **Receivers fill up to their receive limit** (pinned, then the followed speaker, then everyone else in device-id
   order). The plan's grid (§4.4) is read this way so that HIGH devices keep seeing everyone, as they did in G1; LOW
   and MEDIUM devices now receive 1 and 2 videos.
7. **Limits in G3 are counts:** receive LOW 1 / MEDIUM 2 / HIGH 5 (3 on 2.4 GHz); send LOW 1 / MEDIUM 2 / HIGH 5 on
   5 GHz, 6 GHz or Ethernet, 3 on 2.4 GHz, 4 when unknown. G4 replaces the 2.4 GHz send count with the 540p/360p
   budget and applies the requested heights.

### Alternatives considered
- **Renegotiate (add/remove the video transceiver) per request:** rejected; an offer/answer round per tap is slower
  and a glare risk in a mesh.
- **`replaceTrack(null)` instead of `active=false`:** kept as the fallback if the desktop's webrtc-java ignores
  `active` (device check).
- **Request only the pinned participant and the speaker:** rejected for now; a HIGH device would lose the full grid
  it has had since G1. Revisit with the G5 layout.
- **Release on reconnect:** rejected (item 5).

### Consequences
- A newly joined participant shows an avatar for a moment until its grant arrives (one signaling round trip).
- A participant turned down is retried when the sender announces room, within 4 s (the presence period), or at once
  when the sender gains room.
- The talker-first rule depends on the local microphone level from the `media-source` stats entry, which is not yet
  verified on either backend. Without it the sender simply denies at its cap.

### Revisit when
G0 measures the keyframe delay after `active` turns on, and the desktop check shows whether `active` is honoured.

## ADR-050 — Group call size caps are enforced by the participants, not the initiator

### Date
2026-09-29

### Status
**IMPLEMENTED (G7), device check pending** (P8). Plan: `docs/calling/GROUP-VIDEO-PLAN.md` §5, §8 G7. Wire:
`docs/protocol.md` `gfull`.

### Context
Owner decision Q6: a video call holds 8 people, a voice call 12 (15 only after measurement). Group calls are a
full mesh with no server, so there's no single place that knows the head count. The initiator rings everyone in
the group; people accept at different moments; others join later from the ongoing-call banner.

### Decision
1. **Everyone rings; the first to arrive get in.** The initiator does not trim the invite list. Refusing to start a
   call for a 20-member group (or picking who gets rung) would be worse than letting the first 7 or 11 in.
2. **Each participant that is in the call checks each newcomer.** When a `gaccept`/`gjoin` arrives from a device it
   doesn't already count, and the call already holds the cap (itself included), it opens no leg and answers `gfull`.
   The newcomer leaves with reason FULL ("Call is full"). One `gfull` from any participant is enough.
3. **A known participant is never turned away**, so a reconnect or a relayed duplicate of its own join is safe.
4. **Caps live in `FlashGroupCallLimits`** (core-calling model), so hosts can show them.

### Consequences
- Two newcomers arriving at the same moment at cap − 1 can each be counted first by different participants, and
  then both are turned away. That's the conservative outcome; a retry gets one of them in. A server-less exact count
  would need a consensus round per join, which isn't worth it at these sizes.
- An old client ignores `gfull` and waits in CONNECTING toward the participants that refused it (they never open a
  leg); the call works for everyone else.
- Today the chat-group cap is 6 (ADR-030), so neither call cap can be reached until vouched groups (ADR-044) land.

### Revisit when
G0 measures 12-person voice on the BelFone (then 15), or a relay peer changes the mesh assumption.

## ADR-051 — No vlcj/libVLC for call video; desktop video cost is fixed in Flash's renderer

### Date
2026-09-29

### Status
**DECIDED** (owner asked "can we add vlcj so the desktop can decode"; answered no, with the reason below).

### Context
A desktop group video call used all RAM and maxed the CPU until hang-up (ERROR-075). The owner suggested vlcj.

### Decision
Don't add vlcj. Fix the renderer and camera capture instead (ERROR-075).

### Why
- vlcj wraps libVLC, a media player. Call video reaches Flash only as decoded frames from libwebrtc (the RTP/SRTP
  stream is encrypted with keys from the DTLS handshake inside WebRTC); there is no stream VLC could open.
- Decoding was not the measured cost: the renderer's two uncollected native copies per frame and full-size
  conversions were, plus a camera opened at its largest mode.
- It would add ~100 MB of native libraries per desktop platform and a second media stack to maintain.

### Revisit when
A desktop hardware *decoder* is wanted: that belongs inside libwebrtc (a webrtc-java build with a hardware decoder
factory), not in a player library. Decide from `CALL_DIAG … dec=` and decode-time numbers first.

## ADR-052 — Hardware video on the desktop: what it would take (assessment)

### Date
2026-09-29

### Status
**PROPOSED** — assessment for the owner ("can we add hardware decoding rather than CPU"); nothing built.

### Context
Desktop calls use webrtc-java 0.17.0, whose libwebrtc has only software codecs: libvpx VP8 (Flash negotiates
VP8 only, `CallSdp.enforceVp8Only`, because the bundled library advertises H.264/VP9/AV1 it cannot decode).
The 2026-09-29 four-person call (ERROR-078) spent 44–63% of 8 cores; the largest share is **encoding** the camera
three times at 720p30 (one encoder per mesh connection), not decoding three 640x360 streams.

### Findings
- webrtc-java has **no API to install a video encoder/decoder factory**. `PeerConnectionFactory` takes only audio
  modules (0.17.0 bytecode); 0.18.0 added field trials; 0.19.0 (2026-09-27) added a native extension API and an
  FFmpeg module, but for *sources* (playing a file into a call), not codecs.
- Windows hardware decode is universal for **H.264** (Media Foundation / DXVA); VP8 hardware decode is rare, VP9
  and AV1 depend on the GPU. Android phones encode and decode H.264 in hardware everywhere. Hardware only pays off
  with a switch to H.264 (VP8 kept as fallback), which also removes the reason for `enforceVp8Only`.
- Even with a hardware decoder, each frame is copied to I420 in system memory by webrtc-java's sink, then
  converted to BGRA and uploaded by Flash; a GPU-texture path would need its own renderer.

### Options
1. **Fork webrtc-java** and give its `PeerConnectionFactory` hardware codec factories: an H.264 decoder and
   encoder on Media Foundation (or FFmpeg with `d3d11va`/`qsv`/`nvenc`/`amf`), with software fallback. Needs a
   Windows libwebrtc build (depot_tools, ~20+ GB checkout, hours per build), C++ codec work, per-GPU testing
   (Intel/AMD/NVIDIA), and a rebuild on every webrtc-java update; macOS (VideoToolbox) and Linux (VA-API) are
   separate work. Weeks, not days.
2. **Upstream it**: propose codec-factory hooks to webrtc-java (the 0.19.0 extension table was designed for "a
   hardware encoder" too), then implement the codec as an extension. Less to maintain; depends on the maintainer.
3. **Cut the work instead** (no native code): cap the send height when sending to several participants (e.g. 540p
   for two, 360p for three or more), which cuts the dominant encode cost roughly in proportion to pixels, and
   upgrade to webrtc-java 0.18.0+ for its native leak fixes.

### Recommendation
Do 3 first and measure with the now-working `CALL_DIAG` encode/decode times (ERROR-078). Take on 1 or 2 only if
the numbers still show codec CPU as the limit.

### Update 2026-09-29 (webrtc-java 0.19.0 source, issue #185)
- The send-height half of option 3 is **ADR-053** (opt-in).
- 0.19.0 (latest, 2026-09-27) still builds its Windows/Linux video factories from a fixed template: libvpx VP8/VP9,
  **OpenH264 (software)**, libaom AV1 encode / dav1d decode. No hardware codec and no hook to add one. On **macOS** it
  uses the platform's default factories (VideoToolbox), so a Mac already has hardware H.264 — once Flash stops
  forcing VP8.
- 0.19.0's `VideoTrackSink::OnFrame` still copies each frame and takes a reference for Java, so ERROR-078's
  `frame.release()` stays correct after an upgrade (no double release). 0.18.0 removed the double
  `DeleteLocalRef` there.
- Issue #185 ("H265 support", open) is **not** the hardware-decoding path Flash needs: it asks for H.265 to talk to
  MediaMTX. The maintainer's answer (2026-09-13, m152) is that open-source WebRTC has no H.265 encoder or decoder and
  it would mean writing one against Media Foundation / VideoToolbox / VA-API — i.e. option 1's work, for a codec
  most Android phones cannot use in WebRTC. It is useful as a signal that the maintainer sees hardware codecs as
  platform work, which makes option 2 (a codec-factory hook) the thing to ask upstream for.

## ADR-053 — Optional "Send smaller video in groups" (send-height cap by watcher count)

### Date
2026-09-29

### Status
**ACCEPTED** — implemented (code; device check pending).

### Context
ERROR-078's call showed a desktop spending most of its CPU encoding its camera at 720p once per watcher (a mesh has
one encoder per connection). ADR-052 option 3 proposed capping the send height when sending to several people. The
owner asked for it **as an option**, offered where the CPU warning appears and kept in Settings.

### Decision
- A user setting, **"Send smaller video in groups"**, default **off**, on Android (DataStore
  `smaller_video_for_many`) and desktop (`~/.flash/settings.properties` `smaller_video_for_many`).
- When on, `GroupVideoRouter.level()` also caps every copy by how many watchers there are
  (`GroupVideoLimits.heightForCopies`): 1 → unchanged (720p), 2 → 540p, 3 or more → 360p. It combines with the
  existing caps (band, split budget, each watcher's own ask) by taking the lowest; the bitrate follows the height
  (`maxBitrateKbps`). The step is immediate both ways: it changes only when a watcher arrives or leaves.
- The CPU health banner (UI-050d) offers **Send smaller** while the setting is off; tapping it turns the setting on
  and saves it (so it shows in Settings), rather than a per-call switch. With two actions the banner takes two lines.
- Plumbing follows "Prioritise voice quality": the host owns the preference and passes a reader lambda
  (`CallCoordinator(smallerVideoForMany = { … })` → `FlashGroupCallSession`), read on every stats tick, so a mid-call
  change applies within 1–2 s and `core:calling` stays persistence-free (ADR-024).

### Alternatives considered
- Always on: rejected by the owner's "make it optional"; also changes the picture everyone else sees.
- A per-call toggle only (like "Show fewer"): would need finding again every call; the owner wanted it in Settings.
- Offering it on the WARM warning too: it would help heat as well, but the owner asked for the CPU warning; revisit
  after measuring.
- Hardware codecs: ADR-052 (large native work).

### Consequences
Watchers of a device with the setting on see its video at 540p/360p in larger calls. The camera still captures at
its profile size; only the encoded copies shrink (WebRTC scales before encoding).

### Revisit when
The next `CALL_DIAG vout … enc=` numbers show whether the cap is enough, or whether it should default on for LOW/MEDIUM
devices.

## ADR-054 — WebRTC on Android sees local-only links (hotspot, Wi-Fi Direct); webrtc-java 0.19.0

### Date
2026-09-29

### Status
**ACCEPTED** — implemented (code; device check pending).

### Context
ERROR-079: the phone hosting the Wi-Fi hotspot could chat with everyone but connect no call leg. libwebrtc's
Android network monitor only knows `ConnectivityManager` networks; a SoftAP interface is not one, so libwebrtc
ignores it and cannot bind to it (source walk-through in ERROR-079). Hosting the hotspot is a normal way to use
Flash with no router. The same applies to a Wi-Fi Direct group (the planned transport), which libwebrtc only covers
with a delegate that is off by default.

Separately, the owner asked for webrtc-java 0.19.0 (desktop). 0.18.0 fixed a native PeerConnection leak on
every `close()` and JNI memory-safety bugs (#283), and made RTP senders/receivers/transceivers hold their own
native references (disposable); 0.19.0 added `AudioTrackSource.dispose()` (#287), encoded transforms and a native
extension API.

### Decision
1. **Report the local-only interface ourselves.** A `NetworkChangeDetector` that delegates to the stock
   `NetworkMonitorAutoDetect` and adds one local-only interface with network handle 0 (the value libwebrtc treats as
   "bind without a network", the way its Wi-Fi Direct delegate does). Installed once through the public
   `NetworkMonitor.setNetworkChangeDetectorFactory`, in Flash code, not in the vendored library.
2. **webrtc-java 0.17.0 → 0.19.0**, all three version sites (`third_party/webrtc-kmp/gradle/libs.versions.toml`
   and the per-OS native artifacts in `core/calling` and `desktop`). The jvm webrtc-kmp layer now disposes every
   sender/receiver/transceiver instance a connection obtained when it closes (`PeerConnection.own`), and the local
   audio track disposes its source on stop. `DesktopVideoSink` keeps `frame.release()` (0.19.0 still hands Java a
   reference per frame).

### Alternatives considered
- `Options.disableNetworkMonitor = true`: simpler, and fixes the hotspot, but every socket becomes unbound. When
  Android's default network is cellular (Wi-Fi without internet), Wi-Fi-sourced packets can be routed via the
  cellular table. Changes behaviour for every call to fix one case.
- Field trial `WebRTC-AndroidNetworkMonitor-IsAdapterAvailable/Disabled/`: makes the interface "available" but the
  bind still fails (`ADDRESS_NOT_FOUND`).
- Disabling the monitor only while hosting: the factory is built once per process, before a hotspot may be turned
  on, so this would need a process restart.
- Staying on 0.17.0: keeps the per-close native leak and the JNI bugs 0.18.0 fixed.

### Consequences
- Only one local-only interface can be reported at a time (native maps are keyed by handle); a hotspot wins over a
  Wi-Fi Direct group.
- A 2 s interface poll runs while WebRTC is monitoring (during calls), on a daemon thread.
- Phones that are not hosting anything are unaffected: the stock detector's output is passed through unchanged.
- Desktop RTP wrappers used after their connection closed throw `NullPointerException` instead of touching a
  freed object; Flash's tuning calls already catch.

### Revisit when
A libwebrtc upgrade on Android adds tethering support to `NetworkMonitorAutoDetect`, or Flash needs two local-only
links at once (then give each a real `Network` via `ConnectivityManager` where the platform allows).

---

## ADR-055 — Schema migration SQL is written once in commonMain; each platform only wraps it

### Date
2026-09-29

### Status
**ACCEPTED** — implemented and unit-tested (desktop upgrade tests run a real encrypted file; no device check applies
to desktop, see ERROR-080).

### Context
`FlashMigrations` (v1→v2→v3→v4) lived only in `androidMain` as `SupportSQLiteDatabase` migrations. The desktop JVM
opener (`openFlashDatabase`) registered none, and there was no destructive fallback (C1.7), so any desktop file older
than the current schema would throw `A migration from X to Y was required but not found` — and `DesktopEngine`
swallows a database failure and degrades to an empty chat repository. No such desktop file exists today (desktop first
opened its database on 2026-09-16, at v4 already), so the gap was latent: the **next** schema bump would have hit it,
and nothing forced anyone to remember it. Neither platform had a test that executed a migration.

### Decision
1. The SQL of every step lives once, in `commonMain` (`FlashSchemaSteps`, an internal list of `FlashSchemaStep`).
2. Android's public `FlashMigrations` (same names, same types, same `ALL`) is built from that list with
   `SupportSQLiteDatabase.execSQL`. Its behaviour is unchanged.
3. The JVM has an internal `FlashJvmMigrations` built from the same list with `SQLiteConnection.execSQL`, registered by
   `openFlashDatabase`, so every JVM open path upgrades an old file.
4. Tests make a forgotten step a build failure: a chain check on both platforms (`FlashSchemaStepsTest`,
   `FlashMigrationsChainTest`) and a JVM suite (`FlashJvmMigrationsTest`) that uses Room's KMP `MigrationTestHelper` to
   build v1 and v3 files from the exported `schemas/<n>.json`, upgrade them with the encrypted production driver, and let
   Room validate the result against the current schema. One test goes through `openEncryptedFlashDatabase`, which is
   what `:desktop` calls; removing the `addMigrations` line makes it fail with Room's original error (checked).

### Alternatives considered
- **One `Migration` written against `SQLiteConnection` in commonMain, deleting the Android file.** Room 2.8.4 supports
  it and it would remove the wrappers, but it changes the SQLCipher production path on Android, which has no automated
  migration test and could not be device-checked in this session. Revisit once an Android migration test exists.
- **Copy the SQL into `jvmMain`.** The exact drift this ADR removes.
- **Room `@AutoMigration`.** Would need the exported schemas to be complete (there is no `2.json`) and changes the
  established explicit-migration policy.

### Consequences
- Adding a schema version = bump `DATABASE_VERSION`, append one `FlashSchemaStep`; both platforms pick it up.
- `room-testing` is a new **jvmTest-only** dependency (already in the catalog for Android host tests; Apache-2.0).
- The Android SQL is now proven correct at the schema level only through the shared list; the wrapper itself is
  covered by a chain check and by earlier on-device upgrades. Test MIG-01 in `docs/testing/TEST-BACKLOG.md` covers it.

### Revisit when
An Android migration test exists (then collapse to a single commonMain `Migration`), or Room drops
`SupportSQLiteDatabase` migrations.

## ADR-056 — Postpone QR, BLE, Wi-Fi Direct, group-attachment fan-out and the scale measurement to FUTURE-OPTIMIZATION.md

### Date
2026-09-29

### Status
**ACCEPTED by the owner** (chat, 2026-09-29). A scope and ordering decision; no architecture changes by itself.

### Context
The discovery resilience plan (ADR-047) had eight phases and the presence plan's PC6 measurement (ADR-044 V3) gated
PC7's tuning, the per-mode session cap and any group size above 20. The owner wants immediate development, not
a queue behind device measurements (see decision P8, "measure last").

### Decision
1. **Postponed** to [`docs/FUTURE-OPTIMIZATION.md`](FUTURE-OPTIMIZATION.md): DR4 QR first contact (FO-01), DR6 BLE
   discovery (FO-02), DR7 Wi-Fi Direct (FO-03), group file sending fan-out **and a later revamp of file sending** (FO-04),
   and PC6 / MEAS-02 (FO-05).
2. **Continue** with DR2 (broadcast beacon), DR3 (subnet sweep) and DR5 (hardening), in that order. DR0 / MEAS-07 stays a
   measure-last device task; DR2 and DR3 do not wait for it.
3. **Then** the group trust model (ADR-044 V0 to V2) and the per-mode session cap, aiming at groups of **20**.
4. Postponed items are marked, never deleted: plan rows say POSTPONED, TEST-BACKLOG MEAS-02 says POSTPONED, and
   `FUTURE-OPTIMIZATION.md` says what the rest of the project assumes in their absence and what brings each back.

### Consequences (derived from the decision; flagged so they are not mistaken for extra owner choices)
- **32 members stays parked.** ADR-044 makes it depend on V3, which is FO-05. `MAX_MEMBERS` targets 20.
- Every PC number tagged *(measure)* stays an estimate; the per-mode session cap is designed by reasoning and labelled as
  such. PC7 is no longer blocked.
- While DR4 is postponed, DR5's "No devices found" hint offers *Scan network* and *Connect by IP*, not *Show QR*.
- Group attachments keep the per-recipient model (N-1 uploads). Acceptable at 6, expensive at 20; ADR-044's "revisit if
  attachments in groups land" is now FO-04.
- DR7 has no plan file yet (`docs/network/WIFI-DIRECT-PLAN.md` is unwritten).

### Alternatives considered
- **Keep the phases in the plans as TODO.** Rejected: they would read as next work and a future AI would start them.
- **Delete them.** Rejected (AGENTS.md §27) and contrary to the owner's wish that nothing be forgotten.

### Revisit when
The owner reprioritises; each item's own "Bring it back when" in `FUTURE-OPTIMIZATION.md`.


## ADR-057 — Session ceiling 24 for every mode; STANDARD and BOOST limit their own dials in a crowd

### Date
2026-09-29

### Status
**IMPLEMENTED, device check pending (SC-01, SC-02).** Every number here is a reasoned estimate, not a measurement (FO-05,
ADR-056). It completes the "per-mode session cap" item the owner ordered after the group trust model's V1a (ADR-056 item 3).

### Context
`SessionHardeningPolicy` allowed 8 live sessions on every host. A 9th peer was refused ("session cap reached") and both sides
retried every 5 s, so a group above 9 members could not form a full mesh. ADR-044 V2 raises groups to 20 members, which needs
19 sessions per phone. The plan (PC2, ADR-045 item 6, ADR-048 item 6) had left "the cap per mode" open, waiting for PC6, which
ADR-056 postponed.

Facts from the code that shape the answer:
- Admission is enforced in one place per host (`WsFlashNetwork.registerSession`, `JvmWsFlashNetwork`), first-come, and a
  replacement of an existing peer's session bypasses the cap.
- **ECO never refuses an incoming session** and only parks sessions it dialed (ADR-048, controller doc item 4). So an ECO
  phone in a group of STANDARD phones holds every inbound session; a lower ECO ceiling would break that invariant.
- What differs between modes is how many sessions a device *asks for*: ECO dials ring neighbours plus active and call peers
  (`EcoLinkSelector`); STANDARD and BOOST dial every discovered device (`ConnectionPlanner`, which has no pairing rule).
- Each live session pins one `Dispatchers.IO` read thread (blocking socket read with a timeout). The pool is max(64, cores)
  and shared with the whole app.

### Decision
1. **One admission ceiling, 24, for every mode and hardware tier.** 19 group peers, plus one call or transfer peer outside
   the group, one pairing peer, and slack for a duplicate being replaced and a reconnect in flight (19 + 1 + 1 + 3 = 24).
   24 pinned read threads leave 40 of 64 for everything else.
2. **`DIAL_HEADROOM = 4`, `DIAL_BUDGET = 20`.** STANDARD and BOOST dial everyone while the devices around fit the budget, so
   a normal room behaves exactly as before. Above it, `DialBudget` returns the set the planner may dial: sessions already
   held, busy (in-call) peers, then contacts (paired peers and group members), then strangers, ranked by device id inside each
   class so the answer is stable. While the Nearby screen is open the limit is the full ceiling (the user is looking for
   someone to pair with). ECO is unchanged. Nothing is closed: the budget only stops new dials, and dial on demand, gateway
   probes and sweep hits ignore it (as they do for ECO).
3. **No per-mode or per-tier admission ceiling.** A mode changes what a device asks for, not what it admits.

### Alternatives considered
- **A different ceiling per mode (for example ECO 8, STANDARD 24).** Rejected: an ECO phone would refuse inbound sessions,
  the STANDARD peers would redial every 5 s, and a group of 20 would never settle. It contradicts ADR-048.
- **A lower ceiling on LOW-tier phones.** Rejected: it would sit below the group's need and produce the same refuse-and-retry
  churn, which costs more radio than holding the sessions. The LOW tier already gets longer keepalive floors.
- **Raise the ceiling and stop there.** Rejected: STANDARD dials strangers too, so in a crowd a phone would fill 24 slots
  with them and refuse the peers that matter. The dial budget is the small rule that prevents it.
- **A much higher ceiling (32 or more).** Rejected until measured: it is the same estimate with less headroom against the
  thread pool, and groups above 20 are parked (FO-05).

### Consequences
- A 20-member group can form a full mesh on every host (device check SC-01).
- **Known gap, kept on purpose:** strangers that dial *in* are admitted first-come up to the ceiling. With 24 or more
  strangers dialing one phone the ceiling can still fill, and a paired peer that arrives afterwards is refused. The headroom
  only protects against this device's own dials. Closing it means priority admission (contacts displace strangers), which
  needs the network layer to know contacts (a lambda from each host) and a rule for which session to evict. Not built;
  revisit if a crowded-room device check shows it.
- ECO with the Nearby screen open can still want every stranger (its own rule); admission bounds it.
- Handshake cost: a Wi-Fi rejoin now re-handshakes up to 19 peers per phone instead of 7. `ReconnectStagger` (ADR-045)
  spreads a storm; PC0/PC6 would measure it (FO-05).
- V2 must assert `GroupPolicy.MAX_MEMBERS - 1 <= ConnectionModePolicy.DIAL_BUDGET` where both are visible.
- PC0 numbers taken before this change were measured at a ceiling of 8 (runbook note).

### Revisit when
A device check shows refusals with contacts present (build priority admission), FO-05 measures the cost of a session (tune
24, 4 and 20), or groups are raised above 20.

## ADR-058 — `:core:ptt` becomes Kotlin Multiplatform behind an audio-device seam; the desktop app joins push-to-talk

### Date
2026-09-30

### Status
**IMPLEMENTED, device check pending (PTTD-01...PTTD-07).** The desktop <-> phone path has been exercised only by two real
`DesktopEngine`s with fake microphones and speakers (`DesktopEnginePttTest`); no sound has crossed a wire between machines.

### Context
Push-to-talk (ADR-031, ADR-032) lived in `:core:ptt`, a plain AGP Android library: `AudioRecord`, `AudioTrack`,
`SystemClock` and `android.util.Log` inside the engine itself. A plain AGP module has no JVM variant, so the desktop app
could not depend on it (ERROR-049) and a Windows user could not take part in a PTT session even though the floor machine
(`PttFloorMachine`) and the wire codecs were already platform-free in `:core:messaging`.

The owner's goals at the time of the decision: desktop PTT now, and **iOS and Linux support later**. A refactor that does not
move toward those two is cost without benefit.

### Decision
1. **`:core:ptt` is a KMP module with an Android and a `jvm()` target** (same plugin pair as the other converted modules,
   `explicitApi()`, `-Xexpect-actual-classes`). The engine, `PttPlayoutCore` (drop-oldest inbox plus `PttJitterBuffer`) and the
   public types are **commonMain**: `PttSessionEngine` uses only `FlashLog`, `UuidIdGenerator`, `SystemTimeSource`, coroutines
   and the codecs/floor machine from `:core:messaging`.
2. **One seam for the hardware: `PttAudioPlatform`** with `PttCaptureDevice` and `PttPlayoutDevice` (commonMain interfaces).
   The engine receives it as a constructor argument (default `platformPttAudio()`), as it does `elapsedRealtimeMs`. The Android
   actual wraps `AudioRecord`/`AudioTrack` unchanged in behaviour; the JVM actual uses `javax.sound.sampled` through a small
   `PttPcmLines` abstraction so the loops are unit-testable without hardware. Three other `expect`s: `pttElapsedRealtimeMs()`
   (public: a session card must read the clock `PttFloorState.startedAtMs` came from), `PttLock` and `platformPttAudio()`.
3. **Decision D1 = Option B stands.** `commonMain` stays strict; the JDK-bound bodies are duplicated per target, **not** shared
   through a `jvmAndAndroidMain` intermediate source set. The two JDK-flavoured helpers (`PttLock`, the nanoTime clock) are
   eight lines each.
4. **The session card is shared.** `PttSessionOverlayContent` (role, elapsed, stats, level meter, Stop/Leave) moved from the
   Android app to `:ui:callui` commonMain, which now `api`s `:core:ptt`. Android keeps the deferred hardware press, the
   `RECORD_AUDIO` prompt and the notification mirror in its wrapper; the desktop shell adds a mic button (rail footer or floating),
   `Ctrl+Shift+T` (in-window only) and the snackbar. Design: UI-051 Addendum A.
5. **`DesktopEngine` wiring:** it builds the same `PttSessionEngine` over its WS sessions. Members are the active sessions that are
   paired; PTT text and `PTT1` binary frames are routed to it before the pairing/chat/transfer parsers; `stop()` shuts it down;
   a call becoming active calls `onCallStarted()`. The audio pair is a constructor parameter so tests never open a microphone.
6. **No global hotkey on desktop**, no notification actions, no mic-permission step (Windows' privacy switch is the gate and may
   silently deliver zeros: PTTD-03).
7. **`:core:engine` is unchanged:** it still names `:core:ptt` in `androidMain` only. Moving it to commonMain is now *possible*
   (the variant-selection failure of ERROR-049 no longer applies to `:core:ptt`), but it is a public-API and publication change
   with no current consumer, so it waits for the engine refactor (ENG-1) if that is ever approved.

### What this does and does not buy (iOS and Linux)
- **Linux:** the JVM actual runs on Linux as written (`javax.sound.sampled` exists there). The remaining Linux items are outside
  this module (identity vault actual, webrtc-java natives, packaging); PTT itself needs only a device check (PTTD-02).
- **iOS:** the engine and the floor machine are now commonMain, so an iOS target only has to supply a `PttAudioPlatform` actual
  (AVAudioEngine) and the three small `expect`s. That is a **real step**, not the whole job: iOS also needs the transport and TLS
  off the JDK, Bonjour discovery, a WebRTC iOS stack, a Mac build host, and an iOS app. Nothing in this ADR starts any of it.
- **Rejected alongside it:** the audit's NET-1 proposal of a `jvmAndAndroidMain` source set. It would share JDK code between
  Android and desktop, gives nothing to iOS or Linux-as-a-target-of-its-own, and contradicts D1 = Option B. Rejected for now; if
  duplication between the two JDK targets becomes a maintenance cost, a drift-guard test is the alternative to try first.
  ENG-1, MSG-2 and APP-2 remain **deferred** (maintainability only; the owner has not approved them).

### Alternatives considered
- **Desktop-only PTT engine in `:desktop`.** Rejected: a second copy of the floor driver, which has already had correctness bugs
  (ERROR-046, -048, -050). The one engine is what makes desktop <-> phone interoperable by construction.
- **`jvmAndAndroidMain` for the shared JDK code.** See above; rejected under D1 = B.
- **Opus instead of raw PCM on the wire.** Out of scope: the wire format is ADR-032's and is unchanged, so old and new builds
  interoperate.
- **A global (system-wide) hotkey.** Needs a native key hook the project does not have and would fire while typing in other apps.
  Revisit on an owner request.

### Consequences
- `ERROR-049`'s constraint ("`:core:ptt` has no JVM variant") is gone; the entry stays as history with an update.
- `:ui:callui`'s POM gains `core-ptt` as an `api` dependency (published, and already in `jitpack.yml`).
- `PttCapture`/`PttPlayout` are now `internal` Android classes; their nested `StartResult`/`Snapshot` types are top-level
  `PttCaptureStart`/`PttPlayoutSnapshot`.
- Android behaviour was re-shaped (engine moved, devices behind an interface): **the Android PTT path must be re-verified on a
  phone (PTTD-05)**, on top of the standing fact that Android PTT never passed its own device gate (ADR-032).
- Known desktop limits: cold capture open took about 1 s the first time and about 0.23 s later on this machine (EXP-018), so the
  first words of a session can be clipped; a pre-warm is the obvious fix and is **not built** (PTTD-04). Windows' microphone
  privacy switch can produce silent capture that the engine cannot detect (PTTD-03).

### Revisit when
A device check shows clipped starts (pre-warm), an iOS target is approved (write its `PttAudioPlatform` actual and revisit the
engine's `api(project(":core:ptt"))` placement), or duplication between the two JDK targets starts to hurt (drift guard first).


---

## ADR-059 — Message Info is a pure Kotlin mapping over three flows, and the catch-up banner is indeterminate

### Date
2026-09-30

### Status
**IMPLEMENTED, device checks pending (CGS-06, CGS-07).**

### Context
The chat/group sync audit (`docs/audit/2026-09-28-chat-group-sync-audit-and-plan.md`) proposed (a) a SQL join across
`group_deliveries`, `group_members` and `read_cursors` for a "Message Info" screen and (b) a determinate progress bar while a member
catches up on group history.

### Decision
1. **Message Info** (`observeMessageInfo`, `MessageInfoBuilder`): `GroupDeliveryDao.observeForMessage` (one scoped query) is combined in
   Kotlin with the roster and the read cursors. First match wins per member: read cursor `upToSentAt >= sentAt` (the predicate of
   `MessageDao.markReadUpTo`) -> Read; delivery state `DELIVERED` -> Delivered with its time; otherwise Waiting (LeftGroup when the roster
   row is inactive). Only members with a delivery row are listed. A direct message gets a single recipient taken from its status; a
   message that is not ours, deleted, or a call row gets none. The sheet is a `Flash*` component (UI-051), opened from the context menu
   and the ticks.
2. **Catch-up banner** (UI-052): indeterminate strip with an arrival count. It starts on the first row newly inserted by a `SyncPush`
   (a duplicate does not count) and clears `GroupPolicy.SYNC_QUIET_MS` (3 s, above the slowest 200 ms pacing gap and the 2 s backup
   delay) after the last arrival; a generation counter stops a stale timer from closing a newer banner.

### Alternatives considered
- SQL join: rejected, it would duplicate the read predicate in SQL and need a Room schema-independent test path; the flows are small
  (at most 20 members).
- Determinate bar: rejected, the requester receives no total and cannot derive an end; a wrong percentage is worse than none.

### Revisit when
Device checks show the 3 s quiet period is too long or too short for real pacing, or a protocol change lets a holder announce a total
(then a determinate bar becomes honest).


## ADR-060 — A group call reaches ENDED before any native work, prunes legs that never answer, and protects its participants, not its group id

### Date
2026-09-30

### Status
**IMPLEMENTED (unit-tested, mutation-checked), device checks pending (GCALL-01…GCALL-07).** Fixes ERROR-086.

### Context
A group call left alone for 30 s could not be closed (ERROR-086): the solo grace timer's `endSession` cancelled its own coroutine,
the native teardown aborted, and because ENDED and `onEnded` came only after that teardown, the call never ended, hang-up did nothing
and the coordinator refused every later call. The audit found ten related weaknesses in the same code (rings that never stop, legs
for members who never joined, no recovery of a failed leg, no joiner timeout, a mode-controller `busy` set holding the group id).

### Decision
1. **Order of ending.** `endSession` claims the end atomically (`endClaim`), cancels the other timers (never the coroutine it runs
   in), publishes ENDED, calls `onEnded`, and only then releases the native resources, under `NonCancellable`. This is the order
   the 1:1 `FlashCallSession.end()` already had. ENDED is terminal: every other UI write goes through `updateUi`, a compare-and-set
   that refuses to leave ENDED. Native work that was queued before the end checks `isEnded` inside the media thread and lifecycle lock.
2. **The coordinator trusts nothing about a finished session.** One `onGroupSessionEnded` (was copied three times), and every call
   entry point plus `hangUp()` first drops a live-call slot held by a session that `isSessionEnded`.
3. **Who is in the call is learned, not assumed.** A joiner still builds a leg for every member (it has no roster of the call), but
   a leg whose peer sent nothing for 15 s after its connection was built is closed and shown as INVITED; a presence or a join frame
   from that peer brings it back; a peer that left is never brought back. A leg that fails is rebuilt by the offerer only, at most
   3 times. A joiner or accepter that connects to nobody in 45 s ends with ERROR. The solo grace and the connect deadline wait
   (10 s at a time, for attempts younger than 40 s) for a leg whose peer is answering.
4. **Ringing ends.** An incoming group call rings at most 45 s and stops when the caller hangs up (unless another member is in it); an
   outgoing one ends after 30 s with nobody, sends GroupHangup to the invitees, and ends at once when everyone declines.
5. **One rule for "who does this call need".** `FlashCallUiState.busyPeerIds` (1:1: the peer; group: connecting / connected /
   disconnected participants, plus invited ones while dialing or ringing; ended: none) feeds `LinkView.busy` on Android, in the
   `Flash.kt` facade and on the desktop.

### Alternatives considered
- **Only stop the timer cancelling itself.** Rejected as the whole fix: any other throw or hang in the teardown would again leave a
  call that cannot be closed, which is the failure the owner cannot recover from without a force-stop.
- **Create legs only for members who sent a join (the audit's first idea for phantom legs).** Rejected: the offerer of a leg is the
  higher device id, so a joiner with the higher id would wait for an offer from a member that never knew it had joined, and a
  lower-id member would never offer. Pruning keeps the symmetric handshake and costs one idle PeerConnection for 15 s.
- **A UI "force close" that resets the coordinator.** Not built: hang-up now cannot be blocked before ENDED. Revisit if GCALL-01 still
  traps a call on a device.
- **Leave `busy` as the call's peerId.** Rejected: for a group call that is the group id, which is no device, so no session was
  protected.

### Consequences
- A member who is in the call but whose first frames were lost may be shown as INVITED for up to ~4 s until its next presence frame.
- Up to 3 rebuilds per failed leg means up to ~9 s of extra recovery delay before a leg is given up; the call itself continues.
- Not done: group call log rows (ERROR-086 (g)); the 1:1 session is unchanged.

### Revisit when
GCALL-01…07 show a timer value (15 s, 30 s, 45 s) is wrong on real networks, or a leg that is pruned wrongly is reported.

## ADR-061 — A group call is offered to its roster members and dials them; the live-key check stays on every announcement

### Decision
1. **Two questions, two predicates.** "Who is in this group's call" is `isGroupCallMember`: an active roster member, this device not
   removed or left, and the member is paired or its owner-signed certificate names a key (`SignedGroups.hasVouchedRosterKey`). It needs
   no session. "May this frame go to, or come from, this connection" stays `isGroupCallPeer` (ADR-044 V2: a vouched member needs a live
   session whose TLS key is the certified one). `CallCoordinator` takes both (`isGroupMember`, `isGroupTrustedPeer`); a host that
   supplies only the second keeps the old, strict behaviour.
2. **Membership is decided from the roster, the key is checked when a frame is sent.** A call's member list is built with the first
   predicate (start, join, query). Every announcement (`GroupInvite`, `GroupPresence`, `GroupAccept`, `GroupJoin`, `GroupQuery`) goes
   through `CallCoordinator.sendGroupFrame`: membership, then `reachPeer`, then the strict gate, then send. Offers, answers,
   candidates, declines and hangups are never dialed.
3. **Dial on demand.** `reachPeer` is `AutoConnector.ensureSession(peer, 3 s)` on both hosts, allowed in ECO (an ECO device parks
   sessions to save battery; a call is the reason to wake one).
4. **Invites are re-offered, presence reaches everyone offered.** An undelivered invite is offered again by the 4 s presence tick while the
   invitee would still ring (45 s). The invite's member list is kept by every receiver as announce-only targets of its own presence
   tick (no leg, no connection), so a member the caller cannot reach still learns of the call from any participant that can.
5. **The caller is told.** `FlashCallParticipantUi.reachable`; the tile reads "Not reachable yet". Every refusal logs under `GROUP_CALL`
   (once a minute per peer, frame and cause, since the presence tick would repeat it every 4 s).

### Context
ERROR-088: a member of a v2 group who is not paired with the caller (vouched by the owner) never saw the call. The strict gate was applied
once, at the tap, to build the member list, so a member with no live session at that moment never got a leg, an invite or a presence;
nothing dialed it; nobody was told. Pairing every member with every other member (what the owner's report implies) is exactly what
ADR-044 V2 exists to avoid.

### Alternatives considered
- **Relax the strict gate for calls** (accept a vouched member without a live key). Rejected: the live-key match is what stops a device
  that answers on a member's address from receiving the invite and the presence of a call; the roster says who is a member, not who is
  on the other end of this socket.
- **Dial from the UI before starting the call.** Rejected: it would block the tap on the slowest member and still not help a member who
  connects later; per-announcement dialing covers both.
- **Build a connection for every invited member from the start.** Rejected: a leg is a native PeerConnection; an unreachable or absent
  member would cost one per call and count against the size cap. Announce-only entries cost a text frame every 4 s.
- **Query the group on every presence tick (`queryGroupCall`).** Not wired: presence from participants already reaches every member.

### Consequences
- A member nobody can reach still sees nothing; the call needs at least one participant with a path to it.
- A 3 s dial budget is a guess (unmeasured); a longer dial is retried on the next 4 s tick.
- The hosts still ignore the Boolean from `startGroupCall` / `joinGroupCall`; a refusal is only logged.
- `FlashGroupCallSession.armPresenceAnnouncement` and `CallCoordinator.sendGroupFrame` are `internal` so the host tests can drive them.
- Not device-verified: `GCALL-08`, `GCALL-09`, `GCALL-10`.

### Revisit when
GCALL-08..10 show the dial budget, the retry window or the announce-only relay behaving differently on a real network, or when a
screen for "who cannot be reached" is designed.

## ADR-062 — The read cursor is never overwritten by a row refresh, a close flushes the acknowledgement, and a new message unarchives

### Decision
1. **Refresh the stored row, never replace it.** A direct chat's inbound text and outbound send, and a legacy group's `Create` / `State`
   frame, read the stored `ConversationEntity` and write a `copy` of it (`upsertDirectConversation`, `upsertLegacyGroupConversation`),
   inside the **same write transaction as the message insert**. `lastReadCursor`, `pinned`, `muted` and a legacy group's position and
   provenance therefore survive; `touchConversation` already worked this way.
2. **A new message unarchives the chat** (`archived = false`), as `touchConversation` already did for group messages and attachments.
   Pin and mute are kept.
3. **Leaving a chat finishes its acknowledgement.** The open chat records what its screen showed (`OpenConversationReadState`);
   `acknowledgeShown` is idempotent; `closeConversation()` and opening another chat call `endOpenConversation()`, which writes any
   cursor and sends any Read receipt that the collector had published but not yet written.
4. **System back closes the chat** on Android (`MainActivity.kt`: the nav entry leaving `Conversation` calls `closeConversation()`).

### Context
ERROR-087: `ConversationDao.upsert` is Room `@Upsert`, a full-row replace, and a freshly built `ConversationEntity` has a NULL cursor, which
`observeUnreadCounts` reads as "no message was ever read". Every inbound direct text reset the cursor, so the badge counted the whole
history; and the open chat's "publish to the screen, then write the cursor" could lose the second step to a close or to `collectLatest`.

### Alternatives considered
- **`@Insert(IGNORE)` plus targeted `UPDATE`s** (so the full-row replace can never wipe a field again). Better in principle, left for a
  later schema/DAO pass: it changes every writer of the table; the copy-in-a-transaction is the smallest fix with a real-Room proof.
- **Re-assert the cursor on every emission** instead of flushing on leave. The collector already acknowledges on every emission; the gap
  was the cancelled write, which a flush on leave closes.
- **Keep an incoming message from unarchiving.** Rejected: an archived chat that receives a message and stays hidden loses the message
  for the owner, and the group paths already behaved this way.

### Consequences
- Still open: `onStop` with a chat open on Android (UNREAD-05), the sender's clock in `sortOrder` and the cursor comparison (root cause C),
  and the fake `InMemoryMessageDao.observeUnreadCounts` ignoring the cursor.
- Not device-verified: `UNREAD-01`...`UNREAD-05`.

### Revisit when
UNREAD-01..05 show the badge wrong on a phone, or the DAO is reworked to targeted updates.

## ADR-063 — Group Admins (`ROLE_ADMIN`) and Successor on Leave: Delegated Group Authority and Two-Hop Vouching for Resilient Groups

### Date
2026-10-01

### Context
In v2 groups (ADR-044), the group identity and root of trust are established by the founding charter (`GroupCharter`), derived deterministically from `(founderKey, nonce)`. In ADR-044 V1/V2, only `charter.ownerId` was permitted to issue membership certificates (add/remove members).

The edge-case audit (`docs/audit/2026-10-01-chat-edge-case-audit.md` §1) identified that owner loss is common rather than rare (app reinstallation, data clear, or new phone generates a new identity, severing the owner permanently). When the creator is gone, the group roster freezes permanently: no new members can be added, dead members cannot be removed, and the owner row remains active indefinitely.

Option D ("Continue in a new group") was implemented to rescue already-orphaned groups without wire or trust changes. Option A ("Co-owners / Admins & Successor on Leave") provides the long-term solution for active groups by allowing delegated administration and orderly ownership transfer.

### Decisions

1. **Three Distinct Membership Roles:**
   - `ROLE_OWNER` ("owner"): Primary authority of the group. Initiated by the charter founder, or designated via an owner-signed successor certificate. Can promote/demote admins, add/remove members, designate a successor owner, or self-leave.
   - `ROLE_ADMIN` ("admin"): Delegated administrator appointed by an active owner. Can add regular members and remove regular members. Cannot remove or demote an owner or another admin. Cannot promote anyone to admin or owner.
   - `ROLE_MEMBER` ("member"): Standard participant. Can send messages, react, vote, and self-leave.

2. **Certificate Rules and Cryptographic Verification (`GroupSignatureRules`):**
   - Supported roles: `MemberCert.ROLE_OWNER` ("owner"), `MemberCert.ROLE_ADMIN` ("admin"), `MemberCert.ROLE_MEMBER` ("member").
   - Issuer authorization:
     - The charter owner (`charter.ownerId`) or an active certified owner (`ROLE_OWNER`) can issue any cert (`ROLE_MEMBER`, `ROLE_ADMIN`, `ROLE_OWNER`, and tombstones `active = false`).
     - An active certified admin (`ROLE_ADMIN`) can issue `ROLE_MEMBER` (`active = true`) and tombstones (`active = false`) for regular members (`ROLE_MEMBER`). An admin cannot issue `ROLE_ADMIN` or `ROLE_OWNER`, and cannot remove an owner or admin.
     - Any member can issue their own leave tombstone (`active = false`, `issuerId == subjectId`).
   - Signature verification:
     - If `issuerId == charter.ownerId`: verified against `charter.ownerKey`.
     - If `issuerId != charter.ownerId`: verified against the certified public key of the issuer (`issuer.subjectKey`), which must be an active owner or admin in the group.

3. **Two-Hop Trust Delegation (Amending ADR-044 Decision 3):**
   - ADR-044 Decision 3 assumed one-hop vouching strictly rooted in the charter owner.
   - Under ADR-063, trust delegation is extended to two hops: `Owner -> Admin -> Member`.
   - When an owner-promoted admin introduces a new member, the member cert is signed by the admin's certified key. Receivers verify the admin's active status and accept the vouch into the trust store (`port.vouch`).

4. **Successor on Owner Leave:**
   - When an owner initiates "Leave group" in a group that has other active members:
     - The Leave flow requires the owner to choose an active member (an existing admin or member) as their successor.
     - The owner issues a `ROLE_OWNER` certificate for the successor (`seq + 1`, signed by the owner).
     - The owner then issues their own leave tombstone (`active = false`, `seq + 1`, signed by the owner).
     - Both certificates are broadcast in the leave bundle. The successor assumes full `ROLE_OWNER` authority.

5. **Wire & Persistence Compatibility:**
   - `GroupCanonical.certBytes` already writes `cert.role` as a UTF-8 string field (`.text(cert.role)`). The canonical byte format and tag (`flash-gcert-v1`) remain unchanged.
   - Room `GroupMemberEntity` already stores `role: String = "member"`. No database schema bump is required.
   - Existing UI models (`FlashMemberRole.Admin`, `FlashGroupMembersMath.roleBadgeLabel`, `FlashGroupMembersMath.roleRank`) already accommodate admins.

### Alternatives considered and rejected
- **Symmetric co-owners (multi-owner peer mesh with no hierarchy):** Rejected. Any owner could remove any other owner, creating race conditions and coup surfaces during concurrent split-brain operations. The hierarchical Owner -> Admin model maintains deterministic authority.
- **Succession by majority voting (Option C):** Rejected as excessive complexity for local network mesh. High latency, split-vote deadlocks, and attack surfaces under dynamic connectivity.

### Consequences
- Groups with appointed admins survive owner loss (such as uninstallation or phone destruction) without roster freeze.
- When an owner leaves gracefully, leadership is explicitly transferred to a successor.
- Two-hop vouching allows admins to onboard new members without requiring the founder to be present.


## ADR-064 — A legacy group never gets a member it cannot trust into a call; a group is not silently made legacy; the caller is told who was left out

### Decision (2026-10-01, ERROR-095)
1. **Trust is not weakened.** A member of a **legacy** group (UUID id, unsigned roster) that the caller is not paired with is not called.
   No TOFU on the roster, no "paired with anyone in the call" shortcut. The only way such a member joins a call is a signed v2 group
   (ADR-044 V2: owner-vouched key, live-key match, ADR-061).
2. **Creation never silently produces a legacy group.** When this device can sign, `createGroupLocked` creates a legacy group only for
   an invitee that was **seen** on a live session reporting the old level. An invitee with no live session is of unknown level
   (`peerGroupProtocol` answers 1 for it) and creation fails, naming it: "Wait until X show as online, then try again." A capable
   invitee with no verified key keeps the existing `V2_KEY_UNAVAILABLE` failure. A device that cannot sign (`signedGroups == null`)
   is unchanged.
3. **The caller is told.** `CallCoordinator.callMembers` returns the callable members and the left-out ones with a reason; the session
   shows the left-out as tiles ("Not paired with you", `FlashCallParticipantUi.note`), which are not legs and receive no frame.
   Android toasts when `startGroupCall` returns false (desktop already did).
4. **A finished group session never answers "busy".** `onInboundText` clears an ended group session first (as the start paths do), and the
   busy declines log the reason.

### Context
The owner reported that a group call started by a device not paired with every member "doesn't work". The desktop log showed a legacy
group, a member left out with one log line, and an instant decline from a called member. Legacy groups are forgeable by design (ERROR-082,
accepted by the owner), so the call gate cannot rely on the roster for them. The group was most likely legacy because it was created while
some members were offline.

### Alternatives considered
- **Trust a legacy roster member for calls.** Rejected: anyone who can forge the roster gets the call's presence and media.
- **Put all roster members in the invite's member list so a callee paired with the missing member relays the announcement.** Not built:
  the missing member could then see and join the call through the callee, but the caller and that member still have no trust path, so
  the caller-to-member media leg (a full mesh needs it) cannot be built; the partial call would look connected and be broken. Revisit
  only with a partial-mesh design.
- **A reason on `GroupDecline`.** Not built (no wire change in this fix); the callee's log now says why.
- **Allow creating a legacy group with an offline invitee after a confirmation.** Not built: it is the failure mode being removed.
  Reconsider if owners need to create groups while members are offline; the clean answer is then v2 with a member added later
  (`addV2MembersLocked` needs the key too, so the same wait applies).

### Consequences
- Creating a group while any member is offline now fails with a message; the owner waits or creates a smaller group.
- An existing legacy group keeps its limit; "Continue in a new group" or a new group from a device paired with all members fixes it.
- A call can show a tile that stays "Not paired with you" for its whole length.
- Not device-verified: `GCALL-11`…`GCALL-14`.

### Revisit when
GCALL-11..14 are run, or when owners report the offline-member creation failure as a blocker.


---

## ADR-065 — Every member that joins a group call tells every other invited member directly, a presence adopts a missing leg, and a silent answerer nudges its offerer

### Decision (2026-10-01, ERROR-096)
1. **The inviter is no longer the only route.** A device that accepts a ringing group call sends `GroupAccept` to the inviter and then, once, to
   every other member the call was offered to. The inviter's relayed `GroupJoin` stays as a second route.
2. **A presence is proof the member is in the call.** A `GroupPresence` from a member this device has no leg for, received once this device is
   itself in the call (media acquired; ACTIVE, CONNECTING or DIALING), adds a leg and starts connecting it. Not while ringing, not when
   relayed, not when the call is full (the member is turned away as before).
3. **An answerer that is never offered to speaks up.** An answerer leg (this device id is lower) still CONNECTING 8 s after its connection was
   built, with no remote description, sends `GroupAccept` straight to the peer, at most 3 times, 8 s apart.
4. **No wire change, no trust change.** The live-key gate of ADR-061 still runs on every announcement frame.

### Context
The owner's call log (call `2e1ebc6e`) showed a late joiner (desktop, higher id) with no leg to an earlier joiner (Android, lower id): the
Android waited 27 s for an offer. The mesh was bootstrapped by one relay through the inviter; one lost or missing relay left a pair of
devices permanently unconnected while presence kept both looking healthy.

### Alternatives considered
- **A `GroupRoster` frame from the inviter listing who is in the call.** Not built: a new wire frame (compatibility with older builds) and it
  still depends on the inviter being alive and reachable by everyone.
- **Re-relay joins on every presence.** Not built: the echo-storm risk the existing "only the direct recipient fans out" rule exists to
  prevent.
- **Let the offerer be whoever sees the other first (drop the glare rule).** Not built: it brings back double offers.
- **Prune the unanswered leg sooner.** Not enough on its own: presence keeps the leg looking alive, and pruning only moves the leg back to INVITED.

### Consequences
- A late joiner reaches every other member within a presence tick (4 s) or a nudge (8 s) instead of never, when the member is reachable.
- More `GroupAccept` frames per accept (one per other member); each goes through the existing trust gate and dials the member if needed,
  so a crowd of offline members costs dial attempts (already the case for presence).
- A device may receive `GroupAccept` from a member it has no leg for and build one; a full call still turns it away.

### Revisit when
`GCALL-15`…`GCALL-17` are run, or a call of more than 8 shows the extra accepts as load. If the unproven Android no-presence behaviour turns
out to be a trust refusal, the gate rather than this fan-out is the thing to change.


---

## ADR-066 — Group video: a leg's own voice priority, a floor that fits the copy, a request that is asked again, a surface only for a picture, and a rebuild after Disconnected

### Decision (2026-10-01, ERROR-097)
1. **Voice priority is per leg.** Each `GroupLeg` owns a `CallQualityGovernor` and the `VideoConcession` rung it last chose
   (FULL → REDUCED_BITRATE → REDUCED_RESOLUTION → PAUSED). The stats tick feeds it that leg's RTT, audio jitter and **per-interval** loss; a
   new rung is applied to that leg's video sender only. The "Prioritise voice quality" setting (default on, `prioritiseVoice`) switches it off
   for every leg and returns reduced legs to full. The call's `videoLimitReason` shows the worst rung.
2. **The encoder numbers come from one pure function** (`groupVideoTuning`): the copy's ceiling is the profile's, capped for the copy's height
   (1.8 Mbps / 0.9 / 0.45), the **floor is at most half the copy's ceiling** (so congestion control can still move the encoder) and is dropped on
   every concession rung (a floor is a promise to keep spending). The profile's floor is not removed.
3. **A request nobody answered is asked again** after 5 s, under a new sequence number; a granted request is never repeated. Peers that
   announce the protocol only, so a legacy peer is never asked.
4. **A tile composes a native video surface only while it has a picture** (granted, or an older client that always sends). Keyed on the grant,
   not the track, so renegotiation still re-binds the same renderer.
5. **A leg that stays `Disconnected` for 10 s is rebuilt by its offerer** (the `Failed` path of ERROR-086, now also reached from
   `Disconnected`), unless signaling to that peer is down (`onSignalingRestored` owns that case).
6. **Protocol corrections:** a camera that goes off stops every current watcher at once; a request from a departed peer is ignored; with room
   for one video a denied pin asks nobody else; a muted microphone is never a talker.

### Context
Another AI's audit of group video was checked claim by claim (ERROR-097). Nine claims were real; the group session had reused the 1:1
call's encoder numbers without its voice protection, and the request protocol was not robust to a lost frame, a camera switched off mid-call
or a peer leaving mid-request.

### Alternatives considered
- **Remove the encoder floor entirely** (the audit's P2). Not built: nothing measured says a floor should go; a floor at full height still
  stops a healthy link from dribbling bitrate away. Revisit with `MEAS-09`.
- **One governor for the whole call.** Not built: every leg is its own connection with its own bandwidth estimate; one bad link must not take
  video from the others.
- **A mesh-wide congestion governor / a single-core CPU metric of 120 %.** Not built: WebRTC already estimates bandwidth per connection, and
  120 % of one core would trigger on a healthy desktop. The CPU threshold needs the G0 measurement first (`MEAS-05`).
- **Make the receiver re-request on every reconnect** (the audit's "reconnect hang"). Not built: grants surviving a rebuilt leg is the
  documented design and the sender re-tunes on Connected; the real gap was a lost request, which item 3 covers.
- **Conditional surface keyed on the track.** Rejected: a renegotiation that briefly drops the track would discard and re-create the renderer
  (the renderer-lifetime rule).

### Consequences
- A weak link now costs its leg video (quality, then resolution, then pause) before it costs everyone audio; a recovered link takes about
  5 s of clean samples to restore each rung. The user can switch it off.
- 360p copies on a HIGH device are no longer held at a constant 450 kbps; they may go lower under congestion (down to the floor, 225 kbps).
  *(measure)*
- A leg's video can now restart after a Wi-Fi roam instead of waiting on a connection that never heals; the rebuild costs one offer/answer.
- Requests are repeated every 5 s for a peer that never answers: a few bytes, only for peers known to speak the protocol.
- The numbers 5 s (retry), 10 s (Disconnected rebuild) and the floor ratio (half the ceiling) are provisional, not measured.

### Revisit when
`GVID-01`…`GVID-07` are run, and after `MEAS-09`, `MEAS-05` and `MEAS-03` (floor value, CPU threshold, LOW-tier encoder instances).

## ADR-067 — In-call extras: one additive `status` frame, a host-owned audio-output list, data saver, reactions and raise-hand

### Decision (2026-10-02, UI-050f)
1. **One new signaling frame, `FLASH_CALL action=status`**, carries what a participant's controls say: `mic`, `cam`, `hand`, `rv` (the sender
   wants the other side's video) as optional `1`/`0` flags, and a one-shot reaction (`react` + `rseq`). A missing field means "not stated",
   so an old client's silence and a new client's partial update read the same, and **an old client ignores the whole frame** (unknown actions
   decode to null). Sent on every change and once when media connects; resent when signaling is restored. See `docs/protocol.md`.
2. **State is kept in `CallStatusBook`**, one immutable value swapped by compare-and-set (no lock). A peer that never spoke reads as mic on,
   camera on, hand down, wanting video: the call as it looked before this frame existed.
3. **Reactions** (like / love / wow) are shown once per sequence number per sender and a sender is held to one per 400 ms on **both** ends
   (the sender's limit stops a held button, the receiver's stops a client that ignores it). The list is capped at 8 and entries leave after 3.5 s.
4. **Data saver.** 1:1: the user's device sends `rv=0`; the peer then sets `encoding.active=false` on its video sender (the same switch the
   voice-priority governor uses, folded into `applyVideoConcession`), so nothing is received or decoded and audio is untouched. Group: purely
   local; `CallHealthMonitor.dataSaver` makes the receive cap 0 and no frame is sent (the peers simply stop being asked).
5. **Audio output list.** The host owns routing (ADR-025). `FlashCallAudioRoute` (earpiece, speaker, Bluetooth, wired) and the pure rule
   `FlashCallAudioRouting.resolve` live in `core:calling` and are unit-tested; the Android `FlashCallAudioRouter` reports `routes` and takes an
   explicit `setRoute`. A pick is honoured while the device exists and is forgotten when it goes (a call is never left silent on an unplugged
   headset). The earpiece is pinned **only** on an explicit pick. The desktop reports no routes and keeps its speaker toggle.
6. **Host features that need no wire change:** Android picture-in-picture (manifest `supportsPictureInPicture` + `configChanges`; auto-enter
   from API 31, the More panel row from API 26), keep-screen-on during a live video call, and the proximity wake lock for a live voice call on
   the earpiece (`FlashCallAudioRouting.screenOffAgainstEar`).
7. **The shield claims only the pairing check** ("Verified": the device is in the paired list). It does not say "end-to-end".
8. **A latent race fixed on the way:** `FlashCallSession` wrote its UI state with plain read-copy-write from the media thread in several places
   (`applyVideoConcession`, connect, mute, camera, speaker); a concurrent status update could be lost (a test lost the peer's data-saver flag).
   All now go through `updateState`/`_state.update`.

### Context
The owner asked for the call features that messaging apps have, in three groups (small UI, medium, Flash-specific). Items that need a native
capability the project does not expose, or a device to be meaningful, are not built (below).

### Alternatives considered
- **One frame per feature** (`mute`, `hand`, `react`...). Rejected: four frames to version, order and test; the combined frame is idempotent.
- **Reactions as chat messages.** Rejected: they are ephemeral, belong to the call, and must not enter history or the outbox.
- **Data saver as a media-level `receive=false` on the receiver.** Not built: the webrtc-kmp surface exposes no receiver-side stop; stopping the
  sender saves the bandwidth, which is the point.
- **A stock Material bottom sheet for the panels.** Rejected by AGENTS section 34; the panels are custom (`FlashCallPanel`).
- **Show an "end-to-end verified" shield.** Rejected: what is verified is the pairing; the wording stays at that.

### Not built in this step (honest list)
Torch and tap-to-zoom (no camera-control API in `webrtc-kmp`), noise-suppression toggle and background blur (need a native audio / video
processing hook), promoting a 1:1 call to a group (a roster and signaling change, needs its own ADR), screen sharing (a capture source per
platform, mainly desktop), sending a file or message from inside the call (a navigation decision: the call overlay covers the chat), the mirror
toggle for **group** self tiles, and an LAN-versus-hotspot label (the app has no such datum; the chip says "Local network").

### Consequences
- A wire addition that older builds ignore; a build that does not know `status` shows no badges and receives no hand / reaction.
- Everything is unit-tested and none of it is device-verified: `CALLX-01`...`CALLX-12` in `docs/testing/TEST-BACKLOG.md` (section 4r).
- The link-quality thresholds (60 / 200 ms, 2 / 8 % loss) are a first guess for a LAN and are not measured.
- Changing the activity's `configChanges` means the Activity is no longer recreated on a size change; Compose handles the new size itself.

### Revisit when
`CALLX-*` are run; a measurement of RTT and loss on LAN and hotspot (to set the link grades); when `webrtc-kmp` exposes camera controls or audio
processing.

## ADR-068 - One whole-file integrity check for every host; a failed check fails the transfer on both ends

### Decision (2026-10-02, ERROR-098, audit finding FA-1)
1. **One implementation.** `WholeFileVerifier.check(path, expectedSha256Hex)` in `core:transfer` (common code) streams the assembled file through
   SHA-256 and returns `MATCH`, `MISMATCH` or `UNVERIFIABLE` (no path, no valid offered digest, unreadable file). The Android holder and the library
   facade had private copies; the Windows desktop had none.
2. **One completion path.** `RealFlashTransferRepository.onIncomingFileAssembled(transferId, path, expectedHex, chunksVerified)` is what every host calls
   when the last chunk is in. `MATCH`: Completed. `UNVERIFIABLE`: Completed and flagged, exactly as before (an absent digest must not fail a
   good file). `MISMATCH`: the file is deleted, the confirmed-chunk set is forgotten (memory and `TransferStore.clearDoneChunks`), and the transfer is
   **Failed** with a plain-language message.
3. **Why Failed, not "Completed, unverified".** Both UIs derive the "Verified" label from `state == Completed`, so a Completed transfer that failed its
   check would have been displayed as verified. The check was invisible to the user on Android as well.
4. **The sender is told.** On `MISMATCH` the host cancels its `ReceivePipeline` session and replies `COMPLETE verified=false`. A sender that receives
   `verified=false` marks its transfer Failed ("The other device reports the file arrived damaged...") and clears its confirmed chunks, so a retry
   resends the whole file. `verified=true`, or no flag, behaves as before, so older peers are unaffected (they always send `true`).
5. **Schema:** none. `TransferChunkDao.deleteChunks` is a new query on the existing table; `TransferStore.clearDoneChunks` has a no-op default so an
   existing adapter still compiles.

### Alternatives considered
- **Turn on `ReceivePipeline.recheckWholeFileDigest`.** Rejected: it needs a digest provider per transfer and only sets the `verified` flag, which both
  UIs ignore (point 3); the host still had to act on it.
- **Keep Completed + an `errorMessage`.** Rejected (point 3).
- **Keep the corrupt file for inspection.** Rejected: a retry would otherwise find a full-size file and a full done-set and finish "instantly".

### Consequences
- Hashing is blocking on the calling thread (as Android already did): a multi-GB file holds the receive-event collector for seconds. Not changed here;
  moving it to a worker is a follow-up if `FA-01` / measurement shows a stall.
- A mismatch on a retry loop is possible if the source file itself keeps changing; each attempt fails visibly instead of "verifying".
- The desktop has `store = null` (no resume across restart), so there the done-set lives in memory only and is cleared in memory.
- Not device-verified: `FA-01`.

### Revisit when
`FA-01` is run; or when FA-4 (persisting transfer identity) is done, which changes what a retry after a mismatch can do across a restart.

## ADR-069 - Refuse a receive that cannot fit, word failures for people, and refuse an empty file visibly

### Decision (2026-10-03, ERROR-099, audit findings FA-2, FA-3, FA-6)
1. **Free-space gate (FA-2).** `RealFlashTransferRepository` takes an optional `freeSpaceBytes: () -> Long?` (the host passes
   `File.usableSpace` of the receive directory, or null when the directory does not exist). `admitIncoming(transferId)` needs
   `bytesTotal - bytesDone` free (a resumed transfer already holds the part it wrote). When it does not fit, the row becomes **Failed** with
   "Not enough free space to receive this file. It needs X and only Y is available...", the host's pending session is dropped (local `ACTION_DECLINE`) and
   the sender's parked send is cancelled (`ACTION_CANCEL`). Android, desktop and the library facade call it as the first line of their `acceptOffer`, which
   both the user's Accept and auto-accept reach. A null or throwing probe admits: an unknown amount is never a reason to refuse.
2. **Typed wording (FA-3).** `TransferFailureText.friendly(rawReason, transferId)` maps the reasons the engine can produce (`source length mismatch`,
   `source read failed`, `all channels failed`, `data channel closed`, `peer disconnected`, `ack drain timeout`, `cancelled by peer`) to one sentence each; anything
   else becomes "The transfer stopped unexpectedly. Try again." The raw reason is logged under `TRANSFER` and never shown. Applied at the three
   failure sites (dispatcher result, caught exception, `onIncomingFailed`). No new field on `FlashTransfer`: the sentence is the `errorMessage`.
3. **Empty and unknown-size files (FA-6).** A size of 0 (or negative) from a picker can mean "empty" or "the provider did not say". `sendFile` now measures it by
   streaming the source once. Measured > 0: the send goes ahead with that size. Measured 0: a **Failed** row "This file is empty (0 bytes)...". Unreadable: a Failed row
   "The file could not be read...". The call still returns `FlashResult.Success`: callers (`MainActivity`, `DesktopShell`) read `getOrNull()` and ignore a Failure, so
   the visible row (Transfers list, chat bubble) is the message. Before this the chunker's `require(totalBytes > 0)` threw inside the send job and the row stayed Queued.
4. **Wire:** no change. `CANCEL` and `RESUME` already exist; the refusing receiver uses `CANCEL`.

### Alternatives considered
- **Refuse at offer time, before the user is asked.** Rejected: the user may free space and accept; refusing earlier would cancel the sender for them. The check is
  repeated at the moment of accept, which is when the space is needed.
- **A typed `FlashTransferFailure` enum on the model.** Deferred: no UI branches on the kind yet (a Retry button is not built). Adding it later is additive.
- **Return `FlashResult.Failure` for an empty file.** Rejected: every caller drops it silently, so the user would see nothing.
- **Pre-allocate the file to fail early.** Rejected here: different behaviour per filesystem and it hides the problem on resume.

### Consequences
- The sender of a refused offer sees its transfer cancelled by the other device, not the reason (the wire has no reason field). Adding one is a protocol
  change and was not done.
- `File.usableSpace` is an estimate: another app can fill the disk after the check, in which case the write still fails and now reads as a generic failure.
  Not checked against the official Android storage documentation or on a device (`FA-02`).
- Measuring an unknown-size file reads it once more: only when the picker gave no size, so the normal path is unchanged.
- A desktop receive directory the user has just changed is re-read on every accept (the provider is a lambda), so the check follows the setting.
- Not device-verified: `FA-02`, `FA-03`, `FA-06`.

### Revisit when
`FA-02`/`FA-03`/`FA-06` are run; when a Retry action or a failure-reason field on the wire is built (then promote the sentence to a typed reason).

## ADR-070 - The group swarm is an optional module, `:core:swarm`, that a host attaches

### Status
**PROPOSED 2026-10-04 (SW-0), awaiting the owner's acceptance of this text.** The owner chose a separate module on 2026-10-04 (plan O-6,
"it should be a different module"). Plan: `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` (section 2, phases SW-1 to SW-3
and SW-8).

**Implementation note (2026-10-19):** SW-0 to SW-11 are **built** and unit-tested (adopted in code despite this ADR's text still reading
PROPOSED), and the owner answered **O-8 on 2026-10-19: the swarm is on by default** (see Decision 7, below) — overriding this ADR's
"switched off until SW-12" gate. Not device-verified.

### Decision (2026-10-04, plan sections 2 and 5.1)
1. **A new Kotlin Multiplatform module `:core:swarm`** (Android + JVM, `explicitApi()`, package `com.transfer.flash.core.swarm`), published as
   its own artifact `core-swarm`. It holds the sans-IO engine, the FSW1 codec (ADR-071), the manifest, the driver and four ports
   (`SwarmTransport`, `SwarmGroupContext`, `PieceStorage`, `SwarmStateStore`; plan 5.2).
2. **Dependency rules (plan 2.5, enforced by a `LayeringTest` in SW-3):**
   - `:core:swarm` may depend on `:core:common`, `:core:transfer` (the `FlashTransfer` model and `Sha256`) and kotlinx-coroutines (driver only).
   - It must not depend on `:core:network`, `:core:messaging`, `:core:persistence`, `:core:engine`, `:core:calling`, `:core:ptt`, any `ui:*`
     module, or any platform API in `commonMain`.
   - No lower module (`common`, `network`, `transfer`, `messaging`, `persistence`, `discovery`, `security`) imports it.
3. **`:core:engine` depends on it with `api` and wires it in one place**, `SwarmHostBinding`, the way it carries `:core:ptt` (ADR-058). Nothing
   runs until the host calls `attachSwarm(config)`. `Flash.create` does not attach.
4. **The lower layers get two generic seams that never name the swarm (SW-2):**
   - a `caps` field in the WebSocket HELLO, exposed as `FlashDevice.features: Set<String>`. With an empty set the HELLO bytes are unchanged
     (golden test);
   - a `MagicFrameRouter` in `:core:engine` that routes binary frames by their 4-byte magic after decryption. `FSW1` is always recognised, and
     dropped when no handler is attached, so a stray frame never reaches the transfer pipeline.
5. **Wire gate.** A device puts `sw1` into `caps` only while the swarm is attached. No device sends an FSW1 frame to a peer whose HELLO lacked
   `sw1`.
6. **Fallback.** The shared group sender (`GroupFileSender`, SW-1) uses today's per-member push for every member without `sw1`, for everyone
   when the swarm is not attached, for legacy `g-` groups (O-4), for empty files (FA-6) and for files above the piece limit (ADR-071).
7. **Default.** ~~The Flash app keeps the swarm switched off until SW-12 shows `SWM-09`..`SWM-15` PASS on at least three devices,
   one of them Windows (O-8).~~ **CHANGED by the owner (2026-10-19):** the swarm is **on by default** in the Flash app now, before
   the O-8 device gate was met. The master switch — Android `FlashSettingsDataStore.groupSwarmEnabled`, desktop
   `~/.flash/settings.properties` `group_swarm_enabled`, the settings row "Group file sharing (swarm, experimental)", and the
   `FlashSettingsModel.swarmEnabled` UI default — is **true**. An explicit opt-out is stored and wins over the default, so a user
   who turns it off keeps it off; turning it off restores today's per-member push (plan R9). The device checks the gate asked for
   are still owed, and `SWM-46`/`SWM-47` now cover the default itself.

### Alternatives considered
- **Inside the transport (`:core:network`):** rejected. Membership lives in `:core:messaging`, which depends on the network, so this creates
  a cycle. It would also put a new, complex policy inside the device-verified transport that carries chat and calls, and a transport that
  owns sockets and real time cannot be simulated on a virtual clock (plan 2.1).
- **A package inside `:core:transfer`:** rejected. It would ship to every transfer consumer, it needs group context the 1:1 engine does not
  have, and it invites shortcuts into the device-verified 1:1 path (plan 2.2).
- **`compileOnly`, like calling (ADR-033):** not chosen. Calling needs it for WebRTC native libraries and permissions; the swarm is pure Kotlin
  with neither. `compileOnly` throws `NoClassDefFoundError` on any path that names a swarm type when the jar is missing, and R8 already strips an
  unused swarm from an app. It stays available if the owner wants consumers not to download the jar at all.
- **One HELLO field per capability** (as `ping` and `gv` are today): rejected for new modules. Every optional module would touch the transport
  again.
- **A third-party torrent engine:** anitorrent is GPL-3 and rejected; Ketch (Apache-2.0) is a reference only (design section 11, D13).

### Consequences
- 15 published modules instead of 14. `jitpack.yml` and the README change in SW-8, and the release dry-run is repeated.
- The HELLO gains one optional field, once. Later optional modules add tokens, not fields.
- A swarm bug can break only group files. Switching the swarm off restores today's behaviour (plan R9).
- **Since 2026-10-19 the swarm is on by default (owner decision O-8), so a swarm defect now reaches group file sharing on a fresh
  install.** The master switch remains the escape hatch; the default-on device checks are `SWM-46`/`SWM-47`.
- Not device-verified: nothing exists yet.

### Revisit when
The owner wants `compileOnly`; a Rust or other non-Kotlin core is decided (D7); a second consumer of the magic router or of `caps` appears.

## ADR-071 - Swarm wire FSW1, the content id and the signed announcement

### Status
**PROPOSED 2026-10-04 (SW-0), awaiting the owner's acceptance.** The byte layout is in `docs/protocol.md` "Group swarm wire FSW1 v1". The golden
vectors are written in SW-3, by hand, before the codec.

### Decision (2026-10-04, plan 5.3)
1. **Envelope:** magic `"FSW1"`, `u8 version = 1`, `u8 type`, `u16 flags` (0 in v1; receivers ignore unknown bits), `u32 bodyLength`, then the
   body. Little-endian, like framing v2. One FSW1 frame per WebSocket binary message, and `bodyLength` must match the rest of the message
   exactly.
2. **Carriage:** binary WebSocket frames through the same `SecureBinaryFrameCodec` layer as transfer chunks. The magic is checked after
   decryption. Between peers without a pairing session key the frames are protected by TLS only (as transfer chunks to vouched members are
   today; ADR-073 lists it as a limit). FSW1 v1 rides the WebSocket session only, never Android's TCP data channels, whose frames are capped
   at 512 KiB (`DataChannelFraming.MAX_FRAME_BYTES` [code, 2026-10-04]) and could not carry a 1 MiB piece.
3. **Twelve frame types:** `SUMMARY`, `MANIFEST_GET`, `MANIFEST_PART`, `HAVE`, `HAVE_ALL`, `REQUEST`, `PIECE`, `REJECT`, `CANCEL`, `CANCEL_ACK`,
   `SOURCE_STATUS`, `UNREQUEST`. **Every frame except `SUMMARY` names both its group id and its root**, so the gate is checked per group and a
   cross-group answer is never ambiguous (INV-3, INV-8). `SUMMARY` names its group once.
4. **Version rule:** an unknown `type` is ignored and not counted as malformed. An unknown envelope `version` is answered with
   `REJECT(UNSUPPORTED)` at most once per peer per 10 minutes.
5. **Limits** (anything over a limit is dropped and counted as malformed):
   - piece size: a power of two from 64 KiB to 1 MiB, and not larger than the largest binary frame the session carries (SW-3 reads it);
   - at most 16,384 pieces, so a file of at most 16 GiB is swarmed, and the manifest is at most 524,341 bytes in at most 9 fragments of 64 KiB;
   - at most 64 indexes in a `REQUEST`, `REJECT` or `UNREQUEST`;
   - at most one `HAVE` per second per peer per root;
   - group id at most 64 bytes, device and message ids at most 128 bytes, a signature at most 128 bytes;
   - three malformed FSW1 frames from one peer within a minute: ignore that peer's FSW1 frames for 10 minutes. **Never drop the session for
     this**, because it also carries chat and calls.
6. **The content id:** `root = SHA-256(manifest bytes)`, and the manifest bytes are `"FSWM" ‖ u8 version ‖ u32 pieceSize ‖ u64 totalSize ‖
   whole-file SHA-256 ‖ u32 pieceCount ‖ pieceCount × piece SHA-256`. The file name is not part of the root; the same bytes under another name
   are the same content. The origin picks the piece size with `PieceMath.choosePieceSize` (SW-3), the smallest allowed size that needs at most
   16,384 pieces.
7. **Not swarmed:** empty files (FA-6 keeps its visible Failed row through the push path) and files that would need more than 16,384 pieces.
8. **The announcement** is today's `FLASH_GMEDIA` frame with four optional fields: `root`, `pieceSize`, `swarm=1` and `rootSig`. `rootSig` is the
   origin's ECDSA P-256 / SHA-256 signature over the statement `flash-swarm-v1/announce` (group id, message id, origin id, root, size, file
   name, MIME type, sent-at), encoded with the v2 group canonical rules. The origin sends these fields only to members whose HELLO had `sw1`;
   every other member gets today's frame, unchanged, followed by today's push.
9. **Domain separation.** Every swarm statement has its own tag (`flash-swarm-v1/announce`, `/cancel`, `/source`), so no chat, charter or cert
   signature can verify as a swarm statement, nor the reverse (tested in SW-8).
10. **Old decoders (SW-0 task 4, answered from the code 2026-10-04).** `FlashTextFraming.parseFields` builds a key-to-value map and
    `GroupFrameCodec.decode` reads only the keys it names, so an older build ignores the four new fields. It would then wait for a FILE_START
    that the swarm never sends, which is why rule 8 sends the fields only to `sw1` peers.

### Alternatives considered
- **New types inside the `FLSH` chunk framing:** rejected. `ChunkFrame` is the device-verified 1:1 pipeline, and the plan forbids changing it.
- **Text frames for the control messages:** rejected. Pieces are binary anyway; one binary codec with one table of limits is simpler to fuzz.
- **kotlinx-serialization or protobuf:** rejected. A new dependency, and the project writes its binary codecs by hand (`ChunkFrame`, `PTT1`).
- **The file name inside the root:** rejected. It would make the same bytes under another name different content (design section 3).
- **A Merkle tree over the pieces** (as BitTorrent v2 uses): not chosen. A flat list is at most 512 KiB at the limit and simpler. A tree pays
  off for much larger files, where the list itself becomes a burden.
- **Root-only frames (no group id):** rejected. With two shared groups the gate would have to guess which group a request belongs to.
- **An unsigned announcement trusted through TLS:** rejected. Catch-up (ADR-059) relays announcements, and a cancel must verify against the
  origin even after it has left.

### Consequences
- One new binary magic, distinct from `FLSH`, `PTT1` and `FSEC`.
- The root depends on the piece size, so the same file announced by two origins is one content only when both chose the same piece size.
  `choosePieceSize` is deterministic, so this holds while both use the same `maxPiece`.
- Files above 16 GiB keep today's push.

### Revisit when
SW-3 finds the largest binary frame below 64 KiB (the floor changes); files above 16 GiB need the swarm; a second FSW version is needed.

## ADR-072 - Swarm reliability: the origin offer policy, wait reasons, cancel everywhere and retention

### Status
**PROPOSED 2026-10-04 (SW-0), awaiting the owner's acceptance.** The owner set the goal on 2026-10-04: "I want the swarm because I don't want
sending a file to fail", a half-sent file must finish among the members and continue when the sender returns, and the sender's cancel must
stop it everywhere. O-1 to O-4, O-7 to O-10 and D9 to D12 were accepted as recommended the same day.

### Decision (2026-10-04, plan sections 3, 4, 5.6 and 8.1)
1. **The contract is R1 to R9** (plan section 3) and the invariants INV-1 to INV-12 (plan 5.6). Each has a test.
2. **Rule A, every receiver:** pull the rarest pieces first with a random tie-break, and ask the origin only for pieces no other connected
   member holds.
3. **Rule B, the origin offer policy (mandatory, INV-7):** the origin serves pieces that no member holds and nobody is fetching before anything
   else, and answers a request for an already-copied piece `REJECT(ELSEWHERE)` while such work waits. Every byte the origin uploads is a byte
   the group did not have.
4. **Waiting is never failing.** Seven wait reasons (`WAITING_FOR_SENDER`, `_HOLDERS`, `_NETWORK`, `_SPACE`, `_STORAGE`, `_SYSTEM`, `_SESSION`),
   each moved on by named events, not by timers (INV-9). The row shows `Queued` plus a new additive field `FlashTransfer.waitReason`; no new
   `FlashTransferState` value.
5. **Cancel everywhere.** Only the origin's Cancel, or its Delete for everyone, creates a tombstone: group id, root, origin id, message id,
   reason (`USER` / `DELETED`), time, signed with `flash-swarm-v1/cancel`. A member applies it only if it verifies against the origin key
   stored with the announcement (INV-6). It is forwarded until `CANCEL_ACK`, carried in every `SUMMARY`, and applied before any request
   (INV-5).
   - **A tombstone cancels one announcement,** the one whose `(groupId, messageId)` it names. The content (group, root) stops being requested
     and served only when every announcement of it in that group is tombstoned. So a replayed old tombstone cannot cancel a later re-send of
     the same file, and one origin's cancel never touches another member's announcement of the same bytes. (This sharpens INV-5 and plan 5.4's
     tombstone key; the plan carries the note.)
   - A receiver's own Cancel is local.
   - **A system stop is never a cancel** (INV-10): Android's foreground-service timeout, battery saver and process death pause the row with
     `WAITING_FOR_SYSTEM`. SW-2 splits the user stop from the system stop, because `FlashBackgroundService.onTimeout` cancels transfers today.
6. **Finished copies after a cancel** stay with their receiver but are no longer served for that announcement (O-2).
7. **The origin loses its file:** a signed `SOURCE_STATUS(LOST)`; not a cancel. Members keep exchanging what exists. If the sender picks the
   same bytes again, the root matches and `RESTORED` resumes it (O-3). No staging copy is made.
8. **Retention (O-1):** an unfinished download waits up to 7 days for missing pieces, and a finished copy stays available to others for 7 days;
   tombstones are kept 14 days.
9. **The only terminal ends** are: complete; cancelled; retention expired; the source lost with no holder left (E-19); the device removed from
   the group (E-34); a whole-file mismatch that survives one re-fetch round (E-14, ADR-068 wording). There is no attempt cap.
10. **Membership.** Every request is checked against the group gate at that moment (ADR-075, INV-3). An origin that left or was removed no
    longer serves, but its cancel still counts (O-9).
11. **Shared resources.** In ECO a device downloads but does not serve (D9). During a call the swarm drops to one serve slot and halves its
    request windows. Downloading rows, and an origin that is the only holder of a needed piece, keep the foreground service; serving a finished
    file does not, unless the device setting "keep available" is on.

### Alternatives considered
- **Holders keep serving after the origin cancels** (design 4.8): rejected by the owner (D10 answered: cancel everywhere).
- **Deterministic striping** of pieces over receivers: rejected (design 4.4). It needs a global assignment and breaks under churn.
- **Rarest-first without the origin offer policy:** rejected. In the half-sent case the members would hold the same pieces twice (R3).
- **Timed retries with a cap:** rejected. They turn "the sender is offline" into a failure (R2).
- **Deleting finished copies on cancel:** rejected (O-2). A delivered file is the receiver's; Delete for everyone stays the way to remove the
  message.
- **A staging copy of the source:** rejected (O-3). It doubles storage.
- **Tombstones keyed by content alone:** rejected (rule 5). A replay would cancel later re-sends and other members' announcements.

### Consequences
- `FlashTransfer` gains one appended parameter: source-compatible, but already-compiled consumers must be rebuilt (minor version bump).
- Honest limits the UI must state: pieces that exist nowhere, frozen devices (Transsion), the Android 15+ six-hour `dataSync` limit
  ([developer.android.com/develop/background-work/services/fg-service-timeout](https://developer.android.com/develop/background-work/services/fg-service-timeout)),
  retention, hotspot client isolation, and that members who join later do not get earlier files.
- Whether 1:1 transfers should also pause on the service limit (O-7) is a separate, later change with its own ADR line.
- Not device-verified: `SWM-09`..`SWM-29` are owed.

### Revisit when
`SWM-*` device results arrive; the retention defaults prove wrong in use; O-7 is built.

## ADR-073 - Group membership by group id + secret (reverses ADR-044's rejection of a group-wide secret)

### Status
**PROPOSED 2026-10-04 (SW-0), awaiting the owner's acceptance of this text.** The owner reversed the rejection on 2026-10-03 and confirmed it on
2026-10-04 (D1 accepted, D2 admin approves, "anyone in the group should receive regardless of paired state"). Track GM of the plan delivers it.
No code before acceptance, and no ownership-transfer code at all (ERROR-089 is the owner's decision).

### Context
ADR-044 rejected a group-wide shared secret because it "authenticates someone in the group, not a device, so a removed member keeps access
until the secret rotates". The objection is correct. So the secret never becomes the identity: it is the key to the door, and the device
key stays the thing that is authenticated, listed and removed.

### Decision (2026-10-04, design 1.2 to 1.7 as changed by plan 1.3, 2.6, 5.7 and O-11 to O-15)
1. **The secret.** Each v2 group has a secret per epoch: 32 bytes from the platform's secure random, made by the app, never typed (D3). Epochs
   start at 1 and grow by one per rotation.
2. **It is used for three things only (O-13):** asking to join; rotation, which locks out a removed member and old invites; and the optional
   beacon (GM-8b, not built unless the owner asks).
3. **The device key is the identity (GINV-2).** The roster stays a set of admin-signed member certificates (ADR-044 V1/V2; admins are the owner
   and co-owners, ADR-063). Traffic between roster members needs no proof of the secret: the gate (ADR-075) compares the live TLS key with the
   key the certificate names. This replaces design 1.5 condition 1.
4. **The invite is a trust root (GINV-3).** `flash://g/1/<base64url>` carries the group id, epoch, secret, group name, the inviter's device id
   and key fingerprint, at most three address hints and the issue time (`docs/protocol.md` "Group membership v1"). Because the group id
   already commits to the owner key (`deriveGroupId`), `checkCharter` gets a second accepted root: an invite this device accepted for exactly
   that group id. Pairing with the owner stays a root. A bundle alone never makes a device accept a group it did not ask for.
5. **Joining:**
   1. The joiner accepts the invite and pre-installs the inviter's key as a vouch **scoped to that group**, so the first dial pins nothing by
      trust-on-first-use (ripple 37).
   2. The two devices run a three-message proof bound to both live TLS key fingerprints (HMAC-SHA-256 with
      `K_auth = HKDF-SHA256(secret, "flash-gsa-v1" ‖ group id ‖ epoch)`; GINV-7).
   3. The joiner sends a signed join request, which members forward to the admins.
   4. An admin decides. Approval is an ordinary admin-signed member certificate, which existing vouching (ADR-044 V2) introduces to every
      member, old builds included.
6. **Join policy** (a signed group setting, ADR-074): `APPROVE` by default (D2), or `OPEN`. `OPEN` never auto-approves a key the group has
   tombstoned (GINV-5). A full group (20) refuses.
7. **Who may share an invite:** every member by default (O-12); a setting can restrict it to admins. It is advisory: whoever holds the link can
   pass it on.
8. **Rotation on every removal, without sealing.** The admin that signs a removal signs a rotation notice in the same transaction (GINV-4):
   group id, new and previous epoch, a commitment `SHA-256("flash-gs-commit-v1" ‖ group id ‖ epoch ‖ secret)`, the removed ids, the reason, the
   admin and a random rotation id. The notice holds no secret. The new secret moves only as a `GsSecret` frame inside a live TLS session, from
   any member that holds it to a peer that passes `allows(CHAT)` at that moment and is not named as removed in any stored notice; the receiver
   checks it against the commitment. Two concurrent rotations: the smaller rotation id wins, and the admin whose rotation lost rotates again. A
   leave does not rotate by default; "Change group code" rotates on demand.
   - **Why not sealing (design 1.6):** every member holds the secret anyway, so sealing only hides it from members that already have it. Sealing
     to each member's identity key needs key agreement, and the Android identity key is `SIGN|VERIFY` only (`docs/security.md` section 2);
     whether a Keystore key can agree keys below API 31 was not verified (`PURPOSE_AGREE_KEY` is API 31+, Flash's minSdk is 24). Sealing would
     need a second, per-device agreement key.
9. **Existing v2 groups** get a secret when an admin device on the new build opens them (O-11, GM-7). Legacy `g-` groups never do (D5).
10. **History:** a new member gets the catch-up of today (O-15, ADR-059).
11. **At rest:** the secret is stored per group and epoch in the chat database, which on Android is SQLCipher with an AndroidKeyStore-wrapped
    key (`docs/security.md` section 1). The desktop's protection is checked in GM-2; if its database is not encrypted, the secret is wrapped with
    the key that protects the desktop identity. It is excluded from backups and never logged (GINV-1).
12. **The 1:1 world is untouched (GINV-8, D4):** a peer that is only a group member never becomes a 1:1 contact, a pairing, a push-to-talk
    recipient or a 1:1 caller.
13. **GINV-1 to GINV-8** (plan 5.7) are part of this decision.
14. **Membership privacy (refinement made in SW-0).** A device that is asked to prove a group it does not hold, or an epoch it cannot verify,
    answers with a random challenge and then a plain failure, so a stranger who knows a group id cannot learn who is in it. It says "stale" only
    after a valid proof at an older epoch it still holds, or to an active roster member. No device acts on an epoch number it was not shown a
    signed notice for.

### What it does not provide (say so in the UI and docs)
- No forward secrecy. A removed member keeps what it received, and a member that has not yet heard of a removal still sends to the removed
  device (ADR-044's limit stands).
- A leaked invite admits nobody on its own under `APPROVE`; under `OPEN` it admits whoever holds it until a removal and rotation.
- File chunks and FSW1 pieces between peers that are not paired travel under TLS only: there is no pairing session key, so no `FSEC`
  envelope, and no group session key is planned.
- A lying admin can approve a key it controls, as a lying owner can vouch one under ADR-044.

### Alternatives considered
- **Keep pairing with the owner as the only root (ADR-044):** rejected by the owner.
- **The secret as the identity** (anyone who proves it is a member): rejected. It is ADR-044's objection: no roster, no removal.
- **A per-session proof for all group traffic (design 1.5):** rejected (O-13). It stops no attacker that the roster check misses, and it adds a
  way for working chat and calls to go silent.
- **A sealed `RekeyBundle` per member (design 1.6):** rejected, rule 8.
- **TLS 1.3 external PSK:** not chosen. Support in JSSE on Android and the desktop JVM was not found.
- **A short typed code with a PAKE:** rejected (D3).
- **MLS (RFC 9420) or sender keys:** out of scope; far larger work.
- **A new `GROUP` pin source (design 1.2):** not chosen. The group-scoped vouch reuses `VouchRules` and its conflict rules with no network
  change (GM-4).

### Consequences
- `checkCharter` has two roots; the refusal test "no invite and an unpaired owner" is mandatory (GM-4).
- One schema step adds `group_secret`, `group_invite` and `group_join_request` (shared with SW-6 and FA-4).
- A new text-frame prefix `FLASH_GMEM` and the `gs1` token in `caps`. Old builds ignore both.
- `PinSource.VOUCHED` is documented as "or named by an invite this device accepted for that group".
- Not device-verified: `GSEC-*`, `GSET-*` and `GMB-01`..`GMB-14` are owed.

### Revisit when
The owner decides what happens when an owner is gone (ERROR-089, "D then A"); typed codes are wanted (then a PAKE); per-sender keys or forward
secrecy are wanted; the device checks fail.

## ADR-074 - Group settings: signed settings shared by the group, and preferences that never leave the device

### Status
**PROPOSED 2026-10-04 (SW-0), awaiting the owner's acceptance.** Design section 2.1; built in GM-9.

### Decision (2026-10-04)
1. **Two scopes, never mixed.**
2. **Signed settings** (`GroupSettings`):
   - fields: group id, `version` (grows by one per change), `joinPolicy` (`APPROVE` / `OPEN`), `inviteSharers` (`ALL` / `ADMINS`), `maxMembers`
     (at most 20), `swarmServing` (on / off), `membersMayAdd` (on / off), `opId`, signer and signature over `flash-gset-v1` (v2 group canonical
     rules);
   - only an admin (owner or co-owner, ADR-063) signs them;
   - the highest valid `version` wins; on a tie, the smaller `opId`;
   - they travel as extra keys in the roster bundle, which old builds ignore (ADR-071 rule 10).
3. **Defaults when a group has no settings object:** `APPROVE`, `ALL`, 20, serving on, `membersMayAdd` off. Until GM-9, the members sheet hides
   "Add members" from devices that are not admins (it fails silently for them today).
4. **The group name is not a setting in v1.** It is part of the owner-signed charter, and a known group's charter must equal the stored one
   (bundle rule 3 in `docs/protocol.md`), so renaming needs its own decision.
5. **Device-local preferences:** serve files to this group; serve only on Wi-Fi; a battery threshold; how long to keep a finished file
   available; auto-accept size; mute. They never appear in any frame.
6. **Who reads what:** the join flow reads `joinPolicy` and `inviteSharers`; the gate reads `swarmServing` and the local serve preference
   (ADR-075 rule 4); `addMembers` reads `membersMayAdd`.
7. **A setting only ever restricts.** No setting grants what the gate refuses.

### Alternatives considered
- **Local preferences only:** rejected. A join policy that each device holds differently cannot be enforced.
- **Last writer wins by timestamp:** rejected. Clocks differ between devices; `(version, opId)` is the membership log's rule already.
- **The settings inside the charter:** rejected. The charter cannot change (rule 4).
- **One signature per field:** rejected. More bytes and no gain.

### Consequences
- One more signed object per group. A forged or older update is dropped, and two admins who change settings at once converge.
- Not device-verified: `GSET-01`..`GSET-03` are owed.

### Revisit when
The owner wants more settings (history for new members after O-15, renaming, a member limit above 20 after FO-05).

## ADR-075 - The group gate: one predicate for all v2 group traffic, read for every frame

### Status
**PROPOSED 2026-10-04 (SW-0), awaiting the owner's acceptance.** Built in GM-5, the first code phase; SW-8 depends on it.

### Decision (2026-10-04, plan 5.7)
1. **`GroupGate`** in `:core:messaging` (package `com.transfer.flash.core.messaging.group`) with `allows(groupId, peerId, kind)`,
   `isMember(groupId, deviceId)` and `changes: Flow<String>`. The kinds are `ROSTER`, `CHAT`, `CALL`, `FILE_SEND`, `FILE_RECEIVE` and
   `FILE_SERVE`.
2. **The rule for a v2 group (`g2-`).** `allows` is true only when:
   1. this device's own roster row is active;
   2. the peer's roster row is active;
   3. the peer is paired with this device, **or** the key of the peer's live TLS session equals the key its roster certificate names. How the
      certificate came to exist does not matter: the owner after pairing, an admin after a join with the secret, or a vouch;
   4. for `FILE_SERVE` only: the group's `swarmServing` and the device's "serve files to this group" are both on (both default to on).
3. **`isMember`** is rules 1 and 2, plus "the roster names a key for the peer, or it is paired" (today's `isGroupCallMember`). It needs no live
   session, and decides whom to dial and invite.
4. **Legacy groups (`g-`)** keep today's rule: paired and active (D5).
5. **What the four predicates of today become:**

   | Today | Becomes | Behaviour change |
   |---|---|---|
   | `isActiveGroupMember` (text, receipts, reads, deletes, typing, sync) | `allows(CHAT)` | None |
   | `isGroupCallPeer` | `allows(CALL)` | None |
   | `isGroupCallMember` | `isMember` | None |
   | `isActiveTrustedMember` on a v2 group (`GroupMedia` receive, `beginGroupAttachment`) | `allows(FILE_RECEIVE)` / `allows(FILE_SEND)` | **Members not paired with the sender now receive and send files** |
   | the recipient filter in `sendGroupAttachment` | `isMember` | Same as above |
   | `onBundle` | not gated by `allows` | None: a bundle is believed for its signatures, not for who relayed it |
   | the swarm's `isPeerAllowed` (SW-8) | `allows(FILE_SERVE)` to serve, `allows(FILE_RECEIVE)` to fetch | New |

6. **Per frame, never per session (GINV-6).** `allows` is read for every inbound frame and request and for every send. No cached answer outlives
   a `changes` event. On `changes`, the call layer re-checks `allows(CALL)` for each live leg of that group and drops the legs that fail, which
   closes the "a call leg already established is not re-checked after a removal" limit (ripple 46).
7. **The 1:1 world is unchanged (GINV-8):** `isTrustedPeer` keeps gating 1:1 chat, 1:1 calls, push-to-talk and pairing.

### Alternatives considered
- **Keep four predicates:** rejected. They have already drifted, and one of them requires pairing.
- **A gate in the network layer:** rejected. The network does not know groups.
- **Decide once per session:** rejected. A removal must take effect on the next frame; ERROR-088 showed what a check made at session start
  misses.
- **A separate swarm gate:** rejected. Two sources of truth for one question.

### Consequences
- Chat and calls behave as before; GM-5 first pins today's behaviour in a table test over the four predicates.
- Files change: members that are not paired with the sender send and receive them through today's push, under TLS only (ADR-073). This
  lifts `docs/security.md` section 9's "vouched members get no files" when GM-5 lands.
- Call legs are now dropped when a member is removed mid-call (new behaviour, device check `GSEC-08`).
- Not device-verified: `GMB-*` and `GSEC-08` are owed.

### Revisit when
The owner decides on ownership loss (ERROR-089); a group kind other than `g-` and `g2-` appears; device checks fail.

## ADR-076 - Group secret rotation on member removal, manual code change, and voluntary leave behavior

### Status
**DECIDED 2026-10-05 (GM-6).** Built and verified in `:core:messaging`.

### Decision
1. **Removal = tombstone + rotation in one transaction (GINV-4):**
   - When an owner or admin removes a member, it creates an authorized tombstone certificate and generates a rotation notice (`GroupRotation`) with `reason = REMOVAL` and `removedIds` containing the removed member ID in a single atomic database operation.
   - The rotation notice is signed by the admin/owner over `"flash-grot-v1"` and carries SHA-256 commitment `GroupSecretCommit.ofHex(groupId, newEpoch, newSecret)`, `adminId`, and a random 16-byte hex `rotationId`.
   - On startup, `SignedGroups.recoverUnrotatedTombstones()` checks if any tombstone authored by the local device lacks a corresponding rotation notice signed by the local device; if so, it issues a rotation covering the unrotated removals.
2. **Secret Handover (GINV-1, GINV-4, SW-0):**
   - Group secrets NEVER leave the device unencrypted, and NEVER appear in bundles, notices, tombstones, or logs.
   - A member holding a rotation notice for `newEpoch` whose current secret epoch is older sends `GsSecretRequest(groupId, localDeviceId, newEpoch)`.
   - A responder validates `groupGate.allows(groupId, peerId, GroupTraffic.CHAT)` **at that exact moment**, and verifies the requesting peer is NOT in `removedIds` of any rotation notice it holds.
   - The responder replies with `GsSecret`. The receiver verifies `GroupSecretCommit.matchesHex(commit, groupId, epoch, secret)` before storing with `source = HANDOVER`.
3. **Concurrent Admin Rotations:**
   - If two admins rotate concurrently at the same `newEpoch`, the rotation notice with the smaller `rotationId` (lexicographically) wins.
   - The losing admin re-rotates at `newEpoch + 1`, listing its removals in `removedIds`, and broadcasts the new rotation notice via `GsStale` to active group members so all admins and members converge without any removals lost.
4. **"Change group code":**
   - An explicit admin/owner action with `reason = MANUAL` that rotates the secret and advances the epoch without listing any removed IDs.
   - Prior group invite codes and join links (bound to older epochs) are immediately invalidated, while existing roster members exchange the new secret via handover without interruption.
5. **Voluntary leave behavior (Plan §7B task 7, row 42):**
   - Voluntary leave does NOT rotate group secrets by default.
   - Rationale: Voluntary departure is not an adversarial expulsion; avoiding automatic secret rotation on every ordinary leave prevents cascade broadcast storms, minimizes mobile radio and battery wake-ups, and prevents unnecessary churn in active groups.
   - If an administrator desires forward secrecy after a member's departure or suspects compromise, the administrator can trigger "Change group code" or remove the member.

### Consequences
- Removed members cannot receive new group secrets or decrypt future group swarm transfers or join via old invites.
- Prospective joiners holding expired/stale invites receive `GroupProofResult.STALE` and no rotation notice (`GsStale` is never sent to non-members).
- All 7 test cases in `GroupRotationTest` are verified green.


## ADR-077 - Persistent on-device log, manual export, restart prompt for the swarm switch (no log server)

### Decision
Android keeps Flash's own log in rotating files (`files/logs/flash-0..4.log`, 1 MB each, bounded 5 MB) through
`RotatingFileLogSink`, installed first thing in `FlashApplication.onCreate` and forwarding to logcat as before. An uncaught
exception is written to the file before the previous handler runs. Settings > Diagnostics > "Export logs" shares the files as
one text file through the existing FileProvider. Changing the swarm switch offers "Restart now" (relaunch + process end).
Lines are redacted for secret assignments, invite links and 64+ character key-like runs before they are queued.

### Context
Owner request 2026-10-06: logs must survive without an `adb logcat` capture (logcat's buffer holds minutes), and the swarm
switch is read once at engine start so it needs a restart. Only `FlashLog` lines are captured: 14 app files still call
`android.util.Log` directly and stay logcat-only until moved to `FlashLog`.

### Alternatives considered
- Log server / automatic upload: rejected for now. It sends device ids, peer ids and timing off the LAN, needs consent UI, a
  server, retention and a threat model, in an app whose promise is "no cloud". Manual export (share to the owner's own desktop)
  covers the need. Revisit if the owner runs a fleet of testers.
- Passive "test evidence" lines mapped to TEST-BACKLOG ids: not built; the file log makes them cheap to add later
  (`TESTEVIDENCE id=SWM-.. result=..`), but each needs a defined pass condition first.
- Desktop: already writes `~/.flash/desktop.log` (overwritten per launch); not changed.

### Why
Bounded size, no caller ever blocks on storage (bounded queue, newest line dropped and counted), no new dependency.

### Revisit when
A tester cannot reproduce with an export, or a crash happens in a native library (an uncaught-handler line is not written for
native crashes).


## ADR-078 - Mid-call video upgrade for 1:1 calls, by renegotiation, offered only to peers that advertise `cv1`

### Decision
A live 1:1 call that has no camera of ours on it (a voice call, or a video call joined without a camera) can add one. The
host asks for CAMERA first; `upgradeToVideo()` opens a video-only capture, adds the track to the existing connection and
the video section is negotiated again by the CALLER: the caller offers directly, the callee sends `Status` with `vu=1`
(`videoUpgrade`) and the caller offers. A call turns into a video call on the side that did not add the camera when it
sees `vu=1` (caller) or an offer with an `m=video` section on a call that had none (callee); that side starts with its own
camera off and nothing is sent until the user turns it on. Offered only to a peer whose HELLO advertised `cv1`
(`FEATURE_VIDEO_UPGRADE`), read by `CallCoordinator.peerFeatures`. The button is `FlashCallUiState.canUpgradeToVideo`.
Group calls are not upgraded.

### Context
ERROR-105 item E. Before this, `video` was fixed at invite time, the only renegotiation was an ICE restart, and webrtc-kmp
has `addTrack` and `replaceTrack` but no `addTransceiver`.

### Alternatives considered
- Always send a receive-only video section in voice calls: changes what new builds send to old peers on every call and
  there is no `addTransceiver` to do it cleanly. Rejected.
- Either side offers: two offerers on one connection is glare; the call already avoids it for setup and ICE restart (ADR-025).
  Rejected; the callee asks instead.
- No capability token, offer to everyone: an older build would be sent a video section it never answers. Rejected.
- Group voice-to-video upgrade: ADR-049 rejected per-request renegotiation, the offerer tie-break (`localDeviceId > peerId`)
  forbids a lower-id member from upgrading, and the 8-participant cap multiplies the cost. Deferred; needs its own design.

### Consequences
- Android foreground service type: `camera` is claimed only while `state.video && !state.cameraOff`. A peer turning our voice
  call into a video call arrives with our camera off, so a background flip cannot request the camera type (which throws from
  the background on Android 14+ and would drop the microphone type with it). A local tap happens in the foreground.
- Speaker routing is kept as it is: a voice call that becomes video stays on the earpiece until the user changes it.
- Known risks, measured only partly: the m-line is added after DTLS under MaxBundle through `CallSdp.tuneLocal` /
  `tuneRemote` / `enforceVp8Only`. A native two-peer test on the desktop `webrtc-java` stack passes in both directions
  (`DesktopMediaStackSmokeTest`); the Android libwebrtc stack is untested (`VUP-01`). A `vu` arriving during an ICE restart
  is serialised by `signalMutex`; if its offer could not be delivered the caller keeps it owed and retries on `Connected` and on
  signaling restored, the callee repeats `vu=1` until it has answered an offer with a video section, and the caller ignores a
  repeated `vu` while its offer awaits an answer (`VUP-04`). A failed upgrade offer is logged and the call carries on.

### Revisit when
`VUP-01`..`04` are run, or a group upgrade is asked for.

## ADR-079 - Media failures: specific end reasons, an audio-only retry, and notices instead of silent degradation

### Decision
`startMedia` / `acquireMedia` return a reason, not a Boolean. `MIC_DENIED` and `MIC_UNAVAILABLE` join the end reasons
(`ERROR` stays for SDP and transport failures). A video call whose capture fails is retried once with the microphone only
(`acquireWithCameraFallback`): success joins audio-only with `cameraOff = true` and a `FlashCallNotice`
(`CAMERA_DENIED_AUDIO_ONLY` when the host asked for audio-only after a refused grant, `CAMERA_UNAVAILABLE_AUDIO_ONLY` when
the camera would not open); failure means the microphone is the problem. `accept(audioOnly)` carries the host's choice. A camera that stops or cannot flip
is `FlashCallUiState.cameraProblem` (`FAILED`, `SWITCH_FAILED`, `UPGRADE_FAILED`), shown as a call-screen banner (owner choice:
banner driven by a state field, not a toast). Tiles under data saver or Show fewer say so (`videoOffLabel`); no router change.

### Context
ERROR-105 items A to D. Owner decisions 2026-10-06: a refused camera answers audio-only automatically (not a prompt);
camera errors are a banner.

### Alternatives considered
- Map the exception from the first failure: a missing camera and a missing microphone fail in the same `getUserMedia` call,
  so the first exception cannot say which. The retry can. Kept.
- Ask the user "join without camera?": adds a tap to a ringing call and the answer is nearly always yes. Rejected by the owner.
- Toast for camera errors: gone before it is read and invisible on the desktop. Rejected by the owner.
- Change the third_party capturer to report errors: a fork change for an event libwebrtc only reports as a stop. Rejected;
  the track's end is the signal.

### Consequences
- A JVM unit test sees either `ERROR` or `MIC_UNAVAILABLE` for the same failed `getUserMedia` depending on class-loading
  order, so tests assert "ended with a media failure" (`assertMediaCouldNotStart`).
- Desktop cannot observe a capturer stop (no hook): only toggle and flip failures show there.

### Revisit when
`CALLMEDIA-01`..`05` are run.

## ADR-080 - Ink launch splash: handwritten name, cold start only, plays to the end, can be turned off

### Decision
The launch splash on Android and on the Windows desktop app is the "Ink" design (UI-056, `docs/ui/launch-splash.md`):
- **What it shows:** the pen writes "Flash" in Hershey Script 1-stroke with a slight wobble, then draws the bolt above
  it, fills it and sends out one ripple. It follows light/dark and the dynamic accent.
- **Drawing:** done in Compose in `:ui:theme`, from letter data compiled into the module. No new dependency and no asset.
- **When it plays:**
  - Only on a **cold start**, meaning the first time this process (Android) or this application (desktop) shows a
    window. `FlashLaunchSplashGate` decides once, and the first decision wins.
  - A warm return, a recreated Activity, a window reopened from the tray, or reopening the app while the foreground
    service kept the process alive never replays it.
- **How it ends:**
  - It always **plays to the end** (about 3.45 s), and taps cannot skip it.
  - After the intro it leaves as soon as the engine is ready or has failed to start, and no later than 6 s
    (ERROR-034 ceiling).
  - Under reduced motion the finished frame shows still for 0.8 s.
- **Turning it off:** Settings → "Launch animation" (default on; DataStore / `settings.properties` key
  `launch_animation`).
- **Skipped:** for launches that must not wait (answering a call, a PTT press), and on Android when the setting read
  takes longer than 1 s.
- **Android handoff:**
  - The `core-splashscreen` system splash is held while the gate is Pending.
  - When the splash plays, the system splash fades out over 200 ms into the identical Ink ground. Otherwise it is
    removed when the engine is ready, as before.
  - `flash_splash_bg` is white by default and dark under `values-night`.

### Context
- Owner requests of 2026-10-06:
  - First, a redesign: round 1 geometric demos were rejected.
  - Then "the font of spark in ink", used as the splash, working on desktop too, "only play on a cold start", "play to
    end", and "a setting to disable it".
- The old splash (`FlashSplashScreen` over `FlashBrandAnimation`) vanished the moment the engine was ready. On a fast
  phone that would cut a 2 s writing animation off mid-word.

### Alternatives considered
- **Leave as soon as the engine is ready:** the owner ruled it out ("play to end").
- **Tap to skip:** contradicts play-to-end, and a tap could reach the app hidden underneath. Rejected; input is
  swallowed. The setting is the way out.
- **Persist "already shown" across process deaths** (once per install or per day): "cold start" is a new process.
  Showing it once per process keeps that meaning simple and testable. Rejected.
- **Lottie / AnimatedVectorDrawable / video:** a dependency or a per-platform asset that cannot follow the dynamic
  accent and does not run on desktop Compose. Rejected.
- **An outline script font with a reveal mask:** single-line fonts already are the pen path. Rejected.
- **Make the system splash follow Flash's own theme setting:** Android draws it from resources before app code runs,
  so only the system night mode can choose it. Accepted as a limitation.

### Consequences
- Every cold start with the setting on takes at least about 3.5 s to reach the app.
- On a slow device the splash already covers boot (it was up to 6 s before too).
- Call-answer and PTT launches are not delayed.
- The Hershey font licence requires an acknowledgement to ship with the letter data. It is in `NOTICE`, the glyph
  file and the generated third-party notices (`config/aboutlibraries`). Conversion to the NTIS "xxx yyy:" format is
  forbidden; we do not use it.
- `FlashBrandAnimation` is no longer the splash; it stays for the transfers loading state.

### Revisit when
`SPLASH-01`..`08` are run (in particular `SPLASH-06` on the Belfone), or the owner asks for a skip gesture.


## ADR-081 - A catch-up message can carry the swarm offer of a file; the offer is stored on the chat row (schema v11)

### Decision
A signed (v2) group file message that was announced to the swarm keeps `swarmRoot`, `swarmPieceSize` and `swarmRootSig` on its `messages` row (schema v10 -> v11, nullable). A catch-up `SyncPush` carries them, with the file name, type and size, as `GroupWireFrame.SwarmOffer`. The receiver verifies the author's announcement signature before it trusts any field, stores the file row (keyed by the message id) and announces to the swarm. The chat layer signs the announcement with the message's own `sentAt`; the origin's registration signature is only a fallback.

### Context
Any member that holds a message may relay it in catch-up, so the offer cannot live only on the author. The signature has to cover the same `sentAt` the receiver sees.

### Alternatives considered
- Keep the offer in `swarm_content`: needs the signature added there and the swarm record would then depend on chat; the swarm content row can also be cleaned up while the message stays.
- Re-announce from the author when a member reconnects: fails when the author is offline, which is the case the swarm exists for.
- Unsigned offer: lets a relaying member swap the file under the author's name.

### Why
Smallest change that works with the existing relay model; old builds ignore the new keys and behave as before (an empty bubble).

### Revisit when
The 7-day swarm retention and the catch-up TTL disagree in practice, or a second kind of attachment needs the same relay.

## ADR-082 - Group join steps: proof single-flight, verified-settings-only for members-may-add

### Decision
1. One mutual proof per (peer, group) at a time, claimed under a lock before any suspending call; later callers wait for the one in flight. The invite flow retries a failed or timed-out proof 3 times (3, 6, 9 s) while the inviter is connected.
2. In `SignedGroups.onBundle`, `membersMayAdd` is taken only from settings that verify against the owner or a known admin, and member-issued certs are verified together with the issuer's own cert in the same bundle.
3. After Join the UI shows the invite status sentence; the chat opens only when the group is already joined.

### Context
ERROR-112 / 113 / 114. The proof race broke the "one in flight per peer/group" rule (ADR-070..075 plan, GM-3) and the unsigned-settings read let a member self-authorise adding other devices.

### Alternatives considered
- Retry only on reconnect: leaves the owner waiting until the next link flap.
- Add the issuer's cert to the stored roster before verifying it: stores unverified data.
- Keep opening the chat after Join: the row does not exist until the roster bundle arrives.

### Why
Smallest change that keeps the wire format unchanged (old builds interoperate) and closes the hole.

### Revisit when
A persistent pending-join row is built, or `pendingInviteHints` is persisted across restarts.

## ADR-083 - An invite that cannot complete is a visible state; the admin is told who is unpaired

### Decision
1. A proof answered `stale` marks the invite `STALE` (M-06, secret and vouch dropped, not retried); one rejected after the retries marks it `INVALID` (M-05, retried when the peer reconnects); a timeout changes nothing. The column stays a free string, no migration.
2. The join dialog says who invited when this device knows the inviter's name; it says nothing for a stranger.
3. A join request from a device the approver is not paired with is marked as such in the members sheet, and its self-chosen name is shown as that; a paired requester is shown under the approver's own name for it.

### Context
ERROR-115. Reading the path showed an out-of-date link failing silently, and an admin approving on a name the requester picks (anyone can call itself "Mom").

### Alternatives considered
- Treat `INVALID` as final: a transient send failure would then kill a good invite.
- Show a key fingerprint to the admin: the joiner has no screen to compare it against, so it would be noise.
- Put an expiry in the link now: changes the meaning of `issuedAtMs` and is the owner's call (kept as an open decision in the handoff).

### Revisit when
An expiry or single-use rule is chosen, or a place exists where a device shows its own key code.

## ADR-084 - Pinned messages are stored on the device, several per chat, and are never sent to anyone

### Decision
A pin is a row of table `message_pins (conversationId, messageId, pinnedAt)` (Room schema v12, migration `STEP_11_12`, a new table only). It is device-local: nothing is sent to the peer or the group, the banner shows the pins of this device, and the toast says "Pinned on this device". A chat may hold several pins, newest first; a tap on the banner walks them. Deleting a conversation deletes its pins. `RealFlashChatRepository` takes the DAO as an optional constructor parameter (null means pins are not stored), so the 36 test construction sites did not change.

### Context
The first implementation was a `remember` state in the conversation screen: lost on leaving the chat, one pin only (UI roadmap 3.4, ERROR-116).

### Alternatives considered
- Pins shared with the group (a wire frame, per-member state, conflict rules): a protocol decision the owner has not made; a local pin is useful on its own and can later be promoted.
- A column on `conversations`: the full-row conversation upsert once reset the read cursor, pin and mute (ERROR-087), so a column there is the riskier place.
- Keeping the pin in memory only: honest, but it is what the review rejected.

### Why this one
Smallest persistent design with no protocol change and no risk to existing rows.

### Revisit when
The owner wants pins visible to other members (then a signed group frame and a conflict rule are needed), or a pinned message must survive its own deletion.

## ADR-085 - A file caption is not built: it needs a field in the file offer

### Decision
Staged files and the composer text are sent as separate messages (files first, then the text). The composer hint says so. No caption field is added to the transfer offer or the swarm offer until the owner decides.

### Context
UI roadmap 3.2 said captions existed; they did not (ERROR-116). An attachment row is local-only; the peer's row is built from the transfer offer, so a caption has to travel in the offer (and in the group-media and swarm-offer frames).

### Alternatives considered
- Add an optional `caption` to the offer frames (forward compatible, older peers ignore it): the likely design, but it changes `docs/protocol.md` and three frame families.
- Send the text as a message and visually attach it to the file bubble: needs a link between two messages, which is also a schema change.

### Revisit when
The owner chooses a caption design.

## ADR-086 - A swarm offer needs no pairing between sender and receiver; a whole-file push still does

### Decision
In a v2 group the chat layer treats a swarm file offer like group text: any active member the device trusts in the group (paired, or vouched with a live session key that matches its certificate) may be announced to and may announce. A whole-file push stays paired-only (ADR-044 E3). The sender keeps the swarm offer on its own row even when nobody could be announced to as a swarm, so catch-up offers the file to members that connect later. A signed message keeps its signed `sentAt` when it is within 5 minutes of the receiver's clock.

### Context
ADR-075 already lets the swarm gate admit vouched members; the chat layer's paired-only check (ADR-044) contradicted it, so in a star topology only the owner's paired peers got files (ERROR-117).

### Alternatives considered
- Allow vouched members for every group file: the transfer layer refuses an unpaired sender's bytes, so the message would sit PENDING; rejected.
- Store the signed `sentAt` in a new column and keep the clamp for display: correct for every skew, but a schema step for a rare case; revisit if skew beyond 5 minutes shows up.

### Revisit when
FO-04 (group attachment fan-out to vouched members) is taken up, or a device log shows a signed message dated more than 5 minutes ahead.

## ADR-087 - Evidence probes and an export header make every log a test record

### Decision
Flash writes a small fixed vocabulary of structured `PROBE` lines (`FlashProbe`, `core:common`) at state changes in the session and group flows, and every Android log export starts with a `session.header` and a live `snapshot.*` block (build, device, clock offset, switches, each connected peer's features and paired state, each group's roles and member trust). The vocabulary is `docs/testing/PROBES.md`. A probe carries ids (shortened), counts, enums and reason codes only, never keys, secrets, invite links, file names or message text.

### Context
The owner tests late and by usage, and wants the logs to say which backlog tests passed. ERROR-117 could not be settled from four real logs: they lacked the peers' `sw1` features, who was paired with whom, the clock offset and the type of the dropped message.

### Alternatives considered
- Log more free text: not machine-readable, and it drifts per author.
- A separate evidence database in Room: stronger retention but a schema step and a new privacy surface; deferred, the rotating log (5 x 1 MB) is enough for the first reading.
- Probe on every frame/chunk: cost on the transfer path, and the volume would evict the lines that matter from the 5 MB log. Rejected: probes are state-change only.
- Auto-writing test results from the log: a probe proves an event happened, not that the behaviour was right; the analyzer (not built) may only propose results.

### Revisit when
A real device log has been read against the vocabulary (gaps will show), or the rotating log evicts probes before the owner exports it (then give probes their own longer-lived stream).

## ADR-088 - Group creation allows up to 20 devices; a host with no share sheet gets no Share button

### Decision
1. The create-group sheet lets the user choose up to 19 peers (20 devices with this one), `GroupPolicy.MAX_MEMBERS_V2`, instead of 5 (6 devices).
2. `FlashConversationScreen.onShareText` is nullable. A null value hides the invite sheet's Share button (desktop has no system share sheet); Copy link stays.

### Context
ADR-044 V2 raised signed groups to 20, but the creation sheet still used the legacy 6, forcing a small group followed by "Add members". The repository already decides: a group above 6 is created only when every invitee reports signed-group support on a live session, otherwise it fails with "Groups of more than 6 need every member on the latest Flash version" (or names the offline invitees, ERROR-095). A default `{}` for the share callback made desktop show a button that did nothing.

### Alternatives considered
- Keep 6 in the sheet and rely on "Add members": rejected, it is the workaround the review complained about and the repository already protects the legacy path.
- Query each peer's group protocol in the sheet to cap at 6 when one is old: rejected for now, the host would have to pass per-peer protocol levels; the failure message is specific. Revisit if owners report confusion.
- Implement a desktop share (clipboard toast): rejected, Copy link already does that.

### Revisit when
A device test (`GSUI-04`) shows the refusal message is not visible enough on Android or desktop when an old-build peer is selected, or when group size 32 (ADR-056, postponed) is taken up.

## ADR-089 - Swarm rows carry the file path and the sender's per-member view; an offer prepares itself while it waits for a person

### Decision
1. A swarm `FlashTransfer` row carries `localPath` (the finished file on a receiver, the source on the sender) and `sourceUri` (sender).
2. The origin reports, per member, how much of the file that member holds, how fast that is growing and whether it has all of it (`FlashTransfer.recipients`); the file bubble shows "N of M have it" and an expandable list.
3. An OFFERED receiver fetches the manifest and sends its Summary as soon as the offer arrives; nothing that moves file data happens before Accept.

### Context
Owner report 2026-10-07 (ERROR-119): received files would not open, the sender could not tell who had the file, and the start was slow. The engine already knew each member's holding from HAVE frames; the rows did not carry it.

### Alternatives considered
- Per-member rate from what the origin itself serves: rejected, it misses pieces a member gets from other members, which is the point of the swarm.
- Auto-accept in groups: rejected, consent stays (default off).
- Pushing pieces to the first member before it asks: rejected, the pull protocol already serves a first requester at once; the delay was the pre-request handshake.
- A new wire frame for progress reports: rejected, HAVE frames already say it.

### Why
No wire change and no consent bypass (the manifest holds hashes, no file data; the Summary says "I hold nothing"). The sender's view comes from the members' own announcements, so it is true whichever device the pieces came from. Granularity is bounded by HAVE batching (about 1 s or 4 pieces).

### Revisit when
Device logs (`swarm.first_byte`, `swarm.member.*`) show the start delay is still dominated by the app and not the person's tap, or the HAVE batching makes the sender's rate too coarse on a slow link.


## ADR-090 - A signed swarm offer is kept when swarm is off here, and a catch-up carries file offers for 7 days

### Decision
1. A verified swarm offer arriving by catch-up becomes a visible file row even if this device has swarm switched off; the row says file sharing is off and nothing is fetched.
2. Catch-up rows that carry a swarm offer live `GroupPolicy.SWARM_OFFER_SYNC_TTL_MS` (7 days, equal to the swarm retention); text keeps 24 h.

### Context
Owner question 2026-10-07 (ERROR-120): swarm-off members got a blank message; members offline over a day never heard of a file still fetchable.

### Alternatives considered
- Drop the offer silently when swarm is off: rejected, a blank or missing message hides that a file exists.
- Push the whole file to swarm-off members: only works for paired peers and is already what `GroupFileSender` does for them live; not extended to catch-up.
- Raise the TTL for all messages: rejected, more catch-up traffic and storage for text with no benefit.

### Why
No wire change; the offer is signed over the row's `sentAt`, so a longer window gives no replay beyond the swarm's own 7-day expiry.

### Revisit when
Device logs show catch-up cost from the longer window, or the owner wants "adopt a kept offer when swarm is turned on".


## ADR-091 - A holder answers the first Summary listing of a root, and a catch-up reads inside its window

### Decision
1. A device that holds pieces of a root sends its Summary back the first time a peer lists that root in a Summary during a session (tracked per peer in `summaryListedRoots`), instead of when no per-peer state exists.
2. A catch-up request reads history from the start of the widest window (7 days, or the cursor if newer), in pages, and applies each row's own window while reading.

### Context
ERROR-121: late offers waited up to 98 s for a reconnect, and a new member of a group with more than 100 older messages received no catch-up.

### Alternatives considered
- Receiver-side periodic re-sending of its Summary: rejected, does not help when the holder withholds its answer, and adds traffic.
- Assume the origin holds every piece from the offer: rejected for now, it changes who the engine trusts before any frame says so; revisit if device logs still show a wait.
- Raise the 100-row limit in the DAO: rejected, the limit protects slow phones; paging with a bound keeps it.

### Why
No wire change. The reply is one frame per root per session; ordering between the swarm channel and the chat channel is not controllable, so the receiver cannot be required to announce before a peer's Summary arrives.

### Revisit when
Device logs show `swarm.first_byte sinceAcceptMs` still above a few seconds on a stable LAN, or catch-up cost on very large groups.



## ADR-095 - An organisation's server holds a delegated, scoped, expiring key; the root key stays offline

### Status
ACCEPTED as a direction by the owner on 2026-10-08 (D-E8). Nothing is built. The open items below must be answered before code. Numbers 092 to 094 are reserved for the Linux port ADRs proposed in `docs/LINUX-PORT-PLAN.md`.

### Decision
1. An organisation has one **root key** (ECDSA P-256, offline, custody per D-E2). It signs **delegation certificates** and nothing else in daily use.
2. A delegation binds `(delegate public key, scope, validFrom/To, maxDevices, serial)`. Scopes are inclusive and narrowing only (for example `enrol:member@dept=X`, `revoke:member`, `admit:group=G`, `admin:group=G`). A delegate can never grant more than it holds and can never sign a delegation unless its scope says so.
3. The organisation's **server holds a delegation, never the root**. It may enrol and revoke members and sign group membership certs inside its scope. It may **not** create or revoke an admin, sign a delegation, or change the root. Every node checks the chain root -> delegation -> device cert and refuses a cert whose scope was exceeded.
4. Adding or revoking an **admin**, issuing a delegation, or rotating the root needs the root key, or **2 of N admin signatures** (threshold). Device certs are short lived (D-E9) and renew from the server or from any node holding a fresh renewal; a revoked device fails to renew.
5. Enrolment is by a **link or QR** `flash://enroll?server=<url>&token=<single-use>&root=<root fingerprint>`; the phone pins the root fingerprint from the link and proves possession of its own identity key. **Flash ships no default server, key, token or trust anchor** (the repository is public).
6. The server never gates local chat: devices keep working on the LAN from cached certs and roster when the server is down.

### Context
An offline root key alone makes the admin a single point of failure: nobody can join or be removed while the key holder is away, the same weakness as the v2 group owner (`docs/audit/2026-10-01-chat-edge-case-audit.md`). An organisation must remove a user at any hour. The owner chose option (a) of three on 2026-10-08. This **amends H7** of `docs/ENTERPRISE-HYBRID-PLAN.md`: the hub may issue limited trust, never admin-level trust.

### Alternatives considered
- Server holds nothing and only forwards: keeps the old plan, leaves the admin-offline problem.
- Server holds the root key: simplest, but a compromised server owns the whole fleet; rejected for a military deployment.
- Shared realm secret only (E1): cannot revoke one device.

### Why
A compromise of the server is bounded by the delegation's scope, quota and expiry, and the root revokes the delegation. Public source is acceptable because authority comes from certificates, not from secrecy of the code or the app (anyone can build a client).

### Consequences
- A stolen device that never meets another node stays usable locally until it does; short cert life bounds this at the cost of locking out a unit that is out of contact longer than the lifetime.
- Needs an independent cryptographic review of the certificate chain format before code, and a threat review of the enrolment endpoint (the only unauthenticated one: token, rate limit).
- Amends ADR-044 decision 4 only if D-E1 (`REALM` trust) is accepted separately.

### Revisit when
D-E9 (cert lifetime) is answered, or the first deployment shows the lock-out window is unacceptable.


## ADR-096 - The organisation directory, departments and a web-login dashboard live on the server

### Status
ACCEPTED as a direction by the owner on 2026-10-08 (D-E10..D-E13). Nothing is built.

### Decision
1. The server stores the organisation graph: **Organisation -> Departments -> Groups**, employees, and each group's admins. Roles (org admin, department admin, group admin, member) are scopes inside delegation certificates (ADR-095).
2. **Visibility is enforced by the server on every directory query.** A group is `listed` or `hidden`; join mode is `open within scope`, `request (a group admin approves)`, or `admin adds only`. A **hidden group is admin-add only and never listed** to anyone outside it (D-E11).
3. An invite sets the employee's **scope** (departments and groups they may see, optional auto-join groups, expiry). The link carries only the server address, a single-use token and the root fingerprint; **the group list is never in the link**.
4. The organisation admin creates departments; **department admins create groups inside theirs** (D-E13).
5. Joining a group yields a **membership cert signed by a group admin or by the server's scoped key** (`admit:group=G`). The **group secret is not stored on the server**; whether org-managed groups drop the secret in favour of the signed chain is for the group-membership ADR that follows this one.
6. The **dashboard has a web login** (D-E12): password (Argon2id) plus a required second factor (TOTP), HttpOnly SameSite session cookies, rate limit with lock-out, a separate listener that is LAN/VPN only by default, **no default credentials** (a one-time setup token printed on the server console creates the first admin, then dies). **Step-up:** creating or revoking an admin, issuing a delegation or changing the root requires a signature from the admin's own enrolled device; a stolen web session cannot do these. All admin actions go to a signed, append-only audit log.
7. Devices cache a signed roster slice and keep working when the server is down (E3).

### Context
The owner wants employees to discover groups and colleagues through the server, with the organisation deciding who sees what, and a dashboard to run it. The first draft used device-key sign-in only; the owner asked for a normal web login as part of the server plan.

### Alternatives considered
- Group list inside the invite link: leaks structure to anyone holding the link; rejected.
- Open directory to every enrolled device: leaks hidden groups and the organisation graph; rejected.
- Dashboard sign-in by device challenge only: strongest, but not what the owner asked for; kept as an optional QR convenience.
- Passwords without a second factor or step-up: a phished password would let an attacker mint admins; rejected.

### Why
The server operator is the organisation, so it may know the graph; message confidentiality is separate (sealed envelopes, E4). Server-side filtering plus short-lived, scoped certs bound the damage of a stolen account; step-up keeps admin creation off the web path.

### Consequences
- A compromised server leaks the directory and can enrol within its quota; it cannot create admins or read sealed messages.
- A large new surface (web app, sessions, MFA, audit log) needs its own threat review and dependency list (AGENTS.md section 3: every dependency documented with a reason).
- The dashboard is code in the public repository; its security cannot depend on source secrecy.

### Revisit when
Hidden-group lookups or the dashboard need delegation to non-technical admins, or a single sign-on provider is wanted.


## ADR-097 - A signed history ceiling bounds what a new member can catch up, and the new member chooses inside it

### Status
ACCEPTED as a direction by the owner on 2026-10-08 (`docs/group/GROUP-SYNC-REVAMP-PLAN.md`). Nothing is built; open items O1 to O4 of that plan remain.

### Decision
1. A signed group setting `historyCeiling` (GM-9 / ADR-074 mechanism) takes `NONE | H24 | D7 | D30 | ALL`, default **D30**, changeable by **any admin** (owner decision 2026-10-08). `NONE` means no earlier messages **and no files** (history includes files).
2. A new member sees a one-time card choosing how much to catch up, clamped to the ceiling: messages (none, 24 h, 7 days, 30 days, everything allowed) and files (none or the last 7 days, each file still needs Accept).
3. `SyncRequest` carries the chosen window; **the holder serves the smaller of the request and the ceiling** and applies its own retention. Holders enforce; a member who already holds data can always leak it, and `docs/security.md` must say so.
4. A **returning** member is guaranteed the full **7 days** it missed (instead of the fixed 24 h), up to the ceiling; whether to fill the whole gap up to the ceiling is O2.
5. Catch-up uses a **contiguous watermark** instead of the newest-row cursor, and is **paged and resumable** with progress.

### Context
Today a new member silently receives text of the last 24 h and swarm file offers of the last 7 days, with no choice and no admin limit (ADR-090, ADR-091, FO-10). Suspected defects S1 (the cursor can skip older missed rows), S2 (24 h text versus 7 day offers) and S3 (one round of at most 500 rows) are to be proven by unit tests before they are fixed.

### Alternatives considered
- Keep automatic sync, no prompt: rejected by the owner (FO-10).
- Per-member-only choice with no ceiling: rejected, an admin must be able to keep older content from newcomers.
- Raise the 24 h TTL for everyone: rejected earlier (ADR-090), more traffic for no benefit.

### Why
The ceiling gives the group a privacy rule, the card gives the person control, and the holder-side clamp keeps the rule in one place. The setting is an additive signed field, so an older build ignores it and behaves as today; a mixed fleet is safe but not equal.

### Consequences
- "Any admin" needs co-admins in plain groups (owner-loss path A, undecided); inside an organisation the admin comes from ADR-095/096.
- Files only reach back as far as the swarm retention (7 days) unless that is raised (O1).
- Holders must keep 30 days of text rows (O4); cleanup must not prune earlier than the ceiling.

### Revisit when
O1 to O4 are answered, or device logs show the paged catch-up cost on large groups.


## ADR-092 - Desktop identity vault: DPAPI on Windows, a key-file vault elsewhere until a keyring provider exists

### Status
ACCEPTED for the fallback tier (Linux plan phase L0, built and unit-tested 2026-10-08, not run on a Linux host). The Secret Service provider and the passphrase option remain PROPOSED in `docs/LINUX-PORT-PLAN.md` (L1, D-L2: which D-Bus library).

### Decision
1. `IdentityKeyVault.defaultForCurrentOs(stateDir)` returns Windows DPAPI on Windows and `IdentityKeyVault.forKeyFile(<stateDir>/identity/vault.key)` on every other OS. DPAPI is never touched off Windows.
2. The key-file vault seals with AES-256-GCM under a random 256-bit master key stored in its own file, created owner-only (`0600`) on a POSIX file system, written complete to a temp file and moved into place. Tampered or foreign blobs throw; a missing key file throws and is never recreated by `unprotect`.
3. The tier is stated plainly: **same-user code can read both files**, so this is stronger than a lone copy of the blob and weaker than a keyring or a passphrase. It is not described as secure storage anywhere.
4. `jdk.crypto.mscapi` is added to the jpackage runtime modules only when the build host is Windows.

### Context
First run on Linux crashed: `PersistedFlashCrypto.persist` called `vault.protect` and `Crypt32Util` could not load (Linux plan C4, audit `TASK-CORE-SEC-1`). Owner made Linux a committed target on 2026-10-08 (D-L1). `PassThrough` would have fixed the crash by writing the key unprotected, which AGENTS.md section 19 forbids as a default.

### Alternatives considered
- `PassThrough` off Windows: rejected, plaintext key at rest.
- Master key derived from `/etc/machine-id`: rejected (world-readable, Linux plan C7).
- Wait for the Secret Service: rejected, the app would stay unusable on Linux until L1.

### Why
It removes the crash with no new dependency (JCE only) and keeps the vault seam, so the Secret Service and a passphrase vault replace it later without a format change (`PersistedFlashCrypto` version byte unchanged).

### Revisit when
L1 lands the Secret Service or passphrase vault; then the key-file vault becomes the last-resort tier and a migration reads the old blob once.


### Update 2026-10-08 (Linux plan L1): the keyring tier is built; D-L2 answered (dbus-java)

**Status of this update:** ACCEPTED for the code, unit-tested on Windows with a fake Secret Service, not run on a Linux host (`LNX-02`).

1. `IdentityKeyVault.defaultForCurrentOs` is unchanged for callers. The desktop builds the Linux vault itself (`DesktopVaults.forCurrentOs`) because the D-Bus client lives in `:desktop`, not in `:core:security`: Windows gets DPAPI, Linux gets `IdentityKeyVault.withKeyring(<Secret Service>, <stateDir>/identity/vault.key)`, anything else gets the key file.
2. **Master key sources are tiered and tagged.** Every sealed blob is `tag(1) || nonce(12) || AES-256-GCM(ciphertext+tag)`. Tag `0x01` = owner-only key file, `0x02` = Secret Service keyring. `protect` uses the first source that works; `unprotect` opens the blob from the source its tag names, and never creates a key. So a machine that gains or loses a keyring still opens what it sealed, and a blob sealed for an absent source fails with a clear message instead of a bad-key error.
3. **Library (D-L2): dbus-java 5.2.2** (`com.github.hypfvieh:dbus-java-core` + `dbus-java-transport-native-unixsocket`), MIT, needs JDK 17+ (the app runs on 21), brings `slf4j-api` (already in the build). Alternatives: JNA to libsecret (the C library may be absent, and JNA cannot do the D-Bus protocol itself), `secret-tool` child process (it needs the tool installed and puts the secret on a pipe), a hand-written D-Bus client (too much to own). Its licence text is in `config/aboutlibraries/` so `generateThirdPartyNotices` stays green (ADR-043). It is used on Linux only and is not loaded on Windows.
4. **The session algorithm is `plain`.** The 32 key bytes cross the per-user session bus unencrypted. That bus is private to the login session, and anything that can read it can also ask the keyring directly, so the encrypted `dh-ietf1024-sha256-aes128-cbc-pkcs7` mode would add code, not protection against the same-user threat. Revisit if a threat review asks for it.
5. **Every keyring failure throws** (no session bus, no service, locked and the prompt dismissed or timed out after 60 s, wrong shape). The throw moves the vault to the key file, so a headless or minimal desktop still starts. The write is read back, so a keyring that silently keeps an old value cannot make the next start unable to find the key.
6. **Security tier, stated plainly:** the keyring keeps the key out of the data folder; code running as the same user in the same login session can still ask for it. The key file is obfuscation plus `0600`. Neither is described as hardware-backed storage. A passphrase (Argon2id) vault was not built (new dependency and a UI prompt) and stays an option.
7. **Paths:** `DesktopPaths` (Linux plan C8/L1) puts the state folder at `$XDG_DATA_HOME/flash` (default `~/.local/share/flash`) on Linux only, keeps a non-empty `~/.flash` when the XDG folder is empty, and sends received files to the XDG download folder + `/Flash`. Windows and macOS keep `~/.flash` and `~/FlashReceived`. Single folder; the config/data/cache split is deferred.

**Revisit when:** `LNX-02` shows the real Secret Service flow differs (GNOME Keyring, KDE/KWallet, KeePassXC), or a threat review wants the encrypted session or a passphrase tier.


## ADR-098 - Group-call video is sent at 540p (High) or 360p (Medium, Low); "Send smaller" is always 360p

### Date
2026-10-08

### Status
ACCEPTED by the owner's instruction 2026-10-08 ("move High to 540p, Medium and Low to 360p, the option that shows when the phone is struggling should change to 360p"). Built and unit-tested; not device-verified.

### Context
The owner's Windows laptop (i5-8350U, 4 cores / 8 threads, 15 W) used 240-365 % of one core in a group call with one person connected (`logs/experiments.md` EXP-021). Flash uses software VP8 on desktop (ADR-052). Until now every tier could send up to 720p (`GroupVideoLimits.of`: `tall = 720` unless on 2.4 GHz), the camera was opened at the tier's profile capped at 720p, and "Send smaller video in groups" (ADR-053) did nothing for a single watcher (1 watcher kept 720p; 2 gave 540p; 3 or more 360p).

### Decision
- `GroupVideoLimits.sendHeightFor(tier)`: HIGH 540p, MEDIUM 360p, LOW 360p. `GroupVideoLimits.of` uses it for both `quality` (what the device asks senders for) and `maxSendHeight`. 720p is no longer the default on any tier or band.
- The camera is opened at the tier's send height (`groupCaptureProfile`), so no larger picture is captured, converted and scaled for nothing. Frame rate and bitrate ceilings are unchanged (the per-copy ceiling already follows the height: 540p 900 kbps, 360p 450 kbps).
- A struggling device (hot or CPU-bound, `CallHealthMonitor`) asks every sender for 360p on every tier (was LOW only).
- "Send smaller video in groups" (and the CPU banner action, now labelled **Send 360p**) caps every copy at 360p whatever the number of watchers (`GroupVideoLimits.SMALLER_HEIGHT`). `heightForCopies` is removed.

### Not changed (on purpose)
- **1:1 calls** (`FlashCallSession`) still use the tier's `FlashVideoProfile` (HIGH = 1080p30, MEDIUM = 540p24, LOW = 360p15). The owner measured a *group* call; whether 1:1 should follow is an open question for the owner.
- Codecs (VP8 stays, ADR-052), the CPU threshold, and the LOW-tier capacities.

### Consequences
- Pictures from High devices are 540p, visibly softer than the old 720p on a large desktop window. That is the trade the owner chose.
- Expected CPU saving is an **estimate**: encode cost scales with pixels (540p is 56 % of 720p), but encode was only about 0.5-1 of 2.4 cores; the rest (capture, preview conversion, decode, audio, rendering) does not shrink. EXP-021 owes the after-measurement (`VID540-01`).
- Mixed calls: a MEDIUM device receiving from a HIGH one asks for 360p, so the HIGH sender sends it 360p (the lowest of ask and level).

### Revisit when
`VID540-01` shows the real saving; or hardware H.264 exists on desktop (ADR-052); or the owner wants 1:1 calls to follow.


## ADR-099 - The shared inbound router reaches host behaviour only through hooks; an inbound transfer id belongs to the peer that started it

### Decision
`FlashInboundRouter` (commonMain) owns frame routing and the `ReceivePipeline` event loop for both hosts, but every behaviour that differed between the two old host copies is a **hook the host supplies**, not a copy of one host:
- `onSessionStarted(frame, peer)` runs first for every `SessionStarted` (before the already-completed and resumable-retry branches); Android registers `incomingByPeer` there.
- `onResumableRetry(frame, peer)`: desktop passes `acceptOffer` (storage admission, progress seeding, RESUME); Android runs `admitIncoming`, then `onIncomingStarted` and RESUME (a small behaviour change: Android's old inline retry skipped the storage admission, now it is refused like a fresh offer); the router default is `admitIncoming` then `acceptSession`, and RESUME is sent only when `acceptSession` returned true.
- `onTransferUntracked(transferId)` runs in a `finally` on completion, cancel and refusal.
- `incomingOwners` (transferId to peer id): control frames, re-offers and chunks from a peer that does not own the id are ignored with a WARN; ids that change under `FlashPathSanitizer.sanitize`, or that differ only by case from a live id, are refused with a cancel.
- A flush or close failure on the received file is trusted only if the whole-file SHA-256 matches; otherwise the transfer fails and the file is deleted.
- The router wraps each routed frame and each `ReceiveEvent` in `try/catch(Throwable)` (cancellation rethrown) so one throwing hook cannot end a session's receive loops.
- `autoAcceptIncoming` applies to paired peers only (AGENTS.md section 19). `FlashConfig.displayName` is an in-memory override on desktop and is never persisted. `DefaultFlashEngine.detachSwarm()` closes an attached swarm that is `AutoCloseable`; Android returns a closeable view of the `SwarmHostBinding`.

### Context
The C7.0 refactor moved ~600 lines of inbound handling out of `Flash.kt` and `DesktopEngine.kt`. The audit (ERROR-125) found that moving it had silently picked one host's variant for each branch (desktop lost its FA-2 disk-space refusal on resumed receives; Android lost tracking of resumed transfers), and that nothing tested the event branches.

### Alternatives considered
- Keep two host copies of the event loop: rejected, it is how the copies drifted.
- Make the router call host methods through a large interface: rejected for now; lambdas keep the commonMain router free of Android and desktop types, and the unit tests use plain fakes.
- Bind sender-side ACK/COMPLETE frames to the peer as well: not done, it needs a signature change in `:core:transfer` (`onInboundFrame(bytes)`); tracked as a follow-up.

### Why this choice
Behaviour that must differ per host is now visible at the construction site, and `FlashInboundRouterTest` pins each branch with a real `ReceivePipeline`.

### Revisit when
A third host appears (iOS/Linux native), or when sender-side frames get peer binding, or when the lambdas grow past about a dozen and a typed host interface becomes clearer.

## ADR-102 - Screen share in calls: replace the camera track, one presenter, a share-specific ladder

### Decision
A desktop participant shares a screen or a window by replacing the video track on every video RtpSender (no renegotiation). Presenting is stated
as Status `ss=1` + `sst=<start value: max(now, highest seen + 1)>`; the presenter also states `cam=1` so older builds show the picture. One presenter at a time: the highest
(sst, id) wins and the loser stops itself; a deliberate take-over asks first. The share has its own ladder (1080/10, 720/8, 720/5, 540/5) and a
watcher cap by performance tier (HIGH 4, MEDIUM 3, LOW 2; 2.4 GHz split 2), because the mesh costs one software encode per watcher (ADR-052).
Receivers render fit-contain and make the presenter the router's main tile. Android watches only.

### Context
The owner lifted the deferral of ADR-056's list for screen share; the investigation (docs/calling/SCREEN-SHARE-INVESTIGATION.md) recommended
replace-not-add and a bounded watcher count.

### Alternatives considered
A second video track per leg (renegotiation, old builds); wall-clock take-over ordering (clock skew); first-presenter-wins (no take-over);
reuse of ADR-098's 540p/360p (text unreadable); a periodic silence watchdog (a static screen sends no frames); a seventh dock button.

### Why
Smallest wire change that old builds survive; cost control where the cost is (encodes, not bandwidth); the same code path for 1:1 and group.

### Revisit when
EXP-024 / SHARE-04 measure CPU and legibility (replace the ladder); the first Android presenter is built; a pending MediaProjection / Wayland result
differs from the assumptions in SCREEN-SHARE-DESIGN.md; the closed-window behaviour (SHARE-10) is known.

## ADR-101 - Bluetooth/radio link: layers, seam and session-layer recommendation (2026-10-09) - PROPOSED

### Decision
1. The radio path is `ByteLink` (serial COM port or RFCOMM) -> `KissTncDriver` -> AX.25 UI (PID F0) -> Flash radio frame (Profile M). It is a separate stack in `:core:network` (`...network.kiss`, `...network.radio`) that does not touch `PeerTransport`-style engine code (no such type exists) or `FlashTransportType`.
2. The radio frame has its own compact AEAD (12-byte header, counter nonce, per-direction HKDF keys from the pairing session key, 64-wide replay window, per-segment AEAD), not FSEC, because FSEC's random nonce and 22-byte header leave too little of a 220-byte information field.
3. For a future Flash-to-Flash session over Bluetooth RFCOMM (phone to phone, no radio): reuse the pairing-derived FSEC-style AEAD over a minimal length-prefixed framer (`StreamFramer`, u16 length), authenticated by the pinned Flash identity. Bluetooth OS pairing alone is NOT Flash trust: it is a link-layer convenience and an unknown peer must still pass Flash's own pairing/TOFU rules.
4. Android: paired devices only (no discovery), permissions BLUETOOTH_CONNECT (+ BLUETOOTH_SCAN neverForLocation for cancelDiscovery), SPP UUID for serial radios, a Flash-specific UUID (`5f1a5c3e-9b24-4d7e-8a61-0c2f4e7b9a13`) for Flash-to-Flash.
5. The test tool uses a public test key and is launched from Gradle only.

### Context
Owner reports the VR-N76 / UV-Pro class radio carries bytes over a Bluetooth virtual COM port. The plan `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md` had known errors (E1, E2, E3, E7, E9, E11) now corrected in `docs/network/RADIO-WIRE-FORMAT.md`.

### Alternatives considered
- Reuse FSEC for the radio frame (rejected: budget). Reuse `core:security` internals (rejected: internal visibility, shared module).
- TLS over RFCOMM for Flash-to-Flash (rejected for now: handshake cost and two security layers; the existing AEAD session design already authenticates by identity).
- A raw stream with no framing (rejected: RFCOMM has no message boundaries).
- Add `FlashTransportType.BLUETOOTH` immediately (deferred: touches shared files; do it with the planner hook once BT-03 has a measured need).
- Discovery-based pairing in-app / Companion Device Manager (deferred).

### Consequences
Radio frames and LAN sessions are separate trust paths until an adapter derives radio keys from the real pairing key. All radio numbers (airtime, goodput, RTT) are estimates until EXP-023.

### Revisit when
BT-00 shows the radio does not behave as assumed (BLE-only, no KISS, frame limit < 236 B); or when Flash-to-Flash Bluetooth is scheduled.

## ADR-100 - Group history is bounded by a signed ceiling; catch-up is paged and resumes from a contiguous watermark

### Decision
1. A v2 group has a signed setting `historyCeiling` (NONE, H24, D7, D30, ALL; default D30) changed by any admin (owner or an `admin` certificate; one seam, `GroupAdminPolicy`). Every holder enforces it whatever a requester asks.
2. A new member chooses its window on a join card (default 30 days, never above the ceiling; "no history" means no files). A returning member asks for `min(ceiling, max(7 days, time away))`. Files are offered for 7 days.
3. Catch-up requests carry an optional window and file flag; a holder answers in 100-row pages and closes each with a `page` marker. The requester keeps a contiguous watermark per (group, holder), advanced only when a whole page was seen, and resumes from it.
4. Wire is additive: optional request keys, a new `op=page`, `setHist` only when not D30. Non-default settings sign under `flash-gset-v2`; D30 keeps the v1 bytes.

### Context
Two defects: the request cursor was the newest local row (an older missed row was never asked for again), and every holder served a fixed 24 hours (a longer absence lost its middle).

### Alternatives considered
Raise swarm retention to 30 days for files (storage cost); always sign v2 (invalidates stored signatures); per-row watermark (reintroduces the gap on a lost frame); a modal dialog or silent default for the join card.

### Consequences
Old builds ignore non-default ceilings and the new request keys (mixed-fleet limit); the ceiling is a sharing rule enforced by honest holders, not a confidentiality control; schema 13.

### Revisit when
A co-admin or enterprise delegation model is designed (change `GroupAdminPolicy`), or device tests show the 100-row page or 3-attempt fallback needs tuning.

## ADR-105 - History ceiling signed separately; setSig stays v1 (PROPOSED)
Decision: `setSig` always covers `flash-gset-v1`. A non-default ceiling adds `setHist` and `setHistSig` (statement `flash-gsethc-v1` over group,
version, opId, signer, ceiling). Stored as `D7~<sig>` in the existing column. Tie: the copy with a non-default ceiling beats a stripped copy.
Alternatives: a `flash-gset-v2` signature (rejected: blinds every older build, never released); an unsigned ceiling (rejected: any member could lower it).
Revisit when: a settings v2 with a version negotiation exists. Limitation: an old-build admin edit resets the ceiling to D30.

## ADR-106 - One holder at a time for group catch-up (PROPOSED)
Decision: `CatchUpLane` per group; holders ordered by watermark `updatedAtMs` ascending; stall 45 s; episode 3 min; per-holder contact time.
Alternatives: ask all holders (rejected: N-fold duplicate pushes); split the window across holders (rejected: needs a wire key, G8 follow-up).
Revisit when: device numbers (HARD-03) show the stall or episode values are wrong.

## ADR-103 - Publish the vendored webrtc-kmp fork under Flash's own coordinates

### Date
2026-10-09

### Decision
`third_party/webrtc-kmp` (the included build of ADR-034) is published by the same JitPack build as the other fifteen modules,
as `com.transfer.flash:webrtc-kmp`, `webrtc-kmp-android` and `webrtc-kmp-jvm`, with the library version (`flashLibraryVersion`
in `gradle.properties`). Its publish task `:webrtc-kmp:webrtc-kmp:publishToMavenLocal` is the first task of the `jitpack.yml`
install line. The catalog entry `webrtc-kmp` is declared in `settings.gradle.kts` so it carries that version.

### Context
`core-calling` and `ui-callui` `api`-depend on the fork. Their published POMs named `com.shepeliev:webrtc-kmp-android:
0.125.11-flash-1`, a coordinate that exists in no repository (the included build is substituted only inside this repository), so
no consumer of the calling modules could resolve them.

### Alternatives considered
- Publish under Flash's own coordinates [chosen]: one build, one version, JitPack rewrites the group like every other module,
  local includeBuild substitution keeps working.
- Depend on upstream `com.shepeliev:webrtc-kmp`: it has no `jvm()` target, so desktop calling would be lost (ADR-034).
- Shade the fork into `core-calling`: bloats the artifact, breaks included-build substitution, hides the licence.
- Drop the calling modules from publication: removes a shipped feature.

### Consequences
Apache-2.0 requires the licence and a notice of changes: `LICENSE` is packed into the jar/AAR and `MODIFICATIONS.md` lists the
changes. The desktop native classifier stays the consumer's choice (documented in the README). The fork version moves with the
library version; bumping `flashLibraryVersion` bumps both.

### Revisit when
Upstream gains a jvm target (drop the fork) or the fork is split into its own repository.


## ADR-107 - `:core:hub-protocol`: the one definition of the enterprise-hub wire contract

### Date
2026-10-12

### Decision
A new Kotlin Multiplatform library `:core:hub-protocol` (package root `com.transfer.flash.core.hubprotocol`, Android + JVM, explicit API,
`@FlashInternalApi`) holds everything both ends of the hub link must agree on:

- `HubContract` (paths, headers, versions, timing, size limits) and `HubErrorCodes`;
- `certs/`: `DeviceCert`, `DelegationCert`, `RevocationList` (tag `flash-revocation-v2`), `AuthCanonicalWriter`, `HubAuth` (the three signing
  tags), `HubRoles` / `HubScopes` (closed role set, one wildcard rule), `RenewalPolicy`, `EnrollmentInvite`, and the `EcdsaVerifier` seam;
- `relay/`: the `RelayFrame` sealed model and `RelayFrameCodec` ("Hub relay wire v2", ADR-109);
- `rest/`: every REST DTO (`ErrorBody`, `AuthChallengeResponse`, `AuthVerifyRequest/Response`, `EnrollRequest/Response`, `RenewRequest`, the
  directory DTOs, `HubInfo`, `StoredEnrollment`) and `HubRestCodec`;
- `HubJson`: the two `Json` configurations (frames, REST).

It depends on `:core:common` and `kotlinx-serialization-json` only: no Ktor, no coroutines, no Room, and NOT `:core:security`. Signature
checks go through the `EcdsaVerifier` fun interface, which the server implements with `java.security` (`JvmEcdsaVerifier`) and the clients
with `FlashCrypto` (`FlashCryptoEcdsaVerifier`). `:server`, `:core:security`, `:core:network` and (through them) the hosts depend on it;
`:server` never depends on a client module at runtime (the contract test uses `:core:network` as `testImplementation` only).

The old copies are deleted, not wrapped: `server/.../auth/{DeviceCert,DelegationCert,RevocationList}.kt` and `AuthCanonicalWriter` in
`CryptoSupport.kt`; `core/security/.../enterprise/{AuthCanonicalWriter,DeviceCert,DelegationCert}.kt`;
`core/network/.../relay/{RelayFrame,RelayFrameCodec}.kt` and `enterprise/EnterpriseJsonHelper.kt`; the `:ui:chat` invite parser now calls
`EnrollmentInvite.parseOrNull`.

### Context
`docs/audit/2026-10-12-server-hub-audit.md` found the server and the clients each carried their own certificate types, canonical bytes,
frame parsers and JSON readers, and that they disagreed (CLI-03 field names, CLI-02 delegation slicing, SEC-18 scope wildcard and time rules,
SEC-14 payload, CLI-17 duplicated invite parser). Two implementations of a security contract drift; the audit's own review recommended one module.

### Alternatives considered
- Put the contract in `:core:network` (rejected): `:server` would depend on a client networking module that pulls discovery, TLS and sockets,
  and the server is a standalone JVM app that must stay headless.
- Put it in `:core:engine` or `:core:messaging` (rejected): the NET-1 / ENG-1 / MSG-2 refactors were already rejected (AGENTS.md section 29);
  the engine is the wrong direction for a server to depend on.
- Make `:core:security` own it (rejected): `:core:security` carries platform keystore code and an Android target; the server would inherit it,
  and the audit review asked that the contract not depend on it. Direction used: `:core:security` depends on `:core:hub-protocol` (it must,
  because `EnterpriseTrustStore` / `EnrollmentCredentials` hold `DeviceCert`), never the reverse.
- A separate `:core:enterprise` client module (recommended by the audit review) is NOT part of this ADR; it is later client work and does
  not change this module's shape.

### Consequences
`publishToMavenLocal` for `:core:hub-protocol` is the first library publish in `jitpack.yml` (before `:core:security`). Golden vectors pin the
canonical bytes (`CertGoldenVectorTest`), and one REAL P-256 signature is verified by three independent verifiers (shared module JVM test,
client `FlashCrypto` in `:core:security` `HubSignedVectorTest`, server `java.security` in `:server` `HubSignedVectorServerTest`).

### Revisit when
A non-JVM client (iOS, Rust) needs the contract: the module is already Kotlin Multiplatform common code; add a target, or generate the
schema from `docs/protocol.md`.

## ADR-108 - `kotlinx-serialization-json` 1.9.0 as the JSON implementation for the hub contract

### Date
2026-10-12

### Decision
`:core:hub-protocol` uses the Kotlin serialization compiler plugin and `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0`
(Apache-2.0, JetBrains). It is declared in `gradle/libs.versions.toml` (`kotlinxSerialization = "1.9.0"`) and is an `api` dependency of the
module only where a public signature exposes it. Every frame and DTO is `@Serializable` and is read or written only through `HubJson` /
`RelayFrameCodec` / `HubRestCodec`. `ignoreUnknownKeys = true` (forward compatibility), `encodeDefaults = true`, `explicitNulls = false`,
`isLenient = false`; relay frames use `coerceInputValues = true` so a newer server's unknown enum value does not drop a frame.

### Context
SEC-15 and REL-12: regex "JSON" in `AuthRoutes`, `RelayFrameRouter.parseRelayMessage`, `DirectoryRoutes` and `EnterpriseJsonHelper` took the
first match anywhere in the text (field confusion, payloads cut at the first escaped quote, certificate blocks cut at the first `}`).

### Alternatives considered
- Keep hand-rolled parsers (rejected: this is exactly SEC-15).
- Moshi or Gson (rejected: JVM-only, reflection or codegen per platform; the contract is common code).
- `ktor-serialization-kotlinx-json` (rejected for the shared module: it pulls Ktor into a module that must not have it; it still wraps this same library).
- Hand-written minimal strict parser in `:core:common` (rejected: more code to audit than adopting a maintained library; the project rule is
  "every dependency has a reason", and this one is the reason for deleting four regex parsers).

### Consequences
One new runtime dependency (about 600 KB on Android, no reflection, R8-safe through the generated serializers; `proguard-rules.pro` /
`consumer-rules.pro` are provided). The licence is in `NOTICE` and flows into `THIRD_PARTY_NOTICES.txt` from the shipped dependency graph.

### Revisit when
A schema-first approach (generated from a protobuf or JSON-schema file) is wanted for non-Kotlin clients.

## ADR-109 - "Hub relay wire v2": authenticated relay handshake, REST bearer, certificate renewal, enrolment proof of possession

### Date
2026-10-12

### Decision
1. **Relay handshake (replaces the unauthenticated query-string wire; SEC-01, SEC-02, SEC-14, CLI-10).** On accept the hub sends
   `FLASH_HUB_CHALLENGE {serverId, serverNonce, issuedAt}` (realm = upper-case hex SHA-256 of the organisation root key; nonce 32 random bytes,
   single use because it belongs to one socket). The client answers within 10 s with `FLASH_HUB_HELLO {deviceId, clientNonce, name, deviceCert,
   delegationChain, signature}`, where `signature` is ECDSA over `HubAuth.authSigningBytes(serverId, serverNonce, clientNonce, deviceId,
   certSerial)` (length-prefixed, domain tag `flash-hub-auth-v1`). The hub verifies the chain against the pinned root, revocation, the closed
   role set, the validity windows (skew at both ends) and the signature, then sends `FLASH_HUB_WELCOME {principal, serverTime, limits}`;
   otherwise `FLASH_ERROR {code, message}` with a fixed sentence and a close. Both sides time out after `HANDSHAKE_TIMEOUT_MS` = 10 s. The
   client refuses to answer a challenge whose `serverId` is not its pinned realm. No identity travels in the URL.
2. **Sender stamping.** `FLASH_RELAY` has no sender field; the hub stamps `FLASH_DELIVER.from` and `FLASH_META.from` from the authenticated
   principal. A `from` or `sender` member in a client frame is not read.
3. **Receipts.** `FLASH_RECV_ACK {msgId}` is the client's statement that it durably stored a message; it is the only thing that may delete a
   mailbox row (REL-02). `FLASH_ACK` carries `AckStatus` (`DELIVERED`, `QUEUED`, `OFFLINE_UNDELIVERABLE`, `DUPLICATE_QUEUED`, `QUOTA_EXCEEDED`,
   `REJECTED`, `ERROR`) with `to` = the recipient the send was addressed to. `FLASH_QUEUE_STATE` reports a later state of a queued message.
   `FLASH_REVOKED` and `FLASH_KICK` end a connection with a reason.
4. **REST bearer.** `GET|POST /api/v1/auth/challenge` returns `{serverId, serverNonce, issuedAt, ttlSeconds}`; `POST /api/v1/auth/verify` takes the
   same signed form as HELLO and returns a 15 minute opaque bearer token (32 random bytes, only its SHA-256 is kept by the hub) with the
   principal. Directory routes resolve the caller from `Authorization: Bearer`; the REST nonce store is separate from the relay's.
5. **Renewal.** `POST /api/v1/enroll/renew` (`RenewRequest`, tag `flash-hub-renew-v1`) is open from 24 h before `validTo` to 72 h after, and
   issues a certificate with a NEW serial and a window clamped to the delegation (SEC-18). The contract (path, DTO, signing bytes,
   `RenewalPolicy`) exists in `:core:hub-protocol`; the hub route and the client caller are later phases.
6. **Enrolment proof of possession (SEC-07 part).** `EnrollRequest.proof` is the device's signature over
   `HubAuth.enrollProofBytes(token, deviceId, subjectPublicKey, serverId)` (tag `flash-hub-enroll-v1`). The hub verifies it before consuming the
   token or a quota slot, so a token holder cannot certify a key it does not hold.
7. **Errors and limits.** Every REST error is `ErrorBody {code, message, correlationId}`; `code` is from the closed `HubErrorCodes` set and
   `message` a fixed sentence (never exception text, SEC-16). Relay frames are limited to 256 KiB (16 KiB before WELCOME), payloads to 64 KiB,
   REST bodies to 64 KiB.

### Context
The audit (SEC-01, SEC-02, SEC-14, SEC-15, CLI-10) found `/flash-relay` accepted any `deviceId` from the query string, signed bytes had no
domain tag, no audience and no length prefix, and `/auth/verify` returned a status but no credential.

### Alternatives considered
- mTLS between device and hub (rejected for now: TLS termination is not implemented on the hub, SEC-06, and the device keys are signing keys
  of a pinned certificate model, not TLS leaf keys; a challenge in the application layer works behind any proxy).
- A signed JWT bearer (rejected: needs a verification key distribution and clock trust; an opaque server-side token can be revoked by deleting it).
- Keep identity in the URL and sign the URL (rejected: replayable and logged by proxies).
- Authenticate only after the first frame (rejected: the first frame must already be bounded; the explicit challenge fixes the audience and the nonce).

### Consequences
v1 clients cannot connect to a v2 hub and vice versa: this is a breaking wire change. The hub is not yet deployed to the field, so no
compatibility shim was written. `docs/protocol.md` "Hub relay wire v2" is the specification; `RelayHandshakeContractTest` runs the real client
transport against the real server handshake.

### Revisit when
TLS termination (SEC-06) lands and channel binding of the signature to the TLS session becomes possible, or when the `resumeToken` field of
WELCOME is used for fast resume.

## ADR-110 - Revocation model: signed monotonic list with certificate, device and delegation entries

### Date
2026-10-12

### Decision
`RevocationList` (tag `flash-revocation-v2`) is signed and monotonic (`version` strictly increases). It lists revoked device-certificate
serials, revoked device ids (revokes every certificate that id ever held) and, new in v2, revoked delegation serials
(`revokedDelegationSerials`). A delegation serial on the list invalidates every device certificate issued under it, which is how a
compromised hub signing key is cut off without re-enrolling the organisation root. Rules:

- `DeviceCert.checkChain` consults the list after the signature/issuer/scope checks and before the time checks, so a revoked certificate
  reports `REVOKED` even when it is also expired.
- A list is acceptable only through `RevocationList.verifyTrusted`: signed by the ROOT (may carry anything), or by a delegate that holds the
  `revoke:member` scope and whose own delegation is valid (such a list may revoke devices but never delegations: a server may not revoke the
  authority above itself).
- `ChallengeResponseAuth` takes an ALREADY trusted list from a provider; installing, signing and persisting the list is the hub's job.
- `RelayFrameRouter` accepts a `revocationListProvider`: a connected device on the list gets `FLASH_REVOKED` and is closed on its NEXT frame (built and unit-tested). Nothing builds, signs or supplies a list in the running hub yet, and there is no active kick without a frame (Phase 2, SEC-05).

The v1 tag (`flash-revocation-v1`, no delegation set) was never produced by any shipped hub (SEC-05: the hub had no list at all), so no v1
list exists in the field and v1 is not verified.

### Context
SEC-05: revocation was never enforced (no list built, signed, stored or served; the relay compared a hard-coded serial of -1; no model for
revoking the server key itself). SEC-18 asked for one consistent time/role/scope rule on both sides.

### Alternatives considered
- Online revocation only (a database flag the hub checks): rejected, an offline organisation root and the clients' need to verify peers
  without the hub argue for a signed artifact that can be distributed over any channel.
- Certificate short lifetimes only (7 days, renewal required): kept as a complement (D-E9), not a replacement: a stolen device must be
  cut off within minutes, not days.
- Revoking a delegation by letting it expire: rejected, a compromised hub key must stop working immediately.

### Consequences
Phase 2 owns the hub side: build, root-sign, persist and serve the list (`GET /api/v1/revocations`), active kick of connected sessions,
and a revoke action in the admin UI (SEC-05, CLI-07). The client side (fetch, verify with `verifyTrusted`, honour on peer checks) is a client
phase.

### Revisit when
The list grows large enough that a delta format is needed (it is a set of numbers and ids; fine to a few thousand entries).


## ADR-112 - Hub relay: acknowledgement-driven at-least-once delivery, a bounded mailbox, server-side group fan-out, observability (2026-10-12)

Status: ACCEPTED in code 2026-10-12 (phase 2 of the hub fix effort, SERVER-B), not device-verified (`HUBB-01`..`HUBB-12`,
`docs/testing/TEST-BACKLOG.md`). Audit rows: `docs/audit/2026-10-12-server-hub-audit.md` REL-01..REL-15, SEC-02, SEC-16.
(ADR-111 is the schema migrator and identity store of the same phase, written by SERVER-A.)

### Decision
1. **Delivery is at-least-once and driven by the recipient.** A message leaves the hub only when the recipient sends `FLASH_RECV_ACK`
   after it has stored the message. Writing to a socket proves nothing (half-open sockets, Transsion/Hiber freezes), so:
   - a message handed to a live session is entered in that session's **in-flight table**; if the session ends before the RECV_ACK, the entries
     are spilled into the mailbox (REL-02);
   - a message for an offline or draining device is stored in the mailbox and **stays there until acknowledged**; on every connect the whole
     mailbox is replayed (`replay=true`), in arrival order (`seq`), in pages of `RelayPolicy.drainPageSize` (100), with a per-connection cursor;
   - the sender hears `FLASH_ACK` (`DELIVERED` = handed to the recipient's bounded queue, `QUEUED`, `DUPLICATE_QUEUED`, `QUOTA_EXCEEDED`,
     `REJECTED`, `OFFLINE_UNDELIVERABLE`) and later `FLASH_QUEUE_STATE` (`DELIVERED` on the recipient's RECV_ACK, `EXPIRED`, `DROPPED`).
   **Clients must deduplicate by `msgId` and send RECV_ACK only after a durable store.** Exactly-once is not claimed.
2. **Ordering.** A session is `draining` from connect until its mailbox is empty. While it drains, live frames go through the mailbox. The
   check "caught up?" and the live hand-off are one atomic step (`RelaySession.handOffLive` / `finishDrain`), so a live frame cannot overtake
   a stored row and a message arriving during the last page cannot be lost (no lost wake-up).
3. **The mailbox is a table with limits, not a queue object** (`mailbox_envelopes`, schema component `mailbox` v2, REL-03/08/09): `seq` =
   arrival order, `UNIQUE(origin, target, msg_id)` with `ON CONFLICT DO NOTHING` (concurrent duplicates become `DUPLICATE_QUEUED`), quotas checked
   and the row inserted in one `BEGIN IMMEDIATE` transaction (per recipient rows and bytes, per sender rows and bytes, global rows and bytes,
   payload cap, `maxMessages`), the recipient must exist in the `DeviceRegistry` (else `ERR_UNKNOWN_RECIPIENT`), the **server** clock stamps
   `created_at`/`expires_at` (client `sentAt` is clamped to +-`maxClockSkewMs` and never decides expiry), TTL from `ServerConfig.mailboxTtlDays`,
   `idx_mailbox_expires` for the sweeper, `auto_vacuum=INCREMENTAL`, JDBC on `Dispatchers.IO`. Payloads are opaque UTF-8 text, stored as BLOB.
   `SealedEnvelope.counter` / `authTagOrSignature` are RESERVED: the hub neither stores nor checks them (replay defence is the recipient's msgId
   dedupe; the AEAD tag lives inside the opaque payload).
4. **The relay is hardened per connection and per frame** (REL-04/05/06, SEC-02): handshake deadline, frame and payload caps, a global and a
   per-IP connection cap (counted before the handshake), a token bucket per session, a bounded outbound queue with one writer coroutine per
   session (a slow consumer is closed, never waited for), an in-flight cap, heartbeat (`ping`/pong timeout), identity-safe unregister (the old
   connection's cleanup can no longer remove its replacement, REL-01), the sender is **always the authenticated device** (client-sent
   ACK/DELIVER/QUEUE_STATE/KICK/REVOKED are `ERR_PROTOCOL`), revocation is checked at the handshake (serial AND deviceId) and on every frame, and
   `kick(deviceId, reason)` closes a live connection when `RevocationService.onRevoked` fires.
5. **Group messages fan out on the server** (REL-14): `FLASH_RELAY.group` + `to` blank; the hub resolves members through `GroupMembershipView`
   (directory), sends to every member except the sender, answers one aggregate `FLASH_ACK` (`to` = group id, `delivered/queued/failed`). A
   non-member and a nonexistent group get the same `REJECTED / ERR_NOT_MEMBER` answer (no group-existence oracle).
6. **Operations:** `/health` is public and minimal (`{"status":"ok|degraded|down"}`, 503 when the database probe fails); `/health/detail` and
   `/metrics` (Prometheus text) sit behind `ManagementGuard` (bearer token, or loopback without proxy headers). Errors answer a fixed sentence plus
   a correlation id; the real cause goes to the log only. Shutdown is graceful and idempotent: new connections refused, every session gets
   `FLASH_KICK server_shutdown`, in-flight messages spill to the mailbox, the sweeper stops, the database is checkpointed and closed; a failed
   bind cleans up. The TTL sweeper counts and logs failures, vacuums incrementally and tells an online sender `QUEUE_STATE EXPIRED`.

### Alternatives considered
- **Mark delivered when the frame is written** (the old behaviour): loses a message whenever the socket was already dead. Rejected.
- **Exactly-once with a server-side per-recipient cursor acknowledged by sequence number:** needs per-device state clients do not have and a new
  wire field; the msgId dedupe the clients already need for retries is enough. Revisit if clients cannot dedupe cheaply.
- **Group fan-out on the client** (sender sends N relay frames): N times the uplink, and the hub cannot enforce membership. Rejected.
- **Redis/Kafka-style queue:** an extra moving part for a single-node hub; SQLite WAL on one file is enough at the target scale (hundreds of
  devices). Revisit with the measurements `HUBB-01`..`HUBB-04`.
- **Hub-side per-message encryption / AEAD verification:** the hub is meant to be blind to content; the tag stays inside the payload.

### Consequences
- Wire additions are backward compatible by defaults (`group` null, `Ack` counts 0): see `docs/protocol.md` "Hub relay delivery semantics".
- A reconnecting device sees redeliveries (`replay=true`) until it acknowledges; clients that do not dedupe will show duplicates.
- `DeviceRegistry.AcceptAll` is a test default: production wiring MUST pass a registry (`DatabaseDeviceRegistry` or the enrolment registry).
- Residual risk, OPEN: Ktor accumulates fragmented WebSocket messages before `maxFrameSize` applies; a malicious client can still make the hub
  buffer one oversized fragmented message (bounded by the engine's limit, to be measured). Behind a reverse proxy the per-IP cap sees the proxy
  address unless `RelayPolicy.trustForwardedFor` is set and the proxy is trusted.

### Revisit when
Measured queue depth, drain latency or database contention (`HUBB-01`..`HUBB-05`) show SQLite as the bottleneck, or clients need exactly-once.


## ADR-120 - The device trusts the hub only through the pinned organisation key: enrolment, session, status and renewal (2026-10-13)

Status: ACCEPTED in code 2026-10-13 (phase 2 of the hub fix effort, CLIENT agent), not device-verified (`HUBC-01`..`HUBC-08`,
`docs/testing/TEST-BACKLOG.md`). Audit rows: `docs/audit/2026-10-12-server-hub-audit.md` CLI-01, CLI-07, CLI-08, CLI-09, CLI-12, CLI-13.
Wire contract: ADR-107 / ADR-109 / ADR-110 (shared with the server).

### Decision
1. **Trust is decided before anything is stored (CLI-01).** `EnterpriseEnrollmentClient.enroll` refuses an invite without a root pin
   (`INVITE_NOT_PINNED`), refuses plain `http`/`ws` unless the host passes the debug-only cleartext switch (`HubUrlPolicy`), and refuses a response whose
   root key does not match the invite's pin (`WRONG_ORGANISATION`), whose certificate is for another device or key (`WRONG_DEVICE`), or whose chain does
   not verify with the shared rules of `:core:hub-protocol` (`CERTIFICATE_INVALID`). Credentials are written in **one atomic save**
   (`EnterpriseTrustStore.saveCredentials`: a single properties file replaced by move on the desktop, one `commit()` on Android). A failed save stores
   nothing (`STORAGE_FAILED`). The old "trust on first use of whoever answered" path is gone.
2. **One object owns the hub on a device: `EnterpriseHub`** (`:core:network`). It holds one shared HTTP client (closed in `shutdown`, CLI-12; the former
   per-tap client leaked a connection pool each time), the `HubSession` (bearer from the challenge/verify exchange, renewed before it ends; a 401 refreshes
   once), the directory client, the `HubPeerDirectory` standing cache, the relay client and the live `HubStatus`. Hosts create it once, call `start()` and
   `shutdown()`, and use `enroll`, `unenrol`, `retryNow`, `dismissRevokedNotice`, `relinkPreviousGroups`.
3. **The relay is connected through the session, not through a stored string (CLI-08).** `RelayTransport` surfaces a typed `RelayFailure`
   (`NETWORK`, `HANDSHAKE_TIMEOUT`, `KEEPALIVE_TIMEOUT`, `AUTH_REJECTED`, `REVOKED`, `SERVER_MISMATCH`, `CERT_EXPIRED`, `KICKED`, `UNSUPPORTED_VERSION`, ...).
   Retries use a jittered, capped backoff (so a hub restart does not receive every phone in the same second); `REVOKED`, `SERVER_MISMATCH` and `CERT_EXPIRED`
   **stop** retrying. A slow collector never blocks ping/pong: inbound deliveries are buffered in a bounded channel in front of the collector, and a full
   channel is a visible overflow (the connection restarts and the hub replays what was not acknowledged), never silent loss.
4. **Revocation is confirmed, never taken from one frame (CLI-07).** A `FLASH_REVOKED` frame only triggers `confirmRevocation()`: the root-signed
   `GET /api/v1/revocations` list must contain this device's serial or id. Only then are the credentials cleared, the linked groups remembered
   (`previousHubLinks`, same organisation only) and `HubStatus.state = REVOKED` kept until the user dismisses it or enrols again. An unconfirmed revoke keeps
   the credentials and waits for "Retry now" (a hub that lies about revocation cannot sign a device out).
5. **Renewal (CLI-13).** `renewIfDue` runs from `maintenanceOnce` when the certificate is inside its renewal window (`EnrollmentCredentials.needsRenewalAt`),
   through `POST /api/v1/enroll/renew`; the answer must carry the same root key and device key and a later `validTo`. A failing renewal shows
   `CERT_EXPIRING`, then `CERT_EXPIRED` (not retried silently for ever).
6. **Leaving is explicit and atomic (CLI-09).** `unenrol()` tells the hub (`POST /api/v1/device/unenroll`; 404 counts as already removed), keeps a
   `pendingUnenrol` notice if the hub is unreachable (retried by maintenance), and clears credentials, session and join state in one write. The groups that
   were linked are remembered for "Restore N groups" after enrolling again with the same organisation.
7. **The live state is one value (`HubStatus`, derived by `HubStatusMath`).** Precedence: revoked notice, server mismatch, expired, then relay state.
   `FlashHubStatusMath` (`:ui:chat`) turns it into the dot, one line, the banner, the detail sheet and the plain-language enrolment failures; nothing
   protocol-shaped reaches the normal UI (AGENTS.md section 22).

### Alternatives considered
- Keep trust-on-first-use and show a fingerprint to compare by eye: users do not compare. Rejected (CLI-01 is P0).
- Treat `FLASH_REVOKED` as authoritative: the hub (or a MITM that got past TLS) could sign any device out. Rejected; the root-signed list costs one GET.
- A status string stored by the relay client: not testable and mixes four causes. Rejected for the typed failure + pure derivation.

### Consequences
- `EnterpriseTrustStore` gained `previousHubLinks`, `pendingUnenrol`, `pendingJoins`, `storageError` and atomic multi-field saves; both stores and the
  in-memory test store implement them. Existing installs read their old properties unchanged (no migration).
- Hosts no longer build `EnterpriseEnrollmentClient`, `EnterpriseDirectoryClient` or `FlashRelayClient` themselves.
- Extraction note for a future `:core:enterprise` module: `EnterpriseTrustStore`/`EnrollmentCredentials` live in `:core:security`, `HubSealing` uses that
  module's internal crypto seams, and `:ui:chat` now depends on `:core:network` for `HubStatus`. See `logs/handoff.md` (CLIENT section).

### Revisit when
A device test shows the confirmation GET is blocked by the same network problem that cuts the relay (`HUBC-03`), or the owner wants the hub URL changeable
without re-enrolling.

## ADR-121 - End-to-end sealing of hub content exists, but readable content through the hub is an explicit opt-in until the hub publishes sealing keys (CLI-05) (2026-10-13)

Status: ACCEPTED in code 2026-10-13, **gated: sealing is implemented and tested, but no hub serves sealing keys yet, so in practice the opt-in path is the
one that runs.** Not device-verified (`HUBC-05`, `HUBC-06`). Audit row CLI-05 stays **PARTIAL** (see below).

### Context
Chat frames sent through the hub were plaintext to the hub and to anyone with the mailbox database. The documented design is a blind hub.

### Decision
1. **Format** (`HubSealing` in `:core:security`): envelope type `SEALED_V1`. Per message an ephemeral P-256 key, ECDH with the recipient's *sealing public
   key*, HKDF-SHA256, AES-256-GCM. The associated data binds sender id, recipient id, message id and the envelope type, so a ciphertext cannot be re-sent
   under another sender, to another recipient or under another message id (tests: tampered, other sender, other recipient).
2. **The sealing key is a separate software P-256 key per device**, bound to the device identity by an identity-key signature over
   `(deviceId, sealingPublicKey)`. Sealing to the certificate key was rejected: the Android Keystore identity key is SIGN/VERIFY only (key agreement needs
   API 31, minSdk is 24), and the desktop key would have to leave its keystore to be used for agreement. The recipient's key is accepted only if
   `HubSealingKeyPair.verifyBinding` verifies against the recipient's **certified** device key from the directory (`DirectoryHubSealing`); a hub that swaps in
   its own key cannot forge the binding (test: forged sealing key never used).
3. **Policy in `HubChatBridge.canCarry`:** the hub carries chat content for a recipient only if the relay is usable, the recipient is a directory MEMBER, and
   either a verified sealing key exists or the user turned on **"Messages through the hub"** (default OFF, stored per device, never reset by "Reset to
   defaults", disclosure dialog before it turns on: "the hub can read these messages"). With the setting OFF and no sealing key the hub path is simply not
   used and the message waits for the LAN, which is what happened before the hub existed. Inbound: a sealed envelope is always opened (sender-bound);
   inbound plaintext through the hub is stored only when the opt-in is ON, else it is dropped, acknowledged and counted (`droppedPlain`).
4. **Metadata-only traffic needs no content opt-in:** typing indicators relayed by the hub carry no text.

### What is open (for the server owner, and why CLI-05 is not FIXED)
The hub does not publish sealing keys. For sealing to be the default path the server must (a) accept `PUT /api/v1/device/sealing-key`
(bearer; body = sealing public key + identity signature), and (b) return `sealingPublicKey` and `sealingSignature` on every `MemberDto` (the optional fields
already exist in `core/hub-protocol`, see `clientAdditionsToTheContractDecodeAndStayOptional`). Until then every device has `hubSealing = null`.
The client side of (a) (publishing the key at enrolment) is not written either, on purpose: it needs the route first.

### Alternatives considered
- Plaintext by default, document it: contradicts the product promise. Rejected.
- Seal to the certificate key: not possible on the Android Keystore at minSdk 24 (above).
- Group sealing with a per-group key: needs member key distribution and removal rekeying; out of scope (see ADR-123 / CLI-16).

### Revisit when
The server publishes sealing keys: then flip the default (content opt-in becomes "also allow readable fallback") and run `HUBC-05` on two phones.

Addendum (2026-10-13, after the mutation check): the sealed payload also carries the SENDER's identity-key signature over (AAD, epk, nonce, ciphertext), format `SEALED_V1.<epk>.<nonce>.<ciphertext>.<signature>`; a receiver opens it only after the signature verifies, so a message cannot be re-sent under another sender. See `docs/protocol.md` "Hub client behaviour and sealed payloads".

## ADR-122 - Hub delivery is acknowledged, LAN is tried first, and a hub-held message is not a failed message (CLI-06, CLI-14, CLI-15) (2026-10-13)

Status: ACCEPTED in code 2026-10-13, not device-verified (`HUBC-04`, `HUBC-07`, `HUBC-08`). Mirrors ADR-112 on the receiving side.

### Decision
1. **Receive path.** `HubChatBridge.handleInbound`: duplicate (LRU of 2048 message ids) -> sender trust (a paired peer or a directory MEMBER; directory
   unavailable -> `RETRY_LATER`, no acknowledgement so the hub replays it; unknown sender -> rejected and acknowledged) -> unseal, or plaintext only under the
   opt-in -> decode (`CHAT_FRAME`, legacy `MSG_TEXT`, `GROUP_FRAME`; the legacy `GROUP_MSG` fallback that stored a group message under the envelope id is
   removed, CLI-15) -> the frame's author must equal the hub-stamped `Deliver.from` (no impersonation by a member) -> the repository **stores it durably**
   -> only then `FLASH_RECV_ACK`. `RealFlashChatRepository` is the only place that decides "stored"; the bridge never acknowledges earlier.
2. **No loss before a collector exists.** Deliveries are buffered in the relay client until the repository collects; a slow collector cannot stall the
   socket (ADR-120 item 3).
3. **Send path (LAN first, hub as fallback).** The old `raceTransports` sent on LAN and hub at once. It is replaced: a LAN attempt first; if it succeeds and
   the recipient's receipt arrives, the hub is never used; if LAN fails, the hub is used immediately; if LAN reports success but no receipt arrives within
   `HUB_FALLBACK_GRACE_MS` (2 s), the hub also receives it (the recipient dedupes by message id). Receipts, reactions, reads, deletes and typing fall back the
   same way. Group chat-level frames go through the hub only for hub-linked groups.
4. **Outbox semantics.** `FLASH_ACK` kinds: `DELIVERED` and `HELD` (queued) keep the message `SENT`; `UNDELIVERABLE`, `QUOTA`, `REFUSED`, `EXPIRED`, `DROPPED`
   fail it with a friendly sentence (`HubDeliveryText`, shown through `hubDeliveryNotes`). A message the hub accepted is **not** failed by the old 30 minute
   timer: the give-up budget is 8 days (`HUB_GIVE_UP_AFTER_MS`, just above the hub's default TTL) and the resend spacing 15 minutes (`HUB_RESEND_MS`).
5. **Sender must be a known colleague to send through the hub (`standing = MEMBER`).** Otherwise a hub failure would wrongly fail a message that a
   LAN-only peer would have received.

### Known limitations
- `hubAccepted` is in memory: after a process restart a resend gets `DUPLICATE_QUEUED` from the hub, which counts as accepted.
- Presence is not surfaced through the hub (a colleague reachable only through the hub shows no online dot).
- Group messages from a hub-only (unpaired) member are rejected by the repository's own group gate: see ADR-123.

### Revisit when
`HUBC-04` shows the 2 s grace is too long or too short on a real Wi-Fi.

## ADR-123 - Joining a department group asks the hub and says what happened; group crypto is not changed (CLI-04, CLI-16) (2026-10-13)

Status: join flow ACCEPTED in code 2026-10-13 (not device-verified, `HUBC-09`..`HUBC-11`); the CLI-16 design note is **PROPOSED** and nothing in it is built.

### Decision (CLI-04)
`joinEnterpriseGroup` calls `POST /api/v1/groups/join-request` and acts on the answer: `JOINED` creates the conversation and links it to the hub;
`PENDING` records "waiting for approval" in the trust store (`pendingJoins`) and shows a row at the top of the chat list; `REJECTED` clears it with the hub's
sentence. A pending request is polled every 60 s (`PENDING_JOIN_POLL_MS`) with the same call (the hub answers `PENDING` until decided, then `JOINED` or
`REJECTED`). Admins review requests from the group row ("N requests waiting" -> Approve / Decline, `GET /api/v1/groups/join-requests`,
`POST /api/v1/groups/join-requests/decision`). Rows show member count, "Needs approval" and a foreign department badge.

### Design note (CLI-16, PROPOSED, not built)
Members who are in a hub group but not paired with each other cannot read each other's group frames: Flash group frames are authenticated by the pairwise
session, and the group roster/vouching model (ADR-044) is pairing-based. The client therefore states the limitation in the UI
(`FlashHubStatusMath.GROUP_LIMITATION_TEXT`) instead of silently dropping. Possible fixes, none chosen: (a) vouched introductions through the hub directory
(a member's certified device key lets two members pin each other without a pairing code), (b) a per-group sender key distributed through sealed hub
messages, (c) accept hub-certified identity as sufficient for group frames. (a) fits ADR-044's vouching with the least new cryptography. **The owner decides;
write no group crypto before that** (same rule as the owner-loss audit).

### Consequences
`FlashChatRepository.joinEnterpriseGroup` now returns `FlashResult<EnterpriseJoinResult>`; the interface exposes `pendingEnterpriseJoins`,
`fetchEnterpriseJoinRequests`, `decideEnterpriseJoinRequest`.

### Revisit when
The owner picks a CLI-16 option, or a device test shows members cannot read each other in a hub-linked group (`HUBC-11`).


## ADR-111 - The organisation root key is OFFLINE: keytool, delegation lifecycle, awaiting-delegation mode, and persisted hub state (2026-10-13)

Status: ACCEPTED in code 2026-10-13 (phase 2 of the hub fix effort, SERVER-A), unit-tested, not device-verified (`HUBA-01`..`HUBA-06`, `docs/testing/TEST-BACKLOG.md`).
Audit rows: `docs/audit/2026-10-12-server-hub-audit.md` SEC-04, SEC-07, SEC-08, SEC-18 (hub part), REL-10. (Numbering: ADR-112 was written by SERVER-B first; ADR-111 was reserved for this decision.)

### Decision
1. **The organisation root private key never lives on the hub.** `server keytool init-root --out DIR` (run on an OFFLINE machine) writes `root-identity.pk8`
   (encrypted: PBKDF2-HMAC-SHA256 + AES-GCM, pass-phrase from the console or `FLASH_ROOT_PASSPHRASE`; `--no-passphrase` is explicit and loud), `root-identity.pub` and prints the
   root fingerprint (never the private key), all owner-only (0600 / owner ACL). It refuses to overwrite and never prints the private key. `keytool issue-delegation` signs a
   delegation certificate (`DelegationCert`) for the hub's PUBLIC delegate key with explicit scopes (`enrol:member`, `revoke:member`, ...), a device limit
   and a validity window (default 90 days), with a monotonic `--serial`. `keytool rotate` prints the rotation procedure (new delegation, same root); the serial is remembered next to the root key (`delegation-serial.txt`).
2. **The hub owns only a delegate key** (`<data>/server-identity.pk8` + `.pub`, owner-only), created once. A file that exists but does not parse, a public/private
   mismatch or a half-written pair is a startup error: the key is NEVER regenerated silently (that was the SEC-04 failure, it breaks every client pin).
3. **Delegation lifecycle.** `DelegationManager` loads `data/delegation.cert` (or `FLASH_DELEGATION_FILE`), verifies it against the pinned root
   (`FLASH_ROOT_FINGERPRINT`, else the fingerprint adopted at the first installation and stored in `meta`), requires it to be for THIS hub's delegate key,
   and accepts a replacement only with a strictly higher serial. A file change is picked up by the 60 s housekeeping (hot reload) or
   `POST /admin/api/v1/delegation/reload` (step-up gated); an admin may also upload one (`.../delegation/install`, step-up gated). Expiry warnings are logged and shown in
   the console at 14 and at 3 days; an expired delegation stops enrolment and renewal (never silently re-issued).
4. **Awaiting-delegation mode.** With no valid delegation the hub still starts: `/health` and the admin console work, enrolment and auth routes are not mounted (404), the relay
   refuses, and the log prints the delegate PUBLIC key plus the exact `keytool issue-delegation` command. (A first delegation
   needs a restart to activate the enrolment services; a replacement hot-reloads.)
5. **A root key found in the hub's data directory refuses the start** (`misplacedRootKey`), because an operator who copied it there defeats the model.
   `--dev-embedded-root` (needs `--allow-insecure`) keeps the old embedded root for development only and logs an error line at startup; it is the only path
   where a root key exists on the hub.
6. **State is persisted and migrated.** `storage/Migrations.kt`: per-component versioned steps in `schema_migrations(component, version)`, each step in one
   transaction, a failing step stops the hub (fail closed). Persisted: invites (secrets stored as SHA-256 only), the device-serial counter (monotonic
   across restarts), enrolled devices/identities (`IdentityStore`), the enrolment quota, the directory (`OrganizationDirectory` on SQLite, REL-10), admins,
   sessions, lockouts, TOTP replay step, revocations and the audit chain. `/admin/setup` stays closed after a restart once an admin exists.
7. **Enrolment is validated and atomic** (SEC-07): proof of possession and P-256 SPKI parse before anything is spent; deviceId format; duplicate or revoked
   id refused without burning the invite; token use, quota and serial are consumed in one transaction (an 8-thread race on a single-use token yields exactly
   one device); a renewal endpoint re-issues a certificate inside the delegation window for a device that is not revoked.

### Alternatives considered
- Keep an encrypted root on the server unlocked by an env pass-phrase: rejected, the pass-phrase sits next to the ciphertext, and the hub is the most attackable host.
- Hardware token / HSM only: not rejected, but too heavy for the first release; `keytool` keeps the file format simple so a later HSM signer can replace it.
- Auto-generating a fresh root when none exists: rejected (breaks pins, silent trust reset).

### Consequences
An operator needs a second, offline step to bring a hub into service (`HUBA-01`). The hub cannot mint a delegation for itself; losing the root key means
re-enrolling the organisation (documented in `docs/security.md`).

### Revisit when
An HSM / smart-card signer is wanted, or `keytool` is asked to produce QR delegation hand-over for hubs without file access.

## ADR-113 - Revocation is a persisted, signed, monotonic list served at `GET /api/v1/revocations`; revoking a device kills its bearer, kicks its socket and removes it from the directory (2026-10-13)

Status: ACCEPTED in code 2026-10-13, unit-tested, not device-verified (`HUBA-07`, `HUBA-14`). Audit rows: SEC-05, SEC-08 (part). Builds on ADR-110 (the list format).

### Decision
1. `RevocationService` (interface in `auth/`): `isRevoked(serial?, deviceId)`, `current()` (the latest signed list), `onRevoked(listener)`.
   `SqliteRevocationService` implements it and adds `revokeDevice`, `revokeSerial`, `unrevokeDevice`, `revokeSelf`, `installRootSigned`, `listAt`, `status`,
   `entries`. Every change is one transaction that updates the tables, bumps `version` strictly, signs the new list with the delegate key
   (`RevocationSigner`, needs the `revoke:member` scope) and stores it; `isRevoked` is two hash lookups, so the relay (SERVER-B), the bearer store and the
   directory call it on every request.
2. **By serial AND by device id.** Revoking a device revokes the id (every certificate it ever held) and the serials issued so far. An id that is revoked
   cannot re-enrol (the invite is not burned). `unrevoke` returns the device to UNENROLLED and the OLD serials stay revoked, so a stolen old certificate stays dead
   while the owner can enrol again with a fresh invite. `unenroll` (device-initiated leave) frees the id and the quota slot.
3. **A hub that cannot sign still protects.** Without the signing right the hub stores the entry and answers `isRevoked` correctly, but
   `/api/v1/revocations` reports `needsSignedList`; a root-signed list can be installed with `installRootSigned` (version must exceed the current one).
4. **Effect is immediate**, not at the next token expiry: `BearerTokenStore.resolve` re-checks `isRevoked` on every use (test
   `aBearerTokenDiesTheMomentItsDeviceIsRevokedEvenWithoutAKick`, mutation-checked), the admin route and `HubServices` call `bearerTokens.revokeDevice`,
   `directory.removeDevice` and the engine's relay `kick` through `onRevoked`.
5. `GET /api/v1/revocations` needs a bearer (any enrolled device); the list is signed so it is also verifiable by a client that got it through a peer.
6. Revoke / unrevoke from the console need a step-up confirmation (ADR-115) and are audited with actor, target, reason and published version.

### Alternatives considered
- A database flag only: rejected, clients must verify peers offline (ADR-110).
- Revoking by serial only: rejected, a device could re-enrol under the same id and the operator would chase serials.

### Revisit when
The list grows to thousands of entries (delta format), or the client needs push of the list over the relay.

## ADR-114 - TLS modes, listeners, trusted proxies and strict configuration (2026-10-13)

Status: ACCEPTED in code 2026-10-13, unit-tested (`AbuseControlsTest`, `ServerConfigTest`, `HubServicesTest`, `MainCommandsTest`); Docker image not built in this session (`HUBA-10`). Audit rows: SEC-06, SEC-09 (part), SEC-10 (part), SEC-16 (part).

### Decision
1. `FLASH_TLS_MODE` is one of `proxy` (default; the hub speaks plain HTTP behind a TLS-terminating reverse proxy on a private network), `self` (the hub terminates TLS itself,
   self-signed P-256 certificate created once under `data/tls/`, owner-only, 365 days, renewed within 30 days of expiry, SAN from the external URL; its SHA-256
   fingerprint is logged and shown in the console so clients can pin it; or a supplied PKCS12 via `FLASH_TLS_KEYSTORE` + `_PASSWORD`) or `none` (plain HTTP,
   only with `--allow-insecure`).
2. **The external URL (invite and QR links) must be `https://`** unless `FLASH_ALLOW_INSECURE=true`; the enrolment service additionally refuses to create
   a plain-http invite (`requireHttpsInvites`). The default external URL is `https://<lan-ip>:<port>`.
3. **Two listeners.** Public (`FLASH_PORT`, 8443): enrol, auth, `/api/v1`, relay, `/health`. Management (`FLASH_ADMIN_PORT`, 8444, bound to `FLASH_ADMIN_HOST`,
   default `127.0.0.1`): the admin console and API. `AdminListenerPolicy` makes `/admin` answer 404 on the public listener unless
   `FLASH_ADMIN_ON_PUBLIC_PORT=true` (single-port setups). The Dockerfile `EXPOSE`s only 8443; inside a container the management listener binds all interfaces so publish it to host loopback only (`-p 127.0.0.1:8444:8444`).
4. **Cookies.** Admin session cookie is `__Host-flash_admin_session` (Secure, HttpOnly, SameSite=Strict, Path=/, no Domain) over https and `flash_admin_session`
   (HttpOnly, SameSite=Strict, Path=/admin) only when the hub is explicitly insecure.
5. **Client address.** `X-Forwarded-For` / `-Proto` are believed only from `FLASH_TRUSTED_PROXIES` (CIDR list; malformed entries refuse the start); the
   rightmost untrusted entry wins, so a prepended forged address does nothing.
6. **Strict config.** A malformed or out-of-range number, an unknown mode, a short setup token or a root fingerprint that is not 64 hex digits is an error
   that names the variable (exit code 2), never a silent default. Dev switches (`--allow-insecure`, `--dev-embedded-root`) log loudly at startup.
7. **Setup token.** Generated into `data/setup-token.txt` (0600), reused until used, never printed to the console in production (only the path); printed only
   with `--dev-embedded-root` or when `FLASH_SETUP_TOKEN` is supplied by the operator.
8. **Docker.** Base images pinned to a release tag (digest pinning documented in the Dockerfile), non-root user (uid 10001), `HEALTHCHECK` through `server healthcheck`, `.dockerignore` excluding keys,
   keystores, `tools/.tavily_api_key`, `.git`, build output; no `EXPOSE 8444`.
9. **Abuse limits** (`RateLimiter`, `HubRateLimits`): token buckets per client address for enrol, auth challenge/verify, directory, admin login, with a
   bounded key map; bodies over 64 KiB answer 413; no regex JSON anywhere in these paths (SEC-15).

### Alternatives considered
- Always terminate TLS in the hub: rejected, most deployments already have a proxy and certificate automation.
- Admin console on the public port by default: rejected, a management surface must not be internet-reachable by accident.

### Revisit when
ACME in the hub is wanted, or `HUBA-10` shows the Docker HEALTHCHECK is unreliable on a slow disk.

## ADR-115 - Admin authentication, step-up confirmation, CSRF, audit chain and the JSON admin API (2026-10-13)

Status: ACCEPTED in code 2026-10-13, unit-tested (`AdminApiTest` 30 cases, `AbuseControlsTest`, `AdminAuthServiceTest`, `StepUpAuthTest`, `AuditLoggerTest`), mutation-checked on CSRF and step-up; the HTML console is replaced in a later phase. Not device-verified (`HUBA-11`..`HUBA-13`). Audit rows: SEC-09, SEC-10, SEC-12, SEC-13, SEC-17, REL-16.

### Decision
1. **Login.** Username + password (PBKDF2-HMAC-SHA256, 600 000 iterations, per-user salt; older 100k hashes are upgraded on login) + TOTP. The attempt counter is
   RESERVED before the password is verified (atomic, so a parallel burst cannot exceed the budget), unknown users take the same time as known ones
   (dummy hash), lockouts are persisted and keyed by user and by client address, TOTP steps are single-use (`last_totp_step`, replay refused), recovery codes
   (hashed, single-use) replace a lost authenticator, `admin-recovery --username` (console only) resets an admin and ends its sessions.
2. **Setup.** First admin is created only with the setup token (constant-time compare, one-shot, persisted so it stays closed after a restart); the server
   generates the TOTP secret, the client must confirm a code before the admin is activated.
3. **Sessions** are random 256-bit tokens, stored hashed, idle timeout 30 min / absolute 2 h, swept by housekeeping, at most 5 per admin, revocable from `/sessions`.
4. **CSRF.** Every state-changing admin API call needs the header `X-CSRF-Token` equal to the session's token AND a same-origin `Origin` / `Sec-Fetch-Site`
   (`CsrfGuard`, also checked on the legacy HTML forms with origin + SameSite).
5. **Step-up.** Dangerous actions (device revoke / unrevoke, revoke by serial, delegation install / reload, create or reset an admin, disable an admin, change a
   setting, invite for a non-member role) require a fresh confirmation signed by the ADMIN'S OWN enrolled device key: `POST /stepup/challenge` returns a
   challenge bound to (admin, action, target), the device signs `HubStepUp.proofBytes(...)` (shared in `core/hub-protocol`), `POST /stepup/verify` checks it
   against the key stored for THAT admin on the server (never one supplied by the caller), the call carries `X-Step-Up: <challengeId>`. A challenge is single-use,
   expires in 5 minutes and is refused when the device was revoked.
6. **Audit.** `AuditLogger`: hash chain, each entry signed by the delegate key and persisted; `/audit/verify` recomputes the chain; `/audit/head` and
   `/audit/export` (CSV) support off-box anchoring; failed logins, setup attempts, enrolments, revocations, key loads and delegation changes are logged.
7. **Invites** use the real `EnrollmentService` registry (the dashboard-only map and the SHA-256("") fingerprint fallback were removed, REL-16): create, bulk
   create, revoke, list with state (`ACTIVE|USED|EXPIRED|REVOKED`), `/enroll` landing page no longer reveals more than validity.
8. **JSON admin API** (`/admin/api/v1`, session cookie + CSRF, errors `{code,message,correlationId}`): setup (`status|begin|complete`), `login`, `logout`,
   `session`, `overview`, `devices` (+revoke/unrevoke), `invites` (+bulk, revoke), `revocations` (+serial), `delegation` (reload/install), `settings`,
   `stepup/challenge|verify`, `departments`, `groups` (+members), `join-requests` (+approve/reject), `admins` (+password, disable/enable, `me/password`,
   `me/recovery-codes`, `me/device`), `sessions`, `audit` (+head, verify, export). The HTML console is a thin layer to be replaced by a later phase; it is not
   hardened here on purpose (SEC-11 stays OPEN until that phase).

### Alternatives considered
- WebAuthn for step-up: attractive, deferred; the enrolled Flash device key already exists and signs the same bytes on desktop and phone.
- JWT sessions: rejected, revocation and idle timeout are simpler with server-side sessions.

### Revisit when
The new admin UI is built (it must use this API and keep CSRF), or an organisation needs more than one admin role.


## ADR-124 - The hub publishes sealing keys after verifying their binding, and client and server are tested against each other (2026-10-13)

### Decision
1. **Sealing keys on the hub.** `PUT /api/v1/device/sealing-key` (bearer, body `SealingKeyRequest {sealingPublicKey, signature}`) stores the PUBLIC half of a device's end-to-end sealing key (ADR-121). `DELETE` on the same path removes it. The hub verifies, before it stores anything: the caller is the authenticated principal (the device id is the principal's, never the body's); the key is a canonical NIST P-256 SPKI (other curves, RSA, trailing bytes, off-curve points refused: `Spki.parseP256`); the signature is the device identity key's ECDSA signature over `HubAuth.sealingKeyBindingBytes(deviceId, spkiBytes)` (tag `flash-hub-sealkey-v1`, golden vector `SEALING_BINDING_HEX` in `CertGoldenVectorTest`, asserted by the client, the hub-protocol and the server tests); the device key used is the one in the registry (certified at enrolment), never a key the request carries; the key is not one this device retired earlier (a captured old binding cannot be put back: 409 `ERR_CONFLICT`).
2. **Served** as `MemberDto.sealingPublicKey` + `sealingSignature`, only to authenticated callers in scope of the member (the same rule as `subjectPublicKey`). The client MUST verify the binding against the member's `subjectPublicKey` again; the hub's check is defence in depth, not the trust root.
3. **Stored** in the identity store (`hub_sealing_keys`, `hub_sealing_retired`, identity schema step 2, migration tested from step 1). Public material only: no private key can reach the hub (the endpoint has no field for it; a test scans the hub's files for the PKCS#8 bytes). Revoking, unenrolling or deleting a device removes the key through triggers (so every code path is covered). Publish, rotate, refuse and remove are audited with a fingerprint prefix, never the key. Rate limit 5 burst then 1 per 30 s per device; bodies over 64 KiB are refused with 413.
4. **Contract tests.** `:server` tests drive the REAL client classes (`EnterpriseHub`, `FlashRelayClient`, `KtorRelayTransport`, `HubSealing`) against the REAL `HubServices` on a temp database (`server/src/test/kotlin/com/transfer/flash/server/e2e/`). `:core:network` and `:core:security` are `testImplementation` only: no runtime dependency from the server on client modules. Test seams in production code, both documented: `HubServices.build(limits = ...)` (default unchanged) and the injected `clock` that the challenge nonce now honours (ERROR-337).
5. **Mismatches fixed** (each with the side that was wrong in `logs/errors.md`): expired invite 410 `ERR_EXPIRED` and used invite 409 `ERR_CONFLICT` (ERROR-330, server); the join decision answers `JoinResponse` (ERROR-331, server); a rejected join is shown once to the requester (ERROR-332, server); concurrent `FlashRelayClient.start()` made two sockets that kicked each other (ERROR-333, client); the mailbox answered `ERR_UNKNOWN_RECIPIENT` for every enrolled offline device (ERROR-334, server); `GET /revocations` needed a bearer a revoked device cannot have, so a revoked phone never cleared itself (ERROR-335, server: now public and rate limited); the client rejected every list the hub signs because it did not offer the delegation (ERROR-336, client).

### Alternatives considered
- Trust the hub to say which sealing key belongs to whom: rejected (ADR-121); the identity-key binding makes a swapped key detectable by the client.
- Put the sealing key in the enrolment certificate: rejected, it would force a re-enrolment to rotate a key and change the certificate wire format (ADR-109).
- Keep `GET /revocations` behind a bearer and let the client accept `FLASH_REVOKED` plus a failed login: rejected, that is exactly the "a hub that merely says revoked can sign a device out" weakness the signed list exists to close.
- Mock the hub in client tests / mock the client in server tests: rejected as the only coverage; both mocks were wrong in the same direction six times (see the errors above).

### Consequences and open points
- Revoked device ids (and serials) are readable by anyone who can reach the hub, at the unauthenticated rate limit. Owner may prefer a narrower list (serials only); the list format is signed, so a change needs a protocol revision.
- The hosts (Android, Desktop) do not yet construct a `HubSealingPort`, store a sealing key pair or call `publishSealingKey`; until they do, `hubSealing = null` and the default stays "readable content through the hub is opt-in" (ADR-121). The server half and the contract are ready and tested. **UPDATE 2026-10-13 (ADR-126): the hosts now do all of this in code (key store, publication, `DirectoryHubSealing`); not device-verified. The sentence above is kept as the state when ADR-124 was written.**
- Group messages are not sealed by this mechanism (one recipient key per message); group sealing is a separate design.

### Revisit when
The hosts publish keys (HUBE tests), or an organisation needs per-department narrowing of who may see a member's sealing key.


## ADR-125 - The hub admin console is static, dependency-free files served by the hub, and its step-up key lives in the browser (2026-10-13)

(Numbered 125 because ADR-124 was taken by the contract work the same day.)

### Decision
1. **Static console, no build step.** The console is plain ES modules, CSS and one HTML shell under `server/src/main/resources/admin-ui/` (about 45 files, 462 KB), served by `AdminConsoleWeb.kt` from the jar, talking to the existing `/admin/api/v1` JSON API. No framework, no bundler, no node at build time, no CDN, no web font, no analytics: it must work on a hub with no internet and nothing in it can change under a deployment. The old server-rendered `AdminUiHtml.kt` / `AdminDashboardRoutes.kt` are deleted (closes SEC-11).
2. **Safe by construction.** One DOM helper (`dom.js`) creates elements with `textContent`, an attribute allow-list and `addEventListener`; `style`, `on*`, `srcdoc` and unsafe `href` values throw. A strict CSP (no `unsafe-inline`) and the other headers are set on every response under `/admin`, `/enroll` and `/enroll-assets/`, errors included. Tests scan every console file for forbidden patterns, so the rule survives later edits.
3. **Step-up in the browser.** Dangerous actions are confirmed by a WebCrypto ECDSA P-256 key, `extractable: false`, kept in IndexedDB. The administrator registers it (`POST admins/me/console-key`, or during setup) with a proof over `flash-console-key-v1\n<username>\n<spki>` and a fresh authenticator code. The hub's step-up verifier accepts the signature as DER (phones) and as 64-byte P1363 `r||s` (browsers) by converting P1363 to DER (`ConsoleKeys`); the signed bytes are unchanged.
4. **TOTP QR** is drawn by the hub as one SVG path (`QrCodeGenerator.generateSvg`); the console re-draws it from an allow-listed viewBox and path instead of inserting the string, so no network markup reaches the DOM.
5. **Console additions to the API** are listed in `docs/protocol.md` "Hub administration" (console key, TOTP reset, metrics, audit order, reject reason, hub public key and session ids in DTOs, the landing page's own assets).

### Alternatives considered
- **Keep and patch the server-rendered HTML** (escape everything, add a CSP with nonces): rejected by the owner (the design is not professional) and every page would keep mixing markup, data and script in Kotlin strings.
- **SPA framework with a build step (React, Vue, Svelte, Vite):** rejected: a toolchain and hundreds of transitive packages in a security product, a build artefact to keep in step with the jar, and no need for it at this size.
- **Compose for Web / Wasm (shared with the apps):** rejected for now: multi-megabyte runtime, canvas rendering that breaks browser accessibility, text selection and zoom, and an administrator console needs those more than it needs code sharing.
- **A CDN library (htmx, Alpine, chart or table libraries):** rejected: external request (breaks air-gapped installs and the CSP), supply-chain exposure; the needed pieces (windowed table, dialogs, sparkline, QR redraw) are small.
- **Enrol the browser as an admin-role device through an invite:** rejected: it would mint a device certificate with the admin role for a browser profile, turn every browser into a directory member and mix two trust anchors; a console-only key bound to one administrator is narrower.
- **Password-only re-authentication for dangerous actions:** rejected: it protects nothing against a session thief who already typed or captured the password path; a non-extractable key does protect against a stolen cookie.
- **WebAuthn:** still attractive (see ADR-115); deferred because it needs relying-party configuration per hub address, and the non-extractable key gives the same cookie-theft protection today.

### Consequences and open points
- Step-up needs a secure context (https or localhost); plain-http hubs show this plainly.
- A cleared browser profile loses the key; re-registration needs a TOTP code.
- The console has English strings only (all text goes through `t()` and `strings/en.js`, ready for more).
- The real-browser matrix (Chrome, Firefox, Safari), screen-reader and zoom checks are owed (`HUBU-*`).

### Revisit when
An organisation asks for WebAuthn or SSO, a second language is needed, or the console grows beyond what plain modules keep readable.


## ADR-126 - Hosts own a software sealing key, publish it, pin colleagues' keys, and hold (never downgrade) on a changed key (2026-10-13)

### Decision
1. **A per-device software P-256 sealing key**, separate from the identity key, made on the first enrolment and used by `HubSealing` (ADR-121). Port: `HubSealingKeyStore` (`:core:security`, `load()` / `save()` / `clear()`); stored format `HubSealingKeyCodec` (version byte, public SPKI, private PKCS#8).
2. **Key at rest, per platform.**
   - Android: `AndroidHubSealingKeyStore`. An AES-GCM key that lives in the Android Keystore (`KeystoreSecretSealer`, alias `flash_hub_seal_wrap_v1`, not exportable) wraps the PKCS#8 bytes; the wrapped bytes are in app-private SharedPreferences `flash_hub_sealing`, written with `commit()`. The backup and data-extraction rules already exclude all shared preferences, so the key never reaches a cloud backup or a device transfer. A key that cannot be unwrapped (Keystore wiped, restored to another phone) reads as "no key" and a new one is made.
   - Desktop: `FileHubSealingKeyStore` over `<stateDir>/hub/sealing.key`, protected by the same `IdentityKeyVault` as the identity key (DPAPI on Windows, Secret Service then an owner-only 0600 key file on Linux), written atomically (temp file and move).
   - Why a software key wrapped by a hardware-backed one, not a Keystore EC key: sealing needs an ECDH agreement with an ephemeral key and a signature on the envelope with the identity key; the same code (`HubSealing`) must run on desktop where no Keystore exists. Wrapping keeps one format and one code path. (Whether the Android Keystore can do ECDH on every supported API level was not checked for this ADR; if it is later verified, an in-Keystore key may replace the wrapper without changing the wire format. Test `HUBH-04` checks the wrapper on a real device.)
3. **Lifecycle** (`HubSealingKeyManager`, driven by `EnterpriseHub`): the key is stored BEFORE it is published, and a key that cannot be stored is never published. It is published (PUT, with the binding signature over the device id and the key made by the identity key) after enrolment and after every renewal or re-enrolment (idempotent on the hub). `409` (the hub remembers this key as retired) makes a NEW key; `429` waits 35 s; offline or a 5xx backs off from 15 s to 15 min; `404/405` (a hub that predates ADR-124) and a refusal retry every 30 min. Unenrol asks the hub to forget the key (DELETE, 5 s limit, best effort) and always deletes the local key; a confirmed revocation wipes the local key without calling the hub; enrolling into a different organisation forgets the old key. Only the short public fingerprint is ever logged.
4. **Sealing in the bridge** (`HubChatBridge` + `DirectoryHubSealing`): a 1:1 message to a colleague with a verified published key is ALWAYS sealed, whatever the "Messages through the hub" switch says. A colleague without a published key gets a readable message only when the switch is ON; otherwise the message is held and the chat says "Waiting for <name> to update the app" (or, when this device cannot seal either, that the hub switch is off). There is no silent plaintext fallback.
5. **Group frames are not sealed here** (the group design is open, ADR-123). They follow the same opt-in readable rule, and a hub-linked group says so in its own words ("the hub can read this group's messages" / "group messages through the hub are off").
6. **Key-change detection** (`HubSealingPins`, pure rules in `HubSealingPinMath`): the fingerprint (SHA-256 of the SPKI) of a colleague's sealing key is pinned on first use. A different verified key for the same device id is `CHANGED`: nothing is sealed or sent to that colleague through the hub, sealed OR readable and even with the switch ON, until the user taps **Continue** in the chat (the calm inline notice also offers **Verify**, which opens the peer details). The pin and the pending change are persisted (`AndroidHubSealingPinStore`, `FileHubSealingPinStore`); a pending change keeps holding while the directory cannot be read; a failed pin write still protects the run. A message waiting for that decision is kept (`wouldHold`), not failed after the short budget.
7. **Incoming sealed messages** are opened only when the sender's signature verifies against the identity key the hub certified for that sender. A failure is dropped, counted (`droppedUnverified`, shown as "Messages not opened" in the connection details, with "Messages not accepted" for readable messages refused while the switch is off) and never shown as message content. A sender the directory cannot be asked about right now is not treated as a forgery: the delivery is kept for later (`RETRY_LATER`).

### Alternatives considered
- **Seal to the identity key.** Rejected: the identity key is also the TLS and pairing identity; using it for static-ephemeral ECDH would couple two jobs, and a sealing key can be rotated (409, unenrol) without touching pairings.
- **Auto-trust a changed key and show a banner afterwards.** Rejected: that is the exact moment a hub that swaps a key would win. The binding signature stops a hub minting a key for a device, but a device that really reinstalled and a stolen-then-re-enrolled device look the same to the sender; one explicit tap is cheap.
- **Fall back to a readable message when the key changed (opt-in ON).** Rejected: a changed key is a reason to stop, not to downgrade.
- **Block on key change with no way forward (strict TOFU).** Rejected: every reinstall would lock the chat for good; Continue is the escape hatch, Verify the careful path.
- **Seal groups with the per-member keys now.** Rejected for this phase: group membership changes, ordering and per-recipient cost are an open design (ADR-123); doing it half-way would look secure and not be.
- **Keep one wrapper-less file on Android.** Rejected: no hardware-backed protection, for no gain over the Keystore wrapper that already exists (`KeystoreSecretSealer`).
- **Back the key up.** Rejected: a backed-up private key is a copied private key. A restored phone gets a new key and every colleague sees one key-change notice.

### Consequences and open points
- A reinstall or a new phone is always a key change for every colleague who had pinned the old key (one notice each, one tap). Two phones of one person have two keys; nothing synchronises them.
- **Verify** opens the existing peer details; the notice itself shows the new and the old short codes so the two people can compare them out loud. A dedicated "compare sealing codes" screen is not built.
- Pins are local to a device and are not part of any backup. Removing a colleague from the directory does not remove the pin.
- The hub notice of a conversation is computed whenever the conversation state emits; a hub-linked group's notice can therefore be stale until the next emission after the "Messages through the hub" switch is flipped (accepted for now).
- A message held for a changed key is retried at once when the user taps Continue (`confirmHubKeyChange` drains the outbox).
- Not built: group sealing, sealing keys for the Desktop `Rejected` handlers, a key-transparency log. The hub-delivery notes (`hubDeliveryNotes`) are still not rendered anywhere in the chat UI (pre-existing gap, now partly covered by the inline notice).
- Not device-verified: all of it. Tests `HUBH-01`..`HUBH-08`, `HUBC-05`, `HUBC-06`, `HUBE-05` in `docs/testing/TEST-BACKLOG.md`.

### Revisit when
Group sealing is designed (ADR-123), the Android Keystore is verified to do ECDH on every supported API level, a security review asks for a sealing-code comparison screen, or the owner wants the key to follow a person across devices.



## ADR-127 - Second-pass hub review: what was fixed, what is decided, and what is left to the owner (REV-01..REV-16, 2026-10-13)

### Decision
An independent adversarial review of the Phase 2 server work (agent a45d6a47517265147) rated SEC-03, SEC-04, SEC-05, SEC-09, SEC-10, SEC-11, SEC-16, REL-04, REL-11 and REL-13 PARTIAL and found new problems (P1-1..3, P2-1..12). The coordinator fixed them in `:server` and `:core:hub-protocol` (IDs `REV-01`..`REV-16`, errors `ERROR-380`..`ERROR-395`). The choices that shape the system:

1. **One client address, everywhere** (REV-01, REV-02). `ClientAddressResolver.clientKey` is the only source of "who is calling": the socket peer, or the rightmost untrusted `X-Forwarded-For` entry for a peer in `FLASH_TRUSTED_PROXIES`; IPv6 is keyed by /64. The relay's per-IP connection cap and every REST limiter use it. The old `trustForwardedFor` switch is removed. A `proxy` TLS mode with no trusted proxies is logged as a WARN, because every device would then share one proxy address.
2. **Canonical request paths** (REV-02): `RequestPathGuard` rejects ambiguous paths with 400 before the API gate and routing, and adds baseline headers to every response.
3. **Data at rest** (REV-03): `SecureFiles` sets owner-only permissions (POSIX, and ACLs on Windows) on the database, key and token files; secrets stored in the database (TOTP seeds) are sealed with AES-GCM under `data.key` (`enc1:` prefix; legacy plaintext values are sealed on start). `data.key` must be backed up **apart from** the database. Whole-database encryption is NOT done (see Proposed).
4. **Delegation can be revoked** (REV-04): `keytool revoke` (root key, offline) signs a list naming delegation serials; `POST /admin/api/v1/revocations/install` (step-up `revocation.install`) installs it; the hub installs the dropped-in list at start; a revoked delegation revokes everything it issued, including the hub's own authority to serve.
5. **A session may not outlive its certificate** (REV-05): the relay closes it with `ERR_EXPIRED` 60 s (`sessionExpirySkewMs`) after expiry; the device renews and reconnects.
6. **Join requests are not an oracle** (REV-06): a missing, hidden or out-of-scope group answers one constant sentence.
7. **Setup is not enumerable and not evictable** (REV-07): `setup/status` says only whether setup is open; `setup/begin` needs the setup token (with the same lockout) and refuses a 17th pending setup rather than evicting one.
8. **Mailbox acknowledgements name the sender** (REV-08): `RecvAck.from` (additive). Without it the OLDEST row with that message id is deleted, never all rows. A live hand-off never overwrites another origin's in-flight entry.
9. **META is silent and unscoped for direct recipients** (REV-09): the sender gets the same (no) answer whether the recipient is online, offline or unknown. Department scoping of direct META is NOT applied, because a direct `Send` between enrolled devices is not scoped either and a cross-department 1:1 chat has to work. Group META keeps its membership check. This closes P2-5 as PARTIAL by owner-visible decision, not as fixed.
10. **Password changes are rate limited** (REV-10): a separate key `pw:<user>`, reserved before the check; 429 with `Retry-After`.
11. **A signer cannot be silently replaced** (REV-11): the first signer needs a fresh authenticator code; replacing an existing one needs a recovery code (403 `ERR_SIGNER_EXISTS`); new recovery codes need step-up `recovery.regenerate`.
12. **Secrets stay out of the Docker context and the log** (REV-12, REV-13): `.dockerignore` uses `**/` patterns, a compose example binds to loopback, and a `logback.xml` plus `LogHygieneTest` keep setup tokens, TOTP seeds, passwords, session tokens and recovery codes out of the log.
13. **Step-up binds the value** (REV-14): `settings.change` signs `<key>=<value>`; a confirmation for another value is refused.
14. **Audit chain v2** (REV-15): length-prefixed canonical fields; rows written before are still verified in the old form (`chain_ver` column). Unauthenticated audit budget lowered to 20 per minute.
15. **Root trust and delegation order** (REV-16): the hub persists the highest delegation serial it ever accepted and refuses an older one after a restart; `FLASH_REQUIRE_ROOT_PIN=true` refuses the first delegation unless `FLASH_ROOT_FINGERPRINT` is set; adopting an unpinned root logs `TRUST ON FIRST USE`; `keytool issue-delegation` never reuses a serial even when its serial file is lost (it also reads the `*.cert` files next to the root key). The serial file is written BEFORE the certificate on purpose: a crash wastes a number, it never reuses one.
16. **The REST surface is checked** (docs round): `docs/protocol-routes.txt` is the list of every route the running server answers; `RouteTableContractTest` fails when code and list differ, so a new route cannot be undocumented again.

### Alternatives considered
- **Scope direct META by department.** Rejected: it would be inconsistent with unscoped direct Send and break cross-department 1:1 chat.
- **Trust `X-Forwarded-For` through a boolean.** Rejected (REV-01): a flag cannot say WHICH peer is a proxy; the allow-list can.
- **Encrypt only secrets in the database (done) vs the whole database now.** The whole-database option needs a native sqlite-jdbc-crypt driver on every deployment target; deferred, see Proposed.
- **Make the first delegation always require a pin.** Rejected as a default for now: it makes first-run provisioning harder; offered as `FLASH_REQUIRE_ROOT_PIN` and recommended for production.
- **Writing the serial file after the certificate.** Rejected: a crash between the two would reuse a serial, which is worse than skipping one.

### Proposed (awaiting the owner; nothing below is built)
- **P-1 Public `/api/v1/revocations`** exposes every revoked device id to anyone who can reach the hub (rate limited per address). Option: serve `SHA-256(serverId | deviceId)` and let a device compare its own hash. Wanted before GA; changes the client and the list format (`docs/protocol.md`).
- **P-2 The owner's phone as the step-up signer** for `delegation.install`, `admin.create`, `device.revoke` and signer replacement, so a stolen console session plus a stolen browser key is not enough.
- **P-3 Default of `FLASH_REQUIRE_ROOT_PIN`** (false today) and whether the first delegation should also need a TOTP confirmation in the console.
- **P-4 Whole-database encryption** through sqlite-jdbc-crypt (the willena driver is already preferred in `resolveDriverClass`): packaging, key hand-over and a migration of an existing database.
- **P-5 META scope** (decision 9) and **multi-device per user** remain as they are until the owner decides.
- **P-6 `core:messaging` callers** should pass `from` to `acknowledgeReceipt` (REV-08 is additive; today the client acknowledges without it, which is safe but deletes the oldest row, not the named one).

### Consequences
- Operators must back up `data.key` apart from the database, and set `FLASH_TRUSTED_PROXIES` when a proxy is used.
- An administrator who loses the console key and the authenticator device needs a recovery code to install a new key.
- Nothing here is device-verified; tests `HUBR-01`..`HUBR-08` in `docs/testing/TEST-BACKLOG.md` section 4zzzzzz.

### Revisit when
The owner answers P-1..P-6, a deployment behind a real proxy shows an address problem, or the audit chain needs a third hash version.


## ADR-127 addendum — owner answers to P-1..P-6 (2026-10-13)

Status of the "Proposed" list above. The owner answered on 2026-10-13; the answers are final until the owner reopens them.

| # | Question | Owner answer | Where it went |
|---|---|---|---|
| P-1 | Public `/api/v1/revocations` lists revoked device ids | **Leave as it is.** Hashed ids (option B) is a later improvement. | `docs/FUTURE-OPTIMIZATION.md` FO-13 |
| P-2 | Who may be the step-up signer | **Browser key only.** The phone as co-signer for delegation install, administrator creation and signer replacement (option C) is later. | FO-14 |
| P-3 | Default of `FLASH_REQUIRE_ROOT_PIN` | **Recommendation accepted:** `true` in the production Docker image (`server/Dockerfile`), `false` for a bare `installDist` / `:server:run` start. In the image, enrolment stays closed until `FLASH_ROOT_FINGERPRINT` is set; a local trial uses `-e FLASH_REQUIRE_ROOT_PIN=false`. The compose example carries a `FLASH_ROOT_FINGERPRINT` placeholder. | `server/Dockerfile`, `server/docker-compose.example.yml`, `docs/security.md` |
| P-4 | Whole-database encryption | **Not now** (implementation is long, and the owner wants to test). Sealed TOTP seeds stay. Small option (encrypt the mailbox payload column only) and the large one (sqlite-jdbc-crypt) are recorded. | FO-15 |
| P-5a | Scope direct META by department | **No: leave it unscoped.** The owner wants inter-department messaging. A department boundary on direct `Send` or `Meta` would break it, so none is added. | this ADR, protocol.md REV section |
| P-5b | Several devices per member | **Deferred.** One device per member stays. | FO-16 |
| P-6 | `core:messaging` passes `from` to the acknowledgement | **Done 2026-10-13.** `HubRelayPort.recvAck(id, from)`; `HubChatBridge` passes the sender of the message it confirms. Test `theAcknowledgementNamesTheSenderOfTheMessageItConfirms`. | `core/messaging/.../hub/` |
| (CLI-16) | Hub-only group roster and secret | **Direction A is chosen** (the hub is the only holder of a group's roster and secret; members fetch them sealed). **NOT built**: ADR-123 is still PROPOSED and A waits for group sealing; until then the current behaviour (option C) stays. | FO-17, ADR-123 |

### Known issue found while doing P-6 (NOT fixed)
`HubChatBridge` keeps a `seen` set keyed by message id only. A second sender that reuses an id already seen is treated as a duplicate and dropped (id squatting: an enrolled device that predicts or copies another device's message id can suppress that message at a receiver). The hub side already scopes acknowledgements by sender (REV-08); the client side has to key `seen` by `(from, id)`. Recorded as ERROR-396 and FIXED the same day: `seen` is keyed by `(from, id)`.

## ADR-128 — Extract the enterprise hub client into `:core:enterprise`

### Status
ACCEPTED by the owner on 2026-10-13 ("do be extract core enterprise"). The step-by-step plan for the implementer is `docs/plans/CORE-ENTERPRISE-EXTRACTION-PLAN.md`. Not started when this entry was written.

**IMPLEMENTED 2026-10-13** in the working tree by another agent following the plan; verified and committed by Claude Sonnet 5.5. Result: 35 files moved, 3 new lock files, `ModuleBoundaryTest` (3 tests). Test counts reconcile with the pre-move baseline: network jvm 443 + enterprise jvm 115 + network android 489 + enterprise android 41 = 1088 (was 1088 in `:core:network`); `:core:messaging:jvmTest` 215, `:ui:chat:jvmTest` 425 and `:server:test` 414 (411 + 3 new) pass, except `E2eRelayContractTest.aReconnectStormLeavesExactlyOneLiveSession`, which turned out to be a real race that existed before the move and is fixed in ERROR-397 (it failed 2 of 5 runs, now 5 of 5 pass; main code diff of the moved relay client is only the package line and the `withRadioLock` -> `withRelayLock` rename). `:server:installDist`, `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin`, `:sample:consumer-granular:compileReleaseKotlin` compile; publish dry run produces `core-enterprise`, `-android`, `-jvm` and the jvm POM lists hub-protocol, security, common, not network. Server main has no `:core:enterprise` on its runtime classpath. `:core:network` has no own dependency on hub-protocol (it still reaches it transitively through `:core:security`, which exposes it). Not device-verified: `EXTR-01`.

### Context
The hub client (enrolment, directory, relay client, trust stores, sealing key manager, hub session) lives in `:core:network` under the packages `com.transfer.flash.core.network.enterprise` and `...network.relay`: about 3,000 of 10,000 common lines. Every consumer of the LAN mesh (and the `consumer-granular` sample, which depends on `:core:network` alone) therefore carries the enterprise client, `:core:hub-protocol` and Ktor client relay code even when it never talks to a hub. The two halves have no code dependency on each other: nothing else in `:core:network` references the enterprise or relay packages, and the moved code references `:core:network` in ONE place only (an internal `withRadioLock` helper used by `FlashRelayClient`).

### Decision
Create the KMP module `:core:enterprise` (artifact `core-enterprise`, same plugin set, `explicitApi()`, android + jvm targets as `:core:network`) and move into it the hub client code and its tests. Rename the packages to `com.transfer.flash.core.enterprise` (+ `.relay`) so one package belongs to exactly one module. `:core:network` keeps the LAN mesh, radio and discovery transports and no longer depends on `:core:hub-protocol`. `:server` main must not depend on `:core:enterprise` (only its tests do, as they drive a real client against a real hub).

### Alternatives considered
- **Keep one module** and only document the boundary. Rejected: the boundary is already clean, and a consumer who wants only LAN pays for the hub client.
- **Move only the relay client.** Rejected: enrolment and relay share the trust store, the credentials and the session state.
- **Keep the old package names.** Rejected: a split package across two modules makes `internal` and `explicitApi` rules per module harder to read and hides which module owns a class.

### Consequences
- A mechanical import rewrite in app, desktop, `core:messaging`, `ui:chat`, `core:engine` (api re-export) and server tests; a published artifact and a line in `jitpack.yml` / README.
- Do it BEFORE multi-device work (FO-16) so that work happens in the right module.
- Acceptance and verification commands are in the plan; nothing is device-verified by this move, which changes no behaviour.

### Revisit when
A second client (for example a Linux or iOS host) wants the hub client without `:core:security`, or the hub protocol gets a v3.


## ADR-129 — The hub's revocation list names devices by hash (FO-13; list v3)

### Status
ACCEPTED 2026-10-14 (the owner asked for FO-13 to be built). Unit-tested; not device-verified.

### Decision
`RevocationList` version 3 (signed tag `flash-revocation-v3`) carries `audience` (the organisation's root fingerprint, uppercase hex) and `revokedDeviceHashes` (a set of `deviceHash(audience, deviceId)` = lower-case hex of `SHA-256("flash-revoked-device-v1" | audience | deviceId)` in `AuthCanonicalWriter` form). Serials and delegation serials stay numbers. A device checks `revokesDevice(deviceId)` (it hashes its own id). The root signature still covers every field. SHA-256 is a pure-Kotlin implementation in `:core:hub-protocol` (`Sha256.kt`, tested against the JDK and the NIST vectors) because the module does no platform cryptography.

### Context
`GET /api/v1/revocations` is public so a revoked device, which cannot log in, can still see its own revocation. The v2 list printed every revoked device id. Option B of ADR-127 P-1 was parked; the owner brought it back on 2026-10-14.

### Alternatives considered
- Keep plain ids (ADR-127 P-1 as it was): rejected now that the owner asked for the privacy.
- Per-hub salted hash: rejected; the audience (root fingerprint) already separates organisations and the client knows it.
- A bloom filter or a keyed hash with a secret: rejected; the device would need the secret, or false positives would revoke strangers.

### Consequences
- Device ids are guessable (UUID-like but also operator-chosen names), so the hash hides the list from a casual reader, not from someone who already has a candidate id. Documented in `docs/security.md`.
- An **older client ignores a v3 list**: it neither crashes nor sees its own revocation by id (a serial entry still works). Hub and clients must be updated together; the hub republishes its plain list as v3 at startup (`republishPlainList()`) and `keytool revoke` writes v3.
- `RelayFrameRouter` and the REST bearer use `list.revokesDevice`; the old `revokedDeviceIds` is still read for lists a hub already holds.

### Revisit when
A client older than this change must keep working against a new hub; then the hub would have to serve both lists (a version query parameter).

---

## ADR-130 — Mailbox payloads are encrypted at rest with the hub's data key (FO-15a)

### Status
ACCEPTED 2026-10-14. Unit-tested (`SealedMailboxPayloadTest`, `MailboxQueueTest`; a mutation check killed three tests); not run against a real restart on a device or a long-lived database.

### Decision
`mailbox_envelopes.payload` is stored as `nonce(12) || ciphertext+tag` (AES-256-GCM) under the hub's existing `data.key` (`SecretBox`), with the additional authenticated data `mailbox_envelopes.payload|<len>:origin|<len>:target|<len>:msgId`. A row moved to another envelope or another database therefore does not open. Migration v3 adds `sealed INTEGER NOT NULL DEFAULT 0`; `payload_bytes` keeps the plain size so the byte quota is unchanged. At start `sealLegacyPayloads()` seals the old plain rows in batches of 200. A row that cannot be opened is logged, skipped and **not deleted**.

### Context
The payload column holds what hubs relay for offline devices (already sealed end to end for direct messages, ADR-121/126, but readable for group traffic until ADR-123 lands). A copied database file should not hand that over.

### Alternatives considered
- Whole-database encryption (SQLCipher or file-level, FO-15b): still deferred by the owner; a native dependency and a build change for every platform.
- A second key just for the mailbox: rejected; `data.key` already protects the TOTP seeds, one key to back up.
- Encrypt only on a flag: rejected; `SecretBox.None` exists for tests, a running hub always has a key.

### Consequences
- **Losing `data.key` loses the queued mailbox** (and the sealed TOTP seeds, as before). Back it up apart from the database.
- Metadata (origin, target, ids, times) stays readable in the database; only payload bytes are sealed.

### Revisit when
FO-15b (whole file), or when the mailbox must be searchable.

---

## ADR-131 — The administrator's phone co-signs the most dangerous console actions (FO-14)

### Status
ACCEPTED 2026-10-14. Server, console, hub client and phone/desktop settings dialog are built and unit-tested; the whole flow is NOT device-verified (tests `APPR-01`...`APPR-08`). No QR transport yet (the console shows six digits; the phone lists pending requests by polling).

### Decision
An administrator may bind **one approver phone** (an ACTIVE enrolled device with the admin role) to the account. Once bound, these console actions need that phone's signature as well as the browser key's: `delegation.install`, `admin.create` (also covers password and TOTP resets), `admin.signer.replace` and `approver.change`. `admin.signer.replace` is **phone only** (the browser key may be the one that was lost).

Flow: the console asks for a step-up challenge; the hub puts `approvalRequired` and a six-digit `approvalCode` (derived from challenge id and server nonce, `HubStepUp.approvalCode`) in the answer; the browser key signs as before; the console shows the digits and polls `GET /admin/api/v1/stepup/{id}`. The phone lists `GET /api/v1/approvals` (device bearer), shows the same digits, signs `HubStepUp.approvalBytes` (domain `flash-hub-approval-v1`, fields: challenge id, action, admin, target, nonce) with its device identity key and posts `POST /api/v1/approvals/{id}`. The hub verifies against the key in its registry and marks the challenge approved; `authorize` then consumes it. Binding the first phone needs a fresh authenticator code (plus the browser key's confirmation when the admin has one); replacing it needs the old phone; removing it needs a **recovery code** (the loss path).

### Context
ADR-127 P-2 option C. A stolen browser key plus a stolen session could take over the hub; REV-11 limited only signer replacement.

### Alternatives considered
- Push notification to the phone: rejected; no push dependency in the hub (FCM out of scope, FO list).
- QR only (console shows QR, phone scans): kept as a later transport; polling works on a LAN hub and with no camera.
- Two approvers or an M-of-N rule: rejected for now; one phone plus recovery codes covers loss.
- Fail open when the phone is unreachable: **rejected**. If the bound phone is revoked or no longer an admin device the hub answers `ERR_APPROVER_UNAVAILABLE` and the action does not happen; only the recovery-code removal unblocks it.

### Consequences
- The six digits protect against a request someone else raised: the person compares them before approving. They are not a secret.
- REV-11 interplay: with a phone bound and a signer present, `/me/console-key` and `/me/device` check the phone's approval FIRST, then the recovery code (a missing approval must not burn a code).
- A phone that approves a request it did not start is a social-engineering risk the UI wording warns about ("only approve a request you started yourself").
- The approval state lives in the in-memory challenge (5 minutes); a hub restart drops pending approvals, the console says "not answered in time".

### Revisit when
A QR transport, push, or a second approver is wanted; or the phone UI moves out of Settings.

---

## ADR-132 — The desktop chat database key is wrapped with the OS vault (Windows DPAPI)

### Status
ACCEPTED 2026-10-14. Unit-tested including real DPAPI on Windows (`ChatDbKeyStoreTest`, `DesktopEngineBootTest`); not tested on a profile with an existing chat history.

### Decision
`<stateDir>/chat/db-key.bin` is `0x01 || vault.protect(passphrase bytes)`, using the same vault the hub sealing key already uses (`hubSealingVault`: DPAPI on Windows, owner-only file elsewhere). A legacy plain 64-hex file is read once and rewritten protected; **the passphrase does not change**, so the database is untouched. If unprotect fails the engine throws and **never replaces the file** (a replaced key would make the history unreadable for ever); the existing `runCatching` around the database open degrades chat instead. A missing or empty file mints a new key.

### Context
The key sat in a plain file next to the database, so a copied profile or a backup opened the history.

### Consequences
- Protects against a stolen copy, another Windows user and backups. It does **not** protect against malware running as the same user (DPAPI by design).
- Moving the profile to another Windows account makes the history unreadable; it is not lost, it needs the original account.
- Linux and macOS use the vault's owner-only file path; unchanged strength there.

### Revisit when
A password or biometric unlock for desktop is wanted, or the Android key storage is audited the same way.

## ADR-133 — The Flash Hub tab replaces Transfers for enrolled devices; Transfers moves to the chat-list top bar (UI-058)

### Status
ACCEPTED as a direction by the owner 2026-10-14 (decision D1). Design DESIGNED (`docs/ui/flash-hub-tab.md`). **Nothing is built.** Plan: `docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md`.

### Decision
- The bottom bar / rail / desktop sidebar is a function of enrolment: enrolled = Chats · **Hub** · Nearby · Settings; not enrolled = Chats · Transfers · Nearby · Settings (unchanged).
- When enrolled, Transfers is a **pushed** destination opened from an icon button in the chat-list top bar (with an active-transfer indicator). The "Browse department groups" top-bar action moves into the Hub screen (Groups segment).
- The Hub screen has four segments: News (announcements), People, Departments, Groups.

### Context
The owner wants the organisation to be a first-class place (colleagues, announcements, departments) once a device joins one, without adding a fifth tab. Today the organisation lives only in Settings → Hub and a Browse sheet; `fetchEnterpriseDirectoryMembers` has no UI caller.

### Alternatives considered
Five tabs; hiding Transfers visually while keeping `TabOrder` static; a separate Announcements tab; swipeable nested tabs. All rejected (`HUB-TAB-IMPLEMENTATION-PLAN.md` §8).

### Consequences
`FlashNavigationMath` gains enrolment-aware tab functions and a stack normaliser; every `Transfers` navigation site needs review; saved navigation state must survive enrolment changes; new icons are required (UI-002).

### Revisit when
The owner wants multi-organisation (FO-18) or more tabs.

## ADR-134 — An organisation trust tier (ORG) lets enrolled colleagues chat on the LAN without the hub

### Status
ACCEPTED by the owner 2026-10-14 (D8 goal, D10 revocation gossip between phones, D11 "all recommended": the mechanism, the 30-day default lease, Layer 2 later). **Nothing is built.** Plan: `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md`.

### Decision (proposed)
- A new predicate `isOrgPeer(deviceId)` is true when a persisted, root-verified **member statement** (`OrgMemberStatement`, signed by the hub's delegate key, delivered by `GET /api/v1/directory/sync`) names the same identity-key fingerprint the live session was accepted under, and the **lease** (`lastSuccessfulSync + offlineGraceDays`, default 30, admin-set 1–365) is valid.
- `isTrustedPeer` keeps its meaning (paired). Every call site is classified in a policy table; PTT, admin, vouching, group creation and v2-group owner trust stay paired-only. An organisation peer may chat, send receipts/typing/reactions, offer files (the receiver still accepts) and ring (never auto-answered).
- No TLS change: the TLS layer already accepts first-use peers and records a pin (verified by reading `TofuPinVerifier` and `Flash.kt:277`), so the gate is the application. A TOFU pin that disagrees with the statement is replaced by the statement's fingerprint; a PAIRED or VOUCHED pin is never replaced.
- Layer 2 (membership proof in the handshake) and revocation gossip are later, optional phases.

### Context
Owner (2026-10-14): a user who paired or joined online must still be able to chat with a colleague on the work Wi-Fi when the server or internet is down. Today only previously paired colleagues work offline; hub groups are silent on the LAN (CLI-16).

### Alternatives considered
Widen `isTrustedPeer`; write ORG pins into the trust store; custom TLS certificate extension; sync per-member `DeviceCert` chains (7-day expiry makes offline use impossible); dial every colleague. See plan section 8.

### Consequences
A revoked employee stays trusted by an offline phone until its next sync, the lease ending, or gossip; this is documented in `docs/security.md` and in the UI. The hub operator can already vouch for any device (ADR-120); this adds no new operator power. A colleague in a hidden department is not in outsiders' snapshots (Layer 2 covers it later).

### Revisit when
Device tests show the mechanism is too weak or too strong, or the owner changes the lease default.

## ADR-135 — Hub groups work on the LAN through a hub-signed roster statement (replaces the FO-17 secret-escrow proposal)

### Status
ACCEPTED by the owner 2026-10-14 ("all recommended": the hub-signed roster statement, option A; the FO-17 secret escrow is dropped). The review gate demanded by ADR-123 is cleared. Nothing is built.

### Decision (proposed)
The hub signs `GroupRosterStatement(groupId, name, departmentId, version, members, admins, issuedAt, validUntil)` (tag `flash-hub-roster-v1`, delegate key + chain). Devices persist it and, for hub-linked groups only, accept group frames from a sender who is an ORG peer and a member in the latest valid statement. Statements are portable and self-verifying, so peers may forward a higher version (`HubRosterOffer`, cap `hrs1`). Group messages are not end-to-end sealed on the hub relay path (as today): sent directly between phones on the LAN they are encrypted in transit by the existing TLS channel with pinned identities and never reach the hub; through the hub they are readable by it, and the UI says so only for that path. Offline group chat is supported up to 200 members.

### Context
Option (a) of ADR-123 (vouched introductions) and FO-17 (roster/secret through sealed hub messages, which needs group sealing that does not exist). A statement needs no secret, no new key distribution and no admin identity key (console-created groups have none).

### Alternatives considered
Secret escrow on the hub (the hub holds secrets; GM bundle issuance needs an admin identity key); per-group sender keys through sealed messages (needs group sealing); accepting every hub-certified identity for any group (no membership at all).

### Consequences
Removal reaches offline phones at their next sync or by gossip; until then a removed member's frames are accepted by phones that have not seen the newer version (bounded by `validUntil`; documented residual risk). FO-17 is marked absorbed by this decision if accepted.

### Revisit when
Group sealing is designed (then sealed groups may replace the unsealed hub group path).

## ADR-136 — Department heads: scoped console logins, scoped admin API and invite roles

### Status
ACCEPTED as direction by the owner 2026-10-14 (D3, D5, D7); details accepted 2026-10-14 (D11: a head cannot create another head, rename a department or revoke a leaver in v1). **Nothing is built.** Plan: `docs/plans/HUB-DEPARTMENT-HEADS-AND-INVITES-PLAN.md`.

### Decision
- A new console role `DEPT_HEAD` bound to one or more departments (`admin_user_departments`), alongside `ORG_ADMIN`. A department may have several heads (device heads already can: `adminDeviceIds`).
- The scope is re-read from the database on every request; every `/admin/api/v1/*` route has a declared policy (default deny, enforced by a test); objects outside the scope answer 404.
- Invite roles in the console: **Employee** (`member`) and **Department head** (`dept_admin`, org admin only); creating a department offers "invite its first head". A head invites only employees to their own department.
- Heads cannot create logins, departments, revocations, settings, and cannot see the audit log in v1.

### Context
Today every console login is `ORG_ADMIN` (the `role` column exists but is never compared) and no admin endpoint filters by department.

### Alternatives considered
Per-department logins implemented only in the client; a generic permission matrix (too large for the need).

### Consequences
Every admin route needs a policy; console navigation adapts; the Hub tab shows the "Department head" badge from the device certificate.

### Revisit when
Heads need audit views, revocation rights or department renaming.

## ADR-137 — Directory privacy option C: an organisation-wide directory with hidden departments

### Status
ACCEPTED as direction by the owner 2026-10-14 (D2). The keys point was **answered by the owner on 2026-10-14 (D9): public keys (identity and sealing) of non-hidden departments are org-visible; hidden departments' keys only to their members, by known-id lookup and in the Layer 2 handshake.** Questions 9 and 11 answered 2026-10-14 (D11): no Layer 2 lookup of hidden members by name for outsiders, no per-department presence switch in v1. **Nothing is built.** Plan: `docs/plans/HUB-DIRECTORY-PRIVACY-PLAN.md`.

### Decision
- Names, departments and online dots are visible org-wide, except departments an org admin marked **hidden** (not listed, not searchable, counts withheld, groups hidden; visible to their own members and admins).
- Hidden means directory listing only: direct messaging by known id stays unscoped (ADR-127 addendum), local-network device names may still be seen, administrators and the hub operator see everything. The docs and console say this.
- One server function `DirectoryVisibility` decides every directory read and the offline-trust sync. New routes `GET /directory/people` (paged, searchable, no keys) and `GET /directory/people/{deviceId}` (known-id lookup).

### Context
Today a member sees only their own department; the client directory cache therefore cannot recognise a colleague from another department.

### Alternatives considered
A: everyone sees everyone; B: own department only (today).

### Consequences
`HubPeerDirectory` standing improves for cross-department senders (still to be proven, Plan B step B0.6). A per-department presence switch is optional (roadmap question 11).

### Revisit when
The owner answers questions 10 and 11, or a department-level privacy need beyond "hidden" appears.

## ADR-138 — Announcements are plain text stored on the hub, posted by admins and department heads

### Status
ACCEPTED as direction by the owner 2026-10-14 (D5: plaintext, server-side search, console previews; posters = org admins and department heads for their department). Details accepted 2026-10-14 (D11: title 120 / body 4,000 characters, no retention by default with an optional expiry per announcement, notifications on for company-wide and own-department announcements, title only). **Nothing is built.** Plan: `docs/plans/HUB-ANNOUNCEMENTS-PLAN.md`.

### Decision
Table `announcement` (+ FTS5 search) on the hub; device REST under `/api/v1/announcements*` authorised from the verified certificate role and department; console routes scoped by `ConsoleScope`; a changes feed (`since=seq`) is the source of truth and a content-free relay frame `AnnouncementEvent` (capability `ann1`) is a best-effort nudge. The app caches announcements in Room and notifies with the title only. Plain text only (no markdown or HTML). Not queued when offline.

### Context
There is no announcement feature anywhere. The owner chose plaintext so that the server can search and the console can preview.

### Alternatives considered
Sealed (end-to-end) announcements (parked FO-24); sending the text inside the relay frame; FCM push (rejected earlier).

### Consequences
The hub operator and administrators can read all announcements; the UI says so. Background notification delivery is only as reliable as the hub connection (no FCM).

### Revisit when
Sealed announcements, acknowledgements or attachments are wanted (FO-19..FO-26).

## ADR-139 — Hub presence is read from the hub's own people listing, and the relay is a named transport

### Status
BUILT 2026-10-19 (unit-tested, NOT device-verified). Closes the dormant "hub presence not surfaced" item in `logs/handoff.md`.

### Decision
1. **Presence source.** A client enrolled in a hub learns which colleagues the hub currently has a live relay session for by polling the existing org-wide people listing (`GET /api/v1/directory/people`) and reading `PersonDto.online`. No new route, no server change, no wire change. The poll runs every 15 s (`HubPresenceDefaults.POLL_MS`) and is bounded to 4 pages × 500 people. The listing's limiter (`HubRateLimits.directoryPeople`: burst 30 then 1 per 2 s per device) leaves a 15 s poll ample headroom.
2. **"Learned nothing" is not "nobody is online".** A round that fails, or a hub too old to serve the listing, yields `null` and the previous set is kept, so a transient hub error never flips the whole organisation to Offline. An enhanced privacy response with no visible presence also yields nothing rather than a false offline.
3. **The relay is a transport, not a fallback.** A peer whose only live path is the hub is `Online` with `FlashNetworkTransport.Relay` (the banner reads "Relayed"), and its typing indicator is admitted. Precedence in the header is: typing (only while reachable) > live LAN session > hub session > reconnect grace > discovery sighting > offline. The hub-online peer is removed from the "seen by discovery, no link" set, because we do hold a link to it.
4. **The Hub tab uses the same set.** `HubTabSources.hubPresence` (already designed for this, previously unwired in both hosts) is fed the same flow, so a person is `HubReachability.HUB` when the hub reports them online, `OFFLINE` when the listing names them and the hub does not (or when presence is hidden), and `UNKNOWN` when the hub could not be read.
5. **Client-announced presence is rejected.** `FlashRelayClient.sendPresence` stays uncalled: the server never originates ONLINE/OFFLINE, it only forwards client-sent metas, and the directory's own privacy model says the hub reports presence. Adding a client announcement would have been a new wire surface with no reader.

### Context
The owner deployed a test hub, enrolled two devices (invites with **no department**) and chatted 1:1 over the hub from different networks (ERROR-741). The messages arrived, but the colleague read Offline and their typing indicator never showed. Presence in every host came only from live LAN sessions (`network.activeSessions`), and the hub's own presence — already served as `PersonDto.online` from the live relay pool — was never read. The typing gate then discarded the (already-collected) typing indicator because the peer was not "online".

### Alternatives considered
- **Client-announced presence over the relay** (`FlashRelayClient.sendPresence`, a `Presence` meta): rejected — the server never originates presence and the documented privacy model has the hub reporting it; it is also a new wire surface.
- **Reusing `HubPeerDirectory`'s cached member rows** (they carry `online`): rejected — a 10-minute TTL with a 30 s minimum refresh is far too stale for presence (and its bulk listing has a separate defect, recorded below).
- **A dedicated presence push frame**: rejected — a wire-shape change needing an ADR of its own and a server change, for information the REST listing already carries.
- **Presence from the relay connection pool on each device**: not possible — a device holds a session with the hub, not with its colleagues.

### Consequences
- The chat header, the chat list and the Hub tab agree on a hub-only colleague: Online, relayed, typing shown.
- A colleague the hub reports offline is Offline, not "seen by discovery" — the honest answer while the hub is the only path.
- Group-chat member presence and the call screens remain LAN-only; the hub set is not yet applied there (recorded as a follow-up, not a defect).
- A 15 s poll is one extra HTTP request per enrolled device every 15 s; negligible against the hub's limiter and the existing directory sync.
- **Recorded, not fixed (needs an owner decision):** `HubPeerDirectory.refreshLocked()` calls `fetchDepartmentMembers(url, "")`, and the server treats a blank `departmentId` as missing scope (`400 MESSAGE_NEEDS_SCOPE`), so the bulk member listing always fails and standing falls back to per-id lookups. Fixing it is a small change (skip the blank fetch, or list by the device's own department) but it touches Plan B Layer 2, so it is left for the owner.

### Revisit when
Group-chat and call presence want the hub set too, or the hub offers presence in the relay handshake (which would remove the poll).


<!-- folded 2026-10-15 from logs/streams/S2-server/decisions-additions.md; ADR numbers kept as written there -->
## Folded from stream S2-server (decisions-additions)

# S2-server decisions additions (fragment; integrator folds into `docs/decisions.md`)

## ADR-137 — Directory privacy option C (status: direction ACCEPTED, keys point D9 ACCEPTED 2026-10-14; now BUILT by S2/A2, needs verifier ACCEPT)

### Decision
Org-wide names/departments/online dots; per-department `hidden` flag for
listing-only secrecy. Identity + sealing keys org-visible for non-hidden
departments (`DIRECTORY_KEYS_ORG_WIDE=true`, D9 2026-10-14), insiders-only for
hidden ones (via membership, known-id lookup, Plan B Layer 2 handshake).

### Built (A2, 2026-10-14, branch `agent/S2-server`)
- `Department.hidden` persisted (directory migration 3), admin API + console
  toggle with the plan's exact help text, audit `directory.department.hidden`,
  epoch bump on every change (full resync).
- `DirectoryVisibility` is the single decider (all reads route through it;
  architecture test proves it).
- New routes `GET /directory/people`, `GET /directory/people/{id}`; widened
  `departments`/`members`/`groups`/sync; `docs/protocol.md` "Directory privacy
  v1"; `docs/protocol-routes.txt` regenerated.
- Client `fetchPeople`/`lookupPerson`, standing rules, hub-too-old fallback
  sentence (exact).
- Owner answer recorded: §3.5 presence switch NOT built in v1 (D11).

### Deviations from the plan text (with reasons)
- `DepartmentDto` counts stay non-null Ints; hidden outsiders get `0` (plan said
  null) — null would break old clients' non-null decode. Hidden rows are
  filtered before counts anyway.
- `/members?departmentId=<missing>` changed 403→404 (same body as hidden) to
  remove the existence oracle.

### Revisit when
Owner flips `DIRECTORY_KEYS_ORG_WIDE` (one const); per-department presence
switch if asked.


<!-- folded 2026-10-15 from logs/streams/S3-hubtab/decisions-additions.md; ADR numbers kept as written there -->
## Folded from stream S3-hubtab (decisions-additions)

# S3 decisions (numbers from the reserved range 159-168)

## ADR-159 — Enrolment lives in the navigation state and is saved with it
**Decision.** `FlashNavigationState` holds `hubEnrolled`, saved as a marker entry `@hubEnrolled|0/1` in the existing list saver; `normalised(entries, hubEnrolled)` rewrites any stack for the current tab set. Hosts call `updateHubEnrolled` only once the engine is `ready`.
**Why.** Enrolment is unknown until the engine boots; restoring a Hub position on process death must not be lost or crash. Alternatives: host-held flag passed into every call (rejected: nine call sites, easy to forget); persisting in settings (rejected: a second source of truth).
**Revisit when** a cold start of an enrolled device must show Hub on the first frame (limitation: it shows the old tab set until ready).

## ADR-160 — ACCEPTED (owner, 2026-10-16, changed): what counts as an "active" transfer in the top-bar indicator
Offered, Queued, Transferring, Verifying **and Paused** count; Completed, Failed, Cancelled do not. The owner chose to count Paused (a paused big file is unfinished work). The original proposal excluded Paused; superseded. One shared rule, `FlashChatListTopBarMath.countsAsActive`, used by both hosts (test `FlashChatListTopBarMathTest`). Device check HUBT-37.

## ADR-161 — Transfers entry points go through `openTransfers()`
Every host path that opens Transfers (notification, shortcut, tray, Ctrl+2, top-bar icon) calls `FlashNavigationState.openTransfers()`: selects the tab when unenrolled, pushes when enrolled. No host calls `selectTab(Transfers)` any more.

## ADR-162 — Hub group rosters on the LAN: statement, gossip and projection (Plan B B4)

### Decision
The hub signs one roster statement per group (`flash-hub-roster-v1`). Clients verify it independently, store it (Room schema 16), exchange it
over the LAN (`hrs1`, `FLASH_GHUBR`) and project it into `GroupMemberEntity` rows marked by `operationId = "hub-roster-v<version>"`, so no
column was added to the member table. The delegation chain is stored with the statement so a gossiped roster verifies offline.

### Consequences
- Removal reaches offline phones by gossip or sync; an expired window closes the group.
- Server group version is not strictly +1 (directory version at last change).
- More than 200 members: not supported offline. Library engine `Flash.kt` is not wired.

### Revisit when
A second source column is needed, or group size 32 / end-to-end group encryption (FO-27) is built.

## ADR-163 — Announcement feed paging, pin-limit status and roster version after a full resync

### Decision
1. A full announcement feed page continues from its last item (`nextCursor`); the hub's highest seq is only used when there is no next page.
2. Pin limit is HTTP 409 with `ERR_PIN_LIMIT` (a state conflict), mapped to PinLimit by the client.
3. A directory full resync (epoch reset, restored hub database) makes the hub's roster versions authoritative; otherwise a lower roster version never replaces a higher one (ADR-162).
4. Department heads are the device role `dept_admin` (`HubRoles.DEPT_ADMIN`); `dept_head` was never issued.

### Revisit when
Roster versions get an explicit epoch field, or announcements move to a signed form.

## ADR-164 - One-tap pairing from the company directory (Plan B follow-up)

### Decision
A person may pair with an organisation colleague without a code. `FlashChatRepository.pairColleagueFromDirectory(deviceId)` records the same local
trust as a code pairing (`trustPeer` + `markVerified`, no session key) but only while the colleague is connected right now and its live TLS key
matches its hub-signed statement (`effectiveIsOrgPeer`). Otherwise it fails with an honest sentence.

### Why
The statement already authenticates the key as strongly as the commit-then-reveal code (ADR-042), so a code adds nothing for a colleague. Pairing unlocks PTT and groups.

### Consequences
- Pairing stays local per side: the colleague must pair back for PTT or group powers.
- Stays paired until the user unpairs, even if the colleague leaves the organisation.
- No end-to-end session key until a code pairing is done; frames stay plain inside TLS.
- ORG-only members still get no PTT or group powers without pairing (ADR-134 gates unchanged).

### Revisit when
FO-27 (end-to-end encryption of hub messages) is built, or if the owner wants pairing to follow org membership.

## ADR-165 - A directly paired colleague's identity key outranks the hub directory for sealing (ADR-164b)

### Decision
`DirectoryHubSealing` takes `pairedIdentityFingerprint(deviceId)`: the fingerprint saved when this device paired with the colleague (trusted AND verified). If the hub directory serves an identity key whose SHA-256 differs, the status is `IDENTITY_MISMATCH`: nothing is sealed, sent or opened for that colleague, the bridge never falls back to readable text, and the user cannot confirm it away (unlike `KEY_CHANGED`). The notice says to meet and pair again or ask the administrator.

### Why
Sealing trusted the chain hub directory -> identity key -> sealing key. A hub that is dishonest from the first sight could pin its own keys for a colleague (TOFU). A direct pairing is an independent source of the real identity key.

### Limits (be honest in the UI and in sales talk)
- Only protects colleagues the user paired with; unpaired colleagues stay trust-on-first-use against the hub.
- A pairing made with `pairColleagueFromDirectory` (ADR-164) is anchored on the live TLS key plus the hub statement, so it adds no check against a hub that colludes with a LAN attacker; a code pairing in person does.
- Groups remain unsealed (FO-27).

### Revisit when
FO-27, or when a QR/in-person verification step is added for unpaired colleagues.

## ADR-166 - Plan B small gaps closed: revocations in the roster path, chat for admin-added hub groups, stricter roster statement

### Decision
- `HubRevocationCache` (`:core:enterprise`) holds the last verified hub revocation list in memory (never replaced by an older version; a failed fetch keeps the old one). Both hosts refresh it before each directory sync and pass `current()` to `OrgDirectorySync` and `HubGroupRosters` as `revocationsProvider`; it is cleared when the organisation snapshot is cleared.
- `projectHubRosters` creates the group chat and links it to the hub when a verified current roster names this device and no conversation exists (an administrator added the device). A removed device is not named, so nothing comes back for it. Deleting the chat while still a member brings it back on the next sync.
- `GroupRosterStatement.verify` returns MALFORMED when an administrator is not also a member (the server already folds admins into members); roster JSON nested deeper than 16 levels is refused before decoding (`jsonTooDeep`).

### Not done on purpose
Library engine `Flash.kt` (no hub sync there); the `org_group_unsealed` header (chat UI, after the Hub tab branch lands); A3 minors 7 and 10.

## ADR-170 - Hub tab memory lives in the hosts, not in Room (ACCEPTED by the owner, 2026-10-16)

### Decision
The last Hub segment, the one-time "Transfers moved" tip and unsent announcement drafts are stored by each host behind two small interfaces in
`ui/chat` (`FlashHubUiPrefs`, `FlashHubDraftStore`): SharedPreferences `flash_hub_ui` on Android (`HubUiPrefs`), `hub-ui.properties` in the state
directory on the PC (`DesktopHubUiPrefs`). Drafts are removed once enrolment is known to have ended. A host that passes nothing gets an in-memory store.

### Context
Plan A4 H9 names `hubLastSegment` "in settings". The settings store is in `core/persistence`, which this stream may not edit, and a Room schema
change for three scalar values and some draft text is out of proportion.

### Alternatives considered
- Add columns to the settings entity (Room schema step 18): rejected for this stream (ownership), also needs a migration test for three scalars.
- Keep it in memory only: rejected, a failed post's draft must survive a restart (Plan A3) and the segment must be remembered.

### Revisit when
The integrator prefers one settings home; moving it means implementing the two interfaces over the settings DAO, nothing else changes.

## ADR-171 - Hub routes enter through an explicit intent extra / launch argument (PROPOSED)

### Decision
`hub/announcement/<id>` and `hub/person/<deviceId>` reach the Hub through the intent extra `com.transfer.flash.extra.HUB_ROUTE` (Android) and
`--hub-route=<route>` (PC), parsed and validated by `FlashHubMath.routeFromArgs/parseRoute/routeDecision`. No new manifest intent filter and no
`flash://` deep link are added. A route waits until enrolment is known; when the device is not enrolled the app lands on Chats with
"This device is not part of an organisation." and the route is forgotten.

### Context
A public deep link into organisation content would be an attack surface (any app or web page could steer the UI) and Plan A4 only requires
notification and cold-start routes. The notification side (Plan A3 push) will set the extra from inside the app's own PendingIntent.

### Alternatives considered
- `flash://hub/...` filter: rejected for now, exported and spoofable; revisit if links must be shared between people.

### Revisit when
Announcement notifications ship (the sender of the PendingIntent must use the same extra), or links to people/announcements must be shareable.

### Clarification (2026-10-16, asked by the owner)
The intent extra is not a closed channel: `MainActivity` is the launcher activity and therefore exported, so any app on the phone can start it with
that extra. What keeps this safe is not the channel but the validation: `FlashHubMath.parseRoute` accepts only the two known shapes with bounded
ids, the screen opens only if the device is enrolled and shows only data already on the phone, and an unknown or hostile value is forgotten.
The difference from a `flash://` link is exposure from outside the phone: a link on a web page, in a chat or in an e-mail can be tapped by a
person who does not know what it does. If shareable links are wanted later, add them the way `flash://g/` group invites work: parse, show a
confirmation naming what will open, require enrolment in the same organisation, and never act on the tap alone. Status stays PROPOSED until the
owner answers.

## ADR-172 - The Hub tab owns its two-pane layout (ACCEPTED by the owner, 2026-10-16)

### Decision
At 840 dp and above the Hub shows list and detail side by side with its own splitter-less layout (`FlashHubBody`); below that the detail is a
sheet or a full-screen reader. It does not use the shell's shared detail pane.

### Context
The shell's detail pane is selection-driven by chat and transfer items. Reusing it would have touched both host shells in many places. The Hub
selection (person, department, announcement) is local to the Hub and is saved with the route state.

### Revisit when
The integrator wants one detail-pane abstraction for the whole shell (ADAPTIVE-UI-PLAN AD-7).

## ADR-173 — Optional Multi-Mode Hardware Radio TNC Gateway (`:core:radio`)

### Decision
1. **Dedicated Module (`:core:radio`):** All radio hardware protocols (KISS framing, AX.25 Level 2.2, serial communications via `jSerialComm` on Desktop and Android RFCOMM sockets, CSMA backoff pacing) are encapsulated in a new Gradle module: `:core:radio`. Core networking, transfer engines, and Room schemas remain completely untouched.
2. **100% Optional & Feature-Gated:** Radio functionality is strictly opt-in behind a setting in **Settings $\rightarrow$ Radio Gateway** (default = OFF). When disabled, zero background services, serial ports, or audio redirects run.
3. **Dual-Channel Radio Architecture:**
   - **Digital Data Channel (Bluetooth SPP / COM port):** AX.25 packets at 1200-baud Bell 202 AFSK for chat, cryptographic delivery ACKs and GPS beacons. (Store-and-forward voice notes were removed by the owner on 2026-10-16, see Amendment.)
   - **Analog Voice Channel (Bluetooth HFP Audio):** Streams real-time audio over the Hands-Free Profile (`VR-N76 Hands-Free HF`), keying the RF transmitter for analog FM walkie-talkie cross-patching.
4. **Five Operational Modes:**
   - **Mode 1: Field Companion** (Individual Operator - Chat & Voice Notes over RF)
   - **Mode 2: Base Station Relay** (Wi-Fi $\leftrightarrow$ Radio Mesh Bridge)
   - **Mode 3: Walkie-Talkie Voice Bridge** (Analog FM PTT Cross-Patch)
   - **Mode 4: Tactical GPS Radar** (Offline Presence & Tracking)
   - **Mode 5: All-in-One Dual Mode** (Simultaneous Data + Voice)
5. **Security Profiles:** Profile M (Tactical / Military - Encrypted, silent, rotating IDs, replay protection) by default; Profile A (Civilian Amateur - Plaintext, callsign, APRS) as selectable mode.

### Context
Physical hardware spike `BT-00` (`EXP-023`) with the Vero Telecom VGC VR-N76 confirmed that Bluetooth Classic SPP (`COM16`), KISS modulation, transmitter keying, and Bluetooth HFP audio operate reliably. 9600 baud was disproven (fixed at 1200 baud). Making the feature optional and modular in its own module keeps Flash lightweight for civilian consumers while enabling military and off-grid emergency capabilities.

### Alternatives considered
- Embedding radio code directly into `:core:network`: Rejected to prevent bloating core networking with serial dependencies (`jSerialComm`) and amateur radio/KISS protocols.
- Forcing all users to run radio discovery: Rejected; standard users don't have radios and should not suffer battery drain or Bluetooth permission prompts.

### Revisit when
Phase R0-R6 execution begins in accordance with `docs/plans/RADIO-GATEWAY-PLAN.md`.

### Amendment (2026-10-16, revision 2 of `docs/plans/RADIO-GATEWAY-PLAN.md`, still PROPOSED)
1. **The existing radio code is moved, not rewritten.** The KISS/AX.25 codecs, `ByteLink` and the serial/Bluetooth catalogues, `KissTncDriver` and pacing, the Flash radio frame and crypto, the simulator and the BT-00 diagnostics (all built 2026-10-09 in `:core:network`, ADR-101) move with their tests into `:core:radio` (package `com.transfer.flash.core.radio`), and jSerialComm leaves `:core:network`. Item 1 above ("encapsulated in a new module") therefore means a move, in one behaviour-free commit (phase R0). ADR-101's session-layer recommendation stands.
2. **Voice notes are out of scope** (owner): no recorded clips, no Codec2/Opus, no audio-note codec. Real-time digital voice stays impossible at 1200 baud. The analog walkie-talkie bridge (Modes 3 and 5) stays but only after a spike (`BT-R10`) shows the Bluetooth audio path works.
3. **Find, pair and connect** is specified (plan section 6, revision 3, owner 2026-10-16): Flash detects an already-paired radio by itself (confirmation once, then automatic reconnect) and shows a live list of nearby devices with in-app pairing, so the user does not have to open Bluetooth settings. The radio is identified by its Bluetooth address, OS pairing is not Flash trust, nothing transmits as a probe. This reverses the 2026-10-09 code decision "paired devices only, no scanning" on Android (needs `BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_CONNECT`, `createBond`). On Windows the candidate is the Win32 Bluetooth API through JNA (already shipped); unverified until spike `BT-R11`.
4. **Item 5 stays: encryption is required** (owner, military use). Profile M remains the default. Because the same radio can sit on an amateur band, where encrypted traffic is usually forbidden, the first enable asks the user once to confirm they are authorised to use encrypted traffic on the frequency; without it only Profile A is selectable. Local law was not researched.

### Owner decision (2026-10-16)
ADR-173 **as amended is APPROVED** by the owner (plan O-5 answered yes). The plan `docs/plans/RADIO-GATEWAY-PLAN.md` moves from "design only" to approved: the code phases R1..R6 are authorised, built by the streams of `docs/plans/RADIO-PROMPTS-2.md`. The owner also answered O-4 **yes** (R3 may add `FlashTransportType.BLUETOOTH`, one planner hook, one outbox port and small host hooks; nothing else in engine files) and adopted ADR-182 and ADR-183 (O-6, O-7). Hardware status: the radios are at the owner's workplace and available only later (O-2), so every test that needs a radio stays TODO until then. The regulatory caution stands: the encrypted profile is selectable only after the recorded authorisation confirmation, and the test radio's frequency (144.625 MHz) is an amateur band, so Profile M is not to be tested there.

## ADR-174 - A colleague chat becomes a pairing once both sides have written (ACCEPTED by the owner, 2026-10-16)

### Decision
Tapping a person in the Hub opens a chat without pairing (the ORG tier, ADR-134). When a 1:1 conversation with a verified organisation colleague
has messages in BOTH directions (you started and they answered, or the reverse), both devices pair automatically, with no code and no button, so
the conversation keeps working with the hub off. `RealFlashChatRepository.maybeAutoPairColleague` runs after a stored inbound text and after an
outbound text, and calls `pairColleagueFromDirectory`, which still requires the live TLS key to match the hub-signed statement and a valid lease.
A failed attempt (the colleague is reachable only through the hub right now) is retried after 60 s at most, never in a loop. Groups never trigger it.
The **Pair** button of ADR-164 stays for people who want to pair before writing.

### Context
Owner: "a user just clicks a person, it opens a chat, he sends a message, and when the receiver replies they pair and can communicate offline".
Replying is the receiver's consent; starting the chat is the sender's. Both are inside the organisation, identity verified by the hub.

### Consequence the owner should know
PAIRED is more than chatting: push-to-talk, creating groups with you, vouching and swarm serving are paired-only (PeerTrustPolicy). After one exchange a
colleague gets those too. This was accepted; a Settings switch "Pair with colleagues after a conversation" is NOT built and would be the answer if
it turns out to be too eager. Pair state is local to each device; a user can still revoke a pairing from the contact.

### Alternatives considered
- Pair on the first message from either side: rejected, one unsolicited message would pair a stranger-in-the-org.
- Keep the manual Pair button only: rejected by the owner, it adds a step to the simplest flow.

### Revisit when
A device test shows pairing fails when the first reply arrives over the hub (the pairing needs a live LAN key), or colleagues report unexpected contacts.

## ADR-175 - Keep the A1 self-disable guard (ACCEPTED by the owner, 2026-10-16)

### Decision
`POST /admins/{u}/disable` answers 403 when an administrator disables their own account. Plan A1 did not ask for it; the implementing stream added it and
the last-administrator rule is asserted at the service. The owner decided to keep it. `AdminApiTest` already reflects it.

## ADR-176 - The desktop chat list / conversation divider is draggable (ACCEPTED by the owner, 2026-10-16; answers AD-D3 for the splitter)

### Decision
On the PC at Expanded width the divider between the chat list and the conversation can be dragged (12 dp strip, 1 dp visible line, east-resize cursor),
reset to the automatic width by a double click, and moved with Left / Right arrows when focused or with the screen-reader actions "Wider chat list" /
"Narrower chat list" (24 dp steps). Limits (pure math in `FlashAdaptiveMath`, tested): list 280 to 720 dp, and the conversation keeps at least 360 dp when the
window allows it. The chosen width is remembered in `layout.properties` in the desktop state directory (`DesktopPaneLayoutPrefs`); an unreadable value means
the automatic width. Android gets no splitter (touch panes do not have one).

### Not done
The 24 dp hit area of the plan is 12 dp here (a wider strip would need a layout overlay); the side rail does not collapse below 1100 dp (AD-D3 rest);
the Hub tab's own two-pane layout does not use this divider yet.

<!-- folded 2026-10-16 from logs/streams/R-move/decisions-additions.md (integration of agent/R-move); the fragment stays as the full record -->

# Architectural Decision Additions — Stream R-move

Reserved ADR range: ADR-177 through ADR-181.

---

## ADR-177 — Separation of Hardware Radio Codecs and Transports into `:core:radio`

### Context
Plan R (Revision 3, `docs/plans/RADIO-GATEWAY-PLAN.md`) and ADR-173 mandate decoupling amateur / hardware radio framing, drivers, and platform serial/Bluetooth implementations from `:core:network`. Previously, AX.25 and KISS codecs, RFCOMM socket handlers, jSerialComm, and TNC drivers resided inside `com.transfer.flash.core.network`. This caused unnecessary coupling where `:core:network` pulled in desktop serial libraries and hardware-specific framing, violating the principle of distinct network versus peripheral radio boundaries.

Furthermore, `ReconnectPolicy.kt` was co-located in `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/resilience/`. Examination revealed `ReconnectPolicy` contains zero network-specific types; it is a general floored exponential backoff with full jitter.

### Decision
1. Create a dedicated Kotlin Multiplatform module `:core:radio` targeting JVM and Android host (`androidLibrary`), with Kotlin `explicitApi()` enabled and `-Xexpect-actual-classes` compiler argument.
2. Relocate all AX.25, KISS, TNC driver, pacing, link abstractions, and test harnesses from `:core:network` to `com.transfer.flash.core.radio` and subpackages (`kiss`, `link`, `driver`, `wire`, `sim`, `diag`, `connect`).
3. Move `jSerialComm` 2.11.4 dependency exclusively into `:core:radio` `jvmMain`.
4. Relocate `ReconnectPolicy` to `:core:common` under `com.transfer.flash.core.common.resilience`, enabling both `:core:network` and `:core:radio` to use resilient backoff without cross-module dependency.
5. Enforce strict unidirectional dependency: `:core:radio` depends only on `:core:common` and `:core:security`. `:core:network` does not depend on `:core:radio`, and `:core:radio` does not depend on `:core:network`. Verified by `RadioDependencyDirectionContractTest`.
6. Host apps (`:app` and `:desktop`) depend on `:core:radio`. `:core:engine` remains completely isolated from `:core:radio`.

### Status
ACCEPTED (2026-10-16)

---

## ADR-178 — Identity and State Model for Radio Connection and Rule 4 Prohibition

### Context
Plan R §6 and §8 define user experience, state flow, and security constraints for radio gateway connectivity. Unlike LAN or Wi-Fi Direct transports which authenticate ephemeral dynamic IP addresses, hardware radios are peripherals identified either via volatile OS port names (e.g. `COM16`, `/dev/rfcomm0`) or stable Bluetooth hardware addresses (MAC addresses). In addition, physical radios emit RF over amateur or commercial frequencies; sending arbitrary packets or ping frames causes physical transmission.

### Decision
1. **Radio Identity is Bluetooth Hardware Address**: Port names are volatile OS artifacts on Windows/Linux. The primary identifier of a remembered radio is strictly its hardware MAC address (`RememberedRadio.bluetoothAddress`). `lastKnownPortName` is preserved strictly as a Windows fallback hint.
2. **Rule 4 Liveness Transmit Prohibition**: The radio connection state machine (`RadioConnectStateMachine`) shall NEVER emit data frames as liveness probes. Transmit commands are strictly confined to user-initiated traffic or explicit diagnostic test frames (`UserRequestedTestMessage`), which requires explicit authorization at instantiation.
3. **Rule 3 Confirmation Gate**: When auto-detect finds an eligible radio candidate, the state machine enters `ConfirmCandidate` and NEVER opens serial/RFCOMM sockets or emits `OpenLink` before explicit user confirmation (`UserConfirmedCandidate`).
4. **Security Profile M Confirmation Gate**: Encrypted transmission (Profile M) requires explicit user confirmation with a strictly positive epoch timestamp (`confirmedAtEpochMs > 0`). In the absence of recorded confirmation, only Profile A (plaintext signed) is selectable.

### Status
ACCEPTED (2026-10-16)

<!-- folded 2026-10-16 from logs/streams/R-spike/decisions-additions.md (integration of agent/R-spike); the fragment stays as the full record -->

# Stream R-spike Decision Additions

These proposed Architecture Decision Records (ADRs) address questions O-6 and O-7 from `docs/plans/RADIO-GATEWAY-PLAN.md` §12. In compliance with stream rules, these are PROPOSED drafts for owner approval, using reserved numbers 182–186.

---

## ADR-182 (ACCEPTED 2026-10-16) — Windows Bluetooth Radio Discovery, Pairing, and COM Port Mapping Architecture (O-6)

### Status
ACCEPTED by the owner (2026-10-16: "adopt both, yes"). Validated by device evidence (Stream R-spike, 2026-10-16); owner runbook executed and passed.

### Context
`docs/plans/RADIO-GATEWAY-PLAN.md` §6 requires Flash desktop on Windows to discover nearby radios, pair with them without forcing the user to navigate through Windows Settings, enable the Bluetooth Serial Port Profile (SPP) service, and map the dynamically allocated virtual COM port (e.g. `COM16`) to the radio's canonical Bluetooth hardware address.

Question O-6 asks:
*How does the PC scan and pair in-app: Win32 Bluetooth API through JNA (already shipped), or a Windows Runtime (WinRT) helper?*

### Facts & Evidence Levels

1. **JNA 5.17.0 is already shipped in Flash desktop:**
   - *Status:* Verified from repo `gradle/libs.versions.toml:152` and `desktop/build.gradle.kts`.
   - Used for Windows DPAPI access (ADR-035). Requires zero new external dependencies.

2. **Win32 Bluetooth APIs exist in `bthprops.cpl`:**
   - *Status:* Verified from official Microsoft documentation (learn.microsoft.com/en-us/windows/win32/api/bluetoothapis/, checked 2026-10-16).
   - Functions:
     - `BluetoothFindFirstDevice` / `BluetoothFindNextDevice` / `BluetoothFindDeviceClose`: device enumeration and inquiry scan.
     - `BluetoothEnumerateInstalledServices`: enumerates enabled service GUIDs.
     - `BluetoothAuthenticateDevice` / `BluetoothAuthenticateDeviceEx`: performs authentication (Transparent mode with PIN, or Wizard mode).
     - `BluetoothSetServiceState`: installs/removes virtual COM port driver for SPP GUID `{00001101-0000-1000-8000-00805F9B34FB}`.

3. **Win32 API JNA Binding Verified on Windows 11:**
   - *Status:* Verified from physical execution on this machine (`:desktop:radioSpike`, 2026-10-16).
   - The native library `bthprops.cpl` loads cleanly via JNA; `BluetoothFindFirstDevice` runs without throwing errors or crashes.

4. **Deterministic COM Port to Bluetooth Address Mapping via Registry:**
   - *Status:* Verified from physical execution and inspection of Windows Registry on this PC (`HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM`, 2026-10-16).
   - Windows creates subkeys under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM\{00001101-0000-1000-8000-00805f9b34fb}*`.
   - Under `...\Device Parameters`, Windows stores `PortName` (`COM16`) and `Bluetooth_UniqueID` which directly contains the remote MAC address (`38D2000135B5`).
   - Incoming server listener ports are distinctly marked with `000000000000` (e.g. `COM7`, `COM15`), whereas outgoing device ports map to the physical radio MAC.
   - Reading this via JNA `Advapi32Util` succeeds without elevated administrator privileges.

5. **Physical Radio Inquiry & In-App Pairing Behavior on Windows:**
   - *Status:* VERIFIED BY DEVICE TEST (Stream R-spike, 2026-10-16).
   - Physical execution of active mode `:desktop:radioSpike -PradioArgs="--active --i-am-the-owner-and-the-radio-is-on"` confirmed:
     - Active Win32 inquiry scan discovered `VR-N76 [**:**:**:01:35:B5]` in 5.12 seconds, ranked at [0] as `Category: RADIO`.
     - `BluetoothAuthenticateDevice` with Wizard mode (null PIN) succeeded immediately without requiring fixed PIN entry.
     - `BluetoothSetServiceState` for SPP GUID returned `AlreadyInDesiredState`.
     - Windows assigned virtual COM ports `COM15` and `COM16`.


### Alternatives Considered

1. **Win32 Bluetooth API via JNA (Recommended):**
   - *Pros:* Zero new dependencies; uses existing JNA already licensed and packaged; provides direct access to `BluetoothSetServiceState` to explicitly install the SPP virtual COM port; registry mapping via `Advapi32Util` is verified 100% reliable on Windows 11.
   - *Cons:* Win32 Bluetooth APIs are C-based structs requiring strict memory alignment (solved in `WindowsBluetoothApi.kt`).

2. **Windows Runtime (WinRT / UWP) Device Information APIs (`Windows.Devices.Bluetooth`):**
   - *Pros:* Modern async API surface.
   - *Cons:* Requires adding a heavy Windows Runtime projection library (e.g. `windows-primitives` or C++/WinRT wrapper DLL); WinRT Bluetooth RFCOMM APIs do not automatically spawn standard virtual COM ports (`COMn`) needed by `jSerialComm` / `ByteLink`, but rather WinRT streams; violates AGENTS §3 and parallel stream rules forbidding new dependencies without approval.

### Recommendation
Adopt **Win32 Bluetooth API through JNA** as implemented in `:desktop:radiospike`. Use registry enumeration under `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` as the primary method to map virtual COM ports to remote Bluetooth addresses.

---

## ADR-183 (ACCEPTED 2026-10-16) — Android In-App Radio Discovery, Permissions, and Bonding Architecture (O-7)

### Status
ACCEPTED by the owner (2026-10-16: "adopt both, yes"). Validated by device evidence (Stream R-spike, 2026-10-16); owner runbook executed and passed.

### Context
`docs/plans/RADIO-GATEWAY-PLAN.md` §6 requires Flash Android to provide an in-app discovery list and pairing flow for the radio.

Question O-7 asks:
*Android scan/pair UI: Flash's own list (`BLUETOOTH_SCAN`, `createBond`) or the system Companion Device Manager chooser?*

### Facts & Evidence Levels

1. **Android Bluetooth Permissions on Android 12+ (API 31+):**
   - *Status:* Verified from official Android documentation (developer.android.com/develop/connectivity/bluetooth/bt-permissions, checked 2026-10-16).
   - `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` allows discovering Bluetooth devices without requesting runtime location permission (`ACCESS_FINE_LOCATION`).
   - `BLUETOOTH_CONNECT` is required to initiate `createBond()` and query `bondedDevices`.
   - Both permissions belong to the user-friendly "Nearby devices" permission group.

2. **Location Filtering Caveat of `neverForLocation`:**
   - *Status:* Verified from official Android documentation (developer.android.com/develop/connectivity/bluetooth/bt-permissions, checked 2026-10-16).
   - Official docs warn: *"If you use BLUETOOTH_SCAN with neverForLocation, some Bluetooth LE beacons might not be discovered."*
   - Classic Bluetooth discovery via `BluetoothAdapter.startDiscovery()` scans for BR/EDR devices. Empirical device testing (`BT-R11`) proves the VR-N76 is NOT dropped by `neverForLocation`.

3. **In-App Discovery and Bonding API (`BluetoothDevice.createBond`):**
   - *Status:* Verified from official Android documentation (developer.android.com/reference/android/bluetooth/BluetoothDevice, checked 2026-10-16).
   - `BluetoothAdapter.startDiscovery()` finds devices via `ACTION_FOUND` broadcast.
   - `BluetoothDevice.createBond()` initiates pairing asynchronously, notifying via `ACTION_BOND_STATE_CHANGED`.
   - The OS renders a standard PIN dialog overlay if required by the device, but the user never leaves the Flash app.

4. **Companion Device Manager (CDM) Capabilities and Constraints:**
   - *Status:* Verified from official Android documentation (developer.android.com/develop/connectivity/bluetooth/companion-device-pairing, checked 2026-10-16).
   - Introduced in Android 8.0 (API 26); requires `<uses-feature android:name="android.software.companion_device_setup" />`.
   - Dispatches a system-modal chooser dialog managed by Android; does not require runtime location permissions or `BLUETOOTH_SCAN`.
   - *Limitation:* The chooser is an uncustomizable system dialog that cannot use FlashTheme tokens, dark mode palette, pill buttons, or Flash radio ranking badges.
   - *Limitation:* `BluetoothDeviceFilter` requires matching name patterns or UUIDs. If the radio's advertising name varies across firmware releases, CDM will filter it out.

5. **Physical Handset Behavior with VR-N76:**
   - *Status:* VERIFIED BY DEVICE TEST (Stream R-spike, 2026-10-16).
   - Owner physical test on Android test phone with `RadioSpikeActivity` confirmed:
     - Discovered 9 devices without prompting for location permissions (`BLUETOOTH_SCAN` with `neverForLocation`).
     - Two physical VR-N76 radios discovered and classified as `Rank 1 (RADIO)` (`**:**:**:01:35:B5` at -47 dBm and `**:**:**:01:34:FD` at -78 dBm) at top of list.
     - Tapping "Pair" triggered `createBond()`, succeeding without external settings navigation (`Screenshot_20261016-134640.jpg`).
     - Exiting screen immediately triggered `onStop()` aborting active discovery, verified via `adb logcat -s RadioSpikeActivity`.


### Alternatives Considered

1. **Flash In-App Screen with `BLUETOOTH_SCAN` + `neverForLocation` (Recommended):**
   - *Pros:* Fully styled in Jetpack Compose using Flash theme tokens; renders custom radio classification badges (Rank 1 Radio, Rank 2 SPP); sorts bonded devices and high RSSI to top; automatically stops discovery when exiting the screen or on a 12s timer; non-disruptive UX.
   - *Cons:* Requires requesting the "Nearby devices" permission group on Android 12+.

2. **Companion Device Manager (`CompanionDeviceManager`):**
   - *Pros:* No `BLUETOOTH_SCAN` or location permission needed.
   - *Cons:* System-dialog UI breaks Flash visual consistency; device filtering is brittle for third-party radio brands; `BLUETOOTH_CONNECT` is still required afterwards to open RFCOMM.

### Recommendation
Adopt **Flash's own in-app discovery list** using `BLUETOOTH_SCAN` (with `neverForLocation`) and `BluetoothDevice.createBond()`, as implemented in `RadioSpikeActivity.kt`.
Keep Companion Device Manager as a documented fallback only if physical device testing reveals that specific OEM handsets suppress the radio under `neverForLocation`.

<!-- folded 2026-10-16 from logs/streams/R-link/decisions-additions.md (integration of agent/R-link, with integrator corrections); the fragment stays as the full record -->

# Decision Log Additions — Stream R-link

## ADR-187 — Promotion of Win32 Bluetooth JNA and Registry Port Mapping to `:core:radio`

### Decision
Promote the spiked Win32 Bluetooth API (JNA), registry BTHENUM COM-to-MAC mapper, models, and device classifier from `desktop/.../radiospike` directly into `core/radio/src/jvmMain/kotlin/.../link/windows/`. Add `libs.jna` and `libs.jna.platform` to `core:radio` `jvmMain`. Keep desktop CLI as a thin runner.

### Context
Spike BT-R11 verified that Windows 11 pairs the VR-N76 and creates virtual COM ports mapped to the radio MAC via `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM`. Production desktop needs these capabilities to resolve COM ports dynamically across OS reboots and renumbering.

### Alternatives considered
- Duplicate code between `:desktop` and `:core:radio`: Rejected (violates single source of truth and maintenance hygiene).
- JNI C++ DLL: Rejected (JNA 5.17.0 is already tested, approved in ADR-035, and pure JVM).

---

## ADR-188 — Common `RadioDeviceDiscovery` Port and `RadioGatewayManager` Facade

### Decision
Define `RadioDeviceDiscovery` in `core:radio` `commonMain` (`pairedDevices()`, `scan(timeoutMs): Flow<DiscoveredDevice>`, `pair(device): PairResult`, `openLink(remembered): ByteLink`). Implement it on Windows (`WindowsRadioDiscovery`) and Android (`AndroidRadioDiscovery`). Create a unified common facade `RadioGatewayManager` in `commonMain` that coordinates state machine, TNC driver, and stores.

### Context
Plan R §3 and §6 require a unified flow and common state model on both Windows and Android without duplicating state logic or coupling to host UI/Activity lifecycles.

### Alternatives considered
- Host-specific state managers: Rejected (leads to divergence in reconnect, scan cancellation, and confirmation rules).

---

## ADR-189 — Strict In-Memory Seam Enforcement of Rule 4 (No Test Frames on Real RF) — SUPERSEDED 2026-10-16 by the amendment below

### Decision
Enforce Plan R §6 Rule 4 and §8 mechanically within `RadioGatewayManager.requestTestMessage()`: test transmissions succeed and emit bytes to the wire ONLY when `currentByteLink` is verified to be an in-memory simulated link (`InMemoryLink`). On any real RF or serial port link, `requestTestMessage()` suppresses transmission and emits `FlashProbe.emit("radio.tx.suppressed_on_real_rf", ...)` without keying the transmitter.

### Context
Amateur radio and transmission regulations prohibit unauthorized or unattended transmissions. KISS TNCs key the transmitter upon receiving data frames. Mechanical enforcement guarantees no accidental RF transmissions during development or debugging.

---

### Amendment 2026-10-16 (integrator review; the decision above is SUPERSEDED, kept for history)
The original rule suppressed every test message on any link not recognised as in-memory, by matching the class name and a description substring ("mem", "fake"). That made the plan's "Send a test message" user action impossible on real hardware, never checked the authorisation record, and relied on string sniffing. Replacement rule (implemented in `RadioGatewayManager.requestTestMessage(text, userConfirmedTransmit)` returning `TestMessageResult`):
- Nothing is written unless: the gateway is connected and ready; the user confirmed the transmit warning in that very request; an authorisation record with a positive date exists; the text is 1..64 UTF-8 bytes; no other test message is in flight (single-flight lock).
- The link type is NOT inspected. Automated tests use in-memory or recording links; the "never transmit" rule for agents and tests is enforced by the tests, not by guessing in product code.
- The text is never logged; the probe `radio.tx.test_message` carries the result name and the length only.
Mutation-checked: removing the authorisation check, the confirmation check or the single-flight lock each fails a named test (RADLNK-U09a, U09c).

---

## ADR-190 — Atomic File and Preferences Persistence for Radio Settings (Zero Key Storage)

### Decision
Store `RememberedRadio` and `AuthorisationConfirmation` using atomic file replacement (`radio.properties.tmp` -> `Files.move(ATOMIC_MOVE)`) on Desktop and synchronous `commit()` on Android `SharedPreferences`. Store only MAC address, display name, last known port, epoch milliseconds, and operator callsign. Store ZERO cryptographic keys or frames. Unreadable or corrupted files return null without throwing.

### Context
DataStore tmp rename issues exist on Windows; standard properties files match `DesktopSettingsStore` and `DesktopIdentityStores`. Keys belong strictly to pairing/identity layers, not radio settings.

---

## ADR-191 — Android 14/15 `connectedDevice` Foreground Service Policy

### Decision
Implement pure policy `RadioServicePolicy` in `commonMain` to determine service lifecycle (`shouldServiceRun`) and honest notifications. Wire thin `RadioLinkService` in `:app` specifying `android:foregroundServiceType="connectedDevice"` and passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` on API 34+.

### Context
Android 14+ requires foreground service types and corresponding manifest permissions. `connectedDevice` is exempt from 6-hour execution limits on Android 15 while actively communicating with the external peripheral.

<!-- folded 2026-10-16 from logs/streams/R-ui/decisions-additions.md (integration of agent/R-ui, with integrator corrections); the fragment stays as the full record -->

# Architectural Decision Additions — Stream R-ui

Reserved ADR range: ADR-192 through ADR-196.

---

## ADR-192 — Decoupled Radio Gateway UI Controller Port in :ui:chat

### Context
Plan R Revision 3 §6 and §8 describe the Hardware Radio KISS TNC Gateway UI covering settings entry, "Is your radio ready?" checklist, BLE scan, device pairing, encrypted profile authorization, operating modes, and RF transmission warning diagnostics.
The UI needs to be developed in parallel with `:core:radio` without taking a direct module dependency on `:core:radio` or native serial/Bluetooth libraries.

### Decision
1. Define `RadioGatewayController` interface and `RadioGatewayUiState` strictly inside `:ui:chat` (`com.transfer.flash.ui.radio`).
2. `RadioGatewayUiState` carries only plain Kotlin types, enums, and ready-to-show status sentences/steps.
3. Provide `FakeRadioGatewayController` that implements the interface completely with deterministic simulation of all scan stages, devices, authorization checks, and failure states.
4. Provide pure JVM-testable helper objects `FlashRadioCopy` (100% of user-facing strings and accessibility labels) and `FlashRadioMath` (pure calculation of candidate ranking, next step guidance, button enablements).
5. All automated unit and UI tests run against the fake controller. Real hardware testing is preserved for manual owner verification via runbook entries.

### Consequences
- `:ui:chat` remains lightweight, multiplatform, and completely isolated from hardware serial / RFCOMM plumbing.
- Stream R-link or future integration streams can implement `RealRadioGatewayController` backed by `RadioManager` without touching any UI layouts or tests.

### Status
PROPOSED / IMPLEMENTED

## ADR-197 — Host Radio Gateway Controller Mapping and Lazy Lifetime Architecture

### Status
ACCEPTED (Stream R-wire, 2026-10-16)

### Context
Plan R §6 and §8 require connecting the `:ui:chat` presentation port (`RadioGatewayController` / `RadioGatewayUiState`) to the `:core:radio` domain logic (`RadioGatewayManager` / `RadioGatewayState`) across both Android and Windows desktop hosts.
The host integration must satisfy strict platform, power, and security constraints:
1. Under Android execution limits and background policies, the radio subsystem must not run, scan, or bind background services when the feature is turned off.
2. The user experience in Settings must show an active, honest toggle that reflects persistent state without initializing Bluetooth adapters or drivers until requested.
3. Tests must be completely hermetic on JVM with zero RF transmission or hardware access.

### Decision
1. Implement two host controllers:
   - `AndroidRadioGatewayController` in `:app` utilizing `AndroidRadioDiscovery`, `AndroidSharedPreferencesRadioStore`, `RadioLinkService`, and Android Activity Result permissions launcher for `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`.
   - `DesktopRadioGatewayController` in `:desktop` utilizing `WindowsRadioDiscovery`, `DesktopPropertiesRadioStore`, and Win32 Bluetooth Settings URI (`ms-settings:bluetooth`).
2. Lifetime Contract:
   - If the gateway master switch is stored OFF at app launch, `RadioGatewayManager` is NEVER instantiated.
   - When the user turns the switch ON, `RadioGatewayManager` is instantiated lazily, stores are read, and discovery/connection commences.
   - When the user turns the switch OFF, `RadioGatewayManager.disable()` is invoked, the foreground service (on Android) is stopped, active coroutine jobs are cancelled, and the manager reference is nullified.
3. Testability Seam:
   - Both controllers expose factory parameters (`discoveryFactory`, `rememberedStoreFactory`, `authorisationStoreFactory`, `driverFactory`, `isSwitchEnabledStored`, `setSwitchEnabledStored`, `openSettingsHandler`) enabling comprehensive JVM unit test verification without mock frameworks or real hardware.

### Consequences
- Prevents battery drain and unauthorized Bluetooth activity at boot.
- Eliminates hard dependencies on real hardware in CI / unit tests.
- Keeps shared UI modules pure and isolated from platform internals.

### Amendment 2026-10-16 (integrator audit)
- Lifetime: the Android controller is owned by a process singleton (`RadioGatewayHolder`), not by the Activity's composition scope (ERROR-681). "Created lazily" still holds: the manager object is only created when the stored switch is ON or the user turns it ON.
- The foreground service is driven through `RadioServiceTracker` and guarded calls (ERROR-682).
- The desktop switch has its own file (ERROR-683).

## ADR-200: Domain-Separated HKDF Radio Key Derivation from Existing Flash Trust Store

### Status
ACCEPTED

### Context
Plan R §7 and `RADIO-WIRE-FORMAT.md` §6 require that radio transport encryption keys must derive from the existing Flash pairing trust path (`FlashTrustStore`), rather than introducing an ad-hoc or secondary pairing mechanism. If two stations communicate symmetrically over a half-duplex radio channel with the same key and nonce space, counter collisions or nonce reuse could occur. Furthermore, rotating station tags must identify packets without exposing stationary device identity.

### Decision
1. Implement `RadioPairingAdapter` bridging `RadioPeerTrustProvider` to `RadioSession`.
2. Derive pairwise radio keys using RFC 5869 HKDF-SHA-256 with domain separation:
   - Salt: `"flash-radio-v1"`
   - Info for Send: `"flash-radio-v1/dir" || localDeviceId || recipientId`
   - Info for Recv: `"flash-radio-v1/dir" || recipientId || localDeviceId`
3. Generate distinct 32-byte AES-GCM keys, 4-byte nonce salts, and 32-byte tag keys for each direction.
4. Derive 15-minute rotating station labels and tags (`RadioWire.stationLabel`) using HMAC-SHA-256.

### Consequences
- Existing pairings in `FlashTrustStore` immediately enable radio reachability without user re-pairing.
- Mathematical impossibility of send/receive key collision between stations.
- Station labels rotate every 15 minutes, preventing RF tracking while maintaining deterministic routing.

---

## ADR-201: Outbox Port & Adapter Architecture for Radio Transport in Chat Engine

### Status
ACCEPTED

### Context
Plan R §7 specifies companion mode text messaging over hardware radio. The shared chat engine (`RealFlashChatRepository`) must support radio transport while adhering strictly to:
- LAN and Hub always take precedence over radio.
- Zero behavior changes for users who do not use or enable the radio.
- O-4 Owner Gate restrictions limiting edits to shared engine files to an outbox port and minimal hooks.

### Decision
1. Define a clean interface `RadioOutboxPort` in `:core:messaging:radio`.
2. Implement `RadioChatBridge` adapter in `:core:messaging:radio` that enforces:
   - Carrier check: radio is used ONLY when recipient has no live LAN or hub path and is radio-reachable.
   - Payload limit: UTF-8 text segmented up to 3,008 bytes (16 segments x 188 bytes); longer refused with `"Message is too long to send by radio (maximum 3,008 bytes)"`.
   - Attachment refusal: photos, files, voice notes and calls refused with `"Photos and files cannot be sent by radio"`, never queued.
   - Strict delivery states: `'sent by radio, waiting'` when driver confirms transmit; `'delivered'` ONLY upon cryptographic ACK frame receipt.
   - Offline queueing: when radio gateway is off or disconnected, messages are queued with `'radio not connected'`.
3. Provide host adapters `DesktopRadioOutboxAdapter` and `AndroidRadioOutboxAdapter` connecting `RadioChatEngine` to `RadioOutboxPort`.

### Consequences
- `RealFlashChatRepository` receives a single optional `radioBridge: RadioChatBridge? = null` parameter.
- Standard LAN and Hub messaging logic is untouched.
- Clean architectural boundary between transport protocol and chat domain logic.

---

## ADR-202: Persistent Send Counter High-Water Mark Ahead-of-Use Reservation

### Status
ACCEPTED

### Context
AES-256-GCM nonce reuse under the same key results in complete loss of confidentiality and authenticity. On mobile devices and desktop computers, abrupt process termination (battery depletion, OS task kill, crash) could occur between counter generation and disk write.

### Decision
1. Implement `PersistentRadioStore` backing `RadioCounterStore` with atomic disk persistence.
2. `RadioSendCounter` reserves counter blocks ahead of use (default reserve = 64). Before issuing counter $n$, the durable store records $n + 64$.
3. On restart or crash recovery:
   - `RadioSendCounter` reads the persisted high-water mark and jumps past it.
   - Applies an epoch second floor (`secondsSince2026`) ensuring counter monotonically advances across calendar time.
4. Mutation verified: removing the jump causes simulated crash tests to catch counter reuse.

### Consequences
- Guaranteed zero counter reuse across crashes, battery pull, or unexpected OS kills.
- Atomic property writes ensure file corruption cannot brick the radio subsystem (safe fallback).
- Zero cryptographic keys or frame plaintexts are written to storage.

## ADR-210 — Recommendation to Drop Modes 3 and 5 (Walkie-Talkie Bridge) from Plan R

### Status
ACCEPTED by the owner 2026-10-18 ("drop mode 3 and 5"; recommendation of stream R-audio after the BT-R10 spike). Modes 3 and 5 are removed from Plan R. The idea is parked as FO-28 in `docs/FUTURE-OPTIMIZATION.md`.

### Context
`docs/plans/RADIO-GATEWAY-PLAN.md` §5 and §9, parent plan ADR-173, and Phase R4 define **Mode 3 (Walkie-Talkie Voice Bridge)** and **Mode 5 (All-in-One Dual Mode)**. The intention was to cross-patch Flash Push-to-Talk (PTT) voice sessions (ADR-032) to the Vero Telecom VR-N76 / BTECH UV-Pro handheld radio via Bluetooth Hands-Free Profile (HFP), allowing Flash users to converse directly with conventional analog VHF/UHF FM walkie-talkies.

Hardware spike `BT-00` (`EXP-023`) proved data communication (1200-baud Bell 202 AFSK KISS TNC over Bluetooth SPP) is reliable, but noted that attempting to connect the radio via the Windows Bluetooth audio settings UI failed with "failed because no call was active".

Spike `BT-R10` was tasked with answering the four core feasibility questions of Plan R §9:
1. Bluetooth Hands-Free profile roles and connection behavior.
2. Transmitter RF keying mechanism from Flash.
3. Voice channel audio quality and inbound discriminator audio routing.
4. Fit with Flash's push-to-talk turn-taking architecture.

### Findings & Evidence Levels

1. **Bluetooth Profile Role Collision & Windows WASAPI Model:**
   - *Evidence Level: Verified (Bluetooth SIG HFP Spec v1.8, Windows Core Audio Docs, EXP-023).*
   - Bluetooth HFP mandates strictly one Audio Gateway (AG) and one Hands-Free unit (HF). Smartphones and Windows PCs operate natively in the AG role.
   - When paired to a PC or phone, the VR-N76 advertises UUID `0000111F-0000-1000-8000-00805F9B34FB` (HandsfreeAudioGateway). If the radio operates as an AG (designed to link to wireless headsets), it cannot form an HFP link with an Android phone or PC AG.
   - Even when Windows recognizes the radio as a Hands-Free audio endpoint (`BTHHFENUM`), Windows 10/11 treats HFP audio as an on-demand telephony bearer. The SCO voice channel is strictly closed while idle (endpoint state `DEVICE_STATE_UNPLUGGED` 0x8), and Windows refuses connection until an application actively opens a communications/VoIP stream (`eCommunications`).

2. **Absence of Standard Software PTT Keying:**
   - *Evidence Level: Verified (Bluetooth SIG HFP Spec) & Reported (VR-N76 Manual).*
   - Bluetooth HFP contains zero commands for Push-to-Talk RF keying (it provides only telephony call control: answer `ATA`, hang up `AT+CHUP`, volume `+VGM`/`+VGS`).
   - The only hands-free transmission mechanism supported by the radio firmware is **Bluetooth VOX** (Voice-Operated Exchange, added in UV-Pro firmware v0.8.0).
   - VOX is inherently flawed for professional communications: it incurs a 100–300 ms attack delay (clipping initial speech syllables), produces squelch tail unkey delays (broadcasting dead air after speech ceases), and suffers from false triggers due to acoustic pops or background noise.

3. **Narrowband Degradation & Asymmetric Audio Routing:**
   - *Evidence Level: Verified (HFP Audio Spec) & UNPROVEN on hardware.*
   - Bluetooth SCO audio is restricted to 8 kHz CVSD mono (300 Hz – 3.4 kHz telephone bandwidth), severely degrading Flash's 16 kHz HD PTT audio stream.
   - Furthermore, it is UNPROVEN whether incoming analog RF received by the radio's discriminator is routed into the Bluetooth SCO uplink (acting as a microphone stream to the phone), or whether it only routes out of the radio's internal loudspeaker.

4. **Severe Architectural Incompatibility with Flash PTT Turn-Taking:**
   - *Evidence Level: Verified (Flash PTT Architecture, ADR-032).*
   - Flash PTT (`FlashPtt`, `PttFloorMachine`) relies on strict, distributed, cryptographic half-duplex floor control: a station requests the floor (`Start`), other stations lock into `Listening`, and audio is streamed over encrypted WebSockets to authenticated peers with total-order tie-breaking.
   - Analog VHF/UHF radio is uncoordinated, unauthenticated, and unencrypted simplex RF: any external station can transmit at any time without a digital floor token.
   - Bridging the two environments creates irreconcilable hazards:
     a. **RF Collisions ("Doubling"):** If a Flash peer speaks while an external analog station is transmitting, Flash blindly keys the radio via VOX, transmitting over active emergency or tactical radio traffic.
     b. **Squelch Tail Glare:** The radio's squelch tail and Bluetooth unkey latency hold the audio channel busy for 300–500 ms after speech stops, causing rapid turn-taking replies in Flash to be dropped or clipped.
     c. **Acoustic Feedback Loops:** Sidetone from the radio speaker picked up by local microphones can re-trigger VOX, creating continuous transmitter lockup.
     d. **Security Compromise:** Flash's primary value proposition is end-to-end encrypted, verified communication (Profile M). Bridging Flash PTT to analog FM broadcasts private team voice communications as cleartext analog audio over the airwaves, completely violating Profile M security requirements.

### Decision / Recommendation
**DROP Modes 3 (Walkie-Talkie Bridge) and 5 (All-in-One Dual Mode) from Plan R.**  
Focus Flash's hardware radio development strictly on digital packet data over the KISS TNC:
- **Mode 1: Field Companion** (End-to-end encrypted Profile M text chat & cryptographic receipts over 1200-baud AX.25).
- **Mode 2: Base Station Relay** (LAN/Wi-Fi to radio mesh message forwarding).
- **Mode 4: Tactical GPS Radar** (Offline encrypted position reports).

### Revisit When
Revisit only if future commercial transceivers provide:
1. Native digital audio over Bluetooth SPP / BLE ISO streams (e.g. digital DMR/P25 voice packets instead of analog FM audio).
2. Hardware carrier-sense / channel-busy telemetry exposed over serial/Bluetooth API to prevent doubling.

---

## ADR-211 — Minimal Architecture and Floor Arbitration Model if Modes 3 & 5 are Rescoped or Built

### Status
NOT USED (2026-10-18): the owner accepted ADR-210, so this contingency design is not built. Kept as the starting point for FO-28.

### Context
If the repository owner decides to retain and implement Mode 3 or Mode 5 despite the findings in ADR-210, a sound architectural boundary and floor arbitration model must be defined to prevent destabilizing `:core:ptt` or `:core:engine`.

### Decision
1. **Module Seam & Dependency Direction:**
   - All radio audio bridge logic must reside in `:core:radio` under package `com.transfer.flash.core.radio.audio`.
   - `:core:ptt` must NEVER depend on `:core:radio`.
   - `:core:radio` may depend on `:core:ptt` interfaces (`PttAudioCapture`, `PttAudioPlayout`, `PttFloorState`).
2. **Floor Arbitration & Cross-Patch Engine:**
   - An `AnalogRfBridgeController` must mediate between Flash's `PttFloorMachine` and the radio audio endpoint:
     - **Outbound (Flash $\rightarrow$ RF):** When a Flash client acquires the digital floor (`PttFloorState.Talking`), audio samples are downsampled from 16 kHz to 8 kHz mono PCM and streamed to the Bluetooth SCO render device. Carrier Sense Multiple Access (CSMA) must check KISS TNC channel activity (`isChannelClear`) before permitting playout.
     - **Inbound (RF $\rightarrow$ Flash):** An `AnalogRfSquelchDetector` monitors the Bluetooth SCO capture endpoint. When energy exceeds a calibrated threshold for > 150 ms, the bridge claims a synthetic local floor in Flash (`RadioStationFloor`) and relays audio to LAN peers.
     - **Preemption Rule:** Hardware radio PTT physically pressed by an operator immediately terminates digital playback.
3. **Regulatory and Security Profile Constraint:**
   - Analog FM RF audio cannot be encrypted.
   - When the audio bridge is enabled, the UI must prominently display an unencrypted warning badge: `[ANALOG RF - UNENCRYPTED CLEAR-TEXT BROADCAST]`.
   - Encrypted Profile M keys are NEVER applied to analog audio streams.

---

## ADR-212 — Windows Core Audio and Android SCO Routing Abstraction for Radio Peripheral Audio

### Status
PROPOSED (Standardized Peripheral Audio Routing Abstraction)

### Context
During Phase R4 (`BT-R10`), diagnostic tools were implemented to detect and route Bluetooth Hands-Free audio on Windows and Android. These diagnostic mechanisms establish the architectural pattern for any future Bluetooth audio integration.

### Decision
1. **Windows Platform Routing (Core Audio / WASAPI):**
   - Query endpoints through `IMMDeviceEnumerator` filtered to `eCommunications`.
   - To open the Bluetooth SCO bearer to a Hands-Free device on Windows 10/11, the application must initialize an `IAudioClient` stream configured with `AudioCategory_Communications`.
   - Correlation between virtual COM ports and Hands-Free audio endpoints must use container GUID matching (`ContainerID`) from `HKLM\SYSTEM\CurrentControlSet\Enum\BTHHFENUM` and `BTHENUM`.
2. **Android Platform Routing (Audio Routing APIs):**
   - On Android 12+ (API 31+), audio routing must use `AudioManager.setCommunicationDevice(AudioDeviceInfo)` where `type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO`.
   - On Android 11 and lower, legacy fallback `AudioManager.startBluetoothSco()` and `setBluetoothScoOn(true)` must be used.
   - Runtime permissions `BLUETOOTH_CONNECT`, `RECORD_AUDIO`, and `MODIFY_AUDIO_SETTINGS` are required.
   - The audio mode must be set to `AudioManager.MODE_IN_COMMUNICATION` during active routing and restored to `AudioManager.MODE_NORMAL` immediately upon release.

## ADR-213 - Radio chat wiring: one host, one shared outbox port, `:core:messaging` depends on `:core:radio`

### Status
ACCEPTED (integrator, 2026-10-18, implements the owner's "fix the errors" for ERROR-703)

### Decision
`RadioChatHost` (`:core:radio`) owns the lifecycle "one `RadioChatEngine` per gateway driver". `HostedRadioOutboxPort` and `RadioChatWiring` (`:core:messaging`) are the single shared `RadioOutboxPort` and the single place that turns Flash pairings, the identity key and the persistent counter store into an engine. Android and Windows differ only in the platform pieces they pass (crypto, persistence, authorisation store) and in how they publish the controller's manager (`RadioGatewayHolder.managers`, `DesktopRadioManagers.current`).

### Context
The gateway creates a new `KissTncDriver` on every connect, the engine is bound to one driver, the controllers (UI side) own the manager, and the engine host (`DiscoveryEngineHolder`, `DesktopEngine`) owns identity and trust. Neither side can build the other.

### Alternatives considered
- Controllers build the engine: they do not own identity or the trust store (rejected).
- Duplicate `AndroidRadioOutboxAdapter` / `DesktopRadioOutboxAdapter` bridges (rejected: they wrap a fixed engine and the mapping was already identical). They stay for the engine-level tests and are no longer used by the hosts.
- A global singleton registry in `:core:radio` (rejected: shared state between tests); a published `StateFlow` per host is used instead.
- Move the host into `:core:engine` (rejected: it does not depend on `:core:radio` and should not).

### Consequences
`:core:messaging` gains `implementation(project(":core:radio"))` (no cycle: `:core:radio` depends only on `:core:common`). The engine now registers every paired peer before ingesting, so a peer we never sent to is heard.

### Revisit when
Profile A receive is wired (needs peer identity public keys), or a second transport needs the same "engine per link" lifecycle.
