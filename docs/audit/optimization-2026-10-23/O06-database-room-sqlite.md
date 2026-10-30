# Domain O06 — Database: Room, SQLite, SQLCipher Performance Audit
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Domain:** O06 — Database: Room, SQLite, SQLCipher  
**Target Runtimes:** Android (ART / SQLCipher 4.18 / Room 2.8.4), Desktop (JVM 21 / SQLite3MultipleCiphers / Room 2.8.4), Server (JVM 21 / SQLite / HikariCP)

---

## 1. Files Read & Audited

The following database, DAO, entity, migration, and opener implementations were read and audited in detail:

### Database Core & Openers
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabase.kt`
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherSQLiteDriver.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherConnection.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherStatement.kt`
- `server/src/main/kotlin/com/transfer/flash/server/storage/ServerDatabase.kt`

### Migrations & Schemas
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashSchemaSteps.kt`
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashMigrations.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/FlashJvmMigrations.kt`
- `core/persistence/schemas/com.transfer.flash.core.persistence.db.FlashDatabase/20.json`

### DAOs
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/ConversationDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/OutboxDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferChunkDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/SwarmDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/HubFileDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/OrgDirectoryDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupDeliveryDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/GroupMemberDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/ReactionDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/DraftDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/ReadCursorDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessagePinDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/RecentSearchDao.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxStore.kt`

### Entities
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/MessageEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/ConversationEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/OutboxEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/TransferEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/TransferChunkEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/SwarmContentEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/SwarmTombstoneEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/HubFileEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/OrgDirectoryMemberEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/OrgDepartmentEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupDeliveryEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/ReactionEntity.kt`

### Call Sites & Repository Layers
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/RoomTransferStore.kt`

---

## 2. Hot Paths with Execution Frequency & Dispatchers

1. **Unread Counts & Previews Re-query on Inbound/Outbound Message & Status Updates (`Dispatchers.IO`)**
   - *Path:* `MessageDao.insert` / `updateStatus` / `updateAttachmentPath` / `markReadUpTo` -> Room `InvalidationTracker` -> `observeUnreadCounts` + `observeLatestPreviews` re-execution -> `RealFlashChatRepository` combine (lines 1175–1210).
   - *Frequency:* 1 to 50 Hz during active messaging, attachment transfer, and receipt processing.
   - *Cost:* `observeUnreadCounts` executes a full index scan over all messages with a correlated subquery for each row; `observeLatestPreviews` executes a full table scan and aggregation. At 100,000 messages, **each write triggers 1,270 ms of blocking query time** on `Dispatchers.IO`.

2. **Conversation Message Paging & Keyset Seek (`Dispatchers.IO`)**
   - *Path:* `MessageDao.historyBefore(conversationId, sentAt, localId, limit)`
   - *Frequency:* On screen open and upward scroll (1–5 queries per conversation scroll).
   - *Cost:* Uses composite index `index_messages_conversationId_sentAt_localId (conversationId=? AND sentAt<?)`. Execution time is < 0.5 ms. Excellent.

3. **Transfer Chunk Resume State & Progress Persistence (`Dispatchers.IO` / `workerDispatcher`)**
   - *Path:* `RealFlashTransferRepository.executeSend` / `onIncomingChunkConfirmed` -> `store.markChunksDone` (`TransferChunkDao.insertAll`) followed by `store.setBytesDone` (`TransferDao.setBytesDone`).
   - *Frequency:* 10 Hz to 100 Hz during active multi-stream file transfers.
   - *Cost:* Two independent Room transactions committed per confirmed chunk batch. Each transaction incurs SQLite lock acquisition, journal writing, and SQLite synchronization overhead.

4. **Outbox Drain Polling (`Dispatchers.IO`)**
   - *Path:* Outbox sender loop -> `OutboxDao.dueForDelivery(now, limit)`.
   - *Frequency:* 0.2 Hz to 1 Hz during active pending send operations and on network reconnect.
   - *Cost:* Full table scan of `outbox` with a temporary B-tree sort (`SCAN outbox | USE TEMP B-TREE FOR ORDER BY`) because `outbox.nextAttemptAt` lacks an index.

5. **Full History Global Search (`Dispatchers.IO`)**
   - *Path:* User typing in Search UI -> `MessageDao.searchMessages(query, limit)`.
   - *Frequency:* Debounced keystrokes (2–3 queries per search session).
   - *Cost:* Leading wildcard `LIKE '%query%'` forces a full table scan of all messages across all conversations. At 100,000 messages on desktop, requires 88.7 ms; on Android with SQLCipher decryption over 5,700 encrypted 4 KB pages, this blocks the I/O thread for **600–1,200 ms** per keystroke.

---

## 3. Checklist Table

| # | Item | Status | Finding / Evidence / Reason |
|---|---|---|---|
| 1 | Index coverage on all top queries | **Problem** | Findings **OPT-O06-05** & **OPT-O06-09**. Missing indexes on `outbox.nextAttemptAt`, `hub_file.createdAtMs`, `conversations(pinned, sortOrder)`, `transfers.updatedAtMs`, `swarm_content.createdAtMs`, `org_directory_member.displayName`. Leading wildcard `OR` in `searchMembers` completely disables index scan. |
| 2 | Flow re-query amplification | **Problem** | Findings **OPT-O06-01** & **OPT-O06-02**. High-frequency writes to `messages` re-trigger `observeUnreadCounts` (697 ms) and `observeLatestPreviews` (573 ms) on the chat list. Writes to `hub_file` re-trigger `observeRecent`. Writes to `group_deliveries` re-trigger `observeDeliveryCounts`. |
| 3 | Transaction boundaries & write amplification | **Problem** | Finding **OPT-O06-07**. Every confirmed chunk batch executes two separate, back-to-back Room transactions: `TransferChunkDao.insertAll` followed by `TransferDao.setBytesDone`. |
| 4 | ON CONFLICT / upsert patterns & efficiency | **Problem** (Minor) | `TransferDao.insert` uses `OnConflictStrategy.REPLACE` (causes row deletion + re-insertion + B-tree rebalancing). Other DAOs use native Room `@Upsert` (SQLite 3.35+ `ON CONFLICT DO UPDATE`) or `IGNORE` correctly. |
| 5 | WAL settings, synchronous, cache size, journal_size_limit | **Problem** | Finding **OPT-O06-04** (Desktop has NO PRAGMAs configured; runs non-WAL, synchronous=FULL, 0ms busy timeout) and Finding **OPT-O06-06** (Missing `journal_size_limit` on client databases). |
| 6 | SQLCipher KDF iterations & per-page overhead | **Problem** | Finding **OPT-O06-10**. Default 256,000 PBKDF2 iterations causes 250–700 ms cold-start delay on ARM handsets. `PRAGMA mmap_size` is an ineffective no-op under SQLCipher. |
| 7 | Migration cost on large databases (v1..v20) | **No problem** | All schema steps in `FlashSchemaSteps.kt` (v1..v20) use exclusively `ALTER TABLE ADD COLUMN` (O(1) catalog update in SQLite) and `CREATE TABLE / INDEX`. No temporary table copies or full table rewrites exist. |
| 8 | VACUUM and auto_vacuum policy | **Problem** | Finding **OPT-O06-06**. Neither Android nor Desktop enables `auto_vacuum = INCREMENTAL` or runs periodic `VACUUM`. Deleted message, chunk, and tombstone pages permanently fragment and bloat disk storage. Server correctly configures incremental vacuum. |
| 9 | BLOB columns inside hot rows | **Problem** | Finding **OPT-O06-08**. `SwarmContentEntity` inlines `manifest BLOB` (up to 131 KB) and `bits BLOB`. `OrgDirectoryMemberEntity` inlines `statementBytes BLOB`. List queries load massive BLOBs from overflow pages. |
| 10 | JSON columns parsed on every row read vs structured columns | **Problem** (Minor) | `ReactionEntity.reactorIdsJson` is decoded from JSON on every emission of `messageContentFlow` for every bubble reaction in the conversation thread (`RealFlashChatRepository:1538`). |
| 11 | FTS5 deferral (ADR-218) cost model at 10k/100k messages | **Problem** | Finding **OPT-O06-03**. Measured: At 100k messages, LIKE '%x%' scan takes 88.69 ms on desktop (~800 ms on phone) vs 0.17 ms with FTS5 (**514x speedup**). Cliff reached at N >= 10,000 messages. |

---

## 4. Detailed Optimization Findings

### OPT-O06-01 — Correlated Scalar Subquery & Full Index Scan in `observeUnreadCounts`
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** T, C, S, M, B   **Runtime:** Android, Desktop
- **Component:** `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:190-198`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  `MessageDao.observeUnreadCounts(selfId)` calculates unread counts for all conversations via a SQL query executed as a reactive Room `Flow`. The query contains a correlated scalar subquery in its `WHERE` predicate: `m.sentAt > (SELECT sentAt FROM messages WHERE localId = c.lastReadCursor)`. Because this subquery is evaluated for every non-tombstoned inbound message row across all conversations, SQLite performs a full index scan of `index_messages_conversationId_sentAt_localId` and repeatedly executes a scalar B-tree search on `sqlite_autoindex_messages_1` for each candidate row. Crucially, because it is an observed `Flow`, Room's `InvalidationTracker` triggers this entire calculation whenever **any write occurs on the `messages` table** (such as an incoming message in any chat, an attachment progress update, or a delivery receipt).
- **Cost model:**  
  - *Frequency:* Triggered on every write to `messages` (1–50 Hz during active messaging/transfers).
  - *Unit cost (Measured on quiet JVM benchmark, commit `4dcc7fad` schema, N=100,000 messages, 20 conversations):* **697.29 ms** per query execution! (10,000 messages: 9.89 ms; 1,000 messages: 1.08 ms).
  - *Multiplier:* Multiplied by every incoming message, delivery receipt, and attachment path stamp across the entire app.
- **User-visible effect:**  
  Severe UI frame drops and sluggishness on the chat list screen; high CPU utilization; background battery drain during active group conversations. On Android devices with SQLCipher encryption, repeated 700 ms queries completely saturate the `Dispatchers.IO` thread pool.
- **Expected gain:**  
  Rewriting the correlated subquery into a pre-filtered `LEFT JOIN` or maintaining a denormalized `unreadCount` column on `conversations` eliminates the per-row scalar subquery, cutting execution time from **697 ms down to < 5 ms** (a >99% reduction in query latency and CPU time).
- **Proposed change:**  
  Replace the correlated subquery with a join on `lastReadCursor`, or denormalize `unreadCount INTEGER NOT NULL DEFAULT 0` onto `ConversationEntity`. At minimum, rewrite the query to join the cursor message:
  ```sql
  SELECT m.conversationId AS conversationId, COUNT(*) AS unread
  FROM conversations c
  LEFT JOIN messages cur ON cur.localId = c.lastReadCursor
  JOIN messages m ON m.conversationId = c.id
  WHERE m.senderId != :selfId AND m.deletedAt IS NULL
    AND (c.lastReadCursor IS NULL OR m.sentAt > cur.sentAt)
  GROUP BY m.conversationId
  ```
- **Invariants touched (5.3):** Durability and ordering preserved; unread count calculation semantics remain identical.
- **Risk / effort:** Low risk; S effort (single DAO query rewrite).
- **Prove it:**  
  Run `python docs/audit/optimization-2026-10-23/bench/db_benchmark.py` before and after query rewrite. Pass threshold: query execution time for 100k messages drops below 10 ms.
- **Verifier result:** Survived. Confirmed by `EXPLAIN QUERY PLAN` (`CORRELATED SCALAR SUBQUERY` executed per row in detail line) and measured benchmark on synthetic dataset.
- **Related:** OPT-O06-02, OPT-O04-01, ADR-218.

---

### OPT-O06-02 — Full Table Scan in `observeLatestPreviews` Re-executed on Every Message Write
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** T, C, S   **Runtime:** Android, Desktop
- **Component:** `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:206-210`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  `MessageDao.observeLatestPreviews()` executes:
  `SELECT conversationId AS conversationId, text AS previewText, MAX(sentAt) AS sentAt FROM messages WHERE deletedAt IS NULL GROUP BY conversationId`.
  Because `MAX(sentAt)` with bare-column selection requires grouping across all conversations, SQLite must scan every single non-tombstoned message in the database. EXPLAIN QUERY PLAN confirms: `SCAN messages USING INDEX index_messages_conversationId_sentAt_localId`. This query is observed as a `Flow` on the chat list screen (`RealFlashChatRepository:1179`), re-executing in full on **every single message write, status update, or attachment progress stamp in any conversation**.
- **Cost model:**  
  - *Frequency:* Every write to `messages` (1–50 Hz).
  - *Unit cost (Measured on quiet benchmark, N=100,000 messages):* **573.64 ms** per execution (10k messages: 4.54 ms; 1k messages: 0.51 ms).
  - *Compounding:* When combined with `observeUnreadCounts` (697 ms), a single message write locks SQLite I/O for **1,270 ms** (1.27 seconds)!
- **User-visible effect:**  
  Chat list freezes or stutters when receiving messages in the background; noticeable delay between message arrival and preview update.
- **Expected gain:**  
  Denormalizing `lastMessageText` and `lastMessageSentAt` directly onto `conversations` (updated on message insert/tombstone) completely eliminates this 573 ms query, reducing preview observation cost to a simple `SELECT` on `conversations` (< 1 ms).
- **Proposed change:**  
  Add `lastMessageText` and `lastMessageSentAt` columns to `ConversationEntity` in a future schema bump, updated atomically when composing or receiving a message. In the interim, debounce or conflate `observeLatestPreviews` emissions in `RealFlashChatRepository`.
- **Invariants touched (5.3):** Ordering and display invariants preserved.
- **Risk / effort:** Medium risk; M effort (schema bump + repository update).
- **Prove it:**  
  Run `db_benchmark.py` comparing `GROUP BY` preview query vs denormalized read. Pass threshold: < 2 ms on 100k messages.
- **Verifier result:** Survived. Verified via EXPLAIN QUERY PLAN and measured benchmark execution times.
- **Related:** OPT-O06-01, OPT-O04-01, EXP-010.

---

### OPT-O06-03 — FTS5 Deferral Cliff: `searchMessages` LIKE '%x%' Full Table Scan at Scale
- **Impact rank:** High        **Evidence:** MEASURED
- **Dimension(s):** T, C, S, B   **Runtime:** Android, Desktop
- **Component:** `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:241-248`, `docs/decisions.md:6381` (ADR-218)
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  ADR-218 deferred FTS5 adoption in favor of a substring `LIKE '%query%'` scan across `text` and `attachmentName`. Because the query uses a leading wildcard (`%query%`), SQLite cannot use B-tree index seeks and must scan the entire `messages` table. When the search query is a common word, `LIMIT 100` allows an early exit. However, when the search query is rare, specific (e.g. an order ID or file hash), or a typo (zero matches), **SQLite must scan and evaluate every single message row in the database**. On Android with SQLCipher, this requires reading and decrypting thousands of 4 KB pages from disk.
- **Cost model:**  
  - *Frequency:* 2–5 queries per search interaction (triggered per keystroke with search UI debounce).
  - *Unit cost (Measured on quiet JVM benchmark, commit `4dcc7fad` schema):*
    - At N = 1,000 messages (0.62 MB): LIKE scan = 0.68 ms | FTS5 = 0.12 ms (5.5x speedup).
    - At N = 10,000 messages (2.62 MB): LIKE scan = **7.20 ms** | FTS5 = **0.13 ms** (56x speedup).
    - At N = 100,000 messages (22.78 MB): LIKE scan = **88.69 ms** | FTS5 = **0.17 ms** (**514x speedup**)!
  - *Mobile translation (PLAUSIBLE):* On an ARM Cortex-A53 device (Infinix X6882B) with SQLCipher page decryption, scanning 23 MB (5,700 encrypted pages) takes **600–1,200 ms of 100% CPU time**.
- **User-visible effect:**  
  At >10,000 messages, searching for a non-matching word or rare phrase freezes the search UI, drops typing frames, and produces perceptible search lag. At 100,000 messages, searching causes ANR risk if not offloaded, and drains battery through continuous page decryption.
- **Expected gain:**  
  Migrating to an FTS5 contentless/external content table yields a **50x to 500x speedup** on search misses and rare terms (from 88 ms down to 0.17 ms), capping search query latency at < 1 ms regardless of message history size.
- **Proposed change:**  
  Revisit ADR-218: Introduce `messages_fts` virtual table using FTS5 with unicode61 tokenizer (`content='messages'`, `content_rowid='rowid'`) via Room `@Fts4`/`@Fts5` or explicit migration step. Update `MessageDao.searchMessages` to query FTS5 with `MATCH`.
- **Invariants touched (5.3):** Preserves search functionality and attachment name matching; requires schema bump (v21).
- **Risk / effort:** Medium risk; M effort (FTS5 migration, tokenizer selection, test suite).
- **Prove it:**  
  Execute `db_benchmark.py`. Compare search latency of rare term across 100k messages. Pass threshold: < 5 ms query latency.
- **Verifier result:** Survived. Confirmed by synthetic benchmark across 1k, 10k, and 100k messages with both early-exit and full-scan terms.
- **Related:** ADR-218, OPT-O04-02.

---

### OPT-O06-04 — Missing PRAGMA Configuration on Desktop (`JvmFlashDatabaseOpener`)
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, S, C, D   **Runtime:** Desktop
- **Component:** `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt:47-79`, `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherSQLiteDriver.kt:65`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  `FlashDatabaseOpener.kt` on Android configures critical SQLite performance pragmas in `RoomDatabase.Callback.onOpen`: `synchronous = NORMAL`, `temp_store = MEMORY`, `wal_autocheckpoint = 250`, and bounded `cache_size`. However, `JvmFlashDatabaseOpener.kt` on Desktop constructs the database with `Room.databaseBuilder` and **attaches NO callbacks, NO open pragmas, and NO connection initialization properties** other than `withKey(key)`.  
  As a result, Desktop SQLite operates under driver defaults:
  1. `journal_mode` may fall back to `DELETE` rollback journal instead of `WAL`.
  2. `synchronous` defaults to `FULL`, causing a synchronous blocking disk flush (`fsync`) on **every single transaction commit**!
  3. `busy_timeout` defaults to `0`, causing immediate `SQLITE_BUSY` crashes if concurrent coroutines access the database during write transactions.
  4. Page cache size is unbound/unoptimized.
- **Cost model:**  
  - *Frequency:* Every write transaction on desktop (message send, receipt, chunk mark, settings update).
  - *Unit cost:* `fsync` with `synchronous=FULL` takes 5–20 ms per commit on consumer SSDs/HDDs, compared to < 0.2 ms with `synchronous=NORMAL` in WAL mode.
  - *Multiplier:* Multiplied by every chunk received or message sent on Desktop.
- **User-visible effect:**  
  Desktop file transfers experience reduced maximum disk write throughput; message sending and database operations block noticeably; intermittent `SQLITE_BUSY` exceptions during concurrent operations.
- **Expected gain:**  
  Configuring `journal_mode = WAL`, `synchronous = NORMAL`, and `busy_timeout = 5000` on Desktop eliminates blocking `fsync` on every transaction, boosting database write throughput by **5x to 10x** and preventing `SQLITE_BUSY` lock failures.
- **Proposed change:**  
  In `JvmFlashDatabaseOpener.openFlashDatabase`, add an `onOpen` callback or pass PRAGMAs in `SQLiteMCConfig.Builder`:
  ```kotlin
  val properties = SQLiteMCConfig.Builder()
      .withKey(key)
      .withPragma("journal_mode", "WAL")
      .withPragma("synchronous", "NORMAL")
      .withPragma("busy_timeout", "5000")
      .withPragma("temp_store", "MEMORY")
      .build().toProperties()
  ```
- **Invariants touched (5.3):** Preserves durability (WAL with NORMAL is safe against application and OS crashes).
- **Risk / effort:** Low risk; S effort (5 lines in opener).
- **Prove it:**  
  Run a benchmark inserting 500 individual message transactions on Desktop before and after PRAGMA configuration. Pass threshold: total write time drops from >2,000 ms to < 200 ms.
- **Verifier result:** Survived. Code inspection of `JvmFlashDatabaseOpener.kt` and `JdbcCipherSQLiteDriver.kt` confirms complete absence of PRAGMAs.
- **Related:** OPT-O02-06, ADR-003.

---

### OPT-O06-05 — Missing Indexes on Top DAO Queries
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC & MEASURED
- **Dimension(s):** T, S, C   **Runtime:** Android, Desktop
- **Component:**  
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/OutboxEntity.kt:11`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/HubFileEntity.kt:17`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/ConversationEntity.kt:10`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/TransferEntity.kt:14`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/SwarmContentEntity.kt:14`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  Our `EXPLAIN QUERY PLAN` audit revealed that several frequently queried tables completely lack secondary indexes on their ordering and filtering columns, forcing SQLite to execute a `SCAN table` followed by `USE TEMP B-TREE FOR ORDER BY`:
  1. `OutboxDao.dueForDelivery`: `SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit` -> Full table scan + temp B-tree sort on every drain pass because `nextAttemptAt` has no index.
  2. `HubFileDao.observeRecent`: `SELECT * FROM hub_file ORDER BY createdAtMs DESC LIMIT :limit` -> Full table scan + temp B-tree sort on every progress tick.
  3. `ConversationDao.observeAll`: `SELECT * FROM conversations ORDER BY pinned DESC, sortOrder DESC` -> Full table scan + temp B-tree sort on every chat list display.
  4. `TransferDao.restorable`: `SELECT * FROM transfers WHERE direction != '' AND dismissed = 0 AND updatedAtMs >= :sinceMs ORDER BY updatedAtMs DESC` -> Full table scan + temp B-tree sort.
  5. `SwarmDao.loadAllContent`: `SELECT * FROM swarm_content ORDER BY createdAtMs DESC` -> Full table scan + temp B-tree sort.
- **Cost model:**  
  - *Frequency:* Outbox drains every 1–5s; HubFile observes on every progress update; Conversations observe on every message.
  - *Unit cost:* Table scan + Temp B-tree creation costs 0.5–5 ms per query execution, scaling linearly with table size.
- **User-visible effect:**  
  Unnecessary CPU churn and disk I/O; delayed outbox drains and UI observation latency.
- **Expected gain:**  
  Adding targeted indexes converts all 5 queries from `SCAN + TEMP B-TREE` into direct index range seeks (`SEARCH table USING INDEX`), reducing query execution time to < 0.1 ms and eliminating temp B-tree allocations.
- **Proposed change:**  
  Add indexes in schema step 21:
  - `Index("nextAttemptAt")` on `outbox`.
  - `Index("createdAtMs")` on `hub_file`.
  - `Index(value = ["pinned", "sortOrder"])` on `conversations`.
  - `Index("updatedAtMs")` on `transfers`.
  - `Index("createdAtMs")` on `swarm_content`.
- **Invariants touched (5.3):** None; read performance optimization only.
- **Risk / effort:** Low risk; S effort (add indexes to `@Entity` definitions and schema step).
- **Prove it:**  
  Run `db_benchmark.py` EXPLAIN QUERY PLAN before and after. Verify `SCAN` and `USE TEMP B-TREE` disappear.
- **Verifier result:** Survived. Directly verified by `db_benchmark.py` EXPLAIN output.
- **Related:** OPT-O06-01, OPT-O01-08.

---

### OPT-O06-06 — Missing `journal_size_limit` and Unchecked Freelist Fragmentation on Client Databases
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S   **Runtime:** Android, Desktop
- **Component:** `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt:105-129`, `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt:47`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  In SQLite WAL mode, when transactions commit large volumes of data (e.g. bulk org directory sync of 20,000 members, schema migrations, or large batch deletes of transfers via `purgeFinishedChunks`), the `-wal` file grows to accommodate the transaction frames. SQLite automatically truncates the `-wal` file during checkpoint only if `PRAGMA journal_size_limit` is explicitly configured. Without this pragma, the WAL file retains its peak allocated size on disk indefinitely.  
  Furthermore, unlike `ServerDatabase.kt` which explicitly sets `auto_vacuum = INCREMENTAL` and runs `incrementalVacuum`, the client database leaves `auto_vacuum = NONE`. When messages, attachments, or transfer chunks are deleted, their pages are placed on the freelist and the database file never shrinks.
- **Cost model:**  
  - *Frequency:* Continuous operation over weeks of usage.
  - *Storage impact:* 20,000 member sync or 50,000 chunk transfers can expand the WAL file to 20–50 MB. On low-end phones (e.g. 32 GB flash with limited free space), this storage is permanently retained until the database is closed or checkpointed with truncate.
- **User-visible effect:**  
  App storage footprint grows continually and never reclaims disk space after deletions, leading to "Storage almost full" warnings on budget devices.
- **Expected gain:**  
  Setting `PRAGMA journal_size_limit = 4194304` (4 MB) guarantees that the WAL file automatically truncates to <= 4 MB after checkpoints. Enabling `PRAGMA auto_vacuum = INCREMENTAL` during initial DB creation enables space reclamation.
- **Proposed change:**  
  Add `"PRAGMA journal_size_limit = 4194304;"` (4 MiB) to `applyPragmas` in `FlashDatabaseOpener.kt` and `JvmFlashDatabaseOpener.kt`.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort (1 line).
- **Prove it:**  
  Execute a test inserting and deleting 10,000 rows. Verify that after checkpoint, the `-wal` file size is <= 4 MB.
- **Verifier result:** Survived. Verified absence in both openers.
- **Related:** OPT-O02-06, EXP-014.

---

### OPT-O06-07 — Split-Transaction Write Amplification per Confirmed Chunk Batch
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S, C, T   **Runtime:** Android, Desktop
- **Component:**  
  - `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:586-594`
  - `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/RoomTransferStore.kt:101-114`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  During file transfers, when incoming chunk confirmations are processed, `RealFlashTransferRepository` persists the progress:
  ```kotlin
  store?.markChunksDone(transferId, fresh)
  if (progress.bytesDone != persistedBytesDone) {
      store?.setBytesDone(transferId, progress.bytesDone)
  }
  ```
  `store.markChunksDone` calls `transferChunkDao.insertAll(...)`, which Room executes in its own standalone transaction.  
  Immediately following that, `store.setBytesDone` calls `transferDao.setBytesDone(...)`, which Room executes in a **second, separate transaction**.  
  This doubles the number of SQLite transaction commits, WAL frame headers, and lock acquisitions on the transfer hot path (10–100 times per second).
- **Cost model:**  
  - *Frequency:* 10 to 100 Hz during active file transfers.
  - *Unit cost:* 2 transactions vs 1 transaction. For a 1 GB file transferred in 4,000 chunks (e.g. at 256 KB chunk size) batched every 4 chunks (1,000 progress events), this produces 2,000 independent SQLite write transactions instead of 1,000.
- **User-visible effect:**  
  Increased SQLite lock contention and CPU wake-ups; potential throughput degradation on slower eMMC/flash storage devices.
- **Expected gain:**  
  Folding `markChunksDone` and `setBytesDone` into a single atomic transaction halves the SQLite commit count and eliminates redundant WAL commits.
- **Proposed change:**  
  Update `TransferStore` port (ADR-024) to add `recordProgress(transferId: String, chunkIndexes: List<Int>, bytesDone: Long)` and implement it in `RoomTransferStore` using `@Transaction` or `database.useWriterConnection`:
  ```kotlin
  db.withTransaction {
      transferChunkDao.insertAll(entities)
      transferDao.setBytesDone(transferId, bytesDone)
  }
  ```
- **Invariants touched (5.3):** Preserves transfer resume durability and ordering.
- **Risk / effort:** Low risk; S effort (port method addition).
- **Prove it:**  
  Trace SQLite transaction commits (`BEGIN` / `COMMIT`) during a 50 MB transfer test. Pass threshold: 50% fewer commits.
- **Verifier result:** Survived. Documented in `logs/handoff.md` and `logs/progress.md` as an open optimization awaiting ADR-024 port change.
- **Related:** EXP-008, EXP-009, ADR-024.

---

### OPT-O06-08 — Heavy BLOB Inlining in `SwarmContentEntity` and `OrgDirectoryMemberEntity`
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M, S, C   **Runtime:** Android, Desktop
- **Component:**  
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/SwarmContentEntity.kt:32-35`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/SwarmDao.kt:27-31`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/OrgDirectoryMemberEntity.kt:26`
  - `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/OrgDirectoryDao.kt:36-37`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  1. `SwarmContentEntity` inlines `manifest: ByteArray?` (which stores 32-byte SHA-256 hashes for all pieces; up to 131 KB for a 4 GB file) and `bits: ByteArray`. In SQLite, rows larger than ~1 KB spill onto overflow pages. When `SwarmDao.loadAllContent()` or `loadContentForGroup()` executes `SELECT *`, SQLite traverses overflow chains to pull these 131 KB BLOBs into memory for every content row, even when the UI only displays the transfer title, role, and percentage.
  2. `OrgDirectoryMemberEntity` inlines `statementBytes: ByteArray` (~300–500 bytes per member). When `OrgDirectoryDao.listAllMembers()` runs to build the directory snapshot, it fetches all statement BLOBs across all 5,000–20,000 members, generating 3–10 MB of transient byte array allocations.
- **Cost model:**  
  - *Swarm:* 10 swarm transfers with 131 KB manifests = 1.3 MB of overflow page reads on every `loadAllContent()`.
  - *Directory:* 20,000 members x 400 bytes statement BLOB = 8 MB of heap churn on every directory refresh.
- **User-visible effect:**  
  Increased memory spike during directory refresh and swarm UI navigation; higher GC pause times on 2 GB RAM handsets.
- **Expected gain:**  
  Using lightweight projections (e.g. `SwarmContentSummary` without `manifest` and `bits`; `OrgMemberHeader` without `statementBytes`) avoids loading BLOB columns from overflow pages during list displays.
- **Proposed change:**  
  Define projection data classes for list queries in `SwarmDao` and `OrgDirectoryDao`. Fetch `manifest` and `statementBytes` only when opening a specific content item or verifying a cryptographic proof.
- **Invariants touched (5.3):** None; proof verification fetches the full row when needed.
- **Risk / effort:** Low risk; S effort.
- **Prove it:**  
  Measure heap allocations with `ThreadMXBean.getThreadAllocatedBytes()` when calling `listAllMembers()` vs `listMemberSummaries()`. Pass threshold: >60% fewer bytes allocated.
- **Verifier result:** Survived. Verified entity schemas and DAO queries.
- **Related:** OPT-O02-03, EXP-022.

---

### OPT-O06-09 — Ineffective Index in `OrgDirectoryDao.searchMembers` Due to Leading Wildcard `OR`
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC & MEASURED
- **Dimension(s):** T, C   **Runtime:** Android, Desktop
- **Component:** `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/OrgDirectoryDao.kt:30-31`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:**  
  `OrgDirectoryDao.searchMembers` is defined as:
  `SELECT * FROM org_directory_member WHERE displayNameFolded LIKE :prefix || '%' OR displayNameFolded LIKE '% ' || :prefix || '%' ORDER BY displayName ASC LIMIT :limit`.  
  Although `index_org_directory_member_displayNameFolded` exists, the second condition (`OR displayNameFolded LIKE '% ' || ...`) has a **leading wildcard**. In SQLite, an `OR` expression where one branch cannot use an index forces the query planner to abandon index seeks entirely and perform a **full table scan of all directory members**, followed by a temporary B-tree sort (`USE TEMP B-TREE FOR ORDER BY`).
- **Cost model:**  
  - *Frequency:* Every keystroke in the directory member picker.
  - *Unit cost:* Scans all 5,000 to 20,000 directory member rows and sorts the results in a temp B-tree. On SQLite, scanning 20,000 rows takes 10–25 ms per keystroke.
- **User-visible effect:**  
  Laggy typing response in organization member search dialog.
- **Expected gain:**  
  Splitting the query into a `UNION` of two queries (the first using the prefix index seek, the second searching word boundaries), or using FTS5 for directory names, enables index usage and reduces search latency from 20 ms to < 1 ms.
- **Proposed change:**  
  Rewrite query using `UNION` so SQLite can use the index for the prefix branch:
  ```sql
  SELECT * FROM (
      SELECT * FROM org_directory_member WHERE displayNameFolded LIKE :prefix || '%'
      UNION
      SELECT * FROM org_directory_member WHERE displayNameFolded LIKE '% ' || :prefix || '%'
  ) ORDER BY displayName ASC LIMIT :limit
  ```
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:**  
  Run `EXPLAIN QUERY PLAN` on the `UNION` form. Verify SQLite utilizes `index_org_directory_member_displayNameFolded`.
- **Verifier result:** Survived. EXPLAIN QUERY PLAN in benchmark confirms `SCAN org_directory_member` on original query.
- **Related:** OPT-O02-03, OPT-O06-05.

---

### OPT-O06-10 — SQLCipher Default 256k PBKDF2 Iterations and Ineffective `mmap_size` on Android Startup
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, C   **Runtime:** Android
- **Component:** `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt:51, 111`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:**  
  In `FlashDatabaseOpener.openEncrypted`:
  1. `SupportOpenHelperFactory(passphraseProvider.passphrase())` is called without specifying a `SQLiteDatabaseHook`. SQLCipher 4 defaults to **256,000 PBKDF2 iterations** with HMAC-SHA512. On budget Android devices (ARM Cortex-A53 cores, e.g. Infinix X6882B), executing 256,000 iterations blocks the calling thread for **250 ms to 700 ms** on cold start just to derive the key before any SQL query can run.
  2. `applyPragmas` sets `"PRAGMA mmap_size = 2097152;"`. However, SQLCipher completely disables memory-mapped I/O because it must intercept page read/write hooks to perform AES-256-CBC decryption/encryption. Setting `mmap_size` is an ineffective no-op that executes on every open.
- **Cost model:**  
  - *Frequency:* Once per application process cold start / database open.
  - *Unit cost:* 250–700 ms of CPU time on budget hardware.
- **User-visible effect:**  
  Contributes to cold-start startup delay before first chat screen is displayed.
- **Expected gain:**  
  Evaluating Argon2 or adjusting PBKDF2 iterations within security guidelines (or caching the derived raw master key in memory via `PRAGMA raw_key` if acceptable under project security posture) would save 200–500 ms on cold start. Removing dead `mmap_size` avoids confusing future engineers.
- **Proposed change:**  
  Remove `"PRAGMA mmap_size = 2097152;"` from `applyPragmas`. Document KDF iteration rationale in `docs/security.md`.
- **Invariants touched (5.3):** Invariant 1 (Security). Any KDF adjustment must maintain cryptographic strength and backward compatibility with existing databases.
- **Risk / effort:** Low risk; S effort for pragma cleanup; owner decision required for any key derivation parameter change.
- **Prove it:**  
  Measure wall-clock time of `openEncrypted` on real device.
- **Verifier result:** Survived. Verified `SupportOpenHelperFactory` invocation and SQLCipher mmap incompatibility.
- **Related:** OPT-O01-08, ADR-002.

---

## 5. "No Problem, Because..." Notes

1. **Schema Migration Execution Cost (Steps v1..v20):**
   - *No problem, because:* All migrations from version 1 to 20 in `FlashSchemaSteps.kt` exclusively use `ALTER TABLE ... ADD COLUMN` and `CREATE TABLE / INDEX IF NOT EXISTS`. In SQLite 3.35+, adding a column with a constant default value is an O(1) metadata catalog update that does not rewrite or copy table rows. There are zero temporary table copies or table rebuilds across all 20 versions. Migration overhead on startup was measured at < 50 ms even on a 100,000-message database.

2. **Keyset Pagination Performance (`historyBefore` & `historyAfter`):**
   - *No problem, because:* Keyset seek queries (`WHERE conversationId = :c AND (sentAt < :s OR (sentAt = :s AND localId < :l)) ORDER BY sentAt DESC, localId DESC LIMIT :limit`) are perfectly matched by the composite index `index_messages_conversationId_sentAt_localId`. `EXPLAIN QUERY PLAN` confirms SQLite performs a direct index range search without any temporary B-tree filesort (`SEARCH messages USING INDEX index_messages_conversationId_sentAt_localId`).

3. **Transfer Chunk Done-Set Index Coverage (`doneChunks`):**
   - *No problem, because:* `transfer_chunks` uses composite primary key `(transferId, chunkIndex)`. Point lookups and range queries filtering by `transferId` use the primary key B-tree directly without requiring secondary index overhead.

4. **Server Storage Performance & WAL Management (`ServerDatabase.kt`):**
   - *No problem, because:* Unlike the client desktop opener, `ServerDatabase.kt` properly configures `journal_mode = WAL`, `synchronous = NORMAL`, `busy_timeout = 5000`, and `auto_vacuum = INCREMENTAL` via HikariCP connection properties. It also implements an active `incrementalVacuum` maintenance loop to prevent database fragmentation.

---

## 6. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk | Verifier Result | Status |
|---|---|---|---|---|---|---|---|---|---|
| OPT-O06-01 | Correlated Scalar Subquery & Full Index Scan in `observeUnreadCounts` (697 ms at 100k messages) | High | T, C, S, M, B | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-02 | Full Table Scan in `observeLatestPreviews` (573 ms at 100k messages) Re-executed on Every Message Write | High | T, C, S | MEASURED | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O06-03 | FTS5 Deferral Cliff: `searchMessages` LIKE '%x%' Full Table Scan Costs 88 ms (PC) / ~800 ms (Phone) at 100k Messages | High | T, C, S, B | MEASURED | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O06-04 | Missing PRAGMA Configuration on Desktop (`JvmFlashDatabaseOpener`) - Runs in Non-WAL Synchronous=FULL Mode | High | T, S, C, D | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O06-05 | Missing Indexes on 5 Top DAO Queries (`outbox.nextAttemptAt`, `hub_file.createdAtMs`, `conversations(pinned, sortOrder)`, etc.) | Medium | T, S, C | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-06 | Missing `journal_size_limit` and Unchecked Freelist Fragmentation on Client Databases | Medium | S | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-07 | Split-Transaction Write Amplification per Confirmed Chunk Batch (`markChunksDone` + `setBytesDone`) | Medium | S, C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-08 | Heavy BLOB Inlining in `SwarmContentEntity` (131 KB manifest) and `OrgDirectoryMemberEntity` | Medium | M, S, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-09 | Ineffective Index in `OrgDirectoryDao.searchMembers` Due to Leading Wildcard `OR` (20k Full Table Scan) | Medium | T, C | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-10 | SQLCipher Default 256k PBKDF2 Iterations and Ineffective `mmap_size` on Android Startup | Low-Medium | T, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
