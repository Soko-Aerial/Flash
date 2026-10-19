package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The direct chat header's presence and transport (the "hub chat shows Offline" fix).
 *
 * Two colleagues who are not on one LAN chat **through the organisation hub**: the messages arrive,
 * so the header must not say Offline. Before `hubOnline` existed, presence came only from LAN
 * sessions, so a hub-only peer was Offline and its typing indicator was dropped on the floor.
 */
class DirectLinkUiTest {

    @Test
    fun `a hub-only colleague is Online with the relay as its transport`() {
        val link = directLinkUi("peer", snapshot(hubOnline = setOf("peer")), typingNames = emptyList())

        assertEquals(FlashPeerPresence.Online, link.presence)
        assertEquals(FlashNetworkTransport.Relay, link.transport)
    }

    @Test
    fun `a hub-only colleague typing is Typing, not dropped`() {
        val link = directLinkUi("peer", snapshot(hubOnline = setOf("peer")), typingNames = listOf("Alice"))

        assertEquals(FlashPeerPresence.Typing, link.presence)
        assertEquals(FlashNetworkTransport.Relay, link.transport)
        assertEquals(listOf("Alice"), link.typingNames)
    }

    @Test
    fun `a live LAN session outranks hub presence for the named transport`() {
        val link = directLinkUi(
            "peer",
            snapshot(online = setOf("peer"), hubOnline = setOf("peer")),
            typingNames = emptyList(),
        )

        assertEquals(FlashPeerPresence.Online, link.presence)
        // The peer has both paths; the LAN session is the one we actually hold.
        assertEquals(FlashNetworkTransport.Lan, link.transport)
    }

    @Test
    fun `a colleague the hub no longer reports is Offline with no transport claimed`() {
        val link = directLinkUi("peer", snapshot(hubOnline = setOf("other")), typingNames = emptyList())

        assertEquals(FlashPeerPresence.Offline, link.presence)
        assertEquals(FlashNetworkTransport.Unknown, link.transport)
    }

    @Test
    fun `typing sticks only while the peer is still reachable`() {
        // A session that died mid-compose: the indicator is dropped rather than left on screen.
        val offline = directLinkUi("peer", snapshot(), typingNames = listOf("Alice"))
        assertEquals(FlashPeerPresence.Offline, offline.presence)
        assertTrue(offline.typingNames.isEmpty(), "typing must be dropped when unreachable")

        // A peer seen by discovery is "Online (ring)", not "typing" — we hold no link to it.
        val seen = directLinkUi("peer", snapshot(reachable = setOf("peer")), typingNames = listOf("Alice"))
        assertEquals(FlashPeerPresence.Reachable, seen.presence)
        assertTrue(seen.typingNames.isEmpty(), "typing must be dropped for a peer with no link")
    }

    @Test
    fun `the reconnect grace and a bare discovery sighting keep their own states`() {
        val connecting = directLinkUi("peer", snapshot(connecting = setOf("peer")), typingNames = emptyList())
        assertEquals(FlashPeerPresence.Connecting, connecting.presence)
        assertEquals(FlashNetworkTransport.Unknown, connecting.transport)

        val reachable = directLinkUi("peer", snapshot(reachable = setOf("peer")), typingNames = emptyList())
        assertEquals(FlashPeerPresence.Reachable, reachable.presence)
        assertEquals(FlashNetworkTransport.Unknown, reachable.transport)
    }

    private fun snapshot(
        online: Set<String> = emptySet(),
        connecting: Set<String> = emptySet(),
        reachable: Set<String> = emptySet(),
        hubOnline: Set<String> = emptySet(),
    ) = PresenceSnapshot(online = online, connecting = connecting, reachable = reachable, hubOnline = hubOnline)
}
