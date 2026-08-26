# Transfers Page (P3 tab)

**Status:** IMPLEMENTED
**Component ID:** UI-047
**Last updated:** 2026-08-25
**Owner phase:** Phase 8 / App Shell & Pages Integration (`../ui-page-plan.md` P3)
**Depends on:** UI-001/002/037 (tokens), UI-016 card language (`FlashFileIconBadge`, `formatFileSize`), UI-025 empty states, UI-033 nav, UI-046 shell

---

## Component

`FlashTransfersScreen` (`ui/chat/src/main/java/com/transfer/flash/ui/transfers/FlashTransfersScreen.kt`)
+ models/logic in the same package. Replaces the removed experimental WS transfer page as the permanent
transfers surface (owner decision 2026-08-22).

## Purpose

One calm place where all of Flash's byte-movement lives: what is moving **right now** (active queue),
what went wrong (failed, with retry), and what already arrived/left (history with open/share/export).
Per ui-page-plan P3: consumes engine `transfers.activeTransfers` flows when Phase-8 wiring lands;
until then it renders a demo `TransfersUiState` exactly shaped like the future repository output so
wiring is a substitution, not a rewrite.

## Research sources

- [ServiceNow Horizon — Upload Manager screen](https://horizon.servicenow.com/native-mobile/screens/upload-manager-screen) — live status-summary bar; dedicated failed list with red error reason; per-item cancel; retry places item back in queue; completed items leave the queue quickly.
- [Bulk Upload UI with Queues, Partial Success and Retry (Filestack blog)](https://blog.filestack.com/bulk-upload-ui-queues-partial-success/) — four promises: visible queue, per-file state, honest partial success, retry scoped to failed rows ONLY; aggregate bars must be bytes-weighted not file-counted; keyed rows so one failure never re-renders/punishes others.
- [Background uploads that survive bad networks (oleg.is)](https://oleg.is/blog/background-uploads-mobile) — honest status vocabulary ("Paused · no signal", "Retrying in 2 min", "Failed · tap to retry"); one status model everywhere; pause with reason; never mark done before confirmation; stable IDs across restarts.
- [Design a File Download Manager (droidly.io)](https://droidly.io/sysdesign_filedownload) — history requirements: open/share from completed rows; checksum-verified completion shown as such; ≤250ms progress throttle to avoid UI thrash.
- [SambaLite transfer queue user guide](https://github.com/egdels/SambaLite/blob/main/docs/transfer_queue_user_guide.md) — stats-card counters (pending/active/completed/failed/cancelled); status-sorted sections; per-row action dialog (retry/cancel/remove); auto-cleanup policy for history.
- In-repo: UI-016 `FlashFileMessageCard` color-coded extension badges + circular progress rings; Dev Console transfer rows (progress bar + Pause/Resume/Cancel TX/RX) prove the control vocabulary users already saw in debug builds; RollingRateMeter fix (ERROR-015 era) gives honest speed numbers.

## Existing approaches studied

1. **Single aggregate progress bar** (bulk-upload anti-pattern): rejected — hides which file stalled;
   Filestack documents this as THE classic lying-progress mistake.
2. **Modal blocking manager** (ServiceNow foreground-blocking model): rejected — Flash is a messenger;
   transfers must coexist with chatting, not block it.
3. **Sectioned per-row queue** (SambaLite/Filestack synthesis): chosen — Active / Failed / History
   sections, each row self-describing with its own state vocabulary and scoped actions.

## What worked

- Per-row state machine with plain-language reasons; one vocabulary everywhere (oleg.is).
- Bytes-weighted progress + throttled updates (≤250ms) — matches our Dev Console behavior.
- Scoped retry: only failed rows re-enter; completed work is sacred.
- Reusing UI-016's `FlashFileIconBadge` keeps visual language identical between chat bubbles and the
  transfers surface — one mental model of "a file".
- History rows lead with Open/Share (droidly.io download-manager requirement).

## What did not work

- Aggregate-only progress (see above).
- Auto-dismissing completed rows instantly (ServiceNow does this) — Flash users share files onward;
  history must persist for open/share/export via SAF.
- Blocking modal managers — incompatible with concurrent chat.

## Chosen approach

```text
FlashTransfersScreen(state, callbacks…)
├── Header: "Transfers" (headingSmall) + summary chips row (N active · M failed) when nonzero
├── LazyColumn sections (sticky-free, simple headers):
│   ├── ACTIVE  — rows: badge(48dp) | name / meta(peer · transport glyph) / 4dp progress bar
│   │             speed · ETA line; trailing Pause⇄Resume + Cancel
│   ├── FAILED  — same anatomy; red error reason line; trailing Retry (+ dismiss)
│   └── HISTORY— completed rows; size · date · verified check; trailing Share/Open
├── States: empty → FlashEmptyState(TransfersFirstRun, CTA→Nearby tab)
│           loading → skeleton rows (72dp geometry match) ; error → FlashErrorState(Retry)
└── All rows keyed by transferId (stable across restarts — oleg.is rule)
```

Model:

```kotlin
enum class FlashTransferState { Queued, Active, Paused, Completed, Failed }
enum class FlashTransferDirection { Send, Receive }
data class FlashTransferItemUi(
    id: String, fileName: String, direction, peerName: String,
    bytesTotal: Long, bytesDone: Long,
    state: FlashTransferState, speedBytesPerSec: Long = 0,
    etaSeconds: Long? = null, timestampMs: Long = 0,
    errorMessage: String? = null, verified: Boolean = false,
    transportLabel: String? = null,     // "Wi-Fi Direct" etc.
)
data class TransfersUiState(active, failed, history, isLoading, isError)
```

## Why it was chosen

It is the smallest honest surface that satisfies every §22 transfer-UI requirement (name, size, bytes,
percent, speed, average-speed-capable, ETA, connection type, pause/resume, error/retry) while matching
the genre research consensus (sectioned per-file queue). Demo-first shape means zero throwaway code:
the exact `TransfersUiState` becomes the C5 flow mapping target.

## Visual specification

| Element | Token |
|---|---|
| Background | `backgroundApp`; cards `backgroundSurface`, radius12 (`attachment`) |
| Progress track | `backgroundSurfaceSubtle` height 4dp radiusFull |
| Progress fill | `accentPrimary`; Paused→`statusTransfer` (spark); Failed→`textError` |
| Extension badge | `FlashFileIconBadge` verbatim (category colors) |
| Section label | `captionEmphasis`, `textTertiary`, uppercase |
| Error reason | `captionDefault` `textError` |
| Verified tick | `statusSuccess`-tinted Check icon 14dp beside size |

## Interaction specification

- Tap active row: nothing (v1) — controls are explicit buttons; tap history row: Open (SAF).
- Trailing buttons ≥40dp effective targets inside 64–72dp rows.
- Cancel needs no confirm (resume-capable engine makes cancel cheap) — matches SambaLite simplicity.

## Animation specification

| Effect | Spec |
|---|---|
| Branch swap (loading ⇄ empty ⇄ error ⇄ populated) | `AnimatedContent` targeting a private `TransfersPageState` **enum** derived by a pure `pageState()` helper — never the whole `TransfersUiState`, or every 250ms progress tick would restart the crossfade |
| Row entrance | `motion.rememberStaggerProgress(index, key)` read inside `graphicsLayer` (alpha + small rise), literal per-item indices, capped at 6 steps so long queues do not cascade |
| Section hop | `Modifier.animateItem(motion.messagePlacementSpec(), motion.messageFadeOutSpec())` — a row keyed by id migrates ACTIVE → FAILED → HISTORY and glides instead of teleporting |
| Progress fill | `animateFloatAsState(fraction, motion.tweenNormalSpec())` — smooths 250ms-throttled jumps |
| Aggregate throughput | `animateFloatAsState(FlashTransfersMath.aggregateSpeed(active), tweenNormalSpec())` rolls the header number, so a burst reads as acceleration and a pause visibly drops the total. Paused/queued rows contribute 0 — no stale sum left behind. Gated with `if (motion.reduceMotion) snap() else …` because `tween*Spec()` does **not** self-collapse |
| Pause⇄Resume icon swap | `AnimatedContent` + `motion.statusCrossfade()` |
| Row press | `Modifier.flashPressScale(interactionSource)` paired with `clickable(indication = null)` — house press feel, no Material ripple |
| Summary chips | `AnimatedVisibility` fade/slide when counts hit zero/nonzero |
| Reduce-motion | Enter/Exit tokens and `rememberStaggerProgress` collapse themselves; the two `tween*Spec()` call sites gate explicitly |

## Gesture specification

None in v1 (swipe-actions deferred; explicit buttons pass a11y review first). RTL mirrors automatically.

## Accessibility requirements

- Rows merge semantics: "<fileName>, Receiving, 42 percent, 3.2 megabytes per second, 1 minute left, Paused button, Cancel button".
- Progress conveyed as text percent (not color-only); state words never color-only.
- 48dp touch targets; section labels are headings for TalkBack navigation.

## Responsive behavior

Single column phone-first; expanded-width two-pane consumption deferred to UI-034 pass (checklist step 6).

## Dark-mode behavior

Token-driven; spark `statusTransfer` for paused reads well on surface1 (audited hue family from UI-035).

## Performance considerations

Keyed rows + per-row animatable fraction ⇒ progress ticks recompose only the changed row.
LazyColumn throughout; no shadows/blur. Speed/ETA strings computed in pure helpers (JVM-testable,
allocation-light).

## Implementation notes

- Files: `ui/chat/src/main/java/com/transfer/flash/ui/transfers/FlashTransfersScreen.kt`
  (models + `FlashTransfersMath` + screen + demo sampler for wiring/previews);
  tests `ui/chat/src/test/java/com/transfer/flash/ui/transfers/FlashTransfersLogicTest.kt`.
- Edits: `FlashStateViews.kt` += `EmptyKind.TransfersFirstRun` (+copy/icon branch; compiler-forced).
- Dependencies added: **none**. Engine wiring (C5) substitutes the demo state later — API identical.
- Post-UI-046 fix pass (2026-08-25): the page owns its status-bar inset on **every** branch
  (`modifier.fillMaxSize().statusBarsPadding()` computed once and handed to error/loading/empty/populated —
  the empty branch previously dropped the caller's modifier); rows animate section hops with
  `Modifier.animateItem(motion.messagePlacementSpec(), motion.messageFadeOutSpec())` since a row keyed
  by id migrates ACTIVE → FAILED → HISTORY; `RowIcon` now fires `FlashHaptic.Tick` and declares
  `Role.Button`.
- Shell-motion pass (2026-08-25): the screen takes `listState: LazyListState` and `bottomInset: Dp` from
  the host. The state is hoisted in `MainActivity` because `FlashAnimatedScreen` disposes the outgoing
  page on every tab hop — a page-local `rememberLazyListState()` would silently reset scroll on each
  switch and make the shell's re-select-to-top impossible. `bottomInset` goes into the `LazyColumn`'s
  `contentPadding` so rows scroll under the hanging capsule with the last row still clearing it.
  Added `FlashTransfersMath.aggregateSpeed(active)` (pure, JVM-tested) behind the header's rolling
  throughput number.

## Testing checklist

- [x] JVM: grouping, percent clamp, speed/ETA formatting, badge collapse rules
- [ ] Compose preview: light/dark, populated/empty/error/loading
- [ ] Physical device: live C5 flow once wired; pause/resume round-trip
- [ ] Physical device: pausing one active row visibly drops the header total
- [ ] Physical device: scroll position survives a tab hop; re-selecting Transfers scrolls to top
- [ ] Physical device: rows scroll under the hanging capsule, last row reachable
- [ ] Large font: two-line clamping intact at fontScale 1.3+
- [ ] Reduced motion: entrance snaps, throughput number jumps

## Known limitations

- Demo data until C5 repository flow exists (Phase-8 wiring step 3 of ui-page-plan checklist).
- No swipe actions, no multi-select batch ops yet.
- Average speed column deferred (§22 lists it "where useful") — current speed + ETA cover v1.
- Scroll position survives rotation but not process death (host `remember`, see UI-046 limitations).

## Future improvements

- Live C5 wiring + notification deep-links (C5.12).
- Batch retry-all-failed chip; SAF export-all for history selection.
- Per-row odometer byte counters (the header total already rolls; individual `bytesDone` still snaps).

## What makes this Flash?

Transfer managers elsewhere shout with giant rings and gradients; Flash treats bytes like messages —
the same quiet file badge you saw in-chat, a 4dp pulse-teal thread filling toward done, honest
one-line status sentences, and a radar-green verified tick the moment BLAKE3 confirms integrity.
Calm, continuous, unmistakably the same app.
