# Core Radio Module (`:core:radio`)

Flash's experimental radio integration is a separate Android and JVM library. It provides
KISS/AX.25 framing, a radio gateway, and radio chat APIs. `core-engine` does not depend on
this module and `FlashEngine` exposes no radio gateway. Add it directly to compile against its APIs.

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-radio:v2.2.0-beta.3")
}
```

### Wire the gateway into your host

1. Give `RadioGatewayManager` a coroutine scope, a `RadioDeviceDiscovery`, a `RememberedRadioStore`,
   and a `RadioAuthorisationStore`. The discovery adapter opens a `ByteLink`; the gateway creates the
   KISS driver. Use the supplied `AndroidRadioDiscovery` or `WindowsRadioDiscovery` where appropriate,
   or implement the adapter for your hardware.
2. Use `enable()` and `disable()` for the gateway lifecycle and observe `state` for discovery, pairing,
   connection and test-transmission state. Observe `driver` for the active link.
3. For radio chat, pass the gateway's driver flow, radio settings and a `RadioChatEngine` factory
   to `RadioChatHost`. It creates an engine for each active driver. Your app handles chat delivery,
   retries and the policy for switching between LAN and radio. Adding `core-radio`, even when it
   arrives at runtime through `ui-chat`, does not set up that routing.

### Android Bluetooth permissions

The consuming app declares and requests permissions. On Android 12+ (API 31+), request
`BLUETOOTH_CONNECT` to list bonded devices and connect, and `BLUETOOTH_SCAN` for discovery and
scan cancellation. On API 30 and earlier, declare the legacy `BLUETOOTH` and `BLUETOOTH_ADMIN`
permissions with `maxSdkVersion="30"`; active discovery there also needs location permission.
For manifest examples and when to use `neverForLocation`, see the
[Android Bluetooth permissions guide](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

### Hardware and test coverage

See the [gateway plan](../../../plans/RADIO-GATEWAY-PLAN.md) and the
[physical test backlog](../../../testing/TEST-BACKLOG.md) for supported paths and checks.
