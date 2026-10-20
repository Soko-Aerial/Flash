package com.transfer.flash.server.files

import com.transfer.flash.core.hubprotocol.HubContract

/**
 * Size, quota and retention policy for sealed files (ADR-215, `docs/plans/HUB-FILES-PLAN.md` section 3.2).
 *
 * [maxFileBytes] and [retentionMs] are read from the administrator's settings on every request, so a change in the console
 * applies to the next upload without a restart. Everything is enforced inside the `BEGIN IMMEDIATE` transaction that writes,
 * so two senders can never jointly overshoot a cap.
 *
 * @property chunkBytes the largest chunk body a client may send; a client that declared a bigger `chunkSize` is refused at
 *   create time, and a body bigger than the declared size is refused at put time.
 * @property deviceStoredBytes what ONE device may keep on the hub (uploads in progress plus ready files it sent).
 * @property globalStoredBytes what the whole hub may keep.
 * @property maxFilesPerDevice how many files one device may have on the hub at once.
 * @property retentionMs how long a READY file is kept after completion.
 * @property uploadWindowMs how long an upload may stay incomplete before the sweeper deletes it.
 */
data class FileLimits(
    val maxFileBytes: Long = HubContract.DEFAULT_MAX_FILE_BYTES,
    val chunkBytes: Int = HubContract.MAX_CHUNK_BYTES,
    val deviceStoredBytes: Long = 500L * 1024 * 1024,
    val globalStoredBytes: Long = 5L * 1024 * 1024 * 1024,
    val maxFilesPerDevice: Int = 50,
    val retentionMs: Long = 7L * 24 * 60 * 60 * 1000,
    val uploadWindowMs: Long = 24L * 60 * 60 * 1000,
) {
    init {
        require(maxFileBytes in 1..HubContract.MAX_FILE_BYTES_CEILING) { "maxFileBytes out of range: $maxFileBytes" }
        require(chunkBytes in 1..HubContract.MAX_CHUNK_BYTES) { "chunkBytes out of range: $chunkBytes" }
        require(deviceStoredBytes > 0) { "deviceStoredBytes must be positive" }
        require(globalStoredBytes >= deviceStoredBytes) { "globalStoredBytes must be at least deviceStoredBytes" }
        require(maxFilesPerDevice > 0) { "maxFilesPerDevice must be positive" }
        require(retentionMs > 0) { "retentionMs must be positive" }
        require(uploadWindowMs > 0) { "uploadWindowMs must be positive" }
    }

    /** The largest chunk body that fits one file of this limit (a 1 MiB minimum keeps small caps usable). */
    val effectiveChunkBytes: Int get() = minOf(chunkBytes, maxFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

/**
 * The outcome of one store operation.
 *
 * [Refused] carries a [HubErrorCodes][com.transfer.flash.core.hubprotocol.HubErrorCodes] code and a FIXED sentence; the route
 * turns it into the contract error body. [Unavailable] is the "the row says the bytes are there, the disk disagrees" case
 * (a restore without `data/files`), which the route answers 503 so a client retries later instead of failing the file for ever.
 */
sealed interface FileOutcome<out T> {
    data class Ok<T>(val value: T) : FileOutcome<T>

    data class Refused(val code: String, val message: String) : FileOutcome<Nothing>

    data object Unavailable : FileOutcome<Nothing>

    data class Failed(val cause: Throwable) : FileOutcome<Nothing>
}

/** Result of [FileStore.putChunk]: whether this PUT actually stored a new chunk (a byte-identical retry stores nothing). */
data class PutResult(val newChunk: Boolean, val receivedChunks: Int)

/** What the sweeper did in one pass. */
data class SweepResult(val uploadsAbandoned: Int, val filesExpired: Int, val bytesFreed: Long)

/** Messages the store hands back. They are fixed strings, never exception text (SEC-16), and name the next step. */
internal object FileMessages {
    const val BAD_ID = "The file id must be 32 lowercase hex characters."
    const val BAD_HASH = "The sha256 field must be 64 lowercase hex characters."
    const val BAD_SIZES = "totalBytes, chunkSize and chunkCount do not agree."
    const val BAD_RECIPIENT = "Name exactly one recipient: toDeviceId or groupId."
    const val UNKNOWN_RECIPIENT = "That device or group is not part of this organisation."
    const val NO_SUCH_FILE = "No such file on this hub, or it has expired."
    const val NOT_SENDER = "Only the device that started the upload may do that."
    const val NOT_RECIPIENT = "This device is not on the file's recipient list."
    const val NOT_UPLOADING = "This upload is already finished or was cancelled."
    const val NOT_READY = "This file is still uploading."
    const val CONFLICT_META = "That file id already exists with different details."
    const val CONFLICT_CHUNK = "This chunk is already stored with different bytes."
    const val CHUNK_TOO_BIG = "The chunk is larger than the chunk size declared for this file."
    const val CHUNK_SIZE = "The chunk is not the size this file declares for that index."
    const val CHUNK_SIZE_LIMIT = "This hub accepts a chunk size of at most 1 MiB."
    const val CHUNK_HASH = "The chunk does not match the sha256 header."
    const val CHUNK_INDEX = "The chunk index is outside this file's range."
    const val INCOMPLETE = "Not every chunk has arrived yet."
    const val TOO_LARGE = "This file is larger than this hub accepts per file."
    const val DEVICE_QUOTA = "This device has reached its storage allowance on the hub."
    const val GLOBAL_QUOTA = "The hub has no storage left for new files."
    const val TOO_MANY_FILES = "This device has too many files on the hub."
    const val EMPTY_FILE = "A zero-byte file is not worth a hub round trip."
}
