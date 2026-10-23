# Errors Log — Stream B (rvw22-b-transfer-server)

## ERROR-770 — RandomAccessSinkHandle leaves stale trailing bytes on smaller file overwrite

### Date
2026-10-22

### Area
Transfer Engine / File Storage (`core:transfer`)

### Symptoms
When overwriting an existing larger file with a smaller file, trailing stale bytes from the old file remained, causing integrity hash mismatch.

### Root cause
`RandomAccessSinkHandle.open()` truncated file to 0 when `totalBytesExpected == 0L`, but when `totalBytesExpected > 0L` it opened with write access without resizing or truncating if the existing file on disk was already larger than `totalBytesExpected`. (Note: expanding/pre-allocating is forbidden due to ERROR-067 sparse-file issues).

### Working fix
In `RandomAccessSinkHandle.open()`, inspect `handle.size()`. If `handle.size() > totalBytesExpected`, call `handle.resize(totalBytesExpected)` to truncate stale bytes. Do not resize if `handle.size() <= totalBytesExpected`.

### Verification
`OkioRandomAccessSinkHandleTest.overwritingLargerFileTruncatesStaleTrailingBytes`: 5 MB file overwritten with 1 MB payload verified to truncate to exactly 1 MB. Mutation check verified test failure when resize removed.

### Related files
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/RandomAccessSinkHandle.kt`
- `core/transfer/src/jvmTest/kotlin/com/transfer/flash/core/transfer/chunked/OkioRandomAccessSinkHandleTest.kt`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-771 — ReceivePipeline ignores duplicate chunks without ACK, causing sender stall

### Date
2026-10-22

### Area
Transfer Engine / Chunk Protocol (`core:transfer`)

### Symptoms
Retransmitted or duplicate chunks were dropped silently without an acknowledgement frame, forcing the sender to stall until its retry timeout expired.

### Root cause
`ReceivePipeline.onChunkReceived` checked `if (done.contains(chunkIndex)) return ReceiveEvent.Ignored`. Because no ACK was returned or emitted, the sender never learned that the receiver already possessed the chunk.

### Working fix
When `done.contains(chunkIndex)` is true, immediately return `ReceiveEvent.ChunkAcknowledged(chunkIndex)` without re-writing bytes, incrementing received byte counters, or re-adding to the `done` bitset.

### Verification
`ReceivePipelineTest.duplicateChunkEmitsImmediateAckWithoutDoubleCountingBytes`: duplicate chunks verify immediate ACK emission without double-counting bytes. Mutation check verified test failure when reverting to `ReceiveEvent.Ignored`.

### Related files
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt`
- `core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-772 — Whole-file hash mismatch completion event reports verified = true

### Date
2026-10-22

### Area
Transfer Engine / Inbound Router (`core:engine`)

### Symptoms
When a whole-file checksum verification failed with `MISMATCH`, the host listener `onCompleted` was invoked with a completion event marked `verified = true`.

### Root cause
`FlashInboundRouter.onFileTransferComplete` received the incoming `ReceiveEvent.Completed` (which held `verified = true` from transport reception) and did not substitute the verified flag when whole-file verification produced a mismatch or I/O failure.

### Working fix
On hash mismatch or I/O verification failure, create `completeReply.copy(verified = false)` and pass `ReceiveEvent.Completed(correctedReply)` to `host.onCompleted`.

### Verification
`FlashInboundRouterTest.hashMismatchDispatchesCompletedEventWithVerifiedFalse`: verifies `onCompleted` receives `verified = false`. Mutation check verified test failure when unpatched.

### Related files
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt`
- `core/engine/src/commonTest/kotlin/com/transfer/flash/core/engine/FlashInboundRouterTest.kt`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-773 — Double-counting dropped messages in RelayFrameRouter.spill

### Date
2026-10-22

### Area
Server Relay / Mailbox Queue Metrics (`server`)

### Symptoms
Messages dropped when spilling disconnected session in-flight queues were counted twice in the dropped metrics.

### Root cause
`RelayFrameRouter.spill()` called `mailboxQueue.noteDropped()` when `enqueue()` returned `Dropped`. However, `MailboxQueue.enqueue()` internally already calls `noteDropped()` whenever a message is dropped.

### Working fix
Removed the redundant `mailboxQueue.noteDropped()` invocation from `RelayFrameRouter.spill()`.

### Verification
`RelayFrameRouterTest.spillDropIncrementsDroppedMetricOnlyOnce`: verifies metric increments by exactly 1 when an enqueued message is dropped. Mutation check verified test failure (counter was 2).

### Related files
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`
- `server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-774 — Timing and existence enumeration oracle on RelayFrameRouter.handleInboundMeta

### Date
2026-10-22

### Area
Server Relay / Security (`server`)

### Symptoms
Inbound `FLASH_GROUP_META` messages for an existing group answered non-members with `ERR_NOT_MEMBER`, whereas non-existent groups produced no response, allowing group id enumeration by unauthenticated/non-member senders.

### Root cause
`handleInboundMeta` only checked membership if the group was found in `groupDirectory`, remaining silent if `group == null`.

### Working fix
Unified behavior: `if (group == null || !group.members.contains(session.deviceId))` sends `RelayFrame.Refused(msgId, HubErrorCodes.ERR_NOT_MEMBER)`. Both non-existent groups and non-member requests now return identical `ERR_NOT_MEMBER` frames. Updated `docs/protocol.md`.

### Verification
`RelayFrameRouterTest.nonExistentGroupMetaReturnsErrNotMemberIndistinguishableFromNonMember`: verifies both cases yield identical `ERR_NOT_MEMBER` response. Mutation check verified test failure when unpatched.

### Related files
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`
- `server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt`
- `docs/protocol.md`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-775 — Quarantined messages deleted by deleteExpired emit duplicate EXPIRED notifications

### Date
2026-10-22

### Area
Server Relay / Mailbox Queue (`server`)

### Symptoms
Messages previously quarantined and notified as `DROPPED` received a second terminal notification (`EXPIRED`) days later when expiration sweep ran.

### Root cause
`MailboxQueue.deleteExpired` queried `WHERE expires_at <= ?` without excluding quarantined rows (`undeliverable_reason IS NOT NULL`), resulting in quarantined rows being included in the returned list of expired notifications.

### Working fix
In `MailboxQueue.deleteExpired`, delete all expired rows from disk/db (preserving ADR-223 disk accounting), but select and report for `ExpiredRow` notifications only those with `undeliverable_reason IS NULL`.

### Verification
`MailboxQuarantineTest.expiredQuarantinedRowsAreDeletedFromDiskWithoutEmittingExpiredNotification`: verifies quarantined + expired row is deleted from disk without returning an `ExpiredRow` notification. Mutation check verified failure when filter removed.

### Related files
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt`
- `server/src/test/kotlin/com/transfer/flash/server/mailbox/MailboxQuarantineTest.kt`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-776 — RelaySession.dispose() never called, leaking session coroutine scope

### Date
2026-10-22

### Area
Server Relay / Session Lifecycle (`server`)

### Symptoms
`RelaySession.dispose()` was never called anywhere in the server codebase, leaving `SupervisorJob` active in memory after session termination.

### Root cause
`RelayFrameRouter.sessionEnded` cleaned up in-flight messages and closed connections but omitted calling `session.dispose()`.

### Working fix
Call `session.dispose()` in `RelayFrameRouter.sessionEnded()`, exactly once, after any background in-flight spilling finishes (in `finally` block if spilling, or immediately if empty).

### Verification
`RelayFrameRouterTest.sessionEndedCallsDisposeAndCancelsSessionScope`: verifies `session.isDisposed` becomes true upon session termination. Mutation check confirmed test failure before router change.

### Related files
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`
- `server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-777 — Transient local storage read errors permanently cancel upload on remote hub

### Date
2026-10-22

### Area
Enterprise Hub Files / Upload Transfer (`core:enterprise`)

### Symptoms
A transient local I/O read glitch during chunk upload triggered `fail(sealed.error)`, invoking `api.cancel(...)` on the hub and deleting local state, permanently aborting nearly completed uploads.

### Root cause
`HubFileError.LocalStorage` had hard-coded `transient = false`, and `HubFileTransfers` chunk upload loop treated any `sealedChunk` error as permanent `fail()`.

### Working fix
1. Made `HubFileError.LocalStorage(val what: String, override val transient: Boolean = false)`.
2. Classified local read exceptions: permanent for missing file (`FileNotFoundException`, `NoSuchFileException`), truncated/shrunk file (`EOFException`, "shorter than expected"), or illegal bounds; transient for general I/O hiccups.
3. In `HubFileTransfers` chunk loop, wrapped `sealedChunk(index)` in `retrying { ... }`. If still failing with transient error, return `interrupted(error)` to preserve local state and remote upload for later resume. If permanent, call `fail(error)` to cancel and delete state.
4. In `fail()`, guard remote `api.cancel` with `!error.transient`.

### Verification
`HubFileTransfersTest.transientDiskReadErrorDuringUploadDoesNotCancelRemoteUploadAndCanResume`: verifies transient read returns `Interrupted`, keeps server upload intact and resumes to completion. `permanentDiskReadErrorCancelsRemoteUpload` verifies missing source file cancels remote upload and removes local state. Mutation check confirmed test failure on unpatched code.

### Related files
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileError.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt`
- `core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfersTest.kt`

### Status
OPEN (unit-tested, not device-verified)

---

## ERROR-778 — BoundedBody.boundedText unconsumed channel cancellation

### Date
2026-10-22

### Area
Enterprise HTTP Client (`core:enterprise`)

### Symptoms
When a response body exceeds `maxBytes`, reading terminates early at `limit = maxBytes + 1` bytes without explicitly cancelling the underlying `ByteReadChannel`.

### Root cause
`BoundedBody.boundedText` broke out of its reading loop upon reaching `limit` without signalling cancellation to the channel.

### Working fix
Explicitly call `channel.cancel(null)` when `filled >= limit` to cancel the unconsumed read channel.

### Verification
`BoundedBodySocketTest.testBoundedTextSocketBehaviorOnEarlyExit`: local loopback HTTP server test verifying early cutoff at limit + 1 bytes and connection handling.

### Related files
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/BoundedBody.kt`
- `core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/BoundedBodySocketTest.kt`

### Status
OPEN (unit-tested, not device-verified)
