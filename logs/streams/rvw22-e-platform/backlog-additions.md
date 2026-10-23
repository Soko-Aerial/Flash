# Backlog Additions — Stream E (`rvw22-e-platform`)

Device / manual checks owed by the fixes in `progress.md` (review 2026-10-22: F50, F51, F53, F61,
F62, F63, F72, F74). Nothing in this stream was device-verified. Move these into
`docs/testing/TEST-BACKLOG.md` when the owner merges the branch; do not delete them.

---

### R22E-01 — A captured photo survives a rotation and a process death (F50, ERROR-800)

- **Setup:** an Android phone. In Developer options enable **Don't keep activities** for step 2.
- **Steps:**
  1. Open a chat, attach → Camera, take a photo, and rotate the phone while the camera app is in
     front; return to Flash.
  2. Repeat with **Don't keep activities** on, and with the "Background process limit" set to
     *No background processes* if the device offers it, so the app's process is killed while the
     camera is in front.
  3. Repeat once normally (no rotation) as the control.
- **Pass:** in all three cases the photo appears in the composer as a normal attachment with a
  thumbnail; sending it produces an image that opens. No empty bubble, no crash, and no
  `FlashMediaDecoder … Decode failed` line for a freshly captured photo.
- **Fail:** the photo is missing after the rotation/process death (that is F50 unfixed), or a bubble
  appears that decodes to nothing (that is the "stale path" half of the fix).
- **Source:** review 2026-10-22 F50; `ERROR-800`; `FlashCameraCaptureRestoreTest` (host half).
- **Status:** TODO

---

### R22E-02 — Two permission dialogs are served in turn, each caller gets its own answer (F51, ERROR-801)

- **Setup:** a fresh install (or Settings → Apps → Flash → Permissions, deny both), with the camera
  and microphone permissions **not** granted.
- **Steps:**
  1. Open a chat. Trigger the microphone request (tap the voice-note mic) and, while that system
     dialog is still up, trigger the camera (attach sheet → Camera) — the second request must queue,
     not replace the first.
  2. Answer the microphone dialog **Allow**. Then answer the camera dialog **Deny**.
  3. Repeat with the answers reversed (microphone **Deny**, camera **Allow**).
  4. Repeat with both denied.
- **Pass:** exactly one dialog is on screen at a time; the second appears only after the first is
  answered. After step 2 a recording starts and the camera reports it cannot open; after step 3 the
  camera opens and no recording starts. Nothing hangs, and the flows that were denied show their
  normal "permission needed" message rather than silently doing nothing.
- **Fail:** the second dialog never appears / the first action never continues (the F51 hang), or an
  action proceeds on the *other* permission's grant (the cross-answer).
- **Source:** review 2026-10-22 F51; `ERROR-801`; `PermissionRequestArbiterTest` (host half).
- **Status:** TODO

---

### R22E-03 — A failed NSD registration does not wedge advertising (F53, ERROR-802)

- **Setup:** two phones on the same Wi-Fi, Flash open on both.
- **Steps:**
  1. On phone A, toggle Wi-Fi off and on several times while Flash is in the foreground, then leave
     both phones idle for a minute.
  2. On phone B, open Nearby / the device list and watch for A.
  3. Send a message from B to A.
- **Pass:** A is discovered again within ~30 s of its Wi-Fi returning; the message arrives; logcat on
  A shows no `IllegalArgumentException: listener not registered` (the NSD `DISCOVERY` tag) and no
  repeated "Failed to initiate NSD registration" without a later successful registration.
- **Fail:** A stays invisible until the app is restarted, or the exception appears (F53 unfixed).
- **Source:** review 2026-10-22 F53; `ERROR-802`.
- **Status:** TODO

---

### R22E-04 — A JPEG with fill bytes shows upright on the Windows app (F63, ERROR-804)

- **Setup:** the Windows desktop app plus an Android phone. A JPEG whose APP1/EXIF segment is
  preceded by `0xFF` fill bytes (some cameras and editors emit them; the host test builds one by
  hand — `exifJpeg(..., fillBytes = 3)` in `FlashImageDecoderJvmTest`).
- **Steps:** send that photo from the phone to the desktop app; look at the chat thumbnail and open
  it full screen.
- **Pass:** both the thumbnail and the full view are upright (the EXIF orientation is applied),
  matching the phone and any other viewer.
- **Fail:** the desktop app shows it rotated a quarter turn (F63 unfixed on that file).
- **Source:** review 2026-10-22 F63; `ERROR-804`; `FlashImageDecoderJvmTest` (host half).
- **Status:** TODO

---

### R22E-05 — Repeated voice playback and recording release their native handles (F74, ERROR-805)

- **Setup:** an Android phone with logcat filtered on `MediaPlayer` / `MediaRecorder`.
- **Steps:**
  1. Play a voice note and leave the chat while it is playing; come back and play it again. Repeat
     about 20 times.
  2. Open Shared content → Audio on a chat with voice notes, play one, close the sheet mid-playback.
  3. Start a voice recording, leave the chat/screen mid-recording (back gesture), then record again
     twice.
- **Pass:** playback stops when the bubble/sheet leaves the screen and never overlaps the next
  playback; recording stops when the screen is left and the next recording starts normally. No
  `MediaPlayer: error (-38…)` / "start called in state" / `MediaRecorder: start failed` lines, and no
  audio continues after the app is backgrounded.
- **Fail:** audio keeps playing after leaving, two clips play at once, or the recorder refuses to
  start after a cancelled recording (a double release would show up here).
- **Source:** review 2026-10-22 F74; `ERROR-805`.
- **Status:** TODO

---

### R22E-06 — Multicast lock at API 34+: run DISC-07 (F72, deferred)

The measurement that decides F72 is **already written** as
`docs/testing/TEST-BACKLOG.md` § DISC-07 ("Does the multicast transport hear peers when NSD is idle
on Android 14+?"), still TODO. Run it as written; no duplicate is created here.

- **If it fails** (the multicast age grows through the idle phase and only NSD browsing resets it):
  the fix is to take the `WifiManager.MulticastLock` on every API level in
  `AndroidMulticastSocketFactory` (the same rule `NsdTransport` already applies), with the battery
  cost the owner has to accept.
- **If it passes:** the skip stays, and the comment added on 2026-10-23 already points at this
  measurement.
- **Source:** review 2026-10-22 F72 (`DEFERRED`); `AndroidMulticastSocketFactory.kt`.
- **Status:** TODO

---

### R22E-07 — Radio discovery and pairing still work on Android 14+ without an export flag (F61, NOT-A-BUG)

Not a regression test for a fix — a check that keeps the `NOT-A-BUG` verdict honest, because the
audit claimed a `SecurityException` risk.

- **Setup:** an Android 14+ phone (the Infinix is API 34), Settings → Radio Gateway.
- **Steps:** scan for the radio (VR-N76 powered on, KISS TNC enabled), pair it if not already paired,
  and connect.
- **Pass:** the radio appears in the candidate list (a `BluetoothDevice.ACTION_FOUND` broadcast
  arrived at a receiver registered with no export flag) and the pairing result is delivered (no
  stall at "Pairing"), with no `SecurityException` naming `registerReceiver` in logcat.
- **Fail:** the scan finds nothing and logcat shows
  `SecurityException: … One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified …`.
  The fix in that case is `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_EXPORTED)` —
  **never** `RECEIVER_NOT_EXPORTED`, which would silence the Bluetooth app's broadcasts.
- **Source:** review 2026-10-22 F61; `platform-notes-additions.md` (the two official-doc quotes).
- **Status:** TODO

---

## Coverage notes — no device test owed

- **F62 (ERROR-803).** The overflow needs a caller-supplied `Int.MAX_VALUE` slice; no app path builds
  one, and the two decoders are fed by the radio link and the in-process framer. The guard is covered
  by `KissFrameCodecTest` / `StreamFramerTest` plus the mutation check; a device run would test
  nothing this does not.
- **F53 (ERROR-802)** has no unit test because `NsdManager` is a final framework class with no
  injected seam in `RealNsdManagerBridge`; `R22E-03` is the only check that can prove the fix.
- **F74 (ERROR-805)** has no host test because `MediaPlayer`/`MediaRecorder` need a device and the
  repo has no Compose UI-test harness; `R22E-05` is the check.

## Not fixed — owner decision

- **F67 (path traversal in `FlashMediaDecoder.resolveLocalFile`)** — `CANNOT-CONFIRM`, no code
  changed (trace in `progress.md`). If the owner wants defence in depth anyway, the change is an
  allow-list of roots (received-files directory, cache, persisted SAF trees) inside
  `resolveLocalFile`, accepting that viewing a user-picked local file whose path later changes would
  stop working. Not built, because no remote-controlled path exists to confine: every inbound
  attachment row stores `attachmentPath = null`.
