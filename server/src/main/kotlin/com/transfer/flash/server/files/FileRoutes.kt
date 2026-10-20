package com.transfer.flash.server.files

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.MESSAGE_RATE_LIMITED
import com.transfer.flash.server.auth.TokenBucketLimiter
import com.transfer.flash.server.auth.receiveBoundedBinary
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.requirePrincipal
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import com.transfer.flash.server.auth.statusFor
import com.transfer.flash.server.relay.HubPrincipalResolver
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The sealed file routes (ADR-215, `docs/plans/HUB-FILES-PLAN.md` section 2).
 *
 * - `POST   /api/v1/files`: create an upload (idempotent per `fileId`)
 * - `PUT    /api/v1/files/{fileId}/chunks/{index}`: one opaque chunk
 * - `POST   /api/v1/files/{fileId}/complete`: every chunk is there, the file becomes readable
 * - `GET    /api/v1/files/{fileId}`: metadata (sender or recipient)
 * - `GET    /api/v1/files/{fileId}/chunks/{index}`: one opaque chunk (sender or recipient)
 * - `DELETE /api/v1/files/{fileId}`: the sender cancels and the hub deletes the bytes
 *
 * Nothing here reads, parses or logs a chunk. The only values in an audit line are the first 8 characters of the id, the
 * chunk index and byte counts: enough to trace an incident, nothing about the content.
 */
fun Route.fileRoutes(
    store: FileStore,
    principalResolver: HubPrincipalResolver,
    audit: AuditSink = AuditSink.None,
    limits: HubRateLimits = HubRateLimits.unlimited(),
) {
    route(HubContract.PATH_API) {
        route("/files") {

            post {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                if (!call.admit(limits.fileWrite, principal.deviceId)) return@post
                val raw = call.receiveBoundedText() ?: return@post
                val request = HubRestCodec.decodeOrNull<CreateFileRequestDto>(raw)
                if (request == null) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_JSON)
                    return@post
                }
                val outcome = withContext(Dispatchers.IO) { store.create(principal.deviceId, request) }
                when (outcome) {
                    is FileOutcome.Ok -> {
                        audit.record(
                            principal.deviceId,
                            "file.create",
                            "id=${request.fileId.take(8)} bytes=${request.totalBytes} " +
                                "to=${request.toDeviceId?.take(8) ?: "group:${request.groupId?.take(8)}"}",
                        )
                        call.respondJson(outcome.value, HttpStatusCode.Created)
                    }
                    else -> call.refuse(outcome)
                }
            }

            put("/{fileId}/chunks/{index}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@put
                if (!call.admit(limits.fileWrite, principal.deviceId)) return@put
                val fileId = call.parameters["fileId"].orEmpty()
                val index = call.parameters["index"]?.toIntOrNull()
                if (index == null) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, FileMessages.CHUNK_INDEX)
                    return@put
                }
                val body = call.receiveBoundedBinary(HubContract.MAX_CHUNK_BYTES) ?: return@put
                val declaredHash = call.request.header(HEADER_CHUNK_SHA256)
                val outcome = withContext(Dispatchers.IO) {
                    store.putChunk(principal.deviceId, fileId, index, body, declaredHash)
                }
                when (outcome) {
                    is FileOutcome.Ok -> {
                        audit.record(
                            principal.deviceId,
                            "file.chunk",
                            "id=${fileId.take(8)} idx=$index bytes=${body.size} new=${outcome.value.newChunk} " +
                                "have=${outcome.value.receivedChunks}",
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    else -> call.refuse(outcome)
                }
            }

            post("/{fileId}/complete") {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                if (!call.admit(limits.fileWrite, principal.deviceId)) return@post
                val fileId = call.parameters["fileId"].orEmpty()
                val outcome = withContext(Dispatchers.IO) { store.complete(principal.deviceId, fileId) }
                when (outcome) {
                    is FileOutcome.Ok -> {
                        audit.record(principal.deviceId, "file.complete", "id=${fileId.take(8)} bytes=${outcome.value.totalBytes}")
                        call.respondJson(outcome.value)
                    }
                    else -> call.refuse(outcome)
                }
            }

            get("/{fileId}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                if (!call.admit(limits.fileRead, principal.deviceId)) return@get
                val fileId = call.parameters["fileId"].orEmpty()
                val outcome = withContext(Dispatchers.IO) { store.status(principal.deviceId, fileId) }
                when (outcome) {
                    is FileOutcome.Ok -> call.respondJson(outcome.value)
                    else -> call.refuse(outcome)
                }
            }

            get("/{fileId}/chunks/{index}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                if (!call.admit(limits.fileRead, principal.deviceId)) return@get
                val fileId = call.parameters["fileId"].orEmpty()
                val index = call.parameters["index"]?.toIntOrNull()
                if (index == null) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, FileMessages.CHUNK_INDEX)
                    return@get
                }
                val outcome = withContext(Dispatchers.IO) { store.readChunk(principal.deviceId, fileId, index) }
                when (outcome) {
                    is FileOutcome.Ok -> {
                        audit.record(principal.deviceId, "file.read", "id=${fileId.take(8)} idx=$index bytes=${outcome.value.size}")
                        call.respondBytes(outcome.value, ContentType.Application.OctetStream)
                    }
                    else -> call.refuse(outcome)
                }
            }

            delete("/{fileId}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@delete
                if (!call.admit(limits.fileWrite, principal.deviceId)) return@delete
                val fileId = call.parameters["fileId"].orEmpty()
                val outcome = withContext(Dispatchers.IO) { store.cancel(principal.deviceId, fileId) }
                when (outcome) {
                    is FileOutcome.Ok -> {
                        audit.record(principal.deviceId, "file.cancel", "id=${fileId.take(8)}")
                        call.respond(HttpStatusCode.NoContent)
                    }
                    else -> call.refuse(outcome)
                }
            }
        }
    }
}

/** Per-device rate limiting, answering 429 with `Retry-After`; false means the route must return. */
private suspend fun ApplicationCall.admit(limiter: TokenBucketLimiter, deviceId: String): Boolean {
    if (limiter.tryAcquire(deviceId)) return true
    response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(deviceId).toString())
    respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, MESSAGE_RATE_LIMITED)
    return false
}

/** Turns a non-`Ok` store outcome into the contract error body (fixed sentences, never exception text). */
private suspend fun ApplicationCall.refuse(outcome: FileOutcome<*>) {
    when (outcome) {
        is FileOutcome.Refused -> respondError(statusFor(outcome.code), outcome.code, outcome.message)
        FileOutcome.Unavailable ->
            respondError(HttpStatusCode.ServiceUnavailable, HubErrorCodes.ERR_INTERNAL, MESSAGE_CHUNK_MISSING)
        is FileOutcome.Failed ->
            respondError(HttpStatusCode.InternalServerError, HubErrorCodes.ERR_INTERNAL, MESSAGE_FILE_INTERNAL)
        is FileOutcome.Ok ->
            respondError(HttpStatusCode.InternalServerError, HubErrorCodes.ERR_INTERNAL, MESSAGE_FILE_INTERNAL)
    }
}

/** Optional header carrying the SHA-256 of the chunk body, so a retry can be proven identical. */
const val HEADER_CHUNK_SHA256: String = "X-Flash-Chunk-Sha256"

private const val MESSAGE_BAD_JSON = "The request body is not a valid file request."

private const val MESSAGE_CHUNK_MISSING =
    "The hub has this file's details but not its bytes (a restore without data/files). Try again later."

private const val MESSAGE_FILE_INTERNAL = "The hub could not handle that file request. Try again later."
