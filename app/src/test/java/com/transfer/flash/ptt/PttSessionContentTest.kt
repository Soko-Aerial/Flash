package com.transfer.flash.ptt

import org.junit.Assert.assertEquals
import org.junit.Test

class PttSessionContentTest {

    @Test
    fun `talker shows listeners and rtt`() {
        assertEquals(
            PttNotificationContent("PTT — You're talking", "Live • 2 listening • 42 ms"),
            pttSessionContent(
                talking = true,
                peerName = null,
                memberCount = 2,
                rttMs = 42L,
                lossPercent = 0f,
            ),
        )
    }

    @Test
    fun `talker without peers or rtt stays minimal`() {
        assertEquals(
            PttNotificationContent("PTT — You're talking", "Live"),
            pttSessionContent(
                talking = true,
                peerName = null,
                memberCount = 0,
                rttMs = null,
                lossPercent = 0f,
            ),
        )
    }

    @Test
    fun `listener shows holder loss and rtt`() {
        assertEquals(
            PttNotificationContent("PTT — Alex", "Live • 1% loss • 87 ms"),
            pttSessionContent(
                talking = false,
                peerName = "Alex",
                memberCount = 1,
                rttMs = 87L,
                lossPercent = 0.013f,
            ),
        )
    }

    @Test
    fun `listener without name or rtt falls back`() {
        assertEquals(
            PttNotificationContent("PTT — Peer", "Live • 0% loss"),
            pttSessionContent(
                talking = false,
                peerName = "  ",
                memberCount = 1,
                rttMs = null,
                lossPercent = 0f,
            ),
        )
    }
}
