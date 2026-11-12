package com.transfer.flash.ui.adaptive

import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlashPlaceholderDetailLogicTest {

    @Test
    fun `every destination resolves non-blank placeholder copy and appropriate icon`() {
        FlashDestination.entries.forEach { destination ->
            val copy = FlashPlaceholderDetailMath.copyFor(destination)
            assertTrue(copy.title.isNotBlank(), "Blank title for $destination")
            assertTrue(copy.subtitle.isNotBlank(), "Blank subtitle for $destination")
        }
    }

    @Test
    fun `non-chat tabs never show Select a chat placeholder`() {
        val nonChat = listOf(
            FlashDestination.Transfers,
            FlashDestination.NearbyDevices,
            FlashDestination.Hub,
            FlashDestination.Settings,
        )
        nonChat.forEach { destination ->
            val copy = FlashPlaceholderDetailMath.copyFor(destination)
            assertNotEquals("Select a chat", copy.title, "Unexpected chat title on $destination")
            assertFalse(
                copy.subtitle.contains("conversation", ignoreCase = true),
                "Unexpected chat subtitle on $destination: ${copy.subtitle}",
            )
        }
    }

    @Test
    fun `find devices action is suppressed on NearbyDevices, Hub, and Settings placeholders`() {
        assertTrue(FlashPlaceholderDetailMath.copyFor(FlashDestination.ChatList).showFindDevicesAction)
        assertTrue(FlashPlaceholderDetailMath.copyFor(FlashDestination.Transfers).showFindDevicesAction)
        assertFalse(FlashPlaceholderDetailMath.copyFor(FlashDestination.NearbyDevices).showFindDevicesAction)
        assertFalse(FlashPlaceholderDetailMath.copyFor(FlashDestination.Hub).showFindDevicesAction)
        assertFalse(FlashPlaceholderDetailMath.copyFor(FlashDestination.Settings).showFindDevicesAction)
    }

    @Test
    fun `placeholder icons match each destination`() {
        assertEquals(FlashIcons.Chat, FlashPlaceholderDetailMath.copyFor(FlashDestination.ChatList).icon)
        assertEquals(FlashIcons.Transfer, FlashPlaceholderDetailMath.copyFor(FlashDestination.Transfers).icon)
        assertEquals(FlashIcons.Nearby, FlashPlaceholderDetailMath.copyFor(FlashDestination.NearbyDevices).icon)
        assertEquals(FlashIcons.Hub, FlashPlaceholderDetailMath.copyFor(FlashDestination.Hub).icon)
        assertEquals(FlashIcons.Settings, FlashPlaceholderDetailMath.copyFor(FlashDestination.Settings).icon)
    }
}
