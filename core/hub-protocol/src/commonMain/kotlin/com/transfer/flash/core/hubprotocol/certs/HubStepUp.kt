package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * The byte string an organisation administrator signs, on their enrolled device, to authorise one dangerous console
 * action ("step-up", SEC-12). Bound to the challenge id, the action, the admin and the action's target, plus the
 * server-chosen nonce, under its own domain tag, so a signature for one action can never authorise another.
 */
@FlashInternalApi
public object HubStepUp {
    public const val STEP_UP_TAG: String = "flash-hub-stepup-v1"

    /** The phone co-signature (ADR-131, FO-14) has its own domain, so a browser proof can never be replayed as an approval. */
    public const val APPROVAL_TAG: String = "flash-hub-approval-v1"
    private const val APPROVAL_CODE_TAG: String = "flash-hub-approval-code-v1"

    public fun proofBytes(
        challengeId: String,
        action: String,
        admin: String,
        target: String,
        nonce: String,
    ): ByteArray = AuthCanonicalWriter(STEP_UP_TAG)
        .text(challengeId)
        .text(action)
        .text(admin)
        .text(target)
        .text(nonce)
        .build()

    /** What the administrator's phone signs to approve the same challenge: same fields, [APPROVAL_TAG] as the domain. */
    public fun approvalBytes(
        challengeId: String,
        action: String,
        admin: String,
        target: String,
        nonce: String,
    ): ByteArray = AuthCanonicalWriter(APPROVAL_TAG)
        .text(challengeId)
        .text(action)
        .text(admin)
        .text(target)
        .text(nonce)
        .build()

    /**
     * Six digits both screens show for one challenge, so the administrator can see that the request on the phone is the one on the
     * console (a request raised by someone else would show another code). Derived from the challenge id and the server nonce.
     */
    public fun approvalCode(challengeId: String, nonce: String): String {
        val digest = Sha256.digest(AuthCanonicalWriter(APPROVAL_CODE_TAG).text(challengeId).text(nonce).build())
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return (value % 1_000_000L).toString().padStart(6, '0')
    }
}
