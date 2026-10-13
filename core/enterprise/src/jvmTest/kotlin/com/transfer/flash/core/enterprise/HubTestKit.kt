@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubScopes
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.OkResponse
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.HubClientAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/** A real P-256 ECDSA [FlashCrypto] for tests (the ECDH half is not needed by the hub code). */
class TestCrypto : FlashCrypto {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val identityPublicKeyEncoded: ByteArray get() = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign()

    override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
        Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(data) }.verify(signature)
    } catch (_: Throwable) {
        false
    }

    override fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair = throw UnsupportedOperationException()
    override fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray): ByteArray = throw UnsupportedOperationException()
}

/** An organisation: a root key, a hub delegation signed by it, and a way to issue device certificates. */
class TestOrg(val rootCrypto: TestCrypto = TestCrypto(), val hubCrypto: TestCrypto = TestCrypto()) {
    val rootKey: String = Base64.encode(rootCrypto.identityPublicKeyEncoded)
    val hubKey: String = Base64.encode(hubCrypto.identityPublicKeyEncoded)
    val fingerprint: String = HubClientAuth.rootFingerprintHex(rootKey)

    fun delegation(validFrom: Long = T0 - DAY, validTo: Long = T0 + 400 * DAY, serial: Long = 1L): DelegationCert {
        val unsigned = DelegationCert(rootKey, hubKey, setOf(HubScopes.ENROL_MEMBER, HubScopes.REVOKE_MEMBER), validFrom, validTo, 100, serial, "")
        return unsigned.copy(signature = Base64.encode(rootCrypto.sign(unsigned.canonicalBytes())))
    }

    fun deviceCert(
        deviceId: String,
        subjectKey: String,
        serial: Long = 10L,
        validFrom: Long = T0 - HOUR,
        validTo: Long = T0 + 30 * DAY,
        role: String = "member",
        signer: TestCrypto = hubCrypto,
        issuerKey: String = hubKey,
    ): DeviceCert {
        val unsigned = DeviceCert(deviceId, subjectKey, role, "dept-1", validFrom, validTo, serial, issuerKey, "")
        return unsigned.copy(issuerSignature = Base64.encode(signer.sign(unsigned.canonicalBytes())))
    }

    fun response(
        status: String = EnrollResponse.STATUS_ENROLLED,
        cert: DeviceCert,
        chain: List<DelegationCert> = listOf(delegation()),
        rootPublicKey: String = rootKey,
        serverId: String = fingerprint,
        hubName: String? = "Acme Hub",
    ): EnrollResponse = EnrollResponse(
        status = status, serverId = serverId, rootFingerprint = fingerprint, rootPublicKey = rootPublicKey,
        deviceCert = cert, delegationChain = chain, hubName = hubName,
    )

    fun revocations(serials: Set<Long> = emptySet(), ids: Set<String> = emptySet(), version: Long = 1L): RevocationList {
        val unsigned = RevocationList(version, serials, ids, emptySet(), rootKey, "")
        return unsigned.copy(signature = Base64.encode(rootCrypto.sign(unsigned.canonicalBytes())))
    }

    companion object {
        const val T0: Long = 1_800_000_000_000L
        const val HOUR: Long = 3_600_000L
        const val DAY: Long = 86_400_000L
    }
}

/** Credentials for [deviceCrypto] issued by the org, with a NON-EMPTY delegation chain (CLI-02). */
fun TestOrg.credentialsFor(
    deviceId: String,
    deviceCrypto: TestCrypto,
    hubUrl: String = "https://hub.example.test",
    serial: Long = 10L,
    validTo: Long = TestOrg.T0 + 30 * TestOrg.DAY,
): EnrollmentCredentials {
    val cert = deviceCert(deviceId, Base64.encode(deviceCrypto.identityPublicKeyEncoded), serial = serial, validTo = validTo)
    return EnrollmentCredentials(
        hubUrl = hubUrl, rootFingerprint = fingerprint, rootPublicKey = rootKey, deviceCert = cert,
        delegationChain = listOf(delegation()), enrolledAtMs = TestOrg.T0, hubName = "Acme Hub",
    )
}

class RecordedRequest(val method: String, val path: String, val authorization: String?, val body: String)

/**
 * A scripted hub for the REST routes. Routes are matched on `METHOD path`; [online] = false makes every call fail like
 * a dead network. Every request is recorded.
 */
class FakeHub {
    val requests = mutableListOf<RecordedRequest>()

    @Volatile
    var online: Boolean = true
    val routes = mutableMapOf<String, (RecordedRequest) -> Pair<HttpStatusCode, String>>()

    fun on(method: String, path: String, handler: (RecordedRequest) -> Pair<HttpStatusCode, String>) {
        routes["$method $path"] = handler
    }

    fun respond(method: String, path: String, status: HttpStatusCode, body: String) = on(method, path) { status to body }

    fun count(path: String): Int = requests.count { it.path == path }

    fun client(): HttpClient = HttpClient(Engine(this))

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class Engine(val hub: FakeHub) : HttpClientEngineBase("fake-hub") {
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()
        override val dispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            if (!hub.online) throw IOException("network is down")
            val text = (data.body as? TextContent)?.text.orEmpty()
            val request = RecordedRequest(data.method.value, data.url.encodedPath, data.headers["Authorization"], text)
            hub.requests += request
            val handler = hub.routes["${request.method} ${request.path}"]
            val (status, body) = handler?.invoke(request) ?: (HttpStatusCode.NotFound to HubRestCodec.encode(ErrorBody(HubErrorCodes.ERR_NOT_FOUND)))
            return HttpResponseData(
                status, GMTDate(), Headers.Empty, HttpProtocolVersion.HTTP_1_1, ByteReadChannel(body.toByteArray()), callContext(),
            )
        }
    }

    companion object {
        fun challenge(serverId: String, nonce: String = "sn-1"): String = HubRestCodec.encode(AuthChallengeResponse(serverId, nonce, TestOrg.T0))

        fun verified(deviceId: String, token: String, ttl: Long = 600L): String = HubRestCodec.encode(
            AuthVerifyResponse(token = token, expiresAt = TestOrg.T0 + ttl * 1000L, ttlSeconds = ttl, principal = HubPrincipal(deviceId, "member")),
        )

        fun error(code: String): String = HubRestCodec.encode(ErrorBody(code))
        fun ok(): String = HubRestCodec.encode(OkResponse(true))
        const val CHALLENGE: String = HubContract.PATH_AUTH_CHALLENGE
        const val VERIFY: String = HubContract.PATH_AUTH_VERIFY
    }
}
