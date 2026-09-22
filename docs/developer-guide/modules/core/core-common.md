# Core Common Module (`:core:common`)

The `:core:common` module forms the root foundation of the Flash ecosystem. It defines shared domain models, error handling primitives, time sources, byte math, and performance profiling tiers. It contains zero dependencies on other Flash modules and pure Kotlin multiplatform primitives.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-common:2.0.0-beta")
}
```

Target platforms: JVM (`jvmTarget`) and Android (`androidTarget`), `minSdk 24`, Java 11 bytecode.

---

## 2. Key Public Interfaces and Classes

### 2.1 Device Identity & Transport Models
Located in [`com.transfer.flash.core.common`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common):

```kotlin
public data class FlashDevice(
    val id: FlashDeviceId,
    val friendlyName: String,
    val transportType: FlashTransportType,
    val presence: FlashPeerPresence = FlashPeerPresence.Online,
    val protocolVersion: Int = 1,
)

@JvmInline
public value class FlashDeviceId(public val value: String)

public enum class FlashTransportType {
    LAN, WIFI_DIRECT, WEBSOCKET, RELAY, MESH, UNKNOWN
}

public enum class FlashPeerPresence {
    Online, Offline, Typing, Connecting
}
```

### 2.2 Error Handling: `FlashResult<T>` and `FlashError`
Flash uses functional result types instead of thrown exceptions for public API boundaries:

```kotlin
public sealed interface FlashResult<out T> {
    public data class Success<out T>(val value: T) : FlashResult<T>
    public data class Failure(val error: FlashError) : FlashResult<Nothing>

    public val isSuccess: Boolean get() = this is Success
    public val isFailure: Boolean get() = this is Failure

    public companion object {
        public inline fun <T> runCatching(block: () -> T): FlashResult<T>
    }
}
```

Available functional extensions: `getOrNull()`, `getOrElse { }`, `map { }`, `flatMap { }`, `onSuccess { }`, `onFailure { }`, and `fold(onSuccess, onFailure)`.

### 2.3 Performance & Hardware Tiering: `FlashPerformanceMode`
Located in [`com.transfer.flash.core.common.perf.FlashPerformanceMode`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt):

Governs how much CPU, memory, radio airtime, and UI complexity a device is permitted to consume:

```kotlin
public enum class FlashPerformanceMode {
    LOW,
    MEDIUM,
    HIGH;

    public val reduceMotion: Boolean get() = this != HIGH
    public val minimalChrome: Boolean get() = this != HIGH
    public val video: FlashVideoProfile
    public val voice: FlashVoiceProfile
    public val transport: FlashTransportProfile
}
```

---

## 3. Practical Code Example

```kotlin
import com.transfer.flash.core.common.FlashDevice
import com.transfer.flash.core.common.FlashDeviceId
import com.transfer.flash.core.common.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.perf.FlashPerformanceMode

fun handlePeerRegistration(rawId: String, name: String): FlashResult<FlashDevice> {
    return FlashResult.runCatching {
        require(rawId.isNotBlank()) { "Peer ID cannot be blank" }
        require(name.isNotBlank()) { "Device name cannot be blank" }
        
        FlashDevice(
            id = FlashDeviceId(rawId),
            friendlyName = name,
            transportType = FlashTransportType.LAN
        )
    }
}

fun inspectPerformanceTier(tier: FlashPerformanceMode) {
    println("Tier: ${tier.name}")
    println(" - Reduce motion enabled: ${tier.reduceMotion}")
    println(" - Minimal chrome: ${tier.minimalChrome}")
    println(" - Video target: ${tier.video.width}x${tier.video.height} @ ${tier.video.maxFps}fps")
    println(" - Voice Opus packet size: ${tier.voice.frameDurationMs}ms (DTX=${tier.voice.dtxEnabled})")
    println(" - Signaling keepalive ping: ${tier.transport.pingIntervalMs}ms")
}
```
