# Group sync revamp and new-member history (FO-10 made concrete)

**Status:** DESIGN, owner decisions taken 2026-10-08, nothing built. Needs an ADR (number on acceptance) before code.
**Owner request 2026-10-08:** "revamp syncing in groups" and, when a member is added, ask how much history to sync (messages and media).

---

## 1. How sync works today (read from the code 2026-10-08; nothing was run)

| Item | Today | Where |
|---|---|---|
| Request | Sent on a session-up edge to the freshly connected peer, once per shared group. `sinceSentAt/sinceMessageId` = the **newest local message** (`0 / ""` for a new member) | `RealFlashChatRepository.sendSyncRequestFor` |
| Text window | Holders send only rows newer than the cursor **and** newer than 24 h | `GroupPolicy.SYNC_TTL_MS`, `GroupSyncPolicy.ownedMessages` |
| Swarm file offers | 7 days (`SWARM_OFFER_SYNC_TTL_MS`) | ERROR-120 |
| Round size | At most 500 messages (100 on LOW tier), paced 20/s (5/s LOW); catch-up read pages of 100, at most 20 pages | `GroupPolicy.syncLimits`, `MAX_CATCH_UP_PAGES` |
| Election | Deterministic: tier rank then FNV-1a hash picks the pusher, rank 1 is the 2 s backup | `GroupSyncPolicy.electRank` |
| Safety | A push is ingested only against a recorded outgoing request (`syncId`, group, asked peer, 10 min TTL) | `OutgoingSyncRequest`, F-4 |
| Choice | None. The new member cannot decline; the group cannot restrict | FO-10 |
| Media | Arrives as catch-up labels; real files only through the swarm while it still holds them | ERROR-120/121 |

### Suspected defects (to prove with a test before fixing)
- **S1, cursor gap:** the cursor is the newest local row, so a device that already holds a recent row (live, or from a partial first holder) never asks for older rows it missed.
- **S2, window mismatch:** a member offline two days loses the middle day of text for good while file offers go back 7 days.
- **S3, one round only:** a 500-row cap with no continuation; the rest waits for the next session-up edge.
- **S4, no progress or resume:** the banner (UI-052) shows activity, not "N of M".

## 2. Owner decisions (2026-10-08)

| # | Decision |
|---|---|
| 1 | **A new member's default is 30 days of history** (an admin can lower or raise the ceiling). |
| 2 | **"No history" means no files either.** History includes files; nothing older than the join is offered. |
| 3 | **A returning member gets the full 7 days** of what it missed. |
| 4 | **Any admin can change the ceiling.** |

### Consequences to settle (open)
- **O1, files vs 30 days:** the swarm keeps content 7 days (`SwarmConfig.retentionMs`). Text can honestly go back 30 days; **files cannot unless retention is raised or the file is still on a member's disk and re-offered.** Proposal: the card says "files: the last 7 days (what the group still holds)". Raising retention is a storage decision (FO).
- **O2, returning member vs 30 days:** a member away 10 days gets 7 (decision 3), so days 8-10 are lost, while a brand-new member can get 30. Proposal: the returning window = `min(gap, ceiling)` with 7 days as the **minimum guaranteed**. Owner to confirm.
- **O3, "any admin" needs co-admins:** a v2 group has exactly one owner who signs settings (ADR-044, ADR-074). "Any admin" requires the owner-loss path **A** (co-owners / admins in the roster) from `docs/audit/2026-10-01-chat-edge-case-audit.md`, which the owner has not yet chosen. Inside an organisation the admin comes from the delegation chain (`ENTERPRISE-HYBRID-PLAN.md` E2b/E2c) instead.
- **O4, the TTL is a holder policy:** holders must actually retain 30 days of text rows (retention/cleanup rules must not prune earlier than the ceiling).

## 3. Design

### 3.1 Signed setting (GM-9 / ADR-074 mechanism)
`historyCeiling` in `GroupSettings`: `NONE | H24 | D7 | D30 | ALL`, default **D30**. Changed by any admin; versioned and signed like the other settings. Wire-compatible: unknown fields are ignored by older builds, which then behave as today (24 h), so a **mixed fleet is safe but not equal**; record this.

### 3.2 Join card (new member, one time per group)
> **Catch up on <group>?**  Messages: *None · 24 h · 7 days · 30 days (default) · Everything allowed*.  Files and media: *Don't fetch · Last 7 days* (each file still asks Accept).

The choice is clamped to the ceiling and **cannot exceed it**. `NONE` ceiling hides the card ("This group does not share earlier history"). The card is also reachable later from the group info ("Load older messages") within the ceiling.

### 3.3 Request and holder rule
`SyncRequest` gains `windowMs` (and `includeFiles`). The holder serves `min(windowMs, ceiling)` and applies its own retention. Holders enforce; a member who already holds data can always leak it (state this in `security.md`).

### 3.4 Watermark instead of newest-row cursor (fixes S1)
Per group and per holder keep a **contiguous watermark** (everything up to here is complete) instead of "newest row". A request asks from the watermark, not from the newest row. Out-of-order live rows do not advance it past a gap.

### 3.5 Paged, resumable catch-up (fixes S3, S4)
Pages of up to 100 rows continue from the last received `(sentAt, id)`; the requester asks for the next page until the holder says `done`; progress `received / expected` feeds the banner; a lost session resumes from the watermark. Pacing and the F-4 ledger stay.

### 3.6 Returning members (decision 3)
On a session-up edge the window is `min(gap since last contact, ceiling)` with 7 days guaranteed (O2), not the fixed 24 h.

### 3.7 Later
The enterprise server mailbox is one more holder answering the same request (`ENTERPRISE-HYBRID-PLAN.md` E4). No second protocol.

## 4. Tests owed (add to `docs/testing/TEST-BACKLOG.md`)
`GSY-01` new member sees the join card and the default 30 days; `GSY-02` choice above the ceiling is clamped; `GSY-03` ceiling NONE shows no card and no files; `GSY-04` returning member gets the 7-day gap, not 24 h; `GSY-05` cursor-gap (S1) unit test, then device; `GSY-06` more than 500 rows arrive in pages with progress and resume after a dropped session; `GSY-07` any admin changes the ceiling, a non-admin cannot; `GSY-08` an old build in the group still works (mixed fleet); `GSY-09` holder retention keeps 30 days of text.

## 5. Order
1. Prove S1/S2 with unit tests on `GroupSyncPolicy` (no behaviour change).
2. ADR + wire fields + ceiling setting. 3. Watermark and paging. 4. Join card UI (Android and desktop, `Flash*` design system, UI doc first per AGENTS.md section 34). 5. Co-admins (O3) when the owner picks path A.

**Read first:** ADR-044, ADR-074, ADR-090, ADR-091, `docs/protocol.md` group sync, `GroupPolicy.kt`, `GroupSyncPolicy.kt`, `OutgoingSyncRequest.kt`, `RealFlashChatRepository` (`sendSyncRequestFor`, `handleSyncRequest`, `catchUpCandidates`).
