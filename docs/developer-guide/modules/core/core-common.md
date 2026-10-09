# Core Common Module (`:core:common`)

The `:core:common` module forms the root foundation of the Flash ecosystem. It defines shared domain models, error handling primitives, time sources, byte math, and performance profiling tiers. It contains zero dependencies on other Flash modules and pure Kotlin multiplatform primitives.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-common:v2.1.0-beta")
}
```

Target platforms: JVM and Android (`minSdk 24`), Java 11 bytecode. `explicitApi()` is on.

---

## 2. Key Public Interfaces and Classes

### 2.1 Device Identity & Transport Models
Located in [`com.transfer.flash.core.common.model`](../../../../core/common/src/commonMain/kotlin/com/transfer/flash/core/common/model):

```kotlin
public data class FlashDevice(
    val id: FlashDeviceId,
    val friendlyName: String,
    val transportType: FlashTransportType,
    val presence: FlashPeerPresence = FlashPeerPresence.Online,
    val protocolVersion: Int = 1,
    val groupProtocol: Int = 1,          // group wire generation the peer speaks
    val identityKey: String? = null,     // pinned identity key, when known
    val features: Set<String> = emptySet(), // HELLO feature tokens (e.g. gs1, cv1)
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

Available functional extensions (top-level functions in `...core.common.result`; import them): `getOrNull()`, `getOrElse { }`, `map { }`, `flatMap { }`, `onSuccess { }`, `onFailure { }`, and `fold(onSuccess, onFailure)`.

`FlashError` is a sealed interface with nine cases: `NetworkUnavailable`, `PeerUnavailable`, `ConnectionTimeout`, `ProtocolMismatch`, `TransferFailed`, `VerificationFailed`, `StorageError`, `Cancelled` and `Unknown`. It has **no common `message` property**; `when` over the cases (or print the data class) to get the detail.

### 2.3 Performance & Hardware Tiering: `FlashPerformanceMode`
Located in [`com.transfer.flash.core.common.perf.FlashPerformanceMode`](../../../../core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt):

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
    public val transfer: FlashTransferProfile
    public val key: String // stable persisted key ("low" / "medium" / "high")
}
```

The profile classes carry the numbers: `FlashVideoProfile` (`captureWidth`, `captureHeight`, `captureFps`, `maxBitrateKbps`, `minBitrateKbps`, `startBitrateKbps`), `FlashVoiceProfile` (`ptimeMs`, `maxBitrateBps`, `useDtx`, `useInbandFec`), `FlashTransportProfile` (`pingIntervalMs`, `livenessTimeoutMs`, `reconnectCapMs`, call timeouts, ICE restart spacing) and `FlashTransferProfile`. Group calls cap video lower than the 1:1 profile (ADR-098), and screen share has its own ladder (ADR-102); those caps live in `:core:calling`, not here.

### 2.4 Other packages in this module

| Package (`...core.common.`) | Contents |
|---|---|
| `annotation` | `@FlashInternalApi` (public for cross-module use, not API; a consumer needs `@OptIn`) and `@FlashExperimentalApi`. |
| `logging` | `FlashLog`, `FlashLogger`, pluggable `FlashLogSink`, and `FlashProbe` (the evidence probes of ADR-087). |
| `perf` | The profiles above plus `FlashPerformanceClassifier`, `MemoryGovernor`, `ThermalGovernor`, `FlashMotionPolicy`. |
| `protocol` | Text framing and envelope helpers (`FlashProtocol`, `FlashTextFraming`, `FlashEnvelope`), `Base64` / `Base64Url`. |
| `time`, `id`, `concurrent`, `net` | `FlashTimeSource`, id and UUID generation, small lock and synchronized-collection helpers, `LinkChangeTracker`. |

---

## 3. Practical Code Example

```kotlin
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
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
    println(" - Video target: ${tier.video.captureWidth}x${tier.video.captureHeight} @ ${tier.video.captureFps}fps")
    println(" - Voice Opus packet size: ${tier.voice.ptimeMs}ms (DTX=${tier.voice.useDtx})")
    println(" - Signaling keepalive ping: ${tier.transport.pingIntervalMs}ms")
}
```
