package com.transfer.flash.ui.chat

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-351: an unpaired session is still a session (Flash admits it for pairing), so presence alone
 * must not read "Connected" after a revocation.
 */
class FlashChatHeaderLabelTest {

    private fun onlinePeer() = FlashChatHeaderUiState(
        title = "Flash Hippo",
        avatarInitials = "FH",
        presence = FlashPeerPresence.Online,
    )

    @Test
    fun aRevokedPeerReadsNotPairedEvenWhileTheTransportIsUp() {
        assertEquals("Not paired", headerStatusLabel(onlinePeer(), isPeerTrusted = false))
    }

    @Test
    fun aTrustedPeerKeepsTheConnectedCopy() {
        assertEquals("Connected", headerStatusLabel(onlinePeer(), isPeerTrusted = true))
    }
}
