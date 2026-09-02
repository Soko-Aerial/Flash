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
    implementation("com.github.Kali452345.Flash:core-engine:v1.1.0")
}
```

The install snippet targets the `v1.1.0` tag. See [Published modules](#published-modules) if
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

## Voice & video calls

Calling is **not** part of `Flash.create`. `FlashEngine` has no `calls` property, because calling
needs three things only an app can supply: a signaling channel the app already owns, runtime
microphone/camera grants, and a `microphone|camera` foreground service declared in its own manifest.
So you depend on `core-calling` directly:

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-calling:v1.1.0")
    implementation("com.github.Kali452345.Flash:ui-callui:v1.1.0")   // optional in-call screen
}
```

Signaling is plain text (`FLASH_CALL|…`), so any duplex text transport works — the Flash WebSocket
mesh is simply the one the sample app uses. Wire two seams:

```kotlin
// outbound: hand every frame the module emits to your transport
val calling: FlashCalling = /* CallCoordinator(sendFrame = { peerId, text -> … }) */

// inbound: offer every received text frame; true means it was a call frame and was consumed
if (calling.onInboundText(peerId, text)) return
// and when a transport dies, so the call does not wait for frames that can't arrive:
calling.onSignalingLost(peerId)
```

`calling.activeCall` is a `StateFlow<FlashCallUiState?>` — non-null while a call is in flight, so a
navigation layer pushes and pops its call route off that one flow. Pair it with
`calling.media` and hand both to `FlashCallScreen` from `ui-callui`. Grant the microphone (and, for
video, the camera) **before** calling `startCall`/`accept`: a microphone opened in the wrong audio
mode does not switch later.

**Cost:** `core-calling` bundles native WebRTC — roughly 30 MB per ABI. Nothing else in Flash pulls
it in, so an app that does not call simply does not depend on it. See
[`docs/architecture/public-api.md`](docs/architecture/public-api.md) §7 for the full contract.

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
**WebRTC** on top of that (~30 MB per ABI) and is the reason calling is a separate artifact rather
than part of the umbrella.

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
| `core-calling` | Experimental — shipped | No (but ~30 MB WebRTC) | Voice/video calls. **Not** reachable via `Flash.create` — see [Voice & video calls](#voice--video-calls). |
| `ui-theme` | Experimental — shipped | No | The Flash design system (colors, typography, motion, icons) for Compose. |
| `ui-chat` | Experimental — shipped | No | The chat list / conversation / transfers / settings UI. Stateless; add `core-messaging` for its state types. |
| `ui-callui` | Experimental — shipped | No (`api`s `core-calling`) | `FlashCallScreen`, the full-screen in-call surface. |

A LAN-only, no-database transfer app can depend on just `core-transfer`,
`core-network`, and `core-discovery`, skipping the SQLCipher native libraries entirely.

The `ui-*` artifacts are Compose libraries and are deliberately stateless — every screen takes a
`*UiState` plus callbacks and holds no repository, so you can render Flash's UI over your own data
source, or take the engine and none of the UI. They build against `compileSdk 37` while the `core-*`
modules stay on 35.

## License

Flash is licensed under the [Apache License 2.0](LICENSE). Copyright The Flash
Project. You are free to use, modify, and distribute it, including commercially,
under the terms of that license.
