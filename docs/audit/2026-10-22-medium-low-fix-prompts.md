# Prompts for the MEDIUM and LOW findings of the 2026-10-22 comprehensive review

Source: `docs/audit/2026-10-22-comprehensive-brutal-code-review.md`.
The CRITICAL and HIGH findings (F1-F33, F69-F71) are fixed by the owner's main session, not by these agents.
Give each agent the **COMMON PREAMBLE** followed by exactly one **STREAM** block.

| Stream | Name | Findings | ERROR range | Backlog prefix |
|---|---|---|---|---|
| A | `rvw22-a-messaging` | F34 F35 F36 F37 F54 F55 F65 F66 | ERROR-760..769 | R22A- |
| B | `rvw22-b-transfer-server` | F38 F39 F40 F41 F56 F60 F64 F78 + F79 (investigate) + dead code in Chunker | ERROR-770..779 | R22B- |
| C | `rvw22-c-calling-ptt` | F42 F43 F44 F45 F46 F57 F58 F59 + F80 (investigate) | ERROR-780..789 | R22C- |
| D | `rvw22-d-ui` | F47 F48 F49 F52 F73 F75 F76 F77 + dead code in callui/shims | ERROR-790..799 | R22D- |
| E | `rvw22-e-platform` | F50 F51 F53 F61 F62 F63 F72 F74 + F67 (investigate) | ERROR-800..809 | R22E- |

The main session keeps ERROR-752..759 and `RVW22H-` for the high findings.

---

## COMMON PREAMBLE (paste first, for every stream)

You are fixing a set of MEDIUM and LOW findings from `docs/audit/2026-10-22-comprehensive-brutal-code-review.md` in the Flash repository
(`C:\Users\KaliOxygen\Downloads\Flash`, branch `dev`). Several other agents work at the same time on other findings, and the owner fixes
the CRITICAL and HIGH ones. Follow these rules exactly.

1. **Read first, do not trust chat memory.** Read `AGENTS.md` (sections 4, 6-10, 13, 19, 25, 27, 35, 36), `logs/handoff.md`, the newest entries
   of `logs/progress.md`, and the audit text of every finding in your stream. Inspect the real current code before changing anything.
2. **Verify every finding before fixing it.** An audit is a claim, not a fact (the previous review had a wrong diagnosis, F8 of 2026-10-22 round 2).
   Open the cited file and lines (line numbers may have moved), trace the callers, and decide one of:
   `FIXED` (defect confirmed, fixed, tested), `ALREADY-FIXED`, `NOT-A-BUG` (give the evidence), `DEFERRED` (needs an owner decision, say which),
   or `CANNOT-CONFIRM`. Never "fix" something you could not confirm. Where the audit's suggested fix is wrong, incomplete or weakens security,
   say so and do the correct fix.
3. **Smallest correct fix, test first.** Write a failing test where practical, then fix. After the fix do a **mutation check**: revert only the
   production change, confirm the new test fails, restore it. Report the mutation result per finding. "Compiles" is not verification.
4. **Do not break documented decisions.** Before changing behaviour, grep `docs/decisions.md`, `logs/errors.md`, `docs/protocol.md` and
   `docs/security.md` for the area. A fix that contradicts an ADR or a resolved ERROR is `DEFERRED` with a question for the owner, not a quiet change.
5. **Workspace.** Never edit the `C:` tree. Create your own worktree and branch from the current `dev` commit:
   `git worktree add E:\FlashWork\<stream-name> -b agent/<stream-name> dev`, copy only `local.properties` into it (never `keystore.properties`
   or any `.jks`). Commit on your branch only, one commit per finding, message `fix(<area>): <what> (<stream-prefix>NN)`, ending with the
   attribution line your tool requires. **Never push, never merge, never touch `dev` or `main`.**
6. **Gradle only through the lock script**, from your worktree root: `tools\flash-gradle.cmd <args>`. Never run `gradlew` directly. Exit code 75
   means another agent is building: do other work and retry. Run the affected module tests; when you touch shared code also run
   `:app:compileDebugKotlin` and `:desktop:compileKotlinJvm`. Do not start a second Gradle command while one runs.
7. **Hands off the phone and the network.** Do not use the connected phone, do not uninstall or clear any app, do not ssh/scp to `myserver`,
   do not attack or probe any remote system. Everything you do is code, unit tests and local loopback tests.
8. **Out of scope: every CRITICAL/HIGH finding (F1-F33, F69-F71).** The owner's session edits these files heavily: `HubChatBridge`,
   `GroupSignatureRules`, `HubSession`, `FlashRelayClient`, `KtorRelayTransport`, `HubFileTransfers` (decode at ~241), `HubFileSealing`,
   `FlashPathSanitizer`, `DesktopEngine` (stream fallback), `RelayConnectionPool`, `MailboxQueue` (`insert`/`existingSeq`), `NsdResolveQueue`,
   `AndroidBluetoothCatalog`, `BluetoothPermissions`, `CallCoordinator`, `CallHealthMonitor`, `PttFloorMachine`, `PttSessionEngine`, `PttPlayout*`,
   `FlashConversationScreen` (draft state, file-card tap), `FlashImageGrid`, `FlashSwipeToReply`, `FlashVideoSurface.android`, `FlashCallScreen`,
   `FlashGroupVideoGrid`, `FlashCallVideoSurface.android`, `ServePolicy`, `RealFlashChatRepository` (group secret request, group outbox, group text inbound).
   If a fix needs lines in one of these, keep the edit minimal and local, `git fetch` and rebase just before committing, and if it conflicts
   **stop and report** instead of resolving by overwriting someone else's change.
9. **Logging (temporary parallel-agent exception, `docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md` section 8).** Do not edit the canonical logs
   (`logs/progress.md`, `logs/errors.md`, `logs/handoff.md`, `docs/testing/TEST-BACKLOG.md`, `docs/decisions.md`, `AGENTS.md`). Write only to
   `logs/streams/<stream-name>/` in the same formats: `progress.md` (what / changed / why / verified / remaining / next AI), `errors.md`
   (one `ERROR-NNN` per confirmed defect or per group of defects, numbers only from your reserved range, status OPEN until a device test),
   `backlog-additions.md` (an owed device/manual test per fixed finding: ID with your prefix, setup, steps, concrete Pass condition, source,
   `Status: TODO`), `decisions-additions.md` (an ADR if you change a rule). Never delete anything.
10. **Be honest.** You cannot device-test. Say "unit-tested, not device-verified". Do not claim a performance improvement you did not measure
    (AGENTS section 23): for the findings that say "benchmark required", make only the structural fix when it is low risk, and write the
    measurement the owner must take into `backlog-additions.md`.
11. **Final report** (in your last message and in `logs/streams/<stream-name>/progress.md`): per finding, its status
    (FIXED / ALREADY-FIXED / NOT-A-BUG / DEFERRED / CANNOT-CONFIRM), files changed, tests added, mutation-check result, the exact commands you ran
    and their results, the branch and last commit hash, and what remains. List any file you had to touch from rule 8.

---

## STREAM A - `rvw22-a-messaging` (messaging, persistence, security crypto)

Worktree `E:\FlashWork\rvw22-a-messaging`, branch `agent/rvw22-a-messaging`, ERROR-760..769, prefix `R22A-`.
Test commands to start from: `:core:messaging:jvmTest`, `:core:messaging:testAndroidHostTest`, `:core:persistence:jvmTest`, `:core:security:jvmTest`.

- **F34 (MEDIUM)** `RealFlashChatRepository.kt` `MessageWireFrame.DeliveryReceipt` handler (~6023) overwrites `READ` with `DELIVERED`.
  Use the DAO's guarded update (`updateStatusIfUnacknowledged`, `MessageDao.kt` ~138-154) but first read what it guards: it must never move a status
  backwards (READ -> DELIVERED, DELIVERED -> SENT, FAILED rules). Test: read receipt first, then delivery receipt, the row stays READ.
  Also check the group variant of the same handler.
- **F35 (MEDIUM)** `SignedGroups.kt` ~1057 `verifiedAuthorLabel` returns null for a former member, so historical group messages from someone who
  later left are dropped on catch-up / SyncPush. The audit's one-line fix is vague and the security side matters: a **removed** member must still
  not be able to inject NEW messages. Only relax the check on the catch-up / history-sync path, only for a message whose signature verifies with
  the author's stored key and whose timestamp is before the author's removal, and only if the member row records when they were removed. If the row
  has no removal time, or the live-inbound path would be loosened, mark `DEFERRED` with the exact data model gap. Add tests for both directions
  (old message from a removed member accepted by history sync; new message from the same member still refused).
- **F36 (MEDIUM)** Voice (`vmsg:<ms>:<amplitudes>`) and call-event (`cmsg:`) bodies stored in `messages.text` match text search and show raw in snippets
  (`RealFlashChatRepository.kt` ~6573-6590, 7288-7290, `MessageDao.kt` ~233-236). Exclude these rows' encoded text from matching (SQL and/or the
  in-memory search path, both global and in-chat) without hiding the message from other search scopes it legitimately belongs to (a voice note has
  no searchable text; a call event may). Test with a voice row whose amplitudes contain the query digits.
- **F37 (MEDIUM)** `E2eFrameCodec.decrypt` (~86-88) rejects a valid empty-plaintext frame (`> NONCE+TAG` should allow equality). Check every caller
  and any test that relies on the old rejection. Add a round-trip test for the empty plaintext and keep rejecting anything shorter than nonce+tag.
- **F54 (LOW)** `FlashSearchText.kt` ~49-60: Links scope looks only at the first URL. Search every URL of the message. Test two URLs, query in the second.
- **F55 (LOW)** `transportSink` has no radio fallback for delivery receipts / reactions (`RealFlashChatRepository.kt` ~695-704, 6003-6005, 7500-7503).
  Before adding it read `docs/network/RADIO-WIRE-FORMAT.md` and ADR-101: radio airtime is scarce and receipts may be deliberately excluded. If they
  are excluded on purpose, mark `NOT-A-BUG`; if not, add the fallback and a test.
- **F65 (MEDIUM, security)** `HubSealing.seal()` / `DirectoryHubSealing.kt` ~87 throw `InvalidKeySpecException` on a corrupt directory public key.
  Return a failure the callers handle. **Critical rule: a seal failure must never fall back to sending plaintext** and must not be indistinguishable
  from "peer has no key" if callers treat that differently; read every caller first and test that a corrupt key produces a refusal/retry-later, not a plaintext send.
- **F66 (LOW)** `HubFileKey` / `GroupSecret` hold raw `ByteArray` with no `clear()`. Add an explicit `clear()` only where an obvious owner can call it at the
  end of the key's life and no copy is still in use (never zero a key that a cache or a running transfer still holds). The JVM gives no guarantee, so keep it small;
  if no safe call site exists, mark `DEFERRED` and say why.

---

## STREAM B - `rvw22-b-transfer-server` (file transfer, hub files, server)

Worktree `E:\FlashWork\rvw22-b-transfer-server`, branch `agent/rvw22-b-transfer-server`, ERROR-770..779, prefix `R22B-`.
Test commands: `:core:transfer:jvmTest`, `:core:engine:jvmTest`, `:core:enterprise:jvmTest`, `:server:test`.
Known flake: `RelayFrameRouterTest.messagesSentWhileTheRecipientConnectsAreAllDeliveredInOrderExactlyOnce` (green when rerun alone).

- **F38 (MEDIUM)** `HubFileTransfers.kt` ~341, 468-475: a local storage read error during a sealed-chunk read calls `api.cancel`, permanently cancelling a nearly
  finished upload on the hub. Distinguish **transient** (I/O hiccup, retry) from **permanent** (source file gone or changed): transient returns `interrupted`
  and keeps the remote upload and the persisted state so a retry resumes; permanent may still cancel. Make sure a persistent error cannot become an infinite
  retry loop (use the existing retry/backoff). This file is also edited for F13 (~241): keep your change to the failure branch only.
- **F39 (MEDIUM)** `MailboxQueue.deleteExpired` (~388-418) also deletes quarantined rows and produces a second terminal `EXPIRED` event for messages that were
  already reported `DROPPED` (`FlashServerEngine.kt` ~158, `RelayFrameRouter.kt` ~398-411). Quarantined rows must still be deleted from disk by their own rule
  (ADR-223 keeps quarantined rows counted in disk totals); only the **notification** must skip rows that already have an `undeliverable_reason`.
  Test: quarantined + expired row yields no `EXPIRED` event and is still removed. `MailboxQueue.insert`/`existingSeq` belong to another agent: do not touch.
- **F40 (MEDIUM)** `RandomAccessSinkHandle.kt` ~64-70: overwriting a larger existing file leaves stale trailing bytes and a hash mismatch. Truncate when the
  existing size is larger than the expected size. Do **not** resize when growing (ERROR-067 is the reason). Test: 5 MB file overwritten by 1 MB.
- **F41 (MEDIUM)** `ReceivePipeline.kt` ~317-334: a duplicate/retransmitted chunk produces no ACK, so the sender stalls until its retry timeout. Read
  `docs/protocol.md` ack rules and the sender's retry logic first. A duplicate must be acknowledged without double-counting progress, bytes or the done-set.
- **F56 (LOW)** `FlashInboundRouter.kt` ~708-716: on a whole-file hash `MISMATCH` the `onCompleted` hook still gets an event with `verified = true`. Pass the
  corrected completion (`verified = false`). Check all `onCompleted` listeners on Android (`DiscoveryEngineHolder`) and desktop so none treats a mismatch as success.
- **F60 (LOW)** `RelayFrameRouter.spill` (~728-738) vs `MailboxQueue` (~119-122, 420-423): the dropped metric is incremented by both `enqueue` and `noteDropped()`.
  Confirm both really fire for the same message, then count once. Test the metric.
- **F64 (MEDIUM, security)** `RelayFrameRouter.handleInboundMeta` (~436-446): an existing group answers a non-member with `ERR_NOT_MEMBER`, a missing group is silent,
  so group ids can be probed. Make both cases answer the same way as `handleGroupSend`. Check client code and tests that rely on `ERR_NOT_MEMBER`, update
  `docs/protocol.md` if the wire behaviour changes, and add a test that the two cases are indistinguishable to the sender.
- **F78 (LOW)** `RelaySession.dispose()` is never called (`RelaySession.kt` ~65, 285-287). Call it when the session ends, exactly once, after in-flight cleanup
  has finished using the scope. Do not touch `RelayConnectionPool.register` (another agent). Heap retention is "benchmark required": add the measurement to your backlog file.
- **Dead code (audit section 7):** `Chunker.kt` ~257-265 "pooled buffer leaked on `ChunkStream.close()`". A buffer that is not returned to a pool is collected by the GC;
  only change it if the pool's accounting or a size cap really breaks. Otherwise `NOT-A-BUG`.
- **INVESTIGATE F79:** does `BoundedBody.boundedText` (`core/enterprise/.../BoundedBody.kt`) leave the connection or socket open when it stops reading at the limit? Reproduce
  only against a **local loopback Ktor server in a unit test**, never a remote host. Report evidence; fix only if confirmed (cancel the channel / `HttpResponse` on early exit).

---

## STREAM C - `rvw22-c-calling-ptt` (calling and push-to-talk)

Worktree `E:\FlashWork\rvw22-c-calling-ptt`, branch `agent/rvw22-c-calling-ptt`, ERROR-780..789, prefix `R22C-`.
Test commands: `:core:calling:jvmTest`, `:core:calling:testAndroidHostTest`, `:core:ptt:jvmTest`, `:core:ptt:testAndroidHostTest`.
**Order of work:** do F42, F43, F45, F46, F57 first. `PttSessionEngine.kt` and `PttFloorMachine.kt` (needed by F44, F58, F59) are being rewritten by the owner's session
for HIGH findings F7-F9, F26-F28, F71: do F44, F58 and F59 **last**, after `git fetch`; if the owner's PTT changes are not on a branch you can rebase onto, skip those three
and report them as `DEFERRED (waiting for the PTT high-severity commits)`.

- **F42 (MEDIUM)** `FlashCallSession.kt` ~457-468: `hangUp()` / `decline()` send the frame before `end(...)`; a thrown network error leaves the user on the call screen. Local teardown
  must always run (try/finally) while real local media-release exceptions are not swallowed silently (log them). Test with a failing `sendFrame`.
- **F43 (MEDIUM)** `CallQualityGovernor.kt` ~146-148: a backwards wall-clock step bypasses the recovery cooldown. Prefer a monotonic time source if the class gets its clock injected
  (check how `now` is provided and what other callers pass); otherwise treat `elapsed < 0` as "still in cooldown". Test with a clock that steps back.
- **F45 (MEDIUM)** `PttJitterBuffer.kt` ~38-55, 66-75: u32 sequence wrap-around is treated as "late", and if packet 1 arrives before packet 0 then packet 0 is dropped for good.
  Use wrap-safe signed difference arithmetic and allow the first packet to arrive out of order within the buffer depth. Read the wire codec first for the real width of `seq`.
  Tests: wrap at 0xFFFFFFFF, first packets swapped, duplicate packets.
- **F46 (MEDIUM)** `PttJitterBuffer.kt` ~58-61: on overflow the buffer removes the **earliest** packet (the one about to play). Choose and justify one policy (drop the newest,
  or advance `nextSeq` past the dropped one so concealment is not forced) and test it.
- **F57 (LOW)** `CallStatusBook.kt` ~83-92: after a backwards clock step peer reactions are dropped. Treat `now < last` as "allowed" (and reset `last`). Test.
- **F44 (MEDIUM)** `PttSessionEngine.kt` ~169-203: a second quick press while Talking can start a session without the permission/peer checks. Move the checks into the locked
  processing step so every press goes through them. (LAST, see order of work.)
- **F58 (LOW)** `PttSessionEngine.kt` ~534-537 / `PttFloorMachine.kt` ~240-247: `MicDenied` emits `SendStop` even when `Start` was never announced. Only send Stop if announced. (LAST.)
- **F59 (LOW)** `PttSessionEngine.kt` ~195 / `PttFloorMachine.kt` ~250: a blank local id disables collision resolution. Refuse a press (with the existing user-facing reason) when the local id is blank. (LAST.)
- **INVESTIGATE F80:** can `releaseMedia()` (async on `callMediaDispatcher`) race a quick re-dial's `startMedia()` (`FlashCallSession.kt` ~2256, `CallCoordinator.kt` ~626-643)? Read the code and
  write a unit test with a fake media engine if the ordering can be forced; otherwise report exactly what needs a physical Camera2 test and add it to `backlog-additions.md`. No phone.

---

## STREAM D - `rvw22-d-ui` (chat and call UI)

Worktree `E:\FlashWork\rvw22-d-ui`, branch `agent/rvw22-d-ui`, ERROR-790..799, prefix `R22D-`.
Test commands: `:ui:chat:jvmTest`, `:ui:chat:testAndroidHostTest`, `:ui:callui:jvmTest`, plus `:desktop:compileKotlinJvm` and `:app:compileDebugKotlin`.
`FlashConversationScreen.kt` and `FlashMessageBubble.kt` are also edited by the owner's session in other regions (draft state, file-card tap): keep edits local, rebase before committing.
Read `docs/ui/flash-premium-chat-ui-implementation.md`, `docs/ui/ui-research-index.md` and the component doc of each component you touch (AGENTS section 34).

- **F47 (MEDIUM)** `FlashVideoPlayer.kt` ~71, 176-184 and `FlashVideoSurface.android.kt` ~28-34: replay sets `seekToTarget = 0L` but never clears it, so the second replay does not re-trigger the seek.
  Clear it once handled (null after use), or use an event counter. Do not change anything else in `FlashVideoSurface.android.kt` (owner edits it for F10).
- **F48 (MEDIUM)** `FlashConversationScreen.kt` ~581-593 `jumpToMessage` and `FlashMessageList.kt` ~181-198: when a peer-typing item is prepended, the computed index is off by one.
  Do not hard-code "+1": derive the offset from the same condition that adds the extra item, so they cannot drift apart, and cover it with a pure function test.
- **F49 (MEDIUM)** `FlashConversationScreen.kt` ~641-658: the multi-select Forward filters on non-blank text, so attachment messages answer "Nothing to forward". First check what the
  forward sheet and `FlashChatRepository` can actually forward (can an attachment be re-sent?). If the repository supports it, include images/files/voice; if not, stop the misleading
  message (say attachments cannot be forwarded yet) and mark the real feature `DEFERRED` for the owner.
- **F52 (MEDIUM)** `FlashCallShare.kt` ~286: `host.listSources()` inside `produceState` can throw and crash the call screen. Catch the failure and show an empty/failed state, but **rethrow
  `CancellationException`**. Test the failure path.
- **F73 (MEDIUM)** `FlashImageDecoder.jvm.kt` ~57: the cache key omits `isVideo`; a video thumbnail and an image at the same path collide. Add it to the key. Test.
- **F75 (LOW)** `FlashMessageBubble.kt` ~672-681: wrap `buildHighlightedMessageText` in `remember(message.text, searchQuery)`.
- **F76 (LOW)** `FlashMessageBubble.kt` ~172-186, 359-378, 615-634: per-bubble lambdas are re-allocated each recomposition. Memoize only where it is safe (key on the stable id, use
  `rememberUpdatedState` for changing callbacks so no stale capture appears). If the change grows large, do the cheap part and report the rest.
- **F77 (LOW)** `FlashAttachmentStagingTray.kt` ~94-97: the list key contains the index, so removing an item recreates all following cards. Key on a stable unique identity. **Check whether two
  staged items can have the same uri** (a duplicate key crashes a `LazyRow`); if so give each staged item a unique id instead of using the uri.
- **Perf findings** (F75-F77) say "benchmark required": make the structural fix and add the measurement the owner must take to `backlog-additions.md`; do not claim a speed-up.
- **Dead code (audit section 7):** `FlashBackHandler.jvm.kt:16` unused `enabled`/`onBack`, `FlashCallVideoSurface.jvm.kt:75` unused `zOrderMediaOverlay`. They belong to `expect/actual`
  signatures: check the common declaration and all actuals before removing; rename or suppress rather than break an actual.

---

## STREAM E - `rvw22-e-platform` (Android platform shims, discovery, radio, JVM image decoding)

Worktree `E:\FlashWork\rvw22-e-platform`, branch `agent/rvw22-e-platform`, ERROR-800..809, prefix `R22E-`.
Test commands: `:ui:platform-shims:jvmTest`, `:ui:platform-shims:testAndroidHostTest`, `:core:discovery:jvmTest`, `:core:discovery:testAndroidHostTest`, `:core:radio:jvmTest`, `:app:compileDebugKotlin`.
Android behaviour must be checked against **current official documentation** (AGENTS section 13); the search helper is `python tools/tavily_search.py --domains=developer.android.com "<query>"`
(a search result is context, not evidence about this app). Record each platform fact in `logs/streams/rvw22-e-platform/platform-notes-additions.md` with the URL, the date and
"verified / reported / community claim".

- **F50 (MEDIUM)** `FlashCameraCapture.android.kt` ~24-39: `currentUri` / `currentFile` live in `remember`, so a rotation or process death loses the captured photo. Use `rememberSaveable`
  with a Saver (a `Uri`/`File` is not saveable by default: save the path/URI string). Make sure the temp file survives (cache dir) and a stale value after restore does not point at a deleted file.
- **F51 (MEDIUM)** `FlashPermissions.android.kt` ~24-51: one `pending.continuation` is overwritten by a concurrent request and the first caller hangs forever. Serialize requests (mutex) or keep a
  queue; resume each continuation exactly once; clean up on cancellation. Test with two concurrent requests (a fake launcher).
- **F53 (MEDIUM)** `NsdTransport.kt` ~326-339: `advertiseListener` is stored before `registerService()` succeeds, so a failed registration makes the later unregister throw
  `IllegalArgumentException: listener not registered`. Only keep it once registration succeeded (`onServiceRegistered`) and guard unregister. `NsdResolveQueue.kt` and the resolve path
  (lines ~458-470) belong to another agent: do not touch them.
- **F61 (LOW)** `AndroidRadioDiscovery.kt` ~164, 252: `registerReceiver` without an export flag. Check the module's `targetSdk` and the official rule for Android 13/14 context-registered receivers
  (what is required for system-only broadcasts such as Bluetooth discovery results), then use `ContextCompat.registerReceiver` with the right flag. If the docs say no flag is needed for these broadcasts, `NOT-A-BUG` with the URL.
- **F62 (LOW)** `KissFrameCodec.kt` ~187 and `StreamFramer.kt` ~42: `offset + length` can overflow `Int` and bypass the range check. Use `offset <= data.size - length`. Tests with `Int.MAX_VALUE`.
- **F63 (LOW)** `FlashImageDecoder.jvm.kt` ~165-175: the EXIF marker loop treats the second `0xFF` of `FF FF E1` as a marker prefix. JPEG allows fill bytes: skip consecutive `0xFF`. Test with a hand-built JPEG header.
  (Another agent changes the cache key at line ~57 of the same file: keep your edit to the marker loop.)
- **F72 (MEDIUM)** `AndroidMulticastSocketFactory.kt` ~64-66, 88 and `NsdTransport.kt` ~1789-1798 skip the multicast lock on API 34+ on purpose (`SDK_MULTICAST_LOCK_NOT_NEEDED`). The audit claims hardware
  drops multicast with the screen off, and says "benchmark required". Read the code comment and the ADR/ERROR that introduced the skip, check the official `MulticastLock` / Wi-Fi docs, and **do not
  remove the skip on the strength of the audit**. Unless you find evidence in the repo (logs, experiments) that the skip causes loss, mark `DEFERRED` and write the measurement
  (Android 14+ phone, screen off, count discovery packets with and without the lock) into `backlog-additions.md`.
- **F74 (LOW)** `FlashAudioPlayer.android.kt` ~13-17 and `FlashVoiceRecorder.android.kt` ~14-17: the `remember...` factories have no internal teardown. Read every caller first: if callers already
  dispose, adding a second release would double-release (a crash). Only add teardown if it is idempotent; otherwise `DEFERRED` with the caller list.
- **INVESTIGATE F67:** `FlashImageDecoder.android.kt` ~167-182 `resolveLocalFile`: trace every caller of the decoder and show whether a path string can come from a remote peer without being confined to the app's
  own received/cache directories, and whether anything lets a remote party observe the decoded image. Report the call chain; fix (directory containment) only if a remote-controlled path is confirmed.
- **Dead code (audit section 7):** none assigned beyond the above.
