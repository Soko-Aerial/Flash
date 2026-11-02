package com.transfer.flash.core.radio.link

import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** RVW22 F22: a quiet link reads 0, a dead one reads -1 (it used to read 0 for ever). */
class BlockingStreamReaderTest {
    private val buffer = ByteArray(16)

    // Real threads and real time: the pump is a thread, so virtual time cannot drive it.
    private fun io(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.Default) { block() } }

    @Test
    fun `a quiet stream reads zero after the timeout`() = io {
        val reader = BlockingStreamReader(PipedInputStream(PipedOutputStream()))
        assertEquals(0, reader.read(buffer, 50))
    }

    @Test
    fun `bytes arrive in order and a short buffer takes them in pieces`() = io {
        val out = PipedOutputStream()
        val reader = BlockingStreamReader(PipedInputStream(out))
        out.write(byteArrayOf(1, 2, 3, 4, 5))
        out.flush()

        val small = ByteArray(2)
        assertEquals(2, reader.read(small, 1_000))
        assertContentEquals(byteArrayOf(1, 2), small)
        assertEquals(2, reader.read(small, 1_000))
        assertContentEquals(byteArrayOf(3, 4), small)
        assertEquals(1, reader.read(small, 1_000))
        assertEquals(5, small[0].toInt())
    }

    @Test
    fun `the end of the stream is reported as minus one, not as silence`() = io {
        val out = PipedOutputStream()
        val reader = BlockingStreamReader(PipedInputStream(out))
        out.write(7)
        out.close()

        assertEquals(1, reader.read(buffer, 1_000))
        assertEquals(-1, reader.read(buffer, 1_000))
        assertEquals(-1, reader.read(buffer, 1_000), "and it stays ended")
    }

    @Test
    fun `a failed read is thrown to the caller`() = io {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("socket closed")
            override fun read(b: ByteArray): Int = throw IOException("socket closed")
        }
        val reader = BlockingStreamReader(broken)
        assertFailsWith<IOException> { reader.read(buffer, 1_000) }
    }

    @Test
    fun `closing the reader releases a pump parked on a full chunk queue`() = io {
        // OPT-O26-03: the chunk queue is bounded, so a pump whose consumer stopped parks in the hand-over. Closing the
        // *socket* would not wake it; close() must, or every restart of the link leaks a thread (ADR-331).
        val out = PipedOutputStream()
        val reader = BlockingStreamReader(PipedInputStream(out, 256 * 1024), threadName = "rfcomm-rx-close-test")
        out.write(ByteArray(64 * 1024) { 7 })
        out.flush()
        reader.read(ByteArray(1), 1_000) // starts the pump
        // The pump is now far ahead of a consumer that never drains: 32 chunks fit, the rest parks it.
        withTimeout(2_000) {
            while (Thread.getAllStackTraces().keys.none { it.name == "rfcomm-rx-close-test" }) delay(10)
        }
        reader.close()
        val deadline = System.nanoTime() + 5_000_000_000L
        var alive = Thread.getAllStackTraces().keys.filter { it.name == "rfcomm-rx-close-test" && it.isAlive }
        while (alive.isNotEmpty() && System.nanoTime() < deadline) {
            delay(20)
            alive = Thread.getAllStackTraces().keys.filter { it.name == "rfcomm-rx-close-test" && it.isAlive }
        }
        out.close()
        val where = alive.joinToString("\n") { t -> t.stackTrace.take(6).joinToString("\n") { "  at $it" } }
        assertTrue(alive.isEmpty(), "the pump thread must end after close(); it was parked at:\n$where")
    }
}
