---

## 2026-10-14 - A1 explain-back (quality contract section 2, no code yet)

### What the phase must achieve and why
Department heads need console logins scoped to their own departments (today the
console assumes org admin and no admin endpoint filters by department), plus
invite roles Employee/Department head with a first-head dialog. The same person
usually holds a head DEVICE (cert role dept_admin, in Department.adminDeviceIds)
and a head LOGIN (admin_users role DEPT_HEAD bound to departments). Scope is
re-read from the DB on every request; unknown/disabled/missing means empty scope.

### Files I will touch
- NEW server/.../admin/ConsoleScope.kt (scopeOf/allows/requireOrg + route
  policy table + policy completeness test).
- AdminAuthService.kt (admin migration 4: display_name, linked_device_id,
  admin_user_departments; scope reads; head CRUD; session purge on disable;
  last-org-admin guard already exists).
- AdminApi.kt (overview/devices/invites/session scoping; invite
  restrictions; bulk forced to own department).
- AdminApiDirectory.kt + AdminApiDtos.kt (departments/groups/join-request
  scoping; new GET/PUT /admins/{u}/departments; POST /admins head creation;
  session fields).
- OrganizationDirectory.kt (addDepartmentAdmin on enrol; scope-row cleanup
  on department delete) + HubServices.kt (call it on enrol + reconcile).
- Console security.js (Add department head), organization.js (Heads list
  + Invite-first-head dialog), invites.js (role radio + required
  department), en.js strings, header switcher.
- NEW tests: ConsoleScopeTest (DEPH-U02..U17 family) + node tests.
- docs/protocol.md (admin API changes), routes regen (2 new routes),
  docs/ui/hub-admin-console.md (Department heads section).

### Three most similar existing classes read per new class
- AdminAuthService (sessions/lockout/step-up patterns) + MIGRATIONS
  1..3: reuse insertAdmin/adminRow/seal patterns; migration 4 same shape.
- DirectoryVisibility (A2 single-decider): same one-function pattern for
  ConsoleScope; policy table mirrors the route table the same way.
- RouteTableContractTest + AdminApiTest Realm (logs in, holds CSRF,
  does step-up): reuse as the template for scoped route tests.
- organization.js dialogs + invites.js form + strings/en.js
  (node strings test enforces keys): reuse dialog/confirm/toast patterns.
- EnrollmentService.createInvite + HubServices.applyToDirectory:
  reuse invite scope validation; add the missing adminDeviceIds write.

### Five likeliest failures
1. A route missing from the policy table (new route added later without policy).
2. IDOR: head of A fetches object of B (must be 404 with the exact
   non-existent body, never 403 for object lookups).
3. Listing leaks: totals/cursors/suggestions revealing out-of-scope objects.
4. Scope cached in session instead of re-read (demotion must apply next request).
5. Enrol/revoke/department-delete leaving adminDeviceIds or scope rows behind.

### Tests first
DEPH-U01 migration from current admin DB; U02 policy-table completeness (test
first: fails until policies declared); U04 head matrix; U05 IDOR 404-equality;
U06 invite restrictions; U09 session purge; U10 scope-next-request; U11 last
admin guard; U12 enrol consistency; U14 audit; U16 old-console compat; node UI
tests; e2e head-invite-enrol-invite-employee.

### VERIFY answers (plan section 2, section 3.8)
- V1 admin_users.role never compared except stored: TRUE (grep: stored, read
  into sessions, never branched on).
- V2 enrolment does NOT add dept_admin devices to Department.adminDeviceIds
  (HubServices.applyToDirectory funnels to applyEnrollment only): must ADD in A1.
- V3 enrolled_device_id is step-up/console-key binding; approver_device_id is
  FO-14 phone: new linked_device_id column is separate (different meaning).
- V4 last-enabled-ORG_ADMIN guard EXISTS (setDisabled checks others greater
  than 0).
- V5 bulk invites are members only (AdminApi.kt refuses other roles).
- V6 admin migration 3 added approver_device_id: TRUE.
- V7 old console + new server: session adds fields only; old pages get the
  standard 403 error DTO on scoped routes (test U16).

---

## 2026-10-15 — Plan A3 Explain-Back (quality contract section 2, no code yet)

### What the phase must achieve and why
Plan A3 implements organization announcements: one-way broadcast messages with a title and plaintext body stored unencrypted on the hub (ADR-138). Org admins can post company-wide or to any department; department heads can post only to their assigned department(s); regular employees can only read and search. The server provides a migration (component "announcements", version 1), SQLite FTS5 full-text indexing, strict input sanitization (`AnnouncementText.clean`), authenticated REST routes for devices and console admins, an idempotent changes feed (`since=<seq>`), optimistic concurrency on edits (`expectedSeq`), pin limits (max 3 org, max 3 dept), optional expiry/retention cleanup, capability-gated live WebSocket push via `RelayFrame.AnnouncementEvent` with `ann1` feature negotiation, and console management UI with live rendering preview. On the client side, it adds DTOs, Room schema step 17 with `HubAnnouncementEntity` and `HubAnnouncementStateEntity`, `HubAnnouncementsApi`, and repository operations in `FlashChatRepository` with typed error handling.

### Files I will touch / create
1. `server/.../announcements/AnnouncementText.kt`: text sanitization. Similar: `TextSearchFold.kt`, `RelayFrameCodec.kt`, `AuditLogger.kt`. Reuse Unicode code-point normalization, strip bidi overrides & zero-width chars, collapse excessive newlines.
2. `server/.../announcements/AnnouncementStore.kt` & `AnnouncementRoutes.kt`: SQLite persistence, migrations, REST device routes. Similar: `OrganizationDirectory.kt`, `DirectoryRoutes.kt`, `MailboxStore.kt`. Reuse `ServerDatabase`, transaction management, monotonic `seq`, token bucket limits, `HubApiGate`.
3. `server/.../announcements/AnnouncementAdminRoutes.kt`: console admin endpoints. Similar: `AdminApi.kt`, `AdminApiDirectory.kt`, `ConsoleScope.kt`. Reuse `ConsoleScope.allows(deptId)` and `requireSession`.
4. `core/hub-protocol/.../rest/AnnouncementDtos.kt`: DTO models. Similar: `RestDtos.kt`, `DirectorySyncDto.kt`, `RelayFrame.kt`. Reuse `@Serializable`, `@FlashInternalApi`, `HubContract`.
5. `core/hub-protocol/.../relay/RelayFrame.kt`: `AnnouncementEvent` and `Hello.features`. Similar: other `RelayFrame` definitions, `RelayFrameCodec.kt`.
6. `core/persistence/.../db/`: Room schema step 17, `HubAnnouncementEntity.kt`, `HubAnnouncementStateEntity.kt`, `HubAnnouncementDao.kt`, `FlashSchemaSteps.kt`, `FlashDatabase.kt`. Similar: `OrgDirectoryMemberEntity.kt`, `OrgSyncStateEntity.kt`, `DraftEntity.kt`. Reuse Room annotations, transactions, flow queries.
7. `core/enterprise/.../announcements/`: `HubAnnouncementsApi.kt`, `RealHubAnnouncementsApi.kt`. Similar: `EnterpriseDirectoryClient.kt`, `HubSession.kt`, `HubPeerDirectory.kt`. Reuse Ktor client, bearer provider, `FlashResult`.
8. `core/messaging/.../`: `FlashChatRepository.kt`, `RealFlashChatRepository.kt` announcement additions.
9. `server/src/main/resources/admin-ui/`: `js/views/announcements.js`, `strings/en.js`. Similar: `organization.js`, `invites.js`, `overview.js`. Reuse safe DOM building (`textContent`, NO `innerHTML`), API helper.

### Five likeliest failures
1. Concurrency: Simultaneous edits colliding. Guarded by atomic SQLite transactions and `expectedSeq` optimistic locking (returning 409 Conflict with current item).
2. Wire & Backward Compatibility: Older clients crashing on new frame. Guarded by capability gating `ann1` in `Hello.features` and forward-compatible codec ignoring unknown frames/fields. Old hubs communicating with new clients guarded by feature discovery and `org_ann_unsupported`.
3. Security & Scope Leaks: Department head posting or viewing cross-department or hidden department announcements. Guarded by enforcing department match against authenticated session/cert, not request body, and filtering inside SQL. Announcements explicitly stored in plaintext (ADR-138) with no false encryption claims.
4. Scale & FTS injection: Malformed search queries breaking FTS5. Guarded by parameterization and escaping of FTS operators, query length bounds (<= 64 chars), and SQL pagination.
5. Platform & Unicode Differences: Code-point counting vs Java string length (surrogate pairs / emoji). Guarded by Unicode code point counting, NFC normalization, stripping bidi overrides.

### Tests first
`AnnouncementTextTest`, `AnnouncementAuthorizationTest`, `AnnouncementVisibilityTest`, `AnnouncementFtsTest`, `AnnouncementConcurrencyTest`, `AnnouncementIdempotencyTest`, `RelayAnnouncementEventContractTest`, `FlashMigrationsChainTest`, `FlashJvmMigrationsTest`.

