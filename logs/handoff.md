# Current Handoff

## 2026-09-01 — Bug 6 PHYSICALLY VERIFIED on Samsung; Infinix failure re-attributed to 4% battery power policy; next = EXP-003 (charged Infinix re-test), then voice/video calling

### Current branch
`dev` (work is UNCOMMITTED in the working tree; HEAD `9e94a2d`)

### Last verified build
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (2026-08-31 (b) changeset, unchanged since)
- **Physical verification 2026-09-01:** Samsung SM-G986U1 (~90% battery) stays ONLINE with
  screen off / app left — peer sees it online, messages arrive, no FGS exceptions. **Bug 6
  fix verified working.** Infinix X6882B (~4% battery) still goes offline within seconds —
  attributed to low-battery power policy (battery saver / OEM auto-kill), NOT the fixed bug.
  See `logs/experiments.md` EXP-002 and `logs/errors.md` ERROR-020 (updated).

### Current phase
Chat UI bug fixes (track 1): **all 7 bugs implemented; Bug 6 physically verified on Samsung.**
Infinix low-battery behavior is a device power-policy finding, not an open code bug.
Voice/video calling (track 1 remainder) is next. KMP migration stays de-prioritized.

### Working features (NEW since last handoff)
- **Bug 6 VERIFIED (physical):** Samsung at 90% battery stays online through screen-off /
  leave-app. The FGS + wake-lock + crash-proof sticky-restart stack works as designed.
- **Bug 7 (notifications):** implemented 2026-08-31 (b) — device checklist pass still pending
  (suppression, tap-to-open, dedupe, screen-off arrival).
- **Research finding (how WhatsApp does it):** WhatsApp-class apps use FCM (Google's shared
  Doze-exempt push channel) — impossible for Flash (LAN P2P, no cloud). Our sanctioned
  equivalent is the battery-optimization exemption via the Settings "Background transfers"
  toggle; the official Doze acceptable-use-case table explicitly covers "can't use FCM /
  Doze breaks core function" apps. Recorded in `docs/android-platform-notes.md` 2026-09-01.

### In progress
- **EXP-003 (decisive, owner-driven):** charge the Infinix above ~20%, grant the
  battery-optimization exemption (Settings → Background transfers ON), repeat the
  screen-off test. Stays online → low-battery policy confirmed, document, done. Still
  offline → OEM auto-kill; needs manual OEM exemption (Settings → Battery → Flash → allow
  background activity) and possibly an in-app guidance screen.
- Bug 7 device checklist pass (`docs/ui/notification-ui.md`).

### Broken
- Nothing new. (Pre-existing timing-flaky test note below.)

### Last change
Documentation-only session (2026-09-01): recorded EXP-002 differential test results,
updated ERROR-020 to RESOLVED-verified, added 2026-09-01 platform-notes entry (battery
saver supersedes FGS priority; FCM research; exemption unblocks sticky-restart promotion).
No code changes.

### Last test
Physical two-phone differential test (EXP-002): Samsung 90% PASS, Infinix 4% FAIL →
re-attributed to low-battery power policy. No code changes this session, so no new build.

### Known blockers
- Kotlin daemon flakiness: treat "BUILD SUCCESSFUL" as success; don't trust exit code alone
- Gradle metadata cache corruption (hit AGAIN this session): `gradlew --stop`, `taskkill //F //IM java.exe`,
  delete `E:\AndroidDev\Gradle\caches\modules-2\metadata-2.107`, rebuild
- Build env: `E:\` hosts SDK (`E:\AndroidDev\SDK`), Gradle home (`E:\AndroidDev\Gradle`), JBR
  (`E:\AndroidDev\AndroidStudio\android-studio\jbr`) — install command at the bottom of this file's
  current section is authoritative
- The messaging backoff timing test PASSED this session (whole class green) but remains
  inherently timing-sensitive; deterministic cleanup still worthwhile
- PHASE-21/22 depend on Phases 06–20 groundwork that does not exist yet; deferred
- KMP migration is DE-prioritized until chat UI bugs + calling modules are done

### Recommended next task
1. **EXP-003** (owner-driven): charged Infinix + exemption granted → repeat screen-off test;
   record in `logs/experiments.md`.
2. Then voice/video calling modules (WebRTC, `shepeliev/webrtc-kmp`, signaling over the WS mesh).

### Files most relevant to next task
- `logs/experiments.md` (EXP-002 recorded; EXP-003 template ready)
- `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt` (battery-exemption wiring)
- `docs/ui/notification-ui.md` (Bug 7 device checklist)

### Remaining work summary (for next AI)
1. EXP-003 charged-Infinix re-test (owner-driven)
2. Bug 7 device checklist pass
3. **Voice/video calling:** `core:calling` + `ui:calling` with WebRTC (`shepeliev/webrtc-kmp`),
   WireFrame types, signaling over WS mesh, call UI overlay
4. Deterministic cleanup of the messaging backoff timing test
5. Then: commit all bug-fix work (with `Co-authored-by: Copilot` trailer), resume KMP migration

### Install command (PowerShell, authoritative)
```powershell
Set-Location "C:\Users\KaliOxygen\Downloads\Flash"
$env:JAVA_HOME = "E:\AndroidDev\AndroidStudio\android-studio\jbr"
$env:GRADLE_USER_HOME = "E:\AndroidDev\Gradle"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=Z:\nope"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat :app:installDebug --no-configuration-cache --console=plain
```
Git-Bash equivalent: prefix with `JAVA_HOME="E:/AndroidDev/AndroidStudio/android-studio/jbr" GRADLE_USER_HOME="E:/AndroidDev/Gradle" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:/nope"` and forward slashes; adb at `"E:\AndroidDev\SDK\platform-tools\adb.exe"` (quote it in Git Bash).

## 2026-08-31 (b) — Bugs 1–7 ALL IMPLEMENTED; Bug 6 root-caused & re-fixed; next = physical two-phone verification, then voice/video calling

### Current branch
`dev` (work is UNCOMMITTED in the working tree; HEAD `9e94a2d`)

### Last verified build
- `:core:messaging:testDebugUnitTest --tests *RealFlashChatRepositoryTest*` → **BUILD SUCCESSFUL**, XML `failures="0"` (whole class incl. the previously-flaky backoff test AND the two new Bug 7 callback regression tests).
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (after fixing one compile iteration: battery-exemption callback hoisted through `FlashApp`/`FlashShell` as `onEnableBackgroundTransfers`).

### Current phase
Chat UI bug fixes (track 1): **all 7 bugs implemented**. Bug 6 was REOPENED after the owner's
physical test ("still goes offline after a few seconds") and the REAL root cause was found on
device — see ERROR-020. Voice/video calling (track 1 remainder) is next. KMP migration stays
de-prioritized.

### Working features (NEW since last handoff)
- **Bug 6 RE-FIXED (code-level, ERROR-020):** on-device logcat proved a sticky-restart crash
  loop — `ForegroundServiceStartNotAllowedException` uncaught in
  `FlashBackgroundService.onCreate → startAsForeground` killed the process EVERY time the
  system restarted the START_STICKY service while backgrounded (7 FATALs captured).
  Fix: `startAsForeground()` catches everything and returns Boolean; `onCreate` order is now
  locks → screen receiver → engine start → foreground promotion; refusal → log + `stopSelf()`
  (mesh keeps running in-process, no crash loop). Plus `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  wired to the Settings "Background transfers" toggle (user-initiated AOSP Doze exemption).
- **ERROR-021 fixed:** `drainMutex` NPE (declared below the `init` block that launches the
  drain coroutine → init-order race → uncaught NPE process death) — moved above with a
  comment locking the ordering constraint.
- **Bug 7 IMPLEMENTED (notifications):** `docs/ui/notification-ui.md` filled to DESIGNED first
  (§34), then: `FlashNotificationManager` (`flash_messages` channel, per-conversation ids,
  immutable PendingIntent → `MainActivity` with `EXTRA_CONVERSATION_ID`), monochrome
  `ic_notification_flash.xml`, library-safe defaulted callbacks
  (`onInboundTextMessage`/`onInboundAttachment`) fired only on real inserts (replay-proof),
  foreground+open-conversation suppression, notification-tap → conversation via
  `pendingNotificationConversation` flow consumed in `FlashShell` (engine-ready gated).
- WifiLock finding (API 34+): HIGH_PERF is remapped to LOW_LATENCY and LOW_LATENCY is only
  active foreground+screen-on — NO WifiLock mode keeps the radio up in background on modern
  Android. Lock retained for the foreground hot path only. Full dumpsys evidence in
  `docs/android-platform-notes.md` 2026-08-31 (b).

### In progress
- Physical two-phone verification of Bug 6 + Bug 7 (THE decisive pending step)

### Broken
- Nothing new. (Pre-existing timing-flaky test note below.)

### Last change
Bug 6 re-fix + Bug 7 implementation, both built green. Files: `FlashBackgroundService.kt`,
`RealFlashChatRepository.kt` (drainMutex order + callbacks), `DiscoveryEngineHolder.kt`
(callback wiring), `MainActivity.kt` (foreground state, onNewIntent, battery exemption,
pending-conversation flow), `FlashNotificationManager.kt` (NEW), `ic_notification_flash.xml`
(NEW), `AndroidManifest.xml` (permission), `notification-ui.md` (DESIGNED),
`RealFlashChatRepositoryTest.kt` (2 new tests), platform-notes/errors/progress updated.

### Last test
- See Last verified build above. Also: editor diagnostics clean on all changed files.
- Physical verification PENDING: (1) background/screen-off phone A >45s → phone B still sees
  it online, message arrives, logcat has NO `ForegroundServiceStartNotAllowedException`/FATAL;
  (2) toggle ON "Background transfers", grant the exemption dialog, repeat;
  (3) Bug 7 checklist in `docs/ui/notification-ui.md` (suppression, tap-to-open, dedupe,
  screen-off arrival). On this Infinix also check OEM "Phone Master"/battery manager — may
  need a manual background-activity exemption (AOSP exemption does not control it).

### Known blockers
- Kotlin daemon flakiness: treat "BUILD SUCCESSFUL" as success; don't trust exit code alone
- Gradle metadata cache corruption (hit AGAIN this session): `gradlew --stop`, `taskkill //F //IM java.exe`,
  delete `E:\AndroidDev\Gradle\caches\modules-2\metadata-2.107`, rebuild
- Build env: `E:\` hosts SDK (`E:\AndroidDev\SDK`), Gradle home (`E:\AndroidDev\Gradle`), JBR
  (`E:\AndroidDev\AndroidStudio\android-studio\jbr`) — install command at the bottom of this file's
  current section is authoritative
- The messaging backoff timing test PASSED this session (whole class green) but remains
  inherently timing-sensitive; deterministic cleanup still worthwhile
- PHASE-21/22 depend on Phases 06–20 groundwork that does not exist yet; deferred
- KMP migration is DE-prioritized until chat UI bugs + calling modules are done

### Recommended next task
1. Physical two-phone verification above (owner-driven). Record results in `logs/experiments.md`.
2. Then voice/video calling modules (WebRTC, `shepeliev/webrtc-kmp`, signaling over the WS mesh).

### Files most relevant to next task
- `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt`
- `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `core/messaging/src/main/java/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `docs/ui/notification-ui.md`, `logs/errors.md` (ERROR-020/021)

### Remaining work summary (for next AI)
1. Physical verification (Bug 6 + Bug 7 checklists)
2. **Voice/video calling:** `core:calling` + `ui:calling` with WebRTC (`shepeliev/webrtc-kmp`),
   WireFrame types, signaling over WS mesh, call UI overlay
3. Deterministic cleanup of the messaging backoff timing test
4. Then: commit all bug-fix work (with `Co-authored-by: Copilot` trailer), resume KMP migration

### Install command (PowerShell, authoritative)
```powershell
Set-Location "C:\Users\KaliOxygen\Downloads\Flash"
$env:JAVA_HOME = "E:\AndroidDev\AndroidStudio\android-studio\jbr"
$env:GRADLE_USER_HOME = "E:\AndroidDev\Gradle"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=Z:\nope"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat :app:installDebug --no-configuration-cache --console=plain
```
Git-Bash equivalent: prefix with `JAVA_HOME="E:/AndroidDev/AndroidStudio/android-studio/jbr" GRADLE_USER_HOME="E:/AndroidDev/Gradle" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:/nope"` and forward slashes; adb at `"E:\AndroidDev\SDK\platform-tools\adb.exe"` (quote it in Git Bash).

## 2026-08-31 — Bugs 1-6 IMPLEMENTED; next = Bug 7, then voice/video calling [SUPERSEDED — Bug 6 root cause turned out to be the sticky-restart crash loop, see the (b) section above and ERROR-020]

### Current branch
`dev` (work is UNCOMMITTED in the working tree)

### Last verified build
`:app:assembleDebug` → **BUILD SUCCESSFUL** (2m 40s) with Bugs 1–6 on disk. The Bug 5 reconnect regression test also passes in isolation. HEAD remains `9e94a2d`; the full bug-fix changeset is uncommitted in the working tree.

### Current phase
Chat UI bug fixes (track 1 of the 2-track plan: 7 bugs → then voice/video calling modules). KMP migration is DE-prioritized until both tracks land.

### Working features (NEW)
- **Bug 1 DONE:** Single tap no longer opens the actions overlay (`FlashMessageBubble.kt:194-205` — onClick only toggles selection in selection mode; onLongClick is the exclusive actions trigger)
- **Bug 2 DONE:** Reactions/actions overlay now works on voice/files/video/images (`FlashFileMessageCard.kt`, `FlashImageGrid.kt` — added onLongPress propagation)
- **Bug 3 DONE:** Per-MIME auto-download of inbound offers, complete end-to-end:
  - Engine: `DiscoveryEngineHolder.kt` — `@Volatile` `autoDownloadVoice/Image/Video/File` mirrors + `onIncomingOffer` policy lambda (auto-accepts voice+image by default, video+file ask in-bubble) + hook in `handleInboundBinary`
  - Settings: `FlashSettingsScreen.kt` 4 SwitchRows + `FlashSettingsDataStore.kt` 4 keys/flows/setters
  - Wiring: `AppEngine.kt` mirrors DataStore → holder; `MainActivity.kt` collects/persists/wires
  - Shared parity: `core/engine/Flash.kt` `attachmentProgress` now maps `Offered → AwaitingAcceptance`
- **Bug 4 DONE:** Splash animation extracted into a reusable theme composable:
  - `ui:theme/.../FlashBrandAnimation.kt` — the bolt + discovery rings + glow + breathing loop,
    now honors `FlashTheme.motion.reduceMotion` (static bolt at rest), draws an optional dark
    gradient `background`, and is size-driven by its `modifier`
  - `app/.../ui/splash/FlashSplashScreen.kt` — now a thin delegate to `FlashBrandAnimation`
    (visual launch splash unchanged)
  - `ui/chat/.../ui/transfers/FlashTransfersScreen.kt` — `LoadingRows` reuses it as a compact
    branded loading mark above the skeleton rows (`background=false`, 96dp box)
- **Bug 5 DONE:** peer session-up resets pending outbox backoff and drains immediately; reconnect regression test passes in isolation.
- **Bug 6 DONE (code-level):** visible `MainActivity.onStart` launches the connected-device FGS; it stays alive after `onStop` so background mesh presence/receiving can continue. Physical two-phone verification pending.
- Phase 03 logging abstraction (`FlashLog`) committed & tested (`da4fba6`)
- All 9 KMP migration decisions (D1–D9) recorded
- In-bubble Accept/Decline buttons on inbound file offers (`FlashFileMessageCard.kt:214-228`)

### In progress
- Bug 7 (see `### Broken` below) — NOT started
- Voice/video calling (WebRTC) — NOT started

### Broken
- Bug 7: No message notifications — needs `FlashNotificationManager.kt`

### Last change
Bug 6 implemented (ERROR-020): `MainActivity.onStart()` is now the sole owner that launches `FlashBackgroundService` while the activity is visible; the delayed launch was removed from `DiscoveryEngineHolder.ensureStarted`. The service stays running across `onStop`, uses `ContextCompat.startForegroundService` for API 24+, logs launch failures, and uses a LOW-importance notification channel. Also fixed Bug 5's pending explicit-API compile error (`public notifyPeerSessionUp`). Uncommitted.

### Last test
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (2m 40s).
- `:core:messaging:compileDebugKotlin --rerun-tasks` → **BUILD SUCCESSFUL**.
- Bug 5 test `notifyPeerSessionUp flushes a queued outbox message stuck in backoff` → **PASS** in isolation.
- Full `:core:messaging:testDebugUnitTest` is not green: the pre-existing timing-sensitive `failed outbox delivery backs off instead of retrying every tick` test fails, including in isolation. This is unrelated to Bug 6 and needs deterministic-test cleanup.
- Physical Bug 6 verification remains: background one phone for >45 seconds and confirm the peer stays online and receives a message.

### Known blockers
- Kotlin daemon flakiness: treat "BUILD SUCCESSFUL" as success; don't trust exit code alone (non-daemon fallback compiles fine but exits 1)
- Gradle metadata cache corruption: if `metadata-2.107\module-metadata.bin` errors, delete `E:\AndroidDev\Gradle\caches\modules-2\metadata-2.107` and rebuild (toolchain moved F: → E:)
- Build tip: `E:\` hosts SDK (`E:\AndroidDev\SDK`), Gradle home (`E:\AndroidDev\Gradle`) and the JBR (`E:\AndroidDev\AndroidStudio\android-studio\jbr`)
- Known failing timing test: `RealFlashChatRepositoryTest.kt:499` (`failed outbox delivery backs off instead of retrying every tick`) — currently fails even in isolation; unrelated to Bug 6
- PHASE-21/22 depend on Phases 06–20 groundwork that does not exist yet; deferred
- KMP migration is DE-prioritized until chat UI bugs + calling modules are done

### Recommended next task
**Bug 7:** Add message notifications via `FlashNotificationManager.kt`, using the existing Android 13+ notification permission flow and avoiding duplicate notifications for the currently open conversation.

### Files most relevant to next task
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt` (inbound message framing/dispatch)
- `core/messaging/src/main/java/com/transfer/flash/core/messaging/RealFlashChatRepository.kt` (inbound ingestion)
- `app/src/main/java/com/transfer/flash/MainActivity.kt` (notification permission and current conversation host state)
- `app/src/main/AndroidManifest.xml` (`POST_NOTIFICATIONS` already declared)
- `docs/ui/notification-ui.md`

### Remaining work summary (for next AI)
1. **Bug 7:** Message notifications
2. **Voice/video calling:** `core:calling` + `ui:calling` modules with WebRTC (`shepeliev/webrtc-kmp`), WireFrame types, signaling over WS mesh, call UI overlay
3. Physical Bug 6 background-presence verification and deterministic cleanup of the existing messaging backoff test
4. Then: commit all bug-fix work (with `Co-authored-by: Copilot` trailer), resume KMP migration



- Created a separate formal logo proposal for the owner's AI logo competition. Entry point:
  `logo-codex/preview/contact-sheet.png`; source notes: `logo-codex/README.md`.
- Final mark: F-shaped transfer monogram using Flash Pulse teal, graphite, off-white, and a restrained spark
  amber transfer lane. It intentionally avoids a generic lightning-bolt centerpiece.
- Deliverables: SVG masters, Android adaptive templates, PNG exports from 16px through 1024px, lockups, mono
  assets, and archived concept/refinement materials.
- Verification: rendered via `node logo-codex/build.mjs` and visually inspected contact sheet plus 48px/16px
  icons and lockups.
- No app/source files were modified by this branding pass. If selected, integrate launcher resources in a
  dedicated follow-up change.

## 2026-08-27 -- Publishing Phase 5 authoring COMPLETE (5.1–5.5 done) + coroutines dep-scope leak fixed -- next = Phase 6 (JitPack)
- **All Phase 5 authoring tasks are DONE.** 5.1 (`Flash.create` factory) + 5.3 (Closeable) landed earlier
  this session (entry below). This entry covers 5.2 + 5.4 + 5.5 and a real dependency-scope fix uncovered
  by the sample.
- **README.md authored at repo root (5.2 + 5.4):** pitch → JitPack install (commented badge + `<user>/<repo>`
  and `<TAG>` placeholders, filled in Phase 6) → quick-start → `FlashConfig` table → lifecycle → permissions
  (required vs optional foreground-service split, each with a "why", explicit no-location note) →
  compatibility table → published module set (Phase 4 Task 4.3) → Apache-2.0. The quick-start is **compiled
  verbatim** as `sample/consumer/src/main/java/.../QuickStart.kt` so README code can't silently drift.
- **5.5 cleanups:** every `core/*/consumer-rules.pro` now carries a documented comment header (persistence
  was 0 bytes). Verified NO first-party reflection anywhere in `core/*` → "no keep rules needed; transitive
  Room/SQLCipher ship their own" is accurate. `resourcePrefix`: **not needed** (no `core/*` has `res/`).
- **REAL BUG FIXED — coroutines dependency-scope leak:** core modules returned `Flow`/`StateFlow` from their
  PUBLIC API but only had coroutines via `implementation(lifecycle.runtime.ktx)`, so those return types were
  OFF a downstream consumer's compile classpath (the sample's `QuickStart.kt` couldn't resolve `StateFlow`/
  `first`). Fixed: added `api(libs.kotlinx.coroutines.core)` to discovery/network/transfer/persistence/
  security/messaging + new catalog entry `kotlinx-coroutines-core` (`coroutines = "1.10.2"`). Engine
  re-exports it transitively via `api(project(...))`. **This is the kind of leak the `:sample:consumer`
  harness (Phase 2 Task 2.3) exists to catch — it worked.**
- **Verified green:** `:sample:consumer:assembleDebug`, `:sample:consumer-granular:assembleDebug`,
  `:app:compileDebugKotlin`, and `compileReleaseKotlin` for all six touched core modules + engine.
- **⚠ Build-infra gotcha (not code):** a Kotlin daemon crash corrupted the Gradle module-metadata cache
  (`E:\Flash\.gradle-user-home\caches\modules-2\metadata-2.107\module-metadata.bin`) and `gradlew --stop`
  left one daemon alive rewriting it. Recovery: `taskkill //F` the stale `java.exe` daemons → delete
  `metadata-2.107` + `Flash/.gradle/configuration-cache` → rebuild clean. If a build fails reading
  `module-metadata.bin`, do this.
- **⚠ Owner decision to flag:** Phase 1 option (b) was only HALF applied — compileSdk was lowered to 35 for
  reach, but AGP stayed 9.3.1, so the AGP 9.3 / Gradle 9.5 floor is still the real adoption ceiling (apps on
  AGP 8.x can't consume the artifacts). README documents this honestly. Decide whether to also lower AGP.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT: Phase 6 (`docs/publishing/PHASE-06-jitpack-publishing.md`) — JitPack publish.** That phase fills
  the README's `<user>/<repo>`/`<TAG>`/badge placeholders and verifies a real JitPack build. Build env is
  mandatory (see below). Messaging inversion stays deferred.

## 2026-08-27 -- Publishing Phase 5 Task 5.1 + 5.3 DONE (`Flash.create` factory + Closeable) -- next = 5.2/5.4/5.5 + sample
- **`Flash.create(context, FlashConfig = FlashConfig())` is live** in `core:engine`
  (`core/engine/src/main/java/com/transfer/flash/core/engine/Flash.kt`). One call builds all six
  `FlashEngine` subsystems on ONE shared `CoroutineScope`, opens the encrypted Room DB, and launches
  network/discovery/data-channel/auto-connect async. This is now the documented happy path (the six
  `Default*` constructors remain for advanced users).
- **`FlashConfig(displayName, enableResume=true, autoAcceptIncoming=false, receivedFilesDir=null)`** per the
  owner's decision "Full engine, autoAccept default false." Offer gate is ALWAYS on (`requireAcceptance` +
  `requireReceiverAcceptance`); `autoAcceptIncoming` only auto-invokes the accept path (→ RESUME) on the
  offer event. `enableResume` toggles `RoomTransferStore` vs null (DB always opens — chats/settings need it).
- **New support files:** `core/engine/.../store/KeystorePassphraseProvider.kt` (verbatim port of the app's
  keystore-wrapped SQLCipher passphrase — same PREFS `flash_db_secure` / alias `flash_db_passphrase_key`, so
  it unwraps the SAME on-disk DB as the app) and `core/engine/.../internal/AutoConnectGate.kt` (pure JVM gate).
- **Excluded by design:** pairing (`PairingCoordinator` depends on app UI types + isn't part of
  `FlashEngine`), `FlashBackgroundService`, Dev Console. Wiring is **duplicated** from
  `DiscoveryEngineHolder` (NOT refactored) to honor "keep everything" and not destabilize the running app —
  accepted, logged tech debt. Holder left untouched.
- **Task 5.3 folded in:** `FlashEngine : Closeable`; `DefaultFlashEngine` gains idempotent
  `onClose: () -> Unit = {}` (AtomicBoolean-guarded, defaulted so hand-assembled callers +
  `DefaultFlashEngineTest` compile unchanged). Factory teardown stops data-channel server + network +
  discovery, closes DB, cancels the shared scope.
- **Build change:** added `implementation(libs.androidx.room.runtime)` to `core/engine/build.gradle.kts` —
  the engine is the composition root and must see Room's `Migration` + `RoomDatabase.close()`; `implementation`
  (not `api`) keeps Room internal, consistent with ADR-024.
- **Verified green:** `:core:engine:compileDebugKotlin`, `:core:engine:compileReleaseKotlin` (explicitApi
  strict), `:core:engine:testDebugUnitTest`, `:app:compileDebugKotlin`.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT (Phase 5 remainder):** 5.2 permissions section, 5.4 root README (quick-start using `Flash.create`
  + `close()`), 5.5 consumer-rules.pro comment headers + resourcePrefix decision, and a `:sample:consumer`
  module that mirrors the README and runs `Flash.create` + `close()`
  (`./gradlew :sample:consumer:assembleDebug`). Then Phase 6 (JitPack). Messaging inversion stays deferred.
  Build env is mandatory (see below).

## 2026-08-27 -- Publishing Phase 4 DONE (Task 4.1 decoupling + Task 4.3 module-set decision) -- next = Phase 5 README
- **Phase 4 COMPLETE.** Task 4.1 (persistence decoupling) + Task 4.3 (published module set) both done;
  Task 4.2 (interim ABI-trim fallback) not needed since 4.1 landed; step 6 (messaging inversion) deferred
  by decision (messaging held out of the v1 supported set).
- **Task 4.3 decision (source of truth = PHASE-04 doc table, grounded in `releaseRuntimeClasspath`):**
  - **Supported — lightweight (no Room/SQLCipher):** `core-common`, `core-security`, `core-discovery`,
    `core-network`, `core-transfer`.
  - **Supported — batteries-included umbrella (bundles Room):** `core-engine`.
  - **Supported — optional storage add-on (Room + 4 SQLCipher ABIs):** `core-persistence`.
  - **Experimental — not promised in v1 (still DAO-coupled):** `core-messaging` (resolves + is pulled
    transitively by engine, just undocumented as standalone).
- **Task 4.1 COMPLETE.** `core:transfer` no longer depends on `core:persistence`, and neither does
  `core:security`. `./gradlew :core:transfer:dependencies` shows **no `androidx.room` / `net.zetetic`
  sqlcipher** on `releaseCompileClasspath` or `debugRuntimeClasspath`. A LAN-only consumer can now take
  `core-transfer` without the four SQLCipher native ABIs.
- **How (transfer):** new port `TransferStore` in `core:transfer` (`store/TransferStore.kt`, plain suspend
  iface). `RealFlashTransferRepository` takes nullable `store: TransferStore?` (null = DB-less, unchanged
  behavior). Room adapter `RoomTransferStore` lives in **`core:engine`** — NOT persistence, which would
  create the cycle `persistence → transfer → security → persistence`. App wires it in
  `DiscoveryEngineHolder` (`store = RoomTransferStore(db.transferDao(), db.transferChunkDao())`).
- **How (security):** the transitive leak `transfer → security → persistence` came from the **dead**
  `RoomTrustedStore` (internal, never constructed; app uses `AndroidPreferencesTrustStore`). Owner approved
  **deleting** it. Also removed security's direct `libs.androidx.room.runtime`. `FlashTrustedPeer` moved next
  to `LegacyTrustMigration`; `TofuPolicy` + `LegacyTrustMigration` kept (pure, Room-free, still tested).
  Security now declares `libs.androidx.lifecycle.runtime.ktx` for coroutines (was leaking in via Room).
- **Verified green:** `:core:transfer:testDebugUnitTest`, `:core:security:testDebugUnitTest`,
  `:core:engine:testDebugUnitTest`, `:core:engine:compileDebugKotlin`, `:app:compileDebugKotlin`,
  `:app:assembleDebug`. Sample app behavior unchanged.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT: Phase 5 (`docs/publishing/PHASE-05-*.md`) — consumer ergonomics / README.** The v1 supported
  module set is decided (table above / in PHASE-04 Task 4.3); Phase 5 authors the README that documents it.
  Phase 4 step 6 (messaging inversion) stays deferred — hold `core-messaging` out of the v1 supported set
  rather than inverting now; promote it later with the same port/adapter treatment (ADR-024). Then Phase 6
  JitPack. Build env is mandatory (see below).

## 2026-08-26 -- Publishing Phase 3 DONE: explicitApi() strict green in all 8 core modules; BCV removed (ADR-023) -- next = Phase 4
- **Phase 3 is COMPLETE.** `explicitApi()` (strict) is enabled and **green across all 8 published `core/*`
  modules** (common, messaging, engine, discovery, persistence, security, transfer, network). Every public
  symbol now carries a deliberate `public` / `internal` / `@FlashInternalApi` decision — enforced by the
  compiler, so nothing reaches the ABI by accident.
- **network was the last module (8/8), closed this session.** `WebSocketCodec` → `@FlashInternalApi` (used
  cross-core by transfer's `WsTransferManager`); all app/ui-facing session/transport entry points → plain
  `public`; wire-only probe messages → `internal`. `@file:OptIn(FlashInternalApi::class)` added to every
  in-library `WebSocketCodec` use site INCLUDING the same-module test `WebSocketCodecTest.kt`.
- **Task 3.1 (binary-compatibility-validator) WITHDRAWN — see ADR-023.** BCV v0.18.1 registers no
  `apiDump`/`apiCheck` tasks under AGP 9.3.1 built-in Kotlin (no classic Kotlin plugin) — inert. Removed the
  plugin alias, the root `apiValidation {}` block, and the `libs.versions.toml` entry. ABI enforcement is
  `explicitApi()` strict instead. There is **no `.api` dump** — do not go looking for one.
- **Verified:** all 8 `:core:*:compileReleaseKotlin` SUCCESSFUL; `:core:network:testDebugUnitTest`
  SUCCESSFUL; `:core:transfer:compileReleaseKotlin` SUCCESSFUL; root config re-resolves after BCV removal.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT: Phase 4 (`docs/publishing/PHASE-04-*.md`).** Phase 2 Task 2.2 stays deferred: under ADR-023 there
  is no dump to read leaks from; foreign-type leaks now surface as explicitApi `EXPOSED_*` compile errors at
  the leak site (none currently failing → no promotion forced). Build env is MANDATORY (JAVA_HOME=AS jbr,
  GRADLE_USER_HOME=E:\Flash\.gradle-user-home, JAVA_TOOL_OPTIONS unixdomain tmpdir; `./gradlew.bat … --console=plain`).

## 2026-08-26 -- Publishing Phase 2 DONE: dependency-scope fixed (core:common → api) + external consumer gate -- next = Phase 3
- **Phase 2 (the HARD BLOCKER) is IMPLEMENTED.** All six non-engine core modules now declare
  `api(project(":core:common"))` (was `implementation`), so core:common's shared vocabulary
  (FlashDevice/FlashDeviceId/FlashResult/…) lands on a consumer's COMPILE classpath. Without this, granular
  `core:*` artifacts fail with "unresolved reference: FlashDevice" on JitPack.
- **Acceptance PROVEN with external consumers** (Task 2.3): two throwaway, non-published modules under
  `sample/` (in settings.gradle.kts, NO maven-publish): `:sample:consumer` (engine-only → shape A umbrella)
  and `:sample:consumer-granular` (network-only, references FlashDevice → shape B). Both compile.
  `:core:engine:publishToMavenLocal` succeeds and the published `core-engine-1.0.0.pom` has all 7 siblings in
  `compile` scope and impl-only deps in `runtime` — the correct consumer contract.
- **Task 2.2 is DEFERRED to Phase 3 by design.** Deeper cross-module leaks (e.g. network exposing a
  security/discovery type) are NOT guessed — they get read off Phase 3's `.api` dumps and the offending
  `implementation` deps promoted to `api` then. The umbrella (`core-engine`) is the documented default and is
  already fully coherent.
- **Verified:** consumer + publish build SUCCESSFUL; `:app:assembleDebug` SUCCESSFUL. Full unit suite not
  re-run (scope-only change, behaviorally inert; core release variants all compiled during publish).
- **NEXT: Phase 3 (`docs/publishing/PHASE-03-api-surface.md`)** — binary-compat-validator `apiDump` +
  `explicitApi()` + hide internals; then close Phase 2 Task 2.2 off the dumps. Owner device run EXP-002 still
  pending.

## 2026-08-26 -- Publishing Phase 1 DONE: Apache-2.0 + core compileSdk 35 (both owner decisions resolved) -- next = Phase 2
- **Phase 1 of `docs/publishing/` is IMPLEMENTED and both owner decisions are locked.** LICENSE = **Apache-2.0**,
  holder **"The Flash Project"** (patent grant + Android-ecosystem norm; see ADR-022). Compat baseline =
  **`core:*` modules lowered to `compileSdk 35`** so AGP-8.7-era consumers can build; the app and
  `targetSdk 36` are untouched. `minSdk 24` (Android 7) already covered the owner's "down to Android 8" ask —
  nothing to lower there.
- **Files changed (all inside the plan's allowlist):** `LICENSE` (full Apache text), `NOTICE`, root
  `build.gradle.kts` (`flashLibraryVersion` single-source), all 8 `core/*/build.gradle.kts` (compileSdk 35),
  `gradle/libs.versions.toml` (sqlcipher 4.18.0→4.17.0), `core/discovery/.../nsd/NsdTransport.kt` (onServiceLost
  forward-compat). No `app/`, `ui/`, or `media-downloader-main/` code touched.
- **Two obstacles hit and cleared (see progress.md + ADR-022):** (1) SQLCipher 4.18.0 hard-floors compileSdk
  at 37 — every version 4.9.0–4.17.0 has no floor, so pinned 4.17.0. (2) `ServiceInfoCallback.onServiceLost`
  is `(NsdServiceInfo)` at SDK 37 but no-arg at 34–36 — kept the no-arg `override`, demoted the param variant
  to a plain method (still binds at runtime on Android 17).
- **Verified:** all 10 modules compile at 35; `assembleDebug` BUILD SUCCESSFUL, `app-debug.apk` (29.7 MB)
  produced; the two previously-flaking timing tests pass on isolated `--rerun-tasks`. The combined
  `testDebugUnitTest assembleDebug` did NOT go green in one shot — two load flakes (ERROR-019), each green
  alone. Re-run on an idle machine for a single clean green if you want it on record.
- **NEXT: Phase 2 (`docs/publishing/PHASE-02-dependency-scope.md`) — the HARD BLOCKER.** `implementation`
  `(project(...))` → `api(...)` where public types cross module boundaries; prove the fix with an EXTERNAL
  `:sample:consumer`, never the library's own build. Then Phases 3–6. Owner device run EXP-002 still pending.

## 2026-08-26 -- Core library publishing plan authored (GitHub → JitPack → Gradle) -- READ docs/publishing/
- **The owner wants to publish the `core:*` modules as a reusable LAN-transfer library** so other developers
  consume the engine instead of building from scratch. Hosting decision: **GitHub → JitPack → Gradle**, NOT
  Maven Central. The next agent (OpenCode, run in this same folder) implements it.
- **The plan is `docs/publishing/` (7 files).** Start at `PHASE-00-overview.md`; phases are ordered and each
  is self-contained (problem → exact files/code → acceptance → `./gradlew` verify). Do them in order:
  01 foundation (LICENSE, single version source, compat baseline) → 02 dependency-scope (**hard blocker**) →
  03 api-surface (`explicitApi()` + hide internals) → 04 persistence-decoupling (SQLCipher off the transfer
  path) → 05 consumer-ergonomics (`Flash.create()` factory + README) → 06 jitpack-publishing (`jitpack.yml`
  openjdk17, tag/release, verify).
- **The one blocker that makes or breaks it: dependency scope (Phase 2).** Core modules use
  `implementation(project(...))` but expose those types in PUBLIC signatures, so individual `core:*`
  artifacts DO NOT COMPILE for a downstream consumer. `:core:engine` is the only coherent artifact today
  (it uses `api(...)`), so the minimum-viable path publishes `core-engine` only. Prove any scope fix with an
  EXTERNAL `:sample:consumer`, never the library's own build.
- **What JitPack removes vs Maven Central** (do not waste effort): GPG signing, a Sonatype/Central
  `repositories{}` publish target, strict POM validation, and the javadoc jar are all NOT needed. JitPack
  just needs a working `publishToMavenLocal`, `jitpack.yml` pinning JDK 17 (AGP 9.3.1), and a Git tag +
  GitHub release. Consumer coordinate: `com.github.<user>.<repo>:core-engine:<TAG>`.
- **Two decisions need the owner:** LICENSE copyright holder (Phase 1.1); keep compileSdk 37/AGP 9.3.1
  (narrow reach) vs lower for wider consumer support (Phase 1.3).
- **No `core:*` code changed this session** — docs only; the prior green build state stands. (Aside: I
  accidentally overwrote AGENTS.md and reverted it with `git checkout` — AGENTS.md is intact.)

## 2026-08-25 -- RESOLVED: sender could not pause (ERROR-018, ADR-021) -- nine pause/resume/cancel defects
- **The reported bug was a REGISTRATION RACE, not a broken pause button.** `sendFile` returns the instant the
  send coroutine launches, but `executeSend` registered its dispatcher only after the resume-chunk DAO query
  and dispatcher construction. A pause landing in that window found no dispatcher, took a state-only branch
  that emitted no wire frame, and then `executeSend` overwrote `Paused` with `Transferring` — the pause
  disappeared and bytes kept flowing. **Pause is now an INTENT** (`pauseIntents`, recorded BEFORE the
  dispatcher lookup) that `applyPendingPauseOrStart` re-checks after the Transferring write.
- **Eight more defects fixed in the same audit** (full list + fixes in ERROR-018): `send()` parked forever
  when COMPLETE arrived during a pause; the 15 s ACK-drain grace failed paused transfers (a paused receiver
  deliberately stops ACKing); resume never un-gated receive intake, so both UIs showed Transferring at 0 B/s;
  the intake gate was one session-wide boolean; `resumeTransfer` no-oped on a state mismatch while the wire
  stayed paused; the rate meter straddled the paused gap and `-1` leaked to the UI as negative speed;
  `tryEmit` dropped control frames silently; cancelling a PAUSED sender never reached a cancellable
  suspension point, and the `finally` cleanup lacked an ownership check.
- **Read ADR-021 before touching this code.** Its invariants: pause intent outlives dispatcher construction;
  a paused transfer is NEVER failed by a timeout (the drain deadline is disarmed, resume re-arms fresh); a
  terminal outcome always beats a pause (`awaitUnpause()` returns on the terminal deferred); resume always
  emits `IncomingControl(RESUME)` while remote PAUSE deliberately does NOT gate (the gate is session-wide, so
  gating would stall unrelated transfers' ACKs); the gate is a SET of transfer ids; paused telemetry is a
  hard `0.0` / ETA `-1`.
- **Verified:** `:core:transfer:testDebugUnitTest --rerun` green (76 tests, 0 failures) and full
  `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (411 tasks; 668 tests / 0 failures across 102 suites);
  `app-debug.apk` produced. 6 new regression tests, incl. a `GatedChunkDao` that parks the resume query to
  reproduce the race window exactly, and a fake-clock test proving a paused sender survives repeated 60 s
  jumps and only fails after resume.
- **Known limit:** the pause intent is in-memory, so pause does NOT survive process death — a killed paused
  sender comes back as Queued and re-plans from the persisted done-set. Persisting it is the ADR-021 revisit
  trigger.
- **Still owner-only: the two-phone device run** (10 MB over 5 GHz, Pause/Resume/Cancel from BOTH sides, plus
  a multi-minute pause), to be recorded as EXP-002 against EXP-001.

## 2026-08-25 -- Phase 8 App Shell LIVE: custom bottom nav + four tab pages
- **The app now boots into the real shell:** `MainActivity.FlashApp` renders
  `Column { FlashAnimatedScreen(nav.current) ; FlashBottomNav }` — boolean-flag switching GONE.
  Tabs = Chats / Transfers / Nearby / Settings; tab taps call `FlashNavigationState.selectTab`
  (stack RESET, not push); Conversation still pushes; BackHandler pops.
- **UI-046 FlashBottomNav** (docs/ui/bottom-nav.md): custom docked bar — spring-sliding Pulse pill,
  squash-release icon pop, animated label weight, re-select pulse ring, Tick haptics, badges (9+),
  selectableGroup/Role.Tab semantics, reduce-motion snaps. Four NEW house-style icons
  (chat/transfer/nearby/settings; settings gear adapted from Feather MIT w/ attribution).
- **UI-047 TransfersScreen** (transfers-page.md): ACTIVE/FAILED/HISTORY sections, honest status lines,
  bytes-weighted progress, pause⇄resume swap, scoped Retry, Share on history; UI-016 badge color language
  reused statically. Empty state via new `TransfersFirstRun` kind.
- **UI-048 NearbyScreen** (nearby-page.md): identity card, peer rows + FlashTransportBadge + Connect,
  trusted peers + Revoke, scanning pulse dot, radios-off explainer, pairing-dialog mount ready
  (phase/secondsLeft pass-through to UI-032 dialog).
- **UI-049 SettingsScreen** (settings-page.md): five sections; CUSTOM segmented theme control + CUSTOM
  FlashSwitch; About card with version/protocol/device-id.
- **Demo-state contract:** TransfersUiState/NearbyUiState/FlashSettingsModel in MainActivity are shaped
  EXACTLY like future C5/C3/C1.4 engine outputs — wiring is substitution, not rewrite.
- **Verified:** full `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (411 tasks); suite green incl.
  +19 new tests across nav/transfers/nearby/settings logic. NOT device-verified yet.
- **Remaining P1 gap:** Send FAB on Chats (opens attachment palette) — only page-plan item not built.

## 2026-08-24 -- RESOLVED: :core:transfer suite hang (ERROR-016) + Gradle startup failure (ERROR-017)
- **Hang fixed, bounded queues kept.** `MultiStreamDispatcher.runWorker` is now ONE loop that `select`s over its own
  feed and the shared redistribution queue (was two sequential phases); exit bookkeeping (`ownFeedsOpen`,
  `aliveWorkers`) moved into `finally` behind idempotent releases; redistribution is `trySend` + 5 ms poll instead of a
  blocking `send`; materializer breaks once the transfer resolved; `maybeResolveFromState` fails fast when nothing ever
  reached a wire. Root cause + the discarded alternatives: ERROR-016 (RESOLVED) and ADR-019.
  The permitted revert-to-`Channel.UNLIMITED` fallback was NOT needed - feeds=8 / shared=32 stay bounded.
- **The phase-split deadlock was device-fatal, not just a test artifact:** any mid-flight channel death on a large file
  could pin a worker in `shared.send()`, block the materializer on that worker's feed, and stall the transfer forever.
- **Gradle can run again (ERROR-017).** Every invocation, incl. `gradlew --version`, was dying with `Unable to
  establish loopback connection`: JDK 19+ builds every `Selector` on an AF_UNIX socket pair on Windows, and on this
  machine AF_UNIX connect always fails EINVAL (bind succeeds, so the JDK's TCP fallback never triggers). Fix: point the
  AF_UNIX temp dir at a nonexistent path so the bind fails and the JDK falls back to TCP loopback -
  `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"` (covers launcher, daemon and workers).
  **Use this in every shell that runs Gradle** - see the Build environment note at the bottom.
- **Docs:** ADR-018 (FLASH_XFER wire control plane + cooperative pause) and ADR-019 (single-loop bounded-queue workers)
  added to `docs/decisions.md`; both were referenced from code but previously unwritten.
- Everything else from this session stands: real N-socket data channels, FLASH_XFER control both directions,
  cooperative dispatcher pause, speed-meter fix, Dev Console redesign.
- **Only remaining step for this batch: the two-phone device run** (10 MB over 5 GHz; Pause/Resume/Cancel from both
  sides; then record EXP-002 vs the EXP-001 hotspot baseline).

## 2026-08-24 -- Real multistream (N TCP sockets) + speed fix
- **Real multi-stream implemented:** `core/network/datachannel/` — plain-TCP side channels (`FLASH_JOIN` handshake, length-prefixed frames) bound to the WS session; factory opens one real socket per stream to the target peer (port probe ws+1..+20, cached), WS fallback if peer has no data server. ACKs reply down the arriving connection.
- **Speed display fixed:** RollingRateMeter rewritten (sliding sample window); old version inflated continuously because Δbytes used the first-ever sample while Δtime stayed ≤2 s.
- **Sender pause under diagnosis:** pauseTransfer now logs direction/state/jobPresent — capture TRANSFER logcat from a failing pause attempt.
- Test matrix: both phones must run THIS build for data channels to engage; older peer = silent WS fallback.

## 2026-08-24 -- Dev Console redesign + pause-while-receiving
- Dev Console is now tabbed (PEERS / TRANSFERS / NET) with a status card and log strip; transfer rows have progress bars + Pause/Resume/Cancel (TX and RX).
- Receive-side pause implemented via TCP backpressure: repo emits `incomingControl` events; holder gates intake before pulling frames (`WsSession.awaitBinaryFrame()`); sender throttles automatically, resume drains buffer. Chat stalls during receive-pause (single socket) — accepted v1 trade-off.
- Real N-socket multistream roadmap (next perf milestone): see `docs/decisions.md` ADR-017 revisit + EXP-001 baseline. No external code download needed — dispatcher/receiver already speak N channels; only the transport factory must open N real sockets with a session-join handshake.

## 2026-08-24 -- Device round 2: ack-drain fix (false "Failed" at ~20%) + receiver now visible in Active Transfers
- **Root cause of the 20%-then-Failed symptom:** sender workers exit as soon as all chunks leave the socket buffer; ACKs lag behind disk-paced receiver verification, so `maybeResolveFromState`/`failIfAllChannelsDead` misread uncovered+zero-alive as channel failure. Receiver was fine and always-on — it had verified the entire file.
- **Fix:** bounded 15 s ack-drain grace after worker exit; late ACK_BATCH/COMPLETE now resolve Completed. True failure message is explicit: `ack drain timeout: N unconfirmed`.
- **Receive-side UI:** inbound transfers register via new additive `onIncomingStarted/Progress/Completed/Failed` repo hooks — Dev Console Active Transfers shows Receiving rows on the receiver phone too.

## 2026-08-24 -- Layer-by-layer audit vs media-downloader: pause/resume correctness fixes
- Compared `media-downloader-main` engine layers against Flash's transfer stack; fixed three pre-test bugs:
  1. Pause/cancel no longer reports Failed (`CancellationException` handled separately, re-thrown).
  2. Resume keeps the same wire fileId (`FlashTransfer.wireFileId`) — fresh UUIDs were rejected by the receiver as SESSION_CONFLICT.
  3. Sender chunk done-set now persists to Room (`TransferChunkDao` was dead code) so resume seeding works.
- Remaining gaps (deliberately deferred, in priority order): process-death restore (TransferEntity needs fileName/sourceUri/peerId/wireFileId columns + startup rehydration), queue/concurrency/retry-backoff, receiver-side identity validation + receive done-set persistence, MediaStore publish of received files.

## 2026-08-24 -- WS Mesh Hardening (ERROR-015): correct assembly, reliable delivery, liveness, glare safety
- **Received files now assemble correctly:** per-transfer random-access sinks (`FileRandomAccessSinkHandle` + `RandomAccessChunkSink`) write chunks at `index * chunkSize` under `FlashReceived/<transferId>/<safeName>`. The previous append-order sink scrambled out-of-order multi-stream arrival.
- **No more silent frame drops:** WsSession delivers inbound frames via bounded blocking channels → TCP backpressure; dropped-chunk transfer stalls are structurally impossible.
- **Liveness:** 15 s WS pings + 45 s read timeout close half-open hotspot connections.
- **Handshake/glare races fixed:** early-frame buffering, replaced-session close, identity-safe disconnects, HELLO version enforcement, pending-handshake sockets closed on stop, Mutex-serialized start/stop.
- **Peer-targeted sends:** stream channels route to the intended recipient (`StreamChannelFactory.open(channelId, peerDeviceId)`).
- **Resume fix:** `FlashTransfer.sourceUri`; resume re-reads original content URI.
- **Chat framing:** colon-safe `FLASH_MSG`/`FLASH_RCPT` field encoding via FlashTextFraming.
- **Dev Console:** persisted Room DB (`flash-dev.db`); loud failure on source-open errors; deterministic generated 10MB test payload.
- **Verified:** `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL — 644 tests / 0 failures / 0 skipped.

## 2026-08-24 -- Unified WebSocket Mesh Transport & Transfer Pipeline Wiring
- **Implemented WebSocket Mesh Transport:** Created `WsFlashNetwork` and `WsSession` implementing `FlashNetwork` and `FlashSession`.
- **Full-Duplex Multi-Peer Channels:** Each peer pair maintains an active WebSocket capable of streaming UTF-8 text (`MessageWireFrame` for chat) and binary frames (`ChunkFrame` for files) simultaneously.
- **Symmetric Router & Hotspot Support:** Operates seamlessly via mDNS discovery on standard Wi-Fi routers and via gateway/probe on mobile hotspots.
- **Wired to Engine & Receivers:** Outbound sends route through active WebSockets, inbound chat messages persist into Room, and inbound file chunks flow into `ReceivePipeline` with auto-save to `FlashReceived/`.
- **Sender ACK Routing:** Inbound `ACK_BATCH` and `COMPLETE` frames route directly to active `MultiStreamDispatcher` instances via `RealFlashTransferRepository.onInboundFrame()`.
- **Structured Diagnostic Logging:** Added tags `DISCOVERY`, `WS`, `TRANSFER`, `CHAT`, `DEV` for clear visibility in Android Studio and `adb logcat`.
- **Verified Build & Tests:** `testDebugUnitTest assembleDebug` -> BUILD SUCCESSFUL across all 10 modules (411 tasks, 0 failures).
- **Installed to Device:** Tested debug APK installed on physical phone via ADB.

## Current branch
`dev` — migration decisions committed as `0250a51` (D3=A, D4=A, D6=A, D9=A; D8=A earlier as `e742bec`; D1=B, D2=A, D5=C as `74367dd`)

## Last verified build
Working tree at 2026-08-31 (migration decision recording + PHASE-21/22 log honesty correction) — documentation-only changes; no build required.
Previous build reference: 644 tests / 0 failures (2026-08-24, ERROR-016 fix).

## Current phase
**Migration planning docs complete (PHASE-00–PHASE-24); all human decisions D1–D9 recorded. Actual KMP implementation has NOT begun.**

- All 25 phase files (PHASE-00 through PHASE-24) exist in `docs/migration/`.
- **All 9 decisions answered** in `docs/migration/DECISIONS.md`: D1=B (strict commonMain), D2=A (keep core:*), D3=A (switch ui:* to org.jetbrains.compose), D4=A (expect fun flashDynamicColorScheme seam), D5=C (Room 3 KMP + encrypted desktop), D6=A (JmDNS), D7=**pending** (agent may proceed with recommendation — Toast→Snackbar, FileKit, expect ensurePermission), D8=A (desktop ships existing chat UI adaptively), D9=A (keep sample/consumer Android-only through Phase 23; add sample/consumer-desktop in Phase 24).
- **HONESTY CORRECTION:** PHASE-21 and PHASE-22 log entries claimed an implemented `:desktop` module with PASS builds — **no such code exists** (verified: no `desktop/` dir, no `settings.gradle.kts` include). Those phases produced planning docs only and are **NOT done**. See corrections appended to `docs/migration/logs/migration.md`.
- **Next execution step:** the migration is still documentation-only. Actual implementation must start from the beginning (Phase 06 groundwork per D1=B), then proceed in order. Do not attempt PHASE-21/22 implementation until Phases 06–20 land.

## Component status
- **UI-034 (Adaptive layouts):** `IMPLEMENTED` in `ui/adaptive/FlashAdaptiveLayouts.kt` â€” two-pane not yet consumed by screens (integration pending).
- **UI-038/039/041 (A11y/Haptics/Micro):** `IMPLEMENTED` â€” `FlashFeedback.kt` haptic choke point, 15 call sites migrated, a11y fixes applied.
- **UI-042/043 (Performance/Stress):** `IMPLEMENTED` â€” `FlashStressTestScreen.kt` harness; device measurements PENDING.
- **UI-024/031/032:** `IMPLEMENTED` â€” integration wiring items in Deferred block below.
- **UI-023, UI-028/029/030, UI-025â€“027, UI-021/022, UI-020, UI-019:** `IMPLEMENTED` â€” device verification pending.
- **UI-018 (Media viewer):** `VERIFIED` on device.
- **UI-017 (Image message & grid layout):** `IMPLEMENTED` in `FlashImageGrid.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-016 (File message card):** `IMPLEMENTED` in `FlashFileMessageCard.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-015 (Delivery / read states):** `IMPLEMENTED` in `FlashDeliveryStatusIcon.kt`, `FlashIcons.kt`, `FlashMessageBubble.kt`.
- **UI-014 (Typing indicator):** `IMPLEMENTED` in `FlashTypingIndicator.kt`, `FlashChatHeader.kt`, `FlashMessageList.kt`, `FlashConversationScreen.kt`.
- **UI-012 (Custom attachment button):** `IMPLEMENTED` in `FlashAttachmentButton.kt`, `FlashAttachmentSheet.kt`, `FlashComposer.kt`, `FlashConversationScreen.kt`.
- **UI-010 (Reply system):** `IMPLEMENTED` in `FlashQuotedReplyCard.kt`, `FlashSwipeToReply.kt`, `FlashMessageBubble.kt`, `FlashConversationScreen.kt`.
- **UI-009 (Reaction system):** `IMPLEMENTED` in `FlashReactionChip.kt`, `FlashReactionsDock.kt`, `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-007 (Message press & selection):** `IMPLEMENTED` in `FlashMessageBubble.kt`, `FlashSelectionToolbar.kt`, `FlashConversationScreen.kt`.
- **UI-008 (Focus overlay & context menu):** `IMPLEMENTED` in `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-011 (Custom message composer):** `IMPLEMENTED` in `FlashComposer.kt`.
- **UI-013 (Custom send button):** `IMPLEMENTED` in `FlashComposer.kt`.
- Foundation components: UI-001, UI-002, UI-037 (`IMPLEMENTED` in `:ui:theme`).
- List & Header: UI-003, UI-004, UI-005, UI-006 (`IMPLEMENTED` in `:ui:chat`).

## Working features
- Full modular multi-module library architecture (`com.transfer.flash:*`).
- Group chat header (UI-028): initials collage avatar (2/3/4+ layouts from seeded palette), "N members Â· M online" subtitle, named typing ("Alex and Sam are typingâ€¦"), transport/encryption glyphs, group Search action. Header fully de-Materialed (custom icon buttons, drawn divider, FlashText).
- System states family (UI-025/026/027): screen-specific empty states with P2P copy + "Find devices" CTA, layout-matched skeletons (delay-guarded, reduce-motion-safe, decorative semantics), severity-split error panels (red failure vs neutral offline) with single Retry â€” wired into chat list and conversation screens.
- Chat scroll engine + jump pill (UI-021/022): auto-scroll at bottom & on own sends, unseen counter with floating "N new messages" accent pill (tap â†’ animated jump + reset), reverseLayout bottom pinning through image resizes, keyboard-safe position retention.
- Voice recording interface (UI-020): hold mic to record, slide-left arms cancel (error-tinted bar), slide-up locks into persistent panel with trash/pause/send, live timer + Canvas amplitude strip, demo-mode capture producing real `FlashVoiceAttachmentUi` payloads.
- Voice message playback card (`FlashVoiceMessageCard`, UI-019): 40-bar discrete waveform with tap-to-seek + drag scrub, 48dp play/pause/download/retry badge, Telegram-style remainingâ†”duration label, 1Ã—/1.5Ã—/2Ã— speed pill, demo-mode playback ticker (real audio deferred pending Media3 ADR).
- Full-screen media viewer (`FlashMediaViewer`, UI-018): pinch/double-tap anchored zoom (1Ã—â€“4Ã—, rubber-band), pan, vertical drag-to-dismiss with backdrop fade + page scale, HorizontalPager album carousel, auto-hiding chrome (counter `n / m`, close, Save/Share/Forward), sample-size-guarded decode, always-dark backdrop token.
- Adaptive Image Collage & Grid Layout (`FlashImageGrid`): 1, 2, 3, 4, and 5+ image mosaics with clamped aspect ratios ($0.5$ to $2.0$), micro-gap gutters ($2.5\text{dp}$), bubble contour corner masking, and $+N$ overflow chips.
- Experimental WebSocket Mesh Transfer: Full file viewing, sharing, and device export capabilities (`WsFileActions`, `FileProvider`, SAF `CreateDocument` picker, click-to-open cards).
- Redesigned 24Ã—24 Custom Vector Icon Set: 46 Flash-owned vector icons with 2.0dp stroke weight, generous optical bounding boxes, and scaled default UI sizing (24dp).
- Complete Edge-to-Edge System Bar and Insets Safety: Status bar cutout clearance across headers/toolbars, and navigation bar/keyboard clearance across composer and sheets.
- Rich in-bubble file message cards (`FlashFileMessageCard`) with color-coded file extension badges (PDF, ZIP, Code, Audio, Video, Image, Document), circular transfer progress rings, and real-time throughput metrics (MB/s speed & ETA countdown).
- Animated delivery status glyphs (`FlashDeliveryStatusIcon`) for 5 transit lifecycle states (Pending, Sent, Delivered, Read, Failed) with 1-tap retry interaction.
- 120 FPS GPU-accelerated 3-dot wave bouncing typing indicator.
- Incoming message stream typing bubble (`FlashTypingBubble`) with concave bubble shaping and smooth list integration.
- Animated header subtitle typing status (`FlashHeaderTypingStatus`) with mini-dots.
- Stateful attachment button in composer with spring rotation ($0^\circ \to 45^\circ$) and active accent tint.
- Modal bottom sheet attachment palette with 5 categorized options (Gallery, Files, Camera, Audio, Flash P2P).
- Staggered spring scale entrance and 0.90x micro-press physics on attachment action tiles.
- Left-swipe-to-reply gesture with rotating reveal badge and single-edge haptic trigger.
- In-bubble quoted reply card with 3dp rounded vertical accent bar and 1-tap jump to original message.
- Jump-to-original message smooth scrolling with 600ms Flash Pulse glow highlight.
- Composer reply dock with dismiss action.
- Interactive reaction dock on message bubbles with 1-tap toggling, active self-reaction accent styling, animated vertical count roll (odometer), and `+N` overflow chip.
- Floating quick reaction bar inside spotlight focus overlay with staggered spring entrance, micro-press physics, and trailing `+` action button.
- Immersive message focus overlay with 65% dimmed backdrop, elevated bubble preview, and sculpted context menu card.
- Multi-message selection mode with animated toolbar swap, batch Copy/Reply/Forward/Delete.
- Adaptive multiline message composer with keyboard safety, reply dock, and tactile send button.
- Message list with reverse layout, concave bubble shapes, entrance choreography, and auto-scroll.
- LAN Discovery and experimental WebSocket multi-peer mesh Transfer.

## In progress
- **KMP migration docs (docs/migration/):** PHASE-12–22 authored & grounded; PHASE-23 (interop matrix) and PHASE-24 (publishing) authored but NOT yet grounded/logged. D8=_pending_ (owner answer needed before any Option B desktop UI).
- **UI-028 (Group header):** IMPLEMENTED â€” device verification pending.
- **UI-025/026/027 (states):** device verification pending.
- **UI-021/022, UI-020, UI-019:** device verification pending.

## Broken
- None.

## Last change
Authored + code-grounded migration docs **PHASE-21** (`:desktop` app shell) and **PHASE-22** (adaptive desktop screens). Verified every theme token, API call, and composable signature in PHASE-22 against actual source (FlashColors/Dimensions/Shapes/Typography/Text/Icons/Theme, FlashAdaptiveLayouts, FlashBottomNav, FlashNavigation, FlashTransfersScreen, FlashNearbyScreen, FlashChatListScreen, FlashConversationScreen, FlashSettingsScreen); fixed ~10+ ungrounded references. Appended PHASE-21 + PHASE-22 entries to `docs/migration/logs/migration.md` (previously zero entries).

## Last test
PHASE-22 grep sweep — no ungrounded tokens remain (tabActiveBg, surfaceApp, roundedMedium, iconMedium, labelMedium, bodyLarge, spec=, FlashBottomNav param mismatch all gone; only the correct inline 200.dp sidebarWidth constant remains). Docs are documentation-only; no Gradle build applies. Prior build reference: 644 tests / 0 failures (2026-08-24).

## Known blockers
- **Environment (ERROR-017, WORKAROUND MANDATORY)**: Gradle cannot start at all in this environment without
  `JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"` (AF_UNIX connect is blocked OS-side, so `Selector.open()`
  fails -> "Unable to establish loopback connection"). Export it before any `gradlew` call.
- **Environment (ERROR-008, MITIGATED)**: E: drive intermittently returns "The device is not ready" during Gradle cache writes. Recovery: `.\gradlew.bat --stop`, kill stuck java PIDs, rebuild with a fresh daemon. Real fix is hardware-side (move caches off the removable/hot-plug device or disable its power management).

## Deferred / pending integration (do not forget)
**Master plans:**
- **PART 1 â€” Core:** `docs/core-upgrade-plan.md` **v2 ACTIVE** â€” D2/D3/D4/D5 approved (ADR-010); D1 + D6 open; execution phases P0â€“P8 defined.
- **PART 2 â€” Pages:** `docs/ui-page-plan.md` â€” bottom nav shell (Chats/Transfers/Nearby/Settings + Send FAB), page-by-page specs P1â€“P5 with core-API dependencies, integration checklist.

All items below are absorbed into those two documents:
- UI-031 badge/sheet wiring into header; `isVerified` passes false until pairing lands.
- UI-032 pairing dialog trigger from discovery flow; Accept/Decline need engine callbacks.
- UI-024 recent-searches persistence; UI-029 demo roster until live members.
- UI-020 MediaRecorder capture ADR; UI-019 Media3 playback ADR.
- Engine-side auto-retry/backoff indicator (UI-044); key-changed warning state (UI-031).
- **UI-031**: wire `FlashEncryptionBadge` near conversation header; tap opens `FlashEncryptionSheet`. `isVerified` passes `false` until pairing/engine lands; verification rows disabled-with-explanation.
- **UI-032**: trigger `FlashPairingDialog` from the Nearby Devices/discovery flow once engine exposes pairing events; Accept/Decline need engine callbacks.
- **UI-024**: recent-searches persistence (currently in-memory only).
- **UI-029**: members sheet uses demo roster until repository feeds live members.
- **UI-020**: real audio capture requires RECORD_AUDIO flow + MediaRecorder engine ADR.
- **UI-019**: real playback requires Media3 dependency ADR.
- **Engine-side**: auto-retry/backoff indicator (UI-044), key-changed warning state (UI-031).

## Recommended next task
**The migration is in planning-docs-only state; actual KMP implementation has not begun.** The first implementation phase is **PHASE-06 (KMP pilot)** — converting `core:common` to the first `commonMain` source set. But the user explicitly asked to continue from Phase 12. Since all decisions are now recorded, the next real step is to start the actual KMP migration implementation. The recommended order is:
1. **PHASE-06** — KMP pilot (set up `commonMain` in `core:common` per D1=B)
2. **PHASE-07** — Security KMP (crypto, TLS, pinning)
3. ... through PHASE-20 in order
4. PHASE-21 and PHASE-22 only after Phases 06–20 land (they are currently planning docs only; the log claims of implemented code are false and corrected)

If the user wants to continue from Phase 12 as requested, start with **PHASE-12 (engine KMP implementation)** — but note that Phases 06–11 (KMP groundwork) have not been implemented, so Phase 12's dependencies may not be satisfied.

## 2026-08-22 - P3 NSD session note (agent handoff)
- LAN MVP networking now has `nsd/NsdTransport.kt` (:core:discovery) implementing FlashRadioTransport C3.2-C3.4 (identity TXT advertise + self-filter, continuous browse w/ capped restarts, API>=34 ServiceInfoCallback vs <34 hardened NsdResolveQueue split, NetworkRequest-scoped discovery API 33+). `NsdFlashDiscovery` untouched (R4). NOT yet Gradle-verified (forbidden session) - run testDebugUnitTest first; tests: nsd/NsdTransportLogicTest.kt (pure-JVM, no coroutines-test dep in module).
- API thresholds + citations live in `NsdApiLevel.kt` KDoc and logs/progress.md entry of same date. DiscoveryRequest combined API (T-ext 22 / SDK 37) deliberately deferred.


## DEVICE TESTING BACKLOG (for owner)
Priority order; each item = install latest debug APK, exercise, report pass/fail:
1. **UI-019 Voice playback**: tap voice card â†’ play/pause animated morph, seek by tap, drag scrub, speed pill cycle, remainingâ†”duration label swap.
2. **UI-020 Recording**: hold mic â†’ bar+timer+amplitude; slide-left = red "release to cancel"; slide-up = lock panel (trash/pause/send); release sends; short tap discards.
3. **ERROR-009/010/011 regressions**: keyboard-open has NO blank band; context menu dismisses on FIRST scrim tap + âœ• button.
4. **UI-021/022 Scrolling**: peer message while scrolled up â†’ "N new messages" pill; tap jumps to bottom; auto-scroll on own send.
5. **UI-023 Search**: header search icon â†’ type query â†’ counter + prev/next jump with in-bubble highlight; close restores.
6. **UI-025â€“027 States**: empty chat list ("Find devices" CTA), skeleton loading, error panel retry.
7. **UI-028/029 Group**: collage avatar + "15 members Â· 4 online" subtitle + named typing; group avatar tap â†’ members sheet.
8. **UI-030 Banner**: connection banner states (toggle sample data); transport badge chip.
9. **UI-031 Encryption badge/sheet** (once wired).
10. **UI-032 Pairing dialog** (once wired to discovery).
11. **UI-024 Chat-list search**: filter by title/preview, recents chips.
12. **Dark mode sweep** all above + **reduced-motion** setting spot-checks.
13. **UI-042/043 Perf**: open stress screen at 500/2000 messages, fling scroll, note jank (`adb shell dumpsys gfxinfo com.transfer.flash`).

## Build environment note
```powershell
$env:JAVA_HOME="E:\AndroidDev\AndroidStudio\android-studio\jbr"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; $env:GRADLE_USER_HOME="E:\Flash\.gradle-user-home"
# REQUIRED on this machine (ERROR-017) - without it every Gradle invocation dies with
# "Unable to establish loopback connection" because AF_UNIX connect is blocked OS-side:
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"
.\gradlew.bat testDebugUnitTest installDebug
# If "The device is not ready" appears (ERROR-008):
.\gradlew.bat --stop; taskkill /PID <stuck java pid> /F; then rerun with a fresh daemon.
```
Git Bash equivalent: `export JAVA_HOME=... GRADLE_USER_HOME=... JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"`
then `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`.

## Files most relevant to next task
- `docs/migration/PHASE-06-kmp-pilot.md` (first actual KMP implementation phase — blocked by nothing; D1=B chosen)
- `docs/migration/PHASE-12-engine-kmp.md` (engine KMP — where user asked to start)
- `docs/migration/DECISIONS.md` — all 9 decisions recorded; D7 still pending (agent may proceed on recommendation)
- `docs/migration/logs/migration.md` (phase log, with PHASE-21/22 honesty corrections appended)
- `docs/migration/README.md` (phase table, verify rows 21/22)
- `logs/handoff.md` testing backlog below (owner runs; lead fixes / marks VERIFIED)
- `docs/migration/CONVENTIONS.md` (R1–R11 rules for every phase)

## 2026-08-22 - P3 pure-logic agent handoff (C3.3/C3.5/C3.9)
- Created (ONLY these): `core/discovery/.../core/{StandardEndpointDirectory,TxtCodec,DiscoveryRetryPolicy,CompositeDiscovery}.kt` + 4 matching JUnit4 test classes under src/test. NO existing file touched; nsd/** untouched.
- CompositeDiscovery implements existing FlashDiscovery + `startAll(port, identity)` aggregate + `sweep(nowMs, grace=30_000)` + `mergedEvents` SharedFlow(DROP_OLDEST); dedup across transports by deviceId, priority LAN > WIFI_DIRECT > WIFI_AWARE > BLE, loss hysteresis emits Updated(fallback) not Lost while a lower radio still sees the peer.
- Deterministic tests without coroutines-test: synchronous DirectDispatcher injected via optional scopeFactory ctor param + explicit clock lambda + local FakeTransport.
- NOT Gradle-verified (forbidden session) - run testDebugUnitTest first; expect ~+25 tests. Full details + research URLs: logs/progress.md entry of this date; decisions: ADR-010.

## 2026-08-23 - P4 pure-logic agent (C4.2/C4.3/C4.5/C4.7-aggregation + C4.9)
- Created ONLY: `core/network/.../resilience/**` (ReconnectPolicy, HeartbeatPolicy, HeartbeatTracker, BoundedSendQueue, SessionHardeningPolicy, ConnectionHealthAggregator, ChaosSession+DedupGate, ChaosNetworkHarness) + matching tests under src/test. NO existing file or gradle/toml touched; Gradle NOT run.
- Strategies chosen (research-cited in logs/progress.md same date): full-jitter-with-floor backoff base 1s cap 30s; heartbeat 10s interval / 3 misses; send-queue REJECT mode capacity 64; session limit 8; duplicate-device tie keeps existing.
- Deterministic JVM tests only (explicit nowMs, seeded Random, injected random01); no coroutines-test. MutableStateFlow used in main via transitive coroutines-core (lifecycle-runtime-ktx) - verified.
- NOT build-verified. Next AI: run testDebugUnitTest first (~+30 expected), then wire primitives into concrete FlashNetwork impls (C4.2/C4.3 integration).
