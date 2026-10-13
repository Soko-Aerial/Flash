package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden vectors: fixed inputs -> fixed bytes. These constants are the wire. If one of these tests fails, the change
 * breaks every certificate already issued and every signature already made; do NOT regenerate the constant, add a new
 * domain tag instead. The same literals are asserted by the server (java.security) and the client (FlashCrypto)
 * signed-vector tests.
 */
@OptIn(FlashInternalApi::class)
class CertGoldenVectorTest {

    private val device = DeviceCert(
        deviceId = "dev-alpha",
        subjectPublicKey = "c3ViamVjdC1rZXk=",
        role = "member",
        department = "dept-eng",
        validFrom = 1_700_000_000_000,
        validTo = 1_700_604_800_000,
        serial = 42,
        issuerPublicKey = "aXNzdWVyLWtleQ==",
        issuerSignature = "",
    )

    @Test
    fun deviceCertCanonicalBytes() {
        assertEquals(DEVICE_HEX, device.canonicalBytes().toHubHex())
    }

    @Test
    fun deviceCertWithoutDepartmentEncodesEmptyText() {
        assertEquals(DEVICE_NO_DEPT_HEX, device.copy(department = null).canonicalBytes().toHubHex())
        assertEquals(DEVICE_NO_DEPT_HEX, device.copy(department = "").canonicalBytes().toHubHex())
    }

    @Test
    fun deviceCertSignatureIsNotPartOfCanonicalBytes() {
        assertEquals(DEVICE_HEX, device.copy(issuerSignature = "anything").canonicalBytes().toHubHex())
    }

    @Test
    fun delegationCertCanonicalBytesAreScopeOrderIndependent() {
        val d = DelegationCert(
            rootPublicKey = "cm9vdC1rZXk=",
            delegatePublicKey = "ZGVsZWdhdGUta2V5",
            scopes = linkedSetOf("revoke:member", "enrol:member"),
            validFrom = 1_700_000_000_000,
            validTo = 1_731_536_000_000,
            maxDevices = 100,
            serial = 1,
            signature = "",
        )
        assertEquals(DELEGATION_HEX, d.canonicalBytes().toHubHex())
        assertEquals(DELEGATION_HEX, d.copy(scopes = linkedSetOf("enrol:member", "revoke:member")).canonicalBytes().toHubHex())
    }

    @Test
    fun revocationListCanonicalBytes() {
        val r = RevocationList(
            version = 7,
            revokedSerials = linkedSetOf(5L, 3L),
            revokedDeviceIds = linkedSetOf("b", "a"),
            revokedDelegationSerials = setOf(2L),
            issuerPublicKey = "aXNzdWVyLWtleQ==",
            signature = "",
        )
        assertEquals(REVOCATION_HEX, r.canonicalBytes().toHubHex())
    }

    @Test
    fun authSigningBytesAreLengthPrefixed() {
        // 'ab' + 'c' and 'a' + 'bc' must differ (SEC-14).
        val a = HubAuth.authSigningBytes("S", "ab", "c", "d", 1)
        val b = HubAuth.authSigningBytes("S", "a", "bc", "d", 1)
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun authSigningBytesGoldenVector() {
        assertEquals(
            "00000011666c6173682d6875622d617574682d7631" + // tag "flash-hub-auth-v1"
                "0000000141" + // serverId "A"
                "0000000142" + // serverNonce "B"
                "0000000143" + // clientNonce "C"
                "0000000144" + // deviceId "D"
                "00000008" + "0000000000000007", // serial 7
            HubAuth.authSigningBytes("A", "B", "C", "D", 7).toHubHex(),
        )
    }

    @Test
    fun sealingKeyBindingGoldenVector() {
        // ADR-124: the exact bytes a device identity key signs to publish a sealing key. Client (`HubSealingKeyPair`),
        // hub (`PUT /device/sealing-key`) and this test assert the same literal. Never regenerate: add a new tag.
        assertEquals(
            SEALING_BINDING_HEX,
            HubAuth.sealingKeyBindingBytes("dev-alpha", ByteArray(16) { it.toByte() }).toHubHex(),
        )
    }

    @Test
    fun sealingKeyBindingIsBoundToDeviceAndKey() {
        val key = ByteArray(16) { it.toByte() }
        val base = HubAuth.sealingKeyBindingBytes("dev-alpha", key)
        assertEquals(false, base.contentEquals(HubAuth.sealingKeyBindingBytes("dev-beta", key)))
        assertEquals(false, base.contentEquals(HubAuth.sealingKeyBindingBytes("dev-alpha", key.copyOf().also { it[0] = 9 })))
        // never confusable with the auth, enrol or renew forms
        assertEquals(false, base.contentEquals(HubAuth.enrollProofBytes("t", "dev-alpha", "pk", "S")))
    }

    @Test
    fun differentPurposesNeverShareBytes() {
        val auth = HubAuth.authSigningBytes("S", "n1", "n2", "d", 1)
        val renew = HubAuth.renewProofBytes("S", "n1", "n2", "d", 1, "pk")
        val enroll = HubAuth.enrollProofBytes("t", "d", "pk", "S")
        assertEquals(false, auth.contentEquals(renew))
        assertEquals(false, auth.contentEquals(enroll))
        assertEquals(false, renew.contentEquals(enroll))
    }

    companion object {
        const val SEALING_BINDING_HEX =
            "00000014666c6173682d6875622d7365616c6b65792d7631000000096465762d616c70686100000010000102030405060708090a0b0c0d0e0f"
        const val DEVICE_HEX =
            "00000014666c6173682d6465766963652d636572742d7631000000096465762d616c7068610000001063335669616d566a644331725a586b3d" +
                "000000066d656d62657200000008646570742d656e67000000080000018bcfe56800000000080000018bf3f1ec0000000008000000000000002a" +
                "0000001061584e7a645756794c57746c65513d3d"
        const val DEVICE_NO_DEPT_HEX =
            "00000014666c6173682d6465766963652d636572742d7631000000096465762d616c7068610000001063335669616d566a644331725a586b3d" +
                "000000066d656d62657200000000000000080000018bcfe56800000000080000018bf3f1ec0000000008000000000000002a" +
                "0000001061584e7a645756794c57746c65513d3d"
        const val DELEGATION_HEX =
            "00000013666c6173682d64656c65676174696f6e2d76310000000c636d3976644331725a586b3d000000105a4756735a576468644755746132563500000004" +
                "000000020000000c656e726f6c3a6d656d6265720000000d7265766f6b653a6d656d626572000000080000018bcfe5680000000008000001932796940000000004" +
                "00000064000000080000000000000001"
        const val REVOCATION_HEX =
            "00000013666c6173682d7265766f636174696f6e2d7632000000080000000000000007000000040000000200000008000000000000000300000008000000000000000500" +
                "000004000000020000000161000000016200000004000000010000000800000000000000020000001061584e7a645756794c57746c65513d3d"
    }
}
