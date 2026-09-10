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
