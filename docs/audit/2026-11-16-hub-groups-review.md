# Hub Groups End-to-End Review & Gap Analysis

**Date:** 2026-11-16  
**Scope:** Live logs (Android, Desktop, `myserver`, repo logs), Hub server (`server/`), Flash client (`core/`, `app/`, `desktop/`, `ui/`), and gap analysis for requested Hub group policies.  
**Status:** Verified read-only audit (no product code modified).

---

## 1. Live Log Findings

1. **Hub Server (`ssh myserver`, `~/flash-hub`):**
   - `flash-hub-hub-1` (Up 2h, healthy) and `flash-hub-tunnel-1` (Up 4w) running at `https://stolen-tribunal-herein-describing.trycloudflare.com`.
   - `docker compose logs hub --since=2h`: zero 4xx/5xx, group, or WebSocket errors. Observed normal relay registrations (`10:34:18Z`, `10:38:00Z`) and a revocation/lift cycle on device `adb2d87d-ac2d-42a9-81a6-fa59340e59c2` (`10:30:43Z`–`10:33:16Z`).
2. **Android (`adb logcat`, device `13114704AP002141`, `com.transfer.flash` PID `14100`):**
   - `10:40:53.704`–`10:40:55.007`: `org.sync.start since=5` -> `org.snapshot.applied version=8 membersCount=1` -> `hub.ui.snapshot people=2 departments=1` -> `org.roster.projected group=data-gro version=8 rows=2`, `ORG_SYNC: reason=hub_tab_opened outcome=Success`.
   - Periodic `hub.online result=read count=2` and 1:1 `CHAT` frame to `fe91abce` (`10:41:07.053`) succeeded. Zero Hub group or WS errors.
3. **Desktop (`C:\Users\KaliOxygen\.flash\desktop.log`, local `fe91abce`):**
   - `09:18:02.119`: created local v2 group `g2-8fd10e7fa18fbd5d98c140d7dee6b9a2` (pre-Hub enrollment).
   - `10:37:59.704`–`10:38:01.762`: enrolled in Hub (`org=B674836F...`, `version=7`, `membersCount=2`), published sealing key (`fp=7e8e 6751 c83f 18b4`), `ORG_SYNC: reason=enrol outcome=Success`.
   - `10:38:01`–`10:41:38`: `hub.online result=read count=2` every ~15s. **No `org.roster.projected`, no `version=8` sync, and no Hub group activity.**
4. **Repo Logs (`logs/handoff.md`, `logs/progress.md`, `logs/errors.md`):**
   - No open Hub group errors in `logs/errors.md`; `2026-11-16` entries cover `myserver` update (`4c17a0a02821`) and invite-link verification.

**Why Desktop stayed at `version=7` while Android got `data-gro` (`version=8`):**  
`OrganizationDirectory` never pushes directory-version-bump notifications over `/flash-relay` (`RelayFrameRouter.kt` only broadcasts `RelayFrame.AnnouncementEvent` at `L919`). On both Android and Desktop, `OrgSnapshotCoordinator` (`core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgSnapshotCoordinator.kt:L53-106`) only syncs on enrollment change (`"enrol"`), relay connect (`"relay"`), every 6 hours (`SIX_HOURS_MS`, `"periodic"`), or explicit UI actions (`"hub_tab_opened"` / `"hub_refresh"`). Desktop enrolled at `version=7` and never opened the Hub tab after `version=8` was created, whereas Android opened the Hub tab at `10:40:53`.

---

## 2. End-to-End Flow Review

### 2.1 Group Creation: Hub-Created vs. Phone-Created

| Aspect | Hub-Created Directory Group (e.g., `data-gro`) | Phone-Created Local Group + Hub Link |
| :--- | :--- | :--- |
| **Creation Path** | Server-only: `POST /api/v1/groups` (`DirectoryRoutes.kt:323-338`) or `POST /admin/api/v1/groups` (`AdminApiDirectory.kt:120-140`). Requires `ORG_ADMIN` (org-wide) or `DEPT_ADMIN` (dept-scoped). | Client UI: `FlashCreateGroupSheet` -> `createGroupLocked` / `createV2GroupLocked` (`RealFlashChatRepository.kt:L2535-2610, L2886-2898`). |
| **Client Creation Support** | **None.** `HubContract.PATH_GROUPS` (`/api/v1/groups`) and `CreateGroupBody` are never called in `core/`, `app/`, `desktop/`, or `ui/`. | Local v2 SignedGroup; requires every invitee to have a **live LAN session** (`peerGroupProtocol` / `peerIdentityKey`). |
| **Server Storage** | `dir_groups`, `dir_group_members`, `dir_join_requests`, `dir_preauth`, `directory_state` (`OrganizationDirectory.kt:1564-1636`). No group E2EE keys stored. | **None.** `linkGroupToHub(groupId)` (`RealFlashChatRepository.kt:L8224-8241`) only sets a local flag in `EnterpriseTrustStore`. |
| **Propagation to Devices** | `GET /api/v1/directory/sync` delivers delegate-signed `GroupRosterStatement`s (`OrgDirectorySync.kt:L490-534`) -> `projectHubRosters()` (`RealFlashChatRepository.kt:L6072-6132`) creates `ConversationEntity`, calls `trust.linkGroupToHub(groupId)`, and upserts `GroupMemberEntity` (`operationId = "hub-roster:<version>"`). | **No Hub propagation.** Recipients do not auto-link the group to Hub; inbound Hub `GROUP_SEALED_V1` / `GROUP_FRAME` frames are rejected (`HubChatBridge.kt:L615, L736`) unless each recipient manually toggles Hub link. |

### 2.2 Join Modes
1. **Enrollment Invite (`autoJoinGroups` / `allowedGroups`):** `POST /admin/api/v1/invites` -> `/api/v1/enroll` -> `OrganizationDirectory.applyEnrollment` (`L967-987`) inserts `dir_group_members` (`autoJoinGroups`) and `dir_preauth` (`allowedGroups`).
2. **Self-Service Browse / Join Request:** `GET /api/v1/directory/groups` -> `RealFlashChatRepository.joinEnterpriseGroup` (`L8291-8325`) -> `POST /api/v1/groups/join-request` (`DirectoryRoutes.kt:387-414`, `OrganizationDirectory.requestJoinGroup` `L996-1059`). `dir_preauth` bypasses `HIDDEN`, `ADMIN_ONLY`, `REQUEST_APPROVAL`, and scope checks. `OPEN_IN_SCOPE` returns `200 JOINED`; `REQUEST_APPROVAL` returns `202 PENDING` (polled via `refreshPendingEnterpriseJoins`, `L8327-8335`, and approved via `/api/v1/groups/join-requests/decision` or `/admin/api/v1/join-requests/{id}/approve`).
3. **Direct Admin Assignment:** `POST /api/v1/groups/members` (`DirectoryRoutes.kt:370-384`) or `POST /admin/api/v1/groups/{id}/members` (`AdminApiDirectory.kt:172-190`), synced to clients via `GET /api/v1/directory/sync`.
4. **Invite Links (`GET /invite/group`, `FlashJoinGroupDialog`):** `InviteWebRoutes.kt:14-18` + `invite-ui/invite.js` only decode `#<b64url>` into `flash://g/1/<token>` for **LAN-only P2P v2 SignedGroups** (`acceptInvite`, `RealFlashChatRepository.kt:L2267-2355`). There is no server-side Hub group invite token API.

### 2.3 Online Members & Presence
- **Server:** `RelayConnectionPool::isOnline` tracks active `/flash-relay` WebSocket sessions, surfaced in `GET /api/v1/directory/groups` (`onlineCount`) and `GET /api/v1/directory/members?groupId=` (`online`).
- **Client:** `EnterpriseHub.pollHubPresence()` populates `peers.hubOnline`, which works for 1:1 chats (`RealFlashChatRepository.kt:L1341-1344, L1353`) and Hub tab group counts, **but is omitted in group chat list/header/member list UI** (see Bug C1).

### 2.4 Group Messaging & E2EE
- Outbox `sendGroupViaPaths` (`RealFlashChatRepository.kt:L8149-8170`) tries LAN first; if `!lanOk` (or after `hubFallbackGraceMs` if still pending), calls `HubChatBridge.sendGroup` (`HubChatBridge.kt:L405-458`).
- `HubGroupE2eeCoordinator.prepareGroupSend` checks `evaluateGroup` (fail-closed if any active member lacks a verified sealing key), sends `GROUP_KEY_DIST_V1` wrapped in pairwise `SEALED_V1`, and sends `GROUP_SEALED_V1`.
- **Important routing detail:** `HubChatBridge.sendGroup` (`L455`) calls `port.send(to = memberId, ...)` -> `FlashRelayClient.kt:L226` emits `RelayFrame.Send(to = recipientDeviceId, group = null)`. Thus, **all client group messages traverse the Hub as pairwise 1:1 `RelayFrame.Send` frames** (`RelayFrameRouter.handleSend`, `L227`), not `RelayFrame.Send(group = groupId)` (`RelayFrameRouter.handleGroupSend`, `L254-305`).
- On receipt, `HubChatBridge.process` (`L607-647`) verifies `groupLinked(meta.groupId)`, `groupMemberAllowed`, and opens `GROUP_SEALED_V1` (returning `RETRY_LATER` if the epoch key has not arrived yet so the envelope stays in `MailboxQueue`).

---

## 3. Verified Bugs (Client & Server)

### Client Bugs
1. **C1 — Group online count & member online status ignore `peers.hubOnline`:**  
   `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt` (`L1332-1337`, `L1805-1806`, `L1855`) only checks `deviceId in peers.online` (LAN), unlike direct chats (`L1341-1344, L1353`) which check `peers.online || peers.hubOnline`. Hub-online group members always show as `0 online` / Offline in chat list, header, and `FlashGroupMembersSheet`.
2. **C2 — `joinEnterpriseGroup` does not populate `GroupMemberEntity` or sync rosters:**  
   `RealFlashChatRepository.kt:L8291-8325` (`joinEnterpriseGroup`) and `L8327-8335` (`refreshPendingEnterpriseJoins`) create `ConversationEntity` and call `trust.linkGroupToHub(groupId)` on `JOINED`, but never trigger `OrgDirectorySync` / `refreshHubGroupRosters()` or insert `GroupMemberEntity` rows. Until a separate directory sync runs, `groupMemberDao.activeMembers(groupId)` is empty -> `sendGroupText` (`L3107-3112`) aborts with `no_other_active_member` and `groupMembershipProvider` (`L639-641`) returns `null`, blocking `HubChatBridge.sendGroup` (`HubChatBridge.kt:L413`).
3. **C3 — No client UI/API support for creating Hub directory groups, and local `linkGroupToHub` does not sync to Hub or recipients:**  
   `HubContract.PATH_GROUPS` / `CreateGroupBody` are unused in client code. `createGroupLocked` (`RealFlashChatRepository.kt:L2535-2566`) requires live LAN sessions for all invitees, and `linkGroupToHub` (`L8224-8241`) is strictly local—causing recipients to reject inbound `GROUP_SEALED_V1` at `HubChatBridge.kt:L615` (`"group is not linked to the hub"`).
4. **C4 — No WebSocket push / reactive sync when Hub directory version bumps:**  
   `OrgSnapshotCoordinator.kt:L53-106` only syncs on enrol, relay connect, 6h timer, or Hub tab open/refresh. Paired with the lack of server WS push, enrolled devices (like Desktop in `desktop.log`) miss newly created Hub groups (`version=8`) until the 6h timer fires or the user opens the Hub tab.

### Server Bugs
5. **S1 — `applyEnrollment` does not bump `group.version`:**  
   `server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt:L967-987` inserts `dir_group_members` and updates in-memory `memberDeviceIds` for `autoJoinGroups` without calling `bumpVersionLocked()` or updating `dir_groups.version`, causing `signedRostersFor` (`L1420`) to emit stale `GroupRosterStatement` versions.
6. **S2 — `removeMemberFromGroup` does not clear `dir_preauth`:**  
   `OrganizationDirectory.kt:L1150-1165` deletes from `dir_group_members` and `adminDeviceIds`, but leaves `(groupId, deviceId)` in `dir_preauth` / `preAuthorized`. Kicked members who originally had `allowedGroups` pre-auth can immediately rejoin via `POST /api/v1/groups/join-request` (`L1009`), even for `HIDDEN` or `ADMIN_ONLY` groups.
7. **S3 — Unvalidated `adminDeviceIds` & zero-member Console groups:**  
   `OrganizationDirectory.kt:L641-658` (`createGroup`) and `L716-723` (`setGroupAdmins`) do not verify that `adminDeviceIds` exist in `dir_members`, inflating `GroupDto.memberCount` relative to `GET /api/v1/directory/members?groupId=` (`DirectoryRoutes.kt:L262`). Additionally, Console-created groups (`AdminApiDirectory.kt:L134-136`) with empty `adminDeviceIds` strip `CONSOLE` and start with 0 members and 0 admins.

---

## 4. Gap Analysis for Requested Hub Settings

### 4.1 Setting (a): Restrict/Disable Hub Usage for Personal (Local) Groups
- **Current state:** **Not implemented.**
  - Server: `AdminApi.kt:L925-973` (`settingsRoutes`), `SettingsDto`, `ServerConfig.kt`, and `HubInfo` (`AuthRoutes.kt`) have no setting or policy flag for personal/local groups. While `RelayFrameRouter.handleGroupSend` (`L255-264`) and `FileStore` (`L83, L577-578`) reject non-directory `groupId`s, **clients send group messages pairwise** (`RelayFrame.Send(to = memberId, group = null)`), so the Hub server cannot distinguish a personal-group frame from a 1:1 DM.
  - Client: `RealFlashChatRepository.linkGroupToHub` (`L8224-8241`) allows linking any local group to the Hub without checking `hubGroupRosters` or any org policy.
- **Required to implement:**
  1. Add a Hub admin setting (e.g., `groups.allow_personal_hub_link`, exposed in `HubInfo` / `SettingsDto` and `admin-ui`).
  2. Enforce on client: disable/hide the "Link to Hub" toggle in `FlashChat` UI and reject in `RealFlashChatRepository.linkGroupToHub` when `!hubGroupRosters.isHubGroup(groupId)` and policy is disabled.
  3. Enforce on server (defense-in-depth): route Hub group messages via `RelayFrame.Send(group = groupId)` (using `RelayFrameRouter.handleGroupSend`, which already enforces `DirectoryGroupMembership.membersOf(groupId)`) so non-directory groups cannot be relayed as group traffic.

### 4.2 Setting (b): Hub Visibility / Inspection of Group Messages
- **Current state:** **Not implemented.**
  - Server: `RelayFrameRouter.kt`, `MailboxQueue.kt`, and `docs/protocol.md:L1916-1931` have no plaintext, audit, or key-escrow mode; `RelayFrame.Send.payload` is opaque and sealed at rest with `SecretBox`.
  - Client: `DiscoveryEngineHolder.kt:L1714` and `DesktopEngine.kt:L1882` unconditionally wire `HubGroupE2eeCoordinator`. `HubChatBridge.sendGroup` (`L405-458`) always seals into `GROUP_SEALED_V1` (ignoring `contentViaHub()`), and `HubChatBridge.route` (`L724-727`) explicitly drops unencrypted `GROUP_FRAME` / `LEGACY_GROUP` payloads when `groupE2ee != null`.
- **Required to implement:**
  1. Explicit org-level policy flag (e.g., `groups.e2ee_mode` = `E2EE_REQUIRED` vs. `HUB_INSPECTABLE` / `KEY_ESCROW`), surfaced in `HubInfo` / `DirectorySyncDto` and clearly disclosed to users in the UI.
  2. Either (a) escrow/encrypt a copy of each group epoch key (`GROUP_KEY_DIST_V1`) to an org audit sealing key so E2EE between endpoints is preserved while the Hub can decrypt for compliance, or (b) allow `GROUP_FRAME` over TLS without `GROUP_SEALED_V1` when policy permits (updating `HubChatBridge.sendGroup` and `HubChatBridge.route:L724-727`).
  3. Server-side audit/inspection storage or webhook pipeline (currently only `announcements` are plaintext on the Hub).

---

## 5. Recommended Fix Plan

1. **Immediate Client Fixes (P0 — unblocks Hub groups on Android & Desktop):**
   - **Fix C1:** Include `peers.hubOnline` in group online counts and member online status in `RealFlashChatRepository.kt` (`L1332-1337`, `L1805-1806`, `L1855`).
   - **Fix C2:** In `RealFlashChatRepository.joinEnterpriseGroup` (`L8304-8318`), trigger `orgSnapshotCoordinator?.requestSync("group_joined", force = true)` (or `refreshHubGroupRosters()` + immediate `GroupMemberEntity` upsert for `localDeviceId` and fetched group members) on `EnterpriseJoinStatus.JOINED` so `groupMemberDao.activeMembers(groupId)` is populated immediately.
2. **Sync Freshness (P1 — fixes Desktop `version=7` lag):**
   - **Fix C4 / Server Push:** Emit a lightweight `RelayFrame.Meta` (or new `RelayFrame` notification) from `RelayFrameRouter` when `OrganizationDirectory` bumps `currentVersion`, and handle it in Android/Desktop relay listeners by calling `orgSnapshotCoordinator.requestSync("ws_dir_bump")`.
3. **Server Directory Integrity Fixes (P1):**
   - **Fix S1:** Call `bumpVersionLocked()` and persist `dir_groups.version` inside `OrganizationDirectory.applyEnrollment` (`L974-980`).
   - **Fix S2:** Delete `(groupId, deviceId)` from `dir_preauth` and `preAuthorized` in `OrganizationDirectory.removeMemberFromGroup` (`L1150-1165`).
   - **Fix S3:** Validate `adminDeviceIds` against `members` in `createGroup` / `setGroupAdmins` (and reject/warn on 0-admin Console groups if unintended).
4. **Client Hub Group Creation & Policy Settings (P2):**
   - Wire `HubContract.PATH_GROUPS` (`POST /api/v1/groups`) into `FlashCreateGroupSheet` for `ORG_ADMIN` / `DEPT_ADMIN` users, or clearly separate "Local P2P Group" from "Hub Organization Group".
   - Implement the two new Hub policy settings (`groups.allow_personal_hub_link` and `groups.e2ee_mode` / key escrow) per Section 4.
