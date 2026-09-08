package com.transfer.flash.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the pure haptic decision logic (UI-039), run on both Android host and desktop JVM.
 * Compose-dependent mapping lives in `rememberFlashHaptics` and is covered by device QA.
 */
class FlashFeedbackLogicTest {

    @Test
    fun enabled_whenSystemHapticsOn_isTrueRegardlessOfReduceMotion() {
        // Reduce-motion must NOT mute haptics: vibration is a non-visual, non-vestibular channel.
        assertTrue(FlashHapticPolicy.enabled(reduceMotion = false))
        assertTrue(FlashHapticPolicy.enabled(reduceMotion = true))
    }

    @Test
    fun enabled_whenSystemHapticsOff_isAlwaysFalse() {
        assertFalse(FlashHapticPolicy.enabled(reduceMotion = false, systemHapticsEnabled = false))
        assertFalse(FlashHapticPolicy.enabled(reduceMotion = true, systemHapticsEnabled = false))
    }

    @Test
    fun enabled_defaultSystemHapticsArgumentIsOn() {
        assertTrue(FlashHapticPolicy.enabled(reduceMotion = false))
    }

    @Test
    fun hapticVocabulary_hasExactlyTheDocumentedIntents() {
        // Guards the documented UI-039 vocabulary against accidental additions/removals.
        assertEquals(
            listOf("Tick", "Confirm", "Warn", "Reject"),
            FlashHaptic.entries.map { it.name },
        )
    }
}
