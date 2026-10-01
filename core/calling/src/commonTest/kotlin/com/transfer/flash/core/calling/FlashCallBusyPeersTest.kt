package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ERROR-086 (k): the connection-mode controller protects the sessions of a call in progress, and
 * was handed the call's `peerId`, which for a group call is the group id (no device), so nothing
 * was protected. [FlashCallUiState.busyPeerIds] is the one rule all three hosts now use.
 */
class FlashCallBusyPeersTest {

    private fun call(
        state: FlashCallState,
        group: Boolean = false,
        vararg participants: Pair<String, FlashCallParticipantState>,
    ) = FlashCallUiState(
        callId = "c",
        peerId = if (group) "group-1" else "peer-1",
        peerName = "x",
        direction = FlashCallDirection.OUTGOING,
        video = false,
        state = state,
        isGroup = group,
        groupId = if (group) "group-1" else null,
        participants = participants.map { (id, st) -> FlashCallParticipantUi(peerId = id, name = id, state = st) },
    )

    @Test
    fun `a one to one call protects its peer`() {
        assertEquals(setOf("peer-1"), call(FlashCallState.ACTIVE).busyPeerIds)
        assertEquals(setOf("peer-1"), call(FlashCallState.DIALING).busyPeerIds)
    }

    @Test
    fun `an ended call protects nobody`() {
        assertEquals(emptySet(), call(FlashCallState.ENDED).busyPeerIds)
        assertEquals(
            emptySet(),
            call(FlashCallState.ENDED, group = true, "a" to FlashCallParticipantState.CONNECTED).busyPeerIds,
        )
    }

    @Test
    fun `a group call protects its participants and not the group id`() {
        val ids = call(
            FlashCallState.ACTIVE,
            group = true,
            "a" to FlashCallParticipantState.CONNECTED,
            "b" to FlashCallParticipantState.CONNECTING,
            "c" to FlashCallParticipantState.DISCONNECTED,
        ).busyPeerIds
        assertEquals(setOf("a", "b", "c"), ids)
    }

    @Test
    fun `in a live group call a member who is only invited or has left is not protected`() {
        val ids = call(
            FlashCallState.ACTIVE,
            group = true,
            "a" to FlashCallParticipantState.CONNECTED,
            "given-up-on" to FlashCallParticipantState.INVITED,
            "gone" to FlashCallParticipantState.LEFT,
        ).busyPeerIds
        assertEquals(setOf("a"), ids)
    }

    @Test
    fun `while a group call is dialing or ringing the people it waits for are protected`() {
        val dialing = call(
            FlashCallState.DIALING,
            group = true,
            "a" to FlashCallParticipantState.INVITED,
            "b" to FlashCallParticipantState.INVITED,
            "declined" to FlashCallParticipantState.LEFT,
        ).busyPeerIds
        assertEquals(setOf("a", "b"), dialing)

        // An incoming group call: the host's leg is INVITED until the user accepts.
        val ringing = call(FlashCallState.RINGING, group = true, "host" to FlashCallParticipantState.INVITED).busyPeerIds
        assertEquals(setOf("host"), ringing)
    }
}
