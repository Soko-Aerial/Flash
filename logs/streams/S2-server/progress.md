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
