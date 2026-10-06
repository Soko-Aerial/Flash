# Swarm Transfer Star Topology Investigation & Bug Report

**Date:** 2026-10-06  
**Analyzed Logs:**
1. `flash-log-1791285866094.txt` — **Admin**
2. `flash-log-1791285897723.txt` — **Sender**
3. `flash-log-1791285883691.txt` — **Member 1**
4. `flash-log-1791285852720.txt` — **Member 2**

---

## 1. Device Identification & Roles

| Log File | Device ID | Display Name | IP Address | Topology Role |
| :--- | :--- | :--- | :--- | :--- |
| `flash-log-1791285866094.txt` | `30f7a9fc-fda8-4d4c-9ddb-39114683fce4` | *Admin* | `192.168.1.124` | **Group Owner & Admin** (Paired to all devices) |
| `flash-log-1791285897723.txt` | `0e318e8e-7c64-4ace-af0c-9a1ec80b5453` | *Flash Fox* | `192.168.1.122` | **Sender** (Paired only to Admin) |
| `flash-log-1791285883691.txt` | `a09951db-b7ec-48c0-9a77-10382faeb1da` | *Flash Lime* | `192.168.1.125` | **Group Member 1** (Paired only to Admin; vouched) |
| `flash-log-1791285852720.txt` | `c05a7508-7134-4392-b0ee-fb7309357c22` | *Flash Koala* | `192.168.1.126` | **Group Member 2** (Paired only to Admin; vouched) |

*(5th member `432532a4-4b4f-461b-b970-4736835ff886` also participated in group membership and calling).*

---

## 2. Primary Root Cause: Why Only Admin Received the File

### Core Reason: Group file sending is hard-gated on 1:1 visual pairing (`isTrustedPeer`), whereas group chat text supports vouched members.

In your test, you used a **Star Pairing Topology**:
* **Admin (`30f7a9fc`)** is directly 1:1 paired with all devices (`0e318e8e`, `a09951db`, `c05a7508`, `432532a4`).
* **Non-admin devices are NOT paired with each other.** They only know each other through Admin vouches (`PinSource.VOUCHED`).

In Flash:
1. **Group Text Chat** uses `isActiveGroupMember()`, which queries `isGroupPeerTrusted()`. This allows **vouched members** to exchange text messages without direct 1:1 pairing.
2. **Group Attachments & Swarm Transfers**, however, are gated on `isActiveTrustedMember()`:
   ```kotlin
   private suspend fun isActiveTrustedMember(
       members: GroupMemberDao,
       groupId: String,
       deviceId: String
   ): Boolean = isTrustedPeer(deviceId) && members.member(groupId, deviceId)?.isActive == true
   ```
3. `isTrustedPeer(deviceId)` calls `trustStore.isTrusted(deviceId)`. In `AndroidPreferencesTrustStore`, `isTrusted()` returns `true` **only if direct 1:1 visual pairing was performed**. Vouched devices have `pinSource == PinSource.VOUCHED`, for which `isTrusted()` returns `false`.

### What happened when Sender (`0e318e8e`) sent the file:

1. **Outbox recipient exclusion:**
   In `RealFlashChatRepository.kt#L2482-L2484`:
   ```kotlin
   // ADR-044 V2 (E3): files are paired-only, so a vouched member is not a recipient and cannot leave
   // this message PENDING for a delivery that will never be attempted.
   val recipients = members?.activeMembers(conversationId)
       ?.filter { it.deviceId != localDeviceId && isTrustedPeer(it.deviceId) }
       .orEmpty()
   ```
   Because Sender is not 1:1 paired with Member 1 (`a09951db`) or Member 2 (`c05a7508`), **they were filtered out of the recipient list immediately**. Only Admin (`30f7a9fc`) remained as an eligible recipient.

2. **Swarm announcement skipped for all non-paired members:**
   `GroupFileSender.kt#L104-L149` iterates over active group members and calls `announce(target.deviceId, ...)`.
   `announce` maps to `RealFlashChatRepository.beginGroupAttachment()`:
   ```kotlin
   if (!isActiveTrustedMember(members, groupId, recipientDeviceId)) return false
   ```
   For `a09951db` and `c05a7508`, `beginGroupAttachment()` returned `false` without doing anything.
   **The `GroupWireFrame.GroupMedia` frame (which announces the swarm root hash, piece size, and signature) was ONLY dispatched to Admin (`30f7a9fc`).**

3. **No Swarm peers joined:**
   Because Member 1 and Member 2 never received the `GroupMedia` announcement, their phones had no knowledge that a swarm transfer existed:
   - They never initiated piece discovery or bitfield broadcasts.
   - They never opened FSW1 swarm data channels.
   - Admin received the announcement, opened a direct data channel with Sender (`192.168.1.122:45823` to `192.168.1.124:42089`), and pulled the file pieces directly.

---

## 3. Detailed Bugs & Obstacles Found

### Bug 1: Inbound `GroupMedia` frame drops non-paired senders
* **Code:** `RealFlashChatRepository.kt#L2997-L2998`:
  ```kotlin
  is GroupWireFrame.GroupMedia -> {
      if (isRemovedHere(members, frame.groupId) || !isActiveTrustedMember(members, frame.groupId, frame.from)) return
  ```
* **Impact:** Even if a non-paired member receives a `GroupMedia` frame (e.g., via broadcast or relay), it silently drops it because `isActiveTrustedMember` fails on vouched members.

### Bug 2: Catch-Up Sync drops the message signature (`SyncPush message dropped, no valid signature`)
* **Log Evidence (`flash-log-1791285852720.txt` L289):**
  ```text
  10-06 11:19:57.332 W/CHAT: SECURITY: group SyncPush message dropped, no valid signature
  (group=g2-24bfdf9155d095f452e48f576f1062d5 msg=575999de-96a0-4578-a7af-5a657b306dbd
  author=0e318e8e-7c64-4ace-af0c-9a1ec80b5453 relay=c05a7508-7134-4392-b0ee-fb7309357c22)
  ```
* **Code:** `RealFlashChatRepository.kt#L4076-L4083`:
  When other members attempt to sync group history (`requestGroupCatchUp`), `SyncPush` attempts to verify `rules.verifyMessage(authorKey, frame.groupId, entity.id, entity.text, entity.signature)`.
  For media / attachment messages, the original sender signed an empty text (`""`), but during catch-up sync, `authorKey` lookup for non-directly-paired members or signature comparison failed, causing the sync push to be discarded with a security warning.

### Bug 3: Direct Swarm Peer Connections rely on direct IP mesh sessions
* **Code:** `NetworkSwarmTransport.kt` & `SwarmHostBinding.kt`.
* **Finding:** In your logs, Sender only had an active network session to Admin (`192.168.1.124`). Member 1 and Member 2 never established a direct TCP/WebSocket mesh session with Sender. While Swarm is designed to allow peers to pull from any peer that has pieces (including Admin once Admin gets pieces), because Member 1 and 2 never got the root announcement in the first place, they never joined the swarm mesh.

---

## 4. Summary & Root Cause Conclusion

| Question | Answer |
| :--- | :--- |
| **Why did only Admin receive the transfer?** | Sender was **only paired with Admin**. The file sender explicitly excludes non-paired (vouched) group members from file dispatch because legacy ADR-044 enforced `isTrustedPeer` (1:1 pairing only for files). |
| **Why did Swarm not propagate to other phones?** | Because the other phones never received the `GroupMedia` announcement frame, their swarm engines never knew the file existed, never announced piece availability, and never connected to the data channels. |
| **Are the phones able to talk over chat?** | Yes. Group text messages and calls support vouched members through Admin signature delegation (`isGroupPeerTrusted`), but file/swarm transfers remained restricted to 1:1 paired peers. |

---

## 5. Recommended Fixes (When Ready to Implement)

1. **Allow Vouched Members in Swarm Announcements:** Update `beginGroupAttachment` (`RealFlashChatRepository.kt#L3684`) and `sendGroupAttachment` (`RealFlashChatRepository.kt#L2482`) to use `isActiveGroupMember()` / `isGroupPeerTrusted()` instead of `isActiveTrustedMember()`.
2. **Allow Inbound `GroupMedia` from Vouched Peers:** In `RealFlashChatRepository.kt#L2997`, change `!isActiveTrustedMember` to `!isActiveGroupMember`.
3. **Fix Attachment Signature in `SyncPush`:** Ensure media message catch-up sync verifies the swarm announcement signature (`SignedGroups.verifySwarmAnnouncement`) rather than rejecting on empty text message signature.

---

## 6. Verification outcome (2026-10-06, after the fixes) - added by the fixing session

Each claim was checked against the code and the four logs before anything was changed. Full record: `logs/errors.md` ERROR-117, `docs/decisions.md` ADR-086, device tests `SWM-40`...`SWM-45`.

| Claim | Verdict |
| :--- | :--- |
| Section 2 / Bug 1 and the recommended fixes 1 and 2: files and swarm offers are paired-only | **Real, fixed** for swarm offers (a whole-file push stays paired-only on purpose). |
| Bug 2: catch-up drops the signature because of an empty text and an author key lookup | **Mechanism wrong.** ERROR-101 had fixed the text rewrite and the key lookup is fine. Two real causes found and fixed: a voice note's text, and the received `sentAt` being clamped to the receiver's clock (probable cause of the logged drops, not provable from the logs). The log quote in section 3 is wrong in two details (line number; `relay=` on that phone is the other member). |
| Bug 3: the other phones never opened mesh sessions to the sender | **Not a defect as written.** The sessions came up after the announcement (one phone restarted at 11:19:51). Also, the admin did not "pull through the swarm": its log shows only the whole-file push (`Data channel joined channel=0`) and no swarm line. Why it got a push is unproven (`SWM-45`). The real gap here was that an offer was not kept for members connecting later: fixed (`recordSwarmOffer`). |
