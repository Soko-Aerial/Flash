# Flash Custom Icon System

**Status:** IMPLEMENTED  
**Component ID:** UI-002  
**Last updated:** 2026-08-19  
**Depends on:** UI-001 (Flash Pulse design system)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Code:** `app/src/main/java/com/transfer/flash/ui/icons/`, `app/src/main/res/drawable/flash_ic_*.xml`

---

## Component

`FlashIcons`, `FlashIconSpec`, `FlashIcon`, `FlashIconState`, `FlashIconSheet`

---

## Purpose

Provide Flash-owned vector icons for all chat chrome and P2P status affordances. Material Icons must not appear in finished chat UI (LAN MVP may still use Material until redesigned).

---

## Research sources

| Source | Use |
|---|---|
| [Tabler Icons](https://tabler.io/icons) (MIT) | Stroke weight / grid reference only — paths redrawn, not copied |
| [Phosphor Icons](https://phosphoricons.com/) (MIT) | Optical balance reference |
| [Material Symbols](https://fonts.google.com/icons) (Apache 2.0) | Semantic coverage checklist — **not** used as final glyphs |
| [Lucide](https://lucide.dev/) (ISC) | Line-icon consistency patterns |
| Stream `stream_design_ic_*` | **Not used** — ADR-003 competitive-use + originality |
| UI-001 `design-system.md` | 20dp grid, 1.5dp stroke, tint tokens |

---

## Existing approaches studied

### A — Material Icons Extended (Gradle dependency)

**Pros:** Complete set, zero draw time.  
**Cons:** Violates AGENTS.md §34; generic Google glyph language; filled/outlined inconsistency.  
**Verdict:** Rejected for chat chrome.

### B — Third-party Compose icon library (e.g. Phosphor/Lucide port)

**Pros:** Faster initial coverage.  
**Cons:** Extra dependency; license review per package; style may not match Flash Pulse; bundle size.  
**Verdict:** Rejected for MVP — document for future if set grows past ~80 icons.

### C — Flash-owned `flash_ic_*` vector drawables (chosen)

**Pros:** Full control; matches Pulse stroke language; no Stream/Material visual clone; tint via `FlashTheme`.  
**Cons:** Manual maintenance; must draw each icon.  
**Verdict:** Selected.

---

## What worked

- **Stroke-only** vectors tint cleanly via Compose `Icon`
- **20dp × 20dp** viewport aligns with `FlashDimensions.iconMd`
- **1.5dp stroke**, round caps/joins — matches UI-001 icon philosophy
- **`FlashIconSpec`** pairs `@DrawableRes` + default content description
- **`FlashIconState`** maps to semantic colors (default/active/disabled/error)
- **`android:autoMirrored="true"`** on directional icons (back, forward, checks)

---

## What did not work

- Importing Stream or Material paths verbatim — license/identity conflict
- Filled icons mixed with stroke — inconsistent at 20dp
- Raw `@DrawableRes` in composables — no TalkBack defaults, easy to drift

---

## Chosen approach

Flash-owned Android Vector Drawable XML (`flash_ic_*`) + typed Kotlin accessors in `ui/icons/FlashIcons.kt` + `FlashIcon` composable using `FlashTheme` tints.

---

## Why it was chosen

Meets UI-002 requirement for custom chat chrome, ADR-003/004 originality, and UI-001 token integration without new dependencies.

---

## Visual specification

| Property | Value |
|---|---|
| Viewport | 24 × 24 dp (generous optical footprint) |
| Stroke width | 2.0 dp |
| Stroke cap/join | round |
| Base color in XML | `#FF000000` (tinted at runtime) |
| Default render size | `FlashDimensions.iconMd` (24dp, scaled up from 20dp) |
| Large tap target | 48dp via `IconButton`, not scaled glyph |

### State tints (`FlashIconState`)

| State | Color token |
|---|---|
| Default | `textPrimary` |
| Active | `accentPrimary` |
| Disabled | `textTertiary` |
| Error | `textError` |

Delivery/read icons use **Active** tint when consumed by UI-015.

### MVP icon registry

| Icon | Drawable | Content description |
|---|---|---|
| Send | `flash_ic_send` | Send message |
| Attach | `flash_ic_attach` | Add attachment |
| Camera | `flash_ic_camera` | Camera |
| Gallery | `flash_ic_gallery` | Gallery |
| Microphone | `flash_ic_microphone` | Voice message |
| Stop | `flash_ic_stop` | Stop |
| Play | `flash_ic_play` | Play |
| Pause | `flash_ic_pause` | Pause |
| Download | `flash_ic_download` | Download |
| Upload | `flash_ic_upload` | Upload |
| Reply | `flash_ic_reply` | Reply |
| Forward | `flash_ic_forward` | Forward |
| React | `flash_ic_react` | Add reaction |
| Search | `flash_ic_search` | Search |
| Call | `flash_ic_call` | Voice call |
| Video call | `flash_ic_video_call` | Video call |
| More | `flash_ic_more` | More options |
| Back | `flash_ic_back` | Back |
| Close | `flash_ic_close` | Close |
| Edit | `flash_ic_edit` | Edit |
| Delete | `flash_ic_delete` | Delete |
| Pin | `flash_ic_pin` | Pin |
| Mute | `flash_ic_mute` | Mute |
| Archive | `flash_ic_archive` | Archive |
| Group | `flash_ic_group` | Group |
| Device | `flash_ic_device` | Device |
| Connection | `flash_ic_connection` | Connection |
| Retry | `flash_ic_retry` | Retry |
| Verified | `flash_ic_verified` | Verified |
| Delivered | `flash_ic_delivered` | Delivered |
| Read | `flash_ic_read` | Read |
| Failed | `flash_ic_failed` | Failed |
| Encryption | `flash_ic_encryption` | Encrypted |
| Relay | `flash_ic_relay` | Relay |
| Wi‑Fi | `flash_ic_wifi` | Wi-Fi |
| Wi‑Fi Direct | `flash_ic_wifi_direct` | Wi-Fi Direct |

Legacy reaction icons (provisional UI-009): `thumb_up`, `heart`, `bolt`, `sliders`, `thumb_down`, `thread`, `flag`.

---

## Interaction specification

- Icons inside `IconButton` use 48dp minimum touch target
- Active state on send when draft non-empty (`FlashIconState.Active`)
- Disabled state for inactive controls
- Error state for failed send/transfer (UI-015/UI-027)

---

## Animation specification

**Deferred to UI-012/UI-013.** Notes for future:

- **Send:** morph attach → send or scale spring on enable (requires `AnimatedVectorDrawable` or Compose path animation)
- **Attach:** subtle rotation on sheet open
- Use `FlashMotion` tokens when UI-037 lands
- Respect reduced motion — opacity/scale only, or instant swap

---

## Gesture specification

N/A — icons are tap targets within parent components.

---

## Accessibility requirements

- Every `FlashIconSpec` includes default `contentDescription`
- `FlashIcon` passes description to platform `Icon` for TalkBack
- State changes (e.g. send enabled) should update button `contentDescription` at call site
- Decorative icons must pass `contentDescription = null` explicitly

---

## Responsive behavior

- Icons scale with `FlashDimensions.iconSm` / `iconLg` when needed; default 20dp
- RTL: auto-mirrored drawables for back, forward, delivered, read

---

## Dark-mode behavior

Tint only — vector strokes have no baked light/dark colors. Use `FlashIconState` + `FlashTheme.colors`.

---

## Performance considerations

- Vector drawables compiled once; no runtime SVG
- `FlashIcons.mvpChatSet` used for QA sheet — not loaded in production hot path
- Avoid `material-icons-extended` in chat module long-term (LAN still uses one Material icon temporarily)

---

## Implementation notes

```
ui/icons/
├── FlashIcons.kt      — FlashIconSpec registry + FlashIcon composable
└── FlashIconSheet.kt  — QA grid + previews

res/drawable/
└── flash_ic_*.xml     — Flash-owned stroke vectors
```

Provisional `ui/chat/` composables import `com.transfer.flash.ui.icons.*`.

**Dependencies added:** none.

---

## Testing checklist

- [x] `./gradlew.bat testDebugUnitTest assembleDebug`
- [x] Compose preview — light/dark icon sheet
- [x] Device screenshot — `logs/screenshots/ui-002-icon-sheet.png`
- [x] Chat composer/header use `FlashIcons` (no Material in touched chat files)
- [ ] RTL mirror verification on device
- [ ] UI-009 reaction icon redesign

---

## Known limitations

- Reaction set still uses provisional glyphs from exploratory scaffold
- LAN home TopAppBar still uses one Material chat icon (non-chat screen)
- No animated send/attach yet
- Some P2P icons (relay, wifi direct) are generic until UI-030 device testing

---

## Future improvements

- UI-012/013: animated send/attach
- UI-015: delivery state composable using Delivered/Read/Failed specs
- UI-030: connection/Wi‑Fi icons tuned with real device states
- Consider generated icon font only if set exceeds ~80 glyphs

---

## What makes this Flash?

Flash icons use a **single stroke language** tuned to the Pulse palette — round 1.5dp lines on a 20dp grid, neither Material filled glyphs nor Stream proprietary paths. P2P-specific symbols (relay, Wi‑Fi Direct, encryption, device) are first-class in the MVP set. Tints come from `FlashTheme`, so icons inherit Flash identity automatically in light and dark. The result reads as a cohesive Flash toolset, not an icon font borrowed from another product.

---

## Addendum 2026-10-02 — call icons redrawn (UI-050e)

- `FlashIcons.Mute` was a crossed-out bell and was used for the in-call microphone. It is now **`NotificationOff`** (same drawable, chat use only).
- New: `MicOff` (`flash_ic_mic_off`), `Video` (alias of `flash_ic_video_call`), `VideoOff` (`flash_ic_video_off`), `Earpiece` (`flash_ic_earpiece`).
- Redrawn: `CameraFlip` (two chasing arrows around a lens), `Hangup` (the handset turned onto its back; no slash).
- A call control that toggles has one glyph per state; see `docs/ui/calling-ui.md` UI-050e.
