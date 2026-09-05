package com.transfer.flash.ui.chat

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI-032: pure logic for the 1:1 peer details sheet + header-derived group roster. */
class FlashPeerDetailsLogicTest {

    // --- presenceLabel ---

    @Test
    fun `presenceLabel covers every presence`() {
        assertEquals("Online", FlashPeerDetailsMath.presenceLabel(FlashPeerPresence.Online))
        assertEquals("Typing…", FlashPeerDetailsMath.presenceLabel(FlashPeerPresence.Typing))
        assertEquals("Connecting…", FlashPeerDetailsMath.presenceLabel(FlashPeerPresence.Connecting))
        assertEquals("Offline", FlashPeerDetailsMath.presenceLabel(FlashPeerPresence.Offline))
    }

    // --- isReachable ---

    @Test
    fun `isReachable only for online or typing`() {
        assertTrue(FlashPeerDetailsMath.isReachable(FlashPeerPresence.Online))
        assertTrue(FlashPeerDetailsMath.isReachable(FlashPeerPresence.Typing))
        assertFalse(FlashPeerDetailsMath.isReachable(FlashPeerPresence.Connecting))
        assertFalse(FlashPeerDetailsMath.isReachable(FlashPeerPresence.Offline))
    }

    // --- transportLabel ---

    @Test
    fun `transportLabel maps each transport when reachable`() {
        val p = FlashPeerPresence.Online
        assertEquals("Local network (Wi-Fi)", FlashPeerDetailsMath.transportLabel(FlashNetworkTransport.Lan, p))
        assertEquals("Wi-Fi Direct", FlashPeerDetailsMath.transportLabel(FlashNetworkTransport.WifiDirect, p))
        assertEquals("Relay", FlashPeerDetailsMath.transportLabel(FlashNetworkTransport.Relay, p))
        assertEquals("Not connected", FlashPeerDetailsMath.transportLabel(FlashNetworkTransport.Unknown, p))
    }

    @Test
    fun `transportLabel is Not connected whenever peer is unreachable`() {
        assertEquals(
            "Not connected",
            FlashPeerDetailsMath.transportLabel(FlashNetworkTransport.Lan, FlashPeerPresence.Offline),
        )
    }

    // --- encryptionLabel ---

    @Test
    fun `encryptionLabel reflects flag`() {
        assertEquals("End-to-end encrypted", FlashPeerDetailsMath.encryptionLabel(true))
        assertEquals("Not encrypted", FlashPeerDetailsMath.encryptionLabel(false))
    }

    // --- groupMembersFromHeader (#23: no fabricated identities) ---

    @Test
    fun `groupMembersFromHeader returns empty when header has no roster`() {
        val header = FlashChatHeaderUiState(title = "Solo", avatarInitials = "SO")
        assertTrue(groupMembersFromHeader(header).isEmpty())
    }

    @Test
    fun `groupMembersFromHeader marks first onlineCount members online`() {
        val header = FlashChatHeaderUiState(
            title = "Squad",
            avatarInitials = "SQ",
            isGroup = true,
            transport = FlashNetworkTransport.Lan,
            memberInitials = listOf("AB", "CD", "EF"),
            memberCount = 3,
            onlineCount = 2,
        )
        val members = groupMembersFromHeader(header)
        assertEquals(3, members.size)
        assertEquals(listOf(true, true, false), members.map { it.isOnline })
        // Names are the neutral initials, never invented identities.
        assertEquals(listOf("AB", "CD", "EF"), members.map { it.name })
        assertEquals(FlashMemberRole.Member, members.first().role)
        // Online rows carry the header transport; offline rows fall back to Unknown.
        assertEquals(FlashNetworkTransport.Lan, members[0].transport)
        assertEquals(FlashNetworkTransport.Unknown, members[2].transport)
    }
}
