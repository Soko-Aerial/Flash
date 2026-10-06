# Group Invite & Join Flow — UI-054

**Status:** IMPLEMENTED AND VERIFIED  
**Component ID:** UI-054  
**Last updated:** 2026-10-05  
**Owner phase:** Track GM Phase GM-10 (`docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` §7B lines 1988–2025, ADR-074, ADR-076)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Builds on:** [group-ui.md](group-ui.md) (UI-028, UI-029), [group-settings.md](group-settings.md) (UI-053), [error-states.md](error-states.md) (UI-027)

---

## Component

`FlashGroupInviteJoin` — components and dialogs governing the group invite, join, and approval lifecycle:
1. **Invite Sharing (`FlashGroupInviteSheet`):** Displays the generated `flash://g/1/...` invite, copy link button, system share sheet trigger, and explanation of who can join.
2. **Join Dialog (`FlashJoinGroupDialog`):** Link input / paste dialog (Desktop and Android fallback), plus deep-link confirmation card ("Join <Group>? Invited by <Name>").
3. **Pending Join Status (`FlashPendingJoinCard` / Sheet):** Displays the Table 8.3 plain-English status sentence (e.g. M-03 "Waiting for a member of <group> to be nearby", M-07 "Waiting for an admin of <group> to approve"), inviter info, and a "Cancel request" button.
4. **Admin Join Requests List (`FlashJoinRequestsSection`):** Rendered at the top of `FlashGroupMembersSheet` for admins/owners when join requests are pending. Shows applicant name, time, "Previously removed" warning chip if applicable, and Approve / Decline buttons.
5. **Chat Inline Invite Card (`FlashInlineInviteCard`):** Renders an interactive preview card within 1:1 or group chats when an invite link is shared as message text (O-14).

---

## Purpose

Joining a group in Flash P2P mesh cannot rely on a centralized server to validate access tokens. Instead:
- An invite embeds a cryptographic epoch secret and inviter address hints (`GroupInviteCodec`).
- Joining initiates a cryptographic handshake and proof exchange (`:core:security`).
- If group settings require approval (`joinPolicy == "ADMINS"`), the joiner's request is recorded and must be explicitly approved by an admin.
- Users must never see raw base64 payloads, hexadecimal device IDs, or cryptographic errors. All states must be translated into direct, respectful human sentences from Table 8.3 (`GroupMembershipStatusText`).

---

## Research sources

1. **Telegram Android Invite Links & Join Requests:**
   - Group info has "Invite Links" showing link, QR code, and copy/share buttons.
   - Pending join requests show a top banner: "Join requests (2)". Tapping opens review list with user avatar, name, and "Add to group" / "Dismiss".
   - Joiner sees dialog: "Join <Group>" with group avatar, member count, and "Request to Join" button.
2. **WhatsApp Group Links:**
   - Link opens a modal sheet showing group icon, title, "Group created by <Name>", member count, and a green "Join group" button.
   - If admin approval is required: "Join request submitted. Admins must approve your request before you can participate."
3. **Signal Group Links:**
   - Link preview shows group name and member count.
   - "Requires admin approval" notice shown prior to tapping join.
4. **Flash Protocol & GM Specifications:**
   - Table 8.3 status sentences (`GroupMembershipStatusText.kt`): M-01 to M-22.
   - `GroupInviteCodec`: safe decoding of `flash://g/1/<payload>`, untrusted input verification.
   - Deep link intent filter: `flash://g/*`.

---

## Existing approaches studied

### Approach A: Automatic silent background join
- When a link is tapped or pasted, immediately join and open the conversation without confirmation.
- **Rejected:** Dangerous in P2P mesh. The link might be expired, for a full group, or the user might have tapped it accidentally. Confirmation gives the user agency and establishes context (inviter name, group name).

### Approach B: Heavy multi-step wizard
- Step 1: parse link; Step 2: show details; Step 3: show security prompt; Step 4: connect.
- **Rejected:** Overly cumbersome for messaging.

### Approach C: Clean two-stage sheet + reactive status (Flash Chosen Approach)
- Stage 1 (Confirm): Tapping or pasting an invite parses via `GroupInviteCodec` and presents `FlashJoinGroupDialog`: shows group name, inviter name, and "Join".
- Stage 2 (Reactive):
  - If open: joins immediately, dismisses dialog, navigates to conversation.
  - If approval required / waiting: displays `FlashPendingJoinCard` with Table 8.3 sentence and "Cancel" action.
- Admin Side: Admins see a badge or top section in `FlashGroupMembersSheet` with pending applicants. If an applicant was previously removed, an amber warning badge is shown ("Previously removed") so admins can make an informed decision.

---

## What worked

- **Table 8.3 Sentences:** Reusing `GroupMembershipStatusText` guarantees consistent, friendly wording across all screens and test suites.
- **"Previously removed" indicator:** Crucial for group safety so an admin doesn't unwittingly re-admit an ejected disruptive peer.
- **Inline chat card (O-14):** Automatically detecting `flash://g/1/` in chat messages and rendering an interactive card makes sharing inside Flash seamless.

---

## Visual specification

| Element | Token / value |
|---|---|
| Join dialog | `colors.backgroundSurface`, `radius24`, max width 400dp |
| Group avatar | `FlashAvatar` 48dp, seeded from group title |
| Inviter caption | `typography.metadataDefault`, `colors.textSecondary` ("Invited by Alex") |
| Primary action | `colors.accentPrimary` filled button, "Join group" |
| Secondary action | Outlined / subtle button, "Cancel" |
| Request row | `colors.backgroundSurfaceSubtle` container, `radius16`, padding `space12` |
| Removed badge | `colors.warning @ 12%` bg pill, `colors.warning` text, "Previously removed" |
| Approve button | `colors.accentPrimary` text / subtle pill, "Approve" |
| Decline button | `colors.textSecondary` text / subtle pill, "Decline" |

---

## Testing checklist

- [x] Unit tests for `FlashGroupInviteJoinMath` (`FlashGroupInviteJoinMathTest.kt`):
  - `parseInviteUrl(raw)` (safe decoding, null on malformed)
  - `joinConfirmationTitle(groupName)`
  - `inviterLabel(inviterName)`
  - `isRemovedMember(subjectId, removedIds)`
  - `canShareInvite(isOwner, isAdmin, inviteSharers)`
- [x] Compose UI implementation in `FlashGroupInviteSheet.kt`, `FlashJoinGroupDialog.kt`, `FlashInlineInviteCard.kt`, `FlashJoinRequestRow`
- [x] Integrated into Android (`AndroidManifest.xml` deep link, `MainActivity.kt`, `FlashNotificationManager.kt`) and Desktop (`DesktopShell.kt`, `DesktopNotificationManager.kt`)
- [ ] Physical device verification (tracked in `docs/testing/TEST-BACKLOG.md` §4x: `GMB-04`, `GMB-14`)
