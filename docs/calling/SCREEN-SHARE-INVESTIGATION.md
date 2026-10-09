# Screen sharing in calls — investigation (2026-10-08)

Status: **SUPERSEDED 2026-10-09: the desktop presenter, the wire, the ladder and the receivers were built (ADR-102, `docs/calling/SCREEN-SHARE-DESIGN.md`, report `docs/reports/2026-10-09-screen-share.md`); not device-verified; the Android presenter is not built.** Original status when written (2026-10-08): **INVESTIGATION, nothing built.** Owner question: "share the laptop screen into the video call; it will be one decoding
to all so it won't impact performance much, right?" Screen share was deferred earlier on purpose (ADR-067 "Not built";
`logs/handoff.md` 2026-09 entries: "do not start unasked"). Labels: **read** (in this repo), **verified** (official docs),
**estimate** (reasoning, not measured).

## 1. The premise is half right

- **Receivers: yes, cheap.** Each receiver decodes one extra stream, and a shared screen is mostly static, so it is usually
  cheaper than a 30 fps camera. **Estimate**; no screen-share measurement exists.
- **The sender: no, not "one for all".** Flash calls are a full mesh (one `PeerConnection` per participant). libwebrtc runs
  **one encoder per connection** and does not share an encoder between connections (ADR-052 findings, ERROR-078: "one
  encoder per mesh connection"). Presenting to 4 people is 4 encodes of the screen, exactly like the camera today. Only a
  relay that encodes once (an SFU, or one encoded stream forwarded by a server) gives "one encode for all"; that is not the
  architecture, and the enterprise server plan is a relay for messages, not a media server.
- **Where the real saving comes from:** screen content is mostly unchanged, so VP8 spends little on repeated frames and the
  frame rate can be 5–10 fps. 1080p at 5–10 fps with the "detail" content hint is cheaper to encode than 720p at 30 fps
  **(estimate)**. Text needs resolution, not frame rate: do **not** reuse ADR-098's 540p/360p caps for a share (360p text is
  unreadable); give the share its own ladder (1080p/720p, low fps) and a watcher limit.

## 2. What exists today (read)

- `third_party/webrtc-kmp` JVM `MediaDevices.getDisplayMedia()` is a **stub**: it creates a `VideoDesktopSource()` but never
  chooses a screen or window, sets no frame rate and starts nothing. webrtc-java's native layer does have desktop capture
  (`DesktopCapturer`, Wayland/PipeWire from 0.16.0 per `PHASE-25-calling-stack-desktop.md`); the wrapper never exposed it.
- Android: the wrapper has `ScreenCapturerController` (MediaProjection) and `MediaProjectionIntentHolder`. Flash does not call it.
- Flash's call sessions (`FlashCallSession`, `FlashGroupCallSession`) carry one camera video track per leg and no share concept
  in the wire frames, the router (`GroupVideoRouter`) or the UI.

## 3. Design sketch (for the owner to judge, not decided)

1. **Replace, don't add.** While presenting, swap the outgoing camera track for the screen track on each leg
   (`RtpSender.replaceTrack`, no renegotiation) so the encoder count stays equal to today's. Adding a second track would
   double the encoders. The presenter's camera tile goes off while sharing (or becomes a small corner picture only for
   watchers who ask, later).
2. **A "presenting" state on the wire** (new optional frame or `Status` field, old builds ignore it) so receivers pin the
   shared picture large, and so the video router can use the share ladder instead of the camera heights.
3. **Presenter controls:** choose screen or window (desktop), stop sharing, a "you are sharing" indicator that cannot be
   missed. Receivers: full-width tile, zoom/pan for text.
4. **Limits:** one presenter at a time per group call; a watcher cap (4 encodes of 1080p on a 15 W laptop is the same
   problem as today's CPU); a "Share at lower quality" fallback when `limit=cpu`.

## 4. Platform facts to settle before code (check on the day, per AGENTS.md section 13)

- **Windows:** webrtc-java desktop capture through the Windows graphics-capture/DXGI path. Needs an on-device test; no
  permission prompt.
- **Linux:** Wayland needs the xdg-desktop-portal (PipeWire) picker; Ubuntu 22.04 defaults to Wayland on some installs, Xorg
  on others. The owner's ThinkPad is the test machine.
- **Android sharing:** MediaProjection needs a user consent every time and a foreground service of type `mediaProjection`
  (started while the app is visible); Android 14+ asks per session, Android 15 can share one app window. Phones sharing is a
  smaller use case than a laptop presenting.
- **Sharing computer audio:** not included; separate problem.

## 5. Tests that would be owed (not added to the backlog until the owner approves the work)

CPU while presenting to 1, 2 and 4 watchers (compare with the camera at the same count), legibility of 10 pt text at the
receiver, camera-to-share-to-camera switch with no renegotiation, stop-share cleanup (no native crash: see ERROR-123), Linux
Wayland picker, mixed old/new builds.

## 6. Recommendation

Worth building **for the desktop presenter first** (Windows, then Linux), as "replace the camera track" with a share-specific
ladder. Do not promise "no performance impact": the presenter pays one encode per watcher. Measure with the camera-vs-share
comparison above before deciding the watcher cap. The owner decides whether to lift the earlier deferral.
