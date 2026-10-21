# Hub and server console review, and the "ledger" redesign (2026-10-20)

Owner request: "review server and hub page, I think some essential features are missing and the available ones are not sufficient,
and the hub tabs are too roundy: think of a whole new design concept and redesign the Hub again."

Status: review written from the code (nothing run on a device); the visual redesign is IMPLEMENTED in `ui/chat/.../hub/` and unit-tested;
every feature below marked "needs a decision" is **proposed only**, not built.

## 1. What exists (verified in code)

Admin console (`server/src/main/resources/admin-ui`, vanilla JS, routes in `docs/protocol-routes.txt`):

- Overview with health, relay, devices and invites tiles (15 s poll; the sparkline lives in the browser, the hub keeps no history).
- Devices: rename, move to a department, revoke, bulk revoke, bulk move, export.
- Invites, including bulk CSV invites.
- Organisation: departments (create, rename, hidden flag, heads), groups (create, members, admins), join-request approval.
- Announcements: create, edit, delete, pin, preview, department scoping, optimistic concurrency (`expectedSeq`).
- Audit log with chain verification and CSV export; security (admins, sessions, approver phone, recovery codes, TOTP reset,
  delegation and revocations); settings (invite defaults, file limits and usage).

Hub tab (client): News (cards, reader, composer for posters), People (search, filters, person sheet with Message / Call / Pair),
Departments (card, department page), Groups (join / open), status strip and status page.

## 2. Gaps (each checked against the code before it was written down)

| # | Gap | Where | Needs |
|---|---|---|---|
| G1 | The console composer never sends `expiresAtMs`, although the server DTO and the client (`HubAnnouncementUi.expiresAtMs`, `FlashHubMath.isLive`) support expiry. Clients show no "ends in ..." text. | console `announcements.js`, client card | console field + client text only, no wire change |
| G2 | No read receipts or reach numbers for announcements ("who has seen the closure notice?"). | server + wire | owner decision, ADR, protocol |
| G3 | No urgency level. `kind` and `flags` exist on the wire (`AnnouncementItemDto`) but neither console nor client exposes them. | console + client | small wire-compatible use of existing fields; decision |
| G4 | No organisation policies (allow files, calls, retention per org or department). Only global file limits exist. | server + client | owner decision, ADR |
| G5 | No history for the overview (the hub keeps none): trends, devices online over time. | server | storage decision |
| G6 | No admin alerts (a lease about to lapse, a device flood, a failed backup) by mail or webhook. | server | owner decision |
| G7 | No in-console backup / restore (only `KeyTool`, `SecretBox`, `KeyFiles` on the host). | server | owner decision |
| G8 | Hub tab: no "me" strip (my department, my role, my devices), no favourites or recents, no "who to ask" (administrators and department heads) filter, no org chart view, no contact-administrator entry. | client | client only for the first three |

Deliberately not claimed: member editing exists (rename and move are in the device table), so "no way to edit members" is not a gap.

## 3. Recommendation

Do now (no wire change, in this round): the redesign (section 4). Next, in order: G1 (expiry field and "ends in" text), G8 ("who to ask"
filter and a "me" strip). G2, G3, G4, G6, G7 each change the server contract or storage; they need an owner pick and an ADR before any code.
Nothing in section 2 is built by this round.

## 4. Design: from round, to boxy, to soft layered surfaces

History (kept so nobody repeats it): the first pass (24 dp cards, pill tabs, circles) was "too roundy"; the second pass ("ledger": 2 dp corners,
ruled rows, no cards) was "too boxy". The owner wants beautiful and professional, so the current pass sits between them.

| Piece | Now |
|---|---|
| Shapes (`HubShapes`) | cards 16 dp, controls 12 dp, chips 6 dp, marks a squircle (30 % of the side); capsules only for the switch, progress tracks, the tab underline and status dots |
| Tabs | text on a hairline, 3 dp capsule underline that glides; no pill |
| Masthead | gradient hero card: squircle org mark, pulsing connected dot, rounded-tick trust meter with days left, role chips, three count tiles (icon, count-up number, label) |
| News, departments | surface cards with a hairline border (accent for pinned / your department), 4 dp gap between cards |
| People, groups | soft rows with a rounded press highlight, no dividers |
| Person sheet | tinted hero card, reachability strip tinted by tone |
| Section labels | small capitals with a hairline to the edge |

Unchanged: every `FlashHubActions` member, the data models and the mode gating (full / reduced motion / minimal chrome / ultra-low). No new
colour, dependency, icon or wire. Files: `FlashHubMotion.kt` (`HubShapes`, `hubCapsStyle`, `hubRuleBelow`, `HubMark`, `HubTickMeter`),
`FlashHubParts/News/People/Org/Status.kt`.

Verified: `:ui:chat:jvmTest` (`com.transfer.flash.ui.hub.*`), `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin` green. Not seen on a device.
Device checks `HUBL-01..HUBL-08` (their wording about 2 to 4 dp corners and ruled rows is superseded by this table: judge by "soft, layered,
not round, not boxy").
