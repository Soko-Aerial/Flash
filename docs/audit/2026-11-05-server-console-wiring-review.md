# Server console wiring review — 2026-11-05

**Status:** REVIEW COMPLETE — findings OPEN, **no code changed**.  
**Target:** merged `dev` `e1bc495b` (merge `ec6c3724`, port `adb26723`), main worktree
`C:\Users\KaliOxygen\Downloads\Flash`.  
**Scope:** the **hub admin console** UI → admin API → server route/DTO wiring, including the
real sign-in/step-up/session/permission contract. Unrelated relay/mailbox/file protocol
internals were not re-audited except where a console feature depends on them.

## Method

Two independent read-only `general` subagents reviewed separate halves of the console:

- **Agent A — frame, auth, Overview, Join requests, shared UI.** Traced the shell/nav/route
  table, boot/setup/login/logout/session/step-up, `/overview`, `/metrics`, `/devices`,
  `/invites`, `/audit`, `#/requests`, tabs/mobile nav, CSP-safe DOM and i18n.
- **Agent B — every feature screen.** Traced Devices, Invites, Organization, Announcements,
  Audit, Security and Settings controls to their API calls and server routes, including
  method/path/body/query, CSRF, role gates, step-up targets, dialogs, print/export, and
  destructive confirmations.

Both agents worked read-only, did not run Gradle/browser/device tests, and did not modify
files. The parent spot-checked the P1s and the main Overview/shell regressions below. The
merged tree had already passed console Node tests **83/83**, `AdminConsoleWebTest`
**15/15** and `AdminConsoleApiTest` **10/10**. The agents re-ran Node tests: **83/83**.

## Verdict

**No P0. The redesign did not unwire the feature APIs.** Every audited control reaches a
real server route with the correct method, path, body, CSRF and step-up wrapper. There is
no remaining sample-data mutation in the shipped console, no direct `fetch`, and the
server-side scope gates are sound. The fictional “Meridian Works”/“Production” world exists
only in `design/server-console/`.

The console is **not complete** as a scoped product:

1. **Department-head scoping exists on the server but not in the client.** A head sees
   org-only pages and actions that will 403 — sometimes after a step-up challenge.
2. **Multi-department heads can get empty/truncated Devices/Invites lists** because the
   server applies `LIMIT/OFFSET` before the scope filter.
3. **The root-signed revocation-list installer has no UI**, although
   `POST /revocations/install` and its step-up action exist.
4. **Department-head administrator accounts cannot be created/edit-departments from the
   console**, although the server supports `role=DEPT_HEAD`, `departments`, `displayName`,
   `linkedDeviceId` and `PUT /admins/{username}/departments`.

There are also several **regressions in the new Overview/shell** and a set of pre-existing
data/UX defects. They are listed below in fix order.

---

## P1 — product/feature gaps

### P1-1 — No client-side department-head scope
- **Evidence:** fixed nav `js/ui/shell.js:11-28`, rendered `:67-74,161-168`; no route guard
  `js/main.js:27-37,248-251`; org-only actions still rendered in
  `js/views/devices.js:481-496`, `js/views/organization.js:99,128-137,274,366`,
  `js/views/invites.js:192`, `js/views/security.js:24`, `js/views/settings.js:24-43`,
  `js/views/audit.js:26`.
- **Server is correct:** `requireOrg` on the routes (`AdminApi.kt:483`,
  `AdminApiDirectory.kt:70-110`), `ConsoleScope` policy, and
  `DepartmentHeadScopeTest.kt:200-221` proves every ORG route returns 403 to a head.
- **Impact:** a head sees Audit, Settings, the org tabs of Security, device revoke/move,
  department editing and admin-role invite options, each failing. Device revoke can run the
  step-up flow *before* the 403. This is a UX/feature failure, not privilege escalation.
- **Fix:** derive an `org`/`head(departments)` scope from the session; filter nav, tabs and
  actions; add a route guard with a clear “not available for your role” page; add browser
  tests that sign in as `DEPT_HEAD` and assert the org-only controls are absent. If heads
  are not intended to use the console, reject their login with a clear sentence instead.

### P1-2 — Multi-department head filtering happens after `LIMIT/OFFSET`
- **Evidence:** `AdminApi.kt:423-426` (devices) and `:525-528` (invites) call the store
  page first and filter by `scope` afterwards. `IdentityStore.kt:306-308` applies
  `LIMIT/OFFSET` in SQL. `AnnouncementAdminRoutes.kt:134-159` has the same shape.
- **Impact:** a head on two departments in a large org can receive an empty first page,
  and the console treats it as “No devices enrolled” / no invites; later own rows are
  never fetched. `devicepicker.js:18-23` can also report “No devices”.
- **Fix:** push `departments: Set<String>` into the store queries (`department IN (...)`),
  apply paging to the already-scoped set (or merge per-department pages), and test a head
  on two departments with >500 other-department devices.

### P1-3 — Revocation-list installation has no console control
- **Evidence:** server route `POST /admin/api/v1/revocations/install`
  (`AdminApi.kt:675-687`, `StepUpActions.REVOCATION_INSTALL`), but
  `js/views/security.js:498-552` only creates a serial (`POST /revocations/serial`).
  `rg revocations/install` finds nothing under `admin-ui`.
- **Impact:** a delegation cannot be revoked from the console. When `canSign` is false,
  the UI says changes are waiting but offers no way to install the root-signed list.
  `HUBR-03` cannot be run as written.
- **Fix:** add the paste/file installer in Security → Revocations, call
  `guarded('revocation.install', 'version:'+list.version, id => api.post('/revocations/install', list, {stepUp:id}))`,
  refresh status, and add the browser check.

### P1-4 — Department-head administrator provisioning is missing
- **Evidence:** create dialog posts only `{username,password}` (`security.js:412-441`);
  the server supports `role`, `departments`, `displayName`, `linkedDeviceId`
  (`AdminApiDtos.kt:286-294`, `AdminApiDirectory.kt:276-309`). No UI calls
  `GET/PUT /admins/{username}/departments` (`AdminApiDirectory.kt:313-344`). The admin
  table does not show role/departments (`security.js:343-357`).
- **Impact:** a head cannot be provisioned or re-scoped from the product; `DEPH-01`
  cannot be performed without API calls.
- **Fix:** role select (`ORG_ADMIN`/`DEPT_HEAD`), department multi-select, display name,
  optional linked device; show role/departments in the table; add edit-departments.

---

## P2 — port regressions and high-value wiring defects

These are in the new shell/Overview/auth work (or became visible because of it) unless
marked **pre-existing**. The first four are the ones to fix immediately.

### R1 — Overview no longer verifies the audit chain
- **Evidence:** `js/views/overview.js:271` renders “Check” with
  `overview.new.auditSub` = “Not checked in this visit” and links to `#/audit`; the new
  Overview never calls `/audit/verify`. `OverviewDto.audit` is fetched but unused. The
  pre-merge Overview did verify and show intact/broken
  (`git show ec6c3724^1:server/src/main/resources/admin-ui/js/views/overview.js`).
- **Impact:** a broken audit chain is not surfaced on the landing page; the decision queue
  row cannot answer its own question. A silent feature loss.
- **Fix:** call `/audit/verify` on load (ORG) or share the Audit page result; render
  “Intact (N entries)” / “Broken at N”; hide the row or explain for a head.

### R2 — Overview “Try again” is dead for `/overview` and `/metrics`
- **Evidence:** memoised rejected promises in `js/views/overview.js:110-111,156-170`;
  `loadInto` retry re-invokes the same closure (`js/ui/boundary.js:28`). Only the 15 s
  `reload()` at `:310-313` clears them.
- **Impact:** after a failed first load, the health/relay/waiting retry re-renders the same
  rejection; a head’s 403 can never recover.
- **Fix:** clear the promise in `.catch` or make retry call `reload()`; test a failed first
  load followed by a successful retry.

### R3 — Overview “Latest activity” labels are wrong
- **Evidence:** mapping `js/views/overview.js:86-94` contains `join.decline`,
  `admin.session.start` and `delegation.verify`; the server logs `join.reject`
  (`AdminApiDirectory.kt:244`), `admin.login` (`AdminApi.kt:261`) and
  `delegation.install` (`AdminApi.kt:733`). Other actions render raw.
- **Impact:** common entries show developer tokens; three mapping entries can never match.
- **Fix:** map the real action names, add a humanised fallback, test against a canonical
  action list.

### R4 — Department-head role key is missing
- **Evidence:** `js/ui/shell.js:254-255` builds `t('role.short.'+roleKey)`; the session role
  is `DEPT_HEAD` (`AdminAuthService.kt:46`), but `strings/en.js:56-62` has only `member`,
  `dept_admin`, `admin`, `org_admin`. `strings.test.mjs` only scans literal `t('...')`.
- **Impact:** every head sees `role.short.dept_head` as the role.
- **Fix:** add `role.dept_head`/`role.short.dept_head` and a test enumerating role values.

### R5 — Display name reverts to username on session refresh
- **Evidence:** initial `shell.setUser(session.displayName || session.user, ...)` at
  `js/main.js:176`, but the subscription at `:178` uses `session.user` only. `setSession`
  emits on visibility/CSRF refresh (`state.js:19-33`, `main.js:322-329`).
- **Impact:** the sidebar name/avatar reverts after returning to the tab.
- **Fix:** use `displayName || user` in the subscription.

### R6 — Mobile/tablet loses identity and session countdown
- **Evidence:** the sidebar is hidden below 1100 px (`redesign.css:449-451`); the user and
  session rows live only in the sidebar (`shell.js:106-123`); the More sheet has help and
  sign-out but no user/session (`:180-198`).
- **Impact:** phone/tablet admins cannot see who is signed in or how long is left.
- **Fix:** add the user/session to the mobile bar or the More sheet.

### R7 — Ledger can falsely say “No devices online” / “No invites waiting”
- **Evidence:** `js/views/overview.js:278,286` filters only the newest
  `/devices?limit=24` and `/invites?limit=100` (`:305-306`), then prints absolute empty
  copy. Both server lists are newest-first.
- **Impact:** on a hub with more rows, an older online device or pending invite is missed.
- **Fix:** request `/devices?status=ACTIVE&limit=...` and `/invites?state=PENDING&limit=3`,
  or label the result as “none in the newest N”.

### R8 — Help/shortcut list is incomplete
- **Evidence:** `shortcuts.js:84-101` omits `g r` (requests) and `g n` (announcements)
  although `shell.js:11-21` routes them; the README/shortcut copy lists only `o d i r a s t`.
  `Ctrl+Enter` is advertised but only implemented in login/invites.
- **Fix:** derive help from `NAV`; implement the advertised shortcut or qualify the copy.

### R9 — “60-minute” strip is actually 15 minutes
- **Evidence:** `overview.js:11-12` uses 60 samples × 15 s; the design record/README still
  say 60 minutes. The UI note correctly says 15 s.
- **Fix:** aggregate four samples per bar for a 60-minute strip, or correct the design
  record/README.

### R10 — Join approval is one click; the approved design promised a gate
- **Evidence:** `organization.js:531-542` posts approve directly with `withBusy`; no
  dialog/`guarded`/step-up. Server route has no step-up
  (`AdminApiDirectory.kt:217-229`). Design record `hub-admin-console-redesign.md:131`,
  README `:78` and `CONSOLE-D06` claim a phone-code confirmation gate. The request DTO has
  no paired/status context.
- **Impact:** a misplaced click adds a device to a group; operators may believe a gate is
  present. Needs an owner decision: add `JOIN_APPROVE` step-up + confirmation, or correct
  the design/backlog and keep one click. At minimum add a confirmation dialog and show
  device status/department/request age.

### R11 — Announcement list reports page length as total and cannot load older
- **Evidence:** `announcements.js:204-214` uses `items.length` and no `before`; the server
  returns `total`/`nextBefore` (`AnnouncementAdminRoutes.kt:117-159`). Result line is
  hardcoded `' announcement(s)'`.
- **Impact:** >50 announcements are truncated and search only covers the page.
- **Fix:** use `res.total`, implement “Load older” with `nextBefore`, add an i18n plural.

### R12 — Invite group selection survives a department change
- **Evidence:** `invites.js:218` creates a `Map`; `drawGroups` (`:219-241`) re-mounts the
  fieldset but never clears it; submit iterates all entries (`:255-258`).
- **Impact:** an org admin can submit an invite with an old department’s group still in
  `allowedGroups`/`autoJoinGroups`; the server accepts it for an org admin. For a head it
  fails after step-up.
- **Fix:** clear the map/checkboxes in `drawGroups`; validate all groups’ department
  server-side regardless of role.

### R13 — Announcement delete/conflict body is empty (API misuse)
- **Evidence:** `announcements.js:273-277,479-482` pass `text:`; `confirmDialog`
  destructures `message` (`ui/dialog.js:88,92`).
- **Fix:** pass `message:` or support `text` as an alias; test the body is non-empty.

### R14 — Devices/Invites show department IDs, not names
- **Evidence:** `devices.js:92,503`, `invites.js:91,363` print `d.department`/`inv.department`
  directly; the DTO carries the registry ID. Organization/Announcements already map IDs.
- **Fix:** load `/departments` once and map id→name for cells, filters, drawer and CSV.

### R15 — Settings → Health caps online count at 500
- **Evidence:** `AdminApi.kt:881` uses `listDevices(ACTIVE, limit=500).count{...}` while
  Overview uses `activeDeviceIds()`. The store caps at 500.
- **Impact:** Health undercounts on a >500-device hub; “fake count” risk.
- **Fix:** use the same uncapped `activeDeviceIds()` implementation; add a >500 test.

### R16 — Sign-out/expiry can leave the stale console and run twice
- **Evidence:** `main.js:201-209` clears the session, then `await loadSetupStatus()` (which
  throws on failure) before `showLogin`; `sessionwatch.js:46-50` emits `expired` on every
  tick after zero because `return` leaves the interval running.
- **Impact:** if setup/status fails during logout/expiry, the console UI remains mounted
  with no sign-in form; concurrent logout can double-mount login.
- **Fix:** set `mode='login'`/a `signingOut` guard before awaiting; stop the watcher;
  always show login in `finally`; make expiry emit once.

### R17 — Cold deep links are lost after sign-in
- **Evidence:** `main.js:311` boots a signed-out visitor with `showLogin('')`; `returnTo`
  is only set by the later `hashchange` (`:229-232`) or an existing session (`:305`).
- **Impact:** a bookmarked `#/requests`, `#/audit` or `#/devices/{id}` lands on Overview
  after login.
- **Fix:** set `returnTo = location.hash` before `showLogin` on cold boot (unless
  login/setup).

### R18 — Print cards can lose the hub name
- **Evidence:** `invites.js:43,287,368` fall back to “Flash Hub” if `ctx.hubName()` is empty
  before `/overview` resolves.
- **Fix:** fetch the hub name for print or wait for it.

---

## P3 — smaller defects

- **P3-1** `shortcuts.js` help omits `g r`/`g n`; `Ctrl+Enter` copy is broader than the
  implementation.
- **P3-2** `main.js:314` sets `documentElement.lang` from `navigator.language` while only
  English strings exist; `overview.js:225` hardcodes “Live”.
- **P3-3** No `h1` on not-found/error pages; unknown route title becomes `nav.nope`.
- **P3-4** Overview lede stays “Loading…” on failure; requests subtitle stays
  “0 requests waiting” on error.
- **P3-5** Requests page hides the tab bar but leaves `role="tabpanel"`/`aria-controls`;
  approving rerenders and loses focus.
- **P3-6** `organization.js` loads `/departments`, `/groups` and all device names even on
  `#/requests`; `refreshRequests` fails all-or-nothing if `/groups` fails.
- **P3-7** Device-name cache survives sign-out (`devicepicker.js:11-17`); can show the
  previous admin’s names.
- **P3-8** Unrevoke has no confirmation (`devices.js:323-330`) though it is a security
  action with step-up.
- **P3-9** About shows a hardcoded console `1.0.0` and no server version/route
  (`settings.js:15,250-274`).
- **P3-10** Device drawer can copy the fingerprint but not the device ID/public key
  (`devices.js:435-438,516-522`).
- **P3-11** File-limit form can rewrite an unchanged non-MB-aligned value
  (`filelimits.js:22-25,73-75`).
- **P3-12** Login/setup theme icon never updates after `cycleTheme()` (`login.js:31`).
- **P3-13** Dead/unreachable state: `main.js:249` duplicate redirect;
  `organization.js:584` unreachable `tab=requests`; `tabBar.setBadge('requests')` targets
  a removed tab; `devices.js:527` no-op listener.
- **P3-14** Overview device rows link to `#/devices`, not `#/devices/{id}` (`overview.js:60`).

---

## What is wired and verified

- **Every API call in scope matches a server route**: setup/status/begin/complete, login,
  logout, session, overview, metrics, devices + revoke/unrevoke/update, invites + bulk +
  revoke, departments, groups + members, join requests + approve/reject, announcements +
  preview/pin/unpin, audit + head/verify/export, admins + password/totp-reset/disable/
  enable/departments, sessions + revoke, delegation + reload, revocations + serial,
  settings, files/stats, step-up challenge/verify/status, admins/me/console-key,
  approvals. No wrong verb/path/body found.
- **CSRF / same-origin** is added by `createApi` for every non-GET; no direct fetch.
- **Step-up action/target strings** match `StepUpAuth` constants, including
  `device.revoke`, `device.unrevoke`, `delegation.install/reload`, `admin.create`,
  `reset:<user>`, `totp:<user>`, `admin.disable`, `settings.change`, `recovery.regenerate`,
  `approver.change`; unknown actions are rejected server-side.
- **Server role gates** are real; `DepartmentHeadScopeTest` covers every ORG route. No
  client omission is a privilege escalation.
- **CSP-safe DOM**: no `innerHTML`/`insertAdjacentHTML`/`eval`/inline handlers/direct
  fetch; QR SVG is rebuilt from viewBox/path only.
- **i18n**: all statically referenced keys exist; the only dynamic gap is
  `role.short.dept_head`. Node `strings.test.mjs` passes.
- **Print/export**: print root and rules exist; invite QR/card steps; client CSV uses
  formula neutralisation; audit export is server-generated CSV.
- **Error/loading/empty**: most views use `loadInto`/skeletons/error boundaries and tokens
  prevent stale responses; exceptions are Announcements and some Security sub-cards.
- **No sample-data mutations**: every state change calls the API. “Meridian Works”/
  “Production” is confined to `design/server-console/`.
- **Real sign-in/sign-out/setup/step-up**; the review harness exercised them earlier, and
  the merged console tests pass.

## Feature matrix (condensed)

| Area | Status | Notes |
|---|---|---|
| Boot / setup / sign-in / lockout / sign-out / session | wired | cold-deep-link and logout/expiry edge cases (R16/R17) |
| Step-up challenge/verify/poll | wired | join approval is the only missing gate (R10) |
| Shell nav, hub name, theme, help, badges | wired | no head filtering (P1-1); mobile identity (R6); help gaps (R8) |
| Overview health / relay / queue / ledger | partial | regressions R1/R2/R3/R7; head scope P1-1 |
| Join requests list/approve/reject | wired | one-click/design gate R10 |
| Devices list/drawer/rename/move/revoke/bulk/export | wired | head scope P1-1; multi-dept P1-2; department IDs R14 |
| Invites create/bulk/print/revoke | wired | head role options; stale groups R12; department IDs R14 |
| Organization departments/groups/members | wired | head sees org-only actions; server scope P1-1 |
| Announcements list/create/edit/pin/delete/preview | wired | paging/total R11; delete body R13; duplicate submit P2 |
| Audit filters/live/paging/verify/export | wired | ORG only; Overview chain R1 |
| Security account/admins/sessions/delegation/revocations/trust | partial | head scope P1-1; head provisioning P1-4; install missing P1-3 |
| Settings configuration/health/about | wired | head scope P1-1; 500 cap R15; hardcoded version P3-9 |

## Recommended order

1. **Fix the console regressions first** — R1–R9 and the small docs/copy corrections. These
   are in the new Overview/shell and are low-risk, testable changes.
2. **Correct or implement the join-approval gate** (R10) — product decision; either add
   server step-up + confirmation or amend the design record/backlog.
3. **Department-head scope + provisioning + multi-department queries** (P1-1/P1-2/P1-4) —
   one coherent feature branch with server tests and a head browser test.
4. **Revocation-list installer** (P1-3) — a contained Security feature.
5. **P2 data/UX fixes** — announcements paging, invite groups, department names, settings
   count, sign-out/return-to, etc.

No code was changed by this review. The two full agent reports remain in the review
session; this document keeps their actionable evidence and the consolidated priority.
