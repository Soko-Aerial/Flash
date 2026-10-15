# C-hub-tab handoff (stream C, branch agent/C-h6-h10)

## State
H6-H10 of Plan A4 are implemented and unit-tested on this branch. UI-058 is IMPLEMENTED, not device-verified. Nothing here was run on a phone or a PC
window. `git push` of this branch failed with HTTP 403 from the cloud sandbox (no GitHub write access for the organisation); the commits are local
until the owner decides how to publish.

## Commits (oldest first)
a31273c H6 repository facade; 78fee77 H7 screens/route/math/tests; 8829905 H8/H9 host mounting, routes, badge; c3d8003 double-tap-safe Message, Android route hand-off,
situation tests; then the route/lifecycle/no-dead-actions tests; then the final commit with persistent drafts, scale test, docs and stream logs.

## What works (unit level)
Hub tab on Android and PC for enrolled devices: status strip, News (read marking, composer), People (search, filters, sheet, Message, Call, Pair hook),
Departments, Groups (join, pending, review requests for admins), routes, unread badge, persistent last segment / tip / drafts.

## Not done / owner decisions
- Pairing from the person sheet (`onPairColleague` hook is null), online people search outside the local snapshot, second-instance `--hub-route`
  forwarding on the PC, Hub detail inside the shell's shared detail pane, `paired` being reactive, a fresher `lastOutcome` when the coordinator throttles.
- `DesktopHubUiPrefsTest` compiles but was not run. `:desktop:jvmTest` was excluded by the brief.
- errors.md C-1 (flaky `:core:messaging:jvmTest`) is OPEN and not this stream's.
- ERROR numbers for C-1..C-5 and ADR-170..172 (PROPOSED) need merging by the integrator; the reserved ERROR range 430-449 collides with an existing ERROR-430.

## Next for the integrator
1. Merge the stream additions (`backlog-additions.md` into TEST-BACKLOG, `decisions-additions.md`, `errors.md`, `experiments-additions.md`).
2. Decide ADR-170 (host prefs vs settings DAO).
3. Run HUBT-01..32 on phones and the PC.

## Shared files touched (small hunks)
`core/messaging`: `FlashChatRepository.kt`, `RealFlashChatRepository.kt`, `SampleFlashChatRepository.kt`; engines `DiscoveryEngineHolder.kt`, `DesktopEngine.kt`;
hosts `MainActivity.kt`, `DesktopShell.kt`, `DesktopSideBar.kt`, `DesktopMain.kt`; docs `docs/ui/flash-hub-tab.md`, `docs/ui/ui-research-index.md`.
