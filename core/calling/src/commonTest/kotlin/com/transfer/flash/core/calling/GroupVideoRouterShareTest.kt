package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.calling.protocol.VideoDenyReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-102: how the group video router serves and asks for a screen share (presenter side and watcher side). */
class GroupVideoRouterShareTest {

    private var now = 1_000_000L
    private var seq = 100L
    private var limits = GroupVideoLimits(receive = 4, send = 4, quality = 540)
    private var cap = 4
    private var peers = listOf("a", "b", "c", "d", "e")

    private fun router(id: String, shareHeight: (Int) -> Int = { ShareLadder.profile(it, ShareQuality.STANDARD, false).maxHeight }) =
        GroupVideoRouter(
            callId = "call",
            localId = id,
            limits = { limits },
            participants = { peers },
            clock = { now },
            shareHeight = shareHeight,
            shareCap = { cap },
        )

    private fun request(r: GroupVideoRouter, from: String, quality: Int = 1080, focus: Boolean = false): List<GroupVideoRouter.Effect> =
        r.onRequest(from, CallWireFrame.VideoRequest("call", from, ++seq, quality, focus))

    private fun denies(effects: List<GroupVideoRouter.Effect>): Map<String, VideoDenyReason> =
        effects.filterIsInstance<GroupVideoRouter.Effect.Send>()
            .mapNotNull { e -> (e.frame as? CallWireFrame.VideoDeny)?.let { e.peerId to it.reason } }.toMap()

    private fun grants(effects: List<GroupVideoRouter.Effect>): Map<String, Int> =
        effects.filterIsInstance<GroupVideoRouter.Effect.Send>()
            .mapNotNull { e -> (e.frame as? CallWireFrame.VideoGrant)?.let { e.peerId to it.quality } }.toMap()

    private fun requests(effects: List<GroupVideoRouter.Effect>): Map<String, CallWireFrame.VideoRequest> =
        effects.filterIsInstance<GroupVideoRouter.Effect.Send>()
            .mapNotNull { e -> (e.frame as? CallWireFrame.VideoRequest)?.let { e.peerId to it } }.toMap()

    // ---- presenter side

    @Test
    fun `a presenter serves watchers at the share height up to the cap and turns the rest down`() {
        cap = 2
        val a = router("a")
        a.setSharing(true)
        assertTrue(grants(request(a, "b")).containsKey("b"))
        assertTrue(grants(request(a, "c")).containsKey("c"))
        assertEquals(VideoDenyReason.SENDER_AT_CAPACITY, denies(request(a, "d"))["d"])
        assertEquals(2, a.sendingCount)
    }

    @Test
    fun `the copy goes at the share's height and at what the watcher asked when that is less`() {
        val a = router("a")
        a.setSharing(true)
        assertEquals(1080, grants(request(a, "b", quality = 1080))["b"])
        assertEquals(720, grants(request(a, "c", quality = 720))["c"])
        assertEquals(1080, a.sendHeight("b"))
        assertEquals(720, a.sendHeight("c"))
    }

    @Test
    fun `the share height follows the number of watchers`() {
        val a = router("a")
        a.setSharing(true)
        request(a, "b"); request(a, "c")
        assertEquals(1080, a.level())
        request(a, "d")
        assertEquals(720, a.level(), "three watchers are three encodes, so the rung steps down")
    }

    @Test
    fun `a camera that is off does not turn the watchers of a share down`() {
        val a = router("a")
        a.setCameraOff(true)
        assertEquals(VideoDenyReason.CAMERA_OFF, denies(request(a, "b"))["b"])
        a.setSharing(true)
        assertTrue(grants(request(a, "b")).containsKey("b"), "the video is the screen now")
        assertTrue(a.freeSlots() > 0)
        a.setCameraOff(true)
        assertTrue(a.isSending("b"), "toggling the camera during a share changes nothing")
    }

    @Test
    fun `starting a share tells the devices turned down for the camera that there is room`() {
        val a = router("a")
        a.setCameraOff(true)
        request(a, "b")
        val effects = a.setSharing(true)
        assertTrue(effects.contains(GroupVideoRouter.Effect.Announce("b")), effects.toString())
    }

    @Test
    fun `starting a share trims the watchers to the share's cap and keeps the focused ones`() {
        cap = 2
        val a = router("a")
        request(a, "b"); request(a, "c", focus = true); request(a, "d"); request(a, "e", focus = true)
        val effects = a.setSharing(true)
        assertEquals(setOf("b", "d"), denies(effects).keys)
        assertTrue(a.isSending("c") && a.isSending("e"))
        assertFalse(a.isSending("b"))
    }

    @Test
    fun `stopping with the camera off turns every watcher down again and with the camera on keeps them`() {
        val off = router("a")
        off.setCameraOff(true)
        off.setSharing(true)
        request(off, "b")
        val stop = off.setSharing(false)
        assertEquals(VideoDenyReason.CAMERA_OFF, denies(stop)["b"])
        assertFalse(off.isSending("b"))

        val on = router("a")
        on.setSharing(true)
        request(on, "b")
        on.setSharing(false)
        assertTrue(on.isSending("b"))
    }

    @Test
    fun `stopping a share that never started is a no-op and a repeated start too`() {
        val a = router("a")
        assertTrue(a.setSharing(false).isEmpty())
        a.setSharing(true)
        assertTrue(a.setSharing(true).isEmpty())
    }

    @Test
    fun `the split budget on 2_4 GHz allows two copies of a screen`() {
        limits = limits.copy(splitBudget = true)
        val a = router("a")
        a.setSharing(true)
        request(a, "b"); request(a, "c")
        assertEquals(VideoDenyReason.SENDER_AT_CAPACITY, denies(request(a, "d"))["d"])
    }

    @Test
    fun `a speaking presenter does not evict a watcher to make room`() {
        cap = 1
        val a = router("a")
        a.setSharing(true)
        request(a, "b")
        a.setLocalSpeaking(true)
        assertEquals(VideoDenyReason.SENDER_AT_CAPACITY, denies(request(a, "c"))["c"])
        assertTrue(a.isSending("b"))
    }

    @Test
    fun `an old client that never asks is counted for the share's rung`() {
        val a = router("a")
        a.setSharing(true)
        a.onAnnouncement("b", videoRequests = false)
        a.onAnnouncement("c", videoRequests = false)
        a.onAnnouncement("d", videoRequests = false)
        assertEquals(720, a.level())
        assertTrue(a.isSending("b"))
        assertNull(a.sendHeight("b"), "an old client is sent the full profile, which the session tunes to the share")
    }

    @Test
    fun `old clients count against the share's cap and the ones beyond it get nothing (S7)`() {
        cap = 2
        val a = router("a")
        listOf("b", "c", "d", "e").forEach { a.onAnnouncement(it, videoRequests = false) }
        assertTrue(listOf("b", "c", "d", "e").all { a.isSending(it) }, "a camera call sends to every old client")
        val effects = a.setSharing(true)
        assertEquals(listOf("b", "c"), listOf("b", "c", "d", "e").filter { a.isSending(it) })
        assertEquals(2, a.sendingCount)
        val off = effects.filterIsInstance<GroupVideoRouter.Effect.Sending>().filter { !it.on }.map { it.peerId }.toSet()
        assertEquals(setOf("d", "e"), off, "the encodings beyond the cap are switched off")
        // A device that asks takes a slot before an old client keeps one: it can be told, the old client cannot.
        assertTrue(grants(request(a, "b")).containsKey("b"))
        assertEquals(2, a.sendingCount)
        assertEquals(listOf("c"), listOf("c", "d", "e").filter { a.isSending(it) })
        // Stopping the share gives every old client the camera again.
        a.setSharing(false)
        assertTrue(listOf("c", "d", "e").all { a.isSending(it) })
    }

    // ---- watcher side

    private fun watcher(): GroupVideoRouter {
        val w = router("w")
        for (p in listOf("a", "b", "c")) w.onAnnouncement(p, videoRequests = true, videoFree = 4)
        w.startReceiving()
        return w
    }

    @Test
    fun `a presenter is pinned, asked first and asked at share quality`() {
        val w = watcher()
        val effects = w.setPresenter("c")
        assertEquals("c", w.pinnedPeer)
        assertEquals("c", w.presenterPeer)
        val ask = requests(effects)["c"]
        assertEquals(ShareLadder.ASK_HEIGHT, ask?.quality)
        assertEquals(true, ask?.focus)
        assertNull(requests(effects)["a"], "everyone else keeps the camera-quality ask they already have")
    }

    @Test
    fun `a struggling watcher asks for the presentation at 720`() {
        limits = limits.copy(struggling = true)
        val w = watcher()
        assertEquals(ShareLadder.ASK_HEIGHT_LOWER, requests(w.setPresenter("c"))["c"]?.quality)
    }

    @Test
    fun `the pin the person had is restored when the presentation ends`() {
        val w = watcher()
        w.setFocus("a")
        w.setPresenter("c")
        assertEquals("c", w.pinnedPeer)
        w.setPresenter(null)
        assertEquals("a", w.pinnedPeer)
        assertNull(w.presenterPeer)
    }

    @Test
    fun `a pin chosen during the presentation is not overwritten when it ends`() {
        val w = watcher()
        w.setPresenter("c")
        w.setFocus("b")
        w.setPresenter(null)
        assertEquals("b", w.pinnedPeer)
    }

    @Test
    fun `without a pin before it nothing stays pinned after`() {
        val w = watcher()
        w.setPresenter("c")
        w.setPresenter(null)
        assertNull(w.pinnedPeer)
    }

    @Test
    fun `unpinning during a presentation goes back to the presenter not to the speaker`() {
        val w = watcher()
        w.setPresenter("c")
        w.setFocus(null)
        assertEquals("c", w.pinnedPeer)
    }

    @Test
    fun `the presenter leaving ends the presentation and restores the pin`() {
        val w = watcher()
        w.setFocus("a")
        w.setPresenter("c")
        w.onPeerLeft("c")
        assertNull(w.presenterPeer)
        assertEquals("a", w.pinnedPeer)
    }

    @Test
    fun `a presenter change from one device to another keeps the original pin`() {
        val w = watcher()
        w.setFocus("a")
        w.setPresenter("b")
        w.setPresenter("c")
        assertEquals("c", w.pinnedPeer)
        w.setPresenter(null)
        assertEquals("a", w.pinnedPeer)
    }

    @Test
    fun `this device is never its own presenter`() {
        val w = watcher()
        assertTrue(w.setPresenter("w").isEmpty())
        assertNull(w.presenterPeer)
    }

    @Test
    fun `a presenter that turned this device down is asked again once presenting`() {
        val w = watcher()
        val first = requests(w.setPresenter("c"))["c"]!!
        w.onDeny("c", CallWireFrame.VideoDeny("call", "c", first.seq, VideoDenyReason.CAMERA_OFF))
        w.setPresenter(null)
        val again = requests(w.setPresenter("c"))["c"]
        assertEquals(ShareLadder.ASK_HEIGHT, again?.quality, "a new presentation clears the earlier refusal")
    }
}
