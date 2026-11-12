# Component Research & Design Document — Voice Message

**Status:** IMPLEMENTED — UI-019 playback (2026-08-21); UI-020 recording DESIGNED → IMPLEMENTED (2026-08-21)
**Component ID:** UI-019 (playback) / UI-020 (recording interface)
**Last updated:** 2026-08-21
**Owner phase:** Premium Chat UI — media track (after UI-018 media viewer)
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

`FlashVoiceMessageCard` — in-bubble voice message playback card: leading circular play/pause badge, discrete bar waveform with played/unplayed coloring and tap-to-seek, duration/remaining label, and playback-speed pill. This doc covers **UI-019 playback only**; UI-020 (recording interface / composer transformation) is a separate component that will extend this doc later.

## Purpose

Voice notes are a first-class message type in every premium messenger. Flash needs an in-bubble player that communicates: is there audio, how long is it, which part has been heard, where am I now — and gives one-tap play/pause, tap-to-seek, and speed control without leaving the conversation. Without it, a received voice file would fall into the generic UI-016 file card, which is the wrong presentation for media you *play*, not *open*.

Appears in: `FlashMessageBubble` (incoming and outgoing bubbles).

## Research sources

- Android Developers — Compose `Canvas` drawing, `pointerInput` tap/drag detection, `rememberInfiniteTransition`, `android.media.MediaPlayer` (current status: not deprecated but Media3 is the recommended path for new features).
- Compose Foundation — stable APIs only check against the project's resolved BOM (Foundation 1.10.0, verified during UI-018 research).
- Reference app behavior (interaction patterns only, no code copied): Telegram (discrete mirrored waveform bars, played-bar coloring, remaining-countdown ↔ duration label swap, tap-to-seek at bar position), WhatsApp (smooth filled waveform, circular play badge, duration label), Signal (plain linear progress bar), iMessage (waveform with drag scrubbing), Discord (waveform + playback speed control).

## Existing approaches studied

### Approach A — Discrete bar waveform + circular badge + speed pill (selected)

Telegram-style amplitude bars rendered in a Compose `Canvas` (bars ~3dp wide, 2dp gaps, heights from a pre-computed amplitude array). Played bars tinted accent, unplayed bars muted. Leading 48dp circular play/pause/download badge (reuses UI-016 badge geometry language). Trailing label shows remaining countdown while listening, total duration otherwise (Telegram behavior). Speed pill cycles 1× → 1.5× → 2× (Discord pattern). Tap-to-seek maps tap X → bar index → seek fraction.

### Approach B — Smooth continuous waveform (WhatsApp)

Path-interpolated smooth envelope. Rejected for v1: visually softer but harder to map taps to precise positions, more path math per frame, and the discrete bar look matches Flash's geometric icon/stroke identity better.

### Approach C — Linear progress bar (Signal)

Rejected outright: a plain progress strip is exactly the "generic progress bar" the master plan prohibits for this component, and it conveys no shape-of-audio information.

### Approach D — Real audio engine now (Media3 ExoPlayer)

Deferred: no real voice files exist in the current sample/transfer pipeline (attachment pipeline not connected — same situation as UI-016/UI-018 actions). Adding a Media3 dependency now would violate the document-before-dependency rule with nothing real to play. The v1 card implements the full UI contract and a timing-driven progress model; real decode/playback is a documented follow-up requiring a dependency ADR (Media3, Apache-2.0).

## What worked

- **Discrete bars** (Telegram): instantly readable shape-of-audio; trivially maps tap X → position; cheap to draw.
- **Remaining-countdown label** (Telegram): communicates "how much is left" while listening, swaps to total duration when untouched/finished — better than static labels.
- **48dp circular badge** (WhatsApp/iMessage consensus): consistent with the UI-016 file-card badge geometry already accepted.
- **Speed pill** (Discord): tiny, discoverable, does not clutter the bubble.

## What did not work

- Continuous-path waveforms: extra math, weaker seek mapping (rejected — see Approach B).
- Inline expanding player rows (Slack-style): over-complex inside a chat bubble; rejected.
- Putting the speed control behind long-press: undiscoverable; kept as visible pill.

## Chosen approach

Approach A. All timing/waveform math lives in pure Kotlin (`FlashVoiceMath`) → unit-testable without instrumentation. Playback progress in v1 is driven by a coroutine ticker scaled by the selected speed (demo/sample mode); wiring a real decoder is explicitly deferred (Approach D).

## Why it was chosen

- Zero new dependencies (AGENTS.md §3); uses only stable Foundation/UI APIs already resolved in the project.
- Reuses accepted Flash language: UI-016 badge geometry, `FlashShapes.attachment` container, semantic attachment colors, `FlashMotion` springs, `numericEmphasis` tabular figures for time labels.
- Pure-math core keeps with the established testing standard (`FlashImageGridLogicTest`, `FlashMediaViewerLogicTest`).
- Consistent with UI-016 precedent: transfer state comes from the model; the pipeline integration is a known limitation, not a silent gap.

## Visual specification

All values reference `FlashTheme` tokens; sizes below are component-local constants documented here.

- Container: `FlashShapes.attachment` (radius12), background `chatBgAttachmentIncoming`/`chatBgAttachmentOutgoing`, hairline border `borderSubtle` @ 50% — identical surface treatment to `FlashFileMessageCard`.
- Layout: Row, vertical center; padding `space12` horizontal / `space8` vertical; min width ~220dp, max = bubble width.
- Badge: 48dp circle, `accentPrimary` background when playable (Play/Pause icons white); `graphite500`-tinted neutral when NotDownloaded (Download icon); `textError` tint when Failed (Retry icon). Matches `FlashFileIconBadge` sizing.
- Waveform: height 28dp, bars 3dp wide, 2dp gap, corner radius 1.5dp (fully rounded caps), vertically centered per bar. Played bars: `accentPrimary` (incoming) / `chatTextOutgoing` (outgoing, keeps contrast on pulse-tinted bubbles). Unplayed bars: `chatTextTimestamp` @ 45% alpha (incoming) / `chatTextTimestampOutgoing` @ 45% (outgoing).
- Time label: `numericEmphasis` (tabular), `metadataDefault` size, secondary text color; shows remaining countdown (`-0:12`) while mid-playback, total (`0:23`) when untouched or finished.
- Speed pill: `chip` shape, hairline border, `metadataEmphasis` label ("1×" / "1.5×" / "2×"), 32dp min height, active accent border while speed ≠ 1×.

## Interaction specification

| Input | Behavior |
|---|---|
| Tap badge | NotDownloaded → request download callback; Failed → retry callback; else toggle play/pause |
| Tap waveform | Seek to tapped fraction (bar-snapped), keep play state |
| Horizontal drag on waveform | Live scrub: preview position while dragging, commit on release |
| Tap speed pill | Cycle 1× → 1.5× → 2× → 1× |
| Long-press bubble | Existing UI-007/UI-008 context menu (unchanged — card consumes only its own taps) |
| Playback end | `isPlaying=false`, elapsed=duration, label returns to total duration |

## Animation specification

Via `FlashTheme.motion` (UI-037):

| Animation | Trigger | Spec |
|---|---|---|
| Badge press scale 0.90× | Badge pressed | `springSnappySpec()` (matches composer send button) |
| Card press scale 0.97× | Card pressed | `springSnappySpec()` (matches file card) |
| Played-bar count growth | Progress ticks | Direct Canvas redraw from state — no per-bar animation cost |
| Speed change | Pill tap | Progress ticker rate changes immediately; no animation needed |
| Reduce motion | System setting | Press scales collapse via existing token contract; no decorative motion added |

No infinite transitions while paused; the only ongoing work while playing is the 100ms progress ticker.

## Gesture specification

- Waveform tap/drag handled by one `detectTapGestures` + `detectDragGesturesAfterLongPress`-free pair: simple `detectTapGestures(onTap)` for seek plus `horizontalDrag` via `detectHorizontalDragGestures` for scrub. Both live in one `pointerInput` keyed on bar count.
- Seek fraction clamped 0..1; bar snapping: `floor(tapX / (width / barCount))`.
- Vertical drags are ignored (bubble swipe-to-reply and list scroll unaffected — child consumes horizontal only after slop within the waveform bounds).
- RTL: waveform is drawn LTR (audio timeline convention); time label placement follows layout direction naturally. Documented decision: audio timelines do not mirror.

## Accessibility requirements

- Badge: Role.Button, contentDescription "Play voice message" / "Pause voice message" / "Download voice message" / "Retry download"; 48dp target.
- Whole card semantics (merged): "Voice message, 23 seconds. Double-tap to play."
- State description updated with play state ("Playing", "Paused").
- Speed pill: own Button semantics, description "Playback speed 1.5 times".
- Waveform: `contentDescription = null` (decorative; position conveyed by state description + seekable actions). Custom seek accessibility actions (`Actions.SeekForward/Back`-style via custom actions) deferred — listed under Future improvements.
- Contrast: played/unplayed bar pairs ≥ 3:1 against both bubble surfaces; time label uses secondary text tokens.

## Responsive behavior

- Phone portrait/landscape: card fills bubble width minus padding; waveform flexes (bar count fixed at model resolution, spacing absorbs width).
- Tablet/foldable/desktop: same code path; bubble max-width cap (UI-005) bounds growth.
- Large fonts: time label scales with `numericEmphasis`; card height grows; badge fixed 48dp.

## Dark-mode behavior

Uses semantic tokens exclusively — incoming/outgoing surfaces and text colors flip correctly in dark theme. No hardcoded colors. Accent played-bars use `accentPrimary` (theme-aware).

## Performance considerations

- Waveform drawn once per progress tick in `Canvas` (DrawScope) — no allocation in the draw pass (amplitude buckets cached via `remember`).
- Progress ticker: single 100ms coroutine while playing; zero work while paused.
- Bar count capped at 40; bucketing is O(n) once per size/model change.
- No recomposition during playback except the time label text (small, isolated composable).

## Implementation notes

**Implementation status (2026-08-21):** UI-019 implemented. `ui/chat/.../FlashVoiceMessageCard.kt` contains `FlashVoiceMessageCard`, `FlashVoiceBadge`, `FlashVoiceWaveform` (Canvas, tap-to-seek + drag scrub), `FlashVoiceSpeedPill`, and `FlashVoiceMath` (pure logic). Model: `FlashVoiceAttachmentUi` + `FlashMessageUi.voiceAttachments` in `:core:messaging`. Integrated in `FlashMessageBubble`; sample voice message added to `sampleFlashConversationState()`. Unit tests: `FlashVoiceLogicTest.kt` (13 tests — duration format, bucketing/peak preservation, tap fraction/bar mapping, elapsed-from-fraction, speed cycle/labels, trailing-label swap, played-bar count).

Implementation deviations from this spec (minor):
- Scrub preview uses the same played-color rendering as settled progress (no separate scrub tint).
- The demo-mode ticker advances `elapsedMs` by `TICK_MS × speed` per tick; playback auto-stops at track end.
- **Device-feedback revision (2026-08-21):** layout restructured — speed pill sits under the badge on the **left side**, remaining/duration label on the **right side**, waveform unobstructed full-width between them (was: time+speed stacked over the waveform's right edge). Card now supports long-press → message context menu via `onLongPress` (`combinedClickable` + haptic). Play/pause badge icon swaps through an animated spring scale+fade `AnimatedContent` morph. Focus-overlay preview for voice messages shows `Voice message • m:ss` via the new `flashMessageContentSummary()` helper in `:core:messaging` (also covers photos and files).

Target files:
- `core/messaging/.../model/FlashMessagingModels.kt`: add `FlashVoiceAttachmentUi(id, uri, durationMs, amplitudes: List<Int>, mimeType, transferStatus: FlashFileTransferStatus)` + `voiceAttachments: List<FlashVoiceAttachmentUi>` on `FlashMessageUi`.
- `ui/chat/.../FlashVoiceMessageCard.kt` (new): `FlashVoiceMessageCard`, `FlashVoiceBadge`, `FlashVoiceWaveform`, `FlashVoiceMath` (pure logic), demo-mode progress ticker.
- `ui/chat/.../FlashMessageBubble.kt`: render `voiceAttachments` cards.
- `core/messaging/.../util/FlashMessagingUtils.kt`: sample voice message in the sample conversation.
- `ui/chat/src/test/.../FlashVoiceLogicTest.kt` (new).

Dependencies: **none added**. Real audio decode/playback intentionally deferred (requires Media3 ADR — see Known limitations).

## Testing checklist

- [x] Unit tests green: duration formatting, amplitude bucketing, tap→bar mapping, speed cycling, remaining-label logic
- [ ] Compose preview (idle / playing / downloaded / failed states)
- [ ] Physical device (Samsung SM_G986U1): play/pause, seek by tap, scrub by drag, speed cycle, label swap, bubble context menu unaffected
- [ ] Dark mode (both bubble directions)
- [ ] Large font / display size
- [ ] Reduced motion (press scales collapse)
- [ ] Performance spot-check: playing card in scrolling list — no jank

## Known limitations

- **No real audio output in v1**: progress is ticker-driven (demo mode); decode/playback requires the attachment pipeline + a Media3 dependency ADR (master plan §10 process).
- Amplitudes arrive pre-computed in the model; on-device amplitude extraction happens at record/send time (UI-020 scope).
- No background/continued playback across screens; playback stops when the card leaves composition (by design for v1).
- No accessibility seek custom-actions yet.

## Future improvements

- Media3 ExoPlayer integration + dependency ADR; wire `onTogglePlay` to real playback state.
- Recording-side amplitude extraction + upload (UI-020).
- Accessibility custom seek actions + scrubbing announcements.
- Voice-to-text transcript chip under the waveform.
- Paused-position persistence per message.

## What makes this Flash?

The card speaks Flash's existing visual dialect — the UI-016 badge geometry, attachment surface treatment, Pulse-accent played bars, tabular numeric time labels, and UI-037 spring press physics — rather than importing any player aesthetic. The discrete geometric waveform mirrors the 2.0dp-stroke icon language, and the remaining-countdown + speed-pill interaction set is Flash's own composition of the best patterns studied, not a clone of any single app. All behavior math is pure Kotlin owned by the design system's testing standard.

---

# UI-020 — Voice Recording Interface

**Status:** DESIGNED → IMPLEMENTED (2026-08-21)
**Depends on:** UI-011 (composer), UI-019 (playback model)

## Component

`FlashMicButton` + `FlashVoiceRecordingBar` — the composer transforms into a recording surface: press-and-hold the microphone to record, slide left past a threshold to arm cancel, slide up past a threshold to lock into a persistent recording panel with trash / pause / send controls.

## Purpose

Sending a voice note must be faster than typing. The recording interface lives inside the existing composer so the user never leaves the conversation: hold → speak → release to send, with escape hatches (slide-to-cancel, lock for hands-free) and live feedback (timer, amplitude strip).

## Research sources

- Reference app behavior (interaction patterns only): WhatsApp (hold mic → record; release sends; slide left reveals "release to cancel" arrow), Telegram (hold; slide up locks into persistent panel with trash/pause/send; timer + waveform), Signal (hold + slide-to-cancel), iMessage (tap opens full-screen recorder — rejected: leaves the conversation context).
- Android Developers — Compose low-level gesture APIs (`awaitEachGesture`, `awaitFirstDown`, `positionChange`) — same stable pointer-input stack verified during UI-018.

## Existing approaches studied

1. **Hold + slide-left-cancel, release-sends** (WhatsApp/Signal) — lowest friction; adopted as the default path.
2. **Hold + slide-up-lock** (Telegram) — adopted as the hands-free extension; locked panel adds explicit trash/pause/send buttons.
3. **Tap-to-open full-screen recorder** (iMessage) — rejected: heavy context switch for short notes.
4. **Separate record screen / bottom sheet** — rejected: breaks the composer-in-place principle.

## Chosen approach

Hybrid 1+2 in the composer row. The send button position becomes a mic button when the draft is blank (WhatsApp pattern). The gesture state machine is pure Kotlin (`FlashVoiceRecordingMath.resolveHoldSlide`) → unit-testable thresholds.

### State machine

```text
Idle ──press──▶ Holding ──release──▶ Send   (if elapsed ≥ MIN_RECORD_MS)
                  │  ▲                        (else silent discard)
                  │  └── drag back ──┘
                  ▼
             CancelArmed ──release──▶ Cancel
Holding/CancelArmed ──drag up past LOCK threshold──▶ Locked
Locked: trash tap = Cancel · pause toggle · send tap = Stop & Send
```

### Thresholds (constants, unit-tested)

```text
CANCEL_SLIDE_DP   = 96f   (leftward)
LOCK_SLIDE_DP     = 72f   (upward)
MIN_RECORD_MS     = 500L  (shorter presses discard silently)
AMPLITUDE_SMOOTHING_ALPHA = 0.35f (EMA on incoming amplitude samples)
TICK_MS           = 100L
```

Slide target resolution: dominant-axis rule — leftward displacement claims Cancel when `|dx| ≥ |dy|` and passes threshold; upward claims Lock otherwise. Moving back inside thresholds re-arms Holding.

## Visual specification

- Mic button: same 40dp circle geometry as `FlashSendButton`; transparent bg + tertiary icon when idle; accent bg + white icon while recording.
- Recording bar replaces the whole input row (`AnimatedContent`, motion tokens): `composerInput`-shaped pill, error-tinted when CancelArmed.
- Hold mode layout: pulsing red dot + `m:ss` timer · amplitude strip (weight) · "‹ Slide to cancel" hint (secondary text).
- Locked mode layout: trash button (error tint) · amplitude strip (weight) · pause/resume button · timer · accent circular send button.
- Amplitude bars: 3dp wide / 2dp gap, rounded caps, accent color — identical language to the playback waveform (UI-019).

## Interaction / gesture specification

- Press starts recording immediately; haptic on start, LongPress haptic on lock trigger, TextHandleMove on cancel-arm.
- Release in Holding sends (if ≥ MIN_RECORD_MS); release in CancelArmed cancels; release in Locked does nothing (panel persists).
- Trash/send/pause are real buttons in Locked mode — every gesture-only action has a button alternative (a11y requirement).
- RTL: slide directions are physical (left = cancel), matching system conventions; documented decision.

## Accessibility requirements

- Mic button: Role.Button, "Hold to record voice message"; recording state announced via stateDescription ("Recording", "Recording paused").
- Timer text is live-readable; amplitude strip decorative (`contentDescription = null`).
- Locked panel exposes trash/pause/send as standard buttons.

## Performance considerations

- One 100ms ticker while recording; amplitude list capped (strip renders last 28 samples); Canvas draw only.
- No recomposition of the message list during recording (state local to composer).

## Implementation notes

Target files:
- `ui/chat/.../FlashVoiceRecording.kt` (new): `FlashRecordingPhase`, `FlashVoiceRecordingMath`, `FlashMicButton`, `FlashVoiceRecordingBar`.
- `ui/chat/.../FlashComposer.kt`: mic/send swap when draft blank; recording bar replaces input row during recording; `onSendVoice: (FlashVoiceAttachmentUi) -> Unit` callback.
- `ui/chat/src/test/.../FlashVoiceRecordingLogicTest.kt`.

Dependencies: **none added**.

## Known limitations

- **Demo-mode capture**: amplitudes are procedurally generated (smoothed random walk) and no audio file is produced — real capture requires a `RECORD_AUDIO` permission flow + `MediaRecorder`/`AudioRecord` engine + transfer pipeline integration (platform/dependency ADR at that point). UI contract, gestures, and thresholds are final.
- Slide-to-cancel has no button alternative in hold mode (locked mode does); noted for a dedicated accessibility pass.
- IME dismissal on record start is not forced (keyboard may remain open).

## Testing checklist

- [x] Unit tests green: slide-target resolution (dominant axis + thresholds), EMA smoothing bounds, demo-amplitude clamping, short-press discard rule
- [ ] Physical device: hold→speak→release sends; slide-left arms cancel (bar tints red); release cancels; slide-up locks; trash/pause/send work; timer runs; short tap discards silently

2026-11-09: After a lock gesture is released, the trailing slot sends the active recording instead
of starting a second capture. A start refused by the host cannot enter the slide or lock states.
The original hold and in-bar controls remain. See VOICE-BTN-01..06 for physical verification.
- [ ] Dark mode; large fonts; reduced motion (pulse collapses)
