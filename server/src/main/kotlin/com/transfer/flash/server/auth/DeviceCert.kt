package com.transfer.flash.server.auth

import com.transfer.flash.core.security.crypto.FlashCrypto
import java.security.PrivateKey

/**
 * Device Certificate model (E2, E2b, ENTERPRISE-HYBRID-PLAN).
 *
 * Binds a device identity to a role and department within the organization realm:
 * - [deviceId]: Hardware / installation unique identifier.
 * - [subjectPublicKey]: Base64-encoded X.509 SPKI identity key of the device.
 * - [role]: Assigned role ("member", "admin", "dept_admin").
 * - [department]: Optional department or battalion unit ID.
 * - [validFrom] and [validTo]: Validity time window in epoch milliseconds.
 * - [serial]: Monotonic device certificate serial number.
 * - [issuerPublicKey]: Base64-encoded X.509 SPKI of the certificate signer (Root or Delegated Key).
 * - [issuerSignature]: ECDSA P-256 signature by the issuer key over [canonicalBytes].
 */
data class DeviceCert(
    val deviceId: String,
    val subjectPublicKey: String,
    val role: String,
    val department: String?,
    val validFrom: Long,
    val validTo: Long,
    val serial: Long,
    val issuerPublicKey: String,
    val issuerSignature: String,
) {
    /**
     * Serializes the device cert fields into canonical bytes (tag `flash-device-cert-v1`)
     * for signature creation and verification.
     */
    fun canonicalBytes(): ByteArray {
        return AuthCanonicalWriter(TAG)
            .text(deviceId)
            .text(subjectPublicKey)
            .text(role)
            .text(department.orEmpty())
            .long(validFrom)
            .long(validTo)
            .long(serial)
            .text(issuerPublicKey)
            .build()
    }

    /**
     * Verifies that the certificate signature was produced by [issuerPublicKey].
     */
    fun verifySignature(): Boolean {
        if (issuerSignature.isBlank() || issuerPublicKey.isBlank()) return false
        return FlashCrypto.verifySignature(
            publicKeyBase64 = issuerPublicKey,
            data = canonicalBytes(),
            signatureBase64 = issuerSignature,
        )
    }

    /**
     * Checks if this certificate is within its validity window at [timestamp].
     */
    fun isValidAt(timestamp: Long = System.currentTimeMillis()): Boolean {
        return timestamp in validFrom..validTo
    }

    /**
     * Verifies the full certificate chain up to the organization's [rootPublicKey].
     *
     * @param delegation Optional delegation certificate if issued by a delegated server/authority.
     * @param rootPublicKey Expected organization root public key (Base64 SPKI).
     * @param timestamp Verification time in epoch millis.
     * @return true if both the device cert and the delegation chain are valid and authorized.
     */
    fun verifyChain(
        delegation: DelegationCert?,
        rootPublicKey: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isValidAt(timestamp)) return false
        if (!verifySignature()) return false

        return if (delegation == null) {
            // Direct root issuance: issuer must be the root key itself
            issuerPublicKey == rootPublicKey
        } else {
            // Delegated issuance (E2b):
            // 1. Delegation cert must bind to the expected root public key
            if (delegation.rootPublicKey != rootPublicKey) return false
            // 2. Delegation cert must be valid at this timestamp
            if (!delegation.isValidAt(timestamp)) return false
            // 3. Delegation signature must verify against root key
            if (!delegation.verifySignature()) return false
            // 4. Issuer of this device cert must match the delegate key
            if (issuerPublicKey != delegation.delegatePublicKey) return false
            // 5. Delegation scope check: a delegated server cannot issue roles it lacks scope for.
            // Under E2b: delegations may not grant REALM admin roles ("admin").
            if (role == ROLE_ADMIN && !delegation.hasScope(DelegationCert.SCOPE_ENROL_ADMIN)) {
                return false
            }
            // Check that delegation has scope to enrol this role (e.g. "enrol:member")
            delegation.hasScope("enrol:$role")
        }
    }

    companion object {
        const val TAG: String = "flash-device-cert-v1"

        const val ROLE_MEMBER: String = "member"
        const val ROLE_ADMIN: String = "admin"
        const val ROLE_DEPT_ADMIN: String = "dept_admin"

        /** Default validity for client device certificates: 7 days (D-E9). */
        const val DEFAULT_VALIDITY_MS: Long = 7L * 24 * 60 * 60 * 1000

        /**
         * Helper to issue and sign a new [DeviceCert] with an issuer private key.
         */
        fun issue(
            issuerPrivateKey: PrivateKey,
            issuerPublicKey: String,
            deviceId: String,
            subjectPublicKey: String,
            role: String = ROLE_MEMBER,
            department: String? = null,
            validFrom: Long = System.currentTimeMillis(),
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
            val sig = FlashCrypto.sign(issuerPrivateKey, unsigned.canonicalBytes())
            return unsigned.copy(issuerSignature = sig)
        }
    }
}
