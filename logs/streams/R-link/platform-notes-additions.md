# Android Platform Notes Additions — Stream R-link

## Foreground Service Type `connectedDevice` on Android 14 (API 34) & Android 15 (API 35)

- **Date verified**: 2026-10-16
- **Official Documentation Sources**:
  - Android Developers: *Foreground service types are required* (Android 14 changes)
    `https://developer.android.com/about/versions/14/changes/fgs-types-required#connected-device`
  - Android Developers: *Foreground service types and permissions*
    `https://developer.android.com/develop/background-work/services/fg-service-types#connected-device`
  - Android Developers: *Behavior changes: all apps (Android 15)*
    `https://developer.android.com/about/versions/15/behavior-changes-all`

### 1. Requirements for `connectedDevice`
1. **Manifest Declaration**:
   - Must declare `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />`. (Normal permission; granted automatically at install time).
   - In `<service>`, must specify `android:foregroundServiceType="connectedDevice"`.
2. **Runtime Invocation on API 34+ (Android 14+)**:
   - `Service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` must explicitly pass the type flag. Calling the legacy 2-argument `startForeground(id, notification)` without type flag defaults to the manifest type on API 34, but passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` avoids ambiguity and meets CTS requirements.
   - If started with a type not declared in the manifest, Android 14+ throws `MissingForegroundServiceTypeException`.
3. **Notification Permission (Android 13+ / API 33+)**:
   - Notification display requires runtime `android.permission.POST_NOTIFICATIONS`. If denied, the service still runs in foreground, but the notification is suppressed (or appears in the Task Manager).
4. **Android 15 Behavior & Lifetime**:
   - Android 15 introduces execution timeouts (6-hour limits) for `dataSync` and `mediaProcessing`, but `connectedDevice` is **exempt** from the 6-hour timeout as long as communication with the external device is maintained.
   - The service must be shut down promptly (`stopSelf()` / `stopForeground(STOP_FOREGROUND_REMOVE)`) when the device disconnects or is no longer communicating.
5. **OEM Battery & App Standby Constraints**:
   - Aggressive OEM battery savers (Transsion HiOS/XOS, Xiaomi MIUI/HyperOS, Samsung OneUI) may freeze or terminate background apps even with an active foreground service unless battery optimization is disabled by the user ("Unrestricted"). No claim of background reliability on Transsion hardware until verified on physical test devices.

### 2. Architecture in Flash
- Pure logic policy: `RadioServicePolicy` in `:core:radio` (`commonMain`), pure Kotlin, decides whether the service should run based on `RadioGatewayState`:
  - `shouldServiceRun(state)`: returns true iff `state.isEnabled` AND `state.connectState is Connected`.
  - Determines honest notification title and text (e.g. "Radio Connected — VR-N76", "Radio Disconnected — Reconnecting...").
- Host adapter: `RadioLinkService` in `:app` (`app/src/main/java/com/transfer/flash/service/RadioLinkService.kt`), thin Android `Service` bound to gateway lifecycle, passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` on API 34+.
