package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.RevocationList

/**
 * A fake signature scheme for pure-logic tests: "signing" with key K hashes (K, data) with FNV-1a 64. It is NOT
 * secure and is only used to test chain LOGIC; the real ECDSA verifiers are covered by the golden signed vector tests.
 */
@OptIn(FlashInternalApi::class)
internal object FakeSigs : EcdsaVerifier {
    private fun mac(key: ByteArray, data: ByteArray): ByteArray {
        var h = -3750763034362895579L
        for (b in key) h = (h xor (b.toLong() and 0xFF)) * 1099511628211L
        for (b in data) h = (h xor (b.toLong() and 0xFF)) * 1099511628211L
        return ByteArray(8) { i -> (h ushr (56 - 8 * i)).toByte() }
    }

    fun keyOf(label: String): String = Base64.encodeUtf8("key-$label")

    fun sign(signerKey: String, data: ByteArray): String = Base64.encode(mac(Base64.decode(signerKey), data))

    override fun verify(publicKeySpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        mac(publicKeySpki, data).contentEquals(signature)

    const val T0: Long = 1_700_000_000_000L
    const val DAY: Long = 86_400_000L

    fun delegation(
        root: String = keyOf("root"),
        delegate: String = keyOf("hub"),
        scopes: Set<String> = setOf("enrol:member", "enrol:dept_admin", "revoke:member"),
        validFrom: Long = T0,
        validTo: Long = T0 + 30 * DAY,
        serial: Long = 1,
        signer: String = root,
    ): DelegationCert {
        val unsigned = DelegationCert(root, delegate, scopes, validFrom, validTo, 100, serial, "")
        return unsigned.copy(signature = sign(signer, unsigned.canonicalBytes()))
    }

    fun device(
        issuer: String = keyOf("hub"),
        role: String = "member",
        validFrom: Long = T0,
        validTo: Long = T0 + 7 * DAY,
        serial: Long = 10,
        deviceId: String = "dev-1",
        signer: String = issuer,
    ): DeviceCert {
        val unsigned = DeviceCert(deviceId, keyOf("subject-$deviceId"), role, "dept-1", validFrom, validTo, serial, issuer, "")
        return unsigned.copy(issuerSignature = sign(signer, unsigned.canonicalBytes()))
    }

    fun revocations(
        version: Long = 1,
        serials: Set<Long> = emptySet(),
        ids: Set<String> = emptySet(),
        delegationSerials: Set<Long> = emptySet(),
        issuer: String = keyOf("root"),
    ): RevocationList {
        val unsigned = RevocationList(version, serials, ids, delegationSerials, issuer, "")
        return unsigned.copy(signature = sign(issuer, unsigned.canonicalBytes()))
    }
}
