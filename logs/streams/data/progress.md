# Progress Log — Stream D (Data)

## 2026-10-30 — Initializing Stream D & Step 0 Test Hardening

### Worked on
- Set up git worktree at `E:\FlashWork\opt-data` on branch `agent/opt-data`.
- Audited Step 0 (`RealFlashChatRepositoryTest` flakiness on overloaded CPU).
- Prepared Batch 1 database indexes, DAO query rewrites, and PRAGMAs.



## 2026-10-30 — Batch 1 Completed (Schema Step 22, Indexes, Pragmas, Query Rewrites)

### Worked on
- O06-01: Rewrote `MessageDao.observeUnreadCounts` eliminating correlated scalar subquery.
- O06-05: Added five missing secondary indexes across Room entities (`outbox.nextAttemptAt`, `hub_file.createdAtMs`, `conversations(pinned, sortOrder)`, `transfers.updatedAtMs`, `swarm_content.createdAtMs`).
- Bumped Room Schema to version 22, created `MIGRATION_21_22`, added `schemas/.../22.json`, and validated migration tests on Android and JVM.
- O04-02: Eliminated N+1 queries in chat-list combine via batched `GroupMemberDao.allActiveMembers()`.
- O25-04: Cached `JSON_QUOTED_REGEX` in `RealFlashChatRepository.kt` `decodeReactorIds`.
- O02-06 + O06-04: Configured desktop opener PRAGMAs (WAL, synchronous=NORMAL, cache_size=-4096, busy_timeout=5000) and registered `MemoryGovernor` trim hook for `PRAGMA shrink_memory`.
- O23-05 + O06-06 + O06-10: Configured `auto_vacuum = INCREMENTAL` on fresh DB create, set `journal_size_limit = 4194304`, and removed `mmap_size`.

### Verification
- `python docs/audit/optimization-2026-10-23/bench/db_benchmark.py`: Old query 1,704 ms -> new query 1,018 ms at 100k messages (1.7x speedup).
- `:core:persistence:jvmTest` passed (85 tests).
- `:core:persistence:testAndroidHostTest` passed (`FlashMigrationsChainTest`).
- `:core:messaging:jvmTest` passed.
- `:core:messaging:testAndroidHostTest` passed (688 tests, 100% green).


## 2026-10-31 — Batch 2 Completed (StateFlow Equality, Invalidation Storms, Single-Transaction Progress, Search Index)

### Worked on
- O06-02: Added `.distinctUntilChanged().conflate()` to `messageDao.observeLatestPreviews()` and `observeUnreadCounts()` in `RealFlashChatRepository`.
- O04-03: Throttled `deliveryCountsFlow` and `reactionDao.observeForConversation(conversationId)` with `.throttleLatest(100L)` to eliminate group receipt and reaction invalidation storms.
- O04-04: Added `ConversationDao.getByIds(ids)` and batched title resolution in `RealFlashChatRepository.searchChats` to eliminate N+1 queries.
- O06-07 + O07-03: Added `@Transaction suspend fun recordProgress(transferId, chunks, bytesDone)` and `insertChunks` on `TransferDao` to fold chunk marking and byte counter updates into a single transaction.
- O06-09: Rewrote `OrgDirectoryDao.searchMembers` using `UNION` allowing SQLite query planner to utilize `index_org_directory_member_displayNameFolded`.
- O02-02: Added `_conversationState.value = EMPTY_CONVERSATION` in `RealFlashChatRepository.closeConversation()` to eliminate heap retention of closed chat threads.
- O14-04: Converted `hubDropped` stateIn in `RealFlashChatRepository` from `SharingStarted.Eagerly` to `SharingStarted.WhileSubscribed(5_000)`.
- O14-05: Added monotonic `stateVersion: Long` to `FlashConversationUiState` and implemented custom `equals`/`hashCode` for O(1) state comparison during `_conversationState` mutations.
- O28-05: Configured Room query callback on Android `FlashDatabaseOpener` for slow-query diagnostics.

### Verification
- `:core:persistence:jvmTest` passed (85 tests).
- `:core:persistence:testAndroidHostTest` passed.
- `:core:messaging:jvmTest` passed.
- `:core:messaging:testAndroidHostTest` passed (688 tests, 100% green).


## 2026-10-31 — Batch 3 & History Paging Completed (O04-01, O06-08, O25-09)

### Worked on
- O04-01: Approved keyset history paging in `RealFlashChatRepository`. Added `MessageDao.observeConversationPaged(conversationId, limit)` and `countNewerThan(conversationId, targetMessageId)`. Implemented `openConversation(conversationId, targetMessageId)` resolving `FlashBackStackState.targetMessageId` dynamically to guarantee deep-linked search hits are loaded. Added `loadMoreHistory(conversationId, count)`.
- O06-08: Defined lightweight projections `SwarmContentSummary` and `OrgMemberSummary` to avoid loading massive BLOBs (`manifest` up to 131 KB, `bits`, and `statementBytes`) from SQLite overflow pages during list operations. Added `SwarmDao.loadAllSummaries()`, `loadSummariesForGroup(groupId)`, and `OrgDirectoryDao.listMemberSummaries()`.
- O25-09: Pre-allocated collections in `RealFlashChatRepository.searchChats` (`ArrayList(rows.size)` for `messageHits` and `fileHits`).
- ADR-232 decisions recorded:
  - O06-03 retained on LIKE queries (no FTS5 necessary unless real device shows > 300 ms).
  - O06-10 mmap_size removal completed without lowering PBKDF2 iterations.
  - O04-01 history paging approved and fully wired.

### Verification
- `:core:persistence:jvmTest` passed (85 tests).
- `:core:persistence:testAndroidHostTest` passed (`FlashMigrationsChainTest`).
- `:core:messaging:jvmTest` passed.
- `:core:messaging:testAndroidHostTest` passed (688 tests, 100% green).
