# Phase 25 (TBD-number) — Calling stack on desktop (`:core:calling` + `:ui:callui`)

**Status:** AUTHORIZED (D11 = Option B, 2026-09-05), RESEARCH DONE (2026-09-12), **DECISION
MADE (2026-09-13): Option B, realized as the vendored webrtc-kmp fork — see "The decision"
below.** Execution authorized; sub-steps run in stage order.
**Blocked by:** nothing in the phase graph (15/16 are done/built; the README's "must not be
inserted ahead of 15/16" is satisfied).
**Risk:** HIGH — `:core:calling` is the WebRTC module; R8-adjacent (the ADR-025 audio path), and
the published `core-calling` coordinate is already consumed at 1.1.0. NEW: the fork is code we
now own (ADR-034).
**Decisions relied on:** D11 = B (in scope, research first — discharged), **D12 = vendored fork
(2026-09-13, ADR-034)**, D1 = B (strict commonMain), R5 (plain `jvm()`), R8 (crypto/security +
ADR-025 audio behaviour untouchable), R10 (no version bumps — the fork's webrtc-java bump IS the
human-authorized exception, recorded in D12).

---

> ## ⚠️ READ FIRST — the research gate is already discharged and its findings bind this file
>
> D11's answer requires this phase to "open with a WebRTC-for-desktop-JVM investigation and
> report its findings before proposing any conversion work". That report exists:
> [`research/calling-stack-desktop-jvm-research.md`](research/calling-stack-desktop-jvm-research.md)
> (2026-09-12, committed `7b20e6a`). Its findings are **inputs to this file, not suggestions**:
>
> - **F1 (hard blocker, measured):** `com.shepeliev:webrtc-kmp:0.125.11` — the module's
>   `api` dependency — **publishes no JVM target at all.** Its Gradle module metadata enumerates
>   android / iosArm64 / iosSimulatorArm64 / iosX64 / js / wasmJs + metadata; there is no `jvm`
>   variant and no JVM platform type. `:core:calling` therefore **cannot declare a `jvm()`
>   target today** — dependency resolution fails before any code question (the ERROR-049 wall
>   `core/engine/build.gradle.kts:139–151` documents for the same reason).
> - **F3:** `FlashCallSession`/`FlashGroupCallSession` import raw `org.webrtc.Priority` +
>   `org.webrtc.RtpParameters.DegradationPreference` — types from the Android libwebrtc wrapper
>   that webrtc-kmp does not re-export multiplatform. Even a hypothetical webrtc-kmp JVM target
>   would not satisfy these two imports; they must be re-typed in any conversion.
> - **F4:** no artifact available today gives `:core:calling` a desktop-JVM media path without
>   either adopting a new plain-Java dependency (a human R10/licence decision) or upstream
>   movement.
> - **F5:** the signaling half IS convertible now (the "Option A" shape below).
>
> **No sub-step in this file may begin before the human answers the A–D decision.** The file is
> authored ahead per this repo's own planning pattern (all 25 phase files pre-exist their
> execution), but its execution steps are written per-option, not as a single assumed path.

---

## What this phase is for

Bring the calling stack — `:core:calling` (10 files, 4,047 lines, `explicitApi()`, published as
`core-calling` at 1.1.0) and `:ui:callui` (one 948-line Compose screen, published as
`ui-callui`) — into the desktop scope D11 = B authorizes, without regressing the Android
release behaviour (R8: the ADR-025/ERROR-032 audio path in `FlashWebRtcEngine` is the product's
call-quality core and must stay bit-identical) and without touching the frozen toolchain (R10).

## The decision (made 2026-09-13 — the human's, recorded as D12 / ADR-034)

The human evaluated the four shapes against the research report and a live verification pass of
the community fork (2026-09-13, same day). **Pick: Option B, realized by vendoring
`aschulz90/webrtc-kmp` (the fork of our own library that adds a `jvm()` target) into this
repository as a composite build — "Shape B".** Fallback if the bring-up fails its stability gate:
roll the thin JVM layer directly over `dev.onvoid.webrtc:webrtc-java` ("Shape C") — the Stage-1
abstraction work is identical either way, so nothing is lost by trying the fork first.

### Fork facts verified 2026-09-13 (measured from its sources, not its README)

- **Real fork of `com.shepeliev:webrtc-kmp`** with a `jvm()` target; 27 JVM implementation files
  in `webrtc-kmp/src/jvmMain` (PeerConnection, tracks, MediaDevices, DataChannel, …). Same
  `com.shepeliev.webrtckmp` package + API surface — common code compiles unchanged.
- **Published NOWHERE.** Its build publishes via the author's own Sonatype credentials with
  `version = env VERSION ?: "0.0.0"`; Maven Central's `com.shepeliev:webrtc-kmp` is the ORIGINAL
  (27 versions, latest 0.125.9). Hence Shape B's composite build — no publication involved.
- **Unmaintained:** last push 2024-11-15, 0 stars, 1 fork. The claim that its author "tested
  Windows desktop ↔ Android, video/screenshare/audio all worked" appears NOWHERE in the repo —
  treat as unverified; our own bring-up is the test.
- **Backend skew:** it pins `dev.onvoid.webrtc:webrtc-java:0.8.0` (2023-10-14). webrtc-java is
  actively maintained — v0.17.0 released 2026-09-13 (libwebrtc branch 7977 = Chrome M152;
  Windows ARM64 added in 0.17.0). **The D12-authorized exception to R10** is bumping the fork's
  catalog `0.8.0 → 0.17.0` and fixing whatever the wrapper churn breaks.
- **Android side stays M125:** the fork's Android/iOS SDK pins are `125.6422.05` — exactly what
  we resolve today, so the Android release path does not move.
- **No JVM screenshare in the wrapper** (its own feature table shows no JVM checkmark) —
  webrtc-java's native layer HAS desktop capture (`DesktopCapturer` since ≤0.8; Wayland/PipeWire
  in 0.16.0), the KMP wrapper just never exposed it.

### 📌 FUTURE FEATURE (do not lose this): desktop screen sharing

Screen sharing on desktop is a **planned future feature, deliberately out of scope for this
phase**. The native layer already exists (webrtc-java `DesktopCapturer`, Wayland/PipeWire since
0.16.0); what is missing is exposure through the vendored wrapper's KMP API (its own feature
table shows screen capture with NO JVM checkmark). When implemented, it lands as a wrapper
addition in `third_party/webrtc-kmp` (a JVM `ScreenCaptureTrack` actual over
`dev.onvoid.webrtc.desktop.*`) plus a desktop source-picker UI — a phase file of its own,
authored when the human schedules it. This file's conversion must NOT be shaped in a way that
forecloses it (keep the track-abstraction seams wide enough for a capture-backed track).

## Verified starting state (census taken 2026-09-13, measured not assumed)

### `:core:calling` — 10 files

| File | Lines | Platform pins |
|---|---|---|
| `model/FlashCallModels.kt` | 198 | **none — pure Kotlin** |
| `protocol/CallFrameCodec.kt` | 235 | **none — pure** |
| `protocol/CallWireFrame.kt` | 125 | **none — pure** |
| `CallQualityGovernor.kt` | 213 | **none — pure** |
| `FlashCalling.kt` | 185 | **none — pure** |
| `CallSdp.kt` | 278 | **none — pure** |
| `CallCoordinator.kt` | 560 | **none — pure Kotlin** (host seams: `sendFrame`, `isTrustedPeer`, `onCallLog`, …) |
| `FlashCallSession.kt` | 1,442 | `org.webrtc.Priority`, `org.webrtc.RtpParameters.DegradationPreference` + webrtc-kmp imports |
| `FlashGroupCallSession.kt` | 1,030 | same two raw `org.webrtc.*` imports |
| `FlashWebRtcEngine.kt` | ~200 | **Android by nature**: `JavaAudioDeviceModule`, `AudioRecord`/`MediaRecorder.AudioSource` (ERROR-032 probe), `AudioAttributes`/`AudioFormat`/`AudioManager`, `Context`, `Manifest` |

Tests: 5 suites in `src/test` (`CallQualityGovernorTest`, `CallSdpTest`, `FlashCallSessionTest`,
`FlashGroupCallSessionTest`, `protocol/CallFrameCodecTest`) — **zero platform imports** (verified
by grep), i.e. the test suites are commonTest-eligible as-is under any option.

Build: `com.android.library` + `maven-publish` (single `release` publication, artifactId
`core-calling`), `explicitApi()`, `api(libs.webrtc.kmp)` + `api(project(":core:common"))` +
`api(libs.kotlinx.coroutines.core)`. `compileDebugKotlin` is on the R3 canonical line since
2026-09-13 and green.

### `:ui:callui` — 1 file

`FlashCallScreen.kt` (948 lines): `android.util.Log` (4 call sites, one import), `androidx.activity
.compose.BackHandler` (the `FlashBackHandler` shim from Phase 19 exists and its jvm actual is a
no-op), ~49 `androidx.compose.*` imports (legal common under the CMP namespace), and the
**WebRTC render surface**: `com.shepeliev.webrtckmp.VideoTrack`/`WebRtc` + raw
`org.webrtc.SurfaceViewRenderer`/`RendererCommon` (~10 sites) — the webrtc-renderer-lifetime
memory documents why that renderer code is subtle (EglRenderer.release() is terminal).
Depends on `:ui:theme` (multiplatform since 18) and names `FlashIconSpec` (whose `drawableRes`
became `DrawableResource` in 18 — this module already took that fix).

### Consumers

`:app`'s `DiscoveryEngineHolder` builds `CallCoordinator` with host lambdas; `:core:engine`
reaches `FlashCalling` through `compileOnly` (the no-`NoClassDefFoundError` discipline, ADR-033);
`:desktop` deliberately does not depend on `:core:calling` (no JVM variant — ERROR-049).

---

## Execution — D12 Shape B, in three stages

> Stages are ordered so nothing is wasted if the fallback triggers: Stage 1 is identical under
> Shape B and Shape C (the direct webrtc-java layer). The stability gate at the end of Stage 2
> decides which way Stage 3 goes.

### Stage 1 — signal the module (was Option A1–A5; unchanged by D12)

### A1 — Convert `:core:calling` to KMP (android + jvm targets)

Swap `com.android.library` for the KMP pair exactly as every converted module does
(`kotlin("multiplatform")` + `com.android.kotlin.multiplatform.library`; see
`core/engine/build.gradle.kts` for the canonical shape — `localDependencySelection`,
`withHostTest { }`, R3.1 task names, the artifactId-preserving publication block with
`core-calling` kept as the root coordinate + `-android`/`-jvm` children).

**Critical:** the `api(libs.webrtc.kmp)` edge **cannot go in commonMain** (F1: no JVM variant —
it is the ERROR-049 wall). It stays on `androidMain` (webrtc-kmp's Android variant is what
ships today). This is the same shape as `:core:engine`'s `compileOnly(project(":core:calling"))`
placement and the reason this conversion is signaling-only.

### A2 — Move the six pure files to `commonMain`

`FlashCallModels.kt`, `CallFrameCodec.kt`, `CallWireFrame.kt`, `CallQualityGovernor.kt`,
`FlashCalling.kt`, `CallSdp.kt` — byte-for-byte relocations (R2's relocation discipline: no
edits; `explicitApi()` visibility already present). Verify with the R3 relocation cross-check:
the Android test count must come back **unchanged**.

### A3 — Split the session files' two `org.webrtc.*` imports

`Priority`/`DegradationPreference` set per-track audio/video priority (R8-adjacent behaviour —
users hear the difference). Under Option A these files **stay in androidMain**, so the imports
stay legal. The split to an `expect`/`actual` seam is **Option B's** first media-adjacent step
(B6), not A's.

`CallCoordinator.kt` moves to `commonMain` (pure Kotlin; its constructor types are all
common — `CallWireFrame`, `FlashCallLogEntry`, lambdas, `FlashTimeSource`).

### A4 — Move the 5 test suites to `commonTest`

They are platform-free today (verified). Convert JUnit 4 assertions to `kotlin.test` per the
established pattern (Phase 11/20); both targets then run them (R3.1: a commonMain class with no
jvmTest run is only compiled, not executed).

### A5 — Gate

- `:core:calling:compileKotlinJvm` + `:core:calling:compileAndroidMain` +
  `:core:calling:testAndroidHostTest` + `:core:calling:jvmTest`
- `:app:assembleDebug` (the Android consumer path — `DiscoveryEngineHolder`'s construction of
  `CallCoordinator` must compile unchanged)
- `:ui:callui:compileDebugKotlin` (still AGP; on the R3 line already)
- `publishToMavenLocal` → the tree gains `core-calling-jvm` + the root coordinate becomes the
  KMP umbrella (same `.module` shape as every converted module; **release note: `core-calling`
  becomes three artifacts** — the Phase 24 list grows)
- R6 gate scans on the new commonMain (the three commands from CONVENTIONS, incl. Defect A/B/C
  forms)

**Honest scope label for the log:** "signaling + call model on both targets; **no desktop
audio/video yet** — desktop media is Stage 2/3."

### Stage 2 — bring up the vendored fork (Shape B proper; ADR-034)

- **S2a — vendor:** copy `aschulz90/webrtc-kmp` (default branch `main`, last push 2024-11-15)
  into `third_party/webrtc-kmp/` as a plain source copy (not a submodule — atomic commits, no
  second remote to manage; Apache-2.0 licence + attribution files stay in place verbatim).
- **S2b — trim targets:** delete the fork's iOS (`iosX64`/`iosArm64`/`iosSimulatorArm64`),
  `js`, and `wasmJs` targets from its build script — every target removed is one never built or
  fixed. Remaining: `androidTarget` + `jvm()`. Its Android SDK pin (`125.6422.05`) stays — the
  Android release path must not move (R10 baseline preserved).
- **S2c — composite build:** one line in `settings.gradle.kts` —
  `includeBuild("third_party/webrtc-kmp")`. The fork declares the SAME coordinates as the
  original (`com.shepeliev:webrtc-kmp`), so Gradle's dependency substitution redirects our
  existing `api(libs.webrtc.kmp)` edges automatically. No version-catalog change, no
  publication, no JitPack (JitPack is NOT viable here: KMP multi-target builds on JitPack's
  Linux runners fail or need target-skipping gymnastics; the fork is unpublished anyway).
- **S2d — bump the backend (the D12-authorized R10 exception):** in the fork's
  `gradle/libs.versions.toml`, `webrtc-java-sdk = "0.8.0"` → `"0.17.0"`. Compile
  `webrtc-kmp/src/jvmMain` and fix the churn. **Stability gate:** if the 27 files need more
  than mechanical adaptation (rewritten APIs, missing natives, structural changes), STOP —
  that is the trigger for Shape C (thin own layer over webrtc-java), and Stage 1 carries over
  untouched.
- **S2e — re-type the two raw `org.webrtc.*` touch points** (`Priority`,
  `RtpParameters.DegradationPreference` in `FlashCallSession`/`FlashGroupCallSession`) onto an
  `expect`/`actual` priority seam so the session files can move toward commonMain. There is NO
  `org.webrtc` package on the JVM target (webrtc-java is `dev.onvoid.webrtc.*`) — these two
  imports are the last compile blockers for a JVM variant of the session files.

### Stage 3 — desktop media + UI (was B7–B8)

- **S3a — desktop `FlashWebRtcEngine` equivalent** over webrtc-java's audio module —
  **this is new security/latency-sensitive code**; the ADR-025 hardware-AEC gating and
  ERROR-032 probe discipline do not transfer mechanically and the desktop equivalent needs its
  own rationale (Java Sound has no hardware AEC path; libwebrtc's software APM is the whole
  story on desktop). `FlashWebRtcEngine` itself stays androidMain verbatim (R8).
- **S3b — `:ui:callui` conversion** — `Log` → `FlashLog` facade (Phase 03 pattern),
  `BackHandler` → the `FlashBackHandler` shim, and the renderer surface behind a video-display
  seam (the `FlashVideoSurface` shim exists in `:ui:platform-shims`; its jvm actual is where a
  desktop renderer lands — the webrtc-renderer-lifetime invariants apply to any renderer we
  add). Publishes as `ui-callui` + children.
- **Explicitly OUT of scope (future feature, see the box above):** desktop screen sharing.

## Do NOT

- **Do NOT run the stages out of order.** Stage 1 first (it is the fallback insurance), then
  the Stage-2 gate decides the path.
- **Do NOT put `api(libs.webrtc.kmp)` (or any webrtc dependency) in commonMain** until the
  vendored fork supplies a JVM variant through substitution — then commonMain becomes legal
  because BOTH variants exist. The build failure is the guard, not the signal.
- **Do NOT touch `FlashWebRtcEngine`'s ADM configuration** (ADR-025 / ERROR-032) — R8. It stays
  androidMain verbatim.
- **Do NOT bump anything else under R10.** The ONLY authorized version movement is the fork's
  `webrtc-java-sdk` 0.8.0 → 0.17.0 (D12/ADR-034). The fork's Android/iOS SDK pins do not move.
- **Do NOT delete or modify the fork's licence/attribution files** when vendoring (Apache-2.0
  obligations).
- **Do NOT regress the Android release**: `core-calling`'s 1.1.0 consumers must resolve
  unchanged (root coordinate preserved by the publication block, exactly as the other twelve
  conversions did; the Android variant must stay M125-equivalent).
- **Do NOT promise desktop screen sharing from this phase** — it is a documented future feature
  requiring its own phase file; this phase only keeps its seams open.

## Rollback

Each sub-step is a separate commit (R1). `git revert` the A1 build-file swap restores the AGP
module; the pure-file relocations revert independently. The R3 gate line already carries the
module both ways (it used `compileDebugKotlin` before conversion and the R3.1 names after).

## Log entry (mandatory)

One entry per R9: D12 (with the ADR-034 pointer), the sub-steps run, the honest scope label,
the test-count arithmetic (relocation cross-check per R3), the publication tree delta
(`core-calling` root → umbrella + `-jvm`), the Stage-2 stability-gate verdict (fork survived or
Shape C triggered), and — after Stage 1 only — the explicit statement that desktop media
remains absent pending Stages 2–3.
