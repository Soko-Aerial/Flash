# Verifier prompt: Hub tab phases H6 to H10 (Plan A4)

**Written:** 2026-10-16 by Claude Code (Sonnet 5.5) for the owner. Paste the block below into a fresh AI session that runs in the repository
(worktree root `C:\Users\KaliOxygen\Downloads\Flash`, branch `dev`). It is the second-pass check of work that a cloud agent produced and that has
not yet been independently verified (H0 to H5 were accepted in `logs/streams/V-verifier/report-3.md`).

What it checks: `docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md` sections H6 to H10 and `docs/ui/flash-hub-tab.md` (UI-058). The general verifier steps are in
`docs/plans/HUB-PLANS-PROMPTS.md` section 2; this prompt adds what is specific to these phases and to the way this machine is shared.

```
You are an independent verifier for the Flash project (Android + Windows desktop Kotlin Multiplatform chat/transfer app with an optional
company hub). A cloud implementer claims it finished phases H6, H7, H8, H9 and H10 of the Hub tab (Plan A4, UI-058). Assume the work is
optimistic until you prove otherwise. Do NOT fix anything and do NOT commit: you produce a report and a verdict. Work alone; do not spawn
subagents. Do not touch any phone and do not start any server on ports 8443 or 8444 (the owner's demo hub may be running there).

READ FIRST, completely, in this order:
1. AGENTS.md (repo root), especially sections 4, 12, 22, 34, 35 and 36.
2. docs/plans/HUB-QUALITY-CONTRACT.md  (what "done" means; the minimum is a failure)
3. docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md  sections H6 to H10 and section 4 "Situations the plan does not spell out"
4. docs/ui/flash-hub-tab.md  (UI-058: the state table, interaction, accessibility, and the "Visual refresh" section added 2026-10-15)
5. docs/plans/HUB-PLANS-PROMPTS.md section 2 (the generic verifier steps; do all of them too)
6. logs/streams/V-verifier/report-3.md (how the last verification was written; match its format) and logs/streams/C-hub-tab/
   (the cloud agent's own report-section7.md, progress.md, errors.md, decisions-additions.md, backlog-additions.md)
7. logs/handoff.md (top 80 lines) and the newest 6 entries of logs/progress.md

WHAT TO VERIFY (the commits, oldest first; use `git show --stat <hash>` and read the code, not only the messages):
  a31273c2  H6  repository facade for the Hub tab read model
  78fee778  H7  Hub tab screens, route and view math
  88299054  H8/H9  mount on both hosts, route intake and unread badge
  d853d929  merge of the cloud branch agent/C-h6-h10
  0dd2a4d1  Pair wired in the person sheet; cloud logs folded (ADR-170..172, ERROR-403..407)
Later commits changed the same area and are in scope as "post-merge changes" (check they did not break the earlier claims):
  82f8859d  bottom bar fix (ERROR-409)        2b487179  visual refresh of the Hub tab
  a7d2c3f3  announcements feed `since` fix (ERROR-410)    23b375df  catch-up fetch on reconnect and on tab open (ERROR-411)

STEPS (do all; write the result of each as PASS / FAIL / PARTIAL with evidence):
1. Scope. `git log --stat a31273c2^..HEAD -- ui core app desktop server` and `git status --porcelain`. Flag anything outside H6 to H10 that
   the cloud commits changed (weakened or deleted tests, edited old Room migrations, new dependencies, loosened security guards).
2. Lazy-pattern scan on the diff of those commits (PowerShell):
     git diff a31273c2^..HEAD -U0 -- ui core app desktop | Select-String -Pattern '^\+.*(TODO|FIXME|XXX|HACK|for now|temporary|stub|NotImplemented|@Ignore|@Disabled|if \(false|catch \([^)]*\) \{\s*\})'
   Every hit is a finding unless it is explained in a log. Also look for composables with string literals instead of FlashHubCopy / resources.
3. H6 facade. Open `RealFlashChatRepository` (hub section), `EmptyFlashChatRepository`, `SampleFlashChatRepository`. Check every member the plan
   lists exists in all three; `refreshHub` is single-flight with a 15 s minimum interval (find the test); no hub-client type leaks into :ui:chat
   (`grep -rn "core.enterprise" ui/chat/src` must be empty or justified); sorting of the 5,000-person list happens in the flow, not in composition;
   hidden departments (Plan A2) are respected in `hubDepartments` and in search.
4. H7 screens. For each state in the design doc's state table (loading, fresh, cached/offline, lease expired, never synced, revoked, search empty,
   deleted announcement, post failed, rate limited, too long, clock wrong) find the pure-logic test in FlashHubMathTest (or note MISSING) and
   the composable that renders it. Check Role.Tab semantics on the segments, stable keys on the LazyColumns, reduce-motion honoured, long and RTL
   names ellipsized, duplicate names show the department and the 4-character id suffix, "You" row has Message disabled, plurals, and that no
   sentence is a literal inside a composable. Run `FlashHubNoDeadActionsTest` and read what it really asserts (is every action wired to something?).
5. H8 conversation start. Read `startColleagueConversation` and every call site. Each result (NotInDirectory, Self, Removed,
   LeaseExpiredAndOffline, NotEnrolled, Unknown) must end in the exact sentence of Plan B section 9. Double tap must not start two
   conversations; a paired colleague must reuse the existing conversation; Call only when reachability is LAN and the existing call gate allows.
6. H9 routes and notifications. Trace `hub/announcement/<id>` and `hub/person/<deviceId>` from the Android intent extra and the desktop
   `--hub-route=` argument through `FlashHubMath.routeFromArgs/parseRoute/routeDecision` to the screen. Try hostile input in a unit test or
   scratch test (empty, huge, `../`, `hub/person/` with 10,000 characters, unknown prefix, control characters): it must never crash or
   navigate to the wrong place. Not enrolled: lands on Chats with "This device is not part of an organisation." and forgets the route.
   Transfers notification actions still work while enrolled (HUBT-32). The Hub tab unread badge is cleared only by visible cards, debounced.
7. H10 quality gate. Read the gate claims in the cloud report. Nothing may be called VERIFIED or device-verified: the status of UI-058 must be
   IMPLEMENTED until the owner reports a PASS. Check docs/ui/ui-research-index.md and docs/ui/flash-hub-tab.md for that wording, and that every
   H10 checklist item has a backlog test (HUBT-xx) with a concrete Pass line.
8. Re-run the builds yourself (section 36 of AGENTS.md: every Gradle command goes through the lock script from the worktree root; exit code 75
   means another agent is building, wait and retry; never call gradlew directly; never run `:desktop:jvmTest`, it hangs, ERROR-106; use
   `:desktop:compileTestKotlinJvm` instead):
     tools\flash-gradle.cmd :ui:chat:jvmTest :ui:chat:testAndroidHostTest
     tools\flash-gradle.cmd :core:messaging:jvmTest :core:messaging:testAndroidHostTest
     tools\flash-gradle.cmd :app:compileDebugKotlin :desktop:compileKotlinJvm :desktop:compileTestKotlinJvm
   Compare the test counts and failures with the reports. Known flakes you may meet: ORGT-U16 under load, ERROR-396, ERROR-403. A failure that
   passes alone is a flake only if you can show it passes alone; say which.
9. Mutation checks. Pick THREE rules and break each one on purpose, run the named test, confirm it fails, then restore with
   `git checkout -- <file>` for the files YOU changed only (never revert anyone else's work; check `git status` first, other sessions may have
   uncommitted edits in shared files). Suggested: (a) the 15 s refresh throttle, (b) the "You" row Message disabled rule, (c) the not-enrolled
   route decision. Report the exact line you changed and the test that failed.
10. Both hosts. The desktop half (sidebar entry, `--hub-route=`, draft and last-segment store `DesktopHubUiPrefs`, notices) must exist and be
   tested. Note: `DesktopHubUiPrefsTest` has never been run; run it (tools\flash-gradle.cmd with `:desktop:compileTestKotlinJvm` first, then
   the single test class only, never the whole desktop suite) and report the result.
11. Situations. Walk section 4 of the plan (all of them, especially 2, 3 and 5: enrolment while on Transfers, unenrol while a sheet is open,
   resize across 600 dp). For each, find the code or test, or mark MISSING. Add at least FIVE situations of your own that the plan does not
   list (for example: relay reconnect while the composer is open, a pinned announcement deleted while its reader is open, 200 % font on the
   overview card, department head with no department, announcement text with right-to-left override characters) and state what happens.
12. Honesty and docs. Does any report or doc claim device verification, "all tests pass" without numbers, or hide a failure? Are ERROR-403..407
   and ERROR-408..411 real and consistent with the code? Does every ADR (170, 171, 172) say PROPOSED or ACCEPTED according to what the owner
   decided (170 and 172 were accepted by the owner on 2026-10-16; 171 is being discussed)? Does TEST-BACKLOG.md contain HUBT-01 to HUBT-35 and
   ANN-12/13 with a concrete Pass line each?

OUTPUT: write the report to logs/streams/V-verifier/report-5.md (do not overwrite the other reports) in the style of report-3.md:
a table with one row per step above (PASS / FAIL / PARTIAL + evidence with path:line), then a ranked defect list (blocking / major / minor),
each with path:line, the failure scenario and the exact change needed, then the extra situations, then the verdict:
ACCEPT / ACCEPT WITH FIXES / REJECT for each of H6, H7, H8, H9, H10 separately. Be blunt. Do not soften. Do not edit source files; the only
files you may create or edit are the report and, if you run mutation checks, the temporary edits you restore. Add a one-line entry to
logs/handoff.md pointing at your report. Do not commit and do not push.
```
