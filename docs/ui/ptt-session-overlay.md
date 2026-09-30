# Component Research & Design Document — PTT Session Overlay

**Status:** IMPLEMENTED (device and accessibility verification pending)

**Component ID:** UI-051

**Last updated:** 2026-09-30 (Addendum A: desktop host)

**Owner phase:** PTT voice session, ADR-032 Phase 3

---

## Component

`PttSessionOverlay`, the in-app status and control surface shown while Flash is talking or listening.
The same session remains controllable from its Android notification while the app is backgrounded.

## Purpose

Make a strict half-duplex voice session visible and stoppable without tying its lifetime to a chat
route. The surface identifies whether the user owns the floor, shows elapsed time and measured
quality, and exposes the only primary action: Stop while talking or Leave while listening.

## Research sources

- Android notification and foreground-service guidance:
  https://developer.android.com/develop/ui/views/notifications/build-notification and
  https://developer.android.com/develop/background-work/services/fgs/service-types
- Android accessibility guidance for Compose:
  https://developer.android.com/develop/ui/compose/accessibility
- Existing Flash component studies and conventions in `calling-ui.md`, `notification-ui.md`,
  `motion-system.md`, `accessibility.md`, and `design-system.md`.
- Half-duplex interaction patterns studied in push-to-talk radios and voice-room applications:
  one explicit floor holder, immediate talk/listen status, and a persistent end action. No
  proprietary UI code or visual assets were copied.

## Existing approaches studied

1. A route-owned full screen. Rejected because navigation away could accidentally end or hide an
   active audio session.
2. A state-derived overlay above the existing shell. Keeps navigation and session lifetimes
   independent while making active audio impossible to overlook.
3. A system overlay window. Rejected because `SYSTEM_ALERT_WINDOW` is unnecessary, intrusive, and
   harder to make accessible.
4. Notification-only controls. Required for background operation but insufficient while Flash is
   foreground because state and quality would be hidden in the shade.

## What worked

- Deriving visibility directly from `PttFloorState` prevents UI/session lifetime disagreement.
- A single role-dependent action avoids ambiguous mute, pause, or floor-request semantics.
- Leaf-scoped elapsed, quality, and level subscriptions avoid recomposing the entire card.
- A notification mirrors Stop/Leave so the user retains control after backgrounding Flash.

## What did not work

- Route ownership couples audio lifetime to back navigation.
- Hold-to-talk UI conflicts with the hardware single-down broadcast and the specified toggle model.
- Constant high-rate animation wastes work on LOW-tier devices and ignores reduced-motion needs.
- Full-screen system intents are inappropriate for a non-urgent PTT session.

## Chosen approach

Use a modal scrim overlay derived from the engine's current floor state. Center one strong-surface
card containing role, elapsed time, sampled quality, optional level bars, and one Stop/Leave action.
The engine remains the sole owner of session state; the composable emits commands only.

## Why it was chosen

The overlay follows the same state-derived lifetime used by the call UI while preserving PTT's
simpler half-duplex interaction. It remains visible over any in-app route, needs no new permission,
and cannot outlive the floor state. Notification controls cover the background case.

## Visual specification

- Full-window `scrim` behind a centered `backgroundSurfaceStrong` card.
- 24 dp outer padding, 20 dp card padding, and 12 dp vertical spacing.
- `bodyEmphasis` for “You're talking” or “Listening — {name}”.
- `bodyDefault` for elapsed `m:ss`; `captionDefault` for quality and fallback activity text.
- Accent chip button with `textOnAccent`; copy is Stop for the holder and Leave for a receiver.
- Optional 24-bar level meter uses `borderSubtle` tracks and `accentPrimary` live levels.
- All colors, shape, spacing, typography, and motion decisions come from Flash theme tokens.

## Interaction specification

- Overlay appears for Talking or Listening and disappears for Idle.
- Stop releases the local floor and announces Stop to current members.
- Leave tears down local playout and sends an informational Leave to the holder.
- A deferred hardware press is consumed once the engine is ready; microphone permission is checked
  before attempting to talk.
- Busy call, voice recording, missing microphone permission, and no-peer outcomes use concise host
  toasts rather than silently failing.
- Back navigation does not own or end the session.

## Animation specification

Quality text samples at 1 Hz and level bars at no more than 10 Hz. LOW performance mode and reduced
motion replace moving bars with static Transmitting/Receiving text. The overlay has no decorative
entrance loop; session state, not animation completion, owns its lifetime.

## Gesture specification

No drag, swipe, or long-press behavior. Stop/Leave is an ordinary semantic click target. Hardware
PTT is toggle-based: a second accepted press stops the user's transmission.

## Accessibility requirements

- Stop and Leave expose explicit click labels matching their visible action.
- Status must remain understandable without color, animation, or the level meter.
- The control must retain at least a 48 dp effective target after physical-device review.
- Verify TalkBack reading order: role, elapsed time, quality, action.
- Verify large font/display size without clipped status or unreachable action.
- Reduced-motion mode must show static activity text and no level animation.

## Responsive behavior

The card fills available width inside 24 dp margins and remains centered in portrait or landscape.
Large screens keep the same focused card rather than stretching content across the window. Device
verification must cover compact rugged-handset displays and large font settings.

## Dark-mode behavior

The overlay uses the deliberate Flash dark/light theme tokens. The scrim, strong surface, border,
and accent colors are not inverted manually.

## Performance considerations

The root subscribes only to floor-state transitions. Elapsed time, sampled stats, and sampled audio
levels are isolated in leaf composables. Level rendering is disabled on LOW and under reduced motion.
No audio packet or raw meter stream is collected by the root card.

## Implementation notes

- App UI: `app/src/main/java/com/transfer/flash/ptt/PttSessionOverlay.kt`.
- State owner: `PttSessionEngine` and common `PttFloorMachine`.
- Background counterpart: `FlashNotificationManager` and `PttSessionActionReceiver`.
- No new UI dependency or asset is required.

## Testing checklist

- [ ] Physical-device talking and listening states
- [ ] Stop and Leave from overlay
- [ ] Notification Stop and Leave while backgrounded
- [ ] TalkBack reading order and action labels
- [ ] Large font / compact rugged handset
- [ ] Landscape and dark mode
- [x] Reduced-motion and LOW-tier static fallback implemented
- [x] Root recomposition isolated from level/stat sampling

## Known limitations

Physical-device proof is pending. Simultaneous floor-claim resolution and transport/audio lifecycle
correctness belong to the engine rather than this component, but block acceptance of the full flow.

## Future improvements

Consider a compact non-modal session chip only if field testing shows the modal foreground surface
blocks useful concurrent reading. Do not add controls until the half-duplex protocol supports them.

## What makes this Flash?

The surface is intentionally smaller than a call screen and stricter than a generic voice room: it
mirrors Flash's deterministic one-floor protocol, keeps local-first operation visible, and scales its
motion and sampling cost to the device tier without hiding the user's only safety action.

---

## Addendum A — Desktop host (2026-09-30, ADR-058)

**Status of this addendum:** DESIGNED, then IMPLEMENTED the same day; not device-verified.

The Windows desktop app now runs the same `PttSessionEngine` (ADR-058), so the surface above needs a
desktop form. The floor machine, the wording, the layout tokens and the level meter do not change; only
*where the card's code lives* and *how a desktop user starts a session* are new.

### Approaches compared (desktop)

1. **A second desktop-only card.** Rejected: two copies of the state-to-copy mapping ("You're talking",
   "Listening — {name}", Stop/Leave, the `Live • n listening • x ms` line) drift, and this card is the
   user's only safety action.
2. **Share the card, keep host wrappers.** The card, its three leaf composables and `formatPttElapsed`
   move to `:ui:callui` commonMain (`PttSessionOverlayContent`). The Android wrapper keeps what is
   Android-only (deferred hardware press, `RECORD_AUDIO` prompt, `FlashNotificationManager`); the desktop
   shell adds its own thin wrapper. **Chosen.**
3. **A OS-level always-on-top overlay window on desktop.** Rejected for now for the reasons row 3 of
   "Existing approaches studied" gives, plus a second AWT window to focus-manage.

### Behaviour on desktop

- **Talk control.** A mic button in the navigation rail footer (expanded two-pane layout) and a floating
  mic button above the bottom nav on tab roots (compact layout). One click toggles the floor
  (`FlashPtt.onPttButton()`), the same toggle semantics as the hardware press, *not* hold-to-talk.
- **Hotkey.** `Ctrl+Shift+T`, in-window only. **No global (system-wide) hotkey**: a global hook needs a
  native key hook the project does not have, and would fire while typing in other apps. Revisit only on an
  owner request.
- **Overlay.** Derived from `PttFloorState` exactly as on Android (visible for Talking/Listening). On
  desktop the scrim also swallows pointer input so a click cannot fall through to the list behind the
  card. `Esc` while a session is live = Stop/Leave.
- **Outcomes.** `NO_PEERS`, `CALL_ACTIVE`, `VOICE_NOTE_ACTIVE` and every engine notice are shown in the
  window's snackbar (Android uses toasts) using the same strings as the Android wrapper.
- **No permission step.** A desktop has no per-app microphone grant to request; Windows' microphone
  privacy switch is the gate and does not always report a denial (TEST-BACKLOG PTTD-03).
- **No notification actions.** The Android notification mirror has no desktop counterpart; the window is
  the only surface while the app runs. A minimised window keeps the session (the engine, not the window,
  owns it) but has no visible Stop; the button/hotkey need the window.

### Accessibility (desktop)

Same requirements as above, plus: the rail/floating button has a `contentDescription` that flips between
"Push to talk" and "Stop talking"; `Ctrl+Shift+T` is listed in the shortcuts help if one exists; keyboard
focus order is not changed (the button is pointer/hotkey only in this pass; a focusable button is
TEST-BACKLOG PTTD-07).

### Testing checklist (desktop)

- [x] Engine wiring exercised over two real `DesktopEngine`s (`DesktopEnginePttTest`)
- [x] Elapsed formatting (`PttElapsedFormatTest`, moved with the composable)
- [ ] Physical: desktop <-> phone, both directions (PTTD-01), Stop/Leave from the card, Esc, hotkey
- [ ] Hi-DPI / 125% / 150% scale and the 640x480 minimum window (PTTD-07)
