package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.server.core.canonicalPath
import com.transfer.flash.server.relay.HubPrincipalResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Where an audit event goes. The admin `AuditLogger` implements it; routes that audit do not depend on the admin package. */
fun interface AuditSink {
    fun record(actor: String, action: String, details: String)

    companion object {
        val None: AuditSink = AuditSink { _, _, _ -> }
    }
}

/** The authenticated principal the [HubApiGate] resolved for this call. */
val HubPrincipalAttribute: AttributeKey<HubPrincipal> = AttributeKey("flash.hub.principal")

/**
 * Every route under `/api/v1` needs a valid bearer token EXCEPT the few a device must reach before it has one
 * (SEC-03): enrol, renew, the auth challenge and verify, the hub info and the root-signed revocation list (a revoked
 * device has no token and must still be able to confirm its revocation; ADR-124). The gate also rate limits by principal
 * (authenticated) and by client address (everything without a valid token), and records the principal for the handlers.
 *
 * Handlers do NOT trust the gate alone: they call [requirePrincipal] too (defence in depth, and so a route mounted in a
 * test without the gate is still protected).
 */
class HubApiGate(
    private val resolver: HubPrincipalResolver,
    private val limits: HubRateLimits = HubRateLimits(),
    private val addresses: ClientAddressResolver = ClientAddressResolver(),
    private val publicPaths: Set<String> = DEFAULT_PUBLIC_PATHS,
) {
    fun isPublic(path: String): Boolean = normalise(path) in publicPaths

    /** Returns true when the call may proceed; responds and returns false otherwise. */
    suspend fun admit(call: ApplicationCall): Boolean {
        val path = call.canonicalPath()
        if (path != HubContract.PATH_API && !path.startsWith(HubContract.PATH_API + "/")) return true
        if (isPublic(path)) return true
        val principal = resolver.resolve(call)
        if (principal == null) {
            val ip = addresses.clientKey(call)
            if (!limits.unauthenticated.tryAcquire(ip)) {
                call.tooManyRequests(limits.unauthenticated.retryAfterSeconds(ip))
                return false
            }
            call.respondError(HttpStatusCode.Unauthorized, HubErrorCodes.ERR_AUTH, MESSAGE_AUTH_REQUIRED)
            return false
        }
        if (!limits.principal.tryAcquire(principal.deviceId)) {
            call.tooManyRequests(limits.principal.retryAfterSeconds(principal.deviceId))
            return false
        }
        call.attributes.put(HubPrincipalAttribute, principal)
        return true
    }

    companion object {
        val DEFAULT_PUBLIC_PATHS: Set<String> = setOf(
            HubContract.PATH_ENROLL,
            HubContract.PATH_ENROLL_RENEW,
            HubContract.PATH_AUTH_CHALLENGE,
            HubContract.PATH_AUTH_VERIFY,
            HubContract.PATH_HUB_INFO,
            HubContract.PATH_REVOCATIONS,
        )

        private fun normalise(path: String): String = if (path.length > 1) path.trimEnd('/') else path
    }
}

/** Installs [gate] in front of every route of the application. */
fun Application.installHubApiGate(gate: HubApiGate) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (!gate.admit(call)) finish()
    }
}

/**
 * The authenticated principal of [this] call: the one the gate resolved, or one resolved here from the bearer.
 * Responds 401 and returns null when there is none (the caller must return).
 */
suspend fun ApplicationCall.requirePrincipal(resolver: HubPrincipalResolver?): HubPrincipal? {
    if (attributes.contains(HubPrincipalAttribute)) return attributes[HubPrincipalAttribute]
    val principal = resolver?.resolve(this)
    if (principal == null) {
        respondError(HttpStatusCode.Unauthorized, HubErrorCodes.ERR_AUTH, MESSAGE_AUTH_REQUIRED)
        return null
    }
    attributes.put(HubPrincipalAttribute, principal)
    return principal
}

/** 429 with `Retry-After`. */
internal suspend fun ApplicationCall.tooManyRequests(retryAfterSeconds: Long) {
    response.header("Retry-After", retryAfterSeconds.coerceAtLeast(1).toString())
    respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, MESSAGE_RATE_LIMITED)
}

/** Rate limits [call] against [limiter] by client address; responds 429 and returns false when over budget. */
internal suspend fun ApplicationCall.withinLimit(limiter: TokenBucketLimiter, addresses: ClientAddressResolver): Boolean {
    val ip = addresses.clientKey(this)
    if (limiter.tryAcquire(ip)) return true
    tooManyRequests(limiter.retryAfterSeconds(ip))
    return false
}

/**
 * Reads the request body as text, refusing more than [maxBytes] (default 64 KiB: [HubContract.MAX_REST_BODY_BYTES]) with
 * a 413 BEFORE buffering it all (SEC-15). Returns null after having responded.
 */
suspend fun ApplicationCall.receiveBoundedText(maxBytes: Int = HubContract.MAX_REST_BODY_BYTES): String? {
    val declared = request.headers["Content-Length"]?.toLongOrNull()
    if (declared != null && declared > maxBytes) {
        respondError(HttpStatusCode.PayloadTooLarge, HubErrorCodes.ERR_TOO_LARGE, MESSAGE_TOO_LARGE)
        return null
    }
    val packet = receiveChannel().readRemaining(maxBytes + 1L)
    val bytes = packet.readByteArray()
    if (bytes.size > maxBytes) {
        respondError(HttpStatusCode.PayloadTooLarge, HubErrorCodes.ERR_TOO_LARGE, MESSAGE_TOO_LARGE)
        return null
    }
    return bytes.decodeToString()
}

/**
 * Reads a raw binary body of at most [maxBytes] (ADR-215: one sealed chunk), refusing more with a 413 BEFORE buffering
 * it all. Returns null after having responded. The bytes are never decoded: a sealed chunk is opaque to the hub.
 */
suspend fun ApplicationCall.receiveBoundedBinary(maxBytes: Int): ByteArray? {
    val declared = request.headers["Content-Length"]?.toLongOrNull()
    if (declared != null && declared > maxBytes) {
        respondError(HttpStatusCode.PayloadTooLarge, HubErrorCodes.ERR_TOO_LARGE, MESSAGE_TOO_LARGE)
        return null
    }
    val packet = receiveChannel().readRemaining(maxBytes + 1L)
    val bytes = packet.readByteArray()
    if (bytes.size > maxBytes) {
        respondError(HttpStatusCode.PayloadTooLarge, HubErrorCodes.ERR_TOO_LARGE, MESSAGE_TOO_LARGE)
        return null
    }
    return bytes
}

internal const val MESSAGE_AUTH_REQUIRED = "Authentication required."
internal const val MESSAGE_RATE_LIMITED = "Too many requests. Try again later."
internal const val MESSAGE_TOO_LARGE = "The request is too large."
