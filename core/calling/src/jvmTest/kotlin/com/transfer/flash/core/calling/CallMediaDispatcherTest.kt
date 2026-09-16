package com.transfer.flash.core.calling

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Pins the WASAPI/COM thread-affinity contract: [callMediaDispatcher] must execute everything
 * on ONE OS thread for the process lifetime. If this ever runs on a pool, every native WebRTC
 * call in both sessions escapes the pin and the silent buzz/zeros failure mode returns.
 *
 * JVM-only: the Android actual is deliberately the shared pool (its native engine owns its
 * audio threads), so this assertion lives here, not in commonTest.
 */
class CallMediaDispatcherTest {

    @Test
    fun `media dispatcher runs everything on a single dedicated thread`() = runBlocking {
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
        coroutineScope {
            repeat(32) {
                launch(callMediaDispatcher) {
                    threads += Thread.currentThread()
                    delay(1)
                }
            }
        }
        assertEquals(1, threads.size, "media work escaped to ${threads.size} threads")
        assertTrue(
            threads.single().name.startsWith("flash-call-media"),
            "unexpected media thread: ${threads.single().name}",
        )
        assertTrue(threads.single().isDaemon, "media thread must not hold the JVM open")
    }
}
