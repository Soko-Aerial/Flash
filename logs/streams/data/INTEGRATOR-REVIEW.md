# Stream D — integrator review (2026-10-31)

The stream's own logs (`progress.md`, `handoff.md`, `experiments.md`, `decisions-additions.md` in this
folder) were written before review. This file records what was **accepted into `dev`**, what was
**corrected**, and what was **not delivered**. Read this before trusting any before/after in the other
files.

Branch: `agent/opt-data` (4 commits, 28 files, `core/persistence` + `core/messaging` only) merged into
`dev` with `--no-ff` (`3b28e5be`); follow-up fixes in `f3154de0`. Base: `6f8fe180`.

Gates: gate 1 (12 tasks, forced with `--rerun-tasks --no-build-cache`) green, 153 tasks executed,
7 m 31 s; gate 2 run after the merge on the merged tree (see the plan's §10 row).

## 1. Accepted

| Finding | Change | Why it is safe |
|---|---|---|
| `O06-01` | `observeUnreadCounts` rewritten on a `LEFT JOIN` against the cursor row | Same result set as before (the left join keeps a conversation whose cursor message is gone); the planner can now seek `messages(conversationId, sentAt)` instead of running a correlated subquery per row. |
| `O06-05` | 5 secondary indexes + Room step 22 | Index names match Room's own derivation and are validated by both migration harnesses against the exported `22.json`. |
| `O04-02` | `allActiveMembers()` batched; `titleOf` prefetch in `searchChats` | Removes the per-group N+1 and the per-hit conversation read. |
| `O25-04` | memoised reactor-id regex | Pure. |
| `O02-06`/`O06-04` | desktop opener: WAL, `synchronous=NORMAL`, bounded page cache, `busy_timeout`, trim listener | Desktop was on rollback journal + `FULL`. |
| `O06-06` | `journal_size_limit = 4 MiB` | Clamps the WAL after checkpoints. |
| `O06-02` | `.distinctUntilChanged().conflate()` on the list-combine feeds | Latest value wins; no intermediate state is needed by a list. |
| `O04-03` | `throttleLatest(100)` on receipt/reaction feeds | `throttleLatest` still emits the final value after the window, so nothing is dropped. |
| `O04-04` | `getByIds` batch for search | One query instead of one per hit. |
| `O06-09` | `searchMembers` `UNION` (prefix branch + contains branch) | The prefix branch becomes an index seek; the contains branch still scans, exactly as before, so the query is never worse. |
| `O04-01` | paged history (`observeConversationPaged`, `loadMoreHistory`) + `countNewerThan` target window | The window is at least 100 rows and at least `count + 20`, so a deep link lands on the target; a stale target degrades to the newest page instead of an empty screen. |
| `O27-05`/`O25-09` | sizing and collection fixes | Pure. |
| `O06-07`/`O07-03` | single-transaction progress write | **Wired by the integrator**, see §2.1; the DAO had no caller on the branch. |
| `O28-05` | slow-statement telemetry | **Made real by the integrator**, see §2.2; the branch installed an empty callback. |
| `O23-05` | `auto_vacuum` | **Applied by the integrator**, see §2.3; `onCreate` alone is too late. |
| `O14-04` | `hubDropped` eager `stateIn` → `WhileSubscribed(5_000)` | Matches the ledger definition of the finding. The plan's wording pinned it on `sendWithTimeout`; that part stays as designed (blocking socket write) and the plan line is corrected. |
| `O14-05` | `equals` short-circuit on a monotonic `stateVersion` | Accepted **with** the `hashCode` fix in §2.4. |

## 2. Corrected by the integrator (`f3154de0`)

### 2.1 `O06-07`/`O07-03` — the single-transaction DAO had no caller

`TransferDao.recordProgress` and `insertChunks` were added, but `RoomTransferStore` still called
`markChunksDone` + `setBytesDone` separately, so nothing used the transaction and the finding's win was
zero. `TransferStore.recordProgress` is now a default method (old adapters keep working),
`RoomTransferStore` overrides it with the one transaction, and the send progress collector calls it.

### 2.2 `O28-05` — the query callback was installed empty

The callback hook ran on every statement and logged nothing. It now reports statements that took
≥ 100 ms, measured as the gap to the next statement on the dedicated single-thread query executor.

### 2.3 `O23-05` — `auto_vacuum` in `onCreate` never takes effect

Room creates the tables first and calls `onCreate` afterwards; `PRAGMA auto_vacuum` only applies while
the file still has no pages, so the recorded value stays pending until a `VACUUM` rewrites the file, and
nothing ran one. Two consequences: inert on every fresh file, and no `PRAGMA incremental_vacuum` anywhere
either, so even a converted file never reclaimed. `onCreate` now sets the pragma, a one-shot `VACUUM` in the
immediately following `onOpen` (a VACUUM cannot run inside the `onCreate` transaction) applies it, and the
memory-trim hook issues `incremental_vacuum` on both hosts.

Note for ADR-240: its item 4 says "on fresh database creation", which is what the code now does; a Room KMP
callback has no `onConfigure`, which is why the VACUUM detour exists.

### 2.4 `O14-05` — `hashCode` did not match `equals`

`equals` returns true for two states with the same non-zero `stateVersion` and otherwise falls back to
structure, but `hashCode` returned `stateVersion.hashCode()` whenever the version was non-zero — so an
unversioned (`0`) state and a versioned one that compare **equal** could hash apart. `hashCode` is a
documented constant; the states are compared by `StateFlow`, never used as map keys.

### 2.5 `O02-02` — the clear raced the cancelled collector

`closeConversation` cancelled the collector and assigned `EMPTY` in the same breath; a last in-flight
emission can still land after the cancel and re-publish the whole thread. It now clears again after the
cancelled job has joined, unless another chat was opened in the meantime.

### 2.6 `O06-08` — the "projections" still read the BLOBs

`listMemberSummaries` / `loadAllSummaries` / `loadSummariesForGroup` were **default bodies** that ran the
existing full `SELECT *` query and mapped the entities afterwards, so `statementBytes` and the up-to-131 KB
`manifest` were still loaded. They are real `@Query` projections now.

**Still open:** no caller uses them. The one candidate, `RoomSwarmStateStore.loadAll()`, needs `manifest`
(`toRecord()`), and `OrgPeerResolver`/`HubFacade` need `statementBytes`; a summary cannot serve either
without a second query. Recorded as `PERF-D03`.

## 3. Not delivered (recorded, not reverted)

- `O27-04` in-memory SQLite for client tests — **not attempted and not mentioned** in the stream's own logs.
  The migration harnesses already use file databases; `PERF-D04`.
- `EXP-086` measured `observeUnreadCounts` 1 704 ms → 1 018 ms (1.7×) on a harness, not the plan's
  "697 ms → 0.41 ms". Both numbers stand as measured; do not quote the audit ratio as achieved.
- `backlog-additions.md` was an empty header. The owed device/behaviour checks are `PERF-D01`/`PERF-D02`
  (written by the integrator) plus `PERF-D03`/`PERF-D04`.

## 4. To do before this is "verified"

1. `PERF-D01` — chat list and history paging on a device with a long thread; also the report that the list
   re-reads older rows (the pager grows the window and re-subscribes, so the UI must not jump).
2. `PERF-D02` — deep link from search into a 5 000-message thread, forwarded message and notification
   variants; the target must be on screen within the first frame set.
3. `PERF-D03` — wire a consumer for the summary projections (or delete them).
4. `O14-04`'s other half: confirm on a device that a timed-out `sendWithTimeout` does not corrupt a frame.
