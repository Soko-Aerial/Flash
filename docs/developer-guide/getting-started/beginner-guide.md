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
| **Gradle** | `8.11+` | Build system |
| **Android Studio / IntelliJ** | Ladybug / 2024.2+ | Recommended IDE with Compose preview support |

---

## 2. Project Setup & Dependency Configuration

Flash libraries are structured as Kotlin Multiplatform (KMP) modules supporting both Android (`androidTarget`) and JVM Desktop (`jvmTarget`).

### 2.1 Repository Setup

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal() // For locally published Flash artifacts
        google()
        mavenCentral()
    }
}
```

### 2.2 Adding Flash Dependencies

Depending on whether you are building a full-featured application, a headless background service, or a custom UI, declare the required Flash coordinates in your `build.gradle.kts`:

#### Option A: Unified Engine (Recommended for most apps)
Imports the complete Flash stack (discovery, pairing, encrypted messaging, chunked file transfer, and background resilience):

```kotlin
dependencies {
    implementation("com.transfer.flash:core-engine:2.0.0-beta")
    implementation("com.transfer.flash:ui-chat:2.0.0-beta")     // Optional: If Jetpack Compose UI is needed
    implementation("com.transfer.flash:ui-theme:2.0.0-beta")    // Design tokens and styling
}
```

#### Option B: Granular / Headless Dependencies
For resource-constrained devices or specialized services that do not need calling or UI:

```kotlin
dependencies {
    implementation("com.transfer.flash:core-common:2.0.0-beta")
    implementation("com.transfer.flash:core-discovery:2.0.0-beta")
    implementation("com.transfer.flash:core-network:2.0.0-beta")
    implementation("com.transfer.flash:core-transfer:2.0.0-beta")
    implementation("com.transfer.flash:core-messaging:2.0.0-beta")
}
```

---

## 3. "Hello World" Tutorial: Discovery and Messaging

Here is a complete, minimal example showing how to initialize the Flash engine, discover peers on the local Wi-Fi network, and send a direct message.

### Step 1: Initialize Flash Engine

On JVM Desktop or Android, obtain or construct an instance of [`FlashEngine`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt):

```kotlin
import com.transfer.flash.core.common.FlashDevice
import com.transfer.flash.core.common.FlashDeviceId
import com.transfer.flash.core.common.FlashTransportType
import com.transfer.flash.desktop.DesktopEngine
import kotlinx.coroutines.*

val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

// Construct DesktopEngine (or obtain DiscoveryEngineHolder on Android)
val engine = DesktopEngine(
    scope = scope,
    peerId = "flash_user_001",
    displayName = "Workstation-Alpha"
)

// Start the network server and background discovery
engine.start()
```

### Step 2: Observe Discovered Peers

Flash uses Network Service Discovery (NSD / mDNS) over LAN. Observe the active peers stream:

```kotlin
scope.launch {
    engine.discovery.discoveredEndpoints.collect { endpoints ->
        println("Discovered ${endpoints.size} Flash peer(s) on LAN:")
        endpoints.forEach { endpoint ->
            println(" -> ${endpoint.displayName} (${endpoint.deviceId}) at ${endpoint.host}:${endpoint.port}")
        }
    }
}
```

### Step 3: Send a Direct Message

Once a peer is discovered, send a text message through [`FlashChatRepository`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/FlashChatRepository.kt):

```kotlin
scope.launch {
    val targetPeerId = "flash_user_002"
    val result = engine.chats.sendText(
        conversationId = targetPeerId,
        text = "Hello from Flash Developer Guide!"
    )
    
    result.fold(
        onSuccess = { msgId -> println("Message delivered successfully, id: $msgId") },
        onFailure = { err -> println("Failed to send message: ${err.message}") }
    )
}
```

### Step 4: Stream an Outbound File Transfer

Streaming a file utilizes the chunked transfer engine ([`FlashTransferRepository`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/FlashTransferRepository.kt)):

```kotlin
scope.launch {
    val targetPeerId = "flash_user_002"
    val fileResult = engine.transfers.sendFile(
        peerId = targetPeerId,
        uri = "file:///path/to/archive.zip",
        fileName = "archive.zip",
        totalBytes = 104_857_600L // 100 MB
    )
    
    fileResult.onSuccess { transferId ->
        println("Transfer started: $transferId")
        // Observe real-time progress
        engine.transfers.observeTransfer(transferId).collect { transfer ->
            println("Progress: ${transfer.bytesTransferred}/${transfer.totalBytes} (${transfer.progressPercent}%) - ${transfer.speedBytesPerSec / 1024} KB/s")
        }
    }
}
```

---

## 4. Next Steps

* Read the [Architecture Overview](architecture-overview.md) to understand how the 14 modules interact without circular dependencies.
* Review the [Core Modules Guides](../modules/core/) to understand each subsystem in depth.
* Explore [Scenarios](../scenarios/) for specialized deployments like ultra-low RAM devices, custom BLE/LoRa transports, or writing a custom Python client.
