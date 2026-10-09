# Group history sync (ADR-100) - build report, 2026-10-09

Stream: GROUP HISTORY SYNC. Plan: `docs/group/GROUP-SYNC-REVAMP-PLAN.md`. Status of everything below: **unit-tested, mutation-checked in part, NOT device-verified.** Nothing was committed, pushed or reverted; shared logs (`logs/*`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`, `AGENTS.md`) were not edited: their text is in the paste-ready sections at the end.

## 1. What was built

### 1.1 The two defects, proven first (items S1 and S2)

- **S1 cursor gap.** A catch-up request started "after the newest row I already hold". A device that got a recent row first (live, or from another holder) then skipped every older row it was missing for good. Proof: `GroupHistorySyncTest.s1TheOldRequestSkipsARowMissedBelowTheNewestOneAndTheWatermarkedRequestDoesNot` (two real repositories) and `GroupHistoryPolicyTest` (`requestStart`, `advanceWatermark`).
- **S2 window mismatch.** Every holder answered with a fixed 24 h of text, so a member away for 3 days could never receive day 2 of its absence. Proof: `GroupHistorySyncTest.s2AReturningMemberGetsTheMiddleDayThatThe24HourWindowLost`.

### 1.2 Wire (all forward-compatible; documented in `docs/protocol.md`, the group-sync and group-settings sections only)

- `FLASH_GSYNC op=request` gains optional `windowMs`, `files`, `cont`. Emitted only when set, so an old holder sees the request it always saw.
- New `FLASH_GSYNC op=page` (marker): `count`, `remaining`, `more`, `lastAt`, `lastId`, `keyEpoch`. Sent after a round's pushes, only to a requester that sent `windowMs`.
- Settings bundle gains `setHist=<NONE|H24|D7|D30|ALL>`, emitted only when not `D30`. A non-default ceiling is signed under `flash-gset-v2` (v1 fields in order, then the ceiling name); `D30` keeps the `flash-gset-v1` bytes, so every existing signature stays valid (golden test in `GroupCanonicalTest`).
- Files: `GroupWireFrame.kt` (`SyncRequest` fields, `SyncPage`), `GroupFrameCodec.kt`, `GroupSettings.kt` (`historyCeiling`), `GroupCanonical.kt`, `GroupSigning.kt`, `OutgoingSyncRequest.kt` (F-4 ledger keeps window and continuation).

### 1.3 Pure policy: `core/messaging/.../protocol/GroupHistoryPolicy.kt` (new)

`GroupHistoryCeiling` (NONE, H24, D7, D30, ALL; default D30), `GroupHistoryChoice`, `GroupHistoryWindows`, `GroupHistoryPolicy` (`defaultChoice`, `clamp`, `messageOptions`, `holderWindows`, `returningWindowMs`, `requestFor`, `requestStart`, `inWindow`, `advanceWatermark`, `expectedTotal`), `GroupAdminPolicy` (`isAdmin`, `canChangeHistoryCeiling`: the O3 seam; owner or an active `ROLE_ADMIN` row).

### 1.4 Behaviour in `RealFlashChatRepository` (and `FlashChatRepository`, `FlashMessagingModels`)

- Holder: serves only inside `min(requested window, ceiling)`; no stated window = legacy 24 h text and 7 d files inside the ceiling; `files=false` skips offers; NONE serves nothing, even to an old requester. 100-row pages, `page` marker with remaining count (capped at 2 000).
- Requester: per (group, holder) **contiguous watermark** (table `group_sync_watermark`), advanced only when the whole page was seen (`seen >= count`) and never backwards; resumes from it after a dropped session. A holder that never sends a marker leaves no watermark: up to 3 floor-start requests per (group, holder) per process, then the old newest-row cursor.
- Returning window: `min(ceiling, max(7 d, time since last contact))`, never reaching further back than the member chose.
- New member: a `PENDING` `group_history_state` row exists only for a device that just joined with no message rows; while pending it asks for nothing. `chooseGroupHistory`, `skipGroupHistory`, `loadOlderGroupHistory` (clamped to the ceiling; load-older resets the watermarks). A null state (pre-feature member) = returning member with the 7-day floor and files.
- UI state: `FlashConversationUiState.groupHistory`, `canChangeHistoryCeiling`; `FlashGroupSyncUi.expectedCount`.
- `updateGroupSettings(..., historyCeiling = "D7")` signs and broadcasts the ceiling like every other setting.

### 1.5 Persistence (Room schema 12 to 13)

New `GroupHistoryStateEntity`, `GroupSyncWatermarkEntity`, `GroupHistoryDao`; `group_settings.historyCeiling TEXT NOT NULL DEFAULT 'D30'`; `FlashDatabase` version 13, `STEP_12_13` + `MIGRATION_12_13`, exported schema `13.json`, `FlashMigrationsChainTest` extended. **Coordination risk:** another stream that also bumps the schema must renumber; check `DATABASE_VERSION` before merging.

### 1.6 UI (docs first, then code)

- `docs/ui/group-history-join-card.md` (DESIGNED; 5 approaches compared; UI-057; row added to `docs/ui/ui-research-index.md`).
- `ui/chat/.../FlashGroupHistoryCard.kt`: `FlashGroupHistoryMath` (pure copy and clamping), `FlashGroupHistoryCard` (inline card above the list, once per group, chip rail clamped to the ceiling, files switch off and disabled for "None" ("No history means no files."), "Catch up" / "Not now"; NONE shows "This group does not share earlier history" with an OK button), `FlashChoiceChipRail` and `HistoryButton` (custom, no stock Material chip). Reduced motion respected through `FlashTheme.motion`.
- `FlashGroupSettingsSheet.kt`: new "Earlier history" section. Admin ceiling picker (five chips) shown **only** when `canChangeHistoryCeiling`; others see a read-only sentence; "Load older messages" row (inline chip rail + Load). New parameters have defaults so existing call sites compile. `SettingsSwitchRow` made `internal`.
- `FlashGroupSyncBanner.kt`: "N of about M" once a holder reported `remaining` (only when M > N).
- `FlashConversationScreen.kt`: the card with enter/exit animation, four new optional callbacks (`onChooseGroupHistory`, `onSkipGroupHistory`, `onLoadOlderGroupHistory`, `onSetGroupHistoryCeiling`).

### 1.7 Host wiring (surgical, one block or line each)

`groupHistoryDao = db.groupHistoryDao()` in `app/.../debug/DiscoveryEngineHolder.kt`, `core/engine/.../Flash.kt`, `desktop/.../DesktopEngine.kt`. Callbacks mapped to the repository in `app/.../MainActivity.kt` (toasts on failure) and `desktop/.../DesktopShell.kt` (snackbars). Files are CRLF and dirty from other sessions; line endings were preserved.

## 2. Verification (honest)

Commands used the documented JBR 21 + AF_UNIX recipe; one Gradle at a time.

| Command | Result |
|---|---|
| `:core:messaging:jvmTest` | 223 tests, 0 failures |
| `:core:messaging:testAndroidHostTest` | 522 tests, 0 failures (includes `GroupHistorySyncTest` 11/11 and 4 new `GroupSettingsTest` ceiling tests) |
| `:ui:chat:jvmTest` | 397 tests, 0 failures (`FlashGroupHistoryMathTest` 10/10, banner math) |
| `:core:persistence:jvmTest` | pass (`RetentionPolicyTest` 10, incl. the new O4 test) |
| `:core:persistence:testAndroidHostTest` | `FlashMigrationsChainTest` 2/2, `FlashSchemaStepsTest` 4/4, `FlashDatabaseInvariantTest` 13/13 pass. **12 failures, all `FlashSettingsDataStoreTest` (11) and `DiscoveryModeSettingTest` (1)**: `IOException: Unable to rename ...preferences_pb.tmp` (Windows file rename in the temp folder, DataStore). They touch no code of this work (no Room, no messaging); I could not confirm they fail on a clean tree without stashing, which the rules forbid. Treat as environment-related, unproven. |
| `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin` | `UP-TO-DATE` in the last run: they had compiled successfully with exactly these sources in an earlier invocation by another stream (Gradle never marks a failed compile up to date). I did not force a rerun. |
| `:desktop:jvmTest` | **not run.** Known unrelated failure ERROR-106 (`DesktopEngineGroupSessionUpTest`), not touched. |

Mutation checks (each reverted byte for byte, file compared with `cmp`): clamp ignores the ceiling (3 tests fail), watermark ignores an incomplete page (1 fails), admin check ignores an inactive row (2 fail). All three mutants killed. Not mutation-tested: the repository paging loop and the UI composables.

**Not done / not verified:** no device run of anything (two-phone catch-up, the card, paging with 500+ rows), no Compose preview check, no dark mode / large font / RTL / TalkBack check of the card, no Android lint.

## 3. Decisions taken (owner away)

1. **New member default 30 days; "no history" = no files; returning member gets the full 7 days; any admin can change the ceiling** (owner decisions, applied as given).
2. **O1 files:** kept and offered for 7 days (= `SwarmConfig.retentionMs`; swarm retention not raised). A stated window limits file offers to inside it. Alternative (raise retention to 30 d) rejected: storage cost on every holder.
3. **O2 returning window** = `min(ceiling, max(7 d, time since last contact))`. Alternative "always the full ceiling" rejected: a 30-day pull on every reconnect.
4. **O3 admin seam:** one function (`GroupAdminPolicy.isAdmin`), no new roster ownership. Finding: the task's "no co-admin concept" is inaccurate; `MemberCert.ROLE_ADMIN` (ADR-063) already lets an owner or admin cert sign GM-9 settings. The check exists in two places (repository seam and `SignedGroups`/`GroupSignatureRules`); a future co-admin rule must change both. Wire-only co-admin not built.
5. **O4 retention:** `RetentionPolicy` is pure with no wired pruner, default `retentionDays` 365, so nothing prunes text younger than 30 days today. Pinned with a test (`the default 365 day retention never prunes text a 30 day group history window still serves`): a future pruner with < 30 days would undercut the promise and the test documents it.
6. **Ceiling signing:** D30 keeps `flash-gset-v1` bytes; others sign `flash-gset-v2`. Alternative (always v2) would have invalidated every stored signature. Cost: old builds reject non-default settings (see limits).
7. **Page marker** is a separate frame, not a flag on pushes, so old requesters never see it, and old holders never send one (the requester detects this).
8. **Watermark rule** (advance only on a fully seen page, last SERVED row) over "advance per row": per-row advance would recreate S1 on a lost frame.
9. **Floor-start cap of 3 per (group, holder) per process** for a holder that never pages, then fall back to the cursor: bounds the load a silent old holder can cause.
10. **Pending card state only for a device that joined with no rows**; everyone else is a returning member. Alternative (card for any member with an empty chat) would nag a reinstalled device.
11. **UI: inline card** (not dialog, sheet or silent default); selected chip uses a check and a thicker outline, not colour alone.
12. **Zero-window requests are not sent**, so a "None" member sends no request at all.

## 4. Known limitations

- A first page that dies before its marker: after 3 floor attempts the requester falls back to the newest-row cursor (S1 can recur for that holder).
- A holder that later back-fills rows older than a requester's watermark is not re-asked; other holders usually cover it.
- Mixed fleet: an old build ignores a non-D30 ceiling and keeps serving its old window; an old holder ignores `windowMs`/`files` (answers with its old fixed window). Old requesters never get `page` markers.
- NONE also blocks returning-member catch-up (a deliberate reading of "no history").
- Legacy rounds now carry at most 100 rows (was up to about 199).
- The ceiling is enforced by holders; a modified client that is a holder can serve more. Not a confidentiality control, only a default-sharing rule.
- Pre-feature members never see the card.
- A window larger than what holders keep returns only what they have; the banner's "about M" is capped (2 000) and an estimate.
- Schema v13 may collide with another stream's bump.
- Admin check duplicated (see decision 4).

## 5. Findings and errors

- **ERROR-126 (S1)**: catch-up cursor skipped older missing rows. Fixed in code (watermark), unit-tested, device test GSY-05/GSY-06 owed. Status OPEN until then.
- **ERROR-127 (S2)**: fixed 24 h holder window lost the middle of a long absence. Fixed in code (returning window), unit-tested, GSY-04 owed. Status OPEN until then.
- Observation (not filed as an error): each holder receives its own `syncId`, so the claim election is effectively a no-op and a 2 s backup delay applies before the first push; the MEDIUM tier paces 20 msg/s. Left as is; paging bounds the load.
- No ERROR-128/129 were warranted. EXP-022 not used (no measurement was taken).

## 6. Paste-ready sections

### 6.1 `logs/progress.md` entry

```markdown
## 2026-10-09 - Group history sync (ADR-100)

### Worked on
Finished the group history sync revamp (`docs/group/GROUP-SYNC-REVAMP-PLAN.md`): proofs of the two catch-up defects, a signed history ceiling, a per-holder contiguous watermark with paged resumable catch-up, the returning-member window, the join card and "Load older messages", and host wiring.

### Changed
- `core/messaging`: `GroupHistoryPolicy.kt` (new, pure), wire fields `windowMs`/`files`/`cont` and the `FLASH_GSYNC op=page` marker, signed `historyCeiling` (`setHist`, `flash-gset-v2` for non-default), `RealFlashChatRepository` holder/requester logic, join-card state API.
- `core/persistence`: schema 13 (`group_history_state`, `group_sync_watermark`, `group_settings.historyCeiling`), `MIGRATION_12_13`, migration test.
- `ui/chat`: `FlashGroupHistoryCard` (UI-057), settings sheet history section, banner "of about N", conversation screen wiring. Doc `docs/ui/group-history-join-card.md`.
- `app/`, `desktop/`, `core/engine`: DAO and callbacks wired. `docs/protocol.md` group-sync and settings sections.

### Verification
Unit only: `:core:messaging:jvmTest` 223, `testAndroidHostTest` 522, `:ui:chat:jvmTest` 397, persistence migration/schema/invariant tests green, 3 mutants killed. Desktop and app compile. 12 `FlashSettingsDataStoreTest`/`DiscoveryModeSettingTest` failures in `:core:persistence:testAndroidHostTest` (Windows DataStore file rename), unrelated to this work. Report: `docs/reports/2026-10-09-group-sync.md`.

### Remaining
Device tests GSY-01..GSY-12. Nothing is device-verified.

### Next AI
Run the GSY tests on two phones and a desktop; confirm the DB version has not collided (13).
```

### 6.2 `logs/handoff.md` entry

```markdown
## Group history sync (ADR-100), built 2026-10-09, unit-tested, NOT device-verified
New member default 30 days (join card, UI-057), returning member 7-day floor, signed ceiling NONE|H24|D7|D30|ALL (admin-only control in the group settings sheet), paged catch-up with a per-holder watermark and resume. Room schema is now 13. Entry points: `GroupHistoryPolicy.kt`, `RealFlashChatRepository` (search `groupHistory`, `SyncPage`), `FlashGroupHistoryCard.kt`. Known gaps: report section 4 (`docs/reports/2026-10-09-group-sync.md`). Device checks GSY-01..GSY-12 in the backlog. Open: ERROR-126, ERROR-127. Unrelated failures: ERROR-106 (desktop test), DataStore tests on Windows.
```

### 6.3 `docs/decisions.md`: ADR-100

```markdown
## ADR-100 - Group history is bounded by a signed ceiling; catch-up is paged and resumes from a contiguous watermark

### Decision
1. A v2 group has a signed setting `historyCeiling` (NONE, H24, D7, D30, ALL; default D30) changed by any admin (owner or an `admin` certificate; one seam, `GroupAdminPolicy`). Every holder enforces it whatever a requester asks.
2. A new member chooses its window on a join card (default 30 days, never above the ceiling; "no history" means no files). A returning member asks for `min(ceiling, max(7 days, time away))`. Files are offered for 7 days.
3. Catch-up requests carry an optional window and file flag; a holder answers in 100-row pages and closes each with a `page` marker. The requester keeps a contiguous watermark per (group, holder), advanced only when a whole page was seen, and resumes from it.
4. Wire is additive: optional request keys, a new `op=page`, `setHist` only when not D30. Non-default settings sign under `flash-gset-v2`; D30 keeps the v1 bytes.

### Context
Two defects: the request cursor was the newest local row (an older missed row was never asked for again), and every holder served a fixed 24 hours (a longer absence lost its middle).

### Alternatives considered
Raise swarm retention to 30 days for files (storage cost); always sign v2 (invalidates stored signatures); per-row watermark (reintroduces the gap on a lost frame); a modal dialog or silent default for the join card.

### Consequences
Old builds ignore non-default ceilings and the new request keys (mixed-fleet limit); the ceiling is a sharing rule enforced by honest holders, not a confidentiality control; schema 13.

### Revisit when
A co-admin or enterprise delegation model is designed (change `GroupAdminPolicy`), or device tests show the 100-row page or 3-attempt fallback needs tuning.
```

### 6.4 `docs/testing/TEST-BACKLOG.md` entries (replace the one-line GSY-01...GSY-09 stub; keep the stub marked SUPERSEDED, do not delete)

```markdown
### GSY-01...GSY-12 - Group history sync (ADR-100, 2026-10-09; unit-tested, none device-verified)
Setup for all: phones A and B plus one desktop in one signed v2 group; a third phone C joins later. Source: ADR-100, `docs/reports/2026-10-09-group-sync.md`, `docs/group/GROUP-SYNC-REVAMP-PLAN.md` section 4.

- **GSY-01 (join card, default):** A and B exchange messages and a file over several days (or seed 40 old messages). C joins by invite. **Steps:** open the group on C. **Pass:** the card "Catch up on this group?" shows once with 30 days selected and Include files on; "Catch up" starts the banner "N of about M"; messages from the last 30 days arrive; the card does not come back after a restart. **Status:** TODO
- **GSY-02 (clamp):** admin sets the ceiling to 7 days, then C joins. **Pass:** the card offers None, 24 hours, 7 days only; footer "This group shares up to 7 days."; nothing older than 7 days arrives. **Status:** TODO
- **GSY-03 (NONE):** ceiling Nothing, then C joins. **Pass:** the card reads "This group does not share earlier history" with OK only; no messages and no file offers arrive; "Load older messages" is hidden. **Status:** TODO
- **GSY-04 (returning member, S2):** C offline for 3 days while A and B chat on each of the 3 days. **Pass:** after reconnecting C has every message of all 3 days (not just the last 24 h); log shows a request with `windowMs` of at least 7 days. **Status:** TODO
- **GSY-05 (cursor gap, S1):** C receives a live message while it is missing older ones from A. **Pass:** the older ones still arrive from A after reconnect (log: request starts at the watermark, not after the newest row). **Status:** TODO
- **GSY-06 (paging and resume):** 600+ messages on A; C joins with 30 days; kill C's session (Wi-Fi off) in the middle. **Pass:** banner "N of about M" advances in steps of 100; after reconnecting it resumes from the last whole page (no restart from zero) and ends with all rows once, no duplicates; the banner disappears. **Status:** TODO
- **GSY-07 (admin control):** a second admin (ROLE_ADMIN) and a plain member open Group Settings. **Pass:** both admins see the five-chip ceiling picker and a change reaches the others within a minute; the plain member sees only a read-only sentence. **Status:** TODO
- **GSY-08 (mixed fleet):** one device on a build without ADR-100 in the group. **Pass:** chat and normal catch-up still work between old and new devices; the old device keeps its previous window when the ceiling is not D30 (record it). **Status:** TODO
- **GSY-09 (retention):** leave A with default retention for 35 days of messages. **Pass:** text younger than 30 days is still on A and served to C; nothing prunes it. **Status:** TODO
- **GSY-10 (Load older messages):** C chose "None" or 24 hours on the card. **Steps:** Group Settings, Load older messages, 30 days, Load. **Pass:** older messages arrive (within the ceiling); a ceiling of 7 days offers at most 7 days; the row is hidden for NONE. **Status:** TODO
- **GSY-11 (no files):** C joins choosing 30 days with Include files off, then once with "None". **Pass:** no file offers appear in either case; with files on, file offers from the last 7 days appear. **Status:** TODO
- **GSY-12 (card UX):** card on phone and desktop, dark mode, large font, reduced motion, TalkBack/keyboard. **Pass:** chips wrap, minimum 48 dp targets, selected chip announced "selected", Tab order chips then switch then buttons, no animation under reduced motion, text readable in both palettes. **Status:** TODO
```

### 6.5 Errors found (`logs/errors.md`)

```markdown
## ERROR-126 - Group catch-up skipped older missing messages (cursor was the newest local row)
Date 2026-10-09. Area: group sync. Symptom: a device that received a recent message first never asked for the older ones it was missing. Root cause: the request cursor was `(sentAt, id)` of the newest local row. Fix (in code): per (group, holder) contiguous watermark advanced only when a whole page was seen; floor start when no watermark. Verification: `GroupHistorySyncTest.s1...`, `GroupHistoryPolicyTest`; mutant "watermark ignores incomplete page" killed. Related: `GroupHistoryPolicy.kt`, `RealFlashChatRepository.kt`. Status: OPEN (device test GSY-05, GSY-06).

## ERROR-127 - A member away longer than 24 hours lost the middle of its absence
Date 2026-10-09. Area: group sync. Symptom: after 3 days offline only the last 24 h of text arrived. Root cause: every holder answered with the fixed `SYNC_TTL_MS` window. Fix (in code): requests carry a window (returning member `min(ceiling, max(7 d, time away))`), holders serve inside `min(window, ceiling)`. Verification: `GroupHistorySyncTest.s2...`. Related: same files. Status: OPEN (device test GSY-04).
```

### 6.6 EXP-022

Not used.

## 7. Files touched (this stream)

New: `core/messaging/.../protocol/GroupHistoryPolicy.kt`, `core/messaging/src/commonTest/.../GroupHistoryPolicyTest.kt`, `core/messaging/src/androidHostTest/.../GroupHistorySyncTest.kt`, `core/persistence/.../entity/GroupHistoryStateEntity.kt`, `.../entity/GroupSyncWatermarkEntity.kt`, `.../dao/GroupHistoryDao.kt`, `core/persistence/schemas/.../13.json`, `ui/chat/.../FlashGroupHistoryCard.kt`, `ui/chat/src/commonTest/.../FlashGroupHistoryMathTest.kt`, `docs/ui/group-history-join-card.md`, this report.

Edited: `core/messaging` (`GroupWireFrame`, `GroupFrameCodec`, `GroupSettings`, `GroupCanonical`, `GroupSigning`, `OutgoingSyncRequest`, `GroupLocalPreferences`, `SignedGroups`, `FlashChatRepository`, `FlashMessagingModels`, `RealFlashChatRepository`, and tests `GroupCanonicalTest`, `GroupFrameCodecTest`, `GroupSettingsTest`), `core/persistence` (`GroupSettingsEntity`, `FlashDatabase`, `FlashSchemaSteps`, `FlashMigrations`, `FlashMigrationsChainTest`, `RetentionPolicyTest`), `ui/chat` (`FlashGroupSettingsSheet`, `FlashGroupSyncBanner`, `FlashConversationScreen`), `app/.../MainActivity.kt`, `app/.../debug/DiscoveryEngineHolder.kt`, `core/engine/.../Flash.kt`, `desktop/.../DesktopEngine.kt`, `desktop/.../DesktopShell.kt`, `docs/protocol.md`, `docs/ui/ui-research-index.md`.
