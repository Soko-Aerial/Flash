@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dialog's parser now delegates to `:core:hub-protocol` (CLI-17); these pin the mapping, not the parser. */
class FlashEnrollMathTest {

    @Test
    fun parsesAFlashEnrollLinkThroughTheSharedParser() {
        val parsed = FlashEnrollMath.parseInvite(
            "flash://enroll?server=https%3A%2F%2Fhub.example.com%3A8443&token=tok%2D1&root=ab12cd",
        )
        assertNotNull(parsed)
        assertEquals("https://hub.example.com:8443", parsed.serverUrl)
        assertEquals("tok-1", parsed.token)
        assertEquals("AB12CD", parsed.rootFingerprint)
    }

    @Test
    fun aLinkWithoutARootStillParsesWithAnEmptyFingerprint() {
        val parsed = FlashEnrollMath.parseInvite("flash://enroll?server=10.0.0.5:8443&token=t")
        assertNotNull(parsed)
        assertEquals("http://10.0.0.5:8443", parsed.serverUrl)
        assertEquals("", parsed.rootFingerprint)
    }

    @Test
    fun rejectsBlankAndNonInviteText() {
        assertNull(FlashEnrollMath.parseInvite("   "))
        assertNull(FlashEnrollMath.parseInvite("hello world"))
        assertFalse(FlashEnrollMath.isEnrollInvite("flash://enroll?server=&token="))
        assertTrue(FlashEnrollMath.isEnrollInvite("flash://enroll?server=h:1&token=t&root=AA"))
    }
}
