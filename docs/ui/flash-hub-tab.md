# Component Research & Design Document — Flash Hub tab (UI-058)

**Status:** IMPLEMENTED 2026-10-15 (H0-H10, branch `agent/C-h6-h10`); unit-tested only, NOT device-verified (HUBT-01..32 owed). Implementation plan: `docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md`.
The shell (tab swap, Transfers button) is H0-H5; the screens, repository facade, Message/Call, routes, badge and previews are H6-H10
(`ui/chat/.../ui/hub/`, `core/messaging/.../hub/`). Differences from this design are listed in "Implementation notes (H6-H10)" at the end.

**Component ID:** UI-058
**Last updated:** 2026-10-14
**Owner phase:** Hub integration, Plan A (after Plan B, offline organisation trust). Owner decisions D1..D7 are in
`docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md` and ADR-133.

---

## Component

`FlashHubScreen` and its parts (`FlashHubPeopleSection`, `FlashHubDepartmentsSection`, `FlashHubAnnouncementsSection`,
`FlashHubGroupsSection`, `FlashHubPersonSheet`, `FlashHubDepartmentScreen`, `FlashAnnouncementCard`, `FlashAnnouncementComposer`),
plus the new bottom-bar / rail / sidebar entry "Hub" and the Transfers entry that moves into the chat-list top bar.

## Purpose

An employee who joined an organisation (enrolment link, ADR-120) today sees the organisation only in Settings → Hub, and the only
organisation screen is the "Browse department groups" sheet behind a Relay icon in the chat-list top bar. The owner wants the organisation
to be a first-class place in the app: who works here, in which department, what was announced, which department groups exist, and a way to
start a conversation with a colleague who is not paired. The tab exists **only while this device is enrolled**; an unenrolled user sees
exactly the app they have today.

## Research sources

- Internal: `docs/ui/navigation.md`, `docs/ui/bottom-nav.md` (UI-046 hanging bar), `docs/ui/chat-list.md`, `docs/ui/empty-states.md`,
  `docs/ui/error-states.md`, `docs/ui/loading-states.md`, `docs/ui/group-ui.md`, `docs/ui/responsive-layout.md`,
  `docs/ui/hub-admin-console.md` (the admin side of the same data), `docs/ui/transfers-page.md` (what moves to the top bar).
- Code facts checked 2026-10-14: `FlashDestination` {ChatList, Conversation, Transfers, NearbyDevices, Settings};
  `FlashNavigationMath.TabOrder`; host tab lists in `MainActivity.kt` and `DesktopShell.kt` / `DesktopSideBar.kt`;
  `FlashBrowseOrgGroupsSheet`; `FlashHubCard`; `fetchEnterpriseDirectoryMembers` (no UI caller today).
- Product references studied for patterns only (no cloning, AGENTS.md §34): Slack (Directory, Channels browser, Announcements channel),
  Microsoft Teams (People/Org, Viva Engage announcements), Google Workspace Directory, Signal (clear trust states), Telegram
  (channel with pinned post and unread jump), WhatsApp Communities (announcement group on top of department groups).

## Existing approaches studied

1. **Slack-style: directory as a searchable list plus a profile sheet.** Strong search, familiar. Weak: a flat list of thousands is
   a wall of names; no sense of departments.
2. **Teams-style: org chart / people cards with department drill-down.** Good for "who is in Finance". Weak: heavy, tree navigation is slow on
   a phone, needs reporting lines that Flash does not have (and deliberately does not model, see roadmap "ideas not decided").
3. **WhatsApp Communities-style: one announcement feed at the top, groups below.** Good for the "what is new" glance and the unread badge.
   Weak: no people directory at all.
4. **Telegram channel-style: single feed with pinned post and jump-to-unread.** Excellent feed ergonomics. Weak: one-to-many only.

## What worked

- A **single segmented screen** (top segmented control, not nested tabs) so the bottom bar stays flat and one-thumb reachable.
- A glance-first order: announcements first (what is new), then people, departments, groups.
- Search that spans the whole screen (people, departments, announcements, groups) with grouped results.
- A profile sheet with one primary action ("Message") and clear status text.

## What did not work

- A deep org-chart tree: too slow, and the hub has no reporting lines.
- Making the tab a duplicate of Nearby (LAN): people on the LAN and colleagues in the organisation are different sets; the profile sheet
  shows *how* each one is reachable instead of mixing lists.
- A second bottom bar entry for announcements: costs a slot; the unread count lives on the Hub tab badge.
- Showing every department to everyone regardless of the hidden flag (D2): see Directory privacy plan.

## Chosen approach

### 1. Where it lives (D1)

- Tab set is a function of enrolment:
  - **Not enrolled:** Chats · Transfers · Nearby · Settings (unchanged).
  - **Enrolled:** Chats · **Hub** · Nearby · Settings. The Hub tab takes Transfers' place and position (second slot).
- **Transfers moves to the chat-list top bar** as an icon button (`FlashIcons.Transfer`) with a small dot/count while any transfer is active
  or waiting for a decision (same source as the existing badge on the Transfers tab). It opens the existing Transfers screen as a **pushed
  destination** (back returns to the chat list; it is not a tab root). It is shown only in the enrolled layout, because unenrolled users
  still have the tab.
- The existing top-bar action "Browse department groups" (Relay icon) is **removed from the chat-list top bar**; its content becomes the
  Groups segment of the Hub tab. This frees the slot the Transfers button takes, so the top bar does not get longer.
- Tablet (≥ 600 dp): navigation rail shows the same four entries; Transfers button is in the chat-list pane's top bar.
  Desktop: sidebar shows Chats, Hub, Nearby, Settings; the Transfers button sits in the list pane header.

### 2. Screen structure

```
┌───────────────────────────────────────────┐
│ Acme Corp                      [search]   │  top bar: org name (hubName), search icon
│ ● Connected · updated just now            │  status strip (see "States")
├───────────────────────────────────────────┤
│ [ News ] [ People ] [ Departments ] [ Groups ] │  segmented control, scrolls if narrow
├───────────────────────────────────────────┤
│  segment content (LazyColumn)             │
└───────────────────────────────────────────┘
```

Segment order and default: **News** (announcements) is the default segment when there is an unread announcement, otherwise the last used
segment (remembered per device in settings, `hubLastSegment`).

**News (announcements, D5).** Pinned announcements first, then newest first. A card: scope chip ("Whole company" or the department name),
title, first 3 lines of body, author display name + role chip ("Admin" / "Department head"), relative time, "Read more" when truncated, unread
dot. Tapping opens a full-screen reader (a detail pane on wide layouts). Authors (admins; department heads for their own department) see a
"+" button that opens the announcement composer; authors see Edit / Delete / Pin on their cards (and admins on every card). Body is plain text
(no markup is rendered; links are detected and made tappable with a "leaving Flash" confirmation for non-organisation hosts).

**People (directory).** Alphabetical list with sticky letter headers, a search field on top, department filter chips (My department, All,
or one chosen). Row: avatar (existing `FlashAvatar`), display name, department, role badge when not a plain member, online dot (per D2 option
C: shown for non-hidden departments and for your own department). Tap → `FlashHubPersonSheet`: large avatar, name, department, role, reachability
line ("On your Wi-Fi now", "Reachable through the company server", "Offline"), primary button **Message** (starts or opens the 1:1
conversation; creates the conversation with the hub as route when the colleague is not paired), secondary **Call** only when reachable
over LAN and the existing call gates allow it. Never shows keys. Footer line: "Not paired with you. Messages go through the company
server and can be read there unless you turn on private messages in Settings → Hub" when that is true (text comes from the existing
`FlashHubStatusMath`; the implementer must reuse the exact sentences, not invent new ones).

**Departments.** Cards of departments visible to the caller (hidden departments appear only to their own members and admins, D2). A card shows
name, member count, number of groups, a "Your department" chip and head names. Tap → `FlashHubDepartmentScreen` with three blocks: Heads
(avatars, Message), Members (the People list filtered), Groups (listed groups of that department with Join/Open), and that department's
announcements.

**Groups.** The existing browse-organisation-groups content moved into the tab (same join / request / pending semantics of ADR-123), grouped
by "Yours" then by department. Joining behaviour is unchanged by this component.

### 3. Chat-list integration

- Unread announcements add to the **Hub tab badge** (count, 99+), not to the chat badge.
- A colleague conversation started from the profile sheet appears in the chat list like any conversation, with the existing hub/route chip.
- Notification tapping an announcement opens Hub → News → that announcement (deep route `hub/announcement/<id>`).

## Why it was chosen

The owner asked for the tab, for Transfers to give way to it, and for deep integration. A single tab with four segments keeps the bottom bar at
four entries, makes the tab set a pure function of enrolment, and keeps every organisational thing in one place. See ADR-133.

## Visual specification

Use only `FlashTheme` tokens. No new colours without an entry in `docs/ui/design-system.md`.

- Top bar height and title style: same as `FlashChatListTopBar` (`FlashDimensions`, `FlashTypography.titleLarge` equivalent token the chat list uses).
- Segmented control: pill shape from `FlashShapes`, selected segment uses the accent container colour already used by the bottom nav
  selection, label `FlashTypography` label style, min touch target `FlashDimensions.minTouchTarget` (48 dp).
- Rows: min height 64 dp (people) / auto (announcements). Avatar 44 dp (`FlashAvatar`).
- Announcement card: surface container, `FlashShapes` card radius, 16 dp padding (`FlashSpacing`), scope chip uses the department chip style of
  `FlashBrowseOrgGroupsSheet` (reuse, do not restyle).
- Role badge: small text chip ("Admin", "Head"), never colour alone.
- Online dot: the existing presence dot; hidden for people whose online status is withheld (no dot, not a grey dot).
- **New icon (UI-002):** `FlashIcons.Hub` (organisation: a small building/network glyph) and `FlashIcons.Announcement` (megaphone). Both drawn in the
  existing icon style (24 dp grid, 2 dp stroke, rounded caps, see `docs/ui/icon-system.md`). No stock Material icons (AGENTS.md §34). The icon doc
  must be updated before the implementation.
- Tab label: "Hub" (localised string, ≤ 8 characters). If the organisation has a `hubName`, the screen title is that name, the tab label stays "Hub".

### States (every one must exist; none may be a blank screen)

| State | What the user sees |
|---|---|
| Loading first time | Skeleton rows (`docs/ui/loading-states.md`), no spinner over content |
| Loaded, fresh | Normal; status strip "Connected · updated just now" |
| Loaded, cached, hub unreachable | Content from the cache; strip "Offline · showing information from <relative time>" (not an error colour, a calm info tone) |
| Cached and lease expired (Plan B) | Strip in warning tone: "Reconnect to the company network to keep colleague trust up to date" with a "Why?" link to a short sheet; content still readable |
| Never synced and offline | Empty state "Connect to the company network once to load your colleagues" with a Retry button |
| Hub says this device is revoked / unenrolled | Whole tab replaced by the existing `FlashHubNotice` text and a "Sign out of organisation" action; tab disappears on the next recomposition after unenrol |
| Search with no match | "No one named …" with the query shown |
| Hidden department deep link (not allowed) | "This department is not visible to you" (never reveals whether it exists) |
| Announcement deleted while open | Reader closes with "This announcement was removed" |
| Post failed | Composer stays open, text kept, banner "Could not post. Try again", Retry; offline posting is **not** queued in v1 (see announcements plan) |
| Rate limited | "You are posting too fast. Try again in <n> s" |
| Over size | Counter turns warning at 90 %, send disabled at limit with the limit stated |
| Clock wrong (> 5 min off) | Strip "Your device clock looks wrong. Colleague trust may fail until it is fixed" |

## Interaction specification

- Tab tap: switches; reselect scrolls the active segment to top; second reselect jumps to News.
- Pull to refresh on every segment triggers the directory/announcement sync (rate-limited client-side to 1 per 15 s; shows "Updated just now").
- Search icon: expands an inline field (not a new screen); results grouped People / Departments / News / Groups; keyboard Enter opens first hit.
- Long-press a person: same as tap (no hidden actions); long-press an announcement (author/admin): Edit / Pin / Delete menu (use `docs/ui/context-menu.md`).
- Desktop: arrow keys move through lists, Enter opens, Ctrl+F focuses search, Esc closes sheets; right-click gives the same menu as long-press.
- Back: sheet → segment content → previous tab root (existing back-stack rules; Hub is a tab root).

## Animation specification

Use `FlashMotion` tokens only. Segment change: horizontal fade-through (≤ 200 ms). Unread dot disappears with a 120 ms fade when the card is
≥ 60 % visible for 1 s (read marking, debounced). New announcement arriving while the screen is open slides in at the top with the standard list
insertion animation and does **not** move the user's scroll position unless they are at the very top. Reduce motion: all of the above become
instant swaps, no sliding.

## Gesture specification

No custom gestures beyond pull-to-refresh and the existing swipe between tabs if the host has it (it does not today; do not add). The segmented
control must not conflict with horizontal scrolling of a segment's content: segments are switched by the control only, not by swipe.

## Accessibility requirements

- Every row announces: "<name>, <department>, <role>, <online/offline>". Announcement cards announce scope, title, author, time, unread state.
- Segmented control uses `Role.Tab` semantics with selected state. Badge on the tab has a content description ("Hub, 3 unread announcements").
- Online state is never colour only (dot has text in the semantics and in the profile sheet).
- Dynamic type to 200 %: rows grow, nothing is truncated without a way to read it (reader screen shows the full text).
- TalkBack live region on the status strip when it changes.
- Contrast ≥ 4.5:1 for all text in both themes; warning strip not colour only (has an icon and text).

## Responsive behavior

- Phone: as drawn. Large phone/tablet portrait: same, content max width 640 dp centred.
- ≥ 600 dp (rail): list pane = segments, detail pane = person/announcement/department (`docs/ui/responsive-layout.md`, ADAPTIVE-UI-PLAN AD-3 pattern); sheets become detail panes.
- Desktop: sidebar entry "Hub", list/detail layout identical to tablet; window minimum 640×480 dp must stay usable (segmented control scrolls).
- Landscape phone: top bar collapses on scroll.

## Dark-mode behavior

Uses the deliberate dark palette tokens; announcement cards use the elevated surface token, pinned ones get the accent outline, not a fill inversion.
Warning strip uses the dark warning container token.

## Performance considerations

- People list is a `LazyColumn` with stable keys (`deviceId`), paged from Room (never load 5,000 rows into memory); avatar colours are computed from the id (no image
  loading).
- Search is done in the database (indexed `LIKE` / FTS only if the implementer measures a problem, record it as an experiment).
- Announcement bodies are rendered lazily (first 3 lines in the card), full body only in the reader.
- Cold-start cost: the tab must not trigger a network call until it is opened the first time after process start, except the background sync owned by Plan B.
- Recomposition: sections receive immutable UI models (`@Immutable`), never repository objects.

## Implementation notes

Package targets (verify they exist, the implementer must read the code first):

- `ui/chat/src/commonMain/.../hub/` (new package): screens and `FlashHubUiState` models. `ui/chat` already hosts shared screens for Android and desktop.
- `ui/chat/.../navigation/FlashNavigation.kt`: add `FlashDestination.Hub`, make `TabOrder` a function of enrolment (see plan §4).
- Hosts: `app/.../MainActivity.kt` and `desktop/.../DesktopShell.kt`, `DesktopSideBar.kt`.
- Repository: `FlashChatRepository` gets read-only flows (`hubDirectory`, `hubDepartments`, `hubAnnouncements`, `hubUnreadAnnouncements`) and the actions in the plan; **UI never calls the hub client directly**.
- No new third-party dependency is needed; adding one requires its own documented decision (AGENTS.md §3).
- Strings: add to the existing string resources of both hosts; no hard-coded strings.

## Testing checklist

- [ ] Compose preview for every state in the table
- [ ] Physical device (Android phone, tablet) and Windows desktop
- [ ] Dark mode
- [ ] Large font / display size 200 %
- [ ] RTL
- [ ] Reduced motion
- [ ] Performance spot-check with 5,000 directory rows (scroll jank, memory)
- [ ] Offline: airplane mode, cached content readable
- [ ] Enrol → tab appears; unenrol (and revoked) → tab disappears and the app lands on Chats without a crash or a stale selected tab
- [ ] Saved navigation state containing Transfers or Hub restores correctly after enrolment changes
- [ ] TalkBack pass

Unit tests are specified in the plan; device tests `HUBT-*` are in `docs/testing/TEST-BACKLOG.md`.

## Known limitations

- One organisation per device (FO-18 parked). No reporting lines or org chart. Announcements are plain text, visible to the hub operator (owner decision D5).
- Online dots depend on the hub's presence view and are absent for hidden departments (D2).
- A colleague in a hidden department cannot be found by name outside their department; they can still be messaged if their id is known.

## Future improvements

Acknowledge-required announcements, scheduled posts, attachments, reactions, org chart, multi-organisation switcher (FO-18), out-of-office status. All parked in
`docs/FUTURE-OPTIMIZATION.md` / the roadmap "ideas not decided"; none is part of UI-058 v1.

## What makes this Flash?

It is not a corporate intranet bolted onto a chat app: the same people list tells you, per colleague, whether they are one tap away on your Wi-Fi, reachable only
through the company server, or offline, and it keeps working when the server is not (Plan B). The tab disappears completely for people who never joined an
organisation, so the consumer app stays what it is.

---

## Implementation notes (H6-H10, 2026-10-15)

Built as designed except for the following, each deliberate and listed so the next reader does not take them for omissions.

- **Per-device memory is host-side, not in `core/persistence`.** The last segment, the one-time "Transfers moved" tip and unsent announcement
  drafts live in `FlashHubUiPrefs` / `FlashHubDraftStore`, backed by SharedPreferences on Android (`HubUiPrefs`) and a properties file on the PC
  (`DesktopHubUiPrefs`), because the persistence module was off limits for this stream. Drafts are cleared when enrolment is known to have ended.
- **Routes** (`hub/announcement/<id>`, `hub/person/<deviceId>`): Android takes them from the explicit intent extra
  `com.transfer.flash.extra.HUB_ROUTE` (no new manifest filter, nothing exported that was not already), the PC from `--hub-route=<route>`.
  They wait until enrolment is known, so the cold-start default "not enrolled" never throws a route away. A second desktop instance does not forward
  `--hub-route` to the running one (not built).
- **Detail panes.** The Hub lays out its own two-pane view at 840 dp and above (person, department, announcement); below that the detail is a
  sheet or a full-screen reader. It does not use the shell's shared detail pane.
- **Pairing** is not implemented: `onPairColleague` is a hook the hosts leave null, so "Pair with this colleague" is never shown today.
- **Online people search** (people the local snapshot does not contain) is not built; there is no API for it in `:core:enterprise`.
- "Review group join requests" in the Groups segment reaches the old `FlashBrowseOrgGroupsSheet` (marked SUPERSEDED, still used by administrators).
- Tests: view maths in `FlashHubMath*Test`, `FlashHubSituationsTest`, `FlashHubRouteLifecycleTest`, `FlashHubSingleFlightTest`, the
  no-dead-actions checklist `FlashHubNoDeadActionsTest` (JVM source scan), synthetic scale `FlashHubScaleTest`; repository facade in `HubFacadeTest`.

## Visual refresh (2026-10-15, owner: "underwhelming ... make it professional")

Status: IMPLEMENTED, unit-tested (`FlashHubMathTest` overview cases), NOT seen on a device (HUBT-35).

- **Overview card** at the top of every segment (not in search): organisation monogram tile, organisation name, "42 colleagues · 5 departments · 3 groups",
  a role chip ("Member · Software", "Department head · Finance", "Administrator"), a "Can post announcements" chip for people who may post and a
  Connected / Offline dot. The top bar title is now "Hub" (the name moved into the card, so it is not shown twice).
- **Segments** carry an icon and, except News, a count (News keeps its unread badge).
- **News**: people who may post get a "Share an update" row (audience hint); everyone else gets one calm sentence saying announcements come from
  administrators and department heads (the old screen showed nothing, which looked like a bug). "Pinned" and "Latest" sections; cards show the
  author's avatar, role chip and time, a coloured edge for unread (strong) or pinned (soft), and a scope chip; empty states say who posts.
- **Composer**: "Visible to: <audience>" line, a real switch for "Pin to the top", the failure banner is a tinted box with an icon.
- **Departments**: monogram tile per department. **Groups**: avatar per group.
- No new dependency, no new colour tokens, no wire or repository change.
