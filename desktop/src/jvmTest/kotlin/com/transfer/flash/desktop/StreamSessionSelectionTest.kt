package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RVW22 F17: a stream for a named peer rides that peer's session or none, never another peer's. */
class StreamSessionSelectionTest {
    private val active = mapOf(FlashDeviceId("peer-b") to "session-b")

    @Test
    fun aNamedPeerWithoutASessionGetsNoSession() {
        assertNull(selectStreamSession(active, "peer-a"))
    }

    @Test
    fun aNamedPeerWithASessionGetsItsOwn() {
        assertEquals("session-b", selectStreamSession(active, "peer-b"))
    }

    @Test
    fun anUnnamedChannelMayTakeTheFirstSession() {
        assertEquals("session-b", selectStreamSession(active, null))
        assertNull(selectStreamSession(emptyMap<FlashDeviceId, String>(), null))
    }
}
