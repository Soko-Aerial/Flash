# Core Network Module (`:core:network`)

The `:core:network` module manages the bidirectional transport layer, WebSocket server and client connections, TLS encryption, session keepalives, and automatic link roaming recovery.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-network:v2.3.0-beta.1")
}
```

---

## 2. Network Architecture & Resilience

1. **Embedded WebSocket server and client over TLS.**
   * Every Flash instance runs a WebSocket server (`WsTransferServer`) on a port it chooses (`start(listenPort = 0)` picks a free one and returns it) and dials peers with `WsTransferClient`. The implementations are `WsFlashNetwork` (Android) and `JvmWsFlashNetwork` (desktop).
   * The channel is always TLS with **pinned device identities** (TOFU on first pairing, `TofuX509TrustManager` / `FlashPinVerifier`); there is no plaintext fallback. Text frames carry control and chat messages, binary frames carry file chunks. The HELLO frame advertises feature tokens (`HelloFeatures`) and the group protocol generation.
2. **Keepalive, health, and reconnect.** Live WebSocket sessions (`WsConnection`) run `WsKeepalive` on a shared `WsKeepaliveTicker` (`WsKeepaliveTiming` derived from the active `FlashTransportProfile` / `ConnectionModePolicy`), with single-pinger negotiation (`PingRole`), background-traffic filtering, and bounded single-stall Doze forgiveness (`confirmClose`). (`HeartbeatPolicy` and `HeartbeatTracker` remain internal to the TCP/`LanSession` path.) On unexpected drops, the dialing side redials via `ReconnectPolicy` (from `:core:common`) and `ReconnectStagger` (which spreads mass-drop reconnect storms), while inbound-only peers also have a slower backup redial loop off `knownEndpoints` (ERROR-026). `ConnectionHealthAggregator` feeds `FlashNetwork.connectionHealth`.
3. **Link roaming.** `JvmNetworkWatcher` (desktop) polls `java.net.NetworkInterface` for IP and interface changes; `AndroidNetworkWatcher` registers a `ConnectivityManager` network callback. Either one triggers a re-browse and a reconnect pass.
4. **Connection planning.** `ConnectionPlanner` / `AutoConnector` decide whom to dial. `ConnectionStrategy` has three modes, `ECO` (few sessions, slow keepalive), `STANDARD` and `BOOST` (every device, fast keepalive); the session ceiling is 24 for every mode (ADR-057), `DialBudget` limits dials in crowds. `RememberedRoutes` (DR1), the `SubnetSweeper` (DR3) and `PresenceExchange` supply addresses and liveness hints.
5. **Radio / Bluetooth transport.** KISS-over-serial/Bluetooth radio transport and diagnostics live in the separate [`:core:radio`](core-radio.md) module (with `FlashTransportType.BLUETOOTH` in `:core:common`), not in `:core:network`.

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
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.network.FlashNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun monitorNetworkSessions(network: FlashNetwork, scope: CoroutineScope) {
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
