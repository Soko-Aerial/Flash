# Message Info — UI-051

**Status:** IMPLEMENTED 2026-09-30 (unit-tested and mutation-checked; **not device-verified**, see `CGS-06`; previews, dark mode, large font and RTL still open)
**Component ID:** UI-051
**Last updated:** 2026-09-30
**Owner phase:** Chat / group sync audit (`docs/audit/2026-09-28-chat-group-sync-audit-and-plan.md` §5, TASK-MSG-INFO-1/2)
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)
**Template:** [component-doc-template.md](component-doc-template.md)
**Builds on:** [delivery-status.md](delivery-status.md) (UI-015), [context-menu.md](context-menu.md), [group-ui.md](group-ui.md) (UI-029 members sheet)

---

## Component

`FlashMessageInfoSheet` — a sheet, opened from one of the owner's own messages, that says **who has it** and **who has
not**. Data model `FlashMessageInfoUi` / `FlashMessageRecipientUi` (`:core:messaging`), repository hook
`FlashChatRepository.observeMessageInfo`, pure builders `buildGroupMessageInfo` / `buildDirectMessageInfo`.

## Purpose

A group bubble shows `2/3` and a tick. That answers "how many" but not "who is missing" — the question a sender actually
has when a message to a group of up to 20 sits at `17/20`. Message Info names the recipients in three sections: read
by, delivered to, and not yet delivered. The same sheet works for a 1:1 message (one recipient) so the menu item is the
same everywhere and never disappears depending on conversation type.

## Research sources

- WhatsApp Android "Message info": sections *Read by* / *Delivered to* / *Remaining*, per-person time. Verified by
  use, not by source (proprietary; no code or assets taken).
- Signal Android "Message details": a list of recipients grouped by status, with the send/receive times in a header.
- Telegram Android: "seen by N" line under the message, opening a list; no "remaining" section.
- iMessage group: no per-person detail (only "Delivered" / "Read" for the whole message) — the baseline we exceed.
- Flash's own data (read in this session, not assumed): `GroupDeliveryEntity` (`state`, `deliveredAt`),
  `ReadCursorEntity` (`upToSentAt`, `upToMessageId`, **no** read time), `GroupMemberEntity` (`displayName`, `isActive`),
  `MessageDao.markReadUpTo` (a message is READ for a member when `message.sentAt <= cursor.upToSentAt`).

## Existing approaches studied

1. **WhatsApp three-section list** — Read by / Delivered to / Remaining with times. Complete, familiar. Needs a read
   time per person; Flash does not store one.
2. **Signal flat list with per-row status glyph** — one list, each row carries its own tick. Compact but hides the
   *count* per state, which is the thing the bubble badge already abbreviates.
3. **Telegram "seen by" avatars** — great for large public groups, says nothing about who has *not* got it, which is
   the P2P question (peer offline).

## What worked

- Three sections ordered by how good the news is (read, delivered, waiting) with a count in each header.
- Showing the *reason* a recipient is waiting ("Waiting for device to connect") — in a P2P mesh "pending" almost always
  means "that device is not reachable right now", which is useful to state.
- Reusing the delivery glyphs users already learned from the bubble.

## What did not work

- A per-person **read time**: Flash stores a read *cursor*, not when it advanced. Fabricating "Read 14:02" from the
  message time would be wrong. Read rows therefore show no time. Adding `readAt` would be a Room schema bump plus a
  wire field for the sender to learn the time; rejected for this step (see Known limitations).
- One SQL join (the audit's `observeMessageDeliveryDetails`): it duplicates the rule that decides "read" outside Kotlin
  where it cannot be unit-tested without a database. The rule lives in `buildGroupMessageInfo` instead, fed by three simple
  flows (delivery rows, roster, read cursors). Recorded as a deliberate deviation from the audit in `docs/decisions.md`.

## Chosen approach

- **Entry points.** (1) Tapping the delivery badge (`2/3` + tick) on a message the user sent. (2) A **Message Info**
  item in the long-press / message-options menu, shown only for `isMine` messages that are not tombstoned and not call
  log rows. Both open the same sheet.
- **Group messages** (`deliveredTotal != null`): the repository observes the recipient rows and maps them.
- **1:1 messages:** no repository call. The recipient is the peer (header title) and the section follows the bubble's own
  `deliveryStatus` (Read → *Read by*, Delivered → *Delivered to*, Sent / Sending / Failed → *Not delivered yet*).
- **Sheet** hosted by `FlashSheetHost` (bottom sheet on Android, dialog-layer overlay on desktop — the host seam already
  exists for exactly this).

### Mapping rule (the one thing worth getting exactly right)

For each recipient row of the message (`group_deliveries`):

| Condition (first match wins) | Section |
|---|---|
| the member's read cursor has `upToSentAt >= message.sentAt` | **Read by** |
| `state == DELIVERED` | **Delivered to** (shows `deliveredAt` as a time label) |
| anything else | **Not delivered yet** |

Read wins over delivered because a member that has read the message has by definition received it, even if the
`DELIVERED` receipt was lost or has not been recorded yet (the receipt and the read cursor travel on different frames).
The read comparison is `sentAt`-based on purpose: it is the same predicate `markReadUpTo` uses to set a message READ, so
the sheet can never disagree with the bubble's own READ tick.

A recipient whose roster row is inactive (`isActive == false`: left or removed) is listed in **Not delivered yet** with
the note "No longer in the group" instead of "Waiting for device to connect", unless it had already read or received the
message. Members who joined after the message was sent have no delivery row and are **not listed** (they were never
recipients).

Names: the roster `displayName`, falling back to the short device id when the roster row is gone. Order inside a section:
delivered by time (earliest first), everything else by name (case-insensitive) then id, so the list is stable between
recompositions.

## Why it was chosen

It answers the real question (who is missing) with data Flash already has, costs no schema change and no wire change,
and keeps the decisive rule in a pure function with unit tests. The 1:1 variant costs nothing because the bubble already
carries the status.

## Visual specification

All tokens from `FlashTheme` (no stock Material list items).

| Element | Token / value |
|---|---|
| Sheet container | `colors.backgroundSurface`, same drag handle as the members sheet (36 × 4 dp, `borderSubtle`) |
| Sheet padding | `space20` horizontal, `space32` bottom (matches the members sheet) |
| Title | "Message info" — `headingMedium`, `textPrimary` |
| Message preview | up to 3 lines, `bodyDefault`, `textSecondary`, then the send time line in `metadataDefault`, `textTertiary` |
| Section header | title + count, e.g. "Read by · 2" — `metadataEmphasis`, `textSecondary` |
| Section glyph | Read: `FlashIcons.Read`, `accentPrimary`. Delivered: `FlashIcons.Delivered`, `textSecondary` (both resolve to the same double-tick drawable, told apart by tint, exactly as the bubble does). Waiting: `FlashIcons.Clock`, `textTertiary`. 18 dp (`iconSm`) |
| Recipient row | 32 dp `FlashAvatar` (seed = device id), name `bodyDefault` bold, subtitle `metadataDefault` (time label or note), hairline divider between rows |
| Empty section | not rendered (no empty headers) |
| Whole sheet empty | "No recipient details yet" in `textSecondary` (a message with no delivery rows). A group sheet draws nothing until the repository's first emission, so this line never flashes while the database answers |

## Interaction specification

- Tap delivery badge → opens the sheet (haptic `Confirm`, like the options button beside it).
- Menu item "Message Info" → closes the menu, opens the sheet.
- Sheet updates **live**: a receipt that arrives while it is open moves the row to the better section.
- Dismiss: scrim tap, back, drag down (Android).
- The badge's tap area is its visual size (count + tick, about 30 × 14 dp). It is **smaller than the 48 dp guideline** and
  cannot be padded without changing the bubble's layout, so the same sheet is always reachable from the message menu
  (**Message Info**, a full-width 48 dp-plus row) for anyone who cannot hit the badge. Recorded as a known limitation.

## Animation specification

No new motion. Section changes are plain recomposition; the sheet enter/exit is the host's. Reduced motion has nothing
to reduce.

## Gesture specification

No custom gestures. Badge tap vs bubble long-press do not conflict: the long-press/selection handler is on the bubble,
the badge owns a plain click.

## Accessibility requirements

- Each row is one merged node: "{name}, read" / "{name}, delivered {time}" / "{name}, waiting for device to connect" (`FlashMessageInfoMath.description`).
- Section headers are headings; the count is read as written ("Read by · 2").
- The badge keeps its existing "Delivered to M of N members" description and gains a button role + click label "Opens message info".
- Text scales with font size; rows wrap to two lines rather than clipping the name.

## Responsive behavior

Phone: bottom sheet, scrolls when the list is long (group max 20 rows, no paging needed). Tablet / desktop: the host's
overlay, content width capped by the host. No layout differs per width.

## Dark-mode behavior

Only semantic tokens; `accentPrimary` read ticks and `textTertiary` waiting glyphs keep the contrast the bubble's own
tick already meets.

## Performance considerations

The flow is observed only while the sheet is open (one message, at most 20 rows, four small Room/DAO flows combined).
Nothing is added to the message list's per-item cost except one `clickable` on the badge.

## Implementation notes

- `:core:persistence` — `GroupDeliveryDao.observeForMessage(messageId): Flow<List<GroupDeliveryEntity>>` (plain query).
- `:core:messaging` — `FlashMessageInfoUi`, `FlashMessageRecipientUi`, `MessageInfoBuilder.kt` (`buildGroupMessageInfo`, `buildDirectMessageInfo`, pure),
  `FlashChatRepository.observeMessageInfo(messageId): Flow<FlashMessageInfoUi?> = flowOf(null)`, real implementation in
  `RealFlashChatRepository` combining the message row, delivery rows, roster and read cursors.
- `:ui:chat` — `FlashMessageInfoSheet.kt` (the sheet, `FlashMessageInfoHost` which resolves group vs 1:1 content, and pure
  `FlashMessageInfoMath` for copy and eligibility), badge click in `FlashMessageBubble` (`onOpenMessageInfo`, plumbed through
  `FlashMessageList`), a `Message Info` item in `FlashMessageContextMenu` (icon `FlashIcons.Read`), hoisting in
  `FlashConversationScreen` (`observeMessageInfo` parameter, default `null` = group detail unavailable and every entry point
  hidden in a group; the 1:1 variant always works). A one-to-one message with no status reads as read, as the bubble draws it.
- Hosts: `MainActivity.kt` and `DesktopShell.kt` pass `repository::observeMessageInfo`.
- No new dependency. No wire change. No Room schema change (a query only).

## Testing checklist

- [x] Pure mapping unit tests (`MessageInfoBuilderTest`, 10) — read beats delivered, delivered time, pending note, inactive
      member, no row → not listed, ordering, blank roster name fallback
- [x] Live wiring (`SignedGroupsTest` message-info section, 4): follows a read, names an unreached and a departed member,
      null for a received / deleted / unknown / direct message; Room query scoped to its message (`FlashDatabaseInvariantTest`)
- [x] Copy and eligibility (`FlashMessageInfoMathTest`, 8)
- [x] Mutation check: 24 mutants of the builder, repository gate, DAO query and UI math, all killed by failing tests
- [ ] Compose preview (two previews exist in `FlashMessageInfoSheet.kt`; not viewed)
- [ ] Physical device (group of 3, one phone offline) — `CGS-06` in `docs/testing/TEST-BACKLOG.md`
- [ ] Dark mode
- [ ] Large font / display size
- [ ] RTL
- [ ] Reduced motion (n/a)
- [ ] Performance spot-check (n/a beyond the above)

## Known limitations

- **No read time.** The sender stores a read cursor, not when it moved; read rows show no time. A `readAt` needs a schema
  bump and a wire field.
- A member's **roster label is the owner-signed name** in a v2 group (renamed devices keep the signed one until a new
  cert is issued), so a renamed member can appear under the old name.
- Members who joined after the message was sent are not listed.
- Group `Read` frames are best-effort and not durable (audit step 3): a read tick lost on the wire is learned at the next
  read, never replayed, so "Read by" can lag reality.

## Future improvements

- `readAt` (schema + wire) so the Read section can show a time.
- Per-recipient resend from the sheet ("Try again now" for a waiting device).

## What makes this Flash?

The third section. Other messengers tell you who has read; a peer-to-peer mesh has to tell you who it could not reach
*yet*, because in Flash "not delivered" is a normal, recoverable state that resolves when the other device comes back on
the network — so the sheet says so, in the same tick glyphs and teal accent the bubble already uses.
