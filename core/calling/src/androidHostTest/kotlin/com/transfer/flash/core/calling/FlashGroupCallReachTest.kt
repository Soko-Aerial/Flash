package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERROR-088: a member of a group who is not paired with the caller (so the caller has no session to it yet) must still see
 * that the group is in a call and be able to join it. The session side of that:
 *
 *  - an invite that found no usable session is offered again by the presence tick while the invitee would still ring,
 *    and never again once it went out;
 *  - every member the call was offered to hears the presence tick, with or without a leg, so a member the caller could not
 *    reach still learns of the call from any participant that can reach it;
 *  - the caller's own screen says which invitee has not been reached.
 *
 * Time is virtual (the session's clock is the test scheduler plus [BASE]); no native media is touched
 * ([FlashGroupCallSession.markMediaAcquiredForTesting]). The frames pass through the coordinator's gate in the product;
 * here the `sendFrame` lambda stands in for it and decides which invite "finds a session".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashGroupCallReachTest {

    private val callId = "call-1"
    private val groupId = "group-1"
    private val sent = mutableListOf<Pair<CallWireFrame, String>>()

    /** Peers an invite goes through to; an invite to anybody else finds no session and reports `false`. */
    private val reachable = mutableSetOf<String>()

    private fun TestScope.session(direction: FlashCallDirection = FlashCallDirection.OUTGOING) = FlashGroupCallSession(
        callId = callId,
        groupId = groupId,
        groupName = "G",
        direction = direction,
        video = false,
        localDeviceId = "me",
        localName = "Me",
        scope = backgroundScope,
        sendFrame = { frame, peer ->
            sent += frame to peer
            frame !is CallWireFrame.GroupInvite || peer in reachable
        },
        onEnded = {},
        nowMs = { BASE + testScheduler.currentTime },
    )

    private fun invitesTo(peer: String) = sent.count { (f, p) -> f is CallWireFrame.GroupInvite && p == peer }
    private fun presencesTo(peer: String) = sent.count { (f, p) -> f is CallWireFrame.GroupPresence && p == peer }

    private fun FlashGroupCallSession.reachableOf(peer: String): Boolean? =
        state.value.participants.firstOrNull { it.peerId == peer }?.reachable

    /** One presence period plus a little, with everything that became due run. */
    private fun TestScope.tick() {
        advanceTimeBy(4_001L)
        runCurrent()
    }

    @Test
    fun `an invite that found no session is offered again by the presence tick until it is delivered`() = runTest {
        val s = session()
        s.markMediaAcquiredForTesting()
        reachable += "a"

        assertTrue(s.startOutgoing(listOf("a", "b")))
        runCurrent()

        assertEquals("a was reached at once", 1, invitesTo("a"))
        assertTrue("b was tried too", invitesTo("b") >= 1)
        assertEquals(true, s.reachableOf("a"))
        assertEquals("b is shown as not reached", false, s.reachableOf("b"))
        assertEquals("b is still an invited participant", FlashCallParticipantState.INVITED, s.getLegStateForTesting("b"))

        // b's session comes up (the dial finished, or b connected to us): the next tick delivers the invite.
        val before = invitesTo("b")
        reachable += "b"
        tick()

        assertEquals("one more invite reached b", before + 1, invitesTo("b"))
        assertEquals("and b is reachable now", true, s.reachableOf("b"))

        // Delivered invites are not repeated, undelivered ones would be: only presence goes on.
        val atDelivery = invitesTo("b")
        val presenceBefore = presencesTo("b")
        tick()
        tick()
        assertEquals("no invite is sent twice", atDelivery, invitesTo("b"))
        assertEquals("a was never re-invited", 1, invitesTo("a"))
        assertTrue("the presence tick keeps telling b the call is on", presencesTo("b") > presenceBefore)
    }

    @Test
    fun `an unreached invitee is offered the call only while it would still be ringing`() = runTest {
        val s = session()
        s.markMediaAcquiredForTesting()
        assertTrue(s.startOutgoing(listOf("b")))
        // Somebody joined, so the dial timeout does not end the call and the ticks go on.
        s.addJoinedLegForTesting("x", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        runCurrent()

        val first = invitesTo("b")
        tick()
        assertTrue("the invite is offered again inside the ring window", invitesTo("b") > first)

        advanceTimeBy(45_000L) // the ring window of an invitee
        runCurrent()
        val afterWindow = invitesTo("b")
        val presenceAfterWindow = presencesTo("b")

        tick()
        tick()
        assertEquals("the ring window is over: the invite is not offered any more", afterWindow, invitesTo("b"))
        assertTrue("but b still hears that the call is on", presencesTo("b") > presenceAfterWindow)
    }

    @Test
    fun `an incoming call tells every member of the invite that it is on, members without a leg included`() = runTest {
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "host", callerName = "Host", members = listOf("host", "me", "c", "d", "c"))
        // What accepting does after the media is open; the connection to the host is native and not built here.
        s.markMediaAcquiredForTesting()
        s.markConnectingForTesting()
        s.armPresenceAnnouncement()
        runCurrent()

        assertEquals("c was not invited by this device, only told", setOf("host", "c", "d"), sent.filter { it.first is CallWireFrame.GroupPresence }.map { it.second }.toSet())
        assertEquals("nobody is invited by an invitee", 0, sent.count { it.first is CallWireFrame.GroupInvite })
        assertEquals("a member is not a participant until it joins: only the host has a leg", listOf("host"), s.state.value.participants.map { it.peerId })
        assertEquals("the host's tile is not marked unreachable", true, s.reachableOf("host"))
    }

    @Test
    fun `the member list never includes this device or the inviter twice`() = runTest {
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "host", callerName = "Host", members = listOf("me", "host", "me"))
        s.markMediaAcquiredForTesting()
        s.markConnectingForTesting()
        s.armPresenceAnnouncement()
        runCurrent()

        assertFalse("never announces to itself", sent.any { it.second == "me" })
        assertEquals("the inviter once", 1, presencesTo("host"))
    }

    companion object {
        /** A wall-clock-sized offset: 0 means "never" in the session's bookkeeping. */
        private const val BASE = 1_700_000_000_000L
    }
}
