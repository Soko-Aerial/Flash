package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.security.pairing.FlashTrustedPeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The conversation header on desktop, which is derived here rather than taken from the repository.
 *
 * Until the chat repository moves to `commonMain`, desktop runs on `EmptyFlashChatRepository`, whose
 * header carries a placeholder title and no presence. The reported symptom was exactly that: tapping
 * a peer in Nearby — a peer whose name the trust store has held all along — opened a chat with a
 * blank name and no online status. These tests pin that the header is built from the trust list and
 * the live discovery roster, that an offline peer still gets its name, and that an unknown
 * conversation yields nothing rather than an invented one.
 */
class DesktopConversationHeaderTest {

    @Test
    fun aTrustedAndCurrentlyDiscoveredPeer_showsItsName_andReadsOnline() {
        val header = assertNotNull(
            desktopConversationHeader(
                conversationId = PEER_ID,
                trusted = listOf(FlashTrustedPeer(PEER_ID, "Pixel 7a")),
                discovered = listOf(endpoint(PEER_ID, "Pixel 7a")),
            ),
            "a trusted, visible peer must produce a header — its absence is the reported bug",
        )

        assertEquals("Pixel 7a", header.title)
        assertEquals(
            FlashPeerPresence.Online,
            header.presence,
            "presence must come from the live discovery roster, the same fact the Nearby dot shows",
        )
    }

    @Test
    fun aTrustedPeerThatIsNotCurrentlyVisible_keepsItsName_andReadsOffline() {
        // The restart / peer-out-of-range case. Trust is durable and discovery is not, so the name
        // must survive the peer dropping off the network — a chat whose title blanks out when the
        // phone sleeps is worse than one that says Offline.
        val header = assertNotNull(
            desktopConversationHeader(
                conversationId = PEER_ID,
                trusted = listOf(FlashTrustedPeer(PEER_ID, "Pixel 7a")),
                discovered = emptyList(),
            ),
        )

        assertEquals("Pixel 7a", header.title)
        assertEquals(FlashPeerPresence.Offline, header.presence)
    }

    @Test
    fun theTrustedNameWins_overTheNameDiscoveryIsReporting() {
        // Discovery carries the peer's self-reported friendly name, which the user does not control;
        // the trust record is what the user confirmed at pairing time. When they disagree the trust
        // record is the authoritative one.
        val header = assertNotNull(
            desktopConversationHeader(
                conversationId = PEER_ID,
                trusted = listOf(FlashTrustedPeer(PEER_ID, "Amara's phone")),
                discovered = listOf(endpoint(PEER_ID, "Flash V760")),
            ),
        )

        assertEquals("Amara's phone", header.title)
    }

    @Test
    fun anUntrustedButDiscoveredPeer_stillGetsAHeader() {
        // Reachable while a pairing is still in flight: the conversation is openable from Nearby
        // before the trust record exists, and it should not render nameless in the meantime.
        val header = assertNotNull(
            desktopConversationHeader(
                conversationId = PEER_ID,
                trusted = emptyList(),
                discovered = listOf(endpoint(PEER_ID, "Flash V760")),
            ),
        )

        assertEquals("Flash V760", header.title)
        assertEquals(FlashPeerPresence.Online, header.presence)
    }

    @Test
    fun anUnknownConversation_producesNothingRatherThanAFabricatedHeader() {
        // Null is what makes the caller fall back to the repository's own state. Inventing a title
        // here would put a made-up name on screen for an id nothing knows about.
        assertNull(
            desktopConversationHeader(
                conversationId = "some-other-id",
                trusted = listOf(FlashTrustedPeer(PEER_ID, "Pixel 7a")),
                discovered = listOf(endpoint(PEER_ID, "Pixel 7a")),
            ),
        )
        // The chat list renders before a conversation is chosen; there is no id to look up.
        assertNull(desktopConversationHeader(conversationId = null, trusted = emptyList(), discovered = emptyList()))
    }

    @Test
    fun theHeaderOffersVoiceCallsSince33a() {
        // 33a flipped this from false to true: the voice button starts an audio call via the
        // shared coordinator. Pinned so a regression cannot silently re-hide it. Video stays
        // out until 33c — but that lives in the shell (`showVideoCallAction = false`), not in
        // this header, so there is nothing to pin here for it.
        val header = assertNotNull(
            desktopConversationHeader(
                conversationId = PEER_ID,
                trusted = listOf(FlashTrustedPeer(PEER_ID, "Pixel 7a")),
                discovered = listOf(endpoint(PEER_ID, "Pixel 7a")),
            ),
        )

        assertEquals(true, header.showCallActions)
        assertEquals(false, header.isGroup, "a Nearby peer is a direct chat")
        assertEquals(false, header.isEncrypted, "Wire TLS is not active yet; matches mobile's truthful isEncrypted=false")
        assertEquals(FlashNetworkTransport.Lan, header.transport)
    }

    @Test
    fun theAvatarInitialsComeFromTheDisplayedName() {
        val header = assertNotNull(
            desktopConversationHeader(
                conversationId = PEER_ID,
                trusted = listOf(FlashTrustedPeer(PEER_ID, "amara okafor")),
                discovered = emptyList(),
            ),
        )

        assertEquals("AO", header.avatarInitials)
        assertEquals("amara okafor", header.avatarSeed, "the seed must match the title so the colour is stable")
    }

    // ------------------------------------------------------------------ helpers

    private fun endpoint(id: String, name: String) = FlashDiscoveredEndpoint(
        device = FlashDevice(
            id = FlashDeviceId(id),
            friendlyName = name,
            transportType = FlashTransportType.LAN,
        ),
        hostAddress = "192.168.1.104",
        port = 45822,
        serviceName = "Flash $name",
    )

    private companion object {
        const val PEER_ID = "92d2c543-bd11-40e6-a3ac-065079a6eb7c"
    }
}
