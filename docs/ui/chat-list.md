# Flash Chat List

**Status:** IMPLEMENTED  
**Component ID:** UI-003  
**Last updated:** 2026-08-25  
**Depends on:** UI-001, UI-002, UI-037, UI-046 (shell inset)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Code:** `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashChatList*.kt`

---

## Component

`FlashChatListScreen`, `FlashChatListRow`, `FlashChatListTopBar`, `FlashChatListItemUi`

---

## Purpose

Primary inbox surface: browse conversations, scan previews/timestamps, spot unread/presence/typing, and open a chat. Must feel fast and calm — Telegram scan speed with Flash Pulse identity.

---

## Research sources

| Source | Studied |
|---|---|
| Telegram Android | 72dp rows, pinned section tint, bold unread titles, typing in accent, swipe archive |
| Signal Android | High contrast titles, muted de-emphasis, minimal trailing chrome |
| WhatsApp Android | Preview prefix (photo/video), timestamp alignment, unread pill badge |
| iMessage patterns | Timestamp top-trailing, single-line preview hierarchy |
| UI-004 header | Reuse `FlashPeerPresence`, transport semantics |
| UI-037 `motion-system.md` | `statusCrossfade`, `messageEnter`, `springSnappy` for row feedback |
| Compose `LazyColumn` + `animateItem` | Insert/remove without full list relayout |

---

## Existing approaches studied

### A — Material 3 `ListItem` + default dividers

**Pros:** Fast, accessible.  
**Cons:** Generic Material list; no pinned/typing/unread motion; violates §34.  
**Verdict:** Rejected.

### B — Stream-style row clone (prior scaffold)

**Pros:** Polished reference.  
**Cons:** ADR-003/004 — too close to Stream product rows.  
**Verdict:** Rejected.

### C — Flash-owned row + LazyColumn (chosen)

**Pros:** Pulse tokens, custom icons, motion tokens, P2P presence affordances, swipe hooks.  
**Cons:** More code to maintain.  
**Verdict:** Selected.

---

## What worked

- **72dp row** with **48dp avatar** — readable at a glance
- **Title + timestamp** on one line; **preview** second line
- **Pinned rows** use `backgroundSurfaceSubtle` + pin icon (not heavy card shadow)
- **`AnimatedContent`** for typing ↔ preview crossfade (same token as header)
- **`AnimatedVisibility` + scale** for unread badge appearance
- **Online dot** on avatar bottom-end (8dp, matches UI-004)
- **`animateItem()`** on LazyColumn rows for insert/remove
- **Swipe end→start** reveals Archive action (snap back — no dismiss yet)
- **Long-press** enters selection mode (UI-007 extends)

---

## What did not work

- Default `ListItem` — insufficient control for unread/typing/pin layout
- Bouncy list insert springs — janky; use `messageEnter` tween+subtle scale instead
- Full swipe-dismiss archive — confusing without undo; snap-back action only for MVP

---

## Chosen approach

Flash chat list: custom row composable, pinned-first sort, repository-driven `FlashChatListItemUi`, navigation via `FlashChatRepository.openConversation(id)`.

---

## Why it was chosen

Inbox is the app's front door for messaging. Flash must own row geometry, motion, and P2P status hints before bubbles/composer polish. Central models keep LAN/session mapping out of composables (UI-030 later).

---

## Visual specification

| Element | Token / size |
|---|---|
| Row height | `FlashDimensions.chatListRowHeight` (72dp) |
| Avatar | `FlashDimensions.avatarLg` (48dp) |
| Horizontal padding | `FlashSpacing.space16` |
| Title | `typography.bodyEmphasis` when unread, else `bodyDefault` |
| Preview | `captionDefault`; typing uses `metadataEmphasis` + `accentPrimary` |
| Timestamp | `metadataDefault`, `textTertiary`; unread uses `accentPrimary` |
| Unread badge | min 20dp circle, `accentPrimary` bg, `textOnAccent`, cap display "99+" |
| Pinned background | `backgroundSurfaceSubtle` |
| Divider | `borderSubtle` hairline between rows (not after last) |
| Online dot | 10dp, `statusOnline`, border `backgroundSurface` 2dp |

Icons: `FlashIcons.Pin`, `Mute`, `Group`, `Gallery`, `Delivered`, `Read`, `Failed` — no Material in row chrome.

---

## Interaction specification

| Gesture | Behavior |
|---|---|
| Tap row | `onConversationClick(id)` — open conversation |
| Long press | Enter selection mode; toggle row selected (UI-007 extends) |
| Swipe end→start | Reveal Archive action; release triggers `onArchive(id)` then snap back |
| Tap search | `onSearchClick` — UI-024 wires search |
| Selection bar | UI-007 — placeholder hidden when not in selection mode |

---

## Animation specification

| Trigger | Token |
|---|---|
| Branch swap (error ⇄ loading ⇄ empty ⇄ content) | `AnimatedContent` over a private `ChatListPageState` **enum** from the pure `chatListPageState(...)` helper — targeting the whole UI state would restart the crossfade on every presence/typing tick |
| Typing ↔ preview | `FlashTheme.motion.statusCrossfade()` |
| Unread badge show/hide | `AnimatedVisibility` + scale 0.8→1, `springSnappySpec` |
| Row press | `Modifier.flashPressScale(interactionSource)` — scale 0.98, `springSnappySpec`, `indication = null` |
| List insert/remove/reorder | `Modifier.animateItem(motion.messagePlacementSpec(), motion.messageFadeOutSpec())`, rows keyed by conversation id |
| Pin state change | background crossfade `statusCrossfade` |
| Row tap → Conversation | the shell's `screenPushEnter()/Exit()`; the header's avatar and title then run their own `rememberStaggerProgress` handoff (see [navigation.md](navigation.md)) |

**Shell integration (2026-08-25):** `listState: LazyListState` and `bottomInset: Dp` are parameters, both
supplied by `MainActivity`. The scroll state must be hoisted — `FlashAnimatedScreen` disposes the outgoing
page on every tab hop, so a page-local `rememberLazyListState()` would reset the list on each switch and
the shell could not implement re-select-to-top. `bottomInset` becomes the `LazyColumn`'s bottom
`contentPadding` so rows scroll *under* the hanging capsule rather than being clipped above it.

---

## Gesture specification

- Swipe threshold: Material3 `SwipeToDismissBox` defaults
- Long-press: 400ms (platform default via `combinedClickable`)
- RTL: swipe directions mirror in UI-034 pass

---

## Accessibility requirements

- Row `contentDescription`: "{title}, {preview}, {timestamp}, {unread} unread"
- Typing state spoken as "typing" in preview
- Badge counts in description
- Touch targets ≥ 48dp row height
- Selection checkbox (when active) has "Selected"/"Not selected"

---

## Responsive behavior

- Title/preview ellipsize single line
- Tablet: same row (UI-034 may show list+detail split)

---

## Dark-mode behavior

Pinned subtle surface uses dark `backgroundSurfaceSubtle`; unread badge stays pulse accent.

---

## Performance considerations

- `LazyColumn` + stable `key = id`
- `animateItem()` only on row modifier — avoid animating entire list state
- Row composables read `FlashTheme` once per recomposition scope

---

## Implementation notes

| File | Role |
|---|---|
| `FlashChatListModels.kt` | `FlashChatListItemUi`, `FlashChatListUiState`, samples |
| `FlashChatListRow.kt` | Row UI, swipe, selection, animations |
| `FlashChatListScreen.kt` | Top bar + LazyColumn |
| `FlashChatListTopBar.kt` | Title + search |
| `FlashChatRepository.kt` | `chatListState`, `openConversation`, `closeConversation` |
| `FlashDimensions.kt` | `chatListRowHeight` |

---

## Testing checklist

- [x] Compose preview — list light/dark, typing row, unread, pinned
- [x] `./gradlew.bat testDebugUnitTest assembleDebug`
- [x] Device — chat list as app entry, tap opens conversation
- [ ] RTL mirror (UI-034)
- [x] Reduced motion — instant crossfades via `FlashMotion`
- [ ] Performance stress — UI-043 with 500+ rows

---

## Known limitations

- Archive swipe fires callback only — no undo snackbar (UI-027)
- Selection mode UI bar minimal — full UI-007 spec deferred
- Context menu on row — UI-008
- Live LAN presence — UI-030 maps repository fields
- Global search — UI-024

---

## Future improvements

- UI-007: selection action bar, batch archive/delete
- UI-008: long-press context menu alternative
- UI-024: search field in top bar
- UI-030: transport/presence from real session
- **`animateItem()`** deferred — not available in current Compose classpath; re-enable when foundation lazy API confirmed

---

## What makes this Flash?

Rows use Pulse typography weight for unread, teal typing indicator, and P2P presence dot — not generic Material lists or Stream clones. Motion is short and functional; swipe and selection hooks exist without slowing scroll.
