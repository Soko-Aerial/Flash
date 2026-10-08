# Modifications to WebRTC KMP

This directory is a **modified** copy of WebRTC KMP, licensed under the Apache License 2.0 (see `LICENSE`).
This file is the notice of changes required by Apache-2.0 §4(b).

- Upstream: https://github.com/shepeliev/webrtc-kmp
- Forked from: https://github.com/aschulz90/webrtc-kmp, which adds a `jvm()` target to upstream
- Vendored into Flash on 2026-09-13 (commit `00b23cf`, ADR-034), versioned `0.125.11-flash-1`
- Upstream has no NOTICE file, so there is none to pass through.

Modified files also carry a header comment pointing here.

## Changes made by the Flash project

| Date | Commit | Change |
|---|---|---|
| 2026-09-13 | `00b23cf` | Removed the iOS, js and wasmJs targets, CocoaPods, and the signing/Nexus/ktlint publishing setup (Flash builds only Android and JVM). Moved the build to Gradle 9.5, AGP 9.3.1, Kotlin 2.2.10, coroutines 1.10.2 and compileSdk 35, using `com.android.kotlin.multiplatform.library`. Renamed the test source sets to `androidHostTest`/`androidDeviceTest`. Files: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `webrtc-kmp/build.gradle.kts`. |
| 2026-09-13 | `00b23cf` | Upgraded webrtc-java from 0.8.0 to 0.17.0. `RtcStats.kt`: `.members` now reads `.attributes`. `PeerConnection.kt` (jvm): `removeIceCandidates` is an honest no-op returning `false`, and `onIceCandidatesRemoved` is no longer emitted, because both were removed upstream. |
| 2026-09-13 | `9303bfb` | Moved `CameraPermissionException` and `RecordAudioPermissionException` from `androidMain` to `commonMain`. |
| 2026-09-16 | `10cd82a` | jvm `WebRtc.kt` / `MediaDevices.kt`: the audio device module is selected and initialised once, before the factory, in the official order. The engine owns start/stop, and `getUserMedia` never touches the ADM (Flash ERROR-061). |
| 2026-09-16 | `94d9200` | jvm `LocalVideoStreamTrack.kt` / `MediaDevices.kt`: camera capture for desktop video calls, and camera switching. |
| 2026-09-17 | `fbd3967`, `7f97c89` | `settings.gradle.kts`: added a Maven Central mirror as a fallback repository. |
| 2026-09-29 | (ERROR-075) | jvm `MediaDevices.kt` / `LocalVideoStreamTrack.kt`: the camera opens at the best mode within the requested size (largest picture, then highest frame rate; the smallest mode if none fits) instead of the first mode the camera lists; camera switching uses the same choice; the local track's `settings` report the opened width, height, frame rate and device; one `[webrtc-jvm] camera … opened at` line is printed (Flash ERROR-075). |
| 2026-09-29 | (ERROR-078) | jvm `RtcStats.kt`: `type` is the W3C stats type name (`inbound-rtp`) instead of webrtc-java's enum name (`INBOUND_RTP`), matching Android (Flash ERROR-078). |
| 2026-09-29 | (ADR-054) | Upgraded webrtc-java from 0.17.0 to 0.19.0 (`gradle/libs.versions.toml`). jvm `PeerConnection.kt`: every `RTCRtpSender`/`RTCRtpReceiver`/`RTCRtpTransceiver` the connection obtains (getters, `addTrack`, track callbacks) is recorded and disposed after `close()`, because since 0.18.0 each instance holds its own native reference. jvm `RtpTransceiver.kt`: optional `owner` connection that records the sender/receiver instances its getters create. jvm `LocalAudioStreamTrack.kt`: disposes its `AudioTrackSource` on stop (0.19.0 added `dispose()`). |
| 2026-10-08 | (ERROR-123) | jvm `RenderedVideoStreamTrack.kt` / `RemoteVideoStreamTrack.kt`: the track records its attached sinks behind a lock; `onStop()` of a remote track removes them all while the native track is alive, and later `addSink`/`removeSink` never reach native code (a late `VideoTrack.removeSinkInternal` after the connection closed killed the JVM with SIGSEGV on Linux). |
