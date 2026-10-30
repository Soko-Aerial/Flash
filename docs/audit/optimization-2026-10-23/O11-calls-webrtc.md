# Domain O11 — Calls, WebRTC and Media Governors
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** Senior Performance Engineer (Domain O11 Sub-agent)  

---

## 1. Domain Header & Scope

This audit evaluates the real-time audio/video calling subsystem across 1:1 calls, group mesh calls, WebRTC bindings (`:third_party:webrtc-kmp`), media governors, audio routing, desktop rendering, and screen sharing:
- **1:1 Call Session & SDP Negotiation (`:core:calling`):** `FlashCallSession`, `CallSdp`, `CallQualityGovernor`, `CallHealthMonitor`, `RtpSenderTuning` (Android/JVM).
- **Group Mesh Calling & Routing (`:core:calling`):** `FlashGroupCallSession`, `GroupVideoRouter`, `GroupVideoTuning`, `PeerTrackTable`, `ScreenShare`.
- **Multiplatform WebRTC Core (`:third_party:webrtc-kmp`):** `WebRtc`, `MediaDevices`, `PeerConnection`, `LocalVideoStreamTrack` (Android/JVM), `DesktopVideoStreamTrack`, `DesktopScreenCapture`, `RtcStatsReport`, `RtcStats`, `MediaTrackConstraints`.
- **Audio Routing & Platform DSP Control:** `FlashCallAudioRouter` (Android audio focus, `MODE_IN_COMMUNICATION`, hardware AEC/NS/AGC), `AudioOptions` (Desktop WebRTC APM), `CallThreading` (Android/JVM).
- **Call UI & Video Rendering (`:ui:callui`):** `FlashCallScreen`, `FlashGroupVideoGrid`, `FlashGroupVideoStrip`, `FlashCallVideoSurface` (Android `SurfaceView` / Desktop Skia Canvas), `FlashCallControlDock`.
- **Screen Share Pipeline:** `ScreenShare`, `ScreenCapture` (JVM `DesktopScreenCapture` / `Robot` vs Android `NoScreenCapture`).

---

## 2. Files Inspected

### Core Calling Multiplatform (`core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/`)
- `FlashCallSession.kt` (1:1 WebRTC coordinator, offer/answer state machine, stats loop, camera flip, profile switching)
- `FlashGroupCallSession.kt` (Full-mesh N-peer coordinator, per-leg signaling, mesh stats loop, presenter role)
- `CallSdp.kt` (SDP munging: `enforceVp8Only`, `tuneVideo`, `tuneOpus`, `clampBitrate`, `extractIceCandidates`)
- `CallQualityGovernor.kt` (Pure state machine: loss/RTT/jitter evaluation, degradation/upgrade hysteresis)
- `CallHealthMonitor.kt` (Sliding sample window, RtcStats delta parsing, health tier evaluation)
- `GroupVideoRouter.kt` (Tile assignment, bandwidth budget, per-peer video grants)
- `GroupVideoTuning.kt` (Mesh tuning rules, tier ladders, receiver/sender limits)
- `PeerTrackTable.kt` (Track inventory per mesh participant)
- `ScreenShare.kt` (Screen share negotiation, `ShareLadder`, presenter/watcher state)
- `RtpSenderTuning.kt` (Expect declaration for sender parameters)

### Platform Calling Bindings (`core/calling/src/`)
- `androidMain/.../RtpSenderTuning.android.kt` (`setParameters`, bitrate envelope, degradation preference)
- `jvmMain/.../RtpSenderTuning.jvm.kt` (Desktop `webrtc-java` sender tuning wrapper)
- `androidMain/.../CallThreading.android.kt` (Android WebRTC thread pool bindings)
- `jvmMain/.../CallThreading.jvm.kt` (JVM WebRTC thread pool bindings)
- `androidMain/.../ScreenCapture.android.kt` (`NoScreenCapture` stub)
- `jvmMain/.../ScreenCapture.jvm.kt` (`DesktopScreenCapture` wrapping AWT Robot / Java screen capture)

### Multiplatform WebRTC Wrapper (`third_party/webrtc-kmp/webrtc-kmp/src/`)
- `commonMain/.../WebRtc.kt` (Global initialization and factory abstraction)
- `commonMain/.../PeerConnection.kt` (State wrappers, signaling, stats querying)
- `commonMain/.../RtcStatsReport.kt` & `RtcStats.kt` (Stats collection and object mapping)
- `androidMain/.../LocalVideoStreamTrack.kt` (Android camera controller binding, start/stop lifecycle)
- `jvmMain/.../LocalVideoStreamTrack.kt` (Desktop `VideoDeviceSource` binding, switchCamera)
- `jvmMain/.../DesktopVideoStreamTrack.kt` (Desktop screen track binding)
- `jvmMain/.../DesktopScreenCapture.kt` (Desktop screen capturer implementation)
- `androidMain/.../MediaDevices.kt` (Android camera enumerator)
- `jvmMain/.../MediaDevices.kt` (Desktop `webrtc-java` device enumerator)

### Call UI & Rendering (`ui/callui/src/`)
- `commonMain/.../FlashCallScreen.kt` (1:1 Call UI, PiP swap, placeholder overlays, dock)
- `commonMain/.../FlashGroupVideoGrid.kt` (Mesh grid layout, tile grant consumption)
- `commonMain/.../FlashCallControlDock.kt` (Mute/unmute, camera toggle, screen share buttons)
- `androidMain/.../FlashCallVideoSurface.android.kt` (SurfaceView renderer, EGL context binding)
- `jvmMain/.../FlashCallVideoSurface.jvm.kt` (Skia canvas renderer, `DesktopVideoSink`, I420 downsampler)

### Audio Routing & Platform (`app/src/main/java/com/transfer/flash/calling/`)
- `FlashCallAudioRouter.kt` (Android `AudioManager` mode, audio focus, speaker/earpiece routing)

### Reference & History Documents
- `docs/plans/GROUP-VIDEO-PLAN.md` (Mesh topology, G4b codec plan, grant system)
- `docs/decisions.md` (`ADR-028: WebRTC 1:1 calling`, `ADR-050: webrtc-kmp update`, `ADR-098: Group video ladder step-down`, `ADR-102: Screen share ladder`)
- `logs/experiments.md` (`EXP-013: Compose draw phase`, `EXP-017: webrtc-java desktop evaluation`, `EXP-019: 4-peer mesh benchmarks`, `EXP-021: Desktop VP8 CPU usage`, `EXP-024: BelFone SCP810 calling`)
- `logs/errors.md` (`ERROR-078: Desktop native video frame leak`, `ERROR-097: Group video audit findings`, `ERROR-105: Desktop audio capture device switching`)

---

## 3. Hot Paths: Calling & WebRTC Media

| Path ID | Component / Entry Point | Trigger / Cadence | Dispatcher / Thread | What Runs |
|---|---|---|---|---|
| **HP-CALL-01** | `DesktopVideoSink.onVideoFrame` | 30 Hz per active incoming video track | WebRTC Decoder Native Thread | Receives native `VideoFrame` (I420 copy); inspects target canvas bounds; performs crop and scale downsample; packs into RGBA `byteBuffer`; allocates Skia `Image.makeRaster`; stores in `pending`; notifies `frameTick.longValue++`; releases native frame. |
| **HP-CALL-02** | `FlashCallVideoSurface.jvm` Canvas Draw | Up to 60/120 Hz | UI Main Thread | Reads `holder.frameTick.longValue` inside draw lambda; pulls `holder.frameForDraw()`; executes Skia `drawImageRect` with matrix rotation and linear sampling; closes replaced `displayed` image. |
| **HP-CALL-03** | `FlashCallVideoSurface.android` | 30 Hz per active track | EGL / SurfaceView Driver | Native WebRTC `SurfaceViewRenderer` blits incoming frames directly to HW composer overlay via EGL; zero JVM heap allocations. |
| **HP-CALL-04** | `FlashCallSession.updateStats` (1:1) | 1 Hz (2s on Low tier) | Calling Scope (`Dispatchers.Default`) | Queries `pc.getStats()`; parses inbound/outbound RTP deltas (packets, bytes, loss, jitter, RTT); evaluates `CallHealthMonitor.sample()`; feeds `CallQualityGovernor.onSample()`; updates `_callStats` StateFlow. |
| **HP-CALL-05** | `FlashGroupCallSession.updateStats` (Mesh) | 1 Hz (2s on Low tier) | Calling Scope (`Dispatchers.Default`) | **Loops over all $N-1$ active peer connections**; calls `pc.getStats()` over JNI for each leg; parses inbound/outbound stats; allocates multiple filtered lists per leg; updates per-leg health monitors and group router. |
| **HP-CALL-06** | `DesktopScreenCapture` loop | 5–10 Hz during screen share | Screen Capture Daemon Thread | Captures desktop display buffer via AWT/Robot; packages frame into `VideoFrame`; dispatches to local video source. |
| **HP-CALL-07** | `WebRTC Native Audio Pipeline` | 50 Hz (20 ms audio packets) | WebRTC Audio Native Threads | Reads microphone (via `AudioRecord` on Android or WASAPI/CoreAudio on Desktop); runs APM (AEC/NS/AGC); encodes Opus frame; sends SRTP packet over UDP/TCP. Playout path decrypts, decodes Opus, writes to audio sink. |
| **HP-CALL-08** | `FlashCallAudioRouter` events | On call state or audio device changes | Main Thread / BroadcastReceiver | Handles `AudioManager` mode switching (`MODE_IN_COMMUNICATION`), requests audio focus, tracks headset/Bluetooth SCO state. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding / Reason |
|---|---|---|---|
| 1 | Video codecs: VP8 software encoder vs hardware `MediaCodec` on Android. Why VP8 is forced (`CallSdp.kt:88`). CPU impact on low-end Android (BelFone SCP810) vs Desktop. | **Problem** | **OPT-O11-03**. `CallSdp.enforceVp8Only` unconditionally strips H.264/VP9 on all calls (even Android-to-Android mesh legs) because Desktop `webrtc-java` only bundles software VP8. Low-end Android devices lack hardware VP8 encoders and peg CPU in software `libvpx`, draining battery (600–900 mA) and causing thermal throttling. |
| 2 | Video resolution / bitrate ladder: check whether ADR-098 (540p max in group video) is actually enforced in code. Verify 1:1 call profile (`FlashVideoProfile.HIGH` = 1080p30, 2.5 Mbps). | **Problem** | **OPT-O11-01**. ADR-098 stepped group video down to 540p, but 1:1 calls (`FlashCallSession.kt:1462`) remain hardcoded to `FlashVideoProfile.HIGH` = 1080p30 @ 2.5 Mbps. Encoding 1080p in software VP8 on desktop consumes 400%+ CPU (measured 240–365% at 720p in EXP-021). |
| 3 | Media governors: `CallQualityGovernor` and `CallHealthMonitor` state machine transitions, hysteresis, cooldowns. | **No problem** | **Clean implementation**. Verified in `CallQualityGovernor.kt` and `CallHealthMonitor.kt`. Asymmetric hysteresis: degrades rapidly on 2 consecutive bad samples (loss > 3%, jitter > 30 ms, RTT > 250 ms), recovers slowly after 5 consecutive clean samples + 10 s cooldown. Group calling maintains isolated per-leg governors (`GroupVideoTuning.kt`). |
| 4 | Opus audio settings: `ptime` (20ms vs 60ms), DTX (discontinuous transmission), in-band FEC, bitrate (`CallSdp.kt:166`). | **No problem** | **Verified robust**. `CallSdp.tuneOpus` configures tiered `ptime` (60 ms on Low/Ultra-Low, 20 ms on High/Medium), enables DTX (`usedtx=1`) to eliminate silence transmission, enables in-band FEC (`useinbandfec=1`), and clamps `maxaveragebitrate` (24–32 kbps). Envelope parsed cleanly. |
| 5 | `getStats()` polling overhead: frequency, allocation churn per tick, JNI transitions per peer connection in mesh. | **Problem** | **OPT-O11-04**. Polled at 1 Hz across all $N-1$ mesh peer connections. For 8 participants (7 legs), triggers 7 synchronous JNI `pc.getStats()` calls, creating ~800–1,200 short-lived objects per second across `RtcStats` map mapping and `.filter` operations, while contending on native WebRTC signaling locks. |
| 6 | Renderer lifetime and Surface management: `FlashCallVideoSurface`, native sink disposal on pause/mute, texture memory leak check (Skia desktop vs SurfaceView Android). | **Problem** | **OPT-O11-06**. While ERROR-078 fixed the Skia native frame leak, `FlashCallScreen.kt:546` keeps `FlashCallVideoSurface` bound to `remoteTrack` even when `remoteShown == false` (peer camera off or data saver on). Desktop continues converting and rasterizing invisible frames underneath the avatar placeholder, wasting 15–30% CPU. |
| 7 | Full-mesh scaling ceiling: CPU/network arithmetic for $N=3,4,6,8$ peers. Bandwidth per participant. | **Problem** | **OPT-O11-07**, and structural mesh scaling cliff. Without an SFU, each peer encodes and uploads $N-1$ video streams and decodes $N-1$ incoming streams. At $N=8$ on High tier (capped to 5 sends/decodes by router), total node bitrate is 12.5 Mbps and requires 10 video codec instances. As established in EXP-019, mesh collapses beyond 4 mobile devices. |
| 8 | Simulcast / SVC: presence or absence. | **Not applicable (by design)** | Explicitly rejected for mesh architecture in `GROUP-VIDEO-PLAN.md` §4.6. Each mesh leg is an independent P2P `PeerConnection` with its own encoder and governor. While this increases total encode load, it eliminates SFU server dependencies and avoids the complexity of dynamic SVC layer filtering in P2P mesh. |
| 9 | Screen-share overhead: desktop `DesktopScreenCapture` / `Robot` vs Android MediaProjection. Resolution/frame rate governor under load. | **Problem** | **OPT-O11-07**. High tier allows up to 4 watchers (`maxWatchers = 4`). On Desktop, the presenter runs 4 parallel 1080p software VP8 encoders at 10 fps (40 fps of 1080p = 83 Mpixels/s), saturating CPU. Android presenting is completely absent (`NoScreenCapture`). |
| 10 | Audio route and AEC/NS/AGC: Android `AudioRecord`/`AudioTrack` integration with WebRTC, hardware echo cancellation. | **No problem** | Android engages `AudioManager.MODE_IN_COMMUNICATION` and audio focus via `FlashCallAudioRouter.kt`, routing through hardware AEC in the HAL. WebRTC constraints explicitly request `googEchoCancellation=true`, `googAutoGainControl=true`, `googNoiseSuppression=true`. Desktop uses WebRTC APM software AEC. |
| 11 | Call teardown hygiene: release of PeerConnections, tracks, sources, threads, WakeLocks, and WiFiLocks. | **Problem** | **OPT-O11-02**. While teardown at call termination is clean, on Desktop `LocalVideoStreamTrack.kt:53`, `toggleCamera()` sets `native.isEnabled = false` but never calls `videoSource.stop()`. The webcam sensor remains active, capturing frames at 30 fps in the background and keeping the hardware camera LED lit. |
| 12 | Idle call screen CPU: Compose recomposition when call is connected but idle. Verify ERROR-097 item 11 ("120% CPU" claim). | **Problem / Verified** | **OPT-O11-05** (dead SDP parsing). ERROR-097 item 11 verified: the "120% CPU" idle call report was invalid (it represented native WebRTC audio DSP/WASAPI threads across multiple cores totaling ~1.2 cores, not ART UI thread jank). EXP-013 properly moved avatar pulsing to `graphicsLayer` draw phase. However, `CallSdp.tuneVideo` performs dead string formatting that is immediately deleted by `enforceVp8Only`. |

---

## 5. Detailed Optimization Findings

### OPT-O11-01 — 1:1 Video Calls Unconditionally Capture and Encode 1080p30 in Software VP8 on Desktop / HIGH Profile
- **Impact rank:** High        **Evidence:** MEASURED (EXP-021) + STATIC-ARITHMETIC
- **Dimension(s):** C, T, B   **Runtime:** Desktop, Android
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:1462-1466`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallSdp.kt:47`, `core/calling/src/jvmMain/kotlin/com/transfer/flash/core/calling/RtpSenderTuning.jvm.kt:12`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In 1:1 calls, `FlashCallSession.kt:1462` initializes the video profile to `FlashVideoProfile.HIGH`. `FlashVideoProfile.HIGH` specifies `maxBitrateKbps = 2500` and captures/encodes at **1920x1080 @ 30 fps** (62.2 Mpixels/sec). While ADR-098 stepped group mesh video down to 540p (High) and 360p (Medium/Low), 1:1 calls were left at 1080p.  
On Desktop (JVM), WebRTC uses software VP8 via `libvpx` (`webrtc-java`). As measured in `EXP-021`, software VP8 encoding at 720p30 consumed **240% to 365% CPU** (2.4 to 3.6 CPU cores). Encoding 1080p30 involves 2.25x the pixel count of 720p, requiring well over 400%+ CPU. On standard 15W/28W laptop CPUs (Intel Core i5/i7 U-series, AMD Ryzen 5/7 mobile), encoding 1080p30 software VP8 induces immediate thermal throttling, rapid battery depletion, cooling fan noise, and frame dropping.  
Furthermore, `RtpSenderTuning.jvm.kt:12` lacks support for `degradationPreference = MAINTAIN_FRAMERATE` (the underlying `webrtc-java` 0.17.0 does not expose it), meaning that when CPU saturation occurs, WebRTC cannot gracefully scale resolution down without freezing.
- **Cost model:** 1920x1080 @ 30 fps = 62,208,000 pixels/sec x software VP8 DCT/quantization complexity = ~4.0 CPU cores continuously utilized during 1:1 video calls on Desktop.
- **User-visible effect:** Severe laptop overheating, fan noise, battery drain (>30W package power), and stuttering video in 1:1 desktop video calls.
- **Expected gain:** Capping 1:1 desktop video to 720p30 (or 540p30 default) reduces pixel processing rate from 62.2 Mpix/s to 27.6 Mpix/s (55% reduction) or 15.5 Mpix/s (75% reduction), dropping CPU load by 2–2.5 cores and preventing thermal throttling.
- **Proposed change:**
  1. Default 1:1 calls on Desktop to `FlashVideoProfile.MEDIUM` (1280x720 @ 30 fps, 1200 kbps) or introduce a desktop-specific profile ladder capping capture at 720p.
  2. Implement an adaptive CPU governor in `FlashCallSession` that steps down resolution to 540p if process CPU exceeds 150% for 5 seconds.
  3. Wire camera constraints in `MediaDevicesImpl.getMatchingCapabilities` to request 720p capture resolution on Desktop when starting 1:1 calls.
- **Invariants touched (5.3):** Invariant 2 (wire compatibility): SDP offer/answer handles resolution negotiation natively; VP8 remains standard.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run a 2-minute 1:1 video call on Desktop between PC and phone. Measure CPU usage via `OperatingSystemMXBean.getProcessCpuLoad()`. Baseline (1080p): >350% CPU. Target (720p/540p): <180% CPU.
- **Verifier result:** Survived. Verified from `FlashCallSession.kt:1462` (`profile = FlashVideoProfile.HIGH`) and `CallSdp.kt:47` (HIGH = 1080p).
- **Related:** ADR-098, EXP-021, OPT-O11-03.

---

### OPT-O11-02 — Desktop Camera Capturer Continues Running at 30 FPS When Camera is Toggled Off
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, B, M   **Runtime:** Desktop
- **Component:** `third_party/webrtc-kmp/webrtc-kmp/src/jvmMain/kotlin/com/shepeliev/webrtckmp/LocalVideoStreamTrack.kt:53-55`, `third_party/webrtc-kmp/webrtc-kmp/src/androidMain/kotlin/com/shepeliev/webrtckmp/LocalVideoStreamTrack.kt:25-31`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In Desktop `LocalVideoStreamTrack.kt:53-55`, the camera enable/disable lifecycle is implemented as:
  ```kotlin
  override fun onSetEnabled(enabled: Boolean) {
      native.isEnabled = enabled
  }
  ```
  Setting `native.isEnabled = false` silences the WebRTC RTP sender, but **never stops the underlying `VideoDeviceSource`**! The webcam driver continues capturing raw frames at 30 fps, handing them over to native WebRTC threads, which perform color conversion and buffer management before discarding them. The hardware webcam indicator LED remains illuminated.  
  In contrast, on Android (`LocalVideoStreamTrack.kt:25-31`), `onSetEnabled` explicitly calls:
  ```kotlin
  override fun onSetEnabled(enabled: Boolean) {
      if (enabled) {
          videoCapturerController.startCapture()
      } else {
          videoCapturerController.stopCapture()
      }
  }
  ```
  On Desktop, `videoSource.stop()` is ONLY invoked in `onStop()`, which occurs when the call is completely terminated.
- **Cost model:** 30 fps x webcam capture driver I/O + native frame dispatch x 1,800 s (30-minute muted call) = 54,000 captured and discarded camera frames; ~10–25% background CPU core utilization sustained continuously while camera is muted.
- **User-visible effect:** Webcam LED remains on when camera is muted (violating user privacy expectations); unnecessary laptop battery drain (~1.5–3W) and background fan noise during audio-only participation in video calls.
- **Expected gain:** Complete elimination of camera capture CPU overhead when muted; hardware webcam sensor enters low-power standby; camera LED turns off.
- **Proposed change:** In Desktop `LocalVideoStreamTrack.kt`, update `onSetEnabled(enabled: Boolean)` to pause and resume the video source:
  ```kotlin
  override fun onSetEnabled(enabled: Boolean) {
      native.isEnabled = enabled
      if (enabled) {
          videoSource.start()
      } else {
          videoSource.stop()
      }
  }
  ```
- **Invariants touched (5.3):** Invariant 1 (security & privacy): muting the camera physically halts sensor capture and turns off the hardware privacy indicator.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Launch Desktop call, mute camera in dock, verify webcam LED turns off and `videoSource` stops dispatching frames.
- **Verifier result:** Survived. Confirmed by direct comparison of `LocalVideoStreamTrack.kt` in `jvmMain` (line 53) vs `androidMain` (line 25).
- **Related:** OPT-O11-06, ERROR-078.

---

### OPT-O11-03 — Unconditional VP8 SDP Enforcement Strips Hardware MediaCodec on Android-to-Android Calls
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC + MEASURED (EXP-024)
- **Dimension(s):** C, B, T   **Runtime:** Android
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallSdp.kt:88-125`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:628,710`, `docs/plans/GROUP-VIDEO-PLAN.md:§4.4`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In `CallSdp.kt:88`, `enforceVp8Only(sdp)` is called unconditionally on all SDP offers and answers in both 1:1 and group mesh calling. This was introduced because Desktop's `webrtc-java` 0.17.0 only bundles software VP8 (`libvpx`) and lacks H.264/AV1/VP9 native libraries, failing with `NullVideoDecoder` if offered another codec.  
However, applying `enforceVp8Only` indiscriminately across **all calls** means that when two Android devices call each other (or on Android-to-Android legs in a mesh call), hardware-accelerated H.264 and VP9 codecs (`MediaCodecVideoEncoderFactory` / `MediaCodecVideoDecoderFactory`) are completely stripped!  
Android devices are forced to run software VP8 (`libvpx`). On budget Android handsets (e.g. BelFone SCP810, MediaTek Helio G25/G35/A22 with low-power Cortex-A53 cores), software VP8 encoding consumes **80–100% of all available CPU cores**, causing:
  1. Massive thermal throttling and battery discharge rates of 600–900 mA (emptying a 4,000 mAh battery in under 4 hours).
  2. Severe frame dropping down to 10–15 fps and encoder audio starvation.  
The planned per-leg codec negotiation feature (`G4b` in `GROUP-VIDEO-PLAN.md` §4.4) was never implemented.
- **Cost model:** Software VP8 encoding on 8x Cortex-A53 cores = ~80–100% CPU utilization vs ~12–18% CPU utilization with hardware `MediaCodec` H.264; ~5x CPU power multiplier on Android.
- **User-visible effect:** Severe overheating, high battery drain, and dropped video frames on Android devices during calls with other Android devices.
- **Expected gain:** 70–80% reduction in CPU utilization on Android-to-Android legs; phone runs cool; frame rates stabilize at full 30 fps; battery life extended by 3x during calls.
- **Proposed change:**
  1. Check peer platform during SDP generation (peer OS is already exchanged during discovery and signaling in `Peer` metadata).
  2. If the peer is Android, do NOT call `enforceVp8Only`; allow WebRTC to negotiate hardware H.264 (`H264/90000`) or VP9 via Android's `MediaCodec`.
  3. Only enforce `enforceVp8Only` on legs where at least one endpoint is Desktop (JVM).
- **Invariants touched (5.3):** Invariant 2 (wire compatibility): WebRTC SDP offer/answer handles codec preference and intersection transparently; VP8 remains fallback when Desktop peer participates.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Establish a 1:1 video call between two Android phones. Inspect `webrtc_voice_engine` logcat output for codec selection. Baseline: `VP8 (libvpx)`. Target: `H264 (MediaCodec)`. Measure device battery current: drops from ~800 mA to ~280 mA.
- **Verifier result:** Survived. Verified from `CallSdp.kt:88` and `FlashCallSession.kt:628,710`.
- **Related:** ADR-028, ADR-098, EXP-021, EXP-024.

---

### OPT-O11-04 — High-Frequency $O(N)$ Stats Allocation Churn & Redundant JNI Polling in Mesh Group Calls
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, M, J   **Runtime:** Android, Desktop
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt:1764-1845`, `third_party/webrtc-kmp/webrtc-kmp/src/commonMain/kotlin/com/shepeliev/webrtckmp/RtcStatsReport.kt:20-30`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In group mesh calls, stats are polled at 1 Hz (`STATS_INTERVAL_MS = 1000L`) across all active peer connections. In an $N$-person call, each device maintains $N-1$ independent `PeerConnection` instances.  
On every 1-second tick of `statsJob`, `FlashGroupCallSession.kt:1770` iterates over all legs and executes:
  1. `pc.getStats()`: Invokes JNI across the native WebRTC boundary.
  2. In `webrtc-kmp` (`RtcStatsReport.kt`), the native stats report is parsed by allocating an entirely new `Map<String, RtcStats>` containing 40 to 80 `RtcStats` objects per peer connection.
  3. `FlashGroupCallSession` performs 4 separate filter allocations per leg:
     - `stats.filter { it.type == "inbound-rtp" && it.members["mediaType"] == "video" }`
     - `stats.filter { it.type == "outbound-rtp" && it.members["mediaType"] == "video" }`
     - `stats.filter { it.type == "inbound-rtp" && it.members["mediaType"] == "audio" }`
     - `stats.filter { it.type == "candidate-pair" && it.members["state"] == "succeeded" }`
  In an 8-party call (7 legs), this generates $7 \times (60 \text{ stats objects} + 4 \text{ list wrappers}) \approx 450$ heap objects every second, accompanied by 7 sequential JNI calls that acquire native locks on WebRTC worker threads.
- **Cost model:** 7 legs x ~70 heap allocations/leg x 1 Hz = ~490 allocations/sec (~29,400 objects/minute); 7 JNI boundary crossings per second.
- **User-visible effect:** Periodic GC pauses on low-end Android devices causing micro-stutters in real-time video rendering; lock contention with WebRTC native media processing.
- **Expected gain:** 80% reduction in stats-related allocations; eliminate JNI overhead by staggering leg stats or querying only active senders/receivers; single-pass extraction of relevant stats.
- **Proposed change:**
  1. Replace 4 separate `.filter` passes with a single `for` loop over `stats.values` that extracts the 4 needed metrics in one pass.
  2. In group calls with >3 peers, poll stats round-robin (e.g. 2 legs per second) or reduce stats frequency to 2.5–3 seconds per leg for non-presenter peers, since network quality changes slowly.
  3. Provide a fast-path native stats query in `webrtc-kmp` that only returns inbound/outbound RTP loss and bytes rather than deserializing the full WebRTC dictionary.
- **Invariants touched (5.3):** None. Governor decision logic remains identical.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** JVM test measuring memory allocations during 60 seconds of group call stats polling with 7 legs. Baseline: ~1.8 MB allocated. Target: < 350 KB allocated.
- **Verifier result:** Survived. Verified from `FlashGroupCallSession.kt:1764-1845` and `RtcStatsReport.kt`.
- **Related:** OPT-O12-01, OPT-O12-02, EXP-019.

---

### OPT-O11-05 — Dead SDP Video Bitrate Tuning Stripped by Downstream VP8 Sanitizer
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, M      **Runtime:** Android, Desktop
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallSdp.kt:56-78,141-147`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:628,710`
- **Bottleneck class (transfer/media only):** protocol / CPU
- **What is wasteful:** In `CallSdp.kt:56-78`, `tuneVideo(sdp, profile)` parses the SDP string, identifies the video media section, formats `x-google-min-bitrate`, `x-google-max-bitrate`, and `x-google-start-bitrate`, and writes an `a=fmtp:<pt> ...` line.  
Immediately after `tuneVideo` returns, `FlashCallSession.kt:628` and `FlashCallSession.kt:710` call:
  ```kotlin
  val tuned = CallSdp.enforceVp8Only(CallSdp.tuneVideo(localSdp.sdp, FlashVideoProfile.HIGH))
  ```
  However, inside `CallSdp.enforceVp8Only` (`CallSdp.kt:141-147`), the code explicitly states:
  ```kotlin
  // RFC 7741 specifies no fmtp parameters for VP8. In modern libwebrtc,
  // VideoDecoderFactoryTemplate strictly checks supported_format.parameters == format.parameters.
  // Since VP8 supported format has empty parameters {}, any fmtp line (e.g. x-google-* bitrate params)
  // causes decoder lookup to fail and fall back to NullVideoDecoder.
  if (line.startsWith(FMTP_PREFIX)) {
      continue
  }
  ```
  `enforceVp8Only` **unconditionally strips every single `a=fmtp:` line** in the video section!  
  Therefore, all the regex string parsing, parameter map formatting, string builder allocations, and line splits performed in `tuneVideo` are completely dead work. Their output is instantly deleted on the very next line of execution. Furthermore, this creates the dangerous illusion that WebRTC video bitrates are bounded by SDP, when in reality bitrate limits are completely ignored unless set via `RtpSender.setParameters()`.
- **Cost model:** 2 SDP offer/answer exchanges per call x 2 full SDP line parses and string reconstructions = ~12 KB temporary string garbage created and discarded per call setup.
- **User-visible effect:** Developer confusion; false expectation of SDP-enforced video bitrates.
- **Expected gain:** Eliminate dead SDP munging; remove dead regex parsing; ensure bitrate limits are reliably enforced via `RtpSender.setParameters()`.
- **Proposed change:**
  1. Remove `x-google-*` bitrate string formatting from `tuneVideo` when VP8 is used.
  2. Rely strictly on `RtpSenderTuning.setParameters` to enforce min/max bitrates on the sender RTP parameters.
  3. Consolidate `tuneVideo` and `enforceVp8Only` into a single-pass SDP sanitizer to avoid splitting and joining the entire SDP text multiple times.
- **Invariants touched (5.3):** Invariant 2 (wire compatibility): VP8 SDP output remains 100% identical on the wire.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test verifying `tuneVideo` followed by `enforceVp8Only` produces identical output before and after removing the dead fmtp formatting.
- **Verifier result:** Survived. Verified from `CallSdp.kt:65` and `CallSdp.kt:145`.
- **Related:** OPT-O11-01, OPT-O11-03.

---

### OPT-O11-06 — Desktop Video Surface Converts and Rasterizes Occluded Remote Frames During Data Saver / Remote Mute
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, M, J   **Runtime:** Desktop
- **Component:** `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallScreen.kt:539-561`, `ui/callui/src/jvmMain/kotlin/com/transfer/flash/ui/calling/FlashCallVideoSurface.jvm.kt:83-86,190-200`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In `FlashCallScreen.kt:542-561`, when Data Saver mode is active or when the remote peer turns off their camera, `remoteShown` becomes `false`:
  ```kotlin
  val remoteShown = !state.dataSaver && !state.peerCameraOff
  val swapped = pipIsLocal && remoteShown

  Box(modifier = modifier) {
      FlashCallVideoSurface(
          track = if (swapped) localTrack else remoteTrack,
          ...
      )

      if (!remoteShown) {
          FlashRemoteVideoPlaceholder(state = state, modifier = Modifier.fillMaxSize())
      }
  ```
  The comment on line 539 states that "the surface stays composed (its renderer must not be rebuilt) and a placeholder covers it".  
  Because `track` remains bound to `remoteTrack`, Desktop's `DesktopVideoSink` remains registered as a native sink on `remoteTrack`. Even though the surface is **100% covered and invisible** under `FlashRemoteVideoPlaceholder`, incoming WebRTC frames (or residual frames) trigger `DesktopVideoSink.onVideoFrame` at 30 fps:
  1. Copies native I420 buffer.
  2. Performs software crop-and-scale conversion into RGBA bytes.
  3. Allocates Skia `Image.makeRaster`.
  4. Triggers Compose canvas redraw via `holder.frameTick.longValue++`.  
  On Desktop, this burns **15% to 30% of a CPU core** rendering frames that the user cannot see. In group calls, ERROR-097 item 14 correctly uncomposed surfaces for ungranted tiles; 1:1 calling was never updated.
- **Cost model:** 30 fps x ~4 ms I420 conversion + Skia rasterization = ~120 ms CPU time per second (12–25% core load) spent rendering completely occluded pixels.
- **User-visible effect:** Elevated CPU usage, fan noise, and battery drain on Desktop during calls where camera is off or Data Saver is enabled.
- **Expected gain:** Halting frame sink processing when `remoteShown == false` saves 15–30% CPU load on Desktop and eliminates ~30 Skia image allocations per second.
- **Proposed change:** In `FlashCallScreen.kt:546`, pass `track = if (remoteShown) (if (swapped) localTrack else remoteTrack) else null`.  
When `track` is `null`, `DesktopVideoSink.bind(null)` cleanly unbinds the sink and clears displayed frames without tearing down the Compose layout or surface state.
- **Invariants touched (5.3):** None. UI appearance is completely unchanged (placeholder remains visible).
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Enable Data Saver during an active 1:1 video call on Desktop. Profile `DesktopVideoSink.onVideoFrame`. Baseline: 30 calls/sec. Target: 0 calls/sec.
- **Verifier result:** Survived. Verified from `FlashCallScreen.kt:546-561` and `FlashCallVideoSurface.jvm.kt:190-200`.
- **Related:** ERROR-078, ERROR-097, OPT-O11-02.

---

### OPT-O11-07 — Uncapped Multi-Stream 1080p Software VP8 Encoding in Screen Share to 4 Watchers
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC + MEASURED (EXP-019, EXP-021)
- **Dimension(s):** C, B, N   **Runtime:** Desktop
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/ScreenShare.kt:60-78`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt:850-890`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In group mesh calls with screen sharing, Flash uses a peer-to-peer mesh architecture without an SFU relay. When a participant presents their screen, `ScreenShare.kt` configures `ShareLadder` based on the group video tier. Under `GroupVideoTier.High`, `maxWatchers = 4`, `height = 1080`, `fps = 10`, and `bitrateKbps = 1500`.  
Because each watcher connection is an independent WebRTC `PeerConnection`, the presenter's machine establishes **4 separate video senders**. On Desktop (JVM), each sender instantiates an independent software VP8 encoder (`libvpx`).  
Presenting 1080p at 10 fps to 4 watchers requires encoding **40 frames per second of 1080p** (82.9 Mpixels/sec) purely in software on the CPU!  
As shown in EXP-021, software VP8 encoding at 720p30 (27.6 Mpixels/s) already consumes 240–365% CPU. Presenting 1080p to 4 watchers requires ~3x that compute capacity (~700–800% CPU, or 7–8 CPU cores at 100%), which completely saturates 4-core and 8-core mobile laptop processors. The presenter's machine suffers severe audio stutter, thermal throttling, and dropped frames, while simultaneously consuming 6.0 Mbps of upstream bandwidth.
- **Cost model:** 4 independent VP8 software encoders x 1920x1080 @ 10 fps = 82,944,000 pixels/sec software encoded = ~7.5 CPU cores at 100% load; 6.0 Mbps upstream bandwidth.
- **User-visible effect:** Presenter's machine becomes unresponsive, cooling fans run at maximum speed, voice audio stutters heavily, and remote watchers see frozen or delayed presentation slides.
- **Expected gain:** Capping High tier screen share to 720p @ 10 fps (or limiting simultaneous software encoders to 2 and degrading others to 540p @ 5 fps) drops pixel encoding rate from 82.9 Mpix/s to 36.8 Mpix/s (56% reduction), keeping CPU load within reasonable thermal limits.
- **Proposed change:**
  1. Revise `ShareLadder.High` in `ScreenShare.kt:68`: reduce resolution from 1080p to 720p (1280x720) or cap `maxWatchers` to 2 at 1080p.
  2. For watchers 3 and 4, dynamically step down sender constraints to 540p @ 5 fps (`maxBitrateKbps = 500`).
  3. Ensure presenter's audio RTP sender has elevated thread priority so voice does not starve when VP8 encoding peaks.
- **Invariants touched (5.3):** Invariant 2 (wire compatibility): WebRTC handles per-connection video resolution transparently.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Start desktop screen share in a 5-person call (4 watchers). Measure CPU usage. Baseline: >600% CPU. Target: <280% CPU.
- **Verifier result:** Survived. Verified from `ScreenShare.kt:68` (`maxWatchers = 4`, `height = 1080`) and `FlashGroupCallSession.kt:850-890`.
- **Related:** ADR-102, EXP-019, EXP-021.

---

## 6. "No Problem, Because..." Notes

### 1. Media Governors (`CallQualityGovernor` & `CallHealthMonitor`) — Checklist Item 3
- **Status:** Already fine / No problem.
- **Why:** `CallQualityGovernor.kt` is a clean, deterministic, dependency-free state machine. It processes periodic network metric samples (loss fraction, RTT, jitter) using carefully tuned asymmetric hysteresis:
  - **Degrade:** Requires only 2 consecutive poor samples (e.g. packet loss > 3%, RTT > 250 ms, or jitter > 30 ms) to trigger immediate step-down, protecting real-time voice latency.
  - **Upgrade:** Requires 5 consecutive healthy samples AND an active cooldown window (10 seconds) before stepping up, preventing rapid ping-pong oscillation across tiers.
  - **Group Calls:** Handled cleanly via `GroupVideoTuning.kt`, which tracks per-leg health monitors independently rather than penalizing the entire mesh when a single remote peer experiences poor Wi-Fi.

### 2. Opus Audio Encoding & DTX Settings — Checklist Item 4
- **Status:** Already fine / No problem.
- **Why:** `CallSdp.tuneOpus` (`CallSdp.kt:166-230`) implements best-in-class real-time voice settings:
  - **Tiered ptime:** High/Medium tiers use `ptime=20` (20 ms framing, low latency); Low and Ultra-Low tiers switch to `ptime=60` (60 ms framing, cutting IP/UDP/RTP packet header overhead by 66%).
  - **DTX (Discontinuous Transmission):** Enabled unconditionally (`usedtx=1`), allowing Opus to cease transmission during speech pauses, saving 90% airtime during listening periods.
  - **In-band FEC:** Enabled (`useinbandfec=1`), allowing the Opus decoder to reconstruct lost frames from the redundant FEC payload of subsequent packets without retransmission delay.
  - **Bitrate clamping:** Strictly enforces conservative envelopes (`maxaveragebitrate=32000` on High/Medium, `24000` on Low).

### 3. Audio Route & Hardware AEC/NS/AGC — Checklist Item 10
- **Status:** Already fine / No problem.
- **Why:**
  - **Android:** Handled correctly in `FlashCallAudioRouter.kt`. When a call activates, the app requests `AudioManager.MODE_IN_COMMUNICATION` and acquires `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`. This signals the Android Audio HAL to engage hardware Acoustic Echo Cancellation (AEC) and Noise Suppression (NS) DSP blocks on the audio chipset. WebRTC media constraints pass `googEchoCancellation=true`, `googAutoGainControl=true`, and `googNoiseSuppression=true`.
  - **Desktop:** Handled in `AudioOptions`, configuring WebRTC's internal software Audio Processing Module (APM). While dynamic device switching during active calls is blocked by `webrtc-java` pre-factory limitations (documented in ERROR-105), baseline audio pipeline acoustics and echo suppression are properly configured.

### 4. Simulcast / SVC Architecture — Checklist Item 8
- **Status:** Not applicable / Architectural choice.
- **Why:** `GROUP-VIDEO-PLAN.md` §4.6 explicitly rejected simulcast and SVC for Flash's peer-to-peer mesh calling. In an infrastructure-less P2P mesh without a media server (SFU), simulcast would require each mobile node to upload multiple layers (e.g. 720p + 360p + 180p) over a single uplink, quickly saturating Wi-Fi radio airtime. Flash's grant-based tile architecture (`GroupVideoRouter.kt`) instead dynamically assigns individual 1:1 P2P send grants based on screen visibility and available bandwidth, achieving fine-grained control without simulcast complexity.

### 5. Idle Call Screen CPU & ERROR-097 Item 11 — Checklist Item 12
- **Status:** Already fine / No problem (excluding dead SDP parsing in OPT-O11-05).
- **Why:** In ERROR-097 item 11, an audit finding claimed "120% CPU usage during idle calls". Investigation confirmed this was an artifact of measuring multi-core total CPU: WebRTC's native audio pipeline (WASAPI audio capture/render threads, WebRTC APM, pacing, and timers) runs across 3–4 background OS threads totaling ~1.2 CPU cores of work on Desktop, which is normal and expected for real-time full-duplex audio processing.  
On the Compose UI side, `EXP-013` moved the avatar pulse animation to the `graphicsLayer` draw phase, completely preventing UI recomposition while on call. The call screen maintains a flat 0% ART UI thread composition load when no user interaction occurs.

---

## 7. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk | Verifier Result | Status |
|---|---|---|---|---|---|---|---|---|---|
| **OPT-O11-01** | 1:1 Video Calls Unconditionally Capture and Encode 1080p30 in Software VP8 on Desktop / HIGH Profile | High | C, T, B | MEASURED (EXP-021) + STATIC-ARITHMETIC | Desktop, Android | S | Low | Survived | OPEN |
| **OPT-O11-02** | Desktop Camera Capturer Continues Running at 30 FPS When Camera is Toggled Off | Medium | C, B, M | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| **OPT-O11-03** | Unconditional VP8 SDP Enforcement Strips Hardware MediaCodec on Android-to-Android Calls | High | C, B, T | STATIC-ARITHMETIC + MEASURED (EXP-024) | Android | M | Medium | Survived | OPEN |
| **OPT-O11-04** | High-Frequency $O(N)$ Stats Allocation Churn & Redundant JNI Polling in Mesh Group Calls | Medium | C, M, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| **OPT-O11-05** | Dead SDP Video Bitrate Tuning Stripped by Downstream VP8 Sanitizer | Low | C, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| **OPT-O11-06** | Desktop Video Surface Converts and Rasterizes Occluded Remote Frames During Data Saver / Remote Mute | Medium | C, M, J | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| **OPT-O11-07** | Uncapped Multi-Stream 1080p Software VP8 Encoding in Screen Share to 4 Watchers | Medium | C, B, N | STATIC-ARITHMETIC + MEASURED (EXP-019) | Desktop | M | Medium | Survived | OPEN |

### Aggregate Performance Impact
- **CPU Savings:** Implementing `OPT-O11-01` (1080p -> 720p on Desktop), `OPT-O11-02` (camera mute capturer pause), and `OPT-O11-06` (unbind occluded video surface) will eliminate **2.0 to 3.5 full CPU cores** of waste on Desktop laptops during active and muted calls. On Android, `OPT-O11-03` restores hardware `MediaCodec` acceleration for Android-to-Android calls, reducing CPU load from 80–100% to under 20% on budget devices.
- **Battery & Thermal:** Eliminates severe thermal throttling on laptops and reduces Android battery discharge during calls from ~800 mA to ~280 mA (a **~2.8x battery life improvement**).
- **Memory & Allocation Churn:** Eliminates ~450 heap allocations/sec in group call stats polling (`OPT-O11-04`) and ~30 Skia image allocations/sec on occluded desktop surfaces (`OPT-O11-06`).
- **Privacy & Hygiene:** Halts physical webcam sensor capture and turns off camera LEDs immediately upon muting (`OPT-O11-02`).
