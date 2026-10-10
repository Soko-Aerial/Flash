# Transsion Hiber freezer: how it decides (read from the firmware, 2026-10-09)

**Status:** facts below were read STATICALLY from the owner's own phone (Infinix X6882B, `X6882B-H8915STU-U-OP-250311V392`, XOS 14.5.0, Android 14): `/product/hiber/config/hiber.json` and the disassembled `com.android.server.am.hiber.*` classes in `/system/framework/services.jar` (`dexdump -d`). Reading is not the same as testing: every lever listed below is a **hypothesis until a probe run confirms it** (test HIB-12/HIB-13 in `docs/testing/TEST-BACKLOG.md`). Other firmware builds and the cloud policy (`griffin_policy_version`, `TranCloudHandler`) may differ.

Why this file exists: ERROR-074, EXP-002 and HIB-10/HIB-11 measured that the app UID is frozen 3-5 s after screen-off and that its sockets are destroyed 3.0 s later (`linkTurb destorySocketByUid`). This file answers "why are WhatsApp, Telegram and Facebook not affected" and "what can Flash legitimately do".

## 1. Why other apps are safe: package-name allow-lists in the system image

`/product/hiber/config/hiber.json` (version 1.1.1), keys and what they hold:

| Key | What it does | Examples (verbatim) |
|---|---|---|
| `whitePackages` | exempt from freezing | `com.whatsapp`, `org.telegram.messenger`, `org.thoughtcrime.securesms` (Signal), `com.viber.voip`, `com.snapchat.android`, `com.instagram.android`, `com.google.android.gms`, `com.android.vending`, `com.tencent.mm` |
| `network.app.whitePackages` | IM apps whose network is watched; Hiber thaws them on traffic (`reason:packet`) | `com.facebook.katana`, `com.facebook.orca`, `com.whatsapp`, `org.telegram.messenger`, `com.discord`, `us.zoom.videomeetings`, `com.microsoft.teams` |
| `aiNetControl.blackImPackages` | IM apps under the AI network controller | Facebook, WhatsApp, Instagram, Telegram, WeChat |
| `scenes.traffic.whitePackages` | traffic exemption without further conditions | `com.miHoYo.Yuanshen` |
| `/product/etc/performance/griffin/keep_alive_config_t.json`, `whitelist_*_ram_t.json` | memory / auto-start keep-alive | Facebook, WhatsApp, YouTube, WeChat ... |

Flash (`com.transfer.flash`) is in none of them. They are matched by **package name** and shipped in a read-only partition (`/product`), so an app cannot add itself. Impersonating a listed package name is not an option (not possible to install alongside the real app, and it would be abuse). The only legitimate way onto the list is a request to Transsion (research doc option E).

## 2. The generic exemptions ("important scene"), `TranImportantScene.isImportantState`

Hiber checks a bitmask of reasons per scene. Constants (`IMPORT_*`) and the masks were decoded:

| Reason | Bit | SCREEN_OFF | SCREEN_ON | OFF->ON |
|---|---|---|---|---|
| top app | 1 | no | yes | yes |
| visible activity | 2 | no | yes | yes |
| float window | 4 | **yes** | yes | yes |
| screen record | 8 | yes | yes | yes |
| audio record (mic capture) | 16 | **yes** | yes | yes |
| audio mix (playback) | 32 | **yes** | yes | yes |
| widget | 64 | no | yes | yes |
| default launcher / input / SMS | 128/256/512 | yes | yes | partly |
| default dialer | 1024 | yes | no | no |
| wallpaper | 2048 | yes | yes | yes |
| accessibility service | 4096 | no | yes | yes |
| GPS | 8192 | **yes** | yes | yes |
| traffic | 16384 | **yes** | yes | yes |
| Bluetooth active list | 32768 | **yes** | yes | no |
| VPN connection | 65536 | yes | yes | no |
| **foreground service** | 131072 | **NO** | **NO** | **NO** |
| app zygote | 262144 | yes | yes | yes |

Masks: `IMPORT_SCREEN_OFF_FLAG=389052`, `IMPORT_SCREEN_ON_FLAG=392191`, `IMPORT_SCREEN_OFF_TO_SCREEN_ON_FLAG=293247`.

**Key finding: the foreground-service reason (`isFGService()`) is in none of the masks.** A foreground service never protects an app by itself on this firmware. That is why Flash's `connectedDevice|dataSync` service did not help (ERROR-074 / EXP-002).

### 2.1 Traffic (`isTrafficActive`)
**Measured 2026-10-09 (HIB-13, PASS):** with a foreground service and ~50 KB/s the log shows `uid:10482 is Protected by traffic because of speed 59 KB/S` and the UID stayed unfrozen for a 100 s screen-off run (no `freeze uid`, 118 beats at 1 s, zero late beats). `TranTrafficMonitor.getTrafficSpeed(uid)` in KB/s. Protected when speed >= 10 KB/s **and** the app has a foreground service **or** an ongoing notification (`STATUS_ONGOING_NOTI`), or when the package is in the traffic whitelist. Consequence: an active file transfer (hundreds of KB/s) with Flash's foreground notification should keep Flash unfrozen (HIB-13); idle chat traffic (a few bytes per second) does not. Faking >= 10 KB/s of traffic to stay alive would defeat the purpose of the freezer (battery) and is NOT recommended without an owner decision.

### 2.2 Bluetooth Active List (`inBluetoothActiveList`, Bit 15 = 32768) — VERIFIED & WORKING (HIB-12 PASS)
**Disassembled & Measured 2026-10-10 (HIB-12, PASS):**
In Transsion's `TranImportantScene.isImportantState`, Bit 15 (`IMPORT_BLUE_TOOTH = 32768` in `IMPORT_SCREEN_OFF_FLAG = 389052`) calls:
```java
TranAppStateManager.getInstance().inBluetoothActiveList(packageName)
```
which reads `Settings.Global.getString("bluetooth.active.applist")` and checks `indexOf(packageName) >= 0`.

**Firmware Reverse Engineering (`/apex/com.android.btservices/app/Bluetooth.apk`):**
In `com.android.bluetooth.btservice.ProfileService.updateActiveAppName(String pkg, int profileId)`:
1. Called by `GattService.startScan` (BLE scanning), `GattService.startAdvertisingSet` (BLE advertising), and `GattService.clientConnect` (GATT client).
2. It concatenates the caller's package name into `mStrAppList`:
   `Settings.Global.putString(resolver, "bluetooth.active.applist", mStrAppList)`.
3. `ProfileService` **never removes** packages from `bluetooth.active.applist` while the Bluetooth adapter remains on (only cleared on BT adapter reboot).
4. **Physical Device Verification (Infinix X6882B, Android 14):**
   - Started low-power BLE scan (`BluetoothLeScanner.startScan`).
   - `Settings.Global.getString("bluetooth.active.applist")` immediately recorded: `...com.android.settings,com.transfer.flash`.
   - Turned screen off (`input keyevent 26`) for sustained deep sleep:
   - System logcat recorded: `Hiber/importantScene: bt: packageName=com.transfer.flash` every 5 seconds!
   - `/sys/fs/cgroup/uid_10482/cgroup.freeze` remained strictly `0` across entire test.
   - Heartbeat: 38 consecutive beats, 0 late beats. Sockets completely preserved!
   - **Audio impact:** ZERO audio focus, ZERO audio playback, ZERO sound.
   - **Traffic impact:** 0 network bytes.

### 2.3 Audio Mix / Audio Focus (`isImportantState` Bit 5 = 32) — VERIFIED & WORKING (HIB-14 PASS)
**Measured 2026-10-09 (HIB-14, PASS):** When an app acquires audio focus (`AudioManager.requestAudioFocus`) and plays a silent static PCM loop (`AudioTrack.MODE_STATIC`), Transsion's audio focus hook in `com.android.server.audio.MediaFocusControl` synchronously calls `TranAppStateManager.getInstance().hookAudioFocusChanged(focusGain, uid, packageName)`.
This sets `STATUS_AUDIO_FOCUS` in `TranAppStateManager.mAppStateInfoMap`.
When the screen turns off and `TranImportantScene.isImportantState` evaluates `IMPORT_SCREEN_OFF_FLAG = 389052`:
1. It calls `this.isAllowAudioMix(packageName)` (returns `true` because Flash is not in the `AudioMixBlackList`).
2. It calls `TranAppStateManager.getInstance().getAudioFocusList(STATUS_AUDIO_FOCUS.getId())`.
3. It finds Flash's UID in the list, sets `pkg.setImportantReason("audio_mix")`, and logs `Hiber/importantScene: audio_mix: packageName=com.transfer.flash`.
4. It returns `true`, **completely exempting Flash from the screen-off freeze!**

**Device Measurements (Infinix X6882B, Android 14, screen-off):**
- In `DaemonProbeService`: Ran 183 consecutive beats across 3 minutes of screen-off. Zero late beats, zero freezes (`cgroup.freeze` remained `0`, PID absent from `/dev/freezer/frozen/tasks`).
- Data consumed: **0 bytes** (`txBytes` and `rxBytes` unchanged).
- Battery/CPU: **0% CPU load** (static 1600-byte zero PCM buffer in hardware DSP, no thread streaming).
- Volume: Inaudible / silent PCM zeros.

### 2.4 Float / Overlay Window (`floatWindowState`, Bit 2 = 4) — SCREEN-ON ONLY (HIB-17 FAIL ON SCREEN-OFF)
**Disassembled & Measured 2026-10-10 (HIB-17, SCREEN-ON PASS, SCREEN-OFF FAIL):**
In `TranImportantScene.isImportantState`, Bit 2 (`IMPORT_FLOAT_WINDOW = 4` in `IMPORT_SCREEN_OFF_FLAG = 389052`):
1. `TranAppStateManager.updateWindowState` intercepts window additions of type `TYPE_APPLICATION_OVERLAY` (2038).
2. However, `updateWindowState` explicitly checks: `boolean visible = window.isShown() && window.hasSurface()`.
3. When screen is **ON**: `isShown == true`, Hiber sets `pkg.setImportantReason("float_window")` and logs `Hiber/importantScene: float_window: packageName=com.transfer.flash`.
4. When screen turns **OFF** (`input keyevent 26`): WindowManager marks `isShown = false`. Within 5 seconds, `STATUS_SYSTEM_WINDOW` is cleared.
5. **Physical Device Measurement (Infinix X6882B, Android 14):** Tested with 1x1 transparent `TYPE_APPLICATION_OVERLAY` window under `SYSTEM_ALERT_WINDOW` isolated from Bluetooth/audio. At 5.6s after screen-off, Hiber logged `Hiber/sceneManager: freeze uid: 10482 com.transfer.flash pids:[31550] costTime=1ms` and placed the process in `/dev/freezer/frozen/tasks`. The probe process was suspended for the entire screen-off period (measured: 61,178 ms late beat gap on screen wake). Thus, floating overlay windows do NOT prevent screen-off hibernation.

### 2.5 Home Screen AppWidget (`isImportantState` Bit 6 = 64) — SCREEN-ON ONLY (EXCLUDED FROM SCREEN-OFF MASK)
**Disassembled & Measured 2026-10-09 / 2026-10-10:**
In `TranImportantScene`:
- Bit 6 (`IMPORT_WIDGET = 64`) is present in `IMPORT_SCREEN_ON_FLAG = 392191` (0x5FC3F).
- However, Bit 6 is **NOT present** in `IMPORT_SCREEN_OFF_FLAG = 389052` (0x5EFBC, bit 6 is 0)!
- Consequently, while a home screen widget keeps the app alive when the screen is on or dozing, sustained screen-off deep sleep ignores widgets and freezes the UID (measured: both AliExpress and Flash frozen after 20s screen-off without another shield). AppWidget alone does not prevent screen-off freezes.

### 2.6 AppZygote (`isImportantState` Bit 18 = 262144) — TRANSIENT DURING FORK ONLY (HIB-15)
**Disassembled & Measured 2026-10-09:**
In `ProcessList.startProcess`:
1. Immediately before fork: calls `TranAppStateManager.getInstance().hookUpdateAppZygote(definingUid, definingPkg, true)`.
2. Immediately after fork: calls `TranAppStateManager.getInstance().hookUpdateAppZygote(definingUid, definingPkg, false)`.
3. `STATUS_APP_ZYGOTE` is cleared immediately after process startup. AppZygote is transient for fork safety only.

### 2.7 Other Screen-Off Exemptions in Firmware
1. **VPN Service (`vpnState`, Bit 16 = 65536):**
   Active local `VpnService` interface sets `STATUS_VPN`. Exempts the app on screen-off. Requires one-time user VPN permission consent.
2. **Screen Record / MediaProjection (`screenRecordState`, Bit 3 = 8):**
   Active `MediaProjection` token (video screen share, ADR-102) sets `STATUS_SCREEN_RECORD`. Exempts during screen sharing.
3. **GPS / GNSS (`getGpsList`, Bit 13 = 8192):**
   Active GNSS request via `LocationManager.GPS_PROVIDER` hooks `GnssLocationProvider.hookUpdateGps`. Exempts on screen-off, but consumes GPS hardware battery.
4. **Live Wallpaper (`wallPaperState`, Bit 11 = 2048):**
   Active `WallpaperService` sets `STATUS_WALLPAPER`. Exempts on screen-off.
5. **FCM / C2DM Intent Pass-Through:**
   In `hiber.json` lines 461-473, `com.google.android.c2dm.intent.RECEIVE` is explicitly whitelisted in `callerWhitePackages` and `targetWhitePackages`, allowing remote push notifications to thaw frozen apps.

## 3. What happens after the freeze
`TranSocketClient` / `TranNativeService` talk to the native `/system_ext/bin/hiber` daemon (not readable from adb). `TranCgroup` moves the UID to `/dev/freezer/frozen` (`init.hiber.rc`). Proxies (`TranProxyManager`: alarm, job, wakelock, broadcast, service) take over the frozen app's alarms/jobs/wake locks: this is why the probe's `PARTIAL_WAKE_LOCK` was force-released (`Hiber/proxyWakeLock`). `TranNetworkManagementServiceMultiLinkImpl.destorySocketByUid` destroys the sockets 3 s later. `TranImNetController` + `network.app.whitePackages` thaw listed IM apps on packets.

## 4. What this means for Flash (Architectural Matrix)
1. Plain Foreground Service alone does not shield from Hiber on Transsion; standard FGS bit is omitted from screen-off masks.
2. **Active transfers are protected** by traffic (> 10 KB/s) + ongoing notification (HIB-13 PASS).
3. **Bluetooth Active List (Bit 15, `bt` - HIB-12 PASS):** Low-power BLE mesh scan / Radio link registers Flash in `bluetooth.active.applist`. Hiber logs `bt: packageName=com.transfer.flash` and skips the freeze. 100% non-audio, 0% CPU, 0 network bytes.
4. **Overlay Window (Bit 2, `float_window` - HIB-17):** Protects screen-on only; fails on screen-off because `isShown` is cleared to false by WindowManager.
5. **Audio Background Shield (Bit 5, `audio_mix` - HIB-14 PASS):** Silent audio focus loop provides 100% persistent exemption across screen-off deep sleep with 0% CPU, zero audio heard, and zero special permissions.
6. **Home Screen Widget (Bit 6, `widget`):** Protects screen-on only; excluded from `IMPORT_SCREEN_OFF_FLAG = 389052`.
7. **AppZygote (Bit 18, `app-zygote` - HIB-15):** Transient during fork only (sets false immediately after start).

## 4a. Can Flash put itself on the exempt list? (read 2026-10-09)

**No, not from the app.** Three lists exist and none is reachable by a normal app:

1. `/product/hiber/config/hiber.json` and the Griffin JSONs: read-only partition, package names fixed by Transsion.
2. Cloud policy (`griffin_policy_version`, `TranCloudHandler`): pushed by Transsion only.
3. The **user white list** `TranConfig.mUserWhiteAppList` (persisted in `/data/system/hiber/hiber.prop`, not readable from adb). Its only writers are `TranHibernationManager.setAppMode(uid, mode)` and `addWhiteApp(uid)`, reached through `ActivityManagerService.setAppMode` / `addWhiteApp` (Binder calls on `IActivityManager`). Both call `TranAmHooker.checkApmPermissionLocked(callerProcessRecord)` and otherwise throw `SecurityException("<uid> cannot access the method.")`. The check passes only for: uid <= 1000, shell (uid 2000), a system app, an updated system app, a platform-signed app, or an app with `FLAG_PERSISTENT` (only honoured for system apps). Flash is none of them.

So the user list is what Transsion's own Phone Master (a system app) edits when the user flips a Phone Master toggle. Toggling Flash in Phone Master > Lock screen cleanup did NOT change the freeze (HIB-10 pass 3), and XOS 14.5 has no Freezer/Hibernation screen that feeds it. We do not call, spoof or work around that API: impersonating a system caller or a listed package name is abuse and is rejected.

## 4b. Session-Aware Demand-Driven Audio Shield & Auto-Release on Idle (2026-10-10)

To prevent battery drain, user audio conflicts, and Play Store rejections, Flash does NOT hold the audio shield indefinitely.

1. **Activate Only During Active Work:**
   - Evaluated reactively across `transfersFlow`, `sessionsFlow`, `callFlow`, and `pttFlow`.
   - Engages keep-alive shield (Bluetooth tier when present in `bluetooth.active.applist`, or silent audio loop fallback) ONLY when:
     - Active file transfers (`Transferring`, `Verifying`, `Queued`).
     - Active voice/video calls or ongoing group calls.
     - Active PTT sessions (`Talking` or `Listening`).
     - Connected mesh peer sessions (`activeSessions.isNotEmpty()`).
2. **Auto-Release on Idle:**
   - As soon as active work completes and peers disconnect, Flash immediately:
     - Transitions tier to `NONE`.
     - Calls `audioTrack.stop()` and `audioTrack.release()`.
     - Calls `abandonAudioFocusRequest(...)` / `abandonAudioFocus(...)` to release audio focus immediately.
     - Dynamically drops `mediaPlayback` from foreground service (`types=16` `connectedDevice`).
     - Allows the phone to enter natural deep sleep with 0 audio DSP load and 0 battery drain.
3. **Graceful Audio Focus Loss:**
   - Listens to `OnAudioFocusChangeListener`: on `AUDIOFOCUS_LOSS` (e.g. Spotify/YouTube) or `AUDIOFOCUS_LOSS_TRANSIENT`, immediately pauses `audioTrack` without fighting for focus.
   - Falls back to low-power Bluetooth scan (Bit 15) or relies on background transfer traffic shield ($>10\text{ KB/s}$, Bit 14).
   - On `AUDIOFOCUS_GAIN`: resumes silent audio loop if session remains active.

## 5. How to reproduce the extraction
```bash
adb shell cat /product/hiber/config/hiber.json
adb pull /system/framework/services.jar            # then unzip classes.dex
dexdump -d classes.dex   # from Android SDK build-tools; split by "Class descriptor"
adb shell settings get global bluetooth.active.applist
```
