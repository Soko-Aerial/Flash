package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-102, the session halves that need no native media: what a session learns from a presenter's status frames, what it
 * puts on the wire when it never shared, and that a start with nothing to put the screen on changes nothing. The
 * capture, `replaceTrack` and the encoder tuning are device checks (SHARE-01...14); the state machine, the one-presenter
 * rule, the ladder and the router are tested in `ScreenShareTest` / `GroupVideoRouterShareTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashScreenShareSessionTest {

    private val callId = "call-0001"
    private val peerId = "peer-0001"
    private val sent = mutableListOf<CallWireFrame>()
    private val groupSent = mutableListOf<Pair<CallWireFrame, String>>()

    private object FakeProvider : ScreenCaptureProvider {
        override val supported = true
        override val usesSystemPicker = false
        var opens = 0
        override suspend fun listSources() = listOf(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN))
        override suspend fun open(source: ShareSource, fps: Int, maxWidth: Int, maxHeight: Int): ScreenCaptureHandle {
            opens++
            error("must not be opened without a video connection")
        }
    }

    private fun TestScope.oneToOne(video: Boolean = true): FlashCallSession = FlashCallSession(
        callId = callId, peerId = peerId, peerName = "Peer", direction = FlashCallDirection.OUTGOING, video = video,
        localDeviceId = "me-0001", localName = "Me", scope = this,
        sendFrame = { frame -> sent += frame; true },
        screenCapture = FakeProvider,
    ).also { it.setStateForTesting(FlashCallState.ACTIVE) }

    private fun status(sharing: Boolean?, at: Long? = null, cam: Boolean? = true) =
        CallWireFrame.Status(callId, peerId, cameraOn = cam, sharing = sharing, shareStartedAt = at)

    // ---- 1:1

    @Test
    fun `a peer that states a share is the presenter until it states the end`() = runTest {
        val s = oneToOne()
        assertNull(s.state.value.presenterId)
        s.onInboundFrame(status(sharing = true, at = 10L))
        assertEquals(peerId, s.state.value.presenterId)
        s.onInboundFrame(status(sharing = null))
        assertEquals("a status that does not mention sharing changes nothing", peerId, s.state.value.presenterId)
        s.onInboundFrame(status(sharing = false))
        assertNull(s.state.value.presenterId)
    }

    @Test
    fun `an older peer never states a share so nobody presents`() = runTest {
        val s = oneToOne()
        s.onInboundFrame(CallWireFrame.Status(callId, peerId, micOn = true, cameraOn = true))
        assertNull(s.state.value.presenterId)
        assertFalse(s.state.value.peerCameraOff)
    }

    @Test
    fun `a session that never shared does not state anything about sharing`() = runTest {
        val s = oneToOne()
        s.toggleMute()
        s.onInboundFrame(status(sharing = true, at = 10L))
        s.setHandRaised(true)
        testScheduler.advanceUntilIdle()
        val statuses = sent.filterIsInstance<CallWireFrame.Status>()
        assertTrue(statuses.isNotEmpty())
        assertTrue("an older peer must see exactly the frames it always saw: $statuses", statuses.all { it.sharing == null && it.shareStartedAt == null })
    }

    @Test
    fun `starting a share with no video connection changes nothing and sends nothing`() = runTest {
        val s = oneToOne()
        val source = ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN)
        assertFalse(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        assertFalse(s.state.value.sharing)
        assertFalse(s.state.value.canShareScreen)
        assertNull(s.state.value.shareNotice)
        assertEquals(0, FakeProvider.opens)
        assertTrue(sent.none { it is CallWireFrame.Status && it.sharing != null })
    }

    @Test
    fun `stopping when nothing is shared is a no-op`() = runTest {
        val s = oneToOne()
        s.stopScreenShare()
        s.stopScreenShare()
        assertFalse(s.state.value.sharing)
        assertTrue(sent.none { it is CallWireFrame.Status && it.sharing != null })
    }

    @Test
    fun `the quality choice is remembered for the next share and the notice can be dismissed`() = runTest {
        val s = oneToOne()
        s.setShareQuality(ShareQuality.LOWER)
        assertEquals(ShareQuality.LOWER, s.state.value.shareQuality)
        s.dismissShareNotice()
        assertNull(s.state.value.shareNotice)
    }

    @Test
    fun `a platform that cannot present lists nothing`() = runTest {
        val s = FlashCallSession(
            callId = callId, peerId = peerId, peerName = "Peer", direction = FlashCallDirection.OUTGOING, video = true,
            localDeviceId = "me-0001", localName = "Me", scope = this,
            sendFrame = { true },
            screenCapture = NoScreenCapture,
        )
        assertTrue(s.listShareSources().isEmpty())
    }

    // ---- group

    private fun TestScope.group() = FlashGroupCallSession(
        callId = callId, groupId = "group-1", groupName = "G", direction = FlashCallDirection.OUTGOING, video = true,
        localDeviceId = "me", localName = "Me", scope = this,
        sendFrame = { frame, peer -> groupSent += frame to peer; true },
        onEnded = {},
        nowMs = { BASE + testScheduler.currentTime },
        screenCapture = FakeProvider,
    )

    private fun gstatus(from: String, sharing: Boolean?, at: Long? = null) =
        CallWireFrame.Status(callId, from, cameraOn = true, sharing = sharing, shareStartedAt = at)

    private fun TestScope.settle() {
        Thread.sleep(250)
        runCurrent()
    }

    @Test
    fun `in a group the latest presenter wins and its tile is marked`() = runTest {
        val s = group()
        s.addJoinedLegForTesting("a", FlashCallParticipantState.CONNECTED)
        s.addJoinedLegForTesting("b", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.onInboundFrame(gstatus("a", true, 100L), peerId = "a")
        settle()
        assertEquals("a", s.state.value.presenterId)
        assertEquals(listOf("a"), s.state.value.participants.filter { it.sharing }.map { it.peerId })
        s.onInboundFrame(gstatus("b", true, 200L), peerId = "b")
        settle()
        assertEquals("b", s.state.value.presenterId)
        assertEquals(listOf("b"), s.state.value.participants.filter { it.sharing }.map { it.peerId })
        s.onInboundFrame(gstatus("b", false), peerId = "b")
        settle()
        assertEquals("a is still presenting when b stops", "a", s.state.value.presenterId)
    }

    @Test
    fun `a presenter who hangs up is no longer the presenter`() = runTest {
        val s = group()
        s.addJoinedLegForTesting("a", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.onInboundFrame(gstatus("a", true, 100L), peerId = "a")
        settle()
        assertEquals("a", s.state.value.presenterId)
        s.onInboundFrame(CallWireFrame.GroupHangup(callId = callId, from = "a", groupId = "group-1"), peerId = "a")
        settle()
        assertNull(s.state.value.presenterId)
    }

    @Test
    fun `in a group a start with no video connection changes nothing and a never-shared session states no share`() = runTest {
        val s = group()
        s.addJoinedLegForTesting("a", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        assertFalse(s.startScreenShare(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN), ShareQuality.STANDARD, takeOver = false))
        settle()
        assertFalse(s.state.value.sharing)
        assertEquals(0, FakeProvider.opens)
        assertTrue(groupSent.none { (f, _) -> f is CallWireFrame.Status && f.sharing != null })
    }

    private companion object {
        const val BASE = 1_790_000_000_000L
    }
}
