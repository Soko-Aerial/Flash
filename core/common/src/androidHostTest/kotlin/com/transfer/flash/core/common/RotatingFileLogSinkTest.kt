@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common

import com.transfer.flash.core.common.logging.FlashLogLevel
import com.transfer.flash.core.common.logging.FlashLogSink
import com.transfer.flash.core.common.logging.RotatingFileLogSink
import java.io.File
import java.nio.file.Files
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
}
