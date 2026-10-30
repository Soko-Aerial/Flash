# Flash Security Audit 2026-10-22 — Domain D05: Messaging Semantics

**Audit Date:** 2026-10-22  
**Audited Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain Lead:** Domain D05 Auditor  
**Status:** COMPLETE  

---

## 1. Executive Summary

Domain D05 encompasses the messaging semantics across 1:1 direct chat, group messaging (both legacy v1 and signed v2), Hub relay bridges, and outbox delivery engines within `:core:messaging`, `:core:engine`, and `:core:persistence`.

Flash implements a multi-transport, multi-topology messaging fabric:
- **Direct 1:1 LAN & Hotspot Messaging:** Point-to-point text and control frames (`FLASH_MSG`, `FLASH_RCPT`, `FLASH_READ`, `FLASH_REACT`, `FLASH_TYPING`, `FLASH_XFER_DELETE`) running directly over TLS 1.3 / WebSocket sessions between peer IP endpoints.
- **Legacy (v1) Group Messaging:** Ad-hoc peer-to-peer fan-out groups with unsigned member rosters and messages.
- **Signed (v2) Group Messaging (ADR-044):** Cryptographically enforced groups with charter founding statements, member certificates, monotonic roster versioning, and per-message ECDSA P-256 signatures over canonical representations.
- **Enterprise Hub Chat Bridge (ADR-122 / ADR-126):** Asynchronous store-and-forward relaying over WebSocket and REST with optional end-to-end sealing (`HubSealing` / AES-256-GCM).
- **History Catch-Up & Sync (ADR-100):** Windowed and paged message catch-up protocol (`GroupWireFrame.Sync*`) allowing members to synchronize missed history up to a signed history ceiling.
- **Durable Local Storage & Outbox:** Encrypted SQLite (SQLCipher via Room) persisting messages, read cursors, delivery receipts, reactions, and an atomic retry outbox.

### Key Conclusions:
1. **Critical Ingress Gate Omission on 1:1 LAN Messaging (SEC-D05-01):** While `PeerTrustPolicy` defines capability predicates (`mayChat`, `mayReact`, `mayRing`), `PeerTrustPolicy.mayChat` is never evaluated for inbound direct 1:1 messages in `RealFlashChatRepository.onInboundWireFrame`. Any unauthenticated LAN or hotspot endpoint establishing a raw TLS connection can inject arbitrary text messages directly into the victim's Room database, create conversation list entries, fire intrusive user-facing notifications, and trigger auto-delivery receipts.
2. **Historical Message Forgery in Legacy Groups (SEC-D05-02):** In legacy (v1) group history synchronization, `handleSyncPush` accepts `fields["author"]` without signature verification and without validating that `message.from == frame.from`. Any malicious group member answering a catch-up request can inject fabricated historical messages attributed to any other member, framing them for statements they never made.
3. **Unauthenticated Typing Spoofing (SEC-D05-03):** 1:1 direct typing indicator processing lacks any peer authentication or session-peer binding, trusting `frame.memberName` directly from the wire. An attacker can broadcast fake typing indicators claiming to be administrative personnel or trusted colleagues.
4. **Delivery Receipt Forgery & Outbox Suppression (SEC-D05-04):** Direct delivery receipts update status and delete outbox rows based solely on `frame.messageId` without verifying that the referenced message was addressed to `frame.memberId`. An attacker who knows or targets an in-flight message ID can silently suppress outbox retransmissions, preventing legitimate recipients from receiving messages.
5. **Pre-Emptive Tombstone Injection Blocks Legitimate Traffic (SEC-D05-05):** `DeleteForEveryone` frames for unknown message IDs insert pre-emptive tombstones. Because Room uses `OnConflictStrategy.IGNORE` on the primary key `localId`, subsequent inserts of legitimate records with that ID are permanently dropped. This enables anti-forensic suppression of WebRTC call event logs (`callId`).
6. **Transcript Reordering via Negative Timestamp Skew (SEC-D05-06):** Inbound message timestamps are bounded on the future side but enforce no lower bound. Messages backdated to 1970 are stored silently deep in historical transcripts and demote conversation ordering in the chat list.
7. **Compulsory Read Receipts Act as a Presence Oracle (SEC-D05-07):** Flash automatically dispatches millisecond-timestamped read receipts on conversation open with no user setting or opt-out mechanism.
8. **Mathematical Integrity in v2 Groups & Access-Gated Catch-Up:** In v2 signed groups, message forgery is mathematically prevented by ECDSA canonical signing. Group catch-up requests (ADR-100) are rigorously gated by `isActiveGroupMember`, preventing non-members from extracting conversation transcripts.

---

## 2. Audited Files

The following files were subjected to line-by-line white-box auditing:

| Module | File Path | Focus & Attack Surface |
|---|---|---|
| `:core:messaging` | `core/messaging/src/commonMain/.../RealFlashChatRepository.kt` | Inbound wire frame dispatch, trust checks, outbox drain loop, reactions, deletions, catch-up |
| `:core:messaging` | `core/messaging/src/commonMain/.../PeerTrustPolicy.kt` | Capability gate predicate matrix (`mayChat`, `mayReact`, `mayRing`, etc.) |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/ChatTextFrameCodec.kt` | Direct chat frame text parsing and encoding (`FLASH_MSG`, `FLASH_RCPT`, `FLASH_READ`, etc.) |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/DirectMessageActionCodec.kt` | Direct deletion action codec (`FLASH_XFER_DELETE`) |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/GroupFrameCodec.kt` | Group wire frames, `SyncRequest`, `SyncPush`, author attribution parsing |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/MessageWireFrame.kt` | Direct messaging wire frame data models |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/GroupWireFrame.kt` | Group messaging wire frame data models |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/GroupCanonical.kt` | Canonical length-prefixed serializer for group messages and rosters |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/CatchUpLane.kt` | Catch-up lane leader election, stall monitoring, episode timeouts |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/GroupHistoryPolicy.kt` | Window math, ceiling clamping, page sizing for catch-up |
| `:core:messaging` | `core/messaging/src/commonMain/.../hub/HubChatBridge.kt` | Hub relay chat ingress, sender standing, sealing verification, outbox ack tracking |
| `:core:messaging` | `core/messaging/src/commonMain/.../radio/RadioChatBridge.kt` | Radio fallback delivery, unencrypted APRS/RFCOMM packet dispatch |
| `:core:engine` | `core/engine/src/commonMain/.../FlashInboundRouter.kt` | Unified inbound routing cascade, E2E decryption, transfer ownership |
| `:desktop` | `desktop/src/jvmMain/.../DesktopEngine.kt` | Desktop message transport sink, session key lookup, dial-on-demand |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/dao/MessageDao.kt` | Message CRUD, pagination, read marks, tombstones, conflict strategies |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/dao/ReceiptDao.kt` | Delivery receipt persistence and observation |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/dao/ReactionDao.kt` | Message reaction aggregation and observation |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/dao/OutboxDao.kt` | Atomic outbox retry scheduling and deletion |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/dao/DraftDao.kt` | Composer draft persistence and clearance |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/dao/GroupMemberDao.kt` | Group roster member status and active counts |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/dao/GroupHistoryDao.kt` | History sync state, holder watermarks, join card status |
| `:core:persistence` | `core/persistence/src/commonMain/.../db/entity/MessageEntity.kt` | Room schema definition, primary keys, indexes, attachment/quote fields |

---

## 3. Checklist for Domain D05

| Item | Checklist Topic | Status | Summary Reason / Finding Reference |
|:---:|---|---|---|
| **1** | **Message authentication** | **Vulnerable** (SEC-D05-01, SEC-D05-02) | In 1:1 LAN direct chat, `PeerTrustPolicy.mayChat` is dead code; unauthenticated LAN devices can inject messages and spoof `senderName` (**SEC-D05-01**). In legacy v1 groups, `SyncPush` accepts unauthenticated `author` fields, allowing malicious peers to forge historical messages from any member (**SEC-D05-02**). In v2 groups, ECDSA canonical signatures strictly prevent forgery. |
| **2** | **Receipt forgery** | **Vulnerable** (SEC-D05-04) | In 1:1 direct chat, `DeliveryReceipt` processing checks `frame.memberId == transportPeerId` but fails to check that `frame.messageId` was addressed to `memberId`. A malicious peer can forge delivery receipts for in-flight messages, deleting them from the victim's outbox and terminating delivery retries (**SEC-D05-04**). Read receipts are scoped by conversation ID and cannot be forged across chats. |
| **3** | **Typing & reaction spoofing** | **Vulnerable** (SEC-D05-03) | Direct 1:1 typing indicators (`FLASH_TYPING`) bypass pairing/org trust and fail to verify `transportPeerId == frame.memberId`. An attacker can spoof `frame.memberName` to inject arbitrary text in the user's chat header (**SEC-D05-03**). Group typing and reactions are properly gated by active membership and transport peer identity. |
| **4** | **Delete-for-everyone & edit semantics** | **Vulnerable** (SEC-D05-05) | Message editing is **Not applicable** (no edit wire frames exist). For deletion, authors can only delete their own messages. However, pre-emptive tombstones inserted for in-flight message IDs silently suppress subsequent legitimate message and call log insertion via primary key collision (**SEC-D05-05**). Deletion blanks plaintext in SQLite but leaves attachments/media on disk. |
| **5** | **Replay & reordering** | **Vulnerable** (SEC-D05-06) | Replayed messages are rejected by SQLite (`OnConflictStrategy.IGNORE`) without duplicate notifications, though direct replay resets conversation sort order. Inbound timestamps lack any lower bound, allowing attackers to backdate messages to 1970, reordering transcripts and demoting conversations (**SEC-D05-06**). |
| **6** | **Message-ID collisions** | **Not vulnerable** (Drops) | Duplicate message IDs are dropped by `OnConflictStrategy.IGNORE` in `MessageDao.insert`, returning `-1L`. Existing message content is preserved and never overwritten. Cross-conversation collisions drop the newcomer due to the global `localId` primary key. |
| **7** | **Outbox retry behavior** | **Vulnerable** (SEC-D05-04) | Outbox rows are encrypted at rest in SQLCipher. However, delayed LAN receipts trigger automatic fallback to Hub relay. Furthermore, if radio is active, outbox retry falls back to unencrypted APRS/RFCOMM broadcasts. Premature deletion via forged receipts suppresses outbox delivery (**SEC-D05-04**). |
| **8** | **Read-receipt / oracle leaks** | **Vulnerable** (SEC-D05-07) | Flash unconditionally dispatches millisecond-timestamped read receipts whenever a conversation is opened on screen. No user preference or privacy setting exists to disable read receipts, leaking real-time user presence and read timing to remote peers (**SEC-D05-07**). |
| **9** | **Draft & quote handling** | **Vulnerable** (SEC-D05-08) | Drafts are isolated per conversation. However, reply quotes (`sendReply` and `FlashQuotedReplyUi`) do not validate that `replyToId` belongs to the current conversation, rendering unverified `replyToPreview` strings directly from incoming wire frames (**SEC-D05-08**). |
| **10** | **Catch-up / history sync (ADR-100)** | **Not vulnerable** (Access gated) | `RealFlashChatRepository.kt:3747` enforces `isActiveGroupMember(members, frame.groupId, frame.from)` before processing any `GroupWireFrame.SyncRequest`. Non-members and removed peers are rejected at the gate. (Historical message forgery within legacy groups is tracked under SEC-D05-02). |

---

## 4. Detailed Security Findings

### SEC-D05-01 — Inbound 1:1 Direct Chat Text Ingestion Is Ungated by Peer Trust or Pairing, Permitting Unsolicited LAN Spam and Phishing
- **Severity:** High  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (Unauthenticated LAN / hotspot device)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5947-5998`
- **CWE / standard:** CWE-306: Missing Authentication for Critical Function, CWE-284: Improper Access Control; ASVS v4.0.3-4.1.1; MASVS-AUTH-1; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:L (6.5 Medium/High)
- **What is wrong:** Inbound 1:1 direct chat message ingestion (`MessageWireFrame.TextMessage`) in `RealFlashChatRepository.onInboundWireFrame` never enforces `PeerTrustPolicy.mayChat(isPaired, isOrg)` or validates peer trust status. Any unauthenticated device on the local network that connects to `WsTransferServer` (port 45822) can transmit `FLASH_MSG` frames. The repository unconditionally inserts the message into SQLite (`messageDao.insert`), upserts a new or unarchived direct conversation (`upsertDirectConversation`), fires system-level sound/vibration notifications (`onInboundTextMessageWithGroupTitle`), and responds with an automatic `DeliveryReceipt`.
- **Impact:** An attacker on the local Wi-Fi or hotspot can inject spam, display arbitrary phishing or social engineering messages, spoof sender display names (`senderName`), trigger continuous notification harassment, and exhaust device storage.
- **Evidence:**
  In `RealFlashChatRepository.kt:5947-5998`:
  ```kotlin
  is MessageWireFrame.TextMessage -> {
      // SENTINEL: Fail closed on claimed-author vs transport-peer mismatch
      if (transportPeerId != null && frame.senderId != transportPeerId) return
      if (frame.text.length > GroupPolicy.MAX_MESSAGE_TEXT_LENGTH) {
          FlashLog.w("CHAT", "Inbound 1:1 message exceeds length cap ... dropping")
          return
      }
      val threadId = frame.senderId
      val now = timeSource.nowMs()
      val boundedSentAt = minOf(frame.sentAt, now)
      val entity = MessageEntity(...)
      var insertedRowId = -1L
      runInTransaction {
          insertedRowId = messageDao.insert(entity)
          upsertDirectConversation(
              conversationId = threadId,
              title = peerNameResolver(threadId)?.ifBlank { null }
                  ?: frame.senderName?.ifBlank { null }
                  ?: frame.senderId,
              sortOrder = boundedSentAt,
          )
      }
      if (insertedRowId != -1L) {
          runCatching {
              onInboundTextMessageWithGroupTitle(threadId, frame.senderName, frame.text, null)
          }
          maybeAutoPairColleague(threadId)
      }
      transportSink?.send(frame.senderId, MessageWireFrame.DeliveryReceipt(...))
  }
  ```
  `frame.senderId` is matched against `transportPeerId`, but neither `isTrustedPeer(threadId)`, `effectiveIsOrgPeer(threadId)`, nor `PeerTrustPolicy.mayChat` is called. In contrast, `ReactionFrame` at line 6106 explicitly evaluates `PeerTrustPolicy.mayReact(isPaired, isOrg)` and drops un-trusted frames.
- **Why existing guards do not stop it:** `PeerTrustPolicy.mayChat` was implemented in `PeerTrustPolicy.kt:12` and tested in unit tests, but production call sites were only wired into `HubChatBridge` relay paths, leaving LAN direct ingress completely unguarded.
- **Verifier result:** Survived. An unauthenticated WebSocket client on LAN can establish TLS, send `FLASH_MSG localId=... senderId=<client-fp> text="Phishing"`, and the message is committed to `messages` and notified.
- **Recommended fix:**
  In `RealFlashChatRepository.onInboundWireFrame` for `TextMessage`, evaluate `PeerTrustPolicy.mayChat`:
  ```kotlin
  val peerId = transportPeerId ?: frame.senderId
  val isPaired = isTrustedPeer(peerId)
  val isOrg = effectiveIsOrgPeer(peerId)
  if (!PeerTrustPolicy.mayChat(isPaired = isPaired, isOrg = isOrg)) {
      FlashLog.w("CHAT", "Dropped 1:1 direct message from untrusted peer $peerId")
      return
  }
  ```
- **Related:** `PeerTrustPolicy.kt:12`, `adversarial-review.md:54` (Finding 2), ADR-134.

---

### SEC-D05-02 — Legacy Group Catch-Up (SyncPush) Lacks Author Verification, Permitting Arbitrary Historical Message Forgery
- **Severity:** High  
- **Confidence:** CONFIRMED  
- **Attacker:** A2 (Paired peer / active member of legacy v1 group)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupFrameCodec.kt:400` and `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5352-5371, 5413-5425`
- **CWE / standard:** CWE-345: Insufficient Verification of Data Authenticity, CWE-290: Authentication Bypass by Spoofing; ASVS v4.0.3-13.2.1; MASVS-AUTH-2; CVSS:3.1/AV:A/AC:L/PR:L/UI:N/S:U/C:N/I:H/A:N (6.5 Medium/High)
- **What is wrong:** In `GroupFrameCodec.kt:400`, `GroupWireFrame.SyncPush` decodes `author = fields["author"]?.ifBlank { null } ?: from`. When a node synchronizes past group history via catch-up (ADR-100), `RealFlashChatRepository.handleSyncPush` processes each pushed message. For v2 signed groups (`isV2Group`), it validates `signedGroups.verifiedAuthorLabel` against ECDSA member certificates. However, for legacy v1 groups (`!isV2Group(frame.groupId)`), signature verification is bypassed, and `handleSyncPush` never verifies that `message.from == frame.from` or that `message.from` is an active group member. A malicious member answering a catch-up request can inject arbitrary historical messages with `author = <victim_id>`, spoofing the author, sender name, timestamp, and message text.
- **Impact:** Complete message fabrication and impersonation in legacy groups. A malicious participant can frame other group members for defamatory, fraudulent, or criminal messages in persistent chat records.
- **Evidence:**
  In `GroupFrameCodec.kt:396-407`:
  ```kotlin
  "push" -> GroupWireFrame.SyncPush(
      groupId, syncId, from, GroupWireFrame.Message(
          groupId, fields.required("msgId") ?: return null,
          fields["author"]?.ifBlank { null } ?: from, // <--- Wire author accepted!
          fields.required("name") ?: return null, fields.long("sentAt") ?: return null,
          fields["text"] ?: return null, fields["replyTo"]?.ifBlank { null },
          fields["replyPreview"]?.ifBlank { null }, epoch,
          signature = fields["sig"]?.ifBlank { null },
          swarmOffer = decodeSwarmOffer(fields),
      ), epoch,
  )
  ```
  In `RealFlashChatRepository.kt:5356-5371, 5413-5425`:
  ```kotlin
  if (isV2Group(frame.groupId)) {
      val label = signedGroups?.verifiedAuthorLabel(...)
      if (label == null) return
      senderName = label
      groupSig = message.signature
  }
  // In legacy group (!isV2Group), signature check is bypassed!
  // message.from is directly written to the database:
  val inserted = messageDao.insert(
      MessageEntity(
          localId = message.messageId,
          conversationId = frame.groupId,
          senderId = message.from, // <--- Forged author from wire!
          senderName = senderName,
          text = message.text,
          sentAt = boundedSentAt,
  ...
  ```
- **Why existing guards do not stop it:** Comment in `GroupFrameCodec.kt:142` notes that `author` was intended only for v2 signed pushes. However, the decoder at line 400 extracts `author` unconditionally, and `handleSyncPush` fails to assert `message.from == frame.from` for unsigned groups.
- **Verifier result:** Survived. Answering a legacy `SyncRequest` with `FLASH_GROUP op=push author=alice name="Alice" text="Forged text"` causes the recipient to persist the message under `senderId = "alice"`.
- **Recommended fix:**
  In `RealFlashChatRepository.handleSyncPush`, enforce that in legacy (unsigned) groups, the message author MUST strictly equal the relay pusher (`frame.from`), and reject any mismatch:
  ```kotlin
  if (!isV2Group(frame.groupId)) {
      if (message.from != frame.from) {
          FlashLog.w("CHAT", "SECURITY: legacy group SyncPush author mismatch (author=${message.from} pusher=${frame.from}), dropping")
          return
      }
      if (!isActiveGroupMember(members, frame.groupId, message.from)) {
          FlashLog.w("CHAT", "SECURITY: legacy group SyncPush author is not an active member (${message.from}), dropping")
          return
      }
  }
  ```
- **Related:** `GroupFrameCodec.kt:400`, `RealFlashChatRepository.kt:5356`, ADR-044, ADR-100.

---

### SEC-D05-03 — Direct Typing Indicator Ingestion Lacks Peer Trust and Identity Verification, Permitting Spoofed UI Notifications
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (Unauthenticated LAN device), A2 (Malicious peer)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6048-6069`
- **CWE / standard:** CWE-290: Authentication Bypass by Spoofing, CWE-306: Missing Authentication for Critical Function; ASVS v4.0.3-4.1.1; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N (4.3 Medium)
- **What is wrong:** In `RealFlashChatRepository.onInboundWireFrame` for `MessageWireFrame.TypingFrame`, group typing verifies that `transportPeerId == frame.memberId` and that the sender is an active member. However, direct 1:1 typing (`else` branch at line 6056) performs NO check on `transportPeerId == frame.memberId`, NO check on `isTrustedPeer`, and NO check on `PeerTrustPolicy.mayChat`. The repository records `convTyping[frame.memberId] = frame.memberName`, which is published directly to the conversation UI header.
- **Impact:** An attacker on the local network can inject typing events with spoofed names (e.g. `memberName = "Security Alert: Verify Credentials"` or spoofing legitimate contacts). The victim's UI displays the attacker-controlled string in the conversation header.
- **Evidence:**
  In `RealFlashChatRepository.kt:6048-6069`:
  ```kotlin
  is MessageWireFrame.TypingFrame -> {
      val isGroup = conversationDao.get(frame.conversationId)?.isGroup == true
      val typingConversationId = if (isGroup) {
          val members = groupMemberDao ?: return
          if (transportPeerId != null && frame.memberId != transportPeerId) return
          if (!isActiveGroupMember(members, frame.conversationId, frame.memberId)) return
          frame.conversationId
      } else {
          // Direct hosts historically keyed inbound typing under the transport peer rather
          // than the wire conversation id (which names this receiver). Preserve that behavior.
          transportPeerId ?: frame.conversationId
      }
      val convTyping = typingStates.getOrPut(typingConversationId) { SyncMap() }
      if (frame.isTyping) {
          convTyping[frame.memberId] = frame.memberName // <--- frame.memberId and memberName unvalidated!
          scheduleTypingExpiry(typingConversationId, frame.memberId)
      } else {
          convTyping.remove(frame.memberId)
          cancelTypingExpiry(typingConversationId, frame.memberId)
      }
      publishTyping()
  }
  ```
- **Why existing guards do not stop it:** The validation check `transportPeerId != null && frame.memberId != transportPeerId` was placed inside `if (isGroup)` instead of being applied universally across all typing frames.
- **Verifier result:** Survived. An unauthenticated attacker sending `FLASH_TYPING conversationId=victim memberId=bob memberName="Spoofed Bob" isTyping=true` modifies `convTyping` and updates the active UI header.
- **Recommended fix:**
  Move the transport peer identity check before the `if (isGroup)` branch, and gate direct typing on `PeerTrustPolicy.mayChat`:
  ```kotlin
  if (transportPeerId != null && frame.memberId != transportPeerId) return
  if (!isGroup) {
      val isPaired = isTrustedPeer(transportPeerId ?: frame.memberId)
      val isOrg = effectiveIsOrgPeer(transportPeerId ?: frame.memberId)
      if (!PeerTrustPolicy.mayChat(isPaired, isOrg)) return
  }
  ```
- **Related:** `RealFlashChatRepository.kt:6052`, `PeerTrustPolicy.kt:12`.

---

### SEC-D05-04 — Delivery Receipt Forgery Enables Silent Outbox Suppression and Premature Retraction
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A2 (Malicious peer / colleague)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6014-6032`
- **CWE / standard:** CWE-863: Incorrect Authorization, CWE-284: Improper Access Control; ASVS v4.0.3-4.1.1; CVSS:3.1/AV:A/AC:H/PR:L/UI:N/S:U/C:N/I:L/A:L (4.2 Medium)
- **What is wrong:** When processing a direct `MessageWireFrame.DeliveryReceipt`, `RealFlashChatRepository.onInboundWireFrame` verifies that `frame.memberId == transportPeerId`. However, it never queries the database or outbox to verify that `frame.messageId` belongs to a message actually addressed to `frame.memberId`. The repository unconditionally executes `messageDao.updateStatus(frame.messageId, "DELIVERED")` and `outboxDao.delete(frame.messageId)`. If an attacker learns or targets an in-flight `messageId` addressed to another peer (e.g. in multi-party interactions, mesh forwarding, or via eavesdropping), sending a DeliveryReceipt for that ID immediately deletes the sender's pending outbox entry and falsely marks the message as DELIVERED.
- **Impact:** Denial of Service / message suppression. The sender stops retrying the message, believing it was successfully delivered to the intended recipient, while the legitimate recipient never receives it.
- **Evidence:**
  In `RealFlashChatRepository.kt:6014-6032`:
  ```kotlin
  is MessageWireFrame.DeliveryReceipt -> {
      if (transportPeerId != null && frame.memberId != transportPeerId) return
      receiptDao.insert(
          ReceiptEntity(
              messageId = frame.messageId,
              memberId = frame.memberId,
              state = "DELIVERED",
          ),
      )
      messageDao.updateStatus(frame.messageId, "DELIVERED")
      // ERROR-031: this is the outbox row's commit point...
      outboxDao.delete(frame.messageId)
  }
  ```
  Notice that `outboxDao.delete(frame.messageId)` deletes the outbox row without checking whether `message.conversationId == frame.memberId`. In contrast, `ReadReceipt` at line 6041 correctly restricts its mutation to `conversationId = frame.memberId`.
- **Why existing guards do not stop it:** The outbox design assumed `localId` is only known to the intended recipient.
- **Verifier result:** Survived. Peer M sending `FLASH_RCPT messageId=targetId memberId=M` causes the sender to delete `targetId` from `outboxDao` and mark it DELIVERED even if `targetId` was addressed to peer B.
- **Recommended fix:**
  In `onInboundWireFrame` for `DeliveryReceipt`, look up the message and ensure `message.conversationId == frame.memberId` before updating status or deleting from the outbox:
  ```kotlin
  val message = messageDao.getByLocalId(frame.messageId) ?: return
  if (message.conversationId != frame.memberId) {
      FlashLog.w("CHAT", "SECURITY: Delivery receipt member ${frame.memberId} does not match message recipient ${message.conversationId}, dropping")
      return
  }
  ```
- **Related:** `RealFlashChatRepository.kt:6023-6031`, ERROR-031.

---

### SEC-D05-05 — Pre-Emptive DeleteForEveryone Tombstone Injection Suppresses Subsequent Legitimate Message and Call Log Ingestion
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A2 (Peer in 1:1 or group)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6079-6092`, `3679-3692`, and `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:19-20`
- **CWE / standard:** CWE-436: Interpretation Conflict, CWE-670: Always-Incorrect Control Flow Implementation; ASVS v4.0.3-13.2.1; CVSS:3.1/AV:A/AC:L/PR:L/UI:N/S:U/C:N/I:L/A:L (4.6 Medium)
- **What is wrong:** When `DeleteForEveryone` is received for a `messageId` that does not exist in local storage (`messageDao.getByLocalId` is `null`), both direct (line 6080) and group (line 3680) handlers insert a pre-emptive tombstone row: `MessageEntity(localId = frame.messageId, deletedAt = now, ...)`. Because the `messages` table uses `@Insert(onConflict = OnConflictStrategy.IGNORE)` with `@PrimaryKey val localId: String`, any subsequent insert of a legitimate message with that `localId` is silently ignored and dropped by SQLite.
  This is exploitable whenever message IDs are shared or known in advance:
  1. **Call Records:** In WebRTC calling, `callId` is exchanged during signaling (`CallWireFrame.Invite`/`Offer`). At call completion, `RealFlashChatRepository.logCallEvent` inserts the call record with `localId = callId`. An attacker who initiates or receives a call can send `DeleteForEveryone(messageId = callId)` before hanging up. When `logCallEvent` executes, SQLite drops the record, erasing all incoming, outgoing, or missed call logs.
  2. **Predictable/Relayed IDs:** An attacker who knows an in-flight message ID can pre-emptively tombstone it, causing the recipient to drop the actual message upon arrival.
- **Impact:** Anti-forensics and audit trail suppression. Call history and predictable messages can be pre-emptively erased.
- **Evidence:**
  In `RealFlashChatRepository.kt:6075-6092`:
  ```kotlin
  val message = messageDao.getByLocalId(frame.messageId)
  if (message != null) {
      if (message.conversationId != peerId || message.senderId != frame.from) return
      messageDao.markDeleted(frame.messageId, now)
  } else {
      messageDao.insert(
          MessageEntity(
              localId = frame.messageId,
              conversationId = peerId,
              senderId = frame.from,
              senderName = null,
              text = "",
              sentAt = now,
              status = "DELIVERED",
              deletedAt = now,
          ),
      )
  }
  ```
  In `RealFlashChatRepository.kt:3386-3396`:
  ```kotlin
  messageDao.insert(
      MessageEntity(
          localId = callId, // <--- Reused callId!
          conversationId = peerDeviceId,
          senderId = if (outgoing) localDeviceId else peerDeviceId,
          ...
      )
  )
  ```
  In `MessageDao.kt:19-20`:
  ```kotlin
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  public suspend fun insert(message: MessageEntity): Long
  ```
- **Why existing guards do not stop it:** Pre-emptive tombstones were designed to handle out-of-order deletion without considering identifier collision with non-chat entities such as `callId`.
- **Verifier result:** Survived. Injecting `FLASH_XFER_DELETE messageId=call-100` before `logCallEvent(callId="call-100")` results in `messageDao.insert` returning `-1L`, leaving only the empty tombstone and suppressing the call event.
- **Recommended fix:**
  1. In `logCallEvent`, generate a dedicated UUID for `localId` (e.g. `callEventId = UuidIdGenerator.newId()`) rather than reusing the wire `callId`.
  2. In `DeleteForEveryone`, reject pre-emptive tombstones unless the sender proves authorship (e.g., in v2 signed groups, require the author's signature over the deletion request).
- **Related:** `RealFlashChatRepository.kt:3388`, `MessageDao.kt:19`.

---

### SEC-D05-06 — Unbounded Negative Timestamp Skew Enables Chat Transcript Backdating and Stealth Message Injection
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (LAN device), A2 (Paired peer / group member)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5964`, `5307-5308`, and `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:23-25`
- **CWE / standard:** CWE-20: Improper Input Validation; ASVS v4.0.3-5.1.1; CVSS:3.1/AV:A/AC:L/PR:L/UI:N/S:U/C:N/I:L/A:N (3.5 Low)
- **What is wrong:** Inbound message timestamps are clamped on the upper bound to prevent future-dated messages from pinning themselves to the bottom of the chat (`minOf(sentAt, now)` in 1:1, or `now + 5min` in v2 groups). However, neither 1:1 nor group chat enforces ANY lower bound on `sentAt`. An attacker can send a message with `sentAt = 0` (epoch 1970) or backdated by days/years. Because conversations are ordered strictly by `ORDER BY sentAt DESC, localId DESC`, the message is inserted deep into the past. In 1:1, `upsertDirectConversation(..., sortOrder = boundedSentAt)` rewrites the conversation's `sortOrder`, demoting the active conversation to the bottom of the user's conversation list.
- **Impact:** Conversation list manipulation (demoting active conversations), stealth messaging (messages received and notified without appearing in the current chat view), and transcript context tampering.
- **Evidence:**
  In `RealFlashChatRepository.kt:5964`:
  ```kotlin
  val boundedSentAt = minOf(frame.sentAt, now)
  ```
  In `RealFlashChatRepository.kt:5307-5308`:
  ```kotlin
  private fun storedSentAt(sentAt: Long, now: Long, signed: Boolean): Long =
      if (signed && sentAt <= now + SIGNED_SENT_AT_SKEW_TOLERANCE_MS) sentAt else minOf(sentAt, now)
  ```
  Neither function enforces `sentAt >= now - MAX_PAST_SKEW_MS`.
- **Why existing guards do not stop it:** Developers focused exclusively on future-dated timestamps.
- **Verifier result:** Survived. Sending `sentAt = 0` stores the message with `sentAt = 0`, placing it at the earliest position in the transcript and resetting conversation `sortOrder = 0`.
- **Recommended fix:**
  Enforce a maximum backward skew (e.g. 30 days, or clamping to `maxOf(sentAt, now - MAX_PAST_SKEW_MS)`):
  ```kotlin
  val boundedSentAt = frame.sentAt.coerceIn(now - MAX_PAST_SKEW_MS, now)
  ```
- **Related:** `RealFlashChatRepository.kt:5308`, `MessageDao.kt:24`.

---

### SEC-D05-07 — Mandatory and Unconfigurable Read Receipt Dispatch Functions as a Real-Time User Presence and Timing Oracle
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A2 (Paired peer / colleague)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:1866-1893`
- **CWE / standard:** CWE-359: Exposure of Private Personal Information to an Unauthorized Actor; ASVS v4.0.3-8.3.1; CVSS:3.1/AV:A/AC:L/PR:L/UI:R/S:U/C:L/I:N/A:N (3.5 Low)
- **What is wrong:** Whenever a user views an open conversation or closes it after messages were displayed, `RealFlashChatRepository.acknowledgeShown` automatically dispatches `MessageWireFrame.ReadReceipt` (in 1:1) or `GroupWireFrame.Read` (in groups) with millisecond timestamp `readAt = timeSource.nowMs()`. Flash provides no user setting, privacy preference, or configuration option to disable read receipts.
- **Impact:** User privacy violation and presence leakage. Remote peers can monitor when the user looks at their phone, sleep/wake cycles, and response latency without user consent.
- **Evidence:**
  In `RealFlashChatRepository.kt:1866-1893`:
  ```kotlin
  private suspend fun acknowledgeShown(read: OpenConversationReadState) {
      ...
      val newestInboundId = read.shownNewestInboundId
      if (newestInboundId != null && newestInboundId != read.ackedInboundId) {
          read.ackedInboundId = newestInboundId
          if (read.isGroup) {
              sendGroupRead(read.conversationId, newestInboundId)
          } else {
              transportSink?.send(
                  read.conversationId,
                  MessageWireFrame.ReadReceipt(
                      conversationId = read.conversationId,
                      memberId = localDeviceId,
                      upToMessageId = newestInboundId,
                      readAt = timeSource.nowMs(),
                  ),
              )
          }
      }
  }
  ```
  No preference lookup (e.g. `userPreferences.sendReadReceipts`) exists in the dispatch path.
- **Why existing guards do not stop it:** Read receipts were designed as a foundational protocol feature without privacy toggle considerations.
- **Verifier result:** Survived. Opening a chat triggers immediate outgoing `FLASH_READ` frames across the active transport.
- **Recommended fix:**
  Add a `sendReadReceipts` boolean preference in user settings and check it before invoking `transportSink?.send(..., ReadReceipt)` or `sendGroupRead(...)`.
- **Related:** `RealFlashChatRepository.kt:1884`, C6.3.

---

### SEC-D05-08 — Unvalidated Reply Quotes Permit Cross-Chat Fabrication and Deletion Leakage
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A2 (Malicious peer / group member)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:2933-2957` & `1518-1529`
- **CWE / standard:** CWE-345: Insufficient Verification of Data Authenticity; ASVS v4.0.3-13.2.1; CVSS:3.1/AV:A/AC:L/PR:L/UI:R/S:U/C:N/I:L/A:N (3.5 Low)
- **What is wrong:** In `RealFlashChatRepository.sendReply`, the repository never validates that `replyToId` actually exists within `conversationId`. On the receiving side, `FlashQuotedReplyUi` displays `entity.replyToPreview` directly from the wire frame as the quoted text snippet when `byLocalId[quotedId]` is null (or for external messages). An attacker can send a reply frame containing an arbitrary `replyToPreview` string attributing fabricated statements to any user. Furthermore, while `clearReplyPreviews` blanks previews on the local device when a message is deleted, it does not clear quotes that were sent to peers or quoted across conversations.
- **Impact:** Attribution spoofing and quote manipulation in group and direct chats.
- **Evidence:**
  In `RealFlashChatRepository.kt:2933-2957`:
  `sendReply(text, replyToId, replyToPreview)` never validates that `messageDao.getByLocalId(replyToId)?.conversationId == conversationId`.
  In `RealFlashChatRepository.kt:1519-1528`:
  ```kotlin
  val replyTo = entity.replyToId?.let { quotedId ->
      val quoted = byLocalId[quotedId]
      FlashQuotedReplyUi(
          messageId = quotedId,
          senderName = quoted?.senderName
              ?: quoted?.senderId?.let { if (it == localDeviceId) "You" else it }
              ?: "",
          textSnippet = entity.replyToPreview ?: quoted?.text ?: "",
          isMine = quoted?.senderId == localDeviceId,
      )
  }
  ```
- **Why existing guards do not stop it:** `replyToPreview` was captured as a client snapshot at send time to survive quoted message pagination or absence, without cryptographic binding to the original message.
- **Verifier result:** Survived. An attacker sending `FLASH_MSG replyToId=fake-id replyToPreview="I agree to transfer the funds" text="Acknowledged"` renders an authentic-looking quote card in the victim's UI.
- **Recommended fix:**
  1. In `sendReply`, verify `messageDao.getByLocalId(replyToId)?.conversationId == conversationId`.
  2. In UI rendering, visually distinguish unverified quoted snippets where the source message is missing or unverified.
- **Related:** `RealFlashChatRepository.kt:1526`, `MessageDao.kt:214`.

---

## 5. Refuted Candidates and Verifier Results

The following candidate hypotheses were tested and refuted:

### REJ-D05-01 — Direct 1:1 Author Device-ID Impersonation on LAN
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** An attacker A2 connected over LAN can forge `senderId` in a direct `FLASH_MSG` frame to impersonate another peer (e.g. Alice).
- **Why Refuted:** `RealFlashChatRepository.kt:5951` strictly enforces:
  ```kotlin
  if (transportPeerId != null && frame.senderId != transportPeerId) return
  ```
  In direct LAN connections, `FlashInboundRouter.routeInboundText` extracts `peerDeviceId` directly from the authenticated TLS session leaf certificate and passes it as `transportPeerId`. If `frame.senderId != transportPeerId`, the frame is dropped immediately. (Note: Unsolicited messaging by strangers who use their own valid device ID is tracked separately under SEC-D05-01).

### REJ-D05-02 — Unauthorized Group History Extraction via Catch-Up Sync (ADR-100)
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** A non-member or removed member can query `GroupWireFrame.SyncRequest` to extract confidential group history.
- **Why Refuted:** `RealFlashChatRepository.kt:3747` enforces:
  ```kotlin
  if (!isActiveGroupMember(members, frame.groupId, frame.from)) return
  ```
  `isActiveGroupMember` verifies that the local device is still active, that the peer is trusted in the group, and that `groupMemberDao.member(groupId, deviceId)?.isActive == true`. If the requester is not an active member in the recipient's local database, the request is discarded before reaching `handleSyncRequest`.

### REJ-D05-03 — Remote Message Plaintext Editing via Wire Action
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** A peer can manipulate or edit previously delivered messages via an unauthorized edit frame.
- **Why Refuted:** While `MessageDao.markEdited` exists in Room DAO queries, there is no message edit frame type in `MessageWireFrame`, `GroupWireFrame`, or `ChatTextFrameCodec`. No wire protocol or repository method exists in production code to mutate message text after insertion.

### REJ-D05-04 — Arbitrary Cross-User Message Deletion
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** An attacker can send `DeleteForEveryone` to delete messages authored by other users.
- **Why Refuted:** In 1:1 chat (`RealFlashChatRepository.kt:6077`), the repository verifies: `if (message.conversationId != peerId || message.senderId != frame.from) return`. In group chat (`RealFlashChatRepository.kt:3677`), it verifies: `if (message.conversationId != frame.groupId || message.senderId != frame.from) return`. A peer can only delete messages where `message.senderId == frame.from`.

### REJ-D05-05 — Database Overwrite via Duplicate Message-ID Injection
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** An attacker can overwrite existing message text by injecting a duplicate message with identical `localId`.
- **Why Refuted:** `MessageDao.kt:19-20` annotates `insert(message)` with `@Insert(onConflict = OnConflictStrategy.IGNORE)`. When a message with an existing primary key `localId` arrives, SQLite ignores the insert and returns `-1L`. The original message content, timestamp, status, and attachments remain completely intact.

---

## 6. Architecture Recommendations

1. **Mandatory Ingress Capability Gating:** Wire `PeerTrustPolicy.mayChat(isPaired, isOrg)` into `RealFlashChatRepository.onInboundWireFrame` for `TextMessage`, `DeliveryReceipt`, `ReadReceipt`, and `TypingFrame`. Never allow unauthenticated LAN devices to commit database rows or trigger system notifications.
2. **Strict Roster and Author Binding in Legacy Catch-Up:** In `RealFlashChatRepository.handleSyncPush`, reject any legacy sync push where `message.from != frame.from` or where `message.from` is not an active member of `frame.groupId`.
3. **Outbox Receipt Scoping:** When processing a `DeliveryReceipt`, query `messageDao.getByLocalId(frame.messageId)` to verify that `message.conversationId == frame.memberId` before updating status or deleting from `outboxDao`.
4. **Isolate Call History Identifiers:** Generate dedicated UUIDs for call log entries in `RealFlashChatRepository.logCallEvent` rather than reusing wire `callId`, preventing pre-emptive tombstone injection attacks.
5. **Enforce Lower-Bound Timestamp Clamping:** Clamp incoming message timestamps using `frame.sentAt.coerceIn(now - MAX_PAST_SKEW_MS, now)` to prevent transcript backdating and conversation list reordering.
6. **Implement User Privacy Controls for Read Receipts:** Provide a user toggle for read receipts and evaluate it before dispatching `MessageWireFrame.ReadReceipt` and `GroupWireFrame.Read`.
