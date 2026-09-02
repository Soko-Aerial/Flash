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
  live `mm:ss` duration (active) → "Call ended" / failure reason (ended/failed).
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

- v1 is 1:1 only (no group calls).
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
