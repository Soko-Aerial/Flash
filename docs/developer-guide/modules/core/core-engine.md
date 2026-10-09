# Core Engine Module (`:core:engine`)

The `:core:engine` module provides the unified orchestrator and runtime facade for the Flash stack. It wires discovery, network servers, crypto identity, mutual pairing, chat repositories, file transfers, and optional calling into a cohesive, single-point-of-entry API: [`FlashEngine`](../../../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-engine:v2.1.0-beta.1")
}
```

---

## 2. Engine Architecture

```text
                     ┌──────────────────────────────┐
                     │   FlashEngine (AutoCloseable)│
                     └──────────────┬───────────────┘
                                    │ exposes
   ┌───────────┬────────────┬───────┴────┬──────────────┬─────────────┐
   ▼           ▼            ▼            ▼              ▼             ▼
 chats      transfers    discovery     network      trustStore   optional seams:
 (messaging) (transfer)  (discovery)   (network)    (security)   ptt, calls, swarm
```

`DefaultFlashEngine` is the standard implementation (it takes the five repositories and the three optional engines).
Two factories assemble a fully wired one:

* **Android:** `Flash.create(context, FlashConfig())` (in `core-engine`'s Android source set). It builds one shared
  `CoroutineScope`, opens the encrypted database (do this **off the main thread**), starts the TLS server, NSD advertising and
  browsing and the proactive auto-connect, and returns the engine. It throws `TransportSecurityUnavailableException` when the TLS
  identity cannot be built: there is no plaintext fallback.
* **JVM desktop:** `FlashDesktop.create(FlashConfig())` (in the JVM source set). The implementation class is
  `com.transfer.flash.desktop.DesktopEngine` in the **desktop application module**, not in a library. `create` loads it by name
  when no factory is registered, so it works inside the Flash desktop app; a standalone JVM host calls
  `FlashDesktop.registerFactory { config -> ... }` first. `FlashEngine.awaitReady()` suspends until the boot finished (or throws
  if it failed).

> **The shipped apps do not use these factories.** The Android app builds its engine in `DiscoveryEngineHolder` and the desktop
> app in `DesktopEngine`; each hand-wires calling (`CallCoordinator`), push-to-talk and the swarm. `Flash.create` is the
> supported entry point for a third-party host and is exercised by `:sample:consumer*`, not by the Flash apps.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashEngine`

Located in [`com.transfer.flash.core.engine.FlashEngine`](../../../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt)
(abridged; there is **no** `start()` / `stop()`: construction starts the engine and `close()` stops it):

```kotlin
public interface FlashEngine : AutoCloseable {
    public val chats: FlashChatRepository
    public val transfers: FlashTransferRepository
    public val discovery: FlashDiscovery
    public val network: FlashNetwork
    public val trustStore: FlashTrustStore

    public val ptt: FlashPtt?                       // null until attached
    public fun attachPtt(hasMicPermission: () -> Boolean = { true },
                         isCallActive: () -> Boolean = { false },
                         audioRateHz: () -> Int = { 16_000 }): FlashPtt?
    public fun attachPtt(engine: FlashPtt)
    public fun detachPtt()

    public val calls: FlashCalling?                 // null until attached
    public fun attachCalling(engine: FlashCalling)
    public fun detachCalling()
    public suspend fun onInboundCallText(peerDeviceId: String, text: String): Boolean
    public fun onCallSignalingLost(peerDeviceId: String)
    public fun onCallSignalingRestored(peerDeviceId: String)
    public fun busyCallPeerIds(): Set<String>

    public val swarm: FlashSwarm?                   // null until attached
    public fun attachSwarm(config: FlashSwarmConfig = FlashSwarmConfig()): FlashSwarm?
    public fun attachSwarm(swarm: FlashSwarm)
    public fun detachSwarm()

    public fun updateFriendlyName(name: String): Boolean
    // close() is inherited from AutoCloseable and is idempotent.
}
```

The attach methods are idempotent, and PTT, calling and the swarm are all opt-in so a consumer that never attaches them pays
nothing (`:core:calling` is `compileOnly` for the engine, so WebRTC is not pulled in transitively; ADR-033).
There is no `crypto` member: identity and pairing live behind `trustStore` and `:core:security`.

### 3.2 `FlashConfig`

```kotlin
public data class FlashConfig(
    val displayName: String? = null,          // advertised name; null = the persisted identity's name
    val enableResume: Boolean = true,         // persist chunk progress (desktop: not yet, resumes within a session)
    val autoAcceptIncoming: Boolean = false,  // paired peers only; an unpaired peer's offer always waits for the user
    val receivedFilesPath: String? = null,    // null = platform default
)
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.engine.Flash
import com.transfer.flash.core.engine.FlashConfig

// Android, off the main thread
val engine = Flash.create(applicationContext, FlashConfig(displayName = "Pixel-Alpha"))

scope.launch {
    engine.discovery.discoveredEndpoints.collect { peers ->
        println("Nearby: ${peers.map { it.device.friendlyName }}")
    }
}

// when the host goes away (Service.onDestroy / ViewModel.onCleared)
engine.close()
```

See the [beginner guide](../../getting-started/beginner-guide.md) for sending a message and a file.
