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
