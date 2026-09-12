package com.transfer.flash.core.ptt

import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.protocol.PttFrameCodec
import com.transfer.flash.core.messaging.protocol.PttPingFrame
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.messaging.protocol.PttSessionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the two inbound seams of [PttSessionEngine] plus the press/lease gates.
 *
 * Scope is deliberately the *decision* surface: decode, dedup, fail-closed rejection, and which
 * frames are claimed (`true` = consumed). Nothing here starts a floor session — a `Talk`/`Listen`
 * state opens a real `AudioRecord`/`AudioTrack`, which needs a physical device and is tested
 * there, not on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttSessionEngineTest {

    private var trusted: Boolean = true
    private var micGranted: Boolean = true
    private var callActive: Boolean = false
    private var members: List<String> = emptyList()

    /** Every text frame handed to the transport sink, so a fan-out test can decode it back. */
    private val sentControl = mutableListOf<Pair<String, String>>()

    private fun newEngine(): PttSessionEngine = PttSessionEngine(
        localId = { LOCAL_ID },
        localName = { LOCAL_NAME },
        isTrustedPeer = { trusted },
        snapshotMembers = { members },
        sendControl = { peerId, text ->
            sentControl += peerId to text
            true
        },
        sendAudio = { _, _ -> },
        hasMicPermission = { micGranted },
        isCallActive = { callActive },
        audioRateHz = { 16_000 },
    )

    // ------------------------------------------------------------------ inbound ping

    @Test
    fun `accepted ping is consumed and emitted once`() = runTest {
        val engine = newEngine()
        val received = mutableListOf<PttPingEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.pings.collect { received += it }
        }
        val frame = PttPingFrame(eventId = "evt-1", from = PEER, senderName = "Ann", sentAt = 42L)

        assertTrue(engine.onInboundText(PEER, PttFrameCodec.encode(frame)))
        assertEquals(listOf(PttPingEvent("evt-1", PEER, "Ann", 42L)), received)

        collector.cancel()
        engine.shutdown()
    }

    @Test
    fun `replayed ping is consumed but emitted only once`() = runTest {
        val engine = newEngine()
        val received = mutableListOf<PttPingEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.pings.collect { received += it }
        }
        val text = PttFrameCodec.encode(
            PttPingFrame(eventId = "evt-2", from = PEER, senderName = "Ann", sentAt = 7L),
        )

        assertTrue(engine.onInboundText(PEER, text))
        assertTrue(engine.onInboundText(PEER, text))
        assertEquals(1, received.size)

        collector.cancel()
        engine.shutdown()
    }

    @Test
    fun `ping claiming another device is consumed and dropped`() = runTest {
        val engine = newEngine()
        val received = mutableListOf<PttPingEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.pings.collect { received += it }
        }
        // Authenticated transport peer is PEER, but the frame claims to come from someone else.
        val text = PttFrameCodec.encode(
            PttPingFrame(eventId = "evt-3", from = "someone-else", senderName = "Mallory", sentAt = 1L),
        )

        assertTrue("a recognized-but-rejected frame must still be consumed", engine.onInboundText(PEER, text))
        assertTrue(received.isEmpty())

        collector.cancel()
        engine.shutdown()
    }

    @Test
    fun `ping from an untrusted peer is consumed and dropped`() = runTest {
        trusted = false
        val engine = newEngine()
        val received = mutableListOf<PttPingEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.pings.collect { received += it }
        }
        val text = PttFrameCodec.encode(
            PttPingFrame(eventId = "evt-4", from = PEER, senderName = "Ann", sentAt = 1L),
        )

        assertTrue(engine.onInboundText(PEER, text))
        assertTrue(received.isEmpty())

        collector.cancel()
        engine.shutdown()
    }

    @Test
    fun `non-ptt text is not claimed so the host can chain handlers`() {
        val engine = newEngine()
        assertFalse(engine.onInboundText(PEER, "FLASH_MSG localId=1 text=hi"))
        assertFalse(engine.onInboundText(PEER, ""))
        engine.shutdown()
    }

    // --------------------------------------------------- inbound session control text

    @Test
    fun `session control frame from the authenticated peer is consumed`() {
        val engine = newEngine()
        val leave = PttSessionCodec.encode(PttSessionFrame.Leave("sess-1", PEER, 1L))
        assertTrue(engine.onInboundText(PEER, leave))
        engine.shutdown()
    }

    @Test
    fun `session control frame claiming another device is consumed and dropped`() {
        val engine = newEngine()
        val leave = PttSessionCodec.encode(PttSessionFrame.Leave("sess-2", "someone-else", 1L))
        assertTrue(engine.onInboundText(PEER, leave))
        engine.shutdown()
    }

    @Test
    fun `session control frame from an untrusted peer is consumed and dropped`() {
        trusted = false
        val engine = newEngine()
        val leave = PttSessionCodec.encode(PttSessionFrame.Leave("sess-3", PEER, 1L))
        assertTrue(engine.onInboundText(PEER, leave))
        engine.shutdown()
    }

    // -------------------------------------------------------------- inbound audio magic

    @Test
    fun `ptt1 binary is claimed even with no live session`() {
        val engine = newEngine()
        val pcm = ByteArray(640)
        val audio = PttAudioFrame.encode(PttAudioFrame("sess-4", seq = 0L, captureTsMs = 3L, pcm = pcm))

        // Idle: the packet is recognized and dropped, never handed on to another parser.
        assertTrue(engine.onInboundBinary(PEER, audio))
        engine.shutdown()
    }

    @Test
    fun `ptt1 binary with no authenticated peer is still claimed`() {
        val engine = newEngine()
        val audio = PttAudioFrame.encode(PttAudioFrame("sess-5", seq = 0L, captureTsMs = 3L, pcm = ByteArray(640)))
        assertTrue(engine.onInboundBinary(null, audio))
        engine.shutdown()
    }

    @Test
    fun `non-ptt binary is not claimed`() {
        val engine = newEngine()
        // "FLSH" — the transfer pipeline's magic, which must fall through untouched.
        assertFalse(engine.onInboundBinary(PEER, byteArrayOf(0x46, 0x4C, 0x53, 0x48, 0, 0, 0, 0)))
        assertFalse(engine.onInboundBinary(PEER, ByteArray(0)))
        engine.shutdown()
    }

    // ------------------------------------------------------------------ press gates

    @Test
    fun `press with no online peers reports NO_PEERS`() {
        val engine = newEngine()
        members = emptyList()
        assertEquals(PttPressOutcome.NO_PEERS, engine.onPttButton())
        engine.shutdown()
    }

    @Test
    fun `press during a call reports CALL_ACTIVE`() {
        callActive = true
        val engine = newEngine()
        members = listOf(PEER)
        assertEquals(PttPressOutcome.CALL_ACTIVE, engine.onPttButton())
        engine.shutdown()
    }

    @Test
    fun `press without the microphone grant reports NO_MIC`() {
        micGranted = false
        val engine = newEngine()
        members = listOf(PEER)
        assertEquals(PttPressOutcome.NO_MIC, engine.onPttButton())
        engine.shutdown()
    }

    @Test
    fun `press with a live voice-note lease is refused`() {
        val engine = newEngine()
        members = listOf(PEER)
        val lease = engine.acquireVoiceNoteLease()
        assertNotNull(lease)

        assertEquals(PttPressOutcome.VOICE_NOTE_ACTIVE, engine.onPttButton())

        // A foreign lease id must not release it.
        engine.releaseVoiceNoteLease("not-my-lease")
        assertEquals(PttPressOutcome.VOICE_NOTE_ACTIVE, engine.onPttButton())

        engine.releaseVoiceNoteLease(lease!!)
        assertNotNull("the gate is free again after its own lease is released", engine.acquireVoiceNoteLease())

        engine.shutdown()
    }

    @Test
    fun `lease acquisition is refused while another lease is held`() {
        val engine = newEngine()
        assertNotNull(engine.acquireVoiceNoteLease())
        assertNull(engine.acquireVoiceNoteLease())
        engine.shutdown()
    }

    // ------------------------------------------------------------------ outbound ping

    @Test
    fun `sendPing fans one frame out to trusted online peers only`() {
        trusted = true
        val engine = newEngine()
        members = listOf(PEER, "peer-b")

        assertTrue(engine.sendPing())

        assertEquals(2, sentControl.size)
        assertEquals(setOf(PEER, "peer-b"), sentControl.map { it.first }.toSet())
        val frames = sentControl.map { PttFrameCodec.decode(it.second) }
        assertTrue(frames.all { it != null })
        assertEquals(LOCAL_ID, frames.first()!!.from)
        assertEquals(LOCAL_NAME, frames.first()!!.senderName)
        // One press is one event: every peer receives the identical frame, which is what makes a
        // duplicate delivery on one leg (not a second press) the thing a receiver dedups.
        assertEquals(1, frames.map { it!!.eventId }.toSet().size)

        engine.shutdown()
    }

    @Test
    fun `sendPing reports false when nobody is online`() {
        val engine = newEngine()
        members = emptyList()
        assertFalse(engine.sendPing())
        assertTrue(sentControl.isEmpty())
        engine.shutdown()
    }

    @Test
    fun `sendPing skips untrusted online peers`() {
        trusted = false
        val engine = newEngine()
        members = listOf(PEER)
        assertFalse(engine.sendPing())
        assertTrue(sentControl.isEmpty())
        engine.shutdown()
    }

    private companion object {
        const val LOCAL_ID = "self-device"
        const val LOCAL_NAME = "Self"
        const val PEER = "peer-a"
    }
}
