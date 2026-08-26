# Current Handoff

## 2026-08-26 -- Core library publishing plan authored (GitHub → JitPack → Gradle) -- READ docs/publishing/
- **The owner wants to publish the `core:*` modules as a reusable LAN-transfer library** so other developers
  consume the engine instead of building from scratch. Hosting decision: **GitHub → JitPack → Gradle**, NOT
  Maven Central. The next agent (OpenCode, run in this same folder) implements it.
- **The plan is `docs/publishing/` (7 files).** Start at `PHASE-00-overview.md`; phases are ordered and each
  is self-contained (problem → exact files/code → acceptance → `./gradlew` verify). Do them in order:
  01 foundation (LICENSE, single version source, compat baseline) → 02 dependency-scope (**hard blocker**) →
  03 api-surface (`explicitApi()` + hide internals) → 04 persistence-decoupling (SQLCipher off the transfer
  path) → 05 consumer-ergonomics (`Flash.create()` factory + README) → 06 jitpack-publishing (`jitpack.yml`
  openjdk17, tag/release, verify).
- **The one blocker that makes or breaks it: dependency scope (Phase 2).** Core modules use
  `implementation(project(...))` but expose those types in PUBLIC signatures, so individual `core:*`
  artifacts DO NOT COMPILE for a downstream consumer. `:core:engine` is the only coherent artifact today
  (it uses `api(...)`), so the minimum-viable path publishes `core-engine` only. Prove any scope fix with an
  EXTERNAL `:sample:consumer`, never the library's own build.
- **What JitPack removes vs Maven Central** (do not waste effort): GPG signing, a Sonatype/Central
  `repositories{}` publish target, strict POM validation, and the javadoc jar are all NOT needed. JitPack
  just needs a working `publishToMavenLocal`, `jitpack.yml` pinning JDK 17 (AGP 9.3.1), and a Git tag +
  GitHub release. Consumer coordinate: `com.github.<user>.<repo>:core-engine:<TAG>`.
- **Two decisions need the owner:** LICENSE copyright holder (Phase 1.1); keep compileSdk 37/AGP 9.3.1
  (narrow reach) vs lower for wider consumer support (Phase 1.3).
- **No `core:*` code changed this session** — docs only; the prior green build state stands. (Aside: I
  accidentally overwrote AGENTS.md and reverted it with `git checkout` — AGENTS.md is intact.)

## 2026-08-25 -- RESOLVED: sender could not pause (ERROR-018, ADR-021) -- nine pause/resume/cancel defects
- **The reported bug was a REGISTRATION RACE, not a broken pause button.** `sendFile` returns the instant the
  send coroutine launches, but `executeSend` registered its dispatcher only after the resume-chunk DAO query
  and dispatcher construction. A pause landing in that window found no dispatcher, took a state-only branch
  that emitted no wire frame, and then `executeSend` overwrote `Paused` with `Transferring` — the pause
  disappeared and bytes kept flowing. **Pause is now an INTENT** (`pauseIntents`, recorded BEFORE the
  dispatcher lookup) that `applyPendingPauseOrStart` re-checks after the Transferring write.
- **Eight more defects fixed in the same audit** (full list + fixes in ERROR-018): `send()` parked forever
  when COMPLETE arrived during a pause; the 15 s ACK-drain grace failed paused transfers (a paused receiver
  deliberately stops ACKing); resume never un-gated receive intake, so both UIs showed Transferring at 0 B/s;
  the intake gate was one session-wide boolean; `resumeTransfer` no-oped on a state mismatch while the wire
  stayed paused; the rate meter straddled the paused gap and `-1` leaked to the UI as negative speed;
  `tryEmit` dropped control frames silently; cancelling a PAUSED sender never reached a cancellable
  suspension point, and the `finally` cleanup lacked an ownership check.
- **Read ADR-021 before touching this code.** Its invariants: pause intent outlives dispatcher construction;
  a paused transfer is NEVER failed by a timeout (the drain deadline is disarmed, resume re-arms fresh); a
  terminal outcome always beats a pause (`awaitUnpause()` returns on the terminal deferred); resume always
  emits `IncomingControl(RESUME)` while remote PAUSE deliberately does NOT gate (the gate is session-wide, so
  gating would stall unrelated transfers' ACKs); the gate is a SET of transfer ids; paused telemetry is a
  hard `0.0` / ETA `-1`.
- **Verified:** `:core:transfer:testDebugUnitTest --rerun` green (76 tests, 0 failures) and full
  `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (411 tasks; 668 tests / 0 failures across 102 suites);
  `app-debug.apk` produced. 6 new regression tests, incl. a `GatedChunkDao` that parks the resume query to
  reproduce the race window exactly, and a fake-clock test proving a paused sender survives repeated 60 s
  jumps and only fails after resume.
- **Known limit:** the pause intent is in-memory, so pause does NOT survive process death — a killed paused
  sender comes back as Queued and re-plans from the persisted done-set. Persisting it is the ADR-021 revisit
  trigger.
- **Still owner-only: the two-phone device run** (10 MB over 5 GHz, Pause/Resume/Cancel from BOTH sides, plus
  a multi-minute pause), to be recorded as EXP-002 against EXP-001.

## 2026-08-25 -- Phase 8 App Shell LIVE: custom bottom nav + four tab pages
- **The app now boots into the real shell:** `MainActivity.FlashApp` renders
  `Column { FlashAnimatedScreen(nav.current) ; FlashBottomNav }` — boolean-flag switching GONE.
  Tabs = Chats / Transfers / Nearby / Settings; tab taps call `FlashNavigationState.selectTab`
  (stack RESET, not push); Conversation still pushes; BackHandler pops.
- **UI-046 FlashBottomNav** (docs/ui/bottom-nav.md): custom docked bar — spring-sliding Pulse pill,
  squash-release icon pop, animated label weight, re-select pulse ring, Tick haptics, badges (9+),
  selectableGroup/Role.Tab semantics, reduce-motion snaps. Four NEW house-style icons
  (chat/transfer/nearby/settings; settings gear adapted from Feather MIT w/ attribution).
- **UI-047 TransfersScreen** (transfers-page.md): ACTIVE/FAILED/HISTORY sections, honest status lines,
  bytes-weighted progress, pause⇄resume swap, scoped Retry, Share on history; UI-016 badge color language
  reused statically. Empty state via new `TransfersFirstRun` kind.
- **UI-048 NearbyScreen** (nearby-page.md): identity card, peer rows + FlashTransportBadge + Connect,
  trusted peers + Revoke, scanning pulse dot, radios-off explainer, pairing-dialog mount ready
  (phase/secondsLeft pass-through to UI-032 dialog).
- **UI-049 SettingsScreen** (settings-page.md): five sections; CUSTOM segmented theme control + CUSTOM
  FlashSwitch; About card with version/protocol/device-id.
- **Demo-state contract:** TransfersUiState/NearbyUiState/FlashSettingsModel in MainActivity are shaped
  EXACTLY like future C5/C3/C1.4 engine outputs — wiring is substitution, not rewrite.
- **Verified:** full `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (411 tasks); suite green incl.
  +19 new tests across nav/transfers/nearby/settings logic. NOT device-verified yet.
- **Remaining P1 gap:** Send FAB on Chats (opens attachment palette) — only page-plan item not built.

## 2026-08-24 -- RESOLVED: :core:transfer suite hang (ERROR-016) + Gradle startup failure (ERROR-017)
- **Hang fixed, bounded queues kept.** `MultiStreamDispatcher.runWorker` is now ONE loop that `select`s over its own
  feed and the shared redistribution queue (was two sequential phases); exit bookkeeping (`ownFeedsOpen`,
  `aliveWorkers`) moved into `finally` behind idempotent releases; redistribution is `trySend` + 5 ms poll instead of a
  blocking `send`; materializer breaks once the transfer resolved; `maybeResolveFromState` fails fast when nothing ever
  reached a wire. Root cause + the discarded alternatives: ERROR-016 (RESOLVED) and ADR-019.
  The permitted revert-to-`Channel.UNLIMITED` fallback was NOT needed - feeds=8 / shared=32 stay bounded.
- **The phase-split deadlock was device-fatal, not just a test artifact:** any mid-flight channel death on a large file
  could pin a worker in `shared.send()`, block the materializer on that worker's feed, and stall the transfer forever.
- **Gradle can run again (ERROR-017).** Every invocation, incl. `gradlew --version`, was dying with `Unable to
  establish loopback connection`: JDK 19+ builds every `Selector` on an AF_UNIX socket pair on Windows, and on this
  machine AF_UNIX connect always fails EINVAL (bind succeeds, so the JDK's TCP fallback never triggers). Fix: point the
  AF_UNIX temp dir at a nonexistent path so the bind fails and the JDK falls back to TCP loopback -
  `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"` (covers launcher, daemon and workers).
  **Use this in every shell that runs Gradle** - see the Build environment note at the bottom.
- **Docs:** ADR-018 (FLASH_XFER wire control plane + cooperative pause) and ADR-019 (single-loop bounded-queue workers)
  added to `docs/decisions.md`; both were referenced from code but previously unwritten.
- Everything else from this session stands: real N-socket data channels, FLASH_XFER control both directions,
  cooperative dispatcher pause, speed-meter fix, Dev Console redesign.
- **Only remaining step for this batch: the two-phone device run** (10 MB over 5 GHz; Pause/Resume/Cancel from both
  sides; then record EXP-002 vs the EXP-001 hotspot baseline).

## 2026-08-24 -- Real multistream (N TCP sockets) + speed fix
- **Real multi-stream implemented:** `core/network/datachannel/` — plain-TCP side channels (`FLASH_JOIN` handshake, length-prefixed frames) bound to the WS session; factory opens one real socket per stream to the target peer (port probe ws+1..+20, cached), WS fallback if peer has no data server. ACKs reply down the arriving connection.
- **Speed display fixed:** RollingRateMeter rewritten (sliding sample window); old version inflated continuously because Δbytes used the first-ever sample while Δtime stayed ≤2 s.
- **Sender pause under diagnosis:** pauseTransfer now logs direction/state/jobPresent — capture TRANSFER logcat from a failing pause attempt.
- Test matrix: both phones must run THIS build for data channels to engage; older peer = silent WS fallback.

## 2026-08-24 -- Dev Console redesign + pause-while-receiving
- Dev Console is now tabbed (PEERS / TRANSFERS / NET) with a status card and log strip; transfer rows have progress bars + Pause/Resume/Cancel (TX and RX).
- Receive-side pause implemented via TCP backpressure: repo emits `incomingControl` events; holder gates intake before pulling frames (`WsSession.awaitBinaryFrame()`); sender throttles automatically, resume drains buffer. Chat stalls during receive-pause (single socket) — accepted v1 trade-off.
- Real N-socket multistream roadmap (next perf milestone): see `docs/decisions.md` ADR-017 revisit + EXP-001 baseline. No external code download needed — dispatcher/receiver already speak N channels; only the transport factory must open N real sockets with a session-join handshake.

## 2026-08-24 -- Device round 2: ack-drain fix (false "Failed" at ~20%) + receiver now visible in Active Transfers
- **Root cause of the 20%-then-Failed symptom:** sender workers exit as soon as all chunks leave the socket buffer; ACKs lag behind disk-paced receiver verification, so `maybeResolveFromState`/`failIfAllChannelsDead` misread uncovered+zero-alive as channel failure. Receiver was fine and always-on — it had verified the entire file.
- **Fix:** bounded 15 s ack-drain grace after worker exit; late ACK_BATCH/COMPLETE now resolve Completed. True failure message is explicit: `ack drain timeout: N unconfirmed`.
- **Receive-side UI:** inbound transfers register via new additive `onIncomingStarted/Progress/Completed/Failed` repo hooks — Dev Console Active Transfers shows Receiving rows on the receiver phone too.

## 2026-08-24 -- Layer-by-layer audit vs media-downloader: pause/resume correctness fixes
- Compared `media-downloader-main` engine layers against Flash's transfer stack; fixed three pre-test bugs:
  1. Pause/cancel no longer reports Failed (`CancellationException` handled separately, re-thrown).
  2. Resume keeps the same wire fileId (`FlashTransfer.wireFileId`) — fresh UUIDs were rejected by the receiver as SESSION_CONFLICT.
  3. Sender chunk done-set now persists to Room (`TransferChunkDao` was dead code) so resume seeding works.
- Remaining gaps (deliberately deferred, in priority order): process-death restore (TransferEntity needs fileName/sourceUri/peerId/wireFileId columns + startup rehydration), queue/concurrency/retry-backoff, receiver-side identity validation + receive done-set persistence, MediaStore publish of received files.

## 2026-08-24 -- WS Mesh Hardening (ERROR-015): correct assembly, reliable delivery, liveness, glare safety
- **Received files now assemble correctly:** per-transfer random-access sinks (`FileRandomAccessSinkHandle` + `RandomAccessChunkSink`) write chunks at `index * chunkSize` under `FlashReceived/<transferId>/<safeName>`. The previous append-order sink scrambled out-of-order multi-stream arrival.
- **No more silent frame drops:** WsSession delivers inbound frames via bounded blocking channels → TCP backpressure; dropped-chunk transfer stalls are structurally impossible.
- **Liveness:** 15 s WS pings + 45 s read timeout close half-open hotspot connections.
- **Handshake/glare races fixed:** early-frame buffering, replaced-session close, identity-safe disconnects, HELLO version enforcement, pending-handshake sockets closed on stop, Mutex-serialized start/stop.
- **Peer-targeted sends:** stream channels route to the intended recipient (`StreamChannelFactory.open(channelId, peerDeviceId)`).
- **Resume fix:** `FlashTransfer.sourceUri`; resume re-reads original content URI.
- **Chat framing:** colon-safe `FLASH_MSG`/`FLASH_RCPT` field encoding via FlashTextFraming.
- **Dev Console:** persisted Room DB (`flash-dev.db`); loud failure on source-open errors; deterministic generated 10MB test payload.
- **Verified:** `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL — 644 tests / 0 failures / 0 skipped.

## 2026-08-24 -- Unified WebSocket Mesh Transport & Transfer Pipeline Wiring
- **Implemented WebSocket Mesh Transport:** Created `WsFlashNetwork` and `WsSession` implementing `FlashNetwork` and `FlashSession`.
- **Full-Duplex Multi-Peer Channels:** Each peer pair maintains an active WebSocket capable of streaming UTF-8 text (`MessageWireFrame` for chat) and binary frames (`ChunkFrame` for files) simultaneously.
- **Symmetric Router & Hotspot Support:** Operates seamlessly via mDNS discovery on standard Wi-Fi routers and via gateway/probe on mobile hotspots.
- **Wired to Engine & Receivers:** Outbound sends route through active WebSockets, inbound chat messages persist into Room, and inbound file chunks flow into `ReceivePipeline` with auto-save to `FlashReceived/`.
- **Sender ACK Routing:** Inbound `ACK_BATCH` and `COMPLETE` frames route directly to active `MultiStreamDispatcher` instances via `RealFlashTransferRepository.onInboundFrame()`.
- **Structured Diagnostic Logging:** Added tags `DISCOVERY`, `WS`, `TRANSFER`, `CHAT`, `DEV` for clear visibility in Android Studio and `adb logcat`.
- **Verified Build & Tests:** `testDebugUnitTest assembleDebug` -> BUILD SUCCESSFUL across all 10 modules (411 tasks, 0 failures).
- **Installed to Device:** Tested debug APK installed on physical phone via ADB.

## Current branch
`main`

## Last verified build
Working tree at 2026-08-24 (ERROR-016 fix) — `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL, 411 actionable tasks,
**644 tests / 0 failures / 0 skipped**. `:core:transfer:testDebugUnitTest` alone: 70 tests green (was hanging).
Previous reference point: commit `9060445` (411 tasks, 0 failures).

## Current phase
**Phase 7 (Engine Facade) Complete + Unified WebSocket Transport Deployed.**
- Ready for multi-device testing on Router / Hotspot networks and Phase 8 UI App Shell wiring (`docs/ui-page-plan.md`).
- `:core:engine` module created and integrated into settings and app.
- `FlashEngine` and `DefaultFlashEngine` facade binding all 6 subsystems (`chats`, `transfers`, `discovery`, `network`, `trustStore`, `settings`).
- Full project build & test suite: 100% GREEN (411 Gradle tasks, `assembleDebug` + `testDebugUnitTest` successful with 0 failures).
- Up next: **Phase 8 / App Shell & Pages Integration** (wiring UI navigation tabs and pages in `docs/ui-page-plan.md` to `FlashEngine`).

## Component status
- **UI-034 (Adaptive layouts):** `IMPLEMENTED` in `ui/adaptive/FlashAdaptiveLayouts.kt` â€” two-pane not yet consumed by screens (integration pending).
- **UI-038/039/041 (A11y/Haptics/Micro):** `IMPLEMENTED` â€” `FlashFeedback.kt` haptic choke point, 15 call sites migrated, a11y fixes applied.
- **UI-042/043 (Performance/Stress):** `IMPLEMENTED` â€” `FlashStressTestScreen.kt` harness; device measurements PENDING.
- **UI-024/031/032:** `IMPLEMENTED` â€” integration wiring items in Deferred block below.
- **UI-023, UI-028/029/030, UI-025â€“027, UI-021/022, UI-020, UI-019:** `IMPLEMENTED` â€” device verification pending.
- **UI-018 (Media viewer):** `VERIFIED` on device.
- **UI-017 (Image message & grid layout):** `IMPLEMENTED` in `FlashImageGrid.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-016 (File message card):** `IMPLEMENTED` in `FlashFileMessageCard.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-015 (Delivery / read states):** `IMPLEMENTED` in `FlashDeliveryStatusIcon.kt`, `FlashIcons.kt`, `FlashMessageBubble.kt`.
- **UI-014 (Typing indicator):** `IMPLEMENTED` in `FlashTypingIndicator.kt`, `FlashChatHeader.kt`, `FlashMessageList.kt`, `FlashConversationScreen.kt`.
- **UI-012 (Custom attachment button):** `IMPLEMENTED` in `FlashAttachmentButton.kt`, `FlashAttachmentSheet.kt`, `FlashComposer.kt`, `FlashConversationScreen.kt`.
- **UI-010 (Reply system):** `IMPLEMENTED` in `FlashQuotedReplyCard.kt`, `FlashSwipeToReply.kt`, `FlashMessageBubble.kt`, `FlashConversationScreen.kt`.
- **UI-009 (Reaction system):** `IMPLEMENTED` in `FlashReactionChip.kt`, `FlashReactionsDock.kt`, `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-007 (Message press & selection):** `IMPLEMENTED` in `FlashMessageBubble.kt`, `FlashSelectionToolbar.kt`, `FlashConversationScreen.kt`.
- **UI-008 (Focus overlay & context menu):** `IMPLEMENTED` in `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-011 (Custom message composer):** `IMPLEMENTED` in `FlashComposer.kt`.
- **UI-013 (Custom send button):** `IMPLEMENTED` in `FlashComposer.kt`.
- Foundation components: UI-001, UI-002, UI-037 (`IMPLEMENTED` in `:ui:theme`).
- List & Header: UI-003, UI-004, UI-005, UI-006 (`IMPLEMENTED` in `:ui:chat`).

## Working features
- Full modular multi-module library architecture (`com.transfer.flash:*`).
- Group chat header (UI-028): initials collage avatar (2/3/4+ layouts from seeded palette), "N members Â· M online" subtitle, named typing ("Alex and Sam are typingâ€¦"), transport/encryption glyphs, group Search action. Header fully de-Materialed (custom icon buttons, drawn divider, FlashText).
- System states family (UI-025/026/027): screen-specific empty states with P2P copy + "Find devices" CTA, layout-matched skeletons (delay-guarded, reduce-motion-safe, decorative semantics), severity-split error panels (red failure vs neutral offline) with single Retry â€” wired into chat list and conversation screens.
- Chat scroll engine + jump pill (UI-021/022): auto-scroll at bottom & on own sends, unseen counter with floating "N new messages" accent pill (tap â†’ animated jump + reset), reverseLayout bottom pinning through image resizes, keyboard-safe position retention.
- Voice recording interface (UI-020): hold mic to record, slide-left arms cancel (error-tinted bar), slide-up locks into persistent panel with trash/pause/send, live timer + Canvas amplitude strip, demo-mode capture producing real `FlashVoiceAttachmentUi` payloads.
- Voice message playback card (`FlashVoiceMessageCard`, UI-019): 40-bar discrete waveform with tap-to-seek + drag scrub, 48dp play/pause/download/retry badge, Telegram-style remainingâ†”duration label, 1Ã—/1.5Ã—/2Ã— speed pill, demo-mode playback ticker (real audio deferred pending Media3 ADR).
- Full-screen media viewer (`FlashMediaViewer`, UI-018): pinch/double-tap anchored zoom (1Ã—â€“4Ã—, rubber-band), pan, vertical drag-to-dismiss with backdrop fade + page scale, HorizontalPager album carousel, auto-hiding chrome (counter `n / m`, close, Save/Share/Forward), sample-size-guarded decode, always-dark backdrop token.
- Adaptive Image Collage & Grid Layout (`FlashImageGrid`): 1, 2, 3, 4, and 5+ image mosaics with clamped aspect ratios ($0.5$ to $2.0$), micro-gap gutters ($2.5\text{dp}$), bubble contour corner masking, and $+N$ overflow chips.
- Experimental WebSocket Mesh Transfer: Full file viewing, sharing, and device export capabilities (`WsFileActions`, `FileProvider`, SAF `CreateDocument` picker, click-to-open cards).
- Redesigned 24Ã—24 Custom Vector Icon Set: 46 Flash-owned vector icons with 2.0dp stroke weight, generous optical bounding boxes, and scaled default UI sizing (24dp).
- Complete Edge-to-Edge System Bar and Insets Safety: Status bar cutout clearance across headers/toolbars, and navigation bar/keyboard clearance across composer and sheets.
- Rich in-bubble file message cards (`FlashFileMessageCard`) with color-coded file extension badges (PDF, ZIP, Code, Audio, Video, Image, Document), circular transfer progress rings, and real-time throughput metrics (MB/s speed & ETA countdown).
- Animated delivery status glyphs (`FlashDeliveryStatusIcon`) for 5 transit lifecycle states (Pending, Sent, Delivered, Read, Failed) with 1-tap retry interaction.
- 120 FPS GPU-accelerated 3-dot wave bouncing typing indicator.
- Incoming message stream typing bubble (`FlashTypingBubble`) with concave bubble shaping and smooth list integration.
- Animated header subtitle typing status (`FlashHeaderTypingStatus`) with mini-dots.
- Stateful attachment button in composer with spring rotation ($0^\circ \to 45^\circ$) and active accent tint.
- Modal bottom sheet attachment palette with 5 categorized options (Gallery, Files, Camera, Audio, Flash P2P).
- Staggered spring scale entrance and 0.90x micro-press physics on attachment action tiles.
- Left-swipe-to-reply gesture with rotating reveal badge and single-edge haptic trigger.
- In-bubble quoted reply card with 3dp rounded vertical accent bar and 1-tap jump to original message.
- Jump-to-original message smooth scrolling with 600ms Flash Pulse glow highlight.
- Composer reply dock with dismiss action.
- Interactive reaction dock on message bubbles with 1-tap toggling, active self-reaction accent styling, animated vertical count roll (odometer), and `+N` overflow chip.
- Floating quick reaction bar inside spotlight focus overlay with staggered spring entrance, micro-press physics, and trailing `+` action button.
- Immersive message focus overlay with 65% dimmed backdrop, elevated bubble preview, and sculpted context menu card.
- Multi-message selection mode with animated toolbar swap, batch Copy/Reply/Forward/Delete.
- Adaptive multiline message composer with keyboard safety, reply dock, and tactile send button.
- Message list with reverse layout, concave bubble shapes, entrance choreography, and auto-scroll.
- LAN Discovery and experimental WebSocket multi-peer mesh Transfer.

## In progress
- **UI-028 (Group header):** IMPLEMENTED â€” device verification pending.
- **UI-025/026/027 (states):** device verification pending.
- **UI-021/022, UI-020, UI-019:** device verification pending.

## Broken
- None.

## Last change
ERROR-013 rewrite attempt REVERTED after findings: structured-concurrency dispatcher fixed symptoms but exposed entangled completion semantics (first-wins terminal guard vs late authoritative frames — racing-ACK regression); dedicated test dispatchers disproved pool starvation; thread dumps show claim/read lock as blocker. Next session: build completion state machine pure-first (PairingSessionStateMachine pattern), then thin executor. Tests remain @Ignore green-skipped.

## Last test
testDebugUnitTest assembleDebug - BUILD SUCCESSFUL (2026-08-24); **644 tests / 0 failures / 0 skipped** across 11 test
modules (app 1, core:common 42, core:discovery 79, core:engine 1, core:messaging 12, core:network 99,
core:persistence 34, core:security 80, core:transfer 70, ui:chat 189, ui:theme 37).
`MultiStreamDispatcherTest` additionally re-run 8x standalone (real threads) - 8/8 green, no flakiness.

## Known blockers
- **Environment (ERROR-017, WORKAROUND MANDATORY)**: Gradle cannot start at all in this environment without
  `JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"` (AF_UNIX connect is blocked OS-side, so `Selector.open()`
  fails -> "Unable to establish loopback connection"). Export it before any `gradlew` call.
- **Environment (ERROR-008, MITIGATED)**: E: drive intermittently returns "The device is not ready" during Gradle cache writes. Recovery: `.\gradlew.bat --stop`, kill stuck java PIDs, rebuild with a fresh daemon. Real fix is hardware-side (move caches off the removable/hot-plug device or disable its power management).

## Deferred / pending integration (do not forget)
**Master plans:**
- **PART 1 â€” Core:** `docs/core-upgrade-plan.md` **v2 ACTIVE** â€” D2/D3/D4/D5 approved (ADR-010); D1 + D6 open; execution phases P0â€“P8 defined.
- **PART 2 â€” Pages:** `docs/ui-page-plan.md` â€” bottom nav shell (Chats/Transfers/Nearby/Settings + Send FAB), page-by-page specs P1â€“P5 with core-API dependencies, integration checklist.

All items below are absorbed into those two documents:
- UI-031 badge/sheet wiring into header; `isVerified` passes false until pairing lands.
- UI-032 pairing dialog trigger from discovery flow; Accept/Decline need engine callbacks.
- UI-024 recent-searches persistence; UI-029 demo roster until live members.
- UI-020 MediaRecorder capture ADR; UI-019 Media3 playback ADR.
- Engine-side auto-retry/backoff indicator (UI-044); key-changed warning state (UI-031).
- **UI-031**: wire `FlashEncryptionBadge` near conversation header; tap opens `FlashEncryptionSheet`. `isVerified` passes `false` until pairing/engine lands; verification rows disabled-with-explanation.
- **UI-032**: trigger `FlashPairingDialog` from the Nearby Devices/discovery flow once engine exposes pairing events; Accept/Decline need engine callbacks.
- **UI-024**: recent-searches persistence (currently in-memory only).
- **UI-029**: members sheet uses demo roster until repository feeds live members.
- **UI-020**: real audio capture requires RECORD_AUDIO flow + MediaRecorder engine ADR.
- **UI-019**: real playback requires Media3 dependency ADR.
- **Engine-side**: auto-retry/backoff indicator (UI-044), key-changed warning state (UI-031).

## Recommended next task
**Owner device run (two phones):** Dev Console → Connect → Test 10MB on Router AND Hotspot. Expected sender log: chunks sent + "consumed by sender dispatcher" ACKs; expected receiver log: `Receiver destination opened file=...` → `Receiver completed transferId=... verified=true`, and a 10,485,760-byte file at `FlashReceived/<transferId>/test_10mb.bin`. Then Ping Msg both ways and Choose File & Send. If green, proceed to Phase 8 UI App Shell wiring (`docs/ui-page-plan.md`).

## 2026-08-22 - P3 NSD session note (agent handoff)
- LAN MVP networking now has `nsd/NsdTransport.kt` (:core:discovery) implementing FlashRadioTransport C3.2-C3.4 (identity TXT advertise + self-filter, continuous browse w/ capped restarts, API>=34 ServiceInfoCallback vs <34 hardened NsdResolveQueue split, NetworkRequest-scoped discovery API 33+). `NsdFlashDiscovery` untouched (R4). NOT yet Gradle-verified (forbidden session) - run testDebugUnitTest first; tests: nsd/NsdTransportLogicTest.kt (pure-JVM, no coroutines-test dep in module).
- API thresholds + citations live in `NsdApiLevel.kt` KDoc and logs/progress.md entry of same date. DiscoveryRequest combined API (T-ext 22 / SDK 37) deliberately deferred.


## DEVICE TESTING BACKLOG (for owner)
Priority order; each item = install latest debug APK, exercise, report pass/fail:
1. **UI-019 Voice playback**: tap voice card â†’ play/pause animated morph, seek by tap, drag scrub, speed pill cycle, remainingâ†”duration label swap.
2. **UI-020 Recording**: hold mic â†’ bar+timer+amplitude; slide-left = red "release to cancel"; slide-up = lock panel (trash/pause/send); release sends; short tap discards.
3. **ERROR-009/010/011 regressions**: keyboard-open has NO blank band; context menu dismisses on FIRST scrim tap + âœ• button.
4. **UI-021/022 Scrolling**: peer message while scrolled up â†’ "N new messages" pill; tap jumps to bottom; auto-scroll on own send.
5. **UI-023 Search**: header search icon â†’ type query â†’ counter + prev/next jump with in-bubble highlight; close restores.
6. **UI-025â€“027 States**: empty chat list ("Find devices" CTA), skeleton loading, error panel retry.
7. **UI-028/029 Group**: collage avatar + "15 members Â· 4 online" subtitle + named typing; group avatar tap â†’ members sheet.
8. **UI-030 Banner**: connection banner states (toggle sample data); transport badge chip.
9. **UI-031 Encryption badge/sheet** (once wired).
10. **UI-032 Pairing dialog** (once wired to discovery).
11. **UI-024 Chat-list search**: filter by title/preview, recents chips.
12. **Dark mode sweep** all above + **reduced-motion** setting spot-checks.
13. **UI-042/043 Perf**: open stress screen at 500/2000 messages, fling scroll, note jank (`adb shell dumpsys gfxinfo com.transfer.flash`).

## Build environment note
```powershell
$env:JAVA_HOME="E:\AndroidDev\AndroidStudio\android-studio\jbr"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; $env:GRADLE_USER_HOME="E:\Flash\.gradle-user-home"
# REQUIRED on this machine (ERROR-017) - without it every Gradle invocation dies with
# "Unable to establish loopback connection" because AF_UNIX connect is blocked OS-side:
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"
.\gradlew.bat testDebugUnitTest installDebug
# If "The device is not ready" appears (ERROR-008):
.\gradlew.bat --stop; taskkill /PID <stuck java pid> /F; then rerun with a fresh daemon.
```
Git Bash equivalent: `export JAVA_HOME=... GRADLE_USER_HOME=... JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"`
then `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`.

## Files most relevant to next task
- `logs/handoff.md` testing backlog above (owner runs; lead fixes / marks VERIFIED)
- `ui/chat/src/main/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt` (two-pane consumption pending)
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashStressTestScreen.kt` (entry-point wiring)
- `docs/ui/performance.md` (device measurement plan for UI-042/043 numbers)
- Integration files from Deferred block: FlashNavigation.kt, FlashNetworkSimSheet.kt, FlashEncryptionIndicators.kt, FlashPairingFlow.kt

## 2026-08-22 - P3 pure-logic agent handoff (C3.3/C3.5/C3.9)
- Created (ONLY these): `core/discovery/.../core/{StandardEndpointDirectory,TxtCodec,DiscoveryRetryPolicy,CompositeDiscovery}.kt` + 4 matching JUnit4 test classes under src/test. NO existing file touched; nsd/** untouched.
- CompositeDiscovery implements existing FlashDiscovery + `startAll(port, identity)` aggregate + `sweep(nowMs, grace=30_000)` + `mergedEvents` SharedFlow(DROP_OLDEST); dedup across transports by deviceId, priority LAN > WIFI_DIRECT > WIFI_AWARE > BLE, loss hysteresis emits Updated(fallback) not Lost while a lower radio still sees the peer.
- Deterministic tests without coroutines-test: synchronous DirectDispatcher injected via optional scopeFactory ctor param + explicit clock lambda + local FakeTransport.
- NOT Gradle-verified (forbidden session) - run testDebugUnitTest first; expect ~+25 tests. Full details + research URLs: logs/progress.md entry of this date; decisions: ADR-010.

## 2026-08-23 - P4 pure-logic agent (C4.2/C4.3/C4.5/C4.7-aggregation + C4.9)
- Created ONLY: `core/network/.../resilience/**` (ReconnectPolicy, HeartbeatPolicy, HeartbeatTracker, BoundedSendQueue, SessionHardeningPolicy, ConnectionHealthAggregator, ChaosSession+DedupGate, ChaosNetworkHarness) + matching tests under src/test. NO existing file or gradle/toml touched; Gradle NOT run.
- Strategies chosen (research-cited in logs/progress.md same date): full-jitter-with-floor backoff base 1s cap 30s; heartbeat 10s interval / 3 misses; send-queue REJECT mode capacity 64; session limit 8; duplicate-device tie keeps existing.
- Deterministic JVM tests only (explicit nowMs, seeded Random, injected random01); no coroutines-test. MutableStateFlow used in main via transitive coroutines-core (lifecycle-runtime-ktx) - verified.
- NOT build-verified. Next AI: run testDebugUnitTest first (~+30 expected), then wire primitives into concrete FlashNetwork impls (C4.2/C4.3 integration).