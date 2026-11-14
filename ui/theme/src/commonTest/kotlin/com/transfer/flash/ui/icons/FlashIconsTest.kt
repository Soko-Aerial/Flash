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

    @Test
    fun `relay nearby hub and wifi icons have distinct drawables and labels`() {
        val networkAndNavIcons = listOf(
            FlashIcons.Relay,
            FlashIcons.Nearby,
            FlashIcons.Hub,
            FlashIcons.Wifi,
            FlashIcons.WifiDirect,
            FlashIcons.Connection,
        )
        assertEquals(networkAndNavIcons.size, networkAndNavIcons.map { it.drawableRes }.toSet().size)
        networkAndNavIcons.forEach { spec ->
            assertTrue(spec.contentDescription.isNotBlank())
        }
        assertEquals("Relay", FlashIcons.Relay.contentDescription)
        assertEquals("Nearby", FlashIcons.Nearby.contentDescription)
        assertEquals("Hub", FlashIcons.Hub.contentDescription)
    }
}
