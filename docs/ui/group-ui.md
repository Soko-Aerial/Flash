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
| Not done here | Owner removal has API and tests (`RealFlashChatRepository.removeGroupMember`) but no UI: it needs a member action menu and a confirmation, which UI-029 deferred ("long-press member actions"). |

**Checklist.** Unit: label copy, null for paired, row description. Physical device: see `docs/testing/TEST-BACKLOG.md` GT-03.
