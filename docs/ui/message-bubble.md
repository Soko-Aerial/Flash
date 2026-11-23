# Flash Message Bubble System

**Status:** IMPLEMENTED (UI-005 + UI-006)  
**Component ID:** UI-005 + UI-006 (this pass) — UI-007 / UI-017 share this doc, separate passes  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI — conversation core  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Depends on:** UI-001 (`design-system.md`), UI-037 (`motion-system.md`)  
**Code:** `app/src/main/java/com/transfer/flash/ui/chat/FlashMessageBubble.kt`, `ui/theme/FlashShapes.kt`

---

## Component

`FlashMessageBubble` + `FlashBubbleShape` — the geometry, grouping, sizing, and feedback system for every message in a conversation.

**2026-11-14 timestamp spacing.** A text or voice message ends with a 2 dp inset
above its timestamp and 4 dp below. The options control uses a 20 dp visual height
and keeps its 48 dp width. Foundation expands its touch height. The low-power bubble
uses the same timestamp spacing, and voice-player controls keep their full size.

## Purpose

Messages are the surface users stare at for hours. The provisional bubble used a zero-radius corner as a fake "tail" — a generic rectangle with one square corner, exactly the "generic `RoundedCornerShape` rectangle" the master plan forbids as a final state. UI-005 replaces it with:

- Real grouped-bubble geometry with a Flash-owned concave tail.
- Sender-group aware spacing (tight inside a group, breathing between groups).
- Adaptive width with fraction + absolute cap (no `LocalConfiguration`).
- Subtle press feedback and reserved slots for delivery status (UI-015), replies (UI-010), media (UI-016/017).

## Research sources

| Source | What was studied |
|---|---|
| Telegram Android (reference only) | Grouped radius rhythm, ~2dp intra-group gap vs ~10dp inter-group, corner-swoosh tail silhouette, timestamp de-emphasis |
| iMessage / Apple HIG (reference only) | Concave tail "scoop" geometry, restrained single-accent incoming/outgoing contrast |
| WhatsApp Android (reference only) | Tail-on-last-of-group rule, sender header pattern in groups |
| Signal Android (reference only) | Border-defined incoming bubbles in dark mode, minimal elevation |
| [Shapes in Compose — Android Developers](https://developer.android.com/develop/ui/compose/graphics/draw/shapes) | Custom `Shape` / `Outline.Generic` / `Path` API surface (current) |
| [Lazy lists — Android Developers](https://developer.android.com/develop/ui/compose/lists) | `Modifier.animateItem()` stable since foundation 1.7 (replaces deprecated `animateItemPlacement`); project BOM 2025.12.00 → available |
| [SmartToolFactory/Compose-Bubble](https://github.com/SmartToolFactory/Compose-Bubble) (evaluated, rejected) | Canvas-drawn bubble library — capability check only |
| JetChat (compose-samples, Apache 2.0) | Reference chat bubble composition in official sample |

No proprietary UI code or assets copied.

## Existing approaches studied

### Approach A — Rounded rectangles with a zero-radius corner (provisional)

`RoundedCornerShape(20,20,20,0)` for the tailed bubble.

**Pros:** Zero custom drawing; already in the provisional build.  
**Cons:** Reads as a rectangle with one broken corner — no silhouette identity; master plan explicitly forbids generic rounded rectangles as final bubbles.  
**Verdict:** Rejected.

### Approach B — Custom `Shape` with concave tail path (chosen)

A Flash-owned `FlashBubbleShape : Shape` producing `Outline.Generic(Path)`: three true circular corners (arc-based) plus one concave cubic-Bézier "scoop" on the sender-facing bottom corner. Mirrors automatically in RTL via `layoutDirection` in `createOutline`.

**Pros:** Fully Flash-owned geometry; clips, borders, and ripples all follow the path; no dependencies; RTL-correct; cheap (one path per measure).  
**Cons:** Must maintain the path math; corner tangents create small crisp transitions at the scoop ends (intentional — that is what makes the tail read as a tail).  
**Verdict:** Selected.

### Approach C — Third-party bubble library (SmartToolFactory/Compose-Bubble)

**Pros:** Ready-made arrows/shadows.  
**Cons:** Extra dependency for one path; canvas-drawn shadow style conflicts with Flash "borders + surface steps, no card shadows" policy; maintenance risk; violates dependency rule "Compose/AndroidX when sufficient".  
**Verdict:** Rejected.

### Approach D — `graphics-shapes` morphing shapes (M3 Expressive)

**Pros:** Shape morphing for group transitions.  
**Cons:** Extra artifact for a static silhouette; morphing between group positions adds motion noise to every message insert; revisit only if UI-006 research wants it.  
**Verdict:** Rejected for now, recorded as future option.

## What worked

- **Tail only on the last message of a group** (WhatsApp/Telegram pattern) — direction stays readable without per-message arrows.
- **Tight intra-group spacing** vs generous inter-group spacing — grouping is communicated by rhythm, not only radius.
- **Concave scoop** (iMessage family) over triangle arrows — calmer, more premium.
- **Border-defined incoming bubbles** in dark mode (Signal) — already in Flash palette (`chatBorderIncoming`).
- **Sender headers only at group start, only in group chats** — direct chats stay clean.

## What did not work

- Zero-radius corner as tail (provisional) — accidental-looking, no identity.
- `LocalConfiguration.current.screenWidthDp` for bubble width — breaks in split screen / resizable windows and recomposes on config change; replaced by `BoxWithConstraints`.
- Uniform `spacedBy(8dp)` list gap — flattens grouping.
- Triangle/arrow tails — cartoonish against Flash's calm direction.

## Chosen approach

`FlashBubbleShape` in `ui/theme/FlashShapes.kt` + rebuilt `FlashMessageBubble` in `ui/chat/`:

- Geometry by `FlashMessageGroupPosition`: `TOP`/`MIDDLE` → fully rounded 20dp; `BOTTOM`/`SINGLE` → concave tail on sender side (end for outgoing, start for incoming).
- Tail token: `FlashShapes.bubbleTailSize = 8.dp` scoop depth.
- Width: `BoxWithConstraints`, `min(parentWidth × 0.78, 320dp)` (existing `FlashDimensions` tokens).
- Gap before item: `space12` at group start (`SINGLE`/`TOP`, non-first), `space4` inside a group.
- Press feedback: scale 0.97 via `FlashMotion.springSnappySpec()`, disabled under reduce-motion; no Material ripple (custom tactile press).
- Outgoing metadata row inside bubble (timestamp + reserved delivery slot, UI-015); incoming time lives in the sender header for groups, inside bubble for direct chats.
- Reactions row slot retained below the bubble (UI-009 will own it).
- Provisional `ui/design/FlashMessageStyling` deleted — styling absorbed into the bubble component.

## Why it was chosen

Satisfies the master-plan bar ("do not use generic `RoundedCornerShape` rectangle as the final bubble") with zero new dependencies, keeps every visible value on UI-001 tokens, and gives Flash a silhouette detail (concave "pulse scoop") that no reference app uses with these proportions. See **ADR-006** in `docs/decisions.md`.

## Visual specification

All values reference tokens; hex from UI-001 palettes.

### Geometry

| Token | Value | Use |
|---|---|---|
| `FlashShapes.radius20` | 20dp | All bubble corners |
| `FlashShapes.bubbleTailSize` | 8dp | Concave tail scoop depth |
| `FlashDimensions.bubbleMaxWidthFraction` | 0.78 | Width fraction of list |
| `FlashDimensions.bubbleMaxWidth` | 320dp | Absolute cap (tablets) |

Tail path (outgoing, LTR): bottom edge stops at `w − t`; cubic Bézier with interior control points `(w − 0.45t, h − t)` and `(w − t, h − 0.45t)` sweeps to the right side at `(w, h − t)` — a concave scoop. Incoming mirrors to the start edge. RTL mirrored in `createOutline`.

### Colors (semantic tokens, light / dark)

| Surface | Light | Dark |
|---|---|---|
| Incoming bubble | `chatBgIncoming` (#FFFFFF) | `chatBgIncoming` (surface2) + `chatBorderIncoming` hairline |
| Outgoing bubble | `chatBgOutgoing` (pulse100) | `chatBgOutgoing` (pulse800), no border |
| Incoming text | `chatTextIncoming` | `chatTextIncoming` |
| Outgoing text | `chatTextOutgoing` | `chatTextOutgoing` |
| Timestamp (incoming, header) | `chatTextTimestamp` | `chatTextTimestamp` |
| Timestamp (outgoing, in-bubble) | **new token** `chatTextTimestampOutgoing` = pulse700 | pulse300 |

New token `chatTextTimestampOutgoing` added to `FlashColors`: contrast-checked — pulse700 `#085F58` on pulse100 `#C8F0EA` ≈ 6.2:1; pulse300 `#4ECCBE` on pulse800 `#0F3D38` ≈ 6.1:1 (both ≥ WCAG 4.5:1).

### Content padding

| Token use | Value |
|---|---|
| Horizontal bubble padding | `FlashSpacing.space12` |
| Top bubble padding | `FlashSpacing.space8` |
| Bottom bubble padding | `FlashSpacing.space8` (text) / metadata row adds `space4` |
| Gap inside sender group | `FlashSpacing.space4` |
| Gap at group start | `FlashSpacing.space12` |
| List horizontal inset | `FlashSpacing.space12` |

### Typography

Body: `FlashTypography.bodyDefault`; sender name: `metadataEmphasis`; timestamps: `metadataDefault` / `numericDefault` (tabular) where clocks appear.

## Interaction specification

- **Tap:** opens message actions (UI-008 context menu lands later; current callback preserved).
- **Press:** bubble scales to 0.97 on `springSnappySpec`, returns on release. No color ripple.
- **Long-press / selection / highlight:** UI-007 — bubble exposes press scale only this pass; hooks documented, not built.
- **Disabled/failed:** UI-015/UI-027 — delivery slot reserved in outgoing metadata row.

## Animation specification

- Press scale: target 0.97f, `FlashMotion.springSnappySpec()` (damping 0.85, stiffness 600) — interruptible both directions; reduce-motion → scale locked at 1.
- Message insertion: see the UI-006 section below — wired via `Modifier.animateItem()` placement/fade-out + progress-driven content entrance keyed to `FlashMotion.messageEnter()`.
- No per-item idle animation (lazy-list rule).

## Gesture specification

- Tap vs scroll: standard Compose touch-slop; bubble click cancels on scroll — no custom thresholds this pass.
- Swipe-to-reply (UI-010) will attach at the row level, not the bubble surface, to avoid conflict with press-scale.
- RTL: geometry mirrored through `LayoutDirection`; alignment uses `Start`/`End`.

## Accessibility requirements

- Timestamp contrast verified ≥ 4.5:1 on both bubble colors (values above).
- Press scale never conveys state alone; reduce-motion disables it.
- Bubble content is one semantic group: `semantics(mergeDescendants = true)` so TalkBack reads text + time as a unit.
- Large font: bubble width capped by fraction, height grows with text; verified via 1.5× font-scale preview.

## Responsive behavior

- Width from `BoxWithConstraints` — correct in split screen, foldables, and previews (no `LocalConfiguration`).
- 320dp absolute cap keeps line length readable on tablets; UI-034 will revisit two-pane behavior.

## Dark-mode behavior

Authored dark palette from UI-001: outgoing = deep teal pulse800 with pulse100 text; incoming = surface2 with hairline border instead of shadow; timestamp token brightened (pulse300) for dark teal. No inversion.

## Performance considerations

- One `Path` per bubble measure; path objects cached in `FlashShapes` (shape instances are `val`s) — no allocation per recomposition beyond Compose's outline cache.
- `BoxWithConstraints` per row is acceptable at chat scale; avoid nesting further constraint boxes.
- Press state is row-local `MutableInteractionSource`; no shared state, no recomposition of siblings.
- Stable keys already in `FlashMessageList` (`it.id`).

## Implementation notes

```
ui/theme/FlashShapes.kt      + FlashBubbleShape class, bubbleTailSize token,
                               bubbleIncoming/bubbleOutgoing instances, bubbleShape(position, outgoing)
ui/theme/FlashColors.kt      + chatTextTimestampOutgoing (light/dark)
ui/chat/FlashMessageBubble.kt  rebuilt on tokens; previews light/dark/group/direct/1.5x font
ui/chat/FlashMessageList.kt    group-aware gap, itemsIndexed stable keys
ui/design/FlashMessageStyling.kt  DELETED (absorbed)
```

**Dependencies:** none added. Compose BOM 2025.12.00 (foundation ≥ 1.7 → `animateItem` available for UI-006).

## Testing checklist

- [x] Compose preview — light group conversation
- [x] Compose preview — dark group conversation
- [x] Compose preview — direct chat (no sender headers)
- [x] Compose preview — 1.5× font scale
- [x] `gradlew testDebugUnitTest assembleDebug` (2026-08-20, BUILD SUCCESSFUL)
- [x] Physical device screenshot — `logs/screenshots/ui-005-bubbles-light.png`, `ui-005-bubbles-dark.png` (Samsung R5CN21CNJAF)
- [ ] RTL spot-check (UI-034)
- [ ] Reduced motion (preview-level this pass; device gate in UI-038)

## Known limitations

- UI-007 selection/long-press states not implemented.
- Delivery-state slot reserved but empty until UI-015.
- System/centered messages not modeled yet (no producer in the app).
- Attachment grid placeholder unchanged (UI-016/017).

## Future improvements

- UI-007: press → selection choreography.
- Consider `graphics-shapes` morph only if UI-006 research justifies it.
- Inline (flow-level) timestamp inside text when width allows — post-acceptance polish.

## What makes this Flash?

Most chat apps signal direction with a corner flare or a cartoon arrow. Flash's tail is a **concave pulse scoop** — a calm inward curve on the sender-facing corner, proportioned 8dp against a soft 20dp body, so direction reads from silhouette rather than decoration. Group rhythm (4dp inside a sender run, 12dp between runs) plus border-defined incoming bubbles in dark mode gives conversations a quiet, engineered feel that matches the local-first P2P product story: no shadows, no gradients, just deliberate geometry in Flash's own teal/graphite language.

---

# UI-006 — Message insertion animation

**Status:** IMPLEMENTED  
**Depends on:** UI-005 (bubble + stable keys), UI-037 (`FlashMotion`)  
**Master plan requirement:** "No large bounce; works in/out; no layout jumps; lazy list performant; different behavior when scrolled up vs at bottom; historical load vs new message."

## Component

`FlashMessageList` insertion choreography: how a newly arrived/sent message enters, how siblings make room, and how the list scrolls (or deliberately does not).

## Purpose

Message arrival is the most frequent animation in a messaging app and the strongest fluidity signal. It must make new content obvious without stealing scroll position, and it must never punish performance in long conversations.

## Research sources

| Source | What was studied |
|---|---|
| Telegram Android | New tail message: short rise + fade, siblings translate smoothly, no bounce; auto-scroll only when already at bottom; scrolled-up users get a jump pill instead |
| Signal Android | Restrained fade/translate on arrival; near-instant feel; no scroll stealing |
| WhatsApp Android | Subtle slide-in at tail; unread divider + no forced scroll when scrolled up |
| Apple iMessage | Spring scale-up anchored at the composer corner — strongest entrance of the set |
| `LazyItemScope.animateItem` API reference | https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyItemScope — signature `animateItem(fadeInSpec, placementSpec, fadeOutSpec)`, added in foundation 1.7.0, requires stable keys (verified 2026-08-20; project BOM 2025.12.00 ships foundation 1.9.x) |
| M3 motion guidance | https://m3.material.io/styles/motion — calm spring physics, short durations, no overshoot theatrics |
| Jetchat (android/compose-samples, Apache 2.0) | Canonical Compose chat list: `LazyColumn(reverseLayout = true)` + stable keys; relies on placement animation only, no content entrance |

## Existing approaches studied

### Approach A — `Modifier.animateItem()` only (default fade-in + placement + fade-out)

One modifier per item; list-level fade covers appearance.

**Pros:** Trivial; allocation-free; officially supported.  
**Cons:** Entrance is a flat whole-item fade — loses the slide + scale signature tokenized in `FlashMotion.messageEnter()` (UI-037); item-level fade double-fades any content-level animation layered later.  
**Verdict:** Rejected as the full answer; its placement/fade-out channels are reused.

### Approach B — `AnimatedVisibility` + `MutableTransitionState` per item with `enter = FlashMotion.messageEnter()`

Wrap each bubble; start hidden, flip to visible on first composition for new ids.

**Pros:** Reuses the `EnterTransition` token verbatim.  
**Cons:** Bounds grow 0 → full height during the enter transition while `animateItem` placement also animates siblings — two layout motions at once read as mush; a zero-height first measure at the tail is a layout jump by definition; extra measure/layout passes per animating item.  
**Verdict:** Rejected for lazy-list items. `messageEnter()` remains the canonical transition for non-list surfaces (system rows, sheets).

### Approach C — Full-size slot + progress-driven content entrance (chosen)

`animateItem(fadeInSpec = null, placementSpec = spring, fadeOutSpec = tween)` handles sibling glide and removals; the new tail item's *content* animates alpha + translationY + scale via `graphicsLayer` driven by a one-shot 0→1 progress. The item slot is allocated at full size immediately, so nothing re-lays out mid-animation.

**Pros:** No layout jump (slot instant, content materializes inside it); GPU-only transforms; exact channel/timing parity with `messageEnter()`; new-vs-historical detection is deterministic.  
**Cons:** Needs a small amount of arrival bookkeeping (id snapshot).  
**Verdict:** Selected.

## What worked

- Telegram's restraint: ~200ms, slight rise, no bounce — matches Flash "calm, fast, tactile" (UI-037).
- Chat-standard `reverseLayout = true`: opens at the newest message with no initial scroll jump; key-anchored scroll keeps a scrolled-up reader exactly in place when items arrive.
- iMessage confirms scale belongs in the entrance vocabulary — kept, but at 0.96 (4%) rather than its playful spring-from-corner.

## What did not work

- iMessage-style spring-from-composer with visible overshoot — violates "no large bounce".
- Whole-item fade as the only entrance (Approach A) — flat, generic.
- Bounds-growing `AnimatedVisibility` insertion (Approach B) — double layout motion + jump risk.
- Scrolling to the bottom on every arrival — hostile while reading history; position is preserved unless the user is already at the bottom or sent the message.

## Chosen approach

1. **Layout direction:** `LazyColumn(reverseLayout = true)` over `messages.asReversed()` (data stays oldest-first; layout index 0 = newest = visual bottom). `contentPadding` is axis-aware, so top/bottom insets render unchanged.
2. **Item animations:** `Modifier.animateItem(fadeInSpec = null, placementSpec = motion.messagePlacementSpec(), fadeOutSpec = motion.messageFadeOutSpec())` — siblings glide on a no-bounce spring (damping 0.90 / stiffness 400); removals fade out fast; item-level appearance fade is off because the content entrance owns the fade.
3. **Content entrance:** only for a message that is (a) at the data tail (layout index 0) and (b) absent from the id set captured at first composition of the list (conversation open). Such a message animates a shared progress 0→1 (`tween(normalMillis = 200, Decelerate)`) driving: alpha 0→1, translationY `height/4 → 0`, scale 0.96→1.0 — the three `messageEnter()` channels at its exact duration/easing; the token's spring is reserved for its scale channel only, and at 4% scale the spring/ease difference is imperceptible, so one tween drives all three channels for determinism.
4. **Scroll policy:** on a new tail message, scroll to layout index 0 iff `message.isMine || atBottom`; `atBottom = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= FlashDimensions.chatBottomStickThreshold (48dp)`. Otherwise leave scroll untouched. Reduce-motion: entrance progress locked at 1, placement/removal specs snap, auto-scroll becomes instant `scrollToItem`.

## Why it was chosen

Satisfies every master-plan requirement with the least machinery: no bounce (0.96 scale, decelerate tween), works for incoming and outgoing (identical animation; `isMine` only affects scroll policy), no layout jumps (full-size slot + placement spring), performant (`graphicsLayer` transforms only; stable keys; one Animatable per entering item), distinct at-bottom vs scrolled-up behavior, and historical-vs-new separation via the open-time id snapshot — future pagination inserts at the data end can never trigger a content entrance.

## Visual specification

| Channel | From → To | Spec |
|---|---|---|
| Alpha | 0 → 1 | `tween(200, Decelerate)` via shared progress |
| TranslationY | +height/4 → 0 (rises into place) | same progress |
| Scale | 0.96 → 1.0, center origin | same progress |
| Sibling placement | position delta | spring damping 0.90 / stiffness 400 (no bounce) |
| Removal | alpha 1 → 0 | `tween(120, Accelerate)` |
| Auto-scroll | → layout index 0 | `animateScrollToItem` default (reduce-motion: `scrollToItem`) |

Durations reference `FlashMotion` tiers (`normalMillis` = 200, `fastMillis` = 120); no literals in the list file.

## Interaction specification

- Arrival while at bottom (or own send): list glides to the newest message — the entrance is always visible.
- Arrival while scrolled up: nothing moves; the unread/jump affordance is UI-022 scope.
- Arrival during an in-flight entrance: each item owns its progress; a displaced item (no longer tail) simply finishes its animation — one spring per item, no cancellation artifacts.

## Animation specification

- Entrance trigger: first composition of a tail-appended id (post-snapshot). Interruptibility: transforms are state-driven; disposal mid-animation is free. Reduce-motion: progress pinned to 1, placement/fade-out `snap()`/zero-tween.
- Group-shape ripple: appending can reclassify the previous tail (BOTTOM→MIDDLE); that bubble recomposes its `FlashBubbleShape` instantly — accepted as imperceptible under the simultaneous entrance (revisit in UI-045 if noticed).

## Gesture specification

No new gestures. Scroll interception rules unchanged; auto-scroll never fights the user because it only fires when already at the bottom or when the user sent the message.

## Accessibility requirements

- Reduce-motion (`animatorDurationScale = 0` / a11y reduce motion, UI-037 probe): all entrance/placement motion disabled, scroll is instant — new content simply appears.
- TalkBack: insertion changes list content; bubbles keep merged semantics from UI-005, so new items are announced once, as a unit.
- Large font: translation uses item `size.height/4`, proportional at any scale.

## Responsive behavior

No width-dependent branches; `graphicsLayer` transforms are resolution-independent. Two-pane/tablet behavior inherits UI-005 width rules.

## Dark-mode behavior

Motion is palette-independent; no per-theme divergence.

## Performance considerations

- Entrance cost per new message: one `Animatable` + one `graphicsLayer` block — no extra measure/layout passes.
- Sibling motion is the lazy list's built-in placement animation (stable keys already present).
- `messages.asReversed()` is an O(1) view, not a copy.
- No per-frame allocations; `derivedStateOf` for at-bottom tracking.

## Implementation notes

```
ui/theme/FlashMotion.kt       + rememberMessageEnterProgress(animate), messagePlacementSpec(), messageFadeOutSpec()
ui/theme/FlashDimensions.kt   + chatBottomStickThreshold = 48.dp
ui/chat/FlashMessageList.kt   reverseLayout LazyColumn, animateItem per item, tail-entrance gating,
                              at-bottom tracking, auto-scroll policy; pure helpers for tests
test/.../FlashMessageInsertionTest.kt  decision-function unit tests
```

**Dependencies:** none added. `Modifier.animateItem` verified stable in project BOM (foundation ≥ 1.7; BOM 2025.12.00 → 1.9.x).

## Testing checklist

- [x] Compose preview — conversation renders bottom-anchored, unchanged visuals (compiles; device screenshots authoritative)
- [x] Physical device — send while at bottom: entrance + auto-scroll + group reclassify verified, `logs/screenshots/ui-006-after-send.png` (Samsung R5CN21CNJAF, dark theme)
- [ ] Physical device — scrolled up: no scroll steal (sample conversations too short to scroll; predicate unit-tested, device check deferred to UI-021/UI-043 long-conversation work)
- [x] Dark mode — device session ran dark theme; motion is palette-independent
- [ ] Reduce motion — preview-level this pass; device gate in UI-038
- [x] Unit tests — `FlashMessageInsertionTest` (6 tests, green)
- [x] Performance spot-check — no jank or misplaced frames observed on device send

## Known limitations

- No jump-to-latest pill when scrolled up (UI-022).
- Previous-tail shape reclassification is instant (see Animation specification).
- Removal fade-out exists but no producer removes messages yet (UI-007 delete flow lands later).
- Provisional composer has no `imePadding` — the keyboard covers it while typing (observed during UI-006 device testing; fix belongs to UI-011).

## Future improvements

- UI-022 jump pill with unread count, paired with the scrolled-up branch.
- Reconsider `graphics-shapes` morph for the BOTTOM→MIDDLE corner transition at UI-045.

## What makes this Flash?

Flash's insertion is deliberately quiet: a 4% scale and a quarter-height rise over 200ms — engineered, not playful. Where iMessage performs and WhatsApp merely appears, Flash treats arrival as a pulse: the bubble surfaces from below the fold like a signal edge, siblings glide apart on a no-bounce spring, and the list never takes the reader's place away. Motion tokens, reduce-motion parity, and P2P-friendly restraint (works identically over LAN lag or Wi‑Fi Direct bursts) make the feel part of the product story rather than decoration.
