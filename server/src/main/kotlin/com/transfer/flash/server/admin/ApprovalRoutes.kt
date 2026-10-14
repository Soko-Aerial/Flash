package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.ApprovalDto
import com.transfer.flash.core.hubprotocol.rest.ApprovalSubmitBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.OkResponse
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.requirePrincipal
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import com.transfer.flash.server.auth.tooManyRequests
import com.transfer.flash.server.relay.HubPrincipalResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.Base64

/**
 * The phone side of the co-signature for the most dangerous console actions (ADR-131, FO-14): `GET /api/v1/approvals` lists what is
 * waiting for THIS device, `POST /api/v1/approvals/{id}` submits its signature.
 *
 * Everything is decided on the server from the authenticated principal, never from the body:
 * - the device id is the bearer's, so a device sees and answers only challenges made for it;
 * - [StepUpAuth.approve] checks the signature against the key the registry holds for that device, over bytes of their own domain
 *   (`HubStepUp.approvalBytes`), and only while the administrator is still bound to this phone and the phone is still ACTIVE.
 *
 * A device that is nobody's approver simply gets an empty list. The hub learns nothing it did not know: the challenge is its own.
 */
fun Route.approvalRoutes(
    stepUp: StepUpAuth,
    principalResolver: HubPrincipalResolver,
    limits: HubRateLimits = HubRateLimits(),
    audit: AuditSink = AuditSink.None,
) {
    get(HubContract.PATH_APPROVALS) {
        val principal = call.requirePrincipal(principalResolver) ?: return@get
        if (!limits.principal.tryAcquire(principal.deviceId)) {
            call.tooManyRequests(limits.principal.retryAfterSeconds(principal.deviceId))
            return@get
        }
        call.respondJson(
            stepUp.pendingApprovals(principal.deviceId).map {
                ApprovalDto(it.challengeId, it.action, it.adminUsername, it.target, it.nonce, it.approvalCode, it.expiresAtMs)
            },
        )
    }

    post("${HubContract.PATH_APPROVALS}/{id}") {
        val principal = call.requirePrincipal(principalResolver) ?: return@post
        if (!limits.principal.tryAcquire(principal.deviceId)) {
            call.tooManyRequests(limits.principal.retryAfterSeconds(principal.deviceId))
            return@post
        }
        val id = call.parameters["id"].orEmpty().take(HubContract.MAX_ID_LENGTH)
        val text = call.receiveBoundedText() ?: return@post
        val body = HubRestCodec.decodeOrNull<ApprovalSubmitBody>(text)
        val signature = body?.signature?.takeIf { it.length <= MAX_SIGNATURE_CHARS }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (signature == null) {
            call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_REQUEST)
            return@post
        }
        val challenge = stepUp.getChallenge(id)
        if (challenge == null || !stepUp.approve(id, principal.deviceId, signature)) {
            audit.record(principal.deviceId, "stepup.approval.refused", "challenge=${id.take(36)}")
            call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, MESSAGE_REFUSED)
            return@post
        }
        audit.record(principal.deviceId, "stepup.approval.accepted", "action=${challenge.action} admin=${challenge.adminUsername}")
        call.respondJson(OkResponse())
    }
}

private const val MAX_SIGNATURE_CHARS = 256
private const val MESSAGE_BAD_REQUEST = "The request could not be processed."
private const val MESSAGE_REFUSED = "That approval is not valid. It may have expired, already been answered, or not be meant for this device."
