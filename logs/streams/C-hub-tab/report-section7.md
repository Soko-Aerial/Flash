# C-hub-tab final report (contract section 7), branch agent/C-h6-h10, base 490ffe7

STATUS: all of H6-H10 implemented and committed locally (5 commits). NOT pushed: `git push` returns HTTP 403 ("Claude doesn't have GitHub access to
Soko-Aerial/Flash"). `logs/streams/C-hub-tab/DONE` is therefore NOT created; create it after the push.

## Gate commands (run through `gradlew -Dorg.gradle.java.home=<jdk21>` with a retry wrapper for Maven 429; not the PC lock script)
| Command | Result |
|---|---|
| `:ui:chat:jvmTest` | 601 tests, 0 failed (`BUILD SUCCESSFUL`) |
| `:ui:chat:testAndroidHostTest` | 592 tests, 0 failed |
| `:core:messaging:testAndroidHostTest` | 594 tests, 0 failed |
| `:core:messaging:jvmTest` | 298 completed, 1 FAILED, a different pre-existing flake each run (errors.md C-1). Reproduced on a clean origin/dev 490ffe7 worktree (265 completed, 1 failed). The affected classes (`RealFlashChatRepositoryColleagueTest`, `EnterpriseGroupLinkAndMultiPathTest`, `HubFacadeTest`) pass in an isolated rerun: `BUILD SUCCESSFUL`. |
| `:desktop:compileTestKotlinJvm` | compiles (includes `DesktopHubUiPrefsTest`, compiled only, NOT run) |
| `:app:compileDebugKotlin` | compiles |
| `:desktop:jvmTest` | NOT run (excluded by the brief) |

## Mutation checks (both restored with git checkout, verified clean)
1. `FlashHubMath.callAllowed` without `PeerTrustPolicy.mayRing` -> 2 tests failed.
2. `routeDecision` without the wait-for-enrolment line -> 3 tests failed.

## Owner's device checks still owed
HUBT-01..32 (`backlog-additions.md`), including HUBT-03 (5,000 people on a phone), HUBT-25 (adb route), HUBT-27 (tablet), HUBT-28 (TalkBack).

## Not done
Pairing (hook only), online people search beyond the local snapshot, second desktop instance forwarding `--hub-route`, Hub detail in the shell's shared
detail pane, reactive `paired`, a fresh outcome when the coordinator throttles a refresh, running `DesktopHubUiPrefsTest`, device verification of anything.

## Deviations the owner must know
Engine hunks in `DiscoveryEngineHolder.kt` and `DesktopEngine.kt` (outside the file list, needed to give the facade the directory DAO); Hub prefs and
drafts kept by the hosts instead of `core/persistence` (ADR-170); `refreshHub` gained a `userInitiated` parameter; the ERROR number range collides
with an existing ERROR-430.
