# Optimization Audit Report: Domain O03 — Compose: Recomposition, Draw Phase and Stability
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Domain:** O03 — Jetpack Compose / Compose Multiplatform (Desktop & Android)  
**Status:** COMPLETE  

---

## 1. Domain Header & Scope

This audit performs an in-depth white-box performance inspection of Jetpack Compose and Compose Multiplatform across `:ui:chat`, `:ui:callui`, `:ui:theme`, `:app` (Android `MainActivity.kt`), and `:desktop` (`DesktopShell.kt`). It verifies recomposition boundaries, draw-phase deferrals, stability configurations, lambda allocations, remember caching, infinite animation loops, and layout passes against the performance rules in `AGENTS.md` and `docs/audit/OPTIMIZATION-AUDIT-PROMPT.md`.

### 1.1 Files Audited
- **`:ui:chat`**:
  - `FlashConversationScreen.kt` (1,757 lines)
  - `FlashMessageList.kt` (467 lines)
  - `FlashMessageBubble.kt` (920 lines)
  - `FlashComposer.kt` (598 lines)
  - `FlashImageGrid.kt` (654 lines)
  - `FlashSwipeToReply.kt` (200 lines)
  - `FlashReactionChip.kt` (264 lines)
  - `FlashQuotedReplyCard.kt` (175 lines)
  - `FlashTypingIndicator.kt` (261 lines)
  - `FlashSwarmPieceMap.kt` (270 lines)
  - `FlashVoiceRecording.kt` (496 lines)
  - `FlashShareTargetSheet.kt` (852 lines)
  - `FlashChatSearchBar.kt` (332 lines)
  - `FlashChatSearchResults.kt` (249 lines)
  - `FlashGlobalSearchResults.kt` (251 lines)
  - `FlashStressTestScreen.kt` (412 lines)
  - `FlashBottomNav.kt` (534 lines)
  - `FlashTransfersScreen.kt` (1,041 lines)
  - `FlashNearbyScreen.kt` (1,110 lines)
- **`:ui:callui`**:
  - `FlashCallScreen.kt` (1,037 lines)
  - `FlashGroupVideoGrid.kt` (457 lines)
  - `FlashCallControlDock.kt` (592 lines)
  - `FlashCallExtras.kt` (610 lines)
- **`:ui:theme`**:
  - `Theme.kt`, `Color.kt`, `Typography.kt`
  - `FlashMotion.kt` (489 lines)
  - `FlashBrandAnimation.kt` (225 lines)
  - `FlashInkSplash.kt` (484 lines)
  - `FlashShapes.kt` (180 lines)
- **`:app` & `:desktop` Hosts**:
  - `app/src/main/java/com/transfer/flash/MainActivity.kt` (4,030 lines)
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt` (2,975 lines)

---

## 2. Hot Paths by Frequency and Dispatcher

| Cadence / Frequency | Hot Path | Component / Composable | Thread / Dispatcher | Notes |
|---|---|---|---|---|
| **60 Hz / 120 Hz** (8.3 ms - 16.6 ms) | Splash Brand Pulse | `FlashBrandAnimation.kt:59` | Main (RenderThread / Skia) | Canvas draw phase allocating Paths and Brushes on every frame |
| **60 Hz / 120 Hz** | Chat List Scroll & Fling | `FlashMessageList.kt:172` | Main Looper (UI thread) | LazyColumn measure & place pass, enter progress, bubble widths |
| **60 Hz / 120 Hz** | Call Ambient HUD Glow & Border | `FlashCallScreen.kt:398`, `FlashGroupVideoGrid.kt:212` | Main (RenderThread) | Draw-phase ripple scaling and speaker border pulse |
| **60 Hz / 120 Hz** | Share Target Scan Dot | `FlashShareTargetSheet.kt:794` | Main Looper (UI thread) | Infinite transition driving composition-phase alpha on every frame |
| **Interactive Burst** (1 - 10 Hz) | Composer Keystrokes | `FlashConversationScreen.kt:415, 914` | Main Looper (UI thread) | Draft snapshot state mutation invalidating root 1,757-line screen |
| **1 Hz - 5 Hz** | Network / Radio Telemetry | `DesktopShell.kt:1010-1065`, `MainActivity.kt:1490` | Main Looper (UI thread) | Background flows collected at shell scope triggering root recomposition |
| **Event-Driven** | Message Ingestion & Status | `FlashMessageList.kt:195`, `FlashMessageBubble.kt:543` | Main Looper (UI thread) | Bubble recomposition on ACK delivery status / reaction updates |

---

## 3. Checklist Compliance Table

| Item # | Checklist Item Description | Status | Reference / Finding ID / Reason |
|---|---|---|---|
| **1** | State reads in composition phase that belong in layout or draw phases (`graphicsLayer {}`, `drawBehind`, `Modifier.offset {}`, `derivedStateOf`) | **PROBLEM** | **OPT-O03-01**, **OPT-O03-02**, **OPT-O03-03** |
| **2** | Unstable parameters: lambdas, List / data classes without stability configuration; is Compose compiler stability config file present? Strong skipping mode enabled? | **PROBLEM** | **OPT-O03-04** (Stability config completely absent; collections compared by referential identity `===`) |
| **3** | Lambdas allocated per list item in LazyColumn / LazyRow | **PROBLEM** | **OPT-O03-05** (`FlashMessageBubble.kt` attachment card lambdas allocated per row) |
| **4** | `remember` keys that defeat caching or cause thrashing | **PROBLEM** | **OPT-O03-06** (`initialMessageIds` HashSet O(N) equality in `FlashMessageList.kt:119`), **OPT-O03-07** |
| **5** | `LaunchedEffect(Unit)` capturing stale variables or re-launching unexpectedly | **PROBLEM** | **OPT-O03-08** (`FlashMessageContextMenu.kt:226` 7-step cascade), other 12 sites verified safe |
| **6** | `collectAsState` / `collectAsStateWithLifecycle` at too high a scope (e.g. shell-scope reads invalidating entire screens, EXP-011, EXP-012) | **PROBLEM** | **OPT-O03-09** (`MainActivity.kt` trusted peers regex), **OPT-O03-10** (`DesktopShell.kt` 8 flows) |
| **7** | `animate*AsState` running continuously when nothing is visible or animating | **NO PROBLEM** | Verified: `rolledSpeed`, `pressScale`, `scale`, `chipBottomInset` settle at target value when idle |
| **8** | Infinite transitions (typing dots, shimmer, pulse, breathing red dot) — do they pause when off-screen or when reduce-motion is enabled? | **PROBLEM** | **OPT-O03-11** (`FlashShareTargetSheet.kt:794` lacks reduceMotion check), **OPT-O03-12** (`HcComponents.kt:247`) |
| **9** | Modifier chains rebuilt on every recomposition | **PROBLEM** | **OPT-O03-13** (`FlashMessageBubble.kt:532` `BorderStroke` allocations per visible row) |
| **10** | Text layout cost and string formatting (`buildHighlightedMessageText`, Markdown parsing) | **NO PROBLEM** | Verified: `buildHighlightedMessageText` is correctly wrapped in `remember` across all call sites; no markdown parsing |
| **11** | Canvas allocations per frame inside DrawScope (Paths, Brushes, Paints created inside onDraw / drawBehind) | **PROBLEM** | **OPT-O03-14** (`FlashBrandAnimation.kt:64, 89, 169`), **OPT-O03-15** (`FlashSwarmPieceMap.kt:189`) |
| **12** | Layout passes: nested weights, IntrinsicSize, SubcomposeLayout use | **PROBLEM** | **OPT-O03-16** (`FlashQuotedReplyCard.kt:102` `IntrinsicSize.Min` doubles text layout passes) |
| **13** | BoxWithConstraints use on hot paths | **PROBLEM** | **OPT-O03-17** (`FlashBottomNav.kt:299`), **OPT-O03-18** (`FlashCallControlDock.kt:208`) |

---

## 4. Detailed Optimization Findings

### OPT-O03-01 — Whole-Row 60/120 FPS Recomposition in Share Target Scanning Dot via Composition Phase Alpha Read
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C/B   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashShareTargetSheet.kt:791-817`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashShareTargetSheet.kt`, `ScanningIndicatorRow` runs a 900 ms infinite pulse animation via `transition.animateFloat()`. The resulting `pulseAlpha` float is read directly in composition scope on line 816 via `Modifier.alpha(pulseAlpha)`. Because `Modifier.alpha(Float)` takes a raw float value, every frame of the animation invalidates and recomposes the entire `ScanningIndicatorRow` (Row, Box, Spacer, FlashText) at display refresh rate (60 Hz / 120 Hz) for as long as local network peer discovery is active.
- **Cost model:** 60 to 120 fps x (1 recomposition pass + 4 layout passes of Row/Box/Spacer/Text) x 10 seconds active scanning = 600 to 1,200 recompositions per share sheet open.
- **User-visible effect:** Unnecessary CPU utilization, thread contention, and battery drain while scanning for nearby share targets on mobile devices.
- **Expected gain:** 100% elimination of recompositions in `ScanningIndicatorRow`; animation execution shifted entirely into the GPU/Skia render node layer.
- **Proposed change:** Replace `Modifier.alpha(pulseAlpha)` on the Box with draw-phase layer property `Modifier.graphicsLayer { alpha = pulseAlpha }`.
- **Invariants touched (5.3):** none. Visual appearance and alpha pulsing remain identical.
- **Risk / effort:** Low risk; S effort (1 line change).
- **Prove it:** Layout Inspector recomposition counter on `ScanningIndicatorRow` drops from ~60/s to exactly 0 while scanning is active.
- **Verifier result:** survived
- **Related:** EXP-013 (per-frame animations moved to draw phase).

---

### OPT-O03-02 — Reaction Chip Frame-by-Frame Recomposition via Composition Phase State and Puff Reads
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashReactionChip.kt:107-131, 149, 170-171`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** When a reaction is toggled on a message, `FlashReactionChip` animates its background, border color, border width, and particle puff. `backgroundColor`, `borderColor`, and `borderWidth` are read in composition scope on lines 170-171 (`.background(backgroundColor)`, `.border(BorderStroke(borderWidth, borderColor))`). Furthermore, line 149 reads `if (puffProgress.value < 1f)` in the composable body. Because `puffProgress.value` updates every frame from 0f to 1f over 250 ms, `FlashReactionChip` recomposes 15 to 30 times during the toggle animation instead of running in layout/draw phases.
- **Cost model:** (60 - 120 frames/sec) x (1 recomposition pass of `FlashReactionChip` + `BorderStroke` allocation) x 0.25s animation duration = 15 to 30 recompositions per reaction toggle.
- **User-visible effect:** Micro-stutter when tapping reactions on low-end handsets (Infinix X6882B).
- **Expected gain:** 70–85% reduction in recomposition count during reaction tap animations.
- **Proposed change:** Wrap `puffProgress.value < 1f` in `derivedStateOf` or handle particle rendering via `drawWithContent` so the condition is not read during the main composable body pass.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Track recomposition count of `FlashReactionChip` during emoji reaction clicks with Layout Inspector.
- **Verifier result:** survived
- **Related:** EXP-013.

---

### OPT-O03-03 — Root ConversationScreen Invalidation on Every Keystroke via Draft Snapshot Read
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C/M   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:415, 428, 914, 917`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashConversationScreen.kt`, draft message text is stored as a snapshot state `var draft by remember { mutableStateOf("") }` directly in the root body of the 1,757-line composable function. On every keystroke typed by the user into `BasicTextField`, `onDraftChanged` writes to `draft`. On line 428, `val latestDraft by rememberUpdatedState(draft)` reads `draft` directly in the root scope of `FlashConversationScreen`, and line 914 reads `draft = draft`. Consequently, typing a message invalidates and recomposes the entire 1,757-line `FlashConversationScreen` restart scope, re-evaluating Scaffold, top bar, banner states, and pinned message derivations on every single character entered.
- **Cost model:** 1 keystroke x (1 full recomposition of `FlashConversationScreen` [1,757 lines] + top bar + pinned messages remember block) x (100 - 300 characters typed per message) = 100 to 300 full-screen recompositions per message drafted.
- **User-visible effect:** Keyboard input latency, dropped frames during rapid typing while scrolling or while background transfers are in flight on low-end Android devices.
- **Expected gain:** 100% elimination of `FlashConversationScreen` root scope recompositions during typing; keystroke recomposition confined exclusively to `FlashComposer`.
- **Proposed change:** Hoist draft state into an internal state holder object (e.g. `FlashComposerState`) passed to `FlashComposer`, or expose a stable interface where `onPersistDraft` reads the current value on disposal via state holder reference rather than reading a mutable state in the root screen's body.
- **Invariants touched (5.3):** Invariant 3 (Durability: draft text persistence on screen exit is preserved).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Layout Inspector shows `FlashConversationScreen` recomposition count stays constant while typing into the input field.
- **Verifier result:** survived
- **Related:** EXP-012 (shell-scope State read invalidation), UI-011.

---

### OPT-O03-04 — Lack of Compose Compiler Stability Configuration File for Domain Models and Collections
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C/M   **Runtime:** Android | Desktop
- **Component:** `gradle/libs.versions.toml`, `ui/chat/build.gradle.kts`, `ui/callui/build.gradle.kts`, `ui/theme/build.gradle.kts`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** The project uses Kotlin 2.2.10 and Compose Multiplatform, where Strong Skipping mode is active by default. However, there is no Compose compiler stability configuration file (`stabilityConfigurationFile`) anywhere in the repository. Standard Kotlin collection interfaces (`List<FlashMessageUi>`, `Set<String>`, `List<NearbyTrustedPeerUi>`) and cross-module model classes without `@Immutable` are classified as UNSTABLE. Under strong skipping, unstable parameters are compared by referential identity (`===`). When Room query flows emit new list instances or state flows copy collections, `oldList === newList` is false even when contents are identical, causing `FlashMessageList`, `FlashConversationScreen`, and `FlashNearbyScreen` to fail skipping and recompose their contents.
- **Cost model:** Upstream Flow emissions (Room invalidations, progress throttles, presence events) x referential inequality (`===`) on immutable lists = unnecessary recomposition cascades of entire list containers.
- **User-visible effect:** Increased frame drop rate during list fling when background updates (presence, progress, typing indicators) arrive.
- **Expected gain:** Enables skippability across all major composables receiving `List` and `Set` parameters when collection contents have not structurally changed.
- **Proposed change:** Create `compose_compiler_config.conf` with `kotlin.collections.*` and `com.transfer.flash.core.*.model.*` marked as stable/immutable, and wire `composeCompiler { stabilityConfigurationFile = ... }` in all UI modules.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Enable Compose compiler metrics (`reportsDestination`), inspect `composables.txt`: composables with `List` parameters flip from `unstable` to `stable` and become skippable.
- **Verifier result:** survived
- **Related:** `docs/ui/performance.md:44-48`.

---

### OPT-O03-05 — Fresh Lambda Allocations per Attachment Card per Recomposition in Message Bubble
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M/C   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt:379-421, 636-675`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In both `FlashBubbleSurface` and `FlashMessageBubbleUltraLow`, file and voice attachments are iterated using `forEach`. For every attachment, 7 new lambdas are allocated on every recomposition pass (`onCardClick = { onFileClick(file) }`, `onAccept = { onAcceptOffer(file) }`, `onDecline = { onDeclineOffer(file) }`, `onPause = { onPauseTransfer(file) }`, `onResume = { onResumeTransfer(file) }`, `onCancel = { onCancelTransfer(file) }`, `onActionClick = { ... }`). In `FlashMessageList.kt:230-244`, list-level callbacks were meticulously memoized via `remember(message.id)`, but at the attachment card level inside the bubble, lambdas capturing `file` are recreated on every recomposition.
- **Cost model:** (Number of visible attachments in conversation, ~10 - 30) x 7 lambdas x bubble recompositions (scroll, delivery status update, progress tick) = 70 to 210 object allocations per recomposition pass.
- **User-visible effect:** Main thread garbage collection churn and minor allocation spikes during fling through attachment-heavy message histories.
- **Expected gain:** 100% elimination of attachment lambda allocations inside `FlashMessageBubble`.
- **Proposed change:** Pass `file` directly to card callbacks (e.g. `onCardClick: (FlashFileAttachmentUi) -> Unit`) and pass the parent method reference, or memoize the per-attachment lambda block keyed on `file.id`.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Track object allocation count with Android Studio Memory Profiler during 500-message attachment scroll preset.
- **Verifier result:** survived
- **Related:** `FlashMessageList.kt:230-244` (BOLT memoization pattern).

---

### OPT-O03-06 — O(N) HashSet Structural Equality Check on Every LazyColumn Recomposition
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C/J   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageList.kt:104, 119`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashMessageList.kt`:
  Line 104: `val initialMessageIds = remember(messages.firstOrNull()?.id) { messages.mapTo(HashSet()) { it.id } }`  
  Line 119: `var previousTailId by remember(initialMessageIds) { mutableStateOf(messages.lastOrNull()?.id) }`  
  `initialMessageIds` is a `HashSet<String>` containing the ID of every message in the conversation. When `initialMessageIds` is used as a key in `remember(initialMessageIds)`, Compose evaluates `currentKey == previousKey`. For `java.util.HashSet`, `equals()` performs an element-by-element lookup across all entries in the set. On every recomposition of `FlashMessageList` (scroll offset changes, arrival of new messages, typing state updates), Compose executes an O(N) set equality check over hundreds or thousands of elements.
- **Cost model:** Recomposition rate (~10 - 30 Hz during scroll/animations) x O(N) HashSet comparisons with N = 100 to 2,000 items = up to 60,000 String/hash bucket operations per second on the Main Looper.
- **User-visible effect:** Frame time overhead and micro-jank on low-end hardware when scrolling through long message threads.
- **Expected gain:** Replaces O(N) HashSet equality check with O(1) String identity/equality check.
- **Proposed change:** Key `previousTailId` on `messages.firstOrNull()?.id` (or conversation ID) rather than the entire `initialMessageIds` HashSet.
- **Invariants touched (5.3):** none. Prevents state leak across conversation switches identically.
- **Risk / effort:** Low risk; S effort (1 line change).
- **Prove it:** Profile CPU time of `LazyColumn` recompositions in `FlashStressTestScreen` at the 2,000-message preset.
- **Verifier result:** survived
- **Related:** `docs/ui/performance.md:118-120`.

---

### OPT-O03-07 — Re-instantiation of GroupFileSender via DiscoveredEndpoints Remember Key
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C/M   **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/MainActivity.kt:1580`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `MainActivity.kt`, `val groupFileSender = remember(engine, chatRepository, discoveredEndpoints) { GroupFileSender(...) }` includes `discoveredEndpoints` in its remember key. `discoveredEndpoints` is a `List<FlashDiscoveredEndpoint>` updated whenever mDNS/NSD service discovery discovers, resolves, or drops an endpoint on the local network. Because `discoveredEndpoints` is passed as a remember key, `GroupFileSender` is instantiated anew on every discovery event, and downstream lambdas capturing it are re-evaluated.
- **Cost model:** Discovery events (mDNS resolve / TTL refresh every 10–30s) x `GroupFileSender` allocation + downstream lambda churn.
- **User-visible effect:** Minor CPU wake-up and heap churn during active LAN discovery.
- **Expected gain:** Prevents `GroupFileSender` re-allocation on network discovery events.
- **Proposed change:** Remove `discoveredEndpoints` from `remember` key; read discovered endpoints dynamically via lambda `deviceFor` inside `GroupFileSender`.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test verifying `GroupFileSender` remains stable across discovery state emissions.
- **Verifier result:** survived
- **Related:** Domain O10 (Discovery).

---

### OPT-O03-08 — Rapid-Fire Recompositions and List Allocation in Message Context Menu
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/M   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageContextMenu.kt:222-233`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashReactionQuickBar`, line 222 allocates `val quickReactions = listOf("❤️", "👍", "🔥", "😂", "😮", "🙏")` directly in the composable body. In line 226, `LaunchedEffect(Unit)` executes a loop `for (i in 1..(quickReactions.size + 1))` with `delay(25L)`, updating `visibleCount = i` seven consecutive times in 175 ms. Each step triggers a full recomposition of `FlashReactionQuickBar`, re-allocating the `listOf` and building fresh modifier chains for every reaction button on each 25 ms tick.
- **Cost model:** 7 recomposition passes in 175 ms x (1 `ArrayList` allocation + 7 `Modifier` chains) per context menu invocation.
- **User-visible effect:** Minor stutter upon opening long-press message actions overlay on older handsets.
- **Expected gain:** Hoist `quickReactions` to a top-level `val`; replace staged delay loop with a single staggered animation or layout-phase visibility.
- **Proposed change:** Move `quickReactions` to a top-level `constant` list and memoize item entrance specs.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Zero allocations of `quickReactions` list confirmed in profiler during context menu open.
- **Verifier result:** survived
- **Related:** UI-007.

---

### OPT-O03-09 — Shell-Scope Unmemoized Regex Mapping of Trusted Peers on ART Main Thread
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C/M   **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/MainActivity.kt:1490, 1495-1508`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `MainActivity.kt`, `trustedPeers` is collected as State in `FlashShell`. Lines 1495-1508 compute `val trustedPeerRoster = trustedPeers.map { trusted -> ... split("\\s+".toRegex()) ... }` directly in `FlashShell` composition scope, completely outside `remember` or `derivedStateOf`. Whenever `trustedPeers` emits (peer pairs, disconnects, updates profile), the entire ~750-line `FlashShell` recomposes and executes regex string splits for all trusted peers on the Android Main Looper, even when `showCreateGroup` is false (which is true >99.9% of the time).
- **Cost model:** Trust update events x (N peers x regex compilation & execution `split("\\s+".toRegex())` + string allocations + `FlashCreateGroupPeerUi` list creation) x execution in root `FlashShell` = 5 to 20 KB garbage allocated on the UI thread per trust event.
- **User-visible effect:** Frame drops and shell recomposition spikes during pairing completion or peer identity changes.
- **Expected gain:** 100% elimination of regex execution and roster allocations when the create-group dialog is closed.
- **Proposed change:** Compute `trustedPeerRoster` lazily only when `showCreateGroup` is true, or wrap in `remember(trustedPeers)`.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Shell recomposition counter does not increment when trusted peers update while create-group sheet is dismissed.
- **Verifier result:** survived
- **Related:** EXP-012.

---

### OPT-O03-10 — Root DesktopShell Invalidation via 8 Background and Settings StateFlows
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C/M/B   **Runtime:** Desktop
- **Component:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:1010, 1013, 1019, 1022, 1025, 1032, 1036, 1061-1063`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `DesktopShell.kt`, eight background and settings flows are collected directly in the root 2,975-line `DesktopShell` composable: `radioUiState`, `desktopSettings`, `enterpriseCredentials`, `hubStatus`, `hubSealingKeyState`, `hubDropped`, `orgLease`, and `orgIdentityMismatch`. Line 1062 constructs `val settings = remember(...) { FlashSettingsModel(...) }` keyed on all eight values. Whenever radio gateway packets arrive (1–5 Hz during active LoRa/BLE link) or hub drop counters tick, `DesktopShell` recomposes its entire root body, re-evaluating window bounds, sidebars, and tab containers even while the user is actively typing in the Chats tab.
- **Cost model:** (Radio packets [1–5 Hz] + Hub telemetry ticks) x (1 full recomposition of 2,975-line `DesktopShell` + Skia window invalidation) = 60 to 300 full desktop shell recompositions per minute while chatting.
- **User-visible effect:** High desktop idle CPU usage, elevated GPU/Skia render loop wake-ups, and fan spin on laptops while running in the background.
- **Expected gain:** Eliminates up to 90% of desktop shell recompositions during idle and radio packet reception.
- **Proposed change:** Defer flow collection into `FlashSettingsScreen` or isolate into a dedicated `DesktopSettingsProvider` composable so `DesktopShell` root does not subscribe to radio/hub telemetry flows.
- **Invariants touched (5.3):** none. Settings screen behavior remains intact.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Measure thread CPU utilization of desktop client via `ThreadMXBean.getCurrentThreadCpuTime()` during sustained radio packet reception: CPU drops by >30%.
- **Verifier result:** survived
- **Related:** EXP-011, EXP-012, Domain O19.

---

### OPT-O03-11 — Infinite Transition Lacks reduceMotion Guard in Share Target Scanning Dot
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B/J   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashShareTargetSheet.kt:794-802`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashShareTargetSheet.kt:794`, `rememberInfiniteTransition()` in `ScanningIndicatorRow` runs unconditionally without checking `FlashTheme.motion.reduceMotion`. Every other infinite transition in the design system (`FlashTypingIndicator.kt:65`, `FlashVoiceRecording.kt:293`, `FlashCallScreen.kt:358`, `FlashBrandAnimation.kt:128`) explicitly returns a static resting value when `reduceMotion` is active. This component violates project Invariant 7 (accessibility and reduce-motion) and causes continuous frame clock wake-ups on low-power devices.
- **Cost model:** 60/120 Hz animation clock wake-ups sustained continuously while the share target sheet is open on devices with "Remove animations" enabled in OS settings.
- **User-visible effect:** Motion sickness for users requiring reduced motion; avoidable battery drain during share target discovery.
- **Expected gain:** 100% elimination of frame ticks in `ScanningIndicatorRow` under reduce-motion.
- **Proposed change:** Guard `rememberInfiniteTransition` with `if (motion.reduceMotion) remember { mutableFloatStateOf(1f) } else rememberInfiniteTransition(...)`.
- **Invariants touched (5.3):** Invariant 7 (Accessibility and reduce-motion strictly honored).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test in `FlashThemeTokensTest` asserting `ScanningIndicatorRow` renders static alpha under `reduceMotion = true`.
- **Verifier result:** survived
- **Related:** Invariant 7, UI-037.

---

### OPT-O03-12 — Unconditional Infinite Transition animateFloat in HcDot
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B/C   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/competition/opencode/HcComponents.kt:247-254`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `HcDot`, `val transition = rememberInfiniteTransition(label = "hcPulse")` and `transition.animateFloat(...)` are instantiated unconditionally, accompanied by the comment: *"Unconditional, so the composition structure does not change when `pulsing` flips; the value is simply unused."* Line 254 then checks `val ring = if (pulsing && !reduce) phase else 0f`. In Compose, registering an `animateFloat` child animation on `rememberInfiniteTransition` causes the Compose animation clock (`withFrameNanos`) to tick continuously on every display frame, even when `pulsing` is false or `reduceMotion` is true. Furthermore, reading `phase` in the composable body forces `HcDot` to recompose on every frame when `pulsing` is true.
- **Cost model:** 60/120 Hz frame wake-ups per `HcDot` instance even when idle and not pulsing.
- **User-visible effect:** Background battery drain and CPU consumption on Hub competition screens.
- **Expected gain:** Halts Compose animation frame clock when `HcDot` is in resting state.
- **Proposed change:** Conditionally start `animateFloat` only when `pulsing && !reduce`, and read the resulting state inside `Canvas { ... }`.
- **Invariants touched (5.3):** Invariant 7.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Layout Inspector shows 0 frame ticks when `pulsing = false`.
- **Verifier result:** survived
- **Related:** Checklist item 8.

---

### OPT-O03-13 — Per-Row BorderStroke Allocation on Every Recomposition of Incoming Bubbles
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M/C   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt:537, 554`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashMessageBubble.kt:537`, for every incoming message bubble (`!message.isMine`), `selectionBorder = BorderStroke(FlashDimensions.borderHairline, colors.chatBorderIncoming)`. Because this instantiation sits in the body of `FlashBubbleSurface` without `remember`, a new `BorderStroke` object and underlying `SolidColor` brush are allocated on every single recomposition pass of every visible incoming message in the conversation.
- **Cost model:** (Number of visible incoming messages, ~10 - 20) x 1 `BorderStroke` allocation x recomposition passes during scroll = 20 to 100 transient allocations per scroll pass.
- **User-visible effect:** Minor GC allocation pressure during fast scrolling on 2GB RAM devices.
- **Expected gain:** 100% elimination of `BorderStroke` allocations for incoming bubbles.
- **Proposed change:** Remember `BorderStroke` at list level or inside `FlashBubbleSurface` using `colors.chatBorderIncoming` as key, or use `FlashTheme.shapes.incomingBorderStroke`.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Heap allocation tracking in `FlashStressTestScreen` shows 0 `BorderStroke` allocations during scroll.
- **Verifier result:** survived
- **Related:** `FlashMessageBubble.kt`.

---

### OPT-O03-14 — Canvas Allocations (Paths, Multiple Brushes, Array Spread) per Frame in Launch Splash
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C/M/T   **Runtime:** Android | Desktop
- **Component:** `ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashBrandAnimation.kt:64, 89-93, 169-185`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In `FlashBrandAnimation.kt`, during the 2.4s launch splash animation, the `Canvas` `onDraw` lambda allocates multiple heavy objects on every single display frame (60 to 120 FPS):
  1. Line 64: `Brush.verticalGradient(listOf(SplashBgTop, SplashBgBottom))` allocates a new `ArrayList` and a new `VerticalGradient` shader.
  2. Lines 89-93: `Brush.radialGradient(...)` allocates a `listOf` and a new `RadialGradient` shader.
  3. Lines 169-174: `val path = Path()` creates a new native Skia/Android `Path` and iterates over `BOLT_POINTS` using destructuring `(x, y)`.
  4. Lines 180-184: `Brush.linearGradient(*BoltStops, ...)` spreads the `BoltStops` array (causing a defensive array copy) and creates a new `LinearGradient` shader.  
  This per-frame allocation storm occurs during the most critical startup window when the transport stack, Keystore, and database are booting on low-end devices.
- **Cost model:** 60 to 120 fps x (1 native `Path` + 3 gradient `Brush` shaders + 3 `List`/`Array` instances = ~1.2 KB heap + native Skia allocations) x 2.4s animation = 144 to 288 frames; ~170 KB to 350 KB allocations on the Main Looper during cold start.
- **User-visible effect:** Cold-start frame jank, stutter in splash bolt animation, and CPU starvation for background database/network initialization.
- **Expected gain:** 100% elimination of per-frame `Path` and `Brush` allocations; renders via cached drawing commands.
- **Proposed change:** Convert `Canvas` to `Modifier.drawWithCache`, allocating the `Path` and gradient `Brush` instances once on size change and reusing them inside `onDrawBehind` / `onDrawWithContent` (matching the pattern used in `FlashTransfersScreen.kt:949` and `FlashInkSplash.kt:118`).
- **Invariants touched (5.3):** none. Preserves 100% visual fidelity and reduce-motion behavior.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Android Profiler CPU & Memory trace during launch splash: 0 `Path` and `Brush` allocations during splash playback.
- **Verifier result:** survived
- **Related:** `FlashTransfersScreen.kt:949`, EXP-013, Domain O01.

---

### OPT-O03-15 — Canvas Per-Frame Geometry Allocations in Active Swarm Piece Map
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M/C   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSwarmPieceMap.kt:189, 246-268`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In `FlashSwarmPieceMap.kt:189`, while blocks are in-flight downloading during a swarm transfer, the pulse layer `Canvas` runs on every display frame (60 Hz / 120 Hz). Inside the `Canvas` `onDraw` block, `val grid = PieceGrid(...)` is instantiated on every frame. The `PieceGrid` constructor allocates a `Size` object and a `CornerRadius` object. Inside the loop over in-flight blocks, `grid.fill` calls `offsetOf(index)` which allocates a new `Offset` object, and `inFlightColor.copy(alpha = pulseAlpha)` allocates a new `Color` value class for every in-flight block on every frame.
- **Cost model:** 60 to 120 fps x (1 `PieceGrid` + 1 `Size` + 1 `CornerRadius` + N `Offset` allocations with N = 10 to 50 in-flight blocks) = ~1,200 to 6,000 allocations per second during active swarm downloads.
- **User-visible effect:** Minor GC pauses and CPU waste while viewing swarm transfers on low-RAM handsets.
- **Expected gain:** Eliminates per-frame grid and geometry allocations; geometry cached on dimension change.
- **Proposed change:** Use `Modifier.drawWithCache` to pre-calculate block offsets and geometry, updating only the pulse alpha inside `onDrawWithContent`.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Heap allocation rate in swarm transfer benchmark drops by ~2 MB/min.
- **Verifier result:** survived
- **Related:** Domain O08 (Swarm).

---

### OPT-O03-16 — Double Text Measurement Pass via IntrinsicSize.Min in Quoted Reply Bubble
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashQuotedReplyCard.kt:100-118`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashQuotedReplyCard.kt:102`, the layout row uses `Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min))`. The purpose of `IntrinsicSize.Min` is to match the left 3dp vertical accent bar's `.fillMaxHeight()` to the height of the right-hand text Column. However, in Compose, `IntrinsicSize.Min` forces a multi-pass measurement: all children are measured once during the intrinsic query pass, and then measured a second time during the actual placement pass. Inside the Column, `FlashText` renders sender name and text snippet with `maxLines = 2` and `overflow = TextOverflow.Ellipsis`. Multi-line text measurement is computationally expensive, and executing it twice per quoted reply card inside `LazyColumn` directly degrades fling frame rates.
- **Cost model:** (Number of visible quoted replies in chat, ~2 - 8) x 2 measurement passes per item x 60/120 fps during list scroll.
- **User-visible effect:** Dropped frames and stutter when scrolling past messages with quoted replies.
- **Expected gain:** 50% reduction in measurement time for `FlashQuotedReplyCard`.
- **Proposed change:** Remove `IntrinsicSize.Min` from the Row. Render the 3dp vertical accent line via `drawBehind { drawRoundRect(...) }` on the Column, or use a custom single-pass `Layout` that measures the text Column first and sizes the indicator bar to match.
- **Invariants touched (5.3):** none.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Android Studio Layout Inspector / Macrobenchmark `frameDurationCpuMs` during scroll through the 2,000-message preset (which includes quoted replies every 7th message).
- **Verifier result:** survived
- **Related:** `docs/ui/performance.md:118`.

---

### OPT-O03-17 — SubcomposeLayout (BoxWithConstraints) in Bottom Navigation Bar
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C   **Runtime:** Android | Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/shell/FlashBottomNav.kt:299-312`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashBottomNav.kt:299`, the bottom navigation bar wraps its tabs in `BoxWithConstraints`. `BoxWithConstraints` is a `SubcomposeLayout`: it defers composition of its children to the layout measurement pass and maintains an independent subcomposition slot table. The sole reason `BoxWithConstraints` is used here is to read `maxWidth` on line 310 to compute `tabWidth = maxWidth / items.size` and `chipWidth`. This defers composition of the entire bottom navigation row into the layout phase on every tab transition and auto-hide scroll animation.
- **Cost model:** 1 `SubcomposeLayout` subcomposition pass on every navigation change and during scroll-driven bottom bar animations.
- **User-visible effect:** Slight delay in tab indicator animation and unnecessary layout pass overhead.
- **Expected gain:** Eliminates subcomposition slot table overhead; standard single-pass layout for bottom navigation bar.
- **Proposed change:** Replace `BoxWithConstraints` with a custom `Layout` or `Modifier.layout` (exactly as was done in `FlashMessageBubble.kt:93-118` when replacing per-bubble `BoxWithConstraints`).
- **Invariants touched (5.3):** none. Tab layout and animated pill indicator geometry remain identical.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Layout Inspector confirms 0 subcompositions in `FlashBottomNav`.
- **Verifier result:** survived
- **Related:** `FlashMessageBubble.kt:bubbleWidthCap`, ERROR-033.

---

### OPT-O03-18 — SubcomposeLayout (BoxWithConstraints) in Active Call Control Dock
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C   **Runtime:** Android | Desktop
- **Component:** `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallControlDock.kt:208-213`
- **Bottleneck class (transfer/media only):** N/A
- **What is wasteful:** In `FlashCallControlDock.kt:208`, `FlashCallControlDock` wraps the call control buttons in `BoxWithConstraints`. It reads `maxWidth` on line 213 exclusively to compute `val buttonSize = ((maxWidth - 16.dp) / count - 4.dp).coerceIn(...)`. Because `BoxWithConstraints` is a `SubcomposeLayout`, whenever call state changes (mute toggles, video upgrades, audio route shifts), composition of the buttons is deferred into the measure pass with a separate subcomposition table.
- **Cost model:** 1 `SubcomposeLayout` pass on every call state change during active audio/video calls.
- **User-visible effect:** Contributes to main-thread latency during call interactions on low-spec devices.
- **Expected gain:** Eliminates subcomposition pass; replaces with standard single-pass measurement.
- **Proposed change:** Replace `BoxWithConstraints` with a single-pass custom `Layout` that measures available width and sizes buttons in a single pass.
- **Invariants touched (5.3):** none. Button sizing behavior on 360dp narrow displays is strictly preserved.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Verify dock button layout on 320dp, 360dp, and tablet viewports with `FlashCallControlDockTest`.
- **Verifier result:** survived
- **Related:** `FlashCallControlDock.kt`.

---

## 5. "No Problem, Because..." Notes

During the audit, several critical areas were verified and confirmed to be already optimized or safe:

1. **`FlashCallScreen` 60 FPS Animation Reads (F69 verified):**  
   *No problem, because* `scale = rememberCallPulseScale(pulsing)` is read strictly inside `graphicsLayer { ... }` blocks (lines 398-412). The pulse scale does not unwrap `.value` in the `FlashCallIdentityBlock` composable body, avoiding the whole-screen 60 FPS recomposition loop that previously existed.
2. **`FlashGroupVideoGrid` Speaker Ring Pulse (F70 verified):**  
   *No problem, because* `speakerPulse.value` is read inside `Modifier.drawWithCache { onDrawWithContent { ... } }` (lines 212-219). The path outline is cached across frames and only the alpha stroke is re-evaluated during draw phase.
3. **`FlashSwipeToReplyContainer` Gesture Handling (F31 & H-19 verified):**  
   *No problem, because* swipe translation is applied via `Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }` (layout phase) and the reply icon scale/rotation is driven by `Modifier.graphicsLayer { ... }` (draw phase). Touch drag events are throttled via a conflated channel (`Channel<Float?>(Channel.CONFLATED)`), preventing coroutine flood.
4. **`FlashMessageList` List-Level Callback Hoisting (BOLT pattern verified):**  
   *No problem, because* list-level callbacks are hoisted with `rememberUpdatedState` (lines 153-166) and item-level callbacks are memoized via `remember(message.id)` (lines 230-244), preventing lambda allocations for top-level bubble interactions.
5. **Search Highlight Text Layout (`buildHighlightedMessageText`):**  
   *No problem, because* `buildHighlightedMessageText` is properly memoized via `remember(message.text, searchQuery, highlightColor)` in `FlashMessageBubble.kt:698`, `FlashChatSearchResults.kt:128`, and `FlashGlobalSearchResults.kt:101`. It does not re-execute during idle scroll passes.
6. **Shell-Scope Transfers Derivation (EXP-011 / EXP-012 verified):**  
   *No problem in `MainActivity.kt:1458`, because* `transfersUi` is wrapped in `derivedStateOf`. The read of `domainTransfers` occurs inside the derivation block, making it lazy and preventing transfer progress ticks from invalidating `FlashShell` while the Transfers tab is off-screen.
7. **Per-Message Entrance Animation (`rememberMessageEnterProgress`):**  
   *No problem, because* entrance animation alpha, scale, and translation are evaluated exclusively inside `Modifier.graphicsLayer { ... }` (lines 272-288). Non-animating historical messages bypass the render layer entirely (`val entering = animateEnter && !motion.reduceMotion`).
8. **Transfer Progress Bar Shimmer (`FlashTransfersScreen.kt:949`):**  
   *No problem, because* the linear gradient brush is built once per bar size in `drawWithCache` and translated during `onDrawWithContent`, and it is completely skipped when `reduceMotion` or `ultraLowUi` is active.

---

## 6. Summary of Findings & Recommended Fix Plan

### 6.1 Findings Ledger Summary
| ID | Title | Impact | Dimensions | Runtime | Risk / Effort |
|---|---|---|---|---|---|
| **OPT-O03-01** | ScanningIndicatorRow Composition Phase Alpha Read | Medium | J, C, B | Android / Desktop | Low / S |
| **OPT-O03-02** | Reaction Chip Recomposition via State & Puff Reads | Low-Med | J, C | Android / Desktop | Low / S |
| **OPT-O03-03** | ConversationScreen Invalidation on Composer Keystroke | **High** | J, C, M | Android / Desktop | Low / S |
| **OPT-O03-04** | Missing Compose Compiler Stability Config File | Medium | J, C, M | Android / Desktop | Low / S |
| **OPT-O03-05** | Unhoisted Lambdas per Attachment in Message Bubble | Medium | M, C | Android / Desktop | Low / S |
| **OPT-O03-06** | O(N) HashSet Equality Check in FlashMessageList | Medium | C, J | Android / Desktop | Low / S |
| **OPT-O03-07** | GroupFileSender Re-allocation on Discovery Key | Low-Med | C, M | Android | Low / S |
| **OPT-O03-08** | Reaction Context Menu Rapid Recompositions & List Alloc | Low | J, M | Android / Desktop | Low / S |
| **OPT-O03-09** | Shell-Scope Unmemoized Trusted Peers Regex Split | Medium | J, C, M | Android | Low / S |
| **OPT-O03-10** | DesktopShell Invalidation via 8 Background StateFlows | **High** | J, C, M, B | Desktop | Med / M |
| **OPT-O03-11** | ScanningIndicatorRow Ignores reduceMotion Invariant | Low-Med | B, J | Android / Desktop | Low / S |
| **OPT-O03-12** | Unconditional Infinite Transition in HcDot | Low | B, C | Android / Desktop | Low / S |
| **OPT-O03-13** | Per-Row BorderStroke Allocation on Incoming Bubbles | Low | M, C | Android / Desktop | Low / S |
| **OPT-O03-14** | Canvas Per-Frame Allocations in Launch Splash | **High** | J, C, M, T | Android / Desktop | Low / S |
| **OPT-O03-15** | Canvas Per-Frame Geometry Allocations in Swarm Piece Map | Low-Med | M, C | Android / Desktop | Low / S |
| **OPT-O03-16** | IntrinsicSize.Min Double Text Pass in Quoted Reply Card | Medium | J, C | Android / Desktop | Low / S |
| **OPT-O03-17** | BoxWithConstraints in Bottom Navigation Bar | Low-Med | J, C | Android / Desktop | Low / S |
| **OPT-O03-18** | BoxWithConstraints in Call Control Dock | Low-Med | J, C | Android / Desktop | Low / S |

### 6.2 Recommended Implementation Order (Fix Batches)

1. **Batch 1: Quick Wins (One commit each, zero risk, high return)**
   - `OPT-O03-14`: Convert `FlashBrandAnimation.kt` Canvas allocations to `drawWithCache`. Eliminates cold-start native Path/Brush churn.
   - `OPT-O03-01` & `OPT-O03-11`: Switch `ScanningIndicatorRow` in `FlashShareTargetSheet.kt` to `graphicsLayer` and add `reduceMotion` check.
   - `OPT-O03-06`: Key `previousTailId` on `messages.firstOrNull()?.id` in `FlashMessageList.kt:119`, eliminating O(N) HashSet equality comparisons.
   - `OPT-O03-09`: Wrap `trustedPeerRoster` regex mapping in `remember(trustedPeers)` in `MainActivity.kt:1495`.
   - `OPT-O03-13`: Memoize `BorderStroke` in `FlashMessageBubble.kt:537`.

2. **Batch 2: Scoping & Recomposition Decoupling (Medium return, low risk)**
   - `OPT-O03-03`: Isolate draft state read from `FlashConversationScreen` root body into `FlashComposer`. Stops 1,757-line recompositions per keystroke.
   - `OPT-O03-05`: Hoist attachment action lambdas inside `FlashMessageBubble.kt:380-420`.
   - `OPT-O03-16`: Replace `IntrinsicSize.Min` in `FlashQuotedReplyCard.kt` with a `drawBehind` vertical accent stroke.
   - `OPT-O03-04`: Add `compose_compiler_config.conf` to stability configuration for domain models and collections.

3. **Batch 3: Desktop Shell & Component Optimization (Medium effort)**
   - `OPT-O03-10`: Defer Settings and Radio telemetry StateFlow collection in `DesktopShell.kt` down to `FlashSettingsScreen`. Cuts desktop idle CPU.
   - `OPT-O03-17` & `OPT-O03-18`: Replace `BoxWithConstraints` in `FlashBottomNav` and `FlashCallControlDock` with single-pass custom `Layout`s.
   - `OPT-O03-15`: Cache block geometry in `FlashSwarmPieceMap.kt` via `drawWithCache`.
