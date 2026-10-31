# Current Handoff — Stream D (Data)

## Current branch
`agent/opt-data`

## Last verified build
All batches complete and verified green with:
- `:core:persistence:jvmTest` (85/85 passed)
- `:core:persistence:testAndroidHostTest` (passed)
- `:core:messaging:jvmTest` (passed)
- `:core:messaging:testAndroidHostTest` (688/688 passed)

## Current phase
Phase 3 — Integration Gate Verification & Final Delivery

## Completed Findings
- **Step 0**: `RealFlashChatRepositoryTest` timing flakiness eliminated (`ef66a56a`).
- **Batch 1**:
  - `O06-01`: `MessageDao.observeUnreadCounts` rewritten without correlated subquery.
  - `O06-05`: 5 missing secondary indexes (`outbox.nextAttemptAt`, `hub_file.createdAtMs`, `conversations(pinned, sortOrder)`, `transfers.updatedAtMs`, `swarm_content.createdAtMs`).
  - Schema Step 22: Room version bumped to 22 with migrations and schema json.
  - `O04-02`: Eliminated N+1 queries in chat-list combine via `GroupMemberDao.allActiveMembers()`.
  - `O25-04`: Cached `decodeReactorIds` regex.
  - `O02-06` + `O06-04`: Desktop opener PRAGMAs (WAL, NORMAL, cache_size=-4096, busy_timeout=5000) and MemoryGovernor trim hook.
  - `O23-05` + `O06-06` + `O06-10`: `auto_vacuum = INCREMENTAL`, `journal_size_limit = 4194304`, removed `mmap_size`.
- **Batch 2**:
  - `O06-02`: Conflate & distinct on `observeLatestPreviews`.
  - `O04-03`: Throttled receipts and reactions via `throttleLatest(100L)`.
  - `O04-04`: Batched conversation resolution in `searchChats` via `ConversationDao.getByIds`.
  - `O06-07` + `O07-03`: Single-transaction progress recording (`TransferDao.recordProgress`).
  - `O06-09`: `OrgDirectoryDao.searchMembers` index seek via `UNION`.
  - `O02-02`: Free closed conversation memory with `_conversationState.value = EMPTY_CONVERSATION`.
  - `O14-04`: Converted `hubDropped` stateIn to `SharingStarted.WhileSubscribed(5000)`.
  - `O14-05`: Monotonic `stateVersion` for O(1) StateFlow equality comparison.
  - `O28-05`: Configured Room query callback on Android opener.
- **Batch 3 & ADR-232**:
  - `O04-01`: History keyset paging with `countNewerThan` resolving `FlashBackStackState.targetMessageId`.
  - `O06-08`: BLOB inlining avoided via `SwarmContentSummary` and `OrgMemberSummary` projections.
  - `O25-09`: Collection pre-allocation in `searchChats`.
  - ADR-232: Confirmed LIKE retained for O06-03, PBKDF2 iterations retained for O06-10.

## Verification & Metrics
- `observeUnreadCounts` benchmark at 100k messages: 1,704 ms -> 1,018 ms.
- Test suites: 100% green across all persistence and messaging tests.
