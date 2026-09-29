# Architectural Audit & Implementation Plan: Chat Synchronization, Delivery Receipts & Desktop Identity

**Date:** 2026-09-28  
**Repository:** [Flash](file:///C:/Users/KaliOxygen/Downloads/Flash) (Android LAN + Wi-Fi Direct Transfer, Messaging & Calling App)  
**Branch:** `dev` @ [`ce7e750`](file:///C:/Users/KaliOxygen/Downloads/Flash)  
**Status:** Audit & Architecture Plan — read-only investigation across `:core:persistence`, `:core:messaging`, `:core:network`, `:core:security`, `:ui:chat`, `:app`, and `:desktop`.

---

## 1. Executive Summary

This document covers an in-depth audit and architectural plan for 5 key chat and identity areas in Flash:
1. **Read & Unread Part of the Chat:** Unread badges, read cursors, outbound and inbound read receipts in direct 1:1 vs group conversations.
2. **Group Catch-Up Sync for Newly Added Members:** Why history doesn't sync for newcomers, how to handle media without ghost bubbles, and top progress bar design.
3. **Group Message Delivery Ticks & Multi-Peer Acknowledgment Lifecycle:** Why only one checkmark appears when 1 member is online, and why it fails to transition to double checkmarks when offline members come online.
4. **Message Delivery Expansion ("Message Info" Screen):** Detailed per-recipient delivery & read breakdown (who received, who read, who is pending) with SQL schema queries and UI sheets.
5. **Desktop Display Name Change Failure:** The 7-point failure chain causing desktop name edits to fail or remain stale across the application.

---

## 2. Area 1: Read & Unread Part of the Chat (Direct vs Group)

### 2.1 Direct (1:1) Chat: Verified & Operational
Direct 1:1 chat has an end-to-end working read receipt mechanism:
1. **Unread Counts on Chat List:**
   In [`MessageDao.kt:143-158`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt#L143-L158):
   ```sql
   SELECT m.conversationId AS conversationId, COUNT(*) AS count
   FROM messages m
   LEFT JOIN conversations c ON c.id = m.conversationId
   WHERE m.senderId != :selfId AND m.deletedAt IS NULL
     AND (c.lastReadCursor IS NULL OR m.sentAt > (
         SELECT sentAt FROM messages WHERE localId = c.lastReadCursor
     ))
   GROUP BY m.conversationId
   ```
   Counts inbound messages whose `sentAt` is strictly greater than the message pointed to by [`ConversationEntity.lastReadCursor`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/ConversationEntity.kt#L17).
2. **Local Mark-as-Read:**
   When a user opens a conversation, [`RealFlashChatRepository.markConversationRead`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L694-L696) updates `conversationDao.updateLastReadCursor(conversationId, newestMessageId)`. This immediately clears the unread badge on this device.
3. **Outbound Receipt Dispatch:**
   In [`RealFlashChatRepository.kt:702-713`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L702-L713):
   ```kotlin
   if (newestInboundId != null && newestInboundId != lastAckedInboundId) {
       lastAckedInboundId = newestInboundId
       transportSink?.send(
           conversationId,
           MessageWireFrame.ReadReceipt(
               conversationId = conversationId,
               memberId = localDeviceId,
               upToMessageId = newestInboundId,
               readAt = timeSource.nowMs(),
           ),
       )
   }
   ```
   In 1:1 direct chat, `conversationId` is the peer's `deviceId`. `transportSink` routes directly to the peer's active WebSocket session.
4. **Inbound Receipt Ingestion:**
   In [`RealFlashChatRepository.kt:2071-2083`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L2071-L2083):
   ```kotlin
   is MessageWireFrame.ReadReceipt -> {
       if (transportPeerId != null && frame.memberId != transportPeerId) return
       messageDao.markReadUpTo(
           conversationId = frame.memberId,
           selfId = localDeviceId,
           upToMessageId = frame.upToMessageId,
       )
   }
   ```
   [`MessageDao.kt:128-133`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt#L128-L133) executes:
   ```sql
   UPDATE messages SET status = 'READ' WHERE conversationId = :conversationId
   AND senderId = :selfId AND status != 'READ' AND sentAt <=
   (SELECT sentAt FROM messages WHERE localId = :upToMessageId)
   ```
   This transitions all messages sent to that peer up to `upToMessageId` to status `'READ'`, illuminating the double checkmarks in primary accent color via [`FlashDeliveryStatusIcon.kt:61`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashDeliveryStatusIcon.kt#L61).

---

### 2.2 Group Chat: Broken Architectural Chain
In group chats, read receipts fail completely due to 5 interconnected flaws:

```
[User opens group conversation]
             │
             ▼
markConversationRead()
             │
             ├─► (1) transportSink?.send(groupId, ReadReceipt) ──► DROPPED! (transportSink searches activeSessions[groupId] -> null)
             │
             ├─► (2) Wrong WireFrame dispatched (MessageWireFrame.ReadReceipt instead of GroupWireFrame.Read)
             │
             ├─► (3) Missing multi-peer fan-out via groupTransportSink
             │
             └─► (4) Inbound GroupWireFrame.Read handler (L1524-1528) is an EMPTY NO-OP!
                     └─► ReadCursorDao is NOT EVEN INJECTED into RealFlashChatRepository
```

1. **Packet Dropped Due to Routing to Group ID:**
   In group chat, `conversationId` is `groupId` (e.g. `group-uuid`). [`markConversationRead`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L704) calls `transportSink?.send(conversationId, ...)`. `transportSink` searches `activeSessions[conversationId]`, finds no peer matching `group-uuid`, and silently drops the packet.
2. **Wrong Wire Frame Dispatched:**
   It transmits 1:1 `MessageWireFrame.ReadReceipt` instead of multi-peer [`GroupWireFrame.Read`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupWireFrame.kt#L64).
3. **No Multi-Peer Fan-Out:**
   Read receipts in a group are not unicast to one peer; they must be fanned out across all active group members via `groupTransportSink`.
4. **Inbound Handler is an Unimplemented NO-OP:**
   In [`RealFlashChatRepository.kt:1524-1528`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1524-L1528):
   ```kotlin
   is GroupWireFrame.Read -> {
       if (!isActiveTrustedMember(members, frame.groupId, frame.from)) return
       // The existing cursor DAO is already per (conversation, member); UI read aggregation
       // remains a Phase 1 UI follow-up while the durable monotonic record lands now.
   }
   ```
   **Zero code is executed.** No read cursors are recorded.
5. **[`ReadCursorDao`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/ReadCursorDao.kt) Never Injected into Repository:**
   [`RealFlashChatRepository.kt:115-135`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L115-L135) does not even accept `readCursorDao` in its constructor. Neither [`DiscoveryEngineHolder.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L884) nor [`DesktopEngine.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L643) passes it.
6. **No Quorum Status Aggregation:**
   A group message should flip to `status = 'READ'` (double blue check) only when **all active remote members** in `group_members` have their read cursor at or beyond `message.sentAt`. Currently, no evaluation exists.

---

### 2.3 Implementation Plan for Group Read Tracking
1. **Inject `ReadCursorDao` into `RealFlashChatRepository`:**
   Add `private val readCursorDao: ReadCursorDao? = null` to the constructor. Wire `db.readCursorDao()` in both `DiscoveryEngineHolder.kt` and `DesktopEngine.kt`.
2. **Branch Group Read Dispatch in `markConversationRead`:**
   In [`RealFlashChatRepository.kt:694`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L694):
   - Check if `conversationDao.get(conversationId)?.isGroup == true`.
   - If true: fan out `GroupWireFrame.Read(groupId = conversationId, from = localDeviceId, upToMessageId = newestInboundId, readAt = now)` to all `groupMemberDao.activeMembers(conversationId) - localDeviceId` via `groupTransportSink`.
3. **Implement Inbound `GroupWireFrame.Read` Handler:**
   ```kotlin
   is GroupWireFrame.Read -> {
       if (!isActiveTrustedMember(members, frame.groupId, frame.from)) return
       val targetMsg = messageDao.getByLocalId(frame.upToMessageId) ?: return
       readCursorDao?.advanceFurthest(
           conversationId = frame.groupId,
           memberId = frame.from,
           upToMessageId = frame.upToMessageId,
           upToSentAt = targetMsg.sentAt,
       )
       evaluateGroupReadQuorum(frame.groupId, targetMsg.sentAt)
   }
   ```
4. **Implement Read Quorum Evaluation:**
   When an author receives read updates, query `readCursorDao.observeCursors(groupId)` and `groupMemberDao.activeMembers(groupId)`. For each outbound message with `status == 'DELIVERED'`, if every other active member's `upToSentAt >= message.sentAt`, call `messageDao.updateStatusIfUnacknowledged(message.localId, "READ")`.

---

## 3. Area 2: Group Catch-Up Sync for Newly Added Members

### 3.1 Root Causes Why Old Messages Do Not Sync
When User C is added to a group with User A and User B:
1. **Mesh P2P Disconnect:** User C is added by User A. C has an active socket session with A, but **no direct connection to B**. When C calls [`requestGroupCatchUp`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1684-L1692), it broadcasts `SyncRequest` to all active members (A and B). The send to B immediately fails.
2. **`SyncClaim` Election Deadlock:** User A receives C's `SyncRequest` and broadcasts `SyncClaim` to all members. If B has a lower latency or wins the deterministic FNV-1a hash rank in [`GroupSyncPolicy.kt:30-40`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSyncPolicy.kt#L30-L40), User A stands down (`mine.isEmpty() -> return`). But B has no socket connection to C! Result: **Neither peer pushes old messages to C**.
3. **No Immediate Rank-0 Push:** `handleSyncRequest` only calls `armBackupPush`, waiting a full 2000 ms (`GroupPolicy.BACKUP_DELAY_MS`). There is no immediate push after `CLAIM_WINDOW_MS` (300 ms).
4. **Desktop Fails to Trigger Sync on Peer Connect:** In [`DiscoveryEngineHolder.kt:1352-1358`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1352-L1358), Android triggers:
   - `chatImpl.notifyPeerSessionUp(session.peerDeviceId.value)`
   - `chatImpl.sendGroupSyncRequests(session.peerDeviceId.value)`
   - `chatImpl.reconcileGroupMembership(session.peerDeviceId.value)`
   On Desktop ([`DesktopEngine.kt:937-966`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L937-L966)), **none of these three calls exist**. If either peer is on Desktop, sync requests are never triggered upon connecting.
5. **24-Hour TTL Hard Limit:** [`GroupPolicy.kt:18`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupPolicy.kt#L18) enforces `SYNC_TTL_MS = 24 * 60 * 60 * 1000L`. Any message older than 24 hours is permanently excluded from synchronization.

---

### 3.2 Excluding Media (Photos, Videos, Files) Without Ghost Bubbles
* **The Problem:** In [`RealFlashChatRepository.kt:1917-1926`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1917-L1926):
  ```kotlin
  private fun MessageEntity.toSyncMessage() = GroupWireFrame.Message(
      groupId = conversationId,
      messageId = localId,
      from = senderId,
      senderName = senderName ?: senderId,
      sentAt = sentAt,
      text = text,
      replyToId = replyToId,
      replyToPreview = replyToPreview,
  )
  ```
  When a message with an attachment (e.g. photo, video, PDF) is pushed, its `text` is `""`. The receiver inserts a `MessageEntity` with `text = ""` and `attachmentPath = null`, rendering an empty ghost bubble.
* **The Plan:**
  1. Add optional lightweight media metadata to [`GroupWireFrame.Message`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupWireFrame.kt#L48):
     ```kotlin
     val mediaSummary: String? = null, // e.g. "📷 Photo", "🎥 Video", "📄 Document.pdf"
     val attachmentType: String? = null,
     ```
  2. In `handleSyncPush`, when `frame.message.attachmentType != null`, insert `MessageEntity` with `text = frame.message.mediaSummary ?: "[Media]"`, `attachmentType = "PLACEHOLDER"`, `attachmentPath = null`.
  3. Do **not** emit `GroupWireFrame.GroupMedia` or initiate byte-stream chunk transfers. The newcomer receives full context and timeline history without downloading historical files.

---

### 3.3 Top Progress Bar for Catch-Up Sync
* **UI Location:** Directly beneath [`FlashChatHeader`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatHeader.kt) in [`FlashConversationScreen.kt:537-570`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt#L537-L570) (above the message list and adjacent to `FlashConnectionBanner`).
* **State Flow Model:** Expose on [`FlashChatRepository`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/FlashChatRepository.kt):
  ```kotlin
  data class GroupSyncProgressUi(
      val groupId: String,
      val isSyncing: Boolean,
      val receivedCount: Int,
      val totalExpected: Int?, // null if indeterminate
  )
  fun observeGroupSyncProgress(groupId: String): Flow<GroupSyncProgressUi?>
  ```
* **UI Composable:**
  ```kotlin
  AnimatedVisibility(
      visible = syncProgress?.isSyncing == true,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
  ) {
      Column(
          modifier = Modifier
              .fillMaxWidth()
              .background(FlashTheme.colors.backgroundElevated)
              .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space6),
      ) {
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
          ) {
              FlashText(
                  text = "Syncing message history…",
                  style = FlashTheme.typography.metadataDefault,
                  color = FlashTheme.colors.textSecondary,
              )
              if (syncProgress.totalExpected != null) {
                  FlashText(
                      text = "${syncProgress.receivedCount}/${syncProgress.totalExpected}",
                      style = FlashTheme.typography.metadataEmphasis,
                      color = FlashTheme.colors.textPrimary,
                  )
              }
          }
          Spacer(modifier = Modifier.height(FlashSpacing.space4))
          LinearProgressIndicator(
              progress = { 
                  syncProgress.totalExpected?.let { syncProgress.receivedCount.toFloat() / it } ?: 0f 
              },
              modifier = Modifier.fillMaxWidth().height(2.dp),
              color = FlashTheme.colors.accentPrimary,
              trackColor = FlashTheme.colors.borderSubtle,
          )
      }
  }
  ```

---

## 4. Area 3: Group Message Delivery Ticks & Multi-Peer Acknowledgment Lifecycle

### 4.1 Why Only One Tick Appears When Only 1 Peer is Online
1. Author sends M1 to a group with Alice (online) and Bob (offline).
2. Author inserts M1 into `messages` (`status = 'PENDING'`) and inserts two rows into [`group_deliveries`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupDeliveryEntity.kt): `(M1, Alice, 'PENDING')` and `(M1, Bob, 'PENDING')`.
3. Alice receives M1 and returns [`GroupWireFrame.Receipt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupWireFrame.kt#L59).
4. Author marks Alice's delivery row as `'DELIVERED'`. It then calls:
   [`deliveries.pendingForMessage(M1)`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupDeliveryDao.kt#L15-L19):
   `SELECT * FROM group_deliveries WHERE messageId = :messageId AND state != 'DELIVERED'`
5. Bob's delivery row is still `'PENDING'`. The pending list is **not empty**.
6. Therefore, [`messageDao.updateStatusIfUnacknowledged(M1, "DELIVERED")`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1519-L1521) is **not called**.
7. In [`FlashMessageBubble.kt:454-474`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt#L454-L474), the bubble renders `FlashDeliveryStatusIcon(status = FlashMessageStatus.Sent)` (single tick), with the count badge displaying `"1/2"`.

---

### 4.2 Why It Does NOT Tick When Bob Later Comes Online
When Bob comes online and ingests M1 via catch-up sync:
1. **Protocol Flaw: `handleSyncPush` Sends `SyncAck` to Holder, NOT Receipt to Author:**
   In [`RealFlashChatRepository.kt:1952-1962`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1952-L1962):
   - When Bob receives missing message M1 via catch-up sync, Bob sends `GroupWireFrame.SyncAck` **only to the peer who pushed it (`frame.from`)**.
   - Bob **never sends `GroupWireFrame.Receipt` to `message.from` (the original author)**!
   - As a result, the author never learns that Bob received M1.
2. **`handleSyncAck` Never Updates `group_deliveries`:**
   Even if the author was the peer who pushed the message directly to Bob, in [`RealFlashChatRepository.kt:1966-1974`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L1966-L1974), `handleSyncAck` **only updates in-memory sets**; it never calls `groupDeliveryDao.markDelivered(msgId, Bob, now)`. Bob's record in `group_deliveries` remains stuck at `'PENDING'`.
3. **Desktop Never Drains Outbox on Reconnect:**
   If the author is on Desktop, `DesktopEngine.kt` never calls `chatImpl.notifyPeerSessionUp(Bob)`. The outbox is never woken to drain pending deliveries to Bob.
4. **Outbox Expiration:**
   If Bob was offline for longer than 30 minutes (`GroupPolicy.OUTBOX_GIVE_UP_AFTER_MS`), the author's outbox expired and deleted the delivery row.
5. **Membership Gate Rejection:**
   If Bob was offline when the new member was added to the group, Bob's `group_members` table does not contain the new member until `reconcileGroupMembership` runs. On Desktop, `reconcileGroupMembership` is never called, causing Bob to reject incoming frames from the new member with `if (!isActiveTrustedMember(...)) return`.

---

### 4.3 Implementation Plan for Group Delivery Ticks
1. **In `handleSyncPush`:**
   When Bob receives a message via sync:
   ```kotlin
   if (message.from != localDeviceId) {
       groupTransportSink?.send(
           message.from,
           GroupWireFrame.Receipt(
               groupId = frame.groupId,
               messageId = message.messageId,
               from = localDeviceId,
               deliveredAt = timeSource.nowMs(),
           ),
       )
   }
   ```
2. **In `handleSyncAck`:**
   When the author receives `SyncAck` for a message it authored:
   ```kotlin
   for (msgId in frame.messageIds) {
       groupDeliveryDao?.markDelivered(msgId, frame.from, timeSource.nowMs())
       if (groupDeliveryDao?.pendingForMessage(msgId)?.isEmpty() == true) {
           messageDao.updateStatusIfUnacknowledged(msgId, "DELIVERED")
           outboxDao.delete(msgId)
       }
   }
   ```
3. **In `DesktopEngine.kt`:**
   Call `chatImpl.notifyPeerSessionUp(session.peerDeviceId.value)` and `chatImpl.reconcileGroupMembership(session.peerDeviceId.value)` on every session-up edge.
4. **Result:** When Bob comes online and ingests M1, the author instantly receives a `Receipt`, updates `group_deliveries`, transitions M1 to `"DELIVERED"`, and the UI updates from single checkmark (`"1/2"`) to double checkmark (`"2/2"`).

---

## 5. Area 4: Message Delivery Expansion ("Message Info" Screen)

### 5.1 UX Flow & Entry Points
Allow senders to inspect granular per-recipient status on any message they sent:
- Who has read the message (with timestamps)
- Who has received/delivered the message (with timestamps)
- Who has not received it / is still pending

**UI Entry Points:**
1. **Tap Delivery Badge:** In [`FlashMessageBubble.kt:454-468`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt#L454-L468), make the `"1/2"` badge clickable with haptic feedback.
2. **Context Menu:** In [`FlashMessageContextMenu.kt:404-443`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageContextMenu.kt#L404-L443), add `FlashContextMenuItem(icon = FlashIcons.Sliders, label = "Message Info")` whenever `message.isMine == true`.

---

### 5.2 Persistence Layer Architecture
Using verified entities [`GroupDeliveryEntity`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupDeliveryEntity.kt), [`GroupMemberEntity`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupMemberEntity.kt), [`ReadCursorEntity`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/ReadCursorEntity.kt), and [`MessageEntity`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/MessageEntity.kt):

Add to [`GroupDeliveryDao.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupDeliveryDao.kt):
```kotlin
data class MessageMemberDeliveryDetail(
    val memberId: String,
    val displayName: String?,
    val deliveryState: String, // PENDING, SENT, DELIVERED
    val deliveredAt: Long?,
    val upToSentAt: Long?,
    val isRead: Boolean,
)

@Query("""
    SELECT 
        gd.memberId AS memberId,
        gm.displayName AS displayName,
        gd.state AS deliveryState,
        gd.deliveredAt AS deliveredAt,
        rc.upToSentAt AS upToSentAt,
        CASE WHEN rc.upToSentAt IS NOT NULL AND rc.upToSentAt >= m.sentAt THEN 1 ELSE 0 END AS isRead
    FROM group_deliveries gd
    INNER JOIN messages m ON m.localId = gd.messageId
    LEFT JOIN group_members gm ON gm.groupId = m.conversationId AND gm.deviceId = gd.memberId
    LEFT JOIN read_cursors rc ON rc.conversationId = m.conversationId AND rc.memberId = gd.memberId
    WHERE gd.messageId = :messageId
    ORDER BY isRead DESC, gd.deliveredAt DESC, gm.displayName ASC
""")
fun observeMessageDeliveryDetails(messageId: String): Flow<List<MessageMemberDeliveryDetail>>
```

---

### 5.3 UI Presentation (`FlashMessageInfoSheet.kt`)
Implement using `FlashSheetHost` (modal bottom sheet on Android, modal centered dialog on Desktop):
- **Message Preview Header:** Original text snippet and sent timestamp.
- **Section 1: Read By (N):**
  - Icon: `FlashIcons.CheckDouble` in `FlashTheme.colors.accentPrimary`.
  - Recipient avatar, name, and read status indicator.
- **Section 2: Delivered To (M):**
  - Icon: `FlashIcons.CheckDouble` in `FlashTheme.colors.textSecondary`.
  - Recipient avatar, name, and delivered timestamp (`deliveredAt`).
- **Section 3: Pending / Not Delivered (P):**
  - Icon: `FlashIcons.Clock` or `FlashIcons.Check` in `FlashTheme.colors.textTertiary`.
  - Recipient avatar, name, and subtitle "Waiting for device to connect".

---

## 6. Area 5: Changing Display Name Feature on Desktop

### 6.1 Root Causes of the Desktop Rename Failure
Desktop display name changes fail or remain stale due to a 7-point failure chain:

```
Desktop Settings Rename Dialog
           │
           ▼
(1) showRenameDialog = false ──────────► Immediate UI recomposition BEFORE async write finishes
           │
           ▼
(2) DesktopEngine.renameLocalDevice()
       │
       ├─► (3) No StateFlow exists in DesktopEngine (localFriendlyName is a plain String property)
       ├─► (4) remember(..., desktopSettings) in DesktopShell never invalidates (desktopSettings has no displayName)
       ├─► (5) JvmWsFlashNetwork.localFriendlyName is NEVER updated (WebSocket handshakes keep old name)
       ├─► (6) FlashPairingCoordinator.localName is immutable (pairing frames keep old name)
       └─► (7) RealFlashChatRepository.localDisplayName is immutable & GroupMemberDao is never updated
```

1. **Non-Reactive Getter Inside `remember`:**
   In [`DesktopShell.kt:860-882`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L860-L882):
   ```kotlin
   val settings = remember(engine, ready, receivedBytes, themeMode, trustedPeersByCoordinator, desktopSettings) {
       FlashSettingsModel(
           displayName = engine.localFriendlyName,
           ...
       )
   }
   ```
   `desktopSettings` does not contain `displayName` (it is stored separately in `DesktopIdentityStores`). None of the `remember` keys change when `engine.renameLocalDevice` runs. `settings.displayName` never recomputes and displays the old name indefinitely.
2. **Asynchronous Race on Dialog Dismissal:**
   In [`DesktopShell.kt:1589-1592`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L1589-L1592):
   ```kotlin
   onConfirm = { name ->
       showRenameDialog = false
       scope.launch { engine.renameLocalDevice(name) }
   }
   ```
   Setting `showRenameDialog = false` immediately recomposes the UI **before** the background coroutine finishes updating the name on disk.
3. **No `StateFlow` in [`DesktopEngine.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt):**
   `DesktopEngine` has no reactive `StateFlow<String>` for friendly name. Compose has nothing to observe.
4. **Sidebar Profile Avatar Stale:**
   In [`DesktopShell.kt:1762`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L1762), `DesktopSideBar` receives `localDisplayName = engine.localFriendlyName`. Because it is not backed by Compose State or StateFlow, the sidebar avatar initials and tooltip never update.
5. **[`JvmWsFlashNetwork.localFriendlyName`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt#L88-L89) is Never Updated:**
   `JvmWsFlashNetwork` defines `@Volatile public var localFriendlyName: String`, but [`DesktopEngine.renameLocalDevice`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L362-L382) never updates it. WebSocket HELLO handshakes on subsequent connections continue sending the old name.
6. **[`FlashPairingCoordinator.localName`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/pairing/FlashPairingCoordinator.kt#L75) is Immutable:**
   `localName` is captured at startup. Renaming does not update it, so pairing handshakes (`FLASH_PAIR`) advertise the old identity.
7. **[`RealFlashChatRepository.localDisplayName`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L117) is Immutable & `GroupMemberDao` is Unmodified:**
   `localDisplayName` is an immutable `val` used in 14 places to stamp `senderName`. Renaming on Desktop never updates the repository, and never updates `group_members` in Room (`groupMemberDao.updateMemberDisplayName`). Existing group chats retain the old name forever.

---

### 6.2 Implementation Plan for Desktop Rename
1. **Add Reactive State to [`DesktopEngine.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt):**
   ```kotlin
   private val _localFriendlyName = MutableStateFlow(identity.friendlyName)
   public val localFriendlyNameState: StateFlow<String> = _localFriendlyName.asStateFlow()
   public val localFriendlyName: String get() = _localFriendlyName.value
   ```
2. **Propagate New Name to All Subsystems in `renameLocalDevice`:**
   ```kotlin
   public suspend fun renameLocalDevice(name: String): Boolean {
       val trimmed = name.trim()
       if (trimmed.isEmpty()) return false
       val renamed = runCatching { identityStore.updateFriendlyName(trimmed) }.getOrNull()
       if (renamed !is FlashResult.Success) return false
       
       _localFriendlyName.value = trimmed
       (network as? JvmWsFlashNetwork)?.localFriendlyName = trimmed
       pairing.updateLocalName(trimmed)
       chatImpl?.updateLocalDisplayName(trimmed)
       chatDb?.groupMemberDao()?.updateMemberDisplayName(localDeviceId, trimmed)
       
       runCatching {
           discoveryImpl?.let { discovery ->
               discovery.updateIdentity(buildAdvertisedIdentity())
               if (advertisedPort > 0) discovery.startAdvertising(advertisedPort)
           }
       }
       return true
   }
   ```
3. **Add Mutator to [`FlashPairingCoordinator.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/pairing/FlashPairingCoordinator.kt):**
   Change `private var localName: String = localName` and add `fun updateLocalName(newName: String)`.
4. **Add Mutator to [`RealFlashChatRepository.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt):**
   Change `private var localDisplayName: String = localDisplayName` and add `fun updateLocalDisplayName(newName: String)`.
5. **Collect Reactive State in [`DesktopShell.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt):**
   ```kotlin
   val localFriendlyName by engine.localFriendlyNameState.collectAsState()
   
   val settings = remember(engine, ready, receivedBytes, themeMode, trustedPeersByCoordinator, desktopSettings, localFriendlyName) {
       FlashSettingsModel(
           displayName = localFriendlyName,
           ...
       )
   }
   
   DesktopSideBar(
       ...
       localDisplayName = localFriendlyName,
   )
   ```
   This guarantees that renaming instantly updates Settings, the Sidebar avatar, mDNS advertisement, WebSocket handshakes, pairing frames, and group membership across the entire application.

---

## 7. Actionable Implementation Tasks

| ID | Module | Task Description | Priority |
|---|---|---|---|
| **TASK-GRP-READ-1** | `:core:messaging` | Inject `ReadCursorDao` into `RealFlashChatRepository` and wire in `DiscoveryEngineHolder` & `DesktopEngine` | **CRITICAL** |
| **TASK-GRP-READ-2** | `:core:messaging` | Branch `markConversationRead` for groups to fan out `GroupWireFrame.Read` via `groupTransportSink` | **HIGH** |
| **TASK-GRP-READ-3** | `:core:messaging` | Implement `GroupWireFrame.Read` inbound handler & evaluate group read quorum for double checkmark | **HIGH** |
| **TASK-GRP-SYNC-1** | `:core:messaging` | Add media summary placeholder in `toSyncMessage()` and handle in `handleSyncPush` without binary file transfer | **HIGH** |
| **TASK-GRP-SYNC-2** | `:core:messaging` / `:ui:chat` | Implement `GroupSyncProgressUi` flow and top `LinearProgressIndicator` in `FlashConversationScreen` | **MEDIUM** |
| **TASK-GRP-ACK-1** | `:core:messaging` | In `handleSyncPush`, send `GroupWireFrame.Receipt` back to original message author (`message.from`) | **CRITICAL** |
| **TASK-GRP-ACK-2** | `:core:messaging` | In `handleSyncAck`, mark `group_deliveries` delivered and evaluate message status transition to `"DELIVERED"` | **HIGH** |
| **TASK-MSG-INFO-1** | `:core:persistence` | Add `observeMessageDeliveryDetails` query in `GroupDeliveryDao` joining `group_members` and `read_cursors` | **HIGH** |
| **TASK-MSG-INFO-2** | `:ui:chat` | Implement `FlashMessageInfoSheet` bottom sheet / dialog and link from bubble delivery badge & context menu | **MEDIUM** |
| **TASK-DSK-NAME-1** | `:desktop` | Add `localFriendlyNameState: StateFlow<String>` to `DesktopEngine` and update in `renameLocalDevice` | **CRITICAL** |
| **TASK-DSK-NAME-2** | `:core:network` / `:core:security` | Propagate new name to `JvmWsFlashNetwork`, `FlashPairingCoordinator`, and `RealFlashChatRepository` | **HIGH** |
| **TASK-DSK-NAME-3** | `:desktop` | Collect `localFriendlyNameState` in `DesktopShell` for `settings` and `DesktopSideBar` | **HIGH** |

---
*Document generated directly from verified codebase facts. No source code was modified.*
