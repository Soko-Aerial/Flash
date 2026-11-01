# accessibility

**Status:** IMPLEMENTED (targeted audit — UI-038)  
**Component ID:** UI-038  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Depends on:** UI-037 (`FlashMotion` reduce-motion contract), UI-005/UI-011/UI-018/UI-019 components

---

## Windows reduced-motion integration — reviewed 2026-11-01 (ADR-308, ERROR-947)

**Implemented and host/native-query tested; live visual toggle not verified (`PERF-E08`).** This desktop integration
changes no chat layout, motion tokens, Android accessibility behavior or dependency.

- Use existing JNA to call `SystemParametersInfoW` with `SPI_GETCLIENTAREAANIMATION = 0x1042`, zero `uiParam`/flags,
  and a 32-bit BOOL output (`IntByReference`). Only a successful query whose output is zero means animation is off.
  A failed query is unknown, not an affirmative reduced-motion preference.
- A cold Flow queries off the UI thread every five seconds, emits distinct values and preserves the last learned
  preference on later failures. Compose collects it at application root above the conditional main window.
  Main-window and toast motion combine the OS preference with Flash's setting by OR.
- Rejected approach: undocumented AWT property names and non-observable atomic/TTL state neither establish the
  Windows preference nor notify Compose. The delivered macOS detection claim is superseded; this implementation
  detects Windows only. Existing in-app reduced motion remains available elsewhere.
- Mapping, observable-update/failure-retention tests and a read-only query against the real Windows API passed.
  Manually changing Windows Animation effects must still be checked with the main window/toast and hide/restore,
  without another recomposition event. A successful native read is not visual acceptance.

**Verified official source, checked 2026-11-01:**
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-systemparametersinfow — output BOOL,
`SPI_GETCLIENTAREAANIMATION`, Vista+/Server 2008+ API. Record host/build/settings and actual visual outcome in
`PERF-E08`; no blanket Linux/macOS OS-preference support is claimed.

## Component

Accessibility layer across chat composables: semantics coverage (content/state
descriptions, roles, live regions), touch-target compliance, color-independence of
meaning, and the reduce-motion contract. Haptic accessibility is documented in
[motion-system.md §UI-039](motion-system.md) and implemented in
`ui/theme/.../FlashFeedback.kt`.

## Purpose

Guarantee that Flash's P2P messaging UI is usable with TalkBack, large fonts,
switch access, and reduced-motion settings — without restructuring layouts or
compromising the custom (non-Material) visual identity.

## Research sources

| Source | What was studied |
|---|---|
| [Compose Accessibility — Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) | `contentDescription`, `stateDescription`, `Role`, `liveRegion` (`Polite` default; avoid on frequently-updating content like countdown timers); merging/clearing strategies |
| [Compose Accessibility — API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | 48dp minimum touch targets; Compose automatically expands clickable targets below 48dp outside visual bounds; decorative icons use `contentDescription = null`; unique per-item descriptions in lists |
| [Make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps) | Descriptions convey purpose not visuals; avoid "Submit button"-style redundancy; 48×48dp ≈ 9mm target guidance |
| [Accessibility codelab](https://developer.android.com/codelabs/jetpack-compose-accessibility) | Touch-target expansion patterns; `clearAndSetSemantics` + custom actions; `stateDescription` for toggleable rows |
| [Touch target size — Google Support](https://support.google.com/accessibility/android/answer/7101858) | 8dp separation between targets; targets extend beyond visual bounds |
| [Android reduced motion — `AccessibilityManager`](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#isReduceMotionEnabled()) | API 33+ `isReduceMotionEnabled()`; legacy `ANIMATOR_DURATION_SCALE == 0` heuristic |

No proprietary code was copied; fixes below are mechanical semantics additions.

## Existing approaches studied

### A — Full rescreen with Material components for built-in a11y

**Verdict:** Rejected — violates AGENTS.md §34 prohibition on Material-template chat UI;
Flash's custom chrome already carries most needed semantics manually.

### B — Targeted semantics audit + mechanical fixes on existing composables (chosen)

**Pros:** Small diffs, no layout restructure, directly addresses real gaps found.  
**Cons:** Requires re-audit when new components land (checklist below mitigates).

## What worked

- `FlashMotion`'s centralized `reduceMotion` flag: every animation already branches on it;
  no component-level work was needed for motion reduction (see contract summary below).
- Components designed post-UI-001 (search bar, voice card, file card) already shipped
  `contentDescription`, `stateDescription`, roles, and live regions — the audit found the
  gaps concentrated in older chrome.

## What did not work

- Relying on merged text alone for bubbles: selection/search highlight is drawn via
  border + accent tint (color-only) and was invisible to TalkBack → fixed with
  `stateDescription`.
- Assuming small clickables are inaccessible without layout change: Compose expands
  touch targets beyond bounds by default, so sub-48dp *visual* sizes are acceptable
  where expansion does not overlap neighbors (documented per finding below).

## Chosen approach

Targeted audit of the six conversation-critical composables
(`FlashMessageBubble`, `FlashMessageList`, `FlashComposer`, `FlashChatHeader`,
`FlashVoiceMessageCard`, `FlashMediaViewer`) against four checks:
missing content/state descriptions, touch targets < 48dp, color-only meaning, missing
live regions on dynamic counters. Fix mechanically in place; log every finding.

## Why it was chosen

Scope discipline (AGENTS.md §34 research-first, minimal mechanical edits) plus the fact
that Flash's token system already centralizes motion; only semantic glue was missing.

## Visual specification

N/A — accessibility fixes add no visuals. Selection highlight remains accent border +
tint but is now also announced as state.

## Interaction specification

- All interactive elements expose `Role.Button` (or inherit from `clickable`).
- Dynamic counters announce via `LiveRegionMode.Polite` (never Assertive; never on
  high-frequency tickers such as recording timers or waveform scrubbing).

## Animation specification

Covered entirely by the **reduce-motion contract** in `ui/theme/.../FlashMotion.kt`
(UI-037), which this component consumes and documents:

- `FlashMotion.isReduceMotionEnabled(context)` detects API 33+
  `AccessibilityManager.isReduceMotionEnabled` **or** `ANIMATOR_DURATION_SCALE == 0`.
- `rememberFlashMotion()` exposes one `reduceMotion` flag; all named transitions
  (`messageEnter`, `statusCrossfade`, `screenTransition`, …) collapse to instant swaps /
  zero-duration specs when set.
- Component rule: press scales, pulses, staggered entrances, and swipe springs all gate
  on `!motion.reduceMotion` (verified during this audit in bubbles, attachment button/
  sheet/tiles, voice badge/card/mic, quick reactions, send button, search chrome).
- Presence/delivery/encryption state is never conveyed by animation alone — each has a
  textual description or label (delivery icon a11y strings, header status text).

## Gesture specification

Swipe-to-reply threshold haptic + reveal are supplemented by the bubble's long-press
menu, so gesture-only functionality has an accessible alternative. Media viewer gestures
(pinch zoom, dismiss drag) have chrome-button equivalents (close/save/share).

## Accessibility requirements

See findings table + checklist below. Ongoing rule for new components:
every interactive element needs role + description (or inherited behavior),
dynamic counters need polite live regions, state must be describable without color.

## Responsive behavior

Not affected. Large-font check deferred to device QA (checklist).

## Dark-mode behavior

Not affected — descriptions are theme-independent; contrast validated in UI-001 palettes.

## Performance considerations

Live regions must not be attached to frequently-updating nodes (per Android guidance):
recording timer and voice elapsed labels deliberately have none; counters (search
results, page index, unseen pill) change rarely enough for Polite announcements.
`graphicsLayer` press-scale animations remain GPU-composited under reduce-motion gating.

## Implementation notes

### Audit table (component / issue / severity / fix)

| # | Component | Issue | Severity | Fix |
|---|---|---|---|---|
| 1 | `FlashMessageBubble.kt` | Selected/search-highlighted state conveyed only by border + tint (color-only meaning); TalkBack silent | High | Added `stateDescription = "Selected"` inside bubble's merged semantics when `isSelected \|\| isHighlighted` |
| 2 | `FlashChatHeader.kt` | Avatar/group-collage clickables had no role/contentDescription (opens conversation info) | Medium | Added `role = Role.Button` + `contentDescription = "View conversation info"` to both avatar branches |
| 3 | `FlashChatHeader.kt` | `FlashHeaderIconButton` had contentDescription but no `Role.Button` | Low | Added `role = Role.Button` |
| 4 | `FlashMediaViewer.kt` | Page counter ("3 / 7") changes on swipe with no live region | Medium | Added `liveRegion = LiveRegionMode.Polite` to counter text |
| 5 | `FlashMediaViewer.kt` | Chrome icon buttons lacked explicit `Role.Button` | Low | Added `role = Role.Button` to `FlashIconButtonChrome` (descriptions already present) |
| 6 | `FlashMessageList.kt` | "N new messages" pill counter updates while visible without announcement | Medium | Added `liveRegion = LiveRegionMode.Polite` to pill semantics |
| 7 | `FlashReactionChip.kt` (audit-only) | Visual min size 36×28dp < 48dp | Info / accepted | No change: Compose expands clickable touch target beyond bounds (API-defaults doc); chips are ≥ space4 apart; layout restructure prohibited |
| 8 | `FlashMessageContextMenu.kt` quick reactions (audit-only) | 32dp buttons, expanded targets may slightly overlap in dense row | Info / accepted | Same rationale as #7; long spacing is space4; menu is transient overlay |
| 9 | `FlashComposer.kt` reply-dock dismiss (audit-only) | M3 `IconButton` sized 24dp visually | Info / accepted | M3 IconButton enforces 48dp minimum interactive size internally; contentDescription present |
| 10 | `FlashDeliveryStatusIcon.kt` retry (audit-only) | 14dp clickable icon in timestamp row | Info / accepted | Touch target auto-expanded; row height constrains overlap risk; description already announces "Double-tap to retry" |
| 11 | `FlashComposer.kt` input pill (audit-only) | Placeholder is separate `Text`, announced when empty | OK | No fix needed; field exposes editable-text semantics natively |
| 12 | `FlashVoiceMessageCard.kt` (audit-only) | Waveform played/unplayed coloring is color-only | Info / accepted | Redundant cues exist: duration/remaining label + card `stateDescription` ("Playing"/"Paused"); Canvas seek is supplementary |

### Reduce-motion contract

Single source of truth: `FlashTheme.motion.reduceMotion` from
`ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashMotion.kt`. Detection, token
zeroing, and transition collapsing live there — see §Animation specification above and
[motion-system.md](motion-system.md) (UI-037). Haptics are intentionally NOT muted by
reduce-motion (non-visual channel); policy lives in `FlashHapticPolicy.enabled()`
([motion-system.md §UI-039](motion-system.md)).

## Testing checklist

- [x] Semantics additions compile-clean within existing modifier chains (no Gradle run permitted this session — build verification deferred to next session)
- [ ] TalkBack pass: bubble selection announcement, header avatar, media-viewer counter, new-messages pill (physical device)
- [ ] TalkBack pass: send/retry/attachment flows announce actions before motion completes
- [ ] Large font (200%): composer row, reaction chips, voice pill do not truncate actionability
- [ ] Display size largest: 48dp-equivalent targets still reachable without mis-taps
- [ ] Reduce-motion ON: all screens swap instantly; haptics still play (by design)
- [ ] Dark mode: no contrast regressions from untouched colors
- [ ] RTL: mirrored layouts keep live regions and roles intact (deferred with UI-033)

## Known limitations

- Sub-48dp visual controls rely on Compose's automatic touch-target expansion; where
  expanded targets sit close (quick reactions, delivery retry), overlap is possible on
  dense layouts — flagged for UI-043 stress test rather than fixed now (layout freeze).
- Hard-coded English description strings throughout (no string resources yet);
  localization is tracked at project level, not per-component.
- Search-result highlighting remains color-only inside message text (AnnotatedString
  background span); TalkBack reads the sentence without marking matches — acceptable
  because the result stepper announces position ("3 / 7").
- Recording timer intentionally has no live region (high-frequency updates would spam
  TalkBack); phase transitions could gain custom accessibility events later.

## Future improvements

- Custom accessibility actions on bubbles (react / reply / copy) instead of relying on
  long-press equivalence.
- `paneTitle` / traversal-order review for the focus overlay (UI-007).
- Automated semantics assertions via Compose UI tests once instrumentation infra exists.
- Re-run this audit for every new component (UI-011+ sequence) before its IMPLEMENTED mark.

## What makes this Flash?

Accessibility here is quiet infrastructure, matching the app's calm-premium character:
instant feedback for users who remove animations, spoken state instead of color-only
accents, and haptics kept as a first-class non-visual channel — without ever letting an
a11y template dictate the visual identity.
