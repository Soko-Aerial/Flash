package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.ChainStatus
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.HubClientAuth

/**
 * Every check a certificate answer from a hub must pass BEFORE the device stores or uses it (CLI-01). The same rule
 * set runs for the first enrolment and for every renewal, so a renewal cannot be used to swap the organisation's root
 * or the device's key.
 *
 * Pure (no I/O): the clock, the verifier and the expected identity are arguments, which is what makes each rule
 * testable on its own.
 */
@FlashInternalApi
public object HubCredentialVerifier {

    /** The verdict of [verify]: either the credentials to store, or why the response is refused. */
    public sealed interface Verdict {
        public data class Accepted(val credentials: EnrollmentCredentials) : Verdict
        public data class Refused(val reason: EnrollFailureReason, val detail: String) : Verdict
    }

    /**
     * @param pinnedRootFingerprint upper-case hex SHA-256 of the organisation's root key: the invite's `root=` for an
     *   enrolment, the stored credentials' pin for a renewal. Must not be blank.
     * @param expectedRootPublicKey for a renewal, the root key already stored: the response must carry exactly it.
     * @param expectedDeviceId this device's id; the certificate must be for it.
     * @param expectedSubjectPublicKey Base64 SPKI of this device's key; the certificate must bind exactly it.
     * @param hubUrl the hub address the response came from; stored in the credentials (this is the pinned host).
     */
    public fun verify(
        response: EnrollResponse,
        hubUrl: String,
        pinnedRootFingerprint: String,
        expectedRootPublicKey: String?,
        expectedDeviceId: String,
        expectedSubjectPublicKey: String,
        verifier: EcdsaVerifier,
        nowMs: Long,
        enrolledAtMs: Long,
    ): Verdict {
        val pinned = pinnedRootFingerprint.trim().uppercase()
        if (pinned.isBlank()) return Verdict.Refused(EnrollFailureReason.INVITE_NOT_PINNED, "no root pin")

        val deviceCert = response.deviceCert
        val delegation = response.delegationChain.firstOrNull()
        val rootPublicKey = response.rootPublicKey.ifBlank { delegation?.rootPublicKey ?: deviceCert.issuerPublicKey }

        // 1. The root key the hub presents must be the one the invite (or the earlier enrolment) pinned.
        val presented = HubClientAuth.rootFingerprintHex(rootPublicKey)
        if (presented != pinned) return Verdict.Refused(EnrollFailureReason.WRONG_ORGANISATION, "root fingerprint")
        if (expectedRootPublicKey != null && expectedRootPublicKey != rootPublicKey) {
            return Verdict.Refused(EnrollFailureReason.WRONG_ORGANISATION, "root key changed")
        }
        // The hub's own statements about its identity are not trusted, but a contradiction is still a red flag.
        if (response.serverId.isNotBlank() && !response.serverId.equals(pinned, ignoreCase = true)) {
            return Verdict.Refused(EnrollFailureReason.WRONG_ORGANISATION, "serverId")
        }

        // 2. The certificate is for THIS device and THIS key.
        if (deviceCert.deviceId != expectedDeviceId) return Verdict.Refused(EnrollFailureReason.WRONG_DEVICE, "deviceId")
        if (deviceCert.subjectPublicKey != expectedSubjectPublicKey) return Verdict.Refused(EnrollFailureReason.WRONG_DEVICE, "subjectPublicKey")

        // 3. A real chain verification (signatures, scope, validity window with the standard clock skew).
        val chain = deviceCert.checkChain(delegation, rootPublicKey, verifier, nowMs)
        if (chain != ChainStatus.OK) {
            return Verdict.Refused(EnrollFailureReason.CERTIFICATE_INVALID, chain.name)
        }

        return Verdict.Accepted(
            EnrollmentCredentials(
                hubUrl = hubUrl,
                rootFingerprint = pinned,
                rootPublicKey = rootPublicKey,
                deviceCert = deviceCert,
                delegationChain = response.delegationChain,
                enrolledAtMs = enrolledAtMs,
                hubName = response.hubName.orEmpty().take(MAX_HUB_NAME),
            ),
        )
    }

    private const val MAX_HUB_NAME: Int = 80
}
