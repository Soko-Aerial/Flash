# Chat Screen — Header & Assembly

**Status:** UI-004 **VERIFIED** · UI-021 / UI-022 **IMPLEMENTED** · UI-030 **IMPLEMENTED** (2026-08-21) · UI-031 **IMPLEMENTED** (2026-08-22)  
**Component ID:** UI-004 (this doc section), UI-021, UI-022, UI-030, UI-031 (future sections)  
**Last updated:** 2026-08-21  
**Code:** `FlashChatHeader.kt`, `FlashChatHeaderUiState`, `FlashMessageList.kt`, `FlashChatScrollMath`

> Other sections of this file will be filled when UI-030+ are researched.

---

## UI-004 — Chat header

### Component

`FlashChatHeader`, `FlashChatHeaderUiState`, `FlashPeerPresence`, `FlashNetworkTransport`

### Purpose

Conversation chrome: identity, presence, P2P transport hint, encryption affordance, and primary actions (back, calls, menu). Must not use Material `TopAppBar`.

### Research sources

| Source | Studied |
|---|---|
| Telegram Android | Center-weight title history; subtitle for member count; minimal chrome height |
| Signal Android | Avatar-left + title stack; trust/presence emphasis |
| WhatsApp Android | Call/video actions on 1:1; group vs direct header differences |
| [Material TopAppBar docs](https://developer.android.com/develop/ui/compose/components/app-bars) | **Rejected** as final UI per AGENTS.md §34 |
| Provisional `FlashChannelHeader` | Center title + avatar-right — replaced |

### Existing approaches studied

**A — Material `TopAppBar` / `CenterAlignedTopAppBar`**  
Generic M3 chrome; wrong identity. **Rejected.**

**B — Telegram-style center title, avatar as trailing action (provisional scaffold)**  
Familiar but avatar-on-right hides identity; weak P2P status placement. **Superseded.**

**C — Flash lead row: back · avatar · title stack · transport/encryption · actions (chosen)**  
Signal-like identity (avatar adjacent to title) + Flash P2P icons (Wi‑Fi / Wi‑Fi Direct / relay / lock) + optional 1:1 call buttons. **Selected.**

### What worked

- Avatar left of title — faster peer recognition
- Dedicated `FlashChatHeaderUiState` decoupled from LAN types
- `AnimatedContent` fade on status changes (typing ↔ online)
- 56dp header height + hairline divider from UI-001 tokens
- FlashIcons for all chrome (no Material glyphs)

### What did not work

- Static subtitle string on `FlashConversationUiState` — cannot represent typing/transport
- Avatar only on far right — easy to miss in 1:1
- Collapsing header without UI-037 motion tokens — deferred to UI-021

### Chosen approach

Custom `FlashChatHeader` composable with Flash-owned layout and presentation model. Status line animates on change; scroll-linked collapse deferred to UI-021.

### Why it was chosen

Meets UI-004 requirements, uses UI-001/UI-002 tokens, supports P2P-specific affordances without Stream/Material clones.

### Visual specification

| Element | Token / value |
|---|---|
| Height | `FlashDimensions.headerHeight` (56dp) |
| Background | `backgroundSurface` |
| Divider | `borderSubtle`, `borderHairline` |
| Title | `headingMedium`, `textPrimary`, 1 line ellipsize |
| Status | `metadataDefault`, `textSecondary` |
| Typing | `metadataEmphasis`, `accentPrimary`, "typing…" |
| Online dot | 8dp circle, `statusOnline` |
| Transport icon | `iconSm` (16dp), `textTertiary` |
| Encryption | `FlashIcons.Encryption`, `iconSm` |
| Touch targets | `minTouchTarget` 48dp |

**Layout (LTR):**  
`[Back] [Avatar 36dp] [Title + status (weight=1)] [Call?] [Video?] [Menu]`

### Interaction specification

| Control | Action |
|---|---|
| Back | `onBack` — navigate up |
| Avatar | `onAvatarClick` — peer / group details |
| Call / Video | `onCallClick` / `onVideoCallClick` — shown when `showCallActions && !isGroup` |
| Menu | `onMenuClick` — conversation menu (UI-008 wires sheet) |

### Animation specification

- Status text: `AnimatedContent` + `FlashTheme.motion.statusCrossfade()` (120 ms fast tier)
- Collapsing on scroll: **deferred UI-021**
- Avatar hero transition: **deferred UI-028**

### Gesture specification

- Standard tap targets only; no header gestures this phase

### Accessibility requirements

- All icon buttons have `contentDescription`
- Online dot has semantics description "Online"
- Title + status readable at large font (sp scales)
- Typing state announced via status text content

### Responsive behavior

- Title/status ellipsize on narrow widths
- Call buttons hidden for group chats (`isGroup`)
- Tablet: same layout (UI-034 may add dual-pane)

### Dark-mode behavior

- Semantic colors from `FlashTheme` — no separate header palette

### Performance considerations

- Header state is `@Immutable` data class — stable for recomposition
- `AnimatedContent` keyed by status string — minimal animation scope

### Implementation notes

```
ui/chat/
├── FlashChatHeader.kt
└── FlashConversationModels.kt  — FlashChatHeaderUiState
```

Provisional `FlashChannelHeader` **removed**.  
Repository maps LAN session → `FlashChatHeaderUiState` later (UI-030); not in UI-004 scope.

### Testing checklist

- [x] Compose preview — group, direct, typing, dark
- [x] `./gradlew.bat testDebugUnitTest assembleDebug`
- [x] Device — conversation screen header visible
- [ ] RTL mirror pass (UI-034)
- [x] Reduced motion — instant status swap via `FlashMotion(reduceMotion = true)`

### Known limitations

- Menu/call callbacks are no-ops in sample screen
- No scroll-linked collapse (UI-021)
- Transport icons generic until UI-030 device QA
- Group header variant full spec in UI-028

### Future improvements

- UI-021: collapsible header on list scroll
- UI-030/031: live transport + encryption from session
- UI-014: typing indicator animation in status line

### What makes this Flash?

Flash puts **peer identity and P2P context in one calm row** — avatar beside the name, a live transport glyph (LAN / Wi‑Fi Direct / relay), and an encryption lock, not a generic Material app bar. Status transitions (online → typing → connecting) crossfade in place. Call actions appear only for 1:1 direct chats. The header reads as Flash infrastructure for local-first messaging, not a template app bar.

---

## UI-021 — Chat scrolling

**Status:** DESIGNED → IMPLEMENTED (2026-08-21)

### Component

`FlashMessageList` scroll engine + `FlashChatScrollMath` — reverse-layout `LazyColumn` behavior for new-message arrivals, bottom pinning, unseen tracking, keyboard resize, and jump actions.

### Research sources

- Android Developers — `LazyColumn` / `reverseLayout` semantics, `LazyListState` (`firstVisibleItemIndex/ScrollOffset`, `animateScrollToItem`), `derivedStateOf`, stable `key`s.
- Reference app behavior: WhatsApp (auto-scroll on own send; "N new messages" pill when scrolled up), Telegram (in-place jump pill with unread count), Signal (scroll-to-bottom chevron).

### Existing approaches studied

1. **Always auto-scroll** (naive) — rips the reader out of history. **Rejected.**
2. **Auto-scroll only at bottom; silent otherwise** (previous Flash v1) — user misses arrivals while reading. **Superseded.**
3. **At-bottom auto-scroll + unseen-count pill with jump** (WhatsApp/Telegram consensus) — **Selected.**

### Behavior specification

| Event | Behavior |
|---|---|
| New message, user at bottom (≤48dp from end) | Auto-scroll to latest (animated; snap under reduce-motion) |
| New message of my own | Always auto-scroll (matches WhatsApp) |
| New message while scrolled up | No scroll — unseen counter increments; pill appears above composer |
| Reaction/edit on existing message | Tail id unchanged → not an arrival; no counter change |
| User returns to bottom | Counter resets to 0, pill hides |
| Image decode changes item size | `reverseLayout` pins item 0 to the bottom — at-bottom users stay pinned |
| Keyboard open/close | Composer `imePadding()` resizes; list retains scroll position |
| Reply quote tap | `animateScrollToItem` to target + pulse highlight (UI-010) |
| History load / pagination | Not implemented — repository has no paging yet (known limitation) |

### Pure logic (`FlashChatScrollMath`, unit-tested)

- `nextUnseenCount(current, isNewTailMessage, wasAtBottom, isMine)` — resets when at bottom or on own send (which auto-scrolls), increments on peer arrivals while scrolled up.
- `isNewTailMessage(previousLastId, currentLastId)` — tail-id change detection.
- `pillLabel(count)` — `"1 new message"` / `"3 new messages"`.
- `shouldShowNewMessagesPill(count)`.

Existing helpers retained: `isAtBottom`, `shouldAutoScrollToNewMessage`, `shouldAnimateMessageEnter`.

### Visual specification

Pill: accent-primary surface, radiusFull, 8dp/16dp padding, white text (`metadataEmphasis`), leading down-chevron (Flash-owned `flash_ic_back` rotated −90° — no new icon), floating bottom-center above composer with 16dp clearance. 48dp min height touch target.

### Animation specification

Pill enter/exit: fade + slide-up quarter-height via `motion.tweenNormalSpec()`; jump scroll uses list default `animateScrollToItem`; reduce-motion collapses both.

### Accessibility requirements

- Pill: Role.Button, contentDescription `"Jump to N new messages"`.
- Auto-scroll never traps TalkBack focus (standard list semantics preserved).

### Known limitations

- No history pagination yet (single in-memory list).
- Pill does not preview sender/text (Telegram shows snippet) — follow-up.

---

## UI-022 — Jump to latest

**Status:** DESIGNED → IMPLEMENTED (2026-08-21, together with UI-021)

The UI-021 unseen-count pill doubles as the jump-to-latest affordance: tapping it animates to the newest message and clears the counter. A separate always-visible chevron button (shown whenever scrolled up, even with zero unseen) is deferred — current apps show it contextually and the pill covers the arrival case; revisit after device QA.

## UI-030 — Device / network status UI

**Status:** DESIGNED → IMPLEMENTED (2026-08-21)

### Component

`FlashConnectionBanner`, `FlashTransportBadge`, `FlashConnectionHealth`, `FlashNetworkStatusMath`, `FlashNetworkBannerSeverity` — file: `ui/chat/.../FlashNetworkStatusUi.kt`.

### Purpose

Surface the P2P connection state inside the conversation screen **without blocking content**: a compact strip under the header appears only when the link is not fully Connected, plus an always-visible transport chip for ambient context. UI-031 (encryption indicators) remains a separate component and section.

### Research sources

| Source | Takeaway applied |
|---|---|
| web.dev offline UX guidelines | Neutral color for environmental conditions vs red reserved for failure; never block content behind a modal |
| Android Developers offline-first LCE architecture guide | Model connectivity as explicit LCE-style state (`FlashConnectionHealth`), retries belong to the engine, not the UI |
| Coder Legion offline-handling piece | Three distinct visual responses — normal / slow(degraded) / fully-offline — instead of one generic "no internet" |
| `docs/ui/error-states.md` + `FlashErrorSeverity` | Severity language precedent: environmental = calm neutrals; red (`textError`) only for faults |

### Existing approaches studied & rejected

1. **Blocking modal dialog on connection loss** — traps users on unstable P2P networks where peers flap constantly; web.dev anti-pattern. **Rejected.**
2. **Red error styling for offline** — offline is a condition, not a fault; red would cry wolf and desensitize users to real failures (error-states.md severity split). **Rejected.**
3. **Inline banner strip + ambient chip** (chosen) — informs without interrupting; retry offered exactly when sending is actually blocked. **Selected.**

### Chosen approach & why

Pure logic in `FlashNetworkStatusMath` derives `FlashConnectionHealth` from existing core enums (`FlashNetworkTransport`, `FlashPeerPresence`, discovery peer count). The banner is caller-gated (integrator wraps in `AnimatedVisibility` keyed on `health != FlashConnectionHealth.Connected`) so the composable stays static and testable; the badge is always visible.

### Behavior matrix (health × presentation)

| Health | Trigger (precedence order) | Banner | Tone | Retry pill |
|---|---|---|---|---|
| `Offline` | Unknown transport + 0 peers; or peer Offline / 0 peers | Shown | Attention (spark tint 15%, `textPrimary`) | Yes ("Retry connection") |
| `Connecting` | peer presence == Connecting | Shown | Calm (surface-subtle bg, `textSecondary`) | No |
| `Degraded` | Relay transport; else-branch fallbacks | Shown | Calm | No |
| `Connected` | Lan/WifiDirect + Online presence | Hidden (caller gates) | — | No |

Copy: `healthLabel` → `"Connected · LAN"` / `"Connected · Wi-Fi Direct"` / `"Relayed"` / `"Connecting…"` / `"Searching for devices…"` (offline reads as active searching — P2P peers come and go, not an error).

### Visual specification

| Element | Token / value |
|---|---|
| Banner min height | 36dp, full width under header |
| Banner bg (Calm) | `backgroundSurfaceSubtle` |
| Banner bg (Attention) | `colors.accentSecondary.copy(alpha = 0.15f)` (spark tone) |
| Content color | Calm → `textSecondary`; Attention → `textPrimary`; **never `textError`** |
| Leading icon | `iconSm` — Calm: `FlashIcons.Connection`; Attention: `FlashIcons.Device` |
| Label | `metadataDefault`, weight(1f) |
| Retry pill | `accentPrimary` bg, `metadataEmphasis` + `textOnAccent`, min height 32dp, `FlashShapes.chip` |
| Badge | chip shape, hairline `borderSubtle` border, transparent bg, `iconSm` + short label, `metadataDefault` `textSecondary` |

### Accessibility requirements

- Banner merged semantics announce the health label; state changes reach TalkBack without focus movement.
- Retry pill: `Role.Button` with description `"Retry connection"`.
- Badge merged contentDescription `"Connection: <label>"`.
- No color-only meaning: icon + text carry the state (design-system.md rule).
- Static banner — no animations, so reduce-motion is inherently satisfied.

### Known limitations

- Banner visibility wiring happens at screen level (`AnimatedVisibility` around `health != Connected`) — `FlashConversationScreen` integration pending.
- Auto-retry/backoff deferred to engine layer per Android offline-first guidance; v1 wires manual retry callback only.
- Multi-hop relay depth ("through 2 nearby devices") from master plan deferred until engine exposes hop count.

### Testing checklist

- [x] Unit tests — `FlashNetworkStatusLogicTest`: resolveHealth branch table, labels, blocking states, severity mapping, icon mapping
- [ ] Compose previews on device — connected-LAN, relayed, connecting, offline+retry, dark offline
- [ ] Screen-level integration (banner show/hide transitions)
- [ ] TalkBack pass on banner announcement + retry
- [ ] Reduced-motion check (trivially passes — static)

### Implementation notes

```
ui/chat/
├── FlashNetworkStatusUi.kt      — health enum, math object, banner, badge
└── src/test/.../FlashNetworkStatusLogicTest.kt
```

Banner visibility contract:

```kotlin
AnimatedVisibility(visible = health != FlashConnectionHealth.Connected) {
    FlashConnectionBanner(health = health, onRetry = engine::retryConnection)
}
```

## UI-030b — Presence states: Connected / Online / Offline (PC3)

**Status:** DESIGNED → IMPLEMENTED (2026-09-28; not device-verified, P8). Plan: `docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.1, owner decision P3.

### Component
`FlashPresenceDot` (`ui/chat/.../FlashPresenceDot.kt`), used by the chat-list avatar, the chat header status row and
the peer-details sheet. It is driven by `FlashPeerPresence`, which gains `Reachable`.

### Purpose
Before PC3 the UI had one positive state: "Online" meant *I hold a live session with this peer*. A peer that
discovery could see but that had no session yet showed as Offline, even though a send would reach it within a
second. The owner chose three states (P3):

| State | Meaning | `FlashPeerPresence` | Dot | Header / sheet label |
|---|---|---|---|---|
| **Connected** | I hold a live session myself | `Online` (unchanged name, see below) | Solid `statusOnline` | "Connected" |
| **Online** | No session, but discovery sees the peer now | `Reachable` (new) | Ring: 1.5 dp `statusOnline` stroke on the surface colour | "Online" |
| **Offline** | Neither | `Offline` | None | "Offline" |

`Connecting` (the ERROR-031 reconnect grace) and `Typing` are unchanged and outrank `Reachable`.

### Research sources
- Slack presence: a solid green dot for active and a hollow circle for away, both driven by client heartbeats
  (idlepilot.com "The Complete Guide to Slack Presence", *reported*, checked 2026-09-28). This is the widest-known
  "filled = live, ring = there but not live" convention.
- Existing Flash tokens: `statusOnline`, and the avatar surface ring already used by the list dot (UI-003 / UI-004).
- UI-030 (above): environmental states are calm, never red.

### Existing approaches studied
1. **Colour split** (green = connected, amber = online). Rejected: amber reads as a warning and colour alone fails
   WCAG 1.4.1 (use of colour).
2. **Label only, same dot.** Rejected: the list has no status text, so the difference would be invisible there.
3. **Solid vs ring, same colour (chosen).** The shape carries the meaning, so it survives colour-blindness and
   greyscale. It matches the Slack convention users already know.

### Chosen approach & why
Solid vs ring. One composable draws both, so the list, header and sheet cannot drift. The enum keeps `Online` as the
connected state instead of renaming it: `FlashPeerPresence` is published API (`core:common`), and renaming it would
break consumers. A silent swap of meanings would be worse still, because every missed call site would compile
unchanged. The **labels** follow the owner's names.

### Visual specification
- List avatar: 12 dp badge, 2 dp surface ring (unchanged geometry). Connected = filled centre; Online = the centre is
  the surface colour with a 1.5 dp `statusOnline` stroke.
- Header: 8 dp dot before the status label. The same fill/ring rule applies, with a 1.5 dp stroke.
- Peer details sheet: its existing dot, same rule.
- Groups: unchanged (member counts are Connected members); presence sharing arrives in PC4.

### Interaction specification
No new interaction. Sending to an Online (ring) peer dials on demand: the host's transport sink asks
`AutoConnector.ensureSession` for up to 1 s, then sends. If that fails, the message waits in the outbox as today.

### Animation specification
None. The dot swaps instantly. The header status text keeps its existing crossfade (UI-004).

### Gesture specification
None.

### Accessibility requirements
- Semantics: "Connected" (solid), "Online" (ring); no node for Offline. Groups keep their counts.
- Shape, not colour, distinguishes the states (WCAG 1.4.1). The 1.5 dp stroke at 8 dp stays visible at 200 % font
  scale because the dot does not scale with text.

### Responsive behavior
Unchanged; the dot has a fixed size in every layout.

### Dark-mode behavior
The ring is drawn on `backgroundSurface`, so it inverts with the theme. `statusOnline` already has light and dark
values (`success500` / `success400`).

### Performance considerations
The dot is a single `Box` with a `border`, with no extra layers or animation. `Reachable` comes from
`discoveredEndpoints`, which is already collected. It joins the existing presence combine, so it adds no new
collector per row.

### Implementation notes
- `RealFlashChatRepository` takes `reachablePeerIds: Flow<Set<String>>` (hosts pass discovery's current device ids).
  `PresenceSnapshot.reachable` = discovered − live − connecting.
- `FlashNetworkStatusMath.resolveHealth`: `Reachable` → new `FlashConnectionHealth.Reachable`, labelled
  "Online · connects when you send". Calm and non-blocking.
- The desktop shell's own resolver (`DesktopShell` `directChatHeader…`) maps a discovered peer without a session to
  `Reachable`, instead of the `Online` it claimed before.

### Testing checklist
- [x] Repository: discovered-without-session → `Reachable`; session wins; the Connecting grace wins over Reachable.
- [x] `resolveHealth` / labels for `Reachable`.
- [x] Dial on demand: `AutoConnectorTest` (urgent dial within budget; one shared wait per peer; Offline returns at once).
- [ ] Device: ring appears for a discovered peer before its session lands; send to it delivers (P8).

### Known limitations
- In STANDARD the ring is short-lived, because the auto-connector dials every discovered peer. It matters more in
  ECO (PC5) and with shared presence (PC4).
- Transfers and calls still require a session; they do not dial on demand yet.

### Future improvements
PC4: rings for peers reported by mutual contacts ("Online via Alex"), with hop and age in the details sheet.

### What makes this Flash?
It is honest about the P2P link. "Connected" promises a live socket, "Online" promises that a send will try to reach
the peer now, and nothing is shown that the transport cannot back up.

## UI-031 — Encryption indicators

**Status:** DESIGNED → IMPLEMENTED (2026-08-22)

### Component

`FlashEncryptionBadge`, `FlashEncryptionSheet`, `FlashEncryptionBadgeState`, `FlashEncryptionMath` — file: `ui/chat/.../FlashEncryptionIndicators.kt`.

### Purpose

Give the conversation an honest, plain-language **encryption trust surface**: a compact chip near the header/composer showing whether the channel is encrypted and verified, plus a tap-for-details bottom sheet explaining what encryption protects in Flash P2P (local network, no cloud) with verification entry points. Complements the small static lock glyph already rendered by `FlashChatHeader` when `state.isEncrypted`. Unobtrusive; expandable explanation; no crypto overload. Separate from UI-030's transport status.

### Research sources (online, 2026-08-22)

| Source | Takeaway applied |
|---|---|
| [Apple — iMessage Contact Key Verification](https://security.apple.com/blog/imessage-contact-key-verification/) | "Notify users only when an unexpected security condition occurs" — positive indicators stay ambient; warnings rare and accurate |
| [Apple Support — Contact Key Verification alerts](https://support.apple.com/en-us/118247) / [about page](https://support.apple.com/en-us/118246) | Checkmark-by-name as persistent verified signal; verification codes compared in person; Conversation Details hosts the trust entry point |
| [WhatsApp FAQ — end-to-end encryption](https://faq.whatsapp.com/820124435853543/?locale=en_US) | "Tap Encryption on contact info" → QR/60-digit security codes; plain-language copy ("no one outside of this chat, not even WhatsApp") |
| [USENIX SOUPS 2017 — authentication ceremony usability study](https://www.usenix.org/system/files/conference/soups2017/soups2017-vaziripour.pdf) | Users cannot find or complete verification without instruction → badge must be a discoverable entry point to verification, not buried in settings |
| [Signal support — safety numbers](https://support.signal.org/hc/en-us/articles/360007060632-What-is-a-safety-number-and-why-do-I-see-that-it-changed) | Verified checkmark in chat header persists until key change; verification is optional but recommended |
| [PoPETs 2025 — user perceptions of key transparency in WhatsApp](https://petsymposium.org/popets/2025/popets-2025-0170.pdf) | E2EE notices breed misconceptions when unexplained ("does anything change?"); explain *what* protection does, automate checks, surface warnings only when needed |
| [Telegram tech FAQ — secret chats](https://core.telegram.org/techfaq) | Secret-chat lock icon + key visualization (identicon/emoji grid) as the compare-in-person artifact |

### Existing approaches studied & rejected

1. **Persistent yellow "messages are end-to-end encrypted" system banner in every new chat** (WhatsApp pattern) — becomes wallpaper users ignore and misread (PoPETs 2025 documented users believing encryption must be manually enabled). **Rejected** for Flash: one tappable chip instead of an always-on notice.
2. **Red/warning styling for "Unverified"** — unverified is a missing *optional* confirmation, not a fault; red would cry wolf and violates error-states.md severity language (environmental ≠ fault). **Rejected.**
3. **Crypto jargon panel (fingerprints, keys, DH)** — PoPETs/SOUPS both show jargon blocks comprehension. **Rejected:** plain language only, technical artifacts deferred behind placeholder rows.

### Chosen approach & why

Three pieces in one file:

1. **`FlashEncryptionBadge(state, onClick)`** — compact chip (FlashIcons.Encryption + short label) matching `FlashTransportBadge` visual language. `EncryptedTrusted` → accent-tinted lock + "Encrypted"; `EncryptedUnverified` → neutral lock + "Unverified"; `None` renders nothing (absence is the honest signal). Tap opens the sheet — making the badge the *discoverable* verification entry point the SOUPS study found missing elsewhere.
2. **`FlashEncryptionSheet(state, onDismiss)`** — bottom sheet styled per `FlashAttachmentSheet` conventions (radius24 top corners, manual drag handle, navigationBars insets): title, plain-language explainer lines (what encryption protects: device-side encryption, local-network direct delivery, no cloud servers), hairline divider, then two **disabled-with-explanation** placeholder rows ("Verify security codes", "View device fingerprint", tagged "Soon") until engine pairing support lands (UI-032) — explained beats hidden (error-states.md).
3. **`FlashEncryptionMath`** — pure logic (`badgeState`, `badgeLabel`, `sheetTitle`, `sheetExplainerLines`, `verificationEntries`) unit-tested without instrumentation.

### State matrix

| Badge state | Trigger | Chip | Tone |
|---|---|---|---|
| `EncryptedTrusted` | `isEncrypted && isVerified` | Lock + "Encrypted" | Positive cue: `accentPrimary` icon, neutral text |
| `EncryptedUnverified` | `isEncrypted && !isVerified` | Lock + "Unverified" | Calm neutrals — **never `textError`** |
| `None` | `!isEncrypted` | Renders nothing | Sheet (if somehow opened) explains encryption not yet established |

### Visual specification

| Element | Token / value |
|---|---|
| Badge | `FlashShapes.chip`, hairline `borderSubtle` border, transparent bg, padding 8dp/4dp, `iconSm` + label `metadataDefault` `textSecondary` |
| Badge semantics | Merged `Role.Button`, description `"<label>. Tap for details"` |
| Sheet container | `backgroundSurface`, top radius 24, manual drag handle (36×4dp circle `borderSubtle`), navigationBars insets |
| Sheet title | `headingSmall` + 48dp Close icon button (`FlashIcons.Close`) |
| Explainer lines | `bodyDefault`, `textSecondary`, 12dp spacing |
| Placeholder rows | `backgroundSurfaceSubtle`, chip shape, min height 48dp, leading `iconMd` (`Check` / `Device`), title `metadataEmphasis`, explanation `metadataDefault` `textTertiary`, alpha 0.7, "Soon" tag |

### Accessibility requirements

- Badge merged contentDescription announces label + action ("Tap for details"); not color-only (label carries the distinction).
- Placeholder rows are non-clickable but fully described ("Coming after device pairing support") via merged semantics.
- Close button has explicit description; sheet dismissible via swipe/backdrop too.
- All text scales via sp tokens.

### Known limitations

- `isVerified` has no engine source yet — integrator passes `false`; placeholder rows are intentionally disabled until UI-032 pairing lands.
- No inline "key changed" warning state yet (Signal/iMessage alert precedent) — requires engine key-history events; tracked for follow-up.
- Sheet does not yet deep-link to a real verification flow.

### Testing checklist

- [x] Unit tests — `FlashEncryptionLogicTest`: full boolean truth table, no-encryption-wins precedence, labels/titles, explainer-line non-blank guard, P2P scope copy assertions, entry-point inventory
- [x] Compose previews — badge trusted/unverified/dark, sheet light/dark
- [ ] Device QA — TalkBack pass on badge + sheet
- [ ] Integration wiring in `FlashConversationScreen` (lead engineer)

### Implementation notes

```
ui/chat/
├── FlashEncryptionIndicators.kt   — badge state enum, math object, badge, sheet
└── src/test/.../FlashEncryptionLogicTest.kt
```

Badge tap contract (lead wires):

```kotlin
var showEncryptionSheet by remember { mutableStateOf(false) }
FlashEncryptionBadge(
    state = FlashEncryptionMath.badgeState(
        isEncrypted = header.isEncrypted,
        isVerified = false, // until UI-032 pairing exposes verification
    ),
    onClick = { showEncryptionSheet = true },
)
if (showEncryptionSheet) {
    FlashEncryptionSheet(state = …, onDismiss = { showEncryptionSheet = false })
}
```
