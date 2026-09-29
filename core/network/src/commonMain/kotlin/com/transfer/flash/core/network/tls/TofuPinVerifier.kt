@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.tls

import com.transfer.flash.core.common.logging.FlashLog

/**
 * Trust-On-First-Use (TOFU) pin verifier backed by lookup and storage delegates.
 *
 * Implements discovery pinning and TOFU verification:
 * - If [deviceId] has a recorded pin in [lookupPin], compares in constant time against [fingerprintHex].
 *   Returns true on match, false on mismatch (hard fail-closed against key change / MITM).
 * - If [deviceId] has no recorded pin yet (first connection): records the presented [fingerprintHex]
 *   via [recordPin] and returns true (Trust On First Use).
 *
 * ## This device's own key ([ownFingerprintHex], ERROR-077)
 * A peer presenting this device's own identity key is this device (a dial that reached itself) or a copy of
 * its key; never a peer. It is refused, and never recorded. Before this rule, a PC whose discovery paired
 * another PC's id with its own address dialled itself, pinned its OWN key under the other PC's id, and could
 * never connect to that PC again. A stored pin equal to the own key is therefore treated as no pin at all:
 * the next real handshake with that id is a first use again, which heals installs that already hold one.
 */
public class TofuPinVerifier(
    private val lookupPin: (deviceId: String) -> String?,
    private val recordPin: ((deviceId: String, fingerprintHex: String) -> Unit)? = null,
    /** SHA-256 of this device's own identity SPKI, hex; null disables the own-key rule. */
    ownFingerprintHex: String? = null,
) : FlashPinVerifier {

    private val ownFingerprint: String? =
        ownFingerprintHex?.let { FlashPinVerifier.normalize(it) }?.takeIf { it.isNotEmpty() }

    override fun isPinned(deviceId: String, fingerprintHex: String): Boolean {
        val presented = FlashPinVerifier.normalize(fingerprintHex)
        if (presented.isEmpty()) return false
        val own = ownFingerprint
        if (own != null && constantTimeEquals(own, presented)) {
            FlashLog.w(TAG, "Refused device $deviceId: it presented this device's own key (a dial that reached itself)")
            return false
        }
        val known = lookupPin(deviceId)?.let { FlashPinVerifier.normalize(it) }?.takeUnless { stored ->
            val poisoned = own != null && constantTimeEquals(own, stored)
            if (poisoned) FlashLog.w(TAG, "Pin for $deviceId was this device's own key; replacing it on this handshake")
            poisoned
        }

        return if (known.isNullOrBlank()) {
            // First connect: trust on first use and record the pin
            recordPin?.invoke(deviceId, presented)
            true
        } else {
            constantTimeEquals(known, presented)
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private companion object {
        const val TAG = "TLS"
    }
}
