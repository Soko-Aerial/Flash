# Progress Log — Stream D (rvw22-d-ui)

## 2026-10-22 — Stream D: Chat and Call UI Medium/Low Audit Fixes

### Worked on
Fixed all assigned MEDIUM and LOW findings from `docs/audit/2026-10-22-comprehensive-brutal-code-review.md` in Stream D (`rvw22-d-ui`):
- F73 (MEDIUM): `FlashImageDecoder.jvm.kt` cache key omits `isVideo`.
- F52 (MEDIUM): `FlashCallShare.kt` `host.listSources()` unhandled exception in `produceState`.
- F48 (MEDIUM): `FlashConversationScreen.kt` `jumpToMessage` scroll offset off-by-one when peer typing.
- F49 (MEDIUM): `FlashConversationScreen.kt` multi-select forward omitting media/file attachments.
- F47 (MEDIUM): `FlashVideoPlayer.kt` and `FlashVideoSurface.android.kt` subsequent replays failing to seek to 0.
- F75 (LOW): `FlashMessageBubble.kt` missing `remember` on `buildHighlightedMessageText`.
- F76 (LOW): `FlashMessageBubble.kt` un-remembered per-bubble lambdas re-allocated on recomposition.
- F77 (LOW): `FlashAttachmentStagingTray.kt` staged tray key contains index and risks duplicate URI collision.
- Section 7 Dead Code: `FlashCallVideoSurface.jvm.kt` unused shim parameter `zOrderMediaOverlay`.

### Changed
1. **F73 (commit `6c00b730`):**
   - Added `isVideo` to `cacheKey` in `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt`.
   - Added unit test in `FlashImageDecoderJvmTest.kt`.
2. **F52 (commit `f1511a36`):**
   - Added `safeListSources` helper in `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallShare.kt` catching non-cancellation `Throwable` and returning `emptyList()` while cleanly rethrowing `CancellationException`.
   - Added unit test in `FlashCallShareTest.kt`.
3. **F48 (commit `74e45709`):**
   - Added `FlashChatScrollMath` (`hasTypingBubble`, `messageListIndexOffset`, `targetScrollIndex`) in `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageList.kt`.
   - Updated `FlashConversationScreen.kt` to derive jump scroll target dynamically from typing state.
   - Added unit test in `FlashChatScrollLogicTest.kt`.
4. **F49 (commit `8efa0773`):**
   - Added `FlashShareTargetMath.buildMultiForwardPayload` and `hasUnforwardableAttachments` in `FlashShareTargetSheet.kt`.
   - Updated `onForward` in `FlashConversationScreen.kt` to collect text and all ready media/file/voice items, prompting if items are undownloaded.
   - Added unit test in `FlashShareTargetMathTest.kt`.
5. **F47 (commit `8d05bc37`):**
   - Added `onSeekHandled: () -> Unit = {}` to `FlashVideoSurface` common expect and android/jvm actuals.
   - Dispatched `currentOnSeekHandled()` upon completing seek in `FlashVideoSurface.android.kt`.
   - Added `FlashVideoPlayerMath` with `onPlayClick` and `onSeekHandled` in `FlashVideoPlayer.kt`, resetting `seekToTarget = null` once handled.
   - Added unit test in `FlashVideoPlayerMathTest.kt`.
6. **F75 (commit `db017d71`):**
   - Wrapped `buildHighlightedMessageText` in `remember(message.text, searchQuery, highlightColor)` in `FlashMessageBubble.kt`.
   - Added unit test in `FlashChatSearchHitsTest.kt`.
7. **F76 (commit `a64e22fb`):**
   - Wrapped 7 per-bubble interaction lambdas in `remember(inSelectionMode)` and hoisted underlying callbacks via `rememberUpdatedState` in `FlashMessageBubble.kt` to avoid stale captures while eliminating re-allocations.
8. **F77 (commit `d9aa9ce0`):**
   - Added `id: String = "${uri}_${kotlin.random.Random.nextLong().toString(16)}"` to `FlashShareItemUi`.
   - Updated `FlashAttachmentStagingTray.kt` `LazyRow` to key on `{ it.id }`.
   - Updated `FlashConversationScreen.kt` to remove by `it.id != item.id`.
   - Added unit test in `FlashStagingMathTest.kt`.
9. **Section 7 Dead Code (commit `ff8ae0be`):**
   - Added `@Suppress("UNUSED_PARAMETER")` with documentation explaining multiplatform expect contract to `FlashCallVideoSurface.jvm.kt`.

### Verification
- **Unit test execution:**
  - `FlashImageDecoderJvmTest`: PASS
  - `FlashCallShareTest`: PASS
  - `FlashChatScrollLogicTest`: PASS
  - `FlashShareTargetMathTest`: PASS
  - `FlashVideoPlayerMathTest`: PASS
  - `FlashChatSearchHitsTest`: PASS
  - `FlashMessage*` tests: PASS
  - `FlashStagingMathTest`: PASS
- **Mutation check:**
  - F73: Reverting `isVideo` in cache key failed `FlashImageDecoderJvmTest` with `AssertionError`.
  - F52: Removing try/catch in `safeListSources` failed `FlashCallShareTest` with `IllegalStateException`.
  - F48: Setting scroll offset to 0 failed `FlashChatScrollLogicTest` with `AssertionError`.
  - F49: Omitting attachment items failed `FlashShareTargetMathTest` with `AssertionError`.
  - F47: Making `onSeekHandled` return `0L` failed `FlashVideoPlayerMathTest` with `AssertionError`.
  - F77: Colliding `id` on same URI failed `FlashStagingMathTest` with `AssertionError`.
- **Full build verification suite:**
  - `:ui:chat:jvmTest :ui:callui:jvmTest`
  - `:ui:chat:testAndroidHostTest`
  - `:desktop:compileKotlinJvm`
  - `:app:compileDebugKotlin`
  All unit tests pass; mutation checks confirmed; NOT device-verified (owed tests logged in `backlog-additions.md`).

### Remaining
- Device verification for R22D-01..08 on physical Android devices and desktop runs.

### Next AI
Run device tests R22D-01 through R22D-08 from `backlog-additions.md` when hardware is connected and merge/rebase branch `agent/rvw22-d-ui` to dev.
