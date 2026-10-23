# Progress Log — Stream B (rvw22-b-transfer-server)

## 2026-10-22 — Stream B Medium & Low Review Fixes

### Worked on
Fixed all assigned MEDIUM and LOW findings from `docs/audit/2026-10-22-comprehensive-brutal-code-review.md` for Stream B:
- F40 (MEDIUM): RandomAccessSinkHandle stale trailing bytes truncation on file overwrite
- F41 (MEDIUM): ReceivePipeline duplicate/retransmitted chunk immediate ACK
- F56 (LOW): FlashInboundRouter verified=false completion event on whole-file mismatch
- F60 (LOW): RelayFrameRouter.spill vs MailboxQueue double-counting of dropped messages
- F64 (MEDIUM, security): RelayFrameRouter.handleInboundMeta uniform ERR_NOT_MEMBER response
- F39 (MEDIUM): MailboxQueue.deleteExpired skipping quarantined rows from notifications while still deleting from disk
- F78 (LOW): RelaySession.dispose() coroutine scope cancellation on session termination
- F38 (MEDIUM): HubFileTransfers transient local storage read error handled as interrupted upload without cancelling remote file
- F79 (INVESTIGATE): BoundedBody.boundedText ByteReadChannel cancellation on early exit
- Dead Code in Chunker.kt (~257-265): Audited ChunkBufferPool; determined NOT-A-BUG.

### Changed
1. `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/RandomAccessSinkHandle.kt`:
   - Truncate handle if `handle.size() > totalBytesExpected`. Avoids pre-allocation to respect ERROR-067.
2. `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt`:
   - When a chunk index is already present in `done`, immediately return `ReceiveEvent.ChunkAcknowledged(chunkIndex)` without re-writing or double-counting progress.
3. `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt`:
   - On whole-file hash mismatch or I/O failure, dispatch `ReceiveEvent.Completed(completeReply.copy(verified = false))` to `host.onCompleted`.
4. `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`:
   - In `spill()`, removed redundant call to `mailboxQueue.noteDropped()` since `mailboxQueue.enqueue()` already increments the counter.
   - In `handleInboundMeta()`, unified missing group and non-member responses to both emit `RelayFrame.Refused(msgId, HubErrorCodes.ERR_NOT_MEMBER)`.
   - In `sessionEnded()`, call `session.dispose()` once in `finally` after spilling in-flight messages (or immediately if empty).
5. `docs/protocol.md`:
   - Updated group metadata response specification to document that non-existent groups and non-member queries both receive `ERR_NOT_MEMBER`.
6. `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt`:
   - In `deleteExpired()`, continue deleting all expired rows from SQLite (including quarantined rows), but query only rows with `undeliverable_reason IS NULL` for the returned list of expired notifications.
7. `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt`:
   - Added `isDisposed: Boolean get() = !scope.isActive` to expose scope state for lifecycle tracking.
8. `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileError.kt`:
   - Updated `LocalStorage(public val what: String, override val transient: Boolean = false) : HubFileError`.
9. `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt`:
   - Classified read exceptions: missing file / shortened file are permanent; general I/O hiccups are transient.
   - Wrapped `sealedChunk(index)` in `retrying { ... }` in the chunk upload loop.
   - If transient error persists, return `interrupted(sealed.error)` without cancelling on the hub or deleting local state.
   - Guarded `api.cancel` in `fail()` with `!error.transient`.
10. `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/BoundedBody.kt`:
    - Cancel `channel.cancel(null)` when `filled >= limit`.

### Verification
Every finding was verified with unit tests and mutation checks:
- **F40**: `OkioRandomAccessSinkHandleTest.overwritingLargerFileTruncatesStaleTrailingBytes` (passed; mutation failed on revert)
- **F41**: `ReceivePipelineTest.duplicateChunkEmitsImmediateAckWithoutDoubleCountingBytes` (passed; mutation failed on revert)
- **F56**: `FlashInboundRouterTest.hashMismatchDispatchesCompletedEventWithVerifiedFalse` (passed; mutation failed on revert)
- **F60**: `RelayFrameRouterTest.spillDropIncrementsDroppedMetricOnlyOnce` (passed; mutation failed on revert)
- **F64**: `RelayFrameRouterTest.nonExistentGroupMetaReturnsErrNotMemberIndistinguishableFromNonMember` (passed; mutation failed on revert)
- **F39**: `MailboxQuarantineTest.expiredQuarantinedRowsAreDeletedFromDiskWithoutEmittingExpiredNotification` (passed; mutation failed on revert)
- **F78**: `RelayFrameRouterTest.sessionEndedCallsDisposeAndCancelsSessionScope` (passed; mutation failed on revert)
- **F38**: `HubFileTransfersTest.transientDiskReadErrorDuringUploadDoesNotCancelRemoteUploadAndCanResume` & `permanentDiskReadErrorCancelsRemoteUpload` (passed; mutation failed on revert)
- **F79**: `BoundedBodySocketTest.testBoundedTextSocketBehaviorOnEarlyExit` against local loopback HTTP server (passed)

Full test suites verified:
- `:core:transfer:jvmTest` (PASSED)
- `:core:engine:jvmTest` (PASSED)
- `:core:enterprise:jvmTest` (PASSED)
- `:server:test` (653 tests passed; 2 known timing flakes passed on standalone rerun)
- `:desktop:compileKotlinJvm` (PASSED)
- `:app:compileDebugKotlin` (PASSED)

### Findings Summary
| Finding | Severity | Status | Commit | Mutation Check |
|---|---|---|---|---|
| F40 | MEDIUM | FIXED | `4215db57` | PASS (revert produced 5 MB instead of 1 MB) |
| F41 | MEDIUM | FIXED | `b70b0c71` | PASS (revert produced Ignored without ACK) |
| F56 | LOW | FIXED | `9e670fc7` | PASS (revert produced verified=true on mismatch) |
| F60 | LOW | FIXED | `bc3845a1` | PASS (revert produced metric count 2 instead of 1) |
| F64 | MEDIUM | FIXED | `236a2d5f` | PASS (revert produced timeout on non-existent group) |
| F39 | MEDIUM | FIXED | `b6913196` | PASS (revert emitted EXPIRED for quarantined row) |
| F78 | LOW | FIXED | `81b07aa5` | PASS (revert timed out awaiting isDisposed) |
| F38 | MEDIUM | FIXED | `10194fe6` | PASS (revert returned Failed and deleted upload) |
| F79 | INVESTIGATE | FIXED | `97b8750d` | PASS (verified loopback channel cancellation) |
| Chunker Dead Code | AUDIT §7 | NOT-A-BUG | N/A | Buffer pool is bounded to 8 arrays, GC collects unreturned buffers |

### Remaining
All Stream B tasks are completed. Changes are committed on branch `agent/rvw22-b-transfer-server`. Device verification on physical hardware remains owed (logged in `backlog-additions.md` under `R22B-01..09`).

### Next AI
When merging into `dev`, ensure all stream branches rebase cleanly without touching out-of-scope files. Run full regression test suite.
