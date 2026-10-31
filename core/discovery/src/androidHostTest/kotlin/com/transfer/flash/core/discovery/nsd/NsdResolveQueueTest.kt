package com.transfer.flash.core.discovery.nsd

import org.junit.Assert.assertEquals
import org.junit.Test

class NsdResolveQueueTest {

    @Test
    fun `default timeout is 2000ms`() {
        assertEquals(2_000L, NsdResolveQueue.RESOLVE_TIMEOUT_MS)
    }
}
