# App essentials audit — chat + transfer apps (2026-09-08)

Phase E of `docs/group/ui-phase-plan.md`. Code-based audit of what every mainstream chat and
transfer app ships, verified against actual source (file refs included). Status legend:
**exists** (verified wired), **partial** (exists with a real gap), **missing** (not found in
code). The prioritized implementation list is at the bottom — each item is a small, separate
phase.

## Messaging essentials

| Essential | Status | Evidence |
|---|---|---|
| Send/receive text, idempotent dedup, durable outbox | exists | `RealFlashChatRepository` (outbox drain, receipt-as-commit ERROR-031) |
| Delivery/read ticks | exists | C6.3, `MessageDao.markReadUpTo`, `FlashDeliveryStatusIcon` |
| Typing indicator (1:1) | exists | `setTyping`, `typingFlow`, `FlashTypingIndicator` |
| Typing indicator (groups) | **partial** | `setTyping` sends `TypingFrame` to `conversationId` — for a group that's the groupId, which has no session, so it drops silently (`RealFlashChatRepository.kt` ~1590). Inbound per-member group typing likewise not surfaced. |
| Copy message text | exists | `FlashMessageContextMenu` `onCopy` + `copyToClipboard` (`FlashConversationScreen.kt:835`) |
| Reply / quote | exists | `sendReply`, `FlashQuotedReplyUi` |
| Reactions | exists | `toggleReaction`, `applyReactionDelta`, reactor sets |
| Forward | **partial** | System share only (`onShareText`); in-app conversation picker deliberately absent (documented in `FlashConversationScreen`) |
| Select multiple / batch delete | exists | UI-007/013, `deleteMessages`, `deleteConversations` |
| Delete message | **partial** | Local tombstone only (`markDeleted`); no delete-for-everyone wire frame |
| **Date separators** | **missing** | No `DateSeparator`/`isSameDay` anywhere in `ui/` or `core/`; bubbles carry per-message timestamps only. Every mainstream chat app groups by day. |
| Mark as unread | **missing** | No `markUnread` in `ui/`, `core/`, or `app/` |
| Message search (in-chat + global + body) | exists | UI-023/024, `searchMessages`, `searchMessageBodies` |
| Drafts | exists | `saveDraft`, restored via `draftText` |
| Unread badges | exists | `observeUnreadCounts`, `lastReadCursor` |
| Empty / loading / error states | exists | ERROR-034, `hasLoaded`, three-state tabs |
| Notifications (per-thread, suppressed while open, tap-to-open) | **partial** | Bug 7 wiring is solid, but for a **group** message the notification title is the sender name, not the group name (`onInboundTextMessage(groupId, senderName, text)` → `FlashNotificationManager.showMessage` titles by sender) |
| Read receipts for groups (per-reader) | **partial** | `GroupWireFrame.Read` ingested; per-reader cursors persisted; no UI aggregation yet ("Seen by…") |
| Group delivery quorum ("delivered to M of N") | **partial** | `group_deliveries` rows persist per-member state (Phase 1A); UI doesn't render it yet |
| Group voice/video calls | missing (planned) | `docs/group/phase-2-group-voice.md`; buttons honestly hidden since group Phase A |
| Voice messages (record + playback) | exists | UI-019/020, B9 |
| Media viewer / image grids / file cards | exists | UI-016/017/018 |
| Encryption/trust indication | exists | UI-031 badge + sheets; call trust gate (ADR-030) |

## Transfer essentials

| Essential | Status | Evidence |
|---|---|---|
| Chunked transfer + integrity (SHA-256) | exists | `Chunker`, `IncrementalSha256`, whole-file verify |
| Pause / resume / cancel / retry per item | exists | `FlashTransfersScreen` callbacks → repository actions |
| Resume across process death + roams | exists | ADR-021, bit-vector done-sets, `TransferReconnectResumePolicy` (ERROR-035) |
| Progress / speed / ETA | exists | throttled to displayable cadence (EXP-010/011) |
| Transfer history | exists | Active/Failed/History sections |
| Accept/decline offers in-bubble + auto-download per MIME | exists | Bug 3, `onAcceptOffer`/`onDeclineOffer` |
| Open/share/save received files | exists | `WSFileActions`, MediaStore save, SAF |
| Background transfers (FGS, battery exemption, Doze) | exists | Bug 6 stack, EXP-002 |
| Multi-peer mesh + discovery (NSD, hotspot, dual-homed) | exists | ERROR-023/031/035 line |
| Storage usage / cache management screen | **missing** | No settings surface for received-files footprint; thumbnail trim exists internally (EXP-014) but nothing user-facing |

## Systemic essentials

| Essential | Status | Evidence |
|---|---|---|
| Dark mode | exists | `FlashTheme(darkTheme)`, tokens both ways |
| Dynamic color | exists | UI-036 |
| Reduce motion + performance tiers | exists | UI-038, ADR-028/029 (`FlashMotionPolicy` tier-as-floor) |
| Accessibility | exists | UI-038 doc + live-region discipline (ADR-026), semantics labels throughout |
| Haptics | exists | UI-039 `FlashFeedback` choke point |
| Display-name editing | exists | Settings `IdentityRow` + rename dialog (`showRenameDialog`) |
| Connectivity honesty (online/connecting/offline, banner retry) | exists | ERROR-031 three-state presence, `FlashConnectionBanner` |
| Profile page for a chat | exists | Peer details sheet (1:1) + group member sheet (groups) |
| Local encryption at rest | exists | SQLCipher + AndroidKeyStore passphrase (C1.7, D2) |

## Prioritized follow-ups (each its own small phase)

1. **Date separators** (missing; high user value, contained change): derive day boundaries in
   `RealFlashChatRepository`'s conversation mapping (sentAt → day label rows or
   `FlashMessageUi` day-header kind) + render in `FlashMessageList`. Pure logic testable.
2. **Group notification naming** (partial; ~10-line fix): pass the group title into
   `onInboundTextMessage` (repository knows the conversation row) so `showMessage` titles
   groups correctly with body "Sender: text".
3. **Group typing fan-out** (partial): `setTyping` on a group iterates active members via
   `GroupTransportSink`; inbound group `TypingFrame` merges into `typingStates[groupId]`
   (membership-gated like other group frames).
4. **"Delivered to M of N"** (partial): join `group_deliveries` counts into the outbound
   message mapping; render via the existing delivery-icon language.
5. **Group voice calls** (Phase 2 of `docs/group/`): per `phase-2-group-voice.md`.
6. **Mark as unread** (missing; low): long-press action setting `lastReadCursor` back.
7. **Delete-for-everyone wire** (partial; low): tombstone broadcast frame + receiver gate.
8. **Storage usage screen** (missing; low): received-files footprint + clear action in Settings.
