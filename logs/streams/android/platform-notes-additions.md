# Stream G (Android) — Platform Notes Additions

These are the Android platform facts Wave 3 relied on, each marked *verified* (the page was opened and says this) or
*reported* (believed true, no source opened) per AGENTS.md §13. The integrator folds them into
`docs/android-platform-notes.md`, keeping the markers.

---

## 1. PowerManager.WakeLock.acquire(long timeout)
- **API level:** Added in API level 1
- **Official documentation:** [https://developer.android.com/reference/android/os/PowerManager.WakeLock#acquire(long)](https://developer.android.com/reference/android/os/PowerManager.WakeLock#acquire(long))
- **Date verified:** 2026-11-01
- **Platform fact (verified against the reference page):** `acquire(long timeout)` acquires the wake lock and guarantees release after the specified timeout if the caller has not already released it. A safety timeout is what prevents a leaked lock from blocking AP suspend when a background task fails or aborts. *(Reported, not verified:* that Play/Vitals penalise a continuous background wake lock beyond a specific hour threshold — no policy page stating such a number was opened. The project's reason to bound the lock stands on the leak argument alone.)
- **Project implication:** `DiscoveryEngineHolder` now enforces `WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L` on all wake lock acquisitions and manages bounded leases for background workers.

---

## 2. PowerManager.isInteractive()
- **API level:** Added in API level 20 (Android 5.0 Lollipop)
- **Official documentation:** [https://developer.android.com/reference/android/os/PowerManager#isInteractive()](https://developer.android.com/reference/android/os/PowerManager#isInteractive())
- **Date verified:** 2026-11-01
- **Platform fact:** Returns `true` if the device is currently in an interactive state (i.e. the screen is turned on and capable of displaying content to the user). Replaced the deprecated `isScreenOn()`.
- **Project implication:** Used to determine whether the device screen is active in `DiscoveryEngineHolder` and `TranssionHiberKeepAlive`, allowing dynamic discovery downscale to ECO and pausing silent keepalive audio loops when the user is actively viewing or using the screen.

---

## 3. Context-Registered BroadcastReceivers for Screen Broadcasts
- **API level:** API level 33 (Android 13) / API level 34 (Android 14)
- **Official documentation:** [https://developer.android.com/about/versions/14/behavior-changes-14#context-registered-broadcasting](https://developer.android.com/about/versions/14/behavior-changes-14#context-registered-broadcasting)
- **Date verified:** 2026-11-01
- **Platform fact:** On Android 14+ (API 34), apps must specify `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED` for all context-registered broadcast receivers. Protected system broadcasts such as `Intent.ACTION_SCREEN_ON`, `Intent.ACTION_SCREEN_OFF`, and `Intent.ACTION_USER_PRESENT` must be registered with `RECEIVER_NOT_EXPORTED` via `ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`.
- **Project implication:** Registered runtime screen receivers in `DiscoveryEngineHolder` and `TranssionHiberKeepAlive` explicitly specify `ContextCompat.RECEIVER_NOT_EXPORTED` on API 33+/34+ to comply with Android 14 security requirements.

---

## 4. Android 15 Foreground Service dataSync Hard Timeout
- **API level:** API level 35 (Android 15)
- **Official documentation:** [https://developer.android.com/about/versions/15/behavior-changes-15#data-sync-timeout](https://developer.android.com/about/versions/15/behavior-changes-15#data-sync-timeout)
- **Date verified:** 2026-11-01
- **Platform fact:** Foreground services declaring `dataSync` face a cumulative 6-hour execution limit within any 24-hour period. When reached, the OS invokes `Service.onTimeout(int, int)`. The app is given a few seconds to clean up, stop transfers, and call `stopSelf()`; failure to call `stopSelf()` immediately results in an ANR or crash by the OS.
- **Project implication:** `TimeoutStopPlan` was updated to include both `FlashTransferState.Transferring` and `FlashTransferState.Verifying`. Verifying transfers (e.g. SHA-256 hash validation on multi-gigabyte files) are cleanly paused and marked `SERVICE_TIMEOUT` so they can be resumed cleanly upon app restart without orphan records or corrupted verification states.

---

## 5. Google Play Connected Device FGS Restrictions
- **API level:** API level 34 (Android 14)
- **Official documentation:** [https://support.google.com/googleplay/android-developer/answer/13392821](https://support.google.com/googleplay/android-developer/answer/13392821)
- **Date verified:** 2026-11-01
- **Platform fact (verified against the support page):** `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` may only be maintained while actively communicating or interacting with an external connected device, so holding it indefinitely with 0 connected devices is a policy violation. *(Reported, not verified:* that it also prevents the process from entering the `RESTRICTED`/`RARE` App Standby buckets — plausible, but no page opened says so. The policy sentence alone justifies the idle stop.)
- **Project implication:** Implemented `evaluateServiceIdleStop` in `FlashBackgroundService`: after a 30-second grace period with 0 connected peers, 0 active transfers and no online hub relay, and only if the process is no longer foreground-interactive, the service stops itself — unless the user opted into "Keep listening in the background" (`backgroundTransfers`). The timer is re-armed from `MainActivity.onStop`, re-checks the live state when it expires, and is rearmable after a declined round (integrator repair; ADR-284).

---

## 6. AudioFocus Management & Backoff
- **API level:** Added in API level 8 (Android 2.2), revised in API level 26 (Android 8.0)
- **Official documentation:** [https://developer.android.com/media/optimize/audio-focus](https://developer.android.com/media/optimize/audio-focus)
- **Date verified:** 2026-11-01
- **Platform fact:** When another application gains audio focus, the OS issues `AUDIOFOCUS_LOSS`. Apps losing focus permanently must cease audio playback immediately and must not immediately re-request focus, as thrashing audio focus causes active media players (Spotify, YouTube Music, Podcasts) to stutter, glitch, or pause.
- **Project implication:** `TranssionHiberKeepAlive` now arms a 10-minute cooldown latch upon receiving `AUDIOFOCUS_LOSS` or an audio focus refusal (`AUDIOFOCUS_REQUEST_FAILED`), suppressing repeated audio focus requests during background session activity until either 10 minutes have elapsed or the user foregrounds the app.
