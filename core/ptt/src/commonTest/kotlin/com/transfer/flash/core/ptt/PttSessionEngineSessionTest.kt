package com.transfer.flash.core.ptt

import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.messaging.protocol.PttSessionFrame
import com.transfer.flash.core.messaging.ptt.PttFloorState
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A whole PTT session through [PttSessionEngine] on fake audio devices: what the floor machine
 * decides, what the engine then does to the devices and to the transport, in which order.
 *
 * The engine runs its own coroutines on `Dispatchers.Default`, so these tests wait in real time
 * (`awaitUntil`) instead of advancing a virtual clock; every wait has a hard timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttSessionEngineSessionTest {
    @Volatile
    private var members: List<String> = listOf(PEER)

    @Volatile
    private var controlDelivered: Boolean = true

    private val audio = FakePttAudio()
    private val sentControl = Recorder<Pair<String, String>>()
    private val sentAudio = Recorder<Pair<String, ByteArray>>()

    private fun newEngine(): PttSessionEngine = PttSessionEngine(
        localId = { LOCAL_ID },
        localName = { LOCAL_NAME },
        isTrustedPeer = { true },
        snapshotMembers = { members },
        sendControl = { peerId, text ->
            sentControl.add(peerId to text)
            PttSessionCodec.decode(text)?.let { audio.events.add("control:${it::class.simpleName}") }
            controlDelivered
        },
        sendAudio = { peerId, bytes -> sentAudio.add(peerId to bytes) },
        hasMicPermission = { true },
        isCallActive = { false },
        audioRateHz = { 16_000 },
        audio = audio,
        elapsedRealtimeMs = ::clockMs,
    )

    // A real, strictly positive monotonic clock: the wire codecs reject a zero timestamp, and the
    // Android host target must not read SystemClock (an android.jar stub).
    private val clockOrigin = TimeSource.Monotonic.markNow()

    private fun clockMs(): Long = clockOrigin.elapsedNow().inWholeMilliseconds + 1

    private fun controlFrames(): List<Pair<String, PttSessionFrame>> =
        sentControl.snapshot().mapNotNull { (peer, text) -> PttSessionCodec.decode(text)?.let { peer to it } }

    private inline fun <reified F : PttSessionFrame> sentFrames(): List<F> =
        controlFrames().map { it.second }.filterIsInstance<F>()

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        try {
            withContext(Dispatchers.Default) {
                withTimeout(TIMEOUT_MS) { while (!condition()) delay(POLL_MS) }
            }
        } catch (_: TimeoutCancellationException) {
            fail("timed out waiting for: $what")
        }
    }

    /** Subscribes before the test acts (the notice flow has no replay); cancelled with the test. */
    private fun TestScope.collectNotices(engine: PttSessionEngine): Recorder<String> {
        val notices = Recorder<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.notices.collect { notices.add(it) } }
        return notices
    }

    private suspend fun withEngine(block: suspend (PttSessionEngine) -> Unit) {
        val engine = newEngine()
        try {
            block(engine)
        } finally {
            engine.shutdown()
        }
    }

    private fun pcm(fill: Int, bytes: Int = PACKET_BYTES) = ByteArray(bytes) { ((it % 40) + fill).toByte() }

    // ------------------------------------------------------------------ talking

    @Test
    fun `a press starts capture, announces the session, then opens packets and streams audio`() = runTest {
        withEngine { engine ->
            assertEquals(PttPressOutcome.ACCEPTED, engine.onPttButton())

            awaitUntil("capture packets enabled") { audio.captures.snapshot().firstOrNull()?.packetsEnabled == true }
            val capture = audio.captures.snapshot().single()
            val talking = assertIs<PttFloorState.Talking>(engine.state.value)

            val start = sentFrames<PttSessionFrame.Start>().single()
            assertEquals(talking.sessionId, start.sessionId)
            assertEquals(LOCAL_ID, start.from)
            assertEquals(LOCAL_NAME, start.senderName)
            assertEquals(16_000, start.sampleRateHz)
            assertEquals(20, start.packetMs)
            assertEquals(PEER, controlFrames().first { it.second is PttSessionFrame.Start }.first)
            val events = audio.events.snapshot()
            assertTrue(
                events.indexOf("control:Start") in 0 until events.indexOf("capture.enablePackets"),
                "the Start frame must be sent before packets are enabled: $events",
            )

            capture.emit(pcm(1), captureTsMs = 1_000L)
            capture.emit(pcm(2), captureTsMs = 1_020L)
            awaitUntil("two audio frames sent") { sentAudio.size == 2 }

            val frames = sentAudio.snapshot().map { (peer, bytes) ->
                peer to assertNotNull(PttAudioFrame.decodeExpectedSize(bytes, PACKET_BYTES))
            }
            assertEquals(listOf(PEER, PEER), frames.map { it.first })
            assertEquals(listOf(0L, 1L), frames.map { it.second.seq })
            assertEquals(listOf(1_000L, 1_020L), frames.map { it.second.captureTsMs })
            assertEquals(talking.sessionId, frames.first().second.sessionId)
            assertContentEquals(pcm(1), frames.first().second.pcm)
        }
    }

    @Test
    fun `stopping a talk session stops capture and tells the members`() = runTest {
        withEngine { engine ->
            engine.onPttButton()
            awaitUntil("talking") { engine.state.value is PttFloorState.Talking }
            val sessionId = (engine.state.value as PttFloorState.Talking).sessionId
            // The engine publishes Talking BEFORE it runs the StartCapture effect, so on a slow runner the state is visible
            // while the capture device does not exist yet (CI 2026-10-08, NoSuchElementException on a Linux runner).
            awaitUntil("capture opened and announced") { audio.captures.snapshot().firstOrNull()?.packetsEnabled == true }

            engine.stopLocal()
            awaitUntil("idle again") { engine.state.value is PttFloorState.Idle }
            // Likewise Idle is published before the StopCapture / SendStop effects run: wait for the effects, not the state.
            awaitUntil("capture stopped and Stop sent") {
                audio.captures.snapshot().singleOrNull()?.stopped == true && sentFrames<PttSessionFrame.Stop>().isNotEmpty()
            }

            val stop = sentFrames<PttSessionFrame.Stop>().single()
            assertEquals(sessionId, stop.sessionId)
            assertEquals(LOCAL_ID, stop.from)
        }
    }

    @Test
    fun `a capture that cannot open ends the session with a notice and sends nothing`() = runTest {
        audio.captureStart = PttCaptureStart.Failed
        withEngine { engine ->
            val notices = collectNotices(engine)

            engine.onPttButton()
            awaitUntil("mic-denied notice") { "Microphone unavailable — session ended" in notices.snapshot() }

            assertIs<PttFloorState.Idle>(engine.state.value)
            assertTrue(sentFrames<PttSessionFrame.Start>().isEmpty(), "no Start frame for a session that never captured")
        }
    }

    @Test
    fun `a Start that reaches nobody ends the session and stops capture`() = runTest {
        controlDelivered = false
        withEngine { engine ->
            val notices = collectNotices(engine)

            engine.onPttButton()
            awaitUntil("no-members notice") { "No paired devices online" in notices.snapshot() }

            assertIs<PttFloorState.Idle>(engine.state.value)
            assertTrue(audio.captures.snapshot().single().stopped)
        }
    }

    @Test
    fun `the capture device dying mid-session releases the floor`() = runTest {
        withEngine { engine ->
            engine.onPttButton()
            awaitUntil("packets enabled") { audio.captures.snapshot().firstOrNull()?.packetsEnabled == true }

            audio.captures.snapshot().single().lose()

            awaitUntil("idle after capture loss") { engine.state.value is PttFloorState.Idle }
            // Idle is published before the SendStop effect runs.
            awaitUntil("Stop sent") { sentFrames<PttSessionFrame.Stop>().isNotEmpty() }
            assertEquals(1, sentFrames<PttSessionFrame.Stop>().size)
        }
    }

    @Test
    fun `a call starting ends a talk session`() = runTest {
        withEngine { engine ->
            engine.onPttButton()
            awaitUntil("talking") { engine.state.value is PttFloorState.Talking }
            awaitUntil("capture opened") { audio.captures.snapshot().isNotEmpty() }

            engine.onCallStarted()

            awaitUntil("idle after call start") { engine.state.value is PttFloorState.Idle }
            awaitUntil("capture stopped") { audio.captures.snapshot().singleOrNull()?.stopped == true }
            assertTrue(audio.captures.snapshot().single().stopped)
        }
    }

    @Test
    fun `shutdown stops the capture device and resets the state`() = runTest {
        val engine = newEngine()
        engine.onPttButton()
        awaitUntil("packets enabled") { audio.captures.snapshot().firstOrNull()?.packetsEnabled == true }

        engine.shutdown()

        assertTrue(audio.captures.snapshot().single().stopped)
        assertIs<PttFloorState.Idle>(engine.state.value)
        assertNull(engine.stats.value)
    }

    @Test
    fun `a heartbeat is sent every second and its ack produces a round-trip time`() = runTest {
        withEngine { engine ->
            engine.onPttButton()
            awaitUntil("a heartbeat sent") { sentFrames<PttSessionFrame.Heartbeat>().isNotEmpty() }
            val heartbeat = sentFrames<PttSessionFrame.Heartbeat>().first()
            assertNull(engine.stats.value?.rttMs, "no round trip before an ack")

            engine.onInboundText(
                PEER,
                PttSessionCodec.encode(PttSessionFrame.HeartbeatAck(heartbeat.sessionId, PEER, heartbeat.seq, 1L)),
            )

            awaitUntil("rtt published") { engine.stats.value?.rttMs != null }
            assertEquals(PttRole.TALKER, engine.stats.value?.role)
        }
    }

    // ------------------------------------------------------------------ listening

    private fun start(sessionId: String = SESSION, rateHz: Int = 16_000, packetMs: Int = 20) =
        PttSessionCodec.encode(PttSessionFrame.Start(sessionId, PEER, "Ann", 1L, rateHz, packetMs))

    private suspend fun listenTo(engine: PttSessionEngine): FakePlayout {
        assertTrue(engine.onInboundText(PEER, start()))
        awaitUntil("playout started") { audio.playouts.snapshot().firstOrNull()?.started == true }
        return audio.playouts.snapshot().single()
    }

    @Test
    fun `a peer's Start opens playout at the announced format`() = runTest {
        withEngine { engine ->
            val playout = listenTo(engine)

            val listening = assertIs<PttFloorState.Listening>(engine.state.value)
            assertEquals(SESSION, listening.sessionId)
            assertEquals(PEER, listening.holderId)
            assertEquals(16_000, playout.sampleRateHz)
            assertEquals(20, playout.packetMs)
        }
    }

    @Test
    fun `a low-rate Start opens playout at 8 kHz and 60 ms`() = runTest {
        withEngine { engine ->
            assertTrue(engine.onInboundText(PEER, start(rateHz = 8_000, packetMs = 60)))
            awaitUntil("playout started") { audio.playouts.snapshot().firstOrNull()?.started == true }

            val playout = audio.playouts.snapshot().single()
            assertEquals(8_000, playout.sampleRateHz)
            assertEquals(60, playout.packetMs)
        }
    }

    @Test
    fun `only audio of the live session from the holder reaches playout`() = runTest {
        withEngine { engine ->
            val playout = listenTo(engine)

            fun frame(session: String, seq: Long, bytes: Int = PACKET_BYTES) =
                PttAudioFrame.encode(PttAudioFrame(session, seq, seq * 20, pcm(3, bytes)))

            assertTrue(engine.onInboundBinary(PEER, frame("another-session", 0)), "claimed, then dropped")
            assertTrue(engine.onInboundBinary("someone-else", frame(SESSION, 1)), "claimed, then dropped")
            assertTrue(engine.onInboundBinary(PEER, frame(SESSION, 2, bytes = PACKET_BYTES - 2)), "claimed, then dropped")
            assertEquals(0, playout.offered.size)

            assertTrue(engine.onInboundBinary(PEER, frame(SESSION, 3)))

            val offered = playout.offered.snapshot().single()
            assertEquals(3L, offered.seq)
            assertEquals(60L, offered.captureTsMs)
            assertContentEquals(pcm(3), offered.pcm)
        }
    }

    @Test
    fun `a heartbeat from the holder is acknowledged with the same sequence number`() = runTest {
        withEngine { engine ->
            listenTo(engine)

            engine.onInboundText(
                PEER,
                PttSessionCodec.encode(PttSessionFrame.Heartbeat(SESSION, PEER, 7L, 1L, null)),
            )

            awaitUntil("ack sent") { sentFrames<PttSessionFrame.HeartbeatAck>().isNotEmpty() }
            val ack = sentFrames<PttSessionFrame.HeartbeatAck>().single()
            assertEquals(7L, ack.seq)
            assertEquals(SESSION, ack.sessionId)
            assertEquals(LOCAL_ID, ack.from)
            assertEquals(PEER, controlFrames().first { it.second is PttSessionFrame.HeartbeatAck }.first)
        }
    }

    @Test
    fun `the holder's Stop ends playout and posts a notice`() = runTest {
        withEngine { engine ->
            val notices = collectNotices(engine)
            val playout = listenTo(engine)

            engine.onInboundText(PEER, PttSessionCodec.encode(PttSessionFrame.Stop(SESSION, PEER, 2L)))

            awaitUntil("idle after Stop") { engine.state.value is PttFloorState.Idle }
            assertTrue(playout.stopped)
            awaitUntil("ended notice") { "Ann stopped talking" in notices.snapshot() }
        }
    }

    @Test
    fun `a listener that stops tells the holder it left`() = runTest {
        // ERROR-046: holderId must survive StopPlayout so the Leave that follows it can be addressed.
        withEngine { engine ->
            val playout = listenTo(engine)

            engine.stopLocal()

            awaitUntil("Leave sent") { sentFrames<PttSessionFrame.Leave>().isNotEmpty() }
            assertTrue(playout.stopped)
            assertEquals(PEER, controlFrames().first { it.second is PttSessionFrame.Leave }.first)
            assertEquals(SESSION, sentFrames<PttSessionFrame.Leave>().single().sessionId)
        }
    }

    @Test
    fun `playout that cannot open ends the listen with a notice`() = runTest {
        audio.playoutStarts = false
        withEngine { engine ->
            val notices = collectNotices(engine)

            engine.onInboundText(PEER, start())
            awaitUntil("could-not-listen notice") { "Could not start listening" in notices.snapshot() }

            assertIs<PttFloorState.Idle>(engine.state.value)
            awaitUntil("Leave sent") { sentFrames<PttSessionFrame.Leave>().isNotEmpty() }
        }
    }

    @Test
    fun `the playout device dying mid-session ends the listen`() = runTest {
        withEngine { engine ->
            val notices = collectNotices(engine)
            val playout = listenTo(engine)

            playout.lose()

            awaitUntil("idle after playout loss") { engine.state.value is PttFloorState.Idle }
            awaitUntil("audio-fault notice") { "Listening stopped (audio fault)" in notices.snapshot() }
        }
    }

    @Test
    fun `a Start while a call is active is ignored`() = runTest {
        val engine = PttSessionEngine(
            localId = { LOCAL_ID },
            localName = { LOCAL_NAME },
            isTrustedPeer = { true },
            snapshotMembers = { members },
            sendControl = { _, _ -> true },
            sendAudio = { _, _ -> },
            hasMicPermission = { true },
            isCallActive = { true },
            audioRateHz = { 16_000 },
            audio = audio,
            elapsedRealtimeMs = ::clockMs,
        )
        try {
            assertTrue(engine.onInboundText(PEER, start()))
            withContext(Dispatchers.Default) { delay(200) } // real time: gives a wrongly-accepted Start time to show

            assertIs<PttFloorState.Idle>(engine.state.value)
            assertEquals(0, audio.playouts.size)
            assertFalse(audio.events.snapshot().contains("playout.start"))
        } finally {
            engine.shutdown()
        }
    }

    private companion object {
        const val LOCAL_ID = "self-device"
        const val LOCAL_NAME = "Self"
        const val PEER = "peer-a"
        const val SESSION = "session-1"
        const val PACKET_BYTES = 640
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
    }
}
