# Domain D23 — Privacy and Data Minimisation

## 1. Domain Overview
This domain evaluates data exposure, metadata leakage, user tracking, presence signals, directory visibility, and telemetry across the Flash peer mesh and enterprise hub infrastructure.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Privacy & Telemetry Inventory

| Data Flow / Channel | Recipient | Content Exposed | Privacy Protection | Evaluation |
|---|---|---|---|---|
| LAN Discovery (mDNS / NSD) | Local Wi-Fi subnet (A1) | Static `deviceId`, `name`, protocol version, capabilities, IP & port | Plaintext mDNS multicast (no encryption) | Long-term tracking vector across networks (`SEC-D23-01`). |
| Hub Relay Mailbox | Hub Server Operator (A4) | `senderDeviceId`, `recipientDeviceId`, timestamp, ciphertext length | Payload sealed with recipient public key (`HubSealing`) | Social graph & traffic analysis metadata visible to hub. |
| Typing Indicators | Hub Server Operator (A4) | `from`, `to`, `conversationId`, millisecond timestamp `ts` | Transmitted unsealed in `RelayFrame.Meta` | Real-time conversational surveillance possible (`SEC-D23-02`). |
| Hub Files Storage | Hub Server Operator (A4) | `fileId`, encrypted chunk blobs, file byte size | Encrypted with symmetric key (`HubFileKey`) | Content is protected; file size and transfer time visible. |
| Organization Directory | Hub Employees (A3) | Name, department, role, device ID, public key | Signed directory snapshots | Device lookup endpoint leaks hidden department members (`SEC-D16-03`). |
| Analytics / Crash Reporting | External 3rd parties | None | Zero SDKs bundled | Compliant with zero-telemetry design. |
| Hardware Identifiers | Local OS / Peers | None | Pure software UUIDs | No IMEI, MAC, or ANDROID_ID collected. |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Third-party analytics, crash reporting, telemetry SDKs | PASS | Zero telemetry SDKs present. No Firebase, Sentry, or Crashlytics. |
| Hardware identifier collection (IMEI, MAC, Android ID) | PASS | Pure software random UUIDs used for `deviceId`. No hardware serials read. |
| Identifier permanence & rotation | DEFECT | `deviceId` is permanently retained without rotation, broadcast via mDNS (`SEC-D23-01`). |
| Metadata visible to hub operator (A4) | DEFECT | Hub observes social graph, message timing, and unsealed typing events (`SEC-D23-02`). |
| Hub file content confidentiality | PASS | Files encrypted with AES-GCM before upload; keys exchanged via sealed 1:1 envelopes. |
| Directory metadata exposure | DEFECT | Hidden department members discoverable via direct device lookup (`SEC-D16-03`). |
| Read receipt privacy | PASS/NOTE | Read receipts are dispatched to sender, functioning as presence oracle (`SEC-D05-07`). |

---

## 4. Files Audited
- `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdFlashDiscovery.kt`
- `core/discovery/src/jvmMain/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsTransport.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/identity/AndroidPreferencesIdentityStore.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubRelayPort.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubFileSealing.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`

---

## 5. Findings

### SEC-D23-01 — Static, Non-Rotating `deviceId` Broadcast in Cleartext via mDNS Enables LAN Device Tracking Across Networks
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A1 (Passive Wi-Fi listener / network observer)
- **Component:** `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdFlashDiscovery.kt:154-157`, `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/identity/AndroidPreferencesIdentityStore.kt:32-35`
- **CWE / standard:** CWE-359: Exposure of Private Personal Information to an Unauthorized Actor; CWE-200: Information Exposure; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N (4.3)
- **What is wrong:** The Flash application generates a local `deviceId` (a random UUID) at first launch and persists it permanently in application settings. Whenever discovery is active, `NsdFlashDiscovery` advertises this exact static identifier, along with the user's friendly name, in plaintext mDNS multicast TXT records (`_flash._tcp.local.`) across the local subnet. Any passive sniffer on public Wi-Fi networks (e.g., cafés, airports, hotels, university campuses) can record this persistent identifier, track the physical presence of the user over time, and correlate the user across multiple physical locations and networks.
- **Impact:** Long-term user location and device tracking across Wi-Fi networks.
- **Evidence:**
  `NsdFlashDiscovery.kt:154-157`:
  ```kotlin
  setAttribute("device_id", localDeviceId.value)
  setAttribute("name", name)
  setAttribute("proto", protocolVersion.toString())
  setAttribute("caps", capabilities)
  ```
- **Why existing guards do not stop it:** mDNS/DNS-SD packets are unencrypted broadcast traffic by design. Because `deviceId` never rotates, it acts as a permanent tracking beacon.
- **Verifier result:** survived. Code verification confirms `deviceId` is static and broadcast in mDNS TXT records.
- **Recommended fix:** Implement ephemeral discovery identifiers:
  1. Generate rotating ephemeral advertisement tokens derived from the identity key and current time window (e.g. daily rotation), resolving the true identity only during mutual TLS handshake.
  2. Provide a user preference for "Invisible Mode" to disable active mDNS advertisement on untrusted public Wi-Fi networks.
- **Related:** `SEC-D10-01`.

---

### SEC-D23-02 — Real-Time Metadata Leakage to Hub Operator via Unsealed Typing Indicators and Relay Envelopes
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A4 (Malicious or curious hub operator)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubRelayPort.kt:148-164`, `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`
- **CWE / standard:** CWE-200: Information Exposure; CWE-213: Exposure of Sensitive Information Through Sent Data
- **What is wrong:** While chat message bodies and files transferred via the hub relay are end-to-end encrypted, communication interaction metadata is exposed in plaintext to the hub server. In particular, typing indicator events are transmitted via `RelayFrame.Meta` with `action = "typing"`, exposing sender `from`, recipient `to`, `conversationId`, and timestamp `ts`. The hub server operator can monitor and log exactly who is communicating with whom, when users begin typing, message frequencies, and conversational rhythms, creating a detailed social graph and behavioral surveillance feed.
- **Impact:** Detailed conversational metadata and behavioral surveillance exposed to enterprise hub infrastructure.
- **Evidence:**
  `HubRelayPort.kt:148-152`:
  ```kotlin
  override val typing: Flow<HubTypingEvent> = client.metaEvents.mapNotNull { meta ->
      if (meta.action != RelayFrame.Meta.ACTION_TYPING) return@mapNotNull null
      val from = meta.from?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      HubTypingEvent(from, meta.conversationId, meta.ts)
  }
  ```
- **Why existing guards do not stop it:** Sealing is applied to `FlashEnvelope.payloadJson` but not to the outer `RelayFrame.Meta` transport envelope.
- **Verifier result:** survived. Confirmed `RelayFrame.Meta` transmits plaintext routing headers and timestamps through the hub relay.
- **Recommended fix:** Provide an organization-wide or client-side toggle to disable typing indicators over the hub relay, or encrypt typing signals inside sealed 1:1 envelopes with short expiry TTLs.
- **Related:** `SEC-D05-07`, `SEC-D15-02`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D23-01: Third-Party Telemetry and Analytics Tracking**: Audited all module build files and imports. Flash contains zero analytics, crash reporting (Crashlytics, Firebase, Sentry), or commercial telemetry SDKs.
- **REJ-D23-02: Hardware Serial / IMEI / Advertising ID Tracking**: Audited `AndroidPreferencesIdentityStore.kt` and `IdentityKeyVault.kt`. Flash never queries `Settings.Secure.ANDROID_ID`, IMEI, MAC address, or Google Advertising ID. Device identity is purely a software-generated random UUID.
- **REJ-D23-03: Hub File Content Decryption by Hub Server**: Audited `HubFileSealing.kt` and `HubFileTransfers.kt`. File payloads stored on the hub are encrypted with symmetric keys exchanged via sealed 1:1 envelopes. The hub server only stores opaque ciphertext chunks and has no access to decryption keys.
