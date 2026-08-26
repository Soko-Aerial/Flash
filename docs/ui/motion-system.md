# Flash Motion Design System

**Status:** IMPLEMENTED (UI-037 foundation; UI-039 + UI-041 + UI-040 appended below)  
**Component ID:** UI-037; **UI-039** haptics; **UI-041** micro-interactions; **UI-040** sound feedback  
**Last updated:** 2026-08-25  
**Depends on:** UI-001 (Flash Pulse design system)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Code:** `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashMotion.kt`, `FlashInteraction.kt`, `FlashMotionSheet.kt`, `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashFeedback.kt`, `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashSounds.kt`

---

## Component

`FlashMotion`, `FlashTheme.motion`, `FlashMotionSheet` (QA preview)

---

## Purpose

Centralize every chat animation duration, easing curve, spring, and named transition so components never invent ad-hoc timings. Motion should feel **fast, calm, and tactile** — alive without slowing communication.

Unblocks: UI-003 (chat list), UI-005 (bubbles), UI-006–UI-014, UI-021, UI-032, UI-038–UI-041.

---

## Research sources

| Source | What was studied |
|---|---|
| [Material Motion — Duration & easing](https://m3.material.io/styles/motion/overview) | Standard / decelerate / accelerate curves; 200–300 ms component motion |
| [Compose Animation API](https://developer.android.com/develop/ui/compose/animation) | `AnimatedContent`, `EnterTransition`/`ExitTransition`, springs, interruptibility |
| [Android reduced motion](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#isReduceMotionEnabled()) | API 33+ `isReduceMotionEnabled`; legacy `ANIMATOR_DURATION_SCALE` |
| Telegram Android (reference) | Snappy message insert; minimal header chrome motion; scroll decoupled from typing |
| Signal Android (reference) | Restrained transitions; status changes via crossfade not bounce |
| WhatsApp Android (reference) | Composer height morph; familiar but not copied spring values |
| UI-001 `design-system.md` | “Premium, calm, lightweight” adjectives |

No proprietary motion code or curves were copied.

---

## Existing approaches studied

### A — Per-composable hardcoded `tween(300)` / default `fadeIn()`

**Pros:** Fast to prototype.  
**Cons:** Inconsistent feel; impossible to tune globally; violates master plan; reduced-motion ignored.  
**Verdict:** Rejected — current `FlashChatHeader` used default fades before UI-037.

### B — Material MotionScheme / M3 Expressive physics only

**Pros:** Platform-aligned; expressive springs built-in on newer Compose Material.  
**Cons:** Ties chat feel to Material template; harder to enforce Flash-specific calm messaging rhythm.  
**Verdict:** Rejected as sole system — borrow easing *ideas*, not dependency on Material motion tokens in chat.

### C — Centralized `FlashMotion` token object (chosen)

**Pros:** Single source of truth; previewable; reduced-motion aware; named specs for message/header/composer/screen.  
**Cons:** Must be referenced consistently in future components.  
**Verdict:** Selected.

---

## What worked

- **Tiered durations** — `fast` (120 ms), `normal` (200 ms), `slow` (320 ms), `emphasis` (400 ms)
- **Fade-first** status and metadata changes (header presence, transport icon context)
- **Subtle vertical slide + scale** on message enter (≤ 8 dp travel, 0.96→1 scale) — Telegram-like without bounce
- **Snappy springs** only for send/composer micro feedback (UI-041); list insert uses mostly tween
- **Instant swap** when reduce-motion is enabled (0 ms, no slide/scale)
- **`FlashTheme.motion`** CompositionLocal — same pattern as colors/typography

---

## What did not work

- Default Compose `fadeIn()` with implicit 300 ms — too slow for header status cycling
- Heavy overshoot springs on every list item — janky on mid-range devices; feels “game UI”
- Long shared-element transitions for chat — blocks reading flow
- Animating during active typing/scrolling (deferred rule for UI-021/UI-011)

---

## Chosen approach

**Flash Calm Motion** — fixed duration tiers + Material-inspired easing + selective springs.

| Category | Token examples | Typical use |
|---|---|---|
| Micro | `fast`, `springSnappy` | Icon state, send tap, reaction pick |
| Component | `statusCrossfade`, `messageEnter`, `composerExpand` | Header, bubbles, composer |
| Screen | `screenTransition`, `mediaOpen` | Navigation, media viewer |
| Gesture | (UI-041) | Swipe reply, drag dismiss — spec placeholders |
| Loading | `emphasis` + fade | Skeleton → content (UI-026) |
| Feedback | `springSnappy` | Haptics paired in UI-039 |

---

## Why it was chosen

Messaging UX research favors **speed over spectacle**. Flash P2P users care about transfer reliability and reading latency; motion must reinforce state changes without adding perceptual delay. A centralized Kotlin API matches UI-001 token architecture and satisfies AGENTS.md §34 “no random per-file durations.”

---

## Visual specification

Motion has no standalone color/shape tokens. Animated elements use existing `FlashTheme.colors` / `FlashTheme.typography`. Opacity endpoints: enter 0→1, exit 1→0. Scale endpoints: 0.96→1.0 enter; 1.0→0.98 press (UI-041).

---

## Interaction specification

| Trigger | Motion token | Interruptible |
|---|---|---|
| Header status text change | `statusCrossfade` | Yes — new state replaces mid-transition |
| New message appended | `messageEnter` / `messageExit` | Yes — list scroll can cancel enter |
| Open conversation | `screenTransition` | Yes — back gesture |
| Composer multi-line grow | `composerExpand` | Yes — typing continues |
| Reply quote expand | `replyExpand` | Yes |
| Open image full-screen | `mediaOpen` | Yes — back |

All transitions must complete or snap within **≤ 400 ms** except media viewer (320 ms open).

---

## Animation specification

### Duration tokens

| Token | ms | Reduced motion |
|---|---|---|
| `fast` | 120 | 0 |
| `normal` | 200 | 0 |
| `slow` | 320 | 0 |
| `emphasis` | 400 | 0 |

### Easing

| Token | Curve | Use |
|---|---|---|
| `Decelerate` | (0, 0, 0.2, 1) | Enter |
| `Standard` | (0.4, 0, 0.2, 1) | Move |
| `Accelerate` | (0.4, 0, 1, 1) | Exit |

### Springs

| Token | Damping | Stiffness | Use |
|---|---|---|---|
| `springSnappy` | 0.85 | 600 | Send, micro scale |
| `springDefault` | 0.90 | 400 | Message enter scale |
| `springGentle` | 1.00 | 280 | Composer expand |

### Named transitions (implemented in `FlashMotion.kt`)

| Name | Enter / transform | Exit | Duration tier |
|---|---|---|---|
| `statusCrossfade` | fade in | fade out | fast |
| `messageEnter` | fade + slide up 25% + scale 0.96 | — | normal |
| `messageExit` | — | fade | fast |
| `screenPushEnter` / `screenPushExit` | fade + slide in from trailing 30% | fade + slide out leading 30% | slow |
| `screenPopEnter` / `screenPopExit` | mirror: fade + slide in from **leading** 30% | fade + slide out **trailing** 30% | slow |
| `screenEnter` / `screenExit` | aliases of the push pair, for callers that do not care about direction | — | slow |
| `tabEnter(towardEnd)` / `tabExit(towardEnd)` | short lateral hop, ±10% width by tab-index sign | — | normal |
| `sheetEnter` / `sheetExit` | fade + rise from the bottom edge (default spring) | fade + slide down | fast / normal |
| `shellBarEnter` / `shellBarExit` | hanging nav capsule rises from below on the snappy spring | fade + drop | normal |
| `badgePopEnter` / `badgePopExit` | fade + scale from 0.5 (snappy spring) | fade + scale back to 0.5 | fast |
| `composerExpand` | fade + slide up 12 dp | fade | normal + gentle spring |
| `replyExpand` | fade + expand vertical | fade | normal |
| `mediaOpen` | fade + scale 0.92 | fade + scale 0.96 | slow |

### Progress helpers (0 → 1 values read inside `graphicsLayer`)

| Name | Use |
|---|---|
| `rememberMessageEnterProgress(animate)` | the freshly appended tail message's own alpha/rise/scale channel (UI-006); fixed at item birth so later `animate` changes cannot reset it |
| `rememberStaggerProgress(index, key)` | per-item entrance delay for a page that just came on screen — `StaggerStepMillis` (24ms) per step, capped at `MaxStaggerSteps` (6) so long lists do not cascade. `key` restarts the stagger: pass the **page/state identity**, not the item, so re-entering a tab replays it while a scroll does not. Indices must be literal in lazy lists — a captured counter hands out arbitrary delays because item lambdas compose out of order |

**Reduce-motion contract.** Every `EnterTransition`/`ExitTransition`/`ContentTransform` token above and both
progress helpers collapse *themselves* (to `None` / immediate 1f). The raw spec accessors
(`tweenFastSpec()`, `tweenNormalSpec()`, `springSnappySpec()`, …) do **not** — they only report
`FastMillis`/`NormalMillis` through `reduceMotion`-aware duration getters, so `spring`-based specs and any
`animate*AsState` built on them keep animating. Call sites that use them must gate explicitly:
`animationSpec = if (motion.reduceMotion) snap() else motion.tweenNormalSpec()`. `messagePlacementSpec()`
and `flashPressScale` already do this internally.

---

## Gesture specification

Deferred to UI-007, UI-008, UI-021, UI-041. FlashMotion will expose shared `springGentle` and `fast` for gesture-settle thresholds documented in those components.

---

## Accessibility requirements

- Detect **reduce motion** via `AccessibilityManager.isReduceMotionEnabled` (API 33+) or `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.
- When enabled: **no slide/scale**; crossfades become instant state swap (`EnterTransition.None`).
- Never convey presence/encryption/delivery **only** through animation (UI-038).
- Large text / display scaling: motion distances are dp-based and do not affect final layout size.

---

## Responsive behavior

Same tokens on phone/tablet/foldable. Tablet screen transitions may use larger slide fraction in UI-034 — token API unchanged.

---

## Dark-mode behavior

Identical timings; no separate dark motion palette.

---

## Performance considerations

- Prefer `AnimatedContent` / `AnimatedVisibility` with fixed specs over manual `Animatable` in lists.
- Message list (UI-021) should use item-level enter only for tail messages, not full list re-layout.
- Avoid simultaneous `slow` transitions on keyboard + composer + list.
- Profile with UI-042 after UI-005/UI-021 land.

---

## Implementation notes

| File | Role |
|---|---|
| `FlashMotion.kt` | Tokens, curves, springs, transition builders, reduce-motion probe |
| `FlashInteraction.kt` | `Modifier.flashPressScale(interactionSource)` — shared 0.98 press feel, read in `graphicsLayer`, gates its own spec on `reduceMotion` |
| `FlashTheme.kt` | `LocalFlashMotion`, `FlashTheme.motion`, `rememberFlashMotion()` |
| `FlashMotionSheet.kt` | QA demo cycling status crossfade + message enter sample |
| `FlashChatHeader.kt` | First consumer — `statusCrossfade()`; also the conversation-open avatar/title handoff via `rememberStaggerProgress` |
| `FlashNavigation.kt` | UI-033 consumer — direction-aware `screenPush*` / `screenPop*` / `tab*` pairs |
| `FlashBottomNav.kt` | UI-046 consumer — `shellBarEnter/Exit`, `badgePopEnter/Exit`, snappy spring for the indicator |
| `FlashFeedback.kt` | UI-039: `FlashHaptic`, `FlashHapticPolicy`, `rememberFlashHaptics()` |

**Dependencies:** Compose Animation (BOM). No new Gradle libraries.

---

## Testing checklist

- [x] Compose preview — `FlashMotionSheet` light/dark
- [x] Physical device — header status uses Flash crossfade (conversation screen)
- [x] Dark mode preview
- [ ] Large font — N/A for motion sheet (no layout change)
- [ ] RTL — screen/tab transition direction (slide offsets are width-fraction based; the shell's
      indicator and header entrance negate their translation under `LayoutDirection.Rtl`)
- [x] Reduced motion — unit logic via `FlashMotion(reduceMotion = true)` instant transitions
- [ ] Performance spot-check — deferred until UI-005 list animations

---

## Known limitations

- ~~UI-040 sound not implemented~~ — implemented 2026-08-22, full section below.
- ~~`screenTransition` not wired to navigation yet (UI-033)~~ — wired 2026-08-25 as the
  direction-aware `screenPush*` / `screenPop*` / `tab*` pairs.
- The raw spec accessors (`tween*Spec`, `spring*Spec`) do not self-collapse under reduce-motion;
  callers must gate them. Easy to forget — see the contract note under Named transitions.
- No automated test for system reduce-motion setting on device (manual QA).

---

## Future improvements

- UI-038: document TalkBack announcements alongside motion degradation.
- UI-041: wire `springSnappy` to send button and reaction picker.
- UI-021: list item placement animation using `messageEnter` with LazyColumn key stability.
- Optional `FlashMotion.debugSlow()` for QA (2× duration) — not added until needed.

---

## What makes this Flash?

Flash motion is **calm connectivity**, not flashy Material demo physics. Short fades acknowledge P2P status changes; message insert is quick and slightly tactile; nothing bounces for attention. The system is owned, centralized, and tuned for local-first messaging — not cloned Telegram springs or Stream transitions.

---

# UI-039 — Haptics (appended 2026-08-22)

**Status:** IMPLEMENTED  
**Code:** `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashFeedback.kt`  
**Tests:** `ui/theme/src/test/java/com/transfer/flash/ui/theme/FlashFeedbackLogicTest.kt`  
**Depends on:** UI-037 (`FlashMotion.reduceMotion`), UI-001 tokens

## Component

`FlashHaptic` (semantic vocabulary), `FlashHapticPolicy` (pure decision logic),
`rememberFlashHaptics()` (single composition choke point).

## Purpose

One central haptic system so components express *intent* (`Confirm`) rather than raw
API calls (`performHapticFeedback(LongPress)`), keeping every future haptic upgrade —
including a VibrationEffect/amplitude-primitive path — a one-file change.

## Research sources

| Source | What was studied |
|---|---|
| [Compose `HapticFeedbackType`](https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType) | Stable common API exposes only `LongPress` and `TextHandleMove`; no amplitude/context primitives in the common surface |
| [`HapticFeedback.performHapticFeedback`](https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedback) | Compose delegates to the View pipeline — no `VIBRATE` permission needed |
| [Add haptic feedback to events (Views)](https://developer.android.com/develop/ui/views/haptics/haptic-feedback) | View-based `performHapticFeedback` is recommended over raw `VibrationEffect`: action-oriented, permission-free, honors `HAPTIC_FEEDBACK_ENABLED`; `View` constants have built-in fallbacks |
| [Android haptics API reference](https://developer.android.com/develop/ui/views/haptics/haptics-apis) | SDK 27+ amplitude control via `hasAmplitudeControl`; predefined effects need Android 10+; composition primitives Android 11+ with per-primitive support checks; `VibrationEffect` paths require `VIBRATE` |
| [Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) | Prefer action-oriented constants; frequent interactions get subtle ticks; be consistent app-wide; avoid legacy one-shot waveforms |

## Existing approaches studied

### A — Ad-hoc `LocalHapticFeedback.current.performHapticFeedback(...)` per component

**Pros:** Zero abstraction.  
**Cons:** No policy gate, no semantic meaning, N call sites to change on any upgrade. Was
the pre-existing state: 15 scattered call sites across 10 files. **Rejected.**

### B — Direct `Vibrator` + `VibrationEffect.Composition` service

**Pros:** Rich SDK 27+/31+ amplitude primitives, distinct Warn/Reject textures.  
**Cons:** Needs `VIBRATE` permission, bypasses the user's touch-feedback setting unless
manually checked, device-support checks required per primitive, not testable without
instrumentation. **Deferred** as the documented future upgrade behind the same API.

### C — Semantic enum + policy object + single composable choke point (chosen)

**Pros:** Testable JVM logic, honest mapping table, one-file future migration.  
**Cons:** Current mapping collapses Warn/Reject onto LongPress (Compose API limit). **Selected.**

## What worked

- Mechanical migration of all 15 existing `performHapticFeedback` call sites preserved
  perceived timing/type exactly (`TextHandleMove → Tick`, `LongPress → Confirm/Warn/Reject`).
- Policy independence from reduce-motion matches platform behavior ("Remove animations"
  does not mute vibration).

## What did not work

- Nothing failed; the only constraint hit was the stable Compose type set
  (`LongPress`, `TextHandleMove`), documented rather than worked around unsafely.

## Chosen approach

```kotlin
enum class FlashHaptic { Tick, Confirm, Warn, Reject }

object FlashHapticPolicy {
    fun enabled(reduceMotion: Boolean, systemHapticsEnabled: Boolean = true): Boolean
}

@Composable
fun rememberFlashHaptics(): (FlashHaptic) -> Unit
```

Mapping (documented honestly — see `FlashFeedback.kt` KDoc):

| `FlashHaptic` | Underlying `HapticFeedbackType` | Used for |
|---|---|---|
| `Tick` | `TextHandleMove` | Light taps, toggles, tiles, play/pause, steppers |
| `Confirm` | `LongPress` | Long-press menus, swipe threshold reached, reaction select, retry tap, record start |
| `Warn` | `LongPress` | Reserved — attention-without-failure states |
| `Reject` | `LongPress` | Destructive actions (recording trash/discard); future send-failure |

## Why it was chosen

Android's own guidance favors action-oriented, setting-respecting feedback; Compose's
common API is intentionally narrow today. Centralizing intent now means Warn/Reject can
gain distinct amplitude textures later without touching any chat component, and the
policy gate is unit-tested on the JVM.

## Visual specification

N/A (non-visual channel). Pairs visually with press-scale micro-interactions (UI-041)
fired by the same gestures.

## Interaction specification

Call-site catalog — before → after (all in `ui/chat/.../chat/`):

| File | Line (pre-edit) | Before | After |
|---|---|---|---|
| FlashAttachmentButton.kt | 88 | `TextHandleMove` | `Tick` (attach toggle) |
| FlashAttachmentSheet.kt | 218 | `TextHandleMove` | `Tick` (tile tap) |
| FlashDeliveryStatusIcon.kt | 135 | `LongPress` | `Confirm` (failed-send retry) |
| FlashFileMessageCard.kt | 147 | `TextHandleMove` | `Tick` (file card tap) |
| FlashMessageBubble.kt | 191 | `LongPress` | `Confirm` (bubble long-press menu) |
| FlashMessageBubble.kt | 248 | `LongPress` | `Confirm` (voice card long-press menu) |
| FlashMessageContextMenu.kt | 249 | `LongPress` | `Confirm` (quick reaction select) |
| FlashMessageContextMenu.kt | 291 | `TextHandleMove` | `Tick` (open emoji picker) |
| FlashReactionChip.kt | 116 | `TextHandleMove` | `Tick` (toggle reaction) |
| FlashReactionChip.kt | 120 | `LongPress` | `Confirm` (reactor list) |
| FlashSwipeToReply.kt | 128 | `LongPress` | `Confirm` (reply threshold armed) |
| FlashVoiceMessageCard.kt | 247 | `LongPress` | `Confirm` (long-press actions) |
| FlashVoiceMessageCard.kt | 264 | `TextHandleMove` | `Tick` (play/pause toggle) |
| FlashVoiceRecording.kt | 194 | `TextHandleMove` | `Confirm` (record start — deliberate semantic upgrade, same primitive) |
| FlashVoiceRecording.kt | 292 | `TextHandleMove` | `Reject` (trash/discard recording) |

Rule: new interactive elements must use `rememberFlashHaptics()`; direct
`LocalHapticFeedback` usage in :ui:chat is now zero and should stay that way.

## Animation specification

None — haptics are instantaneous. They are gated by `FlashHapticPolicy.enabled()`,
which deliberately ignores reduce-motion (vibration is non-vestibular, non-visual
feedback; platform reduce-motion settings do not affect it either). The parameter stays
in the signature so a product-level reversal flips one function.

## Gesture specification

Swipe-to-reply fires exactly one `Confirm` per crossing (re-arms when dragging back
under threshold) — existing dedupe logic retained unchanged.

## Accessibility requirements

See [accessibility.md §UI-038](accessibility.md): haptics are an assistive channel;
system touch-feedback setting is honored downstream by `View.performHapticFeedback`.

## Responsive behavior / Dark-mode behavior

N/A.

## Performance considerations

Haptic dispatch is cheap; no caching needed beyond the remembered lambda. Never fire
haptics from per-frame animation callbacks — only from discrete gesture/state events
(current codebase complies; verified during migration).

## Implementation notes

- `rememberFlashHaptics()` returns a *stable* lambda (remembered on
  `hapticFeedback + reduceMotion`), safe to capture in gesture callbacks.
- Unit tests cover the full policy truth table + vocabulary freeze.
- Device QA pending (no Gradle/device run this session): confirm LongPress-equivalent
  feel on Pixel/Samsung actuators and record in `logs/experiments.md` per AGENTS.md §23.

## Testing checklist

- [x] Policy truth-table unit tests (`FlashFeedbackLogicTest.kt`)
- [x] All 15 legacy call sites migrated; grep shows zero remaining direct usage
- [ ] Physical device: each mapped haptic feels appropriate (esp. Reject on discard)
- [ ] TalkBack session: haptics do not interfere with announcements
- [ ] System "touch feedback" off → all Flash haptics silent (downstream enforcement)

## Known limitations

- Warn/Reject share the LongPress primitive until a VibrationEffect backend lands.
- No amplitude control, no waveform patterns, no per-device tuning (by design, MVP).
- Reduce-motion currently has no effect on haptics — intentional, but undocumented to
  users; revisit if research requests it.

## Future improvements

- Backend switch inside `rememberFlashHaptics()` to `Vibrator` + predefined effects /
  composition primitives (SDK 27/31+ guards), keeping the exact public API.
- Distinct textures: double-tick for delivery confirmation, rising tick ramp for scrub.
- Settings toggle "haptic strength" wired through `FlashHapticPolicy`.

---

# UI-041 — Micro-interactions (appended 2026-08-22)

**Status:** PARTIALLY IMPLEMENTED (inventory + gap fill; additions minimal & token-driven)

## Inventory of existing micro-interactions

All press scales gate on `!motion.reduceMotion` and use `motion.springSnappySpec()`
(damping 0.85 / stiffness 600) unless noted:

| Micro-interaction | Component | Scale / motion | Spec source |
|---|---|---|---|
| Bubble press | `FlashMessageBubble.kt` | 0.97 press scale | springSnappy |
| Attachment button press + 45° morph | `FlashAttachmentButton.kt` | 0.88 press; rotation morph | springSnappy |
| Attachment tile press + staggered entrance | `FlashAttachmentSheet.kt` | 0.90 press; 0.6→1 enter, 20ms stagger | springSnappy / springDefault |
| File card press | `FlashFileMessageCard.kt` | 0.97 | springSnappy |
| Voice badge press + play/pause glyph morph | `FlashVoiceMessageCard.kt` | 0.90 press; 0.6→1 scale-swap | springSnappy |
| Voice card press | `FlashVoiceMessageCard.kt` | 0.97 | springSnappy |
| Mic button press/recording shrink | `FlashMicButton` (FlashVoiceRecording.kt) | 0.90 while pressed or recording | springSnappy |
| Send button press | `FlashSendButton` (FlashComposer.kt) | 0.90 (local spring ζ0.6/k500 — pre-token legacy) | ad-hoc spring |
| Quick reaction pop-in + press | `FlashMessageContextMenu.kt` | 0.5→1 staggered pop; 0.85 press | springSnappy |
| Reaction chip border-width morph | `FlashReactionChip.kt` | hairline→1.5dp active border | springSnappy |
| Count odometer roll | `FlashReactionChip.kt` | vertical slide ± fade | tween fast + slide |
| Swipe-to-reply icon reveal | `FlashSwipeToReply.kt` | 0.4→1 scale, −35°→0° rotate, α=progress² | drag-driven |
| Typing dots bounce | `FlashTypingIndicator.kt` | 0.85→1.15 dot scale | infinite loop |
| Recording dot pulse | `FlashVoiceRecordingBar` | α 1↔0.45 pulse | infinite loop |
| Delivery icon crossfade | `FlashDeliveryStatusIcon.kt` | status crossfade | `statusCrossfade()` |

## Spring token usage table

| Token | Consumers |
|---|---|
| `springSnappySpec` (0.85/600) | All press scales, attachment rotation, badge glyph swap, reaction chip border, quick-reaction pops, search chrome press (new, below) |
| `springDefaultSpec` (0.90/400) | Message-enter scale, media zoom, tile entrance |
| `springGentleSpec` (1.00/280) | Composer expand |
| Ad-hoc springs (legacy) | `FlashSendButton` press (ζ0.6/k500), swipe-to-reply snap-back (ζ0.65/k500) — flagged below |

## Gaps found

1. Search chrome buttons (`SearchStepButton`, search-bar close) had **no press
   feedback at all** while every other chrome button scales. → Fixed (below).
2. `FlashSendButton` uses an ad-hoc spring instead of tokens (pre-dates UI-037).
   → Documented; token migration belongs to a composer polish pass (UI-011 owner).
3. No send-success acknowledgment (e.g., subtle tick/scale settle after send).
   → Requires send-state plumbing through UI-013 callbacks; deferred, not faked here.
4. Swipe-to-reply snap-back uses local spring values instead of a shared token.

## New micro-interactions added (2026-08-22)

**1. Search-chrome press scale — `FlashChatSearchBar.kt`**
- `SearchStepButton` and the close-search button now compress to **0.90** on press,
  animated with `motion.springSnappySpec()` and gated on `!motion.reduceMotion`
  (labels: `searchStepPressScale`, `searchClosePressScale`).
- Implemented with `MutableInteractionSource` + `collectIsPressedAsState` +
  `graphicsLayer` scale (GPU-composited, no recomposition per frame), matching the
  established Flash pattern. Ripple indication replaced by the scale, consistent with
  all other custom chrome buttons.

That is the complete addition this pass — deliberately minimal per scope; items 2–4 are
documented gaps, not silent rewrites.

## Testing checklist

- [x] Additions compile within existing modifier chains; no layout-affecting changes
- [ ] Physical device: search steppers feel consistent with attachment button press
- [ ] Reduced-motion ON: search chrome presses are instant (no scale)
- [ ] UI-042 profile once list animations land (press scales are graphicsLayer-only)

## Known limitations / Future improvements

- Tokenize `FlashSendButton` + swipe snap-back springs (owner: UI-011/UI-010 passes).
- Send-success micro-interaction after UI-013 exposes a send-result callback.
- Optional `FlashPressable` helper modifier if a fourth press-scale copy-paste appears.

---

# UI-040 — Sound Feedback (appended 2026-08-22)

**Status:** IMPLEMENTED (code + JVM tests; device QA pending — no Gradle/device run this session)  
**Decision record:** Owner approved **subtle synthesized tones, OPT-IN, DEFAULT OFF** on 2026-08-22 (today). Settings persistence lands later via DataStore in core phase C1.5; until then the enable flag lives in an in-memory `mutableStateOf` bridge (`FlashSoundSettings`).  
**Code:** `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashSounds.kt`  
**Tests:** `ui/theme/src/test/java/com/transfer/flash/ui/theme/FlashSoundsTest.kt` (pure JVM, JUnit 4 — no Robolectric)  
**Depends on:** UI-039 (`FlashFeedback.kt` structure mirrored), UI-001 tokens (no visual tokens consumed)

## Component

`FlashSound` (semantic vocabulary + per-event synthesis spec), `FlashSoundPolicy`
(pure decision logic), `FlashSoundSettings` (temporary opt-in state bridge),
`FlashSoundSynth` (pure PCM waveform math — zero Android imports at runtime),
`rememberFlashSounds()` (single composition choke point),
`FlashSoundPlayer` (internal AudioTrack backend).

## Purpose

Mirror the UI-039 haptic choke point for audio: components express *intent*
(`MessageSent`) and never touch `android.media` directly, so tuning pitches,
changing the playback backend, or muting everything is a one-file change.
Sounds are a **supplementary** channel layered on top of visuals + haptics —
never the sole carrier of state information.

## Research sources

| Source | What was studied |
|---|---|
| [`AudioTrack` API reference](https://developer.android.com/reference/android/media/AudioTrack) | "Static mode should be chosen when dealing with short sounds that fit in memory… preferred for UI and game sounds that are played often, with smallest overhead possible"; `MODE_STATIC`, `PERFORMANCE_MODE_LOW_LATENCY`, `reloadStaticData()` reuse |
| [`SoundPool` API reference](https://developer.android.com/reference/android/media/SoundPool) | Pre-decodes resources/files into 16-bit PCM; `load()` accepts only APK resources/assets/file paths — **no raw in-memory buffer API**; 1 MB per-sound cap |
| [AOSP audio latency design](https://source.android.com/docs/core/audio/latency/design) | Both SoundPool and ToneGenerator request `AUDIO_OUTPUT_FLAG_FAST`; Java AudioTrack can reach the fast mixer path |
| [Ackee — High Performance Audio APIs](https://www.ackee.agency/blog/android-high-performance-audio-apis) | Comparison: SoundPool = short existing clips; AudioTrack MODE_STATIC = same latency class but accepts *generated* raw data |
| [SO: AudioTrack/SoundPool/MediaPlayer choice](https://stackoverflow.com/questions/13527134/audiotrack-soundpool-or-mediaplayer-which-should-i-use) | SoundPool needs fully-loaded clips + load callbacks; MediaPlayer too heavy for tones |
| [SO: Playing an arbitrary tone / generated PCM](https://stackoverflow.com/questions/16084316/generate-a-sound-pcm-android-java) and [siliconfish tone-generator write-up](http://blog.workingsi.com/2012/03/android-tone-generator-app.html) | Procedural sine → 16-bit PCM pattern; phase discontinuities/waveform not ending at zero cause audible clicks → envelope required |
| [`AudioAttributes` reference](https://developer.android.com/reference/android/media/AudioAttributes) | `USAGE_ASSISTANCE_SONIFICATION` = "sonification, such as with user interface sounds" |
| [AOSP audio attributes](https://source.android.com/docs/core/audio/attributes) | `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION` maps to legacy `STREAM_SYSTEM` volume (system volume, not media/notification); HAL context SYSTEM_SOUND |
| [AOSP `AudioAttributes.java`](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/AudioAttributes.java) | Sonification usages are classified `SUPPRESSIBLE_SYSTEM` → **muted automatically by Zen/DND when priority mode disallows system sounds** |
| [`NotificationManager` interruption filter](https://developer.android.com/reference/kotlin/android/app/NotificationManager) | `getCurrentInterruptionFilter()`: ALL=1 normal; PRIORITY/ALARMS/NONE suppress system sounds; UNKNOWN=0 means filter unavailable |
| [`AudioManager` ringer mode](https://developer.android.com/reference/android/media/AudioManager) | `getRingerMode()`: `RINGER_MODE_SILENT` / `RINGER_MODE_VIBRATE` mean user wants quiet |

No proprietary sounds or third-party audio libraries were used.

## Existing approaches studied

### A — Asset-based `SoundPool` (ship .ogg/.wav chimes)

**Pros:** Lowest runtime CPU (pre-decoded); classic UI-sound path; stream mixing built in.  
**Cons:** Requires binary assets (owner explicitly wants none unless clearly better); cannot feed
procedurally generated buffers (`load()` has no byte[] overload); load-callback bookkeeping;
assets need professional sound design to not sound cheap. **Rejected** for this app.

### B — `ToneGenerator`

**Pros:** One-liner DTMF/beep tones; framework-managed.  
**Cons:** Fixed preset tone table only (no custom two-note chimes or double-buzz);
legacy `STREAM_*` volume routing; no envelope control (harsh clicks). **Rejected.**

### C — `Oboe`/AAudio native low-latency synth

**Pros:** Pro-audio grade latency (~10 ms round trip).  
**Cons:** NDK toolchain + CMake dependency in a pure-Kotlin theme module; massive overkill
for ≤400 ms opt-in UI chimes where ±20 ms start latency is imperceptible. **Rejected.**

### D — Procedural PCM into `AudioTrack` MODE_STATIC (chosen)

**Pros:** Zero shipped assets; exact control of pitch/envelope (click-free attack+decay);
official docs recommend static mode precisely for often-played short UI sounds;
pure-JVM-testable synthesis separated from Android classes; one AudioTrack per event,
created lazily on first play and reused via `reloadStaticData()`.  
**Cons:** Synthesis cost on first play per event (~10–18 k samples — negligible, off critical
path); manual lifecycle/error handling around `IllegalStateException`. **Selected.**

## What worked

- Sine + exponential-decay envelope with short linear attack (~5 ms) and ~3 ms fade-out:
  click-free per the siliconfish/SO findings about waveforms not ending at zero.
- `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION`: routes to **system**
  volume (not media, not notification) and is auto-suppressed under restrictive Zen modes.

## What did not work

- Considering `SoundPool` directly: its public `load()` API simply has no in-memory-buffer
  overload — would have required writing temp files to disk, which is worse than AudioTrack.
- Naive sine without envelope audibly clicks at segment boundaries (documented failure from
  research; avoided by construction rather than discovered by device testing).

## Chosen approach

```kotlin
enum class FlashSound(val spec…) { MessageSent, MessageDelivered, MessageReceived,
    RecordingStart, RecordingStop, TransferComplete, PairingSuccess, Error }

object FlashSoundPolicy {
    fun shouldPlay(soundsEnabled, ringerMode, interruptionFilter): Boolean
}

object FlashSoundSettings { var soundsEnabled: Boolean }   // mutableStateOf, default FALSE

object FlashSoundSynth {                                   // pure, JVM-testable
    fun render(sound: FlashSound, sampleRateHz: Int = 44_100): ShortArray
    fun totalDurationMs(sound: FlashSound): Long
    fun dominantFrequencyHz(sound: FlashSound): Double
}

@Composable fun rememberFlashSounds(): (FlashSound) -> Unit // choke point
internal object FlashSoundPlayer                            // AudioTrack MODE_STATIC cache
```

Tone map (44 100 Hz mono, 16-bit PCM; amplitude peak-relative):

| Event | Segments (freq Hz × duration ms) | Peak amp | Character |
|---|---|---|---|
| `MessageSent` | 880×60 → 1320×70 | 0.50 | higher rising pair ("wing up") |
| `MessageDelivered` | 1174.66×90 | 0.45 | single bright tick |
| `MessageReceived` | 659.25×120 | 0.35 | softer, lower, longer decay |
| `RecordingStart` | 740×80 | 0.45 | neutral mid blip |
| `RecordingStop` | 493.88×100 | 0.40 | descending settle |
| `TransferComplete` | 1046.5×60 → 1318.5×60 → 1568×110 | 0.50 | rising triad flourish |
| `PairingSuccess` | 587.33×80 → 880×140 | 0.50 | two-note major confirmation |
| `Error` | 196×130 → rest×60 → 196×130 | 0.55 | dull low double-buzz |

Envelope per segment: linear attack ≈5 ms → exponential decay (τ = duration/3, ends ≈ −21 dB)
→ final ≈3 ms fade-out. All values live in one table in `FlashSounds.kt`.

## Why it was chosen

Owner decision (opt-in/default-off, no binary assets) plus platform guidance: Android's own
docs name AudioTrack static mode as the recommended mechanism for exactly this use case, and
the usage attribute gives correct volume-group + DND semantics for free. Separating
`FlashSoundSynth` (pure math) from `FlashSoundPlayer` (Android media) keeps every waveform
property unit-tested on the JVM without Robolectric, matching the UI-039 test philosophy.

## System-state respect rules

1. `soundsEnabled == false` (default) → **zero work**: policy returns false before any player
   init, track creation, or synthesis. No AudioTrack object exists until first enabled play.
2. Ringer mode `SILENT` or `VIBRATE` → never play (user wants quiet).
3. Interruption filter `PRIORITY`, `ALARMS`, or `NONE` (DND active) → never play. Filter
   `UNKNOWN` is treated as allowed because reading it failed — belt-and-braces only, since
   `USAGE_ASSISTANCE_SONIFICATION` is already OS-classified `SUPPRESSIBLE_SYSTEM`.
4. Volume: tones follow the **system/ringer volume group** via the usage attribute — they
   neither blast over music (media volume) nor behave like notifications.
5. Policy checks happen at play time (not cached), so flipping the silent switch mid-session
   takes effect on the very next event.

## Accessibility requirements

- Sounds are **always supplementary**: no state may be conveyed *only* by sound; delivery/
   pairing/recording states have visual (UI-038) and haptic (UI-039) counterparts.
- TalkBack announcements are unaffected — this system plays nothing through the
   accessibility channel and does not intercept announcements.
- Default OFF respects users who find UI sounds noisy; the toggle (when surfaced) must be a
   real setting, not buried.
- Error events pair with visible error states and `Reject`/`Warn` haptics, not sound alone.

## Interaction specification (call sites to wire later — NOT yet wired)

The repo currently has no message-send/deliver/receive pipeline in the chat UI layer, so no
call sites were modified (file ownership limited to FlashSounds.kt/tests/doc). When the real
data flow lands, fire these once per discrete event:

| Event | Where it should fire (future owner) |
|---|---|
| `MessageSent` | send-result success callback after UI-013 composer send completes (also closes UI-041 gap #3) |
| `MessageDelivered` | delivery-status transition observer (same place `FlashDeliveryStatusIcon` flips state) |
| `MessageReceived` | incoming-message insert into conversation list (UI-021 data layer) |
| `RecordingStart` / `RecordingStop` | `FlashVoiceRecording` start/cancel-or-commit handlers (pair with existing `Confirm`/`Reject` haptics) |
| `TransferComplete` | transfer engine completion event surfaced to chat UI |
| `PairingSuccess` | pairing flow success handler |
| `Error` | send-failure / transfer-failure surfaces (pair with `Reject` haptic) |

Rule: new call sites must use `rememberFlashSounds()` (or the player via settings/policy) —
direct `android.media` usage in feature modules is prohibited, mirroring the UI-039 rule.

## Animation specification

None — tones are instantaneous one-shots (60–320 ms). They are event-driven like haptics;
never trigger from per-frame animation callbacks.

## Gesture specification

None direct. Recording gestures fire `RecordingStart`/`RecordingStop` alongside the existing
UI-039 haptics at the same discrete gesture boundaries (press-to-record, discard/commit).

## Responsive behavior / Dark-mode behavior

N/A (non-visual channel). Same tones on all form factors.

## Performance considerations

- First-play synthesis per event: ≤ ~14 k samples (≈57 KB ShortArray) — sub-millisecond on
  any modern device; cached thereafter (both PCM and AudioTrack).
- One AudioTrack instance per event type held after first play (8 max ≈ <500 KB total);
  trivially releasable if a teardown hook is needed later.
- Static-mode tracks request the fast-mixer path where available (per AOSP latency docs).
- Play calls are cheap (`stop → reloadStaticData → play`); guarded against
  `IllegalStateException` from racing stop/release during process teardown.

## Implementation notes

- `FlashSoundSettings.soundsEnabled` is a `mutableStateOf`-backed singleton property — a
  **deliberate temporary bridge** until DataStore wiring in core phase C1.5 replaces it with
  persisted state. Compose can observe it today; swap its backing implementation without
  touching call sites.
- `FlashSoundPolicy.shouldPlay` takes primitive ints for ringer/interruption state so it stays
  JVM-testable; the constants referenced in defaults are compile-time `static final int`s,
  safe in unit tests.
- Unit tests cover: rendered length == Σduration·sampleRate/1000; bounded amplitude;
  monotonic post-attack decay of positive peaks; silence gap in `Error`; zero-crossing
  frequency estimate within tolerance of spec; distinct dominant frequency across all 8
  events; full policy truth table; vocabulary freeze.
- Device QA pending (no Gradle/device run this session — Gradle forbidden here): confirm
  perceived loudness/balance on Pixel + Samsung speakers, confirm silent/DND behavior on
  physical devices, record in `logs/experiments.md` per AGENTS.md §23.

## Testing checklist

- [x] Pure-JVM synthesis tests (`FlashSoundsTest.kt`): length, envelope decay, frequencies, gap silence, bounds
- [x] Policy truth-table unit tests (enabled flag, ringer modes, all interruption filters)
- [x] Vocabulary freeze guard (8 documented events, distinct dominant frequencies)
- [ ] Physical device: each tone feels subtle & appropriate; volumes balanced
- [ ] Physical device: ringer SILENT/VIBRATE and each DND level mute all tones
- [ ] Media-volume-only scenarios: tones inaudible at zero system volume
- [ ] TalkBack session: no interference with announcements

## Known limitations

- **Persistence bridge pending C1.5:** `soundsEnabled` is in-memory only; resets to OFF on
  process death until DataStore-backed settings land. Documented, intentional.
- **Call sites not wired:** no production event fires sounds yet (see Interaction
  specification table); lead must wire send/receive/delivery/pairing/transfer hooks as those
  flows materialize.
- Device QA pending: loudness balance, silent/DND enforcement, speaker-vs-earpiece variance
  untested (Gradle forbidden this session).
- Tones are fixed specs in-code; no per-device loudness normalization (device-dependent
  speaker gain may vary — revisit after QA).
- `FlashSoundPlayer` holds up to 8 small AudioTrack instances for process lifetime once
  sounds have been used while enabled; acceptable footprint, add release hook only if needed.

## Future improvements

- DataStore persistence + settings-screen toggle (core C1.5).
- Wire call sites per the interaction table as data flows land.
- Optional per-event enable matrix ("sent yes / received no") behind the same policy gate.
- Consider `PERFORMANCE_MODE_LOW_LATENCY` flag after measuring first-play latency on device.
