package com.transfer.flash.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlashIconsTest {

    @Test
    fun `copy icon has distinct drawable and non-blank content description`() {
        assertEquals("Copy", FlashIcons.Copy.contentDescription)
        assertNotEquals(FlashIcons.Share, FlashIcons.Copy)
        assertNotEquals(FlashIcons.Share.drawableRes, FlashIcons.Copy.drawableRes)
        assertTrue(FlashIcons.Copy.contentDescription.isNotBlank())
    }
}
