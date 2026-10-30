# Domain D25 — Bluetooth / Radio Link (`:core:radio`)

## 1. Domain Overview
This domain audits the physical radio and Bluetooth connectivity stack implemented in `:core:radio`, focusing on RFCOMM link security, null-authentication Bluetooth connections, KISS/AX.25 framing robustness, pairing key derivation (`RadioPairingAdapter`), over-the-air anti-replay safeguards, command injection, and Android Bluetooth permissions.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Radio & Bluetooth Architecture Inventory

| Layer | Protocol / Spec | Security Mechanism | Evaluation |
|---|---|---|---|
| Bluetooth Transport | RFCOMM / SPP (`00001101-0000-1000-8000-00805F9B34FB`) | Insecure (null-authentication) RFCOMM by design | Vulnerable to proximity MITM / injection (`SEC-D25-01`). |
| Host Link Interface | Private Non-SDK Reflection | Reflection into `createInsecureRfcommSocket(int)` | Android hidden API restriction risk (`SEC-D25-02`). |
| TNC Framing | KISS Protocol (Phil Karn 1987) | Escape character delimiter (`FEND`, `FESC`) | Robust parser; invalid frames dropped and counted. |
| Over-the-Air Framing | AX.25 UI-frames | Rotating station labels (`Ax25Address`) | Protects against long-term over-the-air station linkability. |
| Message Cryptography | AES-256-GCM (128-bit tag) | HKDF-SHA256 derived directional keys (`RadioPairingAdapter`) | E2E encrypted pairwise chat over radio. |
| Replay Protection | Monotonic 64-bit counters + Sliding Window | `ReplayWindow` + `PersistentRadioStore` | Replayed OTA frames are rejected with `RadioDrop.REPLAY`. |
| Broadcast Traffic | Unencrypted AX.25 broadcast | Optional ECDSA signatures (`RadioSignatureScheme`) | Public unsealed broadcast traffic. |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Insecure (null-auth) RFCOMM sockets by design | DEFECT | Radios reject authenticated pairing; connections use insecure RFCOMM (`SEC-D25-01`). |
| Reflection into hidden Android APIs | DEFECT | Invokes `createInsecureRfcommSocket(int)` via reflection (`SEC-D25-02`). |
| Framing & parser robustness (KISS / AX.25) | PASS | `KissStreamDecoder` bounded by `maxFrameBytes`; malformed frames silently dropped. |
| Authentication of radio identity | PASS/NOTE | Over-the-air peers authenticated via AES-GCM under pairing key; radio hardware link is unauthenticated. |
| Pairing-key adapter status (`RadioPairingAdapter`) | PASS | Built, tested, and bridges `RadioPeerTrustProvider` to `RadioSession` with directional keys. |
| Over-the-air replay protection | PASS | Sliding window (`ReplayWindow`) and persistent counters prevent packet replays (`REJ-D25-01`). |
| Permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) | PASS | Validated against SDK 31+ permissions model in `BluetoothPermissions.kt`. |
| Logging of radio payloads | PASS | Radio payloads are not written to logs (`REJ-D25-03`). |

---

## 4. Files Audited
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt`
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/AndroidRadioDiscovery.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioSession.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioPairingAdapter.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodec.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/KissTncDriver.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/BluetoothPermissions.kt`

---

## 5. Findings

### SEC-D25-01 — Null-Authentication RFCOMM by Design Permits Proximity Eavesdropping and Injection to Local TNC
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A1 (Adversary within Bluetooth range ~10–30 meters)
- **Component:** `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt:136,156-167,232-242`
- **CWE / standard:** CWE-306: Missing Authentication for Critical Function; CWE-319: Cleartext Transmission of Sensitive Information; CVSS:3.1/AV:A/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H (7.1)
- **What is wrong:** Handheld amateur radio transceivers (such as Vero VR-N75/N76) do not implement Bluetooth Secure Simple Pairing (SSP) or authentication PINs. To connect to these devices, `AndroidBluetoothCatalog.openRadioLink` explicitly opens insecure RFCOMM sockets via `device.createInsecureRfcommSocketToServiceRecord(id)` and channel reflection. The Bluetooth link between the phone/PC and the radio hardware has zero link-layer encryption and zero device authentication. A nearby Bluetooth attacker can spoof the radio's BD_ADDR or SDP record, intercept unencrypted KISS commands, or inject malicious KISS frames directly into the phone's receive pipeline or transmit queue.
- **Impact:** Proximity eavesdropping of unencrypted broadcast radio traffic and local transmission injection on the radio channel.
- **Evidence:**
  `AndroidBluetoothCatalog.kt:156-159`:
  ```kotlin
  // These handhelds accept only a null-authentication RFCOMM connection (the secure variants force an authentication
  // the radio rejects), and their SDP record can advertise a server channel that the firmware then refuses...
  val s = if (secure) device.createRfcommSocketToServiceRecord(id) else device.createInsecureRfcommSocketToServiceRecord(id)
  ```
- **Why existing guards do not stop it:** Handheld radio firmware limitations force null authentication. While pairwise chat messages inside `RadioSession` are protected by AES-256-GCM, link-level KISS commands and unencrypted broadcast frames over Bluetooth remain unauthenticated.
- **Verifier result:** survived. Code and hardware architecture confirm that insecure RFCOMM is deliberately chosen and enforced.
- **Recommended fix:**
  1. Clearly document in the UI that the local Bluetooth-to-radio link is unencrypted.
  2. Forbid transmission of unencrypted broadcast traffic or control commands over the radio link when operating in sensitive environments.
  3. Where supported by newer hardware models, prefer USB serial connections over Bluetooth SPP.
- **Related:** `SEC-D02-01`.

---

### SEC-D25-02 — Non-SDK Reflection on `createInsecureRfcommSocket(int)` Risks Platform Compatibility and Crash Failures
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A6 (Host system / platform compatibility constraint)
- **Component:** `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt:230-242`
- **CWE / standard:** CWE-470: Use of Externally-Controlled Input to Select Classes or Code; CWE-112: Missing XML Validation
- **What is wrong:** In `AndroidBluetoothCatalog.kt:232-242`, the fallback channel connection strategy uses Java reflection to invoke the private Android hidden method `createInsecureRfcommSocket(int)`. Since Android 9 (Pie), the Android runtime enforces non-SDK interface restrictions. Depending on target SDK level, device vendor ROM customizations, and Google Play policy checks, reflective access to greylisted or blacklisted Bluetooth APIs can be blocked or throw runtime exceptions.
- **Impact:** Connection failure or instability on customized Android devices when connecting to radios on non-standard channels.
- **Evidence:**
  `AndroidBluetoothCatalog.kt:234-237`:
  ```kotlin
  val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
      ?: device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
      ?: throw IOException("this Android build does not expose createInsecureRfcommSocket(int)")
  ```
- **Why existing guards do not stop it:** Although wrapped in `runCatching`, the code relies on undocumented platform internals that may disappear in future Android releases.
- **Verifier result:** survived. Confirmed reflection call is in active connection fallback paths.
- **Recommended fix:** Migrate to public standard `BluetoothDevice.createInsecureRfcommSocketToServiceRecord(UUID)` and deprecate raw channel number reflection.
- **Related:** `SEC-D11-01`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D25-01: Over-the-Air Radio Frame Replay Attack**: Audited `RadioSession.kt:107, 180-210`. The radio session implements a strict anti-replay sliding window (`ReplayWindow`) and persistent counter store (`RadioCounterStore`). Inbound replayed frames are rejected with `RadioDrop.REPLAY`.
- **REJ-D25-02: Plaintext Over-the-Air Eavesdropping of Pairwise Radio Chat**: Audited `RadioSession.kt` and `RadioPairingAdapter.kt`. Pairwise radio frames are end-to-end encrypted with AES-256-GCM using directional keys derived via HKDF from the shared pairing secret.
- **REJ-D25-03: Plaintext Logging of Radio Frame Contents**: Audited `RadioEvidence.kt` and logging calls in `:core:radio`. Logs record packet metrics, signal counters, and 8-character truncated identifiers; message plaintexts are never logged.
