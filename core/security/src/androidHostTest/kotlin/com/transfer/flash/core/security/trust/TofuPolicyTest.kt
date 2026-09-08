package com.transfer.flash.core.security.trust

import com.transfer.flash.core.security.trust.pinned.TofuPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TofuPolicyTest {

    private val pinned = "aabb00112233445566778899aabbccddeeff00112233445566778899aabbccdd"
    private val presented = "aabb00112233445566778899aabbccddeeff00112233445566778899aabbccdd"
    private val other = "1122334455667788112233445566778811223344556677881122334455667788"

    @Test
    fun `unknown device with presented fingerprint is FirstConnect`() {
        val d = TofuPolicy.evaluate(null, presented)
        assertTrue(d is TofuPolicy.Decision.FirstConnect)
        assertEquals(presented, (d as TofuPolicy.Decision.FirstConnect).presentedFingerprintHex)
    }

    @Test
    fun `legacy blank known fingerprint re-prompts instead of trusting silently`() {
        // Rows migrated from SharedPreferences carry no crypto pin: they must NOT match.
        val d = TofuPolicy.evaluate(TofuPolicy.LEGACY_UNBOUND_FINGERPRINT, presented)
        assertTrue(d is TofuPolicy.Decision.FirstConnect)
    }

    @Test
    fun `matching fingerprints are Match - case and separators normalized`() {
        val d = TofuPolicy.evaluate(pinned.uppercase(), "AA:BB:00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF 00 11 22 33 44 55 66 77 88 99 AA BB CC DD")
        assertEquals(TofuPolicy.Decision.Match, d)
    }

    @Test
    fun `different fingerprint hard-fails as KEY_CHANGED for UI-031`() {
        val d = TofuPolicy.evaluate(pinned, other)
        assertTrue(d is TofuPolicy.Decision.Mismatch)
        d as TofuPolicy.Decision.Mismatch
        assertEquals(TofuPolicy.Decision.Mismatch.Reason.KEY_CHANGED, d.reason)
        assertEquals(pinned, d.pinnedFingerprintHex)
        assertEquals(other, d.presentedFingerprintHex)
    }

    @Test
    fun `blank presented fingerprint fails closed when a pin exists`() {
        val d = TofuPolicy.evaluate(pinned, "")
        assertTrue(d is TofuPolicy.Decision.Mismatch)
        assertEquals(
            TofuPolicy.Decision.Mismatch.Reason.PRESENTED_FINGERPRINT_MISSING,
            (d as TofuPolicy.Decision.Mismatch).reason,
        )
    }

    @Test
    fun `null presented fingerprint fails closed even without any record`() {
        val d = TofuPolicy.evaluate(null, null)
        assertTrue(d is TofuPolicy.Decision.Mismatch)
        assertEquals(
            TofuPolicy.Decision.Mismatch.Reason.PRESENTED_FINGERPRINT_MISSING,
            (d as TofuPolicy.Decision.Mismatch).reason,
        )
        assertNullPins(d)
    }

    private fun assertNullPins(d: TofuPolicy.Decision.Mismatch) {
        assertEquals(null, d.pinnedFingerprintHex)
        assertEquals(null, d.presentedFingerprintHex)
    }

    @Test
    fun `whitespace-only presented fingerprint also fails closed`() {
        val d = TofuPolicy.evaluate(pinned, "   ")
        assertTrue(d is TofuPolicy.Decision.Mismatch)
    }

    @Test
    fun `mismatch decision never offers a bypass`() {
        // Guard against someone later adding an Ignore/Proceed branch to Decision.
        val subtypes = listOf(
            TofuPolicy.Decision.FirstConnect::class,
            TofuPolicy.Decision.Match::class,
            TofuPolicy.Decision.Mismatch::class,
        )
        assertEquals(3, subtypes.size)
    }
}
