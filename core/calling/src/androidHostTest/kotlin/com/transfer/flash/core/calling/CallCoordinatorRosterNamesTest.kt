package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A group call labels a member this device is not paired with by the name its group roster stores, instead of the
 * "Member (a1b2)" fallback. Paired or discovered names still win. An incoming invite builds a leg for its caller only
 * (the other members are announce-only until they join), so the caller is the member these tests can observe; the
 * outgoing paths read the same cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallCoordinatorRosterNamesTest {

    private val host = "host-0000-0001"
    private val stranger = "stranger-0000-0002"
    private val groupId = "group-1"

    private fun TestScope.coordinator(
        rosterNames: suspend (String) -> Map<String, String>,
        pairedNames: Map<String, String> = emptyMap(),
    ) = CallCoordinator(
        localDeviceId = "me-0001",
        localName = "Me",
        scope = backgroundScope,
        sendFrame = { _, _ -> true },
        isTrustedPeer = { true },
        peerNameResolver = { pairedNames[it] },
        groupRosterNames = rosterNames,
    )

    private fun invite(callId: String) = CallFrameCodec.encode(
        CallWireFrame.GroupInvite(
            callId = callId,
            from = host,
            groupId = groupId,
            callerName = "",
            video = false,
            members = listOf(host, stranger),
        ),
    )

    private fun namesOf(c: CallCoordinator): Map<String, String> =
        c.activeCall.value?.participants.orEmpty().associate { it.peerId to it.name }

    @Test
    fun `an unpaired caller is labelled with its roster name`() = runTest {
        val c = coordinator(rosterNames = { mapOf(host to "Alice") })

        assertTrue(c.onInboundText(host, invite("call-1")))
        runCurrent()

        assertEquals("Alice", namesOf(c)[host])
    }

    @Test
    fun `a paired name wins over the roster name`() = runTest {
        val c = coordinator(
            rosterNames = { mapOf(host to "Roster Alice") },
            pairedNames = mapOf(host to "Paired Alice"),
        )

        assertTrue(c.onInboundText(host, invite("call-1")))
        runCurrent()

        assertEquals("Paired Alice", namesOf(c)[host])
    }

    @Test
    fun `a roster that cannot be read leaves the fallback label and the call still rings`() = runTest {
        val c = coordinator(rosterNames = { error("database closed") })

        assertTrue(c.onInboundText(host, invite("call-1")))
        runCurrent()

        assertEquals(FlashCallState.RINGING, c.activeCall.value?.state)
        assertEquals("Member (host)", namesOf(c)[host])
    }
}
