package com.transfer.flash.ui.chat

import com.transfer.flash.core.security.group.GroupInvite
import com.transfer.flash.core.security.group.GroupInviteCodec
import com.transfer.flash.core.security.group.GroupSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashGroupInviteJoinMathTest {

    @Test
    fun testIsInviteUrlAndExtract() {
        val sampleInvite = "flash://g/1/AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8w"
        assertTrue(FlashGroupInviteJoinMath.isInviteUrl(sampleInvite))
        assertFalse(FlashGroupInviteJoinMath.isInviteUrl("https://example.com"))
        assertFalse(FlashGroupInviteJoinMath.isInviteUrl("flash://other/link"))

        val chatText = "Hey join our group: $sampleInvite see you there!"
        assertEquals(sampleInvite, FlashGroupInviteJoinMath.extractInviteUrl(chatText))
        assertNull(FlashGroupInviteJoinMath.extractInviteUrl("Hello world no link here"))
    }

    @Test
    fun testParseValidAndInvalidInviteUrl() {
        assertNull(FlashGroupInviteJoinMath.parseInviteUrl("not an invite"))
        assertNull(FlashGroupInviteJoinMath.parseInviteUrl("flash://g/2/invalidVersion"))

        val invite = GroupInvite(
            groupId = "g2-group-123",
            epoch = 1L,
            secret = GroupSecret.fromBytes(ByteArray(32) { it.toByte() }),
            groupName = "Alpha Team",
            inviterDeviceId = "dev-alice",
            inviterKeyFingerprint = ByteArray(32) { (it + 1).toByte() },
            addressHints = listOf("192.168.1.10:4545"),
            issuedAtMs = 1700000000000L,
        )
        val uri = GroupInviteCodec.encode(invite)
        val decoded = FlashGroupInviteJoinMath.parseInviteUrl(uri)
        assertNotNull(decoded)
        assertEquals("g2-group-123", decoded.groupId)
        assertEquals("Alpha Team", decoded.groupName)
        assertEquals("dev-alice", decoded.inviterDeviceId)
    }

    @Test
    fun testLabelsAndMessages() {
        assertEquals("Invited by Alice", FlashGroupInviteJoinMath.inviterLabel("Alice"))
        assertEquals("Invited by a group member", FlashGroupInviteJoinMath.inviterLabel(null))
        assertEquals("Invited by a group member", FlashGroupInviteJoinMath.inviterLabel(""))

        assertEquals("Join Alpha Team?", FlashGroupInviteJoinMath.joinConfirmationTitle("Alpha Team"))
        assertEquals("Join Group?", FlashGroupInviteJoinMath.joinConfirmationTitle(""))

        assertEquals(
            "You were invited by Alice to join Alpha Team.",
            FlashGroupInviteJoinMath.joinConfirmationMessage("Alpha Team", "Alice"),
        )
        assertEquals(
            "You were invited to join Alpha Team.",
            FlashGroupInviteJoinMath.joinConfirmationMessage("Alpha Team", null),
        )
    }

    @Test
    fun testFormatRequestTime() {
        val now = 100_000_000L
        assertEquals("Just now", FlashGroupInviteJoinMath.formatRequestTime(now - 10_000L, now))
        assertEquals("5m ago", FlashGroupInviteJoinMath.formatRequestTime(now - 5 * 60 * 1000L, now))
        assertEquals("2h ago", FlashGroupInviteJoinMath.formatRequestTime(now - 2 * 3600 * 1000L, now))
        assertEquals("Yesterday", FlashGroupInviteJoinMath.formatRequestTime(now - 25 * 3600 * 1000L, now))
        assertEquals("3d ago", FlashGroupInviteJoinMath.formatRequestTime(now - 3 * 24 * 3600 * 1000L, now))
    }
}
