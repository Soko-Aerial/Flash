# Platform Notes — Stream E (`rvw22-e-platform`)

Platform facts checked while fixing review 2026-10-22 F50/F51/F53/F61/F63/F72/F74 (AGENTS §13).
Each entry names the URL, the date it was checked, and its evidence level:
**verified** = read on the official page/source that date, **reported** = taken from a secondary
statement or from the repository, **community claim** = neither.

---

## PN-E1 — The Android 14 runtime-receiver export flag exempts system-broadcast-only receivers, and `RECEIVER_NOT_EXPORTED` silences Bluetooth broadcasts

- **Android version / API:** 14 (API 34) and later; the app's `targetSdk` is 36, so the requirement
  applies to it.
- **API involved:** `Context.registerReceiver(BroadcastReceiver, IntentFilter)` vs
  `ContextCompat.registerReceiver(…, RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED)`.
- **Facts (verified, 2026-10-23):**
  - `https://developer.android.com/about/versions/14/behavior-changes-14` → "Apps targeting Android
    14 or higher" → "Runtime-registered broadcasts receivers must specify export behavior":
    *"Apps and services that target Android 14 (API level 34) or higher and use context-registered
    receivers are required to specify a flag to indicate whether or not the receiver should be
    exported … either `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED`."*
    Sub-section **"Exception for receivers that receive only system broadcasts"**: *"If your app is
    registering a receiver only for system broadcasts through `Context#registerReceiver` methods,
    such as `Context#registerReceiver()`, then it shouldn't specify a flag when registering the
    receiver."*
  - `https://developer.android.com/develop/background-work/background-tasks/broadcasts` →
    "Receivers and permissions": *"Some system broadcasts come from highly privileged apps, such as
    Bluetooth and telephony, that are part of the Android framework but don't run under the system's
    unique process ID (UID). To receive all system broadcasts, including broadcasts from highly
    privileged apps, flag your receiver with `RECEIVER_EXPORTED`."* and *"If you flag your receiver
    with `RECEIVER_NOT_EXPORTED`, the receiver is able to receive some system broadcasts and
    broadcasts from your app, but not broadcasts from the highly privileged apps."*
- **Project implication:** `AndroidRadioDiscovery` registers for `ACTION_FOUND`,
  `ACTION_DISCOVERY_FINISHED` and `ACTION_BOND_STATE_CHANGED` only — the configuration the first
  sub-section describes. No flag is required and **`RECEIVER_NOT_EXPORTED` (the review's suggested
  fix) would break radio discovery and pairing**. This is why F61 is `NOT-A-BUG`; the two call sites
  now carry a comment saying so. If a future Android version ever enforces the flag for these
  actions, the correct value is `RECEIVER_EXPORTED` — never `NOT_EXPORTED`.
- **Residual uncertainty:** which actions the framework counts as "system broadcasts" for its own
  runtime check is framework-internal and not documented on these pages, so the verdict rests on the
  documented rule plus the owner's Android 14 radio session (ADR-214's symptom report implies these
  registrations did not throw). Device check `R22E-07` keeps it honest.
- **Evidence level:** verified (both pages), plus reported (the ADR-214 inference).

---

## PN-E2 — `WifiManager.MulticastLock`: without it the Wi-Fi stack filters multicast out; with it the battery cost is real

- **Android version / API:** `MulticastLock` is not tied to an API level; the project's skip is
  `apiLevel >= 34`. Permissions: `ACCESS_WIFI_STATE` for `createMulticastLock` (already held).
- **Fact (verified, 2026-10-23):**
  `https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock` — *"Normally
  the Wifi stack filters out packets not explicitly addressed to this device. Acquiring a
  MulticastLock will cause the stack to receive packets addressed to multicast addresses.
  Processing these extra packets can cause a noticeable battery drain and should be disabled when
  not needed."*
- **Project implication:** the reference states the filtering default and the power cost; it does
  **not** say that any API level acquires the lock for the app. The repository's skip at API 34+ is
  therefore a bet on framework/T-extensions behaviour, not a documented guarantee — but the audit's
  "acquire it on all levels" is equally unproven and pays the battery cost the platform warns about
  on every phone. F72 is `DEFERRED` to the DISC-07 measurement (age of a multicast-only peer during
  an NSD-idle phase), not silently changed.
- **Evidence level:** verified (reference quote); the skip's original justification is `reported`
  (repository history, `NsdApiThresholds`).
- **Note on the neighbouring path:** `NsdTransport` now takes the lock on **every** API level after a
  field report that a backgrounded foreground-service with the screen off received no mDNS without
  it. That report is about NSD/mDNS, not about the DR2 beacon this factory serves, so it is not
  evidence for F72.

---

## PN-E3 — A camera capture must survive both a configuration change *and* system-initiated process death; the pending activity result is restored for you

- **Android version / API:** all supported levels; `rememberSaveable` from Compose runtime.
- **Facts (verified, 2026-10-23):**
  - `https://developer.android.com/develop/ui/compose/state-saving` lists the two cases that destroy
    the UI state — *"Configuration changes. The activity is destroyed and recreated …"* and
    *"System-initiated process death. The app is in the background and the device frees up resources
    (like memory) to be used by other processes."* — and says *"`rememberSaveable` stores UI element
    state in a `Bundle` through the saved instance state mechanism. It is able to store primitive
    types to the bundle automatically."*
  - AndroidX source `ActivityResultRegistry.kt` (`androidx-main`, read 2026-10-23) saves
    `KEY_COMPONENT_ACTIVITY_PENDING_RESULTS` and `KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS` in
    `onSaveInstanceState` and restores them in `onRestoreInstanceState`, i.e. a **pending
    `TakePicture` result is delivered to the recreated composition** — the app does not have to
    relaunch the camera.
- **Project implication:** `rememberFlashCameraCaptureLauncher` must keep its own capture in
  `rememberSaveable` (the result will arrive, but the uri/path it needs are ordinary state that is
  not restored automatically). Only primitives are automatic, which is why the capture is stored as
  two `String`s. Because the cache directory can be cleared by the platform, the restored path is
  re-validated (`isFile` and non-empty) before it is reported as a photo.
- **Evidence level:** verified (Compose page and AndroidX source).
- **Limit:** the host test covers the restore contract, not the platform replay — `R22E-01`.

---

## PN-E4 — `NsdManager.registerService` can fail synchronously *or* through `onRegistrationFailed`

- **API involved:** `NsdManager.registerService(serviceInfo, protocol, listener)` (`android.net.nsd`).
- **Fact (reported, consistent with the platform API):** the call can throw (the listener is then not
  registered at all) and can also report a failure later via
  `NsdManager.RegistrationListener.onRegistrationFailed`, which only exists because the framework
  accepted the listener and holds it. `unregisterService` on a listener the framework never accepted
  throws `IllegalArgumentException`.
- **Project implication:** `RealNsdManagerBridge.advertise` must publish its listener *before* the
  call (so the asynchronous failure stays releasable) and withdraw it when the call itself throws —
  which is the opposite order from the review's "only set it on success". `ERROR-802`.
- **Evidence level:** reported (no official page fetched this session states the two failure shapes
  explicitly; the callback's existence is the platform contract). Device check `R22E-03`.

---

## PN-E5 — JPEG markers may be preceded by any number of `0xFF` fill bytes

- **Standard:** ITU T.81 (JPEG), B.1.1.2 — fill bytes before a marker are legal and encoders emit
  them; a decoder must skip them.
- **Project implication:** `JvmImageDecoder.readExifOrientation` reads the marker byte after skipping
  a run of `0xFF`; otherwise `FF FF E1` makes the EXIF segment disappear and the photo renders
  sideways. `ERROR-804`. The Android path uses `BitmapFactory`, which handles this itself.
- **Evidence level:** verified (standard; the host test builds `FF FF FF FF E1`).

---

## PN-E6 — `MediaPlayer` / `MediaRecorder` have no idempotency guarantee, so the shim nulls first

- **API involved:** `android.media.MediaPlayer.release()`,
  `android.media.MediaRecorder.stop()/reset()/release()`.
- **Fact (reported):** the platform documents `release()` as releasing resources but does not promise
  that a second call is a no-op, and `MediaRecorder.stop()` throws when stopped too quickly or twice.
- **Project implication:** the shims added in F74 are only safe to layer under callers that already
  dispose because both implementations null their handle **before** touching the native object
  (`FlashAudioPlayer.android.kt:110-112`, `FlashVoiceRecorder.android.kt:112-121`, the latter inside
  `runCatching`). Any future change to either must keep "null first, then release".
- **Evidence level:** reported (platform habit, not a fetched statement).
