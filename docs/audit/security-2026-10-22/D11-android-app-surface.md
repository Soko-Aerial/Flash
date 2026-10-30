# Domain D11: Android Application Surface — White-Box Security Audit

**Date:** 2026-10-22  
**Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D11 — Android Application Surface (`:app`, `AndroidManifest.xml`, Activities, Services, Receivers, Providers)  
**Auditor:** Flash Security Audit Team (Domain D11 Specialist)  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash's Android client (`:app`) provides UI, background services, notifications, and platform integration for modern Android (API 24 to API 36/37).

The audit verified component declarations in `AndroidManifest.xml`, deep links, intent handlers, `PendingIntent` flags, `FileProvider` paths, backup rules, notification visibility, and tapjacking guards:
- All background services (`FlashBackgroundService`, `FlashCallService`, `PttSessionService`, `RadioLinkService`) and action receivers are strictly `exported="false"`.
- All `PendingIntent` instances specify `FLAG_IMMUTABLE`.
- Deep links (`flash://g/*`, `flash://enroll`) only populate mutable state flows that open confirmation dialogs requiring explicit user action (`REJ-D11-01`).
- `FileProvider` roots in `file_paths.xml` restrict sharing to dedicated subdirectories (`FlashReceived/`, `ws-received/`, cache), having previously removed root-path (`REJ-D11-02`).

However, several critical application surface vulnerabilities were identified:
- **High Severity (`SEC-D11-01`):** `MainActivity` handles `FlashNotificationManager.ACTION_OPEN_FILE` without checking caller identity, converting untrusted file path extras into `FileProvider` URIs with `FLAG_GRANT_READ_URI_PERMISSION` and launching `startActivity(view)`.
- **Medium Severity (`SEC-D11-02`):** Absence of `FLAG_SECURE` allows Android to store unencrypted task switcher thumbnails of confidential chats and keys.
- **Medium Severity (`SEC-D11-03`):** Message notifications omit `VISIBILITY_PRIVATE` and public versions, leaking message text on the lock screen.
- **Low Severity (`SEC-D11-04`):** Exported `FlashAppWidgetProvider` accepts unsolicited `com.transfer.flash.action.PIN_WIDGET` broadcasts from third-party apps to trigger system widget dialogs.
- **Low Severity (`SEC-D11-05`):** Missing touch filtering when obscured (`filterTouchesWhenObscured`) exposes pairing and transfer acceptance dialogs to tapjacking overlays.
- **Low Severity (`SEC-D11-06`):** Release builds retain unstripped `Log.*` calls printing device identifiers and connection states to Logcat.

---

## 2. Checklist Item Evaluation

| # | Checklist Item | Evaluation | Summary / Finding ID |
|---|---|---|---|
| 1 | `AndroidManifest.xml` exported components and permissions | **Vulnerable** (`SEC-D11-01`, `SEC-D11-04`) | `MainActivity` and `FlashAppWidgetProvider` are `exported="true"` without caller verification on privileged actions. All services/receivers are properly `exported="false"`. |
| 2 | Intent filters (`flash://g/*`, `flash://enroll`) and deep links | **Not vulnerable** | Handled in `MainActivity.kt:281, 436`; deep links only set state to open interactive confirmation dialogs (`REJ-D11-01`). |
| 3 | Intent redirection and extras trust | **Vulnerable** (`SEC-D11-01`) | `ACTION_OPEN_FILE` takes `EXTRA_FILE_PATH` directly from untrusted intent extras and executes `startActivity` with `FLAG_GRANT_READ_URI_PERMISSION`. |
| 4 | `PendingIntent` mutability | **Not vulnerable** | All `PendingIntent` call sites across `app/` strictly include `PendingIntent.FLAG_IMMUTABLE`. |
| 5 | `FileProvider` paths | **Not vulnerable** | `file_paths.xml` defines constrained paths (`FlashReceived/`, `ws-received/`, cache). Root-path `.` was previously eliminated (`REJ-D11-02`). |
| 6 | Content providers | **Not vulnerable** | No custom content providers exist; `androidx.core.content.FileProvider` is `exported="false"`. |
| 7 | Broadcast receivers (implicit vs explicit) | **Vulnerable** (`SEC-D11-04`) | `FlashAppWidgetProvider` is exported and processes implicit `ACTION_PIN_WIDGET` broadcasts without permission checks. |
| 8 | Foreground-service types and permissions | **Not vulnerable** | `FlashBackgroundService` declares `connectedDevice|dataSync|mediaPlayback`; `FlashCallService` declares `microphone|camera|connectedDevice`; `PttSessionService` declares `microphone|mediaPlayback|connectedDevice`. |
| 9 | `allowBackup` / `dataExtractionRules` / `fullBackupContent` | **Vulnerable** (Reported in `SEC-D01-06`) | `data_extraction_rules.xml` excludes `sharedpref`, `database`, `external`, and `logs`, but omits `filesDir` root, allowing unencrypted identity/swarm files to be backed up to cloud. |
| 10 | `debuggable` and network security config | **Not vulnerable** | `debuggable` defaults to false in release builds. Cleartext traffic is enabled only in debug variant (`app/src/debug/AndroidManifest.xml`). |
| 11 | WebViews | **Not applicable** | No `WebView` exists in the application; UI is 100% Jetpack Compose. |
| 12 | Notification content on lock screen | **Vulnerable** (`SEC-D11-03`) | Chat notifications lack `VISIBILITY_PRIVATE` and `.setPublicVersion()`, exposing message text on lock screens. |
| 13 | Clipboard use | **Vulnerable** (Reported in `SEC-D06-06`) | `FlashClipboard` copies group secrets, keys, and message text without setting `ClipDescription.EXTRA_IS_SENSITIVE`. |
| 14 | Screenshots and `FLAG_SECURE` | **Vulnerable** (`SEC-D11-02`) | `FLAG_SECURE` is never applied, permitting system recent task thumbnails and unauthorized screen capture of private chats. |
| 15 | Share-sheet / `ACTION_SEND` hostile inputs | **Not vulnerable** | `MainActivity.extractUrisFromIntent` validates all URIs using `ShareUriPolicy.accepts()`, rejecting `file:` URIs and Flash's internal authorities. |
| 16 | Overlay / tapjacking protection | **Vulnerable** (`SEC-D11-05`) | UI does not enable `filterTouchesWhenObscured`, leaving pairing and transfer approval dialogs vulnerable to overlay tapjacking. |
| 17 | Task hijacking | **Not vulnerable** | Default `taskAffinity` used with standard launchMode; single-task / single-top correctly managed. |
| 18 | ProGuard / R8 rules | **Vulnerable** (`SEC-D11-06`) | R8 optimization is enabled, but ProGuard rules omit `-assumenosideeffects` to strip Logcat logging in release builds. |
| 19 | Exposed debug screens (`app/src/main/java/.../debug`) | **Not vulnerable** | Spike activities (`RadioSpikeActivity`, `DaemonProbeService`) are strictly located in `app/src/debug` and excluded from release APKs. |
| 20 | Runtime permission scope creep | **Not vulnerable** | Uses-permissions in manifest are justified (`RECORD_AUDIO` for voice/calling, `CAMERA` for video calls, `BLUETOOTH_SCAN/CONNECT` for keep-alive/RFCOMM). `SYSTEM_ALERT_WINDOW` is requested for Transsion Hiber keepalive. |

---

## 3. Detailed Audit Findings

### SEC-D11-01 — Intent Redirection and FileProvider Grant Confusion via Exported MainActivity ACTION_OPEN_FILE
- **Severity:** High        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Malicious/untrusted app on the same device)
- **Component:** `app/src/main/java/com/transfer/flash/MainActivity.kt:475-485`, `app/src/main/java/com/transfer/flash/MainActivity.kt:3923-3978`
- **CWE / standard:** CWE-926: Improper Export of Android Application Components; CWE-927: Use of Implicit Intent for Sensitive Communication; CVSS:3.1/AV:L/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N (7.7 High)
- **What is wrong:** 
  `MainActivity` is exported (`android:exported="true"`) to act as the main launcher and share target. In `handleIncomingIntent(intent)`:
  ```kotlin
  FlashNotificationManager.ACTION_OPEN_FILE -> {
      val path = intent.getStringExtra(FlashNotificationManager.EXTRA_FILE_PATH)
      if (path != null) {
          openAttachment(this, path, guessMimeType(path, path, this))
      }
      pendingShortcutTab.value = FlashDestination.Transfers
  }
  ```
  `MainActivity` treats `ACTION_OPEN_FILE` identically regardless of whether the intent originated from Flash's internal notification `PendingIntent` or an external application.
  
  In `openAttachment`:
  ```kotlin
  val uri: android.net.Uri = try {
      if (path.startsWith("content://") || path.startsWith("file://")) {
          android.net.Uri.parse(path)
      } else {
          androidx.core.content.FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              java.io.File(path),
          )
      }
  } ...
  val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
      setDataAndType(uri, effectiveMime.ifBlank { "*/*" })
      addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
  }
  context.startActivity(view)
  ```
  Flash takes the untrusted `path` extra, converts it into a `FileProvider` URI, grants `FLAG_GRANT_READ_URI_PERMISSION`, and fires an implicit `ACTION_VIEW` intent.
- **Impact:** 
  1. **Private File Exfiltration:** A rogue app installed on the device can launch `MainActivity` with `ACTION_OPEN_FILE` and `EXTRA_FILE_PATH` set to any file inside Flash's FileProvider roots (e.g. downloaded transfer files, cached voice recordings `flash-voice-*.m4a`, camera captures, or log export archives). By registering an intent filter for `ACTION_VIEW`, the rogue app receives read permissions to Flash's private files.
  2. **Unsolicited Execution / Package Installation:** If `path` points to a received APK file or executable payload, Flash automatically launches the system package installer or handler without user prompt or confirmation.
- **Evidence:** 
  In [MainActivity.kt:475-485](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/MainActivity.kt#L475-L485) and [MainActivity.kt:3923-3978](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/MainActivity.kt#L3923-L3978).
- **Why existing guards do not stop it:** 
  `ShareUriPolicy` was implemented to protect `ACTION_SEND`, but `ACTION_OPEN_FILE` was overlooked and left unauthenticated on the exported activity.
- **Verifier result:** survived
- **Recommended fix:** 
  Do not route `ACTION_OPEN_FILE` through an exported Activity. Route notification file opening through an internal unexported BroadcastReceiver (`exported="false"`) or explicitly verify that the intent originated from the app itself (e.g. validating a cryptographically secure in-memory nonce or using an internal pending intent token).
- **Related:** `file_paths.xml`, `ShareUriPolicy.kt`.

---

### SEC-D11-02 — Missing FLAG_SECURE Allows Android Task Switcher Snapshots and Screen Capture of Confidential Chats and Secrets
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Screen recording app / Local spyware), **A7** (Forensic device examiner / Device thief)
- **Component:** `app/src/main/java/com/transfer/flash/MainActivity.kt:96-154`
- **CWE / standard:** CWE-200: Exposure of Sensitive Information; CWE-359: Exposure of Private Personal Information; CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N (5.5 Medium)
- **What is wrong:** 
  `MainActivity` never calls `window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)`.
- **Impact:** 
  1. When Flash is placed in the background or switched via the Android Overview (recents) task switcher, the Android OS captures an unencrypted bitmap snapshot of the active screen and stores it in `/data/system_ce/0/snapshots/`. Confidential messages, group invite codes, device keys, and user contacts remain stored on disk in cleartext image format.
  2. Applications with `MediaProjection` or accessibility permissions can capture screenshots of active Flash conversations and private keys.
- **Evidence:** 
  Inspection of `MainActivity.kt` confirms complete absence of `FLAG_SECURE`.
- **Why existing guards do not stop it:** 
  No screen security policy or user preference toggle was implemented.
- **Verifier result:** survived
- **Recommended fix:** 
  Provide a "Screen Security" toggle in Settings (defaulting to enabled for high-security environments) that applies `FLAG_SECURE` to the window, preventing recents snapshots and third-party screen capture.
- **Related:** `docs/security.md` §13.

---

### SEC-D11-03 — Missing Lock Screen Visibility Restrictions Leaks Private Message Content and Sender Information
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A7** (Thief, Shoulder-surfer with physical sight of locked phone)
- **Component:** `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt:617-628`
- **CWE / standard:** CWE-200: Exposure of Sensitive Information; CVSS:3.1/AV:P/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N (4.6 Medium)
- **What is wrong:** 
  In `FlashNotificationManager.kt`:
  ```kotlin
  val notification = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
      .setSmallIcon(R.drawable.ic_notification_flash)
      .setContentTitle(title)
      .setContentText(body)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setAutoCancel(true)
      .setContentIntent(contentIntent)
      .build()
  ```
  The notification builder does not specify `.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` and does not provide an obfuscated public notification via `.setPublicVersion(...)`.
- **Impact:** 
  On devices with default Android notification settings, full message contents (`body`) and sender identity (`title`) are rendered on the lock screen, exposing confidential conversations to bystanders without requiring device unlock.
- **Evidence:** 
  [FlashNotificationManager.kt:617-628](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt#L617-L628).
- **Why existing guards do not stop it:** 
  Notification channels are initialized with standard importance (`IMPORTANCE_HIGH`), but per-notification visibility levels are omitted.
- **Verifier result:** survived
- **Recommended fix:** 
  Configure `.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` on message notifications and supply a sanitized public version displaying "New message" without message text or sender name.
- **Related:** Android Notification Privacy Guidelines.

---

### SEC-D11-04 — Unsolicited System "Pin Widget" Prompts via Exported FlashAppWidgetProvider
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Untrusted app on the same device)
- **Component:** `app/src/main/java/com/transfer/flash/widget/FlashAppWidgetProvider.kt:49-55`, `app/src/main/AndroidManifest.xml:174-185`
- **CWE / standard:** CWE-926: Improper Export of Android Application Components; CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:N/I:L/A:N (3.3 Low)
- **What is wrong:** 
  `FlashAppWidgetProvider` is declared in `AndroidManifest.xml` with `exported="true"` and an intent filter for `com.transfer.flash.action.PIN_WIDGET`. In `onReceive`:
  ```kotlin
  if (intent.action == ACTION_PIN_WIDGET) {
      val result = requestPinWidget(context)
      Log.i(TAG, "Pin widget requested via action: result=$result")
  }
  ```
  `requestPinWidget` invokes `AppWidgetManager.requestPinAppWidget`, which prompts the user to add Flash's widget to their home screen.
- **Impact:** 
  Any third-party app on the device can send an implicit broadcast to trigger the system "Add to Home screen?" confirmation dialog repeatedly, harassing the user or interfering with foreground tasks.
- **Evidence:** 
  [FlashAppWidgetProvider.kt:49-55](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/widget/FlashAppWidgetProvider.kt#L49-L55).
- **Why existing guards do not stop it:** 
  The receiver does not enforce a signature-level permission or restrict the action to internal callers.
- **Verifier result:** survived
- **Recommended fix:** 
  Remove `com.transfer.flash.action.PIN_WIDGET` from the exported receiver's manifest `<intent-filter>` and trigger widget pinning exclusively through explicit internal calls from in-app UI.
- **Related:** `HIB-16`.

---

### SEC-D11-05 — Absence of Tapjacking / Window Obscuration Protection on Critical Pairing & Transfer Accept UI
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Malicious overlay app on the same device)
- **Component:** `app/src/main/java/com/transfer/flash/MainActivity.kt:96-103`
- **CWE / standard:** CWE-1021: Improper Restriction of Rendered UI Layers or Frames; CVSS:3.1/AV:L/AC:H/PR:L/UI:R/S:U/C:N/I:H/A:N (4.4 Low)
- **What is wrong:** 
  Neither `MainActivity` nor the Compose root views configure `android:filterTouchesWhenObscured="true"` or verify `MotionEvent.FLAG_WINDOW_IS_OBSCURED`.
- **Impact:** 
  A malicious application with `SYSTEM_ALERT_WINDOW` permission can display a transparent or deceptive overlay over Flash while a pairing confirmation dialog or incoming file acceptance dialog is displayed, tricking the user into touching the "Accept" button (tapjacking).
- **Evidence:** 
  Search across `app/` and `ui/` confirms zero occurrences of `filterTouchesWhenObscured` or touch obscuration handling.
- **Why existing guards do not stop it:** 
  No overlay touch filtering was implemented.
- **Verifier result:** survived
- **Recommended fix:** 
  Set `filterTouchesWhenObscured = true` on root Compose views or in window attributes, discarding touches when another window is obscuring Flash.
- **Related:** `docs/security.md` §13.

---

### SEC-D11-06 — Production Release Build Retains Unstripped Log.* Calls Leaking Device Identifiers and Runtime State
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Other apps reading Logcat on older Android / adb), **A7** (Forensic device examiner)
- **Component:** `app/build.gradle.kts:25-35`, `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:797`
- **CWE / standard:** CWE-532: Insertion of Sensitive Information into Log File; CVSS:3.1/AV:L/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N (4.0 Low)
- **What is wrong:** 
  `app/build.gradle.kts` enables R8 optimization (`optimization.enable = true`), but does not configure ProGuard rules to discard `android.util.Log` calls in release builds.
  
  Production classes emit hundreds of `Log.i` and `Log.w` calls, including:
  ```kotlin
  Log.i(TAG_DISCOVERY, "Starting Flash discovery with deviceId=${identity.deviceId.value} friendlyName=${identity.friendlyName}")
  ```
- **Impact:** 
  Device identifiers, peer IDs, connection outcomes, transfer states, and internal routing parameters are emitted to Android Logcat in release APKs, exposing operational metadata to local examiners and debugging tools.
- **Evidence:** 
  [app/build.gradle.kts:25-35](file:///C:/Users/KaliOxygen/Downloads/Flash/app/build.gradle.kts#L25-L35).
- **Why existing guards do not stop it:** 
  No `-assumenosideeffects` rules are defined in the release configuration.
- **Verifier result:** survived
- **Recommended fix:** 
  Add a release ProGuard rule stripping debug and info logging:
  ```proguard
  -assumenosideeffects class android.util.Log {
      public static int d(...);
      public static int v(...);
      public static int i(...);
  }
  ```
- **Related:** `AGENTS.md` §24.

---

## 4. Candidate Findings Refuted During Audit

| Candidate | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D11-01: Malicious Deep Link Execution via `flash://g/*` and `flash://enroll` | A browsable deep link intent can force Flash to join an arbitrary group or enroll into an arbitrary hub without user consent. | **Refuted.** In `MainActivity.kt:1181-1204`, deep links only set `pendingJoinLink` / `pendingEnrollLink`, which opens `FlashJoinGroupDialog` or `FlashEnrollDialog`. The user must manually review and tap the "Join Group" or "Enroll Device" action buttons. |
| REJ-D11-02: FileProvider Path Traversal via `content://` Authority | An attacker can access arbitrary filesystem files by supplying path traversal (`..`) in `androidx.core.content.FileProvider`. | **Refuted.** `androidx.core.content.FileProvider` canonicalizes file paths against configured XML roots (`<external-files-path>`, `<files-path>`, `<cache-path>`) and throws `IllegalArgumentException` if any path escapes the root directory. Furthermore, `file_paths.xml` was already hardened to remove root-path (`<root-path path="." />`). |
| REJ-D11-03: Service / Receiver Hijacking via Unprotected Exported Components | Core background services (`FlashBackgroundService`, `FlashCallService`, `PttSessionService`, `RadioLinkService`) or action receivers can be triggered by third-party apps. | **Refuted.** All background services and action receivers in `AndroidManifest.xml` are explicitly marked `android:exported="false"`. `FlashTileService` is protected by `android.permission.BIND_QUICK_SETTINGS_TILE`. |

---

## 5. Files Inspected

- `app/src/main/AndroidManifest.xml`
- `app/src/debug/AndroidManifest.xml`
- `app/src/main/res/xml/file_paths.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/shortcuts.xml`
- `app/src/main/res/xml/widget_flash_info.xml`
- `app/build.gradle.kts`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `app/src/main/java/com/transfer/flash/ShareUriPolicy.kt`
- `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt`
- `app/src/main/java/com/transfer/flash/notifications/FlashTransferActionReceiver.kt`
- `app/src/main/java/com/transfer/flash/widget/FlashAppWidgetProvider.kt`
- `app/src/main/java/com/transfer/flash/calling/FlashCallActionReceiver.kt`
- `app/src/main/java/com/transfer/flash/calling/FlashCallService.kt`
- `app/src/main/java/com/transfer/flash/ptt/PttSessionActionReceiver.kt`
- `app/src/main/java/com/transfer/flash/ptt/PttSessionService.kt`
- `app/src/main/java/com/transfer/flash/service/RadioLinkService.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
- `app/src/main/java/com/transfer/flash/debug/TranssionHiberKeepAlive.kt`
- `ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashClipboard.kt`
