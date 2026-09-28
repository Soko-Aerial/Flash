package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashParticipantVideo
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.calling.protocol.VideoDenyReason
import com.transfer.flash.core.common.perf.FlashNetworkBand
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G3 request protocol, three (or more) devices in one process: every frame goes through the
 * real codec, the way it would over the signaling link. "Sending" is what each device's
 * encodings would be switched to.
 */
class GroupVideoRouterTest {

    private var now = 1_000_000L

    private inner class Node(val id: String, var limits: GroupVideoLimits, val legacy: Boolean = false) {
        val peers = mutableListOf<String>()
        val sendingTo = mutableSetOf<String>()
        val router = GroupVideoRouter(
            callId = CALL,
            localId = id,
            limits = { limits },
            participants = { peers.sorted() },
            clock = { now },
        )
    }

    private inner class Mesh(vararg nodes: Node) {
        val byId = nodes.associateBy { it.id }
        private val queue = ArrayDeque<Triple<String, String, String>>()
        val wire = mutableListOf<CallWireFrame>()

        init {
            nodes.forEach { node -> node.peers += nodes.map { it.id }.filter { it != node.id } }
        }

        fun apply(node: Node, effects: List<GroupVideoRouter.Effect>) {
            effects.forEach { effect ->
                when (effect) {
                    is GroupVideoRouter.Effect.Send -> queue += Triple(node.id, effect.peerId, CallFrameCodec.encode(effect.frame))
                    is GroupVideoRouter.Effect.Announce -> queue += Triple(node.id, effect.peerId, CallFrameCodec.encode(presence(node)))
                    is GroupVideoRouter.Effect.Sending -> if (effect.on) node.sendingTo += effect.peerId else node.sendingTo -= effect.peerId
                }
            }
        }

        fun presence(node: Node) = CallWireFrame.GroupPresence(
            callId = CALL, from = node.id, groupId = "g", callerName = "G", video = true,
            videoRequests = !node.legacy, videoFree = if (node.legacy) null else node.router.freeSlots(),
        )

        /** Everyone announces itself once (as the join frames do), then starts receiving. */
        fun join() {
            byId.values.forEach { from ->
                byId.values.filter { it != from }.forEach { to ->
                    queue += Triple(from.id, to.id, CallFrameCodec.encode(presence(from)))
                }
            }
            deliver()
            byId.values.filter { !it.legacy }.forEach { apply(it, it.router.startReceiving()) }
            deliver()
        }

        fun deliver() {
            while (queue.isNotEmpty()) {
                val (from, to, text) = queue.removeFirst()
                val frame = CallFrameCodec.decode(text)!!
                wire += frame
                val node = byId.getValue(to)
                if (node.legacy) {
                    // An old client drops actions it does not know and never asks for video.
                    assertTrue(frame is CallWireFrame.GroupPresence, "an old client was sent $frame")
                    continue
                }
                val r = node.router
                apply(
                    node,
                    when (frame) {
                        is CallWireFrame.GroupPresence -> r.onAnnouncement(from, frame.videoRequests, frame.videoFree)
                        is CallWireFrame.VideoRequest -> r.onRequest(from, frame)
                        is CallWireFrame.VideoGrant -> r.onGrant(from, frame)
                        is CallWireFrame.VideoDeny -> r.onDeny(from, frame)
                        is CallWireFrame.VideoRelease -> r.onRelease(from, frame)
                        else -> emptyList()
                    },
                )
            }
        }

        fun step(node: Node, effects: GroupVideoRouter.() -> List<GroupVideoRouter.Effect>) {
            apply(node, node.router.effects())
            deliver()
        }
    }

    private fun node(id: String, receive: Int, send: Int, legacy: Boolean = false) =
        Node(id, GroupVideoLimits(receive = receive, send = send, quality = 540), legacy)

    @Test
    fun `nobody who wants nothing is sent anything`() {
        val a = node("a", receive = 0, send = 5)
        val b = node("b", receive = 0, send = 5)
        val c = node("c", receive = 0, send = 5)
        val mesh = Mesh(a, b, c).apply { join() }
        assertTrue(listOf(a, b, c).all { it.sendingTo.isEmpty() })
        assertTrue(mesh.wire.none { it is CallWireFrame.VideoRequest })
    }

    @Test
    fun `each receiver gets what it asked for, and only that`() {
        val a = node("a", receive = 2, send = 5)
        val b = node("b", receive = 1, send = 5)
        val c = node("c", receive = 1, send = 5)
        Mesh(a, b, c).join()
        // a takes both; b and c take the first in order (a).
        assertEquals(setOf("b", "c"), a.sendingTo)
        assertEquals(setOf("a"), b.sendingTo)
        assertEquals(setOf("a"), c.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("a"))
        assertEquals(FlashParticipantVideo.OFF, b.router.receiveState("c"))
    }

    @Test
    fun `a tap moves the one video in a single round trip`() {
        val a = node("a", receive = 1, send = 5)
        val b = node("b", receive = 1, send = 5)
        val c = node("c", receive = 1, send = 5)
        val mesh = Mesh(a, b, c).apply { join() }
        assertEquals(setOf("a"), b.sendingTo)
        mesh.wire.clear()
        mesh.step(a) { setFocus("c") }
        // a released b and asked c: b stops sending to a, c starts.
        assertFalse("a" in b.sendingTo)
        assertTrue("a" in c.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, a.router.receiveState("c"))
        val frames = mesh.wire.map { it::class.simpleName }
        assertEquals(listOf("VideoRelease", "VideoRequest", "VideoGrant"), frames)
        assertTrue((mesh.wire[1] as CallWireFrame.VideoRequest).focus)
    }

    @Test
    fun `the send cap turns the extra watcher down and it takes the next participant`() {
        val a = node("a", receive = 1, send = 1)
        val b = node("b", receive = 1, send = 1)
        val c = node("c", receive = 1, send = 1)
        val d = node("d", receive = 1, send = 1)
        Mesh(a, b, c, d).join()
        // Everyone first wants "a" (or "b" for a); a serves one, the others move on.
        listOf(a, b, c, d).forEach { assertTrue(it.sendingTo.size <= 1, "${it.id} sends to ${it.sendingTo}") }
        val busyForA = listOf(b, c, d).filter { it.router.receiveState("a") == FlashParticipantVideo.BUSY }
        assertEquals(2, busyForA.size)
    }

    @Test
    fun `room frees up, the turned-down watcher is told and gets the video`() {
        val a = node("a", receive = 0, send = 1)
        val b = node("b", receive = 1, send = 1)
        val c = node("c", receive = 1, send = 1)
        val mesh = Mesh(a, b, c).apply { join() }
        // b got a; c was turned down by a and then asked b.
        assertEquals(setOf("b"), a.sendingTo)
        assertEquals(FlashParticipantVideo.BUSY, c.router.receiveState("a"))
        // b leaves: a has room, tells c, c asks again (a comes first in its order) and gets it.
        mesh.step(a) { onPeerLeft("b") }
        mesh.step(c) { onPeerLeft("b") }
        assertEquals(setOf("c"), a.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, c.router.receiveState("a"))
    }

    @Test
    fun `a talking sender at its cap makes room by dropping its oldest unpinned watcher`() {
        val a = node("a", receive = 0, send = 1)
        val b = node("b", receive = 1, send = 1)
        val c = node("c", receive = 1, send = 1)
        val mesh = Mesh(a, b, c).apply { join() }
        assertEquals(setOf("b"), a.sendingTo)
        a.router.setLocalSpeaking(true)
        now += 10
        mesh.step(c) { setFocus("a") }
        assertEquals(setOf("c"), a.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, c.router.receiveState("a"))
        assertEquals(FlashParticipantVideo.BUSY, b.router.receiveState("a"))
        // A pinned watcher is not dropped for the next one.
        mesh.step(b) { setFocus("a") }
        assertEquals(setOf("c"), a.sendingTo)
    }

    @Test
    fun `a late request never undoes a release`() {
        val a = node("a", receive = 0, send = 5)
        val b = node("b", receive = 1, send = 5)
        Mesh(a, b).join()
        assertEquals(setOf("b"), a.sendingTo)
        val stale = CallWireFrame.VideoRequest(CALL, "b", seq = 1, quality = 540)
        val release = CallWireFrame.VideoRelease(CALL, "b", seq = now + 50)
        a.router.onRelease("b", release)
        a.router.onRequest("b", stale).forEach { assertFalse(it is GroupVideoRouter.Effect.Sending && it.on) }
        assertFalse(a.router.isSending("b"))
        // A grant or deny for an old request is ignored by the receiver too.
        val bState = b.router.receiveState("a")
        b.router.onDeny("a", CallWireFrame.VideoDeny(CALL, "a", seq = 1, reason = VideoDenyReason.SENDER_AT_CAPACITY))
        assertEquals(bState, b.router.receiveState("a"))
    }

    @Test
    fun `an old client is sent video as before and never sent a new frame`() {
        val a = node("a", receive = 2, send = 5)
        val old = node("old", receive = 0, send = 0, legacy = true)
        val c = node("c", receive = 2, send = 5)
        Mesh(a, old, c).join()
        assertTrue("old" in a.sendingTo)
        assertTrue("old" in c.sendingTo)
        assertEquals(FlashParticipantVideo.UNMANAGED, a.router.receiveState("old"))
        // a and c still gate video between themselves.
        assertTrue("c" in a.sendingTo && "a" in c.sendingTo)
    }

    @Test
    fun `a peer that has not announced anything is sent nothing`() {
        val a = node("a", receive = 1, send = 5)
        a.peers += "x"
        a.router.startReceiving()
        assertFalse(a.router.isSending("x"))
        assertTrue(a.router.reconcile().isEmpty())
    }

    @Test
    fun `camera off turns requests down until the camera is back`() {
        val a = node("a", receive = 0, send = 5)
        val b = node("b", receive = 1, send = 5)
        a.router.setCameraOff(true)
        val mesh = Mesh(a, b).apply { join() }
        assertEquals(FlashParticipantVideo.CAMERA_OFF, b.router.receiveState("a"))
        assertTrue(a.sendingTo.isEmpty())
        mesh.step(a) { setCameraOff(false) }
        assertEquals(setOf("b"), a.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("a"))
    }

    @Test
    fun `the view follows a speaker only after two seconds`() {
        val a = node("a", receive = 1, send = 5)
        val b = node("b", receive = 1, send = 5)
        val c = node("c", receive = 1, send = 5)
        val mesh = Mesh(a, b, c).apply { join() }
        assertTrue("a" in b.sendingTo) // a watches b (first in order)
        mesh.step(a) { onSpeakers(setOf("c"), now) }
        mesh.step(a) { onSpeakers(setOf("c"), now + 1_900) }
        assertTrue("a" in b.sendingTo)
        mesh.step(a) { onSpeakers(setOf("c"), now + 2_000) }
        assertTrue("a" in c.sendingTo)
        assertFalse("a" in b.sendingTo)
        // Silence keeps the current view.
        mesh.step(a) { onSpeakers(emptySet(), now + 9_000) }
        assertTrue("a" in c.sendingTo)
    }

    @Test
    fun `a ringing device asks nobody`() {
        val a = node("a", receive = 5, send = 5)
        a.peers += "b"
        val effects = a.router.onAnnouncement("b", videoRequests = true)
        assertTrue(effects.none { it is GroupVideoRouter.Effect.Send })
    }

    @Test
    fun `limits follow the tier and the band`() {
        val high24 = GroupVideoLimits.of(FlashPerformanceMode.HIGH, FlashNetworkBand.WIFI_2_4GHZ)
        assertEquals(GroupVideoLimits(receive = 3, send = 3, quality = 540), high24)
        assertEquals(GroupVideoLimits(5, 5, 720), GroupVideoLimits.of(FlashPerformanceMode.HIGH, FlashNetworkBand.ETHERNET))
        assertEquals(GroupVideoLimits(5, 4, 720), GroupVideoLimits.of(FlashPerformanceMode.HIGH, null))
        assertEquals(GroupVideoLimits(2, 2, 720), GroupVideoLimits.of(FlashPerformanceMode.MEDIUM, FlashNetworkBand.WIFI_5GHZ))
        assertEquals(GroupVideoLimits(2, 2, 540), GroupVideoLimits.of(FlashPerformanceMode.MEDIUM, FlashNetworkBand.WIFI_2_4GHZ))
        assertEquals(GroupVideoLimits(1, 1, 540), GroupVideoLimits.of(FlashPerformanceMode.LOW, FlashNetworkBand.WIFI_6GHZ))
    }

    private companion object {
        const val CALL = "call-1"
    }
}
