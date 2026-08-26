# Settings Page (P5 tab)

**Status:** IMPLEMENTED
**Component ID:** UI-049
**Last updated:** 2026-08-25
**Owner phase:** Phase 8 / App Shell & Pages Integration (`../ui-page-plan.md` P5)
**Depends on:** UI-001/002/037 tokens, UI-035/036 theme APIs, UI-039 haptics toggle, C1.4 DataStore at wiring

---

## Component

`FlashSettingsScreen` (`ui/chat/src/main/java/com/transfer/flash/ui/settings/FlashSettingsScreen.kt`)
+ `FlashSettingsModel` demo-shaped for C1.4 substitution.

## Purpose

The few switches that matter (page-plan §What-makes-this-Flash): appearance, identity, security entries,
data retention, about. Pure consumption of Phase-1 stores — nothing blocking.

## Research sources

- Telegram Android settings: grouped plain rows, icon+label+value pattern, section headers uppercase;
  identity shown as avatar+name header block opening editor.
- Signal iOS privacy screens: destructive actions styled red text-buttons, explainer sublabels under titles.
- M3 guidance: switches carry state via color+position (never color alone); rows ≥48dp; grouped lists with
  headers beat tabbed settings on phones.
- In-repo: FlashTheme APIs (theme mode/dynamic accent from UI-035/036), FlashFeedback choke point (haptics),
  trust store revoke pattern from Nearby trusted rows.

## Existing approaches studied

1. **Tabbed/sub-page-heavy settings** (WhatsApp): overkill for 5 groups; extra nav depth.
2. **Single scrolling list with sticky section headers** (Telegram lineage): chosen.
3. **Dashboard cards** (OEM-style big tiles): decorative; hides values behind taps.

## What worked / did not

Worked: uppercase section labels, value-preview trailing text, red destructive styling, switch+label
semantic merge. Did not: burying version/about (kept visible), modal-only editors for single-line fields
(inline dialog planned post-v1).

## Chosen approach

```text
FlashSettingsScreen(model, callbacks)
├── IDENTITY: avatar-medallion + display name row (tap → onEditDisplayName; editor lands with C1.4)
├── APPEARANCE: Theme mode segmented [System|Light|Dark]; Dynamic accent Switch; Haptics Switch
├── SECURITY: Encryption entry row (→ UI-031 sheet host); Trusted peers count row (→ Nearby)
├── DATA: Save location row (SAF picker host); Background transfers Switch; Retention days slider (v2)
└── ABOUT: Version · Protocol · Device id · "Flash is a local-first messenger" note
```

Models:

```kotlin
enum class FlashThemeMode { System, Light, Dark }
data class FlashSettingsModel(
    displayName: String,
    themeMode: FlashThemeMode = System,
    dynamicAccent: Boolean = false,
    hapticsEnabled: Boolean = true,
    backgroundTransfers: Boolean = false,
    trustedPeerCount: Int = 0,
    saveLocationLabel: String? = null,
    appVersion: String = "dev",
    protocolVersion: String = "FLASH_XFER/1",
    deviceIdShort: String = "00000000",
)
```

## Why chosen / Visual spec

Assembly of existing primitives only. Rows: 56dp min, icon 20dp textSecondary, title bodyDefault,
sublabel metadataDefault textTertiary, trailing Switch/value textSecondary; section labels captionEmphasis
textTertiary uppercase; cards radius12 backgroundSurface; destructive textError. Dark mode token-driven.
Segmented control: custom pill-in-track (accentPrimary fill springs between options, springSnappy).

## Interaction / Animation / A11y

Rows merged semantics ("Theme mode, System, segmented"); switches announce state; 48dp targets; segment
fill animates x-offset springSnappy (snap under reduce-motion); switch ticks Tick haptic via
rememberFlashHaptics when enabled flag true. Slider deferred (v2) — not present to a11y-audit yet.

Shell-motion pass (2026-08-25) adds an entrance stagger: all 15 items (section labels included) are
wrapped in a private `StaggerIn(index)` helper backed by `motion.rememberStaggerProgress(index, key = Unit)`,
read inside `graphicsLayer` as alpha + a small rise so the reveal costs a render pass, never a
recomposition. **Indices are literal, not a captured counter** — lazy item lambdas compose lazily and out
of order, so an incrementing variable would hand out arbitrary delays. The delay is capped at
`MaxStaggerSteps` (6) so the list does not cascade for seconds, and `rememberStaggerProgress` reports 1f
immediately under reduce-motion. Every tappable row also carries `Modifier.flashPressScale(interactionSource)`
with `indication = null`, matching the chat-list and header buttons.

## Performance / Implementation notes

Static LazyColumn; one animatable per animated control (segment offset, switch thumb, tints) plus one
stagger `Animatable` per item, all read in `graphicsLayer`/`offset { }`. Files:
`ui/settings/FlashSettingsScreen.kt` (+math/test `FlashSettingsLogicTest.kt`). Dependencies: none.
The screen takes `listState: LazyListState` and `bottomInset: Dp` from `MainActivity`: the scroll state
is hoisted because `FlashAnimatedScreen` disposes the outgoing page on a tab hop, and `bottomInset` is
added to the `LazyColumn` `contentPadding` bottom so rows scroll under the hanging capsule.
Known limitations: rename editor, SAF picker, retention slider are host callbacks only (v1);
DataStore binding at wiring step 5 (selections are in-memory and reset on process death).
**Appearance and Haptics are live as of 2026-08-25:** `FlashApp` hoists `FlashSettingsModel` above
the theme and feeds `FlashSettingsMath.resolveDarkTheme(mode, systemDark)` into both
`FlashMaterialTheme(darkTheme, dynamicColor)` and `FlashTheme(darkTheme, dynamicAccent,
hapticsEnabled)`; the haptics flag rides a new `LocalFlashHapticsEnabled` composition local that
`rememberFlashHaptics` passes to `FlashHapticPolicy.enabled(systemHapticsEnabled = …)`, so one
provider silences every call site. The previously nested `FlashTheme { }` scopes (Conversation, dev
console, bottom nav) were removed — with default arguments each one reset `darkTheme` to the OS
value and undid the user's choice.
Post-UI-046 fix pass (2026-08-25): page owns its status-bar inset; `SwitchRow` is a real
`toggleable(role = Role.Switch)` with `stateDescription` and a Tick haptic on **both** edges (it
previously fired only when switching off, and its `enabledHaptics` parameter was dead); the segmented
control is a `selectableGroup()` of `Role.RadioButton` segments whose indicator slides via
`Modifier.offset { }` (placement pass only); `FlashSwitch` reads its thumb offset inside `offset { }`
and cross-fades track/thumb tint with `animateColorAsState`.

## Testing checklist

- [x] JVM: segment label mapping, subtitle formatting, model defaults
- [x] JVM: `resolveDarkTheme` — System follows OS, Light/Dark override it
- [ ] Preview light/dark; large font; reduced motion (segment snaps, stagger collapses)
- [ ] Device: Light/Dark/System repaints the whole shell incl. the hanging nav capsule
- [ ] Device: Haptics OFF silences taps app-wide (nav, switches, transfer row icons)
- [ ] Device: entrance stagger plays top-to-bottom once, and does not replay on scroll
- [ ] Device: scroll position survives a tab hop; re-selecting Settings scrolls to top
- [ ] Device: store-bound persistence post-wiring

## What makes this Flash?

Five calm groups, our own segmented pill that glides like the nav indicator it siblings, and honest
protocol/version lines printed right on the face — settings as spec-sheet, not maze.
