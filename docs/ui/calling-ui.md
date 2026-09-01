# Component Research & Design Document — Calling UI

**Status:** DESIGNED

**Component ID:** UI-050

**Last updated:** 2026-09-02

**Owner phase:** Voice/video calling (`:core:calling` + `:ui:calling`)

---

## Component

`FlashCallScreen` — full-screen in-call surface (audio + video), plus the incoming/outgoing
call affordances that already exist in `FlashChatHeader` (call / video-call buttons, UI-050
wires them to real behavior).

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
  tap to switch which stream is large). Simple, predictable, no gesture bugs.
- State-driven UI: one sealed `FlashCallUiState` drives every visual (dialing / ringing /
  connecting / active / ended / failed). No imperative UI branching.
- Audio calls: large avatar + `FlashBrandAnimation` pulse + status text — reuses existing
  identity visuals, keeps v1 small.
- Duration counter only while `Active`.
- `SurfaceViewRenderer` init on ON_RESUME / release on ON_PAUSE with sink add/remove wrapped
  in `runCatching` (track may already be disposed while paused) — from the webrtc-kmp sample.

## What did not work

- Floating window overlays (approach 3) — permission model unacceptable.
- Putting the call UI inside the conversation screen composable tree directly — couples
  call lifetime to message-list lifetime; back navigation would kill the call.
- Material `Slider`/`Switch`-style call controls — off-design-system; Flash uses its own
  icon buttons (`FlashHeaderIconButton` pattern, 48dp targets).

## Chosen approach

**Approach 2**: a dedicated `FlashCallScreen` composable hosted as a full-screen route in
the existing navigation stack, with call *state* owned by a process-level
`CallCoordinator` (in `:core:calling`) that outlives the route. Back from an active audio
call minimizes to an ongoing-call chip in the chat header (call continues); video calls
keep the screen (camera preview requires a visible surface). Hanging up or the peer
hanging up ends the call and pops the route.

## Why it was chosen

- One Activity, one theme, one navigation system — matches ADR-020's dependency-free shell
  and UI-033's snapshot-state stack.
- Call state outside Compose (holder pattern, same as `DiscoveryEngineHolder`) keeps the
  WebRTC session alive across configuration changes and route changes.
- FGS story stays compliant: the call FGS is started while the app is foreground (user taps
  call / answers from notification), which is the only legal way to start a microphone FGS.

## Visual specification

All tokens from `FlashTheme` (`FlashColors`, `FlashTypography`, `FlashShapes`,
`FlashSpacing`, `FlashMotion`, `FlashIcons`):

- **Background:** `colors.background` for audio calls; video fills edge-to-edge (remote
  stream), PiP has `FlashShapes.cornerL` + 1dp `colors.surfaceVariant` border.
- **Peer identity:** avatar via existing `FlashAvatar` system (seeded by device id), name in
  `FlashTypography.titleM`, status line in `FlashTypography.bodyM` +
  `colors.textSecondary`.
- **Status text:** "Calling…" (dialing) → "Ringing" (ringing) → "Connecting…" (connecting) →
  live `mm:ss` duration (active) → "Call ended" / failure reason (ended/failed).
- **Controls (bottom row, 48dp targets, `FlashSpacing.space4` gaps):**
  - Audio: mute (`FlashIcons.Mute`), speaker (new `flash_ic_speaker`), hang up
    (new `flash_ic_hangup`, tinted `colors.textError` background circle).
  - Video: mute, camera toggle (new `flash_ic_camera_flip`), video off
    (`FlashIcons.Camera`), hang up.
  - Incoming (ringing): accept (new `flash_ic_call_accept`, accent circle) + decline
    (hangup styling).
- **Pulse:** avatar pulse while ringing/active audio — `FlashMotion` spring, collapses
  under reduced motion (UI-038 rule).
- **Dark mode:** video calls are inherently dark-surface; audio call background follows
  `FlashTheme` dark palette. PiP border uses `colors.surfaceVariant` in both modes.

## Interaction specification

- Tap accept → `coordinator.accept()`; tap decline/hang up → `coordinator.decline()` /
  `coordinator.hangUp()`.
- Tap PiP ↔ large video → swap which stream is large (local ↔ remote).
- Mute toggles `audioTrack.enabled`; camera-off toggles `videoTrack.enabled`;
  camera-flip calls `VideoTrack.switchCamera()`.
- Back gesture on audio call → minimize (chip in header, call continues); back on video
  call → hang-up confirmation is NOT added in v1 (back minimizes too; camera keeps running
  — acceptable v1 simplification, see Known limitations).
- System back while ringing (incoming) → decline.
- Disabled controls: none in v1 (all controls always tappable; no-op states are hidden
  instead — e.g. no camera-flip button on audio calls).

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
  recomposition. Track sinks added/removed on lifecycle, not on recomposition.
- Duration counter uses a single `LaunchedEffect` ticking `MutableStateFlow` — one
  recomposition/second on a leaf text node only.
- Call state reads are `collectAsState` on `StateFlow`s from the coordinator; no polling.

## Implementation notes

- New module `:ui:calling` (compileSdk 37, Compose, depends on `:ui:theme`, `:core:calling`).
- New icons: `flash_ic_speaker`, `flash_ic_hangup`, `flash_ic_call_accept`,
  `flash_ic_camera_flip` (Flash-owned vectors, UI-002 rules).
- Renderer: `AndroidView` + `SurfaceViewRenderer` + `WebRtc.rootEglBase.eglBaseContext`,
  `ScalingType.SCALE_ASPECT_BALANCED` remote / `SCALE_ASPECT_FIT` local PiP.
- Route hosting: `MainActivity` pushes `FlashCallScreen` onto `FlashNavigationState` when
  `CallCoordinator.state` becomes non-null and pops when it becomes null (single source of
  truth — no manual push/pop pairing bugs).
- License note: `webrtc-kmp` is MIT; wraps `io.github.webrtc-sdk:android` (WebRTC native,
  BSD-3) — documented in ADR-025.

## Testing checklist

- [ ] Compose preview (audio + video, all states)
- [ ] Physical device: two-phone audio call
- [ ] Physical device: two-phone video call
- [ ] Dark mode
- [ ] Large font / display size
- [ ] RTL
- [ ] Reduced motion
- [ ] Performance spot-check (video call thermals over 5 min)

## Known limitations

- v1 is 1:1 only (no group calls).
- No call history entries in v1 (calls are ephemeral; a future component may log them).
- Back on video call minimizes rather than hanging up (camera keeps running) — deliberate
  v1 simplification; revisit with a "minimize or end?" bottom sheet if it confuses users.
- No Bluetooth-device picker in v1 (speaker/earpiece toggle only).
- Screen sharing not in scope.

## Future improvements

- Call history rows in the conversation timeline.
- Bluetooth headset routing picker.
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
