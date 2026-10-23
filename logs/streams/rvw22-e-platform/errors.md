# Errors — Stream E (`rvw22-e-platform`)

Reserved range ERROR-800..809. Every one here is fixed in code, unit/host-tested and
mutation-checked, and **not device-verified**; that is why each status is OPEN (AGENTS §7: do not
mark a problem resolved until it has been tested). The device check that closes each one is named in
`backlog-additions.md`.

Environment for all entries unless stated otherwise:

- App `targetSdk 36`, `minSdk 24`; `:ui:platform-shims` `compileSdk 37`; `:core:discovery` and
  `:core:radio` Android + JVM targets.
- Reviewer host: Windows 11, JDK from the Gradle toolchain, no phone attached.
- Device context quoted from the repository: Infinix X6882B, Android 14 (API 34) is the owner's
  reference phone (`AGENTS.md`, ORGT-B01).

---

## ERROR-800 — A photo taken with the camera is silently dropped after a rotation or a process death

### Date
2026-10-23

### Area
Android / `:ui:platform-shims` — `rememberFlashCameraCaptureLauncher` (review 2026-10-22, F50)

### Symptoms
Take a photo from the chat composer. If the device rotates while the camera app is in front, or
Android kills the app's process while the camera is in front, the photo never appears: the composer
shows nothing, no error is raised, and the file is left orphaned in the app cache.

### Error
No exception. The result callback simply saw `success == true` with `currentUri == null` and
`currentFile == null` and did nothing:

```kotlin
val uri = currentUri
val file = currentFile
if (success && uri != null && file != null && file.exists() && file.length() > 0) { onCaptured(…) }
```

### Root cause
`ActivityResultContracts.TakePicture()` runs in the camera app, i.e. in another process. While it is
in front, the Compose composition can be destroyed (configuration change) or the whole process can be
killed for memory. The `ActivityResultRegistry` replays the pending result into a **new** composition,
but `currentUri`/`currentFile` were held in plain `remember`, which does not survive either event.
So the two values the callback needs are gone exactly when the result arrives.

### Failed attempts
1. "Use `rememberSaveable`" as the audit suggests — not directly possible: `android.net.Uri` and
   `java.io.File` are not Bundle-safe, so there is no default `Saver` for them. Saving the objects
   themselves throws at save time.
2. Trusting the restored path — a `rememberSaveable` string survives, but the cache directory (where
   the temp capture lives) is the platform's to clear, and a camera killed mid-write leaves a
   zero-length file. Reporting either as a photo produces a bubble that decodes to nothing.

### Working fix
`FlashCameraCapture.android.kt`:

- keep the capture as two `String`s in `rememberSaveable` (`uri.toString()`, `file.absolutePath`);
- rebuild the result through a new pure helper `restoredCapture(uri, path)`, which returns `null`
  unless both strings are non-blank **and** the path is an existing, non-empty regular file, and only
  then constructs the `FlashPickedFile`.

### Verification
`FlashCameraCaptureRestoreTest` (host, 4 tests): live file reported with the saved uri/name/size;
deleted path → nothing; zero-length file → nothing; blank/null values → nothing. Mutation check:
with the `isFile`/`length() > 0` check removed, 2 of the 4 fail. Unit-tested only — that the platform
really restores `rememberSaveable` and replays the pending result across a real process death is not
covered (device check `R22E-01`).

### Related files
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt`
- `ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashCameraCaptureRestoreTest.kt`

### Status
OPEN (fixed in code, unit-tested, mutation-checked, not device-verified)

---

## ERROR-801 — Two permission requests at the same time hang the first caller and can answer it with the other permission's result

### Date
2026-10-23

### Area
Android / `:ui:platform-shims` — `rememberFlashPermissionRequester` (review 2026-10-22, F51)

### Symptoms
With two permissions missing (for example the microphone and the camera), trigger a second request
while the first system dialog is still up — e.g. tap the voice-note mic and then the camera attach in
the same chat. The first action never continues (the coroutine stays suspended for the rest of the
screen's life), and whichever flow survives can receive the answer that belongs to the other
permission: granting the camera can make the microphone request report "granted".

### Error
No exception; a coroutine suspension leak plus a cross-answer.

### Root cause
`ActivityResultContracts.RequestPermission()` has one callback and no request id, and the shim kept
exactly one pending continuation in a field:

```kotlin
pending.continuation = continuation
launcher.launch(permission.manifestName)
```

A second `ensureGranted` overwrote the field; the first continuation was never resumed, and the
single launcher callback resumed whichever continuation happened to be in the field when the dialog
was answered — which may be the one for a different permission.

### Failed attempts
1. "Guard `ensureGranted` with a mutex", as the audit suggests — insufficient and in the worst case a
   deadlock: a caller holds the lock while suspended waiting for a dialog that the *next* caller is
   supposed to raise. It also still resumes a caller with an answer it cannot attribute to its
   request, because nothing carries request identity across the launcher.
2. Resuming on a `launch` that throws by simply dropping the continuation — leaves the caller
   suspended and blocks every later request behind a dialog that was never raised.

### Working fix
New `PermissionRequestArbiter` (pure Kotlin, no `android.*`, host-testable):

- one dialog at a time, later requests queued;
- `isGranted` re-read before every dialog, so a request already satisfied raises none;
- every waiter completed exactly once; a waiter cancelled while queued is dropped without raising
  anything, and one cancelled while its own dialog is up keeps its place until the answer arrives so
  the next dialog is never raised on top of one the system still owns;
- a launcher that throws fails that caller and frees the queue.

`FlashPermissions.android.kt` now delegates to it; the user-visible behaviour (same dialog, same
moment, same outcomes) is unchanged.

### Verification
`PermissionRequestArbiterTest` (host, 7 tests) covers the two-concurrent-requests case, denial,
cancellation before and after the dialog is up, "granted meanwhile", "already granted", and a
throwing launcher. Mutation check: restoring the old overtake behaviour fails the concurrency test.
Unit-tested only; the device check is `R22E-02`.

### Related files
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiter.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashPermissions.android.kt`
- `ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiterTest.kt`

### Status
OPEN (fixed in code, host-tested, mutation-checked, not device-verified)

---

## ERROR-802 — A failed NSD service registration leaves a listener the framework does not know, so unregistering throws

### Date
2026-10-23

### Area
Android / `:core:discovery` — `RealNsdManagerBridge.advertise` (review 2026-10-22, F53)

### Symptoms
After a transient network problem while advertising (Wi-Fi toggled, interface not ready),
`unregisterService`/`stopAdvertising` throws `java.lang.IllegalArgumentException: listener not
registered` from `NsdManager`, and the next `advertise()` believes an advertisement is already live:
this device is not discoverable, and the app's own state disagrees with the platform's.

### Error
```text
java.lang.IllegalArgumentException: listener not registered
    at android.net.nsd.NsdManager.unregisterService(NsdManager.java:…)
```

### Root cause
`advertiseListener = listener` was assigned **before** `nsdManager.registerService(...)`, and
`registerService` can fail in two different ways:

1. it **throws** (the framework never accepted the listener, and `unregisterService` on it always
   throws), or
2. it returns normally and reports failure later through `onRegistrationFailed` (the framework *has*
   the listener and holds it until unregister is called).

The single assignment could not distinguish them: in case 1 the field kept a listener the platform
does not know; leaving it out until `onServiceRegistered` (the audit's suggested fix) instead leaks
it in case 2, where it must stay releasable.

### Failed attempts
1. Setting the field only after `registerService` returns `true` — that is "call succeeded", not
   "registration succeeded", so the async-failure case still needs a handle; and clearing it only in
   `onServiceRegistered` leaves an accepted-but-failed registration unreachable.
2. Clearing the field unconditionally in `onFailure` — a failure arriving after a newer
   advertisement was registered would erase the newer listener. The fix compares identity.

### Working fix
Publish the listener before the call (case 2 stays releasable as today) and, when the call itself
throws, withdraw it (`if (advertiseListener === listener) advertiseListener = null`) so case 1 leaves
no stale reference in either direction.

### Verification
**Compile-verified only.** `NsdManager` is a final framework class and `RealNsdManagerBridge` takes no
injected `NsdManager`, so no host test can reach this branch without adding a seam to a class shared
with another stream's resolve work (rule 8). `:core:discovery:jvmTest` and
`:core:discovery:testAndroidHostTest` compile and pass; device check `R22E-03` is owed.

### Related files
- `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt`
  (`RealNsdManagerBridge.advertise`, ~341; `NsdResolveQueue` and the resolve path were not touched)

### Status
OPEN (fixed in code, compile-verified, not unit-tested, not device-verified)

---

## ERROR-803 — The slice guards of the KISS and stream framers accept an overflowing end offset

### Date
2026-10-23

### Area
`:core:radio` — `KissStreamDecoder.feed`, `StreamFrameDecoder.feed` (review 2026-10-22, F62)

### Symptoms
None observed in the field; the guards are public API and part of a wire decoder.

### Error
`feed(data, offset = 1, length = Int.MAX_VALUE)`:

- `KissStreamDecoder`: the guard passed, `bytesIn` grew by `Int.MAX_VALUE`, and no input was consumed
  while the call reported success.
- `StreamFrameDecoder`: the guard passed and the failure surfaced from `copyOfRange` as
  `ArrayIndexOutOfBoundsException` / `OutOfMemoryError` instead of the documented "bad slice".

### Root cause
`require(offset >= 0 && length >= 0 && offset + length <= data.size)` evaluates `offset + length` in
`Int`: `1 + Int.MAX_VALUE` wraps to `Int.MIN_VALUE`, which is `<= data.size`, so the last condition
was always true for a huge `length`.

### Failed attempts
None: the arithmetic cause is unambiguous and the subtraction form the audit suggests is correct.

### Working fix
Both guards now read `require(offset >= 0 && length >= 0 && offset <= data.size - length)`; `length`
is non-negative and `data.size <= Int.MAX_VALUE`, so the right-hand side cannot overflow.

### Verification
`KissFrameCodecTest.aSliceWhoseEndOverflowsAnIntIsRefused` and
`StreamFramerTest.aSliceWhoseEndOverflowsAnIntIsRefused`. Mutation check: reverting both guards to the
addition form fails both tests.

### Related files
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodec.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/StreamFramer.kt`
- `core/radio/src/commonTest/.../KissFrameCodecTest.kt`, `.../StreamFramerTest.kt`

### Status
OPEN (fixed in code, unit-tested, mutation-checked; no device path reaches it — see the backlog note)

---

## ERROR-804 — A JPEG whose EXIF marker is behind 0xFF fill bytes is decoded sideways

### Date
2026-10-23

### Area
Desktop JVM / `:ui:platform-shims` — `JvmImageDecoder.readExifOrientation` (review 2026-10-22, F63)

### Symptoms
On the Windows app, some JPEGs are shown rotated 90°/180° (thumbnail and full view), while the same
file looks right in other viewers. Only files whose segments are preceded by fill bytes are affected.

### Error
No error: the orientation is simply reported as 1 (normal).

### Root cause
The marker loop read `0xFF`, then took the next byte as the marker:

```kotlin
if (markerPrefix != 0xFF) continue
val marker = input.read()
```

JPEG allows any number of `0xFF` fill bytes before a marker (ITU T.81 B.1.1.2). In `FF FF E1` the
second `FF` was treated as the marker byte, so the real `E1` became the segment length's high byte and
the APP1/EXIF segment was skipped as a bogus 57 600-byte blob — the orientation tag was never read.

### Failed attempts
None: the standard is explicit, and skipping the run of `0xFF` is the accepted handling.

### Working fix
`var marker = input.read(); while (marker == 0xFF) marker = input.read()` before the existing
`-1 / SOS / EOI` checks. Confined to the marker loop; the cache key in the same file belongs to
another stream and was not touched.

### Verification
`FlashImageDecoderJvmTest.exif orientation is read behind jpeg fill bytes` (builds `FF FF FF FF E1`),
with the pre-existing EXIF test refactored onto the same `exifJpeg(...)` helper. Mutation check:
removing the skip loop fails the new test (`:230`).

### Related files
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt`
- `ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/FlashImageDecoderJvmTest.kt`

### Status
OPEN (fixed in code, unit-tested, mutation-checked, not device-verified — desktop check `R22E-05`)

---

## ERROR-805 — The audio player and voice recorder shims own a native handle but no teardown

### Date
2026-10-23

### Area
Android / `:ui:platform-shims` — `rememberFlashAudioPlayer`, `rememberFlashVoiceRecorder` (review
2026-10-22, F74)

### Symptoms
Latent: a caller that does not dispose an instance leaves a prepared `MediaPlayer` (or an open
`MediaRecorder`) alive after the screen that used it is gone. Audio can keep playing over another
chat; recording can keep the microphone open.

### Error
No exception; leaked native handles.

### Root cause
Both `remember…` factories returned a `remember`ed object with no `DisposableEffect`, so the shim
relied entirely on every caller remembering to release it. The three current callers do
(`FlashVoiceMessageCard`, `FlashSharedContentSheet` for the player, `FlashConversationScreen` for the
recorder), which is why no leak is proven today — the defect is that the shim's contract allowed one.

### Failed attempts
Adding a teardown without checking idempotency would have caused a double release from the three
callers that already dispose. Checked first: `AndroidAudioPlayer.release()` nulls `player` before
releasing, and `AndroidVoiceRecorder.cancel()` takes the handle into a local, nulls the field, then
`runCatching { rec.stop() }` + release. Both are safe to call twice.

### Working fix
Each factory wraps its instance in `DisposableEffect(instance) { onDispose { … } }`:
`player?.release()` and `recorder.cancel()`.

### Verification
Read-through of both implementations (idempotency) and of every caller. No host test: native
`MediaPlayer`/`MediaRecorder` need a device, and the repo has no Compose UI-test harness. Device check
`R22E-06` is owed.

### Related files
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.android.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.android.kt`

### Status
OPEN (fixed in code, not unit-tested, not device-verified)

---

## Not an error (recorded so the next reader does not "fix" them)

- **F61 — `RECEIVER_NOT_EXPORTED` on `AndroidRadioDiscovery`'s Bluetooth receivers.** NOT-A-BUG; the
  flag requirement exempts system-broadcast-only receivers, and `RECEIVER_NOT_EXPORTED` would stop
  the Bluetooth app's broadcasts (URLs in `platform-notes-additions.md`). Comments added at both call
  sites instead.
- **F67 — path traversal in `FlashMediaDecoder.resolveLocalFile`.** CANNOT-CONFIRM; no inbound row
  ever stores a peer-supplied path (`RealFlashChatRepository.kt:3297/3331/3838/3951` all
  `attachmentPath = null`) and nothing returns decoded pixels to the wire. No change.
- **F72 — multicast lock skipped at API 34+.** DEFERRED to the DISC-07 measurement, not a silent fix.
  Only the stale "mirrors `NsdTransport`" comment was corrected.
