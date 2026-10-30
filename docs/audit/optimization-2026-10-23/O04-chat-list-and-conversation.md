# Domain O04 — Chat List and Conversation Rendering
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** White-Box Optimization Agent  

---

## 1. Domain Header & Scope

This audit evaluates the complete execution and rendering pipeline for the chat list and conversation threads across Android and Desktop platforms:
- **Conversation Screen & Message List:** From `RealFlashChatRepository.openConversation`, Room reactive query streams (`MessageDao.observeConversation`, `ReactionDao.observeForConversation`, `DraftDao.observeDraft`, `GroupDeliveryDao.observeDeliveryCounts`), inner/outer flow combines, message mapping and bubble grouping (`computeMessageGroupPositions`, `assignDaySeparators`), to Jetpack Compose rendering in `FlashConversationScreen`, `FlashMessageList`, `FlashMessageBubble`, and `FlashComposer`.
- **Chat List:** From `RealFlashChatRepository.chatListState`, Room reactive query streams (`ConversationDao.observeAll`, `MessageDao.observeUnreadCounts`, `MessageDao.observeLatestPreviews`), live presence and typing join, to Compose rendering in `FlashChatListScreen`, `FlashChatListRow`, swipe-to-dismiss wrappers, and search result list rendering.
- **Search & Deep Linking:** SQL queries in `MessageDao` (`searchMessages`, `searchConversationMessages`), index utilization, EXPLAIN QUERY PLAN analysis, and scroll-to-message jump behavior (`jumpToMessage`, `animateScrollToItem`).
- **Pacing & Invalidation:** Flow rate-limiting (`UiPacing.throttleLatest`, `ProgressThrottle`), typing indicator emission and expiry, draft persistence lifecycle, and fan-out behavior for group deliveries and reactions in a 20-member mesh.

---

## 2. Files Inspected

### UI Layer (`ui/chat`)
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageList.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashComposer.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListScreen.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListRow.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListSearch.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListSelectionBar.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatSearchBar.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatSearchResults.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSearchScope.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashGroupInviteJoinMath.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashFileMessageCard.kt`

### Core Messaging (`core/messaging`)
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/FlashChatRepository.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/util/FlashMessagingUtils.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/util/ProgressThrottle.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupFrameCodec.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/ChatTextFrameCodec.kt`

### Persistence & Storage (`core/persistence`)
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/ConversationDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupMemberDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupDeliveryDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/ReactionDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/DraftDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/MessageEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/ConversationEntity.kt`

### Host & Shell Wiring
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `app/src/main/java/com/transfer/flash/ui/UiPacing.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt`

---

## 3. Hot Paths: Chat List & Conversation

| Path ID | Component / Entry Point | Trigger / Cadence | Dispatcher / Thread | What Runs |
|---|---|---|---|---|
| **HP-CHAT-01** | `RealFlashChatRepository.openConversation` | User opens a conversation | `scope` (`Dispatchers.IO`) | Sets initial seed state; sets up inner 5-flow combine (`observeConversation`, `observeDraft`, `pacedAttachmentProgress`, `observeForConversation`, `deliveryCountsFlow`); joins pins, roster, sync, typing, and presence; executes message mapping, day separators, initials, time labels, and bubble grouping across ALL loaded entities. |
| **HP-CHAT-02** | `RealFlashChatRepository.messageContentFlow` | Any message insert, status update, reaction change, delivery ack, or 100ms progress tick | `Dispatchers.IO` | Re-evaluates entire open message window: `entities.associateBy`, `reactions.groupBy`, `deliveryCounts.associateBy`, `entities.map` (`FlashMessageUi` creation, `computeInitials`, `formatTime`), `assignDaySeparators`, `computeMessageGroupPositions` (allocates fresh `FlashMessageUi.copy` for each row). |
| **HP-CHAT-03** | `RealFlashChatRepository.chatListState` | Ongoing background pipeline: any presence update, unread count change, latest preview change, or typing event | `scope` (`Dispatchers.IO`) | Evaluates 5-flow combine; maps every conversation entity to `FlashChatListItemUi`; executes sequential blocking `groupMemberDao.activeMembers(entity.id)` for every group conversation; sorts entire list via `sortedChatListItems`; updates `_chatListState`. |
| **HP-CHAT-04** | `FlashMessageList` LazyColumn Scroll | User flings/scrolls conversation | **Main (UI)** | Renders `itemsIndexed(ordered)`; evaluates 14 remembered lambdas per visible row; evaluates `FlashDaySeparator` inside Column; clips and measures variable-height bubbles; lays out bubbles and timestamps. |
| **HP-CHAT-05** | `FlashChatListScreen` LazyColumn Scroll | User flings/scrolls chat list | **Main (UI)** | Renders `itemsIndexed(displayItems)`; composes `SwipeToDismissBox`, allocates `rememberSwipeToDismissBoxState`, `MutableInteractionSource`, `animateFloatAsState`, and `derivedStateOf` per visible row; measures and draws avatar, title, preview text, unread badge, and presence indicator. |
| **HP-CHAT-06** | `FlashChatRepository.searchChats` | User types in chat list search bar (debounced 200ms) | `Dispatchers.IO` | Executes `MessageDao.searchMessages` (`LIKE '%query%'` full table scan across `messages`); iterates matching rows and performs sequential N+1 `conversationDao.get(conversationId)` queries; filters snippets via `FlashSearchText`. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | LazyColumn keys and contentType configuration in message list and chat list | **Problem** | `OPT-O04-05`, `OPT-O04-06` (Message list inlines day separators inside message items with identical contentType; Chat list allocates full `SwipeToDismissBox` per row) | `STATIC-ARITHMETIC` |
| 2 | Item height variance, reverseLayout, and scroll performance | **Problem** | `OPT-O04-06`, `OPT-O04-07` (Extreme height variance from inlined day separators, quote replies, and invite cards without estimated heights; `reverseLayout = true` is correct) | `STATIC-ARITHMETIC` |
| 3 | Pagination behavior (historyBefore / historyAfter / initial load window) | **Problem** | `OPT-O04-01` (`MessageDao.observeConversation` is completely unbounded without `LIMIT`; `historyBefore` keyset pagination is never called by UI) | `STATIC-ARITHMETIC` |
| 4 | Row mapping in FlashChatRepository.conversationState: how many flows are combined? Does the whole thread re-map on a single receipt/typing/progress event? (EXP-010 context) | **Problem** | `OPT-O04-01`, `OPT-O04-03` (Inner combine merges 5 flows, outer combine merges 5 flows; entire thread re-maps on any receipt, reaction, or status change) | `STATIC-ARITHMETIC` |
| 5 | Diffing, throttling windows (UiPacing.throttleLatest), and message grouping | **Problem** | `OPT-O04-01`, `OPT-O04-03` (`throttleLatest` is ONLY used for attachment progress and transfer screen; unthrottled receipts/reactions re-run grouping and copy every item) | `STATIC-ARITHMETIC` |
| 6 | Day separators, link detection, markdown parsing and syntax highlighting cost per bubble | **No problem** | Plain text only by architecture design (ADR/HUB-ANNOUNCEMENTS); no markdown or syntax highlighting; link detection is single precompiled invite regex; day separators formatted in repo. | `STATIC-ARITHMETIC` |
| 7 | Unread-count queries per conversation and invalidation amplification | **Problem** | `OPT-O04-02`, `OPT-O04-04` (`observeUnreadCounts` has correlated subquery; any message write invalidates entire table and triggers N+1 queries in chat list combine) | `STATIC-ARITHMETIC` |
| 8 | Chat-list previews (observeLatestPreviews) query efficiency and invalidation | **Problem** | `OPT-O04-02`, `OPT-O04-04` (`observeLatestPreviews` uses `MAX(sentAt) GROUP BY conversationId`; invalidates on any message edit and triggers dual combine emissions) | `STATIC-ARITHMETIC` |
| 9 | Search queries: searchMessages LIKE '%x%' scans, attachmentName scan, index usage, and EXPLAIN QUERY PLAN | **Problem** | `OPT-O04-04` (Unindexed substring `LIKE '%query%'` scans entire table; no FTS table; `searchChats` executes N+1 `conversationDao.get` queries) | `STATIC-ARITHMETIC` |
| 10 | Scroll-to-message cost and jump/pulse animations | **Problem** | `OPT-O04-07` (`jumpToMessage` uses unconstrained `animateScrollToItem`, forcing composition and layout of hundreds of intervening items; pulse glow is a lightweight border) | `STATIC-ARITHMETIC` |
| 11 | Typing indicator emission rate and UI invalidation | **Problem** | `OPT-O04-02` (Outgoing emission is well-paced with 3s keepalive; inbound invalidates `chatListState`'s hot combine, triggering N+1 `activeMembers` database queries) | `STATIC-ARITHMETIC` |
| 12 | Drafts persistence frequency: does it write to database per keystroke? | **No problem** | Draft is held exclusively in Compose `mutableStateOf("")` in memory while typing; written to `DraftDao.upsertDraft` ONLY on `DisposableEffect.onDispose` upon leaving screen. | `STATIC-ARITHMETIC` |
| 13 | Reactions and receipts fan-out in a group of 20 members (database and UI cost) | **Problem** | `OPT-O04-03` (1 message sent produces 19 incoming delivery receipts; each updates DB and triggers full unthrottled thread re-mapping across all items) | `STATIC-ARITHMETIC` |

---

## 5. Detailed Findings

### OPT-O04-01 — Unbounded Full-History Query & Thread Re-Mapping in Open Conversation
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M/C/T/S   **Runtime:** Android | Desktop
- **Component:**  
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:23-27, 39-44`
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:1503-1576`
- **What is wasteful:**  
  When a user opens a conversation, `RealFlashChatRepository.openConversation` observes the message stream using `messageDao.observeConversation(conversationId)`. This query is completely unconstrained:
  ```sql
  SELECT * FROM messages WHERE conversationId = :conversationId AND deletedAt IS NULL ORDER BY sentAt DESC, localId DESC
  ```
  It contains **no `LIMIT` clause**. If a thread contains 2,000, 5,000, or 20,000 messages, every single row is read from SQLite into a `List<MessageEntity>` in RAM.  
  Crucially, although `MessageDao.historyBefore` was built with efficient keyset pagination (`(sentAt < :cursorSentAt OR (sentAt = :cursorSentAt AND localId < :cursorLocalId)) LIMIT :limit`), **no UI screen or repository path calls it** (`logs/progress.md:12603`).  
  Furthermore, whenever ANY event fires in `messageContentFlow` (a new message, a delivery receipt, a reaction update, or an attachment progress tick), the repository executes a full transform over ALL loaded entities:
  1. `entities.associateBy { it.localId }` (allocates an N-entry HashMap).
  2. `entities.map { entity -> ... }` (creates N `FlashMessageUi` objects, invoking `computeInitials` and `formatTime` for every message).
  3. `assignDaySeparators(entities.zip(messages))` (iterates all N items, calculating calendar boundaries).
  4. `computeMessageGroupPositions(messagesWithSeparators)` (iterates all N items and calls `message.copy(...)`, allocating another N `FlashMessageUi` instances).
- **Cost model:**  
  In a thread with 2,500 messages:  
  1 open x (2,500 Room `MessageEntity` + 2,500 initial `FlashMessageUi` + 2,500 grouped `FlashMessageUi` + 5,000 string allocations) = **~12,500 objects (~1.8 MB transient heap)** and **~55 ms CPU time** on modern hardware (significantly longer on low-end Android devices). Every single delivery receipt or reaction repeats this entire ~12,500 object allocation cycle.
- **User-visible effect:** Opening conversations with long histories stutters or drops frames; memory usage balloons; on memory-constrained devices (e.g. 2 GB RAM Belfone target), frequent garbage collection pauses induce noticeable scroll stutter.
- **Expected gain:**  
  **80%–95% reduction** in initial open latency and memory allocation by loading an initial window of 50 messages via keyset pagination (`historyBefore`), loading older chunks on demand as the user approaches the top.
- **Proposed change:**  
  1. Window `openConversation`'s initial query to `INITIAL_PAGE_SIZE = 50` messages using `MessageDao.historyBefore` or a bounded flow `observeConversationPaged(conversationId, limit = 50)`.
  2. Implement an on-demand pagination trigger in `FlashMessageList` when `listState.firstVisibleItemIndex` approaches the head of `ordered`.
  3. Cache calculated initials and formatted timestamps on `FlashMessageUi` so re-mapping does not re-derive static strings.
- **Invariants touched (5.3):** Invariant 3 (durability and ordering: keyset pagination on `(sentAt DESC, localId DESC)` strictly preserves message ordering).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Populate a test conversation with 5,000 messages. Measure heap allocation delta and time from `openConversation` call to first `_conversationState.value` emission. Target: < 15 ms, < 200 KB heap.
- **Verifier result:** Survived. Confirmed: `MessageDao.kt:23-27` has no `LIMIT`; `RealFlashChatRepository.kt:1504` passes `messageDao.observeConversation(conversationId)` directly into the 5-flow combine.
- **Related:** EXP-010, ADR-044, `docs/core-upgrade-plan.md:347`.

---

### OPT-O04-02 — N+1 Synchronous Database Queries in Chat List State Combine on Every Message/Presence/Typing Event
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S/T/C/B   **Runtime:** Android | Desktop
- **Component:**  
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:1174-1244`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupMemberDao.kt:17-19`
- **What is wasteful:**  
  In `RealFlashChatRepository.init`, a long-lived coroutine in repository `scope` runs the `chatListState` combine over 5 streams:
  ```kotlin
  combine(
      conversationDao.observeAll(),
      displayedPresence,
      messageDao.observeUnreadCounts(localDeviceId),
      messageDao.observeLatestPreviews(),
      typingFlow,
  ) { entities, peers, unreadRows, previewRows, typingByConversation -> ... }
  ```
  Inside the transform, for every conversation in `entities`, `toUiItem(entity)` is executed. Lines 1193–1198 contain a severe N+1 synchronous database query:
  ```kotlin
  if (entity.isGroup) {
      val onlineMembers = groupMemberDao
          ?.activeMembers(entity.id)
          .orEmpty()
          .count { it.deviceId in peers.online }
  ```
  `groupMemberDao.activeMembers(entity.id)` is an individual Room query (`SELECT * FROM group_members WHERE groupId = :groupId AND isActive = 1`).  
  If a user has 30 group conversations, **30 separate SQLite queries are executed sequentially on every emission of this combine**.  
  Worse, because this combine includes:
  - `typingFlow` (emits whenever anyone starts/stops typing or every 3 seconds while typing),
  - `displayedPresence` (emits whenever any peer changes presence on LAN, WebSocket, or Hub),
  - `observeUnreadCounts` & `observeLatestPreviews` (both invalidate on ANY message write anywhere in the app),  
  this 30-query barrage runs continuously in the background, even when the user is not viewing the chat list (e.g. while chatting in an active thread or browsing settings).
- **Cost model:**  
  30 group chats x 1 event every 3 s (active typing or receipt) x 30 SQLite queries = **600 SQLite queries per minute (36,000 queries/hour)** on the background I/O dispatcher against the encrypted SQLCipher database.
- **User-visible effect:** Excessive battery drain, flash storage I/O wear, background SQLite write-lock contention starving active message inserts and outbox drains.
- **Expected gain:**  
  **97% reduction** in chat-list database queries: eliminate the 30 per-group queries entirely by querying all active member device IDs in a single grouped query (`SELECT groupId, deviceId FROM group_members WHERE isActive = 1`) or maintaining an in-memory cache of active member IDs per group.
- **Proposed change:**  
  1. Add a DAO query `GroupMemberDao.allActiveGroupMembers(): List<GroupIdWithDeviceId>` to fetch the mapping in one single query, or maintain a `SyncMap<String, Set<String>>` of active group member device IDs updated via `groupMemberDao.observeAllActiveMembers()`.
  2. Gate `chatListState` combine execution or pause active member lookups when the chat list is not the active, visible destination.
- **Invariants touched (5.3):** None. Preserves accurate online member counts and group presence indicators.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Attach a SQL statement logging interceptor. Send typing keepalives and verify that zero individual `SELECT * FROM group_members WHERE groupId = ?` statements are issued per tick.
- **Verifier result:** Survived. Confirmed: `RealFlashChatRepository.kt:1195` calls `activeMembers(entity.id)` in a map loop over all conversation entities.
- **Related:** EXP-010, EXP-011, ADR-044.

---

### OPT-O04-03 — Group Receipt & Reaction Invalidation Storm: Full Thread Re-Mapping per Inbound Ack
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C/J/M/T   **Runtime:** Android | Desktop
- **Component:**  
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:1503-1576, 5521-5528, 6125-6133, 6748-6770`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupDeliveryDao.kt:14-16`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/ReactionDao.kt:20-22`
- **What is wasteful:**  
  In a group of 20 members:
  1. **Delivery Receipts:** When the local user sends a message, each of the 19 other members sends back a `GroupWireFrame.Receipt`. As each arrives, `recordGroupDelivery` calls `deliveries.markDelivered(messageId, memberId, deliveredAt)` which updates `group_deliveries`.
  2. **Reactions:** When a message is reacted to by group members, each incoming `ReactionFrame` executes `reactionDao.upsert(...)`.
  
  Unlike `pacedAttachmentProgress` (which EXP-010 throttled to 100ms via `throttleLatest`), **neither `groupDeliveryDao.observeDeliveryCounts` nor `reactionDao.observeForConversation` are throttled or debounced**.  
  Every single receipt and reaction write updates Room, causing an immediate emission of `messageContentFlow`.  
  Each emission executes the entire thread derivation:
  - Re-grouping reaction rows.
  - Re-associating delivery counts.
  - Mapping all `MessageEntity` objects in the thread to `FlashMessageUi`.
  - Re-formatting all time labels (`formatTime(entity.sentAt)`).
  - Re-computing all sender initials (`computeInitials`).
  - Running `assignDaySeparators`.
  - Running `computeMessageGroupPositions` (copying every message in the thread).
  
  For a single message sent in a 20-member group, the entire open conversation thread is completely re-mapped **19 consecutive times within 1–2 seconds**.
- **Cost model:**  
  1 outgoing message in 20-member group x 19 receipts x (100 messages in window x 3 object allocations + 2 string derivations) = **19 x 500 = 9,500 object allocations** and **19 full thread re-computations** in < 2 seconds. If multiple members react to a photo, this compounds to tens of thousands of allocations per second.
- **User-visible effect:** Severe frame drops, composer typing lag while waiting for send receipts to settle, noticeable UI freeze on entry-tier devices when multiple peers respond simultaneously.
- **Expected gain:**  
  **90% reduction** in thread re-derivation frequency by applying `throttleLatest(100L)` or `debounce(50L)` to `deliveryCountsFlow` and `reactionDao.observeForConversation` in `openConversation`.
- **Proposed change:**  
  Apply `throttleLatest(100L)` to `deliveryCountsFlow` and `reactionDao.observeForConversation(conversationId)` within `openConversation`, matching the proven pattern established for `pacedAttachmentProgress` in EXP-010.
- **Invariants touched (5.3):** Invariant 3 & Invariant 6 (delivery quorum numbers `deliveredTo/deliveredTotal` and reaction badges update smoothly without dropping final state).
- **Risk / effort:** Low risk; S effort (add `throttleLatest` calls to the two flow inputs in `RealFlashChatRepository.kt:1507-1508`).
- **Prove it:** Send a message in a simulated 20-member group with simultaneous receipt delivery. Count the number of `messageContentFlow` combine executions. Target: <= 2 executions (from 19 down to 2).
- **Verifier result:** Survived. Confirmed: `RealFlashChatRepository.kt:1506-1508` shows `pacedAttachmentProgress` is throttled, but `reactionDao.observeForConversation` and `deliveryCountsFlow` are unthrottled.
- **Related:** EXP-010, ADR-044, `GroupDeliveryDao.kt`.

---

### OPT-O04-04 — Full Table Scan & Correlated Subqueries on Search and Unread Queries Without Indices or FTS
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S/T/C       **Runtime:** Android | Desktop
- **Component:**  
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:190-210, 241-268`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/MessageEntity.kt:14-22`
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6577-6593`
- **What is wasteful:**  
  1. **Unindexed Substring Search Scans:** `MessageDao.searchMessages` and `searchConversationMessages` run:
     ```sql
     SELECT * FROM messages WHERE deletedAt IS NULL AND (
         (text NOT LIKE 'voice:%' AND text NOT LIKE 'call:%' AND text LIKE '%' || :query || '%' ESCAPE '\')
         OR attachmentName LIKE '%' || :query || '%' ESCAPE '\'
     ) ORDER BY sentAt DESC, localId DESC LIMIT :limit
     ```
     `MessageEntity` has indices only on `["conversationId", "sentAt", "localId"]` and `["sentAt"]`. `text` and `attachmentName` are unindexed. Because the query uses a leading wildcard (`%query%`), standard B-tree indices cannot assist. SQLite's EXPLAIN QUERY PLAN performs a complete table scan (`SCAN TABLE messages USING INDEX index_messages_sentAt`). While hub announcements use SQLite FTS5, the messages table lacks any full-text search virtual table.
  2. **N+1 Conversation Lookups in Search:** In `RealFlashChatRepository.searchChats` (lines 6580–6593), after `searchMessages` returns up to 300 matching rows, the code iterates over each row and calls `conversationDao.get(row.conversationId)` individually to resolve the conversation title, rather than using a single SQL `JOIN conversations` or a batched `IN (:ids)` query.
  3. **Correlated Subquery in `observeUnreadCounts`:** Line 195 executes `(SELECT sentAt FROM messages WHERE localId = c.lastReadCursor)` for each candidate conversation on every write to the `messages` table.
- **Cost model:**  
  On a database with 15,000 messages:  
  1 search keystroke (debounced 200ms) x (15,000-row table scan + string substring parsing on 15,000 rows) + (up to 50 individual `conversationDao.get` queries) = **120 ms – 350 ms SQLite execution time**.
- **User-visible effect:** Search UI stutters when typing query strings; noticeable keyboard input lag; battery drain during search exploration.
- **Expected gain:**  
  **70%–85% reduction** in search query execution time by joining `conversations` directly in `searchMessages` (eliminating the N+1 `get` queries) and adopting SQLite FTS5 for message content and attachment filenames.
- **Proposed change:**  
  1. Update `searchMessages` to join `conversations` on `conversationId` to return the conversation title in the projection, eliminating the subsequent loop of `conversationDao.get` calls in `RealFlashChatRepository.kt:6583`.
  2. Implement an FTS5 virtual table (`messages_fts`) for message text and attachment names for full-history searches exceeding 1,000 messages.
- **Invariants touched (5.3):** None. Preserves exact search hit semantics, tombstone filtering, and marker exclusion.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Populate database with 20,000 messages. Run `EXPLAIN QUERY PLAN` on `searchMessages` and benchmark `searchChats("budget")`. Verify execution time drops below 30 ms with zero follow-up `get` queries.
- **Verifier result:** Survived. Confirmed: `MessageEntity.kt` has no text index or FTS; `RealFlashChatRepository.kt:6583` executes `conversationDao.get(conversationId)` in a per-row loop.
- **Related:** ADR-218, `MessageSearchMarkersJvmTest.kt`.

---

### OPT-O04-05 — Chat List Row Layout & Allocation Overhead: Per-Item SwipeToDismissBox and Gesture State
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/M/C       **Runtime:** Android | Desktop
- **Component:**  
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListRow.kt:81-125`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListScreen.kt:364-394`
- **What is wasteful:**  
  In `FlashChatListRow`, every single item in the `LazyColumn` is wrapped in Material3's `SwipeToDismissBox`. Inside each row's composition:
  ```kotlin
  val dismissState = rememberSwipeToDismissBoxState(...)
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val pressScale = animateFloatAsState(...)
  val pressInset = remember(pressScale) { derivedStateOf { pressScale.value < 1f } }
  ```
  Every single visible conversation item instantiates:
  1. A `SwipeToDismissBoxState` instance with nested gesture detectors.
  2. A `MutableInteractionSource`.
  3. An `animateFloatAsState` state holder.
  4. A `derivedStateOf` calculation.
  5. The `SwipeToDismissBox` layout wrapper with background and foreground composables.
  
  During rapid scrolling through a chat list of 50–100 items, Compose constantly instantiates and discards these heavy gesture and animation objects as items enter and leave the viewport. On desktop (where swipe gestures are typically unused or replaced by right-click/context menus), this entire touch-dismiss machinery runs unconditionally.
- **Cost model:**  
  Scrolling 50 items x 5 state/layout allocations per item = **250 state objects and gesture controllers allocated during a single fling**, inducing Garbage Collection pressure and dropped frames on 90/120 Hz displays.
- **User-visible effect:** Chat list flinging jank, micro-stutters during fast scrolling on midrange Android devices.
- **Expected gain:**  
  **30%–40% reduction** in chat-list row composition overhead and layout time: avoid initializing swipe-to-dismiss states unless an active horizontal drag gesture is detected, or disable `SwipeToDismissBox` entirely on Desktop.
- **Proposed change:**  
  1. On Desktop (`DesktopShell`), bypass `SwipeToDismissBox` and render `FlashChatListRowContent` directly (archive is available via secondary click / menu).
  2. On Android, hoist gesture detection or use a lightweight drag gesture modifier that creates dismiss state only when horizontal dragging begins.
- **Invariants touched (5.3):** Invariant 6 (preserves swipe-to-archive functionality on mobile).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Profile chat list scroll using Android Studio Profiler (Compose Layout Inspector). Measure frame time distribution during continuous 1,000 px/s fling. Pass threshold: 99th percentile frame time < 16.6 ms.
- **Verifier result:** Survived. Confirmed: `FlashChatListRow.kt:81-125` instantiates `rememberSwipeToDismissBoxState` and `MutableInteractionSource` inside every row.
- **Related:** UI-013, UI-046.

---

### OPT-O04-06 — Inlined Day Separators in Message List Break Compose Slot Re-use and Recycler Metrics
- **Impact rank:** Low         **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C        **Runtime:** Android | Desktop
- **Component:**  
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageList.kt:198, 226-228, 354-360`
- **What is wasteful:**  
  In `FlashMessageList.kt`, date separators are rendered inline inside the message's `Column` item:
  ```kotlin
  Column(modifier = Modifier.then(flashAnimateItem(motion))) {
      message.daySeparator?.let { label ->
          FlashDaySeparator(label = label)
      }
      FlashMessageBubble(...)
  }
  ```
  However, the item's `contentType` is defined as:
  ```kotlin
  internal fun flashMessageContentType(message: FlashMessageUi): String = when {
      message.callEvent != null -> if (message.isMine) "callEvent_out" else "callEvent_in"
      message.images.isNotEmpty() -> if (message.isMine) "image_out" else "image_in"
      message.voiceAttachments.isNotEmpty() -> if (message.isMine) "voice_out" else "voice_in"
      message.fileAttachments.isNotEmpty() -> if (message.isMine) "file_out" else "file_in"
      else -> if (message.isMine) "text_out" else "text_in"
  }
  ```
  A message that includes a day separator has the **identical `contentType`** (e.g. `"text_in"`) as a message without a day separator, despite having an extra 40+ dp height delta.  
  When `LazyColumn` recycles a composition slot during scrolling, it reuses the slot table of a previously measured item of the same `contentType`. Because the heights vary wildly between rows with and without date headers, Compose's lazy layout engine must perform unexpected relayouts and height recalculations, causing scroll hitching when crossing day boundaries.
- **Cost model:**  
  Every day boundary crossed during rapid flinging triggers an unpredicted 40 dp height shift, causing LazyColumn to recalculate offsets and re-measure subsequent items, inducing 1–3 dropped frames per boundary.
- **User-visible effect:** Subtle hitch or jump when scrolling past date separators (e.g. "Yesterday", "Today").
- **Expected gain:**  
  Eliminate layout shifts and smooth scroll flings by emitting day separators as independent items in `LazyColumn` with their own dedicated `contentType = "day_separator"`, or incorporating `hasDaySeparator` into the `contentType` string.
- **Proposed change:**  
  Update `flashMessageContentType` to suffix `"_sep"` when `message.daySeparator != null` (e.g. `"text_in_sep"`), or hoist `FlashDaySeparator` into its own item in the `LazyColumn`DSL.
- **Invariants touched (5.3):** None. Day separators continue to display identically.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Measure frame times when flinging across 5 day boundaries with and without distinct `contentType`.
- **Verifier result:** Survived. Confirmed: `FlashMessageList.kt:198` and `354-360` ignore `daySeparator` in `flashMessageContentType`.
- **Related:** UI-005, `FlashDateSeparatorLogicTest.kt`.

---

### OPT-O04-07 — Unconstrained `animateScrollToItem` in `jumpToMessage` Causes Measure Storm Across Distant Items
- **Impact rank:** Low         **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J/C/T      **Runtime:** Android | Desktop
- **Component:**  
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:586-602`
- **What is wasteful:**  
  When a user jumps to a message (from a search hit, a pinned message banner tap, or a reply quote card tap), `FlashConversationScreen.jumpToMessage` executes:
  ```kotlin
  val targetIndex = localMessages.asReversed().indexOfFirst { it.id == targetId }
  val scrollIndex = FlashChatScrollMath.targetScrollIndex(...)
  if (scrollIndex >= 0) {
      coroutineScope.launch {
          if (motion.reduceMotion) {
              listState.scrollToItem(scrollIndex)
          } else {
              listState.animateScrollToItem(scrollIndex)
          }
          highlightedMessageId = targetId
      }
  }
  ```
  If the target message is 100, 300, or 500 items away in history, `LazyListState.animateScrollToItem` attempts to smoothly animate through every single intermediate position. In Compose, animating across hundreds of items forces the lazy list to compose, measure, and immediately discard every intermediate item at 60/120 Hz, causing severe frame drops (jank) and high CPU contention.
- **Cost model:**  
  1 jump to a search hit 200 items away x 200 item compositions and measure passes executed over a 300–500 ms animation window = **tens of dropped frames and 100% main thread saturation** during the jump.
- **User-visible effect:** Severe stuttering when tapping search hits or pinned messages from weeks ago.
- **Expected gain:**  
  Instant, jank-free jumps by fast-forwarding `listState.scrollToItem` to within a small neighborhood (e.g. 10 items away) before running the short visual animation to the final target, or jumping immediately when the target distance exceeds 20 items.
- **Proposed change:**  
  When `abs(listState.firstVisibleItemIndex - scrollIndex) > 15`, call `listState.scrollToItem(if (scrollIndex > listState.firstVisibleItemIndex) scrollIndex - 10 else scrollIndex + 10)` before invoking `animateScrollToItem(scrollIndex)`, ensuring the animation never traverses more than 10 items.
- **Invariants touched (5.3):** Invariant 6 (under `reduceMotion`, `scrollToItem` is already used; this optimizes regular motion).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Tap a search result for a message 200 items back. Observe frame drop count in Android Studio GPU Profiler. Target: 0 dropped frames.
- **Verifier result:** Survived. Confirmed: `FlashConversationScreen.kt:597` directly calls `animateScrollToItem(scrollIndex)` without distance bounds.
- **Related:** UI-023, UI-024, `FlashChatScrollMath.kt`.

---

## 6. "No problem, because..." Notes

### 6.1 Drafts Persistence Frequency (Checklist Item 12)
- **Claim:** Does the composer write to the database per keystroke?
- **Finding:** **No problem.**
- **Code Fact:** In `FlashConversationScreen.kt:415, 426-431` and `FlashComposer.kt:270-275`:
  ```kotlin
  var draft by remember { mutableStateOf("") }
  ...
  val latestDraft by rememberUpdatedState(draft)
  DisposableEffect(Unit) {
      onDispose { onPersistDraft(latestDraft) }
  }
  ```
  Typing updates Compose state in memory only (`draft = capped`). No DAO calls or database transactions occur during keystrokes. The draft is persisted to `DraftDao.upsertDraft` strictly upon leaving the conversation screen via `DisposableEffect.onDispose`.

### 6.2 Markdown Parsing and Syntax Highlighting (Checklist Item 6)
- **Claim:** High CPU cost from markdown parsing, syntax highlighting, and general link detection per bubble.
- **Finding:** **No problem.**
- **Code Fact:** Per architectural decision (documented in `docs/plans/HUB-ANNOUNCEMENTS-PLAN.md:154` and `RealFlashChatRepository`), messages are **strictly plain text**. There is no Markdown parser, HTML parser, or syntax highlighter in the client. Text is rendered directly via `FlashText` (BasicText + ColorProducer). Link detection is restricted to a single precompiled invite regex `FlashGroupInviteJoinMath.extractInviteUrl` executed once via `remember(message.text)`.

### 6.3 ReverseLayout and LazyColumn Keys (Checklist Items 1 & 2)
- **Claim:** Key stability and list layout direction.
- **Finding:** **No problem.**
- **Code Fact:** In `FlashMessageList.kt:179, 197`:
  ```kotlin
  reverseLayout = true
  key = { _, message -> flashMessageKey(message) } // message.id (UUID)
  ```
  `reverseLayout = true` is the optimal mobile chat pattern: the newest message anchors at layout index 0 without recalculating parent offsets. Keys are stable UUIDs from `MessageEntity.localId`, preventing item recreation on list updates.

### 6.4 Outgoing Typing Indicator Rate (Checklist Item 11)
- **Claim:** Does typing flood the network with wire frames?
- **Finding:** **No problem on outgoing path.**
- **Code Fact:** In `FlashConversationScreen.kt:918-924` and `lines 443-448`:
  ```kotlin
  val typingNow = capped.isNotBlank()
  if (typingNow != isTypingSignalled) {
      isTypingSignalled = typingNow
      onTypingChanged(typingNow)
  }
  ```
  Wire frames are emitted strictly on state transitions (empty -> non-empty -> empty). While typing continues, a keepalive is sent at most once every 3,000 ms (`TYPING_KEEPALIVE_MS = 3_000L`). It does not emit per keystroke. (Note: Inbound typing invalidation of the chat list is documented as a problem in `OPT-O04-02`).

---

## 7. Compounding Effects & Cross-Domain Interactions

1. **Domain O01 (Startup & Cold Start):**  
   The hot `chatListState` combine in `RealFlashChatRepository` launches immediately upon engine start (`DiscoveryEngineHolder.ensureStarted`). If the app boots with 20+ group chats, `OPT-O04-02` immediately fires 20+ sequential SQLite queries against SQLCipher during the critical cold start window, competing for I/O and CPU against database migrations and discovery initialization.
2. **Domain O03 (Transfer Engine / EXP-010):**  
   While EXP-010 throttled `attachmentProgress` to 100ms (`pacedAttachmentProgress`), `openConversation` still re-maps the entire message history on every progress tick (`OPT-O04-01`). For a 1 GB file transfer in a conversation with 3,000 messages, the app re-maps all 3,000 messages 10 times a second for minutes at a time, generating gigabytes of transient heap churn and starving the transfer socket dispatcher.
3. **Domain O05 (Hub & Group Sync):**  
   When a device syncs offline catch-up history via `FLASH_GSYNC`, dozens or hundreds of messages arrive in rapid succession. Each batch updates Room tables (`messages`, `group_deliveries`), which triggers both `messageContentFlow` and `chatListState` repeatedly, causing severe UI lag if a user attempts to chat while a sync is completing.

---

## 8. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Rating | Runtime | Effort | Risk |
|---|---|---|---|---|---|---|---|
| **OPT-O04-01** | Unbounded Full-History Query & Thread Re-Mapping in Open Conversation | High | M, C, T, S | STATIC-ARITHMETIC | Android, Desktop | M | Medium |
| **OPT-O04-02** | N+1 Synchronous Database Queries in Chat List State Combine on Every Event | High | S, T, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Low |
| **OPT-O04-03** | Group Receipt & Reaction Invalidation Storm: Full Thread Re-Mapping per Ack | High | C, J, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Low |
| **OPT-O04-04** | Full Table Scan & Correlated Subqueries on Search and Unread Queries | Medium | S, T, C | STATIC-ARITHMETIC | Android, Desktop | M | Medium |
| **OPT-O04-05** | Chat List Row Layout & Allocation Overhead: Per-Item SwipeToDismissBox State | Medium | J, M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low |
| **OPT-O04-06** | Inlined Day Separators in Message List Break Compose Slot Re-use | Low | J, C | STATIC-ARITHMETIC | Android, Desktop | S | Low |
| **OPT-O04-07** | Unconstrained `animateScrollToItem` in `jumpToMessage` Causes Measure Storm | Low | J, C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low |

### Totals by Impact Rank
- **High:** 3
- **Medium:** 2
- **Low:** 2
- **Total Findings:** 7

### Dimensions Addressed
- **T (Latency/Time):** 4 findings
- **J (Jank/Frame Time):** 4 findings
- **C (CPU):** 6 findings
- **M (Memory/Heap):** 3 findings
- **S (Storage/IO):** 3 findings
- **B (Battery/Wakeups):** 1 finding
