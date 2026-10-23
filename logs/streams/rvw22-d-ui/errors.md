# Errors and Defects — Stream D (rvw22-d-ui)

## ERROR-790 — FlashImageDecoder.jvm.kt cache key omits isVideo (F73)

### Date
2026-10-22

### Area
ui/platform-shims (Desktop JVM)

### Symptoms
When a video file thumbnail and a still image at the same file path are decoded, the cached still image is returned for the video (or vice versa), causing wrong rendering or aspect ratios.

### Root cause
`JvmImageDecoder.cacheKey` used `"$source:$maxDimension"` and omitted `isVideo`.

### Working fix
Changed `cacheKey` in `FlashImageDecoder.jvm.kt` to `"$source:$maxDimension:$isVideo"`. Added unit test in `FlashImageDecoderJvmTest.kt`. Mutation check verified test failure without `isVideo`.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-791 — FlashCallShare.kt host.listSources() unhandled crash in produceState (F52)

### Date
2026-10-22

### Area
ui/callui

### Symptoms
If `host.listSources()` throws an exception (e.g. platform permission denial, display disconnected), the unhandled throwable in `produceState` crashes the call screen composable.

### Root cause
`produceState(initialValue = emptyList())` called `host.listSources()` directly without catching non-cancellation exceptions.

### Working fix
Extracted `safeListSources` helper in `FlashCallShare.kt` that catches non-cancellation `Throwable` and returns `emptyList()`, while cleanly rethrowing `CancellationException`. Added unit test in `FlashCallShareTest.kt`. Mutation check verified.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-792 — jumpToMessage scroll offset off-by-one when peer typing (F48)

### Date
2026-10-22

### Area
ui/chat

### Symptoms
In `FlashConversationScreen`, jumping to a quoted message scrolls to the wrong item (shifted by 1) or to the typing indicator when `peerTypingName != null`.

### Root cause
`FlashMessageList` prepends a typing indicator item before the messages when `peerTypingName != null`. The scroll calculation in `FlashConversationScreen` did not account for this item offset.

### Working fix
Extracted `FlashChatScrollMath` with `hasTypingBubble`, `messageListIndexOffset`, and `targetScrollIndex`. `FlashConversationScreen` derives its jump index offset dynamically from the same condition (`peerTypingName != null`). Unit-tested in `FlashChatScrollLogicTest.kt`. Mutation check verified.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-793 — Multi-select forward drops attachments and reports "Nothing to forward" (F49)

### Date
2026-10-22

### Area
ui/chat

### Symptoms
In multi-select mode in `FlashConversationScreen`, selecting messages containing only images, files, or voice notes caused the forward action to display "Nothing to forward" because only `message.text` was forwarded.

### Root cause
`onForward` filtered `selectedTexts = localMessages.filter { it.id in selectedMessageIds }.map { it.text }.filter { it.isNotBlank() }` and checked `selectedTexts.isEmpty()`, ignoring attachment items.

### Working fix
Implemented `FlashShareTargetMath.buildMultiForwardPayload` and `hasUnforwardableAttachments`. Collects text and all ready media/file/voice items into `FlashSharePayloadUi`. Shows informative message if attachments are undownloaded, and launches share target sheet with full payload. Added unit test in `FlashShareTargetMathTest.kt`. Mutation check verified.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-794 — FlashVideoPlayer subsequent replays fail to seek to 0 (F47)

### Date
2026-10-22

### Area
ui/chat & ui/platform-shims

### Symptoms
On replaying a video for the second time, the player does not seek back to 0 and playback restarts at the end or fails to rewind.

### Root cause
`FlashVideoPlayer.kt` set `seekToTarget = 0L` on replay click. Because `seekToTarget` was never cleared back to `null`, on subsequent replays `seekToTarget = 0L` produced no state change in Compose (`0L == 0L`), so `LaunchedEffect(seekToMs)` was skipped.

### Working fix
Added `onSeekHandled: () -> Unit = {}` to `FlashVideoSurface` expect and actuals. In `FlashVideoSurface.android.kt`, calls `currentOnSeekHandled()` after `vv.seekTo(seek.toInt())`. In `FlashVideoPlayer.kt`, resets `seekToTarget = FlashVideoPlayerMath.onSeekHandled()`. Added unit tests in `FlashVideoPlayerMathTest.kt`. Mutation check verified.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-795 — Missing remember on buildHighlightedMessageText in FlashMessageBubble (F75)

### Date
2026-10-22

### Area
ui/chat

### Symptoms
In chat search mode, every recomposition of `FlashMessageBubble` recomputed `buildHighlightedMessageText` with full regex range matching and annotated string allocations, causing GC churn during list scrolling.

### Root cause
`buildHighlightedMessageText` was invoked directly in the composable body without `remember(message.text, searchQuery, highlightColor)`.

### Working fix
Wrapped `buildHighlightedMessageText` in `remember(message.text, searchQuery, highlightColor)`. Added unit test in `FlashChatSearchHitsTest.kt`.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-796 — Un-remembered per-bubble lambdas re-allocated each recomposition (F76)

### Date
2026-10-22

### Area
ui/chat

### Symptoms
In `FlashMessageBubble.kt`, 7 attachment interaction lambdas were re-allocated on every bubble recomposition even when selection mode did not change.

### Root cause
`imageClick`, `fileClick`, `acceptOffer`, etc. evaluated an un-memoized conditional lambda expression on every recomposition.

### Working fix
Wrapped lambdas in `remember(inSelectionMode)` and hoisted underlying callbacks into `rememberUpdatedState` to avoid stale captures while eliminating re-allocations.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-797 — Attachment staging tray keys on index and lacks unique item identity (F77)

### Date
2026-10-22

### Area
ui/chat

### Symptoms
1. Removing a staged card recreated all subsequent cards in `FlashAttachmentStagingTray` because the key contained the list index.
2. If two staged items shared the same URI, using URI as a key would crash `LazyRow` due to duplicate keys.

### Root cause
Key function was `key = { index, item -> "${item.uri}_$index" }`. `FlashShareItemUi` lacked a unique stable identity.

### Working fix
Added `id: String = "${uri}_${kotlin.random.Random.nextLong().toString(16)}"` to `FlashShareItemUi`. Updated `LazyRow` to `key = { it.id }`, and `FlashConversationScreen` to remove by `it.id != item.id`. Added unit test in `FlashStagingMathTest.kt`.

### Status
OPEN (unit-tested, not device-verified)


## ERROR-798 — Unused expect/actual shim parameter on desktop video surface (Section 7 Dead Code)

### Date
2026-10-22

### Area
ui/callui

### Symptoms
Compiler/IDE warning about unused parameter `zOrderMediaOverlay` in `FlashCallVideoSurface.jvm.kt`.

### Root cause
The parameter is required to match the common `expect` signature for Android SurfaceView layering, but desktop renders to Compose Canvas.

### Working fix
Annotated `FlashCallVideoSurface` actual with `@Suppress("UNUSED_PARAMETER")` and documentation explaining the multiplatform contract.

### Status
OPEN (unit-tested, not device-verified)
