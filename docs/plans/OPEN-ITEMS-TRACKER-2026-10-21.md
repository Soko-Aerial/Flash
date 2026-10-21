# Open items tracker — cancel/transfer audit + chat/Hub review (2026-10-21)

Owner instruction (2026-10-21): "add the accept and decline [item 6]; explain item 14; do your recommendation of the rest; note
them all in a file so you won't forget and implement all of them one by one, updating the file; also implement the deferred ones."

A new AI: read this file first when resuming. Update the **Status** of an item the moment it changes. Never delete an item (§27).

## Where the work lives

| Stream | Checkout | Branch | Commit policy |
|---|---|---|---|
| **C** cancel / transfer items | `E:/FlashWork/A-cancel` | `agent/A-cancel` — **MERGED into `dev` 2026-10-21** (`4a6c8dc7`); the worktree can be removed | (was: focused commits on that branch) |
| **H** chat / chat list / Hub items | `C:\Users\KaliOxygen\Downloads\Flash` | `dev` — **committed 2026-10-21** (`1d604a89`, together with the Hub redesign files) | owner asked for the commit |

The two checkouts overlap in 4 files (`RealFlashChatRepository.kt`, `TEST-BACKLOG.md`, `handoff.md`, `progress.md`), so `agent/A-cancel` cannot be merged
into the dirty `dev`. All Gradle goes through `tools\flash-gradle.cmd` (AGENTS §36). Do not touch the phone.

Status values: `TODO`, `IN PROGRESS`, `DONE` (built + unit-tested, **never** "device-verified"), `NO CHANGE` (decision: keep), `DEVICE` (needs a phone).

## Stream C — cancel / transfer (`agent/A-cancel`)

| # | ID | Item | Decision | Status |
|---|---|---|---|---|
| 1 | C-01 | Android dataSync timeout pauses (not cancels) a 1:1 transfer (ADR-219) | keep | NO CHANGE |
| 2 | C-02 | Auto-resume of a 1:1 row that was "paused by system" when the app/service returns | build it (owner: implement deferred) | DONE `ae91f59b` (unit-tested, not device-verified) |
| 3 | C-03 | A cancelled send is final (no retry from the bubble) | keep | NO CHANGE |
| 4 | C-04 | No Cancel button in a group-file origin's notification | keep | NO CHANGE |
| 5 | C-05 | History retention: Failed/Cancelled stay until dismissed, auto-clear after 30 days | owner accepted recommendation | DONE `3df0bfa5` (unit-tested; Android store only, desktop `store = null`) |
| 6 | C-06 | Desktop toasts with **Accept / Decline** (and Cancel/Retry where they apply), not reason-only | owner: add them | DONE (commit 2225fe3c, built + unit-tested, not device-verified) |
| 7 | C-07 | B-08: bound the hub retry at 24 h → row becomes Failed with a reason, and Retry works for hub rows | accepted | DONE (commit 91b0c69f, ADR-220, unit-tested) |
| 8 | C-08 | B-06 remainder: failed 1:1 receive with no session must not sit at "Downloading" after Retry (bounded wait + message) | accepted | DONE (agent/A-cancel, unit-tested) |
| 9 | C-09 | B-11/F-01: persist transfer identity, restore rows after process death (list query, restore on start) | accepted | DONE `3df0bfa5` (Room step 20; unit-tested, not device-verified; desktop not enabled, D5 pending) |
| 10 | C-10 | B-13/F-02: dismiss a Failed/Cancelled row, "Clear failed" (per session first, persisted with C-09) | accepted | DONE incl. persistence (`dismissed` column, `3df0bfa5`) |
| 11 | C-11 | B-15: receiver refusal reaches the sender (new reject frame; ADR + `docs/protocol.md`) | owner: implement deferred | DONE `3e828b8b` (ADR-221; reason on the `cancel` frame; refusing device shows no Failed row) |
| 12 | C-12 | B-19: group cancel carries a reason ("Cancelled by Ada" / "Deleted by Ada") | accepted | DONE (commit 57af1ad8) |
| 13 | C-13 | Reword 1:1 "Cancel for everyone?" → "Cancel and tell the other device?" | accepted | DONE (commit 57af1ad8) |
| 14 | C-14 | B-17 and B-18 first half: explained, nothing to decide (see below) | explanation only | DONE |

### Item 14 explained (why no decision is needed)

- **B-17** claimed: if a cancel lands in the window between the hub upload finishing and the local row being marked uploaded, `markUploaded`
  returns 0, the ticket/release is skipped, and the sealed ciphertext stays on the hub until the 7-day sweeper. I traced it in
  `HubFileBinding`: `cancelUpload` takes the *same* per-file lock (`u:$fileId`) as `upload`, so the two cannot interleave, and
  `HubFileBinding.cancel` issues the hub `DELETE` for every row it moves to Cancelled. A completed object therefore cannot be orphaned.
  I could not build a sequence that reproduces it, so changing the code would be guessing. Cost of being wrong: at worst one object lingers
  7 days (the hub's sweeper removes it). If the owner wants belt and braces, a one-line idempotent `DELETE` when `markUploaded == 0` is safe;
  recorded as optional, not scheduled.
- **B-18 first half** claimed: cancelling a transfer row that no longer exists is a silent local no-op while the peer keeps sending. A row that
  does not exist has no peer id and no session to send a cancel frame on, so there is nothing more to *do* than record the attempt; I now log
  a warning so it is diagnosable. The second half (desktop `previousTransferStates` never pruned) was fixed under B-14.

## Stream H — chat / chat list / Hub (`dev`, uncommitted)

| # | ID | Item | Decision | Status |
|---|---|---|---|---|
| 15 | H-15 | Hub segment underline possibly 8 dp off (`positionInParent` vs padding) | device check `HUBFIX-09`; cannot be settled by reading | DEVICE |
| 16 | H-16 | Two-pane: opening a colleague from the Hub must not replace the Hub with the chat list; Back returns to the Hub unchanged | accepted | DONE |
| 17 | H-17 | Multi-select must work on attachment messages (taps select instead of opening the viewer) | accepted | DONE |
| 18 | H-18 | An undownloaded voice note must be downloadable | accepted | DONE |
| 19 | H-19 | Swipe-to-reply must not recompose the whole bubble per drag frame | accepted (read the code, defer only the measurement) | DONE |
| 20 | H-20 | Tapping the same search hit twice must jump both times | accepted | DONE |
| 21 | H-21 | `FlashMessageActionsSheet` dead code: mark OBSOLETE (do not delete, §27) | accepted | DONE |
| 22 | H-22 | Ultra-low bubble: rectangular border and ripple spill | accepted | DONE |
| 23 | H-23 | Shared-content Audio "Play" with no local file: disabled with a download hint | accepted | DONE |
| 24 | H-24 | Caption + attachment sent as two messages | product decision: keep | NO CHANGE |
| 25 | H-25 | Piece map LOW tier reuses the ULTRA_LOW text view | accepted | DONE |
| 26 | H-26 | Unproven claims: read-marking timer, desktop `tipSeen` reset, optimistic-reaction race, rejected send clearing the draft, search counter "12 / 2" — read the code, report which are real, fix the real ones | accepted | DONE (see log for verdicts) |

## Process items

| # | Item | Decision | Status |
|---|---|---|---|
| 27 | The other agent's Hub files stay CRLF | leave | NO CHANGE |
| 28 | Commits: stream C commits on `agent/A-cancel`; stream H stays uncommitted (listed hunks in `logs/handoff.md`) | accepted | ongoing |
| 29 | Device test order if time is short: `CNCL-01`, `CNCL-02`, `CNCL-04`, `CHATFIX-01`, `CHATFIX-02`, `HUBFIX-01`, `HUBFIX-03` | info | — |

## Implementation log (newest first)
- 2026-10-21 C-09 + C-05 + C-10 persistence DONE (`3df0bfa5`, agent/A-cancel): Room schema step 20 (identity columns on `transfers`), write-behind identity save and restore-at-start in `RealFlashTransferRepository`, running rows return Failed ("Flash was closed while this was transferring"), 30-day retention, dismissals stored, `restored` rows are not announced. Desktop keeps `store = null` (D5). Main checkout is still at schema 19: renumber on merge.
- 2026-10-21 C-11 DONE (`3e828b8b`, ADR-221): refusal reason (`session_full` / `invalid_file_start` / `write_failed`) rides on `cancel`; the sender's row fails with the receiver's sentence. Not built: a Failed row on the refusing device.
- 2026-10-21 C-02 DONE (`ae91f59b`): `resumeSystemPaused()` called when the foreground service starts.

- 2026-10-21 C-07 DONE (agent/A-cancel 91b0c69f, ADR-220): upload older than 24 h that is interrupted -> Failed `UPLOAD_WINDOW_ENDED`; download past ticket expiry -> Failed (not found); Retry on a Failed hub row resumes it and puts the chat message back to waiting (`outgoingRetried`); `canRetry` true for Failed hub rows. Tests in `HubFileBindingTest` (two C-07 tests; the fake server clock now follows real time).
- 2026-10-21 C-08 DONE (agent/A-cancel): a retried Failed 1:1 receive that sees no bytes in 30 s returns to Failed with `SENDER_DID_NOT_RESUME`.
- 2026-10-21 C-10 DONE per session (agent/A-cancel): `dismissTransfer` / `clearFailedHistory`, Dismiss icon on Failed/Cancelled rows, "Clear" in the section header. Not persisted until C-09.
- 2026-10-21 C-06 DONE (agent/A-cancel 2225fe3c): `DesktopTransferToastModel` + always-on-top toast window with Accept/Decline (offers) and Retry/Dismiss (failed sends), only while the app is not in front.

_(each finished item appends one line here: date, item, what changed, tests run)_

- 2026-10-21 C-12/C-13: PublishRow.cancelReason, receivers' Cancelled row says "Cancelled by/Deleted by <name>"; `FlashCancelCopy` dialog wording; desktop transfer mapper aligned. `:core:swarm` SwarmCancelLifecycleTest, compiles of engine/ui/desktop/app green. Commit 57af1ad8 on agent/A-cancel.
- 2026-10-21 H-16..H-25: Hub stays the list pane when a chat opens from it in two-pane (`FlashNavigationMath.conversationOpenedFromHub`, both hosts; Back/Esc return to the Hub; Android segment kept via prefs, the Hub's search selection/scroll resets). Multi-select works on attachments; undownloaded voice note downloads; swipe badge animates in graphicsLayer; same search hit twice re-jumps (targetNonce); actions sheet marked OBSOLETE; ultra-low bubble shape; shared Audio Download button; piece map text view for minimal chrome. `:ui:chat:jvmTest` (692 tests), `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin` green. Not committed (stream H).
- 2026-10-21 H-26 verdicts: (1) read-marking timer: by design, a re-arm only delays marking by <= 1 s, NOT a defect. (2) desktop tipSeen reset: NOT real, `tipSeen` re-reads the persisted `DesktopHubUiPrefs`. (3) optimistic-reaction race: REAL, two quick taps both read "not reacted"; `toggleReaction` now read-modify-writes under `reactionToggleLock`. (4) search counter "12 / 2": REAL (stale index after the hits shrink); `counterLabel`/`stepIndex` clamp, test added. (5) rejected send clearing the draft: composer already caps at MAX_MESSAGE_TEXT_LENGTH, the removed-from-group case swaps the composer for a notice; no repro by reading, left as is.

## Recommendations for later (written 2026-10-21 at the owner's request; nothing here is scheduled)

Each line: the problem, what I recommend, and what would make me change my mind. Pick these up after the device checks
(`CNCL-*`, `CHATFIX-*`, `HUBFIX-*`) tell us what is real.

| ID | Problem / state | Recommendation for later | Revisit when |
|---|---|---|---|
| C-01 | dataSync timeout *pauses* a 1:1 transfer (kept). C-02 now resumes it when the service returns. | Keep pause + auto-resume. Run `CNCL-02`/`CNCL-15` first: if Android does not restart the service on its own, add a "Resume" action to the "Paused by Android" notification. | `CNCL-15` shows the row stays paused for hours |
| C-03 | A cancelled send is final; the bubble offers no retry. | Keep. If people re-send often, add an explicit "Send again" that makes a **new** transfer id, never revive the cancelled one (that was B-01). | owner reports confusion about "how do I send it again" |
| C-04 | No Cancel in a group-file origin's notification (cancelling there would delete it for everyone). | Keep. If wanted, add "Stop sharing" with a confirm step, only from the bubble. | owner asks for it |
| H-15 | Hub segment underline may be 8 dp off. | Device check `HUBFIX-09` first. If off, measure with the label's own `onGloballyPositioned` rect instead of `positionInParent`. | `HUBFIX-09` fails |
| H-24 | Caption + attachment go out as two messages. | Keep. Sending them as one message needs a wire/storage change (caption on the attachment message) and an ADR; not worth it until groups of images with captions are a real complaint. | product asks for it |
| B-17 | Not reproducible; worst case an object lingers on the hub up to 7 days. | Optional belt and braces: when `markUploaded` returns 0, send one idempotent hub DELETE. About 5 lines in `HubFileBinding.upload`, plus a test with a fake server. | the hub's storage stats show orphaned files |
| B-18 | Cancelling a vanished row only logs a warning. | Nothing more to do. If it shows up in logs often, find who calls `cancelTransfer` with a stale id. | log line `cancel of unknown transfer` is frequent |
| C-09 | **Desktop does not restore transfers** (`store = null`; D5 pending). | After the owner answers D5: add a JVM `TransferStore` adapter over the existing Room JVM database (the shared `FlashSchemaSteps` step 20 already has the columns), pass it in `DesktopEngine`, then decide whether desktop also resumes across restarts (it would). Add a `CNCL-19` desktop variant. | owner decides D5 |
| C-11 | The device that **refuses** a file shows no Failed row. | Extend `ReceiveEvent.Rejected` with name/size/peer so the router can create a Failed row ("You could not receive X: not enough space"). Needs a small event change in `FlashInboundRouter` + one test per refusal reason. | owner wants the receiver to see what it refused |
| C-09 | Room schema is now 20 on `dev`. Any other branch still holding its own step 20 needs renumbering. | Before merging another branch run `git diff dev -- core/persistence` and renumber (`FlashSchemaSteps`, `FlashMigrations`, `FlashMigrationsChainTest`, regenerate `schemas/*/N.json`). `agent/search-upgrades` adds no schema step (ADR-218), so it is safe. ADR numbers 218 (search) and 219-221 (cancel) are taken. | before each merge |
| Tests | Timing flakes under load, pass alone: `ExternalSourcesAndInterceptorTest` (two tests), `RealFlashTransferRepositoryTest` "B-01 resume refuses a cancelled or declined row...". | Replace the `Thread.sleep`/real-clock waits with an injected `TimeSource` and a channel the test awaits (the `TransferRestoreTest` `await` helper is a start). Do not raise timeouts. | the flakes fail a CI run |
| H-19 | Swipe-to-reply recomposition fixed by reading the code only. | Measure once on a mid-range phone (frame times with a long image thread). Only add more work if the numbers are bad. | `CHATFIX-12` stutters |
| H-26 | Optimistic reaction race and search counter fixed by code; the read-marking timer and desktop `tipSeen` were judged not defects. | If `CHATFIX-15`/`16` pass, nothing more. If read-marking is reported late, instrument it with a probe before changing the 1 s re-arm. | a device test fails |
| D-ops | Device order if time is short (item 29). | `CNCL-01`, `CNCL-02`, `CNCL-04`, `CNCL-19`, `CHATFIX-01`, `CHATFIX-02`, `HUBFIX-01`, `HUBFIX-03`. | — |
| Cleanup | The `E:/FlashWork/A-cancel` worktree and `agent/A-cancel` branch are merged. | Remove the worktree (`git worktree remove E:/FlashWork/A-cancel`) and keep the branch until the owner confirms the device checks. | after `CNCL-*` pass |
