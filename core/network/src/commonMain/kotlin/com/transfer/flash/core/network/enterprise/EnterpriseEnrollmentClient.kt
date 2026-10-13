package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.rest.EnrollRequest
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.FlashCryptoEcdsaVerifier
import com.transfer.flash.core.security.enterprise.HubClientAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client for enterprise invitation token redemption and device certificate enrollment (CLI-01).
 *
 * The device refuses to store anything unless ALL of these hold, and stores nothing otherwise:
 * 1. the invite carries a root pin (a blank `root=` is refused: the device could not tell whose hub it trusts);
 * 2. the hub address is secure (`https`), or loopback, or the host opted in to cleartext for development
 *    ([allowCleartext]); see [HubUrlPolicy];
 * 3. the request carries a proof of possession of THIS device's key;
 * 4. the root key in the answer hashes to the pin; the certificate binds this device's id and key; the certificate
 *    chain verifies with real ECDSA and is inside its validity ([HubCredentialVerifier]);
 * 5. the credentials are written in ONE atomic store operation (the trust store throws if the write fails, in which
 *    case the device is still "not enrolled").
 *
 * The hub address stored in the credentials is the one the invite named; every later REST and relay call is checked
 * against that host ([HubUrlPolicy.sameHub]).
 */
@FlashInternalApi
public class EnterpriseEnrollmentClient(
    private val httpClient: HttpClient,
    private val trustStore: EnterpriseTrustStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Debug-build opt-in for cleartext hubs on a trusted network. Release builds keep the default `false`. */
    private val allowCleartext: () -> Boolean = { false },
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
) {

    /** Source-compatible constructor for callers that have no shared client yet; prefer passing one shared client. */
    public constructor(
        trustStore: EnterpriseTrustStore? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(createDefaultClient(), trustStore, ioDispatcher)

    /**
     * Redeems [invite] and returns the structured outcome. The credentials are stored (when a trust store is given)
     * only inside this call and only when every check passed.
     */
    public suspend fun enroll(
        invite: EnrollmentInvite,
        deviceId: String,
        friendlyName: String,
        crypto: FlashCrypto,
        nowMs: Long = 0L,
    ): EnrollmentOutcome = withContext(ioDispatcher) {
        val pinned = invite.rootFingerprint.trim().uppercase()
        if (pinned.isBlank()) return@withContext EnrollmentOutcome.Failed(EnrollFailureReason.INVITE_NOT_PINNED)

        when (HubUrlPolicy.check(invite.serverUrl, allowCleartext())) {
            HubUrlPolicy.Verdict.OK -> Unit
            HubUrlPolicy.Verdict.CLEARTEXT_REFUSED -> return@withContext EnrollmentOutcome.Failed(EnrollFailureReason.CLEARTEXT_REFUSED)
            HubUrlPolicy.Verdict.INVALID -> return@withContext EnrollmentOutcome.Failed(EnrollFailureReason.INVALID_ADDRESS)
        }

        val pubKeyBase64 = Base64.encode(crypto.identityPublicKeyEncoded)
        val request = EnrollRequest(
            token = invite.token,
            deviceId = deviceId,
            subjectPublicKey = pubKeyBase64,
            friendlyName = friendlyName.take(HubContract.MAX_NAME_LENGTH),
            proof = HubClientAuth.signEnrollProof(crypto, invite.token, deviceId, pubKeyBase64, pinned),
        )

        val reply = runSuspendCatching {
            val response = httpClient.post("${HubUrlPolicy.baseUrl(invite.serverUrl)}${HubContract.PATH_ENROLL}") {
                contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(request))
            }
            response.status to response.bodyAsText()
        }.getOrElse { return@withContext EnrollmentOutcome.Failed(EnrollFailureReason.UNREACHABLE, it::class.simpleName.orEmpty()) }

        val (status, body) = reply
        if (status != HttpStatusCode.OK) return@withContext EnrollmentOutcome.Failed(mapHttpFailure(status, body), "HTTP ${status.value}")

        val enrolled = HubRestCodec.decodeOrNull<EnrollResponse>(body)
            ?: return@withContext EnrollmentOutcome.Failed(EnrollFailureReason.UNEXPECTED_RESPONSE, "enroll body")

        val now = if (nowMs > 0L) nowMs else clock()
        when (
            val verdict = HubCredentialVerifier.verify(
                response = enrolled,
                hubUrl = HubUrlPolicy.baseUrl(invite.serverUrl),
                pinnedRootFingerprint = pinned,
                expectedRootPublicKey = null,
                expectedDeviceId = deviceId,
                expectedSubjectPublicKey = pubKeyBase64,
                verifier = FlashCryptoEcdsaVerifier(crypto),
                nowMs = now,
                enrolledAtMs = now,
            )
        ) {
            is HubCredentialVerifier.Verdict.Refused -> EnrollmentOutcome.Failed(verdict.reason, verdict.detail)
            is HubCredentialVerifier.Verdict.Accepted -> {
                val stored = runSuspendCatching { trustStore?.saveCredentials(verdict.credentials) }
                if (stored.isFailure) {
                    EnrollmentOutcome.Failed(EnrollFailureReason.STORAGE_FAILED, stored.exceptionOrNull()?.let { it::class.simpleName }.orEmpty())
                } else {
                    EnrollmentOutcome.Enrolled(verdict.credentials)
                }
            }
        }
    }

    /**
     * [enroll] with a [FlashResult] for callers that only need success or a sentence. The sentence is plain language,
     * never protocol text.
     */
    public suspend fun enrollDevice(
        invite: EnrollmentInvite,
        deviceId: String,
        friendlyName: String,
        crypto: FlashCrypto,
        nowMs: Long = 0L,
    ): FlashResult<EnrollmentCredentials> = when (val outcome = enroll(invite, deviceId, friendlyName, crypto, nowMs)) {
        is EnrollmentOutcome.Enrolled -> FlashResult.Success(outcome.credentials)
        is EnrollmentOutcome.Failed -> FlashResult.Failure(FlashError.Unknown("Enrollment failed: ${outcome.reason}"))
    }

    public companion object {
        /** One HTTP client for the hub's REST routes: timeouts so a dead hub cannot hang a tap for ever. */
        public fun createDefaultClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000L
                requestTimeoutMillis = 20_000L
                socketTimeoutMillis = 20_000L
            }
        }

        /** Maps an HTTP error answer of the enrolment route to a reason (see the contract table in `docs/protocol.md`). */
        internal fun mapHttpFailure(status: HttpStatusCode, body: String): EnrollFailureReason {
            val code = HubRestCodec.decodeOrNull<ErrorBody>(body)?.code
            return when (code) {
                HubErrorCodes.ERR_EXPIRED -> EnrollFailureReason.INVITE_EXPIRED
                HubErrorCodes.ERR_CONFLICT -> EnrollFailureReason.INVITE_USED
                HubErrorCodes.ERR_QUOTA_EXCEEDED -> EnrollFailureReason.QUOTA_FULL
                HubErrorCodes.ERR_RATE_LIMIT -> EnrollFailureReason.RATE_LIMITED
                HubErrorCodes.ERR_AUTH, HubErrorCodes.ERR_FORBIDDEN, HubErrorCodes.ERR_NOT_FOUND,
                HubErrorCodes.ERR_BAD_REQUEST, HubErrorCodes.ERR_REVOKED,
                -> EnrollFailureReason.INVITE_REJECTED
                HubErrorCodes.ERR_INTERNAL -> EnrollFailureReason.HUB_ERROR
                else -> when (status.value) {
                    409 -> EnrollFailureReason.INVITE_USED
                    410 -> EnrollFailureReason.INVITE_EXPIRED
                    429 -> EnrollFailureReason.RATE_LIMITED
                    in 500..599 -> EnrollFailureReason.HUB_ERROR
                    else -> EnrollFailureReason.INVITE_REJECTED
                }
            }
        }
    }
}
