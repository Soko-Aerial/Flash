@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.server.auth.AuthRejected
import com.transfer.flash.server.auth.ChallengeResponseAuth
import io.ktor.server.application.ApplicationCall

/** Outcome of a relay handshake attempt. */
sealed interface AuthResult {
    /** The device proved who it is; [principal] is what the hub stamps on everything it sends. */
    data class Accepted(
        val principal: HubPrincipal,
        /**
         * When the credential that proved the identity stops being valid (the earlier of the device certificate and its delegation,
         * epoch ms), or null when unknown (test authenticators). The relay closes the session then (REV-05).
         */
        val validUntilMs: Long? = null,
    ) : AuthResult

    /**
     * The device is refused. [code] is a [HubErrorCodes] value sent to the client; [logDetail] stays in the server log
     * (never on the wire: SEC-16).
     */
    data class Rejected(val code: String, val logDetail: String) : AuthResult
}

/**
 * Seam: decides whether a relay `FLASH_HUB_HELLO` is acceptable (ADR-109). Production uses
 * [CertChainRelayAuthenticator]; tests may substitute their own to exercise the router without cryptography.
 */
fun interface RelayAuthenticator {
    /**
     * @param challenge the `FLASH_HUB_CHALLENGE` this connection was sent (its nonce is single-use by construction:
     *   one socket, one challenge).
     * @param hello the client's answer.
     * @param nowMs server time used for every validity-window check.
     */
    suspend fun authenticate(challenge: RelayFrame.Challenge, hello: RelayFrame.Hello, nowMs: Long): AuthResult
}

/**
 * Production [RelayAuthenticator]: verifies the certificate chain against the pinned root, revocation, the closed role
 * set and the possession signature, exactly as the REST `/auth/verify` does ([ChallengeResponseAuth.verifyIdentity]).
 */
class CertChainRelayAuthenticator(private val auth: ChallengeResponseAuth) : RelayAuthenticator {
    override suspend fun authenticate(
        challenge: RelayFrame.Challenge,
        hello: RelayFrame.Hello,
        nowMs: Long,
    ): AuthResult {
        if (hello.delegationChain.size > MAX_CHAIN_LENGTH) {
            return AuthResult.Rejected(HubErrorCodes.ERR_AUTH, "delegation chain too long (${hello.delegationChain.size})")
        }
        val result = auth.verifyIdentity(
            serverNonce = challenge.serverNonce,
            clientNonce = hello.clientNonce,
            deviceId = hello.deviceId,
            signature = hello.signature,
            deviceCert = hello.deviceCert,
            delegationCert = hello.delegationChain.firstOrNull(),
            currentTimeMs = nowMs,
        )
        return result.fold(
            onSuccess = {
                AuthResult.Accepted(
                    it.toHubPrincipal(),
                    validUntilMs = minOf(hello.deviceCert.validTo, hello.delegationChain.firstOrNull()?.validTo ?: Long.MAX_VALUE),
                )
            },
            onFailure = {
                val code = (it as? AuthRejected)?.code ?: HubErrorCodes.ERR_AUTH
                AuthResult.Rejected(code, it.message ?: "authentication failed")
            },
        )
    }

    private companion object {
        /** Root -> delegate -> leaf is the only shape this version issues; a longer chain is never valid. */
        const val MAX_CHAIN_LENGTH = 1
    }
}

/**
 * Seam: resolves the authenticated principal of a REST call from its bearer token (ADR-109: `/auth/verify` issues a
 * 15 minute opaque token). Null means "no valid credential".
 *
 * PHASE-2: SEC-03 installs the real bearer store behind this and makes every directory/admin route require it. Until
 * then the directory routes accept an optional resolver and otherwise fall back to the legacy `X-Device-Id` header.
 */
fun interface HubPrincipalResolver {
    suspend fun resolve(call: ApplicationCall): HubPrincipal?
}
