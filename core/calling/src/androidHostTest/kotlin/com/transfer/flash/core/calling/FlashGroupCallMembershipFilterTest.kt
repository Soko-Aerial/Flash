package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERROR-103: the participant list showed devices that are not in the group (and, for an unpaired member, its raw id).
 * A live call's frames name devices (an invite's member list, a relayed join, a presence); only an active roster member
 * may get a leg or a tile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashGroupCallMembershipFilterTest {

    private val callId = "call-1"
    private val groupId = "group-1"
    private val roster = setOf("a", "b")

    private fun TestScope.session() = FlashGroupCallSession(
        callId = callId,
        groupId = groupId,
        groupName = "G",
        direction = FlashCallDirection.INCOMING,
        video = false,
        localDeviceId = "me",
        localName = "Me",
        scope = backgroundScope,
        sendFrame = { _, _ -> true },
        onEnded = {},
        isGroupMember = { it in roster },
        nowMs = { BASE + testScheduler.currentTime },
    )

    private fun FlashGroupCallSession.shown() = state.value.participants.map { it.peerId }.toSet()

    @Test
    fun `an invite naming a device outside the group gives it no tile`() = runTest {
        val s = session()
        s.startIncomingRinging(peerId = "a", callerName = "A", members = listOf("me", "a", "b", "ghost"))

        s.onInboundFrame(
            CallWireFrame.GroupInvite(callId, from = "a", groupId = groupId, callerName = "A", video = false, members = listOf("me", "a", "b", "ghost")),
            peerId = "a",
        )
        runCurrent()

        assertEquals(setOf("a", "b"), s.shown())
    }

    @Test
    fun `a relayed join of a device outside the group is ignored`() = runTest {
        val s = session()
        s.startIncomingRinging(peerId = "a", callerName = "A")

        s.onInboundFrame(CallWireFrame.GroupJoin(callId, from = "ghost", groupId = groupId, participantName = "Ghost"), peerId = "a")
        s.onInboundFrame(CallWireFrame.GroupAccept(callId, from = "ghost", groupId = groupId), peerId = "ghost")
        runCurrent()

        assertEquals(setOf("a"), s.shown())
    }

    @Test
    fun `a relayed join of a roster member still gets a tile`() = runTest {
        val s = session()
        s.startIncomingRinging(peerId = "a", callerName = "A")

        s.onInboundFrame(CallWireFrame.GroupJoin(callId, from = "b", groupId = groupId, participantName = "B"), peerId = "a")
        runCurrent()

        assertEquals(setOf("a", "b"), s.shown())
    }

    @Test
    fun `an invite from a device outside the group is dropped`() = runTest {
        val s = session()
        s.startIncomingRinging(peerId = "a", callerName = "A")

        s.onInboundFrame(
            CallWireFrame.GroupInvite(callId, from = "ghost", groupId = groupId, callerName = "G", video = false, members = listOf("b")),
            peerId = "ghost",
        )
        runCurrent()

        assertEquals(setOf("a"), s.shown())
    }

    companion object {
        private const val BASE = 1_700_000_000_000L
    }
}
