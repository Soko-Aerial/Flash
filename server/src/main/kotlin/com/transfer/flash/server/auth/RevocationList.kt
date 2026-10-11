package com.transfer.flash.server.auth

import com.transfer.flash.core.security.crypto.FlashCrypto
import java.security.PrivateKey

/**
 * Monotonic versioned Revocation List (E2, ENTERPRISE-HYBRID-PLAN).
 *
 * Distributed via gossip between devices and from the server:
 * - [version]: Monotonically increasing version counter. Higher versions supersede lower ones.
 * - [revokedSerials]: Set of revoked certificate serial numbers.
 * - [revokedDeviceIds]: Set of revoked device identifiers.
 * - [issuerPublicKey]: Base64-encoded X.509 SPKI of the revocation issuer (Root or Admin).
 * - [signature]: ECDSA P-256 signature by the issuer key over [canonicalBytes].
 */
data class RevocationList(
    val version: Long,
    val revokedSerials: Set<Long>,
    val revokedDeviceIds: Set<String>,
    val issuerPublicKey: String,
    val signature: String,
) {
    /**
     * Serializes the revocation list fields into canonical bytes (tag `flash-revocation-v1`).
     */
    fun canonicalBytes(): ByteArray {
        return AuthCanonicalWriter(TAG)
            .long(version)
            .longSet(revokedSerials)
            .stringSet(revokedDeviceIds)
            .text(issuerPublicKey)
            .build()
    }

    /**
     * Verifies that the revocation list signature was produced by [issuerPublicKey].
     */
    fun verifySignature(): Boolean {
        if (signature.isBlank() || issuerPublicKey.isBlank()) return false
        return FlashCrypto.verifySignature(
            publicKeyBase64 = issuerPublicKey,
            data = canonicalBytes(),
            signatureBase64 = signature,
        )
    }

    /**
     * Checks if a certificate serial number or deviceId is revoked.
     */
    fun isRevoked(certSerial: Long, deviceId: String): Boolean {
        return revokedSerials.contains(certSerial) || revokedDeviceIds.contains(deviceId)
    }

    /**
     * Checks if a [DeviceCert] is revoked.
     */
    fun isRevoked(cert: DeviceCert): Boolean {
        return isRevoked(cert.serial, cert.deviceId)
    }

    companion object {
        const val TAG: String = "flash-revocation-v1"

        /**
         * Helper to issue and sign a new [RevocationList] with an issuer private key.
         */
        fun issue(
            issuerPrivateKey: PrivateKey,
            issuerPublicKey: String,
            version: Long,
            revokedSerials: Set<Long>,
            revokedDeviceIds: Set<String>,
        ): RevocationList {
            val unsigned = RevocationList(
                version = version,
                revokedSerials = revokedSerials,
                revokedDeviceIds = revokedDeviceIds,
                issuerPublicKey = issuerPublicKey,
                signature = "",
            )
            val sig = FlashCrypto.sign(issuerPrivateKey, unsigned.canonicalBytes())
            return unsigned.copy(signature = sig)
        }
    }
}
