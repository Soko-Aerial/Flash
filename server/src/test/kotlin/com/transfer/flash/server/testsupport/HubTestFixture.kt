@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.testsupport

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.enterprise.relay.RelayIdentity
import com.transfer.flash.core.enterprise.relay.RelaySigner
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.ChallengeResponseAuth
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.InviteScope
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.relay.AuthResult
import com.transfer.flash.server.relay.HubPrincipalResolver
import com.transfer.flash.server.relay.RelayAuthenticator
import io.ktor.server.application.ApplicationCall
import java.security.KeyPair
import java.util.Base64

/**
 * One organisation realm for tests: a root key, the hub's delegated key and delegation, and the services built on them.
 * Devices are enrolled through the real [EnrollmentService] so a test exercises the same certificates production issues.
 */
class HubTestFixture(
    val now: Long = System.currentTimeMillis(),
    val rootKey: KeyPair = FlashCrypto.generateEcKeyPair(),
    val serverKey: KeyPair = FlashCrypto.generateEcKeyPair(),
) {
    val rootPublicKey: String = rootKey.toBase64Spki()

    val delegation: DelegationCert = DelegationCert.issue(
        rootPrivateKey = rootKey.private,
        rootPublicKey = rootPublicKey,
        delegatePublicKey = serverKey.toBase64Spki(),
        scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER, DelegationCert.SCOPE_REVOKE_MEMBER),
        validFrom = now - 60_000,
        validTo = now + 24L * 3600 * 1000,
        maxDevices = 100,
        serial = 1L,
    )

    /** The (already trusted) revocation list the hub consults; tests assign it to revoke a device. */
    @Volatile
    var revocationList: com.transfer.flash.core.hubprotocol.certs.RevocationList? = null

    val auth = ChallengeResponseAuth(rootPublicKey, revocationListProvider = { revocationList })
    val enrollment = EnrollmentService(rootPublicKey, serverKey.private, delegation)
    val serverId: String get() = auth.serverId

    /** A device enrolled through the real service. */
    class Device(val deviceId: String, val key: KeyPair, val cert: DeviceCert, val chain: List<DelegationCert>)

    fun enrol(deviceId: String): Device {
        val key = FlashCrypto.generateEcKeyPair()
        val token = enrollment.generateInviteLink("https://hub.test", InviteScope(department = "eng", expiry = now + 60_000), now)
            .substringAfter("token=").substringBefore("&")
        val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), serverId))
        val result = enrollment.enrollDevice(token, deviceId, key.toBase64Spki(), proof, currentTimeMs = now).getOrThrow()
        return Device(deviceId, key, result.deviceCert, result.delegationChain)
    }

    /** A certificate with an arbitrary window, signed by the hub's delegated key (for expiry cases). */
    fun issueWithWindow(deviceId: String, key: KeyPair, validFrom: Long, validTo: Long, serial: Long = 77L): DeviceCert =
        DeviceCert.issue(
            issuerPrivateKey = serverKey.private,
            issuerPublicKey = serverKey.toBase64Spki(),
            deviceId = deviceId,
            subjectPublicKey = key.toBase64Spki(),
            role = DeviceCert.ROLE_MEMBER,
            department = "eng",
            validFrom = validFrom,
            validTo = validTo,
            serial = serial,
        )

    /** The client-side identity the real transport signs with. [signingKey] defaults to the device's own key. */
    fun identity(
        device: Device,
        pinnedServerId: String = serverId,
        signingKey: KeyPair = device.key,
        cert: DeviceCert = device.cert,
        chain: List<DelegationCert> = device.chain,
    ): RelayIdentity = RelayIdentity(
        deviceId = device.deviceId,
        friendlyName = "Test ${device.deviceId}",
        deviceCert = cert,
        delegationChain = chain,
        expectedServerId = pinnedServerId,
        signer = RelaySigner { data -> Base64.getDecoder().decode(FlashCrypto.sign(signingKey.private, data)) },
    )
}

/**
 * TEST [RelayAuthenticator]: accepts exactly the listed device ids with a fixed role, no cryptography. For router and
 * routing tests that are not about the handshake. Never use outside tests.
 */
class TestRelayAuthenticator(
    private val accepted: Set<String>,
    private val role: String = DeviceCert.ROLE_MEMBER,
) : RelayAuthenticator {
    override suspend fun authenticate(challenge: RelayFrame.Challenge, hello: RelayFrame.Hello, nowMs: Long): AuthResult =
        if (hello.deviceId in accepted) {
            AuthResult.Accepted(HubPrincipal(deviceId = hello.deviceId, role = role, departmentId = "eng", certSerial = 1L))
        } else {
            AuthResult.Rejected(HubErrorCodes.ERR_AUTH, "test authenticator: ${hello.deviceId} not listed")
        }
}

/** TEST [HubPrincipalResolver] that returns a fixed principal (or null) for every call. */
class FixedPrincipalResolver(private val principal: HubPrincipal?) : HubPrincipalResolver {
    override suspend fun resolve(call: ApplicationCall): HubPrincipal? = principal
}
