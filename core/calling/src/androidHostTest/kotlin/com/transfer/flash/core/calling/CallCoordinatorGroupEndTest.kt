package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERROR-086 at the coordinator: a finished group session must release the one-call-at-a-time slot
 * at once. Before the fix the coordinator kept a session whose `onEnded` had never run as the live
 * call, so `hangUp()` did nothing and every later call, incoming or outgoing, was refused until
 * the app was force-stopped.
 *
 * An incoming invite only rings (no native media), so the whole path runs on the host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallCoordinatorGroupEndTest {

    private val host = "host-1"
    private val groupId = "group-1"

    private fun TestScope.coordinator() = CallCoordinator(
        localDeviceId = "me-0001",
        localName = "Me",
        scope = backgroundScope,
        sendFrame = { _, _ -> true },
        isTrustedPeer = { it == host },
    )

    private fun invite(callId: String) = CallFrameCodec.encode(
        CallWireFrame.GroupInvite(callId = callId, from = host, groupId = groupId, callerName = "G", video = false),
    )

    private fun hangup(callId: String) = CallFrameCodec.encode(
        CallWireFrame.GroupHangup(callId = callId, from = host, groupId = groupId),
    )

    @Test
    fun `a ringing group call the caller hangs up on ends and frees the coordinator at once`() = runTest {
        val c = coordinator()
        // What the call overlay showed, in order. Asserting on the final value would race with the
        // 2 s overlay clear: runTest skips virtual time while the native teardown runs on a real thread.
        val seen = mutableListOf<Pair<String?, FlashCallState?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.activeCall.collect { seen += it?.callId to it?.state }
        }

        assertTrue(c.onInboundText(host, invite("call-1")))
        runCurrent()
        assertEquals(FlashCallState.RINGING, c.activeCall.value?.state)

        assertTrue(c.onInboundText(host, hangup("call-1")))
        runCurrent()
        assertTrue("the overlay must have shown the ended call", ("call-1" to FlashCallState.ENDED) in seen)

        // The very next call is accepted; the coordinator does not wait for the overlay to clear.
        assertTrue(c.onInboundText(host, invite("call-2")))
        runCurrent()
        assertEquals("call-2", c.activeCall.value?.callId)
        assertEquals(FlashCallState.RINGING, c.activeCall.value?.state)

        // The first call's delayed overlay clear must not wipe the second call.
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals("call-2", c.activeCall.value?.callId)
    }

    @Test
    fun `an unanswered group call stops ringing by itself and the overlay clears`() = runTest {
        val c = coordinator()
        assertTrue(c.onInboundText(host, invite("call-1")))
        runCurrent()

        advanceTimeBy(46_000L)
        runCurrent()
        assertEquals(FlashCallState.ENDED, c.activeCall.value?.state)
        assertEquals(FlashCallEndReason.NO_ANSWER, c.activeCall.value?.endReason)

        advanceTimeBy(2_500L)
        runCurrent()
        assertNull("the ended call's overlay is cleared shortly after", c.activeCall.value)
    }

    @Test
    fun `an invite that arrives while an ended group session still holds the slot rings instead of being declined busy`() = runTest {
        // ERROR-095: the start paths dropped such a zombie, the inbound path did not, so the device answered every group
        // invite with an instant "busy" decline (140 ms in the report) until the app was restarted.
        val sent = mutableListOf<Pair<CallWireFrame, String>>()
        val c = CallCoordinator(
            localDeviceId = "me-0001",
            localName = "Me",
            scope = backgroundScope,
            sendFrame = { frame, peer ->
                sent += frame to peer
                true
            },
            isTrustedPeer = { it == host },
        )
        val zombie = FlashGroupCallSession(
            callId = "old-call",
            groupId = groupId,
            groupName = "G",
            direction = FlashCallDirection.INCOMING,
            video = false,
            localDeviceId = "me-0001",
            localName = "Me",
            scope = backgroundScope,
            sendFrame = { _, _ -> true },
            onEnded = {}, // the coordinator is never told: that is what makes it a zombie
            nowMs = { 1_700_000_000_000L + testScheduler.currentTime },
        )
        zombie.startIncomingRinging(peerId = host, callerName = "Host")
        zombie.onInboundFrame(CallWireFrame.GroupHangup(callId = "old-call", from = host, groupId = groupId), peerId = host)
        runCurrent()
        assertTrue("the old call has ended", zombie.isSessionEnded)
        c.installGroupSessionForTesting(zombie)

        assertTrue(c.onInboundText(host, invite("call-2")))
        runCurrent()

        assertEquals("the new call rings", "call-2", c.activeCall.value?.callId)
        assertEquals(FlashCallState.RINGING, c.activeCall.value?.state)
        assertTrue("and nobody was told this device is busy", sent.none { it.first is CallWireFrame.GroupDecline })
    }

    @Test
    fun `a roster member the caller cannot trust is named as left out with the reason, not just dropped`() = runTest {
        val c = CallCoordinator(
            localDeviceId = "me-0001",
            localName = "Me",
            scope = backgroundScope,
            sendFrame = { _, _ -> true },
            isGroupMember = { peer, _ -> peer == "paired" },
        )

        val callable = c.callMembers(groupId, listOf("me-0001", "paired", "stranger", "paired"), "start")

        assertEquals(listOf("paired"), callable.members)
        assertEquals(mapOf("stranger" to "Not paired with you"), callable.leftOut)
    }

    @Test
    fun `hangUp after a group call has ended does not report a live call and later calls ring`() = runTest {
        val c = coordinator()
        assertTrue(c.onInboundText(host, invite("call-1")))
        runCurrent()
        assertTrue(c.onInboundText(host, hangup("call-1")))
        runCurrent()

        assertFalse("there is no live call to hang up", c.hangUp())

        assertTrue(c.onInboundText(host, invite("call-2")))
        runCurrent()
        assertEquals(FlashCallState.RINGING, c.activeCall.value?.state)
    }
}
