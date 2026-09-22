# Core Network Module (`:core:network`)

The `:core:network` module manages the bidirectional transport layer, WebSocket server and client connections, TLS encryption, session keepalives, and automatic link roaming recovery.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-network:2.0.0-beta")
}
```

---

## 2. Network Architecture & Resilience

1. **Embedded WebSocket Server & Client:**
   * Every Flash instance runs an embedded WebSocket server (`WsTransferServer`) bound to an available port.
   * Peers initiate bidirectional WebSocket connections (`ws://` or encrypted `wss://`).
   * Handles text framing for control messages and binary framing for high-speed file chunk transfers.
2. **Keepalive Watchdog & Health Probing:**
   * Sends periodic ping frames (`pingIntervalMs`) to detect half-open or dead TCP sockets.
   * If no inbound frame arrives within `livenessTimeoutMs`, the session is cleanly closed and redialed.
3. **Link Roaming & Interface Monitoring:**
   * **Desktop (`JvmNetworkWatcher`):** Periodically polls system network interfaces (`java.net.NetworkInterface`) to detect Wi-Fi router roams, IP address changes, and cable reconnects, immediately re-triggering discovery and reconnect sweeps.
   * **Android (`AndroidNetworkWatcher`):** Registers an Android `ConnectivityManager.NetworkCallback` with `NetworkCapabilities.NET_CAPABILITY_INTERNET` or `NET_CAPABILITY_NOT_RESTRICTED` to react immediately to cellular/Wi-Fi transitions.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashNetwork`
Located in [`com.transfer.flash.core.network`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network):

```kotlin
public interface FlashNetwork {
    /** Reactive map of currently active connected peer sessions. */
    public val activeSessions: StateFlow<Map<String, WsSession>>

    /** Starts the local inbound listening server. */
    public suspend fun startServer(): Int // Returns bound port

    /** Connects to an outbound peer endpoint. */
    public suspend fun connect(peerId: String, host: String, port: Int): FlashResult<WsSession>

    /** Sends a text frame to a connected peer. */
    public suspend fun sendText(peerId: String, text: String): Boolean

    /** Sends a raw binary frame (e.g. file chunk) to a connected peer. */
    public suspend fun sendBinary(peerId: String, data: ByteArray): Boolean

    /** Shuts down the server and disconnects all sessions. */
    public suspend fun stop()
}
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.network.FlashNetwork
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun monitorNetworkSessions(network: FlashNetwork) {
    scope.launch {
        network.activeSessions.collect { sessions ->
            println("Active peer connections: ${sessions.size}")
            sessions.forEach { (peerId, session) ->
                println(" -> Session with $peerId is OPEN (${session.remoteAddress})")
            }
        }
    }
}
```
