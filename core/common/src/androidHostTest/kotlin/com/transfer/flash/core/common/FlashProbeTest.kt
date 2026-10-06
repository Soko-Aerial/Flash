@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashLogLevel
import com.transfer.flash.core.common.logging.FlashLogSink
import com.transfer.flash.core.common.logging.FlashProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashProbeTest {

    private data class Line(val level: FlashLogLevel, val tag: String, val message: String)

    private val lines = mutableListOf<Line>()

    private fun capture() {
        FlashLog.installSink(FlashLogSink { level, tag, message, _ -> lines += Line(level, tag, message) })
    }

    @After
    fun resetProvidersAndSink() {
        FlashProbe.setSnapshotProvider(null)
        FlashProbe.setHeaderFields(emptyList())
        FlashLog.installSink(FlashLogSink { _, _, _, _ -> })
    }

    @Test
    fun emit_writes_one_info_line_under_the_probe_tag() {
        capture()

        FlashProbe.emit("group.msg.in", "group" to "g2-abcdef", "skewMs" to -420L, "signed" to true)

        assertEquals(1, lines.size)
        assertEquals(FlashLogLevel.INFO, lines[0].level)
        assertEquals("PROBE", lines[0].tag)
        assertEquals("group.msg.in group=g2-abcdef skewMs=-420 signed=true", lines[0].message)
    }

    @Test
    fun a_null_or_empty_value_is_a_dash() {
        assertEquals("x a=- b=-", FlashProbe.format("x", listOf("a" to null, "b" to "")))
    }

    @Test
    fun values_stay_on_one_line_without_spaces() {
        val text = FlashProbe.sanitize("two words\nnext\tline\r")
        assertFalse(text.any { it.isWhitespace() })
        assertEquals("two_words_next_line_", text)
    }

    @Test
    fun values_are_bounded() {
        assertEquals(80, FlashProbe.sanitize("a".repeat(500)).length)
    }

    @Test
    fun short_keeps_eight_characters_and_survives_null() {
        assertEquals("abcdefgh", FlashProbe.short("abcdefghijklmnop"))
        assertEquals("abc", FlashProbe.short("abc"))
        assertEquals("-", FlashProbe.short(null))
        assertEquals("-", FlashProbe.short(""))
    }

    @Test
    fun emit_never_throws_when_the_sink_does() {
        FlashLog.installSink(FlashLogSink { _, _, _, _ -> error("sink failed") })

        FlashProbe.emit("session.up", "peer" to "p")
    }

    @Test
    fun export_header_carries_the_clock_the_fields_and_the_snapshot() {
        FlashProbe.setHeaderFields(listOf("build" to "2.0.0", "device" to "Pixel 8"))
        FlashProbe.setSnapshotProvider { listOf("session.snapshot peers=2") }

        val header = FlashProbe.exportHeader(wallClockMs = 1_700_000_000_000L)

        assertEquals("session.header exportAtMs=1700000000000 build=2.0.0 device=Pixel_8", header[0])
        assertEquals("session.snapshot peers=2", header[1])
    }

    @Test
    fun a_failing_snapshot_does_not_lose_the_header() {
        FlashProbe.setSnapshotProvider { error("boom") }

        val header = FlashProbe.exportHeader(wallClockMs = 1L)

        assertTrue(header[0].startsWith("session.header"))
        assertEquals("session.snapshot.failed error=IllegalStateException", header[1])
    }
}
