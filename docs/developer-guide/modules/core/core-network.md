# Core Network Module (`:core:network`)

The `:core:network` module manages the bidirectional transport layer, WebSocket server and client connections, TLS encryption, session keepalives, and automatic link roaming recovery.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-network:v2.1.0-beta")
}
```

---

## 2. Network Architecture & Resilience

1. **Embedded WebSocket server and client over TLS.**
   * Every Flash instance runs a WebSocket server (`WsTransferServer`) on a port it chooses (`start(listenPort = 0)` picks a free one and returns it) and dials peers with `WsTransferClient`. The implementations are `WsFlashNetwork` (Android) and `JvmWsFlashNetwork` (desktop).
   * The channel is always TLS with **pinned device identities** (TOFU on first pairing, `TofuX509TrustManager` / `FlashPinVerifier`); there is no plaintext fallback. Text frames carry control and chat messages, binary frames carry file chunks. The HELLO frame advertises feature tokens (`HelloFeatures`) and the group protocol generation.
2. **Keepalive and health.** `HeartbeatPolicy` / `HeartbeatTracker` ping at the profile's `pingIntervalMs` and close a session that has shown no life within `livenessTimeoutMs`; only the dialing side redials (`ReconnectPolicy`, `ReconnectStagger`). `ConnectionHealthAggregator` feeds `FlashNetwork.connectionHealth`.
3. **Link roaming.** `JvmNetworkWatcher` (desktop) polls `java.net.NetworkInterface` for IP and interface changes; `AndroidNetworkWatcher` registers a `ConnectivityManager` network callback. Either one triggers a re-browse and a reconnect pass.
4. **Connection planning.** `ConnectionPlanner` / `AutoConnector` decide whom to dial. `ConnectionStrategy` has three modes, `ECO` (few sessions, slow keepalive), `STANDARD` and `BOOST` (every device, fast keepalive); the session ceiling is 24 for every mode (ADR-057), `DialBudget` limits dials in crowds. `RememberedRoutes` (DR1), the `SubnetSweeper` (DR3) and `PresenceExchange` supply addresses and liveness hints.
5. **Radio / serial link (ADR-101, groundwork only).** `radio/` holds a KISS-over-serial/Bluetooth byte link (`ByteLink`, `KissTncDriver`, `RadioSession`, `RadioCrypto`) and the `RadioLinkTester` diagnostic. It is **not** wired into the apps' sessions yet (no pairing-key adapter, no `FlashTransportType.BLUETOOTH`), and the tester classes currently ship inside `core-network-jvm` (open item in `logs/handoff.md`).

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashNetwork`

Located in [`com.transfer.flash.core.network`](../../../../core/network/src/commonMain/kotlin/com/transfer/flash/core/network/FlashNetwork.kt):

```kotlin
public interface FlashNetwork {
    public val networkState: StateFlow<FlashNetworkState>
    public val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>>
    public val connectionHealth: StateFlow<FlashConnectionHealth>

    public suspend fun start(listenPort: Int = 0): FlashResult<Int>   // returns the bound port
    public suspend fun stop(): FlashResult<Unit>
    public suspend fun connect(device: FlashDevice): FlashResult<FlashSession>
    public suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession>
    public suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit>
    public fun retryConnection(): Boolean = false
}

public interface FlashSession {
    public val peer: FlashDevice
    public val peerDeviceId: FlashDeviceId
    public val connectionState: StateFlow<FlashConnectionState>
    public val transportType: FlashTransportType
    public val frameAcks: Flow<FrameAck>                       // SocketWritten, then PeerAcknowledged
    public suspend fun send(message: ByteArray): FlashResult<Unit>
    public suspend fun sendText(text: String): FlashResult<Unit>
    public fun disconnect(reason: String = "Normal disconnect")
}
```

Sending is per session: there is no `sendText(peerId, ...)` / `sendBinary` on `FlashNetwork`. `connectManual` pins whoever answers the first time, so
only use it for a host you trust (the named-dial TOFU trap); the engine dials only already-pinned peers on its own.

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.network.FlashNetwork

fun monitorNetworkSessions(network: FlashNetwork) {
    scope.launch {
        network.activeSessions.collect { sessions ->
            println("Active peer connections: ${sessions.size}")
            sessions.forEach { (peerId, session) ->
                session.connectionState.value.let { println(" -> ${session.peer.friendlyName} ($peerId): $it") }
            }
        }
    }
}

suspend fun ping(network: FlashNetwork, peerId: FlashDeviceId) {
    network.activeSessions.value[peerId]?.sendText("hello")
}
```
