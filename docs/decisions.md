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
