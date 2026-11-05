# Flash chat — redesign (design record)

A visual redesign of the chat conversation screen, composer, icon set and chat appearance, built
as a web prototype for review **before** any Compose change. Branch `design/chat-redesign`,
worktree `E:\FlashWork\chat-redesign`, 5 November 2026.

Nothing in this folder is wired into Gradle, and no app code, wire format, repository or callback
changed. The prototype renders the same conversation states the app renders, on the same tokens.

---

## 1. What the owner asked for

Inspired by WhatsApp and Telegram, read from their actual screens rather than from memory:

- the send key separated from the text field, as its own round control (WhatsApp's send circle,
  Telegram's trailing mic/send slot);
- a text field that reads as a pill, distinct from the bar it sits in;
- icons redrawn rather than the stock Material shapes;
- a chat background the user can change;
- the settings surface that owns that choice;
- **design only** — no behaviour, no protocol, no new business logic.

---

## 2. Art direction

**Dealt style:** `framed-sheet`, seed `1686666480` (`draw.mjs --app --kind support --screens
inbox,settings --keep palette,type`). Palette and type were kept because Flash already fixes them;
the style contributes composition only.

Why this style: its move is "depth from one tone step, chrome grey, one accent" — the opposite of the
shipped conversation, which stacks a grey list, a grey bar and a grey input pill with a divider on
top. Adopted: the frame/sheet relationship (a tinted shell around a white work surface), the 48px
top bar, and the rule that the accent appears on the action only.

Recorded redraws:

- **Palette and type are Flash's**, not the dealt ones (`--keep`). The dealt `framed-sheet` palette
  is blue-grey; Flash's Pulse teal, Spark amber and Graphite ramp are the product's identity.
- **The "no hue in chrome" rule is relaxed for the send key**, the one filled accent control on the
  screen. `framed-sheet` keeps the brand to a logo and an underline; a chat with an invisible send
  key would lose WhatsApp's and Telegram's clearest affordance.
- **The desktop shell keeps Flash's 68dp rail and 320dp list** (the style's 230px sidebar does not
  match `FlashNavigationRail`).

### The idea (from the subject, not the category)

> On this screen, **the direct link between two devices** becomes **the room the conversation sits
> in**: a field behind the bubbles that can be changed like wallpaper, while the bubbles themselves
> stay opaque and keep their own contrast.

A competitor test: WhatsApp's doodle wallpaper and Telegram's cloud wallpaper are decoration with a
brand pattern. Flash's own thing is that there is no server in the middle — so the wallpaper set is
named and drawn as *signal*: a dot grid (the mesh), a surveyor's hatch (the LAN survey), rings from
the corner (the pulse arcs the app already uses for presence). It is a field, not a picture, and it
never sits under text.

**Signature element:** the transfer bubble with its chunk progress and the "Verified · SHA-256" line
— the one thing in this conversation no other messenger would draw.

**The face:** the platform system face (as shipped; `FlashTypography` passes `fontFamily = null`), so
the prototype and the app cannot diverge. No webfont.

---

## 3. What changes against the shipped screen

| Area | Shipped | Redesign |
| --- | --- | --- |
| Header | 56dp, avatar 36dp, four actions (call, video, search, more), transport icon in the status line | **60dp, avatar 34dp, three actions (call, video, more)**; search moves into the conversation menu, which already lists it. Transport stays in the subtitle ("Online · direct over LAN") with a 13dp lock beside the name |
| Thread | `backgroundChat` flat colour, 12dp gutters, 4dp/12dp run gaps, 78% cap | Wallpaper field behind opaque bubbles; 2dp inside a run, 16dp between runs; day chip is a surface pill, unread is a 1px accent rule with a label |
| Bubble | 20dp corners, 8dp scoop on the last of a run, 12/8 padding | unchanged geometry; incoming keeps its hairline, outgoing keeps `pulse100`; the time sits inline at the end of the last line |
| Composer | 40dp attach + grey `graphite100` pill (radius 20) + 40dp send/mic in one row | **44dp attach · 44dp pill (radius 22, `graphite50`) · 44dp trailing slot**; 8dp gaps; one trailing control that crossfades mic → send; bar stays `surface` with a 1dp hairline |
| Icons | custom, 24dp grid, **2dp** stroke | custom, 24dp grid, **1.75dp** stroke, one asymmetric detail per glyph; names unchanged |
| Wallpaper | none | 5 presets + one colour, each with light and dark variants, applied instantly with a visible status and Undo |
| Settings | no appearance surface | "Chat appearance": wallpaper (opens the sheet), a working theme control and a live preview of the chosen field |

Deliberately **not** changed: message grouping rules, delivery ticks, reactions (still the app's text
glyphs), attachments, transfer cards' states, the conversation menu's items, and every callback.

---

## 4. Tokens

Values are copied from `ui/theme` (`FlashColors`, `FlashTypography`, `FlashShapes`, `FlashSpacing`,
`FlashMotion`); the prototype adds no colours of its own except the two darkened text greys in §6.

| Role | Light | Dark |
| --- | --- | --- |
| app ground | `graphite50` #F5F6F8 | `void` #07080A |
| surface / bar | white | `surface1` #181C26 |
| surface-2 / pill | `graphite50` #F5F6F8 | `surface0` #101218 |
| ink primary | `graphite900` #171A22 | `graphite50` #F5F6F8 |
| ink secondary | `graphite700` #454C5C | `graphite300` #A8B0C0 |
| ink tertiary (text) | #5C6475 (see §6) | #8B93A4 |
| accent | `pulse500` #0D9488 | `pulse400` #1FB8A6 |
| accent ink | `pulse900` #042F2B | `pulse900` #042F2B (on accent) |
| incoming bubble | white · border `graphite150` | `surface2` #222836 · border `surface3` |
| outgoing bubble | `pulse100` #C8F0EA | `pulse800` #0F3D38 |
| bubble time | `graphite500` | `graphite300` |

Type: 16/22 message and composer text, 16/600 header name, 14/20 captions, 12/16 metadata,
11/14 timestamps (tabular). Header name at 16/600, presence 12. Two sizes do the work; nothing
below 11px.

Geometry: 20dp bubble, 8dp scoop, 22dp pill, 12dp attachment card, 16dp cards, 24dp sheet top,
44dp controls, 60dp header (56 shipped), 64dp composer bar, 8dp gaps.

---

## 5. The icon set ("Flash Line")

24dp grid, 1.75dp stroke, round caps and joins, no fills except the play triangle and the "more"
dots. Fifteen chat glyphs are redrawn; navigation glyphs are untouched because they are outside this
screen.

| Name | One deliberate detail |
| --- | --- |
| Send | nose cut square, fold stops short of the tip |
| Attach | the outer loop is squared |
| Voice | tapered capsule, plain stem (no crossbar tick) |
| Camera | flat top deck, aperture glint inside the lens |
| Call | flat mouthpiece |
| Video | the lens is its own shape overlapping the body |
| More | wider dot pitch (5.4 / 12 / 18.6) |
| Back | short tail |
| Reply | one hinge, one long underline |
| Encrypted | padlock with a keyhole, no ring |
| Sending / Sent / Read | 9 o'clock hand; long tail; two offset ticks |
| File | folded corner |
| Pin | short tack |
| Search | long handle |

The Compose port replaces `flash_ic_*.xml` path data and `android:strokeWidth`, keeping every
`FlashIcons` name and `contentDescription`.

---

## 6. Contrast (measured)

| Pair | Ratio |
| --- | --- |
| ink / white | 17.39 |
| ink-2 / white | 8.60 |
| ink-3 / white | 5.94 |
| ink-3 / chat ground `graphite100` | 5.07 |
| outgoing text `pulse900` / `pulse100` | 11.83 |
| outgoing time / `pulse100` | 6.40 |
| white / `pulse600` (accent fill: send, Done) | 5.21 |
| `pulse900` / `pulse400` (dark accent fill) | 5.85 |
| dark outgoing text / `pulse800` | 6.97 |
| dark meta / `surface2` | 6.76 |
| dark tertiary / `surface0` | 6.07 |
| verified line `success700` / white | 6.60 |

Two token corrections the port should carry:

1. `textTertiary` (`graphite500` #6E778A) is **4.50:1 on white — exactly on the line — and 3.86:1 on
   `graphite100`**. 12dp metadata that sits outside a bubble, and the composer placeholder, need
   `textSecondary` (`graphite700`) or a new #5C6475 step. The prototype uses #5C6475.
2. Every **accent fill that carries text or a glyph** must use `pulse600` with white (5.21:1) in
   light and `pulse400` with `pulse900` (5.85:1) in dark. The shipped `textOnAccent` is white on
   `pulse500` (3.74:1), which fails AA for the 16dp label.
3. Outgoing timestamps (`pulse700` #0A7A70) are 4.24:1 on `pulse100`; darken to #075C55 (6.40:1).

Touch targets: 44dp for the composer, 40dp for header icons (the shipped `minTouchTarget` is 48dp and
is kept in Compose — the prototype's 34dp avatar and 38dp header buttons are the mock's only
sub-44 controls and are inside larger hit areas in the port).

---

## 7. Wallpaper

Five presets and one custom colour. Every preset has a light and a dark variant, so choosing a
wallpaper is not a theme switch:

| Preset | Field | Pattern | Why it belongs to Flash |
| --- | --- | --- | --- |
| Graphite | `graphite100` / `surface0` | none | the shipped surface, now the default |
| Signal | #EDF0F4 / #0D1014 | 22dp dot grid at 5–7% | the device mesh |
| Blueprint | #E6EEEF / #0A211F | 28dp survey hatch | the LAN survey |
| Draft | #F2EEE6 / #171310 | 24dp + 96dp graph grid | a surveyor’s notebook, for long reads |
| Pulse | #E6F7F4 / #07211F | four rings from the corner at 10–15% | the presence arcs already in the app |
| Yours | one of eight muted colours | none | a flat field with no pattern |

Rules: the wallpaper sits **behind** bubbles only; every message, time and day chip is on an opaque
surface. Patterns are drawn at 5–9% (rings 10–15%) so they read as texture and vanish behind a
bubble. No wallpaper dims text.

---

## 8. Motion

| Element | Change | Token | Reduced motion |
| --- | --- | --- | --- |
| Mic → send | opacity + scale 0.7→1 on two stacked glyphs in one 44dp control | 120ms `cubic-bezier(0,0,.2,1)` | instant swap |
| New message | opacity, 6dp rise, 1.5% scale | 200ms Decelerate | appears |
| Wallpaper sheet | opacity + 16dp rise | 320ms `cubic-bezier(.32,.72,0,1)` | appears |
| Press | scale .94 (header), .97 (bubbles) | 120ms out | none |
| Typing dots | opacity loop 1.2s, 2dp rise | loop | static at 60% |
| Reaction (not in this mock) | scale pop | 220ms spring | none |

No height, width, margin or top is animated. `prefers-reduced-motion: reduce` and the prototype's
own Motion switch both collapse every animation to 1ms.

---

## 9. Compose port plan (after the owner approves this mock)

Presentation only; every parameter and callback of `FlashConversationScreen` stays as it is.

1. `ui/theme/.../composeResources/drawable/flash_ic_*.xml` — new path data at 1.75dp (§5).
2. `FlashChatHeader.kt` — 60dp row, 34dp avatar, three actions; search already exists in
   `FlashConversationMenu`.
3. `FlashComposer.kt` — 44dp targets, 22dp pill, `graphite50` fill, one trailing slot with the
   crossfade (no new state: it is `canSend` and `recordingPhase`, both already there).
4. `FlashMessageList.kt` — paint the wallpaper behind `backgroundChat`, 2dp/16dp run gaps, day chip
   and unread rule restyle. **`FlashConversationScreen`'s `Scaffold` also needs
   `containerColor = backgroundChat`** so overscroll does not reveal the Material scheme.
5. `FlashMessageBubble.kt` — inline time + tail unchanged; timestamps to the corrected token.
6. New `FlashChatAppearanceSheet` + a `Chat appearance` entry in `FlashConversationMenu`, with a
   preview and the five presets.
7. Wallpaper persistence — **the one logic question.** The mock applies to the open chat and offers
   Undo; it deliberately does not pretend to offer per-chat vs all-chats scope. Options for the
   owner:
   a. one app-wide wallpaper in `FlashSettings` (Android + desktop datastore);
   b. per-chat in Room (a schema step);
   c. visual-only for now: the picker previews, nothing is stored.
   Recommendation: (a) first, then (b) if per-chat is wanted.

Not in scope for the port: reactions, attachments, transfer logic, call UI, desktop rail.

---

## 10. Limits of this mock

- The 8dp concave scoop is approximated with a clipped corner. Compose keeps the exact
  `FlashBubbleShape` cubic **and its stroke**, so the real bubble's tail will read slightly crisper
  than the prototype's.
- The demo appends messages to prove the composer; nothing persists.
- Sample conversation, names, files and sizes are fictional. The photo is a real, credited
  photograph (Sivas Buruciye Medrese, Dosseman, CC BY-SA 4.0, via Wikimedia Commons) and is not
  presented as the project's own.
- Reactions are omitted rather than faked: the app renders reaction text glyphs, and the inspector's
  gate bans emoji in UI, so showing them here would either misrepresent the product or fail the
  gate.
- The prototype is not a Compose preview: sizing at other densities, RTL mirroring and the IME are
  the port's to verify on a device.

---

## 11. Checks run

- The critique is recorded in `CRITIQUE.md` (dual-agent: design review + detector/evidence), and its
  P1/P2 findings are fixed except where noted as owner decisions.
- `node <skill>/scripts/lint.mjs --app design/chat-web/src` — **0 FAIL**. Remaining warnings are
  clock zero-padding (the voice timer) and shadows on real overlays (sheet, phone bezel).
- Impeccable `detect` on `design/chat-web/src` — **0 findings, exit 0** after the quote's side-tab
  and the file-in-bubble nesting were removed.
- `node <skill>/scripts/inspect.mjs --app http://localhost:4174` on the production build —
  **0 FAIL, 3 WARN**: five corner radii (two are the phone frame's 44 and 56), "2 of 32 blocks above
  the fold" and "no data hue" (this is a neutral-chrome review document, not a landing page or a
  chart screen).
- Screenshots at 1440, 768 and 390, light and dark, five wallpapers; no horizontal overflow
  (`scrollWidth == clientWidth` at all three widths, re-measured after the composer fix).
- Contrast measured in the browser: every sampled app pair is ≥5.21:1 (§6). The pre-critique run
  found 42 failing text elements in four pairs; all four were fixed.
- Keyboard: 36 stops at 390, 13 inside the phone, input and send/mic in visual order; every icon
  control has an accessible name; the ring is the app's `--focus-ring`.
- Reduced motion: `data-motion="reduced"` (and the media query) collapse every animation to 1ms.
