# S2-server stream log — Plan A2 (directory privacy, option C)

## 2026-10-14 — A2 explain-back (quality contract §2, no code yet)

### What the phase must achieve and why
Today the hub shows each device only its own department, so a company-wide
People list is impossible and cross-department senders risk UNKNOWN rejection.
Option C (owner D2, refined by D9) makes names, departments and online dots
org-visible by default, while an admin can mark a department hidden: outsiders
then see neither the department nor its people, counts, groups or keys. Hidden
is listing-only (known ids can still message; LAN device names still visible;
admins see everything). B1 already provides the epoch (`epochId`,
`currentVersion`, `resetEpoch`), the sync route and a partial
`DirectoryVisibility` wrapper, so A2 extends them instead of stubbing.

### Files I will touch (new code in new files; hooks ≤ 3 lines in big files)
- `server/.../directory/OrganizationDirectory.kt` (extend `Department`,
  `DirectoryVisibility`, migration 3, epoch bump, people search/lookup helpers).
- `server/.../directory/DirectoryPrivacy.kt` (NEW: `DIRECTORY_KEYS_ORG_WIDE`,
  rate-limit helpers, cursor codec, search folding/escaping).
- `server/.../directory/DirectoryRoutes.kt` (new `people`, `people/{id}`;
  changed `departments`, `members`, `groups`; all through `DirectoryVisibility`).
- `core/hub-protocol/.../rest/DirectoryPrivacyDtos.kt` (NEW: `PersonDto`,
  `PeoplePageDto`).
- `core/hub-protocol/.../rest/RestDtos.kt` (hook: `DepartmentDto.hidden=false`).
- `core/hub-protocol/.../HubContract.kt` (hook: 2 path constants).
- `server/.../admin/AdminApiDirectory.kt` + `AdminApiDtos.kt` (accept `hidden`,
  audit `directory.department.hidden`).
- `server/src/main/resources/admin-ui/js/views/organization.js` + `js/strings/en.js`
  (hidden toggle, exact help text, confirmation dialog).
- `core/enterprise/.../DirectoryPrivacyClient.kt` (NEW: `fetchPeople`,
  `lookupPerson`, standing resolver with old-route fallback).
- `server/src/test/.../directory/DirectoryPrivacyTest.kt` (NEW: PRIV-U01..U12).
- `docs/protocol.md` (new subsection), `docs/protocol-routes.txt` (regen).

### Three most similar existing classes read per new class
- `DirectoryVisibility` (existing object): reuse `canSeeDepartment`/`canSeeMember`
  shape; differ by adding hidden + keys/presence/group predicates in one place.
- `DirectoryRoutes.kt` (`/members`, `/departments`, `/groups`, `/sync`): reuse
  `requirePrincipal`, `respondError` fixed sentences, `withSealing`, cursor
  Base64url pattern from `getSyncChanges`; differ by org-wide scope + miss
  rate limiting for lookup.
- `OrgMemberStatement`/`DepartmentSyncDto(hidden=false)` + `HubJson(rest,
  ignoreUnknownKeys=true)`: reuse defaulted-field forward compat; verified
  `HubJson.kt:36-42` sets `ignoreUnknownKeys=true`, so old clients ignore
  `hidden`.
- `MigratorTest`/`OrganizationDirectorySyncTest`: reuse migration-test pattern
  (build DB at schema 2, migrate, assert); differ by asserting `hidden`
  default 0 and epoch bump.
- `EnterpriseDirectoryClient`/`HubPeerDirectory`: reuse bearer+refresh,
  `HubUrlPolicy`, `DirectorySyncPageResult.HubUnsupported` fallback pattern.

### Five likeliest failures
1. Hidden leaks via counts/cursors/search ranking or group member lists.
2. Old client breaks on new `DepartmentDto.hidden` or new departments shown.
3. Epoch not bumped on hidden toggle → outsiders keep stale rows.
4. Lookup oracle: distinguishing "hidden exists" from "not exists".
5. Migration on a DB made by schema 2 fails or drops data.

### Tests first
`PRIV-U01` visibility matrix, `U02` migration from schema-2 DB, `U03`
paging/search/escape, `U04` leak matrix, `U05` lookup+limits, `U06` old
`/members` unchanged, `U07` hidden-department groups, `U11` epoch bump,
`U12` log hygiene, `U13` route table. Mutation: `canListMember=true`,
drop hidden filter, leak counts.

## B1 gate verdict (A2 step 5 prerequisite)
B1 IS built at `b717ea50`: directory migration 2 (`directory_state`,
`epochId`, `currentVersion`, tombstones, statements), `GET
/api/v1/directory/sync` (`DirectoryRoutes.kt:73`), `getSyncChanges` with
epoch/full-resync logic, `DirectoryVisibility` object with
`canSeeDepartment`/`canSeeMember` used by sync+routes, `DepartmentSyncDto.hidden`
already defaulted. Missing pieces (hidden flag, people routes, keys flag) are
exactly what A2 builds. PROCEED; no stub needed.

---

## 2026-10-14 — Plan A2 report (quality contract §7)

### Phase A2 report — 2026-10-14
Done: directory privacy option C on the hub (server + console + hub-protocol
DTOs + enterprise client), evidence table below.
Done but only unit-tested (device checks owed as PRIV-01..PRIV-05): everything
below; no device verification claimed.
Not done, and why:
- Presence switch per department (plan §3.5): answered by the owner 2026-10-14
  (D11): NOT built in v1. Nothing to do.
- `docs/security.md` threat rows + `docs/testing/PROBES.md` entries: written as
  stream fragments `security-additions.md` / `probes-additions.md` (parallel-mode
  rule); the integrator folds them into the canonical files at merge.
- `AGENTS.md` §29 line + canonical `logs/*` + `TEST-BACKLOG.md`: same fragment
  rule (`handoff.md`, `backlog-additions.md` here).
Surprises (plan got wrong or drifted):
1. B1's `DirectoryVisibility` had no hidden/group/keys/presence predicates and
   `Department` had no `hidden` — extended in place (plan assumed the wrapper
   was fuller than it was).
2. Three existing tests encoded the pre-A2 own-scope rule
   (`DirectoryRoutesTest:173`, `OrganizationDirectorySyncTest:401`,
   `E2eDirectoryContractTest:124-129`) and contradicted plan §3.3.4/§3.3.6;
   updated to the plan-mandated superset with A2 citations (not a weakening:
   the old rule is superseded by owner D2/D9).
3. `/members?departmentId=<missing>` used to answer 403; it now answers 404
   with the same body as a hidden department (oracle removal). Old clients that
   distinguished 403/404 on typos see 404 for both.
4. `DepartmentDto` counts stay non-null Ints (plan said null): null would break
   old clients' non-null fields; hidden outsiders get `0` instead (wire keeps
   working, nothing real leaks since hidden rows are filtered first).
5. `tools/flash-gradle.ps1` cannot take bare `:module:task` args (first
   positional binds `-Runner`, ERROR-430); workaround: `-GradleArgs '...'` with
   direct `&` invocation. Integrator note in errors fragment.
Decisions I took that the plan did not make (recorded here + decisions fragment):
- `DIRECTORY_KEYS_ORG_WIDE=true` ships as a `const` on `DirectoryVisibility`
  (D9); the `false` path is implemented (`keysVisible` returns false for
  cross-department) and covered by U01/U06-style assertions.
- One-char `q` is 400 (plan Beyond-4 min length 2); the Hub tab (A4) must
  debounce to ≥2 chars.
- Unknown `departmentId` in `/people` filter answers an empty 200 page
  (identical to hidden), not 404 — listing filters never oracle.
- `lookupMissLimiters` is a static per-device map (no eviction; bounded by
  enrolled devices).
Adversarial review findings (15) and the status of each:
1. `OrganizationDirectory.kt:119` `canSeeDepartment` was a convoluted
   disjunction — FIXED by rewrite (insiders first, then org-wide non-hidden).
2. `OrganizationDirectory.kt` `load()` probes for the `hidden` column with a
   throwing SELECT on schema-2 DBs — ACCEPTABLE: caught by `runCatching`, and
   U02 proves load-after-migrate; a fresh DB always has the column post-migrate.
3. `searchPeople` is in-memory O(n log n); the new SQL index
   `idx_dir_members_dept_name` serves no current query — ACCEPTABLE, documented:
   consistent with the existing in-memory directory; 5,000-row synthetic test
   passes (EXP-030).
4. `DirectoryPrivacy.kt` cursor uses default-locale `lowercase()` — ACCEPTABLE:
   encode and filter use the same fold; a mid-pagination rename may skip/dup a
   row (opaque cursor, best effort, converges on next sync).
5. Multi-page sync across an epoch bump mixes epochs — ACCEPTABLE: next sync
   sends the new epoch and fully resyncs (tested U11).
6. `PersonDto` exposes displayName for hidden members by known id — INTENDED
   (plan §security-3: id known = allowed); ids are 128-bit random.
7. Console toggle has no role gate — ACCEPTABLE: console is org-admin-only
   until A1 scopes it; A1 adds the policy table.
8. `toPrivacyDto` zeroing is unreachable (filter drops hidden first) — KEPT as
   defense in depth; mutation proved the filter (not the zeroing) is the
   control.
9. `/members` unknown-dept 403→404 — INTENDED oracle removal (surprise 3).
10. `HubPeerDirectory` lookup hits accumulate until the next listing refresh
    (TTL 10 min) — ACCEPTABLE: bounded by lookups, cleared on refresh.
11. Group-member path keeps `withSealing` (not `withPrivacy`) — ACCEPTABLE:
    only members/admins reach it (`canSeeMembers`), i.e. insiders.
12. `q` min-length-2 400 vs Hub tab as-you-type — A4 must debounce; recorded.
13. `departmentId` filter for a caller's OWN hidden? A member always sees their
    own department (insider rule) — tested U01.
14. Admin device (departmentless) sees non-hidden only — tested U01 addition.
15. Tests would still pass if the console toggle were deleted (JS untested by
    Kotlin) — COVERED by `privacy.test.mjs` (toggle + exact strings).
Extra situations handled beyond the plan (7):
a. Unknown `departmentId` filter → empty 200 identical to hidden (U04).
b. Departmentless admin device visibility (U01).
c. Lookup miss bursts ≥10/min → 429 + audit `directory.lookup.missburst` (U05).
d. 1,000-cycle cursor/query fuzz, never throws (U14).
e. 5,000 members / 50 departments paging + search (U05b, EXP-030 synthetic).
f. Migration idempotence: second migrate changes nothing (U02).
g. `toDto` keeps shipping `hidden` so old admin console JSON stays shaped
    (AdminDepartmentDto gains `hidden=false` default).
Tests: 12 added in `DirectoryPrivacyTest` (12 run, 0 failures, executed);
4 in `DirectoryPrivacyClientTest` (run, 0 failures, executed);
2 in `privacy.test.mjs` (run, 0 failures); full `:server:test` green
(BUILD SUCCESSFUL, 20 executed); `:core:hub-protocol:jvmTest` +
`:core:enterprise:jvmTest` green (150 tests, 0 failures, executed);
node suite 62/62.
Mutation checks:
- M1 `canListMember → true`: U01 (`DirectoryPrivacyTest.kt:82`) + U04 FAIL;
  restored, green.
- M2a `toPrivacyDto → toDto` (leak counts): NO failure — proved the zeroing is
  unreachable (filter is the control); documented, not a gap.
- M2b `canSeeGroup hidden → true`: U07 (`:191`) FAILS; restored, green.
Open risks the owner should know:
- No device verification (PRIV-01..05 owed).
- `flash-gradle.ps1` bare-args bug affects all streams (ERROR-430, workaround).
- A4 must debounce people search to ≥2 chars and show the hub-too-old sentence.
Self-score against section 3 (12 lines):
1. Failure paths — covered (400/404/429 shapes, fallback sentence, U03/U05).
2. Hostile input — covered (U14 fuzz 1,000, escape, bounds, bidi/RTL names
   via existing requireName; announcement text N/A).
3. Concurrency — covered (lock.write serializes toggles; single-flight not
   needed server-side; U11 epoch race converges).
4. Lifecycle — covered (migration from schema-2 DB, idempotent remigrate;
   process death safe: write-DB-first then index).
5. Scale — covered (5,000/50 synthetic U05b, O(n log n) named, EXP-030).
6. Security — covered (positive+negative per predicate, 2.5 mutation checks,
   fail closed via exceptions→403/404, no secrets in logs/audit).
7. Compatibility — covered (ignoreUnknownKeys verified, old routes keep
   shapes, hub-too-old sentence + fallback).
8. Both hosts — N/A with reason: A2 has no host code (client is shared
   `commonMain`; Android/desktop call the same API in A4).
9. Accessibility/honesty — covered (console help = plan's exact text, confirm
   dialog, "Applies at next update"; no dead buttons added).
10. Observability — covered (audit `directory.people.list`,
    `directory.lookup.hit/missburst`, `directory.department.hidden`; no names).
11. Documentation — covered (protocol.md subsection, routes regen + contract
    test, fragments for security/probes/backlog/decisions/handoff).
12. Admin/support — covered (help text, confirm dialog, fixed error sentences,
    troubleshooting via audit actions).

### Evidence table
| Step | Files changed (path:line) | Tests (full names) | Mutation (what I broke, which test failed) | Compile / test commands and result |
|---|---|---|---|---|
| A2.2 model+migration | `server/.../directory/OrganizationDirectory.kt:61` (`hidden`), `:1317` (migration 3) | `DirectoryPrivacyTest.migration3AddsHiddenWithDefaultFalseOnASchema2Database` | — | `& .\tools\flash-gradle.ps1 -WaitMinutes 45 -GradleArgs ':server:test','--tests','com.transfer.flash.server.directory.DirectoryPrivacyTest','--rerun-tasks'` → BUILD SUCCESSFUL, 12 tests 0 failures, 20 executed |
| A2.3 visibility | `OrganizationDirectory.kt:119` (`DirectoryVisibility`), `DirectoryPrivacy.kt:1` (NEW) | `...outsidersCannotSeeHiddenDepartmentsButSeeNonHiddenOnes`, `...everyDirectoryReadRouteConsultsDirectoryVisibility` | M1 `canListMember→true` fails U01:82 + U04; restored green | same command, green |
| A2.4 routes | `DirectoryRoutes.kt` (people:158, lookup:215, members:129, departments:158) `HubContract.kt:84`, `RestDtos.kt:128`, `DirectoryPrivacyDtos.kt:1` (NEW) | U03/U04/U05/U06/U07/U11/U12/U13 (`RouteTableContractTest`), `privacy.test.mjs` 2/2 | M2b `canSeeGroup hidden→true` fails U07:191; restored green | full `:server:test --rerun-tasks` → BUILD SUCCESSFUL, 20 executed; `RouteTableContractTest` green after regen |
| A2.5 epoch hook | `OrganizationDirectory.kt` (`setDepartmentHidden`, `resetEpoch` on change) | `...togglingHiddenBumpsTheEpoch` | — | same full run, green |
| A2.6 client | `DirectoryPrivacyClient.kt:1` (NEW), `EnterpriseDirectoryClient.kt:302`, `HubPeerDirectory.kt:48`, `EnterpriseModels.kt:39` | `DirectoryPrivacyClientTest` 4/4 incl. U09 `hubPeerDirectoryResolvesCrossDepartmentSendersByKnownId` | — | `:core:enterprise:jvmTest --rerun-tasks` → BUILD SUCCESSFUL, 150 tests 0 failures, 16 executed |
| A2.7 console | `organization.js` (toggle+confirm), `en.js` (+6 strings) | `privacy.test.mjs` 2/2; suite 62/62 | — | `node --no-warnings --test server/admin-ui-tests/*.test.mjs` → 62 pass 0 fail |
| A2.8 keys flag | `DirectoryVisibility.DIRECTORY_KEYS_ORG_WIDE=true`, `withPrivacy` | U01 keys/presence assertions, U06 | — | covered by runs above |
| A2.9 docs | `docs/protocol.md` (privacy v1), `docs/protocol-routes.txt` (2 new routes) | `RouteTableContractTest.theCheckedInRouteListEqualsTheRoutesTheServerAnswers` | — | green in full run |

### Gate commands and last output lines
- `& .\tools\flash-gradle.ps1 -WaitMinutes 45 -GradleArgs ':server:test','--rerun-tasks'` → `BUILD SUCCESSFUL in 5m 1s / 20 actionable tasks: 20 executed` (run before final test-only additions; final targeted rerun below re-proves the touched file).
- `& .\tools\flash-gradle.ps1 -WaitMinutes 45 -GradleArgs ':server:test','--tests','com.transfer.flash.server.directory.DirectoryPrivacyTest','--rerun-tasks'` → `BUILD SUCCESSFUL in 1m 40s / 20 actionable tasks: 20 executed`; XML: `tests: 12 failures: 0 errors: 0`.
- `& .\tools\flash-gradle.ps1 -WaitMinutes 45 -GradleArgs ':core:enterprise:jvmTest','--rerun-tasks'` → `BUILD SUCCESSFUL in 1m 25s / 16 actionable tasks: 16 executed`; total 150, failures 0.
- `node --no-warnings --test server/admin-ui-tests/*.test.mjs` → `tests 62 pass 62 fail 0`.
- Banned-pattern grep over the 17 touched files: only pre-existing `sec.admin.resetPw.text` "temporary" (not mine); no TODO/FIXME/stub/Ignore/empty-catch/`!!` in added code.

### Shared files touched with hunk sizes
- `core/hub-protocol/.../HubContract.kt`: +3 lines (2 path constants).
- `core/hub-protocol/.../rest/RestDtos.kt`: +2 (`hidden=false`).
- `core/enterprise/.../EnterpriseModels.kt`: +7 (departmentName/hidden fields + mapping).
- `core/enterprise/.../EnterpriseDirectoryClient.kt`: +51 (fetchPeople/lookupPerson).
- `core/enterprise/.../HubPeerDirectory.kt`: +19 (lookup fallback + cache).
- `server/.../directory/OrganizationDirectory.kt`: +164/-? (visibility, hidden, migration 3, search).
- `server/.../directory/DirectoryRoutes.kt`: +205 (people, lookup, privacy rendering).
- `server/.../admin/AdminApiDirectory.kt`: +15 (hidden accept + audit).
- `server/.../admin/AdminApiDtos.kt`: +4 (hidden fields).
- `server/.../auth/RateLimiter.kt`: +4 (directoryPeople limiter).
- `server/.../resources/admin-ui/js/views/organization.js`: +15 (toggle+confirm+badge).
- `server/.../resources/admin-ui/js/strings/en.js`: +6 (exact plan strings).
- `server/.../directory/DirectoryRoutesTest.kt`: 6-line hunk (A2 citation).
- `server/.../directory/OrganizationDirectorySyncTest.kt`: 6-line hunk (A2 citation).
- `server/.../e2e/E2eDirectoryContractTest.kt`: 5-line hunk (A2 citation).
- `docs/protocol.md`: +27 (privacy v1 subsection). `docs/protocol-routes.txt`: +2 routes.
- NEW files (no conflicts): `DirectoryPrivacy.kt`, `DirectoryPrivacyDtos.kt`, `DirectoryPrivacyClient.kt`, `DirectoryPrivacyTest.kt`, `DirectoryPrivacyClientTest.kt`, `privacy.test.mjs`.
- NOT touched (per rule): `logs/*.md`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`, `AGENTS.md`, integration tree, hosts, `ui/*`.
