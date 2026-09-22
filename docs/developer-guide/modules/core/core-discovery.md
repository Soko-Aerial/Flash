# Core Discovery Module (`:core:discovery`)

The `:core:discovery` module enables zero-configuration, decentralized peer discovery across local area networks (LAN) and peer-to-peer Wi-Fi Direct connections.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-discovery:2.0.0-beta")
}
```

---

## 2. Discovery Mechanisms

Flash employs two distinct transport discovery mechanisms behind a unified interface:

1. **LAN Network Service Discovery (NSD / mDNS):**
   * Publishes and discovers DNS-SD service records (`_flash._tcp`) on the local subnet.
   * **Android:** Uses Android's `android.net.nsd.NsdManager` with lifecycle recovery on Wi-Fi link drops.
   * **Desktop / JVM:** Uses pure Kotlin/Java multicast DNS implementation (`JmDNS` / raw UDP multicast on `224.0.0.251:5353`).
   * Advertises: Device ID, friendly display name, WebSocket port, protocol version, and public key fingerprint.
2. **Wi-Fi Direct (P2P):**
   * Uses Android's `WifiP2pManager` to discover nearby peers without requiring an external Wi-Fi router.
   * Handles peer group negotiation, Group Owner (GO) election, IP allocation, and connection intents.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashDiscovery`
The primary discovery interface located in [`com.transfer.flash.core.discovery`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery):

```kotlin
public interface FlashDiscovery {
    /** Reactive stream of currently active nearby endpoints on the local link. */
    public val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>>

    /** Begins advertising the local service and listening for peer broadcasts. */
    public suspend fun startDiscovery()

    /** Stops broadcasting and releases multicast sockets. */
    public suspend fun stopDiscovery()

    /** Forces a cache clear and rediscovery cycle. */
    public fun refresh()
}

public data class DiscoveredEndpoint(
    val deviceId: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val transport: FlashTransportType,
    val lastSeenMs: Long
)
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.discovery.FlashDiscovery
import kotlinx.coroutines.launch

fun startPeerMonitoring(discovery: FlashDiscovery) {
    scope.launch {
        discovery.startDiscovery()
        
        discovery.discoveredEndpoints.collect { peers ->
            println("=== Discovered Flash Peers (${peers.size}) ===")
            peers.forEach { peer ->
                println("Peer: ${peer.displayName} (${peer.deviceId})")
                println(" -> Address: ${peer.host}:${peer.port} via ${peer.transport}")
            }
        }
    }
}
```
