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
    fun `a radio-only peer keeps the ring shape in its own colour`() {
        // ADR-228: shape still means "no session"; the separate style exists so the dot is not drawn in the
        // Wi-Fi sighting's colour and so a screen reader says "Radio only", not "Online".
        assertEquals(FlashPresenceDotStyle.RingRadio, FlashPresenceDotMath.style(FlashPeerPresence.RadioOnly))
    }

    @Test
    fun `descriptions use the owner's names`() {
        assertEquals("Connected", FlashPresenceDotMath.description(FlashPresenceDotStyle.Solid))
        assertEquals("Online", FlashPresenceDotMath.description(FlashPresenceDotStyle.Ring))
        assertEquals("Radio only", FlashPresenceDotMath.description(FlashPresenceDotStyle.RingRadio))
        assertNull(FlashPresenceDotMath.description(FlashPresenceDotStyle.None))
    }

    @Test
    fun `reachable health is calm, non-blocking and says a send will connect`() {
        assertEquals(FlashNetworkBannerSeverity.Calm, FlashNetworkStatusMath.bannerSeverity(FlashConnectionHealth.Reachable))
        assertEquals(false, FlashNetworkStatusMath.isBlockingState(FlashConnectionHealth.Reachable))
        assertEquals("Online · connects when you send", FlashNetworkStatusMath.healthLabel(FlashConnectionHealth.Reachable))
    }
}
