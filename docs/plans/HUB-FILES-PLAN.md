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
| `HUB_FILE_EXPIRED` fallback | `{name}` is "the sender" when the name is unknown (P2) |
| `HUB_FILE_REASON_OFFLINE` | "The hub cannot be reached. It will continue when the connection is back." |
| `HUB_FILE_REASON_SLOW_DOWN` | "The hub asked to slow down. It will continue in a moment." |
| `HUB_FILE_REASON_HUB_PROBLEM` | "The hub had a problem. It will try again." |
| `HUB_FILE_REASON_NO_SPACE` | "There is not enough free space on this device. Free some space and it will continue." |
| `HUB_FILE_REASON_NOT_SIGNED_IN` | "This device is not signed in to that hub." |
| `HUB_FILE_REASON_SIGNED_OUT` | "This device is no longer signed in to the hub." |
| `HUB_FILE_REASON_TOO_LARGE` | "The file is larger than the hub accepts." |
| `HUB_FILE_REASON_EMPTY` | "The file is empty." |
| `HUB_FILE_REASON_UNKNOWN_RECIPIENT` | "The recipient is not part of this organisation." |
| `HUB_FILE_REASON_GONE` | "The file is no longer on the hub." |
| `HUB_FILE_REASON_CONFLICT` | "The hub already has a different file with this id. Send it again." |
| `HUB_FILE_REASON_REFUSED` | "The hub refused the request. Send it again." |
| `HUB_FILE_REASON_INTEGRITY` | "The file that arrived is not the file that was sent, so it was discarded." |
| `HUB_FILE_REASON_SOURCE_CHANGED` | "The file changed while it was being sent. Send it again." |
| `HUB_FILE_REASON_LOCAL_STORAGE` | "The file could not be read or saved on this device." |

The `HUB_FILE_REASON_*` rows were added in P2 (`HubFileText.reason`); they fill the `{reason}` of `HUB_FILE_FAILED`.

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
### P2 explain-back — 2026-10-20

**What P2 must achieve.** Give a phone and the desktop everything they need to move one sealed file through the P1 hub
and to survive the network going away in the middle: a typed HTTP client for the six routes that refuses a hub it is not
enrolled with, per-chunk sealing whose bytes are pinned by golden vectors made with an independent implementation, and an
upload/download engine whose progress lives on disk (a chunk bitmap per file) so that a killed app, a dropped Wi-Fi, a
switch from Wi-Fi to mobile data or a hub restart only costs the chunk in flight. Nothing in P2 knows about chats,
messages or UI; P3 hands it a source and a recipient and receives a ticket (file id, key, hash) to put in a message.

**Files.** `:core:hub-protocol`: `HubContract.HEADER_CHUNK_SHA256` + `SEALED_CHUNK_OVERHEAD_BYTES`, `HubFileIds`, DTO KDoc
fix, `HubFileDtosTest` (golden + 1,000-input fuzz). `:core:security`: `Sha256Stream` (expect/actual next to the other
`PlatformCrypto` primitives), `enterprise/HubFileSealing.kt` (`HubFileKey`, `HubFileLayout`, `HubFileHasher`). `:core:enterprise`:
new package `files/` — `HubFileError`, `HubFilesApi`, `RealHubFilesApi`, `FakeHubFilesApi`, `HubFileStorage` +
`DiskHubFileStorage` + `MemoryHubFileStorage`, `HubFileSource`, `HubFileStateCodec`, `HubChunkBitmap`, `HubFileTransfers`
(the engine) and `HubFileDisk` (expect/actual java.io primitives); `HubRestExecutor` shared with
`RealHubAnnouncementsApi`. `:server`: the create size check counts the sealing overhead (below), and an e2e test drives the
real client engine against the real routes.

**Three nearest existing classes read first.** `RealHubAnnouncementsApi` (pinned-hub + cleartext refusal before the bearer,
401 refresh-once loop, error mapping) — reused by extracting its loop into `HubRestExecutor` instead of copying it a third
time. `HubSealing` (AES-GCM over `aesGcmSeal`, domain-tagged AAD, never-throwing `open`) — same primitives and the same
"null on any failure" rule, but deterministic nonces (index-derived) because a resumed upload must re-produce byte-identical
chunks for the hub's idempotent `PUT`. `OrgDirectorySync` (single-flight per key with a `Mutex`, jittered backoff, injected
clock) — the engine's per-file mutex and retry policy follow it. For disk I/O the precedent is `PlatformCrypto.jvm/android`:
duplicated thin actuals, logic in `commonMain` (CONVENTIONS R5, no `jvmAndAndroidMain`).

**Five ways it is most likely to go wrong.** (1) A resumed upload re-seals a chunk differently (fresh nonce, changed source)
and the hub answers 409 or the recipient's hash fails — so nonces are derived from the index and the source is re-hashed on
resume. (2) The bitmap claims a chunk whose bytes never reached the disk (crash between write and state save) — so the part
file is synced before the bitmap is saved. (3) A hostile or broken hub swaps metadata (chunk size, count, hash) or serves an
oversized body — so every status field is checked against the sealed message and every body is bounded before it is read.
(4) A stalled socket after a network change hangs a transfer for ever — so every request has a timeout that counts as
"offline, retry". (5) Two callers run the same file at once (a retry racing the original) and interleave state writes — so
there is one mutex per file and per direction.

**A plan defect found while reading.** Sealing adds a 16-byte tag per chunk, and P1 compares the *ciphertext* size with
`maxFileBytes`: a file of exactly 50 MB would be refused by 800 bytes, which breaks the owner's "at least 50 MB". The hub
now allows `maxFileBytes + chunkCount × SEALED_CHUNK_OVERHEAD_BYTES`, so the number in the console is the largest
*plaintext* file. Also: the plan's non-goal "no resumable sending after process death" contradicts its own situation 1
(upload resumes at chunk 37 after the app is killed); P2 implements situation 1.

**Tests written first.** `HubFileSealingTest` (golden vectors from Python `cryptography`, tamper/wrong-key/wrong-index/
truncation, layout boundaries), `HubFileStateCodecTest` (round trip, torn/garbage input, fuzz), `RealHubFilesApiTest`
(every status code, pinned hub, cleartext, bounded body), `HubFileTransfersTest` (every interruption in section 7 that the
client owns, on virtual time), and `E2eHubFileTransferContractTest` in `:server` (the real engine against the real routes).

### P2 result — built, unit-tested, NOT deployed, NOT device-verified (2026-10-20)

**Status.** `HF-C1..HF-C4` exist. Nothing in the app calls the engine yet (that is P3), so no phone or desktop can send a
hub file today; the client was driven end to end only by `E2eHubFileTransferContractTest` against the real P1 routes on
the Ktor test host. Nothing is deployed. Device checks: `HUBFILE-C01`..`C06` and `MEAS-HUBFILE-01` in
`docs/testing/TEST-BACKLOG.md` section 4zzzzzzzzzzzz.

**Built.**

| Item | File | Notes |
|---|---|---|
| Wire additions | `core/hub-protocol/.../HubContract.kt`, `rest/HubFileDtos.kt` | `SEALED_CHUNK_OVERHEAD_BYTES` (16), `HEADER_CHUNK_SHA256` (the server's `FileRoutes` now uses it), `HubFileIds.isFileId` / `isSha256` shared by client and server; `HubInfo.maxFileBytes` documented as the **plaintext** limit |
| Streaming hash | `core/security/.../crypto/PlatformCrypto(.jvm/.android).kt` | `Sha256Stream` expect/actual next to the existing primitives |
| Sealing | `core/security/.../enterprise/HubFileSealing.kt` | `HubFileKey` (32-byte AES key + 8-byte nonce prefix, text form `hfk1.<base64>`, redacted `toString`), `HubFileLayout` (chunk maths, refuses a layout past `MAX_FILE_CHUNKS`), `sealChunk` / `openChunk` (never throws, null on any failure), `HubFileHasher` |
| Shared REST loop | `core/enterprise/.../HubRestExecutor.kt` | pinned hub, cleartext refusal, bearer, one refresh on 401 — extracted from `RealHubAnnouncementsApi`, which now uses it (its 13 tests unchanged and green) |
| Client | `core/enterprise/.../files/HubFilesApi.kt`, `RealHubFilesApi.kt`, `FakeHubFilesApi.kt`, `HubFileError.kt` | the six routes; a chunk body is read to at most `expected + 1` bytes; every status code mapped to one `HubFileError`; the fake is a whole in-memory hub (sweep, lost chunks, tampering, groups, offline, stalls) |
| Storage | `files/HubFileStorage.kt`, `HubFileDisk.kt` + jvm/android actuals | `DiskHubFileStorage` (`<root>/up|down/<fileId>.state|.part`, write-temp-then-rename, `sync` before a bit is saved, free-space query), `MemoryHubFileStorage` for tests, file and byte-array sources |
| Records | `files/HubFileState.kt` | `HubChunkBitmap`, `UploadState`, `DownloadState`, line-format `HubFileStateCodec` with a SHA-256 check line; every field shape-checked, layout cross-checked against `HubFileLayout` |
| Engine | `files/HubFileTransfers.kt` | resumable upload and download, one mutex per file and direction, `HubTransferPolicy` (60 s per request, 6 transient failures in a row, 1–30 s jittered backoff, `Retry-After` capped at 120 s), probes `hubfile.upload` / `hubfile.download` |
| Server | `server/.../files/FileStore.kt` | `create` allows `maxFileBytes + chunkCount × 16` of ciphertext, so the console number is the largest plaintext file; `!!` on request fields replaced by local values |
| Docs | `docs/protocol.md`, `docs/security.md`, `docs/testing/PROBES.md`, `docs/testing/TEST-BACKLOG.md` | sealing and client behaviour paragraphs, four client threat rows, two probes, seven device checks |

**Sealing, as built.** AES-256-GCM per chunk. Nonce = `noncePrefix(8) || index(4, big-endian)`. AAD =
`"flash-hub-file-v1" || 0x00 || fileId (32 ASCII) || index (4) || chunkCount (4) || plainBytes (8)`. A ciphertext chunk is
its plaintext plus a 16-byte tag; the default ciphertext chunk is 1 MiB (1 048 560 plaintext bytes). 50 MiB of plaintext =
51 chunks = 52 429 616 ciphertext bytes. Golden vectors were produced with Python `cryptography` 4x (an independent AES-GCM)
and are asserted byte for byte in `HubFileSealingTest`.

**Where the build deviates from this plan, and why.**

| # | The plan said | Built | Why |
|---|---|---|---|
| D8 | AAD = `fileId || index` | AAD adds a domain tag, `chunkCount` and `plainBytes` | a hub that truncates the file (drops the last chunks and lies about the count) or swaps two files' chunk 0 must fail to open, not only a moved chunk |
| D9 | a 32-byte file key | 40 bytes: key + 8-byte nonce prefix | the nonce must be deterministic (a resumed upload re-produces byte-identical chunks for the hub's idempotent `PUT`) and still unique per file; a random prefix per file gives that without a counter on disk |
| D10 | `FlashResult` for the API | `HubFileResult` (`Ok` / `Err(HubFileError)`) | the engine needs to tell transient from terminal errors exhaustively; a typed sealed error does that, `FlashResult`'s string error does not |
| D11 | non-goal: "no resumable sending after process death" | upload **and** download resume after process death | the plan's own situation 1 (resume at chunk 37 after the app is killed) needs it, and the record costs one small file |
| D12 | `maxFileBytes` compared with the ciphertext size (P1) | compared with the plaintext size, plus the per-chunk tag allowance | otherwise a file of exactly 50 MB is refused by 816 bytes |
| D13 | (no word on the REST loop) | `HubRestExecutor` shared with announcements | the quality contract bans a third copy of the pinned-hub/refresh loop |

**Answer to "does it resume on the LAN and on the internet".** Within the hub path, yes, and the engine does not need to
know which: every chunk is one HTTPS request to the pinned hub address, the chunk bitmap lives on disk keyed by file id
and hub, and a request that runs past 60 s counts as offline. A Wi-Fi to mobile-data switch, a laptop moving from the
office LAN to home, an app kill or a hub restart therefore cost at most the chunk in flight (tested on the fake hub and
on the real routes). What does **not** exist: a transfer that starts as a direct LAN transfer (ADR-014/015/018, which
resumes on its own done-set) and continues through the hub, or the reverse. Per-chunk sealing keeps plaintext chunk *i*
mapped to ciphertext chunk *i*, so such a hand-over could be designed later; it is an owner decision, not part of P2.

**Evidence.**

| Step | Test | Result |
|---|---|---|
| HF-C1 | `HubFileDtosTest` (8: golden JSON, 1 000-input fuzz, quoted numbers, id shapes) | pass on jvm (123 in `:core:hub-protocol:jvmTest`) and Android host (117) |
| HF-C2 | `HubFileSealingTest` (12: golden vectors, tamper / wrong key / wrong index / wrong count / wrong length / truncation, key text, layout bounds, 50 MiB layout) | pass on jvm (161 in `:core:security:jvmTest`) and Android host (252) |
| HF-C3 | `RealHubFilesApiTest` (10: every status code, pinned hub, cleartext, 401 refresh once, oversized and short bodies, `Retry-After`) | pass on jvm and Android host |
| HF-C4 | `HubFileStateCodecTest` (8: round trip, every torn write, 1 000 flipped bytes, 1 000 garbage inputs, re-signed bad shapes), `HubFileTransfersTest` (32: every client-side situation of section 7 and those below, on virtual time), `DiskHubFileStorageTest` (5, jvm) | `:core:enterprise:jvmTest` **255 tests, 0 failed**; `:core:enterprise:testAndroidHostTest` **154 tests, 0 failed** |
| end to end | `:server` `E2eHubFileTransferContractTest` (5): an exact 50 MiB file through the real routes sealed and intact (51 chunks, 10–12 s on the test host), one byte over refused by the client and by the hub, upload and download cut half-way resume, a removed group member is refused, a cancelled upload is gone | pass |
| server | `FileStoreTest` 24, `FileRoutesTest` 10 | pass. Full `:server:test`: **572 tests, 1 failure** — `E2eRelayContractTest.aReconnectStormLeavesExactlyOneLiveSession` (relay code this phase did not touch, same load-flake family as P1); 8/8 green when rerun alone with the 47 file tests |
| hosts | `:app:compileDebugKotlin`, `:desktop:compileKotlinJvm` | BUILD SUCCESSFUL |
| load flake | `OrgDirectorySyncTest` ORGT-U16 (not touched by P2) | failed its 25 s wall-clock budget at 33.7 s once when both enterprise hosts ran together; 20.8 s and green alone |

**Mutation checks (each caught, each restored, suites green afterwards).**

| # | Mutation | Caught by |
|---|---|---|
| M1 | download skips the sender's ciphertext-hash check | `withoutAPlaintextHashTheSendersCiphertextHashStillCatchesADamagedLocalCopy` (added after the mutation first survived) |
| M2 | download ignores the hub's status metadata against the ticket | 1 test in `HubFileTransfersTest` |
| M3 | a resumed upload makes a new key | 5 tests in `HubFileTransfersTest` |
| M4 | `RealHubFilesApi` does not check a chunk body's length | 1 test in `RealHubFilesApiTest` |
| M5 | the record codec drops the layout cross-check | `valuesOutsideTheirShapeAreRefusedEvenWithAValidCheck` |
| M6 | a chunk's bit is saved before its bytes | `aChunkIsMarkedDoneOnlyAfterItsBytesAreWritten` (added after the mutation first survived) |
| M7 | the chunk index is dropped from the AAD | 2 tests in `HubFileSealingTest` |
| M8 | `HubRestExecutor` drops the pinned-hub check | 2 tests (files and announcements) |

**Adversarial findings (what reading and testing turned up, and what was done).**

1. P1 compared ciphertext with a plaintext promise: a 50 MB file was refused by 816 bytes → hub allowance (D12).
2. The plan contradicted itself on resume after process death → implemented (D11).
3. A record could carry a `chunkCount` that disagrees with its sizes and still decode → layout cross-check (M5).
4. kotlinx.serialization decodes a quoted number (`"88"`) as a number even with `isLenient = false` → documented in the test; harmless because every value is range-checked after decoding.
5. A recipient asking for a file still uploading gets 404, not "not ready" → the engine treats a 404 before the ticket's file is complete as `NotFound` and P3 must only deliver the ticket after `complete`.
6. A torn upload record (app killed between `create` and the save) left an orphan on the hub → the engine cancels and restarts on a create conflict (at most twice).
7. A record whose key no longer decodes would have re-sealed under a fresh key with the same nonces → the upload is withdrawn and ends `Failed(LocalStorage)`.
8. A source edited between two runs would reuse nonces with other plaintext (GCM key-stream reuse) → re-hash on resume, `SourceChanged` + `DELETE`.
9. A failed record save in the middle of an upload must not delete the key of an upload the hub already holds → it ends `Interrupted`, not `Failed`.
10. A half-open socket after a network switch hung a request for ever in the first draft → `requestTimeoutMs` 60 s maps to `Offline`.
11. A response whose declared `Content-Length` disagrees with the body surfaces from Ktor as an I/O error → mapped to `Offline` (retry), recorded in `RealHubFilesApiTest`.
12. A refused upload stayed on the hub until the sweep → the engine cancels an upload it created and could not finish (except on `Conflict`, where the file is someone else's).
13. Two mutations (M1, M6) first survived → two tests added; both now fail the mutation.

**Extra situations covered by tests (beyond section 7).**

- A byte of the local part file changes after its chunk was marked done: the whole-file check fails, the download refetches once in full and succeeds (`aDamagedLocalCopyIsFetchedOnceMoreThenRefused`).
- The hub serves a flipped byte in one chunk: it does not open, the download ends `Integrity` and nothing is handed over or left behind (`aTamperedChunkIsRefusedAndNothingIsHandedOver`).
- Two callers start the same upload at once: one waits on the mutex and finds it done (`twoCallersOfTheSameUploadDoNotRace`).
- Free space below what the file needs: `Interrupted(NoSpace)`, and the same download continues once space is freed (`aFullDiskPausesTheDownloadAndFreedSpaceContinuesIt`).
- `Retry-After: 100000`: the wait is capped at 120 s (`aHubAskingForAnAbsurdWaitIsHeardForTwoMinutesAtMost`).
- A record for another hub: never resumed (`anUploadIsNotResumedAgainstAnotherHub`).
- A download record overwritten with garbage: the download starts over and fetches every chunk once (`aTornDownloadRecordStartsTheDownloadOver`). A part file shorter than its bitmap claims (power loss despite the sync) is caught only by the whole-file check, not tested separately.

**Known limits, recorded rather than hidden.**

- Nothing calls the engine: P3 wires it into messaging (ticket in the sealed message, `releaseUpload` after the message is stored) on both hosts.
- No device test, no deployed hub, no real network numbers; the only throughput figure is the synthetic test host's.
- The record's check line is an integrity check against torn writes, not a MAC (`docs/security.md`).
- Auto Backup / desktop backup exclusion of `<root>/up` (which holds file keys) is P3 work.
- Hub-to-LAN or LAN-to-hub hand-over of one transfer is not designed (see the answer above).
- The Python vector generator (`hubfile_vectors.py`, Python 3 + `cryptography`) was run once in a scratch folder and is not in the repository; the vectors in `HubFileSealingTest` are the record, and any independent AES-GCM reproduces them from the inputs printed in the test.
