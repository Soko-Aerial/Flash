# Component Research & Design Document — Calling UI

**Status:** IMPLEMENTED — shipped as `:ui:callui` (`FlashCallScreen`) over `:core:calling`

**Component ID:** UI-050

**Last updated:** 2026-09-02

**Owner phase:** Voice/video calling (`:core:calling` + `:ui:callui`)

> The module shipped as `:ui:callui`, not the `:ui:calling` this document originally proposed —
> a `ui-calling` artifact sitting next to `core-calling` reads as the same thing twice. Its
> published surface is a single composable; see
> [`docs/architecture/public-api.md`](../architecture/public-api.md) §13 for the contract and §7
> for the `FlashCalling` / `FlashCallMedia` abstractions it binds to.

---

## Component

Three surfaces, one component:

1. `FlashCallScreen` (`:ui:callui`) — the full-screen in-call surface, audio + video.
2. The call / video-call buttons that already existed in `FlashChatHeader` — UI-050 wires them
   to real behavior.
3. `FlashCallEventRow` (`internal` to `:ui:chat`) — the finished call's row in the thread. It
   renders as bubble *content* rather than its own list item, so it inherits selection,
   long-press actions, reactions and the timestamp row from `FlashMessageBubble`; the bubble's
   side already encodes direction, so the label only says what kind of call it was.

## Purpose

Flash is a P2P messenger: peers chat over the WS mesh today, but there is no way to talk.
This component adds 1:1 voice and video calling between two paired devices on the same
LAN/hotspot, using WebRTC media (not data channels) with signaling riding the existing
WebSocket mesh text frames. It appears as a full-screen overlay launched from the chat
header buttons, and (for incoming calls) from a high-priority system notification.

## Research sources

- `shepeliev/webrtc-kmp` v0.125.11 source (M125, Maven Central, 2025-09-08) — `WebRtc`
  object, `WebRtcInitializer` (androidx.startup auto-init), `PeerConnection` suspend SDP
  APIs + Flow events, `MediaDevices.getUserMedia`, `MediaStreamTrack.enabled` var,
  `VideoTrack.switchCamera()`, `MediaStream.release()`, `RtcConfiguration`.
- webrtc-kmp sample app — `Video` composable is **sample-only** (expect/actual); Flash must
  implement its own `AndroidView { SurfaceViewRenderer }` with `WebRtc.rootEglBase`.
- Official Android docs (verified 2026-09-01/02):
  - FGS types & while-in-use restrictions (microphone/camera FGS cannot be *created* while
    backgrounded; one started while foreground may continue in background).
  - `Notification.CallStyle` (API 31+): `forIncomingCall` / `forOngoingCall`, system-styled
    buttons, `setOngoing(true)` on Android 14+.
  - Runtime permissions model (CAMERA + RECORD_AUDIO).
- Existing Flash surfaces studied: `FlashChatHeader` (call buttons already present, no-op),
  `FlashConversationScreen`, `MainActivity` conversation call-site, `FlashNotificationManager`
  (channel pattern + foreground suppression), `FlashBackgroundService` (FGS + locks template).
- Calling UX patterns studied (no code copied): WhatsApp/Telegram/Signal call screens —
  remote-video-full + local-PiP, bottom control row, avatar + pulse for audio calls,
  state-driven status text ("Calling…", "Ringing", duration counter).

## Existing approaches studied

1. **Full-screen dedicated call Activity** (WhatsApp-style). Pros: independent of chat
   navigation, survives chat teardown, cleanest FGS/foreground story for incoming-call
   notification taps. Cons: second Activity to theme, transition jank, duplicates design-system
   scaffolding, complicates the single-Activity navigation state Flash already built
   (UI-033 snapshot-state stack).
2. **In-app full-screen overlay route inside the existing navigation stack** (Telegram
   Android in-app approach). Pros: one Activity, one theme, reuses `FlashNavigationState`
   push/pop + direction-aware transitions, call state survives recomposition naturally.
   Cons: call must survive *navigation* away (user presses back to chat while audio call
   continues) — solved by keeping call state in a holder outside the stack and rendering a
   compact "ongoing call" chip in the header instead of tearing the call down.
3. **Floating overlay window (SYSTEM_ALERT_WINDOW)**. Rejected outright: requires
   Settings canary permission (`SYSTEM_ALERT_WINDOW`), banned-ish on modern Android,
   hostile to accessibility, and unnecessary for a v1.

## What worked

- Remote video fills the screen; local video is a small draggable-free PiP (fixed corner,
  tap either surface to switch which stream is large). Simple, predictable, no gesture bugs.
- State-driven UI: one `FlashCallUiState` data class carrying a `FlashCallState` enum drives
  every visual (dialing / ringing / connecting / active / ended). No imperative UI branching.
  Failure is not a state but an `endReason` on ENDED, which is why the end screen can say *why*
  without a separate FAILED branch to keep in sync.
- Audio calls: large avatar + an accent halo pulse + status text — reuses existing identity
  visuals, keeps v1 small.
- Duration counter only while `ACTIVE`, keyed off `connectedAt` so it survives recomposition
  without a stored tick count.
- Sink add/remove wrapped in `runCatching`: a track can already be disposed by the time the
  renderer unbinds from it.

## What did not work

- Floating window overlays (approach 3) — permission model unacceptable.
- Putting the call UI inside the conversation screen composable tree directly — couples
  call lifetime to message-list lifetime; back navigation would kill the call.
- Material `Slider`/`Switch`-style call controls — off-design-system; Flash uses its own
  icon buttons (`FlashHeaderIconButton` pattern, 48dp targets).
- The webrtc-kmp sample's renderer lifecycle (`init` on ON_RESUME / `release` on ON_PAUSE, and
  a release whenever the track changes). It reads as symmetric and is a trap:
  `EglRenderer.release()` is terminal, so the next `init` on the same view renders nothing. The
  symptom is a black video tile with working audio — no error anywhere. The screen now releases
  only in `onViewDestroyed` and treats a track change as a sink swap.

## Chosen approach

**Approach 2**, with one adjustment made during implementation: `FlashCallScreen` is a
full-screen *overlay* drawn above the shell whenever `FlashCalling.activeCall` is non-null,
rather than a pushed entry in `FlashNavigationState`. Call state is owned by `CallCoordinator`
(in `:core:calling`, the `FlashCalling` implementation), which outlives both the overlay and any
route underneath it. Hanging up — locally or from the peer — flips `activeCall` to ENDED, the
screen renders the end reason, and the flow goes null a moment later, which is what removes the
overlay. There is no push/pop pair to keep balanced.

The screen itself holds no call: it takes a `FlashCallUiState` plus a nullable `FlashCallMedia`
and ten callbacks, so it can neither mutate a call nor see the concrete session type.

## Why it was chosen

- One Activity, one theme, one navigation system — matches ADR-020's dependency-free shell
  and UI-033's snapshot-state stack.
- Call state outside Compose (holder pattern, same as `DiscoveryEngineHolder`) keeps the
  WebRTC session alive across configuration changes and route changes.
- Deriving the overlay from `activeCall` rather than pushing a route removes a whole class of
  bug: the call's existence and the screen's existence are the same fact, so they cannot
  disagree. A pushed route has to be popped by whoever notices the call ended, from either side.
- FGS story stays compliant: the call FGS is started while the app is foreground (user taps
  call / answers from notification), which is the only legal way to start a microphone FGS.

## Visual specification

All tokens from `FlashTheme` (`FlashColors`, `FlashTypography`, `FlashShapes`,
`FlashSpacing`, `FlashMotion`, `FlashIcons`):

- **Background:** `colors.backgroundApp` for audio calls; video fills edge-to-edge over black
  (remote stream), PiP has `FlashShapes.radius12` + a 1dp `colors.backgroundSurfaceSubtle` border.
- **Peer identity:** avatar via existing `FlashAvatar` system (seeded by device id), name in
  `FlashTypography.headingLarge`, status line in `FlashTypography.bodyDefault` +
  `colors.textSecondary`. On a video call the same identity block shrinks to a top-start overlay
  in white, and the full avatar block comes back once the call is ENDED.
- **Status text:** "Calling…" (dialing) → "Ringing" (ringing) → "Connecting…" (connecting) →
  live `mm:ss` duration (active) → "Call ended" / failure reason (ended/failed). G7 (2026-09-29) adds the end
  reason FULL → "Call is full" (a group call already at its cap turned this device away); text only.
- **Quality badge:** directly under the status line — a colored dot plus
  `RTT · resolution·fps · inbound bitrate`, and `n% loss` once loss passes 2%. Dot is green
  under 60 ms RTT, amber under 150, red past that; `metadataDefault` type, and on video it sits
  on a translucent black chip so it stays legible over the remote frame. Shown only while
  ACTIVE, and each field appears only once WebRTC has actually measured it, so the badge grows
  into itself over the first few seconds rather than showing zeros.
- **Controls (bottom row, 48dp circles, `FlashSpacing.space4` gaps, state-driven):**
  - Ringing (incoming): accept (`FlashIcons.CallAccept`, `accentPrimary` circle) + decline
    (`FlashIcons.Hangup`, `textError` circle).
  - Dialing / connecting / active, audio: mute (`FlashIcons.Mute`), speaker
    (`FlashIcons.Speaker`), hang up (`FlashIcons.Hangup`).
  - Dialing / connecting / active, video: mute, camera flip (`FlashIcons.CameraFlip`),
    camera off (`FlashIcons.Camera`), hang up. No speaker toggle — a video call is created
    with `speakerOn = true`, so the button would only ever turn routing *off*.
  - Ended: one dismiss button (`FlashIcons.Close`).
  - An engaged toggle is shown by the circle, not a badge: `backgroundSurfaceSubtle` →
    `backgroundSurfaceStrong` with the glyph tinted `accentPrimary`.
- **Pulse:** avatar pulse while ringing/active audio — `FlashMotion` spring, collapses
  under reduced motion (UI-038 rule).
- **Dark mode:** video calls are inherently dark-surface; audio call background follows
  `FlashTheme` dark palette. PiP border uses `colors.surfaceVariant` in both modes.

## Interaction specification

- Every control is a lambda the host wires to `FlashCalling`: accept → `accept()`,
  decline → `decline()`, hang up → `hangUp()`, mute → `toggleMute()`, camera →
  `toggleCamera()`, flip → `switchCamera()`, speaker → `setSpeaker(!speakerOn)`. The screen
  never holds the call, so it cannot short-circuit any of them.
- Speaker is the one control with a second half outside `:core:calling`: `setSpeaker` records
  the preference on `activeCall`, and the host mirrors the same value onto its
  `FlashCallAudioRouter` — platform routing is not reachable from a per-call API.
- Accept is permission-gated by the host, not by the screen. `:app` checks `RECORD_AUDIO`
  (plus `CAMERA` on a video call), launches the request if either is missing, and re-enters
  accept once granted; then it attaches the audio router **before** `accept()`, because a mic
  opened outside `MODE_IN_COMMUNICATION` never picks up the platform AEC afterwards.
- Tap either video surface (large or PiP) → swap which stream is large.
- Mute toggles `audioTrack.enabled`; camera-off toggles `videoTrack.enabled`;
  camera-flip calls `VideoTrack.switchCamera()`.
- System back while ringing (incoming) → decline. Otherwise back calls `onDismiss`, which
  `:app` deliberately leaves empty in v1: there is no minimized presentation to fall back to,
  and a back press that hid the only hang-up button would strand a live call. On ENDED the same
  lambda is what dismisses the overlay early.
- Disabled controls: none (all controls always tappable; states that would be no-ops are
  hidden instead — no camera-flip on an audio call, no speaker button on a video call).

## Animation specification

- Route entry: existing direction-aware push transition (UI-033) — no new motion.
- Avatar pulse: `FlashMotion` spring scale 1.0 → 1.06, repeat while ringing/active;
  disabled under `reduceMotion` (UI-038).
- Status text changes: no animation in v1 (text swap only) — keeps recomposition cheap.
- Control press: existing `flashPressScale` (UI-041).

## Gesture specification

- No drags, no swipes in v1. PiP position fixed (top-end corner, RTL-mirrored).
- Edge case: PiP tap target ≥ 48dp.

## Accessibility requirements

- All controls: `contentDescription` from `FlashIconSpec` (existing pattern).
- Status text is live-region (`LiveRegionMode.Polite`) so TalkBack announces
  "Ringing" → duration changes.
- The quality badge is a dot plus abbreviated numbers, so it carries its own spoken form —
  `contentDescription = "Call quality: 42 ms, 1080p · 30fps, 2.1 Mbps"` — rather than leaving
  TalkBack to read a row of glyphs and units.
- Accept/decline/hangup buttons ≥ 48dp; contrast from `FlashColors` tokens (already AA+).
- Reduced motion: pulse collapses; route transition already respects UI-038.

## Responsive behavior

- Portrait-first. Landscape: controls move to a leading column (video calls) / centered
  row (audio calls); PiP stays cornered. Tablet/foldable: same layout scaled by
  `FlashDimensions`; no separate layout in v1.

## Dark-mode behavior

Deliberate palette per UI-035: audio-call background uses the dark `colors.background`
token (not inverted light); controls use dark-surface tints. Video surfaces are
renderer-driven (no palette).

## Performance considerations

- `SurfaceViewRenderer` is a non-Compose view behind `AndroidView` — no per-frame
  recomposition. Sinks are added/removed when the bound track changes; the renderer itself is
  initialized once and released only when the view is discarded, because `EglRenderer.release()`
  is terminal — releasing it on a track change (a camera flip, a renegotiation) leaves a
  permanently black surface with the audio still flowing.
- Tracks are `StateFlow`s, so they are observed and re-bound, never sampled: the local track
  appears roughly 130 ms after the screen does, and the remote one only when the peer publishes.
- Duration counter is a `remember`ed string driven by one `LaunchedEffect` loop keyed on
  `connectedAt` — one recomposition/second on a leaf text node only.
- The quality badge rides the session's own 1 s `getStats()` poll (matching the RTCP reporting
  interval); the UI adds no polling of its own.
- Video capture is requested at 1920x1080/30 and the sender's degradation preference is
  `MAINTAIN_FRAMERATE`, so a constrained link sheds *resolution* (1080p → 720p → 540p → …) and
  keeps 30 fps rather than producing a sharp slideshow. The camera enumerator snaps the request
  to the nearest supported format, so a device with no 1080p mode degrades instead of failing.
- Call state reads are `collectAsState` on `StateFlow`s from the coordinator; no polling.

## Implementation notes

- Module `:ui:callui` (namespace `com.transfer.flash.ui.calling`, artifactId `ui-callui`,
  compileSdk 37, minSdk 24). Depends on `:ui:theme` + `:core:common` with
  `api(project(":core:calling"))` — `api`, because `FlashCallScreen`'s own signature names
  `FlashCallUiState` and `FlashCallMedia`, and because `:core:calling` re-exports webrtc-kmp,
  which `FlashVideoRenderer` needs for `SurfaceViewRenderer`. It builds without `:app`,
  `:core:engine` or `:ui:chat`.
- New icons landed as `FlashIcons` entries (Flash-owned vectors, UI-002 rules):
  `Speaker`, `Hangup`, `CallAccept`, `CameraFlip`, reusing existing `Mute`, `Camera`, `Close`.
- Renderer: `AndroidView` + `SurfaceViewRenderer` + `WebRtc.rootEglBase.eglBaseContext`,
  `ScalingType.SCALE_ASPECT_BALANCED` for the full surface / `SCALE_ASPECT_FIT` for the PiP.
  The PiP is a fixed 120dp-wide 3:4 tile — `widthIn(min = …)` let it expand to the parent's
  max width and swallow the surface it is supposed to sit on.
- Overlay hosting: `MainActivity` renders `FlashCallScreen` above the shell whenever
  `FlashCalling.activeCall` is non-null. One source of truth, no push/pop to pair.
- Host responsibilities the screen deliberately does not take on: the `microphone|camera`
  foreground service (started on DIALING/RINGING, stopped when `activeCall` goes null), the
  runtime permission prompts, and `FlashCallAudioRouter` — attached for every state except
  RINGING and ENDED, since exclusive voice-communication focus would silence the incoming-call
  ringtone.
- License note: `webrtc-kmp` is MIT; wraps `io.github.webrtc-sdk:android` (WebRTC native,
  BSD-3) — documented in ADR-025.

## Testing checklist

JVM unit tests in `:core:calling` cover the state machine and the signaling this screen renders:
`FlashCallSessionTest` (media-before-Accept ordering, early offer while RINGING, decline/hangup
races, dial timeout → NO_ANSWER, speaker default per call type, tracks and stats staying null
until media exists) and `CallSdpTest` (the SDP rewrites — codec targeting, bitrate hints only in
the video section).

Two-phone audio and video calls were exercised on physical devices during bring-up, which is
how the connect-glare, "stuck on Connecting", and black-video-tile regressions were found. They
have **not** been re-run since 1080p capture, the quality badge and the audio router landed, so
every box below is open for the current tree:

- [ ] Compose preview (audio + video, all states)
- [ ] Physical device: two-phone audio call
- [ ] Physical device: two-phone video call
- [ ] Physical device: headset connect/disconnect mid-call (Bluetooth and wired)
- [ ] Dark mode
- [ ] Large font / display size
- [ ] RTL
- [ ] Reduced motion
- [ ] Performance spot-check (video call thermals over 5 min)

## Known limitations

- v1 is 1:1 only (no group calls). *Superseded:* group calls exist (mesh); the group video grid is UI-050b below.
- No minimize. Back on a live call calls `onDismiss`, which `:app` leaves empty, so the overlay
  stays; a call is left by ending it. The ongoing-call chip this document originally proposed was
  not built, and back is deliberately inert rather than hiding the only hang-up button.
- No Bluetooth (or output-device) *picker*. Routing is automatic and priority-ordered —
  Bluetooth SCO → BLE headset → hearing aid → USB → wired → earpiece — with the speaker toggle
  as the only user-facing override, and it re-applies when devices come and go mid-call.
- No speaker toggle on video calls: they start `speakerOn = true`, so the button could only
  ever turn routing off.
- Call rows land in the thread, but each device derives its own row locally, so `missed` is
  "incoming call that never carried media" — it cannot separate "declined" from "the caller gave
  up", because both end as `NORMAL` on the wire.
- Screen sharing not in scope.

## Future improvements

- Minimize: an ongoing-call chip in the chat header, so back can leave the call running.
- Output-device picker (Bluetooth / speaker / earpiece) instead of a single speaker toggle.
- Video call screenshot prevention (`FLAG_SECURE`) once tested.
- Group calls (mesh multi-peer — needs multi-point ICE + mixing; large effort).

## What makes this Flash?

Flash's call screen is P2P-aware by construction: no phone number, no server, no SIM —
the "contact" is a paired device on your local network, so identity is the device avatar
and fingerprint system Flash already built (UI-031 trust badges apply to calls too). The
visual language is the same Flash Pulse identity (avatar pulse while ringing) used across
the app rather than a generic green-call-button Material screen, and the call rides the
same trust/pairing story as file transfer: if the peer is verified, the call screen shows
it. Nothing here clones WhatsApp's layout; it shares only the universal grammar
(remote-large + local-PiP) that every calling app converges on because it is correct.

---

## UI-050b — Group video grid (G1)

**Status:** DESIGNED → IMPLEMENTED (2026-09-28; not device-verified, P8). Plan: `docs/calling/GROUP-VIDEO-PLAN.md` G1. The final layouts (LOW main tile plus
strip, tap to focus, "Video busy" / "Camera off") are G5; this section covers only what G1 needs.

### Component
The video layer of `FlashCallScreen` when the call is a **group video** call and active. Audio group calls keep
`FlashGroupParticipantsGrid` (avatars) unchanged; 1:1 calls keep remote-full + local PiP unchanged.

### Purpose
Before G1 the group session kept one remote video track (the last to arrive won), so a 3-way video call showed at
most one of the two other people, and the grid never showed video at all. G1: every participant's video is visible.

### Research sources
- Existing Flash 1:1 surfaces (`FlashCallVideoSurfaces`) and the renderer-lifetime rule (`FlashCallVideoSurface`
  KDoc; a renderer is initialised once per composable instance and released only on disposal).
- Common grid grammar of group video apps (Signal, Google Meet, Jitsi, FaceTime): equal tiles up to ~4–6 people,
  the local camera as a corner thumbnail, name and mute on each tile, a highlight on whoever speaks. Studied for the
  shared grammar only; no layout is copied.

### Approaches considered
1. **Equal grid of remote tiles + local PiP (chosen).** Simple, shows everyone, same PiP as 1:1.
2. **Local camera as a grid tile.** Wastes a tile on the user's own face on a phone screen; Flash's 1:1 PiP already
   sets the expectation that "me" is a thumbnail.
3. **Speaker-focused main tile + strip now.** That is G5's LOW layout and depends on G3's request protocol; building
   it now would be redone.

### Chosen approach
- **Tiles:** one per participant that is not LEFT, in the session's participant order (stable; a newcomer is added at
  the end, nobody jumps). Each tile is wrapped in `key(peerId)`, so a tile's renderer stays with its person when others
  join or leave (the renderer-lifetime rule: a moved tile must not become another composable instance).
- **Layout** (`groupVideoGrid(count, wide)`): 1 → full screen; 2 → two rows (side by side when the box is wider than
  tall); 3–4 → 2 × 2; 5 → 2 columns × 3 rows (3 × 2 when wide). The last row's tiles share its width evenly, so 3
  people show as two on top and one wide tile below. The group cap is 6 members, so at most 5 remote tiles.
- **Tile content:** the participant's video with `CallVideoFit.Balanced` (crop to fill, same as the 1:1 remote), or,
  with no track yet (connecting, camera off, a peer on an audio-only device), the avatar on `backgroundSurface` dark.
- **Tile overlay:** name bottom-start on a 40 % black scrim; "Muted" / "Connecting…" / "Reconnecting…" beneath it in
  the existing status wording. The speaking participant gets a 2 dp `statusOnline` border.
- **Local camera:** the same fixed-width PiP as 1:1, top-end, above the grid. The PiP tap-to-swap of 1:1 is not
  offered in a group (swapping with which tile?); G5 adds tap-to-focus.
- **Header:** group name + status line + stats badge top-start, as in 1:1.
- **Video by request (G3, 2026-09-28):** a participant's track exists from negotiation, but its picture arrives only
  after the participant grants this device's request. The avatar therefore covers the tile unless the participant's
  `video` is RECEIVING (or UNMANAGED: an older client that always sends). Under the name, a connected participant's
  status word is "Muted", else "Video busy" (BUSY) or "Camera off" (CAMERA_OFF). The words use the existing status
  style and add no new visual. Tapping a tile to pin it is still G5.
- **Stats badge band (G2, 2026-09-28):** the badge appends the call's slowest link band ("5 GHz", "2.4 GHz",
  "6 GHz", "Ethernet") when one is known. Nothing is shown for an unknown band. The text uses the badge's existing style, with no new visual.

### Visual specification
- Tiles: 4 dp gaps on black (`Color.Black`, as the 1:1 video background), `FlashShapes.radius12` corners when more
  than one tile, none for a single full-screen tile.
- Avatar in an empty tile: `FlashDimensions.avatarLg`, seeded by peer id (same as the audio grid).
- Text: `metadataDefault` white; status in white 80 %.

### Interaction specification
None new in G1 (no tap on tiles). Controls row unchanged.

### Animation specification
None in G1. Tiles appear and disappear without animation, so a renderer is never kept alive in an exit transition.

### Accessibility requirements
Each tile has a content description "<name>, video" / "<name>, no video" plus the status word, so TalkBack reads who
is shown. Text keeps the existing typography scale.

### Responsive behavior
`wide` = the video box is wider than tall (landscape phone, tablet, desktop window). Same rules on every host.

### Dark-mode behavior
The video layer is always dark (black background), as in 1:1.

### Performance considerations
- Up to 5 renderers at once. Android: one `SurfaceViewRenderer` per tile on the shared EGL context. Desktop: one Skia
  sink per tile. G1 does not change how many videos are decoded (today every leg already decodes); G3/G4 reduce that.
- `key(peerId)` prevents a renderer from being rebuilt on every join or leave.

### Implementation notes
- Core: `FlashCallMedia.remoteVideoTracks: StateFlow<Map<String, VideoStreamTrack>>` (default empty for 1:1),
  filled from each leg's `onTrack` and cleared when the leg closes. `remoteVideoStreamTrack` stays for 1:1 callers and
  becomes "the newest remaining participant's track" in a group instead of "the last track that ever arrived".
- The layout math is a pure function with unit tests.

### Testing checklist
- [x] Unit: grid shape for 1–5 tiles, tall and wide; track table add/replace/remove.
- [ ] Device (pending, P8): 3 devices in a video call, each sees both other videos; one leaves, the others keep their
      tiles without a black flash; the leaver's tile disappears.
- [ ] Device (pending): the local PiP draws above the grid tiles on Android. Every tile and the PiP is a
      `SurfaceView` with the default z-order, as in the 1:1 screen; if the PiP hides behind a tile, give the PiP
      `setZOrderMediaOverlay(true)`.

### Known limitations
- Every connection still sends and decodes video (G3 fixes this).
- No focus, no strip, no "Video busy" (G5). A participant whose camera is off shows the avatar only after the track
  ends; a muted-but-live camera track may show a frozen last frame until G5 adds the camera-off state.

---

## UI-050c — Group video focus: compact main tile + strip, tap to pin (G5)

**Status:** DESIGNED → IMPLEMENTED (2026-09-29; not device-verified, P8). Code: `FlashGroupVideoGrid.kt` (main tile,
tappable tiles), `FlashGroupVideoStrip.kt` (strip), `FlashCallScreen(onVideoFocus)`. Plan:
`docs/calling/GROUP-VIDEO-PLAN.md` §4.4 and G5. Builds on UI-050b (grid) and G3/G4 (request protocol, budgets).

### Component
The group-video layer of `FlashCallScreen`, in two shapes:
1. **Compact** (the device receives one video at a time: LOW tier, or a G6 cap): one main tile plus a horizontal
   strip of every participant.
2. **Grid** (MEDIUM/HIGH): the UI-050b grid, now tappable.

### Purpose
G3 made video arrive only by request, and G4 set LOW to one video. Without G5 a LOW user sees one tile with no way to
choose whom it shows, and a grid user can't keep a person on screen when the speaker changes. §4.4 asks for: follow the
speaker by default (2 s hold, Q1), a tap pins, tapping the pinned person again returns to following the speaker.

### Research sources
- Plan §4.4 and owner decisions Q1/Q5.
- Shared grammar of group call apps (Google Meet's "pin", Signal's speaker view with a participant strip, FaceTime's
  tile tap). Studied only for the common idea "tap to pin, tap again to unpin; a row of people under a main view".
  Nothing is copied: no floating pin badges, no multi-level menus.
- Flash constraints: one renderer per tile (renderer-lifetime rule), `FlashIcons` only (no stock icons), no Material
  ripple (`flashPressScale`), TalkBack labels on every tappable tile.

### Approaches considered
1. **Main tile + avatar strip for compact, tap-to-pin grid (chosen).** One renderer on LOW (the device decodes one video
   anyway), everyone visible as an avatar, a tap is the only gesture.
2. **Strip of live thumbnails.** Needs a decoder per thumbnail, which is exactly what LOW can't afford (G4).
3. **Long-press menu with "Pin".** Hidden, slower, and adds a menu component for one action.

### Chosen approach
- **Compact is decided by the core**, not by the screen: `FlashCallUiState.compactVideo` is true when the device's
  receive limit is 1 (LOW, or G6's "Show fewer"/severe heat). The screen never guesses the tier.
- **Main tile (compact):** the participant in `FlashCallUiState.videoMainPeerId` (pinned, else the followed speaker),
  falling back to the first participant whose video arrives, then the first participant. It fills the video area and
  uses the UI-050b tile (video or avatar, name, status). One composable instance: when the person changes, the same
  renderer re-binds to the new track (no new surface, no black flash from a released renderer).
- **Strip (compact):** a horizontally scrolling row directly above the call controls, one chip per participant that is
  not LEFT, in participant order. A chip: `avatarMd` avatar, a 2 dp `statusOnline` ring while speaking, a small
  `FlashIcons.Mute` badge (`iconSm`, bottom-end) while muted, the first name under it (one line, 64 dp wide). The chip
  of the person in the main tile sits on a `Color.White` 16 % pill so it's clear who's shown.
- **Tap (both shapes):** tapping a person calls `FlashCalling.setVideoFocus(peerId)`; tapping the pinned person calls
  `setVideoFocus(null)` (back to following the speaker).
- **Pinned marker:** the pinned tile or chip shows `FlashIcons.Pin` (`iconSm`, white) before the name. No other change.
- **Grid (MEDIUM/HIGH):** unchanged layout; every tile tappable as above. Because the router asks for the pinned
  person first, pinning someone outside the receive limit swaps them in and releases the lowest-ordered video.

### Visual specification
- Strip: height = 48 dp chip + 4 dp + one metadata line; `FlashSpacing.space8` between chips; `space16` side padding.
- Main-chip pill: `RoundedCornerShape(FlashShapes.radius12)`, `Color.White.copy(alpha = 0.16f)`.
- Mute badge: `FlashIcons.Mute` at `iconSm` tinted white on a 20 dp black 60 % circle.
- Text: `metadataDefault` white; no new colours, no new typography.

### Interaction specification
- Single tap on a grid tile, the main tile (compact: only unpins if pinned) or a strip chip. No long-press, no swipe.
- Press feedback: `flashPressScale` (the house press feel), no ripple.
- The main tile in compact is tappable only while something is pinned (tap = unpin), so a stray tap does not pin the
  current speaker by surprise.

### Animation specification
None beyond the press scale. The strip does not animate reordering (participant order is stable).

### Gesture specification
Tap only. The strip scrolls horizontally with the platform's standard scroll.

### Accessibility requirements
- Tile/chip content description: "<name>, video|no video[, status][, pinned]".
- Click label: "Pin <name>'s video" / "Unpin <name>'s video"; role Button. Touch target ≥ 48 dp (chips are 64 dp wide).

### Responsive behavior
Same on every host. On a wide window the compact shape is unchanged (the strip scrolls if needed).

### Dark-mode behavior
The video layer is always dark, as in UI-050b.

### Performance considerations
Compact mode composes exactly one video renderer (plus the local PiP). The strip is avatars only. The grid is
unchanged from UI-050b.

### Implementation notes
- Core: `FlashCallUiState.compactVideo`, `videoFocusPeerId` (pinned), `videoMainPeerId` (pinned or followed),
  filled by `FlashGroupCallSession.refreshUiState` from the router.
- UI: `FlashCallScreen(onVideoFocus: (String?) -> Unit = {})`; the app and desktop hosts pass
  `calling::setVideoFocus`. Pure helpers `groupVideoMainPeer(state)` and `nextVideoFocus(state, tapped)` are unit
  tested.

### Testing checklist
- [x] Unit: main-peer fallback order; tap on the pinned person unpins, tap on another pins.
- [ ] Device (pending, P8): LOW phone in a 3-way video call: main tile follows the speaker after ~2 s; tapping a chip
      moves the video within ~1 s; tapping it again returns to the speaker.
- [ ] Device (pending): MEDIUM/HIGH grid tap pins; pinning someone outside the receive limit swaps them in.
- [ ] TalkBack reads the pin/unpin labels.

### Known limitations
- Compact mode shows no live picture for anyone but the main person (by design, G4's LOW budget).
- A pin is local; other participants don't see it (no "spotlight for everyone").

---

## UI-050d — Group video health banner and "Show fewer" (G6)

**Status:** DESIGNED → IMPLEMENTED (2026-09-29; not device-verified, P8). Code: `FlashCallHealthBanner.kt`,
`FlashCallScreen(onShowFewerVideos)`. Plan: `docs/calling/GROUP-VIDEO-PLAN.md` §4.5 and G6.

### Component
A one-line banner in a group video call, directly above the participant strip (compact) or the call controls
(grid), plus a small "Showing one video · Show all" pill while receiving is capped.

### Purpose
§4.5 (R7): when the phone warms up, the processor is loaded for a long time, or videos are decoded in software, tell
the user in plain words and offer one action, **Show fewer** (receive one video). When the phone is hot, Flash already
drops to one video by itself and must say so.

### Research sources
- Plan §4.5 wording (the owner's): "Your phone is warming up. Showing fewer videos saves battery."
- Android's own thermal UX (a notification-style heads-up, no numbers) and call apps' "poor connection" banners:
  short, non-modal, dismissible, one action. Studied only for that grammar.
- Flash's existing on-video text style (UI-050b: white `metadataDefault` on a 40 % black scrim).

### Approaches considered
1. **Inline banner above the controls (chosen).** Visible without covering faces; the controls are where the thumb is.
2. **Snackbar / toast.** Disappears on its own; the hot state needs to stay visible while it lasts.
3. **Dialog.** Blocks the call for advice the user may ignore; wrong weight.

### Chosen approach
- One banner at a time, chosen by the core (`FlashCallUiState.healthWarning`: WARM > CPU > SOFTWARE_DECODE; HOT
  overrides all).
- Text:
  - WARM: "Your phone is warming up. Showing fewer videos saves battery."
  - CPU: "This call is keeping the processor busy. Showing fewer videos helps."
  - SOFTWARE_DECODE: "Videos are being decoded without hardware help. Showing fewer videos saves battery."
  - HOT: "Your phone is hot. Showing one video until it cools down."
- Action: **Show fewer** (WARM, CPU, SOFTWARE_DECODE) → `FlashCalling.setShowFewerVideos(true)`. HOT has no action.
- CPU only, while the "Send smaller video in groups" setting is off (ADR-053, 2026-09-29): a second action,
  **Send 360p** (label "Send my video at 360p in group calls"; was "Send smaller" until ADR-098, 2026-10-08), which turns
  the setting on and saves it. The text
  then reads "This call is keeping the processor busy. Showing fewer videos, or sending yours at 360p, helps." With
  two actions the banner has two lines: the words and close on top, the actions right-aligned below. Actions size to
  their text (min 96 dp wide, 48 dp tall).
- Dismiss: a close icon (`FlashIcons.Close`). A dismissed kind does not come back for the rest of the call (the
  "once per call" rule for software decoding applies to all of them, so a flapping signal can't nag).
- While `showingFewerVideos` is true and there is no banner: the pill "Showing one video · Show all" →
  `setShowFewerVideos(false)`. Not shown while HOT (the cap is automatic there).
- Since the receive limit becomes 1, the layout switches to UI-050c's compact shape automatically.

### Visual specification
- Banner: `RoundedCornerShape(FlashShapes.radius12)`, `Color.Black` 60 %, padding `space12` × `space8`, side margin
  `space16`. Text `metadataDefault` white, up to 3 lines. Action: `metadataDefault` in `accentPrimary`, 48 dp touch
  target. Close: `FlashIcons.Close` at `iconSm`, white 80 %, 48 dp touch target.
- Pill: same surface, one line, `metadataDefault` white with "Show all" in `accentPrimary`.

### Interaction specification
Tap "Show fewer", tap close, tap the pill. No swipe. Press feel: `flashPressScale`, no ripple.

### Animation specification
None (appears and disappears), matching UI-050b's no-animation rule on the video layer.

### Gesture specification
Taps only.

### Accessibility requirements
The banner text is a polite live region so TalkBack announces a new warning once. The action and close are role
Button with labels "Show fewer videos" / "Dismiss warning"; the pill's label is "Show all videos".

### Responsive behavior
Full width minus margins on phones; on wide windows capped at 480 dp and centred.

### Dark-mode behavior
The video layer is always dark.

### Performance considerations
Recomposes only when the warning or the cap changes (at most once per stats sample).

### Implementation notes
- Core: `FlashCallHealthWarning`, `FlashCallUiState.healthWarning` / `showingFewerVideos`,
  `FlashCalling.setShowFewerVideos`; `CallHealthMonitor` decides.
- UI: `FlashCallHealthBanner.kt`; `FlashCallScreen(onShowFewerVideos: (Boolean) -> Unit = {})`; hosts wired.
  Pure helpers `healthWarningText` / `healthWarningOffersShowFewer` are unit tested.

### Testing checklist
- [x] Unit: text and action per warning.
- [ ] Device (pending, P8): force MODERATE (`adb shell cmd thermalservice override-status 2`) → WARM banner; Show
      fewer → compact layout and the pill; Show all → back. Force SEVERE (`override-status 3`) → HOT banner, one video,
      another device's request is turned down with `thermal`. A normal call shows nothing.

### Known limitations
- The CPU threshold (40 % of all cores for 30 s) is provisional until G0 measures a real call.
- Desktop has no thermal signal; only CPU (and software decoding on a non-HIGH tier) can warn there.

---

## UI-050e — In-call control dock: icons, state and motion (2026-10-02)

**Status:** IMPLEMENTED (unit-tested, rendered in a Skia scene on the desktop JVM; not device-verified, `CALLDOCK-01`...`CALLDOCK-05`).

### Purpose
The owner found the in-call icons unrepresentative. Checked against the code, they were: the Mute button drew a crossed-out **notification bell**
(`flash_ic_mute`, also the chat list's "muted conversation" glyph), the camera toggle was a still-photo camera that never changed, the flip button
did not read as a flip, hang-up was the accept handset with a slash ("call blocked"), and every toggle changed only tint, with the *muted* state
in the accent colour that elsewhere means *on*. There were no labels and the accessibility text never followed the state.

### Approaches considered
1. Keep the glyphs and only change colours. Rejected: the wrong symbol stays wrong.
2. Swap in Material Symbols. Rejected: AGENTS.md section 34 (no stock Material glyphs) and UI-002.
3. **Chosen:** new Flash-stroke glyphs (24 dp viewport, 2 dp round strokes), a glyph per state, a one-word label under every control, and a per-control
   motion signature that exists only at the HIGH tier.

### Icons
| Control | On / default | Off / alternate |
|---|---|---|
| Microphone | `flash_ic_microphone` | `flash_ic_mic_off` (capsule broken by a slash) |
| Video | `flash_ic_video_call` (`FlashIcons.Video`) | `flash_ic_video_off` |
| Flip camera | `flash_ic_camera_flip` (two chasing arrows around a lens, 180-degree symmetric) | n/a |
| Audio route | `flash_ic_speaker` | `flash_ic_earpiece` (phone) |
| End / decline | `flash_ic_hangup` (the accept handset turned onto its back via a `<group rotation="135">`) | n/a |
| Accept | `flash_ic_call_accept` (unchanged) | n/a |
The bell is now `FlashIcons.NotificationOff` (was `Mute`); the group-video strip's muted-participant badge uses `MicOff`. The geometry of
mic-off / video-off is the usual "shape broken by a diagonal" construction and was drawn here, not imported.

### State language
Resting control: subtle surface, primary glyph. Control in its **emphasised** state (muted mic, camera off, speaker on): solid inverted surface
(`textPrimary` fill, `backgroundApp` glyph). The glyph also changes, so state never depends on colour alone. Flip is dimmed (and disabled for
TalkBack) while the camera is off instead of vanishing, so the row does not jump. Labels show the **state** ("Mic on / Muted", "Video on / Video off",
"Speaker / Earpiece", "Flip", "End"); the TalkBack description is the **action** ("Mute microphone", "Turn camera on", "Switch to earpiece").
The label is hidden from TalkBack so the button is not read twice. Audio calls show Mic, Route, End; video calls show Mic, Video, Flip, Route, End.

### Motion (by performance tier)
`FlashPerformanceMode` collapses to two UI behaviours (ADR-036 / `FlashPerformanceMode` KDoc): **HIGH animates; MEDIUM and LOW both set
`reduceMotion` and `minimalChrome`.** This work follows that policy rather than inventing a third level.

| | HIGH | MEDIUM / LOW |
|---|---|---|
| Mic, Route | new glyph pops in on a spring (scale 0.5 to overshoot to 1, fade in) | instant swap |
| Video | eyelid: the new glyph opens vertically (scaleY 0.08 to 1) | instant swap |
| Flip | a half turn per tap on a spring | no turn |
| End / Decline | handset tips 28 degrees while pressed, springs back | static |
| Ripple ring | on a toggle a ring leaves the button (signal out: mic live, camera on, speaker on) or collapses onto it (signal cut) | none |
| Accept (incoming) | the handset swings like a ringing phone for ~0.6 s and a ripple leaves the button, then 1.4 s rest, repeating | static |
| Colour change | 120 ms tween | snaps |
| Dock surface | 85 % translucent over the video | opaque (`minimalChrome`) |
| Haptic | Tick on toggles, Reject on end / decline, Confirm on accept | the same (haptics are independent of reduce-motion, `FlashHapticPolicy`) |
Every animated value is read inside `graphicsLayer` / `drawBehind`, so a frame costs a render pass, never a recomposition (EXP-013). No loop runs
outside the RINGING state. The first composition never animates.

### Code
`ui/callui/.../FlashCallControlDock.kt` (dock, `FlashCallDockButton`, `FlashIncomingCallButton`, `CallDockText`, pure motion math),
`FlashCallScreen.kt` (uses them), `ui/theme/.../FlashIcons.kt` + `composeResources/drawable/flash_ic_*.xml`.
Tests: `FlashCallControlDockTest` (wording and motion math), `FlashCallControlDockRenderTest` (HIGH is mid-animation 60 ms after a toggle and
settled later; reduce-motion is already final; every state draws in light and dark; `FLASH_DOCK_SHOTS=<dir>` writes PNGs).

### Known limitations
- Not seen on a phone. The Skia render shows glyph legibility and states, not the feel of the springs.
- MEDIUM could afford a cheap fade; it is deliberately identical to LOW until a measurement says otherwise (ERROR-033 reasoning).
- Bluetooth / wired routes have no state in `FlashCallUiState`; the route control is still a two-way speaker / earpiece toggle (planned: audio-route picker).
- The single-camera case (most desktops) still shows Flip when the camera is on; hiding it needs a capability flag from the call core.

## UI-050f — In-call extras: audio output, More panel, badges, reactions, data saver (2026-10-02)

**Status:** IMPLEMENTED (unit-tested; not device-verified, `CALLX-01`...`CALLX-12`). Wire and rules: ADR-067, `docs/protocol.md` ("Call status").

### Purpose
The owner's list after the dock redesign: features a call needs beyond mute / camera / speaker. This section records what was built and, as
plainly, what was not.

### Approaches considered
1. A stock Material bottom sheet and Material icons. Rejected (AGENTS section 34).
2. A second row of dock buttons for every feature. Rejected: six buttons already fill a 360 dp phone.
3. **Chosen:** keep the dock to six slots (mic, video, flip, output, **More**, end), put the rest behind a custom panel, and show the other
   end's state as small badges where the person already is.

### What the screen does now
| Feature | Where | Notes |
|---|---|---|
| Audio output | dock button; opens a list (earpiece, speaker, Bluetooth, wired) only when a headset is in play, otherwise it is the old speaker toggle | the button shows the output in force; a desktop reports no routes and keeps the toggle |
| Remote badges | mic-off, camera-off, hand: under the name (1:1), next to the name on a group tile, on a group chip; the group status word also says "Camera off" / "Hand raised" | `FlashPeerBadges`; state is also in the semantics |
| Remote camera off | 1:1: the avatar and "<name>'s camera is off" cover the video | the surface stays composed (renderer rule) |
| More panel | reactions (like / love / wow), raise or lower hand, data saver, mirror my video, picture-in-picture (Android) | `FlashCallPanel`, custom; tap the dim, back, or the close glyph |
| Reactions | float up from above the dock at HIGH; sit in a still stack of at most 3 at MEDIUM / LOW | live-region announcement per reaction |
| Data saver | switch in More; a pill "Data saver on - Turn off" above the dock; 1:1 shows "Video paused"; the other side sees a note that it is on data saver | audio is untouched |
| Mirror my video | switch in More; flips the self preview only, never what is sent | 1:1 only; group self tiles are not mirrored |
| Picture-in-picture | Android: auto-enter on API 31+ for a live 1:1 video call, More row on API 26+; in PiP the screen draws the picture or the avatar only | `inPictureInPicture` |
| Keep screen on / proximity | host: screen stays on during a live video call; a live voice call on the earpiece turns the screen off against the ear | `FlashCallProximity` |
| Link chip + shield | "Local network" with a dot graded from RTT and loss; a "Verified" shield for a paired 1:1 peer | wording is deliberately limited to the pairing check |

### Motion
Same two behaviours as the dock: HIGH animates (the panel slides, reactions float and fade), MEDIUM / LOW show the same information still.

### Not built (and why)
Torch and tap-to-zoom, noise-suppression toggle, background blur, add-a-person to a 1:1 call, screen sharing, send a file or message from inside a
call, a LAN-versus-hotspot label. They need a native capability the project does not expose, a roster / signaling design of their own, or a
navigation decision. See ADR-067 "Not built".

### Known limitations
- Nothing here has been seen on a device. The link-quality thresholds are a first guess.
- A 1:1 call and an Android activity with `configChanges` are new combinations; see `CALLX-06`.
- Pre-API-31 phones enter picture-in-picture only from the More panel (no `onUserLeaveHint` hook was added).
- A proximity wake lock is released after 2 hours as a safety net.

## UI-050g — Screen share in calls (2026-10-09, ADR-102)

**Status:** DESIGNED, then IMPLEMENTED on 2026-10-09 (unit-tested; not device-verified, `SHARE-01`...`SHARE-14`). Wire, states and ladder:
`docs/calling/SCREEN-SHARE-DESIGN.md`. This section was written before the UI code (AGENTS section 34).

### Purpose
Let a person on the desktop app show a screen or a window to the other people in a 1:1 or group call, and let everyone else (phones
included) see it clearly. Android can watch; it cannot present yet.

### Approaches considered
1. A seventh dock button. Rejected: six buttons already fill a 360 dp phone (UI-050e), and a phone cannot present.
2. A full-screen picker route. Rejected: leaving the call surface hides the people.
3. **Chosen:** a row in the existing More panel ("Share screen" / "Stop sharing") that opens the same custom panel as a picker; while
   sharing, an indicator strip pinned to the top of the call that cannot be missed and carries its own Stop; receivers get a label and
   a letterboxed picture.

### Screens and copy (all text lives in `CallShareText`, unit-tested)
| Where | Text |
|---|---|
| More panel row, idle | "Share screen" / "Show your screen or a window to the call" |
| More panel row, sharing | "Stop sharing" / "You are sharing {title}" |
| Picker title | "Share your screen" |
| Picker sections | "Screens", "Windows" |
| Picker quality switch | "Share at lower quality" / "Smaller picture for a slow computer. Small text may be harder to read." |
| Picker, system dialog (Wayland) | "Your system will ask which screen or window to share." |
| Picker, someone else presenting | "{name} is presenting. If you share, their share stops." |
| Picker, nothing found | "Nothing to share was found." |
| Indicator, opening | "Starting to share..." |
| Indicator, live | "You are sharing {title}" with the action "Stop" |
| Indicator detail | "Nobody is watching yet" / "Seen by 1 person" / "Seen by N people" |
| Indicator, lowered | "Your computer is busy, so the picture is smaller" |
| Receiver label | "{name} is presenting" (1:1 and group); group tile chip "Presenting" |
| Notice, taken over | "{name} started sharing, so yours stopped" |
| Notice, source lost | "The shared window closed, so sharing stopped" |
| Notice, no frames | "Nothing could be captured from that screen, so sharing stopped" |
| Notice, failed | "Sharing could not start" |
| Notice, someone presenting | "{name} is presenting. Stop theirs first or share anyway" |
| Notice, watcher cap | "Only {n} people can watch your share at once" |

### Behaviour
- **Indicator** is a strip under the status bar, accent-coloured, with the screen-share glyph, a red dot and the words (never colour
  alone), a 48 dp "Stop" button, and a live-region description. It is drawn above the video and above every panel, so it is visible
  while the More panel is open. It does not hide while the call is minimised inside the app; picture-in-picture shows the picture only
  (unchanged).
- **Camera:** while sharing, the camera button is disabled (its description says "Camera is off while sharing"), the self preview tile
  shows nothing, and the camera comes back on its own when the share ends if it was on.
- **Receivers:** the presenter's picture is letterboxed (`CallVideoFit.Fit`), never cropped, in the 1:1 main surface and in the group
  tile. In a group the presenter is the main tile: the stage (about 70 % of the height) with everyone else in a row under it; the
  compact (one-video) layout shows the presenter as its one tile; what the person had pinned is restored when the share ends.
  Tapping the presenter's tile still pins and unpins it.
- **Motion:** none added. The indicator appears without animation (it is a safety indicator, it must be there on the first frame).
- **Accessibility:** the indicator and the receiver label are live regions; every control is 48 dp or larger; state is a word and a
  glyph.
- **Dark mode:** the strip uses the accent colour and `textOnAccent`; the label uses the same on-video scrim as the tile name bar.

### Not built
Presenting from Android (MediaProjection, a foreground service of type `mediaProjection`), audio of the shared content, annotation, a
remote-control channel, a thumbnail preview in the picker (the capturer gives titles only), and group voice calls (a share needs the
video call kind).
