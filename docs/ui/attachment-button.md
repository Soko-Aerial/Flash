# Custom Attachment Button & Palette — UI-012

**Status:** IMPLEMENTED  
**Component ID:** UI-012  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

`FlashAttachmentButton` (tactile composer attach trigger with rotation micro-interaction), `FlashAttachmentSheet` (modal bottom sheet attachment palette), and `FlashAttachmentTile` (animated action item with vibrant themed container).

## Purpose

Each source URI appears once in the current draft. Picking the same source again keeps
its existing tray entry. A later draft can send that source again. The composer consumes
its ready entries before dispatching them to the host.

The attachment system allows users to select and send various types of media, documents, and high-speed peer-to-peer data transfers. It provides:
1. **Tactile composer attachment trigger** with rotation feedback (`FlashIcons.Attach`) and active state highlight.
2. **Sculpted modal attachment palette** presenting categorized options (Gallery, Files, Camera, Audio, Flash Direct Transfer).
3. **High-speed P2P transfer integration** highlighting Flash's local-first offline LAN / Wi-Fi Direct file sharing capabilities alongside standard media selection.
4. **Keyboard-safe sheet presentation** seamlessly coordinating with IME dismissals.

---

## Research sources

1. **Telegram Android / iOS** — Modal bottom sheet with grid of colorful circular action icons, quick gallery strip, file picker, location, contact, and high-speed P2P transfer items.
2. **Signal Android / iOS** — Clean 4-column modal bottom sheet with high-contrast icon tiles (Gallery, File, Contact, Location).
3. **WhatsApp Android / iOS** — 3×3 / 2×4 action tile palette with vibrant circular backgrounds, spring scale entrance on open, and keyboard auto-dismissal.
4. **iMessage (iOS 17/18)** — '+' expand trigger rotating 45° to 'x', vertical app palette with staggered spring reveal.
5. **Discord Mobile** — Media tray with tabs for photos, files, and server integrations.
6. **Jetpack Compose Material 3 ModalBottomSheet** — `rememberModalBottomSheetState`, drag handle, edge-to-edge system insets, and zero-recomposition animation dispatch.

---

## Existing approaches studied

### Approach A: Modal Bottom Sheet Action Grid (WhatsApp / Telegram / Signal)
- Tapping attach opens a half-height bottom sheet with a 4-column grid of circular action icons.
- **Strength:** High discoverability; touch targets exceed 56dp; spacious text labels; easily dismissible by drag down or tap outside; cleanly replaces software keyboard.
- **Weakness:** Consumes bottom half of the screen.

### Approach B: Inline Expanding Speed Dial (iMessage)
- '+' button expands into a vertical or horizontal overlay above the composer.
- **Strength:** Keeps focus inline with the message text field.
- **Weakness:** Limited space for rich actions; cramped on smaller Android devices; easily obscures earlier messages.

### Approach C: Immediate Full Gallery Drawer (Telegram Desktop / iOS)
- Opens an embedded horizontal photo roll directly inside the bottom sheet above action icons.
- **Strength:** 1-tap photo sending.
- **Weakness:** Demands heavy thumbnail caching, runtime storage permissions before intent selection, and high battery/memory consumption.

---

## What worked

| Pattern | Source | Why it works |
|---|---|---|
| Modal Bottom Sheet with 4-column Action Grid | WhatsApp, Signal | Clear, spacious, high accessibility, handles 5–8 distinct actions cleanly |
| 45° rotation micro-interaction on Attach icon | iMessage | Communicates expanded state visually; feels playful and responsive |
| Distinct vibrant circular icon containers | Telegram, WhatsApp | Instant visual categorization (e.g. Gallery = Cyan, Files = Indigo, Camera = Amber) |
| Tactile press physics on tiles (0.92x scale) | Flash Design System | Micro-spring feedback on tile touch confirms intent |
| Staggered spring entrance on sheet reveal | Telegram, iMessage | Makes the palette feel physical and alive without slowing user interaction |
| IME keyboard auto-dismissal | Material 3 / Android | Avoids layout fighting between soft keyboard and bottom sheet |

## What did not work

| Pattern | Source | Why rejected |
|---|---|---|
| Embedded live camera viewfinder inside sheet | WhatsApp | Heavy battery drain, camera hardware lock contention, unnecessary complexity |
| Inline horizontal carousel | Discord | Horizontal scroll hides secondary actions like Files and Flash Transfer |
| Uncategorized single file picker dialog | System SAF default | Too generic; doesn't distinguish media from high-speed P2P folders |

---

## Chosen approach

**Flash Sculpted Modal Attachment Sheet (`FlashAttachmentSheet`) + Rotating Composer Trigger (`FlashAttachmentButton`).**

1. **Trigger Button (`FlashAttachmentButton`)**:
   - Icon: `FlashIcons.Attach`.
   - Rotation: Rotates from $0^\circ \to 45^\circ$ when the attachment sheet is visible.
   - Tint: Switches from `colors.textPrimary` to `colors.accentPrimary` when active.
   - Press physics: Springs to 0.88x scale on touch press.
2. **Attachment Palette (`FlashAttachmentSheet`)**:
   - Form factor: `ModalBottomSheet` with `FlashShapes.radius24` top corners and subtle drag handle.
   - Grid: 4-column layout (`Arrangement.spacedBy(FlashSpacing.space16)`).
   - Action Items:
     1. **Gallery** (`FlashIcons.Gallery`, Cyan/Teal container) — Photos & videos via Photo Picker / SAF.
     2. **Files** (`FlashIcons.Upload`, Blue/Indigo container) — General documents, archives, and bulk files.
     3. **Camera** (`FlashIcons.Camera`, Amber/Orange container) — Instant photo capture.
     4. **Audio** (`FlashIcons.Microphone`, Violet/Purple container) — Audio and voice recordings.
     5. **Flash Transfer** (`FlashIcons.Device`, Flash Pulse Teal container) — High-speed offline Wi-Fi Direct / LAN folder sync.
3. **Tile Visuals (`FlashAttachmentTile`)**:
   - 56dp circular icon container with 1dp `borderSubtle`.
   - 24dp themed vector icon.
   - Centered label in `typography.captionEmphasis`.
   - Staggered spring scale-in on sheet presentation.

---

## Visual specification

### Attachment Trigger Button (`FlashAttachmentButton`)

| Property | Value |
|---|---|
| Size | 40.dp × 40.dp circle |
| Icon | `FlashIcons.Attach` (20.dp) |
| Inactive tint | `colors.textPrimary` (or `colors.textTertiary` when disabled) |
| Active tint | `colors.accentPrimary` |
| Rotation (active) | $45^\circ$ |
| Touch target | Minimum 48.dp × 48.dp semantic target |

### Attachment Bottom Sheet (`FlashAttachmentSheet`)

| Property | Value |
|---|---|
| Container shape | Top-start 24.dp, Top-end 24.dp rounded corners (`FlashShapes.radius24`) |
| Surface background | `colors.backgroundSurface` |
| Border | `FlashDimensions.borderHairline`, `colors.borderSubtle` |
| Drag handle | 32.dp × 4.dp pill, `colors.borderSubtle` |
| Padding | Horizontal 24.dp, Top 12.dp, Bottom 32.dp (plus navigation bars insets) |
| Grid spacing | Horizontal 16.dp, Vertical 20.dp |

### Attachment Action Tiles (`FlashAttachmentTile`)

| Action | Icon | Container Color | Tint | Label |
|---|---|---|---|---|
| **Gallery** | `FlashIcons.Gallery` | `Color(0xFF00B4D8)` (Cyan) | `Color.White` | Gallery |
| **Files** | `FlashIcons.Upload` | `Color(0xFF4361EE)` (Indigo) | `Color.White` | Files |
| **Camera** | `FlashIcons.Camera` | `Color(0xFFF77F00)` (Amber) | `Color.White` | Camera |
| **Audio** | `FlashIcons.Microphone` | `Color(0xFF7209B7)` (Violet) | `Color.White` | Audio |
| **Flash Transfer** | `FlashIcons.Device` | `FlashColors.accentPrimary` (Teal Pulse) | `Color.Black` | Flash P2P |

---

## Interaction specification

| Action | Behavior |
|---|---|
| Tap `FlashAttachmentButton` in composer | Opens `FlashAttachmentSheet`, rotates button icon $45^\circ$, hides soft keyboard if active |
| Tap outside / drag down bottom sheet | Dismisses `FlashAttachmentSheet`, resets button rotation to $0^\circ$ |
| Tap action tile (e.g. Gallery) | Plays haptic click, dismisses sheet, invokes corresponding callback (`onSelectGallery()`, etc.) |
| Hardware back press | Closes `FlashAttachmentSheet` before navigating back |

---

## Animation specification

| Animation | API / Tokens | Spec |
|---|---|---|
| Attach icon rotation | `animateFloatAsState` | `spring(dampingRatio = 0.65f, stiffness = 500f)` ($0^\circ \to 45^\circ$) |
| Attach icon color tint | `animateColorAsState` | `tween(FlashMotion.fastMillis)` |
| Tile staggered reveal | `LaunchedEffect(Unit)` | 20ms delay per tile index, spring scale ($0.7f \to 1.0f$) |
| Tile touch press | `collectIsPressedAsState` | Scale to 0.92x with snappy spring |

---

## Accessibility requirements

- **TalkBack Announcements**:
  - Attach button: `contentDescription = "Attach media or files. Double-tap to open attachment options."` (or "Close attachment options" when open).
  - Tiles: `contentDescription = "Attach from $label"` with `Role.Button`.
- **Reduced Motion**: Under `reduceMotion == true`, icon rotation and tile scale stagger are bypassed.

---

## Implementation notes

### New Files in `:ui:chat` (`com.transfer.flash.ui.chat`)
- `FlashAttachmentButton.kt` — stateful composer attachment trigger with rotation animation.
- `FlashAttachmentSheet.kt` — modal bottom sheet containing the attachment action grid and tile definitions.

### Modified Files in `:ui:chat`
- `FlashComposer.kt` — integrate `FlashAttachmentButton` and manage attachment sheet visibility.
- `FlashConversationScreen.kt` — wire attachment action callbacks (Gallery, Files, Camera, Audio, Flash Transfer).

---

## Testing checklist

- [ ] Compose preview — `FlashAttachmentButton` inactive and active states
- [ ] Compose preview — `FlashAttachmentSheet` with 5 action tiles
- [ ] Physical device — tap attach button opens sheet with smooth icon rotation
- [ ] Physical device — tile tap triggers haptic feedback and invokes callback
- [ ] Dark mode — sheet surface and tile container contrast verified
- [ ] TalkBack — all action tiles announce label and button role
- [ ] Unit tests — attachment button state transitions and action dispatching

---

## What makes this Flash?

Flash's attachment system bridges everyday messaging with high-speed local-first transfers. Alongside standard gallery and document pickers, it features a dedicated **Flash Transfer** tile that connects directly to Flash's Wi-Fi Direct and LAN peer transport engine. The tactile $45^\circ$ rotation of the attach glyph, paired with vibrant category containers and spring physics, makes staging transfers feel immediate, crisp, and responsive.
