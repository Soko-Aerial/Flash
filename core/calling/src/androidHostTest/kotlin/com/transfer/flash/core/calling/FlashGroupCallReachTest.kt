package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashGroupCallLimits
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
    fun `a member the caller cannot call is shown with the reason, gets no frame and has no leg`() = runTest {
        // ERROR-095: a legacy group has no way to trust a member this device is not paired with.
        val s = session()
        s.markMediaAcquiredForTesting()
        reachable += "a"

        assertTrue(s.startOutgoing(listOf("a"), unavailable = mapOf("q" to "Not paired with you", "me" to "x", "a" to "y")))
        runCurrent()
        tick()

        val q = s.state.value.participants.filter { it.peerId == "q" }
        assertEquals("one tile for the member that cannot be called", 1, q.size)
        assertEquals("Not paired with you", q.single().note)
        assertEquals(FlashCallParticipantState.INVITED, q.single().state)
        assertEquals(false, q.single().reachable)
        assertEquals("a callable member never gets a note, and neither does this device", 2, s.state.value.participants.size)
        assertTrue(s.state.value.participants.filter { it.peerId != "q" }.all { it.note == null })
        assertEquals("it is not a leg", null, s.getLegStateForTesting("q"))
        assertTrue("nothing is sent to it, not an invite and not a presence tick", sent.none { it.second == "q" })

        // It joins through somebody that can reach it: it is a participant like any other now.
        s.addJoinedLegForTesting("q", FlashCallParticipantState.CONNECTED)
        s.onInboundFrame(CallWireFrame.GroupPresence(callId = callId, from = "a", groupId = groupId, callerName = "G", video = false), peerId = "a")
        runCurrent()
        val after = s.state.value.participants.filter { it.peerId == "q" }
        assertEquals(1, after.size)
        assertEquals(null, after.single().note)
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

    // ---------------------------------------------------------------- ERROR-096: devices in the same call that never connect

    private fun presenceFrom(from: String) = CallWireFrame.GroupPresence(callId = callId, from = from, groupId = groupId, callerName = "G", video = false)

    private fun acceptsTo(peer: String) = sent.count { (f, p) -> f is CallWireFrame.GroupAccept && p == peer }

    @Test
    fun `a presence from a member this device has no leg for connects it once this device is in the call`() = runTest {
        // The 2026-10-01 log: the desktop accepted a call whose other participant had joined while it rang, was never told
        // (the inviter's relayed join did not arrive) and so built no leg to it; that participant's presence was ignored.
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "host", callerName = "Host", members = listOf("host", "me", "z"))
        s.markMediaAcquiredForTesting()
        s.markConnectingForTesting()
        assertEquals("only the inviter has a leg", null, s.getLegStateForTesting("z"))

        s.onInboundFrame(presenceFrom("z"), peerId = "z")
        runCurrent()

        assertEquals("z is in the call: it has a leg and is connecting", FlashCallParticipantState.CONNECTING, s.getLegStateForTesting("z"))
    }

    @Test
    fun `a presence adds no leg while this device is still ringing, when relayed, or when the call is full`() = runTest {
        val ringing = session(FlashCallDirection.INCOMING)
        ringing.startIncomingRinging(peerId = "host", callerName = "Host", members = listOf("host", "me", "z"))
        ringing.onInboundFrame(presenceFrom("z"), peerId = "z")
        runCurrent()
        assertEquals("a ringing device is not in the call yet: no connection to build", null, ringing.getLegStateForTesting("z"))

        val joined = session(FlashCallDirection.INCOMING)
        joined.startIncomingRinging(peerId = "host", callerName = "Host", members = listOf("host", "me", "z"))
        joined.markMediaAcquiredForTesting()
        joined.markConnectingForTesting()
        joined.onInboundFrame(presenceFrom("z"), peerId = "host") // relayed: sent by someone else
        runCurrent()
        assertEquals("only a presence the member sent itself says it is in the call", null, joined.getLegStateForTesting("z"))

        val full = session(FlashCallDirection.INCOMING)
        full.startIncomingRinging(peerId = "host", callerName = "Host", members = emptyList())
        full.markMediaAcquiredForTesting()
        full.markConnectingForTesting()
        (1..FlashGroupCallLimits.maxParticipants(video = false)).forEach { full.addJoinedLegForTesting("p$it", FlashCallParticipantState.CONNECTED) }
        full.onInboundFrame(presenceFrom("late"), peerId = "late")
        runCurrent()
        assertEquals("a full call gives no tile to the member it turns away", null, full.getLegStateForTesting("late"))
        assertTrue("and tells it", sent.any { it.first is CallWireFrame.GroupFull && it.second == "late" })
    }

    @Test
    fun `accepting tells every other member the call was offered to, directly, and nobody twice`() = runTest {
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "host", callerName = "Host", members = listOf("host", "me", "c", "d", "c"))

        // What accept() does after it told the legs: the host already has its GroupAccept, so it is skipped.
        s.announceAcceptToOtherMembers(skip = setOf("host"))
        runCurrent()

        assertEquals(1, acceptsTo("c"))
        assertEquals(1, acceptsTo("d"))
        assertEquals("the inviter is told by accept() itself", 0, acceptsTo("host"))
        assertFalse("never itself", sent.any { it.second == "me" })
    }

    @Test
    fun `an answerer leg that gets no offer tells the peer it is in the call, escalating, and stops after three`() = runTest {
        val s = session() // "me" < "z": z must offer, this device waits for it
        s.addSettingUpLegForTesting("z", pcCreatedAtMs = BASE, heardAtMs = BASE + 1_000L) // z's presence keeps arriving
        s.markConnectingForTesting()
        s.armPresenceAnnouncement()
        runCurrent()
        assertEquals("not at once", 0, acceptsTo("z"))

        advanceTimeBy(7_900L)
        runCurrent()
        assertEquals("not before it has waited", 0, acceptsTo("z"))

        advanceTimeBy(300L) // 8.2 s
        runCurrent()
        assertEquals("after 8 s of silence the peer is told once", 1, acceptsTo("z"))

        advanceTimeBy(8_000L) // 16.2 s
        runCurrent()
        assertEquals(2, acceptsTo("z"))

        advanceTimeBy(40_000L)
        runCurrent()
        assertEquals("bounded", 3, acceptsTo("z"))
        assertEquals("and the leg is not torn down meanwhile: z is alive", FlashCallParticipantState.CONNECTING, s.getLegStateForTesting("z"))
    }

    @Test
    fun `the offerer side never nudges, the peer is waiting for its offer not the other way round`() = runTest {
        val s = session() // "a" < "me": this device offers
        s.addSettingUpLegForTesting("a", pcCreatedAtMs = BASE, heardAtMs = BASE + 1_000L)
        s.markConnectingForTesting()
        s.armPresenceAnnouncement()
        runCurrent()

        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(0, acceptsTo("a"))
    }

    companion object {
        /** A wall-clock-sized offset: 0 means "never" in the session's bookkeeping. */
        private const val BASE = 1_700_000_000_000L
    }
}
