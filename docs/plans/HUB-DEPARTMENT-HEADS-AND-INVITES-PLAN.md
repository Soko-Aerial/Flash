# Plan A1 — Department heads: console logins, scoped admin API, invite roles

**Written:** 2026-10-14. **Status: PLANNED, nothing built.** Read `docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md` first (rules, ground truth, expectations).
**Owner decisions used:** D3 (several heads per department), D5 (heads post announcements for their own department, from app and console), D7 (invite roles Employee / Department head,
creating a department offers "invite its first head").
**ADR:** ADR-136 (ACCEPTED by the owner 2026-10-14, "all recommended"). **Tests owed:** `DEPH-01`… in `docs/testing/TEST-BACKLOG.md` section 4zzzzzzzzz.
**Build order:** after Plan B phase B1 and Plan A2 (directory privacy), before Plan A3 (announcements need the head role).

**Quality contract (mandatory, read before coding):** `docs/plans/HUB-QUALITY-CONTRACT.md` applies to every phase of this plan: explain-back before code, the 12 coverage categories, evidence table, banned shortcuts, adversarial self-review (>= 10 findings), phase report. **The minimum is a failure.** The section "Beyond the minimum" below is required work, not a wish list.

---

## 1. What the owner asked for

1. "Department heads with logins" on the **server side**: a person who manages only their department through the admin console (the same console the org admin uses).
2. When a department is created, **inviting employees** offers two roles: **Department head** and **Employee**.
3. A department may have **several heads**.
4. A department head can post announcements to his department **from the app and from the console** (the posting itself is Plan A3; this plan provides the role, login, scope and the checks).

## 2. Ground truth (read 2026-10-14; implementer VERIFY every line before use)

- `admin_users(username, password_hash, totp_secret (sealed), role, enrolled_device_id, disabled, last_totp_step, created_at, created_by)` in `AdminAuthService.kt` (~line 139); `role` default `"ORG_ADMIN"`;
  `createAdmin(username, password, createdBy, role = "ORG_ADMIN"): AdminCredentials` (~534); sessions (`admin_sessions`) carry `role` (~143, 445-470); `AdminSession` has `username`, `role`, `csrfToken`.
- `ApplicationCall.adminSession(d, requireSession)` (`AdminApi.kt` ~108) is the single entry for session + CSRF; `stepUpOk(d, session, action, target)` (~149); `StepUpActions` constants (`StepUpAuth.kt`).
- Admin routes (from `docs/protocol-routes.txt`): admins (list/create/disable/enable/password/totp-reset/me/*), audit*, delegation*, departments (GET/POST/PUT/DELETE), devices (GET/GET{id}/PUT/revoke/unrevoke),
  groups (GET/POST/PUT/DELETE, members POST/DELETE), invites (GET/POST/bulk/revoke), join-requests (GET/approve/reject), metrics, overview, revocations*, session, sessions*, settings (GET/PUT), setup*, stepup*.
- Invite routes already accept `department`, `role`, `allowedGroups`, `autoJoinGroups`, `ttlMinutes`, `maxUses`, `note`; non-member roles need step-up `StepUpActions.ADMIN_CREATE`; bulk = members only.
- Hub certificate roles: `HubRoles.MEMBER = "member"`, `DEPT_ADMIN = "dept_admin"`, `ADMIN = "admin"`; scopes `enrol:member|dept_admin|admin`, `revoke:member`; admin never via wildcard.
- `Department(id, name, adminDeviceIds)` (so a head is a **device** id today); only org admin creates/changes/deletes departments (D-E13); a department admin (device) may create groups in their department through REST.
- **No admin-API endpoint filters by department. The console assumes an org admin.** Today's role string is never compared anywhere except being stored. VERIFY with `grep -rn "\.role" server/src/main`.

## 3. Design

### 3.1 Two kinds of "head", one concept
- **Head device** (phone/desktop app): a device enrolled with role `dept_admin` + department; present in `Department.adminDeviceIds`. Powers: whatever the REST directory API already gives a department admin, plus announcements (A3).
- **Head login** (console): an `admin_users` row with `role = "DEPT_HEAD"` bound to one or more departments. Powers: the scoped console (§4).
- The same person usually has both. They are **linked** by the org admin (optional) through `admin_users.linked_device_id` so announcements show the same author (A3) and the approver-phone flow (ADR-131) is not confused with this link (**different columns, different meaning**; do not reuse `enrolled_device_id`/`approver_device_id`; VERIFY what each means before choosing the column name).

### 3.2 Console roles
| Role string | Who | Scope |
|---|---|---|
| `ORG_ADMIN` | existing | everything |
| `DEPT_HEAD` | new | only the departments listed in `admin_user_departments` |

Never trust the role string in the session row for scope: the scope is **re-read from the database on every request** (so a demotion, a department change or a disable applies immediately). Cache nothing across requests except per-request.

### 3.3 Schema (server, `Migrations.kt`, component "admin": next free step; VERIFY current version, admin migration 3 added `approver_device_id` on 2026-10-14)
```
ALTER TABLE admin_users ADD COLUMN display_name TEXT NOT NULL DEFAULT ''
ALTER TABLE admin_users ADD COLUMN linked_device_id TEXT            -- nullable
CREATE TABLE admin_user_departments(username TEXT NOT NULL, department_id TEXT NOT NULL, added_by TEXT NOT NULL, added_at INTEGER NOT NULL,
                                    PRIMARY KEY(username, department_id))
CREATE INDEX admin_user_departments_dept ON admin_user_departments(department_id)
```
Migration test (pattern: existing `MigratorTest.kt`). Migrations are forward-only and idempotent. Existing admins keep `ORG_ADMIN` and have no rows in the new table (they do not need any).

### 3.4 Scope function (single source of truth)
```kotlin
sealed interface ConsoleScope {
    data object Org : ConsoleScope
    data class Departments(val ids: Set<String>) : ConsoleScope   // may be empty
}
fun AdminApiDeps.scopeOf(session: AdminSession): ConsoleScope        // reads DB each call; unknown role => empty Departments (deny)
fun ConsoleScope.allows(departmentId: String?): Boolean              // Org: true; Departments: id in ids; null/blank department => false for heads
fun ConsoleScope.requireOrg(call): Boolean                           // responds 403 and returns false for heads
```
Unknown/legacy role strings, a missing user row, a disabled user → **empty scope + 403 on everything except `GET /session` and logout**.

### 3.5 Route policy table (default deny; enforced by a test that lists every `/admin/api/v1/*` route and fails if a route has no declared policy)

`ORG` = org admin only; `OWN` = head allowed, restricted to own departments; `SELF` = any signed-in admin acting on their own account.

| Route | Policy | Notes |
|---|---|---|
| `GET /admin/api/v1/session` | SELF | returns `role`, `displayName`, `departments:[{id,name}]` (new fields; old console ignores) |
| `POST login/logout`, `GET /sessions` (own), `POST /admins/me/password|recovery-codes|console-key|device|approver*` | SELF | head must complete the same password/TOTP rules (below) |
| `GET /admins`, `POST /admins`, `…/disable|enable|password|totp-reset` | ORG | creating a head = `POST /admins` with `role: DEPT_HEAD`, `departments: [...]`, `displayName`, `linkedDeviceId?` (step-up ADMIN_CREATE as today) |
| `GET/PUT /admins/{username}/departments` (new) | ORG | add/remove a head's departments; removing the last one leaves a login with empty scope (allowed, shown as "No departments") |
| `GET /audit*`, `/audit/verify|head|export` | ORG | heads get no audit in v1 (parked idea: per-department audit view) |
| `GET /delegation`, `POST /delegation`, `/delegation/reload` | ORG | |
| `GET /revocations`, `POST /revocations/install|serial` | ORG | |
| `GET /settings`, `PUT /settings` | ORG | includes offline grace (Plan B) |
| `GET /metrics`, `GET /sessions` (all) , `POST /sessions/{id}/revoke` | ORG | a head may revoke only own sessions |
| `GET /overview` | OWN | counts limited to own departments (members, devices online, open join requests, pending invites); no server/system info |
| `GET /departments` | OWN | only own; response includes heads' names |
| `POST /departments`, `PUT /departments/{id}`, `DELETE /departments/{id}` | ORG | D-E13: heads do not create/rename/delete departments (owner decision 2026-10-14: heads do not rename in v1) |
| `GET /devices`, `GET /devices/{id}` | OWN | devices whose member department is in scope; id of another department → **404** |
| `PUT /devices/{id}` | OWN (display name only) | a head may not change a device's department or role (cross-department moves are org-admin) |
| `POST /devices/{id}/revoke|unrevoke` | ORG | v1; owner decision 2026-10-14: heads do not revoke leavers in v1 |
| `GET/POST/PUT/DELETE /groups…`, `POST/DELETE /groups/{id}/members…` | OWN | only groups whose `departmentId` is in scope; may add only members of own department; org-wide groups (no department) are ORG |
| `GET /join-requests`, `POST …/approve|reject` | OWN | only requests for groups in scope |
| `GET /invites` | OWN | only invites whose `department` is in scope |
| `POST /invites`, `POST /invites/bulk`, `POST /invites/{id}/revoke` | OWN with restrictions | head: `role` must be `member`; `department` must be in scope and **required**; `allowedGroups`/`autoJoinGroups` must be groups of that department; a head cannot create a `dept_admin` or `admin` invite (those are ORG + step-up) |
| `GET/POST /stepup*` | SELF | |
| Announcements routes (Plan A3) | OWN | defined in A3 |
| setup*, enroll landing, health | unchanged | |

Cross-department object ids (group/device/invite/join-request) outside the scope return **404 Not Found**, not 403 (no existence leak). A list endpoint filters; it never returns "forbidden" for a partly allowed list.

### 3.6 Console login for a head
- Org admin creates the login (Security view → "Add department head": username, display name, temporary password, departments multi-select, optional linked device). The server returns the TOTP secret/QR **once** exactly as for org admins today (VERIFY and reuse that UX and the `AdminCredentials` return type; do not invent a second credential flow).
- First login rules same as any admin: password change if the existing flow forces it (VERIFY), TOTP enrolment, recovery codes; **password policy is the existing one**. Rate limit and lockout are the existing ones.
- Session lifetime: same or shorter (VERIFY the existing setting; heads get the same).
- Heads **cannot** create other logins. Heads **cannot** see other admins.
- Step-up for heads: none of the head actions are step-up actions in v1, so a head needs no console key. Their UI must not offer step-up-gated actions (they do not appear).
- Disabling or removing a head: org admin disables the login → all that user's sessions are deleted in the same transaction; the head device role (`adminDeviceIds`) is a separate action (§3.8).

### 3.7 Invite UX in the console (`admin-ui/js/views/invites.js`, `strings/en.js`)
- Invite form: **Who is it for?** radio: **Employee** (`member`, default) / **Department head** (`dept_admin`, org admin only; hidden for heads). **Department** select (required for head role; required for heads creating employee invites; optional with "No department" only for org admin).
  Help line under Department head: "Heads can invite employees to their department, post announcements for it and approve requests to join its groups."
- After creating a **department** (organization view) a dialog offers "Invite its first head" (opens the invite form with role = head and the department preselected) and "Skip".
- Invite list shows role chip and department; filter by role.
- The invite link and QR are rendered exactly as today.
- Validation messages (exact strings in `en.js`): "Choose a department for a department head." / "A head can only invite employees to their own department." / "That department no longer exists."
- Node tests (`server/admin-ui-tests`) for: role radio visibility per role, department required rules, dialog after department creation, strings present.

### 3.8 Keeping head device role and console scope consistent
- Enrolling with a `dept_admin` invite already sets the cert role and department; the server must also add the device id to `Department.adminDeviceIds` (VERIFY whether it does; if not, **do it in the enrolment transaction** and add a test).
- A head device that is revoked or unenrolled is removed from `adminDeviceIds` in the same transaction; if `linked_device_id` points to it, the link is cleared (not the login).
- Moving a member to another department removes them from the previous department's `adminDeviceIds` (org-admin action `PUT /devices/{id}`).
- Department deletion: if the department still has members, existing behaviour stays (VERIFY); otherwise rows in `admin_user_departments` for it are deleted in the same transaction and heads with an empty scope see "No departments assigned. Ask an administrator."
- Last head removed: nothing special; the org admin can always act (state this in the console: "This department has no head. Administrators manage it.").
- A head can be a head of several departments; the console shows a department switcher in the header (default: all).

### 3.9 Phone/desktop app side
- `EnrollmentCredentials.deviceCert.role == dept_admin` means this device is a head. The Settings Hub card shows "Department head · <department>". The Hub tab (A4) shows the announcement composer for the own department.
- The app **never** trusts its own role for authorisation: the hub checks the certificate on each call.
- Nothing else changes in the app for A1.

## 4. Security expectations (requirements)
1. **Default deny** with the route-policy test described above; adding a route without a policy fails CI.
2. **Server-side only**; hiding buttons is cosmetic.
3. Scope re-read per request; no scope in cookies/session tokens.
4. IDOR tests for every resource id route (head of A requests B's group/device/invite/join-request → 404; sends a request body naming B's department → 403 or 400 with the validation message, never partial effects).
5. A head cannot escalate: creating a `dept_admin` invite, creating logins, changing a department assignment, installing delegation/revocations, reading audit/settings/metrics → 403 (test each).
6. CSRF, session, rate limiting behave as for org admins; CSRF test for a head session.
7. Audit entries (existing `AuditLogger`): every head action records `actor = username`, `actorRole = DEPT_HEAD`, `department`; `ADMIN_CREATE`-style entries for head login creation/disable/department changes.
8. No secret in logs (TOTP secret shown once, never logged; `LogHygieneTest`).
9. Org admin lockout protection: the last enabled `ORG_ADMIN` cannot be disabled (VERIFY existing guard; add if missing).
10. A disabled head's sessions die immediately (test).

## 5. Situations the plan does not spell out (expectations)
- Head logs in but has zero departments → console shows a single page with the explanation, no errors in the console log, all data routes return empty lists or 404 (test).
- Head's department is deleted mid-session → next request shows the empty-scope page.
- Two org admins edit a head's departments at once → last write wins; audit shows both; no partial rows (transaction).
- A head creates an invite for 50 employees and the org admin disables the department's group in between → invite still valid, auto-join list drops invalid groups at redemption (VERIFY existing handling).
- Invite for a department head redeemed by someone who is already a member (re-enrolment): follows existing re-enrol rules; if the person becomes head, role changes with a new cert; old cert serial revoked (VERIFY existing behaviour; test).
- Head invites an employee whose phone is already enrolled in another department → existing enrol rules (one device per member, FO-16); show the existing error text.
- Head device exists but no console login → all the app-side powers work; console not available. Console login exists but no device → console works; announcements show the login's display name.
- A head tries to remove their own scope or disable themselves → blocked (403 with sentence).
- Unicode / long names for display name and department: length caps (display name ≤ 60 chars), control characters rejected, stored NFC.
- Concurrency of invite creation by head and admin → independent rows.
- Locale: console strings are in `en.js` only; do not add other locales unless the console already has them.
- Time: `expires` etc. as existing.
- Backward compatibility: an old console JS (cached) talking to the new server: `GET /session` adds fields, nothing removed; old console shows all admin pages to a head but every call is rejected by scope → the server returns clear 403 JSON the old UI already shows as an error banner (test that the body is the standard error DTO).
- Hub **without** this plan (older) and new console: not a case (console ships with the hub).

## 6. Implementation steps (do in order; each step = commit candidate only if the owner asks)

1. **Read** `AdminApi.kt`, `AdminApiDirectory.kt`, `AdminApiDtos.kt`, `AdminAuthService.kt`, `StepUpAuth.kt`, `OrganizationDirectory.kt`, `Migrations.kt`, the invite code and the enrolment code. Fill "Implementation log" entries for every VERIFY.
2. Migration + `ConsoleScope` + `AdminRoutePolicy` map + the policy test (the test first: it should fail for every route until policies are declared).
3. Apply `scopeOf` to each route per the table (departments, devices, groups, join-requests, invites, overview). Write the IDOR/escalation tests as you go.
4. `POST /admins` with `role=DEPT_HEAD`; `GET/PUT /admins/{username}/departments`; `GET /session` fields; disable invalidates sessions.
5. Enrolment/`adminDeviceIds` consistency (§3.8).
6. Console UI: login navigation by role, header department switcher, Security view "Add department head", Organization view "Heads" list + "Invite first head" dialog, invites form changes, strings, CSS; Node tests; update `docs/ui/hub-admin-console.md` (new section "Department heads").
7. Client: show head role in Settings Hub card (read `deviceCert.role`); no other change.
8. Docs: `docs/protocol.md` (admin API changes, session fields), `docs/protocol-routes.txt` (two new routes), `docs/security.md` (head threat model), ADR-136 text, progress/handoff, AGENTS.md §29, test backlog `DEPH-*`.
9. Mutation checks: remove scope check in invites route → test fails; make a head's `role` param honoured → fails; skip session purge on disable → fails; restore each.

## 7. Tests (unit/e2e; ids for the log)
`DEPH-U01` migration; `U02` policy table complete (every route has a policy); `U03` org admin unchanged behaviour (existing suite untouched and green); `U04` head matrix: ~25 routes × {head A, head B, head with no department, disabled head, anonymous} expected statuses; `U05` IDOR → 404; `U06` invite restrictions (role/department/groups); `U07` bulk invites forced to own department; `U08` head cannot create head; `U09` session purge on disable; `U10` scope change applies on next request; `U11` last org admin guard; `U12` head enrol consistency (`adminDeviceIds`); `U13` department delete cleans scope rows; `U14` audit entries; `U15` log hygiene; `U16` old-console compatibility; `U17…` Node UI tests.
Device/manual tests (backlog): `DEPH-01` head logs into the console in a browser, sees only own department; `DEPH-02` head invites an employee → phone enrols into the department; `DEPH-03` org admin invites a head → phone shows "Department head"; `DEPH-04` disable head while logged in; `DEPH-05` two heads, one department; `DEPH-06` first-head dialog after creating a department.

## Beyond the minimum (REQUIRED for Plan A1; the contract's section 3 applies on top)

1. **The route-policy test enumerates the real router**, not a hand-copied list (reflection over the registered routes, or the same source `RouteTableContractTest` uses; VERIFY). It fails when a route has no declared policy. Prove it by mutation: add a dummy route without a policy and watch the test fail, then remove it.
2. **A cross-scope matrix for every route**: a head of department X against an object of department Y, a head of two departments, a head after being moved to another department, a head whose login was disabled mid-session, a head whose scope changed between two requests. Out-of-scope answers **404 with exactly the body of a non-existent id** (no existence oracle; assert byte equality), never 403 for object lookups.
3. **Listing endpoints never leak**: totals, counts, pagination cursors, search suggestions, error text and ordering must not reveal objects outside the scope. One test per listing.
4. **Audit completeness**: every state-changing head action writes an audit entry with the username and scope; no secret or full token appears in it; a head cannot read the audit log (v1).
5. **Login lifecycle for heads**: creation by an org admin, first login with TOTP enrolment, wrong password and wrong code lockouts (reuse the existing limiter), password change, recovery path, disable and delete (sessions end at once), and step-up for dangerous actions kept as for admins. Test each.
6. **Invite roles cannot be forged**: a head calling the API directly with role `dept_admin`, with another department id, with a huge `maxUses`, with a TTL outside the allowed range, with a role string in other case or with whitespace: all refused with the plan's sentence. The role claim in the issued device certificate is checked against the invite after enrolment.
7. **Migration**: a test starting from an existing admin database (current admin schema with admins that exist today); existing admins stay `ORG_ADMIN`; the new tables and the `role` column are used; downgrade behaviour documented.
8. **Console UI completeness**: keyboard-only use, visible focus, empty states, an error state for every form, long names, 200 % zoom, right-to-left, the navigation hides what a head cannot use while the server stays the authority (test with the sections hidden *and* the API called directly). A head never sees an org-admin-only button even for a moment (no flash of admin UI).
9. **End-to-end test** using the existing e2e harness: org admin creates a department and its first head invite; the head enrols a phone; the head logs into the console; creates an Employee invite; an employee phone enrols into that department only.
10. **Documentation for administrators**: a short "Department heads" section in `docs/ui/hub-admin-console.md` (what a head can and cannot do, how to add, disable, replace a head), and a threat row in `docs/security.md` (a compromised head account: reach, residual risk).

## 8. Definition of done
- [ ] Quality contract satisfied for every phase: explain-back, evidence table, 12 categories, >= 10 adversarial findings, >= 5 extra situations, phase report (`docs/plans/HUB-QUALITY-CONTRACT.md`); the verifier prompt (`docs/plans/HUB-PLANS-PROMPTS.md`) was run by the owner or is pending.

All unit/e2e tests green (`:server:test`, node console tests, `:core:hub-protocol` if DTOs changed); mutation checks done; docs and logs updated; backlog filled; no existing test weakened; nothing claimed device-verified.

## 9. Implementation log
_(empty; record answers to every VERIFY here)_
