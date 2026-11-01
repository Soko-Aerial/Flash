@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common

import com.transfer.flash.core.common.logging.FlashLogLevel
import com.transfer.flash.core.common.logging.FlashLogSink
import com.transfer.flash.core.common.logging.RotatingFileLogSink
import java.io.File
import java.nio.file.Files
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RotatingFileLogSinkTest {
    private lateinit var dir: File
    private val sinks = mutableListOf<RotatingFileLogSink>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("flashlog").toFile()
    }

    @After
    fun tearDown() {
        sinks.forEach { it.stop() }
        dir.deleteRecursively()
    }

    private fun sink(max: Long = 1_000_000L, keep: Int = 3, forward: FlashLogSink? = null, cap: Int = 4_096) =
        RotatingFileLogSink(dir, forward, max, keep, { 1_700_000_000_000L }, cap).also { sinks += it }

    private fun text(s: RotatingFileLogSink) = s.files().joinToString("") { it.readText() }

    @Test
    fun `lines reach the file with level tag and message and the forward sink`() {
        var forwarded = 0
        val s = sink(forward = FlashLogSink { _, _, _, _ -> forwarded++ })
        s.write(FlashLogLevel.WARN, "SWARM", "restored root=ab12", null)
        s.flush()
        assertTrue(text(s).contains("W/SWARM: restored root=ab12"))
        assertEquals(1, forwarded)
    }

    @Test
    fun `a throwable is written with its stack trace`() {
        val s = sink()
        s.write(FlashLogLevel.ERROR, "TRANSFER", "failed", IllegalStateException("boom"))
        s.flush()
        val t = text(s)
        assertTrue(t.contains("IllegalStateException: boom"))
        assertTrue(t.contains("RotatingFileLogSinkTest"))
    }

    @Test
    fun `secrets and invite links are redacted`() {
        val s = sink()
        s.write(FlashLogLevel.INFO, "PAIRING", "code accepted secret=hunter2 invite flash://g/1/abcdef", null)
        s.write(FlashLogLevel.INFO, "TLS", "key " + "A".repeat(80), null)
        s.flush()
        val t = text(s)
        assertFalse(t.contains("hunter2"))
        assertFalse(t.contains("abcdef"))
        assertFalse(t.contains("A".repeat(64)))
        assertTrue(t.contains("secret=<redacted>"))
        assertTrue(t.contains("flash://g/<redacted>"))
    }

    @Test
    fun `files rotate and total size stays bounded`() {
        val s = sink(max = 2_000L, keep = 3)
        repeat(400) { s.write(FlashLogLevel.INFO, "CHUNK", "line $it ${"x".repeat(60)}", null); if (it % 20 == 0) s.flush() }
        s.flush()
        val files = s.files()
        assertTrue("rotated into more than one file", files.size > 1)
        assertTrue("at most keep files", files.size <= 3)
        assertTrue("bounded", files.sumOf { it.length() } < 3 * 2_000L + 2_000L)
        assertTrue("newest line kept", text(s).contains("line 399"))
        assertFalse("oldest line gone", text(s).contains("line 0 "))
    }

    @Test
    fun `a full queue drops lines and says so instead of blocking`() {
        val s = sink(cap = 2)
        repeat(200) { s.write(FlashLogLevel.INFO, "T", "n$it", null) }
        s.flush()
        assertTrue(text(s).contains("dropped (log queue was full)") || text(s).contains("n199"))
    }

    @Test
    fun `a crash is written immediately`() {
        val s = sink()
        s.writeCrashNow("main", RuntimeException("fatal"))
        assertTrue(text(s).contains("E/CRASH") && text(s).contains("fatal"))
    }

    @Test
    fun `flush waits for an entry already taken by the writer`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val s = sink()
        val blockingThrowable = object : RuntimeException("writer in flight") {
            override fun printStackTrace(writer: PrintWriter) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "writer was not released" }
                super.printStackTrace(writer)
            }
        }
        s.write(FlashLogLevel.ERROR, "TRANSFER", "in-flight entry", blockingThrowable)
        assertTrue("writer took the entry", entered.await(10, TimeUnit.SECONDS))
        val flushing = Thread {
            try {
                s.flush()
                assertTrue("the dequeued entry is on disk when flush returns", text(s).contains("in-flight entry"))
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finished.countDown()
            }
        }
        flushing.start()
        try {
            assertFalse("flush cannot finish while formatting is blocked", finished.await(200, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            flushing.join(10_000)
        }
        assertTrue("flush completed", finished.await(10, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }

    @Test
    fun `export cannot overwrite one of its own rotating source files`() {
        val s = sink()
        s.write(FlashLogLevel.INFO, "T", "kept", null)
        s.flush()
        var rejected = false
        try {
            s.exportGzip(File(dir, "flash-0.log"))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("live log destination is rejected", rejected)
        assertTrue("source is not truncated", text(s).contains("kept"))
    }

    @Test
    fun `exportGzip streams header and logs into valid gzip archive`() {
        val s = sink()
        s.write(FlashLogLevel.INFO, "SESSION", "session started", null)
        s.write(FlashLogLevel.WARN, "TRANSFER", "chunk slow", null)
        s.flush()

        val exportFile = File(dir, "export.log.gz")
        s.exportGzip(exportFile, listOf("session.header exportAtMs=1700000000000", "device=Pixel_8"))

        assertTrue("archive exists", exportFile.exists() && exportFile.length() > 0)

        val unzipped = java.util.zip.GZIPInputStream(exportFile.inputStream()).bufferedReader().readText()
        assertTrue("contains header", unzipped.contains("session.header exportAtMs=1700000000000"))
        assertTrue("contains device", unzipped.contains("device=Pixel_8"))
        assertTrue("contains session line", unzipped.contains("I/SESSION: session started"))
        assertTrue("contains transfer line", unzipped.contains("W/TRANSFER: chunk slow"))
    }
}
