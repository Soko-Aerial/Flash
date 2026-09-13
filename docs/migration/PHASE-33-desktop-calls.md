# PHASE-33 — Desktop calls (the substrate is proven; the wiring is not)

**Status:** AUTHORED (2026-09-13, planning only — **no code written**). **Depends on Phases 27 + 25**
(executed). Likely wants splitting — see "Scope warning".
**Risk:** HIGH — the largest of the six desktop phases. First live media on desktop.
**Decisions relied on:** D12/ADR-034 (vendored fork), D11 = B, R5, R6.

---

## What this phase is for

Phase 25 ended with this recorded, and it is accurate:

> What is NOT done — and never was part of D12 — is *product* wiring: `:desktop` does not yet place
> calls (no call UI in the desktop shell, no desktop call service/ringer equivalent). That is new
> feature work for a future phase, not a migration gap.

This is that phase. The gap is concrete and verified:

- `desktop/build.gradle.kts` has **no** `project(":ui:callui")` and **no** `project(":core:calling")`
  edge. (Its comment at line 42–43 still says `:ui:callui` is "still `com.android.library`" — that
  is now **stale**: `ui/callui/build.gradle.kts` is KMP with a `jvm()` target, and
  `core/calling` publishes a `-jvm` variant as of Phase 25.)
- On Android the call screen is **not a destination** — it is a topmost overlay:
  `if (activeCall != null) { FlashCallScreen(…) }` at `MainActivity.kt:1594`, driven by
  `engine.calls?.activeCall` (`:1563`).
- Nothing on desktop calls `FlashCallScreen`.

## What is already done (do not rebuild)

This is the good part, and it is most of the hard part:

| Piece | Where | State |
|---|---|---|
| `FlashCallScreen(state, session, onAccept, onDecline, onHangUp, onToggleMute, onToggleSpeaker, onToggleCamera, onSwitchCamera, onDismiss)` | `ui/callui/.../FlashCallScreen.kt:90` | **shared, commonMain**, no platform types |
| The video renderer | `ui/callui/.../FlashCallVideoSurface.jvm.kt` | **a real desktop renderer** — Swing panel implementing `VideoTrackSink`, `VideoBufferConverter` I420→ARGB, letterbox/fill-crop (Phase 25 S3b) |
| `CallCoordinator` — the `FlashCalling` implementation | `core/calling/.../CallCoordinator.kt:88` | **commonMain** since Phase 25 S2e; takes platform-free lambdas (`prioritiseVoice`, `performanceMode`, `peerNameResolver`) per ADR-024 |
| `FlashCalling` + `FlashCallMedia` + both session classes | `core/calling/commonMain` | **shared** |
| The media stack actually running on a JVM | `DesktopMediaStackSmokeTest` | **proven**: factory init loads webrtc-java's natives, real offer with an `m=application` section, ICE host candidates gathered, `MediaDevices.enumerateDevices()` returns |
| Sender tuning with a recorded desktop gap | `RtpSenderTuning.jvm.kt` | bitrate only — **no pacer priority, no degradationPreference** on webrtc-java 0.17.0; logged once per process |

**So: the screen, the renderer, the engine, the sessions and the media substrate all exist and are
tested. What is missing is three edges and one owner.**

## The three real gaps

### 1. `FlashWebRtcEngine` has no desktop counterpart — and needs almost none

`core/calling/src/androidMain/.../FlashWebRtcEngine.kt:71` is a `public object` doing two
Android-specific things before the first `PeerConnection` is touched:

- installs an `org.webrtc.audio.JavaAudioDeviceModule` with `useLowLatency = true`. Its KDoc is
  emphatic about why it must run first: webrtc-kmp builds its factory lazily and never sets an ADM,
  so libwebrtc's default (`useLowLatency = false`) would be upstream of the jitter buffer and
  unreachable from any per-call API.
- **probes the ADM capture source** (`VOICE_COMMUNICATION`, ERROR-032) because some OEM HALs open
  cleanly and then deliver zero frames — recorded in this repo's own incident memory.

**Neither applies to `webrtc-java`.** There is no `JavaAudioDeviceModule` class; the JVM backend
uses its own ADM, and the OEM-HAL failure mode is an Android audio-stack bug. So desktop bring-up is
a **much smaller** object — possibly nothing at all, since `DesktopMediaStackSmokeTest` already
constructs a working `PeerConnection` with no configuration step. **Confirm this before writing
code; if no equivalent is needed, the honest outcome is "no desktop engine object", and the phase
should say so rather than inventing a symmetric-looking one.**

### 2. Nobody owns the call lifecycle on desktop

On Android, four `:app` classes exist with **no desktop analogue and no obvious need for one**:

| Android (`app/.../calling/`) | Job | Desktop equivalent needed? |
|---|---|---|
| `FlashCallService` (`:63`) | foreground service + call notification | **No service** — but see the notification question below |
| `FlashCallRinger` (`:46`) | ringtone + vibration, transient ring focus | **No vibration.** A ringtone is a product question |
| `FlashCallActionReceiver` (`:22`) | notification Answer/Decline actions | Only if a tray notification exists |
| `FlashCallAudioRouter` | `AudioManager` mode/focus/routing; hardware AEC depends on the mode | **Must be replaced** — see gap 3 |

The open product question: **an incoming call while the desktop window is minimised.** Android's
answer is a notification with actions. Desktop's options are window attention
(`Window.toFront()`/urgency hint), a system-tray notification, or nothing until the user looks. This
is a decision, not an implementation detail, and it determines whether
`FlashCallActionReceiver` has a desktop twin.

### 3. Audio routing and device selection

The Android router exists because **the platform audio mode gates hardware AEC** (a recorded bug
class: calls had no echo cancellation until the mode was set, and attach must precede `startMedia`
but never run during RINGING). Desktop has no `AudioManager`, and the equivalent controls are:

- **Capture/playback device choice** — `MediaDevices.enumerateDevices()` works on the JVM (proven),
  so a device picker is implementable. A laptop with a headset plugged in will otherwise pick the
  wrong default.
- **Echo cancellation** — webrtc-java's ADM handles this itself; whether the defaults are adequate
  is a **measurement**, not an assumption. Test it before claiming it.

### 4. Who can you call?

Calls need a peer. Desktop has no conversation screen (Phase 29) and no send affordance (Phase 30) —
and the same entry-point problem applies here: the natural home is a **Call** action on a *trusted*
peer row in Nearby, which is the only place desktop has a trusted-peer identity to call. This
overlaps Phase 30's chosen entry point (a trusted-peer action) and the two phases should share it
rather than each inventing one.

## Scope warning — this phase should probably be three

The above is roughly:

- **33a** — dependency edges + a desktop call lifecycle owner + `FlashCallScreen` routed as the
  desktop's call overlay, **audio-only, outgoing only**. Smallest thing that can possibly work, and
  it proves the engine wiring.
- **33b** — incoming calls (ringing, accept/decline, the minimise-while-ringing decision), plus
  either a tray notification or an explicit decision not to have one.
- **33c** — video (the renderer is ready; camera enumeration, mute/camera/speaker toggles,
  `onSwitchCamera` on a machine with one camera) and the device picker.

Splitting is recommended: each of the three has a different failure mode, and a single phase that
fails gives no signal about which layer broke — the same argument Phase 31 makes for manual connect.

## Do NOT

- **Do NOT change `FlashCallScreen`, `CallCoordinator`, or the sessions.** They are shared and
  phone-tested. If something needs to differ per platform, it goes behind a seam, not behind an
  `if`.
- **Do NOT port `FlashCallAudioRouter` by analogy.** `AudioManager` has no JVM counterpart; guessing
  at an equivalent is how the AEC bug returns. Measure what webrtc-java's ADM does by default first.
- **Do NOT claim the desktop tuning gap is closed.** `RtpSenderTuning.jvm.kt` sets bitrate only —
  webrtc-java 0.17.0 exposes no pacer priority and no `degradationPreference`. A desktop call will
  behave differently from a phone call under congestion, and the log entry must say so.
- **Do NOT skip the `preferIPv4Stack` question.** `:desktop:run` now sets it (see that flag's
  comment). ICE on the JVM backend may or may not care; if desktop calls fail to connect while the
  WS session is fine, that flag is the first suspect.
- **Do NOT touch `:core:calling`'s Android `consumer-rules.pro`** — the blunt `org.webrtc.**` keep
  without which a minifying consumer crashes inside factory init.

## Sub-step plan (if NOT split)

| # | Sub-step | Content |
|---|---|---|
| 33-1 | Dependency edges | `:desktop` gains `:ui:callui` + `:core:calling`; delete the stale "still com.android.library" comment at `desktop/build.gradle.kts:42–43`. |
| 33-2 | Desktop bring-up | Establish whether a `FlashWebRtcEngine` equivalent is needed at all (see gap 1). Record the answer either way. |
| 33-3 | Lifecycle owner | A desktop call host: builds `CallCoordinator` with desktop lambdas, exposes `activeCall`, and owns accept/decline/hangup. |
| 33-4 | Route the screen | `FlashCallScreen` as a topmost overlay in the Phase‑27 shell, mirroring `MainActivity.kt:1594`'s z-order. |
| 33-5 | Call entry point | Trusted-peer Call action (shared with Phase 30's send entry point). |
| 33-6 | Incoming + ringing | The minimise-while-ringing decision, implemented. |
| 33-7 | Audio | Device enumeration + picker; measure AEC. |
| 33-8 | Verification | Compile gates, R6 scan, plus **a real desktop↔phone call end to end** — the only evidence that counts. |
| 33-9 | Log + README | Honest entry including the tuning gap and the AEC measurement. |

## The acceptance test

A real call between the Windows app and the phone: audio both directions, hang-up clean on both
sides, a second call placed afterwards works (the connecting-race and half-open-session bug classes
both live here), and the phone's screen-off behaviour is unchanged.
