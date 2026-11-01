# Integrator review — Hub (H) stream, `agent/opt-hub`

Reviewer: integration session, 2026-11-01. Scope: `dev..7492e2d1` plus the repairs made below, verified against **the code,
the callers and the tests**, not against the stream's report. Every verdict was reached by reading the branch's commits and
the surrounding call sites; where the report cited a test as evidence I read the test and recorded what it asserts.

Test evidence produced on this branch tree after the repairs in §3:

| Command | Result |
|---|---|
| `:server:test` (full suite) | **664 tests, 0 failed** |
| `:core:enterprise:jvmTest` | **276 tests, 0 failed** |
| `:core:enterprise:testAndroidHostTest` | **165 tests, 0 failed** |
| `:core:hub-protocol:jvmTest` | BUILD SUCCESSFUL |
| `:core:persistence:jvmTest` | **85 tests, 0 failed** |
| `node --no-warnings --test server/admin-ui-tests/*.test.mjs` | 83 passed (run by the stream; unchanged by this review) |

The **first** full run of `:server:test` on this branch was 664 tests with 1 failure, and the failure was *not* the stream's:
`CachedMailboxStatsTest.aFailingRefreshKeepsTheLastNumbersAndIsRetried` is one of the two load-dependent flakes already
recorded in `docs/audit/optimization-2026-10-23/00-baseline.md`, in a class this branch does not touch
(`git diff dev..HEAD -- server/src/main/kotlin/.../observability/` is empty). It is fixed in the test in §3.5 rather than
left as a flake, because the finding it belongs to (`OPT-O27`, suite determinism) was this stream's own subject.

## 1. Verdict table

| # | Finding | Commit | Verdict |
|---|---|---|---|
| 1 | OPT-O23-01 open chunk handle, deferred fsync | `8f87ada6` | **KEEP** (durability cost documented in ADR-292) |
| 2 | OPT-O25-10 indexed delegation-revocation JOIN | `954e6d21` | **KEEP** |
| 3 | OPT-O18-01 precompressed gzip/deflate console assets | `a6c19325` | **KEEP, FIXED** (integrator: `Vary` was missing on 304) |
| 4 | OPT-O18-02 `modulepreload` for console modules | `835aa54a` | **KEEP** |
| 5 | OPT-O17-04 `ExitOnOutOfMemoryError`/G1GC in deploy templates | `931fc34f` | **KEEP** |
| 6 | OPT-O17-02 chunk write outside the SQLite writer | `0f8ac330` | **KEEP, FIXED** (integrator: as delivered it gave up the row⇒file invariant) |
| 7 | OPT-O25-02 O(M+D) department counts | `db3a529c` | **KEEP** |
| 8 | OPT-O25-06 O(1) group roster checks | `db3a529c` | **KEEP** |
| 9 | OPT-O25-09 pre-sized collections, allocation-free UTF-8 budget | `db3a529c` | **KEEP** |
| 10 | OPT-O16-01 bounded concurrent chunk pipeline (3 in flight) | `83700104` | **KEEP** |
| 11 | OPT-O16-03 suppress the presence poll while the relay is up | `0504a5f2` | **REJECTED AND REVERTED** (`8b622a9b`, ADR-298) |
| 12 | OPT-O02-03 member-summary projection in the peer resolver | `ccf238b7` | **KEEP** |
| 13 | OPT-O27-03 shared HTTP/WS client in the e2e harness | `56420cb0` | **KEEP** (test fixture only) |
| 14 | OPT-O17-03 one encoded frame per group fan-out | `8e55053b` | **KEEP** |
| 15 | OPT-O18-03 console devices page renders page 1 first | `64672286` | **KEEP** |
| 16 | OPT-O16-04 batched mailbox receipt acks | `ffccd137` | **KEEP, FIXED** — and **transport-only**: no product path sends a batch |
| 17 | OPT-O16-02 streaming member projection during directory sync | `5ea4e6bb` | **KEEP** |
| 18 | OPT-O02-07 / OPT-O17-01 deployment baseline (documents only) | `7492e2d1` | **KEEP** as documents; its numbers are estimates |

Ledger: 18 rows, **16 kept** (four of them repaired here), **1 rejected and reverted**, 1 kept only as a documented design,
none silently dropped. Unlike Wave 2, nothing in this stream contradicted a documented *product* invariant except `O16-03`
— but two of the changes, as committed, broke an invariant in the code they touched (`O17-02`, `O16-04`), and those are §3.1
and §3.2.

## 2. What was verified rather than believed

- **OPT-O16-03 really is unsafe, and the code says so in three places.** `EnterpriseHub.hubOnline` has exactly two writers
  and both are on the poll path; `HubPresence.kt`'s own KDoc states the hub serves presence over REST and **has no presence
  push**; `RelayFrame` has no presence frame (the tunnel carries chat, mail and receipts). With the relay up — the
  ordinary case — the change did not age the only presence source, it **stopped updating it**: the diff wraps the request in
  `else if (!isRelayOnline)`, so the poll never fetched while the tunnel was up and the 10-minute constant only bounded the
  relay-state re-read. Staleness was therefore the whole relay session, not ten minutes (this review first wrote "30 s to
  10 min" and corrected it on re-reading the hunk). `HubFacade` chooses the `HUB` route
  from `hubOnline` (ADR-229). This is Wave 2's `O10-05`/`O10-07` failure mode again: an unmeasured saving bought by
  deleting an invariant the code documents.
- **OPT-O17-03 is sound because the `Deliver` frame has no per-recipient field.** `RelayFrame.Deliver(from, id, type,
  sentAt, payload, group)` is identical for every member of the fan-out, so one encoding is valid for all of them. The
  write itself is still per recipient.
- **OPT-O23-01's failure paths close the handle.** `finishPart` → `syncAndClose` then `move`; a failed download →
  `fail()` → `deletePart` → `close` + delete; a non-reusable resume → `deletePart` first. `HubFileDisk.delete` closes
  before unlinking. The one handle that outlives a run belongs to an **interrupted** transfer, which is ADR-292's stated
  residual and is reused when it resumes.
- **OPT-O23-01's verify() is a real backstop, not a comment.** `DownloadRun.verify()` re-reads and re-seals every chunk
  from offset 0 and compares both the ciphertext and (when given) the plaintext hash before `finishPart`, so a done bit set
  over bytes that never reached the platter costs one refetch (`verifyFailures++`) and never a silently accepted file.
  Both durability directions named in ADR-292 therefore end in the same place. What *is* new information for a reader:
  `replaceAtomically` — the resume-record writer — also lost its fsync, and that is written once per chunk, so the state
  file and the data file now have the same (weaker) guarantee. ADR-292 §Cost states it; nothing else claims otherwise.
- **OPT-O16-02 keeps failure atomicity.** The lazy `AbstractList` projection is only a view over `StagedMember`; the batch is
  still applied in one Room transaction, so a failure still rolls the whole page set back.
- **OPT-O16-01's pipeline really is bounded** (three chunk buffers) and serialises the shared `RandomAccessFile` per chunk
  (`synchronized(raf)` around seek+write), so three workers cannot interleave two chunks into one file position.

## 3. What the integrator repaired (all on this branch, two commits)

### 3.1 OPT-O17-02 — as committed, a committed row could outlive its chunk file

`FileStore.putChunk` wrote the chunk file **before** opening the transaction. A crash (or a throw between the two) left a
`file_chunk` row whose file was gone — and `complete()` checks counts and byte totals, not file presence, so the file would
have been sealed and then answered 500 on every download. The performance idea is kept: the megabyte write still happens
outside SQLite's single writer. The bytes now go to a uniquely named `chunk-*.part` (a unique name matters because
`OPT-O16-01` keeps three chunks in flight and a retry can race the attempt it retries), and the **rename into place happens
inside the transaction, before the row insert**, so "a committed row implies a present chunk file" holds again. A throw
after the rename deletes the published file; the staging file is always removed. ADR-290.

### 3.2 OPT-O16-04 — an unbounded ack batch would have turned every ack into a redelivery

The server side is fine: `acknowledgeBatch` deletes in one transaction, under the same per-device budget. The client half
had no length bound, and `RelayFrameCodec` rejects a frame with more than 100 ids **as a whole** — the client would have
thrown on its own ack and the hub would have redelivered the same messages, forever, for any drain longer than 100. The
client now splits at 100 (`MAX_RECV_ACK_IDS`). ADR-296 also records the honest scope: `HubChatBridge.handleInbound` still
acks one delivery at a time, so **no product path sends a batch yet** and the advertised saving does not happen until the
drain collects ids.

### 3.3 The stream's own test fix was not deterministic

`RevocationAndEnrolmentTest` failed 2/663 under load (`expected:<1> but was:<0>`): both assertions read the handler list on
the line after `revokeDevice`, but `SqliteRevocationService.fire` dispatches on `notifyScope` by design (kicking a relay
session must not hold the revocation write transaction). A bounded `awaitKicks()` helper keeps "fires **once**" as the
assertion and makes the suite deterministic; the production path is untouched. ERROR-934, RESOLVED.

### 3.4 A comment that became a lie

`HubFileTransfers` still said "data first, synced; then the bit" after `OPT-O23-01` removed the per-chunk fsync. It now
says what is true: data first, then the bit, with one sync at the end, and `verify()` as the backstop.

### 3.5 Two defects the report did not mention (found on review)

- **`Vary: Accept-Encoding` was missing on 304 answers.** The compression negotiation added the header only on the 200
  paths, so a revalidated asset could be handed to a client that asked for a different encoding. The header is now set
  before the branch, and a test asserts it on the 304.
- **`CachedMailboxStatsTest.aFailingRefreshKeepsTheLastNumbersAndIsRetried` was still the documented flake** (see the
  header). It now injects `CoroutineScope(Dispatchers.Unconfined)` — an existing constructor parameter — so the refresh
  runs on the calling thread: no sleeps, no deadline, and the test states the product's real semantics (the read that
  starts a refresh still serves the old snapshot; the next read has the new numbers). Production behaviour unchanged.

## 4. Numbers withdrawn

Every figure the stream reported — "2 100 ms → 8 ms", "14.9 → 41.0 MB/s", "42.5 MB → 3.8 MB", "−94 % transactions",
"TTI 3.5 s → 150 ms", "95 % of radio wakeups", "14.8 ms → 0.18 ms", "8.2 ms → 0.4 ms", "over 80 % faster" — is
**withdrawn**. No benchmark harness was added anywhere on the branch; every suite named under its "Verification" is a
correctness test that asserts nothing about time, heap or frame counts. Each `EXP-110`..`EXP-114` entry is now retitled
*NOT MEASURED* and names the `PERF-H0n` test that would produce the number, the same treatment Wave 2 gave `EXP-095`..`098`
and `EXP-100`..`102`. ADR-291/292/294/295/297/299 carry the structural claim and nothing more.

## 5. Open risks carried into `dev`

- **`OPT-O16-04` is ahead of its caller.** The wire frame and the server batch exist; `HubChatBridge` does not use them.
  Until it does, the change is neutral. Recorded in ADR-296 and `PERF-H04`.
- **A paused hub download holds a descriptor** (ADR-292 §Residual). One per transferred file, reused on resume, released on
  finish/fail/delete. Not capped — worth revisiting if a user can queue many paused hub downloads.
- **The console devices page can show partial search results while paging.** Page 1 renders immediately (that is the
  point), the remaining pages are appended in the background, and a filter applied in that window filters what has
  arrived. The previous code was slow but complete. `PERF-H01`-style console check the stream did not owe; noted here so it
  is not rediscovered as a bug.
- **`OPT-O18-01` keeps three copies of each asset in memory** (plain, gzip, deflate), capped by `MAX_ASSET_BYTES = 2 MB`
  per asset and bounded by the console's own file set. Fine today, worth knowing before adding large static files.
- **Everything above is unit-tested only.** Nothing in this stream has been deployed or run on a phone; `PERF-H01`..`H07`
  are the tests that would turn any of it into a measured claim.

## 6. Owed device/server tests

`PERF-H01`..`PERF-H07` in this stream's `backlog-additions.md`, folded into `docs/testing/TEST-BACKLOG.md`. Two of them
were rewritten by this review: `PERF-H04` now says explicitly that the client sends one ack per message today, and
`PERF-H07` covers the repaired `putChunk` (kill the hub between the staged write and the commit; no row may exist whose
chunk file is missing, and no `chunk-*.part` may survive a resumed upload).
