package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
 * Client for enterprise invitation token redemption and device certificate enrollment.
 */
@FlashInternalApi
public class EnterpriseEnrollmentClient(
    private val httpClient: HttpClient = createDefaultClient(),
    private val trustStore: EnterpriseTrustStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Redeems a `flash://enroll` invitation, exchanges the single-use token for a signed
     * device certificate, verifies the root fingerprint pin, and stores the credentials.
     */
    public suspend fun enrollDevice(
        invite: EnrollmentInvite,
        deviceId: String,
        friendlyName: String,
        crypto: FlashCrypto,
        nowMs: Long = 0L,
    ): FlashResult<EnrollmentCredentials> = withContext(ioDispatcher) {
        return@withContext try {
            val pubKeyBase64 = Base64.encode(crypto.identityPublicKeyEncoded)
            val requestJson = buildString {
                append("{")
                append(""""token":"${EnterpriseJsonHelper.escape(invite.token)}",""")
                append(""""deviceId":"${EnterpriseJsonHelper.escape(deviceId)}",""")
                append(""""subjectPublicKey":"${EnterpriseJsonHelper.escape(pubKeyBase64)}",""")
                append(""""friendlyName":"${EnterpriseJsonHelper.escape(friendlyName)}"""")
                append("}")
            }

            val enrollUrl = "${invite.serverUrl}/api/v1/enroll"
            val response = httpClient.post(enrollUrl) {
                contentType(ContentType.Application.Json)
                setBody(requestJson)
            }

            val body = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                val errorMsg = EnterpriseJsonHelper.extractField(body, "error") ?: "HTTP ${response.status}"
                return@withContext FlashResult.Failure(FlashError.Unknown("Enrollment failed: $errorMsg"))
            }

            val rootFingerprint = EnterpriseJsonHelper.extractField(body, "rootFingerprint").orEmpty().uppercase()
            if (invite.rootFingerprint.isNotBlank() && rootFingerprint != invite.rootFingerprint) {
                return@withContext FlashResult.Failure(
                    FlashError.Unknown("Root fingerprint mismatch! Expected '${invite.rootFingerprint}', but server presented '$rootFingerprint'")
                )
            }

            val deviceCert = EnterpriseJsonHelper.parseDeviceCert(body)
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Malformed device certificate returned by hub"))

            val delegationChain = EnterpriseJsonHelper.parseDelegationChain(body)
            val rootPublicKey = delegationChain.firstOrNull()?.rootPublicKey ?: deviceCert.issuerPublicKey

            val credentials = EnrollmentCredentials(
                hubUrl = invite.serverUrl,
                rootFingerprint = rootFingerprint,
                rootPublicKey = rootPublicKey,
                deviceCert = deviceCert,
                delegationChain = delegationChain,
                enrolledAtMs = if (nowMs > 0L) nowMs else deviceCert.validFrom,
            )

            trustStore?.saveCredentials(credentials)

            FlashResult.Success(credentials)
        } catch (error: Throwable) {
            FlashResult.Failure(FlashError.Unknown(error.message ?: "Enrollment error", error))
        }
    }

    public companion object {
        public fun createDefaultClient(): HttpClient = HttpClient(CIO)
    }
}
