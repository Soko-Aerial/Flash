package com.transfer.flash.server.identity

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.OkResponse
import com.transfer.flash.core.hubprotocol.rest.SealingKeyRequest
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.JvmEcdsaVerifier
import com.transfer.flash.server.auth.Spki
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.requirePrincipal
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import com.transfer.flash.server.auth.tooManyRequests
import com.transfer.flash.server.relay.HubPrincipalResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import java.security.MessageDigest
import java.util.Base64

/**
 * `PUT|DELETE /api/v1/device/sealing-key` (ADR-121, ADR-124): a device publishes the PUBLIC half of its end-to-end
 * sealing key, bound to its identity by a signature.
 *
 * The hub verifies, before it stores anything:
 * 1. the caller is an authenticated principal (the bearer); the device id is the principal's, never the body's, so a
 *    device can only ever publish a key for itself;
 * 2. the key is a canonical NIST P-256 SubjectPublicKeyInfo ([Spki.parseP256]: other curves, RSA, trailing bytes and
 *    off-curve points are refused);
 * 3. the signature is the device identity key's ECDSA signature over [HubAuth.sealingKeyBindingBytes] (the device key
 *    is the one in the registry, i.e. the one the hub certified at enrolment, not a key the request supplies);
 * 4. the key was not this device's key before a rotation (a captured old binding cannot be put back).
 *
 * The hub never receives a private key and never decrypts anything: a sealed payload is opaque to it (ADR-121).
 * Every publication, rotation and removal is audited with the key's fingerprint, never its bytes.
 */
fun Route.sealingKeyRoutes(
    identities: IdentityStore,
    principalResolver: HubPrincipalResolver,
    limits: HubRateLimits = HubRateLimits(),
    audit: AuditSink = AuditSink.None,
    clock: () -> Long = System::currentTimeMillis,
) {
    put(HubContract.PATH_DEVICE_SEALING_KEY) {
        val principal = call.requirePrincipal(principalResolver) ?: return@put
        if (!limits.sealingKey.tryAcquire(principal.deviceId)) {
            call.tooManyRequests(limits.sealingKey.retryAfterSeconds(principal.deviceId))
            return@put
        }
        val text = call.receiveBoundedText() ?: return@put
        val request = HubRestCodec.decodeOrNull<SealingKeyRequest>(text)
        if (request == null || request.signature.length > MAX_SIGNATURE_CHARS) {
            call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_BAD_REQUEST)
            return@put
        }
        val device = identities.device(principal.deviceId)
        if (device == null || device.status != DeviceStatus.ACTIVE) {
            call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, MESSAGE_NOT_ACTIVE)
            return@put
        }
        val spki = Spki.parseP256(request.sealingPublicKey)
        val signature = runCatching { Base64.getDecoder().decode(request.signature) }.getOrNull()
        val identityKey = runCatching { Base64.getDecoder().decode(device.subjectPublicKey) }.getOrNull()
        val bound = spki != null && signature != null && identityKey != null &&
            JvmEcdsaVerifier.verify(identityKey, HubAuth.sealingKeyBindingBytes(device.deviceId, spki.encoded), signature)
        if (!bound) {
            audit.record(principal.deviceId, "device.sealing-key.refused", "reason=invalid")
            call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MESSAGE_INVALID_KEY)
            return@put
        }
        val fingerprint = fingerprintOf(spki!!.encoded)
        when (val outcome = identities.putSealingKey(device.deviceId, request.sealingPublicKey, request.signature, fingerprint, clock())) {
            is SealingPutOutcome.Stored -> {
                audit.record(
                    principal.deviceId,
                    if (outcome.rotated) "device.sealing-key.rotate" else "device.sealing-key.publish",
                    "fp=${fingerprint.take(16)} version=${outcome.record.version}",
                )
                call.respondJson(OkResponse())
            }
            is SealingPutOutcome.Unchanged -> call.respondJson(OkResponse())
            SealingPutOutcome.RetiredKey -> {
                audit.record(principal.deviceId, "device.sealing-key.refused", "reason=retired fp=${fingerprint.take(16)}")
                call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, MESSAGE_RETIRED)
            }
            SealingPutOutcome.NotActive ->
                call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, MESSAGE_NOT_ACTIVE)
        }
    }

    delete(HubContract.PATH_DEVICE_SEALING_KEY) {
        val principal = call.requirePrincipal(principalResolver) ?: return@delete
        if (!limits.sealingKey.tryAcquire(principal.deviceId)) {
            call.tooManyRequests(limits.sealingKey.retryAfterSeconds(principal.deviceId))
            return@delete
        }
        if (identities.deleteSealingKey(principal.deviceId)) {
            audit.record(principal.deviceId, "device.sealing-key.remove", "")
        }
        call.respondJson(OkResponse())
    }
}

/** Upper-case hex SHA-256 of an SPKI. */
internal fun fingerprintOf(spki: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02X".format(it) }

private const val MAX_SIGNATURE_CHARS = 256
private const val MESSAGE_BAD_REQUEST = "The request could not be processed."
private const val MESSAGE_INVALID_KEY = "The sealing key is not valid, or its signature does not match this device."
private const val MESSAGE_RETIRED = "That sealing key was replaced earlier and can not be used again."
private const val MESSAGE_NOT_ACTIVE = "This device is not enrolled."
