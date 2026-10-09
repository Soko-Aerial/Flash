# Android Platform Notes

## 2026-09-03 - A mesh Wi-Fi roam is invisible to `ConnectivityManager`; `VOICE_COMMUNICATION` is a request, not a contract; and what a 2 GB API-27 handset actually cannot afford

### Android version / API level
- Roam invisibility: all supported versions (API 24+). The *severity* depends on the **client**, not the
  OS: a device without 802.11k/v/r fast transition does a full scan + reassociation + DHCP.
- Silent `AudioRecord`: observed on Android 8.1 / API 27 (BelFone SCP810, qcom). Not version-specific —
  it is a HAL property.
- `AudioManager.setMode` audio-focus requirement: API 31+.
- Reduce-motion detection: `ANIMATOR_DURATION_SCALE` all versions; the accessibility reduce-motion
  toggle is API 33+.

### APIs / permissions involved
- `ConnectivityManager.NetworkCallback` — `onAvailable` / `onLost` / `onLinkPropertiesChanged` /
  `onCapabilitiesChanged`; `Network`, `LinkProperties`, `NetworkCapabilities`
- `MediaRecorder.AudioSource.VOICE_COMMUNICATION` / `MIC` / `DEFAULT`; `AudioRecord.getState()` and
  `read()`; `AudioManager.setMode(MODE_IN_COMMUNICATION)`; `RECORD_AUDIO`
- `ActivityManager.MemoryInfo.totalMem`, `Runtime.availableProcessors()`, `DisplayMetrics`
- `Settings.Global.ANIMATOR_DURATION_SCALE`; `AccessibilityManager.isReduceMotionEnabled` (API 33+,
  reached reflectively — it is not in the public SDK surface we compile against)

### Sources
Reference pages consulted for the API contracts above:
- https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
- https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
- https://developer.android.com/reference/android/media/AudioManager#setMode(int)
- https://developer.android.com/reference/android/provider/Settings.Global#ANIMATOR_DURATION_SCALE

Discoveries 1 and 2 below were established **on device** in this project (EXP-006, ERROR-032/033), not
read out of a doc page. Neither behaviour is documented as such, which is the point of recording them.

### Discovery 1 — a roam between mesh APs keeps the same `Network` object
Android hands out one `Network` per *network*, not per *association*. An AP-to-AP handoff inside one
SSID therefore keeps the same `Network`: **`onAvailable` and `onLost` never fire**, and any recovery
logic hung off them is unreachable for the single most common real-world connectivity event in a mesh
deployment. What does change is the link — `LinkProperties` (interface, routes, DNS, addresses) and
`NetworkCapabilities` — so `onLinkPropertiesChanged` / `onCapabilitiesChanged` are the only callbacks
that see it, and they fire for benign reasons too, so they are a *hint* rather than a verdict.

The differential is entirely client-side. A Pixel 7 and an Infinix X6882B crossed the same node
boundary without a visible interruption; a BelFone SCP810 with no 802.11k/v/r support lost seconds to a
full scan, reassociation and DHCP. Same network, same build, same walk.

**Do not** infer "the link moved" from a socket error either: on a roam the sockets survive the address
change often enough that failure is not reliable evidence, and a session that is merely paused looks
identical to one that is dead. The workable shape is: diff successive link snapshots to *suspect* a
move, then **probe** each live session and reap only the ones that fail to answer within a bounded
window.
### Discovery 2 — `AudioSource.VOICE_COMMUNICATION` can open, verify, and deliver nothing
`MediaRecorder.AudioSource` values are **requests, not contracts**. An OEM HAL that carries a vendor
radio stack on the voice path can accept `VOICE_COMMUNICATION`, report `AudioRecord.getState() ==
INITIALIZED`, pass libwebrtc's own `verifyAudioConfig`, enable both hardware effects — and then never
return a frame from `read()`. No exception, no `AudioRecordErrorCallback`, nothing to detect after the
fact. The only symptoms are downstream: libwebrtc's "Join of AudioRecordJavaThread timed out",
"AudioRecord.read failed: 0", and a `stop()` that blocks for 8 seconds. Those blocking HAL calls also
ANR the app, which presents as an unrelated second bug.

Three properties of the workaround are worth carrying forward:
- **The pass criterion must be frame delivery, never loudness.** A healthy mic in a quiet room returns
  buffers of zeros and a broken HAL returns nothing — a non-zero-PCM check scores both the same. Two
  identical SCP810 units proved it: the noisy one fell back correctly, the quiet one rejected every
  candidate and kept the source that does not work.
- **Probe in `MODE_IN_COMMUNICATION`.** The stall is mode-dependent on this HAL, so a measurement taken
  in `MODE_NORMAL` caches the source that is about to fail. From API 31 the platform refuses the mode
  change without audio focus, so this is best-effort — and a probe that ends up measuring `MODE_NORMAL`
  is still no worse than not probing.
- **Hardware AEC/NS belong to `VOICE_COMMUNICATION` only.** On a raw `MIC` fallback there is no platform
  echo-cancellation path for them to attach to; stacking them anyway is a known way to produce a capture
  stream that is present but useless. Software APM does that work instead — and the effects log lines
  become the field tell for which source won.

### Discovery 3 — what a 2 GB / API 27 / 480x640 handset cannot afford
- **Capture size is a CPU cost paid before the encoder exists.** `getUserMedia` at 1920x1080@30 means
  ≈62 Mpixel/s of scale and colour conversion on the way *in*, spent whether or not a byte of it survives
  the encoder's decision to send 360p. Both adaptive mechanisms in the stack (WebRTC's
  `MAINTAIN_FRAMERATE` degradation, and Flash's `CallQualityGovernor`) act on the encoder and therefore
  cannot reach it. Capping the **request** is the only lever.
- **Packet rate, not bit rate, is what a congested 2.4 GHz link cannot afford.** 802.11 charges a largely
  fixed airtime price per frame — preamble, PHY header, inter-frame spacing, an ACK — on a half-duplex
  shared medium. At 100 packets/s, RTP 12 + UDP 8 + IPv4 20 + SRTP tag 10 ≈ 50 bytes of header is
  ~40 kbit/s of wrapping around 25 kbit/s of speech. Opus's legal frame sizes are 10/20/40/60 ms, so
  60 ms framing is the available 6× reduction in frames on the air.
- **`totalMem` is a classification input, not a truth.** Some OEMs under-report it, so a RAM threshold
  should be one signal among several and a low reading should never be the *only* thing that demotes a
  device irreversibly. Recompute on every boot rather than persisting a verdict.
- **Two ways to ask "should this device animate?", and they are not the same question.**
  `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` is the developer-options / OEM answer and works
  everywhere; the accessibility reduce-motion toggle is API 33+ and is not in the public SDK surface, so
  it needs reflection. A device that cannot animate smoothly reports neither — the hardware verdict has
  to come from classification, and it has to be a **floor** under the other two rather than another vote,
  because on such hardware the animation is the jank.

### Project implication
1. `LinkChangeTracker` + `AndroidNetworkWatcher.onLinkChanged` + `probeSessionsAfterLinkChange()` exist
   because of Discovery 1; do not "simplify" any of the three back onto `onAvailable`/`onLost`.
2. `FlashWebRtcEngine.configureOnce` probes the capture source once per process because of Discovery 2 —
   `WebRtc.configure` builds the `PeerConnectionFactory` immediately and throws if one exists, so the ADM
   is process-wide and permanent and there is no later window to fix a bad source in.
3. Discovery 3 is the empirical basis for `FlashPerformanceClassifier`'s thresholds and for
   `FlashVideoProfile`/`FlashVoiceProfile` (ADR-028). The numbers are field-derived, not tasteful.
4. Known gap: the source probe needs `RECORD_AUDIO`, which may not be granted at engine construction. It
   is then skipped and `VOICE_COMMUNICATION` is used as before, so a device that both prompts for the mic
   *and* needs the fallback gets it from the next process start rather than the first call.


## 2026-09-01 - Battery saver / low-battery power policy supersedes FGS priority; why WhatsApp-class apps use FCM (and why Flash cannot)

### Android version / API level
- Applies to all supported versions (battery saver since API 21+; behavior intensifies on 15/16)
- Verified against official docs 2026-09-01

### APIs / permissions involved
- `PowerManager.isPowerSaveMode` / `PowerManager.isIgnoringBatteryOptimizations`
- Foreground service priority vs. device power state
- FCM high-priority messages (research only — not adopted)

### Official documentation source
- Power management resource limits: https://developer.android.com/topic/performance/power/power-details
- Doze & App Standby (incl. acceptable-use-case exemption table): https://developer.android.com/training/monitoring-device-state/doze-standby
- FGS background-start exemptions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

### Discovery 1 — battery saver / low-battery states supersede FGS priority
The official power-management resource-limits table shows device state can override app
state: while battery saver is on, restrictions apply regardless of the app's standby bucket
or FGS status. At critically low battery (e.g. the Infinix test device at ~4%), OEM power
managers (Transsion "Phone Master"/"Phoenix") and/or AOSP battery saver aggressively kill
background processes **even when they hold foreground-service priority**. This matches the
2026-09-01 differential test (EXP-002): Samsung at 90% stayed online with screen off (Bug 6
fix verified), Infinix at 4% went offline within seconds.

### Discovery 2 — how WhatsApp-class apps receive messages with screen off
WhatsApp/Telegram-class messengers do NOT keep their own socket alive through Doze. They use
**FCM (Firebase Cloud Messaging)**: Google maintains ONE shared persistent connection to the
device that is exempt from Doze; a high-priority FCM message wakes the app, grants temporary
network + partial wake lock, delivers the notification, then the device returns to idle.
This is why the user sees a WhatsApp notification with the screen off — the app itself is
not running a live connection; Google's transport is.

**Flash cannot use FCM**: we are LAN P2P with no cloud server, no Google account
dependency, and no internet path — FCM requires Google's cloud. The official Doze
acceptable-use-case table explicitly covers this case: *"can't use FCM because of technical
dependency on another messaging service, or Doze/App Standby break the core function of
the app" → exemption acceptable*. Our Settings "Background transfers" toggle +
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the sanctioned equivalent for a P2P app.

### Discovery 3 — battery-optimization exemption unblocks sticky-restart FGS promotion
The official FGS background-start exemptions list includes *"The user turns off battery
optimizations for your app"* — meaning once the user grants our exemption, even the
START_STICKY restart path may legally promote to foreground. Without the exemption, our
`stopSelf()` fallback keeps the process alive but unprotected (no FGS priority), which is
exactly when OEM power managers kill it. **The exemption is the load-bearing lever for
background liveness on aggressive OEMs; the wake lock alone is not.**

### Project implication
1. Bug 6 fix stack is correct and now physically verified on the Samsung (EXP-002).
2. On the Infinix, expect background liveness to fail while battery saver / critically low
   battery is active — this is platform behavior, not a Flash bug. EXP-003 (charged Infinix +
   exemption granted) will separate "low battery" from "OEM auto-kill".
3. Optional UX follow-up (needs owner approval): detect `isPowerSaveMode` and surface a
   hint in Settings/Nearby ("Battery saver is on — background receiving may be interrupted")
   rather than silently going offline.

## 2026-08-31 (b) - Sticky FGS restarts CANNOT call startForeground; WifiLock is ineffective in background

### Android version / API level
- Device: Infinix X6882B (Transsion, Android 15/16, targetSdk 36 runtime)
- ForegroundServiceStartNotAllowedException applies to Android 12 / API 31+
- WifiLock mode remap applies to Android 14 / API 34+

### APIs / permissions involved
- `Service.startForeground()` + `START_STICKY` restart path
- `ForegroundServiceStartNotAllowedException` (API 31+)
- `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` / `WIFI_MODE_FULL_HIGH_PERF`)
- `PowerManager.isIgnoringBatteryOptimizations` + `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### Official documentation source
- Background-start restrictions (exemptions list): https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- `startForegroundService()` contract ("did not then call startForeground" kill): https://developer.android.com/reference/android/content/Context#startForegroundService()
- WifiLock modes incl. the API 34 HIGH_PERF→LOW_LATENCY remap: https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY
- Battery-optimization exemption: https://developer.android.com/training/monitoring-device-state/doze-standby

### Discovery 1 — sticky restart crash loop (ERROR-020 root cause)
Device logcat captured SEVEN `FATAL EXCEPTION` process deaths (2026-08-29…31):

```text
java.lang.RuntimeException: Unable to create service com.transfer.flash.debug.FlashBackgroundService
Caused by: android.app.ForegroundServiceStartNotAllowedException: Service.startForeground()
not allowed due to mAllowStartForeground false
  at FlashBackgroundService.startAsForeground(FlashBackgroundService.kt:153)
  at FlashBackgroundService.onCreate(FlashBackgroundService.kt:74)
```

The chain: OEM/Android kills the backgrounded Flash process → `START_STICKY` restarts the
service with a **null intent while the app is NOT TOP** → `onCreate` called `startForeground()`
unconditionally → the throw was **uncaught** → process death → restart → crash loop. This is
the actual "goes offline after a few seconds" Bug 6 behavior — the earlier FGS-launch-site fix
was necessary but not sufficient, because the restart path re-enters through `onCreate`.

Implication for the app: a sticky service's `onCreate` may run at ANY process state.
`startForeground()` there must be wrapped: on failure, log, and `stopSelf()` (which also cancels
the 5-second `startForegroundService()` follow-up obligation instead of triggering the
"did not then call startForeground" kill). Locks + engine start happen BEFORE the promotion
attempt so the mesh is restored even when foreground priority is refused. Empirically, the
system's sticky restart was NOT on the BG-start exemption list on this OEM — treat the
exemption list as best-effort, never as a guarantee.

### Discovery 2 — WifiLock cannot keep the radio powered while backgrounded (API 34+)
`dumpsys wifi` on the test device while Flash was backgrounded:

```text
WifiLock{flash:ws-mesh-wifi type=4 uid=1000 workSource=WorkSource{10445 com.transfer.flash}}
Low-latency uid watchlist:
    UidRec{uid=10445, lockCount=1, isFg=false, isFgExempt=false, isScreenExempt=false}
is_low_latency_activated=false
```

Per current reference docs, `WIFI_MODE_FULL_LOW_LATENCY` is active ONLY while the app is
foreground AND the screen is on; from API 34, `WIFI_MODE_FULL_HIGH_PERF` is automatically
**replaced** by `WIFI_MODE_FULL_LOW_LATENCY` (deprecated), so the old "keeps radio out of power
save even with screen off / background" lock no longer exists on modern Android. The WifiLock
is therefore retained (helps foreground + screen-on hot path) but is NOT part of the
background-liveness story. Background survival rests on: FGS priority + partial wake lock
(CPU) + the battery-optimization exemption.

### Discovery 3 — battery-optimization exemption is the user-facing lever
`dumpsys deviceidle` showed Flash NOT on the doze whitelist; `am get-standby-bucket` returned 10
(ACTIVE) only while in use. The Settings "Background transfers" toggle now fires
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
so the user can exempt Flash from AOSP Doze/App Standby. Caveat recorded honestly: Transsion
("Phone Master"/"Phoenix" — visible in the device's own doze whitelist) applies a SECOND,
OEM-specific auto-kill that AOSP exemption does not control; on such devices the user may
additionally need Settings → Battery → Flash → allow background activity.

### Project implication
Bug 6 fix stack: (1) crash-proof `startAsForeground` with graceful `stopSelf` fallback;
(2) locks + engine start ordered before promotion; (3) user-initiated battery-optimization
exemption on the Background transfers toggle. Verified: build green, crash loop eliminated in
code. Physical two-phone re-verification still required (see `logs/errors.md` ERROR-020).

## 2026-08-31 - Foreground mesh service must start from a visible activity

### Android version / API level
- Project target SDK: 36
- Foreground-service background-start restriction applies to Android 12 / API 31+
- Type-specific foreground-service permissions apply to target/API 34+

### APIs / permissions involved
- `ContextCompat.startForegroundService`
- `foregroundServiceType="connectedDevice"`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `CHANGE_WIFI_MULTICAST_STATE`

### Official documentation source
- Launch a foreground service: https://developer.android.com/develop/background-work/services/fgs/launch
- Background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Foreground-service types: https://developer.android.com/develop/background-work/services/fgs/service-types

### Project implication
Android 12+ rejects most foreground-service launches after an app is already in the background. Flash therefore starts `FlashBackgroundService` synchronously from `MainActivity.onStart`, while the activity is user-visible, and deliberately does not stop it from `onStop`; the service keeps the WebSocket mesh and NSD runtime alive after backgrounding. The previous launch inside asynchronous `DiscoveryEngineHolder.ensureStarted` was timing-dependent and could occur after the activity had stopped.

The `connectedDevice` type remains appropriate for the local-network mesh. Its Android 14+ prerequisites are satisfied by the manifest's `FOREGROUND_SERVICE_CONNECTED_DEVICE` and `CHANGE_WIFI_MULTICAST_STATE` declarations. `ContextCompat.startForegroundService` preserves the API 24-25 fallback to `startService`.

## 2026-08-18 - Pixel 7 local-network permission issue

### Android version / API level
- Project compile SDK: 37
- Project target SDK changed from 37 to 36 for the LAN MVP.

### APIs / permissions involved
- `ACCESS_LOCAL_NETWORK`
- `INTERNET`
- `NsdManager`

### Official documentation source
- Android local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Android 17 behavior changes: https://developer.android.com/about/versions/17/behavior-changes-17
- Android `NsdManager` reference: https://developer.android.com/reference/android/net/nsd/NsdManager

### Project implication
Android documentation says `ACCESS_LOCAL_NETWORK` is required only for apps targeting Android 17 / SDK 37 or higher. For apps targeting SDK 36 or lower, local network access is implicitly granted through `INTERNET`, and apps should not declare or request `ACCESS_LOCAL_NETWORK`.

During Pixel 7 testing, the app logged repeated system-server errors:

```text
Operation not found: uid=10315 pkg=com.transfer.flash(null) op=ACCESS_LOCAL_NETWORK
```

The Pixel also showed a system-mediated local-network device prompt that was not part of this app's UI. For the current LAN MVP, the app now targets SDK 36 and no longer declares `ACCESS_LOCAL_NETWORK`. When the app returns to target SDK 37, it needs a deliberate runtime permission or system-device-picker flow.

## 2026-08-18 - LAN NSD kickoff

### Android version / API level
- Project compile SDK: 37
- Project target SDK: 36
- Project min SDK: 24

### APIs / permissions involved
- `NsdManager`
- `NsdServiceInfo`
- `WifiManager.MulticastLock`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_MULTICAST_STATE`

### Official documentation source
- Android `NsdManager` reference: https://developer.android.com/reference/android/net/nsd/NsdManager
- Android NSD guide: https://developer.android.com/develop/connectivity/wifi/use-nsd

### Project implication
- NSD registration and discovery are asynchronous and must be stopped when the app no longer needs them.
- `NsdManager.resolveService` remains usable for the minSdk 24 MVP path, but current documentation deprecates it on newer API levels in favor of service-info callbacks that keep service addresses current.
- mDNS reception over Wi-Fi can require a multicast lock on older devices / extension levels. The LAN MVP acquires a non-reference-counted multicast lock only while LAN discovery is running.
- API 37 targets need to account for local network restrictions. The MVP currently avoids that path by targeting SDK 36 until the local-network runtime permission/user flow is implemented.

### Current project implication
The first LAN implementation uses classic NSD discovery and per-service resolution to keep compatibility down to API 24. A later reliability pass should introduce the newer `registerServiceInfoCallback` path for API levels/extensions where it is available.

## 2026-09-09 - tydtech clip-mic PTT button: 4 intents per press, Zello hook is the public one

### Android version / API level
Observed on an MT6789 phone (rugged/PTT white-label, tydtech system service tag � a
Shenzhen ODM firmware layer). Implicit-broadcast rules are API 26+: manifest-declared
receivers never see these actions, and API 34+ requires an explicit export flag on every
runtime registration.

### APIs / permissions involved
- `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`
- `adb shell am broadcast -a <action>` (hardware-free test path)

### Behavior (logcat, one press of the main button)
Four broadcasts fire simultaneously for the same press:
- `shmaker.android.intent.action.SCANER_KEYEVENT_DOWN/UP` (barcode-scanner trigger reuse)
- `android.intent.action.ptt.down/up` and `android.intent.action.PTT.down/up` (generic conventions)
- `com.zello.ptt.down/up` (Zello walkie-talkie hook)

Volume/play-pause/voice buttons do NOT produce custom broadcasts: they route through the
standard `mt6789-mt6366 Headset Jack` input device (normal headset-remote keys, catchable
in `Activity.onKeyDown` if ever needed � not implemented in v1).

### Project implication
- Listen to `com.zello.ptt.down` ONLY via an engine-lifetime dynamic receiver
  (`DiscoveryEngineHolder.registerPttReceiver`); the other three actions would 4x the
  fan-out and are ignored by the filter.
- No new permission, no manifest receiver entry.
- Other vendors will differ; nothing beyond the Zello action is assumed.

## 2026-09-10 - Background PTT transmit must re-enter foreground before microphone capture

### Android version / API level
Android 12+ restricts starting foreground services from the background, and Android 14+
additionally checks while-in-use microphone permission when a `microphone` foreground service is
created. A runtime permission grant does not by itself make background microphone capture eligible.

### APIs / permissions involved
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`
- `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`

### Official documentation sources
- Android foreground-service launch restrictions:
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Foreground-service types and microphone while-in-use restrictions:
  https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
- Restrictions on starting foreground services that need while-in-use permissions:
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions

### Project implication
- A hardware PTT broadcast received while Flash is backgrounded must not directly create a
  microphone foreground service or assume `AudioRecord` will provide usable samples.
- The press is deferred and surfaced through the foreground activity/notification path; capture
  starts only after Flash is visible and microphone permission is confirmed. If that path cannot
  run, v1 may fall back to the non-audio PTT ping rather than silently opening the microphone.
- A talking session uses the `microphone` service type. A receiving session uses
  `mediaPlayback`; call semantics and `Notification.CallStyle` are not reused for PTT.
- This platform rule is separate from Flash's call/PTT/voice-note ownership gate: both Android
  eligibility and exclusive in-app audio ownership must succeed before capture starts.

## 2026-09-28 - OEM freezers ignore the foreground service and the battery whitelist (Transsion "Hiber")

### Android version / API level
Seen on Android 14 (API 34), Transsion XOS (Infinix X6882B). Not an AOSP behaviour: the freezer is Transsion's own
`Hiber/sceneManager` in `system_server`. Other OEMs ship similar ones (see dontkillmyapp.com, a community source).

### APIs / permissions involved
- Foreground service (manifest types `connectedDevice|dataSync`), partial wake lock, Wi-Fi multicast lock
- `dumpsys deviceidle whitelist` (what "Battery: Unrestricted" sets), appop `RUN_ANY_IN_BACKGROUND`

### Evidence (measured, ERROR-074)
- `Hiber/sceneManager: freeze uid: 10455 com.transfer.flash` about 10 s after every screen-off, `unfreeze ...
  reason:appToTop` only when Flash came to the front. Flash logged nothing in between.
- The foreground service, the deviceidle whitelist, `RUN_ANY_IN_BACKGROUND=allow` and standby bucket ACTIVE did not
  prevent it. `dumpsys hiber` fails; there is no shell switch.

### Official documentation sources
None: the behaviour is outside the Android platform. Android's own rules (foreground services keep a process
runnable; https://developer.android.com/develop/background-work/services/fgs) do not hold on this device.

### Project implication
- On Transsion phones with default settings Flash sends and receives nothing while the screen is off: no
  keepalive, no messages, no calls. No connection mode (PC5) can change that.
- The user must exempt Flash in the OEM's own settings. Reported, **not verified here**: Settings → Battery → power
  saving → untick Flash under "screen-off sleep" and "screen-off push block"; Phone Master → Auto-start management
  → allow. Verify on the device before the final test pass (plan P8) and then write the exact path into the app.
- Before trusting any screen-off measurement, grep logcat for `Hiber` (PC0 runbook §4 step 4).
- An in-app hint (detect `Build.MANUFACTURER` in {INFINIX, TECNO, ITEL} and link to the settings) is a candidate
  UI task; it needs a component doc first (AGENTS.md §34) and a verified settings path.

### Addendum 2026-10-02 - the owner's three candidate fixes for the Transsion freeze (assessed, none built)
The owner pasted a community write-up (Don't Kill My App style; source and date not recorded, **community claim**, not verified here).
Its claims: Transsion's phone manager hardcodes OEM whitelists; WhatsApp is woken by FCM and YouTube by audio focus / MediaSession.
Three workarounds came from it. Assessment against this app (no code changed):

1. **Guide the user to the OEM screens** (an Intent to a `ComponentName` in `com.transsion.phonemaster`). Recommended first, because it
   needs no new permission or service and it is already half there: Settings "Background transfers" requests the battery exemption
   and then tries the OEM screen (`OemBatteryOptimizationHelper`, with a MainActivity fallback). **Discrepancy to settle on the
   device:** the helper uses `com.transsion.phonemaster.autostart.AutoStartActivity`; the pasted text uses
   `com.transsion.phonemaster.autostart.AutoStartManageActivity`. Phone Master activities differ by version and the `ComponentName`
   may be missing, so every launch needs `resolveActivity`/try-catch and a fallback to the app's own settings page. Test `HIB-01`.
   An autostart permission does not by itself prove the freezer will spare Flash; EXP-002 already shows the battery exemption is not enough.
2. **Silent audio loop in a `mediaPlayback` foreground service** (the idea is that Hiber spares an app holding audio focus).
   Unverified on this handset. Costs: continuous audio output wakes the audio stack and drains battery; Play policy and the Android 14+
   foreground-service-type rules (`mediaPlayback` needs a real media use) put a store release at risk; a call or a music app would
   have to share audio focus. It is an experiment, not a design: test `HIB-02`, measure battery and whether `Hiber` still logs `freeze`.
3. **High-priority FCM data messages.** This is how WhatsApp is woken, but it needs a push server holding a key plus Google Play
   Services on the phone. Flash has no server and no Google dependency (checked 2026-10-02: no Firebase or Play Services entry in
   any Gradle, version-catalog or manifest file), and "no server, no account" is a product property. Not recommended unless the owner
   changes that property; it would also tell Google that "someone messaged you". Needs an ADR before any work.

Suggested order: 1, then 2 as a measured experiment, 3 only if both fall short. All measured claims stay as in the 2026-09-28 entry above
(freeze about 10 s after screen-off; Infinix 4 % vs Samsung 90 % delivery with the screen off, EXP-002).

### Update 2026-10-02 (b) - the Transsion options checked against public sources
- **Guide the user: real.** dontkillmyapp.com (Tecno page; the Infinix page returned 404, so XOS steps are unconfirmed there) lists:
  Battery Lab, Power Saving Management off; Phone Master, Auto-start Management allow; lock the app in the Recents list (lock icon);
  Power Boost off in Power Marathon; App Booster; Sleep Mode and the screen-off options ("Sleep when screen is off", "Block push
  notifications when screen is off"). A Dimagi CommCare support page reports the same for HiOS and XOS and says a registered
  foreground service is still killed. Same page, verbatim: **"No known solution on the developer end."** The app can only guide the user.
  *community sources, not verified on the Infinix here.*
- **Silent audio loop: unproven.** A search found no source that Transsion's freezer spares a process that plays audio; the claim came
  from the owner's pasted text. Android defines `mediaPlayback` as "continue audio or video playback from the background"
  (https://developer.android.com/develop/background-work/services/fgs/service-types, checked 2026-10-02); a silent loop is outside
  that intent. Experiment HIB-02 only.
- **Companion Device Manager: a real server-free option, unproven here.** After a companion association the app may hold
  `REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` and
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`; Android 16 adds `startObservingDevicePresence()`; device filters are
  Bluetooth, BLE or Wi-Fi (https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing, checked 2026-10-02).
  Flash has no Bluetooth code, and nothing says Transsion's freezer honours the exemption. Experiment only; recorded as HIB-04.
- **Already in the code, server-free:** the sender's durable outbox keeps retrying (a message to a frozen phone is late, not lost; not
  measured on the Infinix) and discovery re-arms on screen-on (ERROR entry of 2026-09-30). **Idea, not built:** tell the sender the
  other phone is probably asleep.
- **FCM stays excluded:** it needs a push server and Google Play Services (owner: no server).

## 2026-10-02 - Mobile hotspot: phones joined to it cannot reach each other (client isolation)

### Android version / API level
Seen on an Infinix Hot 50 hotspot (ERROR entry of 2026-09-30); the owner reports the same on a hotspot generally (2026-10-02).

### APIs involved
`SoftApConfiguration` (tethered hotspot), `WifiManager.startLocalOnlyHotspot` (API 26), `WifiAwareManager` (API 26),
`WifiP2pManager` (Wi-Fi Direct).

### Official documentation sources (checked 2026-10-02)
- https://source.android.com/docs/core/connect/wifi-softap: does **not** mention client isolation.
- https://developer.android.com/develop/connectivity/wifi/localonlyhotspot: local-only hotspot exists "to enable applications on devices
  connected to the Wi-Fi hotspot to communicate with each other"; no internet; permission `NEARBY_WIFI_DEVICES` (API 33+) or
  `ACCESS_FINE_LOCATION` (to API 32); one request per app.
- https://developer.android.com/develop/connectivity/wifi/wifi-aware: API 26+, no access point, publish/subscribe discovery, data path
  through `WifiAwareNetworkSpecifier` (IPv6), `NEARBY_WIFI_DEVICES` on 13+, hardware dependent, may not work while Wi-Fi Direct, SoftAP
  or tethering is in use on some devices.
- https://developer.android.com/develop/connectivity/wifi/wifip2p: Wi-Fi Direct connects "without an intermediate access point";
  `NEARBY_WIFI_DEVICES` on 13+; the doc does not say whether normal Wi-Fi can stay connected at the same time.

### Project implication
- Isolation is most likely a phone-maker setting, not Android behaviour; which models do it is unmeasured (HOT-01).
- Today's discovery cannot cross it. Options and trade-offs: `docs/FUTURE-OPTIMIZATION.md` FO-06. Nothing is built.

## 2026-09-28 - Reading the connected Wi-Fi band without location permission (G2)

### Android version / API level
All supported levels. API 31+ reads `WifiInfo` from `NetworkCapabilities.getTransportInfo()`; below that,
`WifiManager.getConnectionInfo()` (deprecated in API 31).

### APIs / permissions involved
- `ConnectivityManager.getActiveNetwork` / `getNetworkCapabilities` (ACCESS_NETWORK_STATE)
- `WifiInfo.getFrequency()` (ACCESS_WIFI_STATE); both are normal permissions the app already declares.

### Official documentation sources
- https://developer.android.com/reference/android/net/wifi/WifiInfo (checked 2026-09-28): the class documentation
  names SSID and BSSID as the fields redacted without location permission. Frequency is not listed.
  **Verified in part:** the page was read through a search summary (the direct fetch returned only navigation), and
  no device has been checked yet.

### Project implication
- `AndroidNetworkBand` requests no location permission. A frequency of 0 or less (redacted, or unknown) reads as
  UNKNOWN, so a redacting OEM degrades to "no band shown", never to a wrong band.
- A phone hosting the hotspot reports UNKNOWN: its default network is cellular, and its own AP band needs a system API.
- Device check pending (GROUP-VIDEO-PLAN §8 G2): confirm a real frequency on the Infinix (API 34) and the V760.

## 2026-10-02 - Picture-in-picture and the proximity screen-off for calls (ADR-067)

### Android version / API level
- Picture-in-picture: `enterPictureInPictureMode` and `PictureInPictureParams` from API 26; `setAutoEnterEnabled` from API 31.
- Proximity: `PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK` (guarded by `isWakeLockLevelSupported`); the app already holds `WAKE_LOCK`.

### Official documentation source
- https://developer.android.com/develop/ui/views/picture-in-picture (fetched 2026-10-02, **verified**): the activity needs
  `android:supportsPictureInPicture="true"` and `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` to avoid being
  relaunched on the transition; `setAutoEnterEnabled(true)` (API 31+) enters PiP without `onUserLeaveHint`; PiP params from API 26.
- The proximity wake lock was **not re-fetched** this session (the API has existed since API 21); treat the exact release behaviour as unverified
  until `CALLX-08` is run.

### Project implication
- `MainActivity` now declares both attributes and is no longer recreated on a size change (Compose handles it); the activity's state in
  `remember` survives a PiP transition.
- Below API 31 the user enters PiP from the call's More panel; no `onUserLeaveHint` override was added.
- PiP is auto-enabled only while a live 1:1 video call exists and is switched off when it ends.
- Device check pending: `CALLX-06` (PiP), `CALLX-08` (proximity), on a Samsung and the Infinix (Transsion may behave differently).

## Discovery review 2026-10-03: multicast lock on API 34+ for an app-owned UDP socket (UNVERIFIED)
- **API / version:** `WifiManager.MulticastLock`, Android 14 (API 34) and later.
- **What the code assumes:** `NsdTransport` and `AndroidMulticastSocketFactory` both skip the explicit lock from API 34 ("the framework manages it"). For NSD this matches the platform
  `NsdService` holding its own lock (`docs/network/PC0-RUNBOOK.md`). Nothing found in the official documentation says the platform lock also covers a `MulticastSocket` the app opens itself.
- **Project implication:** while NSD holds a lock the chipset filter is open for every socket, so the multicast beacon works by side effect; when NSD is not browsing (ECO idle) it may hear nothing.
  Source status: *not verified* (a documentation search returned only API-reference headings). Settled by the device check `DISC-07`; do not change the lock rule before it runs.

## 2026-10-05 - Group secrets, Room schema v8, and Auto Backup / Data Extraction protection (GM-2)

### Android version / API level
All supported Android versions (API 24+).
- API 24–30: legacy Auto Backup governed by `res/xml/backup_rules.xml`.
- API 31+ (Android 12+): Data Extraction rules governed by `res/xml/data_extraction_rules.xml`.

### APIs / files involved
- `app/src/main/res/xml/backup_rules.xml` (`<exclude domain="database" path="." />`)
- `app/src/main/res/xml/data_extraction_rules.xml` (`<exclude domain="database" path="." />`, `<exclude domain="device_database" path="." />`)
- Room schema v8: `group_secret`, `group_invite`, `group_join_request` tables in the primary SQLCipher encrypted database.

### Official documentation sources
- https://developer.android.com/guide/topics/data/autobackup (verified): on Android 12+ `android:dataExtractionRules` takes precedence over `android:fullBackupContent`; `device-transfer` must be explicitly configured because `allowBackup="false"` does not prevent OEM device-to-device transfers.

### Project implication
- The `group_secret` table stores 32-byte group encryption secrets (`secretWrapped` column). The database is encrypted at rest via SQLCipher with a passphrase protected by AndroidKeyStore (`KeystorePassphraseProvider`).
- Because `backup_rules.xml` and `data_extraction_rules.xml` explicitly exclude `domain="database"` and `domain="device_database"` from both `<cloud-backup>` and `<device-transfer>`, group secrets and encrypted chat databases never leave the device to Google Drive or unencrypted device migration tools.
- Restoring onto another device would fail to open the database anyway (as the AndroidKeyStore master key is non-exportable hardware-backed), but excluding the files prevents quota exhaustion, unopenable file leaks, and corrupted state on new devices.


## Addendum 2026-10-08 - Background delivery on Transsion: what the official documentation allows

- Android developer documentation (read 2026-10-08): starting a foreground service from the background is allowed for a high-priority FCM message, Companion Device Manager (with `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`), exact alarms for user actions, boot broadcasts, battery optimisation disabled, and device/profile owners. Android 14+ forbids creating camera, microphone or location foreground services from the background even then.
- Companion Device Manager presence is BLE and Bluetooth only, not Wi-Fi. Android 16 deprecates the string-based `startObservingDevicePresence`/`onDeviceAppeared`.
- None of these is documented to unfreeze a process that an OEM freezer holds; whether Transsion's Hiber respects them is unverified (HIB-04, HIB-06, HIB-07). Project impact and ranked options: `docs/network/TRANSSION-BACKGROUND-DELIVERY-RESEARCH.md`.
