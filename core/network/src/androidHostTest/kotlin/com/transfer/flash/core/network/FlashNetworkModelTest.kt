package com.transfer.flash.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
