# Architectural Decision Additions — Stream R-chat

Reserved ADR range: ADR-200 through ADR-209.

---

## ADR-200: Domain-Separated HKDF Radio Key Derivation from Existing Flash Trust Store

### Status
ACCEPTED

### Context
Plan R §7 and `RADIO-WIRE-FORMAT.md` §6 require that radio transport encryption keys must derive from the existing Flash pairing trust path (`FlashTrustStore`), rather than introducing an ad-hoc or secondary pairing mechanism. If two stations communicate symmetrically over a half-duplex radio channel with the same key and nonce space, counter collisions or nonce reuse could occur. Furthermore, rotating station tags must identify packets without exposing stationary device identity.

### Decision
1. Implement `RadioPairingAdapter` bridging `RadioPeerTrustProvider` to `RadioSession`.
2. Derive pairwise radio keys using RFC 5869 HKDF-SHA-256 with domain separation:
   - Salt: `"flash-radio-v1"`
   - Info for Send: `"flash-radio-v1/dir" || localDeviceId || recipientId`
   - Info for Recv: `"flash-radio-v1/dir" || recipientId || localDeviceId`
3. Generate distinct 32-byte AES-GCM keys, 4-byte nonce salts, and 32-byte tag keys for each direction.
4. Derive 15-minute rotating station labels and tags (`RadioWire.stationLabel`) using HMAC-SHA-256.

### Consequences
- Existing pairings in `FlashTrustStore` immediately enable radio reachability without user re-pairing.
- Mathematical impossibility of send/receive key collision between stations.
- Station labels rotate every 15 minutes, preventing RF tracking while maintaining deterministic routing.

---

## ADR-201: Outbox Port & Adapter Architecture for Radio Transport in Chat Engine

### Status
ACCEPTED

### Context
Plan R §7 specifies companion mode text messaging over hardware radio. The shared chat engine (`RealFlashChatRepository`) must support radio transport while adhering strictly to:
- LAN and Hub always take precedence over radio.
- Zero behavior changes for users who do not use or enable the radio.
- O-4 Owner Gate restrictions limiting edits to shared engine files to an outbox port and minimal hooks.

### Decision
1. Define a clean interface `RadioOutboxPort` in `:core:messaging:radio`.
2. Implement `RadioChatBridge` adapter in `:core:messaging:radio` that enforces:
   - Carrier check: radio is used ONLY when recipient has no live LAN or hub path and is radio-reachable.
   - Payload limit: UTF-8 text segmented up to 3,008 bytes (16 segments x 188 bytes); longer refused with `"Message is too long to send by radio (maximum 3,008 bytes)"`.
   - Attachment refusal: photos, files, voice notes and calls refused with `"Photos and files cannot be sent by radio"`, never queued.
   - Strict delivery states: `'sent by radio, waiting'` when driver confirms transmit; `'delivered'` ONLY upon cryptographic ACK frame receipt.
   - Offline queueing: when radio gateway is off or disconnected, messages are queued with `'radio not connected'`.
3. Provide host adapters `DesktopRadioOutboxAdapter` and `AndroidRadioOutboxAdapter` connecting `RadioChatEngine` to `RadioOutboxPort`.

### Consequences
- `RealFlashChatRepository` receives a single optional `radioBridge: RadioChatBridge? = null` parameter.
- Standard LAN and Hub messaging logic is untouched.
- Clean architectural boundary between transport protocol and chat domain logic.

---

## ADR-202: Persistent Send Counter High-Water Mark Ahead-of-Use Reservation

### Status
ACCEPTED

### Context
AES-256-GCM nonce reuse under the same key results in complete loss of confidentiality and authenticity. On mobile devices and desktop computers, abrupt process termination (battery depletion, OS task kill, crash) could occur between counter generation and disk write.

### Decision
1. Implement `PersistentRadioStore` backing `RadioCounterStore` with atomic disk persistence.
2. `RadioSendCounter` reserves counter blocks ahead of use (default reserve = 64). Before issuing counter $n$, the durable store records $n + 64$.
3. On restart or crash recovery:
   - `RadioSendCounter` reads the persisted high-water mark and jumps past it.
   - Applies an epoch second floor (`secondsSince2026`) ensuring counter monotonically advances across calendar time.
4. Mutation verified: removing the jump causes simulated crash tests to catch counter reuse.

### Consequences
- Guaranteed zero counter reuse across crashes, battery pull, or unexpected OS kills.
- Atomic property writes ensure file corruption cannot brick the radio subsystem (safe fallback).
- Zero cryptographic keys or frame plaintexts are written to storage.
