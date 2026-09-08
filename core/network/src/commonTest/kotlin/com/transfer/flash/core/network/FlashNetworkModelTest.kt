package com.transfer.flash.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Moved to `commonTest` in Phase 15-1. [FlashNetworkState] and [FlashConnectionState] have been
 * `commonMain` since Phase 10, so the desktop `jvm()` target compiled them without ever running an
 * assertion against them. The only edit is the JUnit 4 → `kotlin.test` import swap: no assertion
 * here carries a message, so no argument order moved.
 */
class FlashNetworkModelTest {

    @Test
    fun flashNetworkState_defaultsAreIdle() {
        val state = FlashNetworkState()
        assertFalse(state.isRunning)
        assertEquals(0, state.localPort)
        assertEquals(emptyList<String>(), state.localAddresses)
        assertEquals(0, state.activePeerCount)
    }

    @Test
    fun flashConnectionState_allStatesExist() {
        val states = FlashConnectionState.values()
        assertEquals(5, states.size)
        assertEquals(FlashConnectionState.Connecting, states[0])
        assertEquals(FlashConnectionState.Connected, states[1])
        assertEquals(FlashConnectionState.Disconnecting, states[2])
        assertEquals(FlashConnectionState.Disconnected, states[3])
        assertEquals(FlashConnectionState.Failed, states[4])
    }
}
