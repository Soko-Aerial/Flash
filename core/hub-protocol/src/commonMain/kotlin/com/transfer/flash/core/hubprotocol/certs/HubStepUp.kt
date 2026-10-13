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
}
