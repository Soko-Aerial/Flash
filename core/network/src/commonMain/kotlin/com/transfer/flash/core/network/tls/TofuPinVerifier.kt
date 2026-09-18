package com.transfer.flash.core.network.tls

/**
 * Trust-On-First-Use (TOFU) pin verifier backed by lookup and storage delegates.
 *
 * Implements discovery pinning and TOFU verification:
 * - If [deviceId] has a recorded pin in [lookupPin], compares in constant time against [fingerprintHex].
 *   Returns true on match, false on mismatch (hard fail-closed against key change / MITM).
 * - If [deviceId] has no recorded pin yet (first connection): records the presented [fingerprintHex]
 *   via [recordPin] and returns true (Trust On First Use).
 */
public class TofuPinVerifier(
    private val lookupPin: (deviceId: String) -> String?,
    private val recordPin: ((deviceId: String, fingerprintHex: String) -> Unit)? = null,
) : FlashPinVerifier {

    override fun isPinned(deviceId: String, fingerprintHex: String): Boolean {
        val presented = FlashPinVerifier.normalize(fingerprintHex)
        if (presented.isEmpty()) return false
        val known = lookupPin(deviceId)?.let { FlashPinVerifier.normalize(it) }

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
}
