package com.transfer.flash.core.security.trust

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap

/**
 * The TLS key an unsolicited inbound connection presented for a device id, held in memory for the life of the process
 * and never written to the trust store (SEC-D02-02).
 *
 * An inbound first contact used to be recorded as a trust-on-first-use pin, so any stranger who connected first
 * while claiming a contact's id owned that id's pin and the real contact was then refused as "key changed". The
 * pairing check still needs to know which key the connection used, to prove the fingerprint in the pairing hello is
 * the one this TLS session proved. It reads that key from here when no pin exists. Pairing, not this class, decides
 * whether the key is ever trusted.
 */
@OptIn(FlashInternalApi::class)
public class ObservedPeerPins {
    private val byDevice = SyncMap<String, String>()

    /** Remembers [fingerprintHex] as the key the latest inbound connection of [deviceId] presented. */
    public fun observe(deviceId: String, fingerprintHex: String) {
        if (byDevice.keysSnapshot().size >= MAX_ENTRIES) byDevice.clear()
        byDevice[deviceId] = fingerprintHex
    }

    /** The key last observed for [deviceId], or null. */
    public fun get(deviceId: String): String? = byDevice[deviceId]

    private companion object {
        /** A stranger can invent ids; the table is dropped rather than allowed to grow without bound. */
        const val MAX_ENTRIES = 256
    }
}
