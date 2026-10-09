# Core Discovery Module (`:core:discovery`)

The `:core:discovery` module enables zero-configuration, decentralized peer discovery across local area networks (LAN). **Wi-Fi Direct discovery is not implemented** (no `WifiP2pManager` code exists; postponed, ADR-056).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-discovery:v2.1.0-beta")
}
```

---

## 2. Discovery Mechanisms

`CompositeDiscovery` runs several `FlashRadioTransport`s at once and merges their sightings into one endpoint list
(`StandardEndpointDirectory` de-duplicates by device id and emits a presence change when a peer reappears). Every transport
announces the same identity, carried as TXT attributes (`TxtCodec`): `device_id`, `name`, `model`, `proto`, `caps` (feature
tokens such as `gs1`, `cv1`) and `fp8` (a short identity-key fingerprint prefix).

1. **Android NSD** (`NsdTransport`, `NsdFlashDiscovery`): `android.net.nsd.NsdManager`, service type
   `_flash-transfer._tcp.` (and `_flashws._tcp.` for the WebSocket port). Resolves go through a queue (`NsdResolveQueue`) because
   NSD cannot resolve concurrently; link drops re-arm browsing.
2. **JmDNS** (`JmdnsTransport`, JVM/desktop): the same service type over JmDNS. Virtual adapters (VPN, Hyper-V, WSL) are filtered
   out (`VirtualAdapters`) but the filter never leaves the list empty, and a hotspot adapter counts as real (DR5).
3. **UDP multicast** (`MulticastTransport`, both platforms): a Flash-own announcement on `224.0.0.168:45823` that already carries
   the address and WebSocket port, so there is no second resolution step to fail. It is the fallback when mDNS is flaky.

Further resolve sources (remembered routes, subnet sweep, address hints) live in `:core:network` / the engine, not here.
Platform notes and failure signatures: `docs/android-platform-notes.md`, the JmDNS empty-TXT trap and NSD hotspot asymmetry
entries in `logs/errors.md`.

### Modes

`FlashDiscoveryMode` is honoured by the transports: `STANDARD` (advertise and browse), `GHOST` (browse only), `BOOST`
(aggressive re-announce and fast restart for crowded networks), `ECO` (duty-cycled browsing) and `RECEIVE_KIOSK` (advertises
willingness to auto-accept from trusted peers). These are discovery modes; the connection planner's ECO / STANDARD / BOOST
modes (`:core:network`) are a separate setting that happens to share names.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashDiscovery`

Located in [`com.transfer.flash.core.discovery`](../../../../core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/FlashDiscovery.kt).
Fallible calls return `FlashResult<Unit>`, and advertising is separate from browsing:

```kotlin
public interface FlashDiscovery {
    public val state: StateFlow<FlashDiscoveryState>
    public val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>

    public suspend fun startDiscovery(): FlashResult<Unit>           // browse
    public suspend fun stopDiscovery(): FlashResult<Unit>
    public suspend fun startAdvertising(listenPort: Int): FlashResult<Unit>
    public suspend fun stopAdvertising(): FlashResult<Unit>
    public suspend fun stopAll(): FlashResult<Unit>
}

public data class FlashDiscoveredEndpoint(
    val device: FlashDevice,       // id, friendlyName, transportType, features, identityKey...
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
    val deviceKind: FlashDeviceKind = FlashDeviceKind.UNKNOWN,
) {
    val deviceId: FlashDeviceId get() = device.id
    val friendlyName: String get() = device.friendlyName
    val transportType: FlashTransportType get() = device.transportType
}
```

There is no `refresh()`; the engine restarts browsing itself after a network change. `EmptyFlashDiscovery` is a no-op
implementation for hosts and tests that have no radio.

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.discovery.FlashDiscovery

fun startPeerMonitoring(discovery: FlashDiscovery, listenPort: Int) {
    scope.launch {
        discovery.startAdvertising(listenPort)
        discovery.startDiscovery()

        discovery.discoveredEndpoints.collect { peers ->
            println("=== Discovered Flash Peers (${peers.size}) ===")
            peers.forEach { peer ->
                println("Peer: ${peer.friendlyName} (${peer.deviceId})")
                println(" -> Address: ${peer.hostAddress}:${peer.port} via ${peer.transportType}")
            }
        }
    }
}
```

With `core-engine` you do not call these yourself: `Flash.create` advertises on the port its server bound and starts browsing.
