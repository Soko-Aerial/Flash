package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto

/**
 * The six sealed-file routes of `docs/protocol.md` "Hub files API", one call each, with the device bearer attached.
 *
 * Every call moves opaque bytes only: sealing happens before [putChunk] and opening after [readChunk]
 * (`HubFileSealing`). Implementations refuse a file id that is not 32 lowercase hex before building any URL.
 */
@FlashInternalApi
public interface HubFilesApi {

    /** `POST /api/v1/files`. Idempotent: the same request for the same id answers the same ticket. */
    public suspend fun create(hubUrl: String, request: CreateFileRequestDto): HubFileResult<FileTicketDto>

    /** `PUT /api/v1/files/{fileId}/chunks/{index}` with [sha256Hex] as `X-Flash-Chunk-Sha256`. */
    public suspend fun putChunk(hubUrl: String, fileId: String, index: Int, bytes: ByteArray, sha256Hex: String): HubFileResult<Unit>

    /** `POST /api/v1/files/{fileId}/complete`. */
    public suspend fun complete(hubUrl: String, fileId: String): HubFileResult<FileStatusDto>

    /** `GET /api/v1/files/{fileId}`. */
    public suspend fun status(hubUrl: String, fileId: String): HubFileResult<FileStatusDto>

    /**
     * `GET /api/v1/files/{fileId}/chunks/{index}`. A body that is not exactly [expectedBytes] long is
     * [HubFileError.Integrity], and a body longer than that is never read past `expectedBytes + 1`.
     */
    public suspend fun readChunk(hubUrl: String, fileId: String, index: Int, expectedBytes: Int): HubFileResult<ByteArray>

    /** `DELETE /api/v1/files/{fileId}`. A file the hub no longer has counts as cancelled. */
    public suspend fun cancel(hubUrl: String, fileId: String): HubFileResult<Unit>
}
