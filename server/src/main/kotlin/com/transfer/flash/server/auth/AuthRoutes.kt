package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.EnrollRequest
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.HubInfo
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.OkResponse
import com.transfer.flash.core.hubprotocol.rest.RenewRequest
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.relay.HubPrincipalResolver
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Ktor routes for identity and enrolment (ADR-109, ADR-111, `docs/protocol.md`).
 *
 * Public (no bearer; each has its own per-address rate limit):
 * - `GET|POST /api/v1/auth/challenge`, `POST /api/v1/auth/verify`: nonce, then certificate-chain login -> 15 min bearer.
 * - `POST /api/v1/enroll`: single-use invite exchange for a delegated device certificate (proof of possession).
 * - `POST /api/v1/enroll/renew`: renews a certificate inside the renewal window (proof of possession of its key).
 * - `GET /api/v1/hub`: server id, name, wire versions, server time.
 * - `GET /enroll`: the browser landing page of an invitation link (always built from the CONFIGURED external URL).
 *
 * Bearer required:
 * - `GET /api/v1/revocations`: the signed revocation list (404 until one is published).
 * - `POST /api/v1/device/unenroll`: the device leaves (certificates revoked, directory membership removed, audited).
 *
 * Bodies are capped at 64 KiB (413). Every error is an `ErrorBody` with a fixed sentence (SEC-16).
 */
fun Route.authRoutes(
    challengeAuth: ChallengeResponseAuth,
    enrollmentService: EnrollmentService,
    serverExternalUrl: String = "http://localhost:8443",
    bearerTokens: BearerTokenStore = BearerTokenStore(),
    revocations: RevocationService = RevocationService.None,
    selfRevocation: SelfRevocation? = revocations as? SelfRevocation,
    directory: OrganizationDirectory? = null,
    audit: AuditSink = AuditSink.None,
    limits: HubRateLimits = HubRateLimits(),
    addresses: ClientAddressResolver = ClientAddressResolver(),
    hubName: String = "",
    clock: () -> Long = System::currentTimeMillis,
    principalResolver: HubPrincipalResolver = BearerPrincipalResolver(bearerTokens),
    /** The largest sealed file this hub accepts (ADR-215); 0 when the hub carries no files. */
    maxFileBytes: () -> Long = { 0L },
) {
    // GET /enroll: the browser landing page. Always shows the CONFIGURED hub address (a `?server=` parameter is ignored:
    // it would let a link point a browser, and the app it opens, at an attacker's hub), and says the same thing for an
    // invalid, expired, used or revoked token so the page is not an oracle.
    // The landing page's stylesheet and script (static, same origin, CSP `script-src 'self'`).
    val landingAssets = com.transfer.flash.server.admin.StaticAssets("enroll-ui")
    get("/enroll-assets/{path...}") {
        landingAssets.respond(call, call.parameters.getAll("path").orEmpty())
    }
    route(HubContract.PATH_ENROLL_LANDING) {
        get {
            if (!call.withinLimit(limits.enroll, addresses)) return@get
            call.response.header("Cache-Control", "no-store")
            val token = call.request.queryParameters["token"].orEmpty()
            val record = if (token.length in 1..MAX_TOKEN_CHARS) enrollmentService.getTokenRecord(token) else null
            val isValid = record != null && !record.revoked && record.usesLeft > 0 && clock() <= record.scope.expiry
            val deepLink = if (isValid) EnrollmentInvite.buildLink(serverExternalUrl, token, enrollmentService.serverId) else ""
            val html = com.transfer.flash.server.admin.EnrollLandingPage.render(
                deepLink = deepLink,
                hubUrl = serverExternalUrl,
                department = if (isValid) record?.scope?.department else null,
                role = if (isValid) record?.scope?.role ?: "member" else "member",
                isValid = isValid,
            )
            val status = if (isValid) HttpStatusCode.OK else HttpStatusCode.BadRequest
            call.respondText(html, ContentType.Text.Html, status)
        }
    }

    route(HubContract.PATH_API) {
        // GET /api/v1/hub
        get("/hub") {
            if (!call.withinLimit(limits.auth, addresses)) return@get
            call.respondJson(
                HubInfo(
                    serverId = enrollmentService.serverId,
                    name = hubName,
                    serverTime = clock(),
                    maxFileBytes = maxFileBytes(),
                ),
            )
        }

        route("/auth") {
            // GET + POST /api/v1/auth/challenge
            get("/challenge") {
                if (!call.withinLimit(limits.auth, addresses)) return@get
                call.respondJson(newChallengeResponse(challengeAuth, clock()))
            }
            post("/challenge") {
                if (!call.withinLimit(limits.auth, addresses)) return@post
                call.respondJson(newChallengeResponse(challengeAuth, clock()))
            }

            // POST /api/v1/auth/verify
            post("/verify") {
                if (!call.withinLimit(limits.auth, addresses)) return@post
                val body = call.receiveBoundedText() ?: return@post
                val request = HubRestCodec.decodeOrNull<AuthVerifyRequest>(body)
                if (request == null) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_REQUEST)
                    return@post
                }
                if (request.delegationChain.size > 1) {
                    call.respondError(HttpStatusCode.Unauthorized, HubErrorCodes.ERR_AUTH, MESSAGE_AUTH_FAILED)
                    return@post
                }

                val result = challengeAuth.verifyChallenge(
                    serverNonce = request.serverNonce,
                    clientNonce = request.clientNonce,
                    deviceId = request.deviceId,
                    signature = request.signature,
                    deviceCert = request.deviceCert,
                    delegationCert = request.delegationChain.firstOrNull(),
                )

                result.fold(
                    onSuccess = { principal ->
                        val hubPrincipal = principal.toHubPrincipal()
                        val (token, expiresAt) = bearerTokens.issue(hubPrincipal)
                        call.respondJson(
                            AuthVerifyResponse(token = token, expiresAt = expiresAt, principal = hubPrincipal),
                        )
                    },
                    onFailure = { error ->
                        val code = (error as? AuthRejected)?.code ?: HubErrorCodes.ERR_AUTH
                        val message = when (code) {
                            HubErrorCodes.ERR_REVOKED -> MESSAGE_REVOKED
                            HubErrorCodes.ERR_EXPIRED -> MESSAGE_EXPIRED
                            else -> MESSAGE_AUTH_FAILED
                        }
                        call.respondError(statusFor(code), code, message)
                    },
                )
            }
        }

        // POST /api/v1/enroll
        post("/enroll") {
            if (!call.withinLimit(limits.enroll, addresses)) return@post
            val body = call.receiveBoundedText() ?: return@post
            val request = HubRestCodec.decodeOrNull<EnrollRequest>(body)
            if (request == null || request.token.isBlank() || request.deviceId.isBlank() || request.subjectPublicKey.isBlank()) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_REQUEST)
                return@post
            }
            if (request.proof.isBlank()) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_PROOF_REQUIRED)
                return@post
            }

            val result = enrollmentService.enrollDevice(
                token = request.token,
                deviceId = request.deviceId,
                subjectPublicKey = request.subjectPublicKey,
                proof = request.proof,
                friendlyName = request.friendlyName,
                currentTimeMs = clock(),
            )

            result.fold(
                onSuccess = { enrollment ->
                    audit.record(
                        actor = request.deviceId,
                        action = if (enrollment.reissued) "device.enroll.reissued" else "device.enroll",
                        details = "serial=${enrollment.deviceCert.serial} invite=${enrollment.invite?.inviteId ?: "-"}",
                    )
                    call.respondJson(
                        EnrollResponse(
                            serverId = enrollmentService.serverId,
                            rootFingerprint = enrollment.rootFingerprint,
                            rootPublicKey = enrollment.rootPublicKey,
                            deviceCert = enrollment.deviceCert,
                            delegationChain = enrollment.delegationChain,
                            hubName = hubName,
                        ),
                    )
                },
                onFailure = { error ->
                    audit.record(actor = request.deviceId.take(128), action = "device.enroll.refused", details = refusalReason(error))
                    val (status, code) = refusalStatus(error)
                    val message = if (code == HubErrorCodes.ERR_INTERNAL) MESSAGE_INTERNAL else error.message ?: MESSAGE_BAD_REQUEST
                    call.respondError(status, code, message)
                },
            )
        }

        // POST /api/v1/enroll/renew
        post("/enroll/renew") {
            if (!call.withinLimit(limits.enroll, addresses)) return@post
            val body = call.receiveBoundedText() ?: return@post
            val request = HubRestCodec.decodeOrNull<RenewRequest>(body)
            if (request == null) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_REQUEST)
                return@post
            }
            val proven = challengeAuth.verifyRenewal(
                serverNonce = request.serverNonce,
                clientNonce = request.clientNonce,
                deviceId = request.deviceId,
                signature = request.signature,
                deviceCert = request.deviceCert,
                currentTimeMs = clock(),
            )
            if (proven.isFailure) {
                call.respondError(HttpStatusCode.Unauthorized, HubErrorCodes.ERR_AUTH, MESSAGE_AUTH_FAILED)
                return@post
            }
            enrollmentService.renew(request.deviceCert, clock()).fold(
                onSuccess = { renewal ->
                    audit.record(
                        actor = request.deviceId,
                        action = "device.renew",
                        details = "old=${request.deviceCert.serial} new=${renewal.deviceCert.serial}",
                    )
                    call.respondJson(
                        EnrollResponse(
                            status = EnrollResponse.STATUS_RENEWED,
                            serverId = enrollmentService.serverId,
                            rootFingerprint = renewal.rootFingerprint,
                            rootPublicKey = renewal.rootPublicKey,
                            deviceCert = renewal.deviceCert,
                            delegationChain = renewal.delegationChain,
                            hubName = hubName,
                        ),
                    )
                },
                onFailure = { error ->
                    audit.record(actor = request.deviceId.take(128), action = "device.renew.refused", details = refusalReason(error))
                    val revoked = error is SecurityException && revocations.isRevoked(request.deviceCert.serial, request.deviceId)
                    val (status, code) = if (revoked) HttpStatusCode.Forbidden to HubErrorCodes.ERR_REVOKED else refusalStatus(error)
                    val message = when (code) {
                        HubErrorCodes.ERR_REVOKED -> MESSAGE_REVOKED
                        HubErrorCodes.ERR_INTERNAL -> MESSAGE_INTERNAL
                        else -> error.message ?: MESSAGE_BAD_REQUEST
                    }
                    call.respondError(status, code, message)
                },
            )
        }

        // GET /api/v1/revocations
        // Public (rate limited per client address): the list is root-signed and a REVOKED device, which by definition
        // can no longer obtain a bearer token, must be able to confirm its own revocation (client contract test, ADR-124).
        get("/revocations") {
            if (!call.withinLimit(limits.unauthenticated, addresses)) return@get
            val list = revocations.current()
            if (list == null) {
                call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, MESSAGE_NO_REVOCATIONS)
                return@get
            }
            call.respondText(HubRestCodec.encode(list), ContentType.Application.Json)
        }

        // POST /api/v1/device/unenroll
        post("/device/unenroll") {
            val principal = call.requirePrincipal(principalResolver) ?: return@post
            val self = selfRevocation
            if (self == null) {
                call.respondError(HttpStatusCode.NotImplemented, HubErrorCodes.ERR_INTERNAL, MESSAGE_INTERNAL)
                return@post
            }
            self.revokeSelf(principal.deviceId, by = principal.deviceId)
            bearerTokens.revokeDevice(principal.deviceId)
            directory?.removeDevice(principal.deviceId)
            audit.record(actor = principal.deviceId, action = "device.unenroll", details = "serial=${principal.certSerial}")
            call.respondJson(OkResponse())
        }
    }
}

// The nonce is stamped with the same clock that later verifies it (an injected clock must not be mixed with the wall clock).
private fun newChallengeResponse(challengeAuth: ChallengeResponseAuth, nowMs: Long): AuthChallengeResponse {
    val challenge = challengeAuth.generateChallenge(nowMs)
    return AuthChallengeResponse(
        serverId = challengeAuth.serverId,
        serverNonce = challenge.serverNonce,
        issuedAt = challenge.timestamp,
        ttlSeconds = challenge.ttlSeconds,
    )
}

/** Maps an enrolment failure to HTTP. The service's messages are authored sentences, never parser or exception text. */
private fun refusalStatus(error: Throwable): Pair<HttpStatusCode, String> = when {
    error is InviteRefusedException && error.refusal == InviteRefusal.EXPIRED -> HttpStatusCode.Gone to HubErrorCodes.ERR_EXPIRED
    error is InviteRefusedException -> HttpStatusCode.Conflict to HubErrorCodes.ERR_CONFLICT
    error is IllegalArgumentException -> HttpStatusCode.BadRequest to HubErrorCodes.ERR_BAD_REQUEST
    error is RoleNotAllowedException -> HttpStatusCode.Forbidden to HubErrorCodes.ERR_ROLE_NOT_ALLOWED
    error is SecurityException -> HttpStatusCode.Forbidden to HubErrorCodes.ERR_FORBIDDEN
    error is IllegalStateException && error.message.orEmpty().contains("quota", ignoreCase = true) ->
        HttpStatusCode.TooManyRequests to HubErrorCodes.ERR_QUOTA_EXCEEDED
    error is IllegalStateException -> HttpStatusCode.Conflict to HubErrorCodes.ERR_CONFLICT
    else -> HttpStatusCode.InternalServerError to HubErrorCodes.ERR_INTERNAL
}

/** A short audit reason: the exception class only (the message may name the token or device). */
private fun refusalReason(error: Throwable): String = "reason=" + when (error) {
    is InviteRefusedException -> if (error.refusal == InviteRefusal.EXPIRED) "invite-expired" else "invite-used"
    is IllegalArgumentException -> "invalid"
    is SecurityException -> "forbidden"
    is IllegalStateException -> if (error.message.orEmpty().contains("quota", ignoreCase = true)) "quota" else "conflict"
    else -> "error"
}

private const val MAX_TOKEN_CHARS = 256
private const val MESSAGE_BAD_REQUEST = "The request could not be processed."
private const val MESSAGE_PROOF_REQUIRED = "Enrollment requires proof of possession of the device key."
private const val MESSAGE_AUTH_FAILED = "Authentication failed."
private const val MESSAGE_REVOKED = "This device has been removed from the organisation."
private const val MESSAGE_EXPIRED = "This device's enrolment has expired. Renew it."
private const val MESSAGE_INTERNAL = "The hub could not complete the request."
private const val MESSAGE_NO_REVOCATIONS = "No revocation list has been published."
