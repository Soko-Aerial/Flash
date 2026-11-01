# Architectural Decisions — Stream U (UI)

> **SUPERSEDED (§27) — the review is `logs/streams/ui/INTEGRATOR-REVIEW.md`; the canonical ADRs are ADR-260..265 in `dev`'s
> `docs/decisions.md`.** Three corrections to what follows: **ADR-260**'s config file is wired into **no build file**, so
> it does nothing yet; **ADR-261** merged a version that matched echoes by text and concatenated them into the durable
> list, which the review replaced with the one-echo-per-new-row-id rule (its "<16 ms" is unmeasured); and **ADR-264**'s
> "90-100 %" is unmeasured.

## ADR-260 — Compose Compiler Stability Configuration for Collections and Domain Models

### Decision
Create `ui/chat/compose_compiler_config.conf` with `kotlin.collections.*` and `com.transfer.flash.core.*.model.*` marked as stable classes.

### Context
Strong Skipping is active in Kotlin 2.2.10 Compose Multiplatform. Unstable collection interfaces (`List`, `Set`) cause referential inequality (`old === new` -> false) on Flow emissions even when items are structurally unchanged, defeating skipping in `FlashMessageList` and `FlashConversationScreen`.

### Boundary Notice
Creation of the config file is owned by Stream U; wiring via `composeCompiler { stabilityConfigurationFiles.add(...) }` in `build.gradle.kts` is deferred to integrator merge per strict boundary rules.

---

## ADR-261 — Optimistic Local Echo as a UI-Local Overlay Only

### Decision
Render sent messages immediately (<16ms) in `localMessages` with status `FlashMessageStatus.Pending` using an in-memory overlay (`pendingOptimisticEchoes`), while delegating durable insertion to `onSendText` / `RealFlashChatRepository`. Never mark optimistic messages as `Sent` or bypass durability.

### Context
Without local echo, the composer blanks out immediately while SQLite commit, WAL fsync, and Room's full-thread query take 50ms–350ms, leaving a frozen gap where no bubble appears. The UI-local overlay decouples instant visual confirmation from background disk latency while preserving 100% durability and Room outbox reconciliation.

---

## ADR-262 — Single-Pass Layout for Floating Bottom Navigation Pill

### Decision
Replace `BoxWithConstraints` in `FlashBottomNav` with a custom single-pass `Layout` utilizing `Placeable.PlacementScope.placeRelativeWithLayer`.

### Context
`BoxWithConstraints` is a `SubcomposeLayout` that defers composition of its children to layout pass and maintains an independent subcomposition slot table solely to query `maxWidth`. A single-pass `Layout` measures the chip with fixed dimensions and positions it via `translationX` and `scaleX/scaleY` inside the draw/layer update phase, eliminating subcomposition overhead.

---

## ADR-263 — Elimination of `IntrinsicSize.Min` in Quoted Reply Cards

### Decision
Replace `Row(Modifier.height(IntrinsicSize.Min))` and vertical accent `Box` with `Column(Modifier.drawBehind { drawRoundRect(...) }.padding(start = 3.dp + space8))`.

### Context
`IntrinsicSize.Min` forces a dual measurement pass over all children in Compose. In `FlashQuotedReplyCard`, measuring multi-line text twice per card during fast scrolling degrades list fling frame rates. Using `drawBehind` aligns the accent line to the exact height of the single-pass Column in the draw phase.

---

## ADR-264 — Decoupling 10 Hz Audio Progress from Composable Recomposition

### Decision
Pass `playedBarCount: () -> Int` as a state lambda to `FlashVoiceWaveform` and read it inside the `Canvas` draw block. Update the time readout label in `FlashVoiceTrailingLabel` at 1 Hz via `derivedStateOf`.

### Context
Audio position updates at 10 Hz (`TICK_MS = 100L`). Reading `elapsedMs` in the composable body forced 600 card recompositions per minute for a time label that only changes once per second. Moving waveform reads to draw phase and memoizing bucketed waveforms via `FlashWaveformCache` cuts recompositions by 90–100%.

---

## ADR-265 — Distance-Bounded Pre-Jump in Chat Search and Quote Jump

### Decision
When the jump target distance $|target - current| > 15$, invoke `listState.scrollToItem(preJumpIndex)` to fast-forward within 10 items before running `animateScrollToItem`.

### Context
Unconstrained `animateScrollToItem` across hundreds of items forces LazyColumn to compose, measure, and place every intermediate item, causing severe main-thread frame drops during deep search jumps.
