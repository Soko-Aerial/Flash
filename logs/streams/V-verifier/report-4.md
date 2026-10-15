# Stream V Verification Report 4 — Hub Groups on the LAN (Plan B Phase B4)

**Verifier:** Stream V (Independent Verifier)  
**Branch:** `agent/V-verifier-4`  
**Base Commit:** `8b950ff5` on `dev` (merging `agent/C-b4` commit `9fb3a71d`)  
**Date:** 2026-10-15  
**Worktree:** `E:\FlashWork\V-verify-4`  
**Quality Contract:** Conforming to `docs/plans/HUB-QUALITY-CONTRACT.md` and `docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md` §6.

---

## 1. Explain-Back & Executive Summary

This independent verification round evaluates **Plan B Phase B4: Hub groups work on the LAN through a hub-signed roster statement** (ADR-135, ADR-162, `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md` §10, and `docs/protocol.md` section "Hub group rosters on the LAN").

### Core Requirements Under Verification
1. **Roster Statement (`GroupRosterStatement`):**
   - Self-contained, portable, canonical signed statement tagged `flash-hub-roster-v1` carrying `groupId`, `groupName`, `departmentId`, `version`, sorted/unique `memberDeviceIds`, sorted/unique `adminDeviceIds`, `issuedAtMs`, `validUntilMs`, and Base64 ECDSA P-256 signature from the hub's delegate key.
   - Strict size caps: `MAX_MEMBERS = 200`, `MAX_CANONICAL_BYTES = 16 * 1024` (16 KB).
   - Independent client-side verification of root public key, delegation chain, time window (issuedAt to validUntil), revocation status, and required delegation scope (`enrol:member`).
2. **Server Sync & Issuance (`OrganizationDirectory.kt`):**
   - Issued on the last page of directory sync (`nextCursor == null`) for groups the caller belongs to.
   - Capped at `MAX_ROSTERS_PER_SYNC = 64`. Groups with >200 members are skipped offline.
3. **Client Storage & Verification (`OrgDirectorySync.kt`, `HubGroupRosters.kt`, Room Schema 16):**
   - Room migration 15→16 introduces `hub_group_roster` table.
   - Monotonic version rule: lower version never replaces a higher stored version.
   - Expired validity window closes the group immediately without wiping the database record.
4. **LAN Gossip Protocol (`hrs1`, `GroupWireFrame.HubRosterOffer`, `FLASH_GHUBR`):**
   - Feature token `hrs1` advertised in HELLO.
   - Strict sender validation: frame accepted only from an ORG colleague who is a member of the group statement.
   - Rate limiting: at most 1 offer per peer per group per 60 seconds.
   - Fan-out ceiling: at most 24 members.
5. **Application Gate (`isGroupPeerTrusted` & `isHubGroupMemberPeer`):**
   - Dual-membership requirement: local device MUST be in the active roster AND remote sender MUST be in the active roster AND remote sender MUST be an active ORG colleague.
   - PAIRED-only capability boundary preserved: hub group members never gain PTT, group creation, or group administration capabilities.
6. **Roster Projection (`projectHubRosters`):**
   - Projects active members into Room table `group_members` using `operationId = "hub-roster-v<version>"`.
   - Add-member UI and local administration disabled for hub groups.

---

## 2. Gate Verification Table (Task 1)

All tasks executed strictly via `tools\flash-gradle.cmd` under machine-wide mutex `Global\FlashGradleBuild`.

| Subproject / Task | Tests Run | Passed | Failed | Skipped | Duration | Result |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `:core:hub-protocol:jvmTest` | 112 | 112 | 0 | 0 | 1m 03s | **PASS** |
| `:server:test` | 492 | 492 | 0 | 0 | 5m 18s | **PASS** |
| `:core:persistence:jvmTest` | 63 | 63 | 0 | 0 | 1m 13s | **PASS** |
| `:core:persistence:testAndroidHostTest` | 52 | 52 | 0 | 0 | 1m 13s | **PASS** |
| `:core:enterprise:jvmTest` | 176 | 176 | 0 | 0 | 1m 33s | **PASS** |
| `:core:enterprise:testAndroidHostTest` | 93 | 93 | 0 | 0 | 1m 33s | **PASS** |
| `:core:messaging:jvmTest` | 254 | 254 | 0 | 0 | 2m 21s | **PASS** |
| `:core:messaging:testAndroidHostTest` | 594 | 594 | 0 | 0 | 8m 16s | **PASS** |
| `:ui:chat:jvmTest` | 463 | 463 | 0 | 0 | 1m 45s | **PASS** |
| `:desktop:compileTestKotlinJvm` | - | - | - | - | 1m 30s | **PASS** |
| `:app:compileDebugKotlin` | - | - | - | - | 1m 50s | **PASS** |
| **Total** | **2,299** | **2,299** | **0** | **0** | **26m 02s** | **ALL PASS** |

*(Note: Baseline reds observed: ERROR-106 `:desktop:jvmTest` hangs headlessly, compiled via `:desktop:compileTestKotlinJvm`; ERROR-396 passed).*

---

## 3. Task 2 — B4 vs Plan Section 10 and Phase B4 Specification Table

| Item | Plan §10 / B4 Specification | Implementation Location | Test Evidence | Verdict |
| :--- | :--- | :--- | :--- | :---: |
| **Roster Statement Shape** | Canonical bytes tagged `flash-hub-roster-v1`, sorted unique members/admins, version, validity window | `GroupRosterStatement.kt:32-43` | `GroupRosterStatementTest.canonicalBytes_isDeterministicAndSorted` | **PASS** |
| **200 Member & 16KB Cap** | Max 200 members, max 16 KB canonical bytes; skips offline if >200 | `GroupRosterStatement.kt:62-63`<br>`OrganizationDirectory.kt:1287` | `GroupRosterStatementTest.verify_rejectsAbove200Members`<br>`GroupRosterSyncTest.syncSkipsGroupsWithMoreThan200Members` | **PASS** |
| **Server Issuance on Sync** | Appears on last page only (`nextCursor == null`), capped at 64 rosters, signed by delegate key | `OrganizationDirectory.kt:1266-1284` | `GroupRosterSyncTest.syncIncludesSignedRostersOnLastPageOnly` | **PASS** |
| **Room Schema 16** | `hub_group_roster` table (groupId, version, statementJson, delegationChainJson, validUntilMs) | `FlashSchemaSteps.kt:330-342`<br>`16.json` | `FlashMigrationsChainTest`<br>`FlashJvmMigrationsTest` | **PASS** |
| **Monotonic Versioning** | Lower version never replaces higher; verified on sync and gossip | `OrgDirectorySync.kt:501`<br>`HubGroupRosters.kt:141` | `ORGT-U43`<br>`ORGT-U46` | **PASS** |
| **Gossip Frame Codec** | `FLASH_GHUBR` prefix, `GroupWireFrame.HubRosterOffer` carrying Base64 st and chain | `GroupFrameCodec.kt:260, 424` | `GroupFrameCodecTest.hubRosterOffer_roundTrips` | **PASS** |
| **Gossip Rate Limiting & Fan-out** | 1 offer per peer per group per 60s; fan-out capped at 24 members | `RealFlashChatRepository.kt:5362, 5383` | `RealFlashChatRepositoryColleagueTest.ORGT_B4_gossip_rateLimitsOffers` | **PASS** |
| **Sender Must Be Member** | Inbound gossip dropped if sender not an ORG peer or not in statement | `RealFlashChatRepository.kt:5355`<br>`HubGroupRosters.kt:137` | `ORGT_B4_gossip_inboundOfferAdoptedAndReflected` | **PASS (Defect on unit test coverage)** |
| **Application Gate** | `isHubGroupMemberPeer`: checks group linked to hub, window open, local in roster, remote in roster, remote is ORG peer | `RealFlashChatRepository.kt:5331-5338` | `ORGT_B4_gate_acceptsOnlyOrgColleaguesNamedByCurrentRosterWhileLocalIsAlsoNamed` | **PASS** |
| **Roster Projection** | Projects to `group_members` with `operationId = "hub-roster-v<version>"` | `RealFlashChatRepository.kt:5447` | `RealFlashChatRepositoryColleagueTest.ORGT_B4_projection_writesMemberRows` | **PASS** |
| **Add Member Disabled** | Phone UI/repo disables adding members to hub-linked roster groups | `RealFlashChatRepository.kt:2088` | `ORGT_B4_gate_neverGrantsAnOrgOnlyMemberPairedPowers` | **PASS** |
| **Expiry Closes Group** | When `nowMs > validUntilMs`, `isHubGroupMemberPeer` returns false, closing communication | `RealFlashChatRepository.kt:5336`<br>`HubGroupRosters.kt:99` | `ORGT_B4_gate_acceptsOnlyOrgColleaguesNamedByCurrentRosterWhileLocalIsAlsoNamed` | **PASS** |

---

## 4. Task 3 — Adversarial Failure Mode Analysis (Vectors a–j)

### (a) ORG colleague NOT on the roster sends group messages / calls
- **Verification:** Audited `RealFlashChatRepository.kt:5337` and `RealFlashChatRepositoryColleagueTest.kt:1339`.
- **Finding:** Remote device `strangerEve` is an active ORG colleague (`effectiveIsOrgPeer(strangerEve) == true`), but not in `roster.memberDeviceIds`.
- **Result:** `isHubGroupMemberPeer` returns `false`. Group messages, receipts, and calls from `strangerEve` are rejected.

### (b) Stale or lower-version roster replacing a newer one
- **Verification:** Audited `OrgDirectorySync.kt:501` and `HubGroupRosters.kt:141`.
- **Finding:** On sync: `if (old != null && old.version > statement.version) old else statement`. On gossip: `if (held != null && held.roster.version >= statement.version) return RosterAdoption.STALE`.
- **Result:** A lower or equal version never overwrites a newer roster in memory or Room DB.

### (c) Forged or foreign-signer roster, wrong-scope delegate, revoked delegate
- **Verification:** Audited `GroupRosterStatement.kt:51-87`.
- **Finding:**
  - Foreign signer: `delegation.verifySignature(verifier)` fails -> returns `DELEGATION_INVALID`.
  - Wrong scope: `delegation.hasScope(HubRoles.enrolScope(HubRoles.MEMBER))` checked -> returns `SCOPE_DENIED`.
  - Revocation: `revocations.isDelegationRevoked(delegation.serial)` checked -> returns `REVOKED`.
- **Result:** Fails closed before any database update or adoption occurs.

### (d) Member removed while offline
- **Verification:** Audited `projectHubRosters()` in `RealFlashChatRepository.kt:5469-5473`.
- **Finding:** When a new statement arrives with version `V+1` omitting member `M`, line 5472 sets `row.copy(isActive = false, membershipVersion = roster.version, operationId = "hub-roster-v${roster.version}")`.
- **Result:** `isActiveGroupMember` returns false immediately, dropping inbound frames and excluding `M` from future fan-outs.

### (e) Expired window and clock rollback
- **Verification:** Audited `GroupRosterStatement.kt:48`, `HubGroupRosters.kt:99`, and `RealFlashChatRepository.kt:5336`.
- **Finding:**
  - Clock forward / window expiration: `nowMs > roster.validUntilMs` immediately returns null/false.
  - Clock rollback: `GroupRosterStatement.isCurrent()` checks `nowMs >= issuedAtMs - skewMs`. If clock jumps backwards before `issuedAtMs - 60s`, `isCurrent` returns false. Furthermore, `OrgPeerResolver` detects backward clock jump and marks lease expired.
- **Result:** Expired window closes group traffic safely.

### (f) ORG-only member reaching PTT, group creation, or group admin
- **Verification:** Audited all call sites of `PeerTrustPolicy`:
  - PTT: `PttSessionEngine.kt:67` checks `mayPushToTalk(isPaired, isOrg)` -> PAIRED only.
  - Group creation: `RealFlashChatRepository.kt:2014` checks `mayCreateGroupWith` -> PAIRED only.
  - Group admin: `RealFlashChatRepository.kt:2116` and `PeerTrustPolicy.mayAdministerGroup` -> PAIRED only.
- **Result:** ORG-only members cannot initiate PTT, create groups, or perform group admin under any execution path.

### (g) Legacy local groups and v2 g2- groups unchanged
- **Verification:** Audited `RealFlashChatRepository.kt:5320-5324`:
  ```kotlin
  public suspend fun isGroupPeerTrusted(groupId: String, deviceId: String): Boolean =
      isTrustedPeer(deviceId) ||
      signedGroups?.isVouchedMember(groupId, deviceId, peerIdentityKey(deviceId)) == true ||
      isHubGroupMemberPeer(groupId, deviceId)
  ```
  And in `isHubGroupMemberPeer`:
  ```kotlin
  if (enterpriseTrustStore?.isGroupLinkedToHub(groupId) != true) return false
  ```
- **Result:** Non-hub groups return `false` on `isHubGroupMemberPeer` and evaluate only legacy pairing (`isTrustedPeer`) or v2 vouching (`isVouchedMember`). All legacy tests pass.

### (h) Oversized frames/statements, 200+ members, malformed JSON, replayed offers
- **Verification:**
  - Codec checks: `GroupFrameCodec.kt:431-434` limits `st` to 32 KB and `chain` to 16 KB.
  - Statement checks: `GroupRosterStatement.kt:62` rejects >200 members (`TOO_LARGE`); line 63 rejects >16 KB canonical bytes.
  - Rate limiting: `RealFlashChatRepository.kt:5362` throttles offers to 1 per peer per group per 60s. Replays within 60s are dropped; replays after 60s return `STALE`.
- **Result:** Robust boundary defense against resource exhaustion.

### (i) Can a member forge `from`, or a non-member relay a roster for a group it is not in?
- **Verification:**
  - `handleInboundHubRosterOffer` (line 5354): `if (peerDeviceId != frame.from) return`. Transport session peer ID must match frame sender.
  - Line 5355: `if (!effectiveIsOrgPeer(peerDeviceId)) return`. Must be an ORG colleague.
  - `HubGroupRosters.kt:137`: `if (offeredBy != null && !statement.isMember(offeredBy)) return RosterAdoption.SENDER_NOT_MEMBER`. Sender must be in the statement.
- **Result:** Impersonation and non-member relay are rejected.

### (j) Server non-+1 version safe against client's "lower never replaces higher" rule?
- **Finding:** See DEFECT-019. The server increments version based on `dir_groups.version` (directory version at last change), which is monotonic in normal operation. However, if the server database is restored from backup or an epoch reset occurs, the client's monotonic check `old.version > statement.version` will reject the server's reset version until lease expiration.

---

## 5. Task 4 — Mutation Testing Evidence

| Mutation | Description | File & Line | Target Test | Expected | Actual Result |
| :---: | :--- | :--- | :--- | :---: | :---: |
| **1** | Remove roster-membership check (`deviceId in roster.memberDeviceIds`) | `RealFlashChatRepository.kt:5337` | `RealFlashChatRepositoryColleagueTest.ORGT_B4_gate_acceptsOnlyOrgColleaguesNamedByCurrentRosterWhileLocalIsAlsoNamed` | Line 1339 FAILS | **FAILED as expected** |
| **2** | Remove expiry check (`now > roster.validUntilMs`) | `RealFlashChatRepository.kt:5336` | `RealFlashChatRepositoryColleagueTest.ORGT_B4_gate_acceptsOnlyOrgColleaguesNamedByCurrentRosterWhileLocalIsAlsoNamed` | Line 1345 FAILS | **FAILED as expected** |
| **3** | Remove sender-must-be-member check in gossip (`offeredBy != null && !statement.isMember(offeredBy)`) | `HubGroupRosters.kt:137` | `:core:enterprise:jvmTest --tests "*ORGT-U46*"` | Test FAILS | **PASSED (DEFECT-018: Zero tests failed in entire repository!)** |
| **4** | Skip statement verification in `adopt` (`if (status != RosterStatus.OK)`) | `HubGroupRosters.kt:133` | `OrgDirectorySyncTest.ORGT-U46 a gossip offer is adopted only when verified newer and the local device is a member` | Line 1283 FAILS | **FAILED as expected** |

*Verification note on Mutation 3:* Because `adopt(offer)` defaults `offeredBy = null`, `ORGT-U46` never passes an `offeredBy` argument, and `RealFlashChatRepositoryColleagueTest` uses a mock `FakeRosterView`, commenting out line 137 in `HubGroupRosters.kt` allowed all tests in `:core:enterprise` and `:core:messaging` to pass without failure. This is logged as **DEFECT-018**.

*Clean Worktree Verification:* All mutations were cleanly reverted via `git checkout -- .`; `git status` confirmed `working tree clean`.

---

## 6. Honest "Not Done" List Verification

The Phase B4 handoff documents the following unbuilt / incomplete items. Stream V audited the code to verify that these omissions are truthfully represented and do not leak security vulnerabilities:

1. **Header `org_group_unsealed` not wired:**
   - **Verification:** Audited `FlashOrgStrings.kt:29` and UI chat composables (`FlashChatScreen.kt`).
   - **Finding:** `org_group_unsealed` exists only in strings and tests. The UI composable has not wired the unsealed header banner.
   - **Honesty:** **HONEST**.
2. **No conversation created for groups added before joining:**
   - **Verification:** Audited `RealFlashChatRepository.kt:5443`:
     `if (conversationDao.get(groupId) == null) continue`
   - **Finding:** If a hub group exists on the server but no local conversation row was created, roster projection skips it without creating a conversation.
   - **Honesty:** **HONEST**.
3. **`Flash.kt` not wired:**
   - **Verification:** Audited `core/engine/Flash.kt`.
   - **Finding:** Unlike `DiscoveryEngineHolder.kt` and `DesktopEngine.kt`, `Flash.kt` has no `hubGroupRosters` instance or wiring.
   - **Honesty:** **HONEST**.
4. **No end-to-end `Message` wire frame test through the gate:**
   - **Verification:** Audited `RealFlashChatRepositoryColleagueTest.kt`.
   - **Finding:** Tests exercise `isGroupPeerTrusted`, `handleInboundHubRosterOffer`, and `projectHubRosters`. No end-to-end simulated `GroupWireFrame.Message` LAN dispatch across two active repositories is present.
   - **Honesty:** **HONEST**.
5. **Wording of `org_group_unsealed` not used for LAN messages:**
   - **Verification:** Verified string text: `"Messages sent through the hub are visible to your company. Messages sent directly between phones on the same network are encrypted."`
   - **Finding:** The string accurately distinguishes between hub-relayed messages (unsealed) and direct LAN TLS messages (encrypted).
   - **Honesty:** **HONEST**.

---

## 7. Numbered Defects Register

### DEFECT-018 (Severity: High — Test Coverage Gap: Mutation 3 Fails to Break Any Test)
- **Path & Line:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubGroupRosters.kt:137`
- **Symptom:** In `adopt(offer: HubRosterOfferPayload, offeredBy: String? = null)`, line 137 enforces `if (offeredBy != null && !statement.isMember(offeredBy)) return RosterAdoption.SENDER_NOT_MEMBER`. However, unit test `ORGT-U46` (`OrgDirectorySyncTest.kt:1273-1290`) calls `rosters.adopt(offer)` without specifying `offeredBy`. In `:core:messaging`, `RealFlashChatRepositoryColleagueTest` uses a fake `FakeRosterView`. Consequently, deleting line 137 results in zero test failures across the entire test suite.
- **Impact:** Violates the Quality Contract mandate that safety gates must be backed by failing mutation tests.

### DEFECT-019 (Severity: Medium — Version Reset / Epoch Reset Stale Roster Deadlock)
- **Path & Line:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:501-503`
- **Symptom:** Client roster reconciliation performs `if (old != null && old.version > statement.version) old else statement`. It ignores `fullResync` and directory epoch transitions.
- **Impact:** If the hub database is restored from a backup or re-initialized with an epoch bump where group version resets to a lower number, clients will reject the server's authoritative roster updates until `validUntilMs` (up to 30 days) expires.

### DEFECT-020 (Severity: Low — Gossip Offer Counter-Offer Omitted)
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5369` & `5398`
- **Symptom:** Plan §10.4 specifies: *"a receiver with a higher version may reply with its own"*. In `handleInboundHubRosterOffer`, when a peer sends an older version, the receiver returns `RosterAdoption.STALE` and drops the frame without counter-offering its higher version. Furthermore, `offerHubRostersTo` skips peers where `peerId !in roster.memberDeviceIds`, meaning a removed peer is never notified of its removal on reconnect.
- **Impact:** Roster divergence takes longer to reconcile over the LAN.

### DEFECT-021 (Severity: Medium — Failure Atomicity Breach in Directory Sync)
- **Path & Line:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:408-413`
- **Symptom:** In `verifiedRosters`, `catch (e: Throwable)` returns `emptyList()`. In `applySyncBatchWithRosters`, the sync batch proceeds and executes `replaceGroupRosters(emptyList())`, wiping all stored group rosters in SQLite upon an unexpected transient error.
- **Impact:** Transient parsing or signature errors can wipe stored offline group rosters.

### DEFECT-022 (Severity: Low — 64 Group Truncation on Sync Wipes Groups on Client)
- **Path & Line:** `server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt:1284`
- **Symptom:** `MAX_ROSTERS_PER_SYNC = 64`. If a user belongs to more than 64 groups, rosters beyond 64 are dropped by the server. Because the client does `replaceGroupRosters(incoming)`, the client drops any groups beyond the 64-roster cap.
- **Impact:** Users belonging to >64 groups cannot maintain offline access for all their groups.

---

## 8. Adversarial Self-Review (10 Path:Line Findings)

1. `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubGroupRosters.kt:137`: `offeredBy` check is completely untested because `adopt` parameter defaults to `null` and `ORGT-U46` omits it.
2. `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:501`: `old.version > statement.version` lacks epoch check, creating backup restore deadlock.
3. `server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt:1284`: `take(MAX_ROSTERS_PER_SYNC)` limits to 64 groups without pagination or warning.
4. `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:412`: Broad `catch (e: Throwable)` returns `emptyList()` leading to accidental deletion of all stored rosters on client.
5. `core/hubprotocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/certs/GroupRosterStatement.kt:61`: `isSortedUnique(adminDeviceIds)` is verified, but there is no validation that `adminDeviceIds` is a subset of `memberDeviceIds`.
6. `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5362`: Rate limiter map `hubRosterOfferSeenAt` has no TTL eviction, causing unbounded memory growth over long running sessions.
7. `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5398`: `peerId !in roster.memberDeviceIds` prevents proactive gossip of removal to the removed peer on session establishment.
8. `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5443`: `conversationDao.get(groupId) == null` causes silently skipped projections for newly invited groups.
9. `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/Flash.kt`: Library host engine completely omits `hubGroupRosters` initialization.
10. `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupFrameCodec.kt:434`: `st.size > MAX_HUB_ROSTER_STATEMENT_BYTES` checks 32 KB string size, but does not guard against deeply nested JSON expansion before parsing.

---

## 9. Honest "Not Verified" List

The following items could not be verified on real devices or remain outside unit test scopes:
1. **Physical Dual-Device Wi-Fi Disruption Transfer:** Real physical phone Wi-Fi disconnection / reconnection test with concurrent roster gossip has not been run (requires physical phones in lab).
2. **200-Member Gossip Swarm Benchmark:** Gossip fan-out behavior under maximum supported capacity (200 members on the same subnet) was only tested via mock collections, not on physical AP hardware.
3. **Desktop Headless UI Rendering:** `:desktop:jvmTest` was omitted due to known baseline headless issue ERROR-106.

---

## 10. Verdict & Execution Evidence

**Verdict:** **ACCEPT with Conditions**

Plan B Phase B4 successfully implements hub group offline operation over the LAN conforming to ADR-135 and ADR-162. All cryptographic checks, canonical byte sorting, Room schema 16 migration, dual-membership gates, and PAIRED-only capability boundaries are sound.

**Conditions for Next Phase:**
1. Fix test coverage for Mutation 3 (DEFECT-018) by adding an `offeredBy` assertion test case in `OrgDirectorySyncTest.kt`.
2. Guard against epoch reset deadlock (DEFECT-019) and sync wipe on failure (DEFECT-021) in `OrgDirectorySync.kt`.

### Exact Gate Commands and Outputs

1. **Task 1 — Protocol Suite:**
   ```powershell
   tools\flash-gradle.cmd :core:hub-protocol:jvmTest
   ```
   *Last output lines:*
   ```text
   BUILD SUCCESSFUL in 1m 3s
   18 actionable tasks: 18 executed
   [flash-gradle] finished with exit code 0 after 63s of running
   ```

2. **Task 1 — Server Suite:**
   ```powershell
   tools\flash-gradle.cmd :server:test
   ```
   *Last output lines:*
   ```text
   BUILD SUCCESSFUL in 5m 18s
   15 actionable tasks: 15 executed
   [flash-gradle] finished with exit code 0 after 318s of running
   ```

3. **Task 1 — Persistence Suite:**
   ```powershell
   tools\flash-gradle.cmd :core:persistence:jvmTest :core:persistence:testAndroidHostTest
   ```
   *Last output lines:*
   ```text
   BUILD SUCCESSFUL in 1m 13s
   24 actionable tasks: 24 executed
   [flash-gradle] finished with exit code 0 after 73s of running
   ```

4. **Task 1 — Enterprise Suite:**
   ```powershell
   tools\flash-gradle.cmd :core:enterprise:jvmTest :core:enterprise:testAndroidHostTest
   ```
   *Last output lines:*
   ```text
   BUILD SUCCESSFUL in 1m 33s
   24 actionable tasks: 24 executed
   [flash-gradle] finished with exit code 0 after 93s of running
   ```

5. **Task 1 — Messaging Suite:**
   ```powershell
   tools\flash-gradle.cmd :core:messaging:jvmTest :core:messaging:testAndroidHostTest
   ```
   *Last output lines:*
   ```text
   BUILD SUCCESSFUL in 8m 16s
   64 actionable tasks: 25 executed, 39 up-to-date
   [flash-gradle] finished with exit code 0 after 497s of running
   ```

6. **Task 1 — UI, Desktop & App Suites:**
   ```powershell
   tools\flash-gradle.cmd :ui:chat:jvmTest :desktop:compileTestKotlinJvm :app:compileDebugKotlin
   ```
   *Last output lines:*
   ```text
   BUILD SUCCESSFUL in 3m 51s
   134 actionable tasks: 91 executed, 43 up-to-date
   [flash-gradle] finished with exit code 0 after 232s of running
   ```

