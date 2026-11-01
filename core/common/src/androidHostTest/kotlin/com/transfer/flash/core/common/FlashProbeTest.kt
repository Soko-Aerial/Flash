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
import org.junit.Before
import org.junit.Test

class FlashProbeTest {

    private data class Line(val level: FlashLogLevel, val tag: String, val message: String)

    private val lines = mutableListOf<Line>()
    private lateinit var previousSink: FlashLogSink
    private var previousLevel = FlashLogLevel.INFO

    @Before
    fun saveLogger() {
        previousSink = FlashLog.sink
        previousLevel = FlashLog.minLevel()
        FlashLog.setMinLevel(FlashLogLevel.INFO)
    }

    private fun capture() {
        FlashLog.installSink(FlashLogSink { level, tag, message, _ -> lines += Line(level, tag, message) })
    }

    @After
    fun resetProvidersAndSink() {
        FlashProbe.setSnapshotProvider(null)
        FlashProbe.setHeaderFields(emptyList())
        FlashLog.setMinLevel(previousLevel)
        FlashLog.installSink(previousSink)
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
    fun emit_flat_overloads_write_identical_lines_without_pairs() {
        capture()

        FlashProbe.emit("test.zero")
        FlashProbe.emit("test.one", "k1", "v1")
        FlashProbe.emit("test.two", "k1", "v1", "k2", 42)
        FlashProbe.emit("test.three", "k1", "v1", "k2", 42, "k3", true)
        FlashProbe.emit("test.four", "k1", "v1", "k2", 42, "k3", true, "k4", null)

        assertEquals(5, lines.size)
        assertEquals("test.zero", lines[0].message)
        assertEquals("test.one k1=v1", lines[1].message)
        assertEquals("test.two k1=v1 k2=42", lines[2].message)
        assertEquals("test.three k1=v1 k2=42 k3=true", lines[3].message)
        assertEquals("test.four k1=v1 k2=42 k3=true k4=-", lines[4].message)
    }

    @Test
    fun flat_overloads_five_through_eight_match_the_existing_vararg_format() {
        capture()
        val f = listOf("a" to "two words\n", "b" to null, "c" to "", "d" to 42L,
            "e" to true, "f" to "界\t😀", "g" to "x".repeat(100), "h" to "\u0000end")
        FlashProbe.emit("five", f[0].first, f[0].second, f[1].first, f[1].second,
            f[2].first, f[2].second, f[3].first, f[3].second, f[4].first, f[4].second)
        FlashProbe.emit("six", f[0].first, f[0].second, f[1].first, f[1].second,
            f[2].first, f[2].second, f[3].first, f[3].second, f[4].first, f[4].second,
            f[5].first, f[5].second)
        FlashProbe.emit("seven", f[0].first, f[0].second, f[1].first, f[1].second,
            f[2].first, f[2].second, f[3].first, f[3].second, f[4].first, f[4].second,
            f[5].first, f[5].second, f[6].first, f[6].second)
        FlashProbe.emit("eight", f[0].first, f[0].second, f[1].first, f[1].second,
            f[2].first, f[2].second, f[3].first, f[3].second, f[4].first, f[4].second,
            f[5].first, f[5].second, f[6].first, f[6].second, f[7].first, f[7].second)
        for ((index, name) in listOf("five", "six", "seven", "eight").withIndex()) {
            val flat = lines[index].message
            FlashProbe.emit(name, *f.take(index + 5).toTypedArray())
            assertEquals(flat, lines.last().message)
            assertEquals(FlashProbe.format(name, f.take(index + 5)), flat)
        }
    }

    @Test
    fun export_header_copies_a_mutable_supplied_field_list() {
        val fields = mutableListOf<Pair<String, Any?>>("build" to "original")
        FlashProbe.setHeaderFields(fields)
        fields.clear()
        assertEquals("session.header exportAtMs=1 build=original", FlashProbe.exportHeader(1L).first())
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
    fun export_header_uses_one_snapshot_when_fields_are_replaced_during_formatting() {
        val replacingValue = object {
            override fun toString(): String {
                FlashProbe.setHeaderFields(emptyList())
                return "before"
            }
        }
        FlashProbe.setHeaderFields(listOf("first" to replacingValue, "second" to "original"))

        assertEquals(
            "session.header exportAtMs=1 first=before second=original",
            FlashProbe.exportHeader(1L).first(),
        )
        assertEquals("session.header exportAtMs=2", FlashProbe.exportHeader(2L).first())
    }

    @Test
    fun a_failing_snapshot_does_not_lose_the_header() {
        FlashProbe.setSnapshotProvider { error("boom") }

        val header = FlashProbe.exportHeader(wallClockMs = 1L)

        assertTrue(header[0].startsWith("session.header"))
        assertEquals("session.snapshot.failed error=IllegalStateException", header[1])
    }
}
