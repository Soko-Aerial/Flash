package com.transfer.flash.ui.calling

import com.transfer.flash.core.calling.FlashShareNotice
import com.transfer.flash.core.calling.ShareQuality
import com.transfer.flash.core.calling.ShareSource
import com.transfer.flash.core.calling.ShareSourceKind
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI-050g, ADR-102: the wording, who presents, which tile is the stage, and when the Share row is offered. */
class FlashCallShareTest {

    private fun state(
        video: Boolean = true,
        group: Boolean = false,
        presenterId: String? = null,
        sharing: Boolean = false,
        canShare: Boolean = true,
        starting: Boolean = false,
        participants: List<FlashCallParticipantUi> = emptyList(),
    ) = FlashCallUiState(
        callId = "c", peerId = if (group) "g" else "p", peerName = if (group) "Team" else "Ana",
        direction = FlashCallDirection.OUTGOING, video = video, state = FlashCallState.ACTIVE,
        isGroup = group, participants = participants, presenterId = presenterId, sharing = sharing,
        canShareScreen = canShare, shareStarting = starting,
    )

    private fun person(id: String, name: String, state: FlashCallParticipantState = FlashCallParticipantState.CONNECTED) =
        FlashCallParticipantUi(peerId = id, name = name, state = state)

    private val host = FlashCallShareHost(
        listSources = { listOf(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN)) },
        usesSystemPicker = false,
        onStart = { _, _, _ -> },
        onStop = {},
    )

    // ---- wording

    @Test
    fun `the indicator says what is shared and who sees it`() {
        assertEquals("You are sharing Screen 1", CallShareText.indicatorLive("Screen 1"))
        assertEquals("You are sharing your screen", CallShareText.indicatorLive(null))
        assertEquals("You are sharing your screen", CallShareText.indicatorLive(" "))
        assertEquals("Nobody is watching yet", CallShareText.watchersLine(0))
        assertEquals("Seen by 1 person", CallShareText.watchersLine(1))
        assertEquals("Seen by 3 people", CallShareText.watchersLine(3))
    }

    @Test
    fun `the receiver label and the panel rows read the way the design says`() {
        assertEquals("Ana is presenting", CallShareText.presenterLabel("Ana"))
        assertEquals("Share screen", CallShareText.ROW_START_TITLE)
        assertEquals("Stop sharing", CallShareText.ROW_STOP_TITLE)
        assertEquals("You are sharing Docs", CallShareText.rowStopSubtitle("Docs"))
        assertEquals("Ana is presenting. If you share, their share stops.", CallShareText.takeOverNote("Ana"))
    }

    @Test
    fun `every notice has a sentence and names the other presenter when it knows`() {
        for (n in FlashShareNotice.entries) {
            assertTrue(CallShareText.notice(n, "Ana", 4).isNotBlank(), n.toString())
            assertTrue(CallShareText.notice(n, null, 4).isNotBlank(), n.toString())
        }
        assertEquals("Ana started sharing, so yours stopped", CallShareText.notice(FlashShareNotice.TAKEN_OVER, "Ana", 4))
        assertEquals("Someone else started sharing, so yours stopped", CallShareText.notice(FlashShareNotice.TAKEN_OVER, null, 4))
        assertEquals("Only 3 people can watch your share at once", CallShareText.notice(FlashShareNotice.WATCHER_CAP, null, 3))
    }

    // ---- who presents

    @Test
    fun `in a one to one call the peer presents under its own name`() {
        val s = state(presenterId = "p")
        assertEquals("Ana", sharePresenterName(s))
        assertNull(sharePresenterName(state()))
    }

    @Test
    fun `in a group the presenter is found by id and an unknown one reads as someone`() {
        val s = state(group = true, presenterId = "b", participants = listOf(person("a", "Ana"), person("b", "Ben")))
        assertEquals("Ben", sharePresenterName(s))
        assertEquals("Someone", sharePresenterName(s.copy(presenterId = "zz")))
    }

    @Test
    fun `a presenter who left the group is not the stage`() {
        val s = state(group = true, presenterId = "b", participants = listOf(person("a", "Ana"), person("b", "Ben", FlashCallParticipantState.LEFT)))
        assertNull(groupPresenterPeer(s))
        assertEquals("b", groupPresenterPeer(s.copy(participants = listOf(person("b", "Ben")))))
    }

    // ---- fit and layout

    @Test
    fun `a presentation is letterboxed and a camera is not`() {
        assertEquals(CallVideoFit.Fit, videoFitFor(true))
        assertEquals(CallVideoFit.Balanced, videoFitFor(false))
    }

    @Test
    fun `the stage takes most of the height and the others share one row under it`() {
        val rects = groupVideoShareRects(count = 4, width = 1000, height = 1000, gap = 10)
        assertEquals(4, rects.size)
        val stage = rects[0]
        assertEquals(0, stage.x)
        assertEquals(0, stage.y)
        assertEquals(1000, stage.width)
        assertTrue(stage.height in 650..750, "the stage is about 70 percent: ${stage.height}")
        val row = rects.drop(1)
        assertTrue(row.all { it.y == stage.height + 10 }, row.toString())
        assertTrue(row.all { it.y + it.height <= 1000 }, "inside the box")
        assertTrue(row.zipWithNext().all { (a, b) -> a.x + a.width + 10 == b.x }, "one gap between neighbours")
        assertTrue(row.last().x + row.last().width <= 1000)
    }

    @Test
    fun `a presenter alone fills the box and nothing yields no tiles`() {
        assertEquals(listOf(TileRect(0, 0, 800, 600)), groupVideoShareRects(1, 800, 600, 8))
        assertTrue(groupVideoShareRects(0, 800, 600, 8).isEmpty())
    }

    @Test
    fun `tiles of a presentation layout never overlap the stage`() {
        for (count in 2..8) {
            val rects = groupVideoShareRects(count, 1920, 1080, 4)
            val stageBottom = rects[0].y + rects[0].height
            assertTrue(rects.drop(1).all { it.y >= stageBottom }, "count=$count")
        }
    }

    // ---- when the Share row is offered

    @Test
    fun `the share row needs a host, a video call that can present, and no share being opened`() {
        assertTrue(shareStartAvailable(state(), host))
        assertFalse(shareStartAvailable(state(), null), "Android today")
        assertFalse(shareStartAvailable(state(canShare = false), host))
        assertFalse(shareStartAvailable(state(video = false), host), "a voice call has no video to put the screen on")
        assertFalse(shareStartAvailable(state(starting = true), host))
    }

    @Test
    fun `while sharing the stop row stays available even if the core says it cannot start another`() {
        assertTrue(shareStartAvailable(state(sharing = true, canShare = false), host))
    }

    @Test
    fun `the camera button explains itself while sharing`() {
        assertEquals("Camera is off while sharing", CallDockText.videoDescription(cameraOff = false, sharing = true))
        assertEquals("Turn camera off", CallDockText.videoDescription(cameraOff = false, sharing = false))
        assertEquals("Turn camera on", CallDockText.videoDescription(cameraOff = true, sharing = false))
    }

    @Test
    fun `the start choice carries the take-over flag the host needs`() {
        var seen: Triple<ShareSource, ShareQuality, Boolean>? = null
        val h = FlashCallShareHost({ emptyList() }, false, { a, b, c -> seen = Triple(a, b, c) }, {})
        val source = ShareSource(2L, "Docs", ShareSourceKind.WINDOW)
        h.onStart(source, ShareQuality.LOWER, true)
        assertEquals(Triple(source, ShareQuality.LOWER, true), seen)
    }
}
