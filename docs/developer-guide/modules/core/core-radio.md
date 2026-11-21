# Core Radio Module (`:core:radio`)

Flash's experimental radio integration is a separate Android and JVM library. It provides KISS/AX.25 framing, Bluetooth/serial TNC link management, a radio gateway, radio audio framing, and radio text chat APIs. `:core:engine` does not depend on this module and `FlashEngine` exposes no radio gateway. Add it directly to compile against its APIs.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-radio:v2.3.0-beta.1")
}
```

On JVM, `:core:radio` brings in `jserialcomm` (serial/RFCOMM ports) and `jna` / `jna-platform` (Win32 Bluetooth API and registry COM-port mapping).

---

## 2. Package Layout & Key Public / `@FlashInternalApi` Types

All packages live under `com.transfer.flash.core.radio`:

| Package | Key types |
|---|---|
| `...radio.gateway` | `RadioGatewayManager` (`@FlashInternalApi` — `enable()`, `disable()`, `state: StateFlow<RadioGatewayState>`, `driver: StateFlow<KissTncDriver?>`, `requestTestMessage(...)`), `RadioGatewayState`, `TestMessageResult`. |
| `...radio.chat` | `RadioChatHost` (`engine`, `enabled`, `status`, `inboundMessages`, `deliveryReceipts`), `RadioChatEngine` (`@FlashInternalApi` — segmented text up to 3,008 bytes, cryptographic ACKs, multi-lane priority `EMERGENCY > TEXT > ACK > LOCATION`), `RadioChatSettings`, `RadioChatInbound`, `RadioChatReceipt`, `RadioChatStatus`. |
| `...radio.link` | `ByteLink`, `RadioDeviceDiscovery`, `DiscoveredDevice`, `PairResult`, `RadioServiceIdentity`; Android `AndroidRadioDiscovery` / `AndroidBluetoothCatalog`; JVM `WindowsRadioDiscovery` / `WindowsRfcommLink` / `WindowsComPortMapper` / `JvmSerialPortCatalog`. |
| `...radio.connect` | `RememberedRadio`, `RememberedRadioStore` (`InMemoryRememberedRadioStore`, `AndroidSharedPreferencesRadioStore`), `RadioAuthorisationStore`, `RadioAuthorisationGate`, `RadioSecurityProfile` (`PROFILE_A_PLAINTEXT_SIGNED`, `PROFILE_M_ENCRYPTED`), `RadioConnectStateMachine`. |
| `...radio.driver` & `...kiss` | `KissTncDriver`, `KissFrameCodec`, `Ax25FrameCodec`, `TxPacing`, `TncLinkState`, `AndroidPrefsRadioStatePersistence`. |
| `...radio.wire`, `...audio`, `...diag` | `RadioSession`, `RadioPairingAdapter`, `RadioCrypto` / `JdkRadioCrypto`, `RadioWire`, `StreamFramer`, `RadioAudioSession` / `RadioAudioCodec`, `RadioLinkTester`. |

---

## 3. Wiring the Gateway Into Your Host

1. Give `RadioGatewayManager` a coroutine scope, a `RadioDeviceDiscovery`, a `RememberedRadioStore`,
   and a `RadioAuthorisationStore`. The discovery adapter opens a `ByteLink`; the gateway creates the
   `KissTncDriver`. Use the supplied `AndroidRadioDiscovery` or `WindowsRadioDiscovery` where appropriate,
   or implement the adapter for your hardware.
2. Use `enable()` and `disable()` for the gateway lifecycle and observe `state` (`RadioGatewayState`) for
   discovery, pairing, connection, and test-transmission state. Observe `driver` for the active link.
3. For radio chat, pass the gateway's `driver` flow, `RadioChatSettings` flow, and a `RadioChatEngine` factory
   to `RadioChatHost`. It creates an engine for each active driver. Your app handles chat delivery,
   retries, and the policy for switching between LAN and radio. Adding `core-radio`—even when it
   arrives transitively through `ui-chat`—does not set up that routing.

---

## 4. Android Bluetooth Permissions

The consuming app declares and requests permissions. On Android 12+ (API 31+), request
`BLUETOOTH_CONNECT` to list bonded devices and connect, and `BLUETOOTH_SCAN` for discovery and
scan cancellation. On API 30 and earlier, declare the legacy `BLUETOOTH` and `BLUETOOTH_ADMIN`
permissions with `maxSdkVersion="30"`; active discovery there also needs location permission.
For manifest examples and when to use `neverForLocation`, see the
[Android Bluetooth permissions guide](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

---

## 5. Hardware and Test Coverage

See the [gateway plan](../../../plans/RADIO-GATEWAY-PLAN.md) and the
[physical test backlog](../../../testing/TEST-BACKLOG.md) for supported paths and checks.
