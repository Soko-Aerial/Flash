# Server Hub End-to-End Review — Bugs, Wiring, Edge Cases & Feature Traces

**Date:** 2026-10-19
**Status:** REVIEW COMPLETE — 3 confirmed defects FIXED & MUTATION-CHECKED; 1 documentation defect corrected; the remaining items are recorded as findings with a rationale for not changing them in this pass.
**Reviewer:** an AI coding session (owner-requested review + fix pass).
**Scope:** the organisation-hub stack end to end — `:server`, `:core:hub-protocol`, the hub client in `:core:enterprise`, the hub facade/bridge in `:core:messaging`, the host wiring in `:app` (`DiscoveryEngineHolder`) and `:desktop` (`DesktopEngine`), and the `:ui:chat` Hub tab consumers.

Related prior work: [`docs/audit/2026-10-12-server-hub-audit.md`](2026-10-12-server-hub-audit.md) (Plan 1), [`docs/plans/HUB-ANNOUNCEMENTS-PLAN.md`](../plans/HUB-ANNOUNCEMENTS-PLAN.md) (Plan A3), [`docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md`](../plans/HUB-OFFLINE-ORG-TRUST-PLAN.md) (Plan B), [`docs/plans/HUB-QUALITY-CONTRACT.md`](../plans/HUB-QUALITY-CONTRACT.md).

---

## 1. Executive summary

The hub stack was read end to end and every feature was traced from its server entry point to the client code that consumes it (section 5). The wiring is complete: the two hosts build one `EnterpriseHub`, hand its parts to the chat repository, and the Hub tab reads the announcement/roster/directory projections from that repository.

Three confirmed, high-value, low-risk defects were fixed and each is locked by a test whose discriminating power was proven by mutation (section 8):

| ID | Severity | Layer | Defect | Status |
|---|---|---|---|---|
| **FIX-1** | **High (security)** | client `:core:enterprise` | The announcements REST client ignored the hub-URL policy: it would attach the device bearer and issue requests to *any* host, unlike `EnterpriseDirectoryClient`. | **FIXED + MUTATION-CHECKED** |
| **FIX-2** | **Medium (correctness)** | server `:server` | The retention purge hard-deleted old announcements with no tombstone, so a client that had already synced one kept showing it for ever. | **FIXED + MUTATION-CHECKED** |
| **FIX-3** | **Medium (hardening)** | server `:server` | `GET /announcements/{id}`, `POST /{id}/pin` and `POST /{id}/unpin` had no per-route limiter; only the coarse global `principal` bucket covered them. | **FIXED + MUTATION-CHECKED** |
| **DOC-1** | Low (docs) | `docs/protocol.md` | The pin-limit status code was documented as `400 ERR_PIN_LIMIT`; the plan, the code and the tests all use `409 ERR_PIN_LIMIT`. | **FIXED (doc corrected)** |

Everything else examined was found correct, intentional, or already documented; those items are listed as findings in section 4 with the reason each was deliberately left alone.

---

## 2. Fixes applied

### FIX-1 — the announcements client now enforces the hub-URL policy (security)

**Modules/files**
- [`core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/announcements/RealHubAnnouncementsApi.kt`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/announcements/RealHubAnnouncementsApi.kt)
- [`core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseHub.kt`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseHub.kt)
- Test: [`core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/RealHubAnnouncementsApiTest.kt`](../../core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/RealHubAnnouncementsApiTest.kt)

**Symptom.** Every `HubAnnouncementsApi` call (`fetchFeed`, `search`, `getById`, `post`, `edit`, `delete`, `setPinned`) went straight to the caller-supplied `hubUrl` with the device bearer attached. The bearer comes from `HubSession` (the enrolled device's credentials), so a wrong or attacker-supplied URL could receive a valid bearer. `EnterpriseDirectoryClient` (the sibling REST client) already refused this; the announcements client did not — an inconsistency in the same hub client.

**Root cause.** `RealHubAnnouncementsApi` had no `HubUrlPolicy` check and no notion of the pinned hub; its constructor took only the HTTP client, dispatcher and bearer source.

**Remediation.** `execute()` now applies exactly the same rule as `EnterpriseDirectoryClient`, *before* any bearer is attached:
- `HubUrlPolicy.sameHub(hubUrl, pinnedHubUrl())` — refuse a host that is not the enrolled hub (`HubAnnouncementError.Server("This is not the hub this device is enrolled with.")`);
- `HubUrlPolicy.check(hubUrl, allowCleartext())` — refuse an insecure address unless the debug-only cleartext opt-in allows it;
- requests use `HubUrlPolicy.baseUrl(hubUrl)`.

The constructor gained `pinnedHubUrl: () -> String? = { null }` and `allowCleartext: () -> Boolean = { false }`, and `EnterpriseHub` wires them from `trustStore.credentials.value?.hubUrl` and the hub's own `allowCleartext` — so the fix is live in production, not merely a default.

**Verification.** Three new tests in `RealHubAnnouncementsApiTest.kt`:
- `refusesAHubUrlThatIsNotThePinnedHub`
- `theSameHubIsAcceptedAcrossSchemeSpellings`
- `cleartextHubIsRefusedUntilTheDebugOptInAllowsIt`

Mutation: removing the `sameHub` guard made `refusesAHubUrlThatIsNotThePinnedHub` fail (section 8).

---

### FIX-2 — the retention purge now retires old announcements with a tombstone (correctness)

**Files**
- [`server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt`](../../server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt) — `purgeExpiredAndOldTombstones`
- Test: [`server/src/test/kotlin/com/transfer/flash/server/announcements/AnnouncementStoreTest.kt`](../../server/src/test/kotlin/com/transfer/flash/server/announcements/AnnouncementStoreTest.kt)

**Symptom.** With `announcementRetentionDays > 0`, the daily purge deleted old rows outright. The delta feed only carries what changed since the client's last `seq`; a row deleted without a tombstone is never reported, so every device that had already synced the announcement kept rendering it for ever (it is not expired and never becomes a tombstone).

**Root cause.** `purgeExpiredAndOldTombstones` had a single hard-delete for retention with no `seq` bump.

**Remediation.** The method now does three distinct things:
1. still hard-deletes **tombstones** older than 90 days (nothing new to tell a client about a tombstone it already has);
2. hard-deletes rows past their own `expires_at` for real — an expired announcement is already invisible to every read (feed, search and get all filter it out), so no client can be holding it as visible and no tombstone is needed;
3. with `retentionDays > 0`, **retires** old live rows as a soft-delete tombstone with a fresh `seq` (empty title/body, `deleted_at` set, unpinned), so every client learns via the same delta feed a manual delete uses. Step 1 reclaims the row 90 days later.

**Verification.** Four new tests:
- `retentionRetiresOldAnnouncementsWithATombstoneSoClientsLearn`
- `retentionLeavesAnnouncementsInsideTheWindowAlone`
- `expiredAnnouncementsArePurgedForReal` (asserts a delta from the item's own `seq` yields **no** tombstone — a soft-delete would have produced one)
- `oldTombstonesAreReclaimedAfterNinetyDays`

Mutation: replacing the retirement `UPDATE` with a hard `DELETE` made `retentionRetiresOldAnnouncementsWithATombstoneSoClientsLearn` fail (section 8).

---

### FIX-3 — per-route announcement rate limits (hardening)

**Files**
- [`server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutes.kt`](../../server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutes.kt)
- [`server/src/main/kotlin/com/transfer/flash/server/auth/RateLimiter.kt`](../../server/src/main/kotlin/com/transfer/flash/server/auth/RateLimiter.kt) (comments)
- Test: [`server/src/test/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutesTest.kt`](../../server/src/test/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutesTest.kt)

**Symptom.** Plan A3 §3.3 asks for per-device limits on the announcement routes. `GET /announcements` (feed/search) carried `announcementRead`; `POST /announcements`, `PUT` and `DELETE` carried `announcementPost`. But `GET /announcements/{id}` — the single-item read path used by the detail view — consumed only the coarse global `principal` bucket (burst 120, 10/s), and `POST /{id}/pin` / `POST /{id}/unpin` consumed none of the announcement budget. This is a hardening gap rather than a hole (the global bucket still caps absolute volume), but the single-item read is the cheap per-`id` route a caller can hammer.

**Remediation.** `GET /{id}` now acquires `limits.announcementRead`; `POST /{id}/pin` and `POST /{id}/unpin` now acquire `limits.announcementPost`, all with the same `429 ERR_RATE_LIMIT` + `Retry-After` shape the other routes already use.

**Verification.** A new test `singleItemReadsAndPinActionsAreRateLimitedPerDevice` mounts the routes with a real `HubRateLimits(clock = { 1_000L })` (frozen clock ⇒ the burst is the whole budget), seeds via the store directly so seeding consumes no post budget, then proves: 60 single-item reads pass and the 61st is `429`; the admin's own read is unaffected (the budget is per device); 5 pins pass and the 6th is `429`. Existing tests are unaffected because they mount `HubRateLimits.unlimited()`.

Mutation: removing the two new limiter guards made the test fail (section 8).

---

## 3. Documentation correction

### DOC-1 — pin-limit status code in `docs/protocol.md` (`400` → `409`)

`docs/protocol.md` ("Announcements API") stated twice that exceeding the pin limit is `400 ERR_PIN_LIMIT`. The implementation returns `409 Conflict` (`AnnouncementRoutes` create + pin/unpin), the client maps `ERR_PIN_LIMIT` to `HubAnnouncementError.PinLimit` on both `400` and `409`, the tests assert `409`, and the plan itself ([`HUB-ANNOUNCEMENTS-PLAN.md`](../plans/HUB-ANNOUNCEMENTS-PLAN.md) §3 route table, §"Risks" line 73/166) says **409 with a sentence**. The two `400` mentions were the outliers, so the doc was corrected to `409 ERR_PIN_LIMIT`. No wire behaviour changed.

(The plan's own §"Risks" bullet "…returning `400 ERR_PIN_LIMIT`" is a leftover that contradicts its own route table; left as-is because the plan is a historical record, but noted here.)

---

## 4. Findings recorded, not fixed

These were examined and deliberately left unchanged in this pass, each for a stated reason. They are candidates for a future, owner-decided change.

### F-1 — Search `total` is the page size, not the match count (server)

`AnnouncementStore.searchAnnouncements` returns `total = items.size` ([`AnnouncementStore.kt`](../../server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt) ~L618). It is therefore the size of the returned page (≤ `limit`), never the real number of matches.

**Why not fixed:** no consumer reads it. The client (`RealFlashChatRepository.searchHubAnnouncements`, `RealHubAnnouncementsApi.search`) uses only `items`; paging uses `nextBefore`. A real total would need an extra `COUNT(*)` over the FTS/LIKE query on every search — a cost with no present benefit. If a "N results" label is ever added, either compute the count then, or rename the field to `pageSize` (a wire change needing a doc/client update).

### F-2 — A `409` on `PUT /announcements/{id}` carries no current item (server/wire)

Plan A3 §3.5 says an `expectedSeq` mismatch should answer `409` "with the current item" so the client can offer "Reload". The implementation returns only `ErrorBody{code=ERR_CONFLICT}`. Consequently `HubAnnouncementError.Conflict.current` is always `null` and the console/UI conflict path cannot show the newer version without a re-fetch.

**Why not fixed:** the current wire shape is what `docs/protocol.md` documents ("`409` if `expectedSeq` mismatch") and no client depends on a body. Adding the item would be a real wire-shape change with two clients and a doc to update in lock-step, and it needs an owner decision on whether the item belongs in the error body or a distinct envelope. Recorded so it is not lost; the re-fetch path already works.

### F-3 — `RelaySession.dispose()` is dead code (server)

`RelaySession.dispose()` ([`RelaySession.kt`](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt) ~L267) cancels the per-session `scope`, but it has **no callers** anywhere in `:server` (grep for `.dispose()` finds none). Session teardown already cancels the writer in the router's `finally`, and the standalone `CoroutineScope(SupervisorJob() + Dispatchers.IO)` holds no other job, so this is not an observed leak — but the method is misleading: a reader may believe the scope is released when it never is.

**Why not fixed:** small and harmless, and choosing between "call it on every session-end path" and "delete it" touches the relay lifecycle, which the release-readiness sweeps already reviewed (ADR-103/105/106). Recommend either deleting the method or calling it from the session-close path (never both) in a focused follow-up; low priority.

### F-4 — an authoritative full resync with an empty roster offer wipes stored hub group rosters (client)

`OrgDirectorySync` treats a **full resync as authoritative** for hub-signed group rosters: `verifiedRosters(..., authoritative = isFullResync)` and then `applySyncBatchWithRosters` → `replaceGroupRosters(rosters)` replaces the whole roster table with exactly what was offered ([`OrgDirectorySync.kt`](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt) ~L408-427, [`OrgDirectoryDao.kt`](../../core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/OrgDirectoryDao.kt) ~L133-146). The server returns rosters **only on the last page** of a sync (`signedRostersFor`, and only if `nextCursor == null`), and skips a group whose roster is over `MAX_ROSTERS_PER_SYNC`.

Consequence: if a hub that previously offered rosters later offers an **empty** roster set on a full resync — no roster signer configured, a restored/blank hub database, epoch reset, or all groups over the cap — every stored roster is dropped, and hub groups lose their hub-signed membership until the hub offers rosters again. The intended meaning ("the hub no longer lists this group ⇒ drop it") is indistinguishable on the wire from "the hub could not produce rosters this time".

**Why not fixed:** the correct fix is an explicit signal — e.g. a `rostersAuthoritative` flag or a null-vs-empty distinction on `DirectorySyncDto` — which is a **wire change** and therefore barred by this pass's rule (do not change documented wire shapes). It also needs an owner call on whether an absent roster set should ever be authoritative. Recorded; recommend the explicit signal if hub groups become load-bearing.

### Not a defect — dual role-string matching (server)

`AnnouncementRoutes` / `AnnouncementStore` accept **both** `"admin"`/`"ORG_ADMIN"` and `"dept_admin"`/`"DEPT_HEAD"` for poster/role checks. This is correct, not sloppy: a device bearer carries the cert role (`HubRoles` = `member`/`dept_admin`/`admin`), while the admin console and directory DTOs carry `HubDirectoryValues.ROLE_*` (`ORG_ADMIN`/`DEPT_ADMIN`/`MEMBER`), and both paths write announcements (`authorRole`). The dual match is required for the same announcement to be editable/deletable/pinnable whichever path created it. The client's `FlashHubMath.authorRoleChip` similarly accepts both vocabularies.

---

## 5. End-to-end feature traces (server ⇄ app)

Each row was traced from the server entry point through the wire type to the client code and the consuming UI. "✓" means every link exists in the current tree.

| Feature | Server entry | Wire | Client | Consuming UI | ✓ |
|---|---|---|---|---|---|
| **Enrolment** | `/enroll` (+ invite, proof of possession) | `EnrollmentInvite`, `EnrollRequest/Response` | `EnterpriseEnrollmentClient` → `EnterpriseTrustStore` credentials | Settings → Hub (enrol dialog) | ✓ |
| **Relay handshake** | `/hub` WebSocket, `RelayAuth` (wire v2 `FLASH_WS_HELLO`) | `RelayFrame.Hello` (device/delegation certs, `features` incl. `ann1`, `gs1`, `cv1`) | `FlashRelayClient` via `FlashRelayHubPort` | connection status in Hub tab / Transfers | ✓ |
| **Directory sync** | `GET /directory/sync` (cursor pages, epoch, rosters on last page, revocations) | `DirectorySyncDto`, `OrgMemberStatement` | `OrgDirectorySync` (single-flight, atomic staging, backoff) + `OrgSnapshotCoordinator` | Hub tab "People", colleague dial list | ✓ |
| **Hub-signed group rosters** | `signedRostersFor` on the last page | `GroupRosterDto` | `HubGroupRosters` → `RealFlashChatRepository.refreshHubGroupRosters()` | group UI via the LAN roster gate | ✓ (see F-4) |
| **Announcements — read/feed** | `GET /announcements?since=` / `q=` / filters | `AnnouncementFeedDto`, `AnnouncementSearchResponseDto` | `RealHubAnnouncementsApi` → `refreshHubAnnouncements` / `searchHubAnnouncements` → Room (`hubAnnouncementDao`) | `FlashHubRoute` news list; unread badge in `MainActivity`/`DesktopShell` | ✓ |
| **Announcements — live push** | `router.broadcastAnnouncement(AnnouncementEvent)` after create/update/delete/pin/unpin | `RelayFrame.AnnouncementEvent` | `hubAnnouncementEvents` flow → `refreshHubAnnouncements` (guarded by `ann1`) | same news list updates live | ✓ |
| **Announcements — catch-up** | delta `since=<last seq>` (incl. tombstones) | `AnnouncementFeedDto` | reconnect/`up` edge triggers `refreshHubAnnouncements` | news list, unread | ✓ |
| **Announcements — write** | `POST/PUT/DELETE /announcements*`, pin/unpin (role-scoped) | `PostAnnouncementRequestDto`, `UpdateAnnouncementRequestDto`, `AnnouncementItemDto` | `RealFlashChatRepository.postHubAnnouncement` / edit / delete / pin (local role gate) | Hub tab composer (admins/heads) | ✓ |
| **Announcements — unenrol** | — | — | unenrol clears stored announcements | news list empties | ✓ |
| **Announcements — expiry** | `expires_at` filter on every read | — | filtered client-side in `hubAnnouncements` / `hubUnreadAnnouncementCount` (`FlashHubMath.isLive`) | expired items hidden | ✓ |
| **Mailbox / offline messages** | relay mailbox, ack by sender, sealed payloads (`data.key`) | `RelayFrame` message types | `FlashRelayHubPort` → chat repository | chat | ✓ |
| **Sealing keys (ADR-124/126)** | `PUT /device/sealing-key`; served on `MemberDto` | binding signature, `SEALED_V1` envelope | `DirectoryHubSealing` + `HubSealingPins` | "Messages through the hub" path | ✓ |
| **Revocations (v3, hashed)** | `GET /revocations` | hashed list | `HubRevocationCache` → directory sync | gate rejections | ✓ |
| **Console announcements** | `/admin/api/v1/announcements*` (org admin + dept heads) | same DTOs | n/a (admin UI in `:server`) | admin console | ✓ |

Notes:
- The `ann1` capability is advertised in `Hello.features`; the server only pushes `AnnouncementEvent` to sessions that advertise it (`RelayFrameRouter`), and the client only subscribes when present.
- Announcement authorisation is two-layered by design: the hub is the source of truth (role + department scope), and the client's `postHubAnnouncement` applies a matching local gate so a member never even attempts a post.

---

## 6. Hub wiring in the hosts

Both hosts build the same object graph; the hub is one `EnterpriseHub` shared with the chat stack.

**Android** — [`app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`](../../app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt):
- builds `EnterpriseHub(...)` (L1175), `hub.start()` (L1187), keeps it in `enterpriseHubRef` (L1186);
- sealing: `AndroidHubSealingKeyStore`, `HubSealingPins`, `DirectoryHubSealing` (L1184-1202);
- rosters/revocations: `HubRevocationCache`, `HubGroupRosters`, refresh/clear on connect (L1222-1257);
- chat wiring: `FlashRelayHubPort(hub.relay)`, `enterpriseDirectoryClient = hub.directory`, `hubPeers = hub.peers`, `hubAnnouncementDao = db.hubAnnouncementDao()`, `hubAnnouncementsApi = hub.announcements`, `hubTabSources`, `hubSealing` (L1477-1504);
- shutdown: `enterpriseHubRef?.shutdown()` and null it (L3071-3072).

**Desktop** — [`desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt): the same graph (L1336-1615), `allowCleartext` from the `flash.allowCleartextHub` system property, sealing key in a DPAPI-wrapped file, shutdown at L1109.

**UI** — the Hub tab ([`ui/chat/.../ui/hub/FlashHubRoute.kt`](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/FlashHubRoute.kt)) reads `repository.hubAnnouncements` (and the unread count via `hubUnreadAnnouncementCount` in `MainActivity` / `DesktopShell`). It is a pure projection: no hub networking happens in the UI module.

Observation (recorded, not a change): the announcement feed's initial sync uses `since=0` (newest up to `limit`, `fullResync=true`) and the client loops `maxLoops = 20`, pruning to 500 stored items; delta pages use `nextCursor` only when a page is full. This is bounded and correct.

---

## 7. Edge cases reviewed

Checked and found handled (no change needed): unauthenticated access to every announcement route (bearer required); outsider access to a hidden/other-department item (404 by design, no existence leak); SQL-injection-shaped `departmentId`/query (parameterised; the route test feeds a quote); idempotent create via `clientRequestId`; `expectedSeq` conflict; pin-limit per scope; expiry filtered on both server and client; audit log never contains title/body; soft-delete tombstone in the delta feed; `429` + `Retry-After`; multi-page directory sync with epoch reset forcing a full resync; stale serial protection; clock-skew clamp; hard lockout on root-key mismatch; roster signature/chain/window/revocation checks; sealed-payload signature verification before use.

Now covered by the new tests: the announcements client refusing a foreign/cleartext hub (FIX-1), retention producing a tombstone while expired items are purged for real (FIX-2), and single-item reads / pin actions being rate-limited per device (FIX-3).

Recorded as open edges (section 4): roster wipe on an empty authoritative offer (F-4), search `total` not being a real count (F-1), a `409` conflict carrying no current item (F-2).

---

## 8. Verification

All Gradle ran through the lock script (`tools\flash-gradle.ps1`), never `gradlew`, per §36.

### Test runs (green)

| Command (abridged) | Result |
|---|---|
| `:server:test --tests "*AnnouncementStoreTest" --tests "*AnnouncementRoutesTest" --tests "*RouteTableContractTest"` | **BUILD SUCCESSFUL** — Store 14/0, Routes 12/0, RouteTable 2/0 |
| `:core:enterprise:jvmTest --tests "…RealHubAnnouncementsApiTest"` | **BUILD SUCCESSFUL** — 13/0 |
| `:app:compileDebugKotlin :desktop:compileKotlinJvm` | **UP-TO-DATE / clean** |

### Mutation checks (each fix's test proven to discriminate)

With all three mutations applied at once, exactly the intended tests failed:

| Mutation | Test that failed | Outcome |
|---|---|---|
| `RealHubAnnouncementsApi`: `sameHub` guard forced off | `RealHubAnnouncementsApiTest.refusesAHubUrlThatIsNotThePinnedHub` | **caught** |
| `AnnouncementStore`: retention `UPDATE` replaced by hard `DELETE` | `AnnouncementStoreTest.retentionRetiresOldAnnouncementsWithATombstoneSoClientsLearn` | **caught** |
| `AnnouncementRoutes`: new `announcementRead`/`announcementPost` guards forced off | `AnnouncementRoutesTest.singleItemReadsAndPinActionsAreRateLimitedPerDevice` | **caught** |

All mutations were then reverted and the green run repeated.

### Flaky observation (not caused by this work)

`core:enterprise:jvmTest` → `OrgDirectorySyncTest.ORGT-U16` ("5,000-member sync time and memory budget test") failed once during the mutated run and passed on the clean run. It is a wall-clock/memory budget test sensitive to machine load (the mutated run had the server suite running alongside it). It is unrelated to these changes (it touches neither announcements nor the hub-URL policy). Not recorded as an `ERROR` this time; if it flakes again it should get one under `docs/testing/TEST-BACKLOG.md`.

---

## 9. Device tests owed

The server-side changes are covered by JVM tests but the client-facing effect (the hub-URL policy on a real device) and the retention tombstone propagation to a phone deserve a device check. Added to [`docs/testing/TEST-BACKLOG.md`](../testing/TEST-BACKLOG.md):

- **HUBR-11** — announcements client refuses a hub whose URL is not the pinned hub (point the app at a second hub; expect the friendly message, and no bearer reaching it), and refuses a cleartext hub without the debug opt-in.
- **ANN-14** — retention retirement reaches a device: with `announcementRetentionDays > 0`, an old announcement disappears from the news list after a delta sync rather than lingering.
- **ANN-15** — single-item reads / pin actions return `429` with `Retry-After` under a burst, per device, and the app surfaces the friendly message.

---

## 10. References

- [`docs/audit/2026-10-12-server-hub-audit.md`](2026-10-12-server-hub-audit.md) — the original hub audit (Plan 1).
- [`docs/audit/2026-10-18-full-app-audit-and-wiring.md`](2026-10-18-full-app-audit-and-wiring.md) — the repo-wide audit whose method and doc style this report follows.
- [`docs/plans/HUB-ANNOUNCEMENTS-PLAN.md`](../plans/HUB-ANNOUNCEMENTS-PLAN.md) — Plan A3 (announcements).
- [`docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md`](../plans/HUB-OFFLINE-ORG-TRUST-PLAN.md) — Plan B (offline org trust, rosters).
- [`docs/plans/HUB-QUALITY-CONTRACT.md`](../plans/HUB-QUALITY-CONTRACT.md) — the quality bar this pass followed.
- [`docs/protocol.md`](../protocol.md) — "Announcements API" (DOC-1 correction) and the hub relay/REST contract.
- [`docs/protocol-routes.txt`](../protocol-routes.txt) — the checked list of every REST route (`RouteTableContractTest`).
