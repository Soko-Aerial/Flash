package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-102 review S1 / S2 / S4 / S9 / S10: the stop path of a screen share, run for real (real scope, real media
 * dispatcher, a clock the test moves) against a capture provider that OPENS successfully and then delivers nothing.
 * The earlier session tests used a provider whose `open` throws, so the watchdog, the stop and the teardown were never
 * reached (that is how S1 went unnoticed).
 *
 * What stays a device check: the native `replaceTrack` / capture disposal (SHARE-06, SHARE-15). Here the RTP sender is
 * an allocated shell whose `replaceTrack` throws, which is also the case "the connection is already gone"; the stop
 * must finish regardless. The ORDER sender-then-capture is pinned in `ScreenShareTest` on the pure `ScreenShareRun`.
 */
class FlashScreenShareStopTest {

    private val callId = "call-0001"
    private val peerId = "peer-0001"
    private val clock = AtomicLong(BASE)
    private val sent = CopyOnWriteArrayList<CallWireFrame>()
    private val groupSent = CopyOnWriteArrayList<Pair<CallWireFrame, String>>()
    private lateinit var scope: CoroutineScope
    private val source = ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A capture that opened and has produced no frame, and counts what is done to it. */
    private class SilentHandle(
        private val onSendOn: suspend () -> Unit = {},
        override val frameCount: Long = 0L,
        override val height: Int = 0,
    ) : ScreenCaptureHandle {
        override val previewTrack: VideoStreamTrack? = null
        override val width = 0
        val closes = AtomicInteger()

        /** Runs inside [close], i.e. in the middle of the native stop. */
        @Volatile
        var onClose: () -> Unit = {}
        override fun lastFrameAgeMs(): Long? = null
        override suspend fun sendOn(sender: RtpSender) = onSendOn()
        override fun addTo(pc: PeerConnection, stream: MediaStream): RtpSender = error("not used")
        override fun close() {
            closes.incrementAndGet()
            onClose()
        }
    }

    private class Provider(val handle: SilentHandle) : ScreenCaptureProvider {
        override val supported = true
        override val usesSystemPicker = false
        override suspend fun listSources() = emptyList<ShareSource>()
        override suspend fun open(source: ShareSource, fps: Int, maxWidth: Int, maxHeight: Int): ScreenCaptureHandle = handle
    }

    /** A sender object without a native peer behind it: every call on it throws, like a sender of a closed connection. */
    private fun sender(): RtpSender {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(field.get(null), RtpSender::class.java) as RtpSender
    }

    /** A camera track nobody can use; only its identity matters (it must come back on the preview after the stop). */
    private fun camera(): VideoStreamTrack =
        Proxy.newProxyInstance(VideoStreamTrack::class.java.classLoader, arrayOf(VideoStreamTrack::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fake-camera"
                else -> null
            }
        } as VideoStreamTrack

    private fun oneToOne(provider: ScreenCaptureProvider): FlashCallSession = FlashCallSession(
        callId = callId, peerId = peerId, peerName = "Peer", direction = FlashCallDirection.OUTGOING, video = true,
        localDeviceId = "me-0001", localName = "Me", scope = scope,
        sendFrame = { frame -> sent += frame; true },
        screenCapture = provider,
        shareNowMs = { clock.get() },
    ).also {
        it.setStateForTesting(FlashCallState.ACTIVE)
        it.setVideoSenderForTesting(sender())
    }

    private fun group(provider: ScreenCaptureProvider): FlashGroupCallSession = FlashGroupCallSession(
        callId = callId, groupId = "group-1", groupName = "G", direction = FlashCallDirection.OUTGOING, video = true,
        localDeviceId = "me", localName = "Me", scope = scope,
        sendFrame = { frame, peer -> groupSent += frame to peer; true },
        onEnded = {},
        nowMs = { clock.get() },
        screenCapture = provider,
    ).also {
        it.addJoinedLegForTesting("a", FlashCallParticipantState.CONNECTED)
        it.setLegVideoSenderForTesting("a", sender())
        it.markActiveForTesting()
        it.allowShareForTesting()
    }

    private fun shareStatuses(): List<CallWireFrame.Status> = sent.filterIsInstance<CallWireFrame.Status>().filter { it.sharing != null }

    private fun groupShareStatuses(): List<CallWireFrame.Status> =
        groupSent.map { it.first }.filterIsInstance<CallWireFrame.Status>().filter { it.sharing != null }

    private suspend fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for: $what")
            delay(20)
        }
    }

    // ---- S1: the first-frame watchdog's own stop

    @Test
    fun `1 to 1 a capture that never delivers a frame is stopped to the end by the watchdog`() = runBlocking {
        val handle = SilentHandle()
        val s = oneToOne(Provider(handle))
        val cam = camera()
        s.setLocalVideoTrackForTesting(cam)
        s.setCameraOffForTesting(true) // a kept (disabled) camera: it must come back on the preview

        assertTrue(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        assertTrue(s.state.value.sharing)
        assertNull("the preview is empty while the screen is on the sender", s.localVideoStreamTrack.value)
        waitFor("ss=1 sent") { shareStatuses().isNotEmpty() } // status frames are fire and forget
        assertEquals(listOf(true), shareStatuses().map { it.sharing })

        clock.addAndGet(ShareLadder.FIRST_FRAME_TIMEOUT_MS + 1_000)
        waitFor("share stopped and capture closed") { !s.state.value.sharing && handle.closes.get() == 1 }
        waitFor("ss=0 sent") { shareStatuses().lastOrNull()?.sharing == false }

        assertEquals(FlashShareNotice.NO_FRAMES, s.state.value.shareNotice)
        assertEquals("the capture is closed exactly once", 1, handle.closes.get())
        assertSame("the kept camera is back on the preview", cam, s.localVideoStreamTrack.value)
        assertFalse(s.state.value.shareStarting)
        // Back to idle: a new share can start (it was stuck in STOPPING for the rest of the call before the fix).
        assertTrue("the machine reached IDLE", s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        s.stopScreenShare()
        waitFor("second stop done") { !s.state.value.sharing && handle.closes.get() == 2 }
    }

    @Test
    fun `group a capture that never delivers a frame is stopped to the end by the watchdog`() = runBlocking {
        val handle = SilentHandle()
        val s = group(Provider(handle))
        val cam = camera()
        s.setLocalVideoTrackForTesting(cam)

        assertTrue(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        assertTrue(s.state.value.sharing)
        waitFor("ss=1 sent") { groupShareStatuses().any { it.sharing == true } }

        clock.addAndGet(ShareLadder.FIRST_FRAME_TIMEOUT_MS + 1_000)
        waitFor("share stopped and capture closed") { !s.state.value.sharing && handle.closes.get() == 1 }
        waitFor("ss=0 sent") { groupShareStatuses().lastOrNull()?.sharing == false }

        assertEquals(FlashShareNotice.NO_FRAMES, s.state.value.shareNotice)
        assertEquals(1, handle.closes.get())
        assertTrue("the machine reached IDLE", s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        s.stopScreenShare()
        waitFor("second stop done") { !s.state.value.sharing && handle.closes.get() == 2 }
    }

    // ---- S4: the caller goes away while the capture opens

    @Test
    fun `1 to 1 a start cancelled while the capture opens is rolled back, not left in STARTING`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val handle = SilentHandle(onSendOn = { entered.complete(Unit); delay(60_000) })
        val s = oneToOne(Provider(handle))

        val caller = launch { s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false) }
        entered.await()
        caller.cancelAndJoin()

        waitFor("rolled back") { !s.state.value.sharing }
        assertFalse(s.state.value.shareStarting)
        assertEquals("closed once, on the media thread", 1, handle.closes.get())
        assertNull("a cancelled caller is not told 'could not start'", s.state.value.shareNotice)
        assertTrue("the machine is idle again", s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false).also { s.stopScreenShare() })
    }

    @Test
    fun `group a start cancelled while the capture opens is rolled back, not left in STARTING`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val handle = SilentHandle(onSendOn = { entered.complete(Unit); delay(60_000) })
        val s = group(Provider(handle))

        val caller = launch { s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false) }
        entered.await()
        caller.cancelAndJoin()

        waitFor("rolled back") { !s.state.value.sharing }
        assertFalse(s.state.value.shareStarting)
        assertEquals(1, handle.closes.get())
        assertNull(s.state.value.shareNotice)
        assertTrue(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false).also { s.stopScreenShare() })
    }

    // ---- stop is not lost when its caller is cancelled (S1 hardening)

    @Test
    fun `1 to 1 a stop whose caller is cancelled mid-stop still reaches idle`() = runBlocking {
        val handle = SilentHandle()
        val s = oneToOne(Provider(handle))
        assertTrue(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        val started = CompletableDeferred<Unit>()
        lateinit var caller: kotlinx.coroutines.Job
        // The caller's scope ends while the native stop is running (close() is called from inside it).
        handle.onClose = { caller.cancel() }
        caller = launch { started.complete(Unit); s.stopScreenShare() }
        caller.join()
        waitFor("not sharing") { !s.state.value.sharing }
        assertEquals(1, handle.closes.get())
        handle.onClose = {}
        assertTrue("idle again, not stuck in STOPPING", s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
    }

    // ---- S2: the end of the call closes the capture, whatever the sender does

    @Test
    fun `1 to 1 ending the call while sharing closes the capture even though the sender is gone`() = runBlocking {
        val handle = SilentHandle()
        val s = oneToOne(Provider(handle))
        assertTrue(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        s.end(FlashCallEndReason.NORMAL, notifyPeer = false)
        waitFor("capture closed by the teardown") { handle.closes.get() == 1 }
        delay(200)
        assertEquals("closed once, not again by the stop path", 1, handle.closes.get())
    }

    @Test
    fun `group ending the call while sharing closes the capture even though the senders are gone`() = runBlocking {
        val handle = SilentHandle()
        val s = group(Provider(handle))
        assertTrue(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        s.hangUp()
        waitFor("capture closed by the teardown") { handle.closes.get() == 1 }
        delay(200)
        assertEquals(1, handle.closes.get())
    }

    // ---- S9 / S10

    @Test
    fun `1 to 1 the watcher count follows the peer's data saver during a share (S9)`() = runBlocking {
        val handle = SilentHandle(frameCount = 5L, height = 1080) // frames flow, so the watchdog stays quiet
        val s = oneToOne(Provider(handle))
        s.onInboundFrame(CallWireFrame.Status(callId, peerId, micOn = true, cameraOn = true, receiveVideo = true))
        assertTrue(s.startScreenShare(source, ShareQuality.STANDARD, takeOver = false))
        assertEquals(1, s.state.value.shareWatchers)
        s.onInboundFrame(CallWireFrame.Status(callId, peerId, receiveVideo = false))
        assertEquals("the peer turned data saver on", 0, s.state.value.shareWatchers)
        s.onInboundFrame(CallWireFrame.Status(callId, peerId, receiveVideo = true))
        assertEquals(1, s.state.value.shareWatchers)
        s.stopScreenShare()
        assertEquals(0, s.state.value.shareWatchers)
    }

    @Test
    fun `group restored signaling to a peer says our status again, so a lost ss=0 is repaired (S10)`() = runBlocking {
        val s = group(Provider(SilentHandle()))
        groupSent.clear()
        s.onSignalingRestored("a")
        waitFor("status to a") { groupSent.any { (f, p) -> p == "a" && f is CallWireFrame.Status } }
    }

    private companion object {
        const val BASE = 1_790_000_000_000L
    }
}
