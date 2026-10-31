# Experiments Log — Stream D (Data)
<!-- Reserved numbers: EXP-085 to EXP-089 -->

## EXP-085 — Baseline SQLite Query Performance & EXPLAIN QUERY PLAN (100k messages)

### Date
2026-10-30

### Environment
- Machine: 8 logical CPUs, Windows 11, SQLite via Python 3 / JVM
- Database: FlashDatabase schema v20 (prior to step 22 optimizations)
- Dataset: 1,000, 10,000, and 100,000 messages across 20 conversations

### Measured Baseline Results (db_benchmark.py)

#### 100,000 Messages (22.78 MB Database):
- `observeUnreadCounts` (correlated subquery): **1,428.46 ms**
- `observeLatestPreviews` (full scan + GROUP BY): **1,037.98 ms**
- `LIKE scan` (rare/miss, full scan): **204.01 ms**
- `LIKE scan` (common, early exit): **1.14 ms**
- `FTS5 match` (rare/miss): **0.12 ms**
- `FTS5 match` (common): **409.79 ms**

#### Missing Secondary Indexes (Full Scan + Temp B-Tree Sort):
1. `dueForDelivery (OutboxDao)`: `SCAN outbox | USE TEMP B-TREE FOR ORDER BY`
2. `observeRecent (HubFileDao)`: `SCAN hub_file | USE TEMP B-TREE FOR ORDER BY`
3. `observeAll (ConversationDao)`: `SCAN conversations | USE TEMP B-TREE FOR ORDER BY`
4. `restorable (TransferDao)`: `SCAN transfers | USE TEMP B-TREE FOR ORDER BY`
5. `loadAllContent (SwarmDao)`: `SCAN swarm_content | USE TEMP B-TREE FOR ORDER BY`
6. `searchMembers (OrgDirectoryDao)`: `SCAN org_directory_member | USE TEMP B-TREE FOR ORDER BY`

### Observation
High-frequency write paths invalidate Room tables and trigger `observeUnreadCounts` and `observeLatestPreviews`, burning over 2.4 seconds of CPU per event under 100k message scale.


## EXP-086 — Batch 1 Query Optimization & Index Verification (100k messages)

### Date
2026-10-30

### Environment
- Machine: 8 logical CPUs, Windows 11, SQLite via Python 3 / JVM
- Database: FlashDatabase schema v22 (with secondary indexes and rewritten queries)
- Dataset: 100,000 messages across 50 conversations

### Measured Results
- `observeUnreadCounts` (correlated subquery): 1,704.82 ms
- `observeUnreadCounts` (rewritten LEFT JOIN cur on localId): **1,018.22 ms** (1.7x faster)
- Missing secondary indexes on `outbox(nextAttemptAt)`, `hub_file(createdAtMs)`, `conversations(pinned, sortOrder)`, `transfers(updatedAtMs)`, `swarm_content(createdAtMs)` now perform index scans rather than full table scans with temp B-trees.
- Desktop opener now sets `synchronous = NORMAL`, `journal_mode = WAL`, `cache_size = -4096`, `busy_timeout = 5000`.
- MemoryGovernor trim listener invokes `PRAGMA shrink_memory` on Desktop when memory pressure occurs.
