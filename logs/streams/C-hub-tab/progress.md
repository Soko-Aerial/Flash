# C-hub-tab progress (stream C, branch agent/C-h6-h10): Plan A4 phases H6-H10

Sandbox: cloud container, JDK 21, Android SDK 36 + 37.0 installed under /opt/android-sdk (sdk.dir in the git-ignored local.properties).
Gradle runs with `-Dorg.gradle.java.home=<jdk21>` because gradle/gradle-daemon-jvm.properties asks for Java 25 (the foojay download is not
reachable here). Maven Central answers 429 under parallel load, so every Gradle call is retried by a wrapper until it stops hitting 429.

## Explain-back H6 (written before code)

**What and why.** The Hub screens (H7) must never talk to the hub client or to Room. H6 is the read-model they bind to: a facade on
`FlashChatRepository` that turns the Plan B directory snapshot (members, departments, sync state, lease) into immutable UI models
(`HubUiState`, `HubPersonUi`, `HubDepartmentUi`, `HubGroupUi`), sorted and filtered in the data layer, never in composition, so 5,000
colleagues stay smooth. `refreshHub` is the one action: single-flight, at most one real attempt every 15 s, it asks the host's
`OrgSnapshotCoordinator` to sync and then refreshes announcements. Hidden departments are filtered defensively on the client too (the server
already withholds them, A2), so an old or wrong snapshot cannot show a department the caller may not see.

**Files.** New: `core/messaging/.../hub/HubFacade.kt`, `hub/HubTabSources.kt`, `model/HubTabModels.kt`, tests `HubFacadeTest` (jvmTest, fake
`OrgDirectoryDao`, no Room). Touched (tiny): `FlashChatRepository.kt` (default members at the end), `RealFlashChatRepository.kt` (one
constructor parameter, one lazy facade, delegating overrides, two invalidation hooks), `SampleFlashChatRepository.kt` (sample data),
`DiscoveryEngineHolder.kt` + `DesktopEngine.kt` (the constructor argument: the facade needs `db.orgDirectoryDao()` and the coordinator, which only
the engines own; this is outside the file list of the prompt but the plan's H6 cannot work without it; hunks reported in the final report).
Three most similar existing classes read: `HubGroupRosters` (snapshot-backed read model with refresh/clear), `RealOrgPeerResolver` (in-memory
index over the same DAO, lease flow), the A3 announcements block in `RealFlashChatRepository` (single-flight mutex + Flow from Room). Reused:
its mutex pattern, `foldForSearch`, `OrgLease`/`OrgPeerResolver`. Deliberately different: no Room Flow exists on `OrgDirectoryDao` and
`core/persistence` is off limits, so changes are signalled by an invalidation counter fired from the same refresh hook the coordinator already calls.

**Five likely failures.** (1) A snapshot read racing a sync commit shows half a directory: reads are one `listAllMembers` + `listDepartments`
pair after the commit and the invalidation fires only after the commit (hook in refreshResolver); a stale read is replaced by the next
invalidation. (2) Unenrol while the screen is collecting: flows are keyed on the credentials flow, so they emit empty at once and stop
touching the DAO. (3) 5,000 rows recomputed on every presence tick: reachability is joined in a cheap map pass, the sort is done once per
snapshot, tested with a synthetic 5,000 list. (4) Hidden department leak: the filter is applied in the load step and has its own test and
mutation check. (5) `refreshHub` called twice at once or twice in 15 s: Mutex plus completion timestamp, tested with virtual time.

**Tests first.** `HubFacadeTest`: ordering/folding (diacritics, case), hidden-department filter (own dept, admin, other), search (word prefix,
department, empty query, no match), role mapping (admin/dept_admin/heads list), paired/reachability join, unenrol empties, 0/1/5,000 rows,
`refreshHub` single-flight / min interval / outcome mapping / announcements refresh, and a fuzz of malformed `headDeviceIdsJson`.

## H7 - screens (commit 78fee77)
Built `ui/chat/.../ui/hub/`: `FlashHubScreen` (status strip, four segments, wide two-pane), News (cards, reader, composer with audience and
counters), People (filters, sticky letters, person sheet / pane), Departments, Groups, combined search, empty/error/revoked states, ~25 previews, all
strings in `FlashHubCopy` shared by both hosts, pure logic in `FlashHubMath` (tests first). `FlashBrowseOrgGroupsSheet` stays, marked SUPERSEDED.

## H8/H9 - hosts (commits 8829905, c3d8003)
Message colleague through `startColleagueConversation` (single-flight, ignores a result for a person no longer selected, a paired person opens the
existing conversation, exact Plan B sentences); Call only on the LAN when `PeerTrustPolicy.mayRing` and the host's call gate agree; `onPairColleague`
hook left null (pairing is not this stream). Routes `hub/announcement/<id>` / `hub/person/<deviceId>` via the intent extra
`com.transfer.flash.extra.HUB_ROUTE` (Android) and `--hub-route=` (PC), waiting for enrolment to be known; unenrolled lands on Chats with a notice.
Unread badge on the Hub tab (bottom bar, rail, sidebar). Transfers notification actions still go through `nav.openTransfers()`.
Persistent Hub prefs and draft stores on both hosts (ADR-170).

## H10 - verification
Tests added: `FlashHubSituationsTest` (13), `FlashHubSingleFlightTest` (4), `FlashHubRouteLifecycleTest` (14), `FlashHubNoDeadActionsTest` (4, JVM
source scan), `FlashHubScaleTest` (5, synthetic), `FlashHubSearchCodecTest`, `FlashHubMathTest` (79), `HubFacadeTest` (33), `DesktopHubUiPrefsTest` (6, compiled
only). Mutation checks (both restored with `git checkout`): (1) `FlashHubMath.callAllowed` without `PeerTrustPolicy.mayRing` -> `FlashHubSituationsTest.anUnverifiedUnpairedPersonOnTheLanIsNeverRung`
and `FlashHubSearchCodecTest.callNeedsTrustLanReachAndTheHostGate` failed; (2) `routeDecision` without the `!enrolmentKnown -> Wait` line ->
`FlashHubRouteLifecycleTest.coldStartWaitsForEnrolmentThenOpensTheHub`, `.anUnknownEnrolmentNeverReportsNotEnrolled` and `FlashHubSearchCodecTest.routeWaitsUntilEnrolmentIsKnownThenOpensTheHubOrSaysNotEnrolled`
failed (5 failures of 46 in the three classes). Earlier, H6's own mutation checks are in the H6 section above.

Gate results (last full runs, see the final report for exact lines):
`:ui:chat:jvmTest` 601 passed; `:ui:chat:testAndroidHostTest` 592 passed; `:core:messaging:testAndroidHostTest` 594 passed;
`:desktop:compileTestKotlinJvm` and `:app:compileDebugKotlin` compile clean; `:core:messaging:jvmTest` 298 completed with 1 failure that is a
pre-existing flake (errors.md C-1, reproduced on a clean origin/dev worktree, the affected classes pass in isolation).
