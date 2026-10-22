@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.announcements

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.HubBearerSource
import com.transfer.flash.core.enterprise.HubRestExecutor
import com.transfer.flash.core.enterprise.HubUrlPolicy
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.AnnouncementFeedDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.core.hubprotocol.rest.UpdateAnnouncementRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import com.transfer.flash.core.enterprise.boundedText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete HTTP implementation of [HubAnnouncementsApi] (Plan A3 §4.1).
 * Plaintext on the hub (ADR-138): hub administrators can read all announcements; encryption is NEVER claimed.
 *
 * Calls are refused (before any bearer is attached) when [hubUrl] is not the pinned hub ([pinnedHubUrl]) or is
 * cleartext outside the development opt-in ([HubUrlPolicy]) — the same rule [EnterpriseDirectoryClient] applies.
 */
@FlashInternalApi
public class RealHubAnnouncementsApi(
    private val httpClient: HttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val bearerSource: HubBearerSource? = null,
    /** The hub address stored in the credentials, or null when not enrolled. When non-null, every call must match its host. */
    private val pinnedHubUrl: () -> String? = { null },
    private val allowCleartext: () -> Boolean = { false },
) : HubAnnouncementsApi {

    private data class Reply(val response: HttpResponse, val body: String)

    private val executor = HubRestExecutor(bearerSource, pinnedHubUrl, allowCleartext)

    private suspend fun execute(hubUrl: String, send: suspend (baseUrl: String, bearer: String?) -> HttpResponse): FlashResult<Reply> =
        // The credentials pin the hub's host (CLI-01): never send the device bearer to a different host, and never over
        // cleartext unless the (debug-only) opt-in allows it. [HubRestExecutor] holds that rule for every device API.
        when (val outcome = executor.call(hubUrl, send) { response -> Reply(response, response.boundedText()) }) {
            is HubRestExecutor.Outcome.Reply -> FlashResult.Success(outcome.value)
            HubRestExecutor.Outcome.NotThisHub -> FlashResult.Failure(
                HubAnnouncementError.Server("This is not the hub this device is enrolled with.").toFlashError(),
            )
            HubRestExecutor.Outcome.Insecure -> FlashResult.Failure(
                HubAnnouncementError.Server("The hub address is not a secure address.").toFlashError(),
            )
            HubRestExecutor.Outcome.NoBearer -> FlashResult.Failure(HubAnnouncementError.Revoked.toFlashError())
            is HubRestExecutor.Outcome.Unreachable -> FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        }

    private fun HttpRequestBuilder.authorize(bearer: String?) {
        if (bearer != null) header(HubContract.HEADER_AUTHORIZATION, HubContract.BEARER_PREFIX + bearer)
    }

    private fun mapError(status: HttpStatusCode, body: String, retryAfter: String?): HubAnnouncementError {
        val errorBody = runCatching { HubRestCodec.decodeOrNull<ErrorBody>(body) }.getOrNull()
        val code = errorBody?.code
        val message = errorBody?.message ?: ""

        return when (status.value) {
            400 -> {
                if (code == HubErrorCodes.ERR_PIN_LIMIT || message.contains("pin", ignoreCase = true)) {
                    HubAnnouncementError.PinLimit
                } else if (message.contains("long", ignoreCase = true) || message.contains("limit", ignoreCase = true)) {
                    val limit = message.filter { it.isDigit() }.toIntOrNull() ?: 4000
                    HubAnnouncementError.TooLong(limit)
                } else {
                    HubAnnouncementError.Server(message.ifBlank { "Invalid announcement request" })
                }
            }
            401 -> HubAnnouncementError.Revoked
            403 -> HubAnnouncementError.NotAllowed
            404 -> HubAnnouncementError.Removed
            409 -> if (code == HubErrorCodes.ERR_PIN_LIMIT) {
                HubAnnouncementError.PinLimit
            } else {
                val current = runCatching { HubRestCodec.decodeOrNull<AnnouncementItemDto>(body) }.getOrNull()
                HubAnnouncementError.Conflict(current)
            }
            429 -> {
                val retrySec = retryAfter?.toIntOrNull() ?: 5
                HubAnnouncementError.RateLimited(retrySec)
            }
            else -> HubAnnouncementError.Server(message.ifBlank { "Server returned status ${status.value}" })
        }
    }

    override suspend fun fetchFeed(hubUrl: String, since: Long, limit: Int): FlashResult<AnnouncementFeedDto> =
        withContext(ioDispatcher) {
            val reply = when (
                val r = execute(hubUrl) { base, bearer ->
                    httpClient.get("$base${HubContract.PATH_ANNOUNCEMENTS}") {
                        // Always sent, also for 0: the hub answers a request WITHOUT `since` with the search shape, not the feed
                        // (ERROR-410: the phone never decoded it, so no announcement ever reached it).
                        parameter("since", since.coerceAtLeast(0L).toString())
                        parameter("limit", limit.coerceIn(1, 500).toString())
                        authorize(bearer)
                    }
                }
            ) {
                is FlashResult.Failure -> return@withContext r
                is FlashResult.Success -> r.value
            }

            val status = reply.response.status
            if (status == HttpStatusCode.NotFound) {
                return@withContext FlashResult.Failure(HubAnnouncementError.NotSupportedByHub.toFlashError())
            }
            if (status != HttpStatusCode.OK) {
                val retryAfter = reply.response.headers[HubContract.HEADER_RETRY_AFTER]
                return@withContext FlashResult.Failure(mapError(status, reply.body, retryAfter).toFlashError())
            }

            val dto = HubRestCodec.decodeOrNull<AnnouncementFeedDto>(reply.body)
            if (dto != null) {
                FlashResult.Success(dto)
            } else {
                FlashResult.Failure(HubAnnouncementError.Server("Malformed feed response from hub").toFlashError())
            }
        }

    override suspend fun search(
        hubUrl: String,
        query: String,
        departmentId: String?,
        limit: Int,
        before: Long?,
    ): FlashResult<AnnouncementSearchResponseDto> = withContext(ioDispatcher) {
        val reply = when (
            val r = execute(hubUrl) { base, bearer ->
                httpClient.get("$base${HubContract.PATH_ANNOUNCEMENTS}") {
                    parameter("q", query.take(64))
                    // The hub applies departmentId only together with scope=department.
                    if (!departmentId.isNullOrBlank()) {
                        parameter("scope", "department")
                        parameter("departmentId", departmentId)
                    }
                    parameter("limit", limit.coerceIn(1, 100).toString())
                    if (before != null && before > 0L) parameter("before", before.toString())
                    authorize(bearer)
                }
            }
        ) {
            is FlashResult.Failure -> return@withContext r
            is FlashResult.Success -> r.value
        }

        val status = reply.response.status
        if (status == HttpStatusCode.NotFound) {
            return@withContext FlashResult.Failure(HubAnnouncementError.NotSupportedByHub.toFlashError())
        }
        if (status != HttpStatusCode.OK) {
            val retryAfter = reply.response.headers[HubContract.HEADER_RETRY_AFTER]
            return@withContext FlashResult.Failure(mapError(status, reply.body, retryAfter).toFlashError())
        }

        val dto = HubRestCodec.decodeOrNull<AnnouncementSearchResponseDto>(reply.body)
        if (dto != null) {
            FlashResult.Success(dto)
        } else {
            FlashResult.Failure(HubAnnouncementError.Server("Malformed search response from hub").toFlashError())
        }
    }

    override suspend fun getById(hubUrl: String, id: String): FlashResult<AnnouncementItemDto?> = withContext(ioDispatcher) {
        val reply = when (
            val r = execute(hubUrl) { base, bearer ->
                httpClient.get("$base${HubContract.PATH_ANNOUNCEMENTS}/$id") {
                    authorize(bearer)
                }
            }
        ) {
            is FlashResult.Failure -> return@withContext r
            is FlashResult.Success -> r.value
        }

        val status = reply.response.status
        if (status == HttpStatusCode.NotFound) {
            return@withContext FlashResult.Success(null)
        }
        if (status != HttpStatusCode.OK) {
            val retryAfter = reply.response.headers[HubContract.HEADER_RETRY_AFTER]
            return@withContext FlashResult.Failure(mapError(status, reply.body, retryAfter).toFlashError())
        }

        val dto = HubRestCodec.decodeOrNull<AnnouncementItemDto>(reply.body)
        FlashResult.Success(dto)
    }

    override suspend fun post(hubUrl: String, request: PostAnnouncementRequestDto): FlashResult<AnnouncementItemDto> =
        withContext(ioDispatcher) {
            val jsonBody = HubRestCodec.encode(request)
            val reply = when (
                val r = execute(hubUrl) { base, bearer ->
                    httpClient.post("$base${HubContract.PATH_ANNOUNCEMENTS}") {
                        contentType(ContentType.Application.Json)
                        authorize(bearer)
                        setBody(jsonBody)
                    }
                }
            ) {
                is FlashResult.Failure -> return@withContext r
                is FlashResult.Success -> r.value
            }

            val status = reply.response.status
            if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
                val retryAfter = reply.response.headers[HubContract.HEADER_RETRY_AFTER]
                return@withContext FlashResult.Failure(mapError(status, reply.body, retryAfter).toFlashError())
            }

            val dto = HubRestCodec.decodeOrNull<AnnouncementItemDto>(reply.body)
            if (dto != null) {
                FlashResult.Success(dto)
            } else {
                FlashResult.Failure(HubAnnouncementError.Server("Malformed announcement response from hub").toFlashError())
            }
        }

    override suspend fun update(
        hubUrl: String,
        id: String,
        request: UpdateAnnouncementRequestDto,
    ): FlashResult<AnnouncementItemDto> = withContext(ioDispatcher) {
        val jsonBody = HubRestCodec.encode(request)
        val reply = when (
            val r = execute(hubUrl) { base, bearer ->
                httpClient.put("$base${HubContract.PATH_ANNOUNCEMENTS}/$id") {
                    contentType(ContentType.Application.Json)
                    authorize(bearer)
                    setBody(jsonBody)
                }
            }
        ) {
            is FlashResult.Failure -> return@withContext r
            is FlashResult.Success -> r.value
        }

        val status = reply.response.status
        if (status != HttpStatusCode.OK) {
            val retryAfter = reply.response.headers[HubContract.HEADER_RETRY_AFTER]
            return@withContext FlashResult.Failure(mapError(status, reply.body, retryAfter).toFlashError())
        }

        val dto = HubRestCodec.decodeOrNull<AnnouncementItemDto>(reply.body)
        if (dto != null) {
            FlashResult.Success(dto)
        } else {
            FlashResult.Failure(HubAnnouncementError.Server("Malformed announcement response from hub").toFlashError())
        }
    }

    override suspend fun delete(hubUrl: String, id: String): FlashResult<Unit> = withContext(ioDispatcher) {
        val reply = when (
            val r = execute(hubUrl) { base, bearer ->
                httpClient.delete("$base${HubContract.PATH_ANNOUNCEMENTS}/$id") {
                    authorize(bearer)
                }
            }
        ) {
            is FlashResult.Failure -> return@withContext r
            is FlashResult.Success -> r.value
        }

        val status = reply.response.status
        if (status != HttpStatusCode.OK && status != HttpStatusCode.NoContent) {
            val retryAfter = reply.response.headers[HubContract.HEADER_RETRY_AFTER]
            return@withContext FlashResult.Failure(mapError(status, reply.body, retryAfter).toFlashError())
        }
        FlashResult.Success(Unit)
    }

    override suspend fun setPinned(hubUrl: String, id: String, pinned: Boolean): FlashResult<Unit> = withContext(ioDispatcher) {
        val action = if (pinned) "pin" else "unpin"
        val reply = when (
            val r = execute(hubUrl) { base, bearer ->
                httpClient.post("$base${HubContract.PATH_ANNOUNCEMENTS}/$id/$action") {
                    authorize(bearer)
                }
            }
        ) {
            is FlashResult.Failure -> return@withContext r
            is FlashResult.Success -> r.value
        }

        val status = reply.response.status
        if (status != HttpStatusCode.OK) {
            val retryAfter = reply.response.headers[HubContract.HEADER_RETRY_AFTER]
            return@withContext FlashResult.Failure(mapError(status, reply.body, retryAfter).toFlashError())
        }
        FlashResult.Success(Unit)
    }
}
