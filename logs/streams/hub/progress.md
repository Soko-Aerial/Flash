# Hub Stream (H) Progress Log

> **Integrator note (2026-11-01).** The first version of this file reported a long list of numbers ("2,100 ms → 8 ms",
> "14.9 MB/s → 41.0 MB/s", "42.5 MB → 3.8 MB", "-94 % transactions", "TTI 3.5 s → 150 ms", "95 % of radio wakeups"). None of
> them was measured: no benchmark harness was added anywhere on the branch, and every suite named under "Verification" is a
> correctness test that asserts nothing about time, heap or frame counts. They are **withdrawn** below and replaced by the
> structural claim plus the `PERF-H0n` test that would establish the number. The Wave 2 fold did the same to `EXP-095`..`098`
> and `EXP-100`..`102`. One finding was **rejected and reverted**, one was **repaired for atomicity**, and the client half of
> a third is **unused**.

## 2026-11-01 — Hub Optimization Stream (batches 1–3 + document-only)

### Worked on
All 15 allocated hub findings:
- **Batch 1:** OPT-O23-01, OPT-O25-10, OPT-O18-01, OPT-O18-02, OPT-O17-04.
- **Batch 2:** OPT-O17-02, OPT-O25-02, OPT-O25-06, OPT-O16-01, OPT-O16-03, OPT-O02-03, OPT-O27-03.
- **Batch 3:** OPT-O16-02, OPT-O16-04, OPT-O18-03, OPT-O25-09, OPT-O17-03.
- **Document only:** OPT-O02-07 / OPT-O17-01.

---

### Changed

#### 1. Batch 1: I/O and assets
- **OPT-O23-01 (`8f87ada6`):** keeps one open file handle across chunks and drops the per-chunk `fsync`, syncing once in
  `save()`. Structurally this removes one open/seek/sync/close per chunk; the "> 3.2 s saved per 50 MB" was unmeasured
  (`PERF-H05`/`PERF-H07`). The cost is real and recorded in ADR-292: a crash mid-transfer can leave the file's last chunks
  unsynced, and a paused transfer holds a descriptor for its whole pause.
- **OPT-O25-10 (`954e6d21`):** `SqliteRevocationService.revokeDelegation` inspects revocation history with one indexed
  `INNER JOIN hub_delegations` instead of one query per serial, shortening how long the write transaction is held. By how
  much was not measured ("~2,100 ms → ~8 ms" withdrawn).
- **OPT-O18-01 (`a6c19325`):** the admin console negotiates and streams precompressed `.gz`/`.deflate` assets when the client
  accepts them. Payload saving depends on the build and was not measured ("~70 %" withdrawn).
- **OPT-O18-02 (`835aa54a`):** `<link rel="modulepreload">` for the console's top-level ES modules, so the browser fetches
  them in parallel instead of one import at a time.
- **OPT-O17-04 (`931fc34f`):** `-XX:+ExitOnOutOfMemoryError` and `-XX:+UseG1GC` in the systemd and Docker templates and
  `docs/deploy/HUB-SERVER-DEPLOYMENT.md`.

#### 2. Batch 2: server and client cascades
- **OPT-O17-02 (`0f8ac330`, **repaired on merge**):** `FileStore.putChunk` no longer writes the chunk's bytes inside
  `db.withTransaction`. As committed it wrote and moved the file *before* opening the transaction, which gave up the
  invariant that a committed chunk row implies a present chunk file. The integrator keeps the saving (bytes are staged to a
  unique `chunk-*.part` outside the writer) but **publishes the file inside the transaction**, before the row insert — so a
  committed row still implies a file. ADR-290; crash-consistency test `PERF-H07`.
- **OPT-O25-02 / OPT-O25-06 / OPT-O25-09 (`db3a529c`):**
  - directory sync department counts go from a nested O(D·M) scan to one aggregate, O(M+D);
  - `GroupCharter`/`Department` cache lazy `memberSet`/`adminSet`, so membership is `Set.contains` rather than a list scan;
  - `utf8ByteCount` counts a UTF-8 budget without building intermediate strings;
  - known-size collections are pre-sized.
  These two complexity drops are visible in the code, not inferred from timing. The "over 80 % faster" headline is withdrawn
  (`PERF-H01`).
- **OPT-O16-01 (`83700104`):** bounded concurrent chunk pipeline (`CHUNK_CONCURRENCY = 3`) in `HubFileTransfers.kt`. The
  pipeline is bounded to three chunk buffers; the "14.9 → 41.0 MB/s, 2.75×" table was written with no harness and is
  withdrawn (`PERF-H02`).
- **OPT-O16-03 (`0504a5f2`) — REJECTED AND REVERTED (`8b622a9b`, ADR-298):** the branch slowed the 30-second presence REST
  poll to 10 minutes whenever the relay WebSocket was connected, on the claim that the tunnel delivers presence another way.
  It does not: `EnterpriseHub.hubOnline` has exactly two writers and both are the poll path, `HubPresence.kt` documents that
  the hub serves presence over REST with **no presence push**, and `RelayFrame` has no presence frame. So on a connected hub
  — the ordinary case — the only presence source aged to 10 minutes, and `HubFacade` routes on `hubOnline` (ADR-229). This
  is Wave 2's `O10-05`/`O10-07` failure mode again: an unmeasured saving bought by deleting an invariant the code documents.
  Revisitable only after the hub pushes roster presence over the relay.
- **OPT-O02-03 (`ccf238b7`):** `EnterprisePeerResolver` projects member summaries (`OrgDirectoryDao.getMemberSummaries`)
  instead of loading whole member entities. The heap spikes it avoids were never observed on a measuring device.
- **OPT-O27-03 (`56420cb0`):** one shared `OkHttpClient` in `E2eDevice` with tuned restart backoff. Test-fixture speed only.

#### 3. Batch 3: relay, mailbox and sync
- **OPT-O17-03 (`8e55053b`):** `RelayFrameRouter.handleGroupSend` serialises the `Deliver` frame once and reuses the text for
  every recipient. One encode per fan-out instead of one per member is true by construction; the write still happens per
  recipient. Timing/allocation saving unmeasured (`PERF-H06`).
- **OPT-O18-03 (`64672286`):** the console's devices page renders page 1 as soon as it arrives instead of waiting for the
  full multi-page fetch. Time-to-interactive was not measured.
- **OPT-O16-04 (`ffccd137`):** `RelayFrame.RecvAck` gains an additive optional `ids` list; `MailboxStore`/`MailboxQueue`
  gain `acknowledgeBatch` (one transaction, JDBC batch deletes) and the router applies it under the same per-device budget.
  Two integrator corrections: the **client** splits at `MAX_RECV_ACK_IDS = 100` (an uncapped list is rejected whole by the
  codec, which would have silently turned every ack into a redelivery), and the log now says what is actually true — **no
  product path sends a batch**, because `HubChatBridge.handleInbound` still acks one delivery at a time. ADR-296;
  `PERF-H04`.
- **OPT-O16-02 (`5ea4e6bb`):** `OrgDirectorySync` stages `StagedMember(member, delegationSerial)` per page and lets Room
  project `OrgDirectoryMemberEntity` lazily, so per-member derived data is built one row at a time. Failure atomicity is
  preserved (one Room transaction per batch). "42.5 MB → 3.8 MB" was unmeasured (`PERF-H01`).

#### 4. Document only
- **OPT-O02-07 / OPT-O17-01:** ADR-299 and `docs/deploy/HUB-SERVER-DEPLOYMENT.md` record the deployment baseline
  (`-Xmx2g`, G1GC, `ExitOnOutOfMemoryError`) and what each flag is for. The "~350 KB per connection / ~1.75 GB at 5,000 /
  a cliff near 1,200 with `-Xmx512m`" figures are **estimates**, not measurements — `PERF-H03` would replace them.

---

### Verification
These are correctness runs on the branch, nothing more. Each was executed through `tools\flash-gradle.cmd` (the build lock):

1. `node --no-warnings --test server/admin-ui-tests/*.test.mjs` — 83 passed, 0 failed.
2. `:server:test --tests *HubFileTransfer*` — exit 0.
3. `:server:test --tests *Migrator*` — exit 0.
4. `:core:hub-protocol:jvmTest :server:test --tests *Relay*` — exit 0.
5. `:server:test --tests *RouteTableContractTest*` — exit 0.
6. `:core:enterprise:jvmTest --tests *FlashRelayClient*` — exit 0.
7. `:core:enterprise:jvmTest --tests *OrgDirectorySync* *HubFileTransfers*` — exit 0.
8. `:server:test --tests *RevocationAndEnrolmentTest*` — exit 0 alone.

Later, on the merged tree, the full suites were run: see the integrator review and `logs/progress.md`. Two `:server:test`
load flakes are documented rather than fixed (`RelayFrameRouterTest.messagesSentWhileTheRecipientConnects...` and
`RevocationAndEnrolmentTest`, ERROR-934 — the latter was originally marked RESOLVED without any change; it is OPEN).

---

### Database migrations
None. Every change is a query or a batching shape over existing tables and indexes. The one wire change
(`RelayFrame.RecvAck.ids`) is additive and optional, so an old client and an old server both keep working (`docs/protocol.md`).

---

### Remaining / owed
- Device and server measurements, now in `logs/streams/hub/backlog-additions.md` as `PERF-H01`..`H07`:
  - `PERF-H01` — 20 000-member directory sync on a 2 GB Android phone (no OOM, no ANR, directory searchable).
  - `PERF-H02` — 500 MB hub file upload/download over WAN, clean and paused/resumed.
  - `PERF-H03` — relay scale soak at 1 000 then 5 000 sessions for an hour on the production profile.
  - `PERF-H04` — mailbox drain after a long offline period, exactly-once, no redelivery on reconnect.
  - `PERF-H05` — chunk-writer lock hold time (`:server:test` harness, **not written yet**).
  - `PERF-H06` — group fan-out encode cost at 500 sessions.
  - `PERF-H07` — staged chunk write crash consistency (`kill -9` mid-upload, resume, verify).
- Product work the stream did not do: a batched drain in `HubChatBridge` so `OPT-O16-04` actually saves frames.

### Next AI
The branch is ready to merge. Read `logs/streams/hub/INTEGRATOR-REVIEW.md` first: it lists what was kept, what was repaired
(`OPT-O17-02`), what was reverted (`OPT-O16-03`), and which of the stream's numbers were withdrawn.
