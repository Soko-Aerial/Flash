package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageGroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class FlashStressLogicTest {

    @Test
    fun `same seed produces identical ids and order`() {
        val first = FlashStressMath.generateMessages(300, seed = 42L)
        val second = FlashStressMath.generateMessages(300, seed = 42L)

        assertEquals(first.size, second.size)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first, second)
    }

    @Test
    fun `different seeds produce different conversations`() {
        val first = FlashStressMath.generateMessages(100, seed = 42L)
        val second = FlashStressMath.generateMessages(100, seed = 7L)

        assertNotEquals(first.map { it.text + it.timeLabel }, second.map { it.text + it.timeLabel })
    }

    @Test
    fun `count is honored exactly`() {
        assertEquals(0, FlashStressMath.generateMessages(0).size)
        assertEquals(1, FlashStressMath.generateMessages(1).size)
        assertEquals(137, FlashStressMath.generateMessages(137).size)
    }

    @Test
    fun `ids are unique and index-shaped`() {
        val messages = FlashStressMath.generateMessages(500)

        assertEquals(500, messages.map { it.id }.toSet().size)
        assertEquals("stress-0", messages.first().id)
        assertEquals("stress-499", messages.last().id)
    }

    @Test
    fun `mix contains every attachment kind and reactions`() {
        val messages = FlashStressMath.generateMessages(2000)

        assertTrue(messages.any { it.images.isNotEmpty() })
        assertTrue(messages.all { img -> img.images.all { it.uri == null } })
        assertTrue(messages.any { it.voiceAttachments.isNotEmpty() && it.voiceAttachments.first().amplitudes.isNotEmpty() })
        assertTrue(messages.any { it.fileAttachments.isNotEmpty() })
        assertTrue(messages.any { it.reactions.isNotEmpty() })
        // Every full 7-message window contains at least one quoted reply
        // (the forced every-7th reply; random Reply-kind picks may add extras).
        assertTrue(messages.drop(1).windowed(7, step = 7).all { window ->
            window.count { it.replyTo != null } >= 1
        })
    }

    @Test
    fun `group positions are computed over the full thread`() {
        val messages = FlashStressMath.generateMessages(500)

        // With hundreds of mixed senders both grouped runs and singles must appear.
        assertTrue(messages.any { it.groupPosition in setOf(FlashMessageGroupPosition.TOP, FlashMessageGroupPosition.MIDDLE) })
        assertTrue(messages.any { it.groupPosition == FlashMessageGroupPosition.SINGLE })
    }

    @Test
    fun `presets match the documented sizes`() {
        assertEquals(listOf(100, 500, 1000, 2000), FlashStressMath.stressPresets())
    }

    @Test
    fun `estimated item bytes is positive and plausible`() {
        val bytes = FlashStressMath.estimatedItemBytes()
        assertTrue(bytes > 0)
        assertTrue(bytes < 64 * 1024)
    }

    @Test
    fun `2000-message generation completes well under one second`() {
        // `System.nanoTime()` is `java.lang` and unavailable in `commonTest`; the common
        // stdlib's monotonic source is the same measurement. Precedent:
        // `core/transfer`'s `RollingRateMeterTest` already uses it from `commonTest`.
        val startedAt = TimeSource.Monotonic.markNow()
        FlashStressMath.generateMessages(2000)
        val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds

        // Device target from performance.md is <100 ms; CI guard stays generous at 1 s.
        assertTrue(elapsedMs < 1000L, "generation took ${elapsedMs}ms")
    }
}
