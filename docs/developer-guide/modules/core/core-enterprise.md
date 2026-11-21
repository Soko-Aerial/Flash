# Core Enterprise Module (`:core:enterprise`)

The `:core:enterprise` module implements the Flash organisation Hub client: enrolment, delegated credentials, challenge/response authentication, directory sync, relay WebSocket connections, end-to-end sealing key management, sealed Hub file transfers, announcements, and revocation checking.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-enterprise:v2.3.0-beta.1")
}
```

`:core:enterprise` `api`-depends on `:core:common`, `:core:hub-protocol`, `:core:security`, and `kotlinx-coroutines-core` (with `:core:persistence` and Ktor CIO/WebSockets internally). `:core:messaging` exports it on the compile classpath so Hub directory and join types appear in messaging signatures.

---

## 2. Public vs. `@FlashInternalApi` Surface

Start with the host's `FlashChatRepository` (from [`:core:messaging`](core-messaging.md)) for Hub-backed people, departments, groups, join approvals, and announcements. Direct consumers of `:core:enterprise` have a small plain-`public` seam and a broader `@FlashInternalApi` integration layer:

### 2.1 Plain `public` Contracts (no opt-in required)
* **`OrgPeerResolver` / `RealOrgPeerResolver`** (`...core.enterprise`): O(1) in-memory lookup of verified organisation colleagues (`OrgPeer`), offline trust lease state (`OrgLease.Valid`, `OrgLease.Expired` with `ExpiredReason.TIME` / `CLOCK`, `OrgLease.None`), cached display names, and colleague revocations (ADR-134).
* **`HubGroupRosterView`** (`...core.enterprise`): synchronous roster view (`HubGroupRoster`, `HubRosterOfferPayload`, `RosterAdoption`) consulted by the LAN group gate and Hub group chat.
* **`RelaySessionState`** (`...core.enterprise.relay`): `DISCONNECTED`, `CONNECTING`, `AUTHENTICATING`, `CONNECTED`, `SUSPENDED`, `REVOKED`.

### 2.2 `@FlashInternalApi` Coordinators & Clients
Lower-level Hub clients and DTOs require `@OptIn(FlashInternalApi::class)` and may change between builds:
* **`EnterpriseHub`**: single coordinator owning `session: HubSession` (bearer auth, renewal, un-enrolment, revocations), `directory: EnterpriseDirectoryClient`, `peers: HubPeerDirectory`, `relay: FlashRelayClient` (backed by `KtorRelayTransport`), `files: HubFilesApi` (`RealHubFilesApi`), `announcements: HubAnnouncementsApi` (`RealHubAnnouncementsApi`), live `status: StateFlow<HubStatus>`, and Hub ICE/TURN configuration refresh.
* **Directory & Sync**: `OrgDirectorySync`, `OrgSnapshotCoordinator`, `DirectoryPrivacyApi`, `HubGroupRosters`, `hubOnlineDeviceIds` (`HubPresenceDefaults`).
* **Enrolment, Sealing & Files**: `EnterpriseEnrollmentClient`, `HubSealingKeyManager`, `HubRevocationCache` / `HubRevocationGossip`, `HubFileTransfers`, `EnterpriseTrustStore` (`AndroidPreferencesEnterpriseTrustStore` on Android, `JvmFileEnterpriseTrustStore` on JVM).

---

## 3. Practical Code Example

```kotlin
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.OrgPeerResolver
import com.transfer.flash.core.enterprise.relay.RelaySessionState

fun checkColleagueAndRelay(
    resolver: OrgPeerResolver,
    peerDeviceId: String,
    relayState: RelaySessionState,
) {
    val peer = resolver.resolve(peerDeviceId)
    val lease = resolver.lease.value
    println("Colleague verified=${peer?.bindingOk == true}, lease=$lease, relayOnline=${relayState.isOnline}")
    if (lease is OrgLease.Expired) {
        println("Offline org lease expired due to ${lease.reason}")
    }
}

@OptIn(FlashInternalApi::class)
fun readHubLinkState(hub: com.transfer.flash.core.enterprise.EnterpriseHub) {
    println("Hub state: ${hub.status.value.state}")
}
```

See the [Hub protocol module](core-hub-protocol.md) for the shared wire types and the [operator guide](../../../deploy/HUB-SERVER-DEPLOYMENT.md) for deploying a test Hub.
