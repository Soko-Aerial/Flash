package com.transfer.flash.core.ptt.protocol

import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPT-O12-02: what one 20 ms PTT audio frame costs to encode and decode, in bytes allocated on the calling thread.
 *
 * The finding's arithmetic says ~11–16 allocations per packet, two of them pure waste: the session id is turned into a
 * fresh UTF-8 `ByteArray` on **every** encode and back into a fresh `String` on **every** decode, although the session
 * is fixed for the whole burst (50 packets/s, so 3 000 of each per minute of talking).
 *
 * Both paths are measured in the same run — [encodeFrame] + [decodeFrame] is the 1:1 API, [encodeCached] +
 * [decodeExpected] is the cached one the engine now uses — so the comparison does not depend on the machine. Numbers
 * are `ThreadMXBean.getThreadAllocatedBytes`: allocator accounting, not wall clock, so a loaded PC does not move them
 * (only a different JVM or a different GC would).
 */
class PttAudioFrameAllocationTest {

    private val sessionId = "session-0123456789abcdef" // 24 chars, like the UUID minus dashes
    private val sessionBytes = sessionId.encodeToByteArray()
    private val pcm = ByteArray(PCM_BYTES) { (it % 97).toByte() }

    private fun allocatedBytes(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        // The counter is per thread and monotonic; two reads around the loop, and the JIT has already run this
        // block once during the warm-up so the measurement is not the first-compilation cost.
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun encodingWithTheCachedSessionBytesAllocatesLessThanTheStringApi() {
        // Warm up both paths (JIT, first-use classes) before measuring.
        repeat(WARMUP) {
            PttAudioFrame.encode(PttAudioFrame(sessionId, it.toLong(), it.toLong(), pcm))
            PttAudioFrame.encode(sessionBytes, it.toLong(), it.toLong(), pcm)
        }
        val legacy = allocatedBytes {
            repeat(PACKETS) { i ->
                PttAudioFrame.encode(PttAudioFrame(sessionId, i.toLong(), i.toLong(), pcm))
            }
        }
        val cached = allocatedBytes {
            repeat(PACKETS) { i ->
                PttAudioFrame.encode(sessionBytes, i.toLong(), i.toLong(), pcm)
            }
        }
        println("PTT_ALLOC encode packets=$PACKETS legacy=${legacy}B cached=${cached}B")
        assertTrue("the cached path must allocate less (legacy=${legacy}B cached=${cached}B)", cached < legacy)
        // Arithmetic: the frame ByteArray itself (695 B here) is unavoidable; the session-id copy (24 B + header) is not.
        assertTrue(
            "cached encode should be at most the frame buffer plus a small wrapper (cached=${cached}B)",
            cached <= PACKETS * (FRAME_BYTES + 32L),
        )
    }

    @Test
    fun decodingWithTheCachedSessionBytesAllocatesLessThanTheStringApi() {
        val encoded = PttAudioFrame.encode(PttAudioFrame(sessionId, 7L, 7L, pcm))
        repeat(WARMUP) {
            PttAudioFrame.decode(encoded)
            PttAudioFrame.decodeExpected(encoded, sessionId, sessionBytes, PCM_BYTES)
        }
        val legacy = allocatedBytes {
            repeat(PACKETS) { PttAudioFrame.decode(encoded) }
        }
        val cached = allocatedBytes {
            repeat(PACKETS) { PttAudioFrame.decodeExpected(encoded, sessionId, sessionBytes, PCM_BYTES) }
        }
        println("PTT_ALLOC decode packets=$PACKETS legacy=${legacy}B cached=${cached}B")
        assertTrue("the cached path must allocate less (legacy=${legacy}B cached=${cached}B)", cached < legacy)
    }

    @Test
    fun theCachedPathsProduceAndAcceptExactlyTheSameBytes() {
        for (seq in longArrayOf(0L, 1L, 0xFFFF_FFFFL, 123_456_789L)) {
            val frame = PttAudioFrame(sessionId, seq, seq * 3, pcm)
            val legacy = PttAudioFrame.encode(frame)
            val cached = PttAudioFrame.encode(sessionBytes, seq, seq * 3, pcm)
            assertEquals("wire bytes must not change for seq=$seq", legacy.toList(), cached.toList())

            val decoded = PttAudioFrame.decodeExpected(cached, sessionId, sessionBytes, PCM_BYTES)
            assertNotNull(decoded)
            assertEquals(frame, decoded)
        }
    }

    @Test
    fun aFrameForAnotherSessionIsRejectedWithoutBeingDecoded() {
        val other = "session-ffffffffffffffff"
        val encoded = PttAudioFrame.encode(PttAudioFrame(other, 1L, 1L, pcm))
        assertNull(
            "the listener must not allocate a String to discover it is not the session it is listening to",
            PttAudioFrame.decodeExpected(encoded, sessionId, sessionBytes, PCM_BYTES),
        )
        assertNull(PttAudioFrame.decodeExpected(encoded, sessionId, sessionBytes, PCM_BYTES + 2))
        assertNull(PttAudioFrame.decodeExpected(ByteArray(8), sessionId, sessionBytes, PCM_BYTES))
    }

    private companion object {
        const val PACKETS = 1_000
        const val WARMUP = 200
        const val PCM_BYTES = 640
        /** magic 4 + version 1 + sessionLen 2 + sessionId 24 + seq 4 + captureTs 8 + PCM 640. */
        const val FRAME_BYTES = 4 + 1 + 2 + 24 + 4 + 8 + PCM_BYTES
    }
}
