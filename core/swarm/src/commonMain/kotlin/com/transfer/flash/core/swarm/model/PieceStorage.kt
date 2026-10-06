package com.transfer.flash.core.swarm.model

/**
 * Handle for reading from an origin source file at arbitrary byte offsets.
 */
public interface SourceHandle : AutoCloseable {
    /**
     * Reads up to [length] bytes at [offset] into [into].
     * Returns the number of bytes read, or -1 if EOF is reached.
     */
    public suspend fun readAt(offset: Long, into: ByteArray, length: Int): Int

    /**
     * Physical file identity (size and last-modified time), or null if the file cannot be read.
     */
    public suspend fun identity(): FileIdentity?

    public override fun close()
}

/**
 * Handle for reading from and writing to a partial (.part) download file.
 */
public interface PartialHandle : SourceHandle {
    /**
     * Writes [length] bytes from [bytes] at [offset].
     */
    public suspend fun writeAt(offset: Long, bytes: ByteArray, length: Int)

    /**
     * Forces unwritten buffered data to underlying storage medium (INV-4).
     */
    public suspend fun sync()
}

/**
 * Result of finalizing a completed partial download.
 */
public data class StorageFinalizeResult(
    public val ok: Boolean,
    public val finalPath: String? = null,
    public val identity: FileIdentity? = null,
    public val badPieces: List<Int> = emptyList(),
    public val errorMessage: String? = null,
)

/**
 * Storage port for swarm piece I/O (§5.2, SW-7).
 */
public interface PieceStorage {
    /**
     * Opens an origin source file for random-access reading.
     * [uri] may be a content URI, file URI, or local filesystem path.
     */
    public suspend fun openSource(uri: String): SourceHandle?

    /**
     * Opens or creates an app-private partial (.part) download file for random-access read/write.
     * [size] is the expected total file size in bytes.
     */
    public suspend fun openPartial(key: String, size: Long): PartialHandle?

    /**
     * Usable free space in bytes on the partition where partial downloads are stored.
     */
    public suspend fun freeBytesFor(key: String): Long

    /**
     * Verifies the whole-file SHA-256 hash and moves the partial file to its final destination,
     * resolving file name collisions.
     */
    public suspend fun finalize(
        key: String,
        fileName: String,
        mime: String,
        expectedSha256: ByteArray,
    ): StorageFinalizeResult

    /**
     * Deletes the partial (.part) download file associated with [key].
     */
    public suspend fun deletePartial(key: String)

    /**
     * Deletes any orphaned `.part` download files whose key is not in [activeKeys].
     */
    public suspend fun purgeOrphanedPartials(activeKeys: Set<String>)
}
