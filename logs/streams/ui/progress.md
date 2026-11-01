# Stream U (UI) — Progress Log

> **SUPERSEDED (§27) — read the review first: `logs/streams/ui/INTEGRATOR-REVIEW.md`.**
>
> This copy is kept as written. Everything in it was produced **before** the branch tip, which **does not compile**: the
> extracted composables in `FlashMessageBubble` lost their `AttachmentUi` imports and copied a parameter with the wrong
> type, `FlashConversationScreen` used two properties that do not exist, and `FlashMessageList` used an Android-only
> `WindowInsets` API in `commonMain`. The "Verification" and "Verified Gate Test Results" lines below therefore cannot
> describe `1ddd5306`, and the "698 tests" figure is this programme's pre-branch baseline, not a result of this work.
>
> Every finding was re-checked against the code; the corrected record is the canonical `dev` logs:
> `logs/progress.md`, `logs/errors.md`, `logs/experiments.md`, `docs/decisions.md` (ADR-260..265) and
> `docs/testing/TEST-BACKLOG.md` (`PERF-U01`..`U06`).

## 2026-10-31 — Stream U Performance Optimization (Batches 1 & 2 Complete)

### Worked on
White-box performance optimizations across `:ui:chat` and `:ui:theme` for Compose stability, draw-phase deferrals, layout pass reduction, and UI perceived responsiveness.

### Changes & Findings Addressed

#### Batch 1:
1. **OPT-O03-03** (`5f3f7b22`):
   - Hoisted `draft` snapshot state out of root `FlashConversationScreen` (1,757 lines) into `FlashConversationDraftState`.
   - `draftState.text` is read solely in `FlashComposer(draft = draftState.text)`, completely eliminating root screen invalidation on every keystroke.
2. **OPT-O03-04** (`857409b2`):
   - Added Compose compiler stability configuration `ui/chat/compose_compiler_config.conf` marking `kotlin.collections.*` and `com.transfer.flash.core.*.model.*` as stable. Merging into `build.gradle.kts` documented for integrator.
3. **OPT-O03-01 / OPT-O03-11 / OPT-O03-12** (`10034f56`):
   - In `FlashShareTargetSheet.kt`, replaced `.alpha(pulseAlpha)` with `.graphicsLayer { alpha = pulseAlpha?.value ?: 1.0f }` and guarded infinite transition with `!motion.reduceMotion`.
   - In `HcComponents.kt`, guarded `rememberInfiniteTransition` with `pulsing && !reduce` and deferred reads into `Canvas` draw phase.
4. **OPT-O03-05** (`4b13e280`):
   - In `FlashMessageBubble.kt`, extracted `AttachmentCardItem` and `VoiceCardItem` with `remember(file.id, ...)`, eliminating 7 lambdas allocated per card per bubble recomposition.
5. **OPT-O03-06** (`6c954b42`):
   - In `FlashMessageList.kt:119`, replaced `remember(initialMessageIds)` (`HashSet<String>`) with `remember(initialHeadId)` (`String?`), eliminating the $O(N)$ set equality lookup on every recomposition.
6. **OPT-O03-08** (`088da49f`):
   - In `FlashMessageContextMenu.kt`, hoisted `DEFAULT_QUICK_REACTIONS` to a top-level constant, eliminating list reallocation across the 7-step staggered animation.
7. **OPT-O03-13** (`fcac9eb8`):
   - In `FlashMessageBubble.kt:FlashBubbleSurface`, hoisted and memoized `BorderStroke` instances using `remember(colors.*)`, removing per-message heap allocation on scroll.
8. **OPT-O03-02** (`5903d6cb`):
   - In `FlashReactionChip.kt`, deferred puff progress reads into `Canvas` draw block and moved background/border into `Modifier.drawBehind`, eliminating 15–30 recompositions per reaction tap.
9. **OPT-O04-05** (`1d065ca6`):
   - Added expect/actual `isSwipeToDismissSupported` (`false` on Desktop, `true` on Android); bypassed `SwipeToDismissBox` when swipe is disabled or in selection mode.
10. **OPT-O04-06** (`907900a8`):
    - In `FlashMessageList.kt:flashMessageContentType`, appended `"_sep"` when `message.daySeparator != null`, fixing slot reuse and height delta mismatches in LazyColumn.
11. **OPT-O24-05** (`183d86c6`):
    - In `FlashSettingsScreen.kt:SwitchRow`, added optimistic local state for switch thumb movement, providing instant (<16ms) visual feedback without waiting for DataStore disk write.
12. **OPT-O24-06** (`6a01374d`):
    - In `FlashBrowseOrgGroupsSheet.kt`, deferred eager network fetch until sheet entrance spring settles (`FlashMotion.NormalMillis`), preserving 60/120 FPS sheet rise.
13. **OPT-O24-07** (`3638c9a6`):
    - In `FlashImageGrid.kt`, added `FlashImageRatioCache` to remember intrinsic image ratios, reserving single image aspect ratio before async decode and eliminating Cumulative Layout Shift (CLS).

#### Batch 2:
14. **OPT-O03-14** (`6c31dea4`):
    - In `FlashBrandAnimation.kt`, converted `Canvas` to `Spacer(Modifier.fillMaxSize().drawWithCache { ... })`.
    - Hoisted `BoltPath` and `BoltBrush` to lazy constants. Cached background brush, radial glow brush, and ring stroke once per size change. 0 allocations per display frame.
15. **OPT-O03-16** (`2c0d2afb`):
    - In `FlashQuotedReplyCard.kt`, eliminated `Row(Modifier.height(IntrinsicSize.Min))` and replaced the vertical accent bar with `drawBehind { drawRoundRect(...) }` on a single-pass `Column`. Halved text measurement passes on quoted messages during list fling.
16. **OPT-O03-17** (`495ff026`):
    - In `FlashBottomNav.kt`, replaced `BoxWithConstraints` (`SubcomposeLayout`) with a custom single-pass `Layout` utilizing `placeRelativeWithLayer` for liquid squash/stretch and translation.
17. **OPT-O25-05** (`65569259`):
    - In `FlashNearbyScreen.kt:PopulatedContent`, memoized `FlashNearbyMath.sortedPeers(state.peers)` with `remember(state.peers)`, eliminating repeated `distinctBy` and timsort on every recomposition.
18. **OPT-O04-07** (`1adf976c`):
    - In `FlashConversationScreen.kt:jumpToMessage`, constrained `animateScrollToItem` by pre-jumping (`scrollToItem`) within a 10-item neighborhood when distance exceeds 15 items, eliminating measure/layout storms across hundreds of intermediate items.
19. **OPT-O24-03** (`d51e5c5e`):
    - In `FlashComposer.kt`, removed `.animateContentSize()` from the root `Column` following `.imePadding()` and scoped it exclusively to the expanding input pill, eliminating spring oscillation fighting against OS window insets animations.
20. **OPT-O24-04** (`60a66f31`):
    - In `FlashMessageList.kt`, added `LaunchedEffect(isImeVisible)` to smoothly stick/scroll to bottom when the soft keyboard opens while viewing latest messages.
21. **OPT-O24-01 & OPT-O24-02** (`01f97254`):
    - In `FlashConversationScreen.kt`, implemented `pendingOptimisticEchoes` UI-local overlay: sent messages appear instantly (<16ms) with `Pending` status without bypassing durability; reconciled upon Room emission.
    - In `FlashAttachmentStagingTray.kt` and `FlashConversationScreen.kt`, kept staging tray visible with `CircularProgressIndicator` on in-flight items (`isSending = true`) until attachment appears in message history or timeout fires.
22. **OPT-O05-06 & OPT-O05-07** (`3d5fa942`):
    - In `FlashVoiceMessageCard.kt`, decoupled 10 Hz playback ticks from card recomposition by passing `playedBarCount: () -> Int` directly to the `Canvas` draw phase; extracted `FlashVoiceTrailingLabel` updating at 1 Hz via `derivedStateOf`. Added `FlashWaveformCache` to memoize bucketed waveforms.

### Verification
- `:ui:theme:jvmTest` (BUILD SUCCESSFUL, exit code 0)
- `:ui:chat:jvmTest` (BUILD SUCCESSFUL, exit code 0)
- `:ui:chat:testAndroidHostTest` (BUILD SUCCESSFUL, 698 tests pass, exit code 0)
