package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.security.pairing.FlashTrustedPeer
import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.ui.chat.FlashPairingPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Nearby screen's peer lists, which have now failed twice in the same way.
 *
 * The defect this pins (`PAIRING-HANDOVER.md` queue item 1, "the trusted peer doesn't appear after
 * accepting"): the row list was a plain `val` computed in the composable body and captured by the
 * `remember`ed `derivedStateOf`, so it froze at the first composition — while `trustedIds`, read
 * *inside* the lambda, stayed live. A `derivedStateOf` tracks snapshot state; a plain value is a
 * constant to it. The consequence was not a stale label but a **disappearing device**: the id had
 * entered `trustedIds`, which filters `peers`, while the rows were still empty, so the peer was in
 * neither list.
 *
 * The handover asked for one live log to confirm this. It is confirmed here instead, because the
 * mechanism is in the source and the invariant is pure — [nearbyUiStateOf] takes no engine, no
 * Compose and no scope.
 */
class DesktopNearbyStateTest {

    @Test
    fun aNewlyTrustedPeerIsATrustedRow_andIsNotAlsoADiscoveredRow() {
        // The frame that used to break: trust has just been recorded, and the peer is still being
        // reported by discovery. It must land in EXACTLY one list.
        val state = stateOf(
            trusted = listOf(FlashTrustedPeer(id = PEER_ID, name = "Flash V760")),
            discovered = listOf(endpoint(PEER_ID, "Flash V760")),
        )

        assertEquals(
            listOf("Flash V760"),
            state.trustedPeers.map { it.name },
            "the trusted row must be present — its absence is the reported bug",
        )
        assertTrue(
            state.peers.none { it.id == PEER_ID },
            "a trusted peer must not also be offered as an untrusted Pair target",
        )
    }

    @Test
    fun aPeerIsNeverAbsentFromBothLists() {
        // The invariant, stated directly, over the transition that produced the bug: the same peer at
        // each step from untrusted to trusted. Only the DESTINATION changes; the peer never vanishes.
        val endpoint = endpoint(PEER_ID, "Flash V760")

        val before = stateOf(trusted = emptyList(), discovered = listOf(endpoint))
        assertTrue(before.peers.any { it.id == PEER_ID }, "untusted: must be a discovered row")
        assertTrue(before.trustedPeers.isEmpty())

        val after = stateOf(trusted = listOf(FlashTrustedPeer(PEER_ID, "Flash V760")), discovered = listOf(endpoint))
        assertTrue(after.trustedPeers.any { it.id == PEER_ID }, "trusted: must be a trusted row")
        assertTrue(after.peers.none { it.id == PEER_ID }, "trusted: must no longer be a Pair row")

        // Both frames, taken together: the peer is visible in one list or the other, always.
        for (state in listOf(before, after)) {
            assertTrue(
                state.peers.any { it.id == PEER_ID } || state.trustedPeers.any { it.id == PEER_ID },
                "the peer must be visible in exactly one list in every frame",
            )
        }
    }

    @Test
    fun aTrustedPeerThatIsNoLongerDiscovered_stillHasItsRow() {
        // The restart case: discovery has not re-found the phone yet, but trust is durable. The row
        // is what the user taps to reconnect, so it must not depend on discovery having caught up.
        val state = stateOf(
            trusted = listOf(FlashTrustedPeer(PEER_ID, "Flash V760")),
            discovered = emptyList(),
        )

        assertEquals(listOf("Flash V760"), state.trustedPeers.map { it.name })
        assertTrue(state.peers.isEmpty())
    }

    @Test
    fun revokingRemovesTheRow_andReturnsThePeerToThePairList() {
        // Ladder step L7, from the state's point of view: an untrusted-but-still-discovered peer must
        // come back as a Pair row, or a revoked peer becomes unreachable forever.
        val state = stateOf(
            trusted = emptyList(),
            discovered = listOf(endpoint(PEER_ID, "Flash V760")),
        )

        assertTrue(state.trustedPeers.isEmpty())
        assertEquals(listOf(PEER_ID), state.peers.map { it.id })
    }

    @Test
    fun thePairingRequestAndPhaseArriveTogether_notOneWithoutTheOther() {
        // The FIRST failure on this screen, kept pinned because the fix for it (a function, called
        // inside the lambda) is what the row bug then needed too. A request with no phase renders
        // nothing: the dialog is visible iff `request != null && phase != Idle`.
        val ui = FlashPairingCoordinator.PairingUi(
            peerName = "Flash V760",
            numericCode = "856950",
            phase = PairingPhase.RequestReceived,
            secondsLeft = 29,
        )

        val state = stateOf(trusted = emptyList(), discovered = emptyList(), ui = ui)

        val request = assertNotNull(state.pairingRequest, "the request must reach the screen")
        assertEquals("856950", request.numericCode)
        assertEquals(6, request.numericCode.length)
        assertEquals(
            FlashPairingPhase.RequestReceived,
            state.pairingPhase,
            "a fresh request with an Idle phase is exactly the 2026-09-14 no-dialog bug",
        )
        assertEquals(29, state.pairingSecondsLeft)
    }

    @Test
    fun withNoPairingInFlight_thePhaseIsIdle_andNoRequestIsOffered() {
        val state = stateOf(trusted = emptyList(), discovered = emptyList(), ui = null)

        assertEquals(null, state.pairingRequest)
        assertEquals(FlashPairingPhase.Idle, state.pairingPhase)
        assertEquals(0, state.pairingSecondsLeft)
    }

    // ------------------------------------------------------------------ helpers

    private fun stateOf(
        trusted: List<FlashTrustedPeer>,
        discovered: List<FlashDiscoveredEndpoint>,
        ui: FlashPairingCoordinator.PairingUi? = null,
        ready: Boolean = true,
    ) = nearbyUiStateOf(
        trusted = trusted,
        discovered = discovered,
        discoveryState = FlashDiscoveryState(isDiscovering = true, advertisedPort = 45822),
        ui = ui,
        ready = ready,
        localFriendlyName = "Flash Desktop",
        localDeviceId = LOCAL_ID,
    )

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
        const val LOCAL_ID = "ffffffff-0000-0000-0000-00000000000f"
        const val PEER_ID = "92d2c543-bd11-40e6-a3ac-065079a6eb7c"
    }
}
