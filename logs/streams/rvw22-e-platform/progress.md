# Progress Log — Stream E (`rvw22-e-platform`)

Worktree `E:\FlashWork\rvw22-e-platform`, branch `agent/rvw22-e-platform`, off `dev`.
Findings handled: F50, F51, F53, F61, F62, F63, F72, F74 and the F67 investigation, from
`docs/audit/2026-10-22-comprehensive-brutal-code-review.md` (prompts:
`docs/audit/2026-10-22-medium-low-fix-prompts.md` § "STREAM E").

Nothing here is device-verified. Every claim below is either a unit/host test result, a mutation
check, or a reading of the code — never a device run.

---

## 2026-10-23 — the nine findings

### Verdict table

| Finding | Severity | Verdict | In one line |
|---|---|---|---|
| F50 | MEDIUM | **FIXED** | The capture is kept in `rememberSaveable` as two strings and re-validated on restore; a rotation or process death no longer drops the photo. |
| F51 | MEDIUM | **FIXED** | A queue (`PermissionRequestArbiter`) serialises system permission dialogs and answers every caller exactly once, instead of one overwritable continuation. |
| F53 | MEDIUM | **FIXED** | The advertise listener is published before `registerService` (so an async failure stays releasable) and withdrawn when the call itself throws. The audit's "only set it on success" would leak it. |
| F61 | LOW | **NOT-A-BUG** | These receivers listen only for Bluetooth system broadcasts, which the Android 14 flag requirement exempts; `RECEIVER_NOT_EXPORTED` (the audit's fix) would stop discovery and pairing. Documented in the code. |
| F62 | LOW | **FIXED** | `offset + length` overflow in the two slice guards replaced with the subtraction form; `Int.MAX_VALUE` tests added. |
| F63 | LOW | **FIXED** | The JPEG marker loop skips `0xFF` fill bytes, so an EXIF block behind them is read and the photo is not sideways. |
| F67 | CANNOT CONFIRM | **CANNOT-CONFIRM** | No remote-controlled path reaches `resolveLocalFile`; inbound rows store `attachmentPath = null`, and nothing returns a decoded bitmap to the wire. No code change. |
| F72 | MEDIUM | **DEFERRED** | The multicast-lock skip at API 34+ is a deliberate, ADR-backed trade-off; the audit says "benchmark required" and the repo has no evidence of beacon loss. Only the stale "mirrors NsdTransport" comment was corrected. DISC-07 decides it. |
| F74 | LOW | **FIXED** | Both `remember…` factories now own an idempotent `DisposableEffect` teardown; every existing caller's own dispose is safe on top of it. |

### F50 — captured photo lost on rotation / process death (`FIXED`)

**Audit claim.** `FlashCameraCapture.android.kt` keeps `currentUri`/`currentFile` in `remember`, so a
rotation (or a low-memory kill) resets them and the photo taken in the camera app is lost.

**Confirmed.** `ActivityResultContracts.TakePicture()` runs in another process. While it is in front,
this composition can be destroyed and the result is delivered to a *new* one; `remember` does not
survive that. The old callback then found `uri == null`/`file == null` and silently did nothing —
the "captured photo dropped on the floor" symptom.

**Changed** — `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt`

- The two held values became two **`String`s** in `rememberSaveable` (`Uri`/`File` are not
  Bundle-safe; the audit's "use `rememberSaveable`" alone does not work).
- The result callback now goes through a new pure helper `restoredCapture(uri, path)` which rebuilds
  the `FlashPickedFile` **only if the temp file still exists and is non-empty**. The cache directory
  is the platform's to clear, and a zero-length file is what a camera killed mid-write leaves; both
  mean "no photo", never a bubble pointing at nothing.
- The failure path is unchanged (`onCaptured` is only called with a real file).

**Tests** — `ui/platform-shims/src/androidHostTest/.../FlashCameraCaptureRestoreTest.kt` (4 tests):
a live file is reported with the saved uri/name/size; a deleted path reports nothing; a zero-length
file reports nothing; blank/null saved values report nothing.

**Mutation** — removing the `isFile`/`length() > 0` check from `restoredCapture` made 2 of the 4 fail
(`:49`, `:57`), and the green run restored.

**Not covered.** That Compose actually restores `rememberSaveable` and replays the pending
`TakePicture` result across a real process death is platform behaviour, not something the host test
can show → `R22E-01` in `backlog-additions.md`.

### F51 — concurrent permission requests hang the first caller (`FIXED`)

**Audit claim.** `FlashPermissions.android.kt` holds one `pending.continuation`; a second request
overwrites it, hanging the first caller for ever.

**Confirmed.** Single field, single launcher callback, no request identity. Worse than the audit
says: the surviving caller could be resumed with the answer to the *other* permission, because the
callback cannot tell which request it belongs to. The audit's "guard with a mutex" is also
insufficient — a mutex makes the second caller wait behind the *first suspension*, i.e. it deadlocks
until the dialog that the first caller already raised is answered, and it still cannot survive an
`onResult` that arrives after cancellation.

**Changed**

- **New** `ui/platform-shims/src/androidMain/.../PermissionRequestArbiter.kt` — pure Kotlin (no
  `android.*`), so the concurrency rules are host-tested:
  - one dialog at a time; later requests queue;
  - `isGranted` is re-read before every dialog, so a request satisfied meanwhile (by an earlier
    answer or Settings) raises no second dialog;
  - every waiter is completed exactly once; a waiter cancelled **while queued** is dropped without
    raising anything; one cancelled **while its dialog is up** keeps its place until the answer
    arrives, so the next dialog is never raised on top of one the system still owns;
  - a launcher that throws fails that caller and frees the queue instead of blocking everyone
    behind a dialog that was never raised.
- `FlashPermissions.android.kt` uses it; the behaviour visible to the user (same system dialog, same
  moment, same two outcomes) is unchanged.

**Tests** — `ui/platform-shims/src/androidHostTest/.../PermissionRequestArbiterTest.kt` (7 tests):
two concurrent requests raise two dialogs in turn and both callers are answered; a denial reaches
the caller that raised the dialog; a caller cancelled while queued never raises a dialog and does
not block the next one; a caller cancelled while its own dialog is up does not steal the next
request; a permission granted meanwhile is answered without a second dialog; an already-granted
permission raises no dialog at all; a launcher that throws fails that caller and leaves the queue
usable.

**Mutation** — restoring the old "overtake" behaviour (`takeNext()` returning early while a request
is in flight) failed "two concurrent requests raise two dialogs in turn and both callers are
answered" (`:36`); restored.

**Not covered.** The real `ActivityResultContracts.RequestPermission` launcher is a framework seam →
`R22E-02`.

### F53 — stale `advertiseListener` after a failed registration (`FIXED`, but not as the audit says)

**Audit claim.** `NsdTransport.kt` sets `advertiseListener = listener` before `registerService()`, so
if registration fails the later unregister throws `IllegalArgumentException: listener not
registered`. **Fix: only set it on registration success.**

**Confirmed** — the assignment precedes the call and nothing withdraws it when the call throws.

**The audit's fix is wrong, and would introduce a leak.** There are two failure shapes:

1. `registerService(...)` **throws** — the framework never accepted the listener; `unregisterService`
   on it always throws, and a stale field also makes the next `advertise()` release it and the
   transport believe an advertisement is live.
2. registration **fails asynchronously** via `onRegistrationFailed` — the framework *has* the
   listener and holds it; it must stay releasable until that callback arrives. Setting the field only
   on `onServiceRegistered` (the audit's wording) leaves exactly this case without any handle, so
   the listener can never be unregistered.

**Changed** — `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt`
(`RealNsdManagerBridge.advertise`): the listener is still published **before** the call (so case 2
stays releasable), and `onFailure` now withdraws it (`if (advertiseListener === listener)
advertiseListener = null`) so case 1 leaves no stale reference in either direction. Identity is
compared, not blind-cleared, so a failure that arrives while a newer advertisement is live cannot
erase the newer listener.

**Tests.** None: `NsdManager` is a `final` framework class, `RealNsdManagerBridge` has no injected
`NsdManager` seam, and this is `androidMain`. Adding a seam to a class another stream's resolve work
shares was out of scope, so this is **compile-verified only** → `R22E-03` (and the mutation check
does not apply to a change no test can fail on — stated plainly rather than faked).

**Rule 8 note.** `NsdResolveQueue.kt` and the resolve path in `NsdTransport.kt` (~458-470) were not
touched; the edit is confined to `advertise` (~341).

### F61 — "missing `RECEIVER_NOT_EXPORTED`" on the Bluetooth receivers (`NOT-A-BUG`)

**Audit claim.** `AndroidRadioDiscovery.kt:164, 252` calls `context.registerReceiver(receiver,
filter)` without an export flag → "potential `SecurityException` on Android 14+". Fix:
`RECEIVER_NOT_EXPORTED`.

**Not a defect, and the suggested fix breaks the feature.**

- Android 14's runtime-receiver flag requirement has an explicit exemption: *"If your app is
  registering a receiver only for system broadcasts through `Context#registerReceiver` methods, such
  as `Context#registerReceiver()`, then it shouldn't specify a flag when registering the
  receiver."* — `https://developer.android.com/about/versions/14/behavior-changes-14`, section
  "Runtime-registered broadcasts receivers must specify export behavior" → "Exception for receivers
  that receive only system broadcasts" (fetched 2026-10-23).
- Both filters contain only Bluetooth system actions (`ACTION_FOUND`, `ACTION_DISCOVERY_FINISHED`,
  `ACTION_BOND_STATE_CHANGED`) — nothing this app or a third party sends.
- `RECEIVER_NOT_EXPORTED` is the one flag that **guarantees** the opposite of the intent: *"Some
  system broadcasts come from highly privileged apps, such as Bluetooth and telephony… If you flag
  your receiver with `RECEIVER_NOT_EXPORTED`, the receiver is able to receive some system broadcasts
  and broadcasts from your app, but not broadcasts from the highly privileged apps."* —
  `https://developer.android.com/develop/background-work/background-tasks/broadcasts` (fetched
  2026-10-23). Applying the audit's fix would silence radio discovery and the pairing result.
- Reported evidence: the owner's radio session that produced the ADR-214 symptom list ("pairs but
  does not stay connected") ran on an Android 14 handset, i.e. these two-argument registrations did
  not throw.

**Changed** — comments only, at both call sites, so the next reader does not apply the audit's fix.
No behaviour change. The residual uncertainty (whether the framework counts each of these three
actions as a "system broadcast" for its own check, which is framework-internal and not documented)
is recorded with the alternative: if a future Android version ever enforces the flag here, the
correct flag is `RECEIVER_EXPORTED`, **never** `NOT_EXPORTED`. Device check `R22E-07`.

### F62 — `offset + length` overflow in the two slice guards (`FIXED`)

**Confirmed.** In `KissStreamDecoder.feed` and `StreamFrameDecoder.feed`,
`require(offset >= 0 && length >= 0 && offset + length <= data.size)` wraps for a huge `length`:
`1 + Int.MAX_VALUE == Int.MIN_VALUE <= data.size`, so the guard passed. The KISS decoder then
reported success while consuming nothing and added `Int.MAX_VALUE` to `bytesIn`; the stream framer
failed later out of `copyOfRange` with `ArrayIndexOutOfBounds`/`OutOfMemoryError` instead of the
documented "bad slice".

**Changed** — both guards now use the subtraction form the audit suggests
(`offset <= data.size - length`), which cannot overflow because `length >= 0` and `data.size` is at
most `Int.MAX_VALUE`.

**Tests** — `KissFrameCodecTest.aSliceWhoseEndOverflowsAnIntIsRefused` (asserts
`IllegalArgumentException("bad slice")` and that `bytesIn` stayed 0) and
`StreamFramerTest.aSliceWhoseEndOverflowsAnIntIsRefused` (same, plus nothing buffered and no
`failed` flag).

**Mutation** — reverting both guards to the addition form failed both tests.

**Not covered.** No caller can feed a `Int.MAX_VALUE` slice from the UI; this is a guard correctness
fix, so no device test is owed (see the backlog note for `R22E-04`).

### F63 — EXIF lost behind JPEG fill bytes (`FIXED`)

**Confirmed.** `readExifOrientation`'s marker loop read one `0xFF` and took the next byte as the
marker. In `FF FF E1` (legal fill bytes, ITU T.81 B.1.1.2) the second `FF` became "the marker", the
real `E1` became the segment length's high byte, and the APP1/EXIF block was skipped as a 57 KB
segment → the photo rendered sideways.

**Changed** — `ui/platform-shims/src/jvmMain/.../FlashImageDecoder.jvm.kt`: skip consecutive `0xFF`
before reading the marker. The edit is confined to the marker loop (the cache key at ~57 belongs to
another stream and was not touched).

**Tests** — `FlashImageDecoderJvmTest`: the existing EXIF test was refactored onto a new
`exifJpeg(name, orientation, fillBytes)` helper, and a new
`exif orientation is read behind jpeg fill bytes` builds `FF FF FF FF E1` and expects orientation 6.

**Mutation** — removing the skip loop failed the new test (`:230`); restored.

**Not covered.** The Android decoder path is `BitmapFactory` and never had this loop, so the fix is
desktop-JVM only → `R22E-05`.

### F67 — "path traversal in `FlashMediaDecoder.resolveLocalFile`" (`CANNOT-CONFIRM`, no change)

Traced every source string that reaches the decoder:

- `decode(source = …)` is called from exactly two places, both with
  `image.uri ?: image.thumbUri` (`FlashImageGrid.kt:480-489`, `FlashMediaViewer.kt:483-487`).
- `FlashImageAttachmentUi.uri/thumbUri` is built in one place,
  `RealFlashChatRepository.kt:7133-7139`, from `path = live?.localPath?.ifBlank { null } ?:
  entity.attachmentPath` (`:7119`).
- `entity.attachmentPath` is only ever the **local sender's** own picked path (`localPath`, `:3127`,
  `:3221`) or `null` (`:3297`, `:3331`, `:3838`, `:3951` — every inbound row, including the group
  and swarm frame branches). A peer cannot put a path into a received row.
- `live.localPath` is the engine's own destination path for this device's transfer, not a wire field.
- `resolveLocalFile` refuses `content://`, canonicalises what is left, and requires an existing
  non-empty regular file. A local path there is the app's own file or a user-picked one — that is the
  feature, not traversal.
- Nothing egresses: the decoded `ImageBitmap` is returned to Compose and never encoded to the wire;
  `openStream` results are only decoded. So even a path that escaped containment would give a local
  file's pixels on the local screen — no remote read primitive.

**Status: cannot confirm.** No code changed. If the owner wants defence in depth anyway, the change
would be an allow-list of roots (received-files dir, cache, persisted SAF) in `resolveLocalFile`, at
the cost of breaking local full-screen viewing of a user-picked file whose path changed. Recorded as
an owner decision in `backlog-additions.md` § "Not fixed".

An existing host test (`FlashMediaDecoderSecurityTest`) already pins that a non-existent/stale path
decodes to `null`.

### F72 — multicast lock skipped on API 34+ (`DEFERRED`)

**What the code does.** `AndroidMulticastSocketFactory` skips the `WifiManager.MulticastLock` at
`apiLevel >= SDK_MULTICAST_LOCK_NOT_NEEDED` (34), on the theory that the T-extensions 7+ framework
manages multicast reception for apps. The audit says hardware drops multicast packets with the
screen off and lists "benchmark required" itself.

**Why not changed.** The skip is deliberate and battery-motivated (the platform reference is
explicit that multicast reception "can cause a noticeable battery drain and should be disabled when
not needed" —
`https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock`, fetched
2026-10-23). Nothing in `logs/experiments.md`, `docs/android-platform-notes.md` or the DR2/EXP
records shows beacon packet loss from this path; the field report that made `NsdTransport` take the
lock unconditionally was about **mDNS/NSD**, and `NsdTransport` already takes it on every API level
(`NsdApiThresholds` "NO LONGER USED"). Turning a whole-discovery-session lock on for every Android
14+ phone on the strength of an unmeasured audit claim is exactly what AGENTS §23 and the stream
prompt forbid.

**Changed** — the stale comment above `acquireMulticastLockIfNeeded` said the rule "mirrors
`NsdTransport`", which is no longer true; it now states the real position, the reference, and points
at DISC-07. No behaviour change.

**Decided by** the DISC-07 device measurement (already in `docs/testing/TEST-BACKLOG.md`, still
TODO); `R22E-06` repeats the exact pass condition. If it shows loss, the fix is to take the lock on
all levels; if it shows no loss, the skip stays and the measurement closes the item.

### F74 — no in-shim teardown for the audio player / voice recorder (`FIXED`)

**Confirmed** as a latent leak: the factories handed out objects owning a native `MediaPlayer` /
`MediaRecorder` with no `DisposableEffect`. Today's callers do dispose — `FlashVoiceMessageCard:193`,
`FlashSharedContentSheet:547`, `FlashConversationScreen:405` — so this is "the next caller leaks",
exactly as the audit says (no current leak proven).

**Why it is safe to add the second release** (the audit's own warning): both implementations null
their handle before releasing —

- `AndroidAudioPlayer.release()` (`FlashAudioPlayer.android.kt:110-112`) sets `player = null`;
- `AndroidVoiceRecorder.cancel()` (`FlashVoiceRecorder.android.kt:112-121`) takes the handle into a
  local, nulls the field, then `runCatching { rec.stop() }` + release.

So a caller's dispose plus the shim's dispose is a no-op, not a double release.

**Changed** — `FlashAudioPlayer.android.kt` and `FlashVoiceRecorder.android.kt`: each factory now
wraps its `remember`ed instance in `DisposableEffect(instance) { onDispose { … } }`, so a bubble that
leaves the composition (new uri, recycled list row, leaving the screen) can no longer keep a
prepared player or an open encoder alive.

**Tests.** None at host level (native `MediaPlayer`/`MediaRecorder` need a device and the repo has no
Compose UI-test harness); the guarantee rests on the two `null`-before-release implementations above
plus `R22E-05` on a device.

---

## Verification runs

All through the lock script, from the worktree root (AGENTS §36).

| Run | Command | Result |
|---|---|---|
| Green (new tests) | `tools\flash-gradle.cmd :core:radio:jvmTest :ui:platform-shims:jvmTest :ui:platform-shims:testAndroidHostTest --console=plain` | BUILD SUCCESSFUL, 2m33s |
| Mutation (production reverted) | the same three tasks | FAILED as designed: `FlashCameraCaptureRestoreTest` ×2 (`:49`, `:57`), `PermissionRequestArbiterTest` `:36`, `FlashImageDecoderJvmTest` `:230` (40 tests, 1 failed in jvmTest), `KissFrameCodecTest` + `StreamFramerTest` (198 tests, 2 failed) |
| Green (restored) | `tools\flash-gradle.cmd :core:radio:jvmTest :ui:platform-shims:jvmTest :ui:platform-shims:testAndroidHostTest :core:discovery:jvmTest :core:discovery:testAndroidHostTest :app:compileDebugKotlin :desktop:compileKotlinJvm --console=plain` | BUILD SUCCESSFUL, 7m8s; `:app:compileDebugKotlin` and `:desktop:compileKotlinJvm` both ran |

`F53` needs `:core:discovery:*` to compile (no test can reach `RealNsdManagerBridge`); `:app` and
`:desktop` are included because `:ui:platform-shims` and `:core:radio` are shared by both hosts.

### Problems during the run

- The mutation Gradle invocation hung after printing its failures (client JVM alive, daemon idle for
  minutes). The client chain was killed, which released the global build lock, and the restored run
  proceeded normally. Recorded so the next stream does not spend time waiting on a dead client.

---

## Final report

**Branch** `agent/rvw22-e-platform` (worktree `E:\FlashWork\rvw22-e-platform`), one commit per
finding, never pushed, `dev`/`main` untouched.

| Finding | Status | Commit |
|---|---|---|
| F50 | FIXED | `0d6dec8c` |
| F51 | FIXED | `ff30c485` |
| F53 | FIXED (not as the audit proposed) | `e7a021ed` |
| F61 | NOT-A-BUG (documented in code) | `81398a57` |
| F62 | FIXED | `894abf99` |
| F63 | FIXED | `f202ca55` |
| F67 | CANNOT-CONFIRM | (no code, nothing to commit) |
| F72 | DEFERRED (DISC-07) | `bd3ba618` |
| F74 | FIXED | `79c6d002` |

Branch head after these commits is the `docs(logs): record stream E …` commit that contains this
file; `dev` and `main` were not touched and nothing was pushed.

**Files changed**

- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiter.kt` (new)
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashPermissions.android.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.android.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt`
- `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt`
- `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/multicast/AndroidMulticastSocketFactory.kt` (comment)
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/AndroidRadioDiscovery.kt` (comment)
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodec.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/StreamFramer.kt`

**Tests added** — `FlashCameraCaptureRestoreTest` (4), `PermissionRequestArbiterTest` (7),
`FlashImageDecoderJvmTest` (+1, one refactored), `KissFrameCodecTest` (+1), `StreamFramerTest` (+1).

**Mutation checks** — 5 production reversions, every one caught by a new test (4 findings); F53 and
F74 have no test seam and say so above.

**What remains**

1. Device checks `R22E-01`…`R22E-07` in `backlog-additions.md` (camera across rotation/death, two
   permission dialogs, NSD re-advertise after a failure, JPEG fill bytes on the desktop app, repeated
   voice playback/recording, DISC-07 for the multicast lock, and an Android 14+ radio discovery
   regression watch for F61).
2. Nothing is device-verified; no phone was touched, no remote host was contacted.
3. F67 stays closed unless the owner asks for defence in depth; F72 stays open until DISC-07 runs.
