package com.transfer.flash.ui.chat

import com.transfer.flash.ui.icons.FlashIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashEncryptionLogicTest {

    // --- badgeState mapping (full boolean truth table) ---

    @Test
    fun `badgeState covers every boolean combination`() {
        val cases = listOf(
            // (isEncrypted, isVerified) -> expected state
            Pair(false, false),
            Pair(false, true),
            Pair(true, false),
            Pair(true, true),
        )
        val expected = listOf(
            FlashEncryptionBadgeState.None,
            FlashEncryptionBadgeState.None,
            FlashEncryptionBadgeState.EncryptedUnverified,
            FlashEncryptionBadgeState.EncryptedTrusted,
        )
        assertEquals(expected.size, cases.size)
        cases.zip(expected) { (isEncrypted, isVerified), state ->
            assertEquals(
                state,
                FlashEncryptionMath.badgeState(isEncrypted, isVerified),
                "isEncrypted=$isEncrypted isVerified=$isVerified",
            )
        }
    }

    @Test
    fun `no encryption wins over verification`() {
        // Even a stale "verified" flag must not fabricate trust without encryption.
        assertEquals(
            FlashEncryptionBadgeState.None,
            FlashEncryptionMath.badgeState(isEncrypted = false, isVerified = true),
        )
    }

    // --- Labels ---

    @Test
    fun `badge labels match copy spec`() {
        val cases = mapOf(
            FlashEncryptionBadgeState.EncryptedTrusted to "Encrypted",
            FlashEncryptionBadgeState.EncryptedUnverified to "Unverified",
            FlashEncryptionBadgeState.None to "",
        )
        cases.forEach { (state, label) ->
            assertEquals(label, FlashEncryptionMath.badgeLabel(state))
        }
    }

    @Test
    fun `sheet titles are non-blank for every state`() {
        FlashEncryptionBadgeState.entries.forEach { state ->
            assertTrue(
                FlashEncryptionMath.sheetTitle(state).isNotBlank(),
                "blank sheet title for $state",
            )
        }
    }

    // --- Sheet explainer lines ---

    @Test
    fun `sheet explainer lines are non-blank and non-empty for every state`() {
        FlashEncryptionBadgeState.entries.forEach { state ->
            val lines = FlashEncryptionMath.sheetExplainerLines(state)
            assertTrue(lines.isNotEmpty(), "no explainer lines for $state")
            lines.forEach { line ->
                assertTrue(line.isNotBlank(), "blank explainer line in $state")
                assertEquals(line, line.trim(), "untrimmed line in $state")
            }
        }
    }

    @Test
    fun `sheet explains local-network no-cloud scope`() {
        // Both encrypted states must communicate the P2P reality: local network, no cloud.
        listOf(
            FlashEncryptionBadgeState.EncryptedTrusted,
            FlashEncryptionBadgeState.EncryptedUnverified,
        ).forEach { state ->
            val text = FlashEncryptionMath.sheetExplainerLines(state).joinToString(" ")
            assertTrue(text.contains("local network"), "missing 'local network' in $state")
            assertTrue(text.contains("cloud"), "missing 'no cloud' phrasing in $state")
        }
    }

    @Test
    fun `unverified sheet mentions verification while trusted confirms it`() {
        val unverified = FlashEncryptionMath.sheetExplainerLines(FlashEncryptionBadgeState.EncryptedUnverified)
            .joinToString(" ")
        val trusted = FlashEncryptionMath.sheetExplainerLines(FlashEncryptionBadgeState.EncryptedTrusted)
            .joinToString(" ")
        assertTrue(unverified.contains("haven't verified"))
        assertTrue(trusted.contains("verified"))
    }

    // --- Verification entry points ---

    @Test
    fun `verification entries expose both code comparison and fingerprint`() {
        val titles = FlashEncryptionMath.verificationEntries.map { it.title }
        assertEquals(2, titles.size)
        assertTrue(titles.contains("Verify security codes"))
        assertTrue(titles.contains("View device fingerprint"))
        FlashEncryptionMath.verificationEntries.forEach { entry ->
            assertTrue(entry.explanation.isNotBlank(), "blank explanation for ${entry.title}")
        }
    }

    @Test
    fun `verification entries use existing Flash icons only`() {
        assertEquals(FlashIcons.Check, FlashEncryptionMath.verificationEntries[0].icon)
        assertEquals(FlashIcons.Device, FlashEncryptionMath.verificationEntries[1].icon)
    }
}
