# Delivery / Read States — UI-015

**Status:** IMPLEMENTED  
**Component ID:** UI-015  
**Last updated:** 2026-09-09
**Owner phase:** Premium Chat UI  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

`FlashDeliveryStatusIcon` (animated delivery state glyph), `FlashDeliveryStatusRow` (timestamp + status layout), and `FlashMessageStatus` data model integration.

## Purpose

Provides unambiguous, real-time visual feedback for outgoing message transit lifecycle across LAN, Wi-Fi Direct, and future relay connections:
1. **Sending (Pending)** — Rotating / pulsing mini clock glyph indicating outgoing buffer queue.
2. **Sent** — Single sharp vector tick indicating message dispatched over local socket.
3. **Delivered** — Double vector tick indicating peer device ACK received.
4. **Read** — Double vector tick illuminated in `colors.accentPrimary` (Flash Teal Pulse) with smooth color morph.
5. **Failed** — Red warning / retry indicator with tap-to-resend action.
6. **Group quorum progress** — outbound group messages with persisted per-member rows show a concise `M/N` beside the delivery glyph; absent rows show no invented progress.
7. **Accessibility** — Explicit TalkBack state announcements plus "Delivered to M of N members" for group progress.

---

## Research sources

1. **WhatsApp Android / iOS** — Clock (unsent) $\to$ Single Grey Check (sent to server) $\to$ Double Grey Check (delivered to phone) $\to$ Double Blue Check (read).
2. **Signal Android / iOS** — Dotted circle (sending) $\to$ Single Check with circle (sent) $\to$ Double Filled Check (delivered) $\to$ Double Solid Check (read).
3. **Telegram Android / iOS** — Clock (sending) $\to$ Single Check (sent & delivered) $\to$ Double Check (read).
4. **iMessage (iOS 17/18)** — Textual status below bubble ("Delivered", "Read 10:42 AM") with fade transition.
5. **Discord Mobile** — Muted opacity on sending $\to$ normal opacity $\to$ red text + retry icon on error.
6. **Flash Architecture (ADR-004 & ADR-008)** — Local-first P2P direct ACK semantics: Sent = socket written; Delivered = peer ACK; Read = peer viewport focus ACK.

---

## Existing approaches studied

### Approach A: 4-Stage Checkmark Iconography (WhatsApp / Signal)
- Clock $\to$ Single Check $\to$ Double Check $\to$ Accent Double Check.
- **Strength:** Universally understood standard in mobile messaging; compact; fits in timestamp row.
- **Weakness:** Generic Material icons look dated without Flash's custom geometric styling.

### Approach B: Text Labels Below Bubble (iMessage)
- Explicit textual words: "Sending...", "Delivered", "Read".
- **Strength:** Highly descriptive.
- **Weakness:** Wastes vertical space; clutters rapid back-and-forth chat streams.

### Approach C: Bubble Opacity Dimming (Discord / Web clients)
- Dims whole bubble to 50% until delivered.
- **Strength:** Simple.
- **Weakness:** Unclear distinction between sent vs delivered vs read.

---

## What worked

| Pattern | Source | Why it works |
|---|---|---|
| 5-Stage Iconography (Clock $\to$ Single Tick $\to$ Double Tick $\to$ Teal Double Tick $\to$ Red Retry) | WhatsApp, Signal | Clear progression matching P2P packet delivery phases |
| Inline Placement in Timestamp Row | WhatsApp, Telegram | Zero vertical overhead; tightly coupled to message time metadata |
| Color Transition to `accentPrimary` on Read | Flash Design System | High visual distinction without intrusive badges |
| Animated Scale / Morph on State Change | Flash Motion System | Micro-spring transition ($0.8f \to 1.0f$) gives physical feedback when ACK arrives |
| 1-Tap Retry on Failed State | Signal, Telegram | Instant recovery without needing long-press context menus |

## What did not work

| Pattern | Source | Why rejected |
|---|---|---|
| Text labels beneath every bubble | iMessage | Adds 20dp vertical height per message; causes layout jumping |
| Spinning circular progress bar | Generic Material | Too heavy for inline timestamp row; looks like slow buffering |
| Modal error alert dialog on send failure | Bad UX | Disrupts messaging flow; inline retry is far superior |

---

## Chosen approach

**Flash Custom Geometric Delivery Status (`FlashDeliveryStatusIcon`):**

1. **Glyph Hierarchy**:
   - `Pending / Sending`: Clock icon (`FlashIcons.Clock` / animated pulsing clock).
   - `Sent`: Single tick (`FlashIcons.Check`).
   - `Delivered`: Double tick (`FlashIcons.Delivered`).
   - `Read`: Double tick (`FlashIcons.Read`) tinted in `FlashColors.accentPrimary` (Teal Pulse).
   - `Failed`: Error / retry glyph (`FlashIcons.Failed` / `FlashIcons.Retry`) tinted in `FlashColors.textError`.
2. **Animation Spec**:
   - State transition: `AnimatedContent` with `motion.statusCrossfade()` and scale pop ($0.75f \to 1.0f$).
   - Read tint transition: `animateColorAsState` smoothly transitioning from timestamp grey to `accentPrimary` over $180\text{ms}$.
3. **Interactive Retry**:
   - When status is `Failed`, clicking the icon triggers `onRetry()`.

---

## Visual specification

| Status | Icon Glyph | Color | Size | Accessibility Label |
|---|---|---|---|---|
| **Pending / Sending** | Clock glyph | `colors.chatTextTimestampOutgoing` | $13\text{dp}$ | "Sending message..." |
| **Sent** | Single tick | `colors.chatTextTimestampOutgoing` | $13\text{dp}$ | "Sent" |
| **Delivered** | Double tick | `colors.chatTextTimestampOutgoing` | $14\text{dp}$ | "Delivered" |
| **Read** | Double tick | `colors.accentPrimary` (Teal Pulse) | $14\text{dp}$ | "Read by recipient" |
| **Failed** | Warning / Retry glyph | `colors.textError` (Red) | $14\text{dp}$ | "Failed to send. Double-tap to retry." |

---

## Interaction specification

| State | Interaction |
|---|---|
| Pending / Sent / Delivered / Read | Read-only visual indicator |
| Failed | Clickable button with haptic feedback that invokes `onRetry()` callback |

---

## Accessibility requirements

- **TalkBack Semantics**:
  - `contentDescription` dynamically describes exact state.
  - When `Failed`, adds `role = Role.Button` and "Double-tap to retry sending".
- **Colorblind Support**:
  - `Read` differs from `Delivered` in both color and vector weight/geometry where appropriate.
  - `Failed` features a distinct exclamation/alert geometry.

---

## Implementation notes

### New Files in `:ui:chat` (`com.transfer.flash.ui.chat`)
- `FlashDeliveryStatusIcon.kt` — animated delivery status icon composable with retry support.

### Modified Files in `:core:messaging`
  - `FlashMessagingModels.kt` — additive `deliveredTo: Int?` / `deliveredTotal: Int?` fields for F5.4 group quorum progress.

### Modified Files in `:ui:chat`
- `FlashMessageBubble.kt` — wires `FlashDeliveryStatusIcon` and optional group `M/N` progress into `FlashMessageTimestampRow`.
- `FlashConversationScreen.kt` — supports delivery status retry callbacks.

### F5.4 persistence/repository extension
- `GroupDeliveryDao.observeDeliveryCounts(conversationId, selfId)` is a query-only observable aggregate.
- `RealFlashChatRepository` subscribes only for a stored group conversation and maps counts only to local outbound rows.
- The nullable UI fields intentionally remain null when media-group paths have no `group_deliveries` rows.
- A valid aggregate renders `M/N` with `Delivered to M of N members` semantics; invalid or absent aggregates render nothing.

---

## Testing checklist

- [ ] Compose preview — all 5 states (Pending, Sent, Delivered, Read, Failed) in Light & Dark modes
- [ ] Transition test — animate from Pending $\to$ Sent $\to$ Delivered $\to$ Read
- [ ] Failure test — tap Failed icon triggers retry callback
- [ ] TalkBack — validates accessible state descriptions for each stage
- [ ] Unit tests — verify status mappings and retry logic

---

## What makes this Flash?

Flash's delivery status mirrors its high-speed P2P engine. Clean, sharp vector checkmarks animate with snappy spring physics, glowing with the signature Flash Teal Pulse the instant peer ACKs cross the local Wi-Fi / LAN connection.
