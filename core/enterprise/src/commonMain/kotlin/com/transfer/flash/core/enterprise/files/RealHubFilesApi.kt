@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.enterprise.HubBearerSource
import com.transfer.flash.core.enterprise.HubRestExecutor
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.core.hubprotocol.rest.HubFileIds
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP implementation of [HubFilesApi] (HF-C2).
 *
 * Like every device API it goes through [HubRestExecutor]: a URL that is not the pinned hub, or cleartext outside the
 * debug opt-in, is refused before a bearer is attached. Bodies are bounded on the way IN as well as out: an error body or
 * a JSON answer above [HubContract.MAX_REST_BODY_BYTES] and a chunk above its expected size are cut off, so a broken or
 * hostile hub cannot make a phone buffer an arbitrary amount.
 */
@FlashInternalApi
public class RealHubFilesApi(
    private val httpClient: HttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    bearerSource: HubBearerSource? = null,
    /** The hub address stored in the credentials, or null when not enrolled. When non-null, every call must match its host. */
    pinnedHubUrl: () -> String? = { null },
    allowCleartext: () -> Boolean = { false },
) : HubFilesApi {

    private val executor = HubRestExecutor(bearerSource, pinnedHubUrl, allowCleartext)

    /** A body read with a limit: [bytes] holds at most `limit + 1` bytes, so "too long" is visible without reading it all. */
    private class Body(val bytes: ByteArray) {
        fun text(): String = bytes.decodeToString()
    }

    override suspend fun create(hubUrl: String, request: CreateFileRequestDto): HubFileResult<FileTicketDto> {
        if (!HubFileIds.isFileId(request.fileId)) return HubFileResult.Err(HubFileError.Refused(HubErrorCodes.ERR_BAD_REQUEST))
        val json = HubRestCodec.encode(request)
        return exchange(hubUrl, Op.CREATE, HubContract.MAX_REST_BODY_BYTES, { base, bearer ->
            httpClient.post("$base${HubContract.PATH_FILES}") {
                authorize(bearer)
                contentType(ContentType.Application.Json)
                setBody(json)
            }
        }) { status, body ->
            if (status != HttpStatusCode.Created && status != HttpStatusCode.OK) return@exchange null
            HubRestCodec.decodeOrNull<FileTicketDto>(body.text())?.takeIf { it.fileId == request.fileId }
        }
    }

    override suspend fun putChunk(
        hubUrl: String,
        fileId: String,
        index: Int,
        bytes: ByteArray,
        sha256Hex: String,
    ): HubFileResult<Unit> {
        if (!HubFileIds.isFileId(fileId) || index < 0 || !HubFileIds.isSha256(sha256Hex)) {
            return HubFileResult.Err(HubFileError.Refused(HubErrorCodes.ERR_BAD_REQUEST))
        }
        return exchange(hubUrl, Op.PUT, HubContract.MAX_REST_BODY_BYTES, { base, bearer ->
            httpClient.put("$base${HubContract.PATH_FILES}/$fileId/chunks/$index") {
                authorize(bearer)
                header(HubContract.HEADER_CHUNK_SHA256, sha256Hex)
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
        }) { status, _ -> if (status == HttpStatusCode.NoContent || status == HttpStatusCode.OK) Unit else null }
    }

    override suspend fun complete(hubUrl: String, fileId: String): HubFileResult<FileStatusDto> {
        if (!HubFileIds.isFileId(fileId)) return HubFileResult.Err(HubFileError.Refused(HubErrorCodes.ERR_BAD_REQUEST))
        return exchange(hubUrl, Op.COMPLETE, HubContract.MAX_REST_BODY_BYTES, { base, bearer ->
            httpClient.post("$base${HubContract.PATH_FILES}/$fileId/complete") { authorize(bearer) }
        }) { status, body -> statusBody(status, body, fileId) }
    }

    override suspend fun status(hubUrl: String, fileId: String): HubFileResult<FileStatusDto> {
        if (!HubFileIds.isFileId(fileId)) return HubFileResult.Err(HubFileError.Refused(HubErrorCodes.ERR_BAD_REQUEST))
        return exchange(hubUrl, Op.STATUS, HubContract.MAX_REST_BODY_BYTES, { base, bearer ->
            httpClient.get("$base${HubContract.PATH_FILES}/$fileId") { authorize(bearer) }
        }) { status, body -> statusBody(status, body, fileId) }
    }

    override suspend fun readChunk(hubUrl: String, fileId: String, index: Int, expectedBytes: Int): HubFileResult<ByteArray> {
        if (!HubFileIds.isFileId(fileId) || index < 0 || expectedBytes !in 1..HubContract.MAX_CHUNK_BYTES) {
            return HubFileResult.Err(HubFileError.Refused(HubErrorCodes.ERR_BAD_REQUEST))
        }
        // The limit covers both a chunk and an error body; a chunk one byte longer than expected is already a lie.
        val limit = maxOf(expectedBytes, ERROR_BODY_LIMIT)
        var wrongSize = false
        val result = exchange(hubUrl, Op.READ, limit, { base, bearer ->
            httpClient.get("$base${HubContract.PATH_FILES}/$fileId/chunks/$index") { authorize(bearer) }
        }) { status, body ->
            if (status != HttpStatusCode.OK) return@exchange null
            if (body.bytes.size != expectedBytes) {
                wrongSize = true
                return@exchange null
            }
            body.bytes
        }
        return if (wrongSize) HubFileResult.Err(HubFileError.Integrity) else result
    }

    override suspend fun cancel(hubUrl: String, fileId: String): HubFileResult<Unit> {
        if (!HubFileIds.isFileId(fileId)) return HubFileResult.Err(HubFileError.Refused(HubErrorCodes.ERR_BAD_REQUEST))
        val result = exchange(hubUrl, Op.CANCEL, HubContract.MAX_REST_BODY_BYTES, { base, bearer ->
            httpClient.delete("$base${HubContract.PATH_FILES}/$fileId") { authorize(bearer) }
        }) { status, _ -> if (status == HttpStatusCode.NoContent || status == HttpStatusCode.OK) Unit else null }
        // Already gone (expired, swept, cancelled twice): the outcome the caller wanted.
        return if (result is HubFileResult.Err && result.error == HubFileError.NotFound) HubFileResult.Ok(Unit) else result
    }

    // ----- plumbing ---------------------------------------------------------------------------------------------

    private enum class Op { CREATE, PUT, COMPLETE, STATUS, READ, CANCEL }

    private fun statusBody(status: HttpStatusCode, body: Body, fileId: String): FileStatusDto? {
        if (status != HttpStatusCode.OK) return null
        return HubRestCodec.decodeOrNull<FileStatusDto>(body.text())?.takeIf { it.fileId == fileId }
    }

    /**
     * Runs one request. [success] returns the value for a success, or null when the status is not a success for this
     * route (then the error body is mapped) or the success body is unusable (then [HubFileError.Server]).
     */
    private suspend fun <T : Any> exchange(
        hubUrl: String,
        op: Op,
        limit: Int,
        send: suspend (baseUrl: String, bearer: String?) -> HttpResponse,
        success: (HttpStatusCode, Body) -> T?,
    ): HubFileResult<T> = withContext(ioDispatcher) {
        when (val outcome = executor.call(hubUrl, send) { response -> readBounded(response, limit) }) {
            HubRestExecutor.Outcome.NotThisHub -> HubFileResult.Err(HubFileError.NotThisHub)
            HubRestExecutor.Outcome.Insecure -> HubFileResult.Err(HubFileError.InsecureAddress)
            HubRestExecutor.Outcome.NoBearer -> HubFileResult.Err(HubFileError.SignedOut)
            is HubRestExecutor.Outcome.Unreachable -> HubFileResult.Err(HubFileError.Offline)
            is HubRestExecutor.Outcome.Reply -> {
                val value = success(outcome.status, outcome.value)
                when {
                    value != null -> HubFileResult.Ok(value)
                    outcome.status.value in 200..299 -> HubFileResult.Err(HubFileError.Server(outcome.status.value))
                    else -> HubFileResult.Err(mapError(op, outcome.status, outcome.value, outcome.retryAfter))
                }
            }
        }
    }

    /** Reads at most `limit + 1` bytes, refusing early when `Content-Length` already says more. */
    private suspend fun readBounded(response: HttpResponse, limit: Int): Body {
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > limit) return Body(ByteArray(limit + 1))
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(limit + 1)
        var filled = 0
        while (filled < buffer.size) {
            val n = channel.readAvailable(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        return Body(buffer.copyOf(filled))
    }

    private fun mapError(op: Op, status: HttpStatusCode, body: Body, retryAfter: String?): HubFileError {
        val code = if (body.bytes.size <= HubContract.MAX_REST_BODY_BYTES) {
            HubRestCodec.decodeOrNull<ErrorBody>(body.text())?.code
        } else {
            null
        }
        return when (status.value) {
            400 -> HubFileError.Refused(code ?: HubErrorCodes.ERR_BAD_REQUEST)
            401 -> HubFileError.SignedOut
            403 -> if (code == HubErrorCodes.ERR_REVOKED) HubFileError.SignedOut else HubFileError.Forbidden
            404 -> when {
                code == HubErrorCodes.ERR_UNKNOWN_RECIPIENT -> HubFileError.UnknownRecipient
                // The create route never answers a plain 404: a hub without /api/v1/files does.
                op == Op.CREATE -> HubFileError.HubTooOld
                else -> HubFileError.NotFound
            }
            409 -> if (op == Op.READ) HubFileError.NotReady else HubFileError.Conflict
            413 -> HubFileError.TooLarge(null)
            429 -> if (code == HubErrorCodes.ERR_QUOTA_EXCEEDED) {
                HubFileError.HubFull
            } else {
                HubFileError.RateLimited(retryAfter?.trim()?.toIntOrNull()?.coerceIn(1, MAX_RETRY_AFTER_SECONDS) ?: 5)
            }
            503 -> HubFileError.Unavailable
            else -> if (status.value >= 500) HubFileError.Server(status.value) else HubFileError.Refused(code ?: "HTTP_${status.value}")
        }
    }

    private fun HttpRequestBuilder.authorize(bearer: String?) {
        if (bearer != null) header(HubContract.HEADER_AUTHORIZATION, HubContract.BEARER_PREFIX + bearer)
    }

    private companion object {
        /** Error bodies are small JSON; a chunk-sized limit would only waste memory on a 404. */
        const val ERROR_BODY_LIMIT = 16 * 1024

        /** A hub asking for more than this is treated as "an hour"; the engine decides when to give up. */
        const val MAX_RETRY_AFTER_SECONDS = 3600
    }
}
