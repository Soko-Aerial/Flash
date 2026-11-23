# Core Hub Protocol Module (`:core:hub-protocol`)

The `:core:hub-protocol` module is the pure Kotlin Multiplatform (`android` and `jvm`) wire contract shared by the Hub server (`:server`) and its clients (`:core:enterprise`). It depends only on `:core:common` and `kotlinx-serialization-json` (no Ktor, no coroutines, no Room, and no `:core:security`; signature verification goes through the `EcdsaVerifier` interface).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-hub-protocol:v2.3.0-beta.1")
}
```

> **Internal API status (`v2.3.0-beta.1`):** Every public declaration in `:core:hub-protocol` is annotated `@FlashInternalApi`. It is published so `:core:enterprise` can expose Hub DTOs and can be pulled in transitively, but callers referencing its types directly must opt in with `@OptIn(FlashInternalApi::class)` and expect wire-seam changes between releases. For most applications, start with [`:core:enterprise`](core-enterprise.md) or [`:core:engine`](core-engine.md).

---

## 2. Package Layout & Wire Types

All packages live under `com.transfer.flash.core.hubprotocol` (all declarations `@FlashInternalApi`):

| Package | Key types & responsibilities |
|---|---|
| `...hubprotocol` | `HubContract` (canonical REST & relay paths such as `PATH_RELAY = "/flash-relay"`, `PATH_API = "/api/v1"`, `PATH_ENROLL`, `PATH_CALL_ICE`, `PATH_GROUPS`, `PATH_FILES`; feature flags `FEATURE_HUB_CALLING`, `FEATURE_ANNOUNCEMENTS_V1`, `FEATURE_GROUP_E2EE`; size/timing bounds), `HubErrorCodes` (`ERR_AUTH`, `ERR_REVOKED`, `ERR_EXPIRED`, `TERMINAL`, etc.), `HubJson`, `HubDecodeException`. |
| `...hubprotocol.certs` | `DelegationCert`, `DeviceCert`, `ChainStatus`, `EnrollmentInvite`, `OrgMemberStatement`, `GroupRosterStatement`, `RosterStatus`, `RevocationList`, `EcdsaVerifier`, `HubAuth`, `RenewalPolicy`, `HubRoles`, `HubScopes`, `HubStepUp`, `AuthCanonicalWriter`, `Sha256`. |
| `...hubprotocol.relay` | `RelayFrame` (sealed wire frames for `/flash-relay`), `RelayFrameCodec`, `HubPrincipal`, `RelayLimits`, `AckStatus`, `QueueEvent`. |
| `...hubprotocol.rest` | `HubRestCodec` and `@Serializable` REST DTOs (`RestDtos.kt` including `HubIceConfigDto`, `DirectorySyncDto`, `HealthLite`, `HubInfo`, `ErrorBody`; `DirectoryAdminDtos.kt` including `MeDto`, `CreateDepartmentBody`, `CreateGroupBody`; `DirectoryPrivacyDtos.kt`; `AnnouncementDtos.kt`; `HubFileDtos.kt`). |

---

## 3. Practical Code Example

```kotlin
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec

@OptIn(FlashInternalApi::class)
fun inspectHubContract(rawRelayJson: String) {
    val relayPath = HubContract.PATH_RELAY             // "/flash-relay"
    val iceConfigPath = HubContract.PATH_CALL_ICE      // "/api/v1/calls/ice-config"
    val frame = RelayFrameCodec.decode(rawRelayJson)
    println("Relay endpoint=$relayPath ice=$iceConfigPath terminalErrors=${HubErrorCodes.TERMINAL} frame=$frame")
}
```

The documented [wire protocol](../../../protocol.md) and its version markers govern interoperability.
