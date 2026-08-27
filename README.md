<p align="center">
  <img src="art/flash-icon.png" alt="Flash" width="120" height="120">
</p>
<p align="center">
  <img src="art/flash-wordmark-dark.png#gh-light-mode-only" alt="Flash" height="52">
  <img src="art/flash-wordmark-light.png#gh-dark-mode-only" alt="Flash" height="52">
</p>

# Flash

**Offline, LAN peer-to-peer file transfer & messaging for Android — no server, no
internet, no account.** Flash discovers nearby devices over your local network
(Wi-Fi, or a phone hotspot), opens a direct encrypted channel between them, and
streams files and chat straight across. Nothing leaves the local network; there is
no backend to run and nothing to sign up for. Free and open source under Apache-2.0.

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
    implementation("com.github.Kali452345.Flash:core-engine:v1.0.0")
}
```

The install snippet targets the `v1.0.0` tag. See [Published modules](#published-modules) if
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

## Permissions

Core modules ship **zero** permissions in their manifests — nothing is force-merged
into your app. The consuming app must declare what the LAN/discovery stack needs in
its own `AndroidManifest.xml`.

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

Flash needs **no location permission**: discovery uses Android NSD (mDNS), not a
Wi-Fi/BLE scan, so no `ACCESS_FINE_LOCATION` is required.

## Compatibility

| Axis | Requirement |
|---|---|
| **minSdk** | 24 (Android 8.0) |
| **compileSdk (library)** | 35 |
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
ABIs with `ndk { abiFilters(...) }` / an ABI split in your app.

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

A LAN-only, no-database transfer app can depend on just `core-transfer`,
`core-network`, and `core-discovery`, skipping the SQLCipher native libraries entirely.

## License

Flash is licensed under the [Apache License 2.0](LICENSE). Copyright The Flash
Project. You are free to use, modify, and distribute it, including commercially,
under the terms of that license.
