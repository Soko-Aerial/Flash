# Screen share in calls: design (ADR-102)

Status: **IMPLEMENTED 2026-10-09 for the desktop presenter (Windows, Linux) and for receivers on every platform; unit-tested; NOT
device-verified (`SHARE-01`...`SHARE-14`). The Android presenter is NOT built.** Investigation and reasoning that led here:
`docs/calling/SCREEN-SHARE-INVESTIGATION.md`. Run report with every decision and the owner checklist:
`docs/reports/2026-10-09-screen-share.md`. UI spec and copy: `docs/ui/calling-ui.md` section "UI-050g".

Labels: **read** (in this repo), **verified** (official docs or library source), **estimate** (reasoning, not measured), **unverified**
(needs a device).

## 1. Goal and scope

A person in a 1:1 or group video call can show a screen or a window to the others. Everyone else sees it letterboxed and knows who is
presenting. Out of scope: audio of the shared content, annotation, remote control, presenting from Android, presenting from a voice call.

## 2. Architecture in one picture

```
 presenter (desktop)                                          watcher (any platform)
 ------------------                                          ----------------------
 DesktopScreenCapture (vendored, webrtc-java)                  FlashCallVideoSurface(fit = Fit)
   VideoDesktopSource -> track + FrameProbe                      ^ remote video track (unchanged path)
          |  RtpSender.replaceTrack(screen)  (one per leg)       |
          v                                                      |
   one PeerConnection per participant (full mesh) ---------------+
   one software VP8 encode PER LEG                      Status ss=1 sst=N   (signaling, FLASH_CALL)
          ^
   ScreenShareRun (ladder rung, watchdog)  <--  GroupVideoRouter (who watches, at what height, cap)
   ShareStateMachine / ShareArbiter / ShareStrain  (pure)
```

- **Media**: the screen replaces the camera track on every video `RtpSender` with `replaceTrack` (**verified** in `webrtc-java`: no
  renegotiation, no new m-line). A connection built while presenting (a late joiner, a rebuilt leg) is given the screen track
  from the start (`ScreenCaptureHandle.addTo`). The camera device is released for the whole share and re-opened afterwards if it was on.
- **Cost model** (**read**, ADR-052, ERROR-078): there is one encoder per connection; sharing to N watchers is N encodes of the screen.
  The saving versus a camera comes from content (mostly static, so 5-10 fps) and not from sharing an encoder.
- **Where the code is**: `core/calling` `ScreenShare.kt` (pure logic), `ScreenCapture.kt` (provider seam), `ScreenCapture.jvm.kt` /
  `ScreenCapture.android.kt` (actuals), `FlashCallSession.kt` and `FlashGroupCallSession.kt` (effects), `GroupVideoRouter.kt` (watchers),
  vendored `third_party/webrtc-kmp/.../DesktopScreenCapture.kt`; UI in `ui/callui` `FlashCallShare.kt` and the call screen files.

## 3. Wire (additive; an older build ignores it)

`Status` (`action=status`, ADR-067) gains two optional fields. A missing field means "unchanged", like every other status field.

| Field | Meaning |
|---|---|
| `ss=1` / `ss=0` | this device presents / stopped presenting. Absent: not stated (an older build, or nothing changed). |
| `sst=<n>` | the start value of the share (`max(now ms, highest seen + 1)`, so it behaves as a counter that is also a rough clock), only with `ss=1`. Orders two claims (section 5). |

- While presenting, `cam=1` is also stated, so a **build from before ADR-102 shows the share as the sender's camera** (full-frame, balanced
  fit instead of letterboxed, and in a group it asks at camera quality, 540p). It does not break; text is just less sharp. This is the
  documented mixed-build behaviour (`SHARE-12`).
- `ss=0` is sent only after a share was ever announced in the call (`shareAnnounced`), so a call that never shared produces exactly the
  frames it produced before. Tested.
- Group calls reuse the group `Status` path; the request-based video protocol (`vreq` / `vgrant` / `vdeny` / `vrel`) is unchanged. A watcher
  asks the presenter for `ASK_HEIGHT` (1080) in the existing `quality` field (720 when the watcher is itself struggling).
- Decode is lenient: `ss=maybe` or `sst=-5` reads as "not stated". `docs/protocol.md` needs the two fields added (owned by another stream;
  paste-ready text is in the report).

## 4. States

Presenter, local (`ShareStateMachine`, pure):

```
IDLE --begin--> STARTING --ready--> SHARING --end--> STOPPING --finished--> IDLE
                   |  \--fail--> STOPPING                 ^
                   \--end (cancelled while opening)-------+
```

- `sharing` (the camera track must not be sent) is true in STARTING and SHARING. `busy` is true until the capturer is closed, so a new share
  never overlaps a closing one. A second Stop is a no-op.
- The UI flag is cleared **before** the native stop (the indicator must not outlive the share by the time a native stop takes).

Start sequence (media thread, lifecycle lock held): open the capture -> `replaceTrack(screen)` on every video sender (a leg that refuses
keeps its camera picture and is logged) -> release the camera -> tune each sender with the share rung -> announce `ss=1` -> start the
watchdog. A failure at any step closes the capture, restores the camera and shows "Sharing could not start".

Stop sequence (ERROR-123 order): UI flag off -> `replaceTrack(camera or null)` on every sender (the screen leaves the senders first) ->
`detachSinks()` on the capture track -> stop the source -> dispose -> camera re-opened if it was on -> `ss=0`.

## 5. One presenter at a time (`ShareArbiter`)

- Every presenter states `ss=1` with `sst`. The presenter is the claim with the largest (`sst`, device id). Every device sees the same
  claims, so every device picks the same winner; a device whose own claim loses stops itself with the notice "<name> started sharing, so
  yours stopped".
- `sst` is **not a plain clock comparison**: `nextStart(now)` is at least one more than the largest start ever seen, so a deliberate take-over
  always sorts after the share it replaces even if the two clocks disagree by minutes. Tested with a ten-minute skew. A value above year
  2100 is clamped (no overflow, no one can claim forever).
- Starting while another device presents asks for confirmation in the picker ("<name> is presenting. If you share, their share stops.");
  the host passes `takeOver = true`. Without it the core refuses with `SOMEONE_PRESENTING`.
- A presenter that leaves (hang-up, pruned leg) is dropped from the claims (found by a test: it was not, see the report).
- An older build never states `ss`, so it is never a claim and never yields.

## 6. The share ladder (`ShareLadder`)

First guesses, **not measurements** (`SHARE-04` replaces them). ADR-098's camera caps (540p/360p) are not reused: 360p text is unreadable.

| Rung | Height | fps | Max kbps | Min kbps |
|---|---|---|---|---|
| 0 | 1080 | 10 | 2500 | 400 |
| 1 | 720 | 8 | 1500 | 300 |
| 2 | 720 | 5 | 900 | 200 |
| 3 | 540 | 5 | 600 | 150 |

- Start rung 0. From 3 watchers one rung lower (each watcher is another encode). "Share at lower quality" (the presenter's choice) skips the
  two best rungs. A struggling computer steps one more rung (`ShareStrain`: 3 strained samples in a row to enter, 12 clean to leave, so it
  does not flap). Clamped to rung 3.
- Capture at most 2560x1440 at 10 fps (a 4K screen is not converted for nothing). The sender is told the scale factor from the captured height
  once the first frame says how big it is (watchdog re-tune; also on a resize of the shared window).
- Tuning sets `maintainResolution` (shed frame rate before resolution; the opposite of a face). **Android** honours it
  (`DegradationPreference.MAINTAIN_RESOLUTION`); **the JVM backend has no degradation preference or content hint** (**verified** in the
  webrtc-java parameters), so on desktop the fps cap and the bitrate window do the work. Logged once per process, like the audio gap.
- Per leg: the rung is capped to the height the watcher asked for (bitrate follows the area, never below the rung's floor), then scaled by
  the voice-priority concession, which can still lower or pause a leg: **voice comes first while presenting**.
- Watcher cap (`watcherCap(tier)`): HIGH 4, MEDIUM 3, LOW 2; on the 2.4 GHz split budget 2. A watcher beyond it is turned down with
  `SENDER_AT_CAPACITY` ("busy"). A speaking presenter does not evict a watcher (the camera rule is disabled while sharing).
- Struggling detection: any outbound video `qualityLimitationReason == "cpu"` in the stats sample, or the call health verdict.

## 7. Router changes (`GroupVideoRouter`, ADR-098 content untouched)

- Presenter side: `setSharing(on)`. While sharing, a camera that is off no longer turns watchers down (the video is the screen), the level is
  the ladder's height for the current watcher count (legacy peers counted), capacity is the share cap, and on start the watchers beyond the
  cap are trimmed (focused ones stay) and devices turned down for the camera hear there is room. On stop with the camera off the watchers are
  turned down again; with the camera on they stay (trimmed to camera capacity).
- Watcher side: `setPresenter(id)`. The presenter is pinned automatically, asked first and at `ASK_HEIGHT`; the previous pin is saved and
  restored when the presentation ends **unless the person pinned someone else meanwhile**; unpinning during a presentation returns to the
  presenter, not to the speaker; a presenter that turned this device down is asked again when it starts presenting; the one-video (LOW)
  layout shows the presenter as its one tile. The presenter is the router's main tile but is **not** reported as a user pin
  (`videoFocusPeerId` excludes it, so tapping the presenter's tile still pins and unpins as usual).

## 8. Platform notes

- **Windows (desktop)**: `VideoDesktopSource` with `setSourceId(id, isWindow)`, `setFrameRate`, `setMaxFrameSize` (**verified** in the
  webrtc-java 0.19.0 API as vendored). Sources are listed with `ScreenCapturer` / `WindowCapturer.getDesktopSources()`. **Unverified**: how a
  closed shared window behaves (frames stop, or a black frame repeats, or the capturer errors). A static screen also delivers no frames, so the
  watchdog only checks the **first** frame (5 s); a later silence is not treated as a dead share. `SHARE-10` captures what happens.
- **Linux**: X11 uses the same capturer. **Wayland**: the capturer is expected to open the system (xdg-desktop-portal / PipeWire) picker
  when it starts; the app then lists one synthetic entry "Choose in the system dialog" and a note in the picker. **Unverified**; the
  documentation URL that was meant to confirm it returned 404 (**not verified**). `SHARE-08`.
- **Android (receiver only)**: nothing to do except render: the presenter's track arrives like a camera track. Renders `Fit`.
- **Android presenter (NOT built)**: would need `MediaProjectionManager.createScreenCaptureIntent()` consent (an Activity result), a foreground
  service with `foregroundServiceType="mediaProjection"` and the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission started before the projection
  is used (Android 14+), a `MediaProjection.Callback` registered before capture (Android 14+), the wrapper's `ScreenCapturerController`
  behind `ScreenCaptureProvider`, and handling of the user revoking from the status-bar chip. Each of these must be re-checked against the
  current Android documentation (AGENTS section 13) on the day it is built; none of it was checked on 2026-10-09 because it was not built.

## 9. Native-resource rules (from ERROR-078 / ERROR-123)

- The capture's frame probe releases every frame in `finally`.
- Close order: `replaceTrack` away from senders -> detach sinks -> `stream.release()` (stops and disposes the source) -> nothing touches the
  track afterwards. `DesktopVideoStreamTrack.onStop` now detaches sinks itself before `videoSource.stop()/dispose()` (fork change, recorded in
  `third_party/webrtc-kmp/MODIFICATIONS.md`).
- Every native call runs on `callMediaDispatcher` under `mediaLifecycleMutex`, never on the UI thread. A call that ends mid-share closes the
  capture first in `teardownMedia`/`releaseMediaLocked`, before the connections.

## 10. Failure behaviour

| Event | Result |
|---|---|
| Capturer cannot open | "Sharing could not start", camera restored, nothing announced |
| No frame within 5 s | share stops, "Nothing could be captured from that screen, so sharing stopped" |
| Another device starts later | this one stops, "<name> started sharing, so yours stopped" |
| Call ends | capture closed before the connections, no notice |
| A leg refuses `replaceTrack` | that leg keeps its camera picture, logged; the others show the screen |
| Computer cannot keep up | one rung down, hint "Your computer is busy, so the picture is smaller" |
| Shared window closed | **unverified** (`SHARE-10`) |

## 11. Not done

Android presenter; group voice calls cannot present (a share needs the video kind); shared-content audio; a thumbnail preview in the picker
(the capturer lists titles only); closed-window detection beyond the first frame; measured ladder numbers (`SHARE-04`).
