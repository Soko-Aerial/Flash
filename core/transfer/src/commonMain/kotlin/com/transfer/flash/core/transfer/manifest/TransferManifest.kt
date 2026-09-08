package com.transfer.flash.core.transfer.manifest

import com.transfer.flash.core.common.time.SystemTimeSource

/**
 * Manifest item representing one file in a multi-file transfer session (C5.10).
 */
internal data class ManifestItem(
    val fileId: String,
    val relativePath: String,
    val fileName: String,
    val totalBytes: Long,
    val fileSha256Hex: String,
    val mimeType: String? = null,
) {
    init {
        require(fileId.isNotBlank()) { "fileId cannot be blank" }
        require(fileName.isNotBlank()) { "fileName cannot be blank" }
        require(totalBytes >= 0) { "totalBytes must be >= 0, was $totalBytes" }
        require(fileSha256Hex.length == 64) { "fileSha256Hex must be a 64-char hex string" }
    }
}

/**
 * Transfer manifest grouping one or more files under a single transfer session (C5.10).
 *
 * Phase 13B-1 moved this file from `androidMain` to `commonMain`. Its only pin was the
 * [createdAtMs] default: `System.currentTimeMillis()` is `java.lang.System` and does not exist in
 * common code. [SystemTimeSource] is `:core:common`'s Phase 06 seam over the same clock — same
 * value, one indirection — and `:core:common` is already an `api` dependency of `commonMain`, so
 * no build-file dependency edit was needed.
 */
internal data class TransferManifest(
    val transferId: String,
    val senderDeviceId: String,
    val items: List<ManifestItem>,
    val createdAtMs: Long = SystemTimeSource.nowMs(),
) {
    init {
        require(transferId.isNotBlank()) { "transferId cannot be blank" }
        require(senderDeviceId.isNotBlank()) { "senderDeviceId cannot be blank" }
        require(items.isNotEmpty()) { "Transfer manifest must contain at least one item" }
    }

    val totalFiles: Int
        get() = items.size

    val totalBytes: Long
        get() = items.sumOf { it.totalBytes }
}
