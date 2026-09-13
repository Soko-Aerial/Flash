# Research report — WebRTC for desktop JVM (the D11-mandated pre-conversion step)

**Date:** 2026-09-12
**Author:** Claude Code (glm-5.3-free), autonomous per the session /goal
**Status:** RESEARCH ONLY — no conversion is proposed from this document. Per D11 = Option B
(2026-09-05, `DECISIONS.md` + README): `:core:calling` and `:ui:callui` ARE in desktop scope, and
the phase that converts them **must open with this research and report its findings before
proposing any conversion.** This document is that report; the phase file for the calling stack
does not exist yet and must be written on top of these findings, not around them.

---

## The question D11 asks

Can `:core:calling` (currently a plain `com.android.library` AGP module, 10 files / 4,047 lines,
`explicitApi()`) and `:ui:callui` (one 948-line Compose screen, also AGP) be brought to the
desktop JVM under the migration's fixed constraints — D1 = B strict `commonMain`, R5 plain
`jvm()`, R10 no version bumps, R8 bit-identical behaviour — and if so, along which path?

## Findings, measured against the actual repo and artifacts

### F1 — The current WebRTC dependency publishes NO desktop-JVM target. (Hard fact, not opinion.)

The engine behind every call is `com.shepeliev:webrtc-kmp:0.125.11` (`core/calling/build.gradle.kts:60-61`,
`api`-scoped, ADR-025). Its published Gradle module metadata — read directly from the artifact
cached on this machine at
`~/.gradle/caches/modules-2/files-2.1/com.shepeliev/webrtc-kmp/0.125.11/.../webrtc-kmp-0.125.11.module` —
enumerates **exactly these targets**: `android` (as `release*Elements-published`), `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `js`, `wasmJs`, plus the `metadata` root. **There is no `jvm`
variant of any kind** — no `jvmApiElements`, no `-jvm` module in the metadata or the POM, and the
local cache (which this build populated by resolving the Android variant) holds only
`webrtc-kmp-android`.

The webrtc-kmp README (upstream) confirms the platform matrix: Android, iOS, JS/Wasm. Desktop JVM
is a long-standing open request in that project, not a shipped target.

**Consequence:** `:core:calling` cannot even declare a `jvm()` target today — its `api`
dependency has no JVM variant to resolve, so `compileKotlinJvm` fails at dependency resolution
(the same variant-selection wall ERROR-049 documented for `:core:ptt`/`:core:calling` in
`core/engine/build.gradle.kts:139–151`). The desktop scope D11 = B authorizes is therefore
**blocked at the dependency level, not the code level.**

### F2 — The module's own code is Android-pinned in exactly four files, but the pins are the whole product.

Census (`grep -l` over `core/calling/src/main`): 4 of 10 files import `android.*`/`androidx.*`/`java.*`:

| File | Lines | Platform pins |
|---|---|---|
| `FlashWebRtcEngine.kt` | ~200 | The entire ADR-025 audio path: `JavaAudioDeviceModule` (low-latency playout, hardware AEC/NS gating, `AudioAttributes`, `AudioFormat`, the ERROR-032 mic-source probe over `AudioRecord`/`MediaRecorder.AudioSource`), `Context`, `Manifest` permission checks, `SharedPreferences` probe cache |
| `FlashCallSession.kt` | 1,442 | `org.webrtc.Priority` + `org.webrtc.RtpParameters.DegradationPreference` (raw libwebrtc-Java types reached *around* webrtc-kmp) |
| `FlashGroupCallSession.kt` | 1,030 | Same two raw `org.webrtc.*` types |
| `CallCoordinator.kt` | 560 | (Android-adjacent host contracts; the coordinator itself is plain Kotlin + coroutines over the seam lambdas) |

The signal-flow half (`CallSdp`, `FlashCalling`, `model`, `protocol` packages — call setup/teardown
state machines, SDP/ICE wire codecs, call-quality governor) is Kotlin+coroutines only, but
**media plumbing is the product**: a converted `commonMain` without a working `PeerConnection` +
`getUserMedia` path on JVM is not a calling module, it is a signaling stub.

### F3 — Raw `org.webrtc.*` usage means even a hypothetical webrtc-kmp JVM target would not be enough.

`FlashCallSession`/`FlashGroupCallSession` import `org.webrtc.Priority` and
`org.webrtc.RtpParameters.DegradationPreference` directly — types from
`io.github.webrtc-sdk:android` (the BSD-3 native wrapper webrtc-kmp re-exports), **not** from
webrtc-kmp's multiplatform surface. Any desktop path must either (a) provide those exact classes
on the JVM classpath from some other artifact, or (F1 makes this the operative case) (b) the
conversion re-types those two touch points onto the KMP surface or an expect/actual seam. Two
imports, but they set audio/video priority per track — R8-adjacent behaviour (the traffic
prioritization users can hear/see).

### F4 — The desktop paths that DO exist for WebRTC on JVM (surveyed, none droppable-in)

1. **webrtc-kmp itself** — no JVM target (F1). Not a path.
2. **`io.github.webrtc-sdk:webrtc-java`** (the same libwebrtc wrapper family, JVM flavour) — exists
   upstream, but it is a **plain Java library, not KMP**: it would land in `jvmMain` only, forcing
   the D1=B-forbidden shape where common code cannot express the media API. Its version is also
   *newer* than the 0.125 webrtc-kmp wraps (R10: any adoption is a version decision for a human).
3. **Raw embedded libwebrtc via JNI** — weeks of work, no maintainable seam, no licence/ABI
   review. Not a serious option for this migration; listed for completeness.
4. **Kotlin/Native + a future webrtc-kmp release** — depends on upstream shipping a target that
   does not exist; speculative.

**Net: there is no artifact available today that gives `:core:calling` a desktop-JVM media path
without either adopting a new plain-Java dependency (a human version/licence decision under R10)
or upstream movement.**

### F5 — What IS convertible now, and what that would prove.

The signaling half — `CallCoordinator`, `CallSdp`, `CallQualityGovernor`, `FlashCalling`,
`model/`, `protocol/` — has no `android.*`/`java.*` imports beyond the two `org.webrtc.*` pins
(F2), and the coordinator is already host-decoupled (`sendFrame`/`isTrustedPeer`/`onCallLog`
lambdas; the engine facade routes inbound `FLASH_CALL` text through
`FlashEngine.onInboundCallText` with the `compileOnly` no-`NoClassDefFoundError` discipline,
ADR-033). A KMP conversion could therefore move the wire codecs + state machines to `commonMain`
and put media behind an expect/actual seam — **proving signaling parity on both targets while
desktop media stays honestly absent**. That is a real, testable conversion shape consistent with
R2 (never force-compile), but it is a **partial** conversion, and its honest scope label is
"signaling + call model; no desktop audio/video" — a desktop that can ring, connect and exchange
SDP/ICE frames but cannot capture or render media.

## Options for the human (the phase file must present these; this report does not pick)

- **Option A — Signaling-only conversion now.** Convert codecs/state/coordinator to commonMain
  behind the existing seams; keep `FlashWebRtcEngine` + the two `org.webrtc.*` touch points in
  androidMain; desktop gets call-model classes with no media. Cheap, honest, unblocks nothing
  user-visible on desktop.
- **Option B — Adopt a JVM WebRTC artifact (e.g. `webrtc-sdk`'s JVM flavour) behind a jvmMain
  actual.** Real desktop calls eventually; requires a **new dependency decision** (R10 — version
  movement + licence review + maintenance assessment, all named as human inputs by the same
  precedent as D5=C's driver questions).
- **Option C — Defer the calling-stack phase until upstream ships a JVM target.** Zero cost now;
  leaves `:ui:callui` unconverted and the Phase 19-era compile-gap widening (README's own words).
- **Option D — New decision: drop calling from desktop scope** (reopen D11). Cheap; contradicts
  the 2026-09-05 answer, so it is explicitly a human call.

## What this report recommends the phase file do

Whichever option the human picks, two things are true now:

1. The phase file for the calling stack must open by restating F1 as the constraint it cannot
   code around, and present options A–D. It must NOT present a conversion as executable without
   answering the F4 dependency question first.
2. Until the phase exists, `:ui:callui:compileDebugKotlin` stays the cheap regression gate it has
   been since Phase 18 (per README: wire it into the documented R3 command line). It is compiling
   against the multiplatform `:ui:theme` today; that must keep holding.

## Verification of this report's claims

- F1: `grep '"name"' webrtc-kmp-0.125.11.module` → 22 variants, all android/ios/js/wasmJs/metadata;
  zero `jvm*`. POM lists `webrtc-kmp-android` and `webrtc-kmp-js` only.
- F2: file census by grep; line counts by `wc -l`.
- F3: `grep "import org.webrtc" core/calling/src/main` → 2 files × 2 imports.
- F4: no candidate artifact exists in this repo's lockfile/caches other than the Android one;
  the JVM flavour names are upstream-only today.
