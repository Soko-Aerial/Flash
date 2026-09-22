# Core Engine Module (`:core:engine`)

The `:core:engine` module provides the unified orchestrator and runtime facade for the Flash stack. It wires discovery, network servers, crypto identity, mutual pairing, chat repositories, file transfers, and optional calling into a cohesive, single-point-of-entry API: [`FlashEngine`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-engine:2.0.0-beta")
}
```

---

## 2. Engine Architecture

```text
                     ┌──────────────────────────────┐
                     │         FlashEngine          │
                     └──────────────┬───────────────┘
                                    │ Coordinates
        ┌──────────────┬────────────┼────────────┬──────────────┐
        ▼              ▼            ▼            ▼              ▼
   FlashDiscovery  FlashNetwork FlashCrypto  FlashChatRepo FlashTransferRepo
```

Host applications construct an engine instance:
* **Android:** Configured and managed by `DiscoveryEngineHolder` inside application scope.
* **Desktop / JVM:** Configured and managed by `DesktopEngine`.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashEngine`
Located in [`com.transfer.flash.core.engine.FlashEngine`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt):

```kotlin
public interface FlashEngine {
    /** Subsystem facades */
    public val discovery: FlashDiscovery
    public val network: FlashNetwork
    public val chats: FlashChatRepository
    public val transfers: FlashTransferRepository
    public val crypto: FlashCrypto
    public val trust: FlashTrustStore
    public val calls: FlashCalling? // Nullable if calling module omitted

    /** Lifecycle methods */
    public fun start()
    public fun stop()

    /** Calling attachment seam */
    public fun attachCalling(calling: FlashCalling)
    public fun detachCalling()
}
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.engine.FlashEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Initializing the engine in an application
fun createAndRunEngine(engine: FlashEngine) {
    // Start networking and discovery
    engine.start()

    println("Flash Engine started!")
    println("Local peer identity: ${engine.crypto.getLocalPublicKey().toHex()}")
}
```
