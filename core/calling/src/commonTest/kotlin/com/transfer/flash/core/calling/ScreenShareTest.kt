package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-102: the share state machine, the one-presenter rule, the share ladder and the watchdog. Pure; no native stack. */
class ScreenShareTest {

    // ---- state machine

    @Test
    fun `a share goes idle starting sharing stopping idle`() {
        val m = ShareStateMachine()
        assertFalse(m.busy)
        assertTrue(m.begin("Screen 1", startedAt = 50L, quality = ShareQuality.STANDARD))
        assertEquals(ShareStateMachine.Phase.STARTING, m.phase)
        assertTrue(m.sharing, "the camera track must not be sent from the moment the share starts opening")
        assertTrue(m.ready())
        assertEquals(ShareStateMachine.Phase.SHARING, m.phase)
        assertEquals("Screen 1", m.sourceTitle)
        assertEquals(50L, m.startedAt)
        assertTrue(m.end())
        assertEquals(ShareStateMachine.Phase.STOPPING, m.phase)
        assertFalse(m.sharing)
        assertTrue(m.busy, "the capturer is still closing, so a new share must wait")
        m.finished()
        assertEquals(ShareStateMachine.Phase.IDLE, m.phase)
        assertNull(m.sourceTitle)
        assertEquals(0L, m.startedAt)
    }

    @Test
    fun `a second start is refused while one is running or closing`() {
        val m = ShareStateMachine()
        assertTrue(m.begin("a", 1L, ShareQuality.STANDARD))
        assertFalse(m.begin("b", 2L, ShareQuality.LOWER))
        assertEquals("a", m.sourceTitle)
        m.ready()
        assertFalse(m.begin("b", 2L, ShareQuality.LOWER))
        m.end()
        assertFalse(m.begin("b", 2L, ShareQuality.LOWER), "still STOPPING")
        m.finished()
        assertTrue(m.begin("b", 3L, ShareQuality.LOWER))
    }

    @Test
    fun `stop is a no-op when nothing runs and twice is safe`() {
        val m = ShareStateMachine()
        assertFalse(m.end())
        m.begin("a", 1L, ShareQuality.STANDARD)
        m.ready()
        assertTrue(m.end())
        assertFalse(m.end(), "a second Stop must not start a second teardown")
        m.finished()
        m.finished()
        assertEquals(ShareStateMachine.Phase.IDLE, m.phase)
    }

    @Test
    fun `a share cancelled while opening cannot become ready`() {
        val m = ShareStateMachine()
        m.begin("a", 1L, ShareQuality.STANDARD)
        assertTrue(m.end())
        assertFalse(m.ready())
        assertFalse(m.sharing)
    }

    @Test
    fun `a failed start moves to stopping so the half open capturer is closed`() {
        val m = ShareStateMachine()
        m.begin("a", 1L, ShareQuality.STANDARD)
        assertTrue(m.fail())
        assertEquals(ShareStateMachine.Phase.STOPPING, m.phase)
        assertFalse(m.fail())
        m.finished()
        assertFalse(m.busy)
    }

    @Test
    fun `quality resets to standard after a share`() {
        val m = ShareStateMachine()
        m.begin("a", 1L, ShareQuality.LOWER)
        assertEquals(ShareQuality.LOWER, m.quality)
        m.end()
        m.finished()
        assertEquals(ShareQuality.STANDARD, m.quality)
    }

    // ---- arbiter: one presenter

    @Test
    fun `the latest start is the presenter`() {
        val a = ShareArbiter("a") { NOW }
        a.onStatus("b", true, 100L)
        a.onStatus("c", true, 200L)
        assertEquals("c", a.presenter)
        assertEquals("c", a.remotePresenter)
    }

    @Test
    fun `equal starts are broken by device id so every device agrees`() {
        val onA = ShareArbiter("a") { NOW }
        val onB = ShareArbiter("b") { NOW }
        onA.setLocal(500L)
        onA.onStatus("b", true, 500L)
        onB.setLocal(500L)
        onB.onStatus("a", true, 500L)
        assertEquals("b", onA.presenter)
        assertEquals("b", onB.presenter)
        assertTrue(onA.localMustYield())
        assertFalse(onB.localMustYield())
    }

    @Test
    fun `a take-over beats the share it replaces even when the clocks disagree`() {
        // b's clock is ten minutes behind a's, and b deliberately takes over from a.
        val onB = ShareArbiter("b") { NOW }
        onB.onStatus("a", true, 10_000_000L)
        val start = onB.nextStart(nowMs = 9_400_000L)
        assertTrue(start > 10_000_000L, "a take-over must sort after the share it replaces: $start")
        onB.setLocal(start)
        val onA = ShareArbiter("a") { NOW }
        onA.setLocal(10_000_000L)
        onA.onStatus("b", true, start)
        assertEquals("b", onA.presenter)
        assertTrue(onA.localMustYield())
        assertFalse(onB.localMustYield())
    }

    @Test
    fun `a share stated off or a peer that left clears its claim`() {
        val a = ShareArbiter("a") { NOW }
        a.onStatus("b", true, 10L)
        a.onStatus("b", false, null)
        assertNull(a.presenter)
        a.onStatus("c", true, 10L)
        a.onPeerLeft("c")
        assertNull(a.presenter)
    }

    @Test
    fun `a status that says nothing about sharing changes nothing`() {
        val a = ShareArbiter("a") { NOW }
        a.onStatus("b", true, 10L)
        a.onStatus("b", null, null)
        assertEquals("b", a.presenter)
    }

    @Test
    fun `an old build never states a share and so is never a presenter`() {
        val a = ShareArbiter("a") { NOW }
        a.onStatus("old", null, null)
        assertNull(a.presenter)
    }

    @Test
    fun `a share without a start counter still counts and a absurd one is clamped`() {
        val a = ShareArbiter("a") { NOW }
        a.onStatus("b", true, null)
        assertEquals("b", a.presenter)
        a.onStatus("c", true, Long.MAX_VALUE)
        assertEquals("c", a.presenter)
        // The claim is cut back to the clock plus the skew (S3), so the next start can neither overflow nor sit above the ceiling.
        assertEquals(NOW + ShareArbiter.CLOCK_SKEW_MS + 1, a.nextStart(1L))
    }

    @Test
    fun `a member that states the largest start cannot keep the role from a later honest start (S3)`() {
        // c claims the absurd value; b, who sorts below c by id, then deliberately starts after it.
        val onB = ShareArbiter("b") { NOW }
        val onD = ShareArbiter("d") { NOW }
        for (arbiter in listOf(onB, onD)) arbiter.onStatus("c", true, ShareArbiter.MAX_START)
        val start = onB.nextStart(NOW)
        onB.setLocal(start)
        onD.onStatus("b", true, start)
        assertEquals("b", onD.presenter, "the later start wins on a third device even though c sorts above b by id")
        assertEquals("b", onB.presenter)
        assertFalse(onB.localMustYield(), "the honest presenter is not told to stop")
        // The same on the attacker's own device: it yields.
        val onC = ShareArbiter("c") { NOW }
        onC.setLocal(ShareArbiter.MAX_START)
        onC.onStatus("b", true, start)
        assertEquals("b", onC.presenter)
        assertTrue(onC.localMustYield())
    }

    @Test
    fun `every device agrees after repeated take-overs on top of an absurd claim`() {
        val devices = listOf("a", "b", "c").associateWith { id -> ShareArbiter(id) { NOW } }
        fun broadcast(from: String, start: Long?) {
            devices.forEach { (id, arb) -> if (id != from) arb.onStatus(from, start != null, start) }
        }
        // c is a modified client: its claim reaches the other two, and it never runs the arbiter's own local rules.
        broadcast("c", Long.MAX_VALUE)
        var last = "c"
        repeat(5) { round ->
            val taker = listOf("a", "b")[round % 2]
            val start = devices.getValue(taker).nextStart(NOW)
            devices.getValue(taker).setLocal(start)
            broadcast(taker, start)
            last = taker
            devices.values.forEach { assertEquals(last, it.presenter, "round $round") }
        }
    }

    @Test
    fun `a claim from a clock a day ahead is cut back but still loses to a later start`() {
        val a = ShareArbiter("a") { NOW }
        a.onStatus("z", true, NOW + 10 * 24 * 3_600_000L)
        val next = a.nextStart(NOW)
        a.setLocal(next)
        assertEquals("a", a.presenter)
    }

    @Test
    fun `this device is not told what it states itself`() {
        val a = ShareArbiter("a") { NOW }
        a.onStatus("a", true, 10L)
        assertNull(a.presenter)
        a.setLocal(5L)
        assertEquals("a", a.presenter)
        assertNull(a.remotePresenter)
        assertFalse(a.localMustYield())
        a.setLocal(null)
        assertNull(a.presenter)
    }

    // ---- ladder

    @Test
    fun `the share ladder trades frame rate before height and never goes under 540`() {
        assertEquals(listOf(1080, 720, 720, 540), ShareLadder.RUNGS.map { it.maxHeight })
        assertEquals(listOf(10, 8, 5, 5), ShareLadder.RUNGS.map { it.fps })
        assertTrue(ShareLadder.RUNGS.zipWithNext().all { (a, b) -> a.maxBitrateKbps > b.maxBitrateKbps })
        assertTrue(ShareLadder.RUNGS.all { it.maxHeight >= 540 }, "360p text is unreadable, ADR-098's camera caps are not reused")
    }

    @Test
    fun `watchers lower the rung from three on`() {
        assertEquals(0, ShareLadder.profile(1, ShareQuality.STANDARD, false).level)
        assertEquals(0, ShareLadder.profile(2, ShareQuality.STANDARD, false).level)
        assertEquals(1, ShareLadder.profile(3, ShareQuality.STANDARD, false).level)
        assertEquals(1, ShareLadder.profile(7, ShareQuality.STANDARD, false).level)
    }

    @Test
    fun `lower quality skips the two best rungs`() {
        assertEquals(2, ShareLadder.profile(1, ShareQuality.LOWER, false).level)
        assertEquals(3, ShareLadder.profile(4, ShareQuality.LOWER, false).level)
    }

    @Test
    fun `a struggling computer steps one rung down and the ladder is clamped`() {
        assertEquals(1, ShareLadder.profile(1, ShareQuality.STANDARD, true).level)
        assertEquals(2, ShareLadder.profile(3, ShareQuality.STANDARD, true).level)
        assertEquals(3, ShareLadder.profile(9, ShareQuality.LOWER, true).level, "clamped to the last rung")
        assertEquals(540, ShareLadder.profile(9, ShareQuality.LOWER, true).maxHeight)
    }

    @Test
    fun `the watcher cap follows the tier and a LOW device sends two`() {
        assertEquals(4, ShareLadder.watcherCap(FlashPerformanceMode.HIGH))
        assertEquals(3, ShareLadder.watcherCap(FlashPerformanceMode.MEDIUM))
        assertEquals(2, ShareLadder.watcherCap(FlashPerformanceMode.LOW))
    }

    @Test
    fun `scale down never upscales and an unknown size is not scaled`() {
        assertEquals(1.0, ShareLadder.scaleDownBy(720, 1080))
        assertEquals(1.0, ShareLadder.scaleDownBy(0, 1080))
        assertEquals(2.0, ShareLadder.scaleDownBy(2160, 1080))
        assertEquals(1440.0 / 720, ShareLadder.scaleDownBy(1440, 720))
    }

    @Test
    fun `the tuning keeps resolution, caps the frame rate at the capture rate and carries the rung`() {
        val t = ShareLadder.tuning(ShareLadder.RUNGS[0], sourceHeight = 1440, demoteForVoice = true)
        assertTrue(t.maintainResolution)
        assertFalse(t.maintainFramerate)
        assertEquals(10.0, t.maxFramerate)
        assertEquals(2_500_000, t.maxBitrateBps)
        assertEquals(400_000, t.minBitrateBps)
        assertEquals(1440.0 / 1080, t.scaleResolutionDownBy)
        assertTrue(t.demoteForVoice)
    }

    // ---- strain

    @Test
    fun `struggling starts after three strained samples in a row and ends after twelve clean ones`() {
        val s = ShareStrain()
        assertFalse(s.onSample(true))
        assertFalse(s.onSample(true))
        assertTrue(s.onSample(true))
        assertTrue(s.struggling)
        repeat(11) { assertFalse(s.onSample(false)) }
        assertTrue(s.struggling)
        assertTrue(s.onSample(false))
        assertFalse(s.struggling)
    }

    @Test
    fun `a clean sample interrupts the entering streak and a strained one interrupts the leaving streak`() {
        val s = ShareStrain()
        s.onSample(true); s.onSample(true); s.onSample(false)
        assertFalse(s.onSample(true))
        assertFalse(s.struggling)
        s.onSample(true); s.onSample(true)
        assertTrue(s.struggling)
        repeat(11) { s.onSample(false) }
        s.onSample(true)
        repeat(11) { s.onSample(false) }
        assertTrue(s.struggling, "the streak of clean samples started again")
    }

    // ---- run: leg tuning and watchdog

    private class FakeHandle(private val events: MutableList<String>? = null) : ScreenCaptureHandle {
        override val previewTrack: VideoStreamTrack? = null
        override var width: Int = 0
        override var height: Int = 0
        override var frameCount: Long = 0L
        var closed = 0
        override fun lastFrameAgeMs(): Long? = null
        override suspend fun sendOn(sender: RtpSender) = Unit
        override fun addTo(pc: PeerConnection, stream: MediaStream): RtpSender = error("not used")
        override fun close() { closed++; events?.add("close") }
    }

    private class FakeProvider(val handle: FakeHandle = FakeHandle(), val fail: Boolean = false) : ScreenCaptureProvider {
        override val supported = true
        override val usesSystemPicker = false
        var opened: Quad? = null
        data class Quad(val fps: Int, val w: Int, val h: Int)
        override suspend fun listSources() = listOf(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN))
        override suspend fun open(source: ShareSource, fps: Int, maxWidth: Int, maxHeight: Int): ScreenCaptureHandle {
            if (fail) error("no capturer")
            opened = Quad(fps, maxWidth, maxHeight)
            return handle
        }
    }

    private var now = 1_000L

    private companion object {
        /** A plausible wall clock (September 2026), so small test starts are well inside the arbiter's bound. */
        const val NOW = 1_790_000_000_000L
    }

    private fun run(provider: ScreenCaptureProvider = FakeProvider()) = ScreenShareRun(provider, nowMs = { now })

    @Test
    fun `the screen leaves the senders before the capture closes (S2, ADR-102 D10)`() = runTest {
        val events = mutableListOf<String>()
        val run = run(FakeProvider(FakeHandle(events)))
        run.open(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN))
        run.closeAfter { events += "off-senders" }
        run.closeAfter { events += "off-senders-again" }
        assertEquals(listOf("off-senders", "close", "off-senders-again"), events, "close is idempotent, the sender step is not skipped")
        assertNull(run.handle)
    }

    @Test
    fun `a sender that cannot be emptied does not keep the capture open`() = runTest {
        val events = mutableListOf<String>()
        val run = run(FakeProvider(FakeHandle(events)))
        run.open(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN))
        run.closeAfter { error("connection already closed") }
        assertEquals(listOf("close"), events)
        assertNull(run.handle)
    }

    @Test
    fun `a cancelled sender step still closes the capture and rethrows`() = runTest {
        val events = mutableListOf<String>()
        val run = run(FakeProvider(FakeHandle(events)))
        run.open(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN))
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            run.closeAfter { throw kotlinx.coroutines.CancellationException("caller gone") }
        }
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `open asks the capturer for the ladder's size and rate and close is idempotent`() = runTest {
        val provider = FakeProvider()
        val run = run(provider)
        run.open(ShareSource(1L, "Screen 1", ShareSourceKind.SCREEN))
        assertEquals(FakeProvider.Quad(10, 2560, 1440), provider.opened)
        run.close()
        run.close()
        assertEquals(1, provider.handle.closed)
        assertNull(run.handle)
    }

    @Test
    fun `an open that fails throws and leaves no handle`() = runTest {
        val run = run(FakeProvider(fail = true))
        assertFailsWith<IllegalStateException> { run.open(ShareSource(1L, "x", ShareSourceKind.SCREEN)) }
        assertNull(run.handle)
        run.close()
    }

    @Test
    fun `a leg is capped to what the watcher asked for and its bitrate follows the area`() = runTest {
        val provider = FakeProvider()
        val run = run(provider)
        run.open(ShareSource(1L, "s", ShareSourceKind.SCREEN))
        provider.handle.height = 1440
        val full = run.legTuning(1, askedHeight = null, active = true, concession = VideoConcession.FULL, demoteForVoice = false)
        val asked720 = run.legTuning(1, askedHeight = 720, active = true, concession = VideoConcession.FULL, demoteForVoice = false)
        assertEquals(2_500_000, full.maxBitrateBps)
        assertEquals(1440.0 / 1080, full.scaleResolutionDownBy)
        assertEquals(1440.0 / 720, asked720.scaleResolutionDownBy)
        assertEquals((2_500 * (720.0 / 1080) * (720.0 / 1080)).toInt() * 1_000, asked720.maxBitrateBps)
        assertTrue(asked720.maintainResolution)
    }

    @Test
    fun `a watcher that asks for more than the rung gets the rung and a lower bitrate never goes under the floor`() = runTest {
        val provider = FakeProvider()
        val run = run(provider)
        run.open(ShareSource(1L, "s", ShareSourceKind.SCREEN))
        provider.handle.height = 1080
        val beyond = run.legTuning(3, askedHeight = 1080, active = true, concession = VideoConcession.FULL, demoteForVoice = false)
        assertEquals(1_500_000, beyond.maxBitrateBps, "three watchers are on the 720p rung and 1080 asked is above it")
        val tiny = run.legTuning(1, askedHeight = 100, active = true, concession = VideoConcession.FULL, demoteForVoice = false)
        assertEquals(400_000, tiny.maxBitrateBps)
    }

    @Test
    fun `voice priority still comes first while presenting`() = runTest {
        val provider = FakeProvider()
        val run = run(provider)
        run.open(ShareSource(1L, "s", ShareSourceKind.SCREEN))
        provider.handle.height = 1080
        val reduced = run.legTuning(1, null, true, VideoConcession.REDUCED_RESOLUTION, false)
        assertEquals(625_000, reduced.maxBitrateBps)
        assertEquals(2.0, reduced.scaleResolutionDownBy)
        assertNull(reduced.minBitrateBps, "a concession drops the floor")
        val paused = run.legTuning(1, null, true, VideoConcession.PAUSED, false)
        assertFalse(paused.active)
        val off = run.legTuning(1, null, false, VideoConcession.FULL, false)
        assertFalse(off.active, "a connection nobody asked for sends nothing")
    }

    @Test
    fun `struggling lowers the rung used by every leg`() = runTest {
        val provider = FakeProvider()
        val run = run(provider)
        run.open(ShareSource(1L, "s", ShareSourceKind.SCREEN))
        provider.handle.height = 1080
        assertEquals(1080, run.profile(1).maxHeight)
        repeat(3) { run.strain.onSample(true) }
        assertEquals(720, run.profile(1).maxHeight)
        assertEquals(1_500_000, run.legTuning(1, null, true, VideoConcession.FULL, false).maxBitrateBps)
    }

    @Test
    fun `the watchdog gives a silent capturer five seconds and then calls it dead`() = runTest {
        val provider = FakeProvider()
        val run = run(provider)
        run.open(ShareSource(1L, "s", ShareSourceKind.SCREEN))
        now += 4_999L
        assertEquals(ScreenShareRun.Check.OK, run.check())
        now += 1L
        assertEquals(ScreenShareRun.Check.NO_FRAMES, run.check())
    }

    @Test
    fun `a static screen that already delivered a frame is never called dead`() = runTest {
        val provider = FakeProvider()
        val run = run(provider)
        run.open(ShareSource(1L, "s", ShareSourceKind.SCREEN))
        provider.handle.frameCount = 1
        provider.handle.height = 1080
        now += 10 * 60_000L
        assertEquals(ScreenShareRun.Check.RETUNE, run.check(), "first sight of the size")
        assertEquals(ScreenShareRun.Check.OK, run.check())
        provider.handle.height = 720
        assertEquals(ScreenShareRun.Check.RETUNE, run.check(), "the shared window was resized")
        assertEquals(ScreenShareRun.Check.OK, run.check())
    }

    @Test
    fun `no capture means nothing to check`() {
        assertEquals(ScreenShareRun.Check.OK, run().check())
    }

    @Test
    fun `the platform without a capturer offers nothing`() = runTest {
        assertFalse(NoScreenCapture.supported)
        assertTrue(NoScreenCapture.listSources().isEmpty())
        assertFailsWith<IllegalStateException> { NoScreenCapture.open(ShareSource(1L, "x", ShareSourceKind.SCREEN), 10, 1, 1) }
    }
}
