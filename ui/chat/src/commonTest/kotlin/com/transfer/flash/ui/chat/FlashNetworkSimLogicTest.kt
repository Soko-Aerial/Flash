package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashNetworkSimLogicTest {

    // --- Cycle helpers ---

    @Test
    fun `nextHealth cycles through all states in declaration order`() {
        val expectedCycle = listOf(
            FlashConnectionHealth.Connected to FlashConnectionHealth.Connecting,
            FlashConnectionHealth.Connecting to FlashConnectionHealth.Degraded,
            FlashConnectionHealth.Degraded to FlashConnectionHealth.Offline,
            FlashConnectionHealth.Offline to FlashConnectionHealth.Connected,
        )
        expectedCycle.forEach { (current, next) ->
            assertEquals(next, FlashNetworkSimMath.nextHealth(current))
        }
    }

    @Test
    fun `nextHealth never returns the input for any state`() {
        FlashConnectionHealth.entries.forEach { health ->
            assertTrue(
                FlashNetworkSimMath.nextHealth(health) != health,
                "nextHealth($health) must differ from $health",
            )
        }
    }

    @Test
    fun `healthFromIndex wraps positive overflow`() {
        val size = FlashConnectionHealth.entries.size
        assertEquals(
            FlashNetworkSimMath.healthFromIndex(0),
            FlashNetworkSimMath.healthFromIndex(size),
        )
        assertEquals(
            FlashNetworkSimMath.healthFromIndex(1),
            FlashNetworkSimMath.healthFromIndex(size + 1),
        )
    }

    @Test
    fun `healthFromIndex wraps negative indices`() {
        val size = FlashConnectionHealth.entries.size
        assertEquals(
            FlashNetworkSimMath.healthFromIndex(size - 1),
            FlashNetworkSimMath.healthFromIndex(-1),
        )
        assertEquals(
            FlashNetworkSimMath.healthFromIndex(0),
            FlashNetworkSimMath.healthFromIndex(-size),
        )
    }

    @Test
    fun `healthFromIndex covers every state across one cycle`() {
        val size = FlashConnectionHealth.entries.size
        val visited = (0 until size).map { FlashNetworkSimMath.healthFromIndex(it) }
        assertEquals(FlashConnectionHealth.entries.toList(), visited)
    }

    @Test
    fun `simulatableStates match enum declaration order`() {
        assertEquals(
            FlashConnectionHealth.entries.toList(),
            FlashNetworkSimMath.simulatableStates,
        )
    }

    // --- Copy ---

    @Test
    fun `sim labels follow Force prefix convention`() {
        val expected = mapOf(
            FlashConnectionHealth.Connected to "Force Connected",
            FlashConnectionHealth.Connecting to "Force Connecting",
            FlashConnectionHealth.Degraded to "Force Degraded",
            FlashConnectionHealth.Offline to "Force Offline",
        )
        expected.forEach { (health, label) ->
            assertEquals(label, FlashNetworkSimMath.simLabel(health))
        }
    }

    @Test
    fun `sim labels are distinct and non-blank`() {
        val labels = FlashConnectionHealth.entries.map { FlashNetworkSimMath.simLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
        labels.forEach { label ->
            assertTrue(label.isNotBlank())
        }
    }
}
