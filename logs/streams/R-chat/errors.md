# Error and Fix Log — Stream R-chat

Reserved ERROR range: ERROR-700 through ERROR-729.

---

## ERROR-700 — Coroutine Lifecycle Leaks in Simulation Tests Causing UncompletedCoroutinesError

### Date
2026-10-17

### Area
Radio Chat Engine / Test Coroutines

### Symptoms
`kotlinx.coroutines.test.UncompletedCoroutinesError` at `TestBuilders.kt:353` across all `RadioChatEngineTest` test cases.

### Environment
- OS: Windows 11
- Gradle: 9.5.0
- Kotlin: 2.x
- Target: JVM Test (`:core:radio:jvmTest`)

### Error
```text
RadioChatEngineTest[jvm] > radchatU01_textRoundTrip_deliveredAndCryptographicAckReceived[jvm] FAILED
    kotlinx.coroutines.test.UncompletedCoroutinesError at TestBuilders.kt:353
```

### Root Cause
`KissTncDriver`, `FakeTnc`, and `RadioChatEngine` launch continuous background pump/collector loops inside their passed `CoroutineScope`. In the unit tests, `this` (`TestScope`) was passed as the scope. `runTest` requires all coroutines launched in `TestScope` to complete before exiting, but background pump loops run indefinitely.

### Working Fix
Updated test station construction to pass `backgroundScope` (provided by `kotlinx-coroutines-test`) instead of `this`. Coroutines launched in `backgroundScope` run during the test and are automatically cancelled when the test body completes.

### Verification
All 12 tests in `RadioChatEngineTest` completed without `UncompletedCoroutinesError`.

### Related files
- `core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt`

### Status
RESOLVED

---

## ERROR-701 — Profile A Plaintext Signed Encode Refusal Due to Unconfigured Signature Scheme

### Date
2026-10-17

### Area
Radio Chat Engine / Wire Protocol

### Symptoms
`radchatU08_profileATransparency_operatorCallsignAppearsOnAir` timed out waiting for AX.25 frame transmission on air.

### Environment
- OS: Windows 11
- Gradle: 9.5.0
- Target: JVM Test (`:core:radio:jvmTest`)

### Error
```text
RadioChatEngineTest[jvm] > radchatU08_profileATransparency_operatorCallsignAppearsOnAir[jvm] FAILED
    kotlinx.coroutines.TimeoutCancellationException at RadioChatEngineTest.kt:385
```

### Root Cause
`RadioSession.encodeSigned` requires both `signer: RadioSignatureScheme?` and `localPublicKey: ByteArray?`. When unconfigured, it returns `RadioEncode.Refused("no_signer")`, which caused `RadioChatEngine.sendText` to return `RadioChatSendOutcome.EncodeFailed` without transmitting any frame to the TNC driver.

### Working Fix
Configured a test `RadioSignatureScheme` producing a 64-byte raw `r||s` ECDSA signature and 64-byte public key on `RadioSession` initialization in the test fixture.

### Verification
`radchatU08` executes in under 200ms and asserts the operator callsign in the transmitted AX.25 frame header.

### Related files
- `core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt`

### Status
RESOLVED

---

## ERROR-702 — Dropped Delivery Receipts in Bridge Tests Due to Zero Replay SharedFlow

### Date
2026-10-17

### Area
Radio Messaging / SharedFlow Test Synchronization

### Symptoms
`RadioChatBridgeTest.testDeliveryReceipt_updatesNoteToDelivered` failed with `AssertionError: expected:<msg-ack> but was:<null>`.

### Environment
- OS: Windows 11
- Target: JVM Test (`:core:messaging:jvmTest`)

### Error
```text
RadioChatBridgeTest[jvm] > testDeliveryReceipt_updatesNoteToDelivered[jvm] FAILED
    java.lang.AssertionError: expected:<msg-ack> but was:<null>
```

### Root Cause
In `FakeRadioOutboxPort`, `receiptFlow` was created with `MutableSharedFlow(extraBufferCapacity = 16)` and default `replay = 0`. Because `RadioChatBridge` starts its collector inside a background coroutine launched in `scope`, the collector had not yet attached when `port.receiptFlow.emit(...)` was called. The emitted receipt was dropped by the bufferless flow.

### Working Fix
Configured `MutableSharedFlow(replay = 16)` in `FakeRadioOutboxPort` and added `testScheduler.runCurrent()` to advance virtual time and process pending collectors.

### Verification
All 7 tests in `RadioChatBridgeTest` passed cleanly.

### Related files
- `core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/radio/RadioChatBridgeTest.kt`

### Status
RESOLVED
