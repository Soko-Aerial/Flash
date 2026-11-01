@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashLogTest {

    private data class LoggedRecord(
        val level: FlashLogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private val records = mutableListOf<LoggedRecord>()

    @BeforeTest
    fun setUp() {
        records.clear()
        FlashLog.installSink(FlashLogSink { level, tag, message, throwable ->
            records += LoggedRecord(level, tag, message, throwable)
        })
        FlashLog.setMinLevel(FlashLogLevel.INFO)
    }

    @AfterTest
    fun tearDown() {
        FlashLog.setMinLevel(FlashLogLevel.INFO)
    }

    @Test
    fun disabled_debug_lambda_is_not_evaluated() {
        var evaluated = false
        FlashLog.d("TAG") {
            evaluated = true
            "computed debug message"
        }
        assertFalse(evaluated, "Lambda should not be evaluated when DEBUG is disabled")
        assertTrue(records.isEmpty())
    }

    @Test
    fun enabled_debug_lambda_is_evaluated_and_logged() {
        FlashLog.setMinLevel(FlashLogLevel.DEBUG)
        var evaluated = false
        FlashLog.d("TAG") {
            evaluated = true
            "computed debug message"
        }
        assertTrue(evaluated, "Lambda should be evaluated when DEBUG is enabled")
        assertEquals(1, records.size)
        assertEquals(FlashLogLevel.DEBUG, records[0].level)
        assertEquals("TAG", records[0].tag)
        assertEquals("computed debug message", records[0].message)
    }

    @Test
    fun info_warn_error_lambdas_are_evaluated_when_level_is_info() {
        var infoEvaluated = false
        var warnEvaluated = false
        var errorEvaluated = false

        FlashLog.i("INFO_TAG") {
            infoEvaluated = true
            "info msg"
        }
        val ex = IllegalArgumentException("test")
        FlashLog.w("WARN_TAG", ex) {
            warnEvaluated = true
            "warn msg"
        }
        FlashLog.e("ERROR_TAG") {
            errorEvaluated = true
            "error msg"
        }

        assertTrue(infoEvaluated)
        assertTrue(warnEvaluated)
        assertTrue(errorEvaluated)
        assertEquals(3, records.size)
        assertEquals(FlashLogLevel.INFO, records[0].level)
        assertEquals(FlashLogLevel.WARN, records[1].level)
        assertEquals(ex, records[1].throwable)
        assertEquals(FlashLogLevel.ERROR, records[2].level)
    }

    @Test
    fun eager_string_overloads_still_record_logs() {
        FlashLog.i("TAG", "eager info")
        FlashLog.w("TAG", "eager warn")
        FlashLog.e("TAG", "eager error")

        assertEquals(3, records.size)
        assertEquals(FlashLogLevel.INFO, records[0].level)
        assertEquals("eager info", records[0].message)
        assertEquals(FlashLogLevel.WARN, records[1].level)
        assertEquals("eager warn", records[1].message)
        assertEquals(FlashLogLevel.ERROR, records[2].level)
        assertEquals("eager error", records[2].message)
    }
}
