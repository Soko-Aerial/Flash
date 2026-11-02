package com.transfer.flash.core.radio.wire

import com.transfer.flash.core.radio.kiss.KissStreamDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Allocation/churn measurements for the radio framing hot path (`OPT-O26-04`).
 *
 * This is a **measurement harness**, not an assertion of a performance target: the numbers are printed as
 * `PERF-R` lines so a forced run (`--rerun-tasks --no-build-cache`) produces a reproducible before/after
 * figure for `logs/streams/radio/experiments.md` (EXP-131). The only assertions are sanity checks on the
 * *behaviour* (same bytes, same frames), so the test cannot flake under CPU load.
 *
 * Allocation is read from `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()`, which counts
 * bytes that this thread allocated inside the JVM (not native serial buffers). It is a JVM number, not a
 * device measurement.
 */
class RadioAllocationBenchTest {

    private val threadMx = java.lang.management.ManagementFactory.getThreadMXBean()

    private fun allocatedBytes(): Long =
        (threadMx as? com.sun.management.ThreadMXBean)?.currentThreadAllocatedBytes ?: -1L

    /** Runs [block] [iterations] times and returns (bytes allocated, elapsed ns). */
    private inline fun measure(iterations: Int, block: (Int) -> Unit): Pair<Long, Long> {
        // Warm up the JIT and any lazily created JCA/provider state outside the measured window.
        repeat(minOf(iterations / 10 + 1, 20_000)) { block(it) }
        val before = allocatedBytes()
        val t0 = System.nanoTime()
        repeat(iterations) { block(it) }
        val elapsed = System.nanoTime() - t0
        val used = allocatedBytes() - before
        return used to elapsed
    }

    private fun report(name: String, iterations: Int, bytes: Long, nanos: Long) {
        val perOp = if (iterations == 0) 0.0 else bytes.toDouble() / iterations
        val nsPerOp = if (iterations == 0) 0.0 else nanos.toDouble() / iterations
        println("PERF-R O26-04 $name iterations=$iterations allocatedBytes=$bytes bytesPerOp=${"%.1f".format(perOp)} nsPerOp=${"%.1f".format(nsPerOp)}")
    }

    @Test
    fun authenticatedBytesAllocationPerCall() {
        val header = RadioHeader(
            kind = RadioKind.TEXT,
            flags = RadioWire.FLAG_SEGMENTED,
            ttl = 3,
            tag = byteArrayOf(0x11, 0x22, 0x33, 0x44),
            counter = 0x01020304L,
        )
        var sink = 0
        val (bytes, nanos) = measure(200_000) { sink = header.authenticatedBytes().size }
        report("authenticatedBytes", 200_000, bytes, nanos)
        // Behaviour: still the same 25 bytes as `label || header[0..2] || header[4..11]`.
        val aad = header.authenticatedBytes()
        assertEquals(25, aad.size)
        assertEquals(25, sink)
        assertEquals("flash-radio-v1".encodeToByteArray().toList(), aad.copyOfRange(0, 14).toList())
    }

    @Test
    fun kissDecoderAllocationOnANoisyLine() {
        val random = kotlin.random.Random(7)
        val noisy = ByteArray(64 * 1024) { random.nextInt(256).toByte() }
        val decoder = KissStreamDecoder()
        var frames = 0
        val (bytes, nanos) = measure(16) { _ ->
            decoder.reset()
            var at = 0
            while (at < noisy.size) {
                val n = minOf(256, noisy.size - at)
                frames += decoder.feed(noisy, at, n).size
                at += n
            }
        }
        // 16 * 64 KiB = 1 MiB of noisy input.
        report("kissDecoder(1MiB noisy, frames complete)", 16, bytes, nanos)
        assertTrue(frames > 0, "random bytes contain FEND, so some frames complete")
        assertTrue(decoder.statistics.bytesIn > 0)
    }

    @Test
    fun kissDecoderAllocationBeforeFirstFrameEnd() {
        // The case the audit describes: a floating or unsynchronised serial line, where every byte is
        // "garbage" until the first FEND arrives. Before the fix each of those bytes allocated a
        // KissDecoderStats. Bytes are kept out of {C0, DB} on purpose, so no frame ever starts.
        val random = kotlin.random.Random(11)
        val noise = ByteArray(64 * 1024) { random.nextInt(0xC0).toByte() }
        val decoder = KissStreamDecoder()
        val (bytes, nanos) = measure(16) { _ ->
            decoder.reset()
            decoder.feed(noise)
        }
        report("kissDecoder(1MiB unsynced noise)", 16, bytes, nanos)
        assertTrue(decoder.statistics.garbageBytes > 0, "unsynced bytes are counted as garbage")
    }

    @Test
    fun streamDecoderAllocationWithOneByteChunks() {
        // Worst case for the old `buf += chunk` decoder: a whole message arriving one byte at a time
        // re-copied the buffer on every byte.
        val payload = ByteArray(8 * 1024) { (it * 7).toByte() }
        val framed = StreamFramer.encode(payload)
        var length = 0
        val (bytes, nanos) = measure(16) { _ ->
            val decoder = StreamFrameDecoder()
            var got = 0
            for (b in framed) got += decoder.feed(byteArrayOf(b)).singleOrNull()?.size ?: 0
            length = got
        }
        report("streamDecoder(8KiB, 1-byte chunks)", 16, bytes, nanos)
        assertEquals(payload.size, length, "the reassembled payload is unchanged")
    }
}
