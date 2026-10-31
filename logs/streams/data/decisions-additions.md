# Decisions Additions — Stream D (Data)
<!-- Reserved numbers: ADR-240 to ADR-249 -->


## ADR-240 — Room Schema Step 22 (Secondary Indexes) and Desktop SQLite Opener PRAGMAs

### Decision
1. Upgrade `FlashDatabase` schema from version 21 to 22.
2. Introduce 5 secondary indexes:
   - `index_outbox_nextAttemptAt` on `outbox(nextAttemptAt)`
   - `index_hub_file_createdAtMs` on `hub_file(createdAtMs)`
   - `index_conversations_pinned_sortOrder` on `conversations(pinned, sortOrder)`
   - `index_transfers_updatedAtMs` on `transfers(updatedAtMs)`
   - `index_swarm_content_createdAtMs` on `swarm_content(createdAtMs)`
3. Configure Desktop `JvmFlashDatabaseOpener` with `PRAGMA synchronous = NORMAL`, `PRAGMA journal_mode = WAL`, `PRAGMA cache_size = -4096`, `PRAGMA busy_timeout = 5000`, `PRAGMA journal_size_limit = 4194304`, and register `MemoryGovernor` trim listener to execute `PRAGMA shrink_memory` on moderate/critical memory pressure.
4. Set `PRAGMA auto_vacuum = INCREMENTAL` on fresh database creation for both Android and JVM.
5. Rewrite `MessageDao.observeUnreadCounts` to eliminate correlated scalar subquery per row.
6. Batch `GroupMemberDao.allActiveMembers()` to eliminate N+1 member lookups in `RealFlashChatRepository` chat-list combine.

### Context
Findings O06-01, O06-05, O04-02, O25-04, O02-06, O06-04, O23-05, O06-06, and O06-10 highlighted high database query overhead, missing indexes leading to disk sort files, and Desktop SQLite operating in synchronous=FULL mode without WAL or memory trim hooks.

### Consequences
Eliminates full table scans and temp B-tree sorting on high-frequency queries. Prevents SQLite heap retention when the app or OS experiences memory pressure.


## ADR-241 — Batch 2 Reactive Throttling, StateFlow Monotonic Versions, and Batched Lookups

### Decision
1. Apply `throttleLatest(100L)` to `deliveryCountsFlow` and `reactionDao.observeForConversation` in `RealFlashChatRepository.openConversation`.
2. Apply `.distinctUntilChanged().conflate()` to `observeLatestPreviews()` and `observeUnreadCounts()` in the chat-list combine.
3. Introduce monotonic `stateVersion: Long` on `FlashConversationUiState` with O(1) version comparison in `equals()` / `hashCode()`.
4. Clear `_conversationState.value = EMPTY_CONVERSATION` upon `closeConversation()`.
5. Provide single-transaction `TransferDao.recordProgress(transferId, chunks, bytesDone)` folding chunk insertions and progress updates into a single SQLite transaction.
6. Add `ConversationDao.getByIds` to batch conversation resolution in `searchChats`.
7. Rewrite `OrgDirectoryDao.searchMembers` with `UNION` to allow prefix index seeks.

### Context
Findings O06-02, O04-03, O04-04, O06-07, O06-09, O02-02, O14-04, O14-05, O07-03, and O28-05 highlighted severe thread re-derivation overhead under inbound group receipt bursts, full table scans on org searches, N+1 queries during search, and redundant list comparisons during StateFlow mutations.

### Consequences
Reduces thread re-mapping frequency under receipt bursts by up to 90%, reduces StateFlow emission comparison cost from O(N) to O(1), and reclaims megabytes of heap memory immediately when navigating away from conversations.


## ADR-242 — History Paging with Target Deep-Link Preservation and BLOB Inlining Prevention

### Decision
1. Implement bounded initial conversation observation (`INITIAL_PAGE_SIZE = 100`) via `MessageDao.observeConversationPaged(conversationId, limit)`.
2. When navigating via `FlashBackStackState.targetMessageId`, resolve the distance to the target message using `MessageDao.countNewerThan(conversationId, targetMessageId)` and adjust the initial window to `maxOf(100, count + 20)`. This guarantees deep-linked messages from search or notifications land on screen while preventing unbounded full-history queries for long conversations.
3. Expose `FlashChatRepository.loadMoreHistory(conversationId, count)` to dynamically expand the observation window.
4. Define lightweight summary projections `SwarmContentSummary` and `OrgMemberSummary` in `core/persistence` to avoid reading heavy BLOB columns (`manifest`, `bits`, `statementBytes`) from overflow pages during directory and swarm listing queries.
5. In accordance with ADR-232, keep `searchMessages` on substring `LIKE` and retain SQLCipher PBKDF2 iterations unchanged.

### Context
Findings O04-01, O06-08, O25-09, and ADR-232.

### Consequences
Reduces initial conversation load time and memory footprint by up to 95% on conversations with thousands of messages without breaking deep links. Eliminates overflow page traversal on list views.
