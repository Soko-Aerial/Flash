package com.transfer.flash.core.transfer.policy

import java.io.File
import java.io.OutputStream
import okio.Path.Companion.toOkioPath

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
 * Android-side convenience over [OkioRandomAccessSinkHandle], kept for the `java.io.File`-shaped
 * constructor its two external consumers already call — `core/engine/.../Flash.kt:198` and
 * `app/.../debug/DiscoveryEngineHolder.kt:362`, both building one over a `File`. Phase 13B-2's
 * brief was explicit that those call sites must need no edit, so the signature
 * `(File, Long)` and the supertype `RandomAccessSinkHandle` are both unchanged; only the
 * implementation moved, and interface delegation is used rather than inheritance so the
 * multiplatform class stays final.
 */
public class FileRandomAccessSinkHandle(
    file: File,
    expectedTotalBytes: Long,
) : RandomAccessSinkHandle by OkioRandomAccessSinkHandle(file.toOkioPath(), expectedTotalBytes)
