package com.transfer.flash.core.common

import com.transfer.flash.core.common.perf.ByteArrayPool
import kotlin.test.Test
import kotlin.test.assertTrue

class ByteArrayPoolWipeTest {

    @Test
    fun aReleasedBufferIsZeroWhenTheNextCallerGetsIt() {
        // SEC-D04-03
        ByteArrayPool.clear()
        val first = ByteArrayPool.acquire(1000)
        first.fill(0x5A)
        ByteArrayPool.release(first)
        val second = ByteArrayPool.acquire(1000)
        assertTrue(second.all { it == 0.toByte() }, "a pooled buffer must not carry the previous caller's bytes")
        ByteArrayPool.release(second)
    }
}
