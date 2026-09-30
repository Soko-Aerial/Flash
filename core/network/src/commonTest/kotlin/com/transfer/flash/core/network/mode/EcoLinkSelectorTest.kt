package com.transfer.flash.core.network.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PC5: ECO's "who do I keep a session with" rules (plan §3.4). */
class EcoLinkSelectorTest {

    private val idle = LinkActivity(outbound = true, userIdleMs = ConnectionModePolicy.ECO_IDLE_PARK_MS)

    private fun view(
        available: Set<String>,
        contacts: Set<String> = available,
        activity: Map<String, LinkActivity> = emptyMap(),
        busy: Set<String> = emptySet(),
        nearbyOpen: Boolean = false,
    ) = LinkView(available, contacts, activity, busy, nearbyOpen)

    @Test
    fun `neighbours are the nearest successors and predecessors on the sorted ring`() {
        val ids = listOf("a", "b", "c", "d", "e", "f", "g")
        assertEquals(setOf("e", "c", "f"), EcoLinkSelector.neighbours("d", ids))
        // Wraps around both ends.
        assertEquals(setOf("b", "g", "c"), EcoLinkSelector.neighbours("a", ids))
        assertEquals(setOf("a", "f", "b"), EcoLinkSelector.neighbours("g", ids))
    }

    @Test
    fun `a small ring gives everyone it has, never yourself`() {
        assertEquals(setOf("b"), EcoLinkSelector.neighbours("a", listOf("b")))
        assertEquals(setOf("a", "c"), EcoLinkSelector.neighbours("b", listOf("a", "c", "b")))
        assertEquals(emptySet(), EcoLinkSelector.neighbours("a", emptyList()))
    }

    @Test
    fun `every phone picking its own neighbours keeps a group joined up`() {
        for (n in 2..24) {
            val ids = (1..n).map { "peer-" + it.toString().padStart(2, '0') }
            val edges = ids.associateWith { EcoLinkSelector.neighbours(it, ids - it).toMutableSet() }
            // Sessions are two-way: a peer that dials me is my link too.
            edges.forEach { (id, ns) -> ns.forEach { edges.getValue(it) += id } }
            val reached = mutableSetOf(ids.first())
            val todo = ArrayDeque(reached)
            while (todo.isNotEmpty()) edges.getValue(todo.removeFirst()).forEach { if (reached.add(it)) todo += it }
            assertEquals(ids.toSet(), reached, "group of $n")
            // And nobody dials more than three.
            ids.forEach { assertTrue(EcoLinkSelector.neighbours(it, ids - it).size <= 3) }
        }
    }

    @Test
    fun `wanted is neighbours plus active, busy, and unpaired only while Nearby is open`() {
        val contacts = setOf("b", "c", "d", "e", "f", "g")
        val available = contacts + "stranger"
        val v = view(
            available = available,
            contacts = contacts,
            activity = mapOf(
                "g" to LinkActivity(outbound = false, userIdleMs = 60_000L),
                "f" to idle,
            ),
            busy = setOf("x-in-call"),
        )
        // Ring a..g: neighbours b, g, c; g is also active; f is idle; plus the peer in a call.
        assertEquals(setOf("b", "g", "c", "x-in-call"), EcoLinkSelector.wanted("a", v))
        assertTrue("stranger" !in EcoLinkSelector.wanted("a", v))
        assertTrue("f" !in EcoLinkSelector.wanted("a", v), "idle for 10 min and not a neighbour")
        val nearby = view(available, contacts, busy = emptySet(), nearbyOpen = true)
        assertTrue("stranger" in EcoLinkSelector.wanted("a", nearby))
    }

    @Test
    fun `neighbours are picked among contacts that are available, so an absent one does not waste a slot`() {
        val v = view(available = setOf("c", "d"), contacts = setOf("b", "c", "d"))
        assertEquals(setOf("c", "d"), EcoLinkSelector.wanted("a", v))
    }

    @Test
    fun `a group nobody in it is paired with still gets its ring neighbours from the roster`() {
        // ADR-044 V2: the host builds contacts as paired peers plus every active group roster, so a vouched member
        // is a contact even when this device never paired it. Twenty of them, all unpaired, still form a ring.
        val roster = (1..20).map { "m" + it.toString().padStart(2, '0') }
        val v = view(available = roster.toSet() - "m01", contacts = roster.toSet())

        val wanted = EcoLinkSelector.wanted("m01", v)

        assertEquals(EcoLinkSelector.neighbours("m01", roster - "m01"), wanted)
        assertEquals(ConnectionModePolicy.ECO_NEIGHBOURS, wanted.size)
    }

    @Test
    fun `only idle, unwanted sessions this side dialed are parked`() {
        val contacts = (1..8).map { "p$it" }.toSet()
        val activity = mapOf(
            "p5" to idle,
            "p6" to idle.copy(outbound = false),
            "p7" to idle.copy(userIdleMs = ConnectionModePolicy.ECO_IDLE_PARK_MS - 1),
        )
        val v = view(available = contacts, contacts = contacts, activity = activity)
        val neighbours = EcoLinkSelector.neighbours("p0", contacts)
        assertTrue("p5" !in neighbours)
        assertEquals(setOf("p5"), EcoLinkSelector.parkCandidates("p0", v))
        // A neighbour is never parked, however idle.
        val n = neighbours.first()
        assertEquals(emptySet(), EcoLinkSelector.parkCandidates("p0", view(contacts, contacts, mapOf(n to idle))))
    }
}
