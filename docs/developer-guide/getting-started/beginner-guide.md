# Flash Beginner's Guide & Getting Started

This guide walks you through setting up your development environment, importing Flash libraries into your build, and building your first peer-to-peer file transfer and messaging application.

---

## 1. Development Prerequisites

To compile or consume Flash modules, ensure your environment meets the following toolchain baselines:

| Tool / Runtime | Required Version | Purpose |
|---|---|---|
| **JDK** | JDK 17 or JDK 21 | Host Java runtime and Gradle build execution |
| **Android SDK** | `compileSdk 35` (core) / `37` (UI), `minSdk 24` | Android platform libraries |
| **Kotlin** | `2.2.10` | Language compiler with KMP multiplatform support |
| **Gradle / AGP** | Gradle `9.5.0` (the repo wrapper), AGP `9.3.1` | Build system (a consumer only needs a Gradle version its own AGP supports) |
| **Android Studio / IntelliJ** | Ladybug / 2024.2+ | Recommended IDE with Compose preview support |

---

## 2. Project Setup & Dependency Configuration

Flash libraries are Kotlin Multiplatform modules with Android and desktop JVM variants. The same root
coordinate selects the right variant for each consumer.

### 2.1 Repository Setup

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // Flash artifacts (com.github.Soko-Aerial.Flash:<module>:v2.2.0-beta.3)
        // mavenLocal() // only for a local `publishToMavenLocal` build (group com.transfer.flash)
    }
}
```

### 2.2 Adding Flash Dependencies

Depending on whether you are building a full-featured application, a headless background service, or a custom UI, declare the required Flash coordinates in your `build.gradle.kts`:

#### Option A: Unified Engine (Recommended for most apps)
Use `core-engine` for the tutorial below. On Android it provides the `Flash.create` factory for discovery,
pairing, encrypted chat and transfers. Add the optional UI modules only if you want the Compose screens.
Calling and [radio](../modules/core/core-radio.md) have separate dependencies and require host setup.

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-engine:v2.2.0-beta.3")
    implementation("com.github.Soko-Aerial.Flash:ui-chat:v2.2.0-beta.3")     // Optional: If Jetpack Compose UI is needed
    implementation("com.github.Soko-Aerial.Flash:ui-theme:v2.2.0-beta.3")    // Design tokens and styling
}
```

#### Option B: Transport-only dependencies
Use these modules if you want discovery and transfers without the unified engine or its database.
Your host constructs and connects the transport components. This option does not provide `Flash.create`
or `engine.chats`; use Option A for the tutorial and for chat through the engine.

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-discovery:v2.2.0-beta.3")
    implementation("com.github.Soko-Aerial.Flash:core-network:v2.2.0-beta.3")
    implementation("com.github.Soko-Aerial.Flash:core-transfer:v2.2.0-beta.3")
}
```

---

## 3. "Hello World" Tutorial: Discovery and Messaging

The following Android walkthrough initializes the engine, discovers a peer on the local Wi-Fi network,
pairs with that peer, and sends a direct message. A JVM host can use the same engine interfaces once it
has registered and wired its own implementation.

### Step 1: Initialize Flash Engine

The engine is the [`FlashEngine`](../../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt) interface. You do not construct it directly; you ask a factory for one with a `FlashConfig`.

**Android:**

```kotlin
import com.transfer.flash.core.engine.Flash
import com.transfer.flash.core.engine.FlashConfig

val engine = Flash.create(
    context = applicationContext,
    config = FlashConfig(displayName = "Pixel-Alpha"),
)
// The engine starts itself; call engine.close() when you are done with it.
```

**JVM desktop:** `FlashDesktop.create(config)` (in `core-engine`) returns the engine, but the implementation class lives in the
Flash **desktop application** (`com.transfer.flash.desktop.DesktopEngine`, module `:desktop`), not in a library. `create` loads that
class by name, so it works inside the Flash desktop app. A standalone JVM program must register its own factory first:

```kotlin
import com.transfer.flash.core.engine.FlashDesktop
import com.transfer.flash.core.engine.FlashConfig
import com.transfer.flash.core.engine.awaitReady

FlashDesktop.registerFactory { config -> MyEngine(config) } // your FlashEngine implementation
val engine = FlashDesktop.create(FlashConfig(displayName = "Workstation-Alpha"))
engine.awaitReady() // suspends until the boot finished, or throws if it failed
```

> **What the shipped apps do.** Neither app calls `Flash.create` for its own engine: the Android app builds it in
> `DiscoveryEngineHolder` and the desktop app in `DesktopEngine`, and both wire calling, push-to-talk and the swarm themselves.
> `Flash.create` is the supported entry point for a third-party host; see [core-engine](../modules/core/core-engine.md).

### Step 2: Observe Discovered Peers

Flash uses Network Service Discovery (NSD / mDNS) over the LAN. `engine.discovery.discoveredEndpoints` is a `StateFlow<List<FlashDiscoveredEndpoint>>`:

```kotlin
scope.launch {
    engine.discovery.discoveredEndpoints.collect { endpoints ->
        println("Discovered ${endpoints.size} Flash peer(s) on LAN:")
        endpoints.forEach { endpoint ->
            println(" -> ${endpoint.device.friendlyName} (${endpoint.device.id}) at ${endpoint.hostAddress}:${endpoint.port}")
        }
    }
}
```

### Step 3: Send a Direct Message

On Android, `Flash.create` wires `engine.pairing`. Start a pairing with the discovered peer, display the
code from the pairing state, and have both users confirm that their codes match. Wait for the peer to be
trusted before sending. See [pairing and trust](../guides/pairing-and-trust.md) for the exact calls and the
steps a custom transport must implement.

`FlashChatRepository` works on an *open conversation*: open it, then send. Both calls return `Unit`; the message appears in
`engine.chats.conversationState` and is delivered by the durable outbox (retried until the peer is reachable).

```kotlin
val conversationId = endpoint.device.id.value // for a 1:1 chat the conversation id is the peer's device id
engine.chats.openConversation(conversationId)
engine.chats.sendText("Hello from Flash Developer Guide!")

// or, without opening the conversation:
engine.chats.sendTextTo(conversationId, "Hello again")
```

### Step 4: Send a File

Sending uses the chunked transfer engine (`FlashTransferRepository`). `sendFile` takes the target `FlashDevice`, a source URI,
a display name and the size, and returns `FlashResult<FlashTransferId>`. On Android, pass the `content://` URI and file size
from your file picker; a desktop host supplies the local path or URI its engine accepts:

```kotlin
scope.launch {
    engine.transfers.sendFile(
        targetDevice = endpoint.device,
        fileUri = pickedFileUri.toString(),
        displayName = "archive.zip",
        fileSize = pickedFileSize,
    ).onSuccess { transferId ->
        println("Transfer started: $transferId")
    }.onFailure { err ->
        println("Could not start: $err") // FlashError is a sealed interface; match on its cases for details
    }
}

// Progress: every transfer is a row in activeTransfers.
scope.launch {
    engine.transfers.activeTransfers.collect { rows ->
        rows.forEach { t ->
            println("${t.state}: ${t.bytesDone}/${t.bytesTotal} at ${t.speedBytesPerSec / 1024} KB/s, ETA ${t.etaSeconds}s")
        }
    }
}
```

The receiver is asked to accept first (unless `FlashConfig.autoAcceptIncoming` is set); a declined or failed transfer shows up in the
same list with `errorMessage` set.

---

## 4. Next Steps

* Read the [Architecture Overview](architecture-overview.md) to understand how the 15 modules interact without circular dependencies.
* Review the [Core Modules Guides](../modules/core/) to understand each subsystem in depth.
* Explore [Scenarios](../scenarios/) for specialized deployments like ultra-low RAM devices, custom BLE/LoRa transports, or writing a custom Python client.
