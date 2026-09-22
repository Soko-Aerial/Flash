<p align="center">
  <img src="art/flash-icon.png" alt="Flash" width="120" height="120">
</p>
<p align="center">
  <img src="art/flash-wordmark-dark.png#gh-light-mode-only" alt="Flash" height="52">
  <img src="art/flash-wordmark-light.png#gh-dark-mode-only" alt="Flash" height="52">
</p>

# Flash

**Offline, LAN peer-to-peer file transfer, messaging and calling for Android — no server, no
internet, no account.** Flash discovers nearby devices over your local network
(Wi-Fi, or a phone hotspot), opens a direct encrypted channel between them, and
streams files, chat and voice/video calls straight across. Nothing leaves the local network; there
is no backend to run and nothing to sign up for. Free and open source under Apache-2.0.

Built as a set of small Android library modules so you can take the whole engine or
just the transport pieces you need. Supports **Android 8.0 (API 24) and up**.

[![](https://jitpack.io/v/Kali452345/Flash.svg)](https://jitpack.io/#Kali452345/Flash)

## Install

Flash is distributed via [JitPack](https://jitpack.io). Add the repository, then the
one umbrella artifact (`core-engine`) that wires everything together:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Kali452345.Flash:core-engine:v2.0.0-beta")
}
```

The install snippet targets the `v2.0.0-beta` tag. See [Published modules](#published-modules) if
you want only the lightweight transport pieces without the encrypted database.

## Quick start

One call builds and starts the whole engine — discovery, advertising, the network
layer, and transfers all share a single coroutine scope. This snippet is compiled
verbatim as `sample/consumer/.../QuickStart.kt`, so it never drifts from the real API:

```kotlin
import com.transfer.flash.core.engine.Flash
import com.transfer.flash.core.engine.FlashConfig
import kotlinx.coroutines.flow.first

// Call off the main thread: create() opens the encrypted database synchronously.
suspend fun sendFirstFileToAnyPeer(context: Context, fileUri: String, fileSize: Long) {
    // 1. One call wires + starts discovery, advertising, network, and transfers.
    val engine = Flash.create(context, FlashConfig(displayName = "My Device"))
    try {
        // 2. Suspend until a peer appears on the LAN, then take the first one.
        val peer = engine.discovery.discoveredEndpoints
            .first { it.isNotEmpty() }
            .first()

        // 3. Send. The receiver sees an OFFER it must accept (autoAcceptIncoming = false).
        engine.transfers.sendFile(
            targetDevice = peer.device,
            fileUri = fileUri,
            displayName = "photo.jpg",
            fileSize = fileSize,
        )

        // Observe live progress anywhere via the StateFlow:
        //   engine.transfers.activeTransfers.collect { list -> /* update UI */ }
    } finally {
        // 4. One off-switch: cancels the scope, stops radios, closes the DB. Idempotent.
        engine.close()
    }
}
```

### `FlashConfig` knobs

| Field | Default | Meaning |
|---|---|---|
| `displayName` | `null` | Friendly name advertised to peers; falls back to the stored device name. |
| `enableResume` | `true` | Persist transfer state so interrupted transfers resume across app restarts. |
| `autoAcceptIncoming` | `false` | `true` = accept inbound offers automatically; `false` (default) = gate each with `engine.transfers.acceptIncoming(id)` / `declineIncoming(id)`. |
| `receivedFilesDir` | `null` | Directory for received files; defaults to app-internal storage. |

## Lifecycle

`Flash.create(...)` owns a shared `CoroutineScope` plus the network, discovery, and
database resources, so the returned `FlashEngine` is a `Closeable`. **Always pair it
with `engine.close()`** — from `Activity.onDestroy`, `ViewModel.onCleared`, or a DI
scope teardown. `close()` cancels the scope, stops discovery/network, and closes the
encrypted database. It is idempotent, so calling it twice is safe.

## Developer Guide & Documentation

Flash includes a comprehensive, modular documentation suite located in [`docs/developer-guide/`](docs/developer-guide/README.md):

| Guide / Section | Description |
|---|---|
| [**Beginner's Guide**](docs/developer-guide/getting-started/beginner-guide.md) | Step-by-step setup, prerequisites, Maven coordinates, and complete "Hello World" connection & file transfer tutorial. |
| [**Architecture Overview**](docs/developer-guide/getting-started/architecture-overview.md) | Multi-module hierarchy, dependency boundaries, lifecycle management, and reactive state paradigms. |
| [**14 Per-Module Guides**](docs/developer-guide/README.md#1-documentation-map) | Dedicated documentation for each of the 10 `:core:*` and 4 `:ui:*` modules with exact signatures, threading, and code examples. |
| [**Ultra-Low Resource Devices (Scenario 1)**](docs/developer-guide/scenarios/scenario-1-ultra-low-resource.md) | Building for devices far more constrained than standard phones (<512MB RAM, IoT, POS, smartwatches, 2.4GHz radios) with memory caps and single-stream transfers. |
| [**Custom Extensions & Architecture (Scenario 2)**](docs/developer-guide/scenarios/scenario-2-custom-extensions.md) | Custom transports (Bluetooth/BLE, LoRa, USB OTG), custom storage sinks, HSM hardware cryptography, and UI whitelabeling. |
| [**Open Protocol Specification (Scenario 3)**](docs/developer-guide/scenarios/scenario-3-open-protocol-interop.md) | Complete wire protocol specification with runnable client samples in **Python**, **Rust**, and **Go**. |
| [**Practical Integration Examples**](docs/developer-guide/examples/) | Standalone module usage, full-stack application integration, and running Flash as a headless background daemon. |

## Performance Modes & Hardware Tiering (ADR-022)

Flash avoids hardcoded flagship assumptions by grading CPU, memory, radio airtime, and UI complexity through `FlashPerformanceMode` and `FlashTransferProfile`:

| Profile Tier | Target Hardware | Streams | Base Chunk | Queue Depths | Video Calls | Voice / PTT | Media Previews |
|---|---|---|---|---|---|---|---|
| **`LOW`** | <512MB RAM, IoT, POS, 2.4GHz radio | **1 stream** | 32 KB / 64 KB | 2 / 4 frames (<250 KB heap) | Disabled / Audio only | 60ms Opus DTX | File icons (skip heavy video thumbs) |
| **`MEDIUM`** | Mid-tier phones, older laptops | **2 streams** | 64 KB | 8 / 16 frames | 540p @ 24fps | 20ms Opus | 512px downsampled |
| **`HIGH`** | Flagships, desktop workstations | **4 streams** | 64 KB (adaptive to 1MB) | 16 / 64 frames (pipe saturation) | 1080p @ 30fps | 20ms Opus | Full 1024px + video keyframes |

## Voice & video calls

Calling is reachable through `Flash.create`, but it is **opt-in and host-built**. The facade owns
the seam — `engine.calls`, `engine.attachCalling(...)`, routing of inbound `FLASH_CALL` frames and
the signaling-recovery window it drives from the sessions it already observes — while the engine
itself is yours to construct, because a call needs three things only an app can supply: a signaling
channel (your `sendFrame`), runtime microphone/camera grants, and a `microphone|camera` foreground
service declared in your own manifest. So you depend on `core-calling` directly:

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-calling:v2.0.0-beta")
    implementation("com.github.Kali452345.Flash:ui-callui:v2.0.0-beta")   // optional in-call screen
}
```

`core-engine` declares `core-calling` as `compileOnly`, so the `FlashCalling` type is in the
facade's API but native WebRTC is **not** pulled in by the umbrella (see
[Published modules](#published-modules)).

Signaling is plain text (`FLASH_CALL …`), so any duplex text transport works — the Flash WebSocket
mesh is simply the one the sample app uses. Wire the outbound seam at construction and hand the
engine to the facade:

```kotlin
// outbound: every frame the module emits goes through your transport
val calling: FlashCalling = CallCoordinator(sendFrame = { peerId, text -> myTransport.send(peerId, text) })

// attach: from here the facade routes inbound FLASH_CALL frames to it and drives its
// signaling-recovery window from the live sessions it observes (ERROR-033) — you no longer
// hand-feed onInboundText / onSignalingLost yourself.
engine.attachCalling(calling)
```

With nothing attached, inbound call frames are recognized and dropped — they never reach the chat,
transfer or PTT parsers, and no calling class is loaded. `engine.detachCalling()` stops routing;
`engine.close()` detaches too. Detaching is deliberately not hanging up: the media, the audio route
and the foreground service are the host's, so ending a live call stays with your own
`calling.hangUp()`.

`calling.activeCall` is a `StateFlow<FlashCallUiState?>` — non-null while a call is in flight, so a
navigation layer pushes and pops its call route off that one flow. Pair it with
`calling.media` and hand both to `FlashCallScreen` from `ui-callui`. Grant the microphone (and, for
video, the camera) **before** calling `startCall`/`accept`: a microphone opened in the wrong audio
mode does not switch later.

**Cost:** `core-calling` bundles native WebRTC — roughly 30 MB per ABI. `core-engine` does not
declare it for a consumer, so an app that does not call simply does not depend on it. See
[`docs/architecture/public-api.md`](docs/architecture/public-api.md) §7 for the full contract.

## Push-to-talk (PTT)

PTT is **inside** the umbrella: `core-engine` declares `api(project(":core:ptt"))`, so a
`core-engine` consumer already has `core-ptt` on its classpath and needs no extra dependency. What
it is *not* is automatic — a live floor needs a microphone grant and a foreground service that only
your app's manifest can declare, so nothing is created until you ask for it:

```kotlin
// 1. Attach the seam. The facade supplies identity, trust and the live sessions; you supply policy.
val ptt: FlashPtt = engine.attachPtt(
    hasMicPermission = { checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED },
    isCallActive = { callTracker.isInCall },          // mic exclusivity: a call tears PTT down
    audioRateHz = { if (lowEndDevice) 8_000 else 16_000 },
) ?: return   // null only on a hand-assembled DefaultFlashEngine (no wiring to attach to)
```

Inbound frames are already routed for you — `Flash.create` dispatches `FLASH_PTT`/`FLASH_PTSS`
text and `PTT1` binary frames to the attached engine, and recognizes-and-drops them when nothing is
attached. On the way out:

```kotlin
ptt.onPttButton()                       // hardware key / UI press: toggles the floor
ptt.state.collect { /* Idle | Talking | Listening */ }
ptt.stats.collect { /* elapsed, RTT, loss, level, member count */ }
ptt.pings.collect { /* someone pressed PTT: tone + your own notification */ }
ptt.stopLocal()                         // notification "Stop"/"Leave" action
engine.detachPtt()                      // stops PTT, keeps the engine; engine.close() does this too
```

| Type | Role |
|---|---|
| `FlashPtt` | The whole contract: press, state/stats/notices/pings, inbound seams, leases, shutdown. |
| `PttSessionEngine` | The one implementation. Hosts construct it directly only when they own their own transport (the sample app does). |
| `PttPressOutcome` | `ACCEPTED`, `NO_PEERS`, `NO_MIC`, `CALL_ACTIVE`, `VOICE_NOTE_ACTIVE`. |
| `PttSessionStats` | `sessionId`, `role`, elapsed, RTT, loss, buffer depth, `amplitude01`, members. |
| `PttPingEvent` | A deduplicated inbound press: `eventId`, `fromDeviceId`, `senderName`, `sentAtMs`. |

Two host duties beyond the attachment: `ptt.acquireVoiceNoteLease()` before your own voice
recorder opens the mic (returns null when PTT or a call holds it — a lease only its owner can
release), and `ptt.onCallStarted()` when a call becomes active if your call engine is not the one
driving `isCallActive`. Frames are fail-closed like the rest of the protocol: a `from` that does not
match the authenticated transport peer, or an untrusted peer, is dropped — and still consumed, so
it never reaches the chat handlers. See
[`docs/architecture/public-api.md`](docs/architecture/public-api.md) §14 and
[`docs/protocol.md`](docs/protocol.md) for the wire format.

## Permissions

Flash's library modules ship **no `AndroidManifest.xml` at all** — nothing is force-merged into
your app, and there is no permission you did not write yourself. The consuming app declares what
it needs.

**Required** (discovery + transport will not work without these):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

| Permission | Why |
|---|---|
| `INTERNET` | Open TCP/WebSocket sockets on the local network (no internet egress is used). |
| `ACCESS_NETWORK_STATE` | Detect connectivity/interface changes to (re)bind discovery. |
| `ACCESS_WIFI_STATE` | Read Wi-Fi/hotspot state to pick the right interface to advertise on. |
| `CHANGE_WIFI_MULTICAST_STATE` | Acquire a multicast lock so NSD/mDNS discovery packets arrive. |

**Optional** — only if you run transfers with the screen off via a foreground service:

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

| Permission | Why |
|---|---|
| `WAKE_LOCK` | Keep the CPU awake so a transfer completes with the screen off. |
| `FOREGROUND_SERVICE` | Run the transfer as a foreground service (required on API 26+). |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground-service type for the connected-device case (API 34+). |
| `POST_NOTIFICATIONS` | Show transfer-progress notifications (runtime-requested on API 33+). |

**Only if you use `core-calling`** — voice/video calls need a microphone, a foreground service that
survives the screen turning off, and control of the platform audio route:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
```

```xml
<service
    android:name=".calling.YourCallService"
    android:exported="false"
    android:foregroundServiceType="microphone|camera" />
```

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Capture the local microphone. Runtime-requested; must be granted **before** `startCall`/`accept`. |
| `CAMERA` | Video calls only. Runtime-requested at call start. |
| `MODIFY_AUDIO_SETTINGS` | Put the platform into `MODE_IN_COMMUNICATION` and pick the output device. Install-time, no prompt. |
| `FOREGROUND_SERVICE_MICROPHONE` | FGS type for the in-call service (API 34+). |
| `FOREGROUND_SERVICE_CAMERA` | FGS type for a video call (API 34+). |

`MODIFY_AUDIO_SETTINGS` looks harmless and is not: without it `setMode`,
`setCommunicationDevice`/`setSpeakerphoneOn` and `startBluetoothSco` are all refused, and the call
silently runs on the *media* audio path — no hardware echo cancellation, a long playout buffer, and
a dead speaker button. Routing to an already-connected Bluetooth headset needs no `BLUETOOTH_*`
permission, so none is required.

**Only if you use push-to-talk** — a PTT session captures the microphone while talking and plays
audio while listening, and it must survive the screen turning off:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

```xml
<service
    android:name=".ptt.YourPttSessionService"
    android:exported="false"
    android:foregroundServiceType="microphone|mediaPlayback" />
```

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Capture the talker's microphone. Runtime-requested; the host reads it through the `hasMicPermission` lambda so a refusal becomes `PttPressOutcome.NO_MIC` instead of a silent session. |
| `FOREGROUND_SERVICE` | Run the live session as a foreground service (required on API 26+). |
| `FOREGROUND_SERVICE_MICROPHONE` | FGS type the talker claims (API 34+); without it a `microphone` promotion is refused. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | FGS type a listener claims (API 34+). A listener needs no runtime grant. |

Like calling, PTT ships **no manifest of its own** — the permission declarations and the service
entry above are yours. The module deliberately does not start that service: `FlashPtt` exposes the
session state and the host decides how to keep the process alive (the sample app starts its service
on the Idle→session edge and stops it on return to Idle). `POST_NOTIFICATIONS` is required only if
you post a session notification, and the platform must be able to promote the service on API 31+ —
a press that happens while the app is backgrounded cannot promote a `microphone` service there, so
hosts surface the app and let the user complete the press.

Flash needs **no location permission**: discovery uses Android NSD (mDNS), not a
Wi-Fi/BLE scan, so no `ACCESS_FINE_LOCATION` is required.

## Compatibility

| Axis | Requirement |
|---|---|
| **minSdk** | 24 (Android 8.0) |
| **compileSdk (library)** | 35 for `core-*`, 37 for `ui-*` |
| **AGP (your app)** | 9.3+ |
| **Gradle** | 9.5+ |
| **Kotlin** | 2.2+ |
| **JDK (to run the build)** | 17+ |
| **Language/bytecode target** | Java 11 |

> **Adoption ceiling:** the AGP 9.3 / Gradle 9.5 floor is the real gate — apps on the
> AGP 8.x line cannot currently build against these artifacts. The `compileSdk` was
> lowered to 35 for wider reach; a lower AGP floor would require a separate downgrade.

The `core-engine` and `core-persistence` artifacts bundle **SQLCipher** for the
encrypted database, which ships native `.so` libraries for four ABIs (`arm64-v8a`,
`armeabi-v7a`, `x86`, `x86_64`). To avoid that footprint, use the **transport-only**
path (`core-transfer` + `core-network` + `core-discovery`, no database), or restrict
ABIs with `ndk { abiFilters(...) }` / an ABI split in your app. `core-calling` adds native
**WebRTC** on top of that (~30 MB per ABI) and is the reason calling is a separate artifact that
`core-engine` does not put on your compile classpath — the facade declares it `compileOnly` and you
add it when you call.

## Published modules

Take the umbrella, or compose only the lightweight pieces:

| Artifact | Tier | Room/SQLCipher? | Use when |
|---|---|---|---|
| `core-engine` | **Supported — umbrella** | Yes | You want everything via `Flash.create` (recommended). |
| `core-common` | Supported — lightweight | No | Shared models/results (usually transitive). |
| `core-discovery` | Supported — lightweight | No | Peer discovery/advertising only. |
| `core-network` | Supported — lightweight | No | Connection/session transport only. |
| `core-transfer` | Supported — lightweight | No | Chunked file transfer without a database (DB-less resume off). |
| `core-security` | Supported — lightweight | No | Trust store / pairing primitives. |
| `core-persistence` | Supported — optional storage | Yes | Add cross-restart persistence to a lightweight setup. |
| `core-messaging` | Experimental — shipped (API may change) | Yes (transitive) | Chat repository; pulled in transitively by `core-engine`. Don't depend on it directly yet. |
| `core-calling` | Experimental — shipped | No (but ~30 MB WebRTC) | Voice/video calls. Reachable via `Flash.create` (`engine.attachCalling(...)`) — but the umbrella declares it `compileOnly`, so **add it yourself**; see [Voice & video calls](#voice--video-calls). |
| `core-ptt` | Experimental — shipped (API may change) | No | Push-to-talk floor, capture, playout and PTT wire codecs. Reachable via `Flash.create` (`engine.attachPtt(...)`) — see [Push-to-talk](#push-to-talk-ptt). |
| `ui-theme` | Experimental — shipped | No | The Flash design system (colors, typography, motion, icons) for Compose. |
| `ui-platform-shims` | Experimental — shipped | No | Platform seams (back handling, clipboard, file picking, permissions, image decode, audio playback, voice capture) that `ui-chat` compiles against. Transitive — depend on it only if you are reimplementing the chat UI. |
| `ui-chat` | Experimental — shipped | No | The chat list / conversation / transfers / settings UI. Stateless; add `core-messaging` for its state types. |
| `ui-callui` | Experimental — shipped | No (`api`s `core-calling`) | `FlashCallScreen`, the full-screen in-call surface. |

A LAN-only, no-database transfer app can depend on just `core-transfer`,
`core-network`, and `core-discovery`, skipping the SQLCipher native libraries entirely.

The `ui-*` artifacts are Compose libraries and are deliberately stateless — every screen takes a
`*UiState` plus callbacks and holds no repository, so you can render Flash's UI over your own data
source, or take the engine and none of the UI. They build against `compileSdk 37` while the `core-*`
modules stay on 35.

**Dependency shape inside the set:** `core-engine` `api`s every other `core-*` module — including
`core-ptt`, so an umbrella consumer gets PTT without naming it (it is declared on the Android target,
because `core-ptt` is an Android-only library and `:core:engine`'s desktop `jvm()` target cannot
resolve it). `core-calling` is the one exception: it is declared `compileOnly` on the Android
target, so `FlashCalling` is on `core-engine`'s *compile* classpath and in its public API, while a
consumer's runtime classpath stays free of `core-calling` and its ~30 MB-per-ABI WebRTC unless the
consumer adds the dependency itself — which any app that places a call does. `ui-chat` reaches
`ui-platform-shims` through an `implementation(project(...))`
dependency, which Gradle writes into the published metadata for the Android target as a runtime
dependency: that is why the shims module is published too, and why a `ui-chat` consumer resolves it
without asking for it.

### Desktop JVM consumers (KMP, since the migration)

Every `core-*` and `ui-*` module above is now a **Kotlin Multiplatform** library: the same root
coordinate that an Android consumer writes also serves a plain **desktop JVM** app. Variant-aware
resolution picks the per-target artifact automatically — `-android` for an Android build, `-jvm`
for a JVM build — so the dependency line is identical on both platforms:

```kotlin
// Desktop app/build.gradle.kts  (plugins { kotlin("jvm") })
dependencies {
    // Same umbrella coordinate as the Android snippet above:
    implementation("com.github.Kali452345.Flash:core-engine:v2.0.0-beta")
}
```

Gradle then resolves `core-engine-jvm` (and `core-common-jvm`, coroutines-jvm, …) from that root
coordinate's module metadata. This is proven in-repo by `sample/consumer-desktop`, a plain
`kotlin("jvm")` module with zero project dependencies whose compile gate is exactly this
resolution against the published tree.

On desktop, the `:desktop` module provides a complete Compose Desktop application shell bundling
`DesktopEngine`, Room encrypted SQLite database (`sqlite-jdbc-crypt`), system tray, single-instance
enforcement, and native Windows installers (`Flash-2.0.0.exe` and `Flash-2.0.0.msi`). Voice and video
calling on JVM is enabled via vendored multiplatform WebRTC.

## License

Flash is licensed under the [Apache License 2.0](LICENSE). Copyright The Flash
Project. You are free to use, modify, and distribute it, including commercially,
under the terms of that license.
