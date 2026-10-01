package com.transfer.flash.ui.calling

import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallHealthWarning
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashParticipantVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI-050b (G1): where each participant's tile goes. */
class FlashGroupVideoGridTest {

    @Test
    fun `rows per participant count, tall and wide`() {
        assertEquals(emptyList(), groupVideoRows(0, wide = false))
        assertEquals(listOf(1), groupVideoRows(1, wide = false))
        assertEquals(listOf(1, 1), groupVideoRows(2, wide = false))
        assertEquals(listOf(2), groupVideoRows(2, wide = true))
        assertEquals(listOf(2, 1), groupVideoRows(3, wide = false))
        assertEquals(listOf(2, 1), groupVideoRows(3, wide = true))
        assertEquals(listOf(2, 2), groupVideoRows(4, wide = false))
        assertEquals(listOf(2, 2, 1), groupVideoRows(5, wide = false))
        assertEquals(listOf(3, 2), groupVideoRows(5, wide = true))
    }

    @Test
    fun `one tile fills the box with no gap`() {
        assertEquals(listOf(TileRect(0, 0, 1080, 2000)), groupVideoTileRects(1, 1080, 2000, gap = 8))
    }

    @Test
    fun `tiles stay inside the box, never overlap, and a short last row is wider`() {
        for (count in 1..5) {
            for ((w, h) in listOf(1080 to 2000, 2000 to 1080, 800 to 800)) {
                val rects = groupVideoTileRects(count, w, h, gap = 8)
                assertEquals(count, rects.size)
                for (r in rects) {
                    assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.width <= w && r.y + r.height <= h, "$count $w×$h $r")
                    assertTrue(r.width > 0 && r.height > 0)
                }
                for (i in rects.indices) for (j in i + 1 until rects.size) {
                    val a = rects[i]
                    val b = rects[j]
                    val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
                    assertTrue(!overlap, "$count $w×$h: $a overlaps $b")
                }
            }
        }
        val three = groupVideoTileRects(3, 1080, 2000, gap = 8)
        assertTrue(three[2].width > three[0].width, "the lone third tile spans the row")
    }

    @Test
    fun `a tile shows video only while it arrives, and says why not`() {
        assertTrue(FlashParticipantVideo.RECEIVING.hasPicture())
        assertTrue(FlashParticipantVideo.UNMANAGED.hasPicture(), "an old client always sends")
        listOf(FlashParticipantVideo.OFF, FlashParticipantVideo.REQUESTED, FlashParticipantVideo.BUSY, FlashParticipantVideo.CAMERA_OFF)
            .forEach { assertFalse(it.hasPicture(), "$it") }
        val p = FlashCallParticipantUi(peerId = "p", name = "P", state = FlashCallParticipantState.CONNECTED)
        assertEquals("Video busy", participantStatusLabel(p.copy(video = FlashParticipantVideo.BUSY)))
        assertEquals("Camera off", participantStatusLabel(p.copy(video = FlashParticipantVideo.CAMERA_OFF)))
        assertEquals("Muted", participantStatusLabel(p.copy(isMuted = true, video = FlashParticipantVideo.BUSY)))
        assertEquals(null, participantStatusLabel(p.copy(video = FlashParticipantVideo.RECEIVING)))
    }

    @Test
    fun `an invitee the invite has not reached says so and one that was reached is just invited`() {
        // ERROR-088: the caller's tile for a member with no session yet must not read as "Invited".
        val p = FlashCallParticipantUi(peerId = "p", name = "P", state = FlashCallParticipantState.INVITED)
        assertEquals("Invited", participantStatusLabel(p), "reachable is the default")
        assertEquals("Not reachable yet", participantStatusLabel(p.copy(reachable = false)))
        assertEquals("Connecting…", participantStatusLabel(p.copy(state = FlashCallParticipantState.CONNECTING, reachable = false)))
    }

    @Test
    fun `the compact main tile falls back from the core's choice to a live video to the first person`() {
        val a = FlashCallParticipantUi(peerId = "a", name = "A")
        val b = FlashCallParticipantUi(peerId = "b", name = "B", video = FlashParticipantVideo.RECEIVING)
        val gone = FlashCallParticipantUi(peerId = "g", name = "G", state = FlashCallParticipantState.LEFT)
        val state = call(listOf(gone, a, b))
        assertEquals("b", groupVideoMainPeer(state))
        assertEquals("a", groupVideoMainPeer(state.copy(videoMainPeerId = "a")))
        assertEquals("b", groupVideoMainPeer(state.copy(videoMainPeerId = "g")), "a participant who left is not shown")
        assertEquals("a", groupVideoMainPeer(call(listOf(a))))
        assertEquals(null, groupVideoMainPeer(call(listOf(gone))))
    }

    @Test
    fun `a tap pins, and a tap on the pinned person unpins`() {
        val state = call(emptyList())
        assertEquals("a", nextVideoFocus(state, "a"))
        assertEquals(null, nextVideoFocus(state.copy(videoFocusPeerId = "a"), "a"))
        assertEquals("b", nextVideoFocus(state.copy(videoFocusPeerId = "a"), "b"))
        assertEquals("Pin Ann's video", videoFocusClickLabel("Ann", pinned = false))
        assertEquals("Unpin Ann's video", videoFocusClickLabel("Ann", pinned = true))
    }

    @Test
    fun `each health warning has its words, and only the hot one offers no action`() {
        FlashCallHealthWarning.entries.forEach { assertTrue(healthWarningText(it).isNotBlank()) }
        assertEquals(
            "Your phone is warming up. Showing fewer videos saves battery.",
            healthWarningText(FlashCallHealthWarning.WARM),
        )
        assertFalse(healthWarningOffersShowFewer(FlashCallHealthWarning.HOT))
        assertTrue(healthWarningOffersShowFewer(FlashCallHealthWarning.WARM))
        assertTrue(healthWarningOffersShowFewer(FlashCallHealthWarning.CPU))
        assertTrue(healthWarningOffersShowFewer(FlashCallHealthWarning.SOFTWARE_DECODE))
    }

    @Test
    fun `only the CPU warning offers send smaller, and only while the setting is off`() {
        assertTrue(healthWarningOffersSmallerVideo(FlashCallHealthWarning.CPU, alreadyOn = false))
        assertFalse(healthWarningOffersSmallerVideo(FlashCallHealthWarning.CPU, alreadyOn = true))
        FlashCallHealthWarning.entries.filter { it != FlashCallHealthWarning.CPU }.forEach {
            assertFalse(healthWarningOffersSmallerVideo(it, alreadyOn = false), "$it")
        }
        val offered = healthWarningText(FlashCallHealthWarning.CPU, offersSmallerVideo = true)
        assertTrue(offered.contains("smaller"), offered)
        assertFalse(healthWarningText(FlashCallHealthWarning.CPU).contains("smaller"))
    }

    private fun call(participants: List<FlashCallParticipantUi>) = FlashCallUiState(
        callId = "c", peerId = "g", peerName = "Group", direction = FlashCallDirection.OUTGOING,
        video = true, state = FlashCallState.ACTIVE, isGroup = true, participants = participants, compactVideo = true,
    )
}
