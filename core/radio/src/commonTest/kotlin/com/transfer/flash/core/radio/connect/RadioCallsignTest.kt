package com.transfer.flash.core.radio.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RADWIRE-U26: the station ID the gateway will put on the air (ERROR-684). */
class RadioCallsignTest {
    @Test
    fun radwireU26a_acceptedFormsAreNormalised() {
        assertEquals("W1AW", RadioCallsign.normalise("w1aw"))
        assertEquals("W1AW", RadioCallsign.normalise("  W1AW "))
        assertEquals("W1AW-7", RadioCallsign.normalise("w1aw-7"))
        assertEquals("W1AW-5", RadioCallsign.normalise("W1AW-05"))
        assertEquals("9A1AAA", RadioCallsign.normalise("9a1aaa"))
        assertEquals("K9AB-15", RadioCallsign.normalise("K9AB-15"))
    }

    @Test
    fun radwireU26b_everythingElseIsRefused() {
        val bad = listOf(
            null, "", "   ", "W1", "TOOLONG1", "ABCDEF", "123456", "W1AW-16", "W1AW-", "W1AW--1", "W1AW-X",
            "W 1AW", "W1AW!", "NOCALL", "N0CALL", "nocall-3", "MYCALL",
        )
        for (input in bad) assertNull(RadioCallsign.normalise(input), "must refuse <$input>")
    }
}
