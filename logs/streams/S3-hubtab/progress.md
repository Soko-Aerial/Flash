# S3 Hub tab shell: progress (stream S3-hubtab, branch agent/S3-hubtab)

## Explain-back (written before code, contract section on explain-back)

Task: UI-058 / `docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md` phases H0-H5 only.

- Enrolled devices (signal: hub enrolment credentials present, only trusted once the engine is ready) get a Hub tab in Transfers' slot (ADR-133, D1). Transfers becomes a pushed destination, opened by an icon in the chat-list top bar. Unenrolled devices must see no change at all.
- H1 icons (Hub, Announcement). H2 pure navigation math with tests first. H3 Android host wiring. H4 desktop host wiring. H5 chat-list top bar (Transfers icon with active count, "Browse department groups" leaves the top bar when enrolled).
- Out of scope: H6+ (Hub data screens need B1 snapshot, A2 visibility, A3 announcements). Hub shows an honest placeholder.
- Risks I will test: process-death restore while enrolment is unknown, un-enrol while on Hub, notification/shortcut/share entry points that used `selectTab(Transfers)`, tab highlight while Transfers is pushed, exhaustive `when` over `FlashDestination` in both hosts.
- Files I may not edit: RealFlashChatRepository.kt, Flash.kt, DiscoveryEngineHolder.kt, DesktopEngine.kt, server/*, core/persistence/*.

## 2026-10-14 — H0-H5 built (UI-058, Hub tab shell)

### H0 answers (read before code)
- `FlashNavigationState.selectTab` REPLACES the whole stack with one root; `navigate` pushes. So "Transfers pushed over Chats" needs `navigate`, not `selectTab`. Added `openTransfers()` for that.
- Saver encodes `DestinationName|conversationId` (names, never ordinals), so appending `Hub` to the enum is safe.
- `pendingShortcutTab` (MainActivity) is a `MutableStateFlow<FlashDestination?>`; set by notification/shortcut intents (`ACTION_VIEW_TRANSFERS`, `ACTION_OPEN_FILE` = Transfers) and consumed by a `LaunchedEffect` in the shell, which now calls `openTransfers()` for Transfers.
- `settings.isEnterpriseEnrolled` is reactive (`enterpriseCreds != null`, a collected StateFlow). Enrolment is trusted only when `engine.ready`.
- Existing tests touching `TabOrder`/navigation: `FlashNavigationLogicTest` (27 tests), unchanged and green.

### Changed
- `ui/theme`: icons `Hub`, `Announcement` (+ `docs/ui/icon-system.md` addendum).
- `ui/chat` navigation: `FlashDestination.Hub` (last), `FlashNavigationMath.tabOrder/tabIndex/isTabRoot/transitionFor/shellTab/normalised` (all with `hubEnrolled` defaults = old behaviour), `FlashNavigationState.hubEnrolled/updateHubEnrolled/openTransfers/shellTab`, saver marker `@hubEnrolled|0/1`, `FlashAnimatedScreen(hubEnrolled)`.
- `ui/chat` new: `shell/FlashShellTabs.kt` (single tab source), `hub/FlashHubPlaceholderScreen.kt` (honest empty Hub + "Browse department groups" so the action is not stranded), `chat/FlashChatListTopBarMath.kt`; top bar and chat list screen got `onTransfersClick`, `transfersActiveCount`.
- Android `MainActivity.kt` (hosts compiled): one tab source, enrolment effect, `openTransfers` at the shortcut consumer, Hub branches, `nav.shellTab` highlight, top-bar wiring, dead `bottomNavTabs` removed.
- Desktop `DesktopShell.kt`/`DesktopSideBar.kt`/`DesktopMain.kt`: sidebar and compact bar from the shared tabs, tray "Transfers" and Ctrl+2 via `openTransfers`, Esc leaves a pushed Transfers.

### Evidence table
| Claim | Evidence |
|---|---|
| Unenrolled behaviour unchanged | `FlashNavigationLogicTest` 27 green unchanged; `unenrolled tab order is exactly the legacy four` |
| Hub nav rules | `FlashHubNavigationTest` 18 tests, `:ui:chat:jvmTest` and `:ui:chat:testAndroidHostTest` |
| Top-bar text rules | `FlashChatListTopBarMathTest` 4 tests |
| Mutation check | removing the Hub filter in `normalised` failed 3 tests (unenrol-on-Hub, old-save restore, totality) in the standalone harness; restored |
| Android host compiles | `:app:compileDebugKotlin` BUILD SUCCESSFUL |
| Desktop host compiles | `:desktop:compileKotlinJvm` BUILD SUCCESSFUL |
| Whole `:ui:chat` | jvmTest 452/0 fail, testAndroidHostTest 452/0 fail |

### Adversarial self-review (findings)
1. `FlashNavigation.kt` `FlashNavigationState` constructor: a fresh cold start of an enrolled device begins with `hubEnrolled=false`, so the old four tabs show until `ready`. Only the saved-state path avoids it. Documented limitation (HUBT-13/HUBT-11 owed).
2. `normalised`, un-enrol with `[Hub, Transfers]`: result `[Chats, Transfers]` has Transfers as a second tab-root-like entry; back goes to Chats. Harmless, but a stack with two tab roots; not what `selectTab` produces.
3. `MainActivity.kt` top-bar `transfersActiveCount`: counts Offered/Queued/Transferring/Verifying, not Paused. A paused transfer shows no indicator. Owner decision recorded as ADR-160 proposal.
4. Same site: reads `domainTransfers` (paced 75 ms) inside the chat-list lambda only when enrolled, to protect EXP-012; not measured. Backlog `HUBT-17`.
5. `DesktopShell.kt` Ctrl+2 now pushes Transfers when enrolled; Hub has no shortcut (slot 2 is kept for muscle memory, per plan). Hub is mouse-only on desktop.
6. `DesktopShell.kt` Esc: the new branch comes before the Conversation branch, so Esc on a pushed Transfers pops it even if a conversation is still selected; the conversation is closed by the next Esc. Acceptable, unverified on a PC.
7. `MainActivity.kt` two-pane `when`: Hub uses the full width (no detail pane); H7 must replace it.
8. `FlashHubPlaceholderScreen`: the copy is hard-coded constants (`FlashHubPlaceholderCopy`), not string resources; the project has no `:ui:chat` resource pattern for these strings (other screens use literals too). H7 must move them.
9. `FlashShellTabs.of` throws `error()` for `Conversation` (unreachable via `tabOrder`); a future tab added to `tabOrder` without a branch fails loudly in tests, not silently.
10. Saver: a marker-less old save restores as unenrolled and keeps `Transfers` as root; first `ready` tick then rewrites it to Chats+Transfers (one visible transition). Tested, not seen on a device.
11. `updateHubEnrolled` is not called while `!ready`: if the engine never becomes ready (start error), an enrolled device keeps the old tab set. Intentional (credentials unknown).
12. Bottom bar/rail badge for Hub is always null (A3 not built); no placeholder count.

### Extra situations tested (beyond the plan)
Totality/idempotence of `normalised` over mixed stacks; depth cap keeps root; malformed saver marker; marker-less legacy save; badge arithmetic at `Int.MIN_VALUE`/`Int.MAX_VALUE`; repeat `openTransfers`; share-effect with Hub.

### Not done (honest)
- H6+ (everything needing B1/A2/A3), Hub data screens, string resources for Hub copy, Hub shortcut, 320 dp / 200 % font layout check of the top bar (needs a device or preview run; the bar now holds LAN, Search, New group, Join link, Transfers = 5 icons at 48 dp = 240 dp plus title; **may overflow at 320 dp**, `docs/ui/chat-list.md` overflow-menu rule not applied yet).
- No device run of anything; nothing is called device-verified.
- `:desktop:jvmTest` did NOT complete: it hung for 37 minutes in `DesktopEngineTlsBindingTest` (real TLS socket engine test, unrelated to the shell) in this sandbox and was killed; no desktop test result is claimed. Instead `:desktop:compileKotlinJvm` and `:desktop:compileTestKotlinJvm` are green. No desktop test references the changed `DESKTOP_SIDE_TABS`.
- `:ui:theme` tests were not run separately (only a pure icon accessor was added).

### Build environment note (sandbox, not product code)
Gradle needed (1) an Android SDK (installed from dl.google.com into the scratchpad, platform 37.0, build-tools 36.0.0, `local.properties` is git-ignored) and (2) a user-level init script mapping `repo.maven.apache.org` (HTTP 429 from this sandbox) to the Google-hosted Maven Central mirror already listed in `settings.gradle.kts`. Nothing in the repository was changed for this.

### Gate commands (final, this sandbox, own Gradle without the PC lock)
```
./gradlew --no-daemon :desktop:compileKotlinJvm :desktop:compileTestKotlinJvm :app:compileDebugKotlin :ui:chat:jvmTest
BUILD SUCCESSFUL in 58s
134 actionable tasks: 2 executed, 132 up-to-date
```
Earlier: `:ui:chat:jvmTest :ui:chat:testAndroidHostTest :desktop:compileKotlinJvm :app:compileDebugKotlin` BUILD SUCCESSFUL (452 tests / 0 failures on each of jvmTest and testAndroidHostTest).
Could not run: `:desktop:jvmTest` (hang above), any device or emulator check, `:core:*` suites (untouched).

### Shared files touched (hunks)
`FlashNavigation.kt` (ui/chat, owned), `FlashChatListTopBar.kt` (+2 params, 1 block), `FlashChatListScreen.kt` (+2 params, 2 pass-through lines), `FlashIcons.kt` (+4 lines), `MainActivity.kt` (about 44 insertions / 46 deletions: tab lists, enrolment effect, `openTransfers`, Hub branches, top-bar args), `DesktopShell.kt` (about 40 lines: effect, tabs, Esc, tray/shortcut, top-bar args, Hub branch), `DesktopSideBar.kt` (tab function), `DesktopMain.kt` (1 line).
