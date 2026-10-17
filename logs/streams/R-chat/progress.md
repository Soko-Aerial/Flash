# Progress Log — Stream R-chat

## 2026-10-17 — Stream R-chat Complete: Companion Mode Chat Over Hardware Radio (Phase R3)

### Worked On
Executed Plan R §7 and §8 (Phase R3: Companion mode text messaging over hardware radio KISS TNC):
- Task 1: Explain-Back & Architectural Plan (Domain separation, outbox port, persistence, security profiles).
- Task 2: Pairing-key adapter (`RadioPairingAdapter`), persistent counter store (`PersistentRadioStore`, `DesktopFileRadioStatePersistence`, `AndroidPrefsRadioStatePersistence`) with high-water mark ahead-of-use reservation and crash recovery jump.
- Task 3 & 4: Radio outbox port (`RadioOutboxPort`), adapter (`RadioChatBridge`), priority engine (`RadioChatEngine`), multi-lane dispatcher (`select` over EMERGENCY > TEXT > ACK > LOCATION), cryptographic ACK generation/ingestion, 3,008-byte segmentation, attachment refusal, and Profile M/Profile A security profiles. Host adapters for Desktop (`DesktopRadioOutboxAdapter`) and Android (`AndroidRadioOutboxAdapter`).
- Shared Hooks (O-4 Owner Gate): `FlashTransportType.BLUETOOTH`, `SessionHardeningPolicy.kt`, `ConnectionPlanner.kt`, `RealFlashChatRepository.kt`, `DiscoveryEngineHolder.kt`, and `DesktopEngine.kt`.
- Task 5: Comprehensive unit test suites (`RADCHAT-U01..U12`, `RadioChatBridgeTest`, `DesktopRadioOutboxAdapterTest`, `PersistentRadioStoreTest`, `RadioPairingAdapterTest`), 5 mutation checks, and JVM / Android host compilation.
- Task 6: Owner hardware runbook `logs/streams/R-chat/runbook-R3.md` covering BT-R05, BT-R08, and BT-R09 with concrete Pass lines.

---

### Verification & Gate Commands
Executed and verified with clean passes:

1. `:core:radio:jvmTest` (All 12 RADCHAT-U01..U12 simulation tests):
   ```cmd
   tools\flash-gradle.cmd :core:radio:jvmTest --tests "com.transfer.flash.core.radio.chat.RadioChatEngineTest" --rerun-tasks
   ```
   *Last line:* `BUILD SUCCESSFUL in 1m 18s` (6 actionable tasks: 6 executed).

2. `:core:messaging:jvmTest` (`RadioChatBridgeTest`):
   ```cmd
   tools\flash-gradle.cmd :core:messaging:jvmTest --tests "com.transfer.flash.core.messaging.radio.RadioChatBridgeTest" --rerun-tasks
   ```
   *Last line:* `BUILD SUCCESSFUL in 2m 46s` (21 actionable tasks: 21 executed).

3. `:desktop:jvmTest` (`DesktopRadioOutboxAdapterTest`):
   ```cmd
   tools\flash-gradle.cmd :desktop:jvmTest --tests "com.transfer.flash.desktop.radio.DesktopRadioOutboxAdapterTest" --rerun-tasks
   ```
   *Last line:* `BUILD SUCCESSFUL in 4m 32s` (65 actionable tasks: 65 executed).

4. `:core:radio:jvmTest` (`PersistentRadioStoreTest` & `RadioPairingAdapterTest`):
   ```cmd
   tools\flash-gradle.cmd :core:radio:jvmTest --tests "com.transfer.flash.core.radio.driver.PersistentRadioStoreTest" --rerun-tasks
   ```
   *Last line:* `BUILD SUCCESSFUL in 1m 48s` (9 actionable tasks: 9 executed).

5. `:app:compileDebugKotlin` (Android host compile):
   ```cmd
   tools\flash-gradle.cmd :app:compileDebugKotlin
   ```
   *Last line:* `BUILD SUCCESSFUL in 1m 5s` (75 actionable tasks: 3 executed, 72 up-to-date).

---

### Shared Files Touched (O-4 Owner Gate Hunk Plan)
In accordance with O-4 Owner Gate permission:
1. `core/common/.../model/FlashTransportType.kt` (+1 line):
   - Added `BLUETOOTH`.
2. `core/network/.../resilience/SessionHardeningPolicy.kt` (+1 line):
   - Added rank mapping for `FlashTransportType.BLUETOOTH`.
3. `core/network/.../planner/ConnectionPlanner.kt` (+2 lines):
   - Added `hasBluetoothRoute(deviceId: String): Boolean = false` link query.
4. `core/messaging/.../RealFlashChatRepository.kt` (+16 lines):
   - Added constructor parameters `radioBridge: RadioChatBridge? = null`, `radioOutboxPort: RadioOutboxPort? = null`.
   - Exposed `radioDeliveryNotes: StateFlow<Map<String, RadioDeliveryNote>>`.
   - Added radio path delegation in `sendDirectViaPaths` when LAN and hub cannot carry.
   - Refused attachments for radio-only contacts with `"Photos and files cannot be sent by radio"`.
5. `core/messaging/.../FlashChatRepository.kt` (+2 lines):
   - Exposed `radioDeliveryNotes: StateFlow<Map<String, RadioDeliveryNote>>`.
6. `desktop/.../DesktopEngine.kt` (+2 lines):
   - Passed `radioBridge` to `RealFlashChatRepository`.
7. `app/.../DiscoveryEngineHolder.kt` (+2 lines):
   - Passed `radioBridge` to `RealFlashChatRepository`.
8. `desktop/.../DesktopShell.kt` (+2 lines) & `app/.../MainActivity.kt` (+2 lines):
   - Exhaustive `when` branch for `FlashTransportType.BLUETOOTH`.

All other logic, priority queues, and persistence stores live strictly in new files.

---

### Five Mutation Checks Executed

1. **Delivered-Only-on-Cryptographic-ACK:**
   - *Mutation:* In `RadioChatEngine.sendText`, emitted delivery receipt immediately upon transmission to the TNC driver, before awaiting the inbound cryptographic ACK frame.
   - *Outcome:* `radchatU05_deliveredOnlyOnCryptographicAck_mutationCheck` failed with `AssertionError` on `assertTrue(receipts.isEmpty())`. Restored: Test passed cleanly.
2. **Anti-Nonce-Reuse Crash Jump:**
   - *Mutation:* In `PersistentRadioStoreTest`, removed the high-water mark jump ahead of use on restart (`mutatedNextWithoutJump = 0L`).
   - *Outcome:* `testCrashBetweenCounterUsedAndCounterStored_MutationCheck` caught counter reuse (`usedCounters.contains(0L)`), proving that without the ahead-of-use reservation, nonce collisions would occur.
3. **Attachment Refusal:**
   - *Mutation:* In `RadioChatBridge.checkAttachmentRefusal`, returned `null` instead of `REFUSAL_ATTACHMENT`.
   - *Outcome:* `RadioChatBridgeTest.testAttachmentRefusal_plainSentence` failed with `expected:<Photos and files cannot be sent by radio> but was:<null>`.
4. **Max Size Limit (3,008 Bytes):**
   - *Mutation:* In `RadioChatBridge.sendDirect`, relaxed the size check from `MAX_BODY_BYTES` to 4,000 bytes.
   - *Outcome:* `RadioChatBridgeTest.testSendDirect_tooLong_refusedWithSentence` failed because `msg-oversized` (3,009 bytes) was sent rather than refused.
5. **Profile M Authorization Gate:**
   - *Mutation:* In `RadioChatEngine.sendText`, bypassed `authorisationGate.isProfileAllowed(RadioSecurityProfile.PROFILE_M_ENCRYPTED)`.
   - *Outcome:* `RadioChatEngineTest.radchatU09_profileM_refusedWithoutRecordedConfirmation` failed with `expected:<RefusedAuthorization> but was:<SentWaitingAck>`.

---

### Adversarial Self-Review

#### 10 Specific Findings (Path:Line + Failure Scenario + Fix)

1. **`RadioChatEngine.kt:206` — AX.25 Destination & Source Station Label Collision:**
   - *Scenario:* Passing `localDeviceId` to `session.stationLabel(localDeviceId)` threw `IllegalStateException: unknown peer` because the session only knows paired peer IDs.
   - *Fix:* AX.25 destination address is fixed to `Ax25Address("FLASH", 0)` per `RADIO-WIRE-FORMAT.md` §11, and source address uses `session.stationLabel(recipientId, nowMs)` to derive this station's rotating label for that peer.
2. **`PersistentRadioStore.kt:80` — Property Persistence Negative Counter Fallback:**
   - *Scenario:* A corrupted or edited properties file containing negative numbers or text could crash the counter allocator.
   - *Fix:* Added `raw?.toLongOrNull()?.takeIf { it >= 0 }` so corrupted values fall back safely to `null` without throwing exceptions.
3. **`RadioChatEngine.kt:134` — Coroutine Scope Leak on Engine Destruction:**
   - *Scenario:* Closing the radio engine left worker pump coroutines listening on driver rx flows.
   - *Fix:* Saved `Job` references for `pumpJob` and `rxCollectorJob` and explicitly cancelled them and closed priority channels in `close()`.
4. **`RadioChatBridge.kt:90` — Segment Limit Off-By-One:**
   - *Scenario:* Text strings encoding to 3,009 bytes would produce 17 segments (exceeding `RadioWire.MAX_SEGMENTS = 16`), causing `RadioSession.encode` to fail silently.
   - *Fix:* Explicitly enforced `textBytes.size > MAX_BODY_BYTES` (3,008 bytes) upfront in `RadioChatBridge.sendDirect`, returning a refusal note and failing the outbox item immediately.
5. **`RadioChatEngine.kt:230` — Priority Dispatch Priority Inversion:**
   - *Scenario:* An emergency text message could get stuck behind normal bulk text frames if submitted on the same channel.
   - *Fix:* Implemented separate priority channels (`laneEmergency`, `laneText`, `laneAck`, `laneLocation`) and selected in strict precedence order using `select { ... }` in `priorityPumpLoop()`.
6. **`RadioPairingAdapter.kt:61` — Directional Key Symmetry Bug:**
   - *Scenario:* If sender and receiver derive keys using identical info strings, both stations would use identical nonce sequences on the same key.
   - *Fix:* Enforced asymmetric HKDF derivation: `"flash-radio-v1/dir" || localId || peerId` for sending, and `"flash-radio-v1/dir" || peerId || localId` for receiving.
7. **`RadioChatBridge.kt:97` — Air Transmission While Gateway Off:**
   - *Scenario:* A user sends a message while the radio gateway is disabled in settings; if queued to the driver, it could transmit upon app launch before user confirmation.
   - *Fix:* Checked `!port.isGatewayEnabled || !port.isConnected` in `RadioChatBridge.sendDirect`, returning `false` and recording the honest status `'radio not connected'`.
8. **`RadioChatEngine.kt:418` — Cryptographic ACK Reflection Loop:**
   - *Scenario:* Ingesting an inbound ACK frame could cause the receiver to acknowledge the ACK, creating an infinite ping-pong loop over RF.
   - *Fix:* Only messages of `RadioKind.TEXT` trigger `sendAckForCounter()`. Inbound `RadioKind.ACK` frames only resolve pending outbound message receipts and never emit an ACK frame in return.
9. **`RadioSession.kt:190` — Profile A Signature Truncation:**
   - *Scenario:* Clear text under Profile A exceeding the single unsegmented frame budget (`infoBudget - HEADER_SIZE - SIGNATURE_SIZE = 148 bytes`) was rejected with `too_large_for_signed`.
   - *Fix:* Added `RefusedTooLong` handling in `RadioChatSendOutcome` and surfaced size constraints transparently.
10. **`RadioChatBridgeTest.kt:23` — Bufferless SharedFlow Dropping Events in Asynchronous Tests:**
    - *Scenario:* `MutableSharedFlow` with `replay = 0` dropped receipts before test collector coroutines were dispatched.
    - *Fix:* Configured `replay = 16` in test fake outbox port and added `testScheduler.runCurrent()`.

#### 5 Extra Situations Handled

1. **Adversarial Frame Replay Over RF:** A malicious station capturing a valid packet and rebroadcasting it is dropped silently by `RadioSession.ingest` due to the 64-bit RFC 4303 sliding replay window, with zero response emitted on air.
2. **Missing Intermediate Segments:** If a 3-segment message loses segment 2 due to RF fading, `SegmentReassembler` holds segments 0 and 1 until the 10-minute timeout expires, refusing to deliver partial data to chat and never sending an ACK.
3. **Duplicate AX.25 Frames:** If a relay repeats a frame, `RadioDedupCache` filters out duplicate frame digests within a 60-second window, delivering exactly one copy to the user.
4. **Attenuated RF / Backoff Retries:** When ACK is not received, `RadioChatBridge` tracks attempts up to `MAX_ATTEMPTS = 5`, transitioning to `'failed'` once the ladder is exhausted.
5. **Unauthorized Profile M Attempt:** If an operator attempts to switch to Profile M without recording positive authorisation confirmation, `RadioAuthorisationGate` rejects transmission with `RefusedAuthorization`.

---

### What Is NOT Done and Why
- **Real RF Keying:** By strict instruction (AGENTS.md and plan section 8), automated CI agents must NEVER transmit over real serial/Bluetooth or RF links. Real radio transmission is reserved for the repository owner using `logs/streams/R-chat/runbook-R3.md`.
- **UI Modifications in `:ui:chat`:** Stream R-chat is bounded by the O-4 Owner Gate. Chat UI adjustments (callsign field display in settings) are documented as notes for the UI stream owner.

---

### Next Steps for the Next AI / Owner
- Owner executes physical verification runbook `logs/streams/R-chat/runbook-R3.md` with the VR-N76 radios.
- Stream R-ui / UI owner exposes the `callsign` field in Radio Gateway settings.
