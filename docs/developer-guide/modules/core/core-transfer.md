# Core Transfer Module (`:core:transfer`)

The `:core:transfer` module contains the high-throughput, multi-stream chunked file transfer engine. It is designed to transfer files ranging from small documents to multi-gigabyte videos and entire folder hierarchies without loading entire files into memory.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-transfer:2.0.0-beta")
}
```

---

## 2. Core Architecture & Transfer Lifecycle

The transfer engine is split into two complementary pipelines:

1. **Send Pipeline (`MultiStreamDispatcher`):**
   * Computes a [`ChunkPlan`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt) dividing files into bounded chunks (default 64KB, adaptive up to 1MB).
   * **Lock-free materializer coroutine:** Streams chunks sequentially from an Okio `BufferedSource`, distributing chunk frames across $N$ worker coroutines.
   * **Multi-Stream parallelism:** Utilizes multiple parallel socket channels (1 to 8 streams) to saturate available Wi-Fi / LAN bandwidth.
   * **Redistribution on stream failure:** If an underlying stream channel dies, unacknowledged chunk frames are caught and redistributed to surviving streams without aborting the transfer.
   * **Cooperative Pause & Resume:** Pausing does not abort the dispatcher; it freezes reading and transmission while keeping the ACK tracking alive. Resuming re-opens streaming from the last unconfirmed chunk.
2. **Receive Pipeline (`ReceivePipeline`):**
   * Listens for inbound binary chunk frames.
   * Direct pwrite / seek writes into the target file via [`RandomAccessSinkHandle`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/sink/RandomAccessSinkHandle.kt) without memory allocations or file pre-allocation crashes.
   * Deduplicates duplicate frames and maintains bit-vectors of completed chunks.
   * Emits `FLASH_ACK` frames back to the sender.
   * Verifies source whole-file SHA-256 or BLAKE3 checksums upon completion before releasing the file to user storage.
3. **Security & Path Sanitization:**
   * Incoming file paths are scrubbed with `sanitizeRelativePath` to prevent directory traversal attacks (`../`).

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashTransferRepository`
Located in [`com.transfer.flash.core.transfer.FlashTransferRepository`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/FlashTransferRepository.kt):

```kotlin
public interface FlashTransferRepository {
    /** Reactive list of all active and historical transfers. */
    public val transfers: StateFlow<List<FlashTransferItemUi>>

    /** Initiates an outbound file transfer to a peer. */
    public suspend fun sendFile(
        peerId: String,
        uri: String,
        fileName: String,
        totalBytes: Long,
        relativePath: String? = null
    ): FlashResult<String> // Returns transfer ID

    /** Observes progress, speed, and ETA for a specific transfer. */
    public fun observeTransfer(transferId: String): Flow<FlashTransferItemUi?>

    /** Cooperatively pauses an active transfer. */
    public suspend fun pauseTransfer(transferId: String)

    /** Resumes a paused or failed transfer. */
    public suspend fun resumeTransfer(transferId: String)

    /** Cancels an active transfer and cleans up temporary files. */
    public suspend fun cancelTransfer(transferId: String)
}
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.transfer.FlashTransferRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun executeFileTransfer(
    repository: FlashTransferRepository,
    peerId: String,
    filePath: String,
    fileName: String,
    fileSize: Long
) {
    scope.launch {
        val result = repository.sendFile(
            peerId = peerId,
            uri = "file://$filePath",
            fileName = fileName,
            totalBytes = fileSize
        )

        result.onSuccess { transferId ->
            println("Initiated transfer: $transferId")
            
            repository.observeTransfer(transferId).collect { item ->
                if (item == null) return@collect
                
                val progress = (item.bytesTransferred.toDouble() / item.totalBytes * 100).toInt()
                val speedMb = item.speedBytesPerSec / (1024 * 1024)
                println("Transfer progress: $progress% @ ${speedMb} MB/s (Status: ${item.status})")
                
                if (item.status.isTerminal) {
                    println("Transfer finished with outcome: ${item.status}")
                }
            }
        }
    }
}
```
