@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.rest.ApprovalDto
import com.transfer.flash.core.hubprotocol.rest.ApprovalSubmitBody
import com.transfer.flash.core.hubprotocol.rest.DepartmentDto
import com.transfer.flash.core.hubprotocol.rest.GroupDto
import com.transfer.flash.core.hubprotocol.rest.HubDirectoryValues
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.JoinDecisionBody
import com.transfer.flash.core.hubprotocol.rest.JoinRequestBody
import com.transfer.flash.core.hubprotocol.rest.JoinRequestDto
import com.transfer.flash.core.hubprotocol.rest.JoinResponse
import com.transfer.flash.core.hubprotocol.rest.MemberDto
import com.transfer.flash.core.hubprotocol.rest.SealingKeyRequest
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The answer to a join request (CLI-04): joined at once, waiting for an admin, or refused. */
@FlashInternalApi
public enum class EnterpriseJoinStatus { JOINED, PENDING, REJECTED }

/** The hub's answer to a join request or to an admin's decision. [reason] is a hub-written sentence, may be null. */
@FlashInternalApi
public data class EnterpriseJoinResult(
    public val status: EnterpriseJoinStatus,
    public val groupId: String,
    public val name: String? = null,
    public val requestId: String? = null,
    public val reason: String? = null,
)

/** A pending request to join a group, as a group admin sees it. */
@FlashInternalApi
public data class EnterpriseJoinRequest(
    public val requestId: String,
    public val groupId: String,
    public val deviceId: String,
    public val displayName: String,
    public val requestedAtMs: Long = 0L,
    public val message: String? = null,
)

/** How the hub answered a sealing-key publish (ADR-124); the host's retry policy depends on which. */
@FlashInternalApi
public enum class SealingPublishResult {
    /** The hub stored (or already had) this key. */
    PUBLISHED,

    /** 409: this device retired that key earlier and the hub never takes a retired key back. Make a new key. */
    KEY_RETIRED,

    /** 429: wait (burst 5, then one per 30 s). */
    RATE_LIMITED,

    /** 404 or 405: the hub does not know the route (an older hub). */
    HUB_TOO_OLD,

    /** 400, 401, 403 or 413: the hub will not take this key from this device as it is now. */
    REFUSED,

    /** No network, the hub did not answer, or this device could not sign in. Try again later. */
    UNREACHABLE,

    /** Any other answer (5xx). */
    SERVER_ERROR,
}

/**
 * A dangerous console action waiting for THIS device (the administrator's approver phone) to approve (ADR-131, FO-14).
 * [code] is the six digits the console shows too: the person compares them before approving.
 */
@FlashInternalApi
public data class EnterpriseApproval(
    public val challengeId: String,
    public val action: String,
    public val admin: String,
    public val target: String,
    public val nonce: String,
    public val code: String,
    public val expiresAtMs: Long,
)

/** The two sealing-key calls the key manager needs, so its state machine is tested with a fake hub. */
@FlashInternalApi
public interface HubSealingKeyApi {
    /** Publishes (or rotates to) the sealing key. Idempotent for an unchanged key. */
    public suspend fun publish(hubUrl: String, sealingPublicKey: String, signature: String): SealingPublishResult

    /** Asks the hub to forget this device's sealing key. Best effort; true when the hub confirmed. */
    public suspend fun remove(hubUrl: String): Boolean
}

/**
 * What the chat repository needs from the hub's directory. An interface so repository tests use a fake and the real
 * [EnterpriseDirectoryClient] stays an HTTP detail.
 */
@FlashInternalApi
public interface HubDirectoryApi {
    public suspend fun fetchVisibleGroups(hubUrl: String, deviceId: String, department: String? = null): FlashResult<List<EnterpriseDirectoryGroup>>

    public suspend fun fetchDepartmentMembers(hubUrl: String, departmentId: String): FlashResult<List<EnterpriseDirectoryMember>>

    public suspend fun fetchDepartments(hubUrl: String): FlashResult<List<EnterpriseDepartment>>

    /** Asks to join [groupId]. JOINED, PENDING and REJECTED are all successful answers; only an error is a failure. */
    public suspend fun requestJoin(hubUrl: String, groupId: String, message: String? = null): FlashResult<EnterpriseJoinResult>

    /** Pending requests the caller may decide (group admin), optionally only for [groupId]. */
    public suspend fun fetchJoinRequests(hubUrl: String, groupId: String? = null): FlashResult<List<EnterpriseJoinRequest>>

    public suspend fun decideJoinRequest(hubUrl: String, requestId: String, approve: Boolean, reason: String? = null): FlashResult<EnterpriseJoinResult>
}

/**
 * Outcome of fetching one page of directory sync changes from the hub.
 */
@FlashInternalApi
public sealed interface DirectorySyncPageResult {
    public data class Success(val dto: DirectorySyncDto) : DirectorySyncPageResult
    public data object HubUnsupported : DirectorySyncPageResult
    public data class RateLimited(val retryAfterSeconds: Long?) : DirectorySyncPageResult
    public data class AuthRefused(val statusCode: Int, val message: String) : DirectorySyncPageResult
    public data class Unreachable(val message: String) : DirectorySyncPageResult
    public data class ServerError(val statusCode: Int, val message: String) : DirectorySyncPageResult
}

/**
 * Interface for fetching directory sync pages from the hub.
 */
@FlashInternalApi
public interface DirectorySyncApi {
    public suspend fun fetchDirectorySync(
        hubUrl: String,
        since: Long = 0L,
        epoch: String? = null,
        cursor: String? = null,
        limit: Int = 500,
    ): DirectorySyncPageResult
}

/**
 * REST HTTP client for the Enterprise Organization Directory and scoped groups.
 *
 * Bodies are the shared `:core:hub-protocol` DTOs; query values are URL-encoded by Ktor. Every call sends
 * `Authorization: Bearer <token>` from [bearerSource] (the challenge/verify session, [HubSession]); a 401 refreshes the
 * token once and retries. The legacy `X-Device-Id` identification is gone: the hub reads the caller from the bearer.
 *
 * Calls are refused when [hubUrl] is not the pinned hub ([pinnedHubUrl]) or is cleartext outside the development
 * opt-in ([HubUrlPolicy]); the device never sends a bearer to a host it did not enrol with.
 */
@FlashInternalApi
public class EnterpriseDirectoryClient(
    private val httpClient: HttpClient = EnterpriseEnrollmentClient.createDefaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bearerSource: HubBearerSource? = null,
    /** The hub address stored in the credentials, or null when not enrolled. When non-null, every call must match its host. */
    private val pinnedHubUrl: () -> String? = { null },
    private val allowCleartext: () -> Boolean = { false },
) : HubDirectoryApi, HubSealingKeyApi, DirectorySyncApi {

    private class Reply(val response: HttpResponse, val body: String)

    /** One authenticated request with a single refresh-and-retry on 401. */
    private suspend fun execute(hubUrl: String, send: suspend (base: String, bearer: String?) -> HttpResponse): FlashResult<Reply> {
        val pinned = pinnedHubUrl()
        if (pinned != null && !HubUrlPolicy.sameHub(hubUrl, pinned)) {
            return FlashResult.Failure(FlashError.Unknown("This is not the hub this device is enrolled with."))
        }
        if (HubUrlPolicy.check(hubUrl, allowCleartext()) != HubUrlPolicy.Verdict.OK) {
            return FlashResult.Failure(FlashError.Unknown("The hub address is not a secure address."))
        }
        val base = HubUrlPolicy.baseUrl(hubUrl)
        var forceRefresh = false
        repeat(2) {
            val bearer = bearerSource?.bearer(forceRefresh)
            if (bearerSource != null && bearer == null) {
                return FlashResult.Failure(FlashError.NetworkUnavailable("This device could not sign in to the organisation hub."))
            }
            val reply = runSuspendCatching {
                val response = send(base, bearer)
                Reply(response, response.bodyAsText())
            }.getOrElse { return FlashResult.Failure(FlashError.NetworkUnavailable("The hub could not be reached.")) }
            if (reply.response.status == HttpStatusCode.Unauthorized && bearerSource != null && !forceRefresh) {
                forceRefresh = true
            } else {
                return FlashResult.Success(reply)
            }
        }
        return FlashResult.Failure(FlashError.Unknown("The hub did not accept this device's sign-in."))
    }

    private fun HttpRequestBuilder.authorize(bearer: String?) {
        if (bearer != null) header(HubContract.HEADER_AUTHORIZATION, HubContract.BEARER_PREFIX + bearer)
    }

    private fun friendlyHttpFailure(status: HttpStatusCode): FlashResult.Failure = FlashResult.Failure(
        FlashError.Unknown(
            when (status.value) {
                401 -> "The hub did not accept this device's sign-in."
                403 -> "You are not allowed to do that in this organisation."
                404 -> "That group or request no longer exists."
                409 -> "That was already decided."
                429 -> "Too many requests. Try again in a moment."
                in 500..599 -> "The hub had a problem. Try again later."
                else -> "The hub could not do that."
            },
        ),
    )

    private suspend fun <T> hubGet(hubUrl: String, path: String, params: Map<String, String?> = emptyMap(), parse: (String) -> T?): FlashResult<T> =
        withContext(ioDispatcher) {
            val reply = when (
                val r = execute(hubUrl) { base, bearer ->
                    httpClient.get("$base$path") {
                        params.forEach { (k, v) -> if (!v.isNullOrBlank()) parameter(k, v) }
                        authorize(bearer)
                    }
                }
            ) {
                is FlashResult.Failure -> return@withContext r
                is FlashResult.Success -> r.value
            }
            if (reply.response.status != HttpStatusCode.OK) return@withContext friendlyHttpFailure(reply.response.status)
            runCatching { parse(reply.body) }.getOrNull()?.let { FlashResult.Success(it) }
                ?: FlashResult.Failure(FlashError.Unknown("The hub sent an answer this app does not understand."))
        }

    private suspend fun <T> hubPost(
        hubUrl: String,
        path: String,
        body: String,
        refusalMayCarryBody: Boolean = true,
        parse: (String) -> T?,
    ): FlashResult<T> =
        withContext(ioDispatcher) {
            val reply = when (
                val r = execute(hubUrl) { base, bearer ->
                    httpClient.post("$base$path") {
                        contentType(ContentType.Application.Json)
                        authorize(bearer)
                        setBody(body)
                    }
                }
            ) {
                is FlashResult.Failure -> return@withContext r
                is FlashResult.Success -> r.value
            }
            val status = reply.response.status
            if (status != HttpStatusCode.OK && status != HttpStatusCode.Created && status != HttpStatusCode.Accepted) {
                // A refusal can still carry a JoinResponse (REJECTED + reason): hand it to the parser first.
                if (!refusalMayCarryBody) return@withContext friendlyHttpFailure(status)
                return@withContext runCatching { parse(reply.body) }.getOrNull()?.let { FlashResult.Success(it) } ?: friendlyHttpFailure(status)
            }
            runCatching { parse(reply.body) }.getOrNull()?.let { FlashResult.Success(it) }
                ?: FlashResult.Failure(FlashError.Unknown("The hub sent an answer this app does not understand."))
        }

    /** The console actions waiting for this device to approve (`GET /api/v1/approvals`). Empty for any device that is nobody's approver. */
    public suspend fun fetchApprovals(hubUrl: String): FlashResult<List<EnterpriseApproval>> =
        hubGet(hubUrl, HubContract.PATH_APPROVALS) { body ->
            HubRestCodec.decodeList<ApprovalDto>(body).map {
                EnterpriseApproval(it.challengeId, it.action, it.admin, it.target, it.nonce, it.code, it.expiresAtMs)
            }
        }

    /**
     * Sends this device's signature for one approval (`POST /api/v1/approvals/{id}`). [signature] comes from
     * `HubClientAuth.signApproval`. A refusal (expired, already answered, not this device's) is a 403 failure sentence.
     */
    public suspend fun submitApproval(hubUrl: String, challengeId: String, signature: String): FlashResult<Unit> =
        hubPost(hubUrl, "${HubContract.PATH_APPROVALS}/$challengeId", HubRestCodec.encode(ApprovalSubmitBody(signature = signature)), refusalMayCarryBody = false) { Unit }

    override suspend fun fetchVisibleGroups(hubUrl: String, deviceId: String, department: String?): FlashResult<List<EnterpriseDirectoryGroup>> =
        hubGet(hubUrl, HubContract.PATH_DIRECTORY_GROUPS, mapOf("department" to department)) { body ->
            HubRestCodec.decodeList<GroupDto>(body).map { it.toDirectoryGroup() }
        }

    override suspend fun fetchDepartmentMembers(hubUrl: String, departmentId: String): FlashResult<List<EnterpriseDirectoryMember>> =
        hubGet(hubUrl, HubContract.PATH_DIRECTORY_MEMBERS, mapOf("departmentId" to departmentId)) { body ->
            HubRestCodec.decodeList<MemberDto>(body).map { it.toDirectoryMember() }
        }

    override suspend fun fetchDepartments(hubUrl: String): FlashResult<List<EnterpriseDepartment>> =
        hubGet(hubUrl, HubContract.PATH_DIRECTORY_DEPARTMENTS) { body ->
            HubRestCodec.decodeList<DepartmentDto>(body).map { it.toDepartment() }
        }

    override suspend fun requestJoin(hubUrl: String, groupId: String, message: String?): FlashResult<EnterpriseJoinResult> =
        hubPost(hubUrl, HubContract.PATH_GROUP_JOIN_REQUEST, HubRestCodec.encode(JoinRequestBody(groupId = groupId, message = message))) { body ->
            HubRestCodec.decodeOrNull<JoinResponse>(body)?.toJoinResult(groupId)
        }

    override suspend fun fetchJoinRequests(hubUrl: String, groupId: String?): FlashResult<List<EnterpriseJoinRequest>> =
        hubGet(hubUrl, HubContract.PATH_GROUP_JOIN_REQUESTS, mapOf("groupId" to groupId)) { body ->
            HubRestCodec.decodeList<JoinRequestDto>(body)
                .filter { groupId == null || it.groupId == groupId }
                .map { it.toJoinRequest() }
        }

    override suspend fun decideJoinRequest(hubUrl: String, requestId: String, approve: Boolean, reason: String?): FlashResult<EnterpriseJoinResult> =
        hubPost(hubUrl, HubContract.PATH_GROUP_JOIN_DECISION, HubRestCodec.encode(JoinDecisionBody(requestId = requestId, approve = approve, reason = reason))) { body ->
            HubRestCodec.decodeOrNull<JoinResponse>(body)?.toJoinResult("")
        }

    override suspend fun fetchDirectorySync(
        hubUrl: String,
        since: Long,
        epoch: String?,
        cursor: String?,
        limit: Int,
    ): DirectorySyncPageResult = withContext(ioDispatcher) {
        val reply = when (
            val r = execute(hubUrl) { base, bearer ->
                httpClient.get("$base${HubContract.PATH_DIRECTORY_SYNC}") {
                    parameter("since", since.toString())
                    if (!epoch.isNullOrBlank()) parameter("epoch", epoch)
                    if (!cursor.isNullOrBlank()) parameter("cursor", cursor)
                    parameter("limit", limit.toString())
                    authorize(bearer)
                }
            }
        ) {
            is FlashResult.Failure -> {
                val msg = (r.error as? FlashError.NetworkUnavailable)?.message
                    ?: (r.error as? FlashError.Unknown)?.message
                    ?: "The hub could not be reached."
                return@withContext DirectorySyncPageResult.Unreachable(msg)
            }
            is FlashResult.Success -> r.value
        }
        val status = reply.response.status
        when (status.value) {
            200 -> {
                val dto = runCatching { HubRestCodec.decode<DirectorySyncDto>(reply.body) }.getOrNull()
                if (dto != null) DirectorySyncPageResult.Success(dto)
                else DirectorySyncPageResult.ServerError(200, "Malformed directory sync response")
            }
            404, 405 -> DirectorySyncPageResult.HubUnsupported
            429 -> {
                val retryAfter = reply.response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                DirectorySyncPageResult.RateLimited(retryAfter)
            }
            401, 403 -> DirectorySyncPageResult.AuthRefused(status.value, reply.body.ifBlank { status.description })
            in 500..599 -> DirectorySyncPageResult.ServerError(status.value, reply.body.ifBlank { status.description })
            else -> DirectorySyncPageResult.ServerError(status.value, reply.body.ifBlank { status.description })
        }
    }

    /**
     * Publishes this device's end-to-end SEALING public key (ADR-121, ADR-124): [sealingPublicKey] is the Base64 SPKI of
     * the key, [signature] the Base64 identity-key signature from `HubSealingKeyPair.bindingSignature`. The hub verifies
     * the binding before it serves the key to colleagues. A hub without the route answers 404 ("hub too old").
     * Callers that need to tell the answers apart (retired key, rate limit, offline) use [publish].
     */
    public suspend fun publishSealingKey(hubUrl: String, sealingPublicKey: String, signature: String): FlashResult<Unit> =
        when (publish(hubUrl, sealingPublicKey, signature)) {
            SealingPublishResult.PUBLISHED -> FlashResult.Success(Unit)
            SealingPublishResult.KEY_RETIRED -> friendlyHttpFailure(HttpStatusCode.Conflict)
            SealingPublishResult.RATE_LIMITED -> friendlyHttpFailure(HttpStatusCode.TooManyRequests)
            SealingPublishResult.HUB_TOO_OLD -> friendlyHttpFailure(HttpStatusCode.NotFound)
            SealingPublishResult.REFUSED -> friendlyHttpFailure(HttpStatusCode.Forbidden)
            SealingPublishResult.UNREACHABLE -> FlashResult.Failure(FlashError.NetworkUnavailable("The hub could not be reached."))
            SealingPublishResult.SERVER_ERROR -> friendlyHttpFailure(HttpStatusCode.InternalServerError)
        }

    override suspend fun publish(hubUrl: String, sealingPublicKey: String, signature: String): SealingPublishResult =
        withContext(ioDispatcher) {
            val body = HubRestCodec.encode(SealingKeyRequest(sealingPublicKey = sealingPublicKey, signature = signature))
            val reply = when (
                val r = execute(hubUrl) { base, bearer ->
                    httpClient.put("$base${HubContract.PATH_DEVICE_SEALING_KEY}") {
                        contentType(ContentType.Application.Json)
                        authorize(bearer)
                        setBody(body)
                    }
                }
            ) {
                is FlashResult.Failure -> return@withContext SealingPublishResult.UNREACHABLE
                is FlashResult.Success -> r.value
            }
            when (reply.response.status.value) {
                200 -> SealingPublishResult.PUBLISHED
                409 -> SealingPublishResult.KEY_RETIRED
                429 -> SealingPublishResult.RATE_LIMITED
                404, 405 -> SealingPublishResult.HUB_TOO_OLD
                400, 401, 403, 413 -> SealingPublishResult.REFUSED
                else -> SealingPublishResult.SERVER_ERROR
            }
        }

    override suspend fun remove(hubUrl: String): Boolean = withContext(ioDispatcher) {
        val reply = when (
            val r = execute(hubUrl) { base, bearer ->
                httpClient.delete("$base${HubContract.PATH_DEVICE_SEALING_KEY}") { authorize(bearer) }
            }
        ) {
            is FlashResult.Failure -> return@withContext false
            is FlashResult.Success -> r.value
        }
        reply.response.status == HttpStatusCode.OK
    }

    /**
     * Source-compatible wrapper: true when the device joined OR is waiting for approval (the old behaviour folded both
     * into success). New code uses [requestJoin].
     */
    public suspend fun requestJoinGroup(
        hubUrl: String,
        groupId: String,
        deviceId: String,
        departmentId: String? = null,
    ): FlashResult<Boolean> = when (val r = requestJoin(hubUrl, groupId)) {
        is FlashResult.Success -> if (r.value.status == EnterpriseJoinStatus.REJECTED) {
            FlashResult.Failure(FlashError.Unknown(r.value.reason ?: "The request was refused."))
        } else {
            FlashResult.Success(true)
        }
        is FlashResult.Failure -> r
    }

    public companion object {
        public fun createDefaultClient(): HttpClient = EnterpriseEnrollmentClient.createDefaultClient()
    }
}

private fun JoinResponse.toJoinResult(fallbackGroupId: String): EnterpriseJoinResult? {
    val mapped = when (status) {
        HubDirectoryValues.JOIN_STATUS_JOINED -> EnterpriseJoinStatus.JOINED
        HubDirectoryValues.JOIN_STATUS_PENDING -> EnterpriseJoinStatus.PENDING
        HubDirectoryValues.JOIN_STATUS_REJECTED -> EnterpriseJoinStatus.REJECTED
        else -> return null
    }
    return EnterpriseJoinResult(mapped, groupId ?: fallbackGroupId, name, requestId, reason)
}

private fun JoinRequestDto.toJoinRequest(): EnterpriseJoinRequest = EnterpriseJoinRequest(
    requestId = requestId,
    groupId = groupId,
    deviceId = deviceId,
    displayName = displayName.ifBlank { deviceId },
    requestedAtMs = requestedAt,
    message = message,
)
