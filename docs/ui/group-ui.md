# group-ui

**Status:** UI-028 **DESIGNED → IMPLEMENTED** (2026-08-21) · UI-029 **DESIGNED → IMPLEMENTED** (2026-08-21)
**Component ID:** UI-028 (group chat header) / UI-029 (group member presentation)
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

**UI-028:** `FlashGroupAvatar` + group-mode `FlashChatHeader` — collage avatar built from member initials, "N members · M online" subtitle, named multi-person typing, transport/encryption glyphs, group actions (search, menu).

## Purpose

A group conversation must answer *who is here and how alive is it* from the header alone. The direct-chat header (single avatar + presence) communicates nothing about a group; this component gives groups their own identity surface that scales from a 2-person pair to a 50+ device mesh room — Flash's P2P reality where "online" means *reachable on the local network*.

## Research sources

- Telegram Android: collage group avatars from member photos; "X members, Y online" subtitle; typing shows member names.
- WhatsApp Android: single group icon; subtitle cycles member list / typing names ("Alex and Sam are typing…").
- Signal Android: member count emphasis; minimal chrome.
- Stream channel-header docs (Android/iOS/RN cookbooks — pattern reference only, **no SDK code or deps**): member-count + online-count subtitle, connection-state override, stacked member avatars fallback.
- Ethora chat UX guide (2026): overlapping circles up to 3 or 2×2 grid for larger groups; consistent color-hash per member across sessions.

## Existing approaches studied

1. **Single static group icon** (WhatsApp default) — no member signal without photos; P2P groups rarely have icons. Rejected as primary.
2. **Overlapping-avatar stack** (shadcn/social apps) — reads as "participants panel", needs photos, muddles at 36dp header size. Rejected.
3. **Initials collage grid** (Telegram-style, initial-based fallback) — every P2P peer has initials; scales naturally. **Selected.**

## What worked / did not work

- Worked: count-based layouts (2 → split, 3 → large+two, 4+ → quad), "members · online" subtitle, named typing capped at two names.
- Did not work: >4 visible tiles (noise at header size — cap at 4), full member-name cycling in subtitle (too wide for one line on phones).

## Chosen approach & why

Extend `FlashChatHeaderUiState` with group fields (`memberInitials`, `memberCount`, `onlineCount`, `typingMemberNames`) — all defaulted, zero breakage. `FlashGroupAvatar` renders a clipped-circle collage whose tile layout is chosen by pure logic (`FlashGroupHeaderMath.collageTileLayout`). Subtitle precedence: typing names → explicit `memberSummary` → computed "N members · M online". Groups get a Search action; call actions stay hidden (already enforced). Zero new dependencies; all layout/count/copy math pure and unit-tested.

### Visual specification

| Element | Token / value |
|---|---|
| Collage container | Circle, size = `avatarMd` (36dp); internal gap 2dp (`space2`) |
| Tile layouts | Single · TwoVertical · OneLargeTwoSmall (60/40 split) · Quad |
| Tile text | `captionEmphasis` scaled to tile; seeds reuse `FlashAvatar` palette hashing |
| Subtitle | `metadataDefault`, `textSecondary`; online dot `statusOnline` 8dp before count |
| Named typing | `FlashHeaderTypingStatus` dots + `metadataEmphasis` accentPrimary names label |
| Actions | Search icon (groups) + More; 48dp targets |

### Interaction / animation / accessibility

- Whole header tappable area unchanged (avatar click = group details).
- Status line crossfades via existing `motion.statusCrossfade()`; typing dots animate per UI-014; reduce-motion collapses.
- Collage merged semantics: "Group avatar, N members"; subtitle announced as sentence; search/menu have descriptions.
- Large fonts: subtitle ellipsizes; collage fixed-size (identity element).

### Responsive / dark mode / performance

- Same layout phone↔tablet (header height fixed); collage unaffected by width.
- Semantic colors only — dark mode inherits.
- Static composition; only AnimatedContent crossfade cost; no allocations per frame.

## Implementation notes

Files: `core/messaging/.../FlashMessagingModels.kt` (state fields), `ui/chat/.../FlashGroupHeader.kt` (new: `FlashGroupAvatar`, `FlashGroupHeaderMath`, `FlashGroupTypingStatus`), `FlashChatHeader.kt` (group branches), `FlashMessagingUtils.kt` (sample group header), `FlashGroupHeaderLogicTest.kt`.

Dependencies: **none added**.

## Testing checklist

- [x] Unit tests green: collage layout selection (1/2/3/4+/degenerate), subtitle labels incl. singular/plural, typing label capping
- [ ] Compose previews (group 15-member, small group, group typing)
- [ ] Physical device: group conversation header, tap-through actions
- [ ] Dark mode / large font / reduced motion

## Known limitations

- No group photo support (P2P mesh has none yet) — initials collage is the identity.
- Member rows/admin badges/relay-per-member = **UI-029** (RESOLVED — see section below).
- Search action is a callback stub until UI-023 lands.

## Future improvements

- Live per-peer reachability coloring inside collage tiles (needs discovery state feed).
- Overflow "+12" tile for huge rooms.

## What makes this Flash?

The collage is built from the same seeded palette hashing as every avatar in Flash, so a member keeps their color everywhere; the subtitle speaks Flash's P2P language (reachable-on-network counts, relay glyph); and named typing respects the same reduce-motion/motion-token contracts as the rest of the app — no stock list-item header, no clone of any single app's chrome.

---

## UI-029 — Group member presentation

### Component

`FlashGroupMembersSheet` + `FlashGroupMembersMath` (`ui/chat/.../FlashGroupMembersSheet.kt`) — group-members bottom sheet with custom member rows: 32dp seeded avatar with online dot, name, per-member transport subtitle, transport glyph, role badge pill.

### Purpose

UI-028 answers *how alive is the group* from the header; UI-029 answers *who exactly is here and how each peer is connected*. In a P2P mesh, "member" is not just a roster entry — each row carries reachability (online dot) and network path (LAN / Wi-Fi Direct / Relay), which is Flash-specific information no stock list item conveys.

### Research sources (pattern study only)

- Telegram Android: member list ordering (online first), admin/owner labels next to names.
- WhatsApp Android: group info participant rows; "Admin" suffix badge; simple presence.
- Slack: compact role badges/pills for owners/admins; sectioned member browser.
- Signal Android: minimal role emphasis; safety-number-first mindset (roles de-emphasized).
- Stream member-list docs (pattern reference only, **no SDK code or deps**): avatar + presence-dot overlay pattern.

### Existing approaches studied

1. **Material `ListItem` / dropdown-menu-style roster** — stock look, prohibited. Rejected.
2. **Overlapping avatar stack** (participants-panel pattern) — needs photos; hides transport info. Rejected.
3. **Custom row: avatar+dot / name+transport subtitle / trailing glyph + role pill** — all-Flash primitives, P2P-aware. **Selected.**

### Chosen approach & why

Pure logic in `FlashGroupMembersMath`: sort = online → role rank (Owner/Admin/Member) → alphabetical; summary copy `"N of M online"` (singular-safe, negatives clamped); badge copy null for plain members (no empty pill); row-count cap at 50. Sheet reuses the UI-012 sheet conventions (ModalBottomSheet infrastructure only, `FlashShapes.radius24` top corners, manually drawn drag handle). Rows are hand-built `Row`s separated by hairline `Box`es drawn like the header divider — no `ListItem`, no `HorizontalDivider`. Subtitle shows the live transport ("LAN"/"Wi-Fi Direct"/"Relay") or "Offline" when the peer is unreachable/unknown.

### Visual specification

| Element | Token / value |
|---|---|
| Avatar | `FlashAvatar` 32dp, seed = member id |
| Online dot | 10dp circle, `statusOnline`, 2dp `backgroundSurface` ring, bottom-end overlay |
| Name | `bodyDefault` bold copy, `textPrimary`, single line ellipsized |
| Subtitle | `metadataDefault`, `textSecondary` ("LAN"/"Wi-Fi Direct"/"Relay"/"Offline") |
| Transport glyph | `FlashIcons.Wifi/WifiDirect/Relay`, `iconSm`, `textTertiary`; none when Unknown |
| Role badge | `FlashShapes.chip`, `accentPrimary @ 12%` bg, `accentPrimary` text, `metadataEmphasis` |
| Divider | hairline `Box` (1dp, `borderSubtle`) between rows |
| Sheet | radius24 top corners, manual drag handle, `navigationBars` insets |

### Interaction / accessibility

- Title row merged semantics announce "Members. N of M online".
- Each row merges to "Name, online/offline[, Owner|Admin]".
- No row actions yet (tap-to-open-member = future); rows are non-clickable presentation.
- Large fonts: name/subtitle ellipsize; avatar/badge fixed-size identity elements.

### Responsive / dark mode / performance

- Single scrollable `Column` (≤50 capped rows) — no lazy machinery inside sheet; phone↔tablet identical.
- Semantic colors only; dark mode inherits.
- Sort computed once per input via `remember(members)`; zero per-frame allocations beyond static composition.

### Implementation notes

Files: `core/messaging/.../FlashMessagingModels.kt` (added `FlashMemberRole`, `FlashGroupMemberUi` — additions only), `ui/chat/.../FlashGroupMembersSheet.kt` (new: `FlashGroupMembersSheet`, `FlashGroupMembersMath`, `sampleGroupMembers`, previews small/large), `ui/chat/src/test/.../FlashGroupMembersLogicTest.kt`.

Dependencies: **none added**.

### Testing checklist

- [x] Unit tests green: sort order (online/rank/alphabetical), summary labels singular/plural/negative, badge labels incl. null Member, row cap
- [ ] Compose previews (5-member mixed roles, ~12-member large group)
- [ ] Physical device: open sheet from group header, dark mode, large font
- [ ] Reduce-motion n/a (no animations in v1)

### Known limitations

- Rows are read-only; long-press member actions (kick/promote) deferred.
- Role changes come from the caller-supplied list; no role-editing logic here.
- Transport shown reflects last-known path; no live refresh while sheet is open.

### What makes this Flash?

Every element speaks the P2P language: an online dot means *reachable on the mesh*, the subtitle names the actual path (LAN vs Wi-Fi Direct vs Relay) instead of a generic "last seen", avatars reuse the shared seeded palette so members keep their color everywhere, and the whole row is built from Flash primitives — zero stock list-item chrome, zero cloned roster design.

### UI-029 addendum — vouched members (ADR-044 V2), DESIGNED 2026-09-30

**Why.** In a v2 group a member may be someone this device never paired with: the group owner (whom this device *did* pair
with) signed a certificate for them. The user must be able to tell that person from a paired contact, and to turn the
introduction into a real pairing. The plan is `docs/group/v2-vouched-trust-plan.md` E6.

**Approaches considered.**

1. *A badge on the avatar* (shield, question mark). Rejected: one more glyph the row already has three of (online dot,
   transport, role pill), and an icon alone does not say *who* vouched.
2. *A separate "Not verified" section in the sheet.* Rejected: it splits the sorted list (online → role → name) and hides
   online members below offline ones.
3. *A third text line under the transport subtitle, plus a trailing text action.* **Selected.** Same row anatomy, nothing new
   to learn, the line names the introducer, and the action sits where the eye already goes for the role pill.

**Specification.**

| Element | Value |
|---|---|
| Model | `FlashGroupMemberUi.introducedBy: String?`. The owner's display name for a member this device never paired with; null for yourself, the owner (the charter needs the owner paired) and every paired member. Legacy groups never set it. |
| Line | `"Added by <owner> · not verified"`, `metadataDefault`, `textTertiary`, single line, ellipsized. Copy comes from `FlashGroupMembersMath.introducedByLabel` (pure, unit-tested). |
| Action | Trailing text `"Verify"`, `bodyDefault`, `accentPrimary`, `Modifier.clickable` like `Pair Device` in the Nearby detail pane. Shown only when the host passes `onVerifyMember` and the member has `introducedBy`. Min touch height 48dp via the row padding. |
| What Verify does | The host runs the ordinary pairing flow (`connectManual` if discovery knows an address, then `pairing.beginPair`), exactly like `onVerifyTrustedClick`. The other user sees the normal Accept/Decline. When it completes the member is paired and the line disappears; there is no new trust logic in the UI. |
| Semantics | Row description appends `", added by <owner>, not verified"`. The action has `Role.Button` and description `"Verify <name>"`. |
| Not done here | *(Done later the same day, see addendum 2.)* Owner removal has API and tests (`RealFlashChatRepository.removeGroupMember`) but no UI: it needs a member action menu and a confirmation, which UI-029 deferred ("long-press member actions"). |

**Checklist.** Unit: label copy, null for paired, row description. Physical device: see `docs/testing/TEST-BACKLOG.md` GT-03.

### UI-029 addendum 2 — owner removes a member (ADR-044 V2 E5), DESIGNED 2026-09-30

**Why.** `RealFlashChatRepository.removeGroupMember` (owner-only, signed tombstone, revokes the vouch on every receiver) had an API
and tests but no way to reach it. A group owner who wants someone out today has no option short of everyone leaving.

**Who sees it.** Only the owner of a **v2** group, on every row except their own. Not a member (they cannot remove anyone), not in a
legacy group (there is no signed roster to remove from), not on the owner's own row (the owner cannot remove itself; leaving is the
existing menu action). The repository decides and says so with `FlashConversationUiState.canRemoveMembers`; the UI never derives
ownership.

**Approaches considered.**

1. *Long-press a row for a context menu.* Rejected: no visible affordance (a destructive action nobody can find is as bad as none),
   it needs custom accessibility actions, and desktop has no long-press habit.
2. *Tap a row to open a per-member detail sheet holding Verify and Remove.* Rejected for now: a whole new surface for one action.
   It is the likely home if promote/demote or per-member info ever arrive; nothing here prevents that move.
3. *A trailing "Remove" text action on each removable row, in the error colour, followed by a confirmation dialog.* **Selected.**
   Same shape as Verify (visible, a real button for screen readers, 48 dp touch height through the row padding), and the dialog
   is the same `FlashConfirmHost` the leave-group confirmation already uses.

**Specification.**

| Element | Value |
|---|---|
| Model | `FlashConversationUiState.canRemoveMembers: Boolean = false`; true only when `groupProto` is v2 and `groupCreatedBy` is this device. |
| Action | Trailing text `"Remove"`, `bodyDefault`, `textError`, `Modifier.clickable(role = Role.Button)`. Shown when the host passes `onRemoveMember`, the state says `canRemoveMembers`, and the row is not the Owner row. Sits after Verify when both apply (they cannot for a healthy owner, who is paired with everyone it invited). |
| Semantics | The action's description is `"Remove <name> from the group"`; it stays outside the merged row description, like Verify. |
| Confirmation | `FlashRemoveMemberDialog`: title `"Remove <name>?"`, body `"<name> will be removed for everyone in the group and stop receiving new messages. Messages they already received stay on their device."`, actions `Cancel` and `Remove` (error colour). Copy comes from `FlashGroupMembersMath.removeTitle/removeMessage` (pure, unit-tested; a blank name reads "this member"). The body is deliberately honest: Flash has no per-sender keys, so a removed member keeps what they already have. |
| After confirming | The host calls `removeGroupMember`. Success needs no message: the roster is reactive, the row disappears when the tombstone is stored. Failure shows a toast (Android) or snackbar (desktop): `"Couldn't remove <name> — try again"`. The sheet stays open. |
| Not done here | No undo (re-adding is the ordinary Add members flow), no bulk remove, no promote/demote, no "remove and block". |

**Checklist.** Unit: `canRemove` (never the Owner row), dialog copy, blank name; repository: `canRemoveMembers` true for the v2
owner only. Physical device: `docs/testing/TEST-BACKLOG.md` GT-03 step 7 (owner removes a member).

**Status: IMPLEMENTED 2026-09-30 (`39d8905`).** Files: `FlashGroupMembersSheet.kt` (`FlashGroupMembersMath.canRemove/removeTitle/removeMessage`,
`onRemoveMember`, the row action), `FlashAddMembersSheet.kt` (`FlashRemoveMemberDialog`, beside the leave dialog it copies),
`FlashConversationScreen.kt` (`onRemoveGroupMember`, `memberToRemove`), hosts `MainActivity` / `DesktopShell`, state
`FlashConversationUiState.canRemoveMembers`. Unit tests green; **Compose previews and the physical-device check are still open.**
Known gap: the removed member's own device has no designed "you were removed" state; it just stops receiving.

### UI-029 addendum 3 — a device that was removed or left (ADR-044 V2, removal ripple), DESIGNED 2026-09-30

**Why.** Once the owner removes a member, or a member leaves, that device keeps the conversation but is out of the group: the other
devices drop everything it sends. Until now its screen did not say so. The composer stayed live, a message typed there was stored as
`PENDING` and retried forever (nobody accepts it), and the call buttons rang members who would decline. The repository now refuses those
sends and reports `FlashConversationUiState.selfMembership` (`Active`, `Left`, `Removed`); this addendum is what the screen does with it.

**Approaches considered.**

1. *Keep the composer but disable it in place, with a hint.* Rejected: a greyed field still looks like something to tap, "disabled"
   does not tell a screen-reader user why, and the composer's own animations and attachment button would need a fourth state.
2. *Delete the conversation when the device is removed.* Rejected: the removal dialog promises the removed member keeps what they
   already received, the history is theirs, and an automatic delete is destructive and surprising (and would also delete the only
   record of who removed them).
3. *Replace the composer with a one-line notice, take the group actions away, keep the history readable.* **Selected.** Nothing
   pretends the member can still talk, nothing is lost, and there is no new modal surface.

**Specification.**

| Element | Value |
|---|---|
| Model | `FlashConversationUiState.selfMembership: FlashSelfMembership` (`Active` default). `Left`: this device left (v2 tombstone issued by itself, or a legacy leave). `Removed`: the owner's tombstone. Decided by the repository from the device's own roster row. |
| Composer | When `selfMembership != Active` the bottom bar shows `FlashGroupSelfNotice` instead of `FlashComposer`: `backgroundSurfaceSubtle` bar, group icon, a title and one line of detail, above the navigation-bar inset. It is not interactive. |
| Copy | `Removed`: title "You were removed from this group", detail "You can still read what you already received." `Left`: title "You left this group", same detail. Pure text from `FlashGroupSelfNoticeMath` (unit-tested). |
| Semantics | One merged node, description "<title>. <detail>". Nothing to focus or click. |
| Header | `showCallActions` is false (the repository sets it), so no voice or video button. An ongoing-call banner can still arrive from members who have not heard; its Join is refused by the call gate. |
| Menu | `FlashConversationMenuMath.groupItems(isMember = false)` keeps Group info, Search and Mark as unread, and drops Add members and Leave group (both would fail). |
| Members sheet | Still readable. Remove is not offered (`canRemoveMembers` is false when not `Active`). |
| Rejoining | Only the owner can add the device back (the ordinary Add members flow on their device). When that arrives the state returns to `Active` and the composer comes back on its own; no button here. |
| Not done here | No "delete this chat" action inside a removed group, no "removed by <name>" line (the owner's name is in the roster if wanted later), no notification when the removal arrives. |

**Checklist.** Unit: notice copy per state, menu without Add/Leave for a non-member, repository `selfMembership` for removed and left
(`SignedGroupsTest`). Physical device: `docs/testing/TEST-BACKLOG.md` GT-03 step 7 (what D's own screen shows). Compose preview and
the accessibility pass are still open.

**Status: IMPLEMENTED 2026-09-30.** Files: `FlashGroupSelfNotice.kt` (`FlashGroupSelfNoticeMath`, `FlashGroupSelfNotice`, two previews),
`FlashConversationScreen.kt` (bottom bar and menu), `FlashConversationMenu.kt` (`groupItems(isMember)`), state
`FlashConversationUiState.selfMembership`. Unit tests green (`FlashGroupSelfNoticeMathTest`, `FlashConversationMenuMathTest`,
`SignedGroupsTest`); **previews and the device check are still open.**

### UI-052 — catch-up progress banner (chat/group sync audit, TASK-GRP-SYNC-2), DESIGNED 2026-09-30

**Why.** A device that has just joined a group (or returned after a long absence) asks the holders for the history it lacks, and
they answer with paced pushes (`GroupWireFrame.SyncPush`, 5–20 per second). Messages appear above the composer one by one while
nothing says why, so the conversation looks broken or "still loading" with no cause. One line that says history is arriving turns that
into an expected state.

**What the requester can and cannot know.** The requester sends a `SyncRequest`; the holders answer with pushes. Nothing tells the
requester *how many* will come (the claim with the id list goes to the co-holders, not to it) and nothing tells it the batch is over
(the `SyncAck` flows the other way). So **there is no honest percentage**. The audit's `receivedCount/totalExpected` bar cannot be built
without a wire change, and inventing a total would be a lie on the screen. This design is therefore *indeterminate by construction*.

**Approaches considered.**

1. *Determinate bar `received/total`* (the audit's sketch). Rejected: needs a `total` on the wire (a holder cannot even know it, since
   co-holders push disjoint subsets), and a wrong number is worse than none.
2. *A banner while any outgoing `SyncRequest` is live.* Rejected: a request goes out on every session-up edge to every peer, and in a
   group that is fully up to date nothing comes back, so the banner would flash on and off at every reconnect with nothing to report.
3. *A banner only while history is actually arriving: appears on the first accepted push, counts the new messages, goes away after a
   quiet period with no further push.* **Selected.** It only ever shows when it is true.

**Specification.**

| Element | Value |
|---|---|
| Model | `FlashGroupSyncUi(receivedCount: Int)`; `FlashConversationUiState.groupSync: FlashGroupSyncUi? = null` (non-null = history arriving). Carried in the state like `ongoingCall`, so hosts wire nothing. |
| Start | The first accepted push that **inserts a new row** for the group (a push of a message already present does not count, so a redundant second holder never starts a banner). |
| Count | New rows inserted by catch-up pushes since the banner appeared. |
| End | `GROUP_SYNC_QUIET_MS` (3 s) after the last counted push. Longer than the slowest pacing gap (1 s) so a slow holder does not flicker it, shorter than the 24 h horizon nobody waits for. A later push (for example the backup holder's, 2 s after the first) starts a fresh banner from 1. |
| Scope | Per group, shown only in that conversation. Not persisted; a restart mid-catch-up shows nothing (the pushes resume into an ordinary conversation). |
| Copy | `"Catching up on earlier messages · 12"`; before the first count is rendered `"Catching up on earlier messages"`. Copy from `FlashGroupSyncMath.label(receivedCount)` (pure, unit-tested; a count ≤ 0 has no number). |
| Placement | Directly under the header, in the same stack as `FlashConnectionBanner` / `FlashOngoingCallBanner` (above the message list). Enter `fadeIn(tweenNormalSpec)`, exit `fadeOut(tweenFastSpec)`, like its siblings. |
| Surface | Full-width strip on `colors.backgroundElevated`, `space16` horizontal / `space8` vertical padding, label `metadataDefault` in `textSecondary`, the count in `metadataEmphasis` / `textPrimary`. |
| Progress line | A 2 dp custom line (no stock `LinearProgressIndicator`, UI prohibition): `borderSubtle` track with an `accentPrimary` segment (35% of the width) sweeping left→right, 1.4 s linear loop. Reduced motion: the segment is static at 35% and only the count changes. |
| Semantics | The strip is one `liveRegion = Polite` node: `"Catching up on earlier messages, 12 received"`. The line is decorative. |
| Not done here | No cancel, no per-holder detail, no total, no retry action (catch-up already re-asks on every session-up edge). |

**Checklist.** Unit: `FlashGroupSyncMath.label`; repository: banner state appears on the first inserted push, ignores a duplicate,
counts inserted rows, clears after the quiet period, is scoped to its group. Physical device: `docs/testing/TEST-BACKLOG.md` CGS-07.
