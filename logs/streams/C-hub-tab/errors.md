# C-hub-tab errors (merge into logs/errors.md)

Numbers are NOT assigned: the prompt reserved ERROR 430-449 for this stream, but `logs/errors.md` already holds ERROR-430 and
`docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md` gives S2 the range 430-459. The integrator assigns numbers; local ids are C-1..C-5.

## C-1 - Core test flake: SQLITE_IOERR_FSTAT / UncaughtExceptionsBeforeTest in `:core:messaging:jvmTest`

### Area
Test hygiene, `RealFlashChatRepositoryColleagueTest` and neighbours. Not caused by the Hub tab work.

### Symptoms
A full `:core:messaging:jvmTest` fails with exactly one test, a different one each run: `ORGT_U40`, `ORGT_U25`, `ORGT_U27` (all
`RealFlashChatRepositoryColleagueTest`), `testZeroCloudDumpInvariantOnGroupLink` (`EnterpriseGroupLinkAndMultiPathTest`). Messages:
`androidx.sqlite.SQLiteException: [SQLITE_IOERR_FSTAT] I/O error in the VFS layer while trying to invoke fstat() (disk I/O error)` raised from
`JdbcCipherStatement.step` while Room opens a connection, and `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` (an earlier test leaked
the exception; whichever test starts next is blamed). In one run the test worker deadlocked for 20 minutes
(`ExceptionCollector.removeOnExceptionCallback` vs `TestScopeImpl.leave` in `ORGT_U25`; thread dump saved by the agent, not committed).

### Evidence it is pre-existing
The same task on a clean worktree of `origin/dev` 490ffe7 (before any commit of this branch) fails the same way: 265 tests completed,
1 failed (`ORGT_U27`, `UncaughtExceptionsBeforeTest`). This branch adds the 33 `HubFacadeTest` tests: 298 completed, 1 failed. The facade is not
constructed in these tests (`hubTabSources` is null). Each affected class passes alone: `--tests '*RealFlashChatRepositoryColleagueTest*'
--tests '*EnterpriseGroupLinkAndMultiPathTest*' --tests '*HubFacadeTest*'` is green.

### Probable root cause (not proven)
Room background work (invalidation tracker / connection open) outlives a test that deletes its temp database directory, so the next
connection open hits a vanished file.

### Status
OPEN (not touched: `core/messaging` test infrastructure outside the Hub files is not this stream's).

## C-2 - Android Hub host never received the pending route (found in my own review, fixed before commit of the final state)
`MainActivity.kt` passed `pendingRoute = null` to the Hub host, so a notification or cold-start route was read from the intent and then dropped.
Fixed (`pendingRoute = hubRoute`, `onRouteConsumed`, `hubRoute` in the `remember` keys). Status RESOLVED in code; HUBT-25 is the device check.

## C-3 - Initials could carry a bidi override
`FlashHubMath.initials` used the first code point of a name unfiltered, so "‮evil Name" gave an initial beginning with U+202E. Found by
`FlashHubSituationsTest.aBidiOverrideInANameCannotReorderTheSpokenOrInitials`. Fixed (bidi, zero-width and control characters are dropped before
splitting). Status RESOLVED (unit-tested).

## C-4 - Announcement drafts were not persistent
`FlashHubHost.drafts` defaulted to an in-memory store on both hosts, so a draft did not survive a restart (Plan A3 says it must). Fixed with
`HubUiPrefs` / `DesktopHubUiPrefs` implementing `FlashHubDraftStore`; cleared when enrolment is known to have ended. Status RESOLVED in code,
device check HUBT-21; the desktop store's test (`DesktopHubUiPrefsTest`) is written and compiles but was NOT run (`:desktop:jvmTest` is excluded by the brief).

## C-5 - `FlashHubAction` enum was dead documentation
The enum existed but nothing used it; `onRetryGroups` had no action row. `FlashHubNoDeadActionsTest` now ties every action to a `FlashHubActions`
member and scans the screens for its use; `RETRY_GROUPS` was added. Status RESOLVED.
