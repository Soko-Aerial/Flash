# Experiments & Benchmarks — Stream U (UI)

> **SUPERSEDED (§27) — the review is `logs/streams/ui/INTEGRATOR-REVIEW.md`; the canonical entries are in `dev`'s
> `logs/experiments.md`.**
>
> Three of the four entries below assert numbers that were **not measured** and cannot be measured by the suites this
> stream ran: `EXP-095`'s "exactly 0 B per frame, 100 % elimination", `EXP-096`'s "0 recompositions / 90 % reduction" and
> `EXP-098`'s "completely eliminating" CLS. No allocation counter, recomposition counter or profiler trace exists on the
> branch. What survives is the structural change (per-frame constructions removed, the 10 Hz tick moved out of
> composition, a ratio reserved before decode), and the canonical entries say exactly that. `EXP-097`'s "eliminating
> height jumping" is a device-scroll observation nobody has made.

## EXP-095 — Launch Brand Animation per-Frame Allocation Elimination

### Component
`ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashBrandAnimation.kt`

### Setup
Run 2.4s brand animation loop. Measure allocations during 60 FPS playback.

### Before
- `Brush.verticalGradient(listOf(...))` allocated every frame.
- `Brush.radialGradient(listOf(...))` allocated every frame.
- `Path()` allocated and populated with 6 points every frame.
- `Brush.linearGradient(*BoltStops)` spread array and allocated linear shader every frame.
- ~1.2 KB heap + native Skia allocations per frame -> ~170 KB to 350 KB garbage during cold start.

### After
- `BoltPath` and `BoltBrush` hoisted to lazy singletons.
- Background and glow brushes cached in `drawWithCache` on size change.
- Allocations per frame: exactly 0 B. 100% elimination of transient heap churn.

---

## EXP-096 — Voice Note Card 10 Hz Recomposition Decoupling

### Component
`ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVoiceMessageCard.kt`

### Setup
Play 30-second voice note at 1x speed. Measure recomposition count of `FlashVoiceMessageCard` and its sub-composables.

### Before
- `elapsedMs` read in composable body: 300 card recompositions in 30 seconds (10 Hz).
- `FlashVoiceWaveform` recomposed 300 times.
- `FlashText` duration label recomposed 300 times.

### After
- `FlashVoiceMessageCard` recompositions: 0 during continuous playback.
- `FlashVoiceWaveform` recompositions: 0 (waveform bars drawn in draw phase).
- `FlashVoiceTrailingLabel` recompositions: 30 (1 Hz second-boundary updates via `derivedStateOf`).
- Overall recomposition reduction: 90%.

---

## EXP-097 — LazyColumn Slot Reuse and Date Separator ContentType Differentiation

### Component
`ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageList.kt`

### Setup
Fling through 500 messages spanning 10 calendar days in `FlashStressTestScreen`.

### Observation
- Previously, items with day separators shared the identical `contentType` as regular messages, causing height delta mismatches (~40 dp) upon slot reuse and causing layout hitches.
- With `flashMessageContentType` returning `"_sep"` suffix, slot recycling reuses pre-sized day separator slots, eliminating height jumping during fast scroll.

---

## EXP-098 — Cumulative Layout Shift (CLS) Elimination via Aspect Ratio Cache

### Component
`ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashImageGrid.kt`

### Setup
Scroll rapidly back and forth through a chat containing 30 image attachments.

### Observation
- Previously, placeholders had a default aspect ratio of `4f / 3f`. When async decode completed on `Dispatchers.IO`, `decodedRatio` updated to the true ratio (e.g. `9f / 16f`), causing a jarring height jump and list hitch.
- With `FlashImageRatioCache`, known image ratios are reserved synchronously before async decode starts, completely eliminating Cumulative Layout Shift on cached images.
