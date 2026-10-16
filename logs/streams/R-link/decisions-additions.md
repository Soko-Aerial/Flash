# Decision Log Additions — Stream R-link

## ADR-187 — Promotion of Win32 Bluetooth JNA and Registry Port Mapping to `:core:radio`

### Decision
Promote the spiked Win32 Bluetooth API (JNA), registry BTHENUM COM-to-MAC mapper, models, and device classifier from `desktop/.../radiospike` directly into `core/radio/src/jvmMain/kotlin/.../link/windows/`. Add `libs.jna` and `libs.jna.platform` to `core:radio` `jvmMain`. Keep desktop CLI as a thin runner.

### Context
Spike BT-R11 verified that Windows 11 pairs the VR-N76 and creates virtual COM ports mapped to the radio MAC via `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM`. Production desktop needs these capabilities to resolve COM ports dynamically across OS reboots and renumbering.

### Alternatives considered
- Duplicate code between `:desktop` and `:core:radio`: Rejected (violates single source of truth and maintenance hygiene).
- JNI C++ DLL: Rejected (JNA 5.17.0 is already tested, approved in ADR-035, and pure JVM).

---

## ADR-188 — Common `RadioDeviceDiscovery` Port and `RadioGatewayManager` Facade

### Decision
Define `RadioDeviceDiscovery` in `core:radio` `commonMain` (`pairedDevices()`, `scan(timeoutMs): Flow<DiscoveredDevice>`, `pair(device): PairResult`, `openLink(remembered): ByteLink`). Implement it on Windows (`WindowsRadioDiscovery`) and Android (`AndroidRadioDiscovery`). Create a unified common facade `RadioGatewayManager` in `commonMain` that coordinates state machine, TNC driver, and stores.

### Context
Plan R §3 and §6 require a unified flow and common state model on both Windows and Android without duplicating state logic or coupling to host UI/Activity lifecycles.

### Alternatives considered
- Host-specific state managers: Rejected (leads to divergence in reconnect, scan cancellation, and confirmation rules).

---

## ADR-189 — Strict In-Memory Seam Enforcement of Rule 4 (No Test Frames on Real RF) — SUPERSEDED 2026-10-16 by the amendment below

### Decision
Enforce Plan R §6 Rule 4 and §8 mechanically within `RadioGatewayManager.requestTestMessage()`: test transmissions succeed and emit bytes to the wire ONLY when `currentByteLink` is verified to be an in-memory simulated link (`InMemoryLink`). On any real RF or serial port link, `requestTestMessage()` suppresses transmission and emits `FlashProbe.emit("radio.tx.suppressed_on_real_rf", ...)` without keying the transmitter.

### Context
Amateur radio and transmission regulations prohibit unauthorized or unattended transmissions. KISS TNCs key the transmitter upon receiving data frames. Mechanical enforcement guarantees no accidental RF transmissions during development or debugging.

---

### Amendment 2026-10-16 (integrator review; the decision above is SUPERSEDED, kept for history)
The original rule suppressed every test message on any link not recognised as in-memory, by matching the class name and a description substring ("mem", "fake"). That made the plan's "Send a test message" user action impossible on real hardware, never checked the authorisation record, and relied on string sniffing. Replacement rule (implemented in `RadioGatewayManager.requestTestMessage(text, userConfirmedTransmit)` returning `TestMessageResult`):
- Nothing is written unless: the gateway is connected and ready; the user confirmed the transmit warning in that very request; an authorisation record with a positive date exists; the text is 1..64 UTF-8 bytes; no other test message is in flight (single-flight lock).
- The link type is NOT inspected. Automated tests use in-memory or recording links; the "never transmit" rule for agents and tests is enforced by the tests, not by guessing in product code.
- The text is never logged; the probe `radio.tx.test_message` carries the result name and the length only.
Mutation-checked: removing the authorisation check, the confirmation check or the single-flight lock each fails a named test (RADLNK-U09a, U09c).

---

## ADR-190 — Atomic File and Preferences Persistence for Radio Settings (Zero Key Storage)

### Decision
Store `RememberedRadio` and `AuthorisationConfirmation` using atomic file replacement (`radio.properties.tmp` -> `Files.move(ATOMIC_MOVE)`) on Desktop and synchronous `commit()` on Android `SharedPreferences`. Store only MAC address, display name, last known port, epoch milliseconds, and operator callsign. Store ZERO cryptographic keys or frames. Unreadable or corrupted files return null without throwing.

### Context
DataStore tmp rename issues exist on Windows; standard properties files match `DesktopSettingsStore` and `DesktopIdentityStores`. Keys belong strictly to pairing/identity layers, not radio settings.

---

## ADR-191 — Android 14/15 `connectedDevice` Foreground Service Policy

### Decision
Implement pure policy `RadioServicePolicy` in `commonMain` to determine service lifecycle (`shouldServiceRun`) and honest notifications. Wire thin `RadioLinkService` in `:app` specifying `android:foregroundServiceType="connectedDevice"` and passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` on API 34+.

### Context
Android 14+ requires foreground service types and corresponding manifest permissions. `connectedDevice` is exempt from 6-hour execution limits on Android 15 while actively communicating with the external peripheral.
