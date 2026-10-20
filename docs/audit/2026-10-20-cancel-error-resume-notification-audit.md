# Cancel, exit, error and resume — correlation with notifications (audit, 2026-10-20)

**Status:** audit only. Nothing in this document has been fixed, and **nothing in it has been reproduced on a device**.
Every claim below is static evidence from the source tree at branch `agent/A-cancel` (based on `dev` @ `51469a84`), with
`file:line` pointers so the next person can check each one in a minute. Where a claim depends on runtime timing it says so.

**Question answered:** for every way a transfer, call, PTT session, pairing or file can be cancelled, declined, paused,
resumed or fail — what actually happens, what the peer is told, what the notification does, and what the user reads.
The output is defects (B-*), improvements (I-*), features the flow needs (F-*) and owed device tests (`CNCL-*`, collected in
`docs/testing/TEST-BACKLOG.md`).

---

## 0. Method and coverage

Read end to end (not grepped only):

| Area | Files |
|---|---|
| 1:1 transfer engine | `core/transfer/.../RealFlashTransferRepository.kt`, `FlashTransferRepository.kt`, `TransferFailureText.kt`, `store/TransferStore.kt`, `chunked/ReceivePipeline.kt` |
| Android host | `app/.../debug/DiscoveryEngineHolder.kt` (accept/decline/cleanup/control/offer), `debug/FlashBackgroundService.kt`, `debug/TimeoutStopPlan.kt`, `TransfersUiMapper.kt`, `MainActivity.kt` (notification & intent entry) |
| Notifications | `app/.../notifications/FlashNotificationManager.kt`, `FlashTransferActionReceiver.kt`, `app/.../calling/FlashCallActionReceiver.kt`, `calling/FlashCallService.kt`, `ptt/PttSessionService.kt` |
| Swarm | `core/swarm/.../engine/ContentLifecycleHandler.kt`, `engine/SwarmEngine.kt`, `driver/SwarmDriver.kt`, `core/engine/.../swarm/SwarmHostBinding.kt` |
| Hub files | `core/engine/.../hubfiles/HubFileBinding.kt`, `HubFileRows.kt` |
| UI | `ui/chat/.../transfers/FlashTransfersScreen.kt`, `ui/chat/.../chat/FlashConversationScreen.kt`, `FlashFileMessageCard.kt`, `FlashSwarmUiMath.kt` |
| Desktop | `desktop/.../DesktopNotificationManager.kt`, `DesktopEngine.kt`, `DesktopShell.kt`, `EngineProxies.kt` |

Not covered here (separate subsystem, separate plan): radio/Bluetooth link state (`TncLinkState`, ADR-214), hub *relay* call
teardown, server-side cancel. Group **membership** cancellation (join request withdrawn) is mentioned only where it touches a
notification. Screen share is out of scope.

---

## 1. How the pieces fit (the map the rest of the document uses)

```
                        producers of "it stopped"
  UI button / notification action / peer XFER frame / Android timeout / session close / chat delete
                    │
                    ▼
        FlashTransferRepository  (1:1)          ExternalTransferControl (swarm, hub)
        ├─ cancelTransfer / pauseTransfer        ├─ SwarmHostBinding  → SwarmDriver.cancelAsOrigin / cancelLocal
        ├─ resumeTransfer / relaunchSend          └─ HubFileBinding   → hub DELETE / chunk state on disk
        ├─ onRemoteTransferControl(PAUSE|RESUME|CANCEL)
        └─ onIncomingFailed / admitIncoming / declineIncoming
                    │
                    ▼
              _activeTransfers : StateFlow<(FlashTransfer)>
                    │
       ┌────────────┼─────────────────────────────┬──────────────────────────┐
       ▼            ▼                             ▼                          ▼
 FlashBackground  TransfersUiMapper          chat bubble            DesktopEngine →
 Service          → FlashTransfersScreen     (FlashAttachment-      DesktopNotificationManager
 (per-state                          Progress → FlashFile-         (Completed / Failed only)
  notification)                     TransferStatus)
```

Three facts drive most of the defects:

1. **The transfer row is the only coordination point.** The notification layer, the Transfers screen and the chat bubble
   each translate `FlashTransfer.state` independently, and they disagree about `Cancelled` (see B-05, B-12, B-13, B-19).
2. **`FlashTransfer` carries no "can this be retried" flag.** The screen computes one (`TransfersUiMapper.kt:53`); the
   notification cannot, so it always offers Retry (`FlashNotificationManager.kt:345-349`). B-06 is that divergence.
3. **`_activeTransfers` is in memory only.** Nothing reads the persisted rows back (`TransferDao` has no list query and no
   delete; see B-11), so "cancel" is permanent within the session *and* history is lost on every restart.

---

## 2. Trace tables

### 2.1 1:1 transfer

| User/system action | Code path | Wire effect | Peer's row | Notification | Text the user reads |
|---|---|---|---|---|---|
| Cancel from Transfers screen (Send) | confirm dialog `FlashTransfersScreen.kt:335-369` → `cancelTransfer` `RealFlashTransferRepository.kt:873` | `XFER CANCEL` (only if the row exists, `:895`) | `onRemoteTransferControl(CANCEL)` → Cancelled, raw reason "cancelled by receiver" | progress notification cleared (`FlashBackgroundService.kt:340-344`) | local row: Failed section, "Cancelled" (mapper default, `:44`); the peer's row shows the raw "cancelled by sender" |
| Cancel from Transfers screen (Receive) | no dialog (`FlashTransfersScreen.kt:315-321`) → `cancelTransfer` | `XFER CANCEL` + local incoming CANCEL | sender row Cancelled ("cancelled by receiver") | cleared | "Cancelled" locally; the sender reads the raw reason (B-12) |
| Cancel from the **progress notification** | `FlashTransferActionReceiver.kt:43-52` | as above | as above | cleared **before** the repo call | nothing if the repo call throws (B-10) |
| Pause / resume (either side) | `pauseTransfer` `:660`, `resumeTransfer` `:743`, peer `XFER PAUSE/RESUME` | PAUSE / RESUME | Paused / Transferring | "paused"/progress | raw "paused by receiver" |
| Decline an offer | `declineIncoming` `:1221` | local DECLINE + `XFER CANCEL` | sender Cancelled | offer notification cleared by the receiver | "declined" (raw) |
| Peer cancels on their side | `onRemoteTransferControl(CANCEL)` `:978` | — | — | cleared | raw "cancelled by sender" (we receive) / "cancelled by receiver" (we send) |
| Receiver refuses for space | `admitIncoming` `:1196` | DECLINE + CANCEL | Cancelled | offer cleared | `notEnoughSpace(...)` sentence (good) |
| Receive fails | `onIncomingFailed` `:1418` | — | — | "Download failed" + **Retry** | friendly sentence (good) |
| Integrity mismatch | `onIncomingIntegrityFailed` `:1397` | — | — | "Download failed" + Retry | friendly sentence (good) |
| Android 15+ FGS timeout | `FlashBackgroundService.onTimeout` `:151` → `TimeoutStopPlan` | 1:1: **CANCEL**; swarm/hub: pause | row Cancelled, chunks forgotten | cleared | raw "…" / nothing; **no resume possible** (B-02) |
| Transport drop | `failInboundForPeer` `DiscoveryEngineHolder.kt:1737` → `cleanupInbound` `:1726` | — | Failed | "Transfer failed" | friendly "connection lost" |
| Retry a Failed row | Retry button → `resumeTransfer` | send: relaunch; receive: RESUME only | — | progress re-posted | "Downloading" even with no session (B-06) |
| Tap a **Cancelled** chat attachment | `FlashConversationScreen.kt:1019-1023` → `resumeTransfer` | RESUME to the peer | **relaunched send** (B-01) | progress | "Failed: … double-tap to retry" (B-05) |

### 2.2 Group swarm

| Action | Path | Effect | Text |
|---|---|---|---|
| Bubble Cancel (mine, confirmed) | `FlashConversationScreen.kt:1367-1403` → external `cancel` → `driver.cancelAsOrigin` (`SwarmHostBinding.kt:168-175`) | signed tombstone: **cancelled for every member** | dialog "Cancel for everyone?" — correct here |
| Transfers screen Cancel (Send) | confirm dialog (always) → same → tombstone | same | dialog says "for everyone" even for a 1:1 send (B-04 wording) |
| **Notification** Cancel on a sending swarm row | `FlashNotificationManager.kt:239-240, 251-255` | tombstone, **no confirmation** | button says just "Cancel" when `canGoOffline == false` (B-04) |
| Receiver cancels | `cancelLocal` | local stop only, no tombstone | — |
| Android timeout | `pauseForSystem` → `SwarmEvent.SystemSuspend` (`SwarmDriver.kt:888`) | suspended, no tombstone | "Paused by Android; continues automatically." (`SYSTEM_TIMEOUT`) — good |
| Tombstone arrives | `SwarmEngine.applyTombstone` / `ContentLifecycleHandler` | row Cancelled | **no reason kept** (`SwarmDriver.kt:705, 719-721`): cancelled / deleted-for-everyone / expired look identical (B-19) |
| Delete the message for everyone | `onHubFileMessageDeleted` → `cancelOutgoing`; swarm `cancelAsOrigin(DELETED)` | tombstone | — |

### 2.3 Hub file

| Action | Path | Effect | Text |
|---|---|---|---|
| Pause / resume | `HubFileBinding.kt:155-163` | `paused` column + stop / `launchRun` | Paused / Transferring / Queued |
| Cancel | `HubFileBinding.cancel` `:527` | hub `DELETE` + local terminal | — |
| Android timeout | `pauseForSystem` → `stop` `:168` | resumes from disk at next start | — |
| Transient failure | `runUntilSettled` `:363-392` | retries with backoff capped at `maxRetryDelayMs` **for ever** (B-08) | last reason as the error text |
| Permanent failure | `failOutgoing` → `FAILED` | Failed, `retryable = false` | reason sentence |
| **Retry** on a Failed hub row (notification) | `resume` `:160-163` → `runUntilSettled` → state FAILED → `else -> false` | **nothing** (B-06) | the button stays, spinning nowhere |

### 2.4 Calls, PTT, pairing

| Action | Path | Effect when the engine is alive | Effect when it is not |
|---|---|---|---|
| Decline / hang up from the call notification | `FlashCallActionReceiver.kt:38-56` | `calling.decline()/hangUp()`; `FlashCallService` clears on `activeCall == null` | `Log.w` + **return**: notification stays for ever (B-09) |
| Answer | `FlashCallActionReceiver.kt:36` | brings `MainActivity` to front (correct: permissions live there) | same |
| PTT stop | `PttSessionActionReceiver` → engine → `render(Idle)` → `stopSelf`; `onDestroy` also cancels the notification | fine | `stopSelf` without cancelling a previously posted notification (`PttSessionService.kt:76-81`) — minor |
| Pairing request notification | `DiscoveryEngineHolder.kt:2010-2020` collector → `showPairingRequest`/`clearPairingRequest` | cleared when the phase leaves `RequestReceived`, on `MainActivity` start (`:344-345`) | — |

---

## 3. Confirmed defects

Severity: **S1** destructive or consent-breaking · **S2** user is stuck or a control does nothing · **S3** wrong words
or an inconsistent surface · **S4** robustness/cost.

### B-01 — S1 — Tapping a cancelled (or declined) attachment re-sends / re-downloads the file

**Evidence.**
- The chat bubble collapses `Cancelled` into "Failed" on **every** host: `DiscoveryEngineHolder.kt:1375-1377`,
  `core/engine/src/androidMain/.../Flash.kt:559-561`, `DesktopEngine.kt` (`attachmentProgress`), and the enum has no
  Cancelled member at all (`FlashMessagingModels.kt`, `FlashFileTransferStatus`).
- The bubble therefore advertises "Failed: … Double-tap to retry" (`FlashFileMessageCard.kt:153-166, 317`) and its tap
  calls resume (`FlashConversationScreen.kt:1019-1023`).
- `resumeTransfer` treats **Cancelled as retryable for a send** and relaunches the whole transfer:
  `RealFlashTransferRepository.kt:770-773`, with the comment at `:768` spelling it out ("a Failed, **Cancelled**, or
  stalled transfer being retried"). For a receive it flips the row to Transferring and emits RESUME (`:789-801`).
- The mapper comment that was supposed to prevent this says the opposite and is false:
  `TransfersUiMapper.kt:48-50` ("cannot be resumed: … resumeTransfer returns without doing anything").

**What the user sees.** They cancel a send (wrong recipient, wrong file, or after the peer said no), later tap the bubble
out of curiosity, and the file goes out again. There is no confirmation and no way to tell it happened.

**For a cancelled receive** the chain is worse: RESUME reaches a sender whose row may be Completed or Cancelled, and
`onRemoteTransferControl` has **no terminal-state guard** (B-03), so the sender relaunches, the receiver's
`isResumableInboundRetry` now answers true (the row was just flipped to Transferring, `FlashTransferRepository.kt:149-156`),
and the sink is resolved automatically (`DiscoveryEngineHolder.kt:2627-2643`) — the bytes land without a second prompt.

**Which "declined" case is reachable.** A *receiver's* declined offer never mints a chat bubble (the bubble is created in
`acceptOffer` only, `DiscoveryEngineHolder.kt:1783-1791`), so that path is closed by accident. The reachable decline is the
**sender's** row: the receiver declines, the sender's row becomes Cancelled with the raw reason `"declined"` (`:1238`), and the
sender's own bubble now says "Failed … retry" — tapping it re-sends the file to the peer that just refused it.

**Smallest fix.** Treat `Cancelled` as terminal in `resumeTransfer` (return a typed failure and keep the row as it is), and
add a `Cancelled` member to `FlashFileTransferStatus` so the bubble can say "Cancelled" and offer no retry. Delete the false
comment. If a user really needs to re-send a cancelled file, that is a new send from the composer, not a resume.

**Test that would catch it.** `resumeTransfer` on a Cancelled send must not start a dispatcher and must not emit RESUME;
the bubble for a cancelled attachment must not expose `onResumeTransfer`.

---

### B-02 — S1 — Android's foreground-service timeout **cancels** a 1:1 transfer while swarm and hub pause

**Evidence.** `FlashBackgroundService.onTimeout` (`:151-165`) → `TimeoutStopPlan.of(...)` (`TimeoutStopPlan.kt`) splits
`Transferring` rows into `toCancel` (1:1) and `toPause` (external rows, i.e. swarm **and hub**). The comment in
`TimeoutStopPlan` says it "preserv[es] existing 1:1 cancel behavior"; the hub phase notes say the hub path "only pauses"
(AGENTS §29, ADR-216), and the swarm path deliberately suspends without a tombstone.

**Why it matters.** On API 35+ a `dataSync` foreground service has a six-hour ceiling. A 40 GB transfer over a slow link is
exactly the case where that fires. The user's transfer becomes `Cancelled` and `forgetChunks` throws away the resume state
(`:892`), so even the persisted chunk rows are purged. Meanwhile the app *has* the right sentence for this case —
`TransferFailureText.SYSTEM_TIMEOUT` = "Paused by Android; continues automatically." (`TransferFailureText.kt:58-59`) —
which cannot ever describe a 1:1 transfer, because the 1:1 path never pauses.

**Fix.** Make `toCancel` empty: every `Transferring` row goes through `pauseForSystem`, with the reason stored on the row so
the UI can say "Paused by Android; continues automatically." and resume from the persisted done-set (which already exists
for 1:1: `TransferStore.doneChunks`). This aligns 1:1 with hub and swarm and with AGENTS §18/§21. If a 1:1 transfer cannot
survive the service stopping (needs a check), that is a *finding* to record, not a reason to destroy it silently.

**Test.** `TimeoutStopPlanTest` already exists; invert its 1:1 expectation and add the end-to-end case in `CNCL-02`.

---

### B-03 — S2 — `onRemoteTransferControl` has no terminal-state guard: a late PAUSE/CANCEL/RESUME rewrites a finished transfer

**Evidence.** `RealFlashTransferRepository.kt:908-1010`. Nothing checks the current state:
- `ACTION_PAUSE` on a **Completed** row → `Paused` + `errorMessage = "paused by receiver"` (`:912-928`).
- `ACTION_CANCEL` on a Completed row → `Cancelled` (`:978-1007`).
- `ACTION_RESUME` on a **Cancelled** row → `relaunchSend` (`:959-966`) — the send is resurrected; on a Completed row
  likewise.

The same class of guard exists on the neighbouring paths (`declineIncoming`/`acceptIncoming` require `Offered`, `:1173,
:1230`; `onIncomingFailed` refuses to clobber Cancelled/Completed, `:1421-1436`), so this is an omission, not a decision.

**What the user sees.** A finished transfer flips to "Paused" (or disappears from History into the Failed section) because
the peer's pause landed a moment late; or a file the peer asked to resume goes out again.

**Fix.** One guard at the top of `onRemoteTransferControl`: ignore PAUSE/CANCEL for terminal states; allow RESUME only from
`Paused`, `Failed`, `Queued`, `Transferring` (never `Completed`, never `Cancelled`).

---

### B-04 — S1/S3 — The destructive group cancel is confirmed where it is harmless and unconfirmed where it is destructive

**Evidence.** "Cancel for everyone" and the tombstone are decided by **direction**, not by whether the file is shared:
- `FlashTransfersScreen.kt:315-321` confirms **every** Send cancel with "Cancel for everyone?" / "Members who already
  have the file keep it." — including plain 1:1 sends, where no group exists.
- `FlashConversationScreen.kt:1056-1062, 1367-1403` does the same for every own attachment.
- The notification goes the other way: `FlashNotificationManager.kt:239-240` labels the action "Cancel for everyone" only
  when `canGoOffline` is true, otherwise plain "Cancel" — but `SwarmHostBinding.kt:168-175` calls `cancelAsOrigin` for
  **any** sending swarm row, whatever the label. So on a group file that is still uploading (the common case), the
  notification offers "Cancel" and performs a signed tombstone that wipes it for the whole group.

**Fix.** Decide from `canGoOffline` / group membership, not from `direction`: 1:1 cancel = plain "Cancel" (confirm only if
it is lossy); group origin cancel = always "Cancel for everyone" **with** a confirmation (a confirm activity from a
notification action is acceptable; Android supports `PendingIntent.getActivity` to a dialog, or a `BroadcastReceiver` +
`setDeleteIntent` style flow).

---

### B-05 — S2 — The chat bubble has no vocabulary for Cancelled/Declined; the Transfers screen has no bucket for them

**Evidence.** `FlashFileTransferStatus` = `NotDownloaded | AwaitingAcceptance | Transferring | Paused | Downloaded |
Failed`. All three hosts map `Cancelled → Failed` (`DiscoveryEngineHolder.kt:1375-1377`, `Flash.kt:559-561`,
`DesktopEngine.kt`). The Transfers screen has no Cancelled section either: `TransfersUiMapper.kt:38-39` folds it into
"Failed" and compensates with `retryable = false` (`:53`) and `errorMessage ?: "Cancelled"` (`:44`).

**What the user sees.** A transfer they cancelled on purpose is reported as a failure ("Failed", error badge, red tint) and
a file the peer declined looks like a broken download.

**Fix.** Add `Cancelled` (and preferably `Declined`) to `FlashFileTransferStatus`, and a Cancelled section (or a muted
"Stopped" row style) to the Transfers screen. This also removes the reason `retryable` had to exist.

---

### B-06 — S2 — Dead Retry: hub failures advertise Retry in the notification and the button does nothing

**Evidence.** `showTransferFailed` unconditionally adds a Retry action (`FlashNotificationManager.kt:345-349`); the
repository says hub rows are not retryable (`TransfersUiMapper.kt:53`); and `HubFileBinding.resume` only does
`setPaused(false)` + `launchRun` (`:160-163`), whose `runUntilSettled` falls through `else -> false` for a `FAILED` row
(`:369-377`) — it returns immediately. The notification's Retry therefore clears nothing and changes nothing.

The same class of problem for 1:1: a Failed **receive** is marked retryable (`TransfersUiMapper.kt:53`), and its retry
turns the row into "Downloading" with no session; recovery depends entirely on the sender relaunching (B-03), which does
not happen if the sender is Completed or offline. There is no timeout on a `Transferring` row without a session, so the
row stays "Downloading" indefinitely.

**Fix.** Put the retryability decision on the model (`FlashTransfer.retryable`, set by the repository/host) and have the
notification read it; or at minimum drop the action for `viaHub` and for a Failed receive. For the 1:1 receive retry, either
perform the retry through a real re-offer request (a new wire frame) or revert the row to `Failed` with a sentence after a
bounded wait.

---

### B-07 — S2 — The stale foreground progress notification when a row leaves `Transferring` for `Queued`

**Evidence.** `FlashBackgroundService.handleTransfersChanged` (`:328-361`) handles `Completed`, `Failed`, `Cancelled`,
`Paused`, `Offered`, `Transferring`/`Verifying` and has `else -> Unit` for everything else — including `Queued`, which is
exactly the state a swarm/hub transfer enters when it is waiting for a peer (`relaunchSend` `:844-849` sets Queued; hub
rows are Queued whenever no run is active, `HubFileRows.kt:29-33`).

The notification was posted with `setOngoing(true)` (`FlashNotificationManager.kt:248`) and only `Cancelled`, `Completed`
and `Failed` replace it. So after `Transferring → Queued` the user keeps a permanent progress bar at the last percentage,
with a **Cancel** button that now cancels a queued transfer — a destructive control offered under false information.

There is no other cleanup: `onDestroy` clears maps only (`:126-141`), and the only way to get rid of it is to tap it
(`MainActivity.handleIncomingIntent(ACTION_VIEW_TRANSFERS)` calls `clearTransfer`, `:467-473`) or press Clear history,
which the screen hides when there is no completed row (`FlashTransfersScreen.kt`, history section).

**Fix.** Handle `Queued` (and `Offered`) by replacing the ongoing progress notification with a "Waiting for network / for
the sender" state, or clear it and let the Transfers tab be the place to watch a pending transfer.

---

### B-08 — S2 — A hub run that keeps being interrupted never fails, never explains and never stops retrying

**Evidence.** `HubFileBinding.runUntilSettled` (`:363-392`) loops for ever on `Interrupted`
(`HubUploadOutcome.Interrupted` → `dao.setProgress` + `dao.setErrorText` + `true`, `:427-431`), doubling the delay up to
`maxRetryDelayMs` (10 min). The row stays `UPLOADING`/`DOWNLOADING`, which the UI shows as Transferring/Queued — never
Failed — and `retryable` is false anyway. The stored "last reason" only surfaces as the row's error text while Queued.

**What the user sees.** "Through the hub · Uploading" at 12 % for days. Pause/resume works; nothing tells them it is not
progressing.

**Fix.** Bound the loop (attempts or elapsed time) and on exhaustion move the row to `FAILED` with a sentence that names
the reason and offers a real Retry (which must then also work — see B-06).

---

### B-09 — S2 — Call notification can become permanently undismissable

**Evidence.** `FlashCallService` posts the notification unconditionally even when it cannot be promoted to foreground
(`:154-159`), optionally `setOngoing(true)` and with a full-screen intent during RINGING (`:262-273`) and **no delete
intent**. If the engine is gone, `onStartCommand` does `stopSelf()` without cancelling `NOTIFICATION_ID` (`:88-93`), and
`FlashCallActionReceiver` returns early on `calling == null` (`:24-28`) — so Decline and Hang up do nothing.

**What the user sees.** A ringing (or "Call in progress") notification that cannot be swiped away and whose buttons do
nothing, until the app is force-stopped or posts a new call state.

**Fix.** In every "no engine" exit path and in `onDestroy`, `notificationManager.cancel(NOTIFICATION_ID)`; add a
`setDeleteIntent` that ends the call (or at least clears the notification) so the user always has an exit.

---

### B-10 — S2 — Notification actions clear the notification *before* doing the work, and swallow their own failures

**Evidence.** `FlashTransferActionReceiver.kt`: `ACTION_CANCEL_TRANSFER` clears first then calls the repo inside
`runCatching { }.onFailure { Log.w }` (`:43-55`); accept and decline do the same (`:66-89`). A repository that throws, or a
`repo == null` after `ensureStarted` fails, leaves the user with no notification and no status — the transfer keeps
running (cancel) or the sender keeps waiting (accept).

Also: the receiver creates a `CoroutineScope(SupervisorJob())` field per instance and never cancels it (`:23`); a
BroadcastReceiver is short-lived, so leaked scopes add up over a session.

**Fix.** Perform the action, then clear on success; on failure keep the notification and update its text (or post a
"Couldn't cancel, tap to retry" state). Use `goAsync()` with a bounded timeout and cancel the scope.

---

### B-11 — S2 — Transfers are not restored after process death, and the persisted rows are write-only

**Evidence.** `TransferStore` has insert/progress/status/chunks only — no list query (`store/TransferStore.kt`).
`TransferDao` has `observe(transferId)`, `setBytesDone`, `setStatus` and **no** "all", "recent" or delete query
(`TransferDao.kt`), and `observe` is called from tests only. `RealFlashTransferRepository` only ever writes to the store
(`store?.insertTransfer`, `setStatus`, …), never reads it at start. `_activeTransfers` starts empty on every process start
(`DiscoveryEngineHolder.kt:1114`).

**Consequences.**
1. AGENTS §18 ("a transfer should be resumable after app restart / process restart") is not met for sends: the sender's
   row and its `sourceUri` are gone, and nothing re-offers. Only a receiver-initiated re-offer can restart anything.
2. §22's Transfers history is lost on every restart — the tab honestly says "No transfers yet" while `transfers` rows for
   completed files sit in the database.
3. Nothing prunes `transfers`/`transfer_chunk` rows, so the table grows for the life of the install.

**Fix (feature-sized).** Add `observeRecent(limit)` / `delete(id)` / `prune(before)` to `TransferDao`; on engine start, load
the unfinished rows, map them to `Paused` (or `Failed` with "Interrupted — tap to resume") and feed them into
`_activeTransfers`; resume sends on demand, not automatically. Prune finished rows with the existing retention machinery
(`RetentionPolicy` already mentions TransferDao in its KDoc but does not touch it).

---

### B-12 — S3 — Raw internal reasons are shown to users; the friendly sentence for a peer cancel is unreachable

**Evidence.** `onRemoteTransferControl` stores `"paused by receiver"`, `"paused by sender"`, `"cancelled by receiver"`,
`"cancelled by sender"` (`:926, :939, :990, :1003`); `declineIncoming` stores `"declined"` (`:1238`); `relaunchSend` /
`sendFile` store `"waiting for receiver to accept"` (`:422, :847`). The Transfers screen prints the message verbatim
(`FlashTransfersScreen.kt:243`) and so does the failed-transfer notification body
(`FlashNotificationManager.kt:774-782`). `TransferFailureText.friendly` — which knows how to turn these into sentences —
is applied only on the failure paths (`:599, :620, :1420`). And its `cancelled by peer → CANCELLED_BY_PEER` branch
(`TransferFailureText.kt:138`, "The other device cancelled the transfer.") is dead for 1:1, because no 1:1 path ever
produces that string.

Also `cancelTransfer` does not clear `errorMessage` (`:888-890`), so a cancelled row can show a stale "paused by receiver".

**Fix.** Make every user-visible message go through one function (pass the raw reason through `friendly`, and add the
missing cases: `cancelled by receiver → "Cancelled by <peer>"`, `declined → "Declined"`). Clear `errorMessage` on
`cancelTransfer`.

---

### B-13 — S3 — Failed and Cancelled rows can never be dismissed or cleared

**Evidence.** `clearFinishedHistory` only dismisses `Completed` rows (`RealFlashTransferRepository.kt:146-150`,
`dismissedFinishedIds`), and the screen offers Clear history only for the History section
(`FlashTransfersScreen.kt:276-370`). There is no per-row dismiss and no swipe.

**What the user sees.** Within one session, every failed or cancelled transfer accumulates in the Failed section with no
way to remove it; the only reset is restarting the app (which, per B-11, also loses everything else).

**Fix.** Let the Failed/Cancelled list be dismissible (per row and "Clear list"), and persist dismissals together with the
history fix in B-11.

---

### B-14 — S3 — Desktop notifications are missing the states where the user most needs them

**Evidence.** `DesktopNotificationManager.handleTransfersUpdate` (`:205-233`) notifies only `Completed` and `Failed`, and
the failure body is a generic `"Failed to transfer ${fileName}"` (`:225`) even though `transfer.errorMessage` is right
there (contrast `FlashNotificationManager.kt:774-782`, which uses it). There is no notification for `Offered` (an inbound
offer while the window is minimised is invisible), `Paused`, `Cancelled`, no progress, and no action buttons (no
cancel/pause/resume/accept). `previousTransferStates` never removes vanished ids (`:46, :207-209`), so a cleared transfer
keeps a stale entry for the session.

**Fix.** Mirror the Android set: Offered (with Accept/Decline), Paused, Cancelled, and the reason sentence on failure.
Windows toast actions are supported; if they are out of scope now, at least stop hiding the reason and the offer.

---

### B-15 — S2 — A 1:1 receiver's refusal is silent on the wire; the sender burns its timeout and reports the wrong cause

**Evidence.** The chunked protocol has no reject frame (only the swarm protocol has `SwarmFrame.Reject`,
`ServeHandler.kt:59`); the receiver's `ReceiveEvent.Rejected` is only logged
(`DiscoveryEngineHolder.kt:2698-2706`). `RejectReason` documents "back off and retry later" for `SESSION_FULL`
(`ReceivePipeline.kt:481-497`) but there is no path that tells the sender.

**What the user sees.** The sender sits at "Transferring" until the ACK-drain grace expires and then reads "the other
device isn't responding", when the truth was `SESSION_FULL`, `INVALID_FILE_START` or `SESSION_CONFLICT`. The receiver
records nothing at all.

**Fix.** Define one refusal frame (or a text control `XFER REFUSE reason=…`) carrying a `RejectReason`, map it to a
sentence on the sender, and create a Failed row on the receiver for the reasons a human can act on (full storage, too many
sessions).

---

### B-16 — S3 — Notification id collisions could cancel the wrong transfer

**Evidence.** `notificationIdForTransfer(id) = 3000 + ((id.hashCode() and 0x7FFFFFFF) % 1000)`
(`FlashNotificationManager.kt:724-725`), and the PendingIntent request codes are derived from the same number with
`FLAG_UPDATE_CURRENT` (`:235, :281-282, :332, :668-674`). Two live transfers whose ids collide share one notification: the
second overwrites the first's progress, and both Cancel actions carry the *last* built extras — i.e. pressing Cancel on one
notification can cancel the other file.

The range itself is fine (`clearAllTransfers` cancels 3000..3999, `:466-471`), and `clearConversation` uses 2000..2999, so
there is no cross-family collision.

**Fix.** Allocate ids from a monotonic counter with an id→notification map (and `0` for "evicted"), or include the
direction and a longer hash slice.

---

### B-17 — S3 — A cancel that races the hub upload completion leaves the ciphertext on the hub and the source handle open

**Evidence.** `HubFileBinding.upload`, `HubUploadOutcome.Done` branch (`:419-425`): `releaseUpload` and `queueTicket` run
only when `markUploaded(...) == 1`. When a cancel lands in that window, `markUploaded` returns 0 and both are skipped — so
the already-uploaded object stays on the hub until the 7-day sweeper, consuming the sender's quota, with no local row
pointing at it.

**Fix.** When `markUploaded` returns 0, call the hub `DELETE` for that `fileId` (the local `cancel` may already have run,
but the delete is idempotent) and release the source unconditionally.

---

### B-18 — S4 — `cancelTransfer` on an unknown row tells nobody, and `previousTransferStates` on desktop is never pruned

The first: `cancelTransfer` looks the row up and skips `emitOutgoing` when it is missing (`:878, :895`), so a cancel from
a stale notification is a silent local no-op while the peer keeps sending. The second is B-14's last sentence. Both are
small; listed so they are not rediscovered as mysteries.

### B-19 — S3 — A group cancel carries no reason: "someone cancelled", "the sender deleted it" and "it expired" read the same

**Evidence.** `SwarmTombstoneReason` is `USER` or `DELETED` (`core/swarm/model/SwarmTombstone.kt:6-16`) and the delete path
passes `DELETED` (`SwarmHostBinding.kt:205`), but the row mapping drops it: `errorMessage` is filled only for `FAILED`
(`SwarmDriver.kt:719-721`), and `Cancelled` is mapped with no reason at all (`:705`). The Transfers screen then shows the
mapper's generic default (`TransfersUiMapper.kt:44`), and the bubble shows "Failed".

`TransferFailureText.cancelledBy(senderName)` ("Cancelled by Ada") and `deletedBy(senderName)` ("Deleted by Ada") already
exist (`:102-108`) and have **no production caller at all** — only `TransferFailureTextTest` asserts them
(`TransferFailureTextTest.kt:33-34`).

**Fix.** Carry the tombstone reason (and the origin's name, which the bubble knows) into the transfer row and use the two
sentences that were written for it. Expiry is a third reason and deserves its own line.

---

## 4. Improvements (not defects, but the flow is worse without them)

- **I-01 — One state → one sentence.** Today three surfaces phrase the same state three ways (notification, Transfers,
  bubble). A single pure function (`state + direction + reason + viaHub → title/body/action set`) reused by all of them
  would have made B-04, B-05, B-12 and B-14 impossible.
- **I-02 — One retryability predicate on the model.** See B-06. `retryable` currently exists only in the app mapper, so
  every other surface guesses.
- **I-03 — A confirm step for destructive notification actions.** Android supports it (`PendingIntent.getActivity` to a
  small transparent activity). Group cancel is the case that needs it (B-04).
- **I-04 — Say who cancelled, using a name, not a role.** "Cancelled by Ada", "Declined by Ada" — the repository knows
  `peerName`; `TransferFailureText.cancelledBy(senderName)` already exists and has no production caller (`:102-104`).
- **I-05 — Bound every wait.** A `Transferring` row with no session and no progress for N seconds should become Failed with
  "Waiting for the other device" (or Queued with a wait reason). This covers B-03's stuck case, B-08 and the "Downloading
  for ever" rows generally.
- **I-06 — Record telemetry for the transitions** (`TRANSFER`, `PROBE` tags): who cancelled (local/peer/system), which
  reason, whether resume state survived. The audit found the states; the device tests (`CNCL-*`) will need these lines.
- **I-07 — Make the timeout path use the sentence that already exists.** `SYSTEM_TIMEOUT` was written for exactly the FGS
  timeout case and, after B-02's fix, becomes reachable.
- **I-08 — Kill the never-cancelled scopes in `FlashTransferActionReceiver` / `FlashCallActionReceiver`** (B-10).

---

## 5. Features the flow needs

- **F-01 — A real transfer history (persist, list, resume offered, prune).** The blocker is B-11. Without it, "Cancel" and
  "Retry" have no meaning past the current process, and §18/§22 cannot be claimed.
- **F-02 — Dismiss and clear for every row** (B-13), including "Clear failed".
- **F-03 — Cancel a message that has not gone out.** The outbox has `retryMessage` and a retry affordance
  (`FlashMessageBubble.kt:139`, `MainActivity.kt:2144`) but no cancel/delete-while-pending; the retry loop is the only
  control. Small and expected by users.
- **F-04 — A "Stopped" concept in the UI** (B-05), so cancelled work is not reported as failure.
- **F-05 — Per-transfer notification for a *waiting* transfer** (network lost, peer away, Android paused), replacing the
  frozen progress bar of B-07. This is the swarm/hub case that today goes silent.
- **F-06 — Desktop parity action set** (B-14).
- **F-07 — A visible reason for a peer-initiated cancel of a 1:1 file** and for a receiver refusal (B-12, B-15).
- **F-08 — A notification action to *keep* rather than only cancel** (e.g. "Pause" next to "Cancel" on the progress
  notification). Today the only action on an in-flight transfer is the destructive one.

---

## 6. Deliberate behaviour, recorded so it is not "fixed" by accident

- A cancelled transfer keeps its in-memory row and its bytes on disk (the receiver's partial file is not deleted);
  `forgetChunks` only drops the resume index. Reasonable, but it means a cancel silently leaves a partial file behind —
  worth a sentence in the UI (see F-04).
- `declineIncoming` / `acceptIncoming` refuse anything but `Offered` (good; it is why a stale ACK cannot revive a decline).
- `onIncomingFailed` never clobbers Cancelled/Completed (good).
- Voluntary swarm leave does not rotate secrets (ADR-076), and a group cancel sends a signed tombstone that members keep
  after they already have the file (documented dialogs say so).
- Hub transient failures deliberately retry in the background (ADR-216); only the *unbounded* part is B-08.
- The FGS idle notification ("Flash is discoverable") is separate from transfer notifications and is not part of this
  audit's defects.

---

## 7. Open questions for the owner

1. **Should a 1:1 transfer survive the Android dataSync timeout** by pausing (B-02), or is "cancel" acceptable because the
   transfer cannot continue without a foreground service? If the latter, the row must at least say so and offer a retry
   that works.
2. **Should cancelling a *send* be recoverable at all?** If yes, it needs an explicit "send again" action (with a confirm)
   rather than the bubble's retry path (B-01).
3. **Group cancel from the notification:** is a confirmation acceptable there, or should the notification simply drop the
   action (B-04)?
4. **Transfer history retention:** how long, and does a failed row stay until dismissed (F-01/F-02)?
5. **Desktop:** are actionable toasts (accept/decline/cancel) in scope for 2.1.x, or is "reason + offer only" enough
   (B-14)?

---

## 8. Owed device tests

`CNCL-01` … `CNCL-14` are in `docs/testing/TEST-BACKLOG.md`, section "Cancel, error, resume ↔ notifications". Each names the
finding it exercises. Nothing in this audit is device-verified; every B-* is a static-code claim until those run.

## 9. What this audit did not do

- No product code was changed. No test was run. No Gradle task was invoked.
- No log file from a real session was inspected for these paths (there is no capture of a cancel/decline flow in `logs/`);
  the claims therefore rest on source only.
- Timing-dependent races (B-03's late PAUSE, B-17's cancel-during-Done) are reasoned from code, not observed.
