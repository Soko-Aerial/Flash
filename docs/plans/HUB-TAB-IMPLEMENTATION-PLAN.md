# Plan A4 — The Hub tab (UI-058): navigation, top-bar Transfers, screens, repository facade

**Written:** 2026-10-14. **Status: PLANNED, nothing built.** Read `docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md` first, then the design doc **`docs/ui/flash-hub-tab.md` (UI-058, DESIGNED)** — it is the visual and interaction contract; this plan is the engineering contract.
**Owner decisions:** D1 (tab replaces Transfers when enrolled, Transfers → top bar, Browse groups moves into the tab), D2, D5.
**ADR:** ADR-133 (ACCEPTED by the owner's decision D1). **Tests owed:** `HUBT-01`… in `docs/testing/TEST-BACKLOG.md` section 4zzzzzzzzz.
**Prerequisites (do not start the UI before these exist, at least built and unit-tested):** Plan B phase B1 (directory snapshot and sync), Plan A2 (people/lookup/visibility), Plan A3 (announcements repository API). Plan A1 (heads) is needed for the "Department head" badge and the composer rights but the screens can be built against the fake repository first.
You may build phases H1–H5 (navigation, icons, top bar) **before** the prerequisites: they do not need hub data.

**Quality contract (mandatory, read before coding):** `docs/plans/HUB-QUALITY-CONTRACT.md` applies to every phase of this plan: explain-back before code, the 12 coverage categories, evidence table, banned shortcuts, adversarial self-review (>= 10 findings), phase report. **The minimum is a failure.** The section "Beyond the minimum" below is required work, not a wish list.

---

## 1. Ground truth (read 2026-10-14; VERIFY every symbol)

- `FlashDestination` (ui/chat/src/commonMain/.../ui/navigation/FlashNavigation.kt): `ChatList, Conversation, Transfers, NearbyDevices, Settings`.
- `FlashNavigationMath.TabOrder = [ChatList, Transfers, NearbyDevices, Settings]` is static; `tabIndex`, `isTabRoot`, `transitionFor` (assumes Conversation is the only non-tab-root), `HOME_TAB`, `pushed`, `popped`; `FlashBackStackState.isValid`; `FlashNavigationState` holds the back stack as one immutable list in `mutableStateOf` with `rememberSaveable` through `FlashNavigationStateSaver` (drops invalid/unknown entries).
- Host tab lists that must agree with `TabOrder`: Android `MainActivity.kt` `bottomNavTabs` (~681), `liveBottomNavTabs` (~1835), `navigationRailTabs` (~1850); desktop `DesktopSideBar.kt` (~55–58), `DesktopShell.kt` `bottomNavTabs` (~2484).
- Transfers references to review one by one: `MainActivity.kt` ~464/475 (shortcut routing via `pendingShortcutTab`), 1081, 2834, 2930, 2946, 3068, 3090; `DesktopMain.kt` ~228; `DesktopShell.kt` ~1620, 1915, 2016, 2348. Re-find with `grep -rn "Transfers" app desktop ui`.
- Chat-list top bar: `FlashChatListTopBar(onSearchClick, title, onLanClick, onNewGroupClick, onJoinWithLinkClick, onBrowseOrgGroupsClick)`; Browse sheet `FlashBrowseOrgGroupsSheet`; `onBrowseOrgGroupsClick` shown only when `settings.isEnterpriseEnrolled` (MainActivity ~2775/3007; DesktopShell ~1137/2121).
- Bottom bar: UI-046 "hanging bar" (`FlashBottomNav`, `FlashBottomNavItem(destination, icon, label, badgeCount)`), `docs/ui/bottom-nav.md`; rail for ≥ 600 dp (AD-6); desktop sidebar.
- Icons exist: Chat, Transfer, Nearby, Settings, Group, Device, Relay, Verified, Search, Pin, Flag, Bolt, Tray, Share. **No Hub/Announcement icon.** Icon system doc: `docs/ui/icon-system.md`. Design tokens: `FlashTheme`, `FlashColors`, `FlashTypography`, `FlashShapes`, `FlashSpacing`, `FlashMotion`, `FlashDimensions` (`minTouchTarget`), `FlashText`.
- Enrolment state in the UI: `settings.isEnterpriseEnrolled` (VERIFY whether it is a `State`/`Flow` that updates live on enrol/unenrol and revoke).

## 2. Design decisions this plan implements (from `docs/ui/flash-hub-tab.md`)
1. Enrolled → tabs Chats · **Hub** · Nearby · Settings; not enrolled → Chats · Transfers · Nearby · Settings (unchanged).
2. Transfers = icon button in the chat-list top bar (enrolled only), with an active-transfers indicator; opens Transfers as a **pushed** destination.
3. "Browse department groups" leaves the top bar and becomes the Groups segment of the Hub screen.
4. Hub screen: top bar (organisation name, search), status strip, segmented control News · People · Departments · Groups; person sheet; department screen; announcement reader/composer.
5. Hub tab badge = unread announcements.

## 3. Phases

### H0 — Read and verify (no code)
Open every file in §1; answer in §10: Does `FlashNavigationState` replace the stack when a tab is chosen (so Transfers pushed on top of ChatList is possible)? Where is `pendingShortcutTab` consumed? How do notifications open Transfers? Is `isEnterpriseEnrolled` reactive? What tests exist (`FlashNavigationMathTest`, saver tests)? Record the list of existing tests that touch `TabOrder` so none is weakened.

### H1 — Icons (UI-002)
- Update `docs/ui/icon-system.md` first: add `Hub` (organisation glyph) and `Announcement` (megaphone) with grid, stroke, and keyline details (follow the existing entries), plus the accessibility names.
- Implement them in `FlashIcons` in the same drawing technique as the existing icons (VERIFY: vector `ImageVector` builders vs resources); no stock Material icons. Add to the icon preview/catalogue screen if one exists; add a unit/golden test if icons have one (`FlashIconsTest`?).

### H2 — Navigation math (pure Kotlin; unit-tested first)
Change **without breaking existing callers/tests** (new parameters have defaults equal to today's behaviour):
```kotlin
enum class FlashDestination { ChatList, Conversation, Transfers, Hub, NearbyDevices, Settings }   // append Hub; keep existing order of old entries? ordinal() must not be persisted anywhere — VERIFY the saver uses names, not ordinals; if ordinals are persisted, append Hub at the END of the enum.
object FlashNavigationMath {
    val TabOrder: List<FlashDestination> = tabOrder(hubEnrolled = false)                 // unchanged value
    fun tabOrder(hubEnrolled: Boolean): List<FlashDestination> =
        if (hubEnrolled) listOf(ChatList, Hub, NearbyDevices, Settings) else listOf(ChatList, Transfers, NearbyDevices, Settings)
    fun tabIndex(d: FlashDestination, hubEnrolled: Boolean = false): Int
    fun isTabRoot(d: FlashDestination, hubEnrolled: Boolean = false): Boolean
    fun transitionFor(from, to, hubEnrolled: Boolean = false): FlashScreenTransition   // Transfers when hubEnrolled is a push/pop destination like Conversation
    /** Rewrites a stack after enrolment changes: Hub entries are removed when !hubEnrolled (stack falls back to ChatList); a Transfers entry that was a *root* when hubEnrolled becomes ChatList + Transfers on top. Never produces an invalid stack; never empties the stack. */
    fun normalised(entries: List<FlashBackStackState>, hubEnrolled: Boolean): List<FlashBackStackState>
}
```
Plus `FlashBackStackState.isValid(hubEnrolled)`? Not needed (Hub has no id). The **saver** must accept `Hub` and unknown names gracefully (an old saved state containing `Hub` on a build without it, or saved while enrolled then restored after unenrol): run `normalised` on restore using the *current* enrolment.
Tests (`FlashNavigationMathTest` additions; keep all old tests untouched): tab order both modes; `isTabRoot(Transfers)` false when enrolled; transitions (ChatList→Hub TabForward; Hub→Nearby TabForward; ChatList→Transfers Push when enrolled; Transfers→ChatList Pop); `normalised` cases (Hub when unenrolled → ChatList; Transfers root when enrolled → ChatList+Transfers; Conversation entries preserved; empty/invalid input; depth limit); saver round trip with Hub and with garbage names.

### H3 — Android host wiring (`MainActivity.kt`)
- A single derived value `val hubEnrolled by settings.isEnterpriseEnrolled…` (collect once at the top); compute `tabs = FlashNavigationMath.tabOrder(hubEnrolled)` and use it for **all three** lists (`bottomNavTabs`, `liveBottomNavTabs`, `navigationRailTabs`). Delete duplicated literal lists; add a unit test or an assertion that the host's list equals `FlashNavigationMath.tabOrder` (extract a pure function `hostTabs(hubEnrolled)` in `ui/chat` so it is testable).
- Bottom bar item for Hub: icon `FlashIcons.Hub`, label "Hub" (string resource), `badgeCount = unread announcements` (from the repository; 0 until A3 exists).
- Every `Transfers` reference (list in §1): decide per site — (a) **navigation requests** (`navigate(Transfers)`, app shortcuts, notification taps, "View transfers" buttons) → `pushDestination(Transfers)` when `hubEnrolled`, tab switch otherwise; (b) **badge** source for the old Transfers tab → feed the top-bar indicator when enrolled; (c) **screen host** (`when (destination) { Transfers -> TransfersScreen }`) stays and works for both modes. Make a helper `openTransfers(navState, hubEnrolled)` and use it everywhere so no site is forgotten; add a test or a grep-based check (`HubTabNavigationGuardTest` in `app` host tests if the project has a pattern for such; otherwise list sites in the log).
- When `hubEnrolled` flips (enrol, unenrol, revoke): call `navState.replaceWith(normalised(...))` in a `LaunchedEffect(hubEnrolled)`; if the current screen is Hub and the user unenrols → land on ChatList with the existing hub notice; never crash, never show an empty selected tab.
- Predictive back/BackHandler interplay: Transfers pushed → back pops to the previous destination; Hub is a tab root (system back falls to HOME_TAB like other tabs).
- Process death/restore with Hub selected: restored through the saver + `normalised`.

### H4 — Desktop host wiring (`DesktopShell.kt`, `DesktopSideBar.kt`, `DesktopMain.kt`)
Same rules: one `tabs` source; sidebar entries Chats, Hub, Nearby, Settings when enrolled; Transfers button in the list-pane header (desktop equivalent of the chat-list top bar); keyboard shortcut for Transfers (VERIFY existing shortcut map; keep the same shortcut so users' muscle memory works: it now pushes); Ctrl+Shift+H? Only add a Hub shortcut if the sidebar already has numbered shortcuts — follow the existing pattern. Tray/notification actions that open Transfers go through `openTransfers`.

### H5 — Chat-list top bar changes (Android and desktop)
- Add parameter `onTransfersClick: (() -> Unit)? = null` and `transfersActive: Boolean/Int = 0` to `FlashChatListTopBar`; when non-null render the Transfers icon button (`FlashIcons.Transfer`, 48 dp target, content description "Transfers", indicator dot/count when `transfersActive > 0`). **Remove** `onBrowseOrgGroupsClick` usage from the host call sites when enrolled (keep the parameter for source compatibility until nothing uses it, then mark it deprecated; do not delete tests).
- Slot budget: LAN, New group, Join with link, Search, Transfers (enrolled). Check the layout at 320 dp width and at 200 % font scale (overflow menu rule from `docs/ui/chat-list.md`; if the bar overflows, move "Join with link" into the overflow menu — read the chat-list doc first and follow it).
- Tests: a Compose/unit test of the top-bar state (`FlashChatListTopBarMath`-style, if one exists) and previews.

### H6 — Repository facade for the Hub tab (`FlashChatRepository`)
Read-only flows and actions (names indicative; implement in `RealFlashChatRepository`, stub in `EmptyFlashChatRepository` and `SampleFlashChatRepository` so previews and tests work):
```kotlin
val hubState: Flow<HubUiState>                                   // enrolment, org name, org trust state (Plan B), last sync, clock-wrong, revoked, supported features
val hubPeople: Flow<List<HubPersonUi>>                           // from the Plan B snapshot, ordered by name; paged by `PagingSource` if the project uses Paging (VERIFY; else a bounded limit + search)
fun searchHubPeople(query: String, departmentId: String?): Flow<List<HubPersonUi>>
val hubDepartments: Flow<List<HubDepartmentUi>>                  // respects hidden departments (A2)
val hubGroups: Flow<List<HubGroupUi>>                            // absorbs fetchVisibleGroups + pendingEnterpriseJoins; join/request actions already exist (joinEnterpriseGroup etc.)
suspend fun refreshHub(): FlashResult<Unit>                      // single-flight, min interval 15 s, runs Plan B sync + announcements refresh
suspend fun startColleagueConversation(deviceId: String): FlashResult<String>   // Plan B §B2.3
// announcements: see Plan A3 §4.3
```
`HubPersonUi(deviceId, displayName, departmentName?, role: PERSON/HEAD/ADMIN, online: Boolean?, reachability: LAN | HUB | OFFLINE | UNKNOWN, paired: Boolean, orgVerified: Boolean)`. `reachability` is computed from live sessions + hub presence. All UI models `@Immutable`. No hub client types leak into `:ui:chat`.

### H7 — Screens in `ui/chat/src/commonMain/.../ui/hub/`
Create (names from the design doc): `FlashHubScreen`, `FlashHubTopBar`, `FlashHubStatusStrip`, `FlashHubSegments`, `FlashHubNewsSection` + `FlashAnnouncementCard` + `FlashAnnouncementReader` + `FlashAnnouncementComposer`, `FlashHubPeopleSection` + `FlashHubPersonRow` + `FlashHubPersonSheet`, `FlashHubDepartmentsSection` + `FlashHubDepartmentScreen`, `FlashHubGroupsSection` (moves the content of `FlashBrowseOrgGroupsSheet`; keep that composable until the Hub tab is verified, mark it SUPERSEDED, do not delete), `FlashHubEmptyState` variants, `FlashHubMath` (pure: status text selection, relative time, role chips, sort keys, search matching, segment default).
Rules:
- Every state of the design doc's state table has a preview and a unit test on the pure logic that selects it (`FlashHubMathTest`): loading, fresh, cached/offline, lease expired, never synced, revoked, search empty, deleted announcement, post failed, rate limited, too long, clock wrong.
- Use the exact strings of Plans A3 §9 and B §9 plus the design doc; add every string to **both** hosts' resources (Android `strings.xml`, desktop resource bundle or Kotlin object — VERIFY the project's pattern in `:ui:chat`), no literals in composables.
- Lists: `LazyColumn` with stable keys; people paged or bounded; never `remember { list.sortedBy… }` on 5,000 items in composition — sort in the repository/flow.
- Segmented control = `Role.Tab` semantics; last segment remembered via a setting `hubLastSegment` (add to the settings store on both hosts; default `NEWS` when unread > 0 else `PEOPLE`).
- Reduce motion: honour the existing `reduceMotion` flag as other components do (see `FlashMotion` usage in `FlashReactionChip` etc.).
- Accessibility per the doc; test with the semantics tree where the project has a pattern (ui tests in `ui:chat` jvmTest/androidUnitTest — VERIFY what is available; otherwise document manual TalkBack check in the backlog).
- Wide layouts: list pane + detail pane using the existing adaptive scaffold used for chats (read `docs/ui/responsive-layout.md`, `ADAPTIVE-UI-PLAN.md` AD-3 before touching layouts; the owner's rule in AGENTS.md §29 requires reading it).

### H8 — Starting a conversation with a colleague
`FlashHubPersonSheet` → **Message**: calls `startColleagueConversation(deviceId)` then navigates to the conversation. Handle each result: success; `NotInDirectory`, `Self`, `Removed`, `LeaseExpiredAndOffline`, `NotEnrolled`, `Unknown` with the exact sentences of Plan B §9; the button shows a progress state and ignores double taps. If the person is paired, open the existing conversation (do not create a second). The header chip logic (colleague vs paired) is Plan B's; here you only navigate.
Call button: only when `reachability == LAN` and existing call gates allow (reuse the existing call-start function and its gate).

### H9 — Notifications and routes
- Route `hub/announcement/<id>` and `hub/person/<deviceId>`: cold-start safe on Android (`Intent` extras) and desktop; if the app is not enrolled any more → open Chats with the hub notice.
- Transfers notification actions (progress/complete/offer) keep working: they call `openTransfers`.
- Add the unread-announcements badge to the Hub tab; clear individual unread marks as the reader/cards become visible (debounced, §design doc).

### H10 — Quality gate for UI-058
Checklist from the design doc: previews, physical device (phone, tablet), desktop, dark mode, 200 % font, RTL, reduced motion, 5,000-row performance spot check (record numbers in `logs/experiments.md` with device names), offline airplane test, enrol/unenrol transitions, saved-state restore, TalkBack. Update `docs/ui/flash-hub-tab.md` status (IMPLEMENTED, then VERIFIED only on the owner's PASS), `docs/ui/ui-research-index.md` (add the UI-058 row now, status DESIGNED), `logs/progress.md`, `logs/handoff.md`, AGENTS.md §29, backlog `HUBT-*`.

## 4. Situations the plan does not spell out (expectations)
1. **Enrolled but never synced and offline:** tab visible; empty state with Retry (never a blank page).
2. **Enrolment completes while the user is on the Transfers tab (unenrolled layout):** Transfers tab becomes Hub; the current screen must not vanish: normalise to ChatList + Transfers pushed (user stays on Transfers, back goes to Chats).
3. **Unenrol/revoke while on Hub, in a Hub sheet, or reading an announcement:** close sheets, land on Chats, show `FlashHubNotice`; no crash from collecting a flow whose source was cleared.
4. **Two windows on desktop?** Not supported (single window); do not add multi-window code.
5. **Rotation/resize across the 600 dp breakpoint with a sheet open:** the sheet becomes a detail pane (state preserved) — if AD-7 state continuity is not built, accept losing the open sheet but **never crash**, and note it in the log.
6. **Very long names, emoji, RTL names, 300-character department names:** ellipsize with accessibility full text; never overflow the row; test with a long-string preview.
7. **Duplicate display names:** show department; person sheet shows a short id suffix (first 4 chars) under the name.
8. **Pull-to-refresh spam:** min interval 15 s, shows "Updated just now".
9. **A person in the list is you:** show "You" and disable Message.
10. **A person is paired and also in the directory:** one row; badge "Paired".
11. **Search while snapshot is empty and online:** fall back to `searchHubPeople(online)` (Plan A2 `people?q=`); offline → "Connect once to load colleagues".
12. **Announcement body huge/at limit:** card shows 3 lines; reader scrolls; copy-to-clipboard action on long press (plain text).
13. **Link tap:** confirmation dialog `org_ann_link_confirm`; only http/https; `intent:`/`javascript:`/`file:` ignored (never launched).
14. **Draft persistence:** composer keeps the draft per scope in settings; clears after success.
15. **App upgrade:** users who were on Transfers with the old build and are enrolled: first run after the update shows the Hub tab in place of Transfers; show a one-time tip "Transfers moved to the top bar" (store a flag; dismissable; rate: once).
16. **Hub supports fewer features (older server):** the segments that need a missing feature show the exact "not supported" sentence; the tab still shows People via fallback if possible.
17. **Accessibility focus order:** top bar → status strip → segments → list; announcing the strip changes.
18. **Performance:** opening the tab must not trigger more than one network sync; recomposition counts for list rows verified by a spot check (document).
19. **Dark mode contrast and focus rings on desktop.**
20. **Memory:** people avatars drawn from initials/colour only; no bitmap loading.
21. **Logging:** `FlashLog` tag `HUB_UI` for navigation and state transitions (ids short, no names, no announcement text); add the tag `HUB_UI` to AGENTS.md section 24's recommended tag list (the owner accepted all recommendations on 2026-10-14).

## 5. Tests (names for the log)
Pure/unit (`:ui:chat`): `FlashNavigationMathTest` additions (H2); `HubHostTabsTest` (host lists equal math); `FlashHubMathTest`; `FlashHubSegmentDefaultTest`; saver tests; `FlashChatListTopBar` state tests; icon tests if present. Repository tests (`:core:messaging`): `startColleagueConversation` results; `hubPeople` ordering/search; `refreshHub` single-flight and min interval; unenrol clears flows. Desktop: `:desktop:jvmTest` shell tests if the project has any pattern (VERIFY), otherwise compile + manual.
Compile gates: `:ui:chat` tests, `:app:compileDebugKotlin`, `:desktop:compileKotlinJvm`.
Device/manual (backlog `HUBT-*`): `HUBT-01` enrol → tab appears in place of Transfers, Transfers reachable from top bar with active indicator; `HUBT-02` unenrol → tab disappears, app on Chats; `HUBT-03` People list with 5,000 entries (scroll/memory numbers); `HUBT-04` Message an unpaired colleague (hub online); `HUBT-05` same offline on LAN (needs Plan B); `HUBT-06` announcements flow (needs A3); `HUBT-07` hidden department not visible (needs A2); `HUBT-08` tablet rail + detail pane; `HUBT-09` desktop sidebar + Transfers button; `HUBT-10` dark, 200 % font, RTL, reduced motion, TalkBack; `HUBT-11` process death restore on Hub; `HUBT-12` notification tap cold start; `HUBT-13` upgrade tip; `HUBT-14` older hub fallback.

## 6. Documentation and logging checklist
`docs/ui/flash-hub-tab.md` (status), `docs/ui/ui-research-index.md` (new UI-058 row), `docs/ui/navigation.md` and `docs/ui/bottom-nav.md` (tab set depends on enrolment; Transfers in top bar), `docs/ui/chat-list.md` (top bar), `docs/ui/icon-system.md`, `docs/ui/transfers-page.md` (entry point changed), `docs/ui/settings-page.md` (new rows if any), `docs/decisions.md` ADR-133 status, `logs/progress.md`, `logs/handoff.md`, AGENTS.md §29 line, `docs/testing/TEST-BACKLOG.md`.

## Beyond the minimum (REQUIRED for Plan A4; the contract's section 3 applies on top)

1. **State-matrix math tests for every screen** (pure Kotlin, in the style of the existing `Flash*Math` files): loading, empty, offline with cache, offline without cache, error, stale, partial page, very long names, 0/1/many/5,000 items. Each state maps to the exact string and layout variant of `docs/ui/flash-hub-tab.md`.
2. **No dead actions.** Enumerate every action the design doc names (buttons, rows, menu items, gestures) in a checklist inside the log; for each, the callback, the host wiring on Android **and** desktop, and the test or manual check that proves it does something. A test lists the design doc's action names and fails if a callback is missing.
3. **Navigation property test**: random sequences of tab changes, pushes of Transfers, enrol, unenrol, revoke, process-death restore, notification taps and back presses never leave an invalid back stack (the normaliser from H2 always returns a valid stack; the saved state restores to a valid screen even if the enrolment changed meanwhile).
4. **Lifecycle**: rotation, process death while on Hub, enrol and unenrol while on each tab and while Transfers is pushed, a notification tap from a cold start, tablet rail and two-pane layout, desktop sidebar and window resize across breakpoints keep the selection.
5. **Performance** (method of UI-042/UI-043 in `docs/ui/`): stable keys in lazy lists, no O(n) work during composition, search debounce, work off the main thread; record frame timings or composition counts for a 5,000-person list as synthetic numbers in `logs/experiments.md`; the Hub tab adds no work for an unenrolled user.
6. **Accessibility pass executed, not promised**: TalkBack/Narrator order, content descriptions, 48 dp targets, 200 % font, dark and light, reduced motion, right-to-left, high contrast; record the result per screen in the log (what was checked and what was found).
7. **Desktop parity**: keyboard shortcuts, focus order, context menus, window minimum size, the sidebar badge, Transfers button with its active indicator.
8. **Upgrade path**: first run after updating shows the one-time tip once (state persisted, tested); saved navigation state from the previous version restores; an unenrolled user sees no change (a test runs the old four-tab scenarios unchanged).
9. **Empty and error states are designed, not placeholders**: every segment has the text, icon and action the design doc specifies; "Retry" actually retries and shows progress; offline shows the cache with its age.
10. **Message a colleague** (H8) works in the three situations: hub online and not paired, hub offline with an ORG colleague (needs Plan B), and a colleague who is revoked or identity-changed (honest reason, no silent failure). One test per situation.
11. **Documentation**: the six docs of the plan's section 6 updated, UI-058 status moved only as far as the evidence allows (never to "verified"), icons registered in `docs/ui/icon-system.md`.

## 7. Definition of done
- [ ] Quality contract satisfied for every phase: explain-back, evidence table, 12 categories, >= 10 adversarial findings, >= 5 extra situations, phase report (`docs/plans/HUB-QUALITY-CONTRACT.md`); the verifier prompt (`docs/plans/HUB-PLANS-PROMPTS.md`) was run by the owner or is pending.

H1–H5 done and verified by tests first; H6–H9 per prerequisites; no existing tests weakened; compile gates green; manual checklist items logged as TODO in the backlog; nothing called device-verified.

## 8. Failed/rejected alternatives (do not retry without a new reason)
- Five bottom tabs (keep Transfers and add Hub): rejected by the owner (D1).
- Making the tab list static and hiding Transfers visually: rejected; transitions and saved-state logic depend on `TabOrder` semantics.
- A separate "Announcements" tab: rejected (costs a slot).
- Nested tabs inside the Hub screen swiped horizontally: rejected (conflicts with list gestures; the segmented control is explicit).

## 9. Former open items, now decided (owner: "all recommended", 2026-10-14)
The first-run tip (situation 15) **is wanted** (shown once); `HUB_UI` **is an official log tag**; the Hub segment that opens by default for users with no unread announcements is **People** (with unread announcements: News). Nothing in this plan remains for the owner to decide; if you find a new open point, record it in the log and stop-and-ask.

## 10. Implementation log
_(empty; answers to H0 questions first)_
