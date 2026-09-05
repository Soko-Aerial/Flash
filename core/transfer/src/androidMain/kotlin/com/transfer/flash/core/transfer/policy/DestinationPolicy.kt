package com.transfer.flash.core.transfer.policy

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.Volatile

/**
 * Storage destination type where a received file will land (C5.9).
 */
internal sealed interface DestinationTarget {
    /** Target is a standard filesystem file (e.g. app-internal cache or public downloads on supported platforms). */
    data class FileTarget(val file: File) : DestinationTarget

    /** Target is an Android content URI (e.g. Storage Access Framework Tree URI or MediaStore insert). */
    data class UriTarget(val uriString: String, val displayName: String) : DestinationTarget
}

/**
 * Strategy for determining whether and where to store an incoming file transfer offer.
 * Rule: Explicit acceptance is required — never auto-download unapproved files into user folders (UI-016 contract).
 */
internal sealed interface TransferAcceptance {
    /** Transfer is accepted and assigned a specific target sink. */
    data class Accepted(val target: DestinationTarget) : TransferAcceptance

    /** Transfer is queued awaiting user interaction / approval. */
    data object PendingUserDecision : TransferAcceptance

    /** Transfer is rejected by policy or user action. */
    data class Rejected(val reason: String) : TransferAcceptance
}

/**
 * Resolves destination targets and manages file creation/resumption handles for [ChunkSink].
 */
internal interface DestinationPolicy {
    /**
     * Determines the initial acceptance decision for an incoming file offer.
     *
     * @param senderDeviceId unique ID of the offering peer.
     * @param fileName declared file name.
     * @param totalBytes declared size in bytes.
     * @param mimeType optional MIME type.
     * @param isTrustedPeer whether the peer is pinned/trusted in TrustStore.
     */
    suspend fun evaluateOffer(
        senderDeviceId: String,
        fileName: String,
        totalBytes: Long,
        mimeType: String?,
        isTrustedPeer: Boolean,
    ): TransferAcceptance

    /**
     * Opens a seekable, random-access write handle ([RandomAccessSinkHandle]) for writing incoming chunks.
     * Using random-access allows out-of-order chunk writes directly to their target byte offsets.
     */
    suspend fun openSinkHandle(
        transferId: String,
        fileId: String,
        target: DestinationTarget,
        totalBytes: Long,
    ): RandomAccessSinkHandle
}

/**
 * A seekable write handle that can write chunks at arbitrary byte offsets.
 */
public interface RandomAccessSinkHandle : Closeable {
    /**
     * Writes [data] at the specified [byteOffset].
     */
    public fun writeAt(byteOffset: Long, data: ByteArray)

    /**
     * Flushes buffered writes to underlying storage.
     */
    public fun flush()

    /**
     * Returns true if the sink handle is valid and ready for writes.
     */
    public val isOpen: Boolean
}

/**
 * JVM / standard filesystem implementation of [RandomAccessSinkHandle] using [RandomAccessFile].
 */
public class FileRandomAccessSinkHandle(
    private val file: File,
    private val expectedTotalBytes: Long,
) : RandomAccessSinkHandle {

    private val raf: RandomAccessFile = RandomAccessFile(file, "rw").apply {
        // Pre-allocate file length if needed to avoid fragmentation on sparse writes
        if (length() < expectedTotalBytes) {
            setLength(expectedTotalBytes)
        }
    }

    @Volatile
    private var _isOpen = true

    override val isOpen: Boolean
        get() = _isOpen

    @Synchronized
    override fun writeAt(byteOffset: Long, data: ByteArray) {
        check(_isOpen) { "Sink handle for ${file.name} is already closed" }
        raf.seek(byteOffset)
        raf.write(data)
    }

    @Synchronized
    override fun flush() {
        if (_isOpen) {
            raf.fd.sync()
        }
    }

    @Synchronized
    override fun close() {
        if (_isOpen) {
            _isOpen = false
            try {
                raf.fd.sync()
            } catch (_: Exception) {}
            raf.close()
        }
    }
}
