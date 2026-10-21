# Server & Hub Internals Audit — Relay, Mailbox, Directory, Announcements, Identity, Hub Client

**Date:** 2026-10-21
**Status:** AUDIT COMPLETE — **docs only**. No product code was changed, nothing was built, nothing was run on a device. Every defect below is read from the source at `df212ac2` (branch `dev`) and is **not** device-verified; the hub is still documented as experimental and undeployed.
**Decisions:** the owner answered all five questions on 2026-10-21 (§8) and asked that they be **recorded, not implemented**; see ADR-222 and the implementation plan in §11.
**Reviewer:** an AI coding session (owner-requested review; "review the server and hub for bugs, plus needed and optional features").
**Scope:** the areas the two previous reviews left unread — relay router internals, mailbox, files read path, directory sync and people paging, announcement store, identity/enrolment/authorisation, and the hub REST client in `:core:enterprise`. The admin console UI, admin CSRF/step-up and the `FileStore` quota machinery were reviewed on 2026-10-19 and 2026-10-21 and are **not** re-litigated here.

Related work:

- [`docs/audit/2026-10-19-server-hub-end-to-end-review.md`](2026-10-19-server-hub-end-to-end-review.md) — FIX-1..FIX-3 fixed; **F-1..F-4 remain open** and are cross-referenced, not repeated, in §6.
- [`docs/audit/2026-10-21-brutal-code-review.md`](2026-10-21-brutal-code-review.md) — F1–F10; its server section (F2–F6) is complementary.
- [`docs/protocol.md`](../protocol.md) "Hub relay wire v2", "Hub files API", "Directory", "Announcements".
- [`docs/plans/HUB-QUALITY-CONTRACT.md`](../plans/HUB-QUALITY-CONTRACT.md), [`docs/plans/HUB-FILES-PLAN.md`](../plans/HUB-FILES-PLAN.md), [`docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md`](../plans/HUB-OFFLINE-ORG-TRUST-PLAN.md).

---

## 1. Executive summary

Eighteen findings: **one high** (DIR-04, found while answering §8.1 — the client cannot decode a legal sync page), **four medium**, four medium-low, and the rest low or informational. None is a remote code execution or a broken-crypto defect; the TLS/identity/revocation core held up under reading. The recurring theme is **check-then-act without a transaction or a cursor**, **a size limit on one side of the wire that the other side does not honour**, and **authorisation attributes that live in two places and drift**:

| ID | Sev | Layer | Defect | Status |
|---|---|---|---|---|
| **DIR-04** | **High** | `:server` + `:core:enterprise` | One legal `/directory/sync` page is larger than the client can decode. The client asks for 500 members and enforces a **64 KiB** body cap on the answer; a member DTO is ~576 B, so a page is refused once it carries ~110 members, or ~70 small group rosters on top of any members at all. Org trust, colleagues and hub group rosters therefore never land for a real organisation, and the failure reads like a server bug. | FIXED 2026-10-21 (step 1; automated tests, not device-verified) |
| **DIR-01** | Medium | `:server` directory | `/directory/people` cursor pagination is applied *after* the result list is truncated, so page 2 is empty and only the first `limit` people (100 by default, 500 max) are ever reachable. Its only paging consumer today is hub presence, which therefore caps at 500 people; the route is unusable for any future paged consumer. | OPEN |
| **RLY-01** | Medium | `:server` mailbox | One page of undecryptable sealed rows stops a device's mailbox drain for ever (until the rows expire): the cursor never advances and no `FLASH_QUEUE_STATE state=DROPPED` is ever sent. | OPEN |
| **ANN-01** | Medium | `:server` announcements | Announcement writes run on an autocommit connection: `nextSeq` can hand two rows the **same** `seq` (a delta-feed item is then skipped by clients), `expectedSeq` is a TOCTOU check (silent lost update), and the pin limit and `clientRequestId` idempotency are racy. | FIXED 2026-10-21 (step 2; automated tests, not device-verified) |
| **DEV-01** | Medium | `:server` identity | Moving a device to another department in the console changes nothing on the wire until the device's certificate is **renewed** (certificates live 7 days, renewed in the last day). The live session keeps the old department for relay, announcement and directory scoping, and the console reports the move as done. | OPEN |
| **ANN-02** | Medium-low | `:server` announcements | A console edit silently **removes an announcement's expiry**: the edit form never sends `expiresAtMs` and the store treats `null` as "clear". An announcement that should have expired stays live for ever. | FIXED 2026-10-21 (step 3; automated tests, not device-verified) |
| **DIR-02** | Medium-low | `:server` directory | Tombstones are not privacy-filtered in `/directory/sync`: the device ids of removed members of a **hidden** department reach every syncing device. `docs/protocol.md` promises the opposite. | OPEN |
| **DIR-03** | Medium-low | `:server` directory | A tombstone is never cleared when an entity comes back: a device id that is revoked and later re-enrolled is deleted by every peer on every sync, for ever. | OPEN |
| **DIR-05** | Medium-low | `:server` + `:core:enterprise` | The last sync page carries "the full current set" of group rosters and the client **replaces** its stored set with what arrived, but the server silently truncates the list at `MAX_ROSTERS_PER_SYNC` (and skips groups above 200 members). A device in more groups than the cap deletes the rosters of groups it is still in, and cannot tell that apart from having left them. Unreachable at 256 groups today; reachable at the protocol's 64. | FIXED 2026-10-21 (step 1; automated tests, not device-verified) |
| **AUTH-01** | Low | `:server` + deployment | Bearers are memory-only, and `/auth/challenge` + `/auth/verify` share **one per-IP bucket** (burst 30, then 1/s). After a hub restart, an office behind one NAT with more than ~15 devices cannot sign them all in for minutes. | OPEN |
| **RLY-02** | Low | `:server` relay | Group fan-out is limited by **frames**, never by bytes: 60 frames/s × 64 KiB payload × up to 256 members is a per-sender egress amplifier. | OPEN |
| **OPS-01** | Low | `:server` observability | `MailboxStats` runs blocking JDBC on the caller's thread and is called from request handlers and the metrics scrape. | OPEN |
| **RLY-03** | Low | `:server` relay | `draining` is set outside the hand-off gate, so a live frame can be delivered in the instant before a drain starts (ordering). | OPEN |
| **CLI-01** | Low | `:core:enterprise` | A 401 refresh is not single-flight: *M* concurrent 401s cause *M* serialised full sign-ins, which also feeds AUTH-01. | OPEN |
| **CLI-02** | Low | `:core:enterprise` | `HubSession.bearer()` holds a mutex across two network round-trips with no client timeout: an unreachable hub stalls every hub call on the device. | OPEN |
| **CLI-03** | Low | `:core:enterprise` | Eight hub-client call sites read `response.bodyAsText()` with no size bound; the 64 KiB `HubRestCodec.decode` guard runs afterwards. | OPEN |
| **FL-01** | Low | `:server` files | `FileStore.access` answers `404` for a non-sender whose blob is not `READY`, so the `409 NOT_READY` in `readChunk` is unreachable for recipients and the two paths disagree. No user-visible trigger found in the current app flow. | OPEN |
| **DOC-01** | Info | docs/code | Five places where the documentation and the code disagree (group rosters per sync 64 vs 256 — **decided: 64**, console pin limit 400 vs 409, "the registry decides role and department", "the first 2,000 people" of presence, dead `IdentityStore.setRole`/`serialsOf`). | OPEN |

Candidates that were checked and **rejected** are listed in §2.3. The two most likely to be re-filed by a future reviewer are recorded there by name: "expired announcements stay visible for ever" (the store filters `expires_at` on every read, and the client also filters with `FlashHubMath.isLive`) and "a multi-page sync loses deletions" (the client takes the version from the *first* page, so a later tombstone is reported on the next sync).

Section 7 splits the feature recommendations the owner asked for into **needed** (the hub is not safe to deploy without them) and **optional** (nice, or deferred by an existing ADR/FO item).

Section 8 records the owner's answers to the five open questions as **decisions** (ADR-222), and section 11 turns them into an ordered implementation plan. **Nothing in section 8 is implemented.**

---

## 2. Method

### 2.1 What was read

Server, in full unless noted:

- `relay/RelayFrameRouter.kt`, `relay/RelaySession.kt`, `relay/RelayConnectionPool.kt`, `relay/RelayPolicy.kt`
- `mailbox/MailboxQueue.kt`, `mailbox/MailboxStore.kt`, `mailbox/TtlSweeper.kt`
- `files/FileStore.kt` (create / access / read / stats), `files/FileRoutes.kt`
- `directory/OrganizationDirectory.kt` (sync, people, tombstones, register/remove), `directory/DirectoryRoutes.kt`, `directory/DirectoryPrivacy.kt`
- `announcements/AnnouncementStore.kt`, `announcements/AnnouncementRoutes.kt`, `announcements/AnnouncementAdminRoutes.kt`
- `identity/IdentityStore.kt`, `identity/SealingKeyRoutes.kt`, `auth/ChallengeResponseAuth.kt`, `auth/EnrollmentService.kt`, `auth/AuthRoutes.kt`, `auth/BearerTokenStore.kt`, `auth/SecretBox.kt`, `auth/RateLimiter.kt`
- `storage/ServerDatabase.kt`, `security/ApiGate.kt`, `security/ClientAddress.kt`, `security/RequestPathGuard.kt`, `security/SqliteRevocationService.kt`, `rest/RestSupport.kt`
- `main` (`Main.kt`), `HubServices.kt` (build wiring), `config/ServerConfig.kt`
- `resources/admin-ui/js/views/announcements.js` (the expiry field only)

Client:

- `core/enterprise/.../EnterpriseDirectoryClient.kt`, `HubSession.kt`, `OrgDirectorySync.kt`, `HubPresence.kt`, `DirectoryPrivacyClient.kt`
- `core/enterprise/.../announcements/RealHubAnnouncementsApi.kt`
- `core/enterprise/.../files/RealHubFilesApi.kt`, `HubFileTransfers.kt`
- `core/engine/.../hubfiles/HubFileBinding.kt`

### 2.2 How a finding was accepted

For each candidate: read the code path end to end (route → store → client), quote the exact statement, name a **trigger**, name the **consequence**, and name the test that would have caught it. Anything that could not be closed this way was dropped. Nothing here is proven by execution — the hub has no device run in this repository yet, and this pass deliberately did not build (§36's build lock was not needed).

### 2.3 Candidates examined and **rejected**

| Candidate | Why it is not a defect |
|---|---|
| "An expired announcement stays visible for ever" | The store filters `expires_at` on every read — initial feed, delta feed, search and get (`AnnouncementStore.kt:409-416, 542, 572, 596`) — and the client filters again with `FlashHubMath.isLive` (`ui/chat/.../FlashHubMath.kt:462-468`). The purge hard-deletes expired rows without a tombstone on purpose and the comment at `:668-669` states exactly that reasoning. No defect, no doc contradiction. |
| "A multi-page `/directory/sync` loses a deletion that happens between pages" (`removedDeviceIds` only on the first page, `version` on every page) | `OrgDirectorySync.kt:238-244` takes `serverVersion` from the **first** page only. A tombstone created after page 1 has `deletedAtVersion >` that stored version, so the next sync reports it. Records a cosmetic inconsistency at most. |
| "The initial announcement feed truncates and clients never learn what they missed" | The plan caps the initial feed at the newest N on purpose; the delta feed catches up from `since`. |
| "Per-frame revocation is a DB hit on the hot path" | `SqliteRevocationService.isRevoked` reads an in-memory snapshot (two hash lookups). Not a defect. |
| "Re-enrolment of an ACTIVE device leaves the old serial valid" | True (`IdentityStore.kt:209-247` inserts a new row in `hub_cert_history` and never revokes the old serial), but the re-issue requires the **same** subject public key (`DEVICE_CONFLICT` otherwise), so the old certificate grants exactly what the new one does; revocation is by device id as well as serial. Recorded as INFO in §4, not as a defect. |

---

## 3. Defects

### DIR-01 — `/directory/people` pagination returns at most one page (Medium)

**Files**
- [`server/.../directory/DirectoryRoutes.kt:186-195`](../../server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryRoutes.kt)
- [`server/.../directory/OrganizationDirectory.kt:704-712`](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt)
- [`server/.../directory/DirectoryPrivacy.kt:15-19`](../../server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryPrivacy.kt)
- Consumers: [`core/enterprise/.../HubPresence.kt:37-57`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubPresence.kt) (the only production pager), `DirectoryPrivacyClient`.

**Symptom.** A caller that pages `/directory/people` gets one page and then `nextCursor == null`, as if the directory ended. Presence polling (`hubOnlineDeviceIds`) therefore reports every colleague past the first page as offline, for ever.

**Root cause.** The page is cut **before** the cursor is applied:

```kotlin
// OrganizationDirectory.kt:704-712 — the take() is inside the store
members.values
    .filter { q.isEmpty() || it.displayName.lowercase().contains(q) }
    .sortedWith(compareBy({ it.displayName.lowercase() }, { it.deviceId }))
    .take(limit.coerceIn(1, 500))
```

```kotlin
// DirectoryRoutes.kt:186-195 — the cursor only re-filters those already-taken rows
val matches = directory.searchPeople(actor, q ?: "", departmentId, limit + 1)
val afterCursor = if (cursor == null) matches else matches.filter {
    val folded = it.displayName.lowercase()
    folded > cursor.foldedName || (folded == cursor.foldedName && it.deviceId > cursor.deviceId)
}
val page = afterCursor.take(limit)
val next = if (afterCursor.size > limit && page.isNotEmpty()) { /* … */ } else null
```

On page 2 the store again returns the **first** `limit + 1` people; the cursor drops all of them, so `afterCursor` is empty (or one row when the page boundary fell inside a duplicate name) and `next` is `null`. `PEOPLE_DEFAULT_LIMIT` is 100 and `PEOPLE_MAX_LIMIT` is 500 (`DirectoryPrivacy.kt:15-16`).

**Trigger.** An organisation with more than `limit` listable members **and** a client that pages the route. Today that is presence alone, which requests 500 per page (`HubPresenceDefaults.PAGE_SIZE`), so the trigger is an organisation of **more than 500** people. The Hub tab's People list is built from the directory-sync snapshot (`HubFacade.people`), not from this route, so it is unaffected — which is why this is not user-visible in a small organisation.

**Consequence.**
- For an organisation over 500 people: every colleague beyond the first 500 (by folded name) is permanently "Offline" in the chat header even while their messages arrive. `MAX_PAGES = 4` cannot help, because page 2 is always empty; the "first 2,000 people" comment (`HubPresence.kt:76-80`) describes a ceiling the server cannot reach.
- The route is unusable for any future paged consumer (a People search box over the whole directory is the obvious next one), and it fails silently: not a log line, not an error, just a well-formed empty page.
- Any client that does page it gets a stable, wrong answer, so the bug will not surface in integration tests that use fewer than `limit` members.

**Fix direction.** Move the cursor into the store: add `afterFoldedName: String?, afterDeviceId: String?` to `searchPeople` and apply the comparison **before** `take(limit + 1)`. Alternatively page by index in the route over the full (unlimited) sorted list, but that materialises the whole directory per request. Keep the "minimum query length 2" rule as is.

**Test that would catch it.** A store-level test with 250 members and `limit = 100`: page 1 (100 rows) → page 2 must return the next 100 followed by page 3 (50) and `nextCursor == null`, with the union equal to the full sorted list and no duplicates. A client-level test for `hubOnlineDeviceIds` with an online device at position 120 of 250.

**Status:** OPEN.

---

### RLY-01 — A page of undecryptable mailbox rows stops a device's drain for ever (Medium)

**Files**
- [`server/.../mailbox/MailboxQueue.kt:219-269`](../../server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt)
- [`server/.../relay/RelayFrameRouter.kt:490-508`](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt)
- [`server/.../mailbox/TtlSweeper.kt:87-105`](../../server/src/main/kotlin/com/transfer/flash/server/mailbox/TtlSweeper.kt)
- [`server/.../auth/SecretBox.kt:97-122`](../../server/src/main/kotlin/com/transfer/flash/server/auth/SecretBox.kt)

**Symptom.** A device reconnects and receives nothing — no live messages, no replay — until the offending rows expire (the mailbox TTL, 7 days by default). The sender is never told the messages were dropped; it keeps showing them as "waiting".

**Root cause.** `fetchPage` applies `LIMIT` to the raw rows and then *skips* the ones it cannot open:

```kotlin
// MailboxQueue.kt:239-249
val plain = if (rs.getInt(11) == 1) {
    try { secrets.openBytes(raw, payloadContext(...)) } catch (e: RuntimeException) {
        logger.error("Queued message seq={} cannot be opened ({}): skipped, not deleted", ...)
        continue          // <- row is neither returned nor deleted
    }
} else raw
```

and the drain treats an empty page as "mailbox finished":

```kotlin
// RelayFrameRouter.kt:490-508
while (session.isOpen) {
    val page = mailboxQueue.fetchPage(session.deviceId, session.cursor, policy.drainPageSize)
    if (page.isEmpty()) return              // <- cursor never advanced past the skipped rows
    for (row in page) { …; session.cursor = row.seq }
}
```

`fetchPage` orders by `seq ASC` and returns the **first** `drainPageSize` (100) rows after the cursor. If all 100 are unopenable, the returned list is empty, the drain ends, and the cursor is still before them, so every later drain repeats the same empty page.

**Trigger.** Any full page of rows sealed under a data key the hub no longer has: `data.key` lost or restored from a backup without it, or a data directory rebuilt under an existing database (the file is created on first use, `SecretBox.loadOrCreate`, so a fresh data dir silently generates a **new** key). Also any row whose AAD no longer matches (a restored row moved to another `msg_id`).

**Consequence.**
- The affected device is permanently cut off from the hub relay while the bad rows live: `handOffLive` is refused while `draining`, and the drain never finishes.
- No `FLASH_QUEUE_STATE state=DROPPED` is emitted for the skipped rows anywhere in the code (contrast `RelayFrameRouter.kt:385-388`, which is only used when an *enqueue* fails). The sender's UI shows "queued" until the TTL sweeper finally expires the rows.
- The log line is `logger.error` but carries no device id and no count, so an operator cannot tell which device is stuck.

**Fix direction.** Advance past an unopenable row and report it: on `open` failure, record the seq in a "skipped" list, `delete` the row (or mark it), notify the origin `QueueEvent.DROPPED` (the same way `spill` does), and let the caller advance `session.cursor` to the highest seq of the page even when the page produced no messages. A `pendingCount` warning at startup when `sealed = 1` rows exist and the active data key cannot open a sample is the operational half of the fix.

**Test that would catch it.** Insert 3 sealed rows with `SecretBox.ofKey(otherKey)` followed by 3 openable rows, connect, drain: expect the 3 good rows delivered **in the same drain**, 3 `DROPPED` frames to the origin, and the 3 bad rows quarantined (not retried, not deleted until the TTL).

**Decision:** §8.3 — quarantine: keep the row (`undeliverable_reason`), skip it, tell the sender `DROPPED`, let it age out with the 7-day TTL.

**Status:** OPEN.

---

### ANN-01 — Announcement writes are not atomic: duplicate `seq`, lost updates, racy pin limit and idempotency (Medium)

**Files**
- [`server/.../announcements/AnnouncementStore.kt:102-177`](../../server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt) (`createAnnouncement`), `:192-242` (`updateAnnouncement`), `:778-783` (`nextSeq`), `:794-799` + `:927`
- [`server/.../storage/ServerDatabase.kt:248-287`](../../server/src/main/kotlin/com/transfer/flash/server/storage/ServerDatabase.kt)

**Symptom.** Two announcements share a `seq`; a client that syncs with `since` skips one permanently. An edit built from an announcement the user just read silently overwrites a newer edit. A 4th pin can appear. A retried `POST` with the same `clientRequestId` creates a second announcement.

**Root cause.** Every write path uses `db.withConnection` (autocommit) instead of `withTransaction` (`BEGIN IMMEDIATE`). `withConnection` documents only "Executes a block within a pooled JDBC Connection" (`ServerDatabase.kt:248-254`) and the pool runs with `isAutoCommit = true` (`ServerDatabase.kt:138`), so these are plain autocommit statements:

```kotlin
// AnnouncementStore.kt:778-783
private fun nextSeq(conn: Connection): Long {
    conn.createStatement().use { st -> st.execute("UPDATE announcement_state SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE key = 'seq'") }
    return readState(conn, "seq")?.toLongOrNull() ?: 1L      // <- reads whatever is current, not what this call produced
}
```

Consequences, each mechanically distinct:

1. **Duplicate `seq`.** T1 executes the `UPDATE` (counter 5→6), T2 executes it (6→7), then T1's `readState` returns **7**. T1 writes `seq = 7` and T2 writes `seq = 7`. Clients page the changes feed with `seq > since` and a stable order, so one of the two is skipped until something else moves the cursor past it.
2. **`expectedSeq` is a TOCTOU check.** `updateAnnouncement` reads the row, compares `current.seq != expectedSeq`, then issues `UPDATE announcement SET … WHERE id = ?` (`:219-242`) — **no `AND seq = ?`**. Two admins editing the same announcement both pass the check; the second silently wins. The whole optimistic-concurrency mechanism (including the console's 409 conflict dialog, `announcements.js:454-463`) therefore only protects against edits that are slow enough to overlap the *read*.
3. **Pin limit.** `checkPinLimit(conn…)` then `UPDATE … SET pinned = 1` on separate statements: two concurrent pins can both see 2 and both land.
4. **Idempotency.** `findByClientRequestId` then `INSERT … announcement_request … INSERT OR REPLACE` (`:151`). Two concurrent retries both find nothing, both insert an announcement, and the second `INSERT OR REPLACE` overwrites the `client_request_id` row — the id now points at the second copy, and the first is an orphan duplicate.

The production pool is 10 connections and Ktor serves handlers concurrently, so this is not a theoretical race; it needs only two admins (or one retrying client) within the same millisecond window. The directory and mailbox stores already do this correctly with `db.immediate`/`ioTransaction` — the announcement store is the outlier.

**Trigger.** Two concurrent `POST /api/v1/announcements`, two concurrent `PUT`, two concurrent pins, or a client retry of `POST` with the same `clientRequestId`.

**Consequence.** A permanent, silent gap in the announcement delta feed (the one case a client can never recover from without a full resync); a lost edit the conflict prompt was supposed to prevent; a 4th pin; a duplicated announcement that no client asked for.

**Fix direction.** Wrap all four paths in `db.withTransaction` (i.e. `BEGIN IMMEDIATE`, which the class already has available through `ServerDatabase`). With an immediate transaction, `nextSeq`'s UPDATE+SELECT is safe, the `expectedSeq` check becomes atomic, and the pin count and idempotency check stop racing. Add `AND seq = ?` to the update for defence in depth. Note `createAnnouncement` is called from a route coroutine: use `database.ioTransaction`.

**Test that would catch it.** A two-thread test per path, mirroring the existing `FileStore` quota race test: 20 concurrent creates must produce 20 distinct `seq` values; 2 concurrent updates with the same `expectedSeq` must produce exactly one success and one `AnnouncementConflictException`; 2 concurrent pins at 2 → 1 success + 1 `AnnouncementPinLimitException`; 2 concurrent creates with one `clientRequestId` → one row.

**Status:** OPEN.

---

### DEV-01 — A department move in the console does not authorise anything until the device renews its certificate (Medium)

**Files**
- [`server/.../admin/AdminApi.kt:454-468`](../../server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt)
- [`server/.../auth/ChallengeResponseAuth.kt:240-247`](../../server/src/main/kotlin/com/transfer/flash/server/auth/ChallengeResponseAuth.kt)
- [`server/.../identity/IdentityStore.kt:250-282`](../../server/src/main/kotlin/com/transfer/flash/server/identity/IdentityStore.kt)
- [`server/.../auth/EnrollmentService.kt:310-375`](../../server/src/main/kotlin/com/transfer/flash/server/auth/EnrollmentService.kt), `RenewalPolicy`

**Symptom.** An admin moves a device from Engineering to Sales. The console, the directory and the audit log all say so; the device keeps receiving Engineering's department announcements, keeps its Engineering relay scope, and the Hub tab keeps showing it in Engineering. Nothing indicates the change has not taken effect.

**Root cause.** The change is written to the registry and the directory, and nothing else:

```kotlin
// AdminApi.kt:462-465
d.identities.setDepartment(id, target)
d.directory.getDepartmentMember(id)?.let { d.directory.registerMember(it.copy(departmentId = target.orEmpty())) }
if (previous != null && previous != target.orEmpty()) d.directory.removeFromAdmins(previous, id)
```

But authorisation reads the **certificate**, not the registry:

```kotlin
// ChallengeResponseAuth.kt:240-247
return Result.success(ServerDevicePrincipal(
    deviceId = deviceCert.deviceId,
    role = deviceCert.role,             // <- from the presented certificate
    department = deviceCert.department, // <- from the presented certificate
    certSerial = deviceCert.serial,
))
```

and `HubPrincipal.role`/`departmentId` are what `broadcastAnnouncement` (`RelayFrameRouter.kt:804-818`) and the relay/directory scoping use. A certificate is only re-issued by `POST /enroll/renew`, which is restricted to `RenewalPolicy`'s window — a day before expiry — and `IdentityStore.renew` is the only place that reads the registry's current role/department (`IdentityStore.kt:272`, `issue(serial, current)`). A device certificate lives **7 days** (`DeviceCert.DEFAULT_VALIDITY_MS`), and the client's five-minute maintenance loop renews it inside the last day (`EnterpriseHub.maintenanceOnce`), so the move lands within 7 days for a device that is running — and never for a device that stays offline past expiry plus the 3-day grace, which needs a new invite. The device is not kicked either, so a killed connection comes back with the same certificate.

The KDoc on `IdentityStore.renew` claims the opposite of what the code does at the point of use — "the registry (not the presented certificate) decides role and department" (`IdentityStore.kt:252`) is true for *renewal* only; `ChallengeResponseAuth` trusts the presented certificate.

**Trigger.** Move a device between departments while it is enrolled. (A *role* change is not reachable from the console: `PUT /admin/api/v1/devices/{id}` accepts a rename and `moveToDepartment` only — `AdminApi.kt:448-468` — so roles change only through a new invite. This keeps the defect at Medium: it is a freshness problem, not a privilege-downgrade window.)

**Consequence.** A stale authorisation attribute on the wire, for up to 7 days: a moved employee keeps reading the old department's announcements and sees the old department's people as *their* scope, while the directory statement already shows them in the new department to everybody else (`registerMember` re-signs it immediately). The two views disagree, and the console reports the move as done. Revocation still works immediately (checked per frame from the revocation list), so this is authorisation freshness, not a hole in the revoke path.

**Fix direction.** Three options, in increasing cost and decreasing delay:
- **(a) Resolve from the registry when building the principal** — map `ServerDevicePrincipal.role/department` through `IdentityStore` right after `ChallengeResponseAuth.authenticate` (the relay router already holds a `directory`, and `AuthRoutes` holds the identity store), then `router.kick(id, "department_changed")` + `bearerTokens.revokeDevice(id)` on the move. Immediate, ≈ 30 lines, and it also repairs any other certificate/registry drift. Must fail closed when the device row is gone.
- **(b) Be honest in the console** — one line of copy ("takes effect when the device next renews, within 7 days"). Zero risk, no delay fix.
- **(c) Revoke the device** — available today, forces re-enrolment, but needs a fresh invite and a user action per moved device; disruptive, not a fix.

(b) is the cheapest and should ship regardless; (a) is the correct long-term answer if organisations expect same-minute effect. Anything called "re-issue" must start from the fact that **enrolment needs an invite token** (`EnrollmentService.enrol(token, …)`), so an automatic re-issue does not exist today.

**Test that would catch it.** A server test: enrol, move the device to another department through the admin route, then reconnect with the *old* certificate and assert the principal's department is the new one (or that the handshake is refused with a "re-enrol" error).

**Decision:** §8.2 — the certificate lifetime becomes **30 days, adjustable** (`FLASH_DEVICE_CERT_VALIDITY_DAYS`, default 30), which makes this worse; the fix is therefore option **(a)** (registry-derived principal + token revoke + kick), with the console sentence as a safety net.

**Status:** OPEN.

---

### ANN-02 — A console edit silently removes an announcement's expiry (Medium-low)

**Files**
- [`server/.../announcements/AnnouncementStore.kt:214-242`](../../server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt)
- [`server/.../resources/admin-ui/js/views/announcements.js:434-440`](../../server/src/main/resources/admin-ui/js/views/announcements.js)
- [`core/hub-protocol/.../rest/AnnouncementDtos.kt:67-73`](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/rest/AnnouncementDtos.kt)

**Symptom.** An announcement created with an expiry (e.g. "canteen closed until Friday") is edited for a typo on Wednesday and never expires.

**Root cause.** Every other field of `updateAnnouncement` treats `null` as "leave unchanged" (`pinned ?: current.pinned`, `title ?: current.title`), but the expiry writes `null` through as a value:

```kotlin
// AnnouncementStore.kt:237
if (expiresAtMs != null) stmt.setLong(5, expiresAtMs) else stmt.setNull(5, java.sql.Types.INTEGER)
```

and the console never sends it:

```javascript
// announcements.js:435-440
await api.put('/announcements/' + encodeURIComponent(existing.id), {
  expectedSeq: existing.seq, title: curTitle, body: curBody, pinned: pinCheck.checked,
});
```

The DTO defaults to `null` (`AnnouncementDtos.kt:72`), so "field omitted" and "clear the expiry" are the same wire value. The console's create dialog does not offer an expiry at all, so an expiry can only be set through the API and can only be destroyed through the console.

**Trigger.** Edit any announcement that has `expires_at` set, from the console.

**Consequence.** A time-boxed announcement becomes permanent, silently. The `PurgeJob`/feed filtering then keep it live indefinitely. Nothing in the audit trail records that the expiry changed (`device.update`-style change lists are not used for announcements).

**Fix direction.** Make the semantics explicit in the wire type: `expiresAtMs` should be a tri-state (omit = unchanged, `0` or a sentinel = clear, value = set) or the update DTO should carry `clearExpiry: Boolean`. Minimum viable: only write `expires_at` when the field was present in the request body (requires an optional-with-presence decode), and add the expiry field to the console editor so the value round-trips.

**Test that would catch it.** Store test: create with `expiresAtMs = now + 1000`, update the body only, assert `expiresAt` is unchanged. Console test: an edit PUT carries the existing `expiresAtMs`.

**Decision:** §8.5 — an edit **keeps** the expiry; `clearExpiry: Boolean = false` makes clearing explicit; the console gains the field on create and edit.

**Status:** OPEN.

---

### DIR-02 — Tombstones leak removed members of hidden departments (Medium-low, privacy)

**Files**
- [`server/.../directory/OrganizationDirectory.kt:1196-1198, 1250-1252`](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt)
- [`docs/protocol.md:1367-1373`](../protocol.md) (the promise: "directory lists, searches, counts, cursors, group browse lists, member lists, keys and presence never reveal a hidden department's rows to outsiders")

**Symptom.** A member of a hidden department resigns. Every device in the organisation, including outsiders who may not list that department, receives the departed device id in `removedDeviceIds` on its next `/directory/sync`, and the department id in `removedDepartmentIds`.

**Root cause.** Members and departments are filtered for visibility — the code even says so:

```kotlin
// OrganizationDirectory.kt:1200-1201
// Filter members visible to actor (A2: hidden-department rows never reach outsiders).
val visibleMembers = members.values.filter { DirectoryVisibility.canListMember(actor, it, this) }
```

but the tombstones are not:

```kotlin
// OrganizationDirectory.kt:1250-1252
val removedMembers = if (isFullResync) emptyList() else tombstones.values
    .filter { it.entityType == "member" && it.deletedAtVersion > effectiveSince }
    .map { it.entityId }
```

`Tombstone` carries only `entityType`, `entityId` and `deletedAtVersion` (no department), so the server cannot filter them at read time as written.

**Trigger.** Delete/revoke a member of a hidden department; any device not entitled to see that department syncs.

**Consequence.** Two leaks per removed entity: the existence of a hidden department (via `removedDepartmentIds`) and the device id of its ex-member (via `removedDeviceIds`). Device ids are stable per install and are what peers put in chat headers. `docs/protocol.md:1367-1373` states that hidden departments never leak through directory lists, searches, counts or cursors, so a client or an auditor reading the protocol would not expect a hidden department to be announced through the delta feed.

**Fix direction.** Store the department id on the tombstone row (`entity_dept`) when it is created, and filter tombstones through the same `DirectoryVisibility` predicate used for members and departments. Note the second-order effect: a member who *moved* out of a hidden department before being removed must still be deleted locally, so the filter must be "visible now **or** was visible to this actor" — the simplest correct rule is to filter by the tombstone's department for **non-org-admins** and leave org admins untouched.

**Test that would catch it.** Sync as an outsider after a hidden-department member is removed: `removedDeviceIds` must not contain that id; sync as an org admin: it must.

**Status:** OPEN.

---

### DIR-03 — A tombstone is never cleared when the entity returns (Medium-low)

**Files**
- [`server/.../directory/OrganizationDirectory.kt:330-339`](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt) (`recordTombstoneLocked`), `:758-798` (`registerMember`)
- [`core/enterprise/.../OrgDirectorySync.kt:370-373`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt)

**Symptom.** A device that is revoked and later enrolled again (a re-imaged phone, a laptop that came back) appears in every peer's directory and then vanishes again on the next sync; it can never be listed.

**Root cause.** Registering a member never deletes the tombstone that recorded its removal:

```kotlin
// OrganizationDirectory.kt:330-339
private fun recordTombstoneLocked(conn: Connection, entityType: String, entityId: String, atVersion: Long) {
    conn.prepareStatement("INSERT INTO directory_tombstone … ON CONFLICT(entity_type, entity_id) DO UPDATE SET deleted_at_version = ?")…
}
```

`registerMember` (`:758-798`) upserts the member and bumps the version, but there is no `DELETE FROM directory_tombstone …`. `getSyncChanges` therefore emits the same `removedDeviceIds` entry on **every** sync, and the client applies it every time:

```kotlin
// OrgDirectorySync.kt:370-373
for (removedId in dto.removedDeviceIds) {
    accumulatedRemovedMemberIds.add(removedId)
    accumulatedMembers.remove(removedId)   // <- a member on the same page is dropped
}
```

**Trigger.** Remove/revoke a device, then enrol the same device id again (the Android client keeps its device id in the trust store; a re-enrol of an `UNENROLLED`/`REVOKED`→unrevoked device keeps the same id).

**Consequence.** The re-added device is invisible in the directory for ever, its member statement is never stored, and org-trust (Plan B) cannot resolve it. Since a re-enrolment also re-issues a certificate, the device authenticates and chats fine — but the directory never shows it, so colleagues can never find it, and `removedDeviceIds` keeps shipping a false statement to every device on every sync.

**Fix direction.** Delete the tombstone (or stamp `restored_at_version`) inside the same transaction that registers the member, and make `getSyncChanges` ignore tombstones older than the entity's current version.

**Test that would catch it.** Server test: register → remove → register the same id → the next sync lists it in `members` and **not** in `removedDeviceIds`. Client test: a page that carries id X in both must end with X present.

**Status:** OPEN.

---

### DIR-04 — One legal `/directory/sync` page is larger than the client can decode (High)

**Files**
- [`core/enterprise/.../EnterpriseDirectoryClient.kt:407`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseDirectoryClient.kt) — `HubRestCodec.decode<DirectorySyncDto>(reply.body)`, default cap
- [`core/hub-protocol/.../rest/HubRestCodec.kt:34-37`](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/rest/HubRestCodec.kt), [`HubContract.kt:198`](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubContract.kt) — `MAX_REST_BODY_BYTES = 64 * 1024`, checked **before** parsing
- [`core/enterprise/.../OrgDirectorySync.kt:208`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt) — the client asks for `limit = 500`
- [`server/.../directory/OrganizationDirectory.kt:1223-1224, 1269`](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt) — the server honours 500 and measures no bytes
- [`core/hub-protocol/.../rest/RestDtos.kt:349-356, 363-368`](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/rest/RestDtos.kt) — a member DTO carries the whole signed statement; a roster DTO carries a **JSON string**

**Symptom.** A real hub answers `200 OK` with a well-formed `DirectorySyncDto`, and the client throws it away as `ServerError(200, "Malformed directory sync response")`. `OrgDirectorySync` treats that as a server failure and backs off, so the directory snapshot never lands: no org trust, no colleague conversations, no hub group rosters, and the Hub tab keeps saying it is waiting. Nothing in the server log and nothing in the metrics mentions it.

**Root cause.** The client requests a page the client itself cannot accept. The limit is only ever counted in rows:

```kotlin
// OrgDirectorySync.kt:208 — the client's page request
limit = 500,
```
```kotlin
// OrganizationDirectory.kt:1223-1224 — the server never looks at bytes
val pageSize = limit.coerceIn(1, 1000)
val membersOnPage = pagedMembers.take(pageSize)
```
```kotlin
// EnterpriseDirectoryClient.kt:407 — and the answer is refused above 64 KiB
val dto = runCatching { HubRestCodec.decode<DirectorySyncDto>(reply.body) }.getOrNull()
```

**Measured sizes** (synthetic payloads in the exact DTO shapes, Node, 2026-10-21; a P-256 SPKI is 91 DER bytes → 124 Base64 chars, an ECDSA signature ~96 Base64 chars):

| Payload | Bytes |
|---|---|
| one `DirectoryMemberSyncDto` (16-char display name, 36-char department) | ≈ 576 |
| the same with a 64-char display name | ≈ 624 |
| one roster DTO, 20 members (statement JSON-escaped a second time) | ≈ 0.9 KB |
| one roster DTO, 200 members (= `GroupRosterStatement.MAX_MEMBERS`) | ≈ 4.3 KB |
| an empty `DirectorySyncDto` | ≈ 294 |

So the ceiling is **≈ 110 member DTOs per response**, and a last page carrying 64 rosters of 20 members is ≈ 58 KB *before* any members are added. The server's own documented maximum (500 members per page) is ≈ 288 KB — more than four times the cap.

**Trigger.** Any organisation where one sync changes more than ~110 visible members (a first full sync of 111+ people, a bulk import, a re-enrolment wave), or a device whose last page holds enough rosters (≈ 70 small ones; 15 of the 200-member maximum). Both are ordinary at the scale the plan claims to support (EXP-022 measured 5,000 members).

**Why it survived review.** Every client test injects DTOs through `FakeDirectorySyncApi` (`OrgDirectorySyncTest.kt`, 24 constructions) and never encodes a body; `grep` finds no `MockEngine` in `:core:enterprise`. EXP-022's "5,000 members, 10 pages of 500" was produced by that fake, so the 64 KiB guard was never on the path. The server tests check the JSON shape, not the client's decoder.

**Consequence.**
- The whole Plan B offline-trust feature is unshippable above ~110 people; below that it works, which is why a demo organisation never shows it.
- It fails as "the hub sent something malformed", i.e. it blames the wrong side.
- Raising the roster cap to 256 (or keeping it at 64) changes nothing on its own: rosters alone can exceed the cap (DIR-05).

**Fix direction.**
1. Give this route its own bound: `MAX_DIRECTORY_SYNC_BODY_BYTES` (e.g. 2 MiB) passed explicitly to `HubRestCodec.decode`, and keep 64 KiB as the default for every other body (that guard is right there).
2. Page by **bytes** on the server: accumulate members until the encoded estimate passes a threshold below that bound, then stop and return `nextCursor`. A server must never emit a body its own client is defined to reject.
3. Drop the client's `limit` to a page that fits (200 members ≈ 115 KB) and write the number, and its rationale, into `docs/protocol.md`.
4. Better still, move the rosters to their own paged route (`GET /api/v1/directory/rosters?cursor=…`): they are the largest variable payload and the only one a device can re-fetch alone.

**Test that would catch it.** A codec test is not enough — it must go through the real client: start a Ktor hub with 300 members, point a real `RealEnterpriseDirectoryClient` at it, run one `syncOnce`, and assert 300 members are stored and `HubSyncStatus` is not `FAILED`. Mirror it at 1,000 members. Cheap, deterministic, `:server:test` + `:core:enterprise:jvmTest` territory.

**Decision:** §8.1 — keep 64 rosters, give the route a 2 MiB bound, page by bytes, 200 members per page, add `rostersTruncated`.

**Status:** OPEN.

---

### DIR-05 — Silent roster truncation, with replace-all semantics on the client (Medium-low)

**Files**
- [`server/.../directory/OrganizationDirectory.kt:1267-1307`](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt) — `.take(MAX_ROSTERS_PER_SYNC)`, `.take(MAX_MEMBERS)`, `rostersSkippedTooLarge`
- [`core/persistence/.../dao/OrgDirectoryDao.kt:133-146`](../../core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/OrgDirectoryDao.kt) — `applySyncBatchWithRosters` ends in `replaceGroupRosters(rosters)`
- [`core/enterprise/.../OrgDirectorySync.kt:274, 418`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt) — everything the last page offered is applied as the new set

**Symptom.** A device that belongs to more groups than the cap loses the extra rosters from its stored set on the next sync — the same outcome as leaving those groups — and keeps whatever membership rows it had, which are then never refreshed. There is no flag, no count and no user-visible warning on the client; `rostersSkippedTooLarge` is a server-side field no route or metric exposes.

**Root cause.** The last page is documented as "the FULL current set … the client replaces its stored set", but the list is truncated first:

```kotlin
// OrganizationDirectory.kt:1282-1291
val out = groups.values
    .filter { actor.deviceId in it.memberDeviceIds || actor.deviceId in it.adminDeviceIds }
    .sortedBy { it.id }
    .take(MAX_ROSTERS_PER_SYNC)        // 256 (protocol says 64)
    .mapNotNull { g ->
        val members = (g.memberDeviceIds + g.adminDeviceIds).distinct().sorted()
        if (members.size > GroupRosterStatement.MAX_MEMBERS) { skipped++; return@mapNotNull null }
        …
```
`replaceGroupRosters` makes the client's view exactly what arrived, so "truncated" and "left the group" are indistinguishable.

**Trigger.** A device in more than `MAX_ROSTERS_PER_SYNC` groups. Unreachable at 256 in practice; **reachable at the protocol's 64** for a busy organisation, and trivially reachable if the owner lowers the cap to bound the body size (DIR-04).

**Consequence.** Stale membership for the affected groups: `refreshHubGroupRosters` → `projectHubRosters` only iterates the groups it still holds, so the vanished group's rows stay active and stop being updated. A removal in such a group is not projected on that device.

**Fix direction.** Either add `rostersTruncated: Boolean` (and let the client keep the rosters of groups it still has) or make the client merge by `groupId` with a version check and a max-age, so a missing roster ages out instead of vanishing. A cap without a truncation flag is the actual defect; the number is secondary.

**Test that would catch it.** Server: 3 groups, cap forced to 2 → the response says it was truncated. Client: apply a truncated page and assert the third group's stored roster survives one sync and is dropped only by age.

**Decision:** §8.1 — `MAX_ROSTERS_PER_SYNC` becomes 64 (matching the protocol), and truncation becomes explicit rather than a silent replace.

**Status:** OPEN.

---

### AUTH-01 — A hub restart cannot re-authenticate an office behind one NAT (Low, operations)

**Files**
- [`server/.../auth/BearerTokenStore.kt:11-20`](../../server/src/main/kotlin/com/transfer/flash/server/auth/BearerTokenStore.kt)
- [`server/.../auth/RateLimiter.kt:88-89`](../../server/src/main/kotlin/com/transfer/flash/server/auth/RateLimiter.kt)
- [`server/.../auth/AuthRoutes.kt:103-115`](../../server/src/main/kotlin/com/transfer/flash/server/auth/AuthRoutes.kt)

**Symptom.** After a hub restart, some devices show "This device could not sign in to the organisation hub." for minutes, apparently at random, and recover by themselves.

**Root cause.** Bearers are memory-only by design ("Nothing is persisted (a restart invalidates all tokens by design)", `BearerTokenStore.kt:19`), so every device re-signs-in at once. The `/auth` limiter is **per client IP**, and both halves of one sign-in consume a token from the same bucket:

```kotlin
// RateLimiter.kt:88-89
/** Per client IP on `/auth/challenge` and `/auth/verify`: a burst of 30, then 1 per second. */
val auth: TokenBucketLimiter = TokenBucketLimiter(30, 1.0, clock = clock),
```

```kotlin
// AuthRoutes.kt:104-115 — challenge (GET/POST) and verify all take limits.auth
if (!call.withinLimit(limits.auth, addresses)) return@get
```

So a burst allows ~15 sign-ins, then one every **2 seconds**. Devices behind one NAT (an office gateway, or a reverse proxy without `FLASH_TRUSTED_PROXIES` configured, where every client collapses to the proxy address) share that bucket.

**Trigger.** Hub restart/redeploy, or any mass token invalidation, with more than ~15 devices behind one address.

**Consequence.** Recovery time grows linearly with the number of devices: 100 devices behind one NAT take about 3 minutes, and every throttled device surfaces an auth failure to the user. Not a security problem — the limiter is doing its job — but the deployment docs present a hub restart as routine.

**Fix direction.** Give `/auth/verify` its own per-IP bucket with a larger burst (it already requires a server nonce issued by `/auth/challenge`, so it is not a brute-force surface on its own), or key the burst by the `deviceId` in the verify body once the challenge is known. Document the restart behaviour in [`docs/deploy/HUB-SERVER-DEPLOYMENT.md`](../deploy/HUB-SERVER-DEPLOYMENT.md).

**Test that would catch it.** Rate-limiter test: 40 devices from one IP, one sign-in each (challenge + verify) — every one succeeds.

**Decision:** §8.4 — `/auth/verify` gets its own per-device bucket (burst 5, 1 per 5 s), `/challenge` and `/hub` stay per-IP; supported scale ≈ 200 devices behind one public IP; plus single-flight refresh (CLI-01) and jitter.

**Status:** OPEN.

---

### RLY-02 — Group fan-out is bounded by frames, not by bytes (Low, hardening)

**Files**
- [`server/.../relay/RelayFrameRouter.kt:227-272`](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt)
- [`server/.../relay/RelayPolicy.kt:35-45`](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayPolicy.kt)
- [`core/hub-protocol/.../HubContract.kt:189-192`](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubContract.kt)

**Symptom.** One authenticated member of a large group can make the hub emit hundreds of megabits per second without exceeding any limit.

**Root cause.** The only per-session budget is a frame bucket (`frameBurst = 120`, `framesPerSecond = 60.0`) and payloads may be 64 KiB (`MAX_PAYLOAD_BYTES`). A group `FLASH_RELAY{group}` is expanded to one delivery per member, each copy encoded separately:

```kotlin
val gate = Semaphore(policy.groupFanoutConcurrency)   // 16
val outcomes = coroutineScope { recipients.map { member -> async { gate.withPermit { routeOne(…) } } }.awaitAll() }
```

60 × 64 KiB × 256 members ≈ 960 MiB/s of egress plus 256 encodings from one sender, with no counter or metric that would show it. Offline members are bounded by the mailbox quotas, online members only by their own outbound queues (256 frames).

**Trigger.** A compromised or buggy client in a large group; also a whole class of accidental loops.

**Consequence.** A single account can exhaust a small VPS's uplink and CPU. The mailbox quotas protecting the durable path do not cover the live path.

**Fix direction.** Add a per-session **byte** bucket (e.g. 2 MiB/s sustained, burst 8 MiB) alongside `rateBucket`, charged once per inbound payload and, optionally, once per fan-out copy; and cap `recipients` per frame with an explicit `ERR_TOO_MANY_RECIPIENTS` if the group exceeds a configured size.

**Test that would catch it.** A test that sends one 64 KiB frame to a 100-member group twice per second and asserts the byte limiter refuses the flood with `ERR_RATE_LIMIT` (while the frame limiter alone would allow it).

**Status:** OPEN.

---

### OPS-01 — Blocking JDBC on request threads (`MailboxStats`) (Low)

**Files**
- [`server/.../mailbox/MailboxQueue.kt:346-377`](../../server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt)
- [`server/.../admin/AdminApi.kt:390-391, 875-877`](../../server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt)
- [`server/.../core/FlashServerEngine.kt:303, 312-313, 341-342`](../../server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt)

**Root cause.** `MailboxStats` is a plain interface of **non-suspend** functions, and the implementation calls `database.withConnection` — which the database class itself documents as "BLOCKING: from a coroutine use [io]" (`ServerDatabase.kt:248-250`). Its callers are Ktor handlers (`AdminApi` overview and health: three queries per call) and the metrics/health text builder (`FlashServerEngine`), i.e. event-loop-adjacent threads.

**Trigger.** A metrics scrape or console overview while the pool is busy: with `maximumPoolSize = 10` and `busy_timeout = 5000`, a stats call can block for up to 5 s.

**Consequence.** Head-of-line blocking on the Ktor dispatch thread; worst case the health endpoint (the Docker `HEALTHCHECK` target) times out during a load spike and the container is restarted by its orchestrator.

**Fix direction.** Make the stats functions `suspend` and wrap them in `database.io`, or cache the totals (`mailbox_totals` is already a counter table) behind a short-lived snapshot so a scrape never touches the pool.

**Status:** OPEN.

---

### RLY-03 — `draining` is set outside the hand-off gate (Low, ordering)

**Files**
- [`server/.../relay/RelayFrameRouter.kt:446-452`](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt)
- [`server/.../relay/RelaySession.kt:87-98, 166-175`](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt)

**Root cause.** `handOffLive` and `finishDrain` carefully make "caught up?" and "hand this frame over" one atomic decision under `gate`, but the start of a drain is a CAS followed by a separate volatile write:

```kotlin
// RelayFrameRouter.kt:446-452
if (session.drainRunning.compareAndSet(false, true)) {
    session.draining = true          // <- outside synchronized(gate)
    scope.launch { runDrain(session) }
}
```

Between the CAS and the write, another sender's `handOffLive` can read `draining == false` and hand a live frame to the session. Because the drain has not pushed anything yet, the recipient sees that live frame **before** mailbox rows with lower `seq`.

**Trigger.** A wake-up (`routeOne` → `requestDrain`) racing a live message to the same recipient.

**Consequence.** Out-of-order delivery in a narrow window (microseconds). Chat messages are sequenced at the application layer, so the user impact is a reordered bubble at worst; group and 1:1 body handling is order-tolerant. Recorded so it is not mistaken for a duplicate-delivery bug later.

**Fix direction.** Set `draining = true` inside `synchronized(gate)` (take the batch under the lock: CAS + flag + launch decision), then launch outside it.

**Status:** OPEN.

---

### CLI-01 — A 401 refresh is not single-flight (Low)

**Files**
- [`core/enterprise/.../EnterpriseDirectoryClient.kt:187-214`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseDirectoryClient.kt)
- [`core/enterprise/.../HubSession.kt:131-144`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubSession.kt)

**Root cause.** On a 401 the client retries once with `forceRefresh = true`, and `bearer(true)` bypasses the cache unconditionally:

```kotlin
// HubSession.kt:133-143
mutex.withLock {
    val current = cached
    if (!forceRefresh && current != null && … ) return@withLock current.token
    cached = null
    authenticate(creds)?.also { cached = it }?.token
}
```

Two concurrent 401s (the presence poll and a file upload after a hub restart, say) therefore perform **two** sequential full sign-ins; *M* concurrent 401s perform *M*.

**Trigger.** Hub restart or a bearer expiring while several requests are in flight (a file transfer plus the 15 s presence poll plus a directory sync is the normal case).

**Consequence.** Serialised sign-ins (each two round-trips) inflate the wake-up storm described in AUTH-01 and delay the user's requests by the queue length.

**Fix direction.** Pass the rejected token into `bearer`: if the cached token is different from the rejected one, return it instead of re-authenticating (the classic single-flight refresh). `HubSession` already has the mutex and the cache; only the comparison is missing.

**Status:** OPEN.

---

### CLI-02 — The bearer mutex is held across network I/O, with no client timeout (Low)

**Files**
- [`core/enterprise/.../HubSession.kt:131-173`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubSession.kt)

**Root cause.** `mutex.withLock { … authenticate(creds) }` spans `fetchChallenge` and the `/auth/verify` POST, and neither sets a request timeout (Ktor's default `HttpTimeout` is not installed for this client — `EnterpriseEnrollmentClient.createDefaultClient`). A hub that accepts the TCP connection and then never answers holds the lock until the OS-level connect/read timeout, which can be minutes.

**Consequence.** Every hub operation on the device (presence, directory sync, announcements, file chunks, ticket delivery) waits behind one stuck request. From the user's point of view the Hub tab freezes and hub file transfers stall without an error, because there is no timeout to report.

**Fix direction.** Install `HttpTimeout` (e.g. 15 s request, 10 s connect) on the shared client, and narrow the mutex to the cache read/write — or keep it but guarantee every network call inside is bounded.

**Status:** OPEN.

---

### CLI-03 — Unbounded response bodies in the hub REST client (Low)

**Files**
- [`core/enterprise/.../EnterpriseDirectoryClient.kt:203-206`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseDirectoryClient.kt)
- [`core/enterprise/.../HubSession.kt:166, 190, 256, 321, 346`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubSession.kt)
- [`core/enterprise/.../EnterpriseEnrollmentClient.kt:100`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseEnrollmentClient.kt)
- [`core/enterprise/.../announcements/RealHubAnnouncementsApi.kt:61`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/announcements/RealHubAnnouncementsApi.kt)

**Root cause.** Eight call sites do `Reply(response, response.bodyAsText())` — materialising the whole body — before any size check. `HubRestCodec.decode` then refuses a body over 64 KiB, but the memory is already allocated. The hub is semi-trusted by design (it holds identities) and the rest of this client bounds everything it accepts (`receiveBoundedText` on the server side, `MAX_FRAME_BYTES` on the relay).

**Consequence.** A compromised or broken hub can OOM a phone with one response. Low likelihood, cheap to fix.

**Fix direction.** Read with `response.bodyAsChannel().readRemaining(limit + 1)` or check `contentLength()` first and refuse early; a single helper beside `Reply` covers all eight sites.

**Status:** OPEN.

---

### FL-01 — The `409 NOT_READY` answer is unreachable for recipients (Low, API consistency)

**Files**
- [`server/.../files/FileStore.kt:309-336, 529-554`](../../server/src/main/kotlin/com/transfer/flash/server/files/FileStore.kt)
- [`core/enterprise/.../files/RealHubFilesApi.kt:200-225`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/RealHubFilesApi.kt)
- [`core/enterprise/.../files/HubFileTransfers.kt:627-635`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt)

**Root cause.** `readChunk` calls `access()` first, and `access()` already answers `404 ERR_NOT_FOUND` for a non-sender whose blob is not `READY`:

```kotlin
// FileStore.kt:545-551 — inside access()
if (!isSender) {
    if (blob.state != FileStates.READY) return FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
    if (blob.expiresAtMs in 1..now)       return FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
}
```

so the `409 NOT_READY` further down is only ever produced for the **sender**:

```kotlin
// FileStore.kt:317-319 — after access() has already returned for a non-sender
if (blob.state != FileStates.READY) return FileOutcome.Refused(HubErrorCodes.ERR_CONFLICT, FileMessages.NOT_READY)
```

The client maps 404 to the non-transient `NotFound` and `fail()` deletes the partial file **and** its resume state.

**Why it is not higher.** In the current app flow a recipient only learns the file id from the ticket, and `HubFileBinding.upload` queues the ticket only after `HubUploadOutcome.Done` (`HubFileBinding.kt:458-464`) — i.e. after `complete`, when the blob is `READY`. So no shipped path hits the race today. It becomes a real transfer-killing bug the moment a ticket is sent earlier (a future "send the ticket first, upload in the background" optimisation, or any integration that guesses file ids).

**Fix direction.** Make the states distinguishable to a recipient: answer `409 NOT_READY` (retryable) for `UPLOADING`, `404` only for `EXPIRED`/missing. One line in `access()`, plus a client rule that `NOT_READY` is transient.

**Status:** OPEN. Not reproduced.

---

## 4. Checked and found correct

Recorded so the next reviewer does not have to re-derive them.

- **Relay identity.** A frame's sender is always `originSession.deviceId` from the handshake (`RelayFrameRouter.kt:129-135`); client-supplied `from` on `FLASH_META` is overwritten; client→client `ACK`/`DELIVER`/`QUEUE_STATE`/`KICK` are refused with `ERR_PROTOCOL` and counted against the abuse budget.
- **Hand-off vs. drain at the end of a drain** (`finishDrain`, `RelaySession.kt:182-193`) is correct: the wake-up counter is compared and the baton retaken under the same lock `handOffLive` uses, so no wake-up is lost and no live frame overtakes a row.
- **Spill on disconnect** (`sessionEnded` → `spill`) moves unacknowledged live messages to the mailbox and wakes a reconnected session; `sessionEnded` is idempotent (`ended` CAS) and unregisters only its own session object (REL-01).
- **Mailbox quotas** are checked inside one `ioTransaction` (`MailboxQueue.kt:138-159`), so per-recipient, per-sender and global limits cannot be raced; `existingSeq` de-duplicates `(origin, msg, target)`.
- **Mailbox acknowledgement** can only delete the acknowledging device's own rows (`acknowledge` keys on `target_device_id`), and re-checks `origin` when the client names one.
- **Group relay membership**: the same `AckStatus.REJECTED` answer is returned whether the group does not exist or the sender is not a member (no membership oracle), and `routeOne` re-checks nothing else because membership was resolved once per frame.
- **Group file reads re-check the live roster** on every chunk (`access` → `groupMembers`, `FileStore.kt:539-544`), so a member removed after receiving the ticket loses access immediately.
- **File write quotas** take `BEGIN IMMEDIATE` and enforce per-file/device/hub/row limits atomically (verified by the existing two-thread quota test); a missing payload answers 503 rather than an empty body.
- **Revocation** is an in-memory snapshot read per frame, not a DB hit; `kickIfRevoked` closes the live session on revocation, and `BearerTokenStore.resolve` re-checks revocation on every REST call.
- **Delegation and root pinning**: `requireRootPin`, TOFU adoption with a recorded fingerprint, monotonic `highestAcceptedSerial`, and a hard refusal when the presented chain claims a different root than the enrolled one.
- **Data at rest**: `data.key` is created owner-only, never silently replaced, and a wrong-size/corrupt file fails closed (`KeyFileException`); admin TOTP seeds and mailbox payloads are sealed with it; `sealLegacySecrets`/`sealLegacyPayloads` migrate plaintext in small transactions at startup.
- **TLS/config defaults**: `tlsMode=none`, a plain-`http` `externalUrl` and `--dev-embedded-root` are all refused unless `FLASH_ALLOW_INSECURE=true`; the admin console listener is separate by default (`adminOnPublicPort = false`) and a port clash is a startup error; a proxy mode with no `FLASH_TRUSTED_PROXIES` logs a loud warning.
- **`healthcheck`** talks only to the hub's own listener over loopback and only reads `/health`; its trust-all manager is scoped and documented (`Main.kt:125-149`).
- **Re-enrolment of an ACTIVE device** requires the same subject key (`DEVICE_CONFLICT` otherwise) and issues a new serial — the old certificate is not revoked, but it grants nothing the new one does not (see §2.3).
- **Client URL policy**: both REST clients refuse a hub that is not the pinned hub and refuse cleartext outside the debug opt-in, **before** the bearer is attached (`EnterpriseDirectoryClient.kt:188-196`; `RealHubAnnouncementsApi` since FIX-1).
- **Client sync failure atomicity**: pages are staged in memory and applied in one `applySyncBatchWithRosters`, and a roster decode failure keeps the stored rosters instead of wiping them.

---

## 5. Documentation vs. code contradictions (DOC-01)

| # | Where | Says | Code |
|---|---|---|---|
| D-1 | [`docs/protocol.md:1602-1603`](../protocol.md) | "at most **64** [group rosters] per sync" | `MAX_ROSTERS_PER_SYNC = 256` (`OrganizationDirectory.kt:1427`), applied as `.take(…)` over groups sorted by id (`:1285`). A device in more than 256 groups loses the rosters past that point silently — `rostersSkippedTooLarge` counts only groups above the 200-member limit. **DECIDED (§8.1): 64 wins**; the code changes, and truncation becomes explicit (DIR-05). (The member ceiling, 200, agrees.) |
| D-2 | [`docs/protocol.md:1425`](../protocol.md) | pin limit `409 ERR_PIN_LIMIT` | device routes agree (`AnnouncementRoutes.kt:204,301,432`); the **console** routes answer `400` (`AnnouncementAdminRoutes.kt:238,327,432`). Harmless today because the console keys on `code`, but it is a contract break. |
| D-3 | [`IdentityStore.kt:252`](../../server/src/main/kotlin/com/transfer/flash/server/identity/IdentityStore.kt) | "the registry (not the presented certificate) decides role and department" | true for `renew` only; `ChallengeResponseAuth` builds the principal from the presented certificate (DEV-01). |
| D-4 | [`HubPresence.kt:76-80`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubPresence.kt) | "4 x 500 = the first 2,000 people" | unreachable: the cursor bug (DIR-01) makes page 2 empty, so the ceiling is one page (500). |
| D-5 | `IdentityStore.setRole` (`:356`) and `serialsOf` (`:335`) | — | no caller in `src/main`. Dead code that suggests a role-change path which does not exist (see DEV-01). |

---

## 6. Cross-reference: findings from the 2026-10-19 review still open

Not re-litigated here; listed so one place shows what is still owed.

- **F-1** search `total` is the page size (no consumer yet) — unchanged.
- **F-2** the `409` on announcement `PUT` carries no current item, so `Conflict.current` is always null — unchanged, and ANN-01 explains why the conflict is rarely produced at all.
- **F-3** `RelaySession.dispose()` is dead code — unchanged.
- **F-4** an authoritative full resync with an empty roster offer wipes stored hub group rosters — unchanged.

---

## 7. Feature recommendations

### 7.1 Needed (do not deploy a hub to real users without these)

| # | Feature | Why |
|---|---|---|
| N-1 | **A `seq`-monotonic, resumable directory and announcement cursor with a server-side "changes since" guarantee** | DIR-01 and ANN-01 both end in silent gaps in the client's view of the organisation. A single shared paging primitive (cursor applied server-side, `seq` allocated inside the write transaction) fixes both and prevents the next recurrence. |
| N-2 | **An operator-visible "broken mailbox" alarm** | RLY-01 is invisible today: no log with a device id, no metric, no console row. `MailboxStats` already counts `dropped`/`expired`; a `sealed_unopenable` counter plus a console warning ("3 devices cannot be served; their queued messages cannot be decrypted") turns a 7-day outage into an alert. Pair it with the `data.key` backup instruction in the deployment doc (losing it is the only way to reach RLY-01). |
| N-3 | **Effective-immediately authorisation** | DEV-01: a revoked employee is cut off immediately, but a **moved** one is not. Since the console offers the move, it must take effect: re-issue + deliver the certificate and kick the session. Until then the console should say "takes effect when the device next renews" instead of pretending. |
| N-4 | **A transfer/journal audit for the admin console beyond login events** | The audit log records admin actions; it records nothing about relay traffic, file uploads, or mailbox drops. For an organisaton hub that is the first question after an incident. At minimum: file create/complete/cancel (already counted), mailbox drops per device, and a daily "delivering N messages to M devices" summary line. |
| N-5 | **Backup/restore of `data.key` + database as one unit, documented and checked at startup** | Losing `data.key` silently invalidates every sealed row (RLY-01) and every TOTP seed. The startup check is cheap: if any `sealed = 1` row cannot be opened with the loaded key, refuse to start with a named error rather than logging per row during a drain. |
| N-6 | **A pinned-hub certificate rotation path** | `TlsSupport` generates a self-signed certificate; clients pin the fingerprint printed at first start. There is no documented, tested way to rotate it without re-enrolling every device. For an on-prem hub with a multi-year life this is a hard operational gap. |

### 7.2 Optional / deferrable

| # | Feature | Note |
|---|---|---|
| O-1 | Per-session byte quota on relay fan-out (RLY-02) | Small, cheap; do it with N-1's shared limiter work. |
| O-2 | Announcement expiry in the console editor (ANN-02) | The field exists in the wire; only the UI is missing. Would also make the "expiring announcement" feature discoverable. |
| O-3 | Announcement **attachments** | Explicitly out of Plan A3's scope; the file API (ADR-215) now exists, so this is a small addition — but only with the same "the hub cannot read it" sealing rule. |
| O-4 | Directory **department tree** / org chart, and per-department people paging | The people route already takes `departmentId`; the UI treats departments as flat chips. |
| O-5 | Group **message encryption** | Parked as FO-27. Unchanged by this audit. |
| O-6 | Server-side **metrics export** (Prometheus text) | `RelayMetrics` collects everything needed; the current `/metrics`-shaped JSON is assembled by string concatenation in `FlashServerEngine` (`:341-342`). |
| O-7 | **Per-device rate dashboard** in the console | Would have made AUTH-01 and RLY-02 visible. |
| O-8 | **Search over announcements** is implemented (FTS5 + LIKE fallback); expose it in the console with the same hit shape as chat | Reuse `FlashSearchResultMath`'s grouping instead of a second results layout. |
| O-9 | **Multi-organisation** (FO-18) | Parked by the owner; do not start. |
| O-10 | A **hub-side "who can see this group"** preview | The privacy rules are spread over `DirectoryVisibility`; a console preview would make hidden departments safe to configure. |

---

## 8. Owner questions and decisions (2026-10-21)

The owner answered the five questions on 2026-10-21. **The decisions are binding for the implementation; no code has been written yet.** The questions are kept, because the reasoning matters more than the answer.

### 8.1 Directory sync transport — was "64 or 256 rosters"

**Question.** Which roster cap is real: 64 (protocol) or 256 (code)?

**Decided.** Keep **64** as the documented per-sync roster cap, and fix the transport instead. The code's `MAX_ROSTERS_PER_SYNC` changes **256 → 64**.

**Why.** 256 cannot fit in any single response under the current client bound; and a cap is a data-loss boundary, not a tuning knob, because the client replaces its stored set (DIR-05). 64 groups per device is already far beyond a realistic organisation.

**Implementation spec (not started).**
1. New `MAX_DIRECTORY_SYNC_BODY_BYTES = 2 * 1024 * 1024` in `HubContract`, passed explicitly by `EnterpriseDirectoryClient.fetchSync` only; every other REST body keeps the 64 KiB `MAX_REST_BODY_BYTES` default (that guard is right elsewhere).
2. Server pages by **bytes**, not only rows: accumulate members until the encoded estimate passes a threshold below the bound, then stop and return `nextCursor`. The server must never emit a body the client is defined to reject.
3. Client `limit` 500 → **200** (≈ 115 KB), and the numbers (200 members/page, 64 rosters/page, 2 MiB bound) go into `docs/protocol.md` with their rationale.
4. Roster truncation becomes explicit: `rostersTruncated: Boolean` on `DirectorySyncDto`; the client keeps the rosters of groups it still has instead of treating "not in this response" as "left the group".
5. Optional, later: move rosters to their own paged route (`GET /api/v1/directory/rosters?cursor=…`), which removes the largest variable payload from `/directory/sync`.

Tests: SRVHUB-13, SRVHUB-14.

### 8.2 DEV-01 — how a department move takes effect (and the certificate lifetime)

**Question.** Force a re-issue + kick, or tell the console operator the change lands at renewal?

**Decided by the owner.** A device certificate should live **30 days, adjustable on the server** (today `DeviceCert.DEFAULT_VALIDITY_MS` is hard-coded to 7 days). Because that makes staleness *worse*, the department move must also become **immediate**: implement option **(a)**, the registry-derived principal, and keep a one-line honest console note as a safety net. Do not attempt an automatic "re-issue" — enrolment needs an invite token, so it does not exist.

**Why (a).** The principal is built from the presented certificate (`ChallengeResponseAuth.kt:240-247`) and cached in REST bearers for 15 minutes, while the directory statement is re-signed immediately — so the person appears in the new department to everyone else while *their own device* keeps the old department's announcements and people for up to a month. `DeviceRecord` already carries `role` and `department` (`IdentityStore.kt:31-37`) and `device(id)` is a primary-key lookup, so this is cheap. The certificate stays the authentication factor; the registry becomes the authorisation source.

**Implementation spec (not started).**
1. Certificate lifetime: `ServerConfig.deviceCertValidityDays` (`FLASH_DEVICE_CERT_VALIDITY_DAYS`, default **30**, bounds 1..365), used at `EnrollmentService.kt:391` in place of `DeviceCert.DEFAULT_VALIDITY_MS`; the leaf stays clamped to `delegation.validTo` (SEC-18) and the hub **warns at startup** when the delegation expires sooner than the configured lifetime + grace. Delegation days must therefore be ≥ lifetime + grace (the `KeyTool` default of 90 days is fine; the `--valid-days` help text must say so).
2. Renewal window and grace: derive from the lifetime with clamps — `early = max(1 day, 10 % of lifetime)`, `grace = max(3 days, 5 % of lifetime)` — and let both be overridden by config; a 365-day certificate must not keep a 1-day window. `RenewalPolicy` already takes both as parameters, and it reads the certificate's own `validTo`, so **no client change is needed for an adjustable lifetime** (checked: nothing outside `ServerCerts` and `EnrollmentService` uses `DEFAULT_VALIDITY_MS`).
3. Principal: after `authenticate`, map `role`/`department` through `IdentityStore.device(deviceId)` in both callers (relay handshake and `AuthRoutes` bearer issue); **fail closed** when the device row is missing. Keep `certSerial` from the certificate (revocation still keys on it).
4. On `PUT /admin/api/v1/devices/{id}` with `moveToDepartment`: `bearerTokens.revokeDevice(id)` (exists) and `router.kick(id, "department_changed")` (exists) so the next handshake uses the new scope. Verify the clients reconnect without user action (SRVHUB-04).
5. Console copy: "Takes effect immediately. The device reconnects once." — and if (a) ever has to be reverted, the text must instead say the change lands at the next renewal.

Tests: SRVHUB-04, SRVHUB-15.

### 8.3 RLY-01 — an unopenable sealed mailbox row

**Question.** Delete it and tell the sender `DROPPED`, or refuse to drain until an operator restores the key?

**Decided by the owner.** **Quarantine (option b)**: keep the row, tell the sender `DROPPED`, let the device continue.

**Why.** Refusing is today's behaviour and it turns one row into a permanent, unexplained outage for a device. Dropping is not content loss — `HubChatBridge.kt:155` marks the message failed with *"The server dropped this message from its queue."* and the sender can resend. Quarantine additionally survives a `data.key` restore inside the existing retention window.

**Implementation spec (not started).**
1. New column on the mailbox table (server migration): `undeliverable_reason TEXT NULL` + index; a decode failure marks the row and moves on instead of throwing out of `fetchPage`.
2. `fetchPage` skips quarantined rows (and must still apply `LIMIT` after the skip — RLY-01's other half).
3. For every quarantined row, send the origin `FLASH_QUEUE_STATE state=DROPPED` and count it (`RelayMetrics` + a console stat), naming the 8-character device id only.
4. Retention: the existing TTL sweeper deletes quarantined rows with everything else (7 days default). A console action "retry quarantined rows" is optional; it becomes useful only after a key restore.
5. If the quarantine write itself fails (DB error), fall back to the current cut-the-session behaviour — never loop silently.

Tests: SRVHUB-02.

### 8.4 AUTH-01 — a hub restart behind one NAT

**Question.** Is a restart behind one office NAT a supported scenario, and should the `/auth` burst grow?

**Decided.** Support it, and do it properly rather than by raising one number: **`/auth/verify` gets its own per-device bucket**, `/auth/challenge` and `GET /api/v1/hub` stay per-IP. Supported scale: **up to ~200 devices behind one public IP** (a campus-sized building), recovering within a couple of minutes.

**Why.** The per-IP bucket is the right tool for the unauthenticated surface; `/verify` is not that surface — it must present a nonce only `/challenge` can mint. Keying it by device id removes the thundering herd without widening the brute-force surface. Raising a single per-IP burst only moves the cliff.

**Implementation spec (not started).**
1. `HubRateLimits.verify` keyed by `deviceId` (fall back to the client IP when the body carries no parsable device id, so a flood of garbage still meets a per-IP limit); burst ~5, refill one per 5 s, with the `/auth` bucket left as it is for challenge and `/hub`.
2. Client: single-flight 401 refresh (CLI-01) and jitter the first re-auth by 0–30 s, so a restart is not a synchronised stampede.
3. Deployment doc: state the supported scale and the `trustedProxies` requirement when a reverse proxy fronts the hub (without it every device collapses onto the proxy's address and the per-IP buckets are meaningless).

Tests: SRVHUB-08.

### 8.5 Announcement expiry on edit

**Question.** Does an edit keep or clear an announcement's expiry?

**Decided.** An edit **keeps** it; clearing becomes an explicit act.

**Why.** An edit is a new body, not a new lifetime, and the wire could not express "unchanged" at all (`explicitNulls = false` makes absent and null identical). No user can depend on the old behaviour yet — the console has no expiry field on create or edit (ANN-02) — so this is the cheapest moment to define it.

**Implementation spec (not started).**
1. `UpdateAnnouncementRequestDto` gains `clearExpiry: Boolean = false`; semantics: `expiresAtMs != null` sets it, `null && !clearExpiry` leaves it, `null && clearExpiry` clears it. An old client sending only `null` now keeps the expiry — the safe direction.
2. Store: `updateAnnouncement` must branch on the two cases; the `:237` `setNull` disappears.
3. Console: an expiry field on **create** and **edit** (date + time, local, optional), plus an explicit "Remove expiry" action, so the feature exists and clearing is deliberate.
4. `docs/protocol.md` documents the tri-state.

Tests: SRVHUB-05, SRVHUB-16.

---

## 9. Tests this work owes

Nothing here was executed, so every defect needs a device or server test before it can be called fixed. The list lives in [`docs/testing/TEST-BACKLOG.md`](../testing/TEST-BACKLOG.md) §4zzzzzzzzzzzzzzzzzzzzz as **SRVHUB-01 … SRVHUB-16**:

- **SRVHUB-01** DIR-01 — people list pages past 100/500 on a real hub with >100 members; presence shows a colleague at position 600 as online.
- **SRVHUB-02** RLY-01 — replace `data.key` under a populated mailbox, reconnect the device: the unreadable rows are **quarantined** (not deleted, not retried), their senders get `DROPPED`, later messages flow, and the count shows in the log/metrics.
- **SRVHUB-03** ANN-01 — two consoles create/pin/edit simultaneously; no duplicate `seq`, the second edit is refused with 409, no 4th pin.
- **SRVHUB-04** DEV-01 — move a device to another department; its department-scoped announcements and relay scope change **within seconds**, without a certificate renewal and without the user doing anything.
- **SRVHUB-05** ANN-02 — edit an expiring announcement from the console: it still expires (keep), and "Remove expiry" makes it stay.
- **SRVHUB-06** DIR-02 — an outsider's sync carries no hidden-department tombstone.
- **SRVHUB-07** DIR-03 — revoke, unrevoke and re-enrol a device; it is listed in the directory again and no peer deletes it.
- **SRVHUB-08** AUTH-01 — restart the hub with 50+ devices behind one NAT; every device signs in within a minute.
- **SRVHUB-09** RLY-02 — a 100-member group cannot be flooded at 60 frames/s from one device.
- **SRVHUB-10** CLI-01/CLI-02 — restart the hub while a file transfer and the presence poll are running: one re-auth, no visible freeze.
- **SRVHUB-11** OPS-01 — `/health` and `/health/detail` answer under 1 s while a large sync runs.
- **SRVHUB-12** FL-01 — a recipient reads a chunk while the blob is `UPLOADING` and gets a retryable answer.
- **SRVHUB-13** DIR-04 — a real hub with 300 members: one `syncOnce` from a real `RealEnterpriseDirectoryClient` stores all 300 (today it fails as "malformed"). Repeat at 1,000 and with a 64-roster last page.
- **SRVHUB-14** DIR-05 — force the roster cap below the device's group count: the answer says it was truncated and the client keeps the missing group's roster.
- **SRVHUB-15** §8.2 — set `FLASH_DEVICE_CERT_VALIDITY_DAYS=30`: a new certificate carries 30 days, a delegation shorter than lifetime + grace is refused/warned at startup, the renewal window scales, and a client on an older build renews without changes.
- **SRVHUB-16** §8.5 — `expiresAtMs` set + `clearExpiry` in the same request is refused (400), and a request with neither leaves the expiry untouched.

The server-level ones can be automated as JVM tests in `:server:test` (that is where they belong); the device-level ones belong to the owner's eventual hub device session.

---

## 10. Status summary

- **Code changed:** none. In particular, **the §8 decisions are recorded but not implemented** (owner: "edit the audit, you will not be implementing it").
- **Build run:** none (`:server` was not rebuilt; nothing here depends on a new compiler result).
- **Device-verified:** nothing; the hub remains experimental and undeployed per `AGENTS.md` §29.
- **Re-opened by this addendum (2026-10-21, same session):** DIR-04 (High) and DIR-05 (Medium-low) were found while answering §8.1, and DEV-01's text was corrected (a role change is not reachable from the console; certificates were 7 hard-coded days at the time of the correction — ADR-222 makes that 30 and adjustable). The workspace was not rebuilt for these either.
- **Decided:** all five §8 questions are answered, with an implementation spec each (ADR-222). Nothing was implemented.

---

## 11. Implementation plan from the decisions (not started)

Ordered by payoff per unit of risk. Each item names the findings/tests it closes; do them as separate commits so a regression is attributable.

| # | Work | Closes | Tests |
|---|---|---|---|
| 1 | **Sync transport budget**: `MAX_DIRECTORY_SYNC_BODY_BYTES` (2 MiB) for `fetchSync` only, server byte-budget paging, client `limit` 200, `MAX_ROSTERS_PER_SYNC` 64, `rostersTruncated` | DIR-04 (High), DIR-05, DOC-01 D-1 | SRVHUB-13, 14 **DONE 2026-10-21** |
| 2 | **Announcement writes in one transaction**: `createAnnouncement`, `updateAnnouncement`, `setPinned`, `clientRequestId` check under `withTransaction`, `nextSeq` returning its own UPDATE's value | ANN-01 | SRVHUB-03 **DONE 2026-10-21** |
| 3 | **Announcement expiry tri-state** + console field on create and edit (do it with #2 — same DTO, same store file) | ANN-02, §8.5 | SRVHUB-05, 16 **DONE 2026-10-21** |
| 4 | **Certificate lifetime 30 days, adjustable** (`FLASH_DEVICE_CERT_VALIDITY_DAYS`), derived renewal window/grace, startup warning when the delegation is shorter | §8.2 (first half) | SRVHUB-15 |
| 5 | **Registry-derived principal** + `revokeDevice` + `router.kick` on a department move + console copy | DEV-01, §8.2 (second half) | SRVHUB-04 |
| 6 | **Mailbox quarantine**: schema column, skip-with-LIMIT, `DROPPED` to the origin, metric | RLY-01, §8.3 | SRVHUB-02 |
| 7 | **`/auth/verify` per-device bucket** + client single-flight refresh + jitter + deployment doc | AUTH-01, CLI-01, §8.4 | SRVHUB-08, 10 |
| 8 | **People paging cursor in the store** | DIR-01 | SRVHUB-01 |
| 9 | **Tombstone hygiene**: privacy filter on `removedDeviceIds`, clear a tombstone when the entity returns | DIR-02, DIR-03 | SRVHUB-06, 07 |
| 10 | **Small correctness/ops**: byte budget on relay fan-out (RLY-02), `draining` inside the gate (RLY-03), `MailboxStats` off the caller's thread (OPS-01), one-shot client refresh with timeouts (CLI-02), bounded response reads (CLI-03), `409 NOT_READY` for recipients (FL-01) | Low findings | SRVHUB-09..12 |

Items 1–3 are the ones I would do before any hub is put in front of real users; item 1 alone decides whether the offline-trust feature works at all above ~110 people.

---

## 12. Implementation log (2026-10-21, main checkout, branch `dev`, one commit per plan item)

### Step 1 — Sync transport budget (DIR-04, DIR-05, DOC-01 D-1): DONE, unit and E2E tested, NOT device-verified

- `HubContract`: `MAX_DIRECTORY_SYNC_BODY_BYTES` 2 MiB, `DIRECTORY_SYNC_MEMBER_BUDGET_BYTES` 1 MiB, `DIRECTORY_SYNC_PAGE_LIMIT` 200, `MAX_DIRECTORY_SYNC_ROSTERS` 64.
- `DirectorySyncDto.rostersTruncated` (default false, older hubs read as complete).
- Server `OrganizationDirectory.getSyncChanges`: pages end at `limit` rows or at the member byte budget (`memberBudgetBytes` parameter, default the 1 MiB constant, so tests can lower it), at least one row per page; `signedRostersFor` returns `(rosters, truncated)`; `MAX_ROSTERS_PER_SYNC` 256 -> 64 (decision of 2026-10-21).
- Client `fetchDirectorySync` decodes with the 2 MiB bound; `OrgDirectorySync` asks for 200 per page and, when any page says `rostersTruncated`, keeps the stored rosters of groups the answer does not mention until their own `validUntilMs`.
- Tests: `DirectorySyncTransportTest` (7: 2,500 members, every page under the bound, no member repeated or skipped; byte budget with a small budget and a budget smaller than one row; 70 groups -> 64 + truncated; complete list not truncated; no signer -> truncated; no group -> not truncated), `E2eDirectorySyncSizeTest` (2, real route + real client: 450 members over >= 3 pages of 200 — the page is larger than 64 KiB; a device in 69 groups is told truncated), `OrgDirectorySyncTest` (2 SRVHUB-14 cases). **Mutation:** putting the client's decode bound back to the 64 KiB default made `E2eDirectorySyncSizeTest` fail at the first page, then restored.
- Measured while testing: with real rows the 1 MiB member budget does not bind at 1,000 rows (a row is 0.5-1 KB), so it is a safety net for large names/ids, not the normal limit; the page size is set by `limit` 200.
- Not done: SRVHUB-13's "OrgDirectorySync with a real DAO against the real hub" (the E2E test stops at the real client fetch; the apply path is covered by `OrgDirectorySyncTest`).

### Step 2 - Announcement writes in one transaction (ANN-01): DONE, tested with racing threads, NOT device-verified

- `AnnouncementStore.createAnnouncement`, `updateAnnouncement`, `deleteAnnouncement`, `setPinned` and `purgeExpiredAndOldTombstones` run under `db.withTransaction` (`BEGIN IMMEDIATE`), so the idempotency check, the pin-limit count, the `seq` allocation and the write cannot interleave. The update also says `WHERE id = ? AND seq = ?` and throws `AnnouncementConflictException` when it changed no row (the transaction already serialises writers; this refuses to report a write that did not happen). `nextSeq` keeps its two statements: inside the transaction they are atomic, and `UPDATE ... RETURNING` would tie the store to a newer SQLite.
- Tests: `AnnouncementStoreConcurrencyTest` (4): 10 rounds of 20 racing creates -> distinct, gap-free `seq`; 8 racing updates with the same `expectedSeq` -> exactly 1 success, the rest `AnnouncementConflictException`; 8 racing pins with 2 already pinned -> exactly 1 fits; 8 racing creates with one `clientRequestId` -> one row and one `seq`. **Mutation:** the pre-change store (`git show HEAD:...`) fails all four; the new one passes.
- Test-harness lesson (recorded because it cost a round): `ServerDatabase.inMemory()` has a ONE-connection pool, which serialises writers by itself, and a multi-connection shared-cache in-memory database fails with `SQLITE_LOCKED` (not covered by `busy_timeout`). A race test needs a WAL **file** database with a pool > 1, as production has.

### Step 3 - Announcement expiry tri-state (ANN-02, section 8.5): DONE, tested, NOT device-verified

- `UpdateAnnouncementRequestDto.clearExpiry` (default false). `AnnouncementStore.updateAnnouncement(..., clearExpiry)`: a value sets, null keeps (`current.expiresAt`), null + `clearExpiry` removes; both set is `IllegalArgumentException` in the store and `400` in both PUT routes (device and console). The old `setNull` is gone.
- Console: `announcement-expiry.js` (pure, no imports) decides what a save sends; the composer gets a date-time field and a "No expiry" box on create and edit, a past or invalid date is refused before the request, and the drawer shows when an announcement expires. An untouched field sends nothing, so editing the text keeps the expiry; ticking "No expiry" on an announcement that has one sends `clearExpiry: true`.
- Behaviour change to know: the device composer (`FlashHubScreen`) never sets an expiry, so a device edit used to wipe an expiry set from the console and now keeps it. `FakeHubAnnouncementsApi` follows the same rule.
- Tests: `AnnouncementRoutesTest.anEditKeepsTheExpiryUnlessItIsClearedOnPurpose` (keep, set, both -> 400, clear; **mutation:** `else -> null` in the store makes it fail, restored); `announcements.test.mjs` +4 (82 console tests green).
- Not done: a server-side "expiry must be in the future" check (not in the decision; the console checks it, a device cannot set one).
