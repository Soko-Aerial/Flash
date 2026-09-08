package com.transfer.flash.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class FlashNotificationContentTest {

    @Test
    fun `direct message preserves sender title and plain body`() {
        assertEquals(
            FlashNotificationContent("Alex", "Hello"),
            messageNotificationContent("peer-a", "Alex", "Hello", null),
        )
    }

    @Test
    fun `group message uses group title and prefixes sender`() {
        assertEquals(
            FlashNotificationContent("Team", "Alex: Hello"),
            messageNotificationContent("group-a", "Alex", "Hello", "Team"),
        )
    }

    @Test
    fun `direct attachment preserves sender title and attachment body`() {
        assertEquals(
            FlashNotificationContent("Alex", "Photo: image.jpg"),
            attachmentNotificationContent("peer-a", "Alex", "image.jpg", "image/jpeg", null),
        )
    }

    @Test
    fun `group attachment uses group title and prefixes sender`() {
        assertEquals(
            FlashNotificationContent("Team", "Alex: Voice message: voice.m4a"),
            attachmentNotificationContent("group-a", "Alex", "voice.m4a", "audio/mp4", "Team"),
        )
    }

    @Test
    fun `blank names retain established conversation fallback`() {
        assertEquals(
            FlashNotificationContent("peer-a", "Hello"),
            messageNotificationContent("peer-a", "", "Hello", null),
        )
        assertEquals(
            FlashNotificationContent("Alex", "Hello"),
            messageNotificationContent("group-a", "Alex", "Hello", ""),
        )
    }
}
