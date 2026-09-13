# Phase 25 (TBD-number) — Calling stack on desktop (`:core:calling` + `:ui:callui`)

**Status:** AUTHORIZED (D11 = Option B, 2026-09-05), RESEARCH DONE (2026-09-12), **EXECUTION
BLOCKED ON THE HUMAN'S A–D PICK** — see "The decision this phase waits on".
**Blocked by:** nothing in the phase graph (15/16 are done/built; the README's "must not be
inserted ahead of 15/16" is satisfied) — but its *first execution sub-step cannot be chosen*
until the A–D decision below is answered, because the options determine the module shape.
**Risk:** HIGH — `:core:calling` is the WebRTC module; R8-adjacent (the ADR-025 audio path), and
the published `core-calling` coordinate is already consumed at 1.1.0.
**Decisions relied on:** D11 = B (in scope, research first — discharged), D1 = B (strict
commonMain), R5 (plain `jvm()`), R8 (crypto/security + ADR-025 audio behaviour untouchable),
R10 (no version bumps — **and no new dependency adoptions without a human decision**).

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

## The decision this phase waits on (the human's)

Presented in full in the research report; summarized as the four execution shapes:

| Option | Shape | What execution looks like | What it needs from the human |
|---|---|---|---|
| **A — Signaling-only conversion** | Wire codecs, call model, SDP, quality governor, and `CallCoordinator`'s state machines move to `commonMain` behind the existing host seams; `FlashWebRtcEngine` + the two session files stay androidMain; **desktop gets call-model classes with no media** (can ring/connect/exchange SDP+ICE text, cannot capture or render) | Sub-steps A1–A5 below | Nothing further — executable now |
| **B — Adopt a plain-JVM WebRTC artifact** | A (or its subset) + a `jvmMain` media actual over e.g. `io.github.webrtc-sdk`'s JVM flavour; real desktop audio/video eventually | Sub-steps A1–A5 then B6–B8 | **A new dependency decision**: version movement + licence review + maintenance assessment (the same class of input as D5=C's driver questions). R10 forbids the agent making it |
| **C — Defer the conversion** | Nothing converts; the R3 gate line (already wired 2026-09-13) keeps measuring the widening gap | None — close this phase as DEFERRED and revisit | A "not now" |
| **D — Reopen D11 (drop desktop calling)** | Nothing converts; D11's answer is amended | None — amend DECISIONS.md | An explicit reversal of the 2026-09-05 answer |

**The report recommends nothing**; D11's own text is why — "WebRTC on desktop is not a small
assumption to make silently — that is exactly why this is a decision and not an agent judgement
call."

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

## Execution — Option A (signaling-only; the only option executable without further input)

> If the human picks B, run A first (B contains A), then continue to B6. If C/D, close this
> phase as DEFERRED/DROPPED with a one-paragraph log entry and no code change.

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
audio/video** — desktop media is Option B, gated on a dependency decision."

### B6–B8 — Option B additions (only after the human's dependency decision)

- **B6:** re-type the two raw `org.webrtc.*` touch points onto the chosen JVM artifact's
  surface (or an `expect`/`actual` priority seam) and re-home the session files into
  `commonMain` + per-target media actuals.
- **B7:** a desktop `FlashWebRtcEngine` equivalent over the chosen artifact's audio module —
  **this is new security/latency-sensitive code**; the ADR-025 hardware-AEC gating and
  ERROR-032 probe discipline do not transfer mechanically and the desktop equivalent needs its
  own rationale (Java Sound has no hardware AEC path; libwebrtc's software APM is the whole
  story on desktop).
- **B8:** `:ui:callui` conversion — `Log` → `FlashLog` facade (Phase 03 pattern),
  `BackHandler` → the `FlashBackHandler` shim, and the renderer surface behind a video-display
  seam (the `FlashVideoSurface` shim exists in `:ui:platform-shims`; its jvm actual is where a
  desktop renderer lands). Publishes as `ui-callui` + children.

## Do NOT

- **Do NOT begin any sub-step before the human answers A–D.** D11's answer is explicit that the
  research reports *before* any conversion is proposed; this file is the proposal, and the
  pick is not the agent's.
- **Do NOT put `api(libs.webrtc.kmp)` (or any webrtc dependency) in commonMain** — it is the
  F1 wall; the build failure is the guard, not the signal.
- **Do NOT touch `FlashWebRtcEngine`'s ADM configuration** (ADR-025 / ERROR-032) — R8. It stays
  androidMain verbatim under every option.
- **Do NOT adopt the JVM webrtc artifact under Option B without the human's explicit
  dependency/licence decision recorded in DECISIONS.md** (R10; the same precedent as D5=C's
  driver questions).
- **Do NOT regress the Android release**: `core-calling`'s 1.1.0 consumers must resolve
  unchanged (root coordinate preserved by the publication block, exactly as the other twelve
  conversions did).
- **Do NOT count this phase as delivering desktop calls** under Option A — a desktop that rings
  but cannot carry media is signaling parity, and the log entry must say so.

## Rollback

Each sub-step is a separate commit (R1). `git revert` the A1 build-file swap restores the AGP
module; the pure-file relocations revert independently. The R3 gate line already carries the
module both ways (it used `compileDebugKotlin` before conversion and the R3.1 names after).

## Log entry (mandatory)

One entry per R9: the option the human picked (with the DECISIONS.md record), the sub-steps
run, the honest scope label, the test-count arithmetic (relocation cross-check per R3), the
publication tree delta (`core-calling` root → umbrella + `-jvm`), and — under Option A — the
explicit statement that desktop media remains absent pending the B decision.
