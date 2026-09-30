package com.transfer.flash.core.engine.group

import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.network.mode.ConnectionModePolicy
import com.transfer.flash.core.network.resilience.SessionHardeningPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-044 V2 (plan E4): a group of [GroupPolicy.MAX_MEMBERS_V2] is a full mesh, so every member holds a session with
 * every other one. `core:messaging` and `core:network` cannot see each other; this module sees both, so it is where
 * the two limits are pinned together. If either moves, this fails before a 20-member group can starve itself.
 */
class GroupSizeBudgetTest {

    @Test
    fun `every other member of a full group fits the dial budget`() {
        assertTrue(
            GroupPolicy.MAX_MEMBERS_V2 - 1 <= ConnectionModePolicy.DIAL_BUDGET,
            "a ${GroupPolicy.MAX_MEMBERS_V2}-member group needs ${GroupPolicy.MAX_MEMBERS_V2 - 1} sessions, " +
                "the dial budget is ${ConnectionModePolicy.DIAL_BUDGET}",
        )
    }

    @Test
    fun `a full group still leaves headroom under the session ceiling for peers that dial in`() {
        val left = SessionHardeningPolicy.DEFAULT_MAX_CONCURRENT_SESSIONS - (GroupPolicy.MAX_MEMBERS_V2 - 1)
        assertTrue(left >= 1, "the ceiling leaves $left sessions beyond one group")
    }

    @Test
    fun `the number the plan states is the number the code has`() {
        assertEquals(20, GroupPolicy.MAX_MEMBERS_V2)
        assertEquals(6, GroupPolicy.MAX_MEMBERS, "legacy groups keep the limit every shipped codec enforces")
        assertEquals(GroupPolicy.MAX_MEMBERS_V2 + GroupPolicy.MAX_BUNDLE_TOMBSTONES, GroupPolicy.MAX_BUNDLE_CERTS)
    }
}
