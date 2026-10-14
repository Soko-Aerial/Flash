# S2-server decisions additions (fragment; integrator folds into `docs/decisions.md`)

## ADR-137 — Directory privacy option C (status: direction ACCEPTED, keys point D9 ACCEPTED 2026-10-14; now BUILT by S2/A2, needs verifier ACCEPT)

### Decision
Org-wide names/departments/online dots; per-department `hidden` flag for
listing-only secrecy. Identity + sealing keys org-visible for non-hidden
departments (`DIRECTORY_KEYS_ORG_WIDE=true`, D9 2026-10-14), insiders-only for
hidden ones (via membership, known-id lookup, Plan B Layer 2 handshake).

### Built (A2, 2026-10-14, branch `agent/S2-server`)
- `Department.hidden` persisted (directory migration 3), admin API + console
  toggle with the plan's exact help text, audit `directory.department.hidden`,
  epoch bump on every change (full resync).
- `DirectoryVisibility` is the single decider (all reads route through it;
  architecture test proves it).
- New routes `GET /directory/people`, `GET /directory/people/{id}`; widened
  `departments`/`members`/`groups`/sync; `docs/protocol.md` "Directory privacy
  v1"; `docs/protocol-routes.txt` regenerated.
- Client `fetchPeople`/`lookupPerson`, standing rules, hub-too-old fallback
  sentence (exact).
- Owner answer recorded: §3.5 presence switch NOT built in v1 (D11).

### Deviations from the plan text (with reasons)
- `DepartmentDto` counts stay non-null Ints; hidden outsiders get `0` (plan said
  null) — null would break old clients' non-null decode. Hidden rows are
  filtered before counts anyway.
- `/members?departmentId=<missing>` changed 403→404 (same body as hidden) to
  remove the existence oracle.

### Revisit when
Owner flips `DIRECTORY_KEYS_ORG_WIDE` (one const); per-department presence
switch if asked.
