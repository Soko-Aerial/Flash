# Hub files — sealed 50 MB transfers through the Flash Hub

**Status:** DESIGNED 2026-10-20, owner decisions taken (section 0). Phases P1..P4, nothing built yet.
**Owner's request:** "the hub should accept at least 50 MB files, adjustable by an admin on the dashboard".
**Owner decisions:** D1 sealed (zero-knowledge storage), D2 1:1 and group chats in the first version.
**Quality bar:** `docs/plans/HUB-QUALITY-CONTRACT.md` applies to every phase. The minimum is a failure.
**Read with:** `docs/protocol.md` "Hub REST contract", `docs/audit/2026-10-20-hub-audio-files-and-radio-investigation.md` F2,
`docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md`, ADR-215.

---

## 0. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | **Sealed.** The sender encrypts the file before it leaves the phone; the hub stores opaque bytes and never receives a key. | The owner chose zero-knowledge. It matches how 1:1 hub chat already crosses the hub (ADR-121) and keeps the hub out of the content business. |
| D2 | **1:1 and group** in the first version. | The owner chose both. Group costs nothing extra in storage (one blob), only in key delivery (section 3.4). |
| D3 | Default **50 MB per file**, admin-adjustable (`maxFileBytes` in the console). | The owner asked for it and for the control. |
| D4 | Chunked **REST** upload/download, not relay frames. | Relay frames cap a payload at 64 KiB (`HubContract.MAX_PAYLOAD_BYTES`) and exist for live sessions; a stored file needs resume, quotas and a body the hub can stream. |
| D5 | One blob per send, stored as files on disk with a metadata index in SQLite. | A 50 MB SQLite BLOB rewrites the database file per chunk and inflates every backup. |
| D6 | Retention: `fileRetentionDays` (default 7), plus explicit cancel by the sender. | Matches the mailbox TTL default. A file nobody downloaded must not live forever. |
| D7 | Group files: the blob is one copy, the **key is delivered per member in a sealed direct message**, never inside the group frame. | Group frames are relayed in clear today (FO-27), so a key in a group frame would hand the hub the plaintext of a group file. |

---

## 1. Goal and non-goals

**Goal.** A member sends a file to a colleague or a group chat when there is no direct LAN path. The file arrives with
resume after a dropped connection, an integrity check the recipient performs itself, a visible size limit that an
administrator can change, and an honest statement of what the hub can and cannot see.

**Non-goals (phase 1).** Streaming media; resumable *sending* after process death (resume of a *receive* is in scope);
attachments on announcements (FO-21); file drop to a department (FO-25); server-side virus scanning (it cannot see the
content); per-group e2e message encryption (FO-27).

---

## 2. Wire

Everything under `/api/v1/files`, bearer-authenticated like the rest of the device API, capability token `fil1`.
New contract constants in `:core:hub-protocol` (`HubContract.kt`), DTOs in `rest/HubFileDtos.kt`.

| Method | Path | Body / result |
|---|---|---|
| `POST` | `/api/v1/files` | `CreateFileRequestDto` → `201 FileTicketDto` |
| `PUT` | `/api/v1/files/{fileId}/chunks/{index}` | raw `application/octet-stream` → `204` |
| `POST` | `/api/v1/files/{fileId}/complete` | → `FileStatusDto` |
| `GET` | `/api/v1/files/{fileId}` | → `FileStatusDto` |
| `GET` | `/api/v1/files/{fileId}/chunks/{index}` | `application/octet-stream` → bytes |
| `DELETE` | `/api/v1/files/{fileId}` | sender only → `204` |

```
CreateFileRequestDto(
  fileId: String,            // client-minted, 32 lowercase hex, idempotency key
  totalBytes: Long,          // ciphertext size, 1..maxFileBytes
  chunkSize: Int,            // 1..MAX_CHUNK_BYTES (1 MiB), must equal every PUT
  chunkCount: Int,           // must equal ceil(totalBytes / chunkSize)
  sha256: String,            // 64 lowercase hex, of the WHOLE ciphertext
  toDeviceId: String?,       // exactly one of these two
  groupId: String?,
  nameHint: String?,         // never displayed by the hub; for the operator's log only (section 9)
)

FileTicketDto(fileId, uploadExpiresAtMs, maxChunkBytes)
FileStatusDto(fileId, state, totalBytes, chunkSize, chunkCount, sha256,
              senderDeviceId, recipients: List<String>, createdAtMs, expiresAtMs,
              receivedChunks: Int)   // how many, not which
```

States: `UPLOADING` → `READY` → `EXPIRED`/`CANCELLED`. A recipient may only `GET` a `READY` file.

Error codes reuse the hub vocabulary: `ERR_TOO_LARGE` (file or chunk), `ERR_QUOTA_EXCEEDED`, `ERR_NOT_FOUND`,
`ERR_FORBIDDEN` (not a recipient, not the sender), `ERR_CONFLICT` (same index with different bytes / wrong state),
`ERR_BAD_REQUEST` (malformed id, chunk math, sha256), `ERR_RATE_LIMIT`. Every error body is the standard
`{error, message}` and the message names the next step in plain words.

**Chunk rules.** `PUT` is idempotent per (fileId, index): identical bytes → `204` (a retry); different bytes → `409`.
Out-of-order chunks are allowed. A chunk larger than `chunkSize` is `ERR_TOO_LARGE`. The last chunk may be shorter
(`totalBytes - chunkSize * (chunkCount - 1)`).

**Compatibility.** The hub advertises `fil1` in `HubInfo` features and in the relay `HELLO` capability list. An old hub
has no `/api/v1/files` (404) and an old client never calls it; a new client against an old hub shows the existing "not
available over the hub" sentence, with no new UI state.

---

## 3. Server design (`:server`)

### 3.1 Schema (migration component `files`, version 1)

```sql
CREATE TABLE file_blob (
  file_id TEXT PRIMARY KEY,            -- 32 hex
  sender_device_id TEXT NOT NULL,
  group_id TEXT,                       -- null for 1:1
  target_device_id TEXT,               -- null for a group send
  state TEXT NOT NULL,                 -- UPLOADING | READY | CANCELLED | EXPIRED
  total_bytes INTEGER NOT NULL,
  chunk_size INTEGER NOT NULL,
  chunk_count INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  name_hint TEXT,
  received_chunks INTEGER NOT NULL DEFAULT 0,
  received_bytes INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  upload_expires_at INTEGER NOT NULL,  -- UPLOADING abandoned
  expires_at INTEGER NOT NULL,         -- READY lifetime
  completed_at INTEGER
);
CREATE TABLE file_recipient (file_id TEXT NOT NULL, device_id TEXT NOT NULL, PRIMARY KEY(file_id, device_id));
CREATE TABLE file_chunk (file_id TEXT NOT NULL, idx INTEGER NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL,
                         PRIMARY KEY(file_id, idx));
CREATE INDEX file_blob_state ON file_blob(state, expires_at);
CREATE INDEX file_blob_sender ON file_blob(sender_device_id, state);
CREATE INDEX file_recipient_device ON file_recipient(device_id);
```

Chunk bytes live at `<dataDir>/files/<fileId>/<index>.chunk` (written to `<index>.part`, fsynced, atomically moved). The
database is the index; a missing chunk file with a row is a repair case the download answers `503`.

### 3.2 Store (`FileStore`)

Same shape as `AnnouncementStore`/`MailboxQueue`: `open(db, clock, limits)`, `BEGIN IMMEDIATE` for every mutation, and
limits enforced inside the transaction that writes.

- `create(principal, request)`: validate the id/maths/sha256, resolve the recipients (a group send reads the roster
  **now**), check the file cap and the quotas (subtracting nothing yet — the declared `totalBytes` is reserved), insert
  the blob row and the recipient rows, and create the directory. Idempotent: the same `fileId` from the same sender with
  identical metadata returns the existing ticket; different metadata is `ERR_CONFLICT`.
- `putChunk(principal, fileId, index, bytes, sha256Header)`: sender only; state `UPLOADING`; index in range; length as
  above; verifies the header hash; writes the file; upserts the row; increments counters **once per new index**.
- `complete(...)`: sender only; every index 0..chunkCount-1 present; the byte sum equals `totalBytes`; state `READY`,
  `expires_at = now + retention`.
- `status(principal, fileId)`: sender or a listed recipient; a group recipient is re-checked against the live roster.
- `readChunk(principal, fileId, index)`: as `status`, plus `state == READY`.
- `cancel(principal, fileId)`: sender only, deletes files and rows.
- `sweep(nowMs)`: `UPLOADING` past `upload_expires_at` and `READY` past `expires_at` → delete files and rows; returns
  counts for the audit line.

Quotas (defaults, all admin-visible in the console settings help): `maxFileBytes` 50 MB, `deviceStoredBytes` 500 MB,
`globalStoredBytes` 5 GB, `maxFilesPerDevice` 50, `fileRetentionDays` 7, `maxUploadHours` 24, `maxChunkBytes` 1 MiB.

### 3.3 Routes (`files/FileRoutes.kt`)

Registered next to `announcementRoutes` in `HubServices`. Every route: `requirePrincipal`, a per-device rate limiter
(`HubRateLimits.fileWrite` / `fileRead`), an audit line without content (`file.create id=… bytes=… to=…`), and a
`receiveBoundedBinary` helper (the existing bounded-text helper does not read raw bodies). Bodies are streamed to the
chunk file, never fully buffered in memory (Ktor `receiveChannel`, 64 KiB at a time).

### 3.4 Key delivery (client side, described here because it shapes the message)

- 1:1: the file key + metadata go inside the existing **sealed** hub chat payload (`HubSealing`), so the hub never sees them.
- Group: one **direct sealed** hub message per member who must receive it through the hub (`HubChatBridge.direct` with a
  `HUB_FILE_KEY` inner type). Members reachable on the LAN get the file over the LAN path as today; only the key
  message is hub-bound for the others.
- A member who joins the group after the send gets nothing (the roster is resolved at send time). Recorded in the string table.

### 3.5 Admin and console

- New settings rows (validated in `AdminApi.settingsRoutes`, `StepUpActions.SETTINGS_CHANGE` applies): `maxFileBytes`,
  `fileRetentionDays`, `deviceStoredBytes`, `globalStoredBytes`.
- A console "Files" card in settings showing the four limits with help text ("the hub stores sealed files; this number is
  the largest single file a member may send"). A read-only usage line (`GET /admin/api/v1/files/stats`: files, bytes,
  per-device top 5).
- `docs/deploy/HUB-SERVER-DEPLOYMENT.md`: back up `<dataDir>/files` with the database; a restore without them leaves rows
  pointing at nothing (the download answers 503 and the sweeper removes them after retention).

---

## 4. Client design

### 4.1 `:core:hub-protocol`
`HubFileDtos.kt` + `PATH_FILES*` constants + `FEATURE_FILES_V1 = "fil1"`; `HubJson` round-trip tests and a fuzz pass over
the decoder (1,000 random/truncated inputs never throw).

### 4.2 `:core:enterprise`
`HubFilesApi` (`create`, `putChunk`, `complete`, `status`, `readChunk`, `cancel`) + `RealHubFilesApi` behind
`HubUrlPolicy` (the pinned-hub + cleartext rule `RealHubAnnouncementsApi` now enforces) + `FakeHubFilesApi`.

### 4.3 Sealing of the file (`:core:security` or `:core:messaging`)
`HubFileSealing`: 32-byte key from a CSPRNG, AES-256-GCM per chunk, nonce = `fileNonce(8) || chunkIndex(4 big-endian)`,
AAD = `fileId || chunkIndex`. The key never leaves the message payload. The recipient verifies the ciphertext `sha256`
as it writes and then the whole-file hash from the message before marking the file received.

### 4.4 Repository (`:core:messaging`)
- `sendAttachment` gains a hub branch: when there is no direct path and the recipient is reachable through the hub, upload
  in the background (foreground service already exists), then emit one chat message of a new local kind `HUB_FILE`
  carrying `{fileId, key, name, mime, size, chunkCount, sha256}` (sealed for 1:1; a per-member sealed key message for a
  group, plus the group frame carrying the file card without the key).
- Receiving: the message arrives, the file downloads chunk by chunk into `files/hub/<fileId>.part`, resume by asking for
  indexes the local bitmap lacks, then `complete` moves it to the normal attachment location and the bubble shows the
  usual preview.
- Pause/cancel/retry reuse `TransferState`; the progress row shows "through the hub" instead of a peer address.

### 4.5 Storage quota on the client
The client refuses a file above `maxFileBytes` **before** uploading, with the sentence naming the limit and how to ask an
administrator to raise it (the limit comes from `GET /api/v1/hub`).

---

## 5. Security (rows for `docs/security.md`)

| Threat | Mitigation | Residual risk |
|---|---|---|
| Hub operator reads a file | Content sealed client-side; hub stores ciphertext only (D1) | The hub learns size, timing, sender, recipients (metadata is not hidden) |
| Group file key exposed | Key delivered in a sealed direct message, never in a group frame (D7) | A member of the group can always read what it was sent |
| Path traversal / crafted id | `fileId` must match 32 lowercase hex; paths built from it only; no user-supplied path component | — |
| Storage exhaustion | Per-file cap, per-device cap, global cap, `maxFilesPerDevice`, all in the writing transaction | A determined insider can fill its own quota |
| Replayed / duplicated upload | `PUT` is idempotent per index with a content check; `create` is idempotent per `fileId` | — |
| Leaked file to a non-recipient | Recipient check on `status`/`readChunk`; group membership re-checked live | A recipient can forward the key (unavoidable) |
| A cancelled or expired file lingering on disk | Sweeper deletes rows **and** files; a startup consistency pass removes orphan directories | Files deleted after a crash between the DB and the filesystem are found by the pass, not immediately |
| Secrets in logs | No keys, no content, no full ids (8 chars), no file names in probes | — |

---

## 6. String table (exact user-facing text; no other copy may appear)

| Id | Text |
|---|---|
| `HUB_FILE_TOO_LARGE` | "This file is {size}, and the hub accepts up to {limit} per file. An administrator can raise the limit in the hub console." |
| `HUB_FILE_SENDING` | "Sending through the hub…" |
| `HUB_FILE_WAITING` | "Waiting for the hub to accept the file." |
| `HUB_FILE_HUB_FULL` | "The hub is full. Try again later, or ask an administrator to raise the storage limit." |
| `HUB_FILE_DOWNLOADING` | "Receiving through the hub… {percent}%" |
| `HUB_FILE_EXPIRED` | "This file is no longer on the hub. Ask {name} to send it again." |
| `HUB_FILE_FAILED` | "The hub could not take this file. {reason}" |
| `HUB_FILE_NEEDS_SEALING` | "Files can only go through the hub when content sealing is on for {name}." |
| `HUB_FILE_NOT_A_RECIPIENT` | "You are not on this file's recipient list." |
| `HUB_FILE_KEY_NOT_YET` | "The file details are still arriving." |
| `HUB_FILE_HUB_OLD` | "This hub cannot carry files yet. Ask an administrator to update it." |

---

## 7. Situations the plan must cover (each gets a test)

1. Upload interrupted at chunk 37/50, app killed, reopened → resume continues at 37.
2. Download interrupted at chunk 20/50 → resume re-requests only what is missing.
3. The same `PUT` replayed byte-identically → 204, counter unchanged; with different bytes → 409.
4. A file created but never completed (app died) → swept after `maxUploadHours`, quota released.
5. The limit is lowered below an existing file's size → the existing file still downloads; only new sends are refused.
6. A group member removed after the send → `status`/`readChunk` answer 403; the member who already downloaded keeps it.
7. A hub restored from a backup without `data/files` → downloads answer 503, not 200 with empty bytes.
8. Two devices send the same `fileId` (collision) → second is 409, never overwrites.
9. A recipient on a metered connection cancels mid-download → partial file removed, no quota leak.
10. Clock jumps backwards on the hub → `expires_at` comparisons stay sane (monotonic sweep uses the later of two reads).
11. A 0-byte file → rejected with `ERR_BAD_REQUEST` (an empty file is not worth a hub round trip; the LAN path handles it).
12. `maxChunkBytes` lower than a client's chunk size (admin tightened it) → `ERR_TOO_LARGE` naming the new limit.

---

## 8. Phases

### P1 — Server: sealed blob store, limits, routes, admin setting (start here)

Steps `HF-S1..HF-S6`: contract constants + DTOs; migration + `FileStore`; routes + bounded binary receive + rate limits;
sweeper + startup consistency pass; admin settings + stats endpoint; `docs/protocol.md` + `docs/protocol-routes.txt` +
`RouteTableContractTest`.

Acceptance: every store rule has a unit test (happy, boundary, one-over, concurrency via two threads), the route table
contract passes, a synthetic 50 MB upload through the real Ktor test host completes and downloads byte-identically, and
the quota arithmetic is mutation-checked.

### P2 — Client transport: DTOs, `HubFilesApi`, sealing, resume engine

Steps `HF-C1..HF-C5`: `:core:hub-protocol` DTOs and fuzz; `RealHubFilesApi` behind `HubUrlPolicy` + fakes; `HubFileSealing`
with golden vectors; an upload/download engine with resume and a persisted chunk bitmap; tests for every interruption.

### P3 — Messaging and UI: the file message, key delivery, both hosts

Steps `HF-M1..HF-M5`: `HUB_FILE` message kind + Room step; `sendAttachment` hub branch; 1:1 sealed key delivery; group
per-member sealed key; progress/pause/cancel in the bubble and the Transfers screen; Android foreground service wiring;
desktop parity.

### P4 — Group fan-out details, console Files card, operations

Steps `HF-G1..HF-G4`: group roster edge cases (member added/removed mid-send), console Files card + stats, deployment
doc updates, backlog device tests `HUBFILE-01..12`.

---

## 9. Implementation log

*(Each phase writes its explain-back, evidence table and report here. Nothing is filled in yet.)*

### P1 explain-back — 2026-10-20

**What P1 must achieve.** Turn "the hub can store an opaque file" into a server capability that is safe to expose: a
sender reserves space under an administrable cap, uploads numbered chunks that are verified and counted exactly once,
declares completion, and the sweeper removes what nobody finished or collected. Nothing in P1 knows what a file *is*.

**Files.** New `server/src/main/kotlin/com/transfer/flash/server/files/{FileModels,FileStore,FileRoutes,FileStats}.kt`;
`HubContract.kt` + `rest/HubFileDtos.kt` in `:core:hub-protocol`; `HubServices.kt` wiring; `Migrations.kt` registration
inside `FileStore.open`; `AdminApi.kt` settings + stats; `docs/protocol.md`, `docs/protocol-routes.txt`.

**Three nearest existing classes read first.** `AnnouncementStore` (SQLite store shape, `BEGIN IMMEDIATE`, migrator
registration, sweeper), `MailboxQueue` (quota arithmetic inside the write transaction, `Txn`, audit lines), and
`AnnouncementRoutes` (principal, rate limiter, error vocabulary, `respondJson`). Reused: `Migrator`, `ServerDatabase`,
`HubRateLimits`, `requirePrincipal`, `respondError`, `AuditSink`. Deliberately different: chunk bytes go to the
filesystem (D5), and the store streams instead of holding a body — those are the two places I must not copy the
announcement code.

**Five ways it is most likely to go wrong.** (1) Quota double-counting when a chunk is retried or two uploads race;
(2) a declared `totalBytes` that never matches the sum of chunks, leaving a `READY` file that cannot be verified;
(3) the filesystem and the database disagreeing after a crash; (4) reading a 1 MiB body into memory on a hub with 20
concurrent uploads (memory blow-up); (5) the group recipient list going stale between `create` and `readChunk`.

**Tests written first.** `FileStoreTest` (create idempotency, chunk maths, one-over limits, quota release on cancel,
sweep), `FileRoutesTest` (auth, 403 for a non-recipient, 409 replay, 404 unknown, 413 oversize, resume after a partial
upload), `FileUploadIntegrationTest` (real Ktor host, 50 MB synthetic, byte-identical download).

### P1 result — built, unit-tested, NOT deployed (2026-10-20)

**Status.** `HF-S1..HF-S6` exist in the working tree. The full `:server:test` run: **566 tests, one failure** —
`RelayFrameRouterTest.messagesSentWhileTheRecipientConnectsAreAllDeliveredInOrderExactlyOnce`, which passes when rerun in
isolation and belongs to the documented load-flake family (`RelayLoadTest`,
`E2eRelayContractTest.aReconnectStormLeavesExactlyOneLiveSession`; `logs/handoff.md` "known flaky"). The 33 file tests pass
alone and inside that run. Nothing is deployed, nothing is device-verified.

**Built.**

| Item | File | Notes |
|---|---|---|
| Contract + DTOs | `core/hub-protocol/.../HubContract.kt`, `rest/HubFileDtos.kt`, `rest/RestDtos.kt` (`HubInfo.maxFileBytes`) | `PATH_FILES`, `MAX_CHUNK_BYTES` (1 MiB), `MAX_FILE_CHUNKS` (4096), `DEFAULT_MAX_FILE_BYTES` (50 MB), `MAX_FILE_BYTES_CEILING` (2 GiB), `MIN_FILE_BYTES_FLOOR` (1 MiB) |
| Policy + messages | `server/.../files/FileModels.kt`, `FileSettings.kt` | `FileLimits`, `FileOutcome`, `SweepResult`, `FileMessages`; the four console settings parsed in one place |
| Store | `server/.../files/FileStore.kt` | migration component `files` v1 (`file_blob`, `file_recipient`, `file_chunk`), create/putChunk/complete/status/readChunk/cancel/sweep/reconcile/stats; storage at `<dataDir>/files/<fileId>/<index>.chunk` via `.part` + atomic move |
| Routes | `server/.../files/FileRoutes.kt` | six routes, `receiveBoundedBinary` (413 before buffering), `HubRateLimits.fileWrite` (burst 120, 20/s) and `fileRead` (burst 240, 40/s), audit without content |
| Wiring | `HubServices.kt`, `auth/AuthRoutes.kt` (`maxFileBytes`), `auth/ApiGate.kt`, `auth/RateLimiter.kt` | store opened after the admin service (its settings are the limits), swept by the existing housekeeping tick, `HubInfo.maxFileBytes` read live |
| Admin | `admin/AdminApi.kt` (`SettingsDto`, settings validation, `GET /admin/api/v1/files/stats`), `AdminApiDtos.kt`, `ConsoleScope.kt` | the four settings are validated with a sentence naming the bound; the stats route is `ORG` in the declared policy table |
| Docs | `docs/protocol.md` "Hub files API", `docs/protocol-routes.txt`, `docs/security.md`, `docs/deploy/HUB-SERVER-DEPLOYMENT.md` | wire spec, checked route list, threat rows, backup/ops notes |

**Where the build deviates from this plan, and why.** The plan text above is left as written so the decision history stays
visible; each deviation is deliberate.

| # | The plan said | Built | Why |
|---|---|---|---|
| D1 | `fil1` in the `HubInfo` feature list and the relay `HELLO` capabilities | **no `fil1`**; availability is `HubInfo.maxFileBytes > 0` | one advertisement instead of two that can disagree, and no relay wire change at all. A hub that does not answer the field reads as `0`, which is the "not available" path the client already has |
| D2 | a chunk body streamed straight to disk, "never fully buffered" | one chunk buffered (≤ `MAX_CHUNK_BYTES`), then written to `.part` and atomically moved | a chunk is already the retry and hashing unit; the buffer is bounded at 1 MiB per in-flight PUT and a bigger body is refused **before** it is read. Streaming mid-transaction would leave half a chunk file when the transaction rolls back |
| D3 | `maxChunkBytes` as an admin setting | constant `MAX_CHUNK_BYTES`; `create` refuses a larger declared `chunkSize` | chunk size is a protocol constant; an admin lowering it mid-upload could only break clients. An upload that declared 1 MiB keeps finishing |
| D4 | `maxUploadHours` as a setting | constant 24 h | no operator has asked for it; one fewer setting to get wrong |
| D5 | a separate `FileStats.kt` | `FileSettings.kt` (policy parsing) + `FileStore.stats()` | stats need the same SQL connection; a second file would only add indirection |
| D6 | an unknown recipient answers `ERR_BAD_REQUEST` | `ERR_UNKNOWN_RECIPIENT` (404) | matches the relay's own vocabulary for "that device is not here" |
| D7 | files keep `CANCELLED` / `EXPIRED` rows | cancel and sweep **delete** the rows | the client's sentence for "gone" is the same 404 either way; tombstones would only grow the database. The DTO states stay for a future version that wants them |

**Evidence (HF-S1..S6).**

| Step | Test | Result |
|---|---|---|
| HF-S1 | contract constants compile; DTOs round-trip through `HubRestCodec` inside every route test | pass |
| HF-S2 | `FileStoreTest` (23 cases): create idempotency and conflict, chunk maths, per-index replay, quotas (device, global, count), cancel, sweep, expiry, live group roster, path-traversal ids, reconcile, 50 MB, stats, **two-thread quota race** | pass |
| HF-S3 | `FileRoutesTest` (10 cases): six routes without a bearer, the whole send/receive/cancel journey with the audit rules, 413 before buffering, the admin limit sentence, per-device 429 + `Retry-After`, bad JSON/bad index, group visibility, 404s, 503 when the bytes are gone, **50 MB over the real Ktor host** | pass |
| HF-S4 | sweep / expiry / reconcile / unavailable-byte cases (inside `FileStoreTest`) | pass |
| HF-S5 | `AdminApiTest.theFileLimitIsBoundedAndTheFilesStatsRouteIsOrgOnly`, `ConsoleScopeTest.everyAdminRouteHasADeclaredPolicy` | pass |
| HF-S6 | `RouteTableContractTest`, `docs/protocol.md`, `docs/protocol-routes.txt` | pass |

**The 50 MB acceptance run.** `FileRoutesTest.aFiftyMegabyteFileGoesThroughTheRealKtorHostAndComesBackIdentically` —
50 × 1 MiB `PUT`s over the real Ktor test host, whole-blob hash computed incrementally (the test never holds 50 MB),
download of the last chunk byte-identical, 1.9 s. `FileStoreTest.aFiftyMegabyteFileIsAcceptedWhenTheLimitAllowsIt` — the
same size at store level, 2.0 s.

**Quota race.** `FileStoreTest.twoSendersRacingForTheLastBytesCannotBothWin`: two threads, 2 × 4096 bytes against a
5000-byte global cap, on a shared-cache in-memory database with a 4-connection pool. Exactly one `Ok` and one `Refused`,
and `stats().uploadingBytes` equals one file. This is what proves the caps sit **inside** the write transaction rather
than being "usually right".

**Mutation checks (each caught by a named test, each restored, suite green afterwards).**

| Mutation | Caught by |
|---|---|
| the device-quota check drops the incoming size (`senderBytes > cap`) | `theDeviceAndGlobalQuotasCountReservationsAndReleaseOnCancel` |
| the declared chunk-length check becomes `>` instead of `!=` (a short chunk is stored) | `aChunkOfTheWrongSizeOrHashIsRefused` |
| the recipient list is only enforced for group files | `anOutsiderCannotReadMetadataOrAChunk`, `aSealedFileTravelsFromOneDeviceToAnotherAndTheAuditStaysShort` |

**Known limits, recorded rather than hidden.**

- No client exists yet, so no file travels end to end; the routes are exercised with synthetic ciphertext.
- The HTTP proof is on the test host: real-network, real-disk and real-phone behaviour is `HUBFILE-*` (P4).
- A crash between writing a chunk file and committing its row leaves an orphan `*.chunk` in an otherwise valid directory;
  the sweeper removes it with the file at expiry, `reconcile()` does not hunt single files.
- The reservation model lets a client declare 50 MB and hold that quota with one byte sent; the 24 h upload window and
  `maxFilesPerDevice` bound the abuse.
- `HubServices.housekeeping()` runs `sweep()` on its existing tick; that interval was not tuned for files.
- The console **Files** card is P4; the JSON stats route and the four settings exist now.
- `HubInfo.maxFileBytes` is clamped to `globalStoredBytes` (never below 1 MiB), so the number advertised and the number
  accepted are the same one.