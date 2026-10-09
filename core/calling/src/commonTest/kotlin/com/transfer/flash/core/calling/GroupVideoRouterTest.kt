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
        val heights = mutableMapOf<String, Int?>()
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
                    is GroupVideoRouter.Effect.Sending -> if (effect.on) {
                        node.sendingTo += effect.peerId
                        node.heights[effect.peerId] = effect.height
                    } else {
                        node.sendingTo -= effect.peerId
                        node.heights -= effect.peerId
                    }
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
        assertEquals(
            GroupVideoLimits(receive = 3, send = 3, quality = 540, splitBudget = true, maxSendHeight = 540),
            high24,
        )
        assertEquals(6, high24.capacity)
        // ADR-098: HIGH sends 540p, MEDIUM and LOW 360p; no tier defaults to 720p any more.
        assertEquals(
            GroupVideoLimits(5, 5, 540, maxSendHeight = 540),
            GroupVideoLimits.of(FlashPerformanceMode.HIGH, FlashNetworkBand.ETHERNET),
        )
        assertEquals(
            GroupVideoLimits(5, 4, 540, maxSendHeight = 540),
            GroupVideoLimits.of(FlashPerformanceMode.HIGH, null),
        )
        assertEquals(
            GroupVideoLimits(2, 2, 360, maxSendHeight = 360),
            GroupVideoLimits.of(FlashPerformanceMode.MEDIUM, FlashNetworkBand.WIFI_5GHZ),
        )
        assertEquals(
            GroupVideoLimits(2, 2, 360, splitBudget = true, maxSendHeight = 360),
            GroupVideoLimits.of(FlashPerformanceMode.MEDIUM, FlashNetworkBand.WIFI_2_4GHZ),
        )
        assertEquals(
            GroupVideoLimits(1, 1, 360, maxSendHeight = 360),
            GroupVideoLimits.of(FlashPerformanceMode.LOW, FlashNetworkBand.WIFI_6GHZ),
        )
        assertEquals(540, GroupVideoLimits.sendHeightFor(FlashPerformanceMode.HIGH))
        assertEquals(360, GroupVideoLimits.sendHeightFor(FlashPerformanceMode.MEDIUM))
        assertEquals(360, GroupVideoLimits.sendHeightFor(FlashPerformanceMode.LOW))
    }

    @Test
    fun `a struggling device of any tier asks for 360p, and the health caps apply`() {
        assertEquals(360, GroupVideoLimits.of(FlashPerformanceMode.LOW, null, struggling = true).quality)
        assertEquals(360, GroupVideoLimits.of(FlashPerformanceMode.MEDIUM, null, struggling = true).quality)
        assertEquals(360, GroupVideoLimits.of(FlashPerformanceMode.HIGH, FlashNetworkBand.WIFI_5GHZ, struggling = true).quality)
        assertEquals(540, GroupVideoLimits.of(FlashPerformanceMode.HIGH, FlashNetworkBand.WIFI_5GHZ).quality)
        val capped = GroupVideoLimits.of(FlashPerformanceMode.HIGH, FlashNetworkBand.ETHERNET, receiveCap = 1, acceptNew = false)
        assertEquals(1, capped.receive)
        assertFalse(capped.acceptNew)
        // A cap above the tier's limit does not raise it.
        assertEquals(2, GroupVideoLimits.of(FlashPerformanceMode.MEDIUM, null, receiveCap = 4).receive)
        assertEquals(1_800, GroupVideoLimits.maxBitrateKbps(720))
        assertEquals(900, GroupVideoLimits.maxBitrateKbps(540))
        assertEquals(450, GroupVideoLimits.maxBitrateKbps(360))
    }

    @Test
    fun `copies go at the lower of the asked height and the sender's own`() {
        val a = Node("a", GroupVideoLimits(receive = 0, send = 5, quality = 720, maxSendHeight = 720))
        val b = Node("b", GroupVideoLimits(receive = 1, send = 5, quality = 540))
        val c = Node("c", GroupVideoLimits(receive = 1, send = 5, quality = 720))
        val mesh = Mesh(a, b, c).apply { join() }
        assertEquals(540, a.heights["b"])
        assertEquals(720, a.heights["c"])
        val grants = mesh.wire.filterIsInstance<CallWireFrame.VideoGrant>().filter { it.from == "a" }
        assertEquals(listOf(540, 720), grants.map { it.quality }.sorted())
    }

    @Test
    fun `a 2_4 GHz sender splits its budget into 360p copies and steps back up after five seconds`() {
        val slow = GroupVideoLimits(receive = 0, send = 2, quality = 540, splitBudget = true, maxSendHeight = 540)
        val a = Node("a", slow)
        val watchers = (1..4).map { Node("w$it", GroupVideoLimits(receive = 1, send = 2, quality = 540)) }
        watchers.forEach { it.router.setFocus("a") }
        val late = Node("w9", GroupVideoLimits(receive = 0, send = 2, quality = 540))
        val mesh = Mesh(a, *watchers.toTypedArray(), late).apply { join() }
        // Four watchers do not fit two 540p copies, so all four go at 360p.
        assertEquals(4, a.sendingTo.size)
        assertTrue(a.heights.values.all { it == 360 }, "${a.heights}")
        assertEquals(0, a.router.freeSlots())
        // Two leave: they fit at 540p again, but only after five seconds.
        mesh.step(a) { onPeerLeft("w3") }
        mesh.step(a) { onPeerLeft("w4") }
        assertTrue(a.heights.values.all { it == 360 }, "${a.heights}")
        now += 4_000
        mesh.step(a) { tick(now) }
        assertTrue(a.heights.values.all { it == 360 }, "${a.heights}")
        now += 1_000
        mesh.step(a) { tick(now) }
        assertTrue(a.heights.values.all { it == 540 }, "${a.heights}")
        // A fifth watcher arrives: back down at once.
        late.limits = late.limits.copy(receive = 1)
        mesh.step(late) { setFocus("a") }
        assertTrue("w9" in a.sendingTo)
        assertTrue(a.heights.values.all { it == 360 }, "${a.heights}")
    }

    @Test
    fun `a fast-band sender does not split its budget`() {
        val a = Node("a", GroupVideoLimits(receive = 0, send = 2, quality = 720, maxSendHeight = 720))
        val watchers = (1..3).map { Node("w$it", GroupVideoLimits(receive = 1, send = 2, quality = 720)) }
        watchers.forEach { it.router.setFocus("a") }
        Mesh(a, *watchers.toTypedArray()).join()
        assertEquals(2, a.sendingTo.size)
        assertTrue(a.heights.values.all { it == 720 })
    }

    @Test
    fun `a hot sender turns new watchers down, keeps the ones it has, and announces no room`() {
        val a = Node("a", GroupVideoLimits(receive = 0, send = 5, quality = 540))
        val b = node("b", receive = 1, send = 5)
        val c = node("c", receive = 1, send = 5)
        c.router.setFocus("b")
        val mesh = Mesh(a, b, c).apply { join() }
        assertEquals(setOf("b"), a.sendingTo)
        a.limits = a.limits.copy(acceptNew = false)
        assertEquals(0, a.router.freeSlots())
        mesh.step(c) { setFocus("a") }
        assertEquals(setOf("b"), a.sendingTo)
        val deny = mesh.wire.filterIsInstance<CallWireFrame.VideoDeny>().last()
        assertEquals(VideoDenyReason.THERMAL, deny.reason)
        assertEquals(FlashParticipantVideo.SENDER_HOT, c.router.receiveState("a"))
    }

    @Test
    fun `send smaller video in groups is off by default, then sends every copy at 360p whatever the watcher count`() {
        val a = Node("a", GroupVideoLimits.of(FlashPerformanceMode.HIGH, FlashNetworkBand.WIFI_5GHZ).copy(receive = 0))
        assertFalse(a.limits.smallerForMany)
        val watchers = (1..3).map { Node("w$it", GroupVideoLimits(receive = 1, send = 5, quality = 720)) }
        watchers.forEach { it.router.setFocus("a") }
        val mesh = Mesh(a, *watchers.toTypedArray()).apply { join() }
        assertEquals(3, a.sendingTo.size)
        // HIGH sends 540p (ADR-098) even to watchers that ask for 720p.
        assertTrue(a.heights.values.all { it == 540 }, "${a.heights}")
        // Turned on mid-call: the next tick re-sends all three at 360p.
        a.limits = a.limits.copy(smallerForMany = true)
        mesh.step(a) { tick(now) }
        assertTrue(a.heights.values.all { it == 360 }, "${a.heights}")
        // Two watchers, then one: still 360p (before ADR-098 one watcher got the full height).
        mesh.step(a) { onPeerLeft("w3") }
        assertEquals(mapOf<String, Int?>("w1" to 360, "w2" to 360), a.heights)
        mesh.step(a) { onPeerLeft("w2") }
        assertEquals(mapOf<String, Int?>("w1" to 360), a.heights)
    }

    @Test
    fun `send smaller video never raises a copy above the watcher's ask or the sender's cap`() {
        assertEquals(360, GroupVideoLimits.SMALLER_HEIGHT)
        assertTrue(GroupVideoLimits.of(FlashPerformanceMode.HIGH, null, smallerForMany = true).smallerForMany)
        val a = Node("a", GroupVideoLimits(receive = 0, send = 5, quality = 540, maxSendHeight = 540, smallerForMany = true))
        val b = Node("b", GroupVideoLimits(receive = 1, send = 5, quality = 540))
        val mesh = Mesh(a, b).apply { join() }
        // The watcher asks for 540p; the sender's "send smaller" caps the copy at 360p.
        assertEquals(mapOf<String, Int?>("b" to 360), a.heights)
        b.limits = b.limits.copy(quality = 720)
        mesh.step(b) { tick(now) }
        assertEquals(mapOf<String, Int?>("b" to 360), a.heights)
        // A watcher that asks for less still gets less.
        b.limits = b.limits.copy(quality = 360)
        mesh.step(b) { tick(now) }
        assertEquals(mapOf<String, Int?>("b" to 360), a.heights)
    }

    @Test
    fun `turning the camera off stops the video to the people already watching, and each is told why`() {
        val a = node("a", receive = 0, send = 5)
        val b = node("b", receive = 1, send = 5)
        val c = node("c", receive = 1, send = 5)
        val mesh = Mesh(a, b, c).apply { join() }
        assertEquals(setOf("b", "c"), a.sendingTo)
        mesh.step(a) { setCameraOff(true) }
        // Audit finding: the encoders used to keep running for the existing watchers with the camera "off".
        assertTrue(a.sendingTo.isEmpty())
        assertEquals(FlashParticipantVideo.CAMERA_OFF, b.router.receiveState("a"))
        assertEquals(FlashParticipantVideo.CAMERA_OFF, c.router.receiveState("a"))
        assertEquals(0, a.router.freeSlots())
        // And a watcher that asks again meanwhile is still turned down.
        mesh.step(b) { setFocus("a") }
        assertTrue(a.sendingTo.isEmpty())
        // The camera back on: everyone who was turned down is told and gets the video again.
        mesh.step(a) { setCameraOff(false) }
        assertEquals(setOf("b", "c"), a.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("a"))
    }

    @Test
    fun `a request nobody answered is asked again after the retry window, and not before`() {
        val a = node("a", receive = 0, send = 5)
        val b = node("b", receive = 1, send = 5)
        val mesh = Mesh(a, b)
        a.router.onAnnouncement("b", videoRequests = true, videoFree = 5)
        b.router.onAnnouncement("a", videoRequests = true, videoFree = 5)
        // The first request is lost on the wire (the peer's session was down for a moment).
        val lost = b.router.startReceiving().filterIsInstance<GroupVideoRouter.Effect.Send>()
            .map { it.frame }.filterIsInstance<CallWireFrame.VideoRequest>()
        assertEquals(1, lost.size)
        assertTrue(a.sendingTo.isEmpty())
        assertEquals(FlashParticipantVideo.REQUESTED, b.router.receiveState("a"))

        now += 4_000
        mesh.step(b) { tick(now) }
        assertTrue(a.sendingTo.isEmpty(), "not yet asked again")

        now += 1_500
        mesh.step(b) { tick(now) }
        assertEquals(setOf("b"), a.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("a"))
        // A grant that answers the lost, older request cannot be taken for this one.
        assertTrue(b.router.onGrant("a", CallWireFrame.VideoGrant(CALL, "a", lost.single().seq, 540)).isEmpty())
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("a"))
    }

    @Test
    fun `a request that keeps going unanswered is reported as no response, and a late answer clears it`() {
        val a = node("a", receive = 0, send = 5)
        val b = node("b", receive = 1, send = 5)
        val mesh = Mesh(a, b)
        a.router.onAnnouncement("b", videoRequests = true, videoFree = 5)
        b.router.onAnnouncement("a", videoRequests = true, videoFree = 5)
        b.router.startReceiving() // the first request is lost
        assertEquals(FlashParticipantVideo.REQUESTED, b.router.receiveState("a"))

        now += 5_500
        b.router.tick(now) // the retry is lost as well
        now += 5_500
        b.router.tick(now) // and the next one
        assertEquals(FlashParticipantVideo.REQUESTED, b.router.receiveState("a"), "11 s is still within the window")

        now += 2_000
        assertEquals(
            FlashParticipantVideo.NO_RESPONSE,
            b.router.receiveState("a"),
            "the retries kept the first-sent time, so the tile can say nobody answered",
        )

        // The link comes back: the next retry is answered and the video flows again.
        now += 4_000
        mesh.step(b) { tick(now) }
        assertEquals(setOf("b"), a.sendingTo)
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("a"))
    }

    @Test
    fun `a sender that is too hot is told apart from one that is full`() {
        val a = node("a", receive = 0, send = 5)
        a.limits = a.limits.copy(acceptNew = false)
        val b = node("b", receive = 1, send = 5)
        Mesh(a, b).join()
        assertEquals(FlashParticipantVideo.SENDER_HOT, b.router.receiveState("a"))

        val full = node("full", receive = 0, send = 0)
        val c = node("c", receive = 1, send = 5)
        Mesh(full, c).join()
        assertEquals(FlashParticipantVideo.BUSY, c.router.receiveState("full"))
    }

    @Test
    fun `a granted request is not asked again, however long the call runs`() {
        val a = node("a", receive = 0, send = 5)
        val b = node("b", receive = 1, send = 5)
        val mesh = Mesh(a, b).apply { join() }
        val before = mesh.wire.count { it is CallWireFrame.VideoRequest }
        repeat(20) {
            now += 5_000
            mesh.step(b) { tick(now) }
        }
        assertEquals(before, mesh.wire.count { it is CallWireFrame.VideoRequest })
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("a"))
    }

    @Test
    fun `with room for one video, a denied pin does not leave a fallback video decoding for nobody`() {
        val a = node("a", receive = 0, send = 0)
        val b = node("b", receive = 1, send = 5)
        val c = node("c", receive = 0, send = 5)
        b.router.setFocus("a")
        Mesh(a, b, c).join()
        assertEquals(FlashParticipantVideo.BUSY, b.router.receiveState("a"))
        // Audit finding: c used to be asked as the fallback, and its stream arrived with no tile to show it.
        assertEquals(FlashParticipantVideo.OFF, b.router.receiveState("c"))
        assertTrue(c.sendingTo.isEmpty())
        assertTrue(a.sendingTo.isEmpty())
    }

    @Test
    fun `with room for two videos, a denied pin still lets the next participant fill the other slot`() {
        val a = node("a", receive = 0, send = 0)
        val b = node("b", receive = 2, send = 5)
        val c = node("c", receive = 0, send = 5)
        b.router.setFocus("a")
        Mesh(a, b, c).join()
        assertEquals(FlashParticipantVideo.BUSY, b.router.receiveState("a"))
        assertEquals(FlashParticipantVideo.RECEIVING, b.router.receiveState("c"))
        assertEquals(setOf("b"), c.sendingTo)
    }

    @Test
    fun `a lower receive limit releases the extra videos on the next tick`() {
        val a = Node("a", GroupVideoLimits(receive = 2, send = 5, quality = 540))
        val b = node("b", receive = 0, send = 5)
        val c = node("c", receive = 0, send = 5)
        val mesh = Mesh(a, b, c).apply { join() }
        assertTrue("a" in b.sendingTo && "a" in c.sendingTo)
        a.limits = a.limits.copy(receive = 1)
        mesh.step(a) { tick(now) }
        assertEquals(1, listOf(b, c).count { "a" in it.sendingTo })
    }

    @Test
    fun `a device that receives no video, as under data saver, asks nobody even when a tile is pinned`() {
        // ERROR-105: the screen explains this with "Video is off to save data"; the router stays silent on purpose.
        val a = Node("a", GroupVideoLimits(receive = 0, send = 5, quality = 540))
        val b = node("b", receive = 0, send = 5)
        val mesh = Mesh(a, b).apply { join() }

        mesh.step(a) { setFocus("b") }

        assertTrue("a" !in b.sendingTo, "b is not asked to send to a")
        assertEquals(FlashParticipantVideo.OFF, a.router.receiveState("b"))
    }

    private companion object {
        const val CALL = "call-1"
    }
}
