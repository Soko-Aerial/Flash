# Progress Log — Stream R-chat

## 2026-10-17 — Task 1: Explain-Back & Architectural Plan

### 1. What We Will Build
Stream R-chat implements Plan R §7 / Phase R3 (Companion Mode: text chat over hardware radio KISS TNC):
1. **Pairing-Key Adapter (`RadioPairingAdapter`):** Bridges existing Flash pairing trust from `FlashTrustStore` into `RadioSession`. It uses domain-separated HKDF-SHA256 (`flash-radio-v1/dir`) as defined in `RADIO-WIRE-FORMAT.md` §6 and ADR-101 to derive distinct directional AES-256-GCM encryption keys, nonce salts, and tag keys so that stations never share key/nonce spaces. It maps rotating station tags and station labels across 15-minute epochs.
2. **Persistent Counter & Replay Stores (`PersistentRadioStore`):** Implements `RadioCounterStore` and `RadioReplayStore` with atomic file/properties persistence (`radio_counters.properties`). Ahead of issuing counter $n$, it reserves up to $n + 64$ and writes the mark to disk. On reboot/crash restart, it jumps past the high-water mark and applies a monotonic epoch floor (`secondsSince2026`), guaranteeing AES-GCM nonce reuse is mathematically impossible.
3. **Radio Outbox Port & Adapter in `:core:messaging` (`RadioOutboxPort`, `RadioChatBridge`):**
   - Integrates with `RealFlashChatRepository` outbox path: radio is selected ONLY when recipient has no live LAN or hub path and is a paired contact known to be radio-reachable. Wi-Fi and hub ALWAYS win.
   - Text segmentation per `RADIO-WIRE-FORMAT.md` up to 16 segments (3,008 body bytes); bodies exceeding this limit are refused with a plain sentence: `"Message is too long to send by radio (maximum 3,008 bytes)"`.
   - Rejection of media: photos, files, voice messages, and calls are rejected with `"Photos and files cannot be sent by radio"` and never queued.
   - Strict delivery state ladder: message enters `'sent by radio, waiting'` when driver confirms packet transmission; transitions to `'delivered'` ONLY when a cryptographic ACK frame returns. Retries with exponential backoff up to a hard limit, transitioning to a clear failed state on exhaustion.
   - Inbound path: decodes KISS/AX.25, ingests into `RadioSession`, authenticates/decrypts, checks replay window, deduplicates via `DedupStore`, reassembles segments (with 10-minute timeout), transmits cryptographic ACK, and hands payload to normal chat inbound pipeline. Silently drops and counts unknown tags or auth failures in `radio.rx.dropped` probe.
   - Strict priority queue: EMERGENCY > TEXT > ACK > LOCATION.
   - Offline/disconnected handling: when radio gateway is disabled or disconnected, outbound messages to radio-only contacts are queued with honest state `'radio not connected'`, and never transmitted without the user switching the gateway on.
4. **Air Security Profiles (Plan R §8):**
   - Profile M (Tactical/Military): AES-256-GCM ciphertext only; zero plaintext substrings emitted on air; gated by `RadioAuthorisationGate` (refuses Profile M transmission without positive confirmation timestamp).
   - Profile A (Civilian Amateur): Signed clear text with callsign in source address / frame body.
5. **Two-Station In-Memory Simulation Test Suite (`RADCHAT-U01..`):** Comprehensive end-to-end simulation with loss, reordering, duplicate frames, replay attacks, wrong keys, crash recovery, attachment refusal, and LAN precedence.
6. **Owner Runbook (`runbook-R3.md`):** Safe, numbered hardware test steps for BT-R05, BT-R08, and BT-R09.

### 2. What Must NOT Change
- LAN and hub behavior remain completely untouched for users who do not use or enable the radio.
- Wi-Fi/LAN and Hub paths always take precedence over radio.
- Zero frame plaintext or cryptographic keys in logs/probes.
- Automated tests run strictly against in-memory simulation / fake TNC / fake air channel; NEVER open real serial ports or RFCOMM sockets.
- No new external libraries or modules; zero Room schema changes.

### 3. Files Touched in Shared Code & Hunk Plan
In compliance with the O-4 Owner Gate:
1. `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/model/FlashTransportType.kt` (+1 line):
   - Add `BLUETOOTH`.
2. `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/resilience/SessionHardeningPolicy.kt` (+1 line):
   - Add `FlashTransportType.BLUETOOTH` rank mapping so existing exhaustive `when` expressions and tests pass.
3. `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/ConnectionPlanner.kt` (+2 lines):
   - Add planner hook `hasBluetoothRoute(deviceId: String): Boolean = false` to `ConnectionPlanner.Links`.
4. `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt` (+10 lines):
   - Add optional constructor parameter `radioBridge: RadioChatBridge? = null`.
   - In `sendDirectViaPaths`: if LAN and hub cannot carry, query `radioBridge?.canCarry(target)` and delegate.
   - In `sendAttachment`: if target is radio-only, refuse with `"Photos and files cannot be sent by radio"`.
5. `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt` (+3 lines):
   - Wire `radioBridge` parameter into `RealFlashChatRepository`.
6. `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt` (+3 lines):
   - Wire `radioBridge` parameter into `RealFlashChatRepository`.

All other business logic, state machines, priority queues, and persistence stores live strictly in NEW files.

### 4. Five Ways This Phase Is Most Likely to Go Wrong
1. **Nonce Reuse After Process Death:** In AES-GCM, reusing a counter with the same key is fatal. Mitigated by reserving counter blocks ahead in persistent storage (`storeCounter(key, n + 64)`) and advancing past the high-water mark upon restart, plus an epoch time floor.
2. **Premature Delivery State Transition:** Displaying a message as "delivered" merely because KISS TNC accepted bytes over Bluetooth serial. Mitigated by strictly decoupling driver transmit confirmation (`'sent by radio, waiting'`) from cryptographic ACK receipt (`'delivered'`).
3. **Accidental RF Transmission in Tests:** Opening a real port or sending data frames in unit tests would key the owner's radio. Mitigated by running all automated suites over `RadioSimulation` in-memory link pairs.
4. **Queue Starvation of ACKs by Outbound Text:** If text chunks saturate the 1200-baud link, delivery ACKs cannot return and cause retransmission storms. Mitigated by a multi-lane priority queue where ACKs and emergency frames preempt bulk message chunks.
5. **Memory Leak in Segment Reassembly:** Packets dropped over the air leaving half-reassembled buffers in memory. Mitigated by `SegmentReassembler` 10-minute eviction timeout and 8-message maximum concurrency limit.

### 5. Test First Strategy
Before implementing production wiring, we write tests for pure logic:
- `RADCHAT-U01`: Pairing key derivation and directional HKDF key separation.
- `RADCHAT-U02`: Persistent counter high-water mark jumping across crash simulations.
- `RADCHAT-U03`: Outbox text segmentation and length limit refusal.
- `RADCHAT-U04`: Refusal of photos, files, and voice attachments without queuing.
- `RADCHAT-U05`: Delivery state transitions: sent waiting -> delivered only on cryptographic ACK.
- `RADCHAT-U06`: Inbound authentication, decryption, replay window, and silent drop of invalid tags.
- `RADCHAT-U07`: Profile M ciphertext-only enforcement and Profile A callsign verification.
- `RADCHAT-U08`: Priority queue ordering (EMERGENCY > TEXT > ACK > LOCATION).
- `RADCHAT-U09`: Zero-behavior-change guarantee for standard LAN peers.
