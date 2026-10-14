# Plan A2 — Directory privacy, option C: an organisation-wide directory with hidden departments

**Written:** 2026-10-14. **Status: PLANNED, nothing built.** Read `docs/plans/FLASH-HUB-INTEGRATION-ROADMAP.md` first.
**Owner decision:** D2 (option C). **ADR:** ADR-137 (accepted as direction; the keys point was answered by the owner on 2026-10-14, D9, §3.4).
**Tests owed:** `PRIV-01`… in `docs/testing/TEST-BACKLOG.md` section 4zzzzzzzzz. **Build order:** after Plan B phase B1 (it reuses the sync endpoint), before A3 and A4.

**Quality contract (mandatory, read before coding):** `docs/plans/HUB-QUALITY-CONTRACT.md` applies to every phase of this plan: explain-back before code, the 12 coverage categories, evidence table, banned shortcuts, adversarial self-review (>= 10 findings), phase report. **The minimum is a failure.** The section "Beyond the minimum" below is required work, not a wish list.

---

## 1. What privacy option C means (explained plainly; this is the text the owner asked for)

Today the hub shows a person only the **people of their own department** (and departments they administer). Consequences found in code on 2026-10-14:

- A person cannot find, list or search a colleague in another department. The new Hub tab (People) would be nearly empty for most users.
- The device's cache of "who is in my organisation" (`HubPeerDirectory`) holds only own-department members, so a message from an employee of **another department** is probably rejected as "unknown" (ADR-122 requires the sender to be paired or listed). VERIFY (Plan B step B0.6).

Three options were considered with the owner:
- **A — everyone sees everyone** (simple, no secrecy between departments).
- **B — own department only** (today; no company-wide People list).
- **C — (chosen) everyone sees names, departments and online dots of everyone, except departments an administrator marked *hidden*.** A hidden department (for example "Executive board", "HR investigations") does not appear in the department list, its people are not listed or searchable, and its member counts are not shown to outsiders. Its members and the organisation's administrators still see it.

What "hidden" does **not** do (state this in the console help and in `docs/security.md`, because it is easy to over-promise):
1. It hides **directory listing only**. It does not stop messages: anyone who knows a person's id, or has been contacted by them, can chat (ADR-127 addendum: inter-department messaging is wanted).
2. People on the same Wi-Fi can still **see each other's device names** through local discovery (NSD); hiding in the directory does not change that. Do not claim otherwise.
3. The hub operator and org administrators see everything.
4. A hidden department's **groups** are hidden to outsiders as well; people who are already members of such a group keep it.

## 2. Ground truth (read 2026-10-14; VERIFY before editing)

`DirectoryRoutes.kt` (`server/.../directory/`):
- `GET /api/v1/directory/members`: with `groupId` → members of a visible group (visibility via `directory.canSee`/`canSeeMembers`); otherwise `departmentId` defaults to the caller's own; allowed only for org admins, the caller's own department, or that department's admin devices; else 403; blank department and no own → 400 `MESSAGE_NEEDS_SCOPE`. The response adds sealing info (`withSealing(sealingKeys, isOnline)`).
- `GET /api/v1/directory/departments`: only own department (or administered ones; org admin sees all).
- `GET /api/v1/directory/groups`: `directory.listVisibleGroups(actor)`.
- `GET /api/v1/directory/me`.
- Mutations (`POST /departments`, groups, members) use `principal.toActor()` and `directory.*` with `DirectoryActor`; `hideFromStrangers(...)` exists for groups (returns not-found for invisible groups; read it).
- `Department(id, name, adminDeviceIds)` has **no hidden flag**; `GroupCharter.visibility LISTED|HIDDEN` is per group.
Client: `HubDirectoryApi.fetchDepartmentMembers/fetchDepartments/fetchVisibleGroups`, `HubPeerDirectory` (in memory, TTL 10 min, min refresh 30 s), `EnterpriseDirectoryMember`, `EnterpriseDepartment`.

## 3. Design

### 3.1 The one visibility function (server)
Create `DirectoryVisibility` in `server/.../directory/` and make **every** directory read and the Plan B sync use it. No route may re-implement the rules.

```kotlin
class DirectoryVisibility(private val directory: OrganizationDirectory) {
    fun canSeeDepartment(actor: DirectoryActor, dept: Department): Boolean   // not hidden || actor.isOrgAdmin || actor is member of dept || actor administers dept
    fun canListMember(actor: DirectoryActor, member: DepartmentMember): Boolean // canSeeDepartment(member's department)
    fun canLookupMember(actor: DirectoryActor, deviceId: String): Boolean       // any authenticated org member who knows the id (see 3.3), never enumerates
    fun canSeeGroup(actor, group): Boolean   // existing group rules AND (department of group not hidden OR actor in that department OR actor is a member of the group OR admin)
    fun keysVisible(actor, member): Boolean  // see 3.4
    fun presenceVisible(actor, member): Boolean // dept hidden => only for those who can see the dept; plus optional per-department switch (3.5)
}
```
`DirectoryActor` today knows `isOrgAdmin` and device/department/role; VERIFY and extend minimally (do not break `DirectoryActor.CONSOLE`).

### 3.2 Data model
- `Department.hidden: Boolean = false`, persisted (VERIFY the directory storage: SQLite store class and its migration component; add a migration `ALTER TABLE departments ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0` — forward-only, test on a DB created by the previous version).
- `DepartmentDto` and `DepartmentSyncDto` gain `hidden: Boolean` (default false; **old clients ignore unknown fields — VERIFY the JSON configuration `ignoreUnknownKeys`**; add a test that decodes a new DTO with the old class definition if feasible, otherwise assert the config flag).
- Admin API: `PUT /admin/api/v1/departments/{id}` accepts `hidden` (org admin only, audit `directory.department.hidden`, step-up not required but **confirmation dialog** required in the console). `POST /admin/api/v1/departments` accepts `hidden` (default false).
- Every change of `hidden` bumps the `directoryEpoch` (Plan B §5.4) so all clients resync fully.

### 3.3 Endpoints
1. **`GET /api/v1/directory/people?cursor=&limit=&q=&departmentId=`** (new): the org-wide people list for the Hub tab. Returns `PersonDto(deviceId, displayName, departmentId, departmentName, role, online?)` for every member the actor `canListMember`; **no keys**. `q` = case-insensitive prefix/contains search on `displayName` (server-side, indexed; escape `%`, `_`; max 64 chars; empty → all). Paged (default 100, max 500) ordered by `(displayNameFolded, deviceId)`; cursor opaque. Rate limit 30/min/device. A caller with no department is allowed (admin devices).
2. **`GET /api/v1/directory/people/{deviceId}`** (new): single lookup by known id for **any** authenticated org device (so a message from a hidden-department colleague can still be recognised, and Plan B's cross-check works). For a hidden-department member the department fields are blank unless the actor can see the department. Rate limited (60/min/device, with a lower limit on misses: 10/min of 404s, to blunt id guessing); a miss returns 404.
3. `GET /api/v1/directory/departments` (changed): returns every department the actor `canSeeDepartment`, with `memberCount`/`groupCount` **omitted (null)** for departments the actor is not part of **when hidden** (never reveal the number); non-hidden show counts.
4. `GET /api/v1/directory/members` (kept, **backward compatible**): same scoping rules as today for old clients, plus allowed for any non-hidden `departmentId`; keys per §3.4; unchanged response shape.
5. `GET /api/v1/directory/groups`: add the hidden-department filter (`canSeeGroup`).
6. Plan B sync (`/directory/sync`): the member list uses `canListMember`; hidden-department rows are never delivered to outsiders; epoch bump on visibility changes.
Add every new route to `docs/protocol-routes.txt` and `docs/protocol.md` (new section "Directory privacy v1").

### 3.4 Keys (**DECIDED by the owner 2026-10-14, D9: org-visible for non-hidden departments**)
D2 said keys "stay restricted as today". Two facts conflict with a literal reading:
- Plan B (offline trust) distributes each colleague's **identity public key** in the signed member statement to every device for non-hidden departments. A public key is not a secret, but this *is* a widening of who learns which key belongs to which person.
- Inter-department chat sealed end to end needs the recipient's **sealing public key** (ADR-124, ADR-126). If sealing keys stay department-scoped, a message to another department is either unsealed or impossible, which contradicts "inter-department messaging is wanted".

**Owner decision (D9): the author's recommendation below was accepted.** Implement it behind one server constant so it can be flipped later: **identity key and sealing key are visible org-wide for non-hidden departments and never for hidden ones to outsiders.** Hidden-department keys are delivered only (a) to members/admins of that department, (b) through the single lookup by id (§3.3.2) because the caller already holds the id (for example from a delivered message), and (c) in the Plan B Layer 2 handshake. If the owner says "keep keys department-scoped", then: sync statements are delivered only for the caller's department and groups' members, Plan B covers only same-department colleagues, and the Hub tab's "Message" for other departments goes through the hub unsealed with the existing honesty line. **Implement the constant `DIRECTORY_KEYS_ORG_WIDE` with default `true` (decided), keep the `false` path implemented and tested (it is the fallback described in the previous sentence), and record the owner's answer (2026-10-14, D9) in ADR-137.**

### 3.5 Optional: a presence switch per department (owner answered 2026-10-14: NOT built in v1)
`Department.presenceHidden` (default false): online dots for its members are shown only to people who can already see the department's members. Without this flag: non-hidden departments always show dots; hidden departments show dots only to insiders. The owner mentioned a separate switch; ask once at the end of A2 whether it is wanted. If not, record "not wanted" in ADR-137.

### 3.6 Client
- `HubPeerDirectory` standing rules: `MEMBER` if the id is in the Plan B snapshot **or** the single lookup (§3.3.2) finds it; `UNKNOWN` only when the lookup returns 404; `UNAVAILABLE` when the hub cannot be reached and the snapshot has no row. This fixes cross-department senders and removes the `fetchDepartmentMembers(url, "")` own-department-only read. Keep the old call as a fallback for hubs without the new routes (capability detection by 404 on `/directory/people`).
- New `HubDirectoryApi` methods: `fetchPeople(url, cursor, limit, q, departmentId)`, `lookupPerson(url, deviceId)`; DTO mapping to `EnterpriseDirectoryMember` (add `departmentName`, `hidden`).
- No UI in this plan (the Hub tab is A4).
- Offline: the Plan B snapshot is the People list; `fetchPeople` is used only when the snapshot is unsupported or for search over the full directory while online.

### 3.7 Console
Organization view → department row: toggle **"Hidden from the company directory"** with this help text (exact): "People in other departments will not see this department, its people or its groups in the directory. They can still message each other if they already know each other. Devices on the same Wi-Fi may still see each other's device names. Administrators and the department's own members still see it." The toggle asks for confirmation (dialog) and shows "Applies to everyone's directory at their next update." Strings in `en.js`; Node tests: toggle visible only to org admin; dialog text; strings exist.

## 4. Security expectations
1. One function decides visibility; a **test enumerates every directory route** and asserts that it calls the function (use a fake `DirectoryVisibility` that records calls, or a per-route policy table like Plan A1).
2. Hidden is enforced **server-side**; the client never filters for secrecy.
3. No oracle: lists never include counts that reveal hidden members; 404 vs 403 consistent; the single lookup is rate-limited and returns the same shape for "does not exist" and "exists but you may not know", **except** where the caller is permitted — decide and test: for hidden-department members the lookup returns the person (id known = allowed) but blank department; non-existent → 404. Document that the lookup confirms existence of an id (ids are random 128-bit; enumeration is infeasible).
4. Search must not leak hidden people through ranking, counts, "N results" text, or paging cursors (test: search for a hidden person's exact name as an outsider returns 0 rows and an identical cursor shape).
5. Timing is not a goal; do not add padding.
6. Audit: every change of the hidden flag and every failed lookup burst (≥ 20 misses/min) is recorded.
7. Logs: no names in server logs.

## 5. Situations the plan does not spell out (expectations)
- Admin hides a department that has cross-department group members → those members keep the group; the group is listed to them under "Yours"; outsiders no longer see it in browse lists; join requests in flight still work for the admin.
- Admin un-hides → epoch bump → clients resync fully; no stale rows (test).
- Org has 0, 1, or 200 departments; department with 0 members; department with a name containing RTL/emoji.
- A member is moved into a hidden department → disappears from outsiders' lists at their next sync; existing conversations stay (conversation rows are local); the Hub tab shows them as a normal conversation, not as a directory entry.
- A person is in a hidden department and an outsider has them paired → pairing is unaffected; the chat shows their name from the pairing.
- A hidden-department head posts an announcement to a company-wide scope (A3): author line shows name + "Department head"; department name omitted if hidden. Decide in A3.
- Page size abuse (`limit=1000000`) → clamped; invalid cursor → 400 (not 500); `q` > 64 chars → 400.
- Old client against new hub: old `/members` and `/departments` calls work as before for own department, plus the changes in §3.3.3 (more departments visible: old clients ignore them? **VERIFY**: the old client lists all returned departments in the Browse sheet; the new rule shows more departments than before; confirm the old UI handles it — it only reads names for the groups sheet).
- New client against old hub: new routes 404 → fallback; the Hub tab says "Your company server is out of date. Some people may not be listed."
- 5,000-member org: a full `people` listing is paged; the Hub tab never requests all pages at once while online except through the Plan B sync.
- Hub restore, version goes backwards → Plan B epoch handles it.
- Clock, time zones: irrelevant here.

## 6. Implementation steps
1. Read the files in §2 and the directory storage class; fill the Implementation log.
2. Server: `Department.hidden` + migration + admin API fields + audit.
3. `DirectoryVisibility` + refactor existing routes to use it (behaviour for old shapes unchanged; the existing directory tests must stay green).
4. New routes `people`, `people/{id}`; changed `departments` and `groups`; route table + protocol docs.
5. Epoch bump hook shared with Plan B: Plan B phase B1 is built first (owner order D4) and provides the epoch and the shared `DirectoryVisibility` wrapper. Call them; toggling `hidden` must bump the epoch (test). If B1 is not built, **stop and ask**: do not write a stub, a TODO or a no-op (quality contract, banned shortcuts).
6. Client: DTOs, API, `HubPeerDirectory` changes with fallback.
7. Console toggle + tests.
8. Keys flag (default `true`, owner decision D9) implemented and both values tested; ADR-137 updated.
9. Docs: `docs/security.md` ("What hidden means"), `docs/protocol.md`, routes, `docs/ui/hub-admin-console.md`, progress, handoff, AGENTS.md §29, backlog `PRIV-*`.
10. Mutation checks: make `canListMember` return true → tests fail; drop the hidden filter in `departments` → fail; leak counts → fail.

## 7. Tests
`PRIV-U01` visibility matrix (actor × resource); `U02` migration; `U03` people paging/search/escape; `U04` hidden never leaks via search/counts/cursor; `U05` single lookup behaviour + rate limit; `U06` old `/members` unchanged; `U07` groups of hidden departments; `U08` console toggle node tests; `U09` HubPeerDirectory: cross-department sender is MEMBER via snapshot/lookup, UNKNOWN on 404, UNAVAILABLE offline; `U10` fallback to old routes; `U11` epoch bump on toggle; `U12` log hygiene; `U13` route-table test.
Device/manual: `PRIV-01` outsider cannot find a hidden department in the console-created company; `PRIV-02` insider can; `PRIV-03` a hidden-department member can message an outsider and the outsider's phone accepts it; `PRIV-04` toggling hidden updates a phone's list after refresh; `PRIV-05` old app + new hub.

## Beyond the minimum (REQUIRED for Plan A2; the contract's section 3 applies on top)

1. **One function decides visibility, and a test proves nothing bypasses it.** Add an architecture test that finds every server read of members, departments, groups and (later) announcements and asserts it goes through `DirectoryVisibility` (a grep-style test over the source set is acceptable if it is strict and has a mutation check: add a bypass, watch it fail).
2. **Leak matrix for an outsider** (a device in another department): names, counts (department size, total members), pagination cursors, search suggestions and ranking, error messages, response timing shape, group lists, group member counts, presence/online dots, join-request lists, relay presence, notification text. One test per row; each asserts the hidden data is absent and the shape equals the "does not exist" shape.
3. **Toggling hidden and visible**: effect on cached clients (epoch bump, full resync, old entries removed from the local cache, not only hidden in the UI), on existing conversations (they stay; a person you already chat with does not vanish from your chat list), on the groups of a hidden department (not browsable; existing members unaffected), on pending join requests and invites.
4. **Search is safe and bounded**: minimum query length 2, page size cap 50, rate limit per device, Unicode normalisation and case folding documented, no wildcard injection, no unbounded scans (assert the query plan uses an index or document the measured cost), the result for a hidden person identical to "no result".
5. **Scale**: 5,000 members across 50 departments, search and paging measured and recorded (synthetic).
6. **Keys flag, both values**: `DIRECTORY_KEYS_ORG_WIDE` true (shipped default) and false are both tested; hidden-department keys are delivered only to members/admins, by known-id lookup, and never in listings.
7. **Compatibility**: old client with the new hub (old routes keep answering with the old, own-department behaviour or the documented superset), new client with an old hub (falls back, shows the not-supported sentence, no crash).
8. **Honest console wording**: the "hidden" toggle explains exactly what hidden means and does not mean (listing only; direct messaging by a known id and local-network device names are not hidden; administrators and the hub operator see everything), using the plan's text.
9. **Security documentation**: a threat row for directory enumeration with measured limits, and the residual risk of local-network name leakage.

## 8. Definition of done
- [ ] Quality contract satisfied for every phase: explain-back, evidence table, 12 categories, >= 10 adversarial findings, >= 5 extra situations, phase report (`docs/plans/HUB-QUALITY-CONTRACT.md`); the verifier prompt (`docs/plans/HUB-PLANS-PROMPTS.md`) was run by the owner or is pending.

Tests green; mutation checks; docs and logs updated; §3.5 (presence switch) answered by the owner 2026-10-14: not built in v1; nothing claimed device-verified.

## 9. Implementation log
_(empty)_
