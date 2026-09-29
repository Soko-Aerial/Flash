package com.transfer.flash.core.network.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-057: what STANDARD and BOOST dial when more devices are around than the budget allows. */
class DialBudgetTest {

    private val live = LinkActivity(outbound = true, userIdleMs = 0L)

    private fun ids(prefix: String, n: Int) = (1..n).map { prefix + it.toString().padStart(2, '0') }.toSet()

    private fun view(
        available: Set<String>,
        contacts: Set<String> = emptySet(),
        held: Set<String> = emptySet(),
        busy: Set<String> = emptySet(),
    ) = LinkView(available + held, contacts, held.associateWith { live }, busy, nearbyOpen = false)

    @Test
    fun `a room that fits the budget is not filtered`() {
        assertNull(DialBudget.allowed("me", view(available = ids("p", 20)), limit = 20))
        assertNull(DialBudget.allowed("me", view(available = emptySet()), limit = 20))
    }

    @Test
    fun `one device over the budget starts filtering`() {
        val allowed = DialBudget.allowed("me", view(available = ids("p", 21)), limit = 20)!!
        assertEquals(20, allowed.size)
    }

    @Test
    fun `the local device does not count toward the budget and is never allowed`() {
        assertNull(DialBudget.allowed("me", view(available = ids("p", 20) + "me"), limit = 20))
        val allowed = DialBudget.allowed("me", view(available = ids("p", 30) + "me"), limit = 20)!!
        assertFalse("me" in allowed)
    }

    @Test
    fun `a 20 member group meshes in a crowd of strangers`() {
        val group = ids("g", 19)
        val strangers = ids("s", 40)
        val v = view(available = group + strangers, contacts = group)
        val allowed = DialBudget.allowed("me", v, limit = ConnectionModePolicy.DIAL_BUDGET)!!
        assertTrue(allowed.containsAll(group), "every group member is dialed")
        assertEquals(ConnectionModePolicy.DIAL_BUDGET, allowed.size)
        assertEquals(1, (allowed - group).size, "strangers only get what the contacts leave")
    }

    @Test
    fun `contacts that are not around are not counted or dialed`() {
        val v = view(available = ids("s", 25), contacts = setOf("gone-1", "gone-2"))
        val allowed = DialBudget.allowed("me", v, limit = 20)!!
        assertFalse("gone-1" in allowed)
        assertEquals(20, allowed.size)
    }

    @Test
    fun `held sessions stay allowed and use up slots`() {
        val held = ids("h", 15)
        val group = ids("g", 10)
        val v = view(available = group + ids("s", 10), contacts = group, held = held)
        val allowed = DialBudget.allowed("me", v, limit = 20)!!
        assertTrue(allowed.containsAll(held), "a held session is never dropped from the filter")
        assertEquals(20, allowed.size)
        assertEquals(5, (allowed - held).size)
        assertTrue((allowed - held).all { it in group }, "the free slots go to contacts before strangers")
    }

    @Test
    fun `a phone that is full dials nobody new, except a busy peer`() {
        val held = ids("h", 20)
        val v = view(available = ids("s", 5), contacts = ids("s", 5), held = held, busy = setOf("s03"))
        assertEquals(held + "s03", DialBudget.allowed("me", v, limit = 20))
        val noCall = view(available = ids("s", 5), contacts = ids("s", 5), held = held)
        assertEquals(held, DialBudget.allowed("me", noCall, limit = 20))
    }

    @Test
    fun `a busy peer outranks contacts`() {
        val contacts = ids("c", 30)
        val v = view(available = contacts + "zz-call", contacts = contacts, busy = setOf("zz-call"))
        val allowed = DialBudget.allowed("me", v, limit = 5)!!
        assertTrue("zz-call" in allowed)
        assertEquals(5, allowed.size)
    }

    @Test
    fun `the same view always gives the same set, and a contact going live changes nothing else`() {
        val contacts = ids("c", 30)
        val before = DialBudget.allowed("me", view(available = contacts, contacts = contacts), limit = 20)!!
        assertEquals(before, DialBudget.allowed("me", view(available = contacts, contacts = contacts), limit = 20))

        val first = before.sorted().first()
        val after = DialBudget.allowed("me", view(available = contacts, contacts = contacts, held = setOf(first)), limit = 20)!!
        assertEquals(before, after, "the peer that came up moves from 'to dial' to 'held'")
    }

    @Test
    fun `the budget leaves headroom under the ceiling for peers that dial in`() {
        assertEquals(
            com.transfer.flash.core.network.resilience.SessionHardeningPolicy.DEFAULT_MAX_CONCURRENT_SESSIONS -
                ConnectionModePolicy.DIAL_HEADROOM,
            ConnectionModePolicy.DIAL_BUDGET,
        )
        assertTrue(ConnectionModePolicy.DIAL_HEADROOM >= 3)
    }

    @Test
    fun `the budget covers a 20 member group plus one more peer`() {
        // ADR-044 V2 sets MAX_MEMBERS = 20; core:network cannot see GroupPolicy, so the number is
        // repeated here and V2 asserts the two agree where both are visible.
        val remoteGroupMembers = 19
        assertTrue(ConnectionModePolicy.DIAL_BUDGET >= remoteGroupMembers + 1)
    }
}
