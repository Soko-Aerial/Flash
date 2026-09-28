package com.transfer.flash.ui.chat

import com.transfer.flash.core.common.model.FlashPeerPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** UI-030b: which presence draws which dot, and what a screen reader hears. */
class FlashPresenceDotLogicTest {

    @Test
    fun `solid for a live session, ring for seen-without-session, nothing otherwise`() {
        assertEquals(FlashPresenceDotStyle.Solid, FlashPresenceDotMath.style(FlashPeerPresence.Online))
        assertEquals(FlashPresenceDotStyle.Solid, FlashPresenceDotMath.style(FlashPeerPresence.Typing))
        assertEquals(FlashPresenceDotStyle.Ring, FlashPresenceDotMath.style(FlashPeerPresence.Reachable))
        assertEquals(FlashPresenceDotStyle.None, FlashPresenceDotMath.style(FlashPeerPresence.Connecting))
        assertEquals(FlashPresenceDotStyle.None, FlashPresenceDotMath.style(FlashPeerPresence.Offline))
    }

    @Test
    fun `descriptions use the owner's names`() {
        assertEquals("Connected", FlashPresenceDotMath.description(FlashPresenceDotStyle.Solid))
        assertEquals("Online", FlashPresenceDotMath.description(FlashPresenceDotStyle.Ring))
        assertNull(FlashPresenceDotMath.description(FlashPresenceDotStyle.None))
    }

    @Test
    fun `reachable health is calm, non-blocking and says a send will connect`() {
        assertEquals(FlashNetworkBannerSeverity.Calm, FlashNetworkStatusMath.bannerSeverity(FlashConnectionHealth.Reachable))
        assertEquals(false, FlashNetworkStatusMath.isBlockingState(FlashConnectionHealth.Reachable))
        assertEquals("Online · connects when you send", FlashNetworkStatusMath.healthLabel(FlashConnectionHealth.Reachable))
    }
}
