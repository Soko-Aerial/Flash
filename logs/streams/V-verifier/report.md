# V-verifier report — Plan B (B1 + B2), checkout dev f949182c

Verifier did not modify product code. Sandbox: Linux, Android SDK, `./gradlew --no-daemon`. Shared files touched: **none**.

## Verdicts
- **TASK 1 gate: FAIL** (4 red test tasks; 3 are pre-existing timing flakes, 1 is a Plan B defect).
- **TASK 2 Phase B1: REJECT (ACCEPT-WITH-FIXES at best)** — see B1 defects.
- **TASK 3 Phase B2: REJECT** — incomplete; a committed gate-disabling `if (false) return` at `RealFlashChatRepository.kt:5508`.

## TASK 1 — gate (all executed, none UP-TO-DATE/FROM-CACHE)
| Task | Result | Tests | Claimed |
|---|---|---|---|
| :core:hub-protocol:jvmTest | PASS | 103/0 | 103 |
| :server:test | FAIL | 459, 3 failed | — |
| :core:persistence:jvmTest | PASS | 59/0 | — |
| :core:persistence:testAndroidHostTest | PASS | 52/0 | — |
| :core:enterprise:jvmTest | PASS | 146/0 | 115 / 140 (reports disagree) |
| :core:enterprise:testAndroidHostTest | PASS | 63/0 | 41 / 50 |
| :core:messaging:jvmTest | **FAIL** | 231, 1 failed | 415 passing |
| :core:messaging:testAndroidHostTest | **FAIL** | 572, 1 failed | 572 passing, 0 failed |
| :desktop:compileKotlinJvm | PASS | — | |
| :app:compileDebugKotlin | PASS | — | |
| :app:compileDebugAndroidTestKotlin | PASS | — | |

Failures:
1. `RealFlashChatRepositoryColleagueTest.reactionGate_acceptsOrgColleagueAndRejectsStranger` (:535), both messaging suites. **Plan B defect**: `RealFlashChatRepository.kt:5508` contains `if (false) return`, introduced by commit b717ea50 (a "docs" commit). Pre-B2 code was `if (transportPeerId != null && !isTrustedPeer(transportPeerId)) return`. Restoring the line on scratch makes the test pass. The "tests green / mutation restored" claim is false for this tree.
2. `RevocationAndEnrolmentTest.kt:113`, `:130`; `E2eRelayContractTest.kt:191`. **Unrelated to Plan B**: tests from fd4cdb88 (2026-10-13); `SqliteRevocationService.fire()` notifies via `notifyScope.launch` (Dispatchers.Default) while tests assert immediately. Re-run in isolation (`--rerun --tests *RevocationAndEnrolmentTest --tests *E2eRelayContractTest`): 30 tests, 0 failures → load-dependent flake.
Jvm messaging count 231 vs claimed 415: claim not reproducible.

## Mutation checks (scratch copy, `.gradle` and module build dir wiped so tasks execute; restored after each)
| # | Mutation | Result |
|---|---|---|
| M1 | skip delegate signature verify in OrgMemberStatement.findMatchingDelegation | caught (tamperedFieldsRejectVerification) |
| M2 | remove DB stale-serial filter in OrgDirectoryDao.applySyncBatch | **persistence suite SURVIVED**; only enterprise `ORGT-U11` (fake DAO path) caught it → real DAO has no own stale-serial test |
| M3 | resolver ignores fingerprint | caught by U22/U23 only (U21 did not fail) |
| M5 | resolver ignores lease | caught (U24) |
| M4 | widen mayPushToTalk | caught by PeerTrustPolicyTest, but no production PTT consumer → proves nothing about product |
| M6 | restore mayReact gate at :5508 | ColleagueTest passes (one-line fix) |
| S1-S3 | `known = true` at :1948; `if (false)` at :3531; `return true ||` at :5320 (jvm suite only) | **all survived** (only the pre-existing reaction failure) → no per-site tests |

## Lazy-pattern scan (diff 97e027be..HEAD, grep form)
Only relevant hit: `if (false)` at RealFlashChatRepository.kt:5508. Others (`!!` in tests, runCatching in desktop ChatDbKeyStore / TLS test) are out of scope or minor.

## §6 call-site table (isTrustedPeer, RealFlashChatRepository.kt)
215 ctor · 390 HubChatBridge (isPaired || org) · 523 SignedGroups · 592 RosterGroupGate · 1948 / 1987 / 2089 (PeerTrustPolicy.mayCreateGroupWith / mayAddGroupMember, both == isPaired) · 2491 · 2867 · 3128 · 3155 · 3464 · 3531 · 5269 · 5278 · 5320 · **5508 (reaction gate: DISABLED)**. All opened. Sites 1948/3531/5320 survive mutation (no per-site test). Only mayCreateGroupWith / mayAddGroupMember have production callers; mayChat, mayRing, mayPushToTalk, mayServeSwarm, mayVouch, mayAdministerGroup have none, so the capability policy is mostly decorative and U25 tests a policy object, not call sites.

## Defects (path:line, ranked)
### Blocking
1. `core/messaging/.../RealFlashChatRepository.kt:5508` — `if (false) return` disables the 1:1 reaction gate; strangers' reactions accepted. Fails ColleagueTest in both suites.
2. `core/enterprise/.../OrgPeerResolver.kt:194-233` (esp. 213-218) — `resolve()` overwrites a TOFU pin with the statement fingerprint and returns bindingOk=true in the same call, so the attacker's live session passes as ORG and is not closed. U22 (`OrgPeerResolverTest.kt:208-236`) enshrines it.
3. `server/.../RateLimiter.kt:101` — `directorySync = TokenBucketLimiter(6, 1.0/10.0)`, client limit=500 → a 5,000-member full sync needs 10 pages against burst 6; cursor is in-memory so retry restarts at page 1 → likely 429 forever. EXP-022 and U16 used a fake API and never hit the limiter.
4. `core/enterprise/.../OrgDirectorySync.kt` (class) — never instantiated outside tests; no sync caller; `clearSnapshot` (:443) never called; hosts (`DesktopEngine.kt:1341`, `DiscoveryEngineHolder.kt:1187`, `Flash.kt:481`) only call `refreshFromSnapshot` at startup. No unenrol clear. Feature is inert end-to-end.
### Major
5. `OrgPeerResolver.kt` clear()/refreshFromSnapshot() — dao reads outside the lock; stale refresh can repopulate after clear. No race test (U32 has none). `lease.value` has side effects.
6. `OrgDirectorySync.kt:320-332` — Rule 8 flag bug: key sequence K1,K2,K1 clears isConflict; conflict drop does not delete the existing DB row.
7. `OrgSyncStateEntity` — lacks `orgRootFingerprint` and `lastErrorKind` required by plan B1.3/B1.4.
8. `server/.../OrganizationDirectory.kt` getSyncChanges (~975-1070) — no 90-day tombstone pruning and no fullResync for a `since` older than pruned tombstones; in-memory filter+sort per page; bad cursor silently restarts (`runCatching{}.getOrNull()`); members without a statement silently dropped.
9. `server/.../DirectoryRoutes.kt` — `offlineGraceDays = 30` hard-coded.
10. B2.5 undelivered: `FlashHubStatusMath.orgTrustState` has no callers; no `org_trust_*` string resources; no IDENTITY_MISMATCH state, Settings row, notification; probes org.sync.start/ok/fail, org.lease.state, org.gate.allow/deny only documented in PROBES.md, never emitted.
11. Missing tests: B2 U28 (restart; only an in-memory fake), U30 (revoked-after-sync), U31 (desktop parity). B1 U02-U09/U17/U19 have no U-tags.
12. Hosts wire RealOrgPeerResolver unconditionally (not gated on enrolment).
### Minor
13. `OrgDirectorySync.kt:390` — probe `revocationsCount` = removed department count (mislabelled); probe emits hub name.
14. `OrgDirectorySync.kt` — `addAll(dto.delegationChain)` per page duplicates chain entries; a force sync joins a non-forced in-flight sync; backoff vars not @Volatile.
15. `startColleagueConversation` (:6862) removed-vs-not-member heuristic misleading for a paired peer with a conversation missing from the directory; `orgDialCandidates` (:6914) starved by `dueForDelivery(limit*2)`.
16. Hygiene: docs/decisions.md rewritten with line endings (5202/5101 lines for ~102 real); commit 4f1991cb bundles unrelated hub-hardening (ADR-129..131); GroupHistorySyncTest.kt touched (+1 line); EXP-022 Android 8-12 ms ARM estimate not marked "(measure)".

## Extra situations considered
1. Attacker with TOFU pin impersonating a colleague (defect 2).
2. Full 5,000-member sync under the real rate limiter (defect 3).
3. Unenrol / hub removal leaves ORG snapshot trusted (defect 4).
4. Clear racing a refresh (defect 5).
5. Key flip K1→K2→K1 during conflict (defect 6).
6. Backup/restore: checked — backup_rules and data_extraction_rules exclude `database` domain wholesale, so org tables are covered. Not a defect.
7. Clock moved backward past lease: covered by U24 mutation catch.

## Not done
`:desktop:jvmTest` (not in gate; ERROR-106 known; earlier engine tests hung 37 min). Site mutations not re-run on the host suite. Physical device untouched (ORGT-B01 not re-run). 20k scale (EXP-022) not re-measured. Lazy scan was grep, not PowerShell form.

## Gate commands (run from checkout root, unchanged)
`./gradlew --no-daemon :core:hub-protocol:jvmTest :server:test :core:persistence:jvmTest :core:persistence:testAndroidHostTest :core:enterprise:jvmTest :core:enterprise:testAndroidHostTest :core:messaging:jvmTest :core:messaging:testAndroidHostTest :desktop:compileKotlinJvm :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` (each run as a separate invocation; exit codes 0,1,0,0,0,0,1,1,0,0,0). Last line of hub-protocol run: `BUILD SUCCESSFUL in 2m 43s / 9 actionable tasks: 9 executed`.
