# S2-server handoff (fragment; integrator folds into `logs/handoff.md`)

## Current branch
`agent/S2-server` (worktree `E:\FlashWork\S2-server`; base `b717ea50`)

## A2 status (2026-10-14)
Plan A2 (directory privacy, option C) BUILT and unit-tested, NOT
device-verified, NOT merged. Fragments in `logs/streams/S2-server/`
(progress with §7 report, errors ERROR-430, decisions ADR-137, backlog
PRIV-01..05, experiments EXP-030, security + probes).

## Working (this stream)
- `Department.hidden` + directory migration 3 + epoch bump.
- `DirectoryVisibility` single decider; `GET /directory/people{,/{id}}`;
  widened departments/members/groups/sync; keys flag true.
- Admin API + console toggle; enterprise client + `HubPeerDirectory` fallback.
- `docs/protocol.md` subsection; `docs/protocol-routes.txt` regenerated.

## Verified
- `:server:test --rerun-tasks` BUILD SUCCESSFUL (20 executed).
- `DirectoryPrivacyTest` 12/12 executed green; `DirectoryPrivacyClientTest`
  4/4; enterprise jvmTest 150/150; hub-protocol jvmTest green.
- Node admin-ui 62/62. Mutations M1 + M2b proven (M2a documented
  unreachable-control finding).
- Banned-pattern grep: only pre-existing hit.

## Next
Commit A2 on `agent/S2-server`, then start Plan A1
(`docs/plans/HUB-DEPARTMENT-HEADS-AND-INVITES-PLAN.md`) per task order.
Do NOT start A3. Shared-file hunks listed in progress.md (largest:
`DirectoryRoutes.kt` +205, `OrganizationDirectory.kt` +164).

## Watch out for
- `tools/flash-gradle.ps1` bare-args bug (ERROR-430): use
  `& .\tools\flash-gradle.ps1 -WaitMinutes 45 -GradleArgs '...'`.
- Three existing tests updated to plan-mandated A2 behavior (cited); S1/S3
  rebases will see them.
