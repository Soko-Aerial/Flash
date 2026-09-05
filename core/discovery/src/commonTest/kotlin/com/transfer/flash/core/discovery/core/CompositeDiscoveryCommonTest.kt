package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.time.SystemTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two `CompositeDiscovery` rewrites that Phase 08 had to make in order to move the class to
 * `commonMain`, asserted on **each** target.
 *
 * Both were claimed to be identities; this suite is what checks the claim where it can be checked
 * cheaply, on every platform rather than only on the Android host JVM:
 *
 * - `transportName.uppercase(Locale.ROOT)` → `transportName.uppercase()`. The no-argument overload
 *   (Kotlin 1.5+) is the locale-independent one and on JVM compiles to exactly the former, so
 *   transport-name matching must stay case-insensitive for every known name.
 * - `{ System.currentTimeMillis() }` → `{ SystemTimeSource.nowMs() }`, which routes through
 *   `:core:common`'s Phase 06 `currentTimeMillisPlatform()` seam. Asserted here directly on
 *   `SystemTimeSource`, because `CompositeDiscovery` exposes no way to read its `clock` back; that
 *   covers the seam and the wall-clock contract, not the wiring of the default argument itself,
 *   which is compile-visible in `CompositeDiscovery`'s constructor.
 *
 * The 19 behavioural `CompositeDiscoveryTest` cases stay in `androidHostTest` unchanged.
 */
class CompositeDiscoveryCommonTest {

    @Test
    fun priorityRank_is_case_insensitive_for_every_known_transport() {
        CompositeDiscovery.PRIORITY_ORDER.forEachIndexed { expectedRank, name ->
            assertEquals(expectedRank, CompositeDiscovery.priorityRank(name))
            assertEquals(expectedRank, CompositeDiscovery.priorityRank(name.lowercase()))
            assertEquals(
                expectedRank,
                CompositeDiscovery.priorityRank(mixedCase(name)),
                "rank of $name must not depend on case",
            )
        }
    }

    @Test
    fun priorityRank_ranks_unknown_transports_last() {
        val last = CompositeDiscovery.PRIORITY_ORDER.size
        assertEquals(last, CompositeDiscovery.priorityRank("ZIGBEE"))
        assertEquals(last, CompositeDiscovery.priorityRank(""))
        assertEquals(last, CompositeDiscovery.priorityRank("lan "))
    }

    @Test
    fun default_clock_source_reads_a_plausible_wall_clock() {
        // 2023-11-14T22:13:20Z: any target whose clock predates that is not returning epoch
        // millis, which the 30 s presence grace window depends on.
        assertTrue(
            SystemTimeSource.nowMs() > 1_700_000_000_000L,
            "SystemTimeSource.nowMs() must return epoch millis",
        )
    }

    @Test
    fun default_clock_source_is_non_decreasing() {
        val first = SystemTimeSource.nowMs()
        val second = SystemTimeSource.nowMs()
        assertTrue(second >= first, "clock went backwards: $first then $second")
    }

    private fun mixedCase(name: String): String =
        name.mapIndexed { index, c -> if (index % 2 == 0) c.lowercaseChar() else c }.joinToString("")
}
