# Core Transfer Module (`:core:transfer`)

The `:core:transfer` module contains the high-throughput, multi-stream chunked file transfer engine. It is designed to transfer files ranging from small documents to multi-gigabyte videos and entire folder hierarchies without loading entire files into memory.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-transfer:v2.1.0-beta")
}
```

---

## 2. Core Architecture & Transfer Lifecycle

Chunked transfer rides the same WebSocket mesh as chat (ADR-016); this module is transport-independent and talks to the network
through the `FLASH_XFER` / `FLASH_FILE_ACK` text messages (`protocol/WsTransferMessages`) and binary chunk frames (`ChunkFrame`, framing v2).

1. **Send side (`MultiStreamDispatcher`, `SendPipeline`):**
   * `Chunker` plans the chunks: default **64 KiB**, clamped to **16 KiB..256 KiB**, and `adaptiveSize` grows the size with measured throughput (256 KiB at 64 MiB/s and above). (Some KDoc in the module still says "up to 1 MB"; the code clamps at 256 KiB.)
   * One *materializer* reads the file sequentially and routes each serialized chunk to `N` worker streams (static assignment `index % N`). The stream count comes from `FlashTransferProfile`: **LOW 1, MEDIUM 2, HIGH 4** (the profile also sets chunk base size and queue depths).
   * At-least-once on the wire, exactly-once write: when a stream dies its unconfirmed frames are redistributed to the survivors.
   * Pause is cooperative: the materializer parks before its next chunk while acknowledgements keep being tracked.
2. **Receive side (`ReceivePipeline`, `MultiStreamReceiver`):**
   * Each chunk's SHA-256 is verified **before** it is written, then written at its offset through a `RandomAccessSinkHandle` (no whole-file buffer). Duplicates are ignored, and completed chunks are kept in a `ResumeBitVector` (persisted per chunk in `TransferChunkEntity` when resume is enabled).
   * When all chunks are in, `WholeFileVerifier` checks the whole-file SHA-256 before the file is released; a mismatch fails the transfer on both ends. (BLAKE3 was deferred, ADR-010.)
   * An inbound offer waits for `acceptIncoming` unless the sender is paired and `autoAcceptIncoming` is on; a receive that cannot fit on disk is refused by name; failures are worded for people by `TransferFailureText`.
3. **Resume.** `TransferReconnectResumePolicy` re-offers a failed transfer when the session comes back; the receiver answers with its done-set so only missing chunks are sent.
4. **Path safety.** Relative paths in folder transfers are sanitized with `FlashPathSanitizer.sanitizeRelativePath` (in `:core:engine`) before anything is written. `DestinationPolicy` (Android) picks the destination.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashTransferRepository`

Located in [`com.transfer.flash.core.transfer.FlashTransferRepository`](../../../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/FlashTransferRepository.kt) (abridged):

```kotlin
public interface FlashTransferRepository {
    /** Every row: active, finished, failed. */
    public val activeTransfers: StateFlow<List<FlashTransfer>>

    public suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<FlashTransferId>
    // an overload adds wireFileId (group fan-out) and more

    public suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit>
    public suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit>
    public suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit>
    public suspend fun acceptIncoming(transferId: FlashTransferId): FlashResult<Unit>
    public suspend fun declineIncoming(transferId: FlashTransferId): FlashResult<Unit>
    public fun clearFinishedHistory()
    public suspend fun pauseForSystem(transferId: FlashTransferId, reason: String): FlashResult<Unit>
}
```

Observe one transfer by filtering `activeTransfers` on `id`; there is no `observeTransfer`. A `FlashTransfer` carries `bytesDone`,
`bytesTotal`, `state` (`Offered`, `Queued`, `Transferring`, `Paused`, `Verifying`, `Completed`, `Failed`, `Cancelled`),
`speedBytesPerSec`, `etaSeconds`, `errorMessage`, `waitReason` and, for swarm rows, `holdersOnline`, `canGoOffline`,
`pieceBlocks` and `recipients`. The `onIncoming*` / `onInboundFrame` methods are for the engine glue, not for consumers.

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransferState

fun executeFileTransfer(
    repository: FlashTransferRepository,
    peer: FlashDevice,
    fileUri: String,
    fileName: String,
    fileSize: Long,
) {
    scope.launch {
        repository.sendFile(peer, fileUri, fileName, fileSize).onSuccess { transferId ->
            println("Initiated transfer: $transferId")
            repository.activeTransfers
                .map { rows -> rows.firstOrNull { it.id == transferId } }
                .takeWhile { it == null || it.state !in setOf(FlashTransferState.Completed, FlashTransferState.Failed, FlashTransferState.Cancelled) }
                .collect { t ->
                    t ?: return@collect
                    val progress = if (t.bytesTotal > 0) (t.bytesDone * 100 / t.bytesTotal).toInt() else 0
                    println("Transfer progress: $progress% @ ${t.speedBytesPerSec / (1024 * 1024)} MB/s (${t.state})")
                }
        }
    }
}
```
