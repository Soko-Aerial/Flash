package com.transfer.flash.ui.calling

import com.transfer.flash.core.calling.model.FlashCallAudioRoute
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallReactionKind
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashLinkQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** UI-050f / ADR-067: the wording and the motion math of the in-call extras. */
class FlashCallExtrasTest {

    private companion object {
        /** Float arithmetic at the segment edges is exact only to a few ulps; the eye needs 1/255. */
        const val ALPHA_TOLERANCE = 0.004f
    }

    private fun call(isGroup: Boolean = false, participants: List<FlashCallParticipantUi> = emptyList()) = FlashCallUiState(
        callId = "c", peerId = "peer-1", peerName = "Alex", direction = FlashCallDirection.OUTGOING,
        video = true, state = FlashCallState.ACTIVE, isGroup = isGroup, participants = participants,
    )

    @Test
    fun `every route has its own word, hint and glyph`() {
        val routes = FlashCallAudioRoute.entries
        assertEquals(routes.size, routes.map { CallExtrasText.routeLabel(it) }.toSet().size)
        assertEquals(routes.size, routes.map { CallExtrasText.routeSubtitle(it) }.toSet().size)
        assertEquals(routes.size, routes.map { routeIcon(it).drawableRes }.toSet().size)
    }

    @Test
    fun `the route button tells what is in force and that it opens a list`() {
        assertEquals(
            "Audio output: Bluetooth. Choose another",
            CallDockText.routeDescription(FlashCallAudioRoute.BLUETOOTH),
        )
        // The two-state wording is untouched for a phone with no headset.
        assertEquals("Switch to speaker", CallDockText.routeDescription(false))
    }

    @Test
    fun `reactions have distinct glyphs and announcements`() {
        val kinds = FlashCallReactionKind.entries
        assertEquals(kinds.size, kinds.map { reactionIcon(it).drawableRes }.toSet().size)
        assertEquals("Sam reacted: Love", CallExtrasText.reactionAnnouncement("Sam", FlashCallReactionKind.LOVE))
    }

    @Test
    fun `a floating reaction fades in, holds and is gone at the end`() {
        assertEquals(0f, reactionFloatAlpha(0f), ALPHA_TOLERANCE)
        assertEquals(1f, reactionFloatAlpha(0.08f), ALPHA_TOLERANCE)
        assertEquals(1f, reactionFloatAlpha(0.5f), ALPHA_TOLERANCE)
        assertEquals(1f, reactionFloatAlpha(0.6f), ALPHA_TOLERANCE)
        assertTrue(reactionFloatAlpha(0.8f) in 0.4f..0.6f)
        assertEquals(0f, reactionFloatAlpha(1f), ALPHA_TOLERANCE)
        // Out-of-range progress (an overshooting spring) cannot give an out-of-range alpha.
        assertEquals(0f, reactionFloatAlpha(-1f), ALPHA_TOLERANCE)
        assertEquals(0f, reactionFloatAlpha(2f), ALPHA_TOLERANCE)
    }

    @Test
    fun `neighbouring reactions take different lanes and stay on screen`() {
        val lanes = (1L..12L).map { reactionLaneDp(it) }
        assertTrue(lanes.all { it in -45..44 }, "$lanes")
        assertTrue(lanes.toSet().size > 6, "lanes should spread, got $lanes")
        assertNotEquals(reactionLaneDp(1), reactionLaneDp(2))
        assertTrue(reactionLaneDp(Long.MIN_VALUE + 1) in -45..44)
    }

    @Test
    fun `badge words name only what is on`() {
        assertEquals("", CallExtrasText.badgeDescription(false, false, false))
        assertEquals("Microphone off", CallExtrasText.badgeDescription(true, false, false))
        assertEquals("Microphone off, Camera off, Hand raised", CallExtrasText.badgeDescription(true, true, true))
    }

    @Test
    fun `the link description grades the link and the shield claims only the pairing check`() {
        assertTrue(CallExtrasText.linkDescription(FlashLinkQuality.UNKNOWN).contains("not measured"))
        assertTrue(CallExtrasText.linkDescription(FlashLinkQuality.POOR).contains("poor"))
        assertTrue(CallExtrasText.VERIFIED_DESCRIPTION.contains("paired"))
        assertTrue(!CallExtrasText.VERIFIED_DESCRIPTION.contains("end-to-end", ignoreCase = true))
    }

    @Test
    fun `the hand row says what the tap will do`() {
        assertEquals("Raise hand", CallExtrasText.handLabel(false))
        assertEquals("Lower hand", CallExtrasText.handLabel(true))
    }

    @Test
    fun `a reaction is credited to the peer, a participant, or else this device`() {
        val oneToOne = call()
        assertEquals("Alex", reactionSenderName(oneToOne, "peer-1"))
        assertEquals("You", reactionSenderName(oneToOne, "my-device"))

        val group = call(
            isGroup = true,
            participants = listOf(FlashCallParticipantUi(peerId = "p2", name = "Sam")),
        )
        assertEquals("Sam", reactionSenderName(group, "p2"))
        // In a group the call's peerId is the group id, not a device: it is never a sender name.
        assertEquals("You", reactionSenderName(group, "peer-1"))
        assertEquals("You", reactionSenderName(group, "my-device"))
    }

    @Test
    fun `a connected participant's own camera-off and raised hand reach the status word`() {
        val p = FlashCallParticipantUi(peerId = "p", name = "Sam", state = FlashCallParticipantState.CONNECTED)
        assertEquals("Camera off", participantStatusLabel(p.copy(cameraOff = true)))
        assertEquals("Hand raised", participantStatusLabel(p.copy(handRaised = true)))
        // Muted outranks both: it is what blocks the conversation.
        assertEquals("Muted", participantStatusLabel(p.copy(isMuted = true, handRaised = true, cameraOff = true)))
        assertEquals(null, participantStatusLabel(p))
    }
}
