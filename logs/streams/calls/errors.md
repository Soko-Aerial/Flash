# Stream C (calls) — error fragment

Reserved block: **ERROR-981..990**. Folded into `logs/errors.md` by the integrator.

## ERROR-981 — Switching camera on a muted desktop track restarted the webcam

### Date
2026-11-02

### Area
WebRTC / desktop video capture (`third_party/webrtc-kmp`, jvm target)

### Symptoms
No report from a user — found while fixing `OPT-O11-02`. With the camera toggled off (track disabled), a "switch
camera" action silently restarted the capture source, so the next frame arrived from the new camera while the UI showed
"Video off", the RTP sender was still silenced, and the OS kept the camera open (LED on).

### Environment
- Android Studio / Gradle: the stream's build (`:core:calling:jvmTest`), Windows host.
- Reproduced: by reading the code path; not reproduced on a device (no desktop call session in this stream).

### Error
Not an exception. The defect is ordering:

```kotlin
// before, jvm LocalVideoStreamTrack.switchCamera()
videoSource.stop()
videoSource.setVideoCaptureDevice(targetDevice)
videoSource.setVideoCaptureCapability(capability)
videoSource.start()          // <- starts even when the track is disabled
```

### Root cause
`switchCamera` had no notion of the track's enabled state, and `onSetEnabled` never touched the source at all (the
`OPT-O11-02` defect). Before this stream the two together meant "the camera is always running until the call ends", so
the switch path never needed the check.

### Working fix
`LocalVideoStreamTrack` keeps a `sourceRunning` flag that `onSetEnabled`/`onStop` maintain, and `switchCamera` only
starts the source when it was running. Committed with `OPT-O11-02` (`3cd95e4a`).

### Verification
Unit-level: the compile of `:webrtc-kmp:webrtc-kmp` and every consumer. **Device check owed** — `PERF-C01` step 3 in
`backlog-additions.md` (switch camera while muted: the camera LED must stay off and no frames may arrive).

### Related files
- `third_party/webrtc-kmp/webrtc-kmp/src/jvmMain/kotlin/com/shepeliev/webrtckmp/LocalVideoStreamTrack.kt`
- `third_party/webrtc-kmp/MODIFICATIONS.md`

### Status
RESOLVED IN CODE, NOT DEVICE-VERIFIED (see `PERF-C01`).
