package com.transfer.flash.core.security.trust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The table in `docs/group/v2-vouched-trust-plan.md` E1, one row per test. */
class VouchRulesTest {
    private val keyA = "AA:BB:CC:DD"
    private val keyB = "11 22 33 44"
    private val g1 = "g2-one"
    private val g2 = "g2-two"

    private fun decide(
        paired: Boolean = false,
        pin: String? = null,
        groups: Set<String> = emptySet(),
        key: String = keyA,
        group: String = g1,
    ) = VouchRules.decide(paired, pin, groups, key, group)

    @Test
    fun `a device with no pin accepts a vouch`() {
        assertEquals(VouchVerdict.ACCEPT, decide())
    }

    @Test
    fun `a first-use pin is replaced by a vouch for another key`() {
        // The attacker who connected first as that id is exactly this case.
        assertEquals(VouchVerdict.ACCEPT, decide(pin = "0102"))
        assertEquals(VouchVerdict.ACCEPT, decide(pin = "AABBCCDD"))
    }

    @Test
    fun `the same key in another notation is the same key`() {
        assertEquals(VouchVerdict.ACCEPT, decide(paired = true, pin = "aabbccdd"))
        assertEquals(VouchVerdict.ACCEPT, decide(pin = "aa bb cc dd", groups = setOf(g2)))
    }

    @Test
    fun `a group replaces its own earlier vouch`() {
        // The owner re-issued the cert after the member reinstalled.
        assertEquals(VouchVerdict.ACCEPT, decide(pin = "0102", groups = setOf(g1)))
    }

    @Test
    fun `two groups that disagree on the key conflict and the first stays`() {
        assertEquals(VouchVerdict.CONFLICT_VOUCHED, decide(pin = "0102", groups = setOf(g2)))
        assertEquals(VouchVerdict.CONFLICT_VOUCHED, decide(pin = "0102", groups = setOf(g1, g2)))
    }

    @Test
    fun `pairing wins over a vouch for a different key`() {
        assertEquals(VouchVerdict.CONFLICT_PAIRED, decide(paired = true, pin = "0102"))
    }

    @Test
    fun `a paired device with no stored pin accepts the vouch`() {
        assertEquals(VouchVerdict.ACCEPT, decide(paired = true, pin = null))
    }

    @Test
    fun `a blank fingerprint or group is invalid`() {
        assertEquals(VouchVerdict.INVALID, decide(key = " : "))
        assertEquals(VouchVerdict.INVALID, decide(group = ""))
    }

    @Test
    fun `the source is derived from pairing, vouches and the pin`() {
        assertNull(VouchRules.sourceOf(paired = true, pin = null, vouchingGroups = setOf(g1)))
        assertEquals(PinSource.PAIRED, VouchRules.sourceOf(paired = true, pin = "AA", vouchingGroups = setOf(g1)))
        assertEquals(PinSource.VOUCHED, VouchRules.sourceOf(paired = false, pin = "AA", vouchingGroups = setOf(g1)))
        assertEquals(PinSource.TOFU, VouchRules.sourceOf(paired = false, pin = "AA", vouchingGroups = emptySet()))
    }

    @Test
    fun `revoking a group that never vouched changes nothing`() {
        assertNull(VouchRules.afterRevoke(setOf(g1), g2))
        assertNull(VouchRules.afterRevoke(emptySet(), g1))
        assertEquals(setOf(g2), VouchRules.afterRevoke(setOf(g1, g2), g1))
        assertEquals(emptySet(), VouchRules.afterRevoke(setOf(g1), g1))
    }

    @Test
    fun `normalize strips separators and uppercases`() {
        assertEquals("AABBCCDD", VouchRules.normalize(keyA.lowercase()))
        assertEquals("11223344", VouchRules.normalize(keyB))
    }
}
