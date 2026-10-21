@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.auth

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.identity.IdentityStore
import java.security.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SRVHUB-04 (DEV-01, ADR-222): the certificate authenticates a device, the registry decides its role and department. */
class RegistryPrincipalTest {

    private val now = System.currentTimeMillis()
    private val root = FlashCrypto.generateEcKeyPair()
    private val delegate = FlashCrypto.generateEcKeyPair()
    private val store = IdentityStore.inMemory()

    private val delegation = DelegationCert.issue(
        rootPrivateKey = root.private,
        rootPublicKey = root.toBase64Spki(),
        delegatePublicKey = delegate.toBase64Spki(),
        scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
        validFrom = now - 60_000,
        validTo = now + 400L * 86_400_000L,
        maxDevices = 50,
        serial = 1L,
    )
    private val enrollment = EnrollmentService(root.toBase64Spki(), delegate.private, delegation, store = store)

    private class Enrolled(val deviceId: String, val key: KeyPair, val cert: DeviceCert)

    private fun enrol(deviceId: String, department: String): Enrolled {
        val key = FlashCrypto.generateEcKeyPair()
        val token = enrollment.generateInviteLink("https://hub.test", InviteScope(department = department, expiry = now + 60_000), now)
            .substringAfter("token=").substringBefore("&")
        val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), enrollment.serverId))
        val cert = enrollment.enrollDevice(token, deviceId, key.toBase64Spki(), proof, currentTimeMs = now).getOrThrow().deviceCert
        return Enrolled(deviceId, key, cert)
    }

    private fun auth(withRegistry: Boolean = true) =
        ChallengeResponseAuth(root.toBase64Spki(), deviceRegistry = if (withRegistry) { id -> store.device(id) } else null)

    private fun verify(auth: ChallengeResponseAuth, d: Enrolled) = auth.verifyIdentity(
        serverNonce = "server-nonce",
        clientNonce = "client-nonce",
        deviceId = d.deviceId,
        signature = FlashCrypto.sign(
            d.key.private,
            HubAuth.authSigningBytes(auth.serverId, "server-nonce", "client-nonce", d.deviceId, d.cert.serial),
        ),
        deviceCert = d.cert,
        delegationCert = delegation,
        currentTimeMs = now + 1_000,
    )

    @Test
    fun aMovedDeviceCarriesTheRegistryDepartmentWithItsOldCertificate() {
        val d = enrol("dev-a", "eng")
        assertEquals("eng", d.cert.department)
        assertEquals("eng", verify(auth(), d).getOrThrow().department)

        store.setDepartment("dev-a", "sales")

        val principal = verify(auth(), d).getOrThrow()
        assertEquals("sales", principal.department, "the old certificate still says eng; the principal says sales")
        assertEquals(d.cert.serial, principal.certSerial, "revocation keeps keying on the certificate serial")
        assertEquals("eng", verify(auth(withRegistry = false), d).getOrThrow().department, "without a registry the certificate is trusted as before")
    }

    @Test
    fun aMoveToNoDepartmentIsRespected() {
        val d = enrol("dev-a", "eng")
        store.setDepartment("dev-a", null)
        assertEquals(null, verify(auth(), d).getOrThrow().department)
    }

    @Test
    fun aValidCertificateWithoutARegistryRowIsRefused() {
        val d = enrol("dev-a", "eng")
        val emptyRegistry = ChallengeResponseAuth(root.toBase64Spki(), deviceRegistry = { null })
        val result = verify(emptyRegistry, d)
        assertTrue(result.isFailure)
        assertEquals(HubErrorCodes.ERR_AUTH, (result.exceptionOrNull() as AuthRejected).code)
    }

    @Test
    fun aRevokedOrUnenrolledRegistryRowIsRefused() {
        val revoked = enrol("dev-r", "eng")
        val gone = enrol("dev-g", "eng")
        store.setStatus("dev-r", com.transfer.flash.server.identity.DeviceStatus.REVOKED, now)
        store.setStatus("dev-g", com.transfer.flash.server.identity.DeviceStatus.UNENROLLED, now)
        assertEquals(HubErrorCodes.ERR_REVOKED, (verify(auth(), revoked).exceptionOrNull() as AuthRejected).code)
        assertEquals(HubErrorCodes.ERR_AUTH, (verify(auth(), gone).exceptionOrNull() as AuthRejected).code)
    }
}
