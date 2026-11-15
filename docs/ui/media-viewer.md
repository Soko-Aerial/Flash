# Component Research & Design Document — Media Viewer

**Status:** IMPLEMENTED  
**Component ID:** UI-018  
**Last updated:** 2026-11-15

**Owner phase:** Premium Chat UI — media track (after UI-017 image grid)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

## Unified media viewer and playback refresh · 2026-11-15

**Status:** IMPLEMENTED. Android device acceptance is tracked by MEDIA-PLAY-01 and MEDIA-PLAY-02. Photo and video pages keep one edge-to-edge media stage and one set of controls. A video poster stays visible while the decoder prepares. The first decoded frame takes over the same fitted area, and the close control, album count, caption, and Save/Share/Forward actions stay in place. The bottom dock adds the timeline once playback starts. Taps toggle chrome during active playback, and paging away releases playback. Android Back closes the viewer from either state.

The stage uses Flash's graphite backdrop, with a translucent dark dock and fine highlight edge so controls remain readable over bright footage. A softly blurred, dimmed copy of the already decoded poster fills letterbox space when a poster is available and the performance tier allows it. The primary play control echoes the small translucent close and album controls. Typography and touch targets use the current Flash system. Unavailable and unplayable video keeps the poster, with a system-player action for the latter.

Compared the existing framework `VideoView`, a custom framework `MediaPlayer` plus `TextureView`, and Media3 ExoPlayer plus `TextureView`. Media3 provides the first-frame, playback, buffering, error and surface lifecycle events needed for one viewer. Use only its Android playback artifact and Flash's Compose chrome. Keep pause/resume ownership in the viewer and pause when the Android host stops. [Media3 surface guidance](https://developer.android.com/media/media3/ui/surface) · [Playback lifecycle](https://developer.android.com/media/media3/exoplayer/hello-world)

### Desktop player recommendation

For a small Windows installer, use Windows Media Foundation's `IMFMediaEngine` behind `FlashVideoSurface`. The OS supplies the playback stack, so the Flash package would carry only the interop code. Start with local H.264/AAC MP4, then measure the capability of each target OS for HEVC, AV1 and less common containers. Make sure decoded frames can sit underneath Compose controls before committing to the interop approach. The same seam can use AVFoundation on macOS or a distro-provided `libmpv` on Linux. [Media Foundation playback API](https://learn.microsoft.com/en-us/windows/win32/api/mfmediaengine/nf-mfmediaengine-imfmediaengine-play) · [Windows codec baseline](https://learn.microsoft.com/en-us/windows/win32/medfound/supported-media-formats-in-media-foundation) · [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer) · [Debian libmpv package](https://packages.debian.org/sid/libmpv2)

| Option | Observed packaging footprint and tradeoff |
|---|---|
| Windows Media Foundation | Uses system decoders. Only the bridge would add to Flash's archive. Its actual size and overlay integration still need a packaged prototype. |
| Existing JCodec | The `0.2.5` JAR already ships with Flash (2,068,570 bytes), so reuse has zero marginal dependency size. It already handles thumbnails and voice notes. Its software decoder and narrower video support make it a poor fit for general playback. [JCodec format and performance notes](https://github.com/jcodec/jcodec) |
| Bundled libmpv | Broad codec coverage and an embeddable engine. The published Windows x64 LGPL v0.42.0 ZIP is 56,277,779 bytes (53.7 MiB), including the assets supplied by that build. A purpose-built package could be smaller. Measure its complete runtime and maintain the corresponding build and licenses. [Release and artifacts](https://github.com/mpv-player/mpv/releases/tag/v0.42.0) · [Windows build options](https://github.com/mpv-player/mpv/blob/master/DOCS/compile-windows.md) |
| Bundled libVLC | Mature embedding API. The Windows x64 VLC 3.0.22 portable archive is 39,098,035 bytes (37.3 MiB). That archive includes the app and serves as a reference, so the installer delta for a curated libVLC plugin bundle needs its own measurement. [Portable artifacts](https://download.videolan.org/pub/videolan/vlc/3.0.22/win64/) · [Native plugin layout](https://github.com/videolan/vlc/blob/master/doc/BUILD-win32.md) |

Recorded 2026-11-15. Package sizes are published archive sizes or the locally resolved JAR. Neither libmpv nor libVLC archive size predicts the Flash installer delta. Desktop currently offers the system-player action from the shared viewer.

---

## Component

`FlashMediaViewer` — full-screen immersive photo viewer overlay opened from `FlashImageGrid` tiles, with a `FlashMediaPage` zoomable page inside a `HorizontalPager` album carousel, chrome (top counter bar + bottom action bar), and vertical swipe-to-dismiss.

## Purpose

Tapping a photo tile in a conversation must open the image full-screen so users can inspect detail: pinch/double-tap zoom, pan while zoomed, swipe through the album, save/share/forward the photo, and dismiss by swiping down or Back. This is the "open → inspect → return" loop required by every premium messenger; without it the UI-017 grid is a dead end.

Appears in: `FlashConversationScreen` (opened from `FlashImageGrid.onImageClick` in `FlashMessageBubble`).

## Research sources

- Android Developers — Compose gestures: `detectTransformGestures`, `detectTapGestures`, low-level `awaitEachGesture` / `awaitPointerEventScope` (official gesture & pointer-input documentation).
- Jetpack Compose documentation — `androidx.compose.foundation.pager` (`HorizontalPager`, `PagerState`, `rememberPagerState`, `beyondViewportPageCount`), stable since Compose Foundation 1.4.x; still stable in the version resolved by this project (Foundation **1.10.0** via `compose-bom = 2025.12.00` — verified by inspecting `foundation-android-1.10.0.aar` classes: `androidx.compose.foundation.pager.*` present; `androidx.compose.foundation.gestures.zoomable.*` **absent** from this release).
- Android Developers — shared element transitions (`Modifier.sharedElement`, `SharedTransitionScope` — `@ExperimentalSharedTransitionApi` in the current release).
- Reference app behavior (interaction patterns only, no code copied): Telegram (full-screen pager + swipe-down dismiss + chrome auto-hide), WhatsApp (drag-to-dismiss with scaling + dim animation, counter `3/7`), Signal (pager + back/save chrome), Google Photos (claim-policy model: zoomed pan owns gestures, un-zoomed vertical drag dismisses, un-zoomed horizontal drag pages).

## Existing approaches studied

### Approach A — Stable Foundation pager + custom pointer-input zoom state (selected)

`HorizontalPager` (stable API) hosts one `FlashMediaPage` per photo. Each page owns a small state holder (`FlashZoomState`: `Animatable` scale + `Animatable` offset) driven by one custom `awaitEachGesture` pointer-input with an explicit gesture **claim policy** (section "Gesture specification"). Dismiss is the same claim policy on the un-zoomed vertical axis. Chrome toggles via `detectTapGestures` single/double-tap. Open/close uses the existing reserved `FlashMotion.mediaOpenEnter()` / `mediaOpenExit()` tokens.

### Approach B — `androidx.compose.foundation.gestures.zoomable` (`ZoomableState`)

Compose Foundation publishes a first-party `ZoomableState`/`Modifier.zoomable` in **alpha** channels only. Verified against the artifact actually resolved by this project (`foundation-android-1.10.0`): the `zoomable` package is not present, so adopting it would mean bumping the whole Compose stack to an alpha BOM just for one component — rejected by the dependency rules (no alpha churn, master plan §10).

### Approach C — Third-party zoomable libraries

| Library | License | Verdict |
|---|---|---|
| `io.coil-kt.telephoto:zoomable` (Coil ecosystem) | Apache-2.0 | Mature API (Coil 2.x/3.x `zoomable()`), single-purpose, actively maintained — closest viable third-party option. |
| `com.github.SmartToolFactory` Compose zoomable suites | Apache-2.0 (per artifact) | Rich features but multi-artifact, API churn, mixed maintenance. |
| `PhotoView` (legacy View) via `AndroidView` | Apache-2.0 | View-world widget; defeats Compose recomposition, shared chrome, and unit-testable math. |

All rejected under AGENTS.md §3 / master plan §10: they add binary dependencies for a ~250-line gesture-state problem the stable Foundation pointer-input APIs fully cover, and they would fork our motion system onto someone else's springs.

### Approach D — Compose shared-element hero transition (`Modifier.sharedElement`)

True shared-element morph from grid tile to full screen. Rejected for the initial version: requires `@ExperimentalSharedTransitionApi` + `SharedTransitionScope` plumbing across `FlashMessageList` → `FlashMessageBubble` → tile → viewer, has documented edge cases with `LazyColumn` recycler bounds (keys recycled while open) and with zoomed/dismissed sibling states, and adds regression risk to already-accepted components (UI-005/UI-006/UI-017). The open/close feel is instead delivered by the existing `FlashMotion.mediaOpenEnter/Exit` scale+fade contract. Listed under Future improvements.

## What worked

- **Claim-policy gesture model** (Google Photos behavior): exactly one gesture family owns the pointer at any time; ownership is decided at touch-slop time by axis + zoom state. This eliminates every pager-vs-zoom-vs-dismiss conflict.
- **Pager counter pill** (WhatsApp): `3/7` position indicator is the most legible album affordance.
- **Chrome auto-hide on single tap** (Telegram): immersive inspection without permanent chrome.
- **Rubber-band springs** on over-zoom/over-pan release (universal in premium viewers) instead of hard clamps during the gesture.

## What did not work

- Two overlapping `detectTransformGestures` scopes (one per page + one for dismiss) — pointer ownership races when both stay attached unconditionally.
- Naive `scale + offset` math without anchor correction: double-tap would zoom from screen center instead of the tap point, and edge-pans would leave blank gaps (rejected in design; centered-anchor math specified instead).
- Blocking pager while loading: dismissing/hiding the viewer before decode completes must cancel the coroutine — `produceState` keyed to the URI handles this naturally.
- Full-resolution decode for every photo — OOM risk on albums; an `inSampleSize` guard (max ~4k on the long edge) is required.

## Chosen approach

Approach A: stable `HorizontalPager` + per-page custom `FlashZoomState` with an explicit claim policy, vertical drag-to-dismiss on the un-zoomed axis, tap-to-toggle chrome, counter + action chrome with Flash tokens, `mediaOpenEnter/Exit` open/close contract, `inSampleSize`-guarded decode. Zero new dependencies; UI-local models only.

## Why it was chosen

- Uses only APIs proven present in the project's resolved Compose version (verified from the 1.10.0 artifact — section "Research sources").
- All gesture math lives in pure Kotlin functions → unit-testable without instrumentation (Flash testing standard, cf. `FlashImageGridLogicTest`).
- No dependency additions (AGENTS.md §3, master plan §10).
- Reuses UI-037 motion tokens (`mediaOpenEnter/mediaOpenExit`, `springDefaultSpec`, `tweenNormalSpec`) — no ad-hoc timings.
- Keeps UI-005/017 untouched except threading `onImageClick`.
- ADR-005 applies: all visible styling through `FlashColors`/`FlashTypography` semantic tokens (two new semantic tokens added — section "Visual specification").

## Initial visual specification

This records the original photo viewer. The unified media viewer section above defines the current translucent controls.

All values reference tokens; raw values below are new **semantic tokens**, not scattered magic numbers.

### Backdrop
- New token `FlashColors.mediaViewerBackdrop` — near-black in **both themes**: light `#FF0A0C0E`, dark `#FF050607` (dark bullish, not pure black, matches ADR-005 graphite void). The viewer is deliberately always dark regardless of system theme (photo viewing readability; Telegram/WhatsApp behavior).
- Backdrop alpha during dismiss drag: `1f → 0f` mapped from dismiss progress (0 → 1).
- During dismiss, pages scale `1f → 0.94f` and translate with the finger.

### Chrome (top + bottom bars)
- Both bars: transparent backgrounds (no glassmorphism per ADR-005 avoid list) over the backdrop; text/icons white at 92% (`Color.White.copy(alpha = 0.92f)`) — constant over the dark backdrop.
- Top bar: `statusBarsPadding()`; leading close action = `FlashIcons.Close` (48dp touch target, `FlashDimensions.minTouchTarget`); center counter `FlashTypography.metadataEmphasis` `"3 / 7"`; trailing `FlashIcons.More` placeholder for future overflow actions.
- Bottom bar: `navigationBarsPadding()`; metadata row `FlashTypography.metadataDefault` → `senderName • timeLabel`; action row → `FlashIcons.Download` (export/save), `FlashIcons.Share` (**new Flash-owned icon**, section "Implementation notes"), `FlashIcons.Forward` (forward). 48dp targets each, spaced `FlashSpacing.space8`.
- New token `FlashColors.mediaViewerChromeText = Color.White` (92% applied at use).
- Counter active page uses `FlashColors.accentPrimary` for the current index part of the label only when dimmed state is off — kept white-92 in v1 for chrome cohesion; accent reserved for the focus ring on save success flash (UI-041 follow-up).

### Page
- Photo centered, `ContentScale.Fit` at scale 1.
- Behind the bitmap while loading: seed-gradient placeholder identical to `FlashImageTile` fallback (reuses `FlashImageAttachmentUi.seedColor`).
- Failure: `FlashIcons.Failed` tinted `FlashColors.mediaViewerChromeText`, caption `metadataDefault` "Couldn't load image", tap-retry via same gesture scope.

## Interaction specification

| Input | Un-zoomed (scale = 1) | Zoomed (scale > 1) |
|---|---|---|
| Single tap | Toggle chrome visibility | Toggle chrome visibility |
| Double tap | Zoom to `2.3×` anchored at tap point | Animate back to `1×` |
| Horizontal drag (1 finger) | `HorizontalPager` pages album | Pan image |
| Vertical drag (1 finger) | Dismiss drag (page tracks finger) | Pan image |
| Pinch (2 fingers) | Claim zoom (min 1×) | Zoom (clamp 1×–4×, +0.35 resistance overshoot) |
| Back gesture / button | Dismiss viewer | Dismiss viewer (after snap-reset zoom) |
| Release while zoomed & out of bounds | — | Rubber-band spring back to clamped bounds |

- Zoom state is **per page**; swiping pages springs the outgoing page back to `1×` so re-entry is always clean.
- Counter tracks `PagerState.currentPage` (snap updates after settle).
- Dismiss cancels cleanly from any state: Back → animate zoom reset if zoomed → overlay exit via `mediaOpenExit`.

## Animation specification

All specs via `FlashTheme.motion` (UI-037):

| Animation | Trigger | Target | Spec | Interruptible |
|---|---|---|---|---|
| Viewer open | Image tile tap | Overlay enter | `motion.mediaOpenEnter()` (fade + 0.92→1 scale, `slowMillis`, Decelerate) | Yes |
| Viewer close (tap/back) | Back / close / <threshold release | Overlay exit | `motion.mediaOpenExit()` | Yes |
| Viewer close (drag) | Dismiss release over threshold | Offset exits with `motion.springDefaultSpec()`, backdrop fade `motion.tweenFastSpec()` | Yes |
| Double-tap zoom | Double tap | Scale 2.3× / 1×, offset re-centered | `motion.springDefaultSpec()` | Yes (tap_new restarts `Animatable`) |
| Pinch rubber-band | Release outside clamp | Scale/offset back to clamp | `motion.springDefaultSpec()` | Yes |
| Chrome toggle | Single tap | Bars slide ±`space16` + fade | `motion.statusCrossfade()` semantics (crossfade via `AnimatedVisibility` with `tweenNormalSpec` slide) | Yes |
| Page position snap | Swipe settle | `HorizontalPager` default pager physics | Pager default | — |
| Reduce-motion (`motion.reduceMotion`) | — | All tweens collapse to 0ms via existing tokens; pager snaps | — | — |

## Gesture specification

Single custom pointer-input per page using `awaitEachGesture`; claim policy constants (unit-tested):

```text
TOUCH_SLOP            = viewConfiguration.touchSlop          (~8dp device-dependent, read at runtime)
VERTICAL_CLAIM_FACTOR = 2.0f    // vertical displacement must exceed 2× touch slop AND |dy| > |dx| to claim dismiss
ZOOM_MIN              = 1.0f
ZOOM_MAX              = 4.0f
ZOOM_OVERSHOOT        = 0.35f   // pinch may temporarily exceed ZOOM_MAX by this fraction before spring-in
DOUBLE_TAP_SCALE      = 2.3f
DISMISS_DISTANCE      = 180.dp  // drag past this → dismiss
DISMISS_VELOCITY      = 900f dp/s → px   // fling past this → dismiss
```

Claim order per gesture event:
1. **Second pointer down** → pinch owns (consume all subsequent moves) until all pointers up.
2. **One pointer, zoomed** → pan owns; clamp check deferred to release (rubber-band).
3. **One pointer, un-zoomed, move first exceeds `VERTICAL_CLAIM_FACTOR × TOUCH_SLOP` with vertical dominance** → dismiss owns; horizontal component continues tracking pager-free (page follows finger X? no — only Y drives dismiss; X is ignored once claimed to avoid diagonal pager fights).
4. **One pointer, un-zoomed, horizontal dominance** → never consume; `HorizontalPager` receives it (child does not claim ⇒ pager handles).
5. **Tap/double-tap** handled by a separate lightweight `detectTapGestures` scope — taps never consume drags, so no conflict with the pager.

RTL: `HorizontalPager` respects layout direction natively; dismiss is vertical-only; no RTL-specific math.

## Accessibility requirements

- Every chrome action: `FlashDimensions.minTouchTarget` (48dp) + `contentDescription` from the `FlashIconSpec`.
- Pager semantics: Compose `HorizontalPager` provides page semantics; a `contentDescription` on each page of form `"Photo {n} of {total}"` — composed from the counter helper (unit-tested).
- TalkBack: single-tap chrome toggle remains; dismiss is reachable through the close button (never gesture-only).
- Reduce motion: all open/close/zoom animations collapse via `FlashMotion` token contract; pager snaps.
- No information conveyed by motion/color alone; failure state has text + icon.
- Large text: chrome metadata row uses `metadataDefault` and wraps; action row fixed-height, labels icon-only → no truncation risk.

## Responsive behavior

- Phone portrait/landscape: `fillMaxSize` page; chrome inset via system bars padding.
- Large phone / tablet / foldable: photo is `ContentScale.Fit` — no stretching; zoom clamp unchanged. No layout branches.
- Desktop window: same code path; min touch targets preserved.
- (Full window-size-chrome adaptation = UI-034 follow-up, tracked in responsive-layout.md.)

## Dark-mode behavior

Viewer is intentionally always-dark (photographic readability, peer-app consensus). Light and dark apps both get the near-black backdrop; chrome text is white-92 in both. This is a deliberate, documented departure from theme-switched surfaces — recorded as `mediaViewerBackdrop` with per-theme raw values rather than one constant, so UI-035 can still tune each theme later.

## Performance considerations

- **Decode**: `BitmapFactory` with `inSampleSize` computed to keep the long edge ≤ 4096px (pure function, unit-tested). Decode on `Dispatchers.IO` via `produceState` keyed by URI — cancelled automatically if the page leaves composition (pager recycling with `beyondViewportPageCount = 1`).
- **Recomposition**: zoom drives only `graphicsLayer` (GPU transform, zero layout); `FlashZoomState.animatables` read in a `graphicsLayer { }` scope.
- **Memory**: at most ~2 decoded bitmaps alive (current + 1 beyond-viewport each side cap by `beyondViewportPageCount = 1`); no manual bitmap cache in v1.
- **Animation cost**: springs on `Animatable` floats; no off-screen infinite transitions.

## Implementation notes

**Implementation status (2026-08-21):** Implemented in `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashMediaViewer.kt` (`FlashMediaViewer`, `FlashMediaPage`, `FlashZoomState`/`rememberFlashZoomState`, `FlashMediaViewerItem`, `FlashMediaViewerMath`). Tap path threaded `FlashImageGrid.onImageClick` → `FlashMessageBubble` → `FlashMessageList` → `FlashConversationScreen` (overlay via `AnimatedVisibility(motion.mediaOpenEnter/Exit)`, BackHandler, Toast placeholder actions). Unit tests: `FlashMediaViewerLogicTest.kt` (zoom clamp, pinch ceiling, anchored-offset invariant, pan limit, dismiss claim/distance/velocity, counter, page description, initial-page clamp, sample-size guard, backdrop/page dismiss mapping).

Implementation deviations from this spec (all minor):
- Double-tap zoom animates with `motion.springDefaultSpec()` as specified; pinch and pan use `Animatable.snapTo` routed through the external composition coroutine scope because `awaitEachGesture` is a restricted-suspension scope (documented pattern; ordering preserved by the main-immediate dispatcher).
- Dismiss drag distance lives in a plain `mutableStateOf(0f)` read only inside `drawBehind`/`graphicsLayer` scopes — zero recomposition during the drag.
- Page zoom reset triggers on `pagerState.settledPage` change (post-settle) rather than raw page change.

Target files:
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashMediaViewer.kt` (new): `FlashMediaViewer`, `FlashMediaPage`, `FlashZoomState` (`rememberFlashZoomState`), chrome bars, `FlashMediaViewerItem` UI model.
- `ui/chat/.../FlashMessageBubble.kt`: thread `onImageClick: (index: Int, image: FlashImageAttachmentUi) -> Unit` into `FlashImageGrid`.
- `ui/chat/.../FlashMessageList.kt`: thread `onImageClick: (FlashMessageUi, Int) -> Unit`.
- `ui/chat/.../FlashConversationScreen.kt`: hold `mediaViewerState` (album + initial index from tapped message), render overlay, wire Share/Save/Forward callbacks (v1: Toast placeholders — attachment pipeline not connected; see Known limitations).
- `ui/theme/src/main/java/.../FlashColors.kt`: add `mediaViewerBackdrop`, `mediaViewerChromeText` tokens (both palettes).
- `ui/theme/src/main/java/.../icons/FlashIcons.kt` + `ui/theme/src/main/res/drawable/flash_ic_share.xml`: **new Flash-owned share icon** (24×24, 2.0dp stroke, round caps/joins — matches the UI-002 redesign brief; the current set has no share glyph).
- `ui/chat/src/test/.../FlashMediaViewerLogicTest.kt` (new unit tests).

Dependencies: **none added**. Uses `androidx.compose.foundation:foundation` (already a `:ui:chat` dep).

Gesture note for device testing: `FlashImageTile` tiles use their own `detectTapGestures`, and the enclosing bubble uses `combinedClickable`. Compose child-before-parent pointer dispatch makes the tile consume its taps, but the smoke test must confirm the bubble context menu does **not** also open on tile tap; if it does, gate the parent click on an untile flag.

## Testing checklist

- [ ] Compose preview (viewer open with sample album)
- [ ] Physical device (Samsung SM_G986U1): open from grid tile, tap/long-press none, single-tap chrome toggle, double-tap zoom anchor, pinch 1×–4×, rubber-band, page swipe, dismiss drag + velocity fling, Back press, close button
- [ ] Dark mode (light app → dark viewer backdrop both directions)
- [ ] Large font / display size (chrome metadata row)
- [ ] RTL (pager direction)
- [ ] Reduced motion (open/close collapse, pager snaps)
- [ ] Unit tests green: zoom clamp math, dismiss distance/velocity, counter format, sample-size guard, initial-page clamp
- [ ] Performance spot-check: fling pager across 8-photo album — no dropped frames on device
- [ ] Build: `testDebugUnitTest assembleDebug`

## Known limitations

- **No video playback**: `FlashMediaAttachmentUi` is image-only in the current model; `FlashVideoViewer` is a separate future component (master plan allows phasing).
- **Per-message albums only**: the pager covers the tapped message's images, not a continuous whole-conversation media timeline (Telegram-style) — deferred.
- **No shared-element hero transition** (Approach D deferred — see above).
- **Share/Save/Forward are callbacks only** until the attachment/transfer pipeline lands; v1 shows Toast confirmation.
- **No zoom persistence** across viewer re-open (acceptable: viewers universally reset).
- **Download-to-gallery (MediaStore write)** not implemented; "Save/Export" is a placeholder action in v1.

## Future improvements

- `FlashVideoViewer` page (UI-018.5) driven by a `FlashVideoAttachmentUi` model + `Media3 ExoPlayer` (dependency ADR required).
- Compose shared-element hero transition from tile → viewer (after `SharedTransitionScope` stabilizes / or alpha decision documented).
- Whole-conversation media pager with virtualized list across messages.
- Double-tap-and-hold interim zoom (Google Photos behavior).
- Save to device gallery via MediaStore `CreateWriteRequest` + confirmation micro-interaction (UI-041).
- Zoom affordance: subtle scale-overshoot on first open hint for never-opened photos (measure with UI-042).

## What makes this Flash?

The viewer uses Flash's own visual language end-to-end: Flash-owned 2.0dp-stroke iconography in chrome (including a new share glyph drawn for this component — no Material icons), `FlashTypography` metadata voice for sender/time, the Flash Pulse accent reserved for state flashes rather than decoration, graphite-void dark backdrop tuned per-theme in the `FlashColors` token system rather than hardcoded black, and UI-037 motion tokens (`mediaOpenEnter/Exit` reserved tokens finally realized, `springDefaultSpec` rubber-bands) — so open/close/zoom feel identical to the rest of Flash. The gesture claim policy (vertical-drag dismiss ↔ horizontal pager ↔ pinch zoom, one owner at a time) is interaction design, not styling — no app is cloned; the chromat of pull-to-dismiss + scale + fade is Flash's own composition of the same primitives the app already uses everywhere else.
