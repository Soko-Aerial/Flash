# Launch prompts for five parallel agents (2026-10-14)

Companion of `HUB-PARALLEL-AGENTS-PROTOCOL.md`. Commit `b717ea50` on `dev` is the checkpoint (B0 + B1 + B2-in-review + all plans). B1 and B2 had **no recorded ACCEPT verdict** at that commit.
Assumed owner answers to protocol section 9: Q1 worktrees on E: yes, Q2 agent branches yes, Q3 per-stream log fragments yes (confirm Q3).

| Agent | Where it runs | Stream | Work | Lock needed |
|---|---|---|---|---|
| Antigravity (lead + 2 subagents) | this PC, `E:\FlashWork\S1-org-trust` (branch `agent/S1-org-trust`) | S1 | Plan B: finish B2 (fix review findings), then B3 | yes (`tools\flash-gradle.cmd`) |
| OpenCode | this PC, `E:\FlashWork\S2-server` (branch `agent/S2-server`) | S2 | Plan A2, then A1 | yes |
| Cloud 1 | cloud sandbox, branch `agent/S3-hubtab` | S3 | Hub tab H0–H5 | no (own machine) |
| Cloud 2 | cloud sandbox, read-only + gate run | V | Baseline + independent verification of B1 and B2 | no |
| Cloud 3 | cloud sandbox, docs only | F | Fact-check the plans against the code | no |

The cloud agents need `dev` pushed to `origin` first (`git push origin dev`; the owner does it). Cloud agents push only `agent/<stream>` branches.

## Common block (already inlined into each prompt below, so every prompt is paste-ready)

```text
PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
```

Local agents add: `Build and test ONLY through tools\flash-gradle.cmd <gradle args> run from your worktree root; never call gradlew directly; exit 75 means another agent is building, do other work and retry.`
Cloud agents add: `You build on your own machine without the PC lock. The Android SDK may be missing: run the JVM tasks that exist (:core:hub-protocol:jvmTest, :server:test, :core:persistence:jvmTest, :core:enterprise:jvmTest, :ui:chat jvm tests, :desktop:compileKotlinJvm) and write exactly which tasks could not run and why; never claim a task you did not run.`

---

## Prompt 1 — Antigravity, stream S1 (lead agent)

```text
You are the S1 lead agent (org trust) and may use two subagents. Worktree: E:\FlashWork\S1-org-trust, branch agent/S1-org-trust. Stream folder for logs: logs/streams/S1-org-trust/.
Reserved numbers: ADR 139-148, ERROR 400-429, EXP 025-029, probes org.*, tests ORGT-.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
Build and test ONLY through tools\flash-gradle.cmd <gradle args>, run from your worktree root. Never call gradlew directly. Exit 75 = another agent is building: do other work and retry.

TASK. The tree at commit b717ea50 contains Plan B phases B0 and B1 and a B2 implementation that is IN REVIEW and has NOT been accepted (PeerTrustPolicy, OrgPeerResolver,
isOrgPeer wiring, FlashHubStatusMath). Another reviewer will report findings to the owner. Your job:
1. Read docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md completely (sections 3, 4 Phase B2, 6, 7, 9, binding rule in 3.3: never use certificate dates).
2. Audit the existing B2 code against plan section 4 Phase B2 and the section 6 call-site table: for EVERY isTrustedPeer site fill the "Verified" column in the plan's implementation log (in your stream fragment logs/streams/S1-org-trust/plan-b-implementation-log.md; the plan file itself you may append to at section 14 only).
   Fix every deviation with a test that fails first (mutation-check each: ignore fingerprint, ignore lease, widen mayPushToTalk, isOrgPeer default must stay false).
3. Only after B2 is complete and its report written, start B3 (plan section 4 Phase B3). Do NOT start B4, B5 or B6.
SUBAGENTS (share your worktree and the build lock, so they must not build at the same time as you; the lock enforces it):
 - Subagent A "tests": writes the per-site policy tests U20-U32 from the plan and the mutation checks, never edits production code.
 - Subagent B "adversarial reviewer": after each step, reads the diff cold and writes findings with path:line, scenario, fix, into the stream fragment; the lead fixes them.
 Lead owns all production edits. Subagents never commit; the lead commits.
Deliver: contract section 7 report for B2 (and for B3 when done) in logs/streams/S1-org-trust/progress.md. Stop after B3.
```

## Prompt 2 — OpenCode, stream S2

```text
You are the S2 agent (server and console). Worktree: E:\FlashWork\S2-server, branch agent/S2-server. Stream folder for logs: logs/streams/S2-server/.
Reserved numbers: ADR 149-158, ERROR 430-459, EXP 030-034, probes dir.* and ann.*, tests PRIV-, DEPH-, ANN-. Server migrations reserved: directory step 3 = A2, admin step 4 = A1. You are the only stream that may add REST routes.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
Build and test ONLY through tools\flash-gradle.cmd <gradle args>, run from your worktree root. Never call gradlew directly. Exit 75 = another agent is building: do other work and retry.

TASK, in this order, each phase finished and reported before the next:
1. Plan A2: docs/plans/HUB-DIRECTORY-PRIVACY-PLAN.md. B1 is built at this commit (DirectoryVisibility, directory sync route, migration 2). FIRST read B1's server code and the plan's assumptions; if B1 does not provide what A2 step 5 needs, STOP and report; do not stub.
2. Plan A1: docs/plans/HUB-DEPARTMENT-HEADS-AND-INVITES-PLAN.md (start only after A2's report is written).
Do NOT start A3 (announcements). New DTOs go in new files; docs/protocol-routes.txt is regenerated per its header and RouteTableContractTest must pass; every migration has a test starting from a database made by the previous schema.
Deliver: contract section 7 report per plan in logs/streams/S2-server/progress.md.
```

## Prompt 3 — Cloud 1, stream S3 (Hub tab shell)

```text
You are the S3 agent (Hub tab UI shell). Clone https://github.com/Soko-Aerial/Flash.git, branch dev (commit b717ea50 or later), create branch agent/S3-hubtab, push only that branch.
Stream folder for logs: logs/streams/S3-hubtab/. Reserved numbers: ADR 159-168, ERROR 460-489, EXP 035-039, UI ids UI-059 and up, tests HUBT-, probes hub.*.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
You build on your own machine without the PC lock. The Android SDK may be missing: run the JVM tasks that exist and write exactly which tasks could not run and why; never claim a task you did not run.

TASK. docs/ui/flash-hub-tab.md (UI-058) and docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md phases H0 to H5 ONLY (read/verify, icons, navigation math with unit tests first, Android host wiring in MainActivity.kt, desktop host wiring, chat-list top bar changes).
Do NOT start H6 or later: they need the directory snapshot (B1), visibility (A2) and announcements (A3). Hub tab data screens stay out. Follow AGENTS.md section 34 (research-first UI rules, Flash* design system, no stock Material icons as final).
Files you must not edit: RealFlashChatRepository.kt, Flash.kt, DiscoveryEngineHolder.kt, DesktopEngine.kt, server/*, core/persistence/*.
Android host files (MainActivity.kt) cannot be compiled without the Android SDK: say so and list them as UNVERIFIED in the report; the owner compiles them on the PC.
Deliver: contract section 7 report in logs/streams/S3-hubtab/progress.md.
```

## Prompt 4 — Cloud 2, verifier V (baseline + B1/B2 verification, read-only)

```text
You are the independent VERIFIER. Clone https://github.com/Soko-Aerial/Flash.git, branch dev at commit b717ea50 or later. Do not modify product code. Write nothing outside logs/streams/V-verifier/ (create it) and push that folder only to branch agent/V-verifier.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
You build on your own machine without the PC lock. The Android SDK may be missing: run the JVM tasks that exist and write exactly which tasks could not run and why; never claim a task you did not run.

TASK 1 (baseline): run the integration gate of docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 6 on the checkout, unchanged. Report per task: executed (not FROM-CACHE), pass/fail counts, and every failure. Mark which failures are unrelated to Plan B (ERROR-106 and others) by checking git blame/log and the known-issue docs; do not guess.
TASK 2 (verify B1): use the verifier prompt in docs/plans/HUB-PLANS-PROMPTS.md for Plan B phase B1 (docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md section 4 Phase B1, tests ORGT-U01..U19). Run the lazy-pattern scans, re-run its mutation checks yourself on a scratch copy (revert after each), and give ACCEPT or REJECT with numbered, path:line-cited defects.
TASK 3 (verify B2, marked IN REVIEW): same prompt for Phase B2 including the section 6 call-site table: open every isTrustedPeer site and check it. Say plainly if B2 is incomplete.
Deliver: verdicts and evidence tables in logs/streams/V-verifier/report.md. Do not fix anything; the stream owners fix.
```

## Prompt 5 — Cloud 3, fact-checker F (plans vs code, docs only)

```text
You are the PLAN FACT-CHECKER. Clone https://github.com/Soko-Aerial/Flash.git, branch dev at commit b717ea50 or later, create branch agent/F-factcheck. You may edit ONLY docs/plans/HUB-*-PLAN.md files and write logs/streams/F-factcheck/; no product code.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
(No build is required. If you run Gradle for a fact, say which task.)

TASK. The plans HUB-DEPARTMENT-HEADS-AND-INVITES-PLAN.md (A1), HUB-DIRECTORY-PRIVACY-PLAN.md (A2), HUB-ANNOUNCEMENTS-PLAN.md (A3), HUB-TAB-IMPLEMENTATION-PLAN.md (A4) and sections 10 to 13 of HUB-OFFLINE-ORG-TRUST-PLAN.md (B4 to B6) were written from reading, before any of this code was built. They contain file paths, class and function names, line numbers, route lists, migration step numbers and module directions marked "VERIFY".
For EVERY such claim: open the code, mark it CONFIRMED, WRONG (give the correct fact with path:line) or MISSING (does not exist). Also check each plan's order dependencies against the code that B1 and B2 now provide (DirectoryVisibility, directory sync, OrgPeerResolver, schema 14, server migration 2, hub-protocol DTO files).
Output: (1) a table per plan in logs/streams/F-factcheck/report.md with claim, status, evidence; (2) a corrected copy of each wrong sentence applied in the plan file itself, each change marked "Corrected 2026-10-14 (fact-check): <old> -> <new>" so nothing is silently rewritten; (3) a list of plan assumptions that are not decided by code and need the owner.
Do not add features or change decisions (D1-D11, ADR-133..138 are final).
```

---

# Round 2 (2026-10-14, after the first integration, dev = 438a196e or later)

Round 1 is merged into `dev`. Each stream agent FIRST runs `git merge dev` inside its own worktree (merge, never rebase), then works the list below.
B1 wiring (coordinator, rate limit, Rule 8) is already done on `dev` (ORGT-U50..U58); do not redo it. Cloud agents need `dev` as a bundle (GitHub is not connected):
`git bundle create ..\flash-dev.bundle dev`.

## R2-1 — Antigravity, stream S1 (lead + 2 subagents)

```text
You are the S1 lead agent (org trust). Worktree E:\FlashWork\S1-org-trust, branch agent/S1-org-trust. FIRST: git merge dev (resolve conflicts, keep dev's version of RealFlashChatRepository.kt reaction gate and OrgPeerResolver unless you can prove yours is better).
Logs only in logs/streams/S1-org-trust/. Numbers: ADR 139-148, ERROR 400-429, EXP 025-029, tests ORGT-U59 and up.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
Build and test ONLY through tools\flash-gradle.cmd <gradle args> run from your worktree root.

TASK: close the remaining defects of logs/streams/V-verifier/report.md for B1 and B2, each with a test that fails first and a mutation check. Defects 1, 2, 3, 4 and 6 are already fixed on dev; verify that and do not redo them.
 1. (5) OrgPeerResolver clear()/refreshFromSnapshot(): the dao reads happen outside the lock, so a refresh that started before clear() can repopulate after it. Serialise them (generation counter or one mutex) and add a race test.
 2. (7) OrgSyncStateEntity lacks orgRootFingerprint and lastErrorKind (plan B1.3/B1.4, section 3 "bound to the organisation"). Add them as Room schema 15 (renumber the reservation: B4 becomes 16, A3 becomes 17; edit docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 7 and say so in your handoff), with migration 14->15 tested from a real 14 database (FlashMigrationsChainTest, FlashJvmMigrationsTest) and exported schema JSON. Use the root fingerprint in OrgSnapshotCoordinator so a snapshot built for another organisation is discarded at start even when the app was not running at the change.
 3. (10) B2.5 undelivered: callers for FlashHubStatusMath.orgTrustState (Settings row, hub status), org_trust_* string resources for every state in plan section 9, IDENTITY_MISMATCH state and notification, and the probes org.sync.start/ok/fail, org.lease.state, org.gate.allow/deny actually emitted (not only documented).
 4. (11) Missing tests: B2 U28 (restart with a real database, not an in-memory fake), U30 (revoked after sync), U31 (desktop parity); U-tags for B1 U02-U09/U17/U19; a persistence-suite test for the stale-serial filter in OrgDirectoryDao.applySyncBatch (mutation M2 survived); per-site tests for the isTrustedPeer sites at RealFlashChatRepository.kt around 1948, 3531, 5320 (mutations S1-S3 survived).
 5. (12) The hosts wire RealOrgPeerResolver unconditionally; the resolver must answer "not a colleague" whenever the device is not enrolled, tested.
 6. (13,14,15) probe revocationsCount mislabelled and hub name in the probe; delegation chain duplicated per page; backoff vars not @Volatile; startColleagueConversation removed-vs-not-member wording; orgDialCandidates starved by dueForDelivery(limit*2).
 7. Call requestOrgSync on pull-to-refresh and when the Hub tab opens ONLY if the Hub tab host code is on your branch after merging dev (S3's work is on dev); otherwise note it for S3.
Subagent A writes tests, subagent B reviews adversarially; the lead owns production edits and commits. Deliver the contract section 7 report in logs/streams/S1-org-trust/progress.md. Stop after this list; do not start B4.
```

## R2-2 — OpenCode, stream S2

```text
You are the S2 agent (server and console). Worktree E:\FlashWork\S2-server, branch agent/S2-server. FIRST: git merge dev. Logs in logs/streams/S2-server/. Numbers: ADR 149-158 (renumber your A2/A1 decisions that you called ADR-137/ADR-136: those numbers are taken; use 149 and 150 and fix every reference), ERROR 430-459 (ERROR-430 is yours), EXP 030-034, tests ANN-/PRIV-/DEPH-.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
Build and test ONLY through tools\flash-gradle.cmd <gradle args> (the script now accepts bare :module:task arguments; use them) from your worktree root.

TASK, in this order:
 1. Server defects of the B1 verification (logs/streams/V-verifier/report.md items 8 and 9), in server/.../OrganizationDirectory.kt getSyncChanges and DirectoryRoutes.kt: prune tombstones after 90 days and answer fullResync when `since` is older than the oldest kept tombstone; paginate in SQL instead of loading and sorting in memory; a bad cursor must be an error (400), not a silent restart; members without a signed statement must be counted and reported, not silently dropped; make offlineGraceDays a server setting (default 30, clamp as the client does). Each with a failing test first and a mutation check. The directory sync rate limit was already raised on dev; keep that test.
 2. The self-disable guard you added to POST /admins/{u}/disable (403) is not in plan A1; dev kept it and changed AdminApiTest. Say in your report whether you want it kept and why; do not remove it.
 3. Plan A3 (docs/plans/HUB-ANNOUNCEMENTS-PLAN.md), only after 1 is reported: announcements server side, console, hub-protocol DTOs. Migration `announcements` step 1. You are the only stream that may add routes; regenerate docs/protocol-routes.txt.
Deliver the contract section 7 report per item in logs/streams/S2-server/progress.md.
```

## R2-3 — Cloud, independent verifier (re-run on the fixed dev)

```text
You are the independent VERIFIER again. Import the bundle: git clone flash-dev.bundle flash-verify -b dev. Write nothing outside logs/streams/V-verifier/ and commit it to branch agent/V-verifier-2.

PARALLEL MODE. Read in this order before anything else: AGENTS.md (all of it, including section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md,
docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, then your plan file(s), then logs/handoff.md.
Rules: (1) work only in your own worktree/branch; never edit the integration tree C:\Users\KaliOxygen\Downloads\Flash; (2) write logs ONLY into logs/streams/<your stream>/
in the canonical formats, never into logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md or AGENTS.md; use only the number ranges reserved for your stream
(protocol section 7); (3) edit only files your stream owns (protocol section 5); new code in new files, three-line hooks in big host files, no reformatting;
(4) commit small, only to your branch; never merge, rebase, push to dev/main, force-push or stash; (5) the plan text wins over any handoff description;
(6) the quality contract applies in full: explain-back before code, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done";
(7) never touch the phone, never uninstall/clear the app, never print keys or tokens; (8) no new dependency or Gradle module without asking;
(9) stop and ask the owner on any contradiction with the plan; do not guess; (10) finish with the contract section 7 report plus the exact gate commands and
last output lines, and the list of shared files you touched with hunk sizes.
You build on your own machine without the PC lock; say exactly which tasks could not run.

TASK 1: run the gate of docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 6 plus :ui:chat jvmTest and :desktop:compileTestKotlinJvm; per task executed/pass/fail counts; mark flakes only with evidence (rerun in isolation).
TASK 2: re-verify B1 against docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md section 4 Phase B1 and logs/streams/V-verifier/report.md: for each of the 16 defects say FIXED / STILL OPEN with path:line. In particular defect 4 (OrgSnapshotCoordinator is constructed and started in DesktopEngine.kt and DiscoveryEngineHolder.kt: prove it by reading, and re-run mutation: remove the clearSnapshot call on unenrol, expect ORGT-U51/U52 to fail), defect 3 (DirectorySyncLimitTest), defect 6 (ORGT-U57/U58).
TASK 3: re-verify B2 and B3 (section 4 Phase B2/B3, call-site table section 6, strings of section 9 verbatim, TalkBack, lock-screen privacy ORGT-U44). ACCEPT or REJECT with numbered path:line defects. Do not fix anything.
```

---

# Overnight master prompt (2026-10-14, owner demos the server tomorrow)

Two agents work in parallel with DISJOINT file ownership: Antigravity (S1) owns the client side, Claude Code (integration owner) owns the server side, the demo kit and the canonical docs/logs.
Paste the block below into Antigravity. It replaces R2-1's "start" and R2-2/R2-3 are NOT run overnight (Claude Code does R2-2's server defects itself).

```text
OVERNIGHT MASTER PROMPT - you are Antigravity, stream S1 (org trust, client side). The owner demos the Flash Hub server tomorrow. Work until the list is done; the owner is asleep, so do NOT stop to ask unless a rule below forces it.

SETUP
 1. cd E:\FlashWork\S1-org-trust (branch agent/S1-org-trust). Run: git merge dev (the worktree shares the repository, no fetch needed). dev already contains B1 wiring (OrgSnapshotCoordinator, ORGT-U50..U58), the S2 server work (A1/A2) and the S3 Hub tab. Keep dev's version of the reaction gate in RealFlashChatRepository.kt and of OrgPeerResolver unless you prove yours better.
 2. Read, in order: AGENTS.md (all, esp. section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md, docs/plans/HUB-QUALITY-CONTRACT.md, docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md, logs/streams/V-verifier/report.md, then the section "R2-1 - Antigravity, stream S1" of docs/plans/HUB-PARALLEL-LAUNCH-PROMPTS.md. That R2-1 TASK list (items 1-7) is your work. The common PARALLEL MODE rules in R2-1 apply in full.

FILE OWNERSHIP (hard line, so the two of us never conflict)
 You may edit: core/enterprise/**, core/persistence/**, core/messaging/**, core/engine/**, ui/chat/**, app/** and desktop/** (three-line hooks only in the big host files), docs/testing notes inside logs/streams/S1-org-trust/.
 You must NOT edit: server/**, core/hub-protocol/** (ask via your handoff if you need a wire change), tools/**, docs/protocol.md, docs/decisions.md, logs/*.md, docs/testing/TEST-BACKLOG.md, AGENTS.md. Claude Code owns those and folds your logs/streams/S1-org-trust/ fragments into them.
 Numbers: ADR 139-148, ERROR 400-429, EXP 025-029, tests ORGT-U59 and up. Room schema 15 is yours (renumber B4 to 16 and A3 to 17 in the protocol section 7 table ONLY by writing the change into logs/streams/S1-org-trust/handoff.md; Claude Code applies it).

ORDER OF WORK (demo value first; commit after EACH item so partial progress can be merged at any moment)
 A. Item 1 (resolver clear/refresh race) and item 5 (resolver says "not a colleague" when not enrolled).
 B. Item 3 (B2.5 UX: callers for orgTrustState in Settings/hub status, org_trust_* strings for every state, IDENTITY_MISMATCH, probes actually emitted).
 C. Item 7 (requestOrgSync on pull-to-refresh and Hub tab open; S3's Hub tab is on dev).
 D. Item 2 (Room schema 15 with orgRootFingerprint + lastErrorKind, migration 14->15 tested on a real 14 database, exported schema JSON).
 E. Items 4 and 6 (missing tests, hygiene).
 Every fix: a test that fails first, then passes, plus a mutation check recorded. Build/test ONLY through tools\flash-gradle.cmd from your worktree root (exit 75 = Claude Code is building: wait and retry, do other work meanwhile).
 Gate before your final report: tools\flash-gradle.cmd :core:enterprise:jvmTest :core:enterprise:testAndroidHostTest :core:persistence:jvmTest :core:messaging:jvmTest :ui:chat:jvmTest :desktop:compileKotlinJvm :app:compileDebugKotlin
 Do NOT run :desktop:jvmTest (DesktopEngineTlsBindingTest hangs, ERROR-106).

HARD RULES
 - Never touch the phone, never uninstall/clear the app, never print keys/tokens, never commit keystore.properties/*.jks/local.properties.
 - Do not remove or weaken any security guard (the server's self-disable guard is Claude Code's business, not yours).
 - Do not start B4. Do not push. Commit only to agent/S1-org-trust. Trailer on every commit: Co-Authored-By: Antigravity <noreply@antigravity.google>
 - No new dependency/module. No decisions beyond the plans (D1-D11, ADR-133..138 are final).
 - If the Gradle lock is busy for more than 30 min, write what you did in the handoff and continue with the next item.

FINISH
 Write logs/streams/S1-org-trust/handoff.md with: items done (commit hashes), items not done and why, the exact gate command output last lines, the schema-15 renumbering note, and the shared files you touched with hunk sizes. Then create the empty marker file logs/streams/S1-org-trust/DONE and commit it. Claude Code will merge agent/S1-org-trust into dev when the marker appears (or whenever the owner asks).
```

Claude Code's side of the same night (so Antigravity knows what not to touch): server defects 8/9 (tombstone pruning, cursor 400, unsigned members reported, `offlineGraceDays` setting), the demo kit (`tools/demo-server.ps1`, `docs/plans/HUB-DEMO-RUNBOOK.md`), folding the three log fragments into the canonical logs and ADR renumbering, then merging S1 and re-running the integration gate.

---

# Round 3 (2026-10-15): verifier prompt for Antigravity (local, this PC)

R2-3 above assumed a cloud sandbox and `flash-dev.bundle`. This version runs on THIS PC under the Gradle lock, reads the current `dev` directly, adds the S1 round 2 and S2/S3 work that no independent agent has checked yet, and never starts the owner's demo hub. Paste the block below into Antigravity.

```text
You are the independent VERIFIER (stream V). You verify; you do NOT fix. Work only in your own worktree:
  git worktree add E:\FlashWork\V-verify-3 -b agent/V-verifier-3 dev
Never edit C:\Users\KaliOxygen\Downloads\Flash. Write only into logs/streams/V-verifier/ (report-3.md) and commit only to agent/V-verifier-3. Do not merge, rebase, push, stash.

READ FIRST, in order: AGENTS.md (all, esp. section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md, docs/plans/HUB-QUALITY-CONTRACT.md,
docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md, logs/streams/V-verifier/report.md (the first verification, 16 defects), logs/handoff.md.
The plan text wins over any handoff description. The quality contract applies to YOU too: explain-back first, evidence table, adversarial self-review with at least 10 path:line findings, an honest "Not verified" list.

RULES: build and test ONLY through tools\flash-gradle.cmd <args> run from your worktree root; exit 75 means another agent is building, do other work and retry.
Never touch the phone or the owner's running demo hub (ports 8443 and 8444 are in use): do not start a server on them; use the :server:test end-to-end tests instead.
Never uninstall or clear the app, never print keys, tokens or full key bytes. No new dependency or module. On any contradiction with the plan, stop and ask the owner. Never claim a task you did not run.
A failure counts as a flake only if it passes when rerun in isolation, with the evidence shown. Known baseline red: ERROR-106 (:desktop:jvmTest hangs, do not run it; run :desktop:compileTestKotlinJvm), ERROR-396 (flaky E2eRelayContractTest.aReconnectStormLeavesExactlyOneLiveSession).

TASK 1 (gate): run the gate of HUB-PARALLEL-AGENTS-PROTOCOL.md section 6 plus :ui:chat jvmTest and :desktop:compileTestKotlinJvm. Report per task: executed / pass / fail counts.

TASK 2 (B1): for each of the 16 defects in report.md say FIXED or STILL OPEN with path:line. Specifically prove by reading that OrgSnapshotCoordinator is constructed AND started in DesktopEngine.kt and DiscoveryEngineHolder.kt (and Flash.kt if it is a host). Re-run two mutations and restore each afterwards: (a) remove the clearSnapshot call on unenrol, expect ORGT-U51/U52 to fail; (b) skip statement verification, expect ORGT-U12b to fail. Also check defect 3 (DirectorySyncLimitTest) and defect 6 (ORGT-U57/U58). Known accepted gap: server directory pagination is in memory, not SQL.

TASK 3 (B2, B3 and the S1 round 2 changes): verify against plan section 4 (Phases B2/B3), the call-site table of section 6, the user-facing strings of section 9 verbatim, TalkBack labels, lock-screen privacy (ORGT-U44). Then check these round 2 items one by one, each with a mutation or a test that fails without the fix:
  - resolver race: generation counter (ORGT-U59);
  - enrolment gate isEnrolled wired in ALL THREE hosts (ORGT-U60);
  - requestOrgSync on pull-to-refresh and Hub tab open (ORGT-U64/U65);
  - Room schema 15 (orgRootFingerprint, lastErrorKind) and migration 14 to 15 (ORGT-U66), and that no schema number is used twice (B4 = 16, A3 = 17).
Check PeerTrustPolicy: PTT, group creation and group administration must stay PAIRED-only; list any path where an unpaired ORG colleague could reach them.

TASK 4 (never independently verified so far): (a) Plan A1 department heads and invite roles, Plan A2 hidden departments: read the server code and tests against their plans, and try to break scoping (a head acting outside their department, a hidden department visible to an outsider in the directory sync). (b) Hub tab shell H0-H5 (docs/ui/flash-hub-tab.md, HUB-TAB-IMPLEMENTATION-PLAN.md): navigation, Transfers moved to the top bar, empty/offline/expired states. (c) Fresh finding from the owner's demo today: enrolling a dept_admin invite on the demo hub (--dev-embedded-root) was refused as "forbidden" because the dev delegation only had enrol:member. Check the PRODUCTION path: which scopes does the keytool delegation issue by default, and can a real hub issue dept_admin invites without the owner knowing a scope is missing? Is the failure message shown to the user helpful or just "forbidden"? Report it as a defect with path:line if not.

OUTPUT: logs/streams/V-verifier/report-3.md with: gate table, B1 defect table (16 rows), B2/B3/round-2 table, A1/A2/H0-H5 findings, numbered defects with path:line and severity, mutation results (what you changed, which test failed, proof you restored it with git diff showing clean), a clear verdict per phase (B1, B2, B3, A1, A2, H0-H5): ACCEPT or REJECT, and the exact gate commands with the last output lines. Do not fix anything.
```

---

# Round 4 (2026-10-15): B1-B3, A1, A2, H0-H5 are ACCEPTED (report-3); build B4 and A3

Start from `dev` at `7401b954` or later (it contains ERROR-400, the invite-role fix). Two implementers can run in parallel because they touch different areas; both use the Gradle lock. Run them one at a time if the PC is slow. After both, run the verifier again with the Round 3 prompt, changing the phases to B4 and A3.

## R4-S1 — Antigravity (this PC), Plan B Phase B4: hub groups on the LAN

```text
You are implementer stream S1. Create your worktree: git worktree add E:\FlashWork\S1-b4 -b agent/S1-b4 dev. Never edit C:\Users\KaliOxygen\Downloads\Flash.
Read first, in order: AGENTS.md (all, esp. section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md, docs/plans/HUB-QUALITY-CONTRACT.md,
docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md (section 4 Phase B4 and section 10 in full, ADR-135 in docs/decisions.md), logs/handoff.md, logs/streams/V-verifier/report-3.md.
Scope: ONLY Phase B4 (hub-signed roster statement, hub groups usable on the LAN through ORG trust plus a verified roster). Room schema step is 16 (reserved; check the table in docs/protocol.md section 7 and never reuse a number). Do not start A3 or the Hub tab screens.
Rules: logs ONLY into logs/streams/S1-org-trust/ in the canonical formats (never logs/*.md, docs/decisions.md or TEST-BACKLOG.md); build and test ONLY through tools\flash-gradle.cmd <args> from your worktree root (exit 75 = busy, retry later);
commit small to agent/S1-b4 only; never merge, rebase, push, stash; never touch the phone, the owner's demo hub (ports 8443/8444) or the app's data; never print keys or tokens; no new dependency or module without asking.
Hard security rules from the plan: a group member who is only an ORG colleague may chat inside a hub-linked group but NEVER gets push-to-talk, group creation or group administration outside the hub-signed roster; PTT and local/v2 group gates stay PAIRED-only; the roster statement must be verified against the hub delegation chain and the pinned root, with a monotonic version and a lease like member statements.
Quality contract applies in full: explain-back first, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done", at least two mutation checks (name them in your report, restore afterwards).
Stop and ask the owner on any contradiction with the plan. Finish with the contract section 7 report, exact gate commands and last output lines, and every owed device test (add to your stream's backlog fragment).
```

## R4-S2 — OpenCode or Antigravity second session, Plan A3: announcements (server first)

```text
You are implementer stream S2. Create your worktree: git worktree add E:\FlashWork\S2-a3 -b agent/S2-a3 dev. Never edit C:\Users\KaliOxygen\Downloads\Flash.
Read first, in order: AGENTS.md (all, esp. section 36), docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md, docs/plans/HUB-QUALITY-CONTRACT.md,
docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md, docs/plans/HUB-ANNOUNCEMENTS-PLAN.md (all), docs/plans/HUB-DEPARTMENT-HEADS-AND-INVITES-PLAN.md section on roles, logs/handoff.md.
Scope: ONLY Plan A3 sections 3 (server), 4.1-4.3 (wire library, Room persistence, repository API) and 5 (console UI). Room schema step is 17 (reserved; B4 uses 16). Do NOT build the Hub tab screens or notifications UI (plan A4 / H6+).
Announcements are PLAINTEXT on the hub (ADR-138): never claim they are encrypted anywhere in code, strings or docs. Department heads may post only to their own department; a hidden department's announcements must never appear for outsiders (same filter as the directory, Plan A2).
Rules: logs ONLY into logs/streams/S2-server/ in the canonical formats; build and test ONLY through tools\flash-gradle.cmd <args> from your worktree root; commit small to agent/S2-a3 only; never merge, rebase, push, stash; never touch the phone or the owner's demo hub (ports 8443/8444); never print keys or tokens; no new dependency or module without asking.
Add every new route to docs/protocol-routes.txt and docs/protocol.md; the RouteTableContractTest must stay green. Sanitise all text through one function (plan 3.2).
Quality contract applies in full: explain-back first, evidence table, adversarial self-review with at least 10 path:line findings, honest "Not done", at least two mutation checks restored afterwards.
Stop and ask the owner on any contradiction with the plan. Finish with the contract section 7 report, exact gate commands and last output lines, and every owed device test.
```
