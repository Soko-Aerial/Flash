package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashLogLevel
import com.transfer.flash.core.common.logging.FlashLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERROR-088 at the coordinator: a group call is built for the members of the group, not for the members this device
 * happens to be connected to, and an announcement (invite, presence, accept, join, query) dials the member first.
 *
 *  - `isGroupMember`: who the call is offered to (roster member this device trusts or was introduced to), no session needed;
 *  - `isGroupTrustedPeer`: may a frame go to / come from the connection that exists right now;
 *  - `reachPeer`: dials a member that has no connection.
 *
 * The announcement path is exercised through [CallCoordinator.sendGroupFrame] and [CallCoordinator.queryGroupCall]:
 * starting or joining a call opens the microphone, which a host test cannot.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlashInternalApi::class)
class CallCoordinatorGroupReachTest {

    private val groupId = "group-1"
    private val sent = mutableListOf<Pair<CallWireFrame, String>>()

    /** The devices that are members of the group. */
    private val members = mutableSetOf("b", "c")

    /** The devices with a live session that presents the key the group expects. */
    private val live = mutableSetOf<String>()

    /** The devices a dial succeeds for; a successful dial leaves a live session behind. */
    private val dialable = mutableSetOf<String>()
    private val dialed = mutableListOf<String>()
    private var dialFails = false
    private val logged = java.util.concurrent.CopyOnWriteArrayList<Pair<FlashLogLevel, String>>()

    /** The global log sink cannot be read back, so the tests that replaced it leave a discarding one behind. */
    @After
    fun discardLogs() = FlashLog.installSink(FlashLogSink { _, _, _, _ -> })

    private fun TestScope.coordinator() = CallCoordinator(
        localDeviceId = "me",
        localName = "Me",
        scope = backgroundScope,
        sendFrame = { frame, peer ->
            sent += frame to peer
            true
        },
        isTrustedPeer = { false }, // the point: nobody here is paired with this device
        isGroupTrustedPeer = { peer, _ -> peer in live },
        isGroupMember = { peer, _ -> peer in members },
        reachPeer = { peer ->
            dialed += peer
            if (dialFails) error("dial failed")
            if (peer in dialable) live += peer
            peer in live
        },
    )

    private fun presence() = CallWireFrame.GroupPresence(callId = "call-1", from = "me", groupId = groupId, callerName = "G", video = false)

    @Test
    fun `an announcement to a member with no session dials it and goes out once the session is up`() = runTest {
        dialable += "b"
        val c = coordinator()

        assertTrue(c.sendGroupFrame(groupId, presence(), "b"))

        assertEquals(listOf("b"), dialed)
        assertEquals(listOf("b"), sent.map { it.second })
    }

    @Test
    fun `an announcement to a member that cannot be reached yet is not sent and does not fail the call`() = runTest {
        val c = coordinator() // b is a member but cannot be dialed

        assertFalse(c.sendGroupFrame(groupId, presence(), "b"))

        assertEquals("the dial was tried", listOf("b"), dialed)
        assertTrue("and nothing went to a connection that is not there", sent.isEmpty())
    }

    @Test
    fun `an announcement to somebody who is not a member of the group is neither dialed nor sent`() = runTest {
        dialable += "stranger"
        val c = coordinator()

        assertFalse(c.sendGroupFrame(groupId, presence(), "stranger"))

        assertTrue("a device outside the group is never dialed for it", dialed.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a member whose live session fails the key check is dialed but never sent the announcement`() = runTest {
        // b is a member and answers the dial, but the session does not present the certified key: the gate says no.
        val c = CallCoordinator(
            localDeviceId = "me",
            localName = "Me",
            scope = backgroundScope,
            sendFrame = { frame, peer ->
                sent += frame to peer
                true
            },
            isGroupTrustedPeer = { _, _ -> false },
            isGroupMember = { peer, _ -> peer in members },
            reachPeer = { peer ->
                dialed += peer
                true
            },
        )

        assertFalse(c.sendGroupFrame(groupId, presence(), "b"))

        assertEquals(listOf("b"), dialed)
        assertTrue("the strict gate still has the last word", sent.isEmpty())
    }

    @Test
    fun `only announcements dial - an offer, a candidate and a farewell are sent as they were`() = runTest {
        val c = coordinator() // b has no session and cannot be dialed

        val frames = listOf(
            CallWireFrame.Offer(callId = "call-1", from = "me", sdp = "v=0"),
            CallWireFrame.IceCandidate(callId = "call-1", from = "me", sdpMid = "0", sdpMLineIndex = 0, candidate = "candidate:1"),
            CallWireFrame.GroupHangup(callId = "call-1", from = "me", groupId = groupId),
        )
        frames.forEach { assertTrue("$it", c.sendGroupFrame(groupId, it, "b")) }

        assertTrue("a connection step never dials (a dial would hold a leg's lock)", dialed.isEmpty())
        assertEquals(frames, sent.map { it.first })
    }

    @Test
    fun `a dial that throws is contained and the announcement is simply not sent`() = runTest {
        dialFails = true
        dialable += "b"
        val c = coordinator()

        assertFalse(c.sendGroupFrame(groupId, presence(), "b"))

        assertEquals(listOf("b"), dialed)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a member that is already connected is not dialed twice by the coordinator`() = runTest {
        live += "b"
        dialable += "b"
        val c = coordinator()

        assertTrue(c.sendGroupFrame(groupId, presence(), "b"))
        assertTrue(c.sendGroupFrame(groupId, presence(), "b"))

        // The coordinator asks every time; making that cheap for a live session is reachPeer's contract
        // (AutoConnector.ensureSession returns at once when a session exists).
        assertEquals(listOf("b", "b"), dialed)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a group query asks every member that can be reached, dialing the ones that need it`() = runTest {
        live += "c"
        dialable += "b"
        val c = coordinator()

        c.queryGroupCall(groupId, listOf("me", "b", "c", "stranger"))

        assertEquals("b through a dial, c over its session, nobody else", setOf("b", "c"), sent.map { it.second }.toSet())
        assertTrue(sent.all { it.first is CallWireFrame.GroupQuery })
        assertEquals("only the member without a session was dialed for", listOf("b"), dialed.filter { it == "b" })
        assertFalse("never a device outside the group", "stranger" in dialed)
    }

    @Test
    fun `a group call is not started when none of the listed devices is a member`() = runTest {
        members.clear()
        dialable += "b"
        val c = coordinator()

        assertFalse(c.startGroupCall(groupId, "G", listOf("me", "b", "c"), video = false))

        assertTrue(dialed.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(null, c.activeCall.value)
    }

    @Test
    fun `a group call is not joined when none of the listed devices is a member`() = runTest {
        members.clear()
        val c = coordinator()

        assertFalse(c.joinGroupCall(groupId, "call-1", listOf("me", "b"), video = false))

        assertTrue(dialed.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(null, c.activeCall.value)
    }

    @Test
    fun `a refusal that repeats every presence tick is logged once, and each peer and cause on its own`() = runTest {
        FlashLog.installSink { level, tag, message, _ -> if (tag == "GROUP_CALL") logged += level to message }
        val c = coordinator() // b is a member that cannot be dialed; stranger and other are not members

        repeat(5) { c.sendGroupFrame(groupId, presence(), "stranger") }
        repeat(5) { c.sendGroupFrame(groupId, presence(), "other") }
        repeat(5) { c.sendGroupFrame(groupId, presence(), "b") }

        val strangerLines = logged.filter { "stranger" in it.second }
        assertEquals("one line for five refusals", 1, strangerLines.size)
        assertEquals(FlashLogLevel.WARN, strangerLines.single().first)
        assertEquals(1, logged.count { "other" in it.second })
        val deferred = logged.filter { " to b " in it.second }
        assertEquals("a member that is only not connected yet is logged once, as info", 1, deferred.size)
        assertEquals(FlashLogLevel.INFO, deferred.single().first)
    }
}
