package com.transfer.flash.core.hubprotocol.rest

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import kotlinx.serialization.Serializable

/**
 * DTOs for sealed files through the hub (ADR-215, `docs/plans/HUB-FILES-PLAN.md`).
 *
 * The hub stores **ciphertext only**: [CreateFileRequestDto.sha256] is the hash of the whole encrypted blob and
 * [CreateFileRequestDto.fileId] is minted by the client. No field here is ever derived from the plaintext, and the key
 * never appears in any of them: it travels inside the (sealed) chat message that announces the file.
 */
@FlashInternalApi
@Serializable
public data class CreateFileRequestDto(
    /** 32 lowercase hex characters; the idempotency key of the whole upload and the only value used in a path. */
    public val fileId: String,
    /** Size of the encrypted blob, 1..the hub's `maxFileBytes`. */
    public val totalBytes: Long,
    /** Plaintext chunk size in bytes, 1..[HubContract.MAX_CHUNK_BYTES], identical for every PUT of this file. */
    public val chunkSize: Int,
    /** Must equal `ceil(totalBytes / chunkSize)`. */
    public val chunkCount: Int,
    /** 64 lowercase hex characters: SHA-256 of the whole encrypted blob. */
    public val sha256: String,
    /** A direct send names the one recipient. */
    public val toDeviceId: String? = null,
    /** A group send names the group; the hub resolves the roster now and re-checks it on every read. */
    public val groupId: String? = null,
    /** Operator hint for the hub log. Never stored for display, never shown to anyone but the operator. */
    public val nameHint: String? = null,
)

/** Answer to `POST /api/v1/files`: the upload may begin. */
@FlashInternalApi
@Serializable
public data class FileTicketDto(
    public val fileId: String,
    /** After this the hub sweeps the unfinished upload and the client must start a new one. */
    public val uploadExpiresAtMs: Long,
    /** The largest body one chunk PUT may carry (the hub may lower it below the declared chunk size). */
    public val maxChunkBytes: Int = HubContract.MAX_CHUNK_BYTES,
)

/** Answer to `GET /api/v1/files/{fileId}` and to `complete`. */
@FlashInternalApi
@Serializable
public data class FileStatusDto(
    public val fileId: String,
    /** One of [FileStates]. A recipient may only read chunks of a [FileStates.READY] file. */
    public val state: String,
    public val totalBytes: Long,
    public val chunkSize: Int,
    public val chunkCount: Int,
    public val sha256: String,
    /** The device that uploaded it. */
    public val senderDeviceId: String,
    /** The recipients resolved at create time. */
    public val recipients: List<String> = emptyList(),
    public val createdAtMs: Long = 0L,
    /** Set when the file became [FileStates.READY]: after this the hub sweeps it. */
    public val expiresAtMs: Long = 0L,
    /** How many chunks are stored; equals [chunkCount] once the file is READY. */
    public val receivedChunks: Int = 0,
)

/** The states a sealed file moves through. */
public object FileStates {
    /** Chunks are still arriving; only the sender may read the status. */
    public const val UPLOADING: String = "UPLOADING"

    /** Every chunk is stored and the byte count matches; the recipients may download. */
    public const val READY: String = "READY"

    /** The sender cancelled it; the hub deleted the bytes. */
    public const val CANCELLED: String = "CANCELLED"

    /** Retention ran out; the hub deleted the bytes. */
    public const val EXPIRED: String = "EXPIRED"
}

/** One row of the console's files usage table (`GET /admin/api/v1/files/stats`). */
@FlashInternalApi
@Serializable
public data class FileUsageRowDto(
    public val deviceId: String,
    public val files: Int = 0,
    public val bytes: Long = 0L,
)

/** Answer to `GET /admin/api/v1/files/stats`: what the hub is holding, for the operator. */
@FlashInternalApi
@Serializable
public data class FileStatsDto(
    public val uploading: Int = 0,
    public val ready: Int = 0,
    public val uploadingBytes: Long = 0L,
    public val readyBytes: Long = 0L,
    public val devices: List<FileUsageRowDto> = emptyList(),
)
