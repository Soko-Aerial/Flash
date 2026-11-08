# Core Radio Module (`:core:radio`)

Flash's experimental radio integration runs on Android and JVM. The module provides
KISS/AX.25 framing, radio discovery, connection state, and radio security policy.
Use it when a host controls compatible radio hardware and its permissions.

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-radio:v2.2.0-beta.3")
}
```

`RadioGatewayManager` accepts a coroutine scope, discovery, remembered-radio and
authorisation stores, and a link factory. Observe `state` for scanning, pairing, connection
and test-transmission state. The platform host opens the Bluetooth or serial link.
The gateway exposes its live KISS driver via `driver`. Start and stop the gateway with
`enable()` and `disable()`.

The Flash apps bind chat delivery, radio retry and per-chat policy at the host edge.
See the [gateway plan](../../../plans/RADIO-GATEWAY-PLAN.md) and the
[physical test backlog](../../../testing/TEST-BACKLOG.md) for supported paths and checks.
