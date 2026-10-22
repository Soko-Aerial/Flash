# Test Backlog Additions — Stream D (rvw22-d-ui)

## R22D-01 — Video Thumbnail vs Image Decoder Cache Separation (F73)
- **Setup:** Launch Desktop app. Place a video file `video.mp4` and a still image at the same relative path or name in the media cache.
- **Steps:**
  1. Trigger decoding of the video thumbnail via `FlashImageDecoder.decode(uri, maxDimension, isVideo = true)`.
  2. In the same session, trigger decoding of an image with `isVideo = false`.
- **Pass condition:** Video thumbnail and still image do not return identical cached drawables; both render correctly according to their source type without aspect ratio distortion or blank placeholder.
- **Source:** F73 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO

## R22D-02 — Screen Share Source Listing Error Handling (F52)
- **Setup:** Run call UI on desktop or Android. Simulate or trigger a permission restriction or display disconnect when screen sharing sources are requested.
- **Steps:**
  1. In an active call, tap the Share Screen button to open `FlashSharePicker`.
  2. Deny display capture permission or cause `host.listSources()` to throw an exception.
- **Pass condition:** The call screen does not crash; `FlashSharePicker` gracefully displays an empty list or dismisses without tearing down the call session.
- **Source:** F52 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO

## R22D-03 — Message List Quoted Reply Jump When Peer Typing (F48)
- **Setup:** Two devices (Device A and Device B) connected in 1:1 chat. Device A has 30+ messages with quoted replies.
- **Steps:**
  1. On Device B, type in the message composer so the typing indicator appears on Device A.
  2. While Device B's typing indicator is active on Device A, tap a quoted reply header in a message to jump to the original message.
- **Pass condition:** The list scrolls smoothly to the exact quoted target message, not shifted down by one item or landing on the typing indicator bubble.
- **Source:** F48 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO

## R22D-04 — Multi-Select Forward of Media and File Attachments (F49)
- **Setup:** 1:1 or group conversation with sent/received photos, documents, and voice messages.
- **Steps:**
  1. Long press a message to enter multi-select mode.
  2. Select 2 messages containing an image and a file attachment (no plain text).
  3. Tap the Forward action icon in the contextual top bar.
- **Pass condition:** The share target sheet opens displaying "2 items" with the combined file size summary, instead of showing "Nothing to forward". Selecting a recipient forwards all staged attachments.
- **Source:** F49 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO

## R22D-05 — In-App Video Player Subsequent Replays Seek to Beginning (F47)
- **Setup:** Android device with video file in chat. Tap video to open full-screen `FlashVideoPlayer`.
- **Steps:**
  1. Allow video to play to completion (or scrub to end). Tap the center replay button. Verify replay starts from 0:00.
  2. Allow video to play to completion a second time. Tap the center replay button again.
  3. Scrub to middle, let play to completion a third time. Tap bottom bar play/replay button.
- **Pass condition:** Every replay attempt seeks back to 0:00 and plays from the start of the video without stalling at the end.
- **Source:** F47 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO

## R22D-06 — Search Mode Frame Allocation Benchmark (F75)
- **Setup:** Android Studio Profiler attached to app on physical device. Chat with 500+ messages containing recurring keywords.
- **Steps:**
  1. Open chat search bar and enter a common search term (e.g. "the" or "meeting").
  2. Fling-scroll through search hits for 10 seconds while recording Java/Kotlin allocations in Profiler.
- **Pass condition:** Memory allocation rate is below 5 MB/s during scroll; no GC pause spikes exceeding 8ms; `buildHighlightedMessageText` is only invoked when messages enter composition, not re-evaluated continuously.
- **Source:** F75 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO

## R22D-07 — Message Bubble Lambda Allocation Benchmark (F76)
- **Setup:** Attach Android Studio Profiler / Compose Tracing to app with 100 messages containing mixed media attachments.
- **Steps:**
  1. Enter and exit selection mode repeatedly (10 times).
  2. Scroll through message list with Profiler tracking object allocations.
- **Pass condition:** Bubble lambdas (`imageClick`, `fileClick`, etc.) are only allocated once per selection mode toggle, not re-allocated on unrelated recompositions or item draws.
- **Source:** F76 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO

## R22D-08 — Staging Tray Item Removal and Duplicate URI Staging (F77)
- **Setup:** Open message composer on Android device.
- **Steps:**
  1. Pick the same photo twice from the attachment picker so two identical URIs appear in the staging tray.
  2. Verify no crash occurs on adding the second item (`LazyRow` duplicate key check).
  3. Tap the 'x' remove button on the first card.
- **Pass condition:** The first card disappears cleanly; the second card remains with its preview and state intact without visual glitches or list recomposition flicker.
- **Source:** F77 (2026-10-22 Comprehensive Code Review)
- **Status:** TODO
