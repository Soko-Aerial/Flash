@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.messaging.model.FlashHubNoticeKind
import com.transfer.flash.core.security.enterprise.HubSealingPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ADR-126: which one calm notice a conversation shows about the hub path. */
class HubNoticeMathTest {

    private fun direct(
        online: Boolean = false,
        waiting: HubWaitReason? = null,
        changed: HubSealingPin? = null,
    ) = HubNoticeMath.notice("bob", false, false, false, "Bob", online, waiting, changed)

    private val changedPin = HubSealingPin(pinned = "aaaabbbbccccddddeeee", pending = "1111222233334444ffff")

    @Test
    fun `a changed key shows both short codes even while the colleague is online`() {
        val n = assertNotNull(direct(online = true, changed = changedPin, waiting = HubWaitReason.KEY_CHANGED))
        assertEquals(FlashHubNoticeKind.KEY_CHANGED, n.kind)
        assertEquals("1111 2222 3333 4444", n.newCode)
        assertEquals("aaaa bbbb cccc dddd", n.oldCode)
        assertEquals("Bob", n.peerName)
    }

    @Test
    fun `a confirmed pin shows no key notice`() {
        assertNull(direct(changed = HubSealingPin(pinned = "aaaa")))
    }

    @Test
    fun `waiting for an update shows only while the colleague is not reachable another way`() {
        assertEquals(FlashHubNoticeKind.PEER_NEEDS_UPDATE, direct(waiting = HubWaitReason.NEEDS_UPDATE)?.kind)
        assertNull(direct(online = true, waiting = HubWaitReason.NEEDS_UPDATE))
        assertEquals(FlashHubNoticeKind.HUB_SWITCHED_OFF, direct(waiting = HubWaitReason.SWITCHED_OFF)?.kind)
    }

    @Test
    fun `nothing to say means no notice`() {
        assertNull(direct())
    }

    @Test
    fun `a hub linked group says truthfully how its messages travel`() {
        val on = HubNoticeMath.notice("g-1", true, true, true, "", false, null, null)
        val off = HubNoticeMath.notice("g-1", true, true, false, "", false, null, null)
        assertEquals(FlashHubNoticeKind.GROUP_READABLE, on?.kind)
        assertEquals(FlashHubNoticeKind.GROUP_HUB_OFF, off?.kind)
    }

    @Test
    fun `a group that is not linked to the hub shows nothing and a sealing key change never leaks into a group`() {
        assertNull(HubNoticeMath.notice("g-1", true, false, true, "", false, null, null))
        assertNull(HubNoticeMath.notice("g-1", true, false, true, "", false, HubWaitReason.KEY_CHANGED, changedPin))
    }
}
