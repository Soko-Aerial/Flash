# FlashBottomNav — Custom Animated Bottom Navigation

**Status:** IMPLEMENTED
**Component ID:** UI-046 (added under Phase 8 App-Shell authority — [`../ui-page-plan.md`](../ui-page-plan.md))
**Last updated:** 2026-08-25
**Owner phase:** Phase 8 / App Shell & Pages Integration
**Depends on:** UI-001 (colors/shapes/spacing), UI-002 (icons — 4 new tab glyphs), UI-037 (motion tokens), UI-039 (haptics choke point), UI-033 (screen-switch contract it complements)

---

## Component

`FlashBottomNav` (`ui/chat/src/main/java/com/transfer/flash/ui/shell/FlashBottomNav.kt`)
plus shell tab semantics added to `FlashNavigationState` (UI-033 file).

## Purpose

The persistent app chrome for Flash's four top-level surfaces — **Chats │ Transfers │ Nearby │ Settings** —
per `docs/ui-page-plan.md`. It lets tab switches happen instantly at shell level (never pushing the UI-033
stack), gives every surface a stable home, and carries Flash's motion identity: a Pulse-accent pill that
springs between tabs, an icon bounce on select, and a pulse-ring ripple on re-select. The Send action stays
one thumb-press away via a Send FAB owned by the Chats page (P1), not by this bar.

## Research sources

- [MehdiSekoba/AnimatedBottomBar](https://github.com/MehdiSekoba/AnimatedBottomBar) — floating curved Bézier "liquid" indicator, animated icon transitions, spring(dampingRatio≈0.65) indicator spec, tab-role semantics, badge dot/count support.
- [sinasamaki.com — Glassmorphic Bottom Navigation](https://www.sinasamaki.com/glassmorphic-bottom-navigation-in-jetpack-compose/) — per-tab alpha/scale springs (StiffnessLow + MediumBouncy), animated selected-index float driving a blurred glow Canvas, Haze blur backdrop, hairline gradient border.
- [Kyriakos-Georgiopoulos gist — Gooey/liquid nav](https://gist.github.com/Kyriakos-Georgiopoulos/c75bd1951283bbbdd5fb7df352f705fd) — squash→rise anticipation phase before settle; dual-icon alpha crossfade in graphicsLayer (render-pipeline-only, avoids recomposition); speed-derived deformation (stretchX/stretchY conserving visual area); RuntimeShader lens (API 13+) with graceful pre-13 fallback.
- [Melikash98/MorphNavBar](https://github.com/Melikash98/MorphNavBar) (Java/XML reference) — bubble stretch ≤2.4× while traveling, proximity fade of neighbors, damped shake (~7.8 Hz, 760 ms) on re-select, badges tracking the bubble, label color blending by proximity.
- [canopas/compose-animated-navigationbar](https://github.com/canopas/compose-animated-navigationbar) — indicator styles (line/filled/dot/worm) as pluggable strategies.
- [exyte/AndroidAnimatedNavigationBar](https://github.com/exyte/AndroidAnimatedNavigationBar) — ball-style indicator, per-icon morph animations.
- [droidcon — Mastering Animated Bottom Navigation](https://www.droidcon.com/2025/11/24/mastering-animated-bottom-navigation-in-android-from-xml-to-jetpack-compose/) — `AnimatedImageVector` + `rememberAnimatedVectorPainter(atEnd=selected)` as the state-driven AVD pattern in Compose; AVD lifecycle pitfalls inside stock `BottomNavigationView`.
- Popular-app anatomy survey (2024–2026): WhatsApp/Signal/X — flat docked bar, plain tint swap; Instagram — icon-only flat bar; Telegram iOS — tint + subtle label; Spotify — flat bar w/ colored text; YouTube — M3 active-indicator pill. Consensus premium cue = **motion of the active indicator**, not exotic containers.
- Material 3 Expressive navigation guidance (2025): active indicator is a rounded pill (~32dp tall, ~64dp wide) behind the icon; duration-based emphasis easing; labels persist for clarity. Flash exceeds rather than ignores this: same pill anatomy, spring physics + re-select pulse instead of static tint.
- Accessibility: 48dp touch targets (`FlashDimensions.minTouchTarget`), `Modifier.selectableGroup()` + `selectable(selected, role = Role.Tab)` semantics, state-described announcement, reduce-motion snap via UI-037.

## Existing approaches studied

1. **Floating pill/glass bar** (MehdiSekoba, sinasamaki/Haze): most visually striking; costs either a new blur
   dependency (Haze) or shader complexity; floats over scrolling content needing scrims; unusual for a
   messenger where the composer already owns bottom chrome; gesture-nav inset juggling.
2. **Docked flat bar + moving active indicator** (YouTube/M3-Expressive lineage): familiar, cheap
   (one translating box), fully themeable with Flash tokens, zero dependencies, trivially RTL/reduce-motion
   correct; risk of looking generic if motion is left as static tint swap.
3. **Gooey droplet / morphing-bubble nav** (gist, MorphNavBar): strongest delight; heaviest custom Canvas +
   path-morph work; proximity-fade and shake effects fight accessibility and RTL; closest to cloning a
   specific OSS look (§34 prohibition).

## What worked

- **Indicator motion as THE premium signal** (all sources): a spring-driven pill gliding between tabs reads
  fluid even when everything else stays calm.
- Dual-layer icon crossfade in `graphicsLayer` (gist): animate alpha/scale in the render pipeline, not
  recomposition-driven tint lerps — keeps 60/120fps during flings elsewhere.
- Squash-&-stretch anticipation (gist/MorphNavBar) scaled DOWN to a tasteful icon bounce (≤1.15×) so it never
  reads cartoonish.
- Re-select affordance (MorphNavBar shake → our softer pulse ring): users expect *something* when tapping the
  active tab (scroll-to-top convention in Telegram/X).
- Always-visible labels (M3 guidance): better discoverability than icon-only; we animate weight/tint, not
  visibility, avoiding layout jumps and TalkBack surprises.
- Spring params consensus: bouncy damping ≈0.65–0.85 for indicators; our existing `SpringSnappyDamping`
  (0.85/600f) sits right in range — reuse instead of inventing new constants.

## What did not work

- Glassmorphism/Haze blur: new third-party dependency for chrome-only effect — violates §3 dependency rule;
  GPU cost during list scroll unmeasured.
- RuntimeShader lens refraction: API 33+ only, fallback path doubles code; delight-per-risk ratio wrong for v1.
- Icon-only bars: fails glanceability for Transfers/Nearby/Settings whose states matter (badges).
- Stock `NavigationBar`: §34 prohibition + no control over indicator/motion/haptics.
- Neighbor proximity fade: motion noise adjacent to content edges; hard to honor reduce-motion meaningfully.

## Chosen approach

**v2 — "hanging" capsule with Flash motion identity.** v1 shipped Approach 2 (docked flat bar). Owner
review asked for the Telegram-family *floating* silhouette ("like it's hanging") with a visible slide on
tab change, so the container moved from docked to detached while every motion/accessibility decision below
was kept. The glass/blur and gooey-morph alternatives are still rejected for the reasons in §"What did not
work" — this is a shape change, not a dependency change.

```text
FlashBottomNav(items, selectedTab, onTabSelected, modifier, onTabReselected)
├── Hanging container: navigationBars inset, then 16dp side / 8dp top / 12dp bottom margins
│   └── Capsule: 60dp tall, FlashShapes.navBar (radiusFull), backgroundSurface fill,
│       hairline borderSubtle ring, FlashElevation.floating (10dp) drop shadow, 4dp inner padding
├── Row(selectableGroup) of 4 FlashBottomNavItem (weight 1f, full 60dp targets)
├── Sliding indicator chip: (tabWidth − 8dp) × 44dp, radiusFull, accentPrimary @ 16% alpha,
│   drawn BEHIND the items; x = spring-animated fractional tab index, applied as graphicsLayer
│   translationX (render pipeline, no layout pass), RTL-mirrored
├── Travel deformation: 0→amplitude→0 pulse per tab change stretches the chip along X
│   (≤1.30×) and thins it on Y by 1/√stretch — squash-and-stretch, area roughly conserved
├── Item anatomy: icon (24dp) + label (captionDefault)
│   ├── selected: accentPrimary tint, weight 600, icon pops 0.82→1 on the snappy spring
│   ├── unselected: textSecondary tint, weight 400
│   └── re-select: expanding/fading pulse ring (Canvas, emphasisMillis, Decelerate)
├── Optional badge dot/count per item (Transfers active-count, Chats unread), entering on
│   `badgePopEnter()` and leaving on `badgePopExit()` so an arriving count pops from the icon corner
└── Haptics: Tick via rememberFlashHaptics() on selection change AND on re-select (both are real
    gestures — the re-select scrolls the tab to top, so it earns the same confirmation)
```

Because the bar now floats over the page, the host reserves space for it instead of stacking below it:
`FlashBottomNavDefaults.contentInset` (80dp) + the system navigation-bar inset. Pushed (non-tab) screens
hide the bar entirely — `FlashConversationScreen` gets the full window back and its composer keeps owning
the bottom inset, which also removes the v1 double-inset under the composer.

**Content scrolls under the capsule.** The reserve is *not* padding on the page container; it is passed
into each tab page as a `bottomInset` that the page adds to its `LazyColumn` `contentPadding`. Rows
therefore travel behind the translucent-edged capsule while the last row still scrolls clear of it, and
the inset stays a constant `Dp` — an `animateDpAsState` inset on page content would relayout the whole
list every frame of the bar's show/hide. Only the floating Dev Console chip, which has no scrolling
content of its own, still animates its inset.

**Scroll position is hoisted per tab.** `FlashAnimatedScreen` disposes the outgoing page on every tab
hop, so each tab's `LazyListState` is remembered in `MainActivity` and passed down. That is also what
makes re-select-to-top possible: the host owns the state, so `onTabReselected` maps the destination to
its list and runs `animateScrollToItem(0)` (`scrollToItem(0)` under reduce-motion). All four tabs are
wired, not just Chats.

Shell semantics (in `FlashNavigation.kt`, UI-033 file):

```text
FlashDestination += Settings("Settings")          // tab roots: ChatList, Transfers, NearbyDevices, Settings
FlashNavigationMath.isTabRoot(destination)        // true for the four roots
FlashNavigationState.selectTab(destination)       // replaces whole stack with [destination] (no push)
```

Tab selection is shell-level state rendered through the SAME `FlashNavigationState.current` — one source of
truth preserved; `selectTab` is a stack *reset*, not a push, so back from Conversation lands on its tab root,
and system back at any tab root exits (predictive-back compatible).

## Why it was chosen

Keeps the messenger tab idiom (four labelled roots — zero learning cost) while the detached capsule gives
Flash a silhouette of its own, costs one animating float per frame, uses ONLY existing Flash tokens (no new
deps — §3), and leaves room to grow: badge slots exist, indicator geometry is parameterized, and the
pulse-ring is Flash-specific without being loud. The exotic alternatives remain documented above with
revisit conditions.

## Visual specification

| Token | Value | Use |
|---|---|---|
| `colors.backgroundSurface` | white/surface1 | capsule fill |
| `colors.borderSubtle` | hairline ring | capsule outline (all edges, not a top divider) |
| `colors.accentPrimary` | pulse500/pulse400 | indicator chip @16% alpha, selected icon/label |
| `colors.textOnAccent` | white/pulse900 | count badge text |
| `colors.textSecondary` | graphite | unselected icon/label |
| `shapes.navBar` (`radiusFull`) | 999dp | capsule, indicator chip, badge |
| `elevation.floating` | 10dp | capsule drop shadow (`clip = false`) |
| `spacing.space4/8/12/16` | 4/8/12/16dp | inner padding, chip gap, margins |
| Capsule height | 60dp | `FlashBottomNavDefaults.barHeight` |
| Margins | 16dp side / 8dp top / 12dp bottom | above the navigationBars inset |
| Host reserve | 80dp (`contentInset`) + system nav inset | tab roots only |
| Indicator chip | (tabWidth − 8dp) × 44dp | slides behind the items |
| Icons | 24dp `iconMd` | flash_ic_chat/transfer/nearby/settings |
| Label | `typography.captionDefault` | weight animates 400↔600 |

Dark mode: token-driven automatically (surface1 bg, pulse400 accent) — verified against UI-035 audit values.

## Interaction specification

- Tap item: `onTabSelected(item)` fires immediately (no press-delay); haptic Tick plays on the change.
- Tap selected item (re-select): pulse-ring animation + haptic Tick + `onTabReselected(item)`; the host
  smooth-scrolls that tab's hoisted list back to item 0 (instant under reduce-motion). Wired for all four
  tabs.
- Long-press: none in v1 (badge tooltips deferred).
- Disabled items: not applicable (all four tabs always enabled).

## Animation specification

All through `FlashTheme.motion` (reduce-motion collapses everything to snap()):

| Effect | Trigger | Spec |
|---|---|---|
| Indicator slide | selection change | `animateFloatAsState(selectedIndex, springSnappySpec())` — 0.85 damping / 600 stiffness; fractional value → `graphicsLayer.translationX` |
| Travel squash/stretch | selection change | `travel` pulse 0→amplitude (`fastMillis`, Decelerate) →0 (`normalMillis`, Standard); `scaleX = 1 + travel·0.30`, `scaleY = 1/√scaleX` |
| Icon pop | own selection | scale 0.82→1 via `Animatable` + snappy spring; interruptible |
| Tint/label-weight crossfade | selection change | `animateColorAsState(tweenFastSpec())`; weight via `animateIntAsState(tweenFastSpec())` |
| Pulse ring | re-select | radius 12→28dp, alpha .35→0, `emphasisMillis` Decelerate; drawn in Canvas, no recomposition churn |
| Badge appear/clear | count crosses 0 | `AnimatedVisibility(badgePopEnter(), badgePopExit())` — fade + scale from 0.5 on the snappy spring; the last non-zero count is retained so the exit has something to render |
| Scroll to top | re-select | host `animateScrollToItem(0)` on that tab's hoisted `LazyListState` |
| Bar show/hide | tab root ↔ pushed screen | `motion.shellBarEnter()` / `shellBarExit()` (slide down + fade); page content keeps a constant inset, so nothing relayouts while the bar travels |
| Reduce motion | system flag | slide spec becomes `snap()`; travel pulse, icon pop and ring suppressed; enter/exit `None`; scroll-to-top jumps instead of animating |

## Gesture specification

No gestures on the bar itself (tap only). Horizontal swipe-between-tabs deliberately NOT in v1 — conflicts
with message-list swipe-to-reply muscle memory near screen bottom; revisit post-device-testing.
RTL: the chip's `graphicsLayer.translationX` is negated under `LayoutDirection.Rtl` (the chip is aligned to
`CenterStart`, so start-relative offsets must mirror); the Row lays out mirrored automatically.

## Accessibility requirements

- `Row(Modifier.selectableGroup())`; each item `Modifier.selectable(selected, role = Role.Tab)` +
  `semantics { contentDescription = "$label tab" }`.
- Selection changes announce via standard selected-state semantics (TalkBack reads "selected, <label> tab").
- Touch target ≥48dp enforced by item height ≥48dp.
- Reduce-motion: all animations snap; pulse ring disabled (vestibular safety).
- Contrast: textTertiary on backgroundSurface ≥4.5:1 both themes (graphite500-on-white 4.6:1;
  graphite500-on-surface1 measured during UI-035 audit).

## Responsive behavior

Phone-first. Expanded widths (UI-034): the capsule remains valid up to ~600dp; beyond that the two-pane Chats
layout consumes it — final expanded-width policy deferred to UI-034 integration pass (checklist step 6 of
ui-page-plan). Landscape phones: unchanged (capsule hangs at the bottom, margins identical).

## Dark-mode behavior

Token-driven; no bespoke dark variant needed. Indicator uses pulse400 (dark accent) which passes contrast on
surface1; labels/textSecondary audited in UI-035.

## Performance considerations

- One `animateFloatAsState` (index) + one `Animatable` (travel) for the whole bar; both are read only inside
  `graphicsLayer { }`, so sliding and stretching invalidate the layer — no recomposition, no layout pass.
- Indicator drawn as a simple Box behind the icons — no Canvas paths, no blur, no shader.
- Per-item color/weight/scale animators run only for the two items whose selection actually changed.
- Pulse ring runs max once per re-select tap; auto-stops (Animatable completes).
- `shadow(clip = false)` keeps the capsule's drop shadow outside the clip bounds without an extra layer.

## Implementation notes

- Files:
  - NEW `ui/chat/src/main/java/com/transfer/flash/ui/shell/FlashBottomNav.kt` (component +
    `FlashBottomNavDefaults` geometry contract + pure `FlashBottomNavMath` helpers for JVM tests:
    `indicatorStartPx`, `indicatorStartPxAt`, `indicatorStretch`, `indicatorSquash`, `travelAmplitude`,
    `formatBadgeCount`)
  - NEW tests `ui/chat/src/test/java/com/transfer/flash/ui/shell/FlashBottomNavLogicTest.kt`
  - EDIT `ui/chat/.../navigation/FlashNavigation.kt` — `Settings` destination, `isTabRoot`, `selectTab`
    (+ extend `FlashNavigationLogicTest`)
  - EDIT `ui/theme/.../icons/FlashIcons.kt` — `Chat`, `Transfer`, `Nearby`, `Settings` specs
  - EDIT `ui/theme/.../theme/FlashShapes.kt` — `navBar`; `FlashElevation.kt` — `floating` (10dp);
    `FlashMotion.kt` — `shellBarEnter()` / `shellBarExit()`
  - EDIT `app/.../MainActivity.kt` — bar hangs over content in a `Box`; host computes
    `FlashBottomNavDefaults.contentInset + navigationBars` and hands it to each tab page as
    `bottomInset` (pages add it to their own `contentPadding`); four `rememberLazyListState()`
    hoisted here and mapped in `onTabReselected`; Dev Console is a sibling overlay layer, never an
    early `return`
  - NEW drawables `ui/theme/src/main/res/drawable/flash_ic_{chat,transfer,nearby,settings}.xml`
    (24dp viewport, 2dp round strokes — house style per flash_ic_device.xml)
- Dependencies added: **none**.
- No androidx.navigation, no exyte/canopas/Haze — concepts only, zero copied code.

## Testing checklist

- [x] JVM: `FlashBottomNavMath` — centering, clamping, invalid geometry, badge format
- [x] JVM: fractional slide interpolation + spring-overshoot clamping
- [x] JVM: stretch ceiling, squash area conservation, travel amplitude by jump distance
- [x] JVM: `selectTab` reset semantics + `isTabRoot` (extends FlashNavigationLogicTest)
- [ ] Compose preview: light, dark, RTL, large-font
- [ ] Physical device: tap each tab — indicator slides to the tapped tab with visible squash/stretch,
      icon pops, haptic fires
- [ ] Physical device: re-select the active tab — pulse ring + haptic + smooth scroll to top
- [ ] Physical device: scroll position of each tab survives switching away and back
- [ ] Physical device: content scrolls *under* the capsule and the last row is still reachable
- [ ] Physical device: slide feel, stretch amount, haptics, re-select ring, badge rendering
- [ ] Physical device: bar slides away entering Conversation and back on return
- [ ] Reduced motion: snaps, no stretch, no ring, scroll-to-top jumps
- [ ] Performance spot-check: no jank while Chats list flings

## Known limitations

- Badge data wiring (unread counts, active transfers) arrives with each page's engine flow (P3/P1);
  bar renders badges from parameters today.
- The capsule never auto-hides on scroll — it stays put while the list moves under it. A pure
  `FlashShellMath.barOffsetForScroll(...)` behind a `FlashBottomNavDefaults` flag is the next
  refinement, deliberately deferred until the static bar has had a device pass.
- Hoisted per-tab scroll positions survive rotation but not process death (they are `remember`, not
  `rememberSaveable`, because `LazyListState`'s saver would need per-tab keys plumbed through).
- No swipe-between-tabs (deliberate, see gestures).
- Predictive-back progress does not drive tab transitions (same deferral as UI-033).

## Future improvements

- AVD stroke-draw morphs for the four tab glyphs (`AnimatedImageVector`) if owner wants richer icon motion.
- Count-badge odometer roll (reuse reactions-dock pattern) when live unread data lands.
- Revisit liquid indicator ONLY after baseline ships and profiling shows headroom.

## What makes this Flash?

Every messaging app has four quiet tabs; almost none make the transition itself the signature. Flash moves a
single teal pulse-pill with spring physics tuned by our own motion system, answers re-taps with a soft radar
ring that echoes the P2P "ping" idea, and draws four brand-new 2dp-stroke glyphs nobody else ships — calm
chrome, characterful motion, zero borrowed pixels.
