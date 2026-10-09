# Review of the uncommitted overnight code, 2026-10-09

Read-only bug review of the three streams written on 2026-10-09 (group history sync ADR-100, radio / serial link ADR-101, screen share
ADR-102). Reviewer: one agent, reading only. No source, test, build or log file was changed; this file is the only output. Nothing was
committed, stashed or deleted. No Gradle task was run: every finding rests on reading the code and its callers, and each one says whether it
is CONFIRMED (the code path was followed to the exact lines and the failure follows from language or library semantics) or PLAUSIBLE (the
path is real, but the outcome depends on something I could not observe: a device, a native library, a timing).

## Summary

| Severity | Count | Where |
|---|---|---|
| Critical | 0 | |
| High | 1 | S1 (screen share watchdog cancels its own stop) |
| Medium | 5 | G1, G2, G3 (history sync), S2, S3 (screen share) |
| Low | 24 | 10 in sync, 7 in screen share, 7 in radio |

Honest overall picture: the cryptographic and wire code (radio frame, KISS, AX.25, settings signing v1/v2, watermark) is careful and I found
no break in it. The defects cluster where native resources and coroutines meet (screen share) and where a design shortcut was taken on
purpose (sync amplification, global "last contact"). The one High is in code that no test reaches, because the session tests use a capture
provider that throws on `open`.

Top findings:
1. S1 (High): the 5 s "no frame" watchdog of a screen share calls `stopShare`, which cancels the watchdog job, which is the parent of the
   coroutine running `stopShare`. The stop is cut off at its first suspension: the capture is never closed, the camera never returns, no
   `ss=0` is sent, and the share state machine stays in STOPPING for the rest of the call.
2. G1 (Medium): `lastContactAtMs` is one value per group, set when the first holder finishes a chain, so every later or slower holder is
   asked for the 7-day returning window instead of the 30 days the member chose.
3. G2 (Medium): any non-D30 history ceiling makes every older build reject the whole signed settings object, so those members also miss every
   other setting change in the same group. The report says "mixed fleet is safe"; it is not for settings.
4. S3 (Medium): a group member can claim the presenter role permanently by stating `sst` at the clamp value; ordinary take-overs are clamped
   to the same value and lose the id tie-break, and the loser's own device keeps encoding to nobody.
5. S2 (Medium, plausible): call teardown closes the screen capture before the peer connections and without taking the track off the senders,
   against the capture handle's own contract, ADR-102 D10 and the camera path's order.

## Scope and method

Read in full or by diff: the three reports in `docs/reports/`, `docs/network/RADIO-WIRE-FORMAT.md`, and the code of each stream (file lists
in each section). Followed callers for every finding. Checked the tests that cover each area for tests that cannot fail (a script listed the
`@Test` bodies in the new test files that contain no assertion: one, `RadioSessionTest.hostileBytesNeverThrow`, which is a legitimate
no-throw fuzz). Authorization checks were traced from the frame envelope to the state change.

---

## Stream 1: group history sync (ADR-100)

### G1 (Medium, CONFIRMED) Returning-member window collapses to 7 days for every holder after the first one finishes

`core/messaging/.../protocol/GroupHistoryPolicy.kt:148` (`requestFor`) and
`core/messaging/.../RealFlashChatRepository.kt:4889-4896` (`handleSyncPage`).

`requestFor` takes `min(reachBack, returningWindowMs(ceiling, lastContactAtMs, now))` as soon as `lastContactAtMs > 0`.
`lastContactAtMs` is a single column of `group_history_state` per group, written when ANY holder's chain completes
(`complete && !frame.more`). The watermark, by contrast, is per (group, holder).

Failure scenario: a new member picks 30 days in a group with 19 holders. Holder A answers fast and its chain finishes, which sets
`lastContactAtMs = now`. Holder B was busy or offline for an hour and answers its first page later (or is asked on reconnect): its request
is now `min(30 d + ..., max(7 d, 1 h))` = 7 days. The member silently never gets days 8 to 30 from B, although B has them and A may
not (A joined later or pruned). The card promised 30 days. The same happens to a member who was away three weeks: after the first holder
completes, the others are asked for 7 days. `GroupHistorySyncTest` only builds two-member groups, so this is untested.

Suggested fix: apply the shrink only to a holder that has a watermark or a completed chain of its own (key the "returning" state by holder),
or store a per-holder last contact next to the watermark.

### G2 (Medium, CONFIRMED) A non-D30 ceiling blinds older builds to ALL settings changes

`GroupCanonical.kt:25,28,163` (`flash-gset-v1` vs `flash-gset-v2`, `settingsBytes`), `GroupSignatureRules.kt:159` (`checkSettings`).

D30 keeps the v1 signature bytes, any other ceiling is signed over v2 bytes (which include the ceiling). An older build verifies with the v1
bytes, fails, and rejects the entire settings entry, not only the ceiling. The admin who sets "7 days" and in the same edit (or any later
edit while the ceiling is not D30) changes `joinPolicy`, `inviteSharers`, `maxMembers`, `swarmServing` or `membersMayAdd` has those changes
ignored by every member on an older build, with no notice. The report states "old builds ignore a non-D30 ceiling ... mixed fleet is safe
but not equal"; the real effect is wider than "ignore the ceiling". The versioning is otherwise sound (domain separation, no malleability
between v1 and v2 because the tags differ and the ceiling is inside the v2 bytes).

Suggested fix: document it in `docs/protocol.md` and the settings sheet ("older versions will not see changes while a limit other than 30
days is set"), or sign twice (v1 bytes without the ceiling plus a separate v2 field) so old builds still verify the rest.

### G3 (Medium design, CONFIRMED by reading; impact PLAUSIBLE) Every holder serves the same history: N-fold duplicate pushes

`RealFlashChatRepository.kt:4416-4470` (`handleSyncRequest`), `4653-4666` (`handleSyncPush`).

A requester sends one request per holder, each with its own `syncId`. The existing claim election (which de-duplicates in the legacy flow)
works inside one `syncId`, so it never fires across holders. Each holder pages up to 100 pages of 100 rows. A member joining a 20-member
group with 30 days of busy history can receive up to 19 copies of every row; each copy costs a signature verification and a DB insert
attempt (inserts are idempotent, so there is no corruption, only waste). The watermark per holder is correct, the amplification is
the cost of "ask every holder for everything".

Suggested fix: ask the first holder fully and the others only from its watermark (or only for the part that holder is known to have
that the first lacks), or cap parallel chains to 2 or 3.

### G4 (Low, CONFIRMED) `handleSyncPage` can hold a session's inbound pipeline for 5 s

`RealFlashChatRepository.kt:4865-4867`. The marker waits up to `SYNC_PAGE_WAIT_MS` (5 s) for `count` pushes to be seen, inside the handler
of the per-session sequential inbound text pipeline (`session.incomingText.collect`). A marker whose pushes were filtered (for example
by `acceptsPush`) or lost blocks every later text frame of that session, including call signalling, for 5 s, and then the chain stalls
(`complete=false`), so the page is re-requested only on the next session. Suggested fix: handle the marker in its own coroutine or
count rejected pushes as seen.

### G5 (Low, CONFIRMED) `syncPushSeen` leaks entries

`RealFlashChatRepository.kt:4191,4666,4869`. Pushes from holders that never send a marker (older builds) add an entry per `syncId` that
nothing removes; a late push after the marker re-creates a removed entry. The `MutableStateFlow<Map>` is copied per push, so a large page
is O(n^2) in allocations. Fix: drop entries by age with the `outgoingSyncRequests` sweep, and use a concurrent map plus a signal.

### G6 (Low, CONFIRMED) Tombstoned rows count as usable in the scan

`RealFlashChatRepository.kt:4326-4375` (`catchUpCandidates`) keeps rows with `deletedAt != null` as "usable"; `GroupSyncPolicy.ownedMessages`
later drops them. A page can then be short while `more=true`, `remaining=0` and the page end sits before the scan end. Progress is still
guaranteed (no infinite loop; checked), but the banner estimate "N of about M" is wrong and paging is inefficient. Fix: filter
`deletedAt == null` inside the scan.

### G7 (Low, CONFIRMED) Legacy `State` handler always passes `newlyJoined = true`

`RealFlashChatRepository.kt:3028`. A returning legacy member with zero local rows (cleared data) gets a PENDING join card and catch-up
is blocked until it answers, while the other call site (3149) passes the real outcome.

### G8 (Low, CONFIRMED) "Load older" can lower the chosen window and re-pulls everything

`loadOlderGroupHistory` (4236) resets watermarks and `lastContactAtMs` and may set a window smaller than the stored one; the next
request re-pulls from the floor. Wasteful rather than wrong (inserts are idempotent).

### G9 (Low, UX) "Not now" is permanent

`skipGroupHistory` stores SKIPPED, which behaves as "None": the card never returns. The report calls it a skip; the user has no way back
except "Load older messages". Intentional per the doc, worth a line in the card text.

### G10 (Low) Admin check is duplicated and stricter than before

`updateGroupSettings` now needs the owner to hold an active roster row; `GroupAdminPolicy.isAdmin` repeats the rule. A later co-admin
change must edit both (the report says so). No bug today.

### G11 (Low, PLAUSIBLE) No rate limit on `SyncRequest`

A member can send `cont=true` requests back to back: continuation skips the pacing delay and `countRemaining` adds up to 2 000 row reads
per page. Group members are authenticated, so this is an insider cost issue, not an outside one.

### G12 (Low, PLAUSIBLE, self-harm only) Marker controls the requester's own watermark

A malicious holder can state any `lastAt/lastId` in the `page` marker and push the requester's watermark for that holder forward.
It only affects that holder's own stream for that requester; other holders have their own watermarks.

### G13 (Low) Equal-timestamp `conversationRefreshTrigger` edge

A StateFlow does not re-emit an equal value; two catch-up pages that finish in the same millisecond can leave one refresh unsignalled.
Cosmetic.

### Tests

`GroupHistorySyncTest` uses real-time sleeps and only two-member legacy groups; it cannot reach G1, G3 or the signed (v2) path. The pure
`GroupHistoryPolicyTest` covers `requestFor` for one holder only.

---

## Stream 2: radio / serial link (ADR-101)

### R1 (Low, CONFIRMED) The BT-00 tester shares an unsynchronised `RadioSession` across coroutines

`core/network/.../radio/diag/RadioLinkTester.kt` (`runBurst`, `handleRx`, `pendingAcks`, `pushFeed`); the harness runs it on
`Dispatchers.Default` (`RadioLinkTestHarness.kt` `newScope`). `RadioSession.kt:78` says "not synchronised; use from one coroutine".
`runBurst`/`sendFlashFramePayload` call `session.encode` from the caller's coroutine while the collector calls `session.ingest` and
`encodeAck`. `pendingAcks` is a plain `HashMap` written by both; `feedFlow.value = cur + line` is a lost-update read-modify-write; `seq` is a
plain int. Worst case: two concurrent `encode` calls take the same counter, which is an AES-GCM nonce reuse under the PUBLIC test key (no
secrecy lost, but the tool then reports a wrong ACK match or throws `ConcurrentModificationException`). Fix: confine the tester to one
dispatcher (`limitedParallelism(1)`) or guard with a Mutex.

### R2 (Low, PLAUSIBLE) Reconnect backoff resets on open, so a connect-then-drop link retries every second forever

`KissTncDriver.kt:221-222,249`. `backoff.reset()` runs right after `openLink()` succeeds, before the link proves anything. A radio that
accepts the RFCOMM or serial open and then closes at once (another app holds it, wrong baud, radio off) cycles Connecting, Connected,
Waiting(1 s) at 1 Hz, re-sending the TNC parameters each time. On Android that is a Bluetooth connect() storm. Not a hot loop (the floor is
`reconnectBaseMs` = 1 s), but not a backoff either. Fix: reset only after the link has been up for N seconds or delivered a valid frame.

### R3 (Low, PLAUSIBLE, latent) `RfcommLink.write` cannot be interrupted, and the link is closed only after `serve` returns

`AndroidBluetoothCatalog.kt` (`RfcommLink.write`), `KissTncDriver.kt:256-270,225-228`. When the read side fails the driver cancels the
transmit coroutine, but a blocking `OutputStream.write` ignores cancellation, and `link.close()` runs only in the `finally` after `serve`
returns, so a stalled write holds the driver until the OS gives up (a Bluetooth supervision timeout, on the order of seconds to a
minute). The serial link is protected by its 2 s write timeout. Nothing calls the Android link yet (BT-16 is blocked), so this is latent.
Fix: close the link from the cancellation handler of `serve`.

### R4 (Low, CONFIRMED, cosmetic) `reason` is last-writer-wins

`KissTncDriver.kt:256-269`. Both children write the shared `reason`; after a write error the read coroutine can still return
`"cancelled"` and overwrite `"write_error:..."` in the `radio.link.down` probe. Evidence quality only.

### R5 (Low, CONFIRMED) The desktop test window blocks its UI thread on native serial calls

`desktop/.../RadioLinkTestMain.kt:88-91,149` uses `rememberCoroutineScope()` (Main) and calls `harness.listPorts()` and
`harness.connect` (which calls `catalog.open` and `SerialPort.openPort()` directly, `RadioLinkTestHarness.kt` `connect`). Port
enumeration and opening are blocking native calls and can take seconds on a Bluetooth virtual port. The window freezes meanwhile. Fix:
`withContext(Dispatchers.IO)` in `listPorts`/`connect`.

### R6 (Low) Android manifest and permission notes

`app/src/main/AndroidManifest.xml:54-57`: the shipped app now declares `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` although nothing requests
them at runtime (Play Console "Nearby devices" declaration impact; mentioned in the manifest comment, not in the report). The code calls
`cancelDiscovery()` on API 30 and lower, which as far as I remember the official docs gate on `BLUETOOTH_ADMIN`, which is not declared;
the `runCatching` swallows the `SecurityException`, so it is harmless but the comment in `BluetoothPermissions.kt` ("BLUETOOTH and
BLUETOOTH_ADMIN are enough") does not match the manifest. I did not re-fetch the Android page (see "could not check").

### R7 (Low) `RadioSession.encode` does not validate `ttl`

`RadioHeader` throws if the TTL is above 255; `encode` passes the caller's value through. A bad caller gets an `IllegalArgumentException`
from deep inside instead of a typed `RadioEncode` refusal like the other limits.

### Tests

`RadioGoldenFrameTest` pins the exact on-air bytes (a regression guard; self-derived, not an independent implementation), the AES-GCM,
HKDF and HMAC vectors in `RadioCryptoVectorsTest` are published vectors. The BT-00 tester and driver are tested against the in-memory
fake TNC only; the jSerialComm path is exercised for "no ports / bad name" only, as the file header states.

---

## Stream 3: screen share (ADR-102)

### S1 (High, CONFIRMED) The no-frame watchdog cancels its own stop; the share is left half torn down

`FlashCallSession.kt:784-800` (`armShareWatchdog`) with `721-745` (`stopShare`); the same in `FlashGroupCallSession.kt:2261-2275` with
`2199-2240`.

```
shareWatchdogJob = scope.launch {                    // job W
    ...
    NO_FRAMES -> { launch { stopShare(NO_FRAMES) }   // child of W (implicit receiver is W's scope)
                   return@launch }
}
stopShare():  shareMutex.withLock { machine.end(); ... shareWatchdogJob?.cancel(); ...   // cancels W, so cancels its own coroutine
              updateState{...}; onMediaThread { ... closeShareLocked() }                // withContext throws CancellationException
```

`launch` inside `scope.launch { }` is a child of that coroutine. `stopShare` runs `shareWatchdogJob?.cancel()` while executing as W's child,
so it cancels itself. The next suspension is `onMediaThread` (`withContext(callMediaDispatcher)`), which throws `CancellationException`
immediately; the `catch (e: CancellationException) { throw e }` rethrows it. What never runs:
- `closeShareLocked()` (the screen is not taken off the sender, the capture is not closed, the kept camera is not restored),
- `shareRun.machine.finished()` (the machine stays in STOPPING forever: `machine.busy` stays true, so `toggleCamera` is disabled,
  `begin` refuses every new share, and `refreshUpgradeOffer` hides "Turn on camera" until the call ends),
- `sendStatus()` / `broadcastStatus()` (peers never get `ss=0`, so they keep showing "X is presenting" over a dead picture; in a group
  `routeVideo { setSharing(false) }` never runs, so the router keeps its watcher cap and share rules).
What does run: the UI flag (`sharing = false`) and the NO_FRAMES notice, so the person sees "sharing stopped" while the capturer is still running
and the peers still think they present.

When it triggers: any share that delivers no frame within 5 s. That includes the Wayland case the design mentions (the system picker is open
while the clock runs, so a user who takes more than 5 s to choose a screen hits it), a window that disappeared between listing and opening,
and a capturer that fails to start. The other stop paths are safe: USER and TAKEN_OVER come from other coroutines, so their
`shareWatchdogJob?.cancel()` cancels a different job (ERROR-086 was the same class in the group call end).

No test reaches this: `FlashScreenShareSessionTest` uses a provider whose `open` throws, so no session test ever runs a share to the point of
arming the watchdog (the file header says the capture path is a device check). The "eleven mutation checks" cover the pure classes only.

Suggested fix: start the stop outside W (`scope.launch { stopShare(...) }` from the session scope, not from inside W's lambda), or make
`stopShare` cancel the watchdog only when it is not running inside it; wrap the native part in `withContext(NonCancellable)` as the call end
does. Add a session test with a fake handle that never delivers a frame.

### S2 (Medium, PLAUSIBLE) Call teardown closes the screen capture before the connections and without taking it off the senders

`FlashCallSession.kt:2262-2265` and `FlashGroupCallSession.kt:2667-2670` call `shareRun.close()` (that is `DesktopCaptureHandle.close()`:
`detachSinks`, then `stream.release()` which stops and disposes the video source) before `peerConnection.close()` / the leg closes, while
the senders still hold the capture track (`replaceTrack(null)` is not called on this path, unlike `closeShareLocked`).
The handle's own contract (`ScreenCapture.kt`: "The track must already be off every sender") and ADR-102 D10 ("senders away from the screen,
detach sinks, stop source, dispose") say the opposite, and the comment at the call site gives "its sinks come off first, ERROR-123" as the reason,
although the probe is the only sink and the PeerConnection's sender is not a Java sink. The camera path closes the connection first and then
releases the local stream. Whether disposing the Java side of a `VideoDesktopSource` that a live sender still references crashes native
code, or only works because libwebrtc reference-counts the source, is exactly what ERROR-123 showed cannot be assumed; I cannot tell from
the code. Suggested fix: call `sender.replaceTrack(null)` on every video sender before `shareRun.close()` in the teardown (cheap), or close
the capture after the connection like the camera. Device check `SHARE-06` should be done at call end, not only at stop.

### S3 (Medium, CONFIRMED logic; needs a group member to exploit) A member can hold the presenter role for good, and the loser keeps encoding for nobody

`ScreenShare.kt` `ShareArbiter.onStatus` / `nextStart` / `set` (about lines 255-300).

Remote claims are clamped to `MAX_START` (year 2100) on receipt; local claims are not. A member states `sst = MAX_START`. Every
receiver now has `highestSeen = MAX_START`, so the next honest presenter computes `nextStart = MAX_START + 1` (the existing test at
`ScreenShareTest.kt:167-175` asserts exactly this), sends `sst = MAX_START + 1`, and every receiver clamps it back to `MAX_START`, which ties
with the attacker and is broken by device id: if the attacker's id sorts higher, it stays presenter on every other device. The honest
presenter's own arbiter stores the unclamped `MAX_START + 1`, so it believes it presents, `localMustYield()` stays false, and it keeps
encoding a screen to watchers who have pinned the attacker. The authorisation itself is right (the claim is bound to the authenticated
leg: group `onStatus` requires `frame.from == peerId`), so one member cannot forge another's claim; the problem is that "latest start
wins" is unauthenticated by design and the clamp makes the attacker's value unbeatable. Cost: a denied feature plus wasted encodes, not data
exposure.

Suggested fix: clamp local claims the same way (so everyone agrees), and bound a claim to "no more than a minute ahead of the largest honest
value seen", or make a deliberate take-over carry a counter that is compared before the clamp.

### S4 (Low, CONFIRMED) Cancellation during start leaves the machine in STARTING and closes the capture on the caller's thread

`FlashCallSession.kt:655-661`, `FlashGroupCallSession.kt:2122-2128`. If the start is cancelled while `onMediaThread` is open (the caller's scope
ends), the `catch (CancellationException)` runs `shareRun.close()` on the caller's dispatcher (the Compose scope on desktop is the UI
thread), a native call the file says must never run there (ERROR-123), and does not reset the machine, the arbiter's local claim or the UI
flags (`sharing=true`, `shareStarting=true`). Rare; same fix family as S1 (NonCancellable native cleanup on the media thread).

### S5 (Low, CONFIRMED) "Window closed" and "went silent" are documented but not implemented

`ShareStopReason.SOURCE_LOST` is never produced (grep: only mapped to a notice), and `ShareStopReason.NO_FRAMES` is documented as "or went
silent" while `ScreenShareRun.check()` only looks at `frameCount == 0`. A presenter who closes the shared window keeps showing "You are
sharing <window>" with a frozen or black picture until they stop it. The reports list this as ERROR-137 (unverified), and decision D8 says
first-frame only on purpose, so this is a doc fix: correct the enum comments.

### S6 (Low, latent) MAINTAIN_RESOLUTION sticks on Android

`RtpSenderTuning.android.kt:33-36` sets `MAINTAIN_RESOLUTION` for a share and no path resets it when the camera tuning is applied again
(`maintainFramerate` only sets, never clears). Android cannot present yet, so no effect today; it will bite when the Android presenter is built.

### S7 (Low) Old clients bypass the watcher cap

`GroupVideoRouter.isSending` returns true for LEGACY peers regardless of `capacity()`, and `level()` counts them. A presenter in a call with
several pre-G3 clients encodes the screen for all of them. Only affects very old builds.

### S8 (Low) Session tests never run a share

`FlashScreenShareSessionTest` covers presenter bookkeeping and no-op paths only; the group tests use `Thread.sleep(250)` (real time) to wait
for scope work. This is why S1 and S4 exist unnoticed. Add a fake `ScreenCaptureHandle` plus a fake `RtpSender` seam, or move the stop
logic into a pure class driven by `runTest`.

### S9 (Low) `shareWatchers` in a 1:1 call is set once

`FlashCallSession.kt` start path sets `shareWatchers = 1` or 0 at ready time from `statusBook.wantsVideo`, and is not updated when the peer
turns data saver on or off during the share. Display only.

### S10 (Low, PLAUSIBLE) A lost `ss=0` leaves a stale presenter in a group

`broadcastStatus` is fire and forget ("the next change or connect repairs a loss"). If the `ss=0` frame is lost, receivers keep the claim
until that leg leaves. The 1:1 session re-sends the status when signalling is restored; the group session does not.

---

## Checked and found fine

- Radio crypto: per-direction HKDF keys (initiator/responder labels not swapped), the AES-GCM nonce built from the counter (never from random),
  the AAD covering the header, the replay window (authenticate before commit; off-by-one at the window edge checked), the counter reservation
  and seconds-since-2026 floor, the per-segment AEAD and the 16-segment bound. Nonce reuse is only possible if the counter store is lost
  AND the clock goes backwards, which is documented.
- KISS decoder/encoder (FEND/FESC, oversize, bad escapes), the AX.25 UI codec (PID F0 only; CC/CD refused), the ECDSA DER to raw conversion
  (50-signature test), `StreamFrameDecoder` bounds.
- Settings signing: v1/v2 domain separation, no malleability, ceiling enforcement by every holder (`holderWindows` clamps whatever the
  requester asks), admin check, overflow-safe window math (`floorMs`, `reachBack`), the watermark never moving backwards or past a page that lost
  a frame, idempotent inserts (no duplicates on resume), the 12 to 13 Room migration against `13.json`, old/new wire compatibility of the
  request keys, the `isSwarmRootHex` change.
- Screen share authorisation: a status is bound to the authenticated leg (group: `frame.from == peerId`), so presenter forgery of another
  member is not possible; a LEFT leg's claims are dropped; hang-up and pruning clear the claim (ERROR-134 fix verified in code).
- Screen share wire: `ss`/`sst` are additive, old builds ignore them and still show the picture because the presenter sends `cam=1`;
  negative `sst` is rejected by the codec.
- Lock order in the group session (`shareMutex` then `mediaLifecycleMutex`, then `videoMutex` separately): no cycle found.
- Desktop capture handle: `close()` is idempotent, the frame probe releases every frame (ERROR-078), sinks come off before the source is
  disposed in `DesktopVideoStreamTrack.onStop` (ERROR-135 fix verified in code).

## Could not check

- Anything native or hardware: the real radio, a real serial port, jSerialComm's behaviour on a Bluetooth virtual COM port, RFCOMM on a
  phone, webrtc-java's behaviour when a source is disposed under a live sender (S2), what the desktop capturer does with a static screen or a
  closed window, the Wayland portal timing (S1 trigger), VP8 behaviour with `scaleResolutionDownBy` on screen content.
- No Gradle build or test was run, so I did not re-confirm the green counts quoted in the overnight report, and I did not write a failing
  test for S1 (it needs a fake capture handle and sender; a coroutine-only reproduction would need a new test file, which this review
  may not add).
- The Android documentation for `cancelDiscovery()` permissions on API 30 and lower was not re-fetched (R6 is from memory).
- Whether jSerialComm 2.11.4 appears in the generated third-party notices (the overnight report asks for this and I did not check).
- `DesktopEngine.kt` (a 1 000-line diff that mixes the ERROR-125 engine refactor with this work), the Android `MainActivity` wiring beyond
  the history callbacks, and the `DesktopShell` changes outside the share and history hooks.
- Whether the three streams' uncommitted state collides on `DATABASE_VERSION` 13 (needs the other working copies).

## Claims in the agents' reports that the code does not support

- "Mixed fleet is safe" (sync): see G2, older builds drop every settings change while a non-D30 ceiling is set.
- "ERROR-123 close order" for screen share (ADR-102 D10): true for stop, not for call teardown (S2) and not for the NO_FRAMES stop (S1).
- "Eleven mutation checks, all killed": true for the pure classes; none exercises the session start/stop code where S1 and S4 live.
- "Group sync: returning-member window `min(ceiling, max(7 d, away))`": per holder this holds for the first holder only (G1).
