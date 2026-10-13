@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.HubClientAuth

/** Signs relay handshake bytes with the device identity key. The private key never leaves the platform keystore. */
@FlashInternalApi
public fun interface RelaySigner {
    public fun sign(data: ByteArray): ByteArray
}

/**
 * Everything a relay connection needs to prove who this device is (Hub relay wire v2): the certificate chain it
 * presents, the pinned realm it expects to talk to, and a signer. A [RelayTransport] never sees a private key.
 *
 * @property expectedServerId the pinned root fingerprint (upper-case hex). Blank means "no pin known" (credentials
 *   stored by a build that did not record it); the transport then signs whatever audience the hub states.
 *   PHASE-3: derive the pin from `rootPublicKey` when blank (CLI-01).
 */
@FlashInternalApi
public class RelayIdentity(
    public val deviceId: String,
    public val friendlyName: String,
    public val deviceCert: DeviceCert,
    public val delegationChain: List<DelegationCert>,
    public val expectedServerId: String,
    public val signer: RelaySigner,
    public val nonceSource: () -> String = HubClientAuth::newNonce,
) {
    public companion object {
        /** Builds an identity from stored enrolment credentials and the device's [FlashCrypto]. */
        public fun from(credentials: EnrollmentCredentials, friendlyName: String, crypto: FlashCrypto): RelayIdentity =
            RelayIdentity(
                deviceId = credentials.deviceId,
                friendlyName = friendlyName,
                deviceCert = credentials.deviceCert,
                delegationChain = credentials.delegationChain,
                expectedServerId = credentials.rootFingerprint,
                signer = RelaySigner { crypto.sign(it) },
            )
    }
}
