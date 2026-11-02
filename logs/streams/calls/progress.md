# Stream C (calls) — progress fragment

The integrator folds these into `logs/progress.md`. Stream C owns `core/calling/**`, `core/ptt/**`, `ui/callui/**`,
`third_party/webrtc-kmp/**`, `app/src/**/calling/**`, `app/src/**/ptt/**`. Worktree `E:\FlashWork\opt-calls`, branch
`agent/opt-calls`, based on `53caa6b6` (`dev` before the stream-S merge).

## 2026-11-02 — Wave 5, stream C: calls and PTT (15 findings)

### Worked on

The calls/PTT/WebRTC slice of the 2026-10-23 optimization audit: O11 (calls, WebRTC, media governors), O12
(push-to-talk and real-time audio), O03-18 (call-dock layout) and O28-03 (call telemetry). Every finding was
re-opened against today's code before it was touched.

### Changed (one commit per finding)

| Finding | Commit | What changed |
|---|---|---|
| `OPT-O11-02` | `3cd95e4a` | **Fork.** jvm `LocalVideoStreamTrack.onSetEnabled(false)` now stops the `VideoDeviceSource` (and `true` starts it), the way androidMain already did. Before it, muting the camera only set `native.isEnabled = false`: the RTP sender went quiet but the webcam kept capturing at 30 fps and the OS kept the device open (LED on) until the call ended. `switchCamera` no longer restarts the source while the track is disabled. Recorded in `third_party/webrtc-kmp/MODIFICATIONS.md`; **the fork must be republished (ADR-103) before the desktop app can use it** — the version was deliberately not bumped. |
| `OPT-O11-06` | `d21da779` | The 1:1 tile is given `null` as its track while `FlashRemoteVideoPlaceholder` covers it (data saver on, or the other camera off). `mainVideoTrack()` is the new pure rule; the surface stays composed, only the sink unbinds. Desktop used to convert and rasterise every decoded frame under the placeholder, and Android uploaded each one to its `SurfaceView`. |
| `OPT-O12-01` | `36f58b4e` | The playout amplitude callback (50 Hz, once per 20 ms packet) no longer publishes `PttSessionStats` every time: `refreshPttSessionStatsIfDue()` rate-limits it to 100 ms (10 Hz), which is what a level bar can show. Other callers (a member leaving, a state change) still publish immediately. |
| `OPT-O12-02` | `fbbc52aa` | `PttAudioFrame` gained a cached-session `encode(sessionBytes, seq, captureTs, pcm)` and `decodeExpected(data, expectedSession, expectedSessionBytes, expectedPcmBytes)`. The engine encodes the session id once per burst and decodes inbound audio by comparing the session **bytes**, so no UTF-8 conversion and no `String` per packet. Measured: see `EXP-135`. |
| `OPT-O12-04` | `71f4b39b` | `PttJitterBuffer` is a fixed ring (`seq % capacity`) instead of a `TreeMap`: O(1), no boxed `Long`, no tree node per packet. New `PttJitterDepth` learns the **start gate** across bursts (120 ms default, 60–160 ms, raised by concealment > 2 %, lowered by a fully clean burst ≥ 200 packets). Once playout has started the depth does not matter, so a wrong guess costs one early concealment in one burst and corrects itself in the next. |
| `OPT-O11-05` | `9be33df5` | The SDP video bitrate hints are gone. `enforceVp8Only` deletes every `a=fmtp:` line in the video section (it has to: libwebrtc compares a VP8 supported format's empty parameters against the offered ones, and any fmtp line turns the decoder lookup into `NullVideoDecoder`), so `tuneVideo`'s output was formatted and thrown away one call deeper. The video section is now passed through untouched and `RtpSenderTuning`/`setParameters` is documented as the only thing that bounds video bitrate. Wire output unchanged — pinned by a test that asserts the sanitized body has no video fmtp for every tier. |
| `OPT-O11-04` | `61bfad6a` | `FlashGroupCallSession.sampleMeshStats` makes **one** pass over each leg's `getStats()` report into session-scoped `ArrayList`s, instead of five filtered lists per leg per second (`candidate-pair`, `inbound-rtp`, `outbound-rtp`, a `track` scan, a `media-source` scan) plus three `firstOrNull` iterator allocations. Candidate-pair selection now walks indices through an inlined `firstWhere`. |
| `OPT-O12-03` | `b073654f` | Desktop audio lines are warmed at engine construction (`JvmPttAudio.warmUpInBackground()`, called by the production `platformPttAudio()`), not inside the first PTT press. EXP-018 measured ~1.0 s for the first `TargetDataLine` open and ~0.23 s after it; that second used to clip the first words and hold the floor's `commandMutex`. |
| `OPT-O12-05` | commit "ask the Android audio server for the low-latency path" | `AudioTrack.Builder.setPerformanceMode(PERFORMANCE_MODE_LOW_LATENCY)` on API 26+ (an advisory request), and the effective `getPerformanceMode()` is logged so a device check reads what it got. The track buffer was **deliberately not** shrunk: halving it trades a possible underrun for latency and that needs the field-handset measurement (`PERF-C01`). |
| `OPT-O03-18` | `961de298` | `FlashCallControlDock` no longer uses `BoxWithConstraints` (a `SubcomposeLayout`) to learn its own width. The old arithmetic is a per-slot constant (`dockButtonInset(count)`), so each button sizes itself from its weighted slot. 360 dp/6 controls is byte-identical to before (48 dp); below ~320 dp the circle now stays inside its slot instead of overflowing the row. |
| `OPT-O28-03` | `6f882c94` | New `CallQualityMath` (pure, tested): average jitter-buffer delay per played sample, concealment share, and audio/video playout skew. `CallDiagnostics` feeds them from counter deltas and prints `jb=<ms> plc=<%> avDrift=<ms>` in the `CALL_DIAG` `ain` section; each is omitted when the backend does not report its ingredients. |

### Not changed, with the reason

- **`OPT-O11-07` — NO LONGER REPRODUCES.** The audit measured "4 independent 1080p10 software encoders". The code
  already steps the share ladder down for a crowd: `ShareLadder.CROWD_WATCHERS = 3` makes `profile(watchers)` start one
  rung lower, and `retuneShareLegs()` passes `watchers = sendingTo.size`. Four watchers therefore get **720p @ 8 fps**
  (rung 1, 1 500 kbps) — 4 × 7.37 = 29.5 Mpix/s, below the audit's own 36.8 Mpix/s target. Nothing was changed; capping
  HIGH at three watchers would change what a presentation reaches and needs a measurement, not an argument.
- **`OPT-O11-01`, `OPT-O11-03`, `OPT-O12-06` — BLOCKED (device/measurement gated).** See `progress.md` §"Blocked".

### Verification

- `:core:calling:jvmTest` — 239 tests, 0 failures (every suite, `CallSdpTest` 29, `CallQualityMathTest` 6).
- `:core:ptt:jvmTest` — 154 tests, 0 failures across 17 suites (new: `PttStatsRateTest` 3, `PttJitterRingTest` 6,
  `PttJitterDepthTest` 7, `PttAudioFrameAllocationTest` 4, `JvmPttWarmUpTest` 3).
- `:ui:callui:jvmTest` — 110 tests, 0 failures (new `DesktopVideoSinkBindingTest` 3; `FlashCallControlDockRenderTest`
  4/4 renders after the layout change; `FlashCallControlDockTest` 9).
- `:core:ptt:compileAndroidMain` and `:app:compileDebugKotlin` / `:desktop:compileKotlinJvm` as part of the final gate.
- Exact commands and their last lines are in the stream report; every number in `EXP-135` came from a forced run.

### Problems found while fixing

1. `switchCamera` on a **disabled** desktop track restarted the camera source (the same privacy bug as `OPT-O11-02`,
   on the switch path) — fixed in the same commit and recorded as `ERROR-981`.
2. `PttPlayoutCore`'s start depth became a **module-global** (`PttJitterDepth`) when `OPT-O12-04` landed. Test classes
   that assert the 120 ms/6-packet gate must reset it (`@BeforeTest`), otherwise a session test that lowered the gate
   would make a later test fail. Done in `PttPlayoutCoreTest`, `JvmPttPlayoutTest` and the new `PttJitterDepthTest`.

### Remaining / next

- The app-side `:webrtc-kmp` version must be republished and bumped for `OPT-O11-02` to reach the desktop app (ADR-103).
- Everything that needs two devices or a camera is owed as `PERF-C01` (see `backlog-additions.md`).
- `OPT-O11-04` and `OPT-O11-05` have no heap measurement: `PeerConnection` and `RtcStatsReport` are `expect` classes, so
  a mesh stats tick cannot be driven from a test. The change is compile-verified and read, not measured.

## Blocked findings (recorded, no code)

| Finding | Why it cannot be proven in this stream |
|---|---|
| `OPT-O11-01` 1:1 desktop capture/encode at 1080p30 | Needs a live camera encode on the desktop to show the CPU drop (EXP-021 measured 240–365 % at 720p30). A loopback encode in `jvmTest` is possible in principle but cannot be trusted under this PC's load, and the rule is "never lower a capture profile without a measurement". `PERF-C01` covers it. |
| `OPT-O11-03` unconditional VP8 SDP | Changes which codec an Android↔Android leg negotiates. Needs two phones and a codec read from `webrtc_voice_engine` logs; it also touches the one thing `enforceVp8Only` exists to prevent (a desktop `NullVideoDecoder` → black video), so an ADR is needed for the per-leg rule. |
| `OPT-O12-06` PCM16 airtime | A wire-format change (`Start` frame codec negotiation + a compatibility window) and it needs Wireshark airtime on 2.4 GHz to justify `ADR-032`'s refusal being revisited. Not a stream-C decision. |
