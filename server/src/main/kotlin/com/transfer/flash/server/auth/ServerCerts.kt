package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.security.crypto.FlashCrypto
import java.security.PrivateKey

/**
 * The hub's half of the certificate model (ADR-107).
 *
 * The certificate TYPES, their canonical bytes and every verification rule live in `:core:hub-protocol` and are shared
 * with the clients. Only the parts that need `java.security` private keys live here: the [EcdsaVerifier] that checks
 * signatures with the JDK provider, and the `issue` helpers that sign new certificates.
 */

/** [EcdsaVerifier] backed by the JDK's `SHA256withECDSA`. Never throws: any malformed input is a failed check. */
object JvmEcdsaVerifier : EcdsaVerifier {
    override fun verify(publicKeySpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        FlashCrypto.verifySignature(publicKeySpki, data, signature)
}

/** Verifies this certificate's own signature with the JDK provider. */
fun DeviceCert.verifySignature(): Boolean = verifySignature(JvmEcdsaVerifier)

/** Verifies this delegation's root signature with the JDK provider. */
fun DelegationCert.verifySignature(): Boolean = verifySignature(JvmEcdsaVerifier)

/** Verifies this revocation list's issuer signature with the JDK provider. */
fun RevocationList.verifySignature(): Boolean = verifySignature(JvmEcdsaVerifier)

/** Issues and signs a [DeviceCert] with [issuerPrivateKey] (the root key, or the delegate key named in a delegation). */
fun DeviceCert.Companion.issue(
    issuerPrivateKey: PrivateKey,
    issuerPublicKey: String,
    deviceId: String,
    subjectPublicKey: String,
    role: String = DeviceCert.ROLE_MEMBER,
    department: String? = null,
    validFrom: Long = System.currentTimeMillis() - ISSUE_BACKDATE_MS,
    validTo: Long = System.currentTimeMillis() + DEFAULT_VALIDITY_MS,
    serial: Long,
): DeviceCert {
    val unsigned = DeviceCert(
        deviceId = deviceId,
        subjectPublicKey = subjectPublicKey,
        role = role,
        department = department,
        validFrom = validFrom,
        validTo = validTo,
        serial = serial,
        issuerPublicKey = issuerPublicKey,
        issuerSignature = "",
    )
    return unsigned.copy(issuerSignature = FlashCrypto.sign(issuerPrivateKey, unsigned.canonicalBytes()))
}

/** Issues and signs a [DelegationCert] with the root private key. */
fun DelegationCert.Companion.issue(
    rootPrivateKey: PrivateKey,
    rootPublicKey: String,
    delegatePublicKey: String,
    scopes: Set<String>,
    validFrom: Long,
    validTo: Long,
    maxDevices: Int,
    serial: Long,
): DelegationCert {
    val unsigned = DelegationCert(
        rootPublicKey = rootPublicKey,
        delegatePublicKey = delegatePublicKey,
        scopes = scopes,
        validFrom = validFrom,
        validTo = validTo,
        maxDevices = maxDevices,
        serial = serial,
        signature = "",
    )
    return unsigned.copy(signature = FlashCrypto.sign(rootPrivateKey, unsigned.canonicalBytes()))
}

/** Issues and signs a [RevocationList] (ADR-110: it can revoke certificate serials, device ids and delegation serials). */
fun RevocationList.Companion.issue(
    issuerPrivateKey: PrivateKey,
    issuerPublicKey: String,
    version: Long,
    revokedSerials: Set<Long>,
    revokedDeviceIds: Set<String>,
    revokedDelegationSerials: Set<Long> = emptySet(),
): RevocationList {
    val unsigned = RevocationList(
        version = version,
        revokedSerials = revokedSerials,
        revokedDeviceIds = revokedDeviceIds,
        revokedDelegationSerials = revokedDelegationSerials,
        issuerPublicKey = issuerPublicKey,
        signature = "",
    )
    return unsigned.copy(signature = FlashCrypto.sign(issuerPrivateKey, unsigned.canonicalBytes()))
}
