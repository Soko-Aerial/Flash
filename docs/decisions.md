# Decisions

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
