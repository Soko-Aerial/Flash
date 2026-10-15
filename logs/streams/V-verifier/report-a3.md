# Stream V Verification Report: Plan A3 — Hub Announcements (Independent Audit)

**Verifier:** Stream V (Independent Verifier)  
**Branch:** `agent/V-verifier-a3`  
**Worktree:** `E:\FlashWork\V-verify-a3`  
**Base Commit (S2):** `6d4bb04c` (`agent/S2-a3`)  
**Merged Dev Commit:** `8b950ff5` (`dev`, holds Plan B Phase B4 = schema 16)  
**Merge Commit:** `d8654ae9`  
**Date:** 2026-10-15  
**Quality Contract:** Conforming to `docs/plans/HUB-QUALITY-CONTRACT.md`, `AGENTS.md` §36, and `docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md`.  
**Final Verdict:** **REJECT for Plan A3** (2 Critical defects, 4 Major defects, 5 Minor defects).

---

## 1. Explain-Back & Executive Summary

This independent verification assesses the implementation of **Plan A3 (Hub Announcements)** authored by Stream S2.

Plan A3 introduces organization-wide and department-scoped broadcast announcements:
1. **Server Architecture:** Database component `"announcements"` (version 1), FTS5 full-text indexing, monotonic sequence (`seq`), strict code-point sanitization (`AnnouncementText`), REST endpoints (`/api/v1/announcements`), optimistic concurrency (`expectedSeq`), pin limits (max 3), retention purge, capability-gated WebSocket push via `RelayFrame.AnnouncementEvent` (`ann1`), and web console views with live preview.
2. **Client Architecture:** Room schema step 17 (`hub_announcements`, `hub_announcements_state`), `HubAnnouncementsApi`, repository operations in `RealFlashChatRepository` with typed error mapping, and host engine wiring across Android, Desktop, and CLI hosts.
3. **Plaintext Decision (ADR-138):** Announcements are deliberately unencrypted on the hub by product owner decision. All documentation and user-facing copy must transparently reflect this.

### Verification Approach
- Merged `dev` (`8b950ff5`, schema 16) into `agent/S2-a3` in worktree `E:\FlashWork\V-verify-a3`, resolving 7 merge conflicts.
- Executed gate tasks across `:core:hub-protocol`, `:core:persistence`, `:core:enterprise`, `:core:messaging`, `:ui:chat`, `:desktop`, `:app`, and Node console tests strictly via `tools\flash-gradle.cmd` and `node --test`.
- Audited role permissions, cross-department visibility, pagination, SQL injection, concurrency, Room pruning, and unenrol cleanup.
- Conducted adversarial analysis uncovering **11 verified defects**, including **2 Critical integration-breaking bugs** and **phantom documentation claims**.

---

## 2. Merge Conflict Resolution (`agent/S2-a3` + `dev` -> `d8654ae9`)

The merge of `dev` (`8b950ff5`, containing Plan B Phase B4 = schema 16) into `agent/S2-a3` (`6d4bb04c`) produced 7 file conflicts. All were resolved cleanly in worktree `E:\FlashWork\V-verify-a3`:

1. `core/messaging/.../RealFlashChatRepository.kt`:
   - *Conflict:* Constructor arguments collided. `dev` added `hubGroupRosterDao` (Plan B4), while `S2-a3` added `hubAnnouncementDao` and `hubAnnouncementsApi`.
   - *Resolution:* Preserved both B4 group roster and A3 announcements dependencies.
2. `core/persistence/.../FlashDatabase.kt`:
   - *Conflict:* Database version and entities. `dev` bumped to version 16 with `HubGroupRosterEntity`, while `S2-a3` jumped 15->16 with `HubAnnouncementEntity` and `HubAnnouncementStateEntity`.
   - *Resolution:* Preserved both `HubGroupRosterEntity` (schema 16) and `HubAnnouncementEntity`/`HubAnnouncementStateEntity` (schema 17). Set canonical `DATABASE_VERSION = 17`. Added both DAOs.
3. `core/persistence/.../FlashSchemaSteps.kt`:
   - *Conflict:* Step definitions. `dev` defined `STEP_15_16` (hub_group_roster), while `S2-a3` defined `STEP_15_16` for announcements.
   - *Resolution:* Retained B4's `STEP_15_16` for `hub_group_roster`. Re-keyed A3's announcements migration as `STEP_16_17`. Updated `ALL` list to `1..17`.
4. `core/persistence/.../FlashMigrations.kt`:
   - *Conflict:* Room migration objects.
   - *Resolution:* Preserved `MIGRATION_15_16` from `dev` and defined `MIGRATION_16_17` for announcements.
5. `core/persistence/.../FlashJvmMigrationsTest.kt`:
   - *Conflict:* Unit migration tests.
   - *Resolution:* Preserved `ORGT-U50` (15->16) and added `ANN-U20` testing `MIGRATION_16_17` from schema 16 to 17.
6. `core/persistence/.../FlashMigrationsChainTest.kt`:
   - *Conflict:* Full migration chain assertion.
   - *Resolution:* Chained `14 -> 15 -> 16 -> 17`, validating table creations and indices across all versions.
7. `docs/testing/TEST-BACKLOG.md`:
   - *Conflict:* Backlog sections.
   - *Resolution:* Preserved both B4 test items (`ORGT-11`..`16`) and A3 test items (`ANN-01`..`11`).

Committed as `d8654ae9`.

---

## 3. Gate Verification Table (Task 1)

All tasks were executed in `E:\FlashWork\V-verify-a3` using `tools\flash-gradle.cmd` (honouring the global build mutex) and `node --test`.

| Subproject / Task | Tests Run | Passed | Failed | Skipped | Duration | Result |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `:core:hub-protocol:jvmTest` | 103 | 103 | 0 | 0 | 1m 35s | **PASS** |
| `:core:persistence:jvmTest` | 61 | 61 | 0 | 0 | 1m 35s | **PASS** |
| `:core:persistence:testAndroidHostTest` | 52 | 52 | 0 | 0 | 1m 35s | **PASS** |
| `:core:enterprise:jvmTest` | 173 | 173 | 0 | 0 | 2m 45s | **PASS** |
| `:core:enterprise:testAndroidHostTest` | 90 | 90 | 0 | 0 | 2m 45s | **PASS** |
| `:core:messaging:jvmTest` | 263 | 263 | 0 | 0 | 5m 07s | **PASS** |
| `:ui:chat:jvmTest` | 463 | 463 | 0 | 0 | 2m 45s | **PASS** |
| `:desktop:compileTestKotlinJvm` | - | - | - | - | 5m 07s | **PASS** |
| `:app:compileDebugKotlin` | - | - | - | - | 5m 07s | **PASS** |
| `node --test announcements.test.mjs` | 6 | 6 | 0 | 0 | 203ms | **PASS** |
| `:server:test` | *Queued* | - | - | - | - | *Pending Lock* |
| `:core:messaging:testAndroidHostTest` | *Queued* | - | - | - | - | *Pending Lock* |

### Schema Verification
- **Schema 15:** Enterprise org trust (`org_members`, `org_departments`, `org_sync_state`).
- **Schema 16:** Hub group rosters (`hub_group_roster`).
- **Schema 17:** Hub announcements (`hub_announcement`, `hub_announcement_state`).
- **Chain Test:** `FlashMigrationsChainTest` successfully validates `14 -> 15 -> 16 -> 17` against actual SQLite database schemas.

---

## 4. Requirement Audit: A3 vs Plan (Task 2)

| Area | Plan Requirement | Implementation Status | Path & Verification Notes |
| :--- | :--- | :---: | :--- |
| **Server Endpoints** | REST endpoints for feed, CRUD, pinning | **COMPLIANT** | `AnnouncementRoutes.kt:53-470`. Implements `GET`, `POST`, `PUT`, `DELETE`, `/pin`, `/unpin`. |
| **Admin Console View** | Web console listing, composer, preview | **COMPLIANT** | `announcements.js`, `announcements.test.mjs` (6 tests passing, XSS safe via `textContent`). |
| **Retention Purge** | Daily purge hard-deletes old/expired announcements & tombstones | **PARTIAL** | Purge inlined in `HubServices.kt:138`. No separate `AnnouncementPurgeJob` exists despite log claims. |
| **Who May Publish** | Org admin (all), Dept head (own dept only), Employee (none) | **DEFECTIVE (Client)** | Server routes enforce correctly (`AnnouncementRoutes.kt:145-170`), but **client repository breaks department heads** (Finding 1). |
| **Audience Targeting** | Whole company vs specific department | **COMPLIANT** | Scopes validated on server; non-admin cannot target `"org"` or other departments. |
| **Hidden Departments** | Privacy Option C interaction | **COMPLIANT** | Visibility filtered in SQL; outsiders cannot discover or search hidden department announcements. |
| **Client Wire API** | `HubAnnouncementsApi` & DTOs | **COMPLIANT** | `HubAnnouncementsApi.kt`, `FakeHubAnnouncementsApi.kt`, `HubContract.kt`. |
| **Room Persistence** | Schema 17, `HubAnnouncementEntity`, state singleton | **DEFECTIVE (Pruning)** | DAO table and queries implemented, but `pruneOldAnnouncements` only prunes org scope (Finding 6). |
| **Repository API** | Flow observations, typed error mapping | **DEFECTIVE (Pagination)** | `RealFlashChatRepository.kt`. Sync skips items 101+ (Finding 2); unenrol wipe is async (Finding 7). |
| **Host Wiring** | Android, Desktop, CLI | **COMPLIANT** | Wired in `DiscoveryEngineHolder.kt`, `DesktopEngine.kt`, and `Flash.kt`. |
| **Plaintext Honesty** | Plaintext on hub (ADR-138) transparently disclosed | **COMPLIANT** | No encryption claimed. Transparent in all user and admin notices. |

---

## 5. Adversarial Audit & Numbered Defects (Task 3)

### CRITICAL DEFECT 1: Device Dept Head Role Mismatch Blocks Client App Posting & Rights
- **Path & Line:**
  `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:434-440`  
  `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:7379-7383`
- **Severity:** **CRITICAL**
- **Symptom:** Every enrolled department head device running the Android or Desktop app is stripped of posting rights (`canPostHubAnnouncement` emits `HubPostingRights.None`) and rejected with `HubAnnouncementError.NotAllowed` when attempting to post.
- **Root Cause:**
  In `HubRoles.kt:16` and `EnrollmentCredentials.kt:36`, the canonical certificate role string issued by the Hub for department heads is `"dept_admin"`.
  However, in `RealFlashChatRepository.kt`, both `canPostHubAnnouncement` and `postHubAnnouncement` strictly check for `"dept_head"`:
  ```kotlin
  // RealFlashChatRepository.kt:434
  return when (creds.role) {
      "admin", "org_admin" -> HubPostingRights.OrgWide
      "dept_head" -> if (creds.departmentId != null) HubPostingRights.Department(creds.departmentId) else HubPostingRights.None
      else -> HubPostingRights.None
  }
  ```
  ```kotlin
  // RealFlashChatRepository.kt:7379
  val allowed = when (role) {
      "admin", "org_admin" -> true
      "dept_head" -> targetScope == "department" && targetDeptId == deptId
      else -> false
  }
  ```
  On every enrolled department head device, `creds.role` is `"dept_admin"`. Thus, `allowed` evaluates to `false` and posting fails closed.
- **Test Fabrication:** In `RealFlashChatRepositoryAnnouncementsTest.kt:248, 264`, the test author mocked synthetic credentials with `role = "dept_head"` instead of testing against the canonical `HubRoles.DEPT_ADMIN`.

---

### CRITICAL DEFECT 2: Feed Pagination Discards Announcements 101+ During Delta Sync
- **Path & Line:**
  `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:7305-7318`
- **Severity:** **CRITICAL**
- **Symptom:** In organisations with more than 100 announcements or sync backlog, announcements between index 100 and the hub high-water mark are permanently lost and never downloaded to the client device.
- **Root Cause:**
  In `AnnouncementStore.kt:486`, `getChangesFeed` sets `seq = highestSeq` (the global max sequence number on the hub), while `nextCursor = items.last().seq.toString()`.
  In `RealFlashChatRepository.kt`:
  ```kotlin
  val nextSeq = maxOf(currentSince, feed.seq)
  ...
  currentSince = nextSeq

  if (feed.nextCursor == null || feed.items.isEmpty()) {
      break
  }
  ```
  On iteration 1, `currentSince` jumps immediately to `feed.seq` (`highestSeq`), completely ignoring `feed.nextCursor`!
  On iteration 2, the client calls `api.fetchFeed(since = highestSeq)`, querying `WHERE seq > highestSeq`. The server returns an empty list, ending pagination and permanently skipping all items between sequence 100 and `highestSeq`.

---

### MAJOR DEFECT 3: Phantom Documentation Claims for Non-Existent Class and Test
- **Path & Line:**
  `logs/streams/S2-server/progress.md:14`  
  `docs/plans/HUB-ANNOUNCEMENTS-PLAN.md:239`  
  `logs/handoff.md`
- **Severity:** **MAJOR**
- **Symptom:** False claims in progress log, handoff, and plan evidence tables regarding non-existent code files.
- **Root Cause:** S2's progress log and evidence table explicitly claim to have implemented and tested:
  - `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementPurgeJob.kt`
  - `com.transfer.flash.server.announcements.AnnouncementPurgeJobTest`
  Neither file exists in the repository. The purge logic was simply inlined into `HubServices.kt:138`, and no unit test `AnnouncementPurgeJobTest` was ever written.

---

### MAJOR DEFECT 4: SQL Injection Vulnerability via Unescaped String Interpolation
- **Path & Line:**
  `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt:510, 520`
- **Severity:** **MAJOR**
- **Symptom:** In `searchAnnouncements`, SQL clauses are constructed via direct string interpolation instead of JDBC query parameters.
- **Root Cause:**
  ```kotlin
  // AnnouncementStore.kt:510
  else -> "(a.scope = 'org' OR (a.scope = 'department' AND a.department_id = '$callerDeptId'))"

  // AnnouncementStore.kt:520
  "a.scope = 'department' AND a.department_id = '${escapeSql(departmentId)}'"
  ```
  In line 510, `callerDeptId` is interpolated directly with zero escaping or parameterization. If `callerDeptId` contains a SQL fragment, it alters the WHERE clause execution. While line 520 calls `escapeSql`, line 510 omits even that. Both should use parameterized `?` placeholders like `listAnnouncements` does.

---

### MAJOR DEFECT 5: Uncaught `AnnouncementForbiddenException` Causes HTTP 500
- **Path & Line:**
  `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutes.kt:294-308, 360-375, 415-430`
- **Severity:** **MAJOR**
- **Symptom:** When a department head attempts to edit, delete, or pin an announcement in their department that was authored by an administrator, the server crashes with HTTP 500 Internal Server Error instead of returning HTTP 403 Forbidden.
- **Root Cause:**
  In `AnnouncementRoutes.kt:245`, `isDeptHeadForAnn` is `true` because the announcement is in the caller's department. The route allows the call through to `store.updateAnnouncement`.
  In `AnnouncementStore.checkCanModify:703`:
  ```kotlin
  val authoredByHead = announcement.authorRole == "dept_admin" || announcement.authorRole == "DEPT_HEAD"
  if (inOwnDept && authoredByHead) return
  throw AnnouncementForbiddenException("Actor cannot modify announcement...")
  ```
  `AnnouncementForbiddenException` inherits from `SecurityException`.
  In `AnnouncementRoutes.kt`, the `try-catch` block catches only `AnnouncementPinLimitException`, `AnnouncementConflictException`, and `IllegalStateException`. `AnnouncementForbiddenException` is uncaught and bubbles up to Ktor, which returns HTTP 500.

---

### MAJOR DEFECT 6: Room DAO `pruneOldAnnouncements` Only Prunes Org Scope and Inverts Unread Priority
- **Path & Line:**
  `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/HubAnnouncementDao.kt:59-69`
- **Severity:** **MAJOR**
- **Symptom:** Department-scoped announcements are never pruned from the client database. Additionally, unread announcements older than the keep limit are pruned while newer read announcements are kept, violating Plan §3.4.
- **Root Cause:**
  `pruneOldAnnouncements` hardcodes `scope = "org"`:
  ```kotlin
  public suspend fun pruneOldAnnouncements(maxKeep: Int = 500) {
      val idsToDelete = mutableListOf<String>()
      idsToDelete += findOlderThanKeepLimit("org", null, maxKeep)
      if (idsToDelete.isNotEmpty()) deleteByIds(idsToDelete)
  }
  ```
  Department announcements accumulate without bound. Furthermore, `findOlderThanKeepLimit` sorts only by `ORDER BY pinned DESC, createdAtMs DESC`, ignoring `readAtMs`. Plan §3.4 explicitly requires: *"pinned and unread are kept first"*.

---

### MINOR DEFECT 7: Unenrol Clear is Asynchronous & Non-Transactional with Plan B Snapshot
- **Path & Line:**
  `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:1198-1206`
- **Severity:** **MINOR**
- **Symptom:** Announcements are wiped outside the atomic transaction that purges organization member and department data on unenrol.
- **Root Cause:**
  Plan A3 §4.2 specifies: *"Unenrol deletes all announcement rows (same transaction as the Plan B snapshot)."*
  Instead, `RealFlashChatRepository` launches an uncoordinated background coroutine listening to `trustStore.credentials.collect { if (it == null) dao.clearAll() }`.

---

### MINOR DEFECT 8: Console Preview Honesty String Mismatch
- **Path & Line:**
  `server/src/main/kotlin/com/transfer/flash/server/admin/AnnouncementAdminRoutes.kt:98`
- **Severity:** **MINOR**
- **Symptom:** The console preview endpoint returns a custom hardcoded string instead of the canonical Section 9 string.
- **Root Cause:**
  Line 98 returns: `"Stored in plain text on the hub (ADR-138). Hub administrators can read this announcement."`
  Plan §9 requires exact string `org_ann_honesty`: `"This announcement is stored in plain text on the hub and visible to all members of the target audience."`

---

### MINOR DEFECT 9: Pin Limit Reached Returns HTTP 400 Bad Request Instead of 409 Conflict
- **Path & Line:**
  `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutes.kt:295`  
  `server/src/main/kotlin/com/transfer/flash/server/admin/AnnouncementAdminRoutes.kt:294`
- **Severity:** **MINOR**
- **Symptom:** Pin limit violation returns status 400 instead of 409.
- **Root Cause:**
  Plan A3 §7 explicitly specifies: *"Pin limits reached → 409 with the sentence naming which ones are pinned."*
  Both device and admin routes catch `AnnouncementPinLimitException` and respond with `HttpStatusCode.BadRequest` (400).

---

### MINOR DEFECT 10: Client Offline Search Lacks Unicode Folding Parity with Server FTS
- **Path & Line:**
  `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:7328-7350`
- **Severity:** **MINOR**
- **Symptom:** Offline search fails to match query terms containing accents or case variants that succeed when searching online against server FTS5.
- **Root Cause:**
  Client offline search uses SQLite `LIKE '%' || :queryFolded || '%'` on `searchFolded` without running the full Unicode NFC decomposition and normalization pipeline that server FTS uses.

---

### MINOR DEFECT 11: Missing WebSocket Broadcast on Retraction When Relay Router Unset
- **Path & Line:**
  `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutes.kt:377-385`
- **Severity:** **MINOR**
- **Symptom:** When announcements are deleted or unpinned, live clients do not receive a push hint if `router` is null during server test / standalone mode.
- **Root Cause:**
  Optional router reference `router?.broadcastAnnouncement(...)` lacks fallback or queued notification when router is temporarily unavailable.

---

## 6. Task 4 Mutation Test Specification

The following 4 mutations will be executed and verified once the build mutex is completely released:

1. **Mutation 1 (Publish Permission):**
   - *Target:* `AnnouncementRoutes.kt:145` — remove `!isOrgAdmin && !isDeptHead` check.
   - *Expected Failure:* `:server:test --tests "*AnnouncementRoutesTest.regularEmployeeCannotPostAnnouncements*"` must FAIL.
2. **Mutation 2 (Department Visibility Scoping):**
   - *Target:* `AnnouncementStore.kt:417, 460` — remove `department_id = ?` filter.
   - *Expected Failure:* `:server:test --tests "*AnnouncementStoreTest.visibilityFilterInSearchAndFeed*"` must FAIL.
3. **Mutation 3 (Retention Purge Guard):**
   - *Target:* `AnnouncementStore.kt:669` — remove `if (retentionDays > 0)` guard.
   - *Expected Failure:* Pruning with retentionDays = 0 must delete all announcements.
4. **Mutation 4 (Unenrol Wipe):**
   - *Target:* `RealFlashChatRepository.kt:1200` — comment out `dao.clearAll()`.
   - *Expected Failure:* `RealFlashChatRepositoryAnnouncementsTest.unenrolWipesAllStoredAnnouncements` must FAIL.

---

## 7. Backlog and Device Test Audit

- **Owed Device Tests:** All 11 device tests (`ANN-01` through `ANN-11`) specified in Plan A3 §10 are properly registered in `docs/testing/TEST-BACKLOG.md` under section `4zzzzzzzzz - Announcements (Plan A3, Stream S2, 2026-10-15)`:
  - `ANN-01`: Admin posts company-wide from console
  - `ANN-02`: Head posts from app to own department
  - `ANN-03`: Head cannot post company-wide
  - `ANN-04`: Offline phone catches up on connect
  - `ANN-05`: Search in console and app
  - `ANN-06`: Edit/delete propagation
  - `ANN-07`: Pinned order & pin limit enforcement
  - `ANN-08`: Hub restore from backup / epoch resync
  - `ANN-09`: Old app + new hub interoperability
  - `ANN-10`: Cold start notification tap routing
  - `ANN-11`: Background delivery observation
- **Status:** All correctly marked as `TODO (PLANNED, not built)`. No premature completion claims.

---

## 8. Final Verdict & Required Action

### Verdict: **REJECT for Plan A3**

**Justification:**
1. **Critical Integration Blocker:** Real department heads cannot use the feature on client devices due to the `"dept_admin"` vs `"dept_head"` role mismatch.
2. **Critical Data Loss:** Organizations with over 100 announcements permanently lose items during sync due to the broken `feed.seq` vs `feed.nextCursor` pagination bug.
3. **Integrity / Documentation Violation:** Documentation in progress logs and handoff files claims existence of `AnnouncementPurgeJob.kt` and `AnnouncementPurgeJobTest`, neither of which exists in the codebase.
4. **Security Vulnerability:** Unescaped string interpolation in `AnnouncementStore.searchAnnouncements`.
5. **Stability Defect:** Uncaught `AnnouncementForbiddenException` results in HTTP 500 server crashes.

### Instructions for Author Stream S2:
1. Fix `canPostHubAnnouncement` and `postHubAnnouncement` in `RealFlashChatRepository.kt` to check `HubRoles.DEPT_ADMIN` (`"dept_admin"`).
2. Fix delta sync pagination in `RealFlashChatRepository.refreshHubAnnouncements` to advance using `feed.nextCursor` rather than jumping to `feed.seq`.
3. Remove false claims about `AnnouncementPurgeJob.kt` and write real tests for purge in `HubServicesTest` or extract `AnnouncementPurgeJob` properly.
4. Parameterize all SQL queries in `AnnouncementStore.searchAnnouncements`.
5. Catch `AnnouncementForbiddenException` in `AnnouncementRoutes.kt` and return HTTP 403 `ERR_FORBIDDEN`.
6. Fix `HubAnnouncementDao.pruneOldAnnouncements` to prune department-scoped announcements and prioritize unread items over read ones.
