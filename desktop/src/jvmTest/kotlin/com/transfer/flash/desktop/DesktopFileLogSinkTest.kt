@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.logging.FlashLogLevel
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringWriter
import java.io.Writer
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFileLogSinkTest {
    private class BlockingOutput : Writer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val text = StringWriter()
        val closed = AtomicBoolean(false)
        val owner = AtomicReference<Thread?>()
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val first = owner.compareAndSet(null, Thread.currentThread())
            assertEquals(owner.get(), Thread.currentThread(), "only the worker writes")
            assertFalse(closed.get(), "no write after close")
            if (first) {
                entered.countDown()
                check(release.await(10L, TimeUnit.SECONDS)) { "blocked test writer was not released" }
            }
            text.write(buffer, offset, length)
        }
        override fun flush() {
            assertEquals(owner.get(), Thread.currentThread(), "only the worker flushes")
        }
        override fun close() {
            assertEquals(owner.get(), Thread.currentThread(), "only the worker closes")
            closed.set(true)
        }
    }

    @Test
    fun `shutdown waits for dequeued entry and only worker closes output`() {
        val output = BlockingOutput()
        val consoleBytes = ByteArrayOutputStream()
        val sink = DesktopFileLogSink(output, PrintStream(consoleBytes), clock = { LocalTime.of(1, 2, 3) })
        val ended = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        try {
            sink.write(FlashLogLevel.INFO, "WS", "first", null)
            assertTrue(output.entered.await(10L, TimeUnit.SECONDS))
            sink.write(FlashLogLevel.ERROR, "TRANSFER", "tail", IllegalStateException("test-failure"))
            val closer = Thread {
                try { assertTrue(sink.closeAndAwait(10_000L)) } catch (t: Throwable) { failure.set(t) }
                finally { ended.countDown() }
            }
            closer.start()
            try {
                assertFalse(ended.await(100L, TimeUnit.MILLISECONDS), "shutdown must wait for the in-flight write")
                assertFalse(output.closed.get())
            } finally {
                output.release.countDown()
                closer.join(10_000L)
            }
            assertTrue(ended.await(10L, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue(output.closed.get())
            val text = output.text.toString()
            assertTrue(text.indexOf("I/WS: first") < text.indexOf("E/TRANSFER: tail"))
            assertTrue(text.contains("IllegalStateException: test-failure"))
            assertTrue(consoleBytes.toString().contains("E/TRANSFER: tail"))
        } finally {
            output.release.countDown()
            sink.close()
        }
    }

    @Test
    fun `overflow does not become a producer write and reports dropped count`() {
        val output = BlockingOutput()
        val sink = DesktopFileLogSink(output, PrintStream(ByteArrayOutputStream()), queueCapacity = 1)
        try {
            sink.write(FlashLogLevel.INFO, "T", "first", null)
            assertTrue(output.entered.await(10L, TimeUnit.SECONDS))
            sink.write(FlashLogLevel.INFO, "T", "accepted-tail", null)
            sink.write(FlashLogLevel.INFO, "T", "dropped-tail", null)
            output.release.countDown()
            assertTrue(sink.closeAndAwait(10_000L))
            val text = output.text.toString()
            assertTrue(text.contains("accepted-tail"))
            assertFalse(text.contains("I/T: dropped-tail"))
            assertTrue(text.contains("1 line(s) dropped (desktop log queue was full)"))
        } finally {
            output.release.countDown()
            sink.close()
        }
    }

    @Test
    fun `a close wait timeout does not close output under a blocked worker`() {
        val output = BlockingOutput()
        val sink = DesktopFileLogSink(output, PrintStream(ByteArrayOutputStream()))
        try {
            sink.write(FlashLogLevel.INFO, "T", "blocked", null)
            assertTrue(output.entered.await(10L, TimeUnit.SECONDS))
            assertFalse(sink.closeAndAwait(1L))
            assertFalse(output.closed.get())
            output.release.countDown()
            assertTrue(sink.closeAndAwait(10_000L))
        } finally {
            output.release.countDown()
            sink.close()
        }
    }
}
