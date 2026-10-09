# Run report 2026-10-09: screen share in calls (ADR-102)

Stream: SCREEN SHARE IN CALLS (one of three parallel agents; the owner is away until tomorrow). Nothing was committed or pushed. The shared
log files (`logs/*.md`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`, `docs/protocol.md`, `AGENTS.md`) were NOT edited: their text is
at the end of this report, ready to paste.

**Bottom line.** A desktop device (Windows, Linux) can share a screen or a window in 1:1 and group video calls. Every receiver (Android and
desktop) shows it fit-contain with "X is presenting" and the presenter as the main tile. Built, unit-tested and mutation-checked; **no part of
the real capture, `replaceTrack`, encoder behaviour, CPU cost or the Linux/Windows teardown has been run on a device.** **The Android
presenter (MediaProjection) is NOT built.**

## 1. What was built, and how

### Core (`core/calling`, package `com.transfer.flash.core.calling`)

| File | Content |
|---|---|
| `ScreenShare.kt` (new) | public: `ShareSourceKind`, `ShareSource`, `ShareStopReason`, `FlashShareNotice`, `ShareQuality`. internal: `ShareProfile`, `ShareLadder`, `ShareStateMachine`, `ShareArbiter`, `ShareStrain`, `ScreenShareRun`. All pure, no native types. |
| `ScreenCapture.kt` (new) | `ScreenCaptureHandle`, `ScreenCaptureProvider`, `expect defaultScreenCaptureProvider()`, `NoScreenCapture` |
| `ScreenCapture.jvm.kt` (new) | `DesktopScreenCaptureProvider` over the vendored `DesktopScreenCapture`; on Wayland with an empty list one synthetic source "Choose in the system dialog" |
| `ScreenCapture.android.kt` (new) | returns `NoScreenCapture` (Android does not present) |
| `FlashCallSession.kt` (edit) | 1:1: `startScreenShare`, `stopScreenShare`, `listShareSources`, `setShareQuality`, `dismissShareNotice`, `closeShareLocked`, first-frame watchdog, `tuneShareSender`, `refreshShareAvailability`; a share starts only when a video sender exists |
| `FlashGroupCallSession.kt` (edit) | the same for groups, plus `shareArbiter`, `shareRun`, legs built mid-share carry the screen, router wiring (`shareHeight`, `shareCap`), `teardownMedia` closes the share first, stats sampler feeds `ShareStrain` |
| `GroupVideoRouter.kt` (edit, surgical) | `setSharing`, `setPresenter`, `capacity()`, `trimToCapacity`, share-quality ask in `reconcile`, `GroupVideoLimits.struggling`. ADR-098's 540p/360p content is untouched. |
| `CallWireFrame.kt`, `CallFrameCodec.kt`, `CallStatusBook.kt` (edit) | `Status.sharing` / `shareStartedAt`; wire keys `ss`, `sst` |
| `RtpSenderTuning*.kt` (edit) | `VideoSendTuning.maintainResolution` (Android sets `MAINTAIN_RESOLUTION`; JVM has no such API and logs the gap once) |
| `FlashCalling.kt`, `CallCoordinator.kt`, `model/FlashCallModels.kt` (edit) | share API delegation; `FlashCallUiState` gains `canShareScreen`, `sharing`, `shareStarting`, `shareSourceTitle`, `shareQuality`, `shareWatchers`, `shareLowered`, `presenterId`, `shareNotice`; `FlashCallParticipantUi.sharing` |

### Vendored WebRTC fork (`third_party/webrtc-kmp`)

`DesktopScreenCapture.kt` (new): source listing (screens and windows), `open` (2560x1440 at most, 10 fps), `FrameProbe` (releases every frame in
`finally`, ERROR-078), handle `close()` = detach sinks, then release the stream. `DesktopVideoStreamTrack.onStop()` now detaches sinks first
(ERROR-135 below). Recorded in `third_party/webrtc-kmp/MODIFICATIONS.md`.

### UI (`ui/callui`, `ui/theme`, `desktop`)

- `FlashCallShare.kt` (new): `FlashCallShareHost` (the seam the host fills; absent on Android), the picker panel, `FlashShareIndicator`,
  `FlashPresenterLabel`, `CallShareText` (all copy), `videoFitFor`, `groupVideoShareRects`, `shareStartAvailable`, `sharePresenterName`.
- `FlashCallScreen.kt`: param `share`, `CallPanel.SHARE`, notice pill, the "You are sharing - Stop" strip drawn last above everything, 1:1 remote
  surface letterboxed while the peer presents.
- `FlashCallControlDock.kt`: while sharing the camera button shows off, is disabled and says "Camera is off while sharing"; flip disabled.
- `FlashCallExtras.kt` (More panel): "Share screen" / "Stop sharing" rows (decision D1).
- `FlashGroupVideoGrid.kt`: presenter first, stage layout (`groupVideoShareRects`), fit-contain, "X is presenting".
- `ui/theme`: `FlashIcons.ScreenShare` + `flash_ic_screen_share.xml`.
- `desktop/.../DesktopShell.kt`: fills `FlashCallShareHost` from `FlashCalling`. `MainActivity` unchanged (Android watch-only).

### Wire (additive, old builds ignore it)

`FLASH_CALL` `Status` gains `ss` (0/1) and `sst` (start value, only with `ss=1`). A missing field means "unchanged". The presenter also sends
`cam=1`, so a pre-ADR-102 client shows the picture as the sender's camera. `ss=0` is sent only after a share was announced. Full spec:
`docs/calling/SCREEN-SHARE-DESIGN.md` section 3.

### Docs written

`docs/calling/SCREEN-SHARE-DESIGN.md` (new), `docs/ui/calling-ui.md` section UI-050g (appended), `docs/calling/SCREEN-SHARE-INVESTIGATION.md`
(status line set to SUPERSEDED; original text kept), this report.

## 2. Verification (honest)

Commands (Gradle via the AF_UNIX/JBR 21 recipe in memory `gradle-build-env`):

| Command | Result |
|---|---|
| `:core:calling:jvmTest` | PASS (includes ScreenShareTest 33, ScreenShareWireTest 9, GroupVideoRouterShareTest 21) |
| `:core:calling:testAndroidHostTest` | PASS (362 tests when last counted, includes FlashScreenShareSessionTest) |
| `:ui:callui:jvmTest` | PASS (81 tests when last counted, includes FlashCallShareTest and the indicator render test) |
| `:desktop:compileKotlinJvm` | PASS (the log shows an incremental-compilation fallback line `e: Incremental compilation failed: null`, then a non-incremental build that succeeded) |
| `:app:compileDebugKotlin` | PASS |
| `:desktop:jvmTest` | NOT RUN (known unrelated failure ERROR-106, `DesktopEngineGroupSessionUpTest`) |

All five were run after the mutation runs, so they also prove every mutated file was restored. No device, no real capture.

### Mutation checks

Each mutant: edit one line in the real source, run only the test class that guards it, expect a failure, restore the bytes. The first full
run on this day reported "SURVIVED" for all 11: that was a harness fault (`cmd` could not find `gradlew.bat`, so no test ever ran), not a weak
suite. After fixing the path one mutant was run by hand (3 tests failed, source restored) and then all 11 were rerun:

| Mutant (file) | Guarding test | Result |
|---|---|---|
| arbiter picks the earliest start (ScreenShare.kt) | ScreenShareTest | KILLED |
| crowd threshold 3 -> 4 (ScreenShare.kt) | ScreenShareTest | KILLED |
| ladder drops maintainResolution (ScreenShare.kt) | ScreenShareTest | KILLED |
| router: camera off turns share watchers down (GroupVideoRouter.kt) | GroupVideoRouterShareTest | KILLED |
| router: pin not restored | GroupVideoRouterShareTest | KILLED |
| router: presenter not asked at share quality | GroupVideoRouterShareTest | KILLED |
| codec: sst written for ss=0 (CallFrameCodec.kt) | ScreenShareWireTest | KILLED |
| status book keeps start after ss=0 (CallStatusBook.kt) | ScreenShareWireTest | KILLED |
| group: presenter hang-up keeps claim (FlashGroupCallSession.kt) | FlashScreenShareSessionTest | KILLED |
| ui: indicator never drawn (FlashCallScreen.kt) | FlashShareIndicatorRenderTest | KILLED |
| ui: presentation cropped, not letterboxed (FlashCallShare.kt) | FlashCallShareTest | KILLED |

11 of 11 killed. Killed means the guarded test class failed with the mutation applied (no compile errors).

## 3. Decisions (all taken without the owner; each can be reversed)

| ID | Decision | Why | Alternative not taken |
|---|---|---|---|
| D1 | The picker lives in the **More panel** ("Share screen" row), not a 7th dock button | The dock already holds six controls; a seventh does not fit a phone width and Android cannot present anyway | A dock button (clutter), a header button (hidden in call chrome) |
| D2 | **Replace, don't add**: `RtpSender.replaceTrack(screen)` on every video sender | No renegotiation, no second m-line, the receiver needs no change to show it | A second video track per leg (renegotiation on every start, old builds break) |
| D3 | **Latest start wins**, ordered by `sst` = max(now, highest start seen + 1), not by a bare timestamp | `nextStart` is above every start ever seen, so a deliberate take-over wins even if the clocks differ by minutes (tested with ten minutes) | Wall-clock time (skew lets the old share win); first-wins (cannot take over) |
| D4 | The presenter sends `cam=1` together with `ss=1` | Older builds then still render the picture instead of an avatar | `cam=0` (older receivers would show nothing) |
| D5 | Ladder 1080/10 fps, 720/8, 720/5, 540/5 with bitrate windows | Text needs resolution, not frame rate; **the numbers are first guesses** (`SHARE-04`/EXP-024 replace them) | Reusing ADR-098's 540p/360p (360p text is unreadable) |
| D6 | **Watcher cap by tier**: HIGH 4, MEDIUM 3, LOW 2; the 2.4 GHz split budget gives 2 | One software VP8 encode per watcher (ADR-052); the cap is the cost control, not a guess about the network | No cap (a 6 person call would be 5 encodes of a 1080p screen) |
| D7 | "Share at lower quality" skips the two best rungs; a struggling machine steps one more (3 strained samples to enter, 12 clean to leave) | Gives the person a manual fallback and the machine an automatic one without flapping | Automatic only |
| D8 | **First-frame watchdog only** (5 s) | A static screen legitimately delivers no frames; declaring silence dead would kill a calm slide | A periodic silence check (false positives) |
| D9 | **Android is watch-only** this run | The task made it secondary; it needs consent UI, a new FGS type and Android 14+ behaviour that must be checked against current docs | Rushing MediaProjection unverified |
| D10 | **ERROR-123 close order**: senders away from the screen, detach sinks, stop source, dispose; UI flag cleared before the native stop | Native stop crashed Linux calls before (ERROR-123); the indicator must not outlive the share | Dispose first |
| D11 | The camera is **released** during a share and re-opened after, if it was on | Two capture devices cost CPU and battery for nothing; the camera button is disabled meanwhile | Keep both open |
| D12 | The presenter is auto-pinned on receivers; the earlier pin is restored only if the presenter is still the pin | Never overrides what the person chose during the share | Always restore (would unpin a new choice) |
| D13 | `maintainResolution=true`; Android honours it, JVM cannot | Text must keep its resolution; on desktop the fps cap and the bitrate window must do it (**no JVM API, verified in webrtc-java 0.19.0**) | Hope the encoder decides |
| D14 | Group voice calls cannot present | A share needs the video kind of call; adding video to a group voice call is not built | n/a |

## 4. Bugs found while building (for `logs/errors.md`)

- **ERROR-134** (found by `FlashScreenShareSessionTest`, FIXED in code): in a group call a presenter who hung up stayed `presenterId`, so
  everyone saw a share that no longer existed. Fix: `shareArbiter.onPeerLeft` plus `refreshUiState()` at the hang-up path and at the pruned-leg
  path in `FlashGroupCallSession`. Mutation-checked.
- **ERROR-135** (latent, FIXED in the fork, same class as ERROR-123): `DesktopVideoStreamTrack.onStop()` stopped and disposed the video source
  without detaching the sinks first, so a sink could receive a frame from a disposed source. It now calls `detachSinks()` first. Device check
  `SHARE-06`.
- **ERROR-136** (OPEN, limitation, not a defect): the JVM backend has no content hint and no degradation preference, so `maintainResolution`
  is a no-op on desktop; whether the encoder sheds resolution before frame rate on a text-heavy screen is unmeasured (`SHARE-04`, `SHARE-05`).
- **ERROR-137** (OPEN, unverified risk): what happens when the shared window is closed is unknown (frames may stop, a last frame may repeat,
  or the capturer may error). The watchdog covers only the first frame. `SHARE-10` records the behaviour; a fix follows the finding.

## 5. UNVERIFIED (device or environment dependent)

Everything below was reasoned or unit-tested at the logic level only:

- The real capture on Windows and Linux (X11) and the Wayland portal picker (the documentation page meant to confirm it returned 404).
- `replaceTrack` on a live sender and the picture the receivers get (and that an older build shows it as a camera).
- CPU of share versus camera at 1, 2 and 4 watchers, and legibility of 10 pt text at each ladder rung.
- Camera -> share -> camera: the camera re-opens, the track returns, no black frame left.
- Stop share on Windows and on Linux without a native crash (ERROR-123 class).
- Closing the shared window; changing the size of the shared window mid-share.
- A call ending, or the app closing, while sharing.
- The look of the indicator and the picker (only "drawn on top" is tested by a render test).
- 2.4 GHz split budget, thermals and battery during a long share.

## 6. Owner test checklist for tomorrow (in order; full cases are `SHARE-01`...`SHARE-14` below)

1. Windows laptop shares a screen to one phone (`SHARE-01`); read a 10 pt line on the phone (`SHARE-03`).
2. Indicator visible and Stop works from the strip and from the More panel (`SHARE-02`).
3. Camera on -> share -> stop: the camera comes back (`SHARE-07`).
4. Stop on Windows, then repeat on Linux: the app does not crash (`SHARE-06`).
5. Share a window, close the window (`SHARE-10`); resize it (`SHARE-11`).
6. Group of 3 and 4: two phones watch, then add a third, a fourth watcher (`SHARE-04`, `SHARE-05`, `SHARE-13`); take over from a second desktop (`SHARE-09`).
7. Linux Wayland picker (`SHARE-08`).
8. An older build in the call (`SHARE-12`).
9. Capture logcat / app log from before pressing Share on every device; run EXP-024 (CPU) in the same session.

## 7. NOT done

- **The Android presenter (MediaProjection) is not built.** `defaultScreenCaptureProvider()` on Android returns `NoScreenCapture` and the host
  passes no `FlashCallShareHost`, so an Android phone shows no Share row. What is missing: the consent flow
  (`MediaProjectionManager.createScreenCaptureIntent()` from an Activity), a foreground service of type `mediaProjection` with its manifest
  permission started before the projection, a registered `MediaProjection.Callback` (required from Android 14), the capturer behind
  `ScreenCaptureProvider`, the revoked-by-system-chip path, and a check of every one of these against the current official documentation on
  the day it is built (AGENTS section 13); this was not checked today because nothing was built. The receiver half already works on Android.
- Shared-content audio, annotation, remote control, group voice-to-video, a thumbnail preview in the picker, closed-window detection, measured
  ladder numbers, `docs/protocol.md` updated (another stream owns the file; text below).
- `:desktop:jvmTest` has the known unrelated failure ERROR-106 (`DesktopEngineGroupSessionUpTest`).

## 8. Paste-ready text

### 8.1 `logs/progress.md`

```markdown
## 2026-10-09 - Screen share in calls (ADR-102)

### Worked on
Desktop screen / window sharing in 1:1 and group video calls; receivers on every platform; the Android presenter was left out.

### Changed
- core:calling: ScreenShare.kt (state machine, one-presenter arbiter, ladder, strain), ScreenCapture.kt + jvm/android actuals, share engines in
  FlashCallSession and FlashGroupCallSession, GroupVideoRouter presenter/sharing support (ADR-098 kept), wire fields ss/sst, maintainResolution tuning.
- third_party/webrtc-kmp: DesktopScreenCapture.kt; DesktopVideoStreamTrack.onStop detaches sinks first.
- ui:callui: FlashCallShare.kt, indicator strip, picker panel, presenter label, stage layout, fit-contain, dock camera state; desktop host fills FlashCallShareHost.
- Docs: docs/calling/SCREEN-SHARE-DESIGN.md, docs/ui/calling-ui.md UI-050g, investigation status SUPERSEDED, docs/reports/2026-10-09-screen-share.md.

### Verification
Unit tests (core:calling jvm + android host, ui:callui jvm), 11 mutation checks, compile of desktop and app: see the report, section 2.
NOT device-verified: capture, replaceTrack, CPU, legibility, Linux/Windows stop, Wayland picker, window close. SHARE-01..14 owed.

### Problems
ERROR-134 (presenter hang-up kept the claim, fixed), ERROR-135 (onStop did not detach sinks, fixed); ERROR-136/137 open (limitation, unverified).

### Remaining
Android presenter (MediaProjection); measured ladder; closed-window handling; protocol.md fields ss/sst.

### Next AI
Do not change the wire fields. Run SHARE-01 first, then EXP-024. Read docs/calling/SCREEN-SHARE-DESIGN.md before touching the ladder or the router.
```

### 8.2 `logs/handoff.md` (add under "In progress")

```markdown
- **Screen share in calls, built 2026-10-09, unit-tested and mutation-checked, NOT device-verified (ADR-102, ERROR-136/137 OPEN):** a desktop
  device presents a screen or window in 1:1 and group video calls; Android and desktop receivers render it fit-contain with "X is presenting".
  Wire: Status `ss` / `sst` (additive). The Android presenter is NOT built (watch-only). Design: `docs/calling/SCREEN-SHARE-DESIGN.md`; report:
  `docs/reports/2026-10-09-screen-share.md`. Device checks `SHARE-01`...`SHARE-14` and EXP-024 in `docs/testing/TEST-BACKLOG.md`.
  Recommended next: run SHARE-01 and SHARE-06 on Windows and Linux; the ladder numbers are first guesses.
  Files: core/calling ScreenShare.kt, FlashCallSession.kt, FlashGroupCallSession.kt, GroupVideoRouter.kt; ui/callui FlashCallShare.kt.
```

### 8.3 `docs/decisions.md`

```markdown
## ADR-102 - Screen share in calls: replace the camera track, one presenter, a share-specific ladder

### Decision
A desktop participant shares a screen or a window by replacing the video track on every video RtpSender (no renegotiation). Presenting is stated
as Status `ss=1` + `sst=<start value: max(now, highest seen + 1)>`; the presenter also states `cam=1` so older builds show the picture. One presenter at a time: the highest
(sst, id) wins and the loser stops itself; a deliberate take-over asks first. The share has its own ladder (1080/10, 720/8, 720/5, 540/5) and a
watcher cap by performance tier (HIGH 4, MEDIUM 3, LOW 2; 2.4 GHz split 2), because the mesh costs one software encode per watcher (ADR-052).
Receivers render fit-contain and make the presenter the router's main tile. Android watches only.

### Context
The owner lifted the deferral of ADR-056's list for screen share; the investigation (docs/calling/SCREEN-SHARE-INVESTIGATION.md) recommended
replace-not-add and a bounded watcher count.

### Alternatives considered
A second video track per leg (renegotiation, old builds); wall-clock take-over ordering (clock skew); first-presenter-wins (no take-over);
reuse of ADR-098's 540p/360p (text unreadable); a periodic silence watchdog (a static screen sends no frames); a seventh dock button.

### Why
Smallest wire change that old builds survive; cost control where the cost is (encodes, not bandwidth); the same code path for 1:1 and group.

### Revisit when
EXP-024 / SHARE-04 measure CPU and legibility (replace the ladder); the first Android presenter is built; a pending MediaProjection / Wayland result
differs from the assumptions in SCREEN-SHARE-DESIGN.md; the closed-window behaviour (SHARE-10) is known.
```

### 8.4 `docs/protocol.md` (to add to the call Status frame description; owned by another stream)

```markdown
Status frame, screen share (ADR-102): optional `ss` (0 = stopped presenting, 1 = presenting) and `sst` (integer start value = max(now ms, highest seen + 1), only with
`ss=1`; the largest (sst, device id) is the presenter). A missing field means unchanged. A presenter also states `cam=1`. Decoders ignore
unknown keys, so older builds interoperate (they show the share as the sender's camera). Invalid values read as "not stated".
```

### 8.5 `logs/errors.md`

```markdown
## ERROR-134 - A group call presenter who hung up stayed the presenter
Date 2026-10-09. Area calling / screen share. Symptoms: after the presenter left, the call still showed "X is presenting". Root cause: the share
arbiter kept the claim of a peer that left through the hang-up path and through the pruned-leg path. Fix: ShareArbiter.onPeerLeft + refreshUiState at
both places. Verification: FlashScreenShareSessionTest `a presenter who hangs up is no longer the presenter`; mutation-checked. Status: RESOLVED (code; device check SHARE-09).

## ERROR-135 - DesktopVideoStreamTrack.onStop disposed the source before detaching sinks
Date 2026-10-09. Area: third_party/webrtc-kmp desktop video. Symptoms: none seen yet; same class as ERROR-123 (native crash when a sink outlives its source).
Fix: onStop calls detachSinks() before videoSource.stop()/dispose(); the screen capture handle closes in the same order. Verification: compile + unit tests only. Status: OPEN until SHARE-06 on Windows and Linux.

## ERROR-136 - No content hint / degradation preference on the JVM backend (limitation)
Date 2026-10-09. maintainResolution is applied on Android only. On desktop the fps cap and the bitrate window are the only protection of text resolution. Status: OPEN, measure in SHARE-04/05.

## ERROR-137 - Behaviour when the shared window is closed is unknown
Date 2026-10-09. The watchdog checks only the first frame (a static screen sends none). Status: OPEN until SHARE-10 records what the capturer does.
```

### 8.6 `logs/experiments.md`

```markdown
## EXP-024 - CPU of a screen share versus a camera, 1 / 2 / 4 watchers (OWED, nothing measured)
Plan: Windows laptop (the EXP-021 machine), presenter shares a screen with a 10 pt text document, then the camera at 540p, with 1, 2, 4 watchers.
Record per run: presenter CPU %, per-watcher encoder bitrate / fps / qualityLimitationReason, whether 10 pt text is legible on a phone, fan / thermals,
ladder rung reached. Replaces the first-guess ladder in SCREEN-SHARE-DESIGN.md section 6.
```

### 8.7 `docs/testing/TEST-BACKLOG.md` (new section, series SHARE-)

Common setup unless stated: Windows laptop (desktop build from this tree) presenter; one Android phone (this build) as a watcher; capture the app log from
before pressing Share on every device (grep `SHARE` / `CALL`). Source for all: ADR-102, `docs/calling/SCREEN-SHARE-DESIGN.md`, this report.

```markdown
### SHARE-01 - Windows screen to a phone (1:1)
Setup: video call laptop <-> phone. Steps: More -> Share screen -> pick the screen -> Share. Pass: the phone shows the laptop screen letterboxed with
"<name> is presenting" within 3 s; the laptop shows the "You are sharing - Stop" strip; log shows ss=1 sent. Status: TODO

### SHARE-02 - Indicator and stop
Steps: while sharing open the More panel, the picker, a chat; press Stop on the strip, then share again and stop from More. Pass: the strip is visible
in every state and never after Stop; the phone returns to the camera or the avatar within 3 s. Status: TODO

### SHARE-03 - Legibility of 10 pt text
Steps: share a document with 10 pt text; look at it on the phone (and on a desktop watcher). Pass: readable without zoom at rung 0 and still readable
at the lower-quality choice; record which rung the log reports. Status: TODO

### SHARE-04 - CPU camera vs share at 1, 2 and 4 watchers (EXP-024)
Setup: group call, laptop plus up to 4 watchers. Steps: camera 540p for 3 min per watcher count, then a share of a text document for 3 min per count. Pass:
a table of presenter CPU %, bitrate, fps, qualityLimitationReason per run; share CPU <= camera CPU at every count, or the gap is recorded and the
ladder revised. Status: TODO

### SHARE-05 - Watcher cap and strain
Steps: on a MEDIUM-tier presenter add a 4th watcher; run a CPU stressor. Pass: the 4th watcher is told "busy"; the share steps one rung down with the
hint "Your computer is busy, so the picture is smaller", and steps back after the load ends. Status: TODO

### SHARE-06 - Stop share does not crash (Windows and Linux)
Steps: share, then Stop, 10 times each on Windows and on Linux (X11); also hang up while sharing. Pass: no native crash, no hs_err file, no hang; the
log shows the close order (senders, sinks, source). Status: TODO

### SHARE-07 - Camera -> share -> camera
Steps: camera on, start a share, stop it. Pass: the camera comes back on by itself and the peer sees it; if the camera was off before the share it
stays off. Status: TODO

### SHARE-08 - Wayland picker
Setup: Linux laptop on Wayland. Steps: open the picker, choose the one entry "Choose in the system dialog". Pass: the system dialog opens, the choice
is shared; cancelling the dialog leaves the call unchanged with the notice "Sharing could not start". Record the compositor and portal versions. Status: TODO

### SHARE-09 - One presenter and take-over
Setup: three devices, two desktops. Steps: desktop A shares; desktop B shares and confirms. Pass: A stops with "B started sharing, so yours stopped";
everyone shows B; B stops and A's share is not resurrected. Also: B hangs up while presenting, the others return to normal. Status: TODO

### SHARE-10 - Closing the shared window
Steps: share a window, close it. Pass: record what happens (frames stop / black / error). Required outcome: the call survives and the presenter can stop
the share; file the exact behaviour in ERROR-137. Status: TODO

### SHARE-11 - Resize / move the shared window
Pass: the picture follows the window without a crash; the receivers re-letterbox. Status: TODO

### SHARE-12 - Mixed builds
Setup: one device with a build from before this change in the call. Pass: the old build shows the share as the presenter's camera, nothing else
changes, no crash on either side; an old presenter never blocks a new one. Status: TODO

### SHARE-13 - Late joiner and rebuilt leg
Steps: a third person joins while A is presenting; kill the Wi-Fi of a watcher for 15 s and restore. Pass: the late joiner sees the share without the
presenter doing anything; the rebuilt leg shows the screen, not the camera. Status: TODO

### SHARE-14 - Call ends / app closes while sharing
Steps: the other side hangs up; separately close the app window while sharing. Pass: no crash, the capture stops (screen-capture indicator of the OS
disappears), the camera is free for the next call. Status: TODO
```

## 9. Files touched (absolute)

New: `C:\Users\KaliOxygen\Downloads\Flash\core\calling\src\commonMain\kotlin\com\transfer\flash\core\calling\ScreenShare.kt`, `ScreenCapture.kt`,
`...\jvmMain\...\ScreenCapture.jvm.kt`, `...\androidMain\...\ScreenCapture.android.kt`,
`C:\Users\KaliOxygen\Downloads\Flash\third_party\webrtc-kmp\webrtc-kmp\src\jvmMain\kotlin\com\shepeliev\webrtckmp\DesktopScreenCapture.kt`,
`C:\Users\KaliOxygen\Downloads\Flash\ui\callui\src\commonMain\kotlin\com\transfer\flash\ui\calling\FlashCallShare.kt`,
tests `ScreenShareTest`, `ScreenShareWireTest`, `GroupVideoRouterShareTest` (commonTest), `FlashScreenShareSessionTest` (androidHostTest),
`FlashCallShareTest` (callui commonTest), `FlashShareIndicatorRenderTest` (callui jvmTest), docs `docs\calling\SCREEN-SHARE-DESIGN.md`, this report.
Edited: the files listed in section 1. Not edited: shared logs, `AGENTS.md`, `docs/protocol.md`.
