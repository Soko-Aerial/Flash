@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.RenewalPolicy
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.OkResponse
import com.transfer.flash.core.hubprotocol.rest.RenewRequest
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.FlashCryptoEcdsaVerifier
import com.transfer.flash.core.security.enterprise.HubClientAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Supplies the bearer for authenticated REST calls; [forceRefresh] drops a token the hub just refused. */
@FlashInternalApi
public fun interface HubBearerSource {
    public suspend fun bearer(forceRefresh: Boolean): String?
}

/** Why the last attempt to authenticate against the hub failed. */
@FlashInternalApi
public enum class HubAuthFailure {
    /** The hub could not be reached. */
    UNREACHABLE,

    /** The hub that answered is not the pinned organisation's. */
    SERVER_MISMATCH,

    /** The hub says this certificate (or device) is revoked. */
    REVOKED,

    /** The hub says the certificate is expired or not valid yet: renewal is due. */
    CERT_EXPIRED,

    /** The hub refused the signature or the certificate for another reason. */
    REJECTED,

    /** The stored hub address is not acceptable (cleartext, or another host than the pinned one). */
    ADDRESS_REFUSED,
}

/** The outcome of telling the hub that this device unenrols. */
@FlashInternalApi
public enum class UnenrollOutcome {
    /** The hub removed the device (or already did not know it). */
    CONFIRMED,

    /** The hub could not be reached: the device is unenrolled locally and the hub is told later. */
    UNREACHABLE,

    /** The hub answered but refused (for example the certificate is already revoked or expired). */
    REFUSED,
}

/**
 * The device's authenticated REST session with its enrolled hub (CLI-01, CLI-09, SEC-14).
 *
 * - **Bearer** ([bearer]): `/auth/challenge` then a signed `/auth/verify` (the same signature form as the relay
 *   handshake, audience-bound to the pinned root fingerprint), cached until shortly before the hub's TTL, refreshed
 *   on a 401 ([forceRefresh]) and whenever the certificate serial changes. Nothing is sent when the hub's
 *   `serverId` is not the pinned one.
 * - **Renewal** ([renew]): `/enroll/renew` with a signed proof, the same acceptance rules as enrolment
 *   ([HubCredentialVerifier]) plus "same root key, same device key, new serial"; the new credentials replace the
 *   old ones in ONE atomic store write.
 * - **Unenrol** ([unenroll]) and **revocations** ([fetchRevocations]).
 *
 * Every request goes to the host stored in the credentials ([HubUrlPolicy] also refuses cleartext); the session
 * never takes a URL from a caller.
 */
@FlashInternalApi
public class HubSession(
    private val httpClient: HttpClient,
    private val credentials: () -> EnrollmentCredentials?,
    private val crypto: FlashCrypto,
    private val trustStore: EnterpriseTrustStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
    private val allowCleartext: () -> Boolean = { false },
) : HubBearerSource {

    private class Cached(val token: String, val expiresAtMs: Long, val serial: Long, val hubUrl: String)

    private val mutex = Mutex()
    private var cached: Cached? = null

    private val _authFailure = MutableStateFlow<HubAuthFailure?>(null)

    /** The failure of the latest authentication attempt; null after a success. */
    public val authFailure: StateFlow<HubAuthFailure?> = _authFailure.asStateFlow()

    private val _renewalFailure = MutableStateFlow<EnrollFailureReason?>(null)

    /** Why the last automatic renewal failed (null when none is pending or the last one worked). */
    public val renewalFailure: StateFlow<EnrollFailureReason?> = _renewalFailure.asStateFlow()

    /** Forgets the cached token (after unenrol, revoke or a changed identity). */
    public fun invalidate() {
        cached = null
    }

    override suspend fun bearer(forceRefresh: Boolean): String? = withContext(ioDispatcher) {
        val creds = credentials() ?: return@withContext null
        mutex.withLock {
            val now = clock()
            val current = cached
            if (!forceRefresh && current != null && current.serial == creds.deviceCert.serial &&
                current.hubUrl == creds.hubUrl && now < current.expiresAtMs - EXPIRY_MARGIN_MS
            ) {
                return@withLock current.token
            }
            cached = null
            authenticate(creds)?.also { cached = it }?.token
        }
    }

    private suspend fun authenticate(creds: EnrollmentCredentials): Cached? {
        val base = baseUrlFor(creds) ?: run { _authFailure.value = HubAuthFailure.ADDRESS_REFUSED; return null }
        val challenge = fetchChallenge(base, creds) ?: return null

        val clientNonce = HubClientAuth.newNonce()
        val request = AuthVerifyRequest(
            serverNonce = challenge.serverNonce,
            clientNonce = clientNonce,
            deviceId = creds.deviceId,
            deviceCert = creds.deviceCert,
            delegationChain = creds.delegationChain,
            signature = HubClientAuth.signAuth(
                crypto, challenge.serverId, challenge.serverNonce, clientNonce, creds.deviceId, creds.deviceCert.serial,
            ),
        )
        val reply = runSuspendCatching {
            val response = httpClient.post("$base${HubContract.PATH_AUTH_VERIFY}") {
                contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(request))
            }
            response.status to response.bodyAsText()
        }.getOrElse {
            _authFailure.value = HubAuthFailure.UNREACHABLE
            return null
        }
        if (reply.first != HttpStatusCode.OK) {
            _authFailure.value = failureFor(reply.first, reply.second)
            return null
        }
        val verified = HubRestCodec.decodeOrNull<AuthVerifyResponse>(reply.second)
        if (verified == null || verified.token.isBlank() || verified.principal.deviceId != creds.deviceId) {
            _authFailure.value = HubAuthFailure.REJECTED
            return null
        }
        _authFailure.value = null
        // The hub's `expiresAt` is on the hub's clock: count the TTL from now on THIS device's clock instead.
        val ttlMs = (verified.ttlSeconds.coerceIn(1L, HubContract.BEARER_TTL_SECONDS * 4)) * 1000L
        return Cached(verified.token, clock() + ttlMs, creds.deviceCert.serial, creds.hubUrl)
    }

    /** Fetches a challenge and refuses a hub whose `serverId` is not the pinned one. Sets [authFailure] on failure. */
    private suspend fun fetchChallenge(base: String, creds: EnrollmentCredentials): AuthChallengeResponse? {
        val reply = runSuspendCatching {
            val response = httpClient.get("$base${HubContract.PATH_AUTH_CHALLENGE}")
            response.status to response.bodyAsText()
        }.getOrElse {
            _authFailure.value = HubAuthFailure.UNREACHABLE
            return null
        }
        if (reply.first != HttpStatusCode.OK) {
            _authFailure.value = failureFor(reply.first, reply.second)
            return null
        }
        val challenge = HubRestCodec.decodeOrNull<AuthChallengeResponse>(reply.second)
        if (challenge == null) {
            _authFailure.value = HubAuthFailure.REJECTED
            return null
        }
        if (!challenge.serverId.equals(creds.rootFingerprint, ignoreCase = true)) {
            _authFailure.value = HubAuthFailure.SERVER_MISMATCH
            return null
        }
        return challenge
    }

    /**
     * Renews the certificate when it is inside the renewal window. Returns the new credentials (already stored) or
     * the reason it did not work. A failure is also kept in [renewalFailure] for the status line.
     */
    public suspend fun renew(): EnrollmentOutcome = withContext(ioDispatcher) {
        val creds = credentials() ?: return@withContext EnrollmentOutcome.Failed(EnrollFailureReason.INVITE_REJECTED, "not enrolled")
        val outcome = mutex.withLock { renewLocked(creds) }
        _renewalFailure.value = (outcome as? EnrollmentOutcome.Failed)?.reason
        if (outcome is EnrollmentOutcome.Enrolled) cached = null
        outcome
    }

    private suspend fun renewLocked(creds: EnrollmentCredentials): EnrollmentOutcome {
        val now = clock()
        if (!RenewalPolicy.isRenewable(creds.deviceCert, now)) {
            // Before the window: nothing to do. After the grace: only a new invite helps.
            return EnrollmentOutcome.Failed(
                if (now > creds.deviceCert.validTo) EnrollFailureReason.CERTIFICATE_INVALID else EnrollFailureReason.INVITE_REJECTED,
                "outside renewal window",
            )
        }
        val base = baseUrlFor(creds) ?: return EnrollmentOutcome.Failed(EnrollFailureReason.CLEARTEXT_REFUSED)
        val challenge = fetchChallenge(base, creds) ?: return EnrollmentOutcome.Failed(
            if (_authFailure.value == HubAuthFailure.SERVER_MISMATCH) EnrollFailureReason.WRONG_ORGANISATION else EnrollFailureReason.UNREACHABLE,
        )
        val subjectKey = creds.deviceCert.subjectPublicKey
        if (subjectKey != Base64.encode(crypto.identityPublicKeyEncoded)) {
            return EnrollmentOutcome.Failed(EnrollFailureReason.WRONG_DEVICE, "this install holds another key")
        }
        val clientNonce = HubClientAuth.newNonce()
        val request = RenewRequest(
            serverNonce = challenge.serverNonce,
            clientNonce = clientNonce,
            deviceId = creds.deviceId,
            deviceCert = creds.deviceCert,
            delegationChain = creds.delegationChain,
            signature = HubClientAuth.signRenew(
                crypto, challenge.serverId, challenge.serverNonce, clientNonce, creds.deviceId, creds.deviceCert.serial, subjectKey,
            ),
        )
        val reply = runSuspendCatching {
            val response = httpClient.post("$base${HubContract.PATH_ENROLL_RENEW}") {
                contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(request))
            }
            response.status to response.bodyAsText()
        }.getOrElse { return EnrollmentOutcome.Failed(EnrollFailureReason.UNREACHABLE, it::class.simpleName.orEmpty()) }
        if (reply.first != HttpStatusCode.OK) {
            return EnrollmentOutcome.Failed(EnterpriseEnrollmentClient.mapHttpFailure(reply.first, reply.second), "HTTP ${reply.first.value}")
        }
        val renewed = HubRestCodec.decodeOrNull<EnrollResponse>(reply.second)
            ?: return EnrollmentOutcome.Failed(EnrollFailureReason.UNEXPECTED_RESPONSE, "renew body")
        if (renewed.status != EnrollResponse.STATUS_RENEWED) {
            return EnrollmentOutcome.Failed(EnrollFailureReason.UNEXPECTED_RESPONSE, "status ${renewed.status}")
        }
        val verdict = HubCredentialVerifier.verify(
            response = renewed,
            hubUrl = creds.hubUrl,
            pinnedRootFingerprint = creds.rootFingerprint,
            expectedRootPublicKey = creds.rootPublicKey,
            expectedDeviceId = creds.deviceId,
            expectedSubjectPublicKey = subjectKey,
            verifier = FlashCryptoEcdsaVerifier(crypto),
            nowMs = now,
            enrolledAtMs = creds.enrolledAtMs,
        )
        return when (verdict) {
            is HubCredentialVerifier.Verdict.Refused -> EnrollmentOutcome.Failed(verdict.reason, verdict.detail)
            is HubCredentialVerifier.Verdict.Accepted -> {
                val fresh = verdict.credentials.copy(hubName = verdict.credentials.hubName.ifBlank { creds.hubName })
                when {
                    fresh.deviceCert.serial == creds.deviceCert.serial ->
                        EnrollmentOutcome.Failed(EnrollFailureReason.UNEXPECTED_RESPONSE, "serial did not change")
                    fresh.deviceCert.validTo <= creds.deviceCert.validTo ->
                        EnrollmentOutcome.Failed(EnrollFailureReason.UNEXPECTED_RESPONSE, "validity did not advance")
                    else -> {
                        val stored = runSuspendCatching { trustStore?.saveCredentials(fresh) }
                        if (stored.isFailure) EnrollmentOutcome.Failed(EnrollFailureReason.STORAGE_FAILED) else EnrollmentOutcome.Enrolled(fresh)
                    }
                }
            }
        }
    }

    /**
     * Tells the hub this device unenrols (`POST /api/v1/device/unenroll`, bearer). [forCredentials] lets the caller
     * retry a pending unenrol with the credentials it kept, after they were cleared from the live state.
     */
    public suspend fun unenroll(forCredentials: EnrollmentCredentials? = null): UnenrollOutcome = withContext(ioDispatcher) {
        val creds = forCredentials ?: credentials() ?: return@withContext UnenrollOutcome.CONFIRMED
        val base = baseUrlFor(creds) ?: return@withContext UnenrollOutcome.REFUSED
        // A pending unenrol uses its own credentials, so do not reuse the live token cache for it.
        val own = if (forCredentials != null && forCredentials != credentials()) {
            HubSession(httpClient, { forCredentials }, crypto, null, ioDispatcher, clock, allowCleartext)
        } else {
            this@HubSession
        }
        var forceRefresh = false
        repeat(2) {
            val token = own.bearer(forceRefresh)
                ?: return@withContext when (own.authFailure.value) {
                    HubAuthFailure.UNREACHABLE -> UnenrollOutcome.UNREACHABLE
                    // A revoked or expired certificate cannot authenticate: the hub no longer lists this device as a member.
                    HubAuthFailure.REVOKED -> UnenrollOutcome.CONFIRMED
                    else -> UnenrollOutcome.REFUSED
                }
            val reply = runSuspendCatching {
                val response = httpClient.post("$base${HubContract.PATH_DEVICE_UNENROLL}") {
                    header(HubContract.HEADER_AUTHORIZATION, HubContract.BEARER_PREFIX + token)
                }
                response.status to response.bodyAsText()
            }.getOrElse { return@withContext UnenrollOutcome.UNREACHABLE }
            when {
                reply.first == HttpStatusCode.OK -> {
                    val ok = HubRestCodec.decodeOrNull<OkResponse>(reply.second)
                    return@withContext if (ok == null || ok.ok) UnenrollOutcome.CONFIRMED else UnenrollOutcome.REFUSED
                }
                reply.first == HttpStatusCode.Unauthorized && !forceRefresh -> forceRefresh = true
                reply.first == HttpStatusCode.NotFound -> return@withContext UnenrollOutcome.CONFIRMED
                reply.first.value in 500..599 -> return@withContext UnenrollOutcome.UNREACHABLE
                else -> return@withContext UnenrollOutcome.REFUSED
            }
        }
        UnenrollOutcome.REFUSED
    }

    /**
     * Fetches the hub's signed revocation list and returns it only when it is signed by this organisation's root key.
     * Used to CONFIRM a `FLASH_REVOKED` frame before the device wipes its credentials.
     */
    public suspend fun fetchRevocations(): RevocationList? = withContext(ioDispatcher) {
        val creds = credentials() ?: return@withContext null
        val base = baseUrlFor(creds) ?: return@withContext null
        val reply = runSuspendCatching {
            val response = httpClient.get("$base${HubContract.PATH_REVOCATIONS}")
            response.status to response.bodyAsText()
        }.getOrNull() ?: return@withContext null
        if (reply.first != HttpStatusCode.OK) return@withContext null
        val list = HubRestCodec.decodeOrNull<RevocationList>(reply.second) ?: return@withContext null
        // The hub signs with its DELEGATE key (the root key is offline), so the delegation certificate that vouches for
        // that key, from the chain stored at enrolment, must be offered to the trust check (ADR-124 contract test).
        val delegation = creds.delegationChain.firstOrNull { it.delegatePublicKey == list.issuerPublicKey }
        list.takeIf { it.verifyTrusted(creds.rootPublicKey, FlashCryptoEcdsaVerifier(crypto), clock(), delegation) }
    }

    private fun baseUrlFor(creds: EnrollmentCredentials): String? =
        if (HubUrlPolicy.check(creds.hubUrl, allowCleartext()) == HubUrlPolicy.Verdict.OK) HubUrlPolicy.baseUrl(creds.hubUrl) else null

    private fun failureFor(status: HttpStatusCode, body: String): HubAuthFailure {
        return when (HubRestCodec.decodeOrNull<ErrorBody>(body)?.code) {
            HubErrorCodes.ERR_REVOKED -> HubAuthFailure.REVOKED
            HubErrorCodes.ERR_EXPIRED -> HubAuthFailure.CERT_EXPIRED
            HubErrorCodes.ERR_AUTH, HubErrorCodes.ERR_FORBIDDEN -> HubAuthFailure.REJECTED
            else -> if (status.value in 500..599 || status.value == 429) HubAuthFailure.UNREACHABLE else HubAuthFailure.REJECTED
        }
    }

    private companion object {
        /** A token is renewed this long before the hub's TTL ends, so a request never races the expiry. */
        const val EXPIRY_MARGIN_MS: Long = 30_000L
    }
}
