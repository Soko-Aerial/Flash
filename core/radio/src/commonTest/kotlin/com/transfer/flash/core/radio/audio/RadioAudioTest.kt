package com.transfer.flash.core.radio.audio

import com.transfer.flash.core.radio.audio.sbc.SbcEncoder
import com.transfer.flash.core.radio.link.ByteLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun sine(samples: Int, hz: Double = 440.0, rate: Int = 32_000, amp: Int = 8000, from: Int = 0) =
    ShortArray(samples) { (amp * sin(2 * PI * hz * (from + it) / rate)).toInt().toShort() }

private fun hex(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

private fun framesOf(bytes: ByteArray): List<AudioFrame> {
    val out = ArrayList<AudioFrame>()
    AudioFrameDecoder { out += it }.feed(bytes)
    return out
}

class RadioAudioFramingTest {
    @Test
    fun endFrameIsTheDocumentedBytes() {
        assertContentEquals(
            hex(0x7E, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0x7E),
            RadioAudioFraming.END_FRAME,
        )
        val f = framesOf(RadioAudioFraming.END_FRAME).single()
        assertEquals(RadioAudioFraming.CMD_END, f.command)
    }

    @Test
    fun escapesDelimiterAndEscapeBytes() {
        val payload = hex(1, 0x7E, 2, 0x7D, 3)
        val wire = RadioAudioFraming.encode(0, payload)
        assertContentEquals(hex(0x7E, 0, 1, 0x7D, 0x5E, 2, 0x7D, 0x5D, 3, 0x7E), wire)
        val f = framesOf(wire).single()
        assertContentEquals(payload, f.payload)
    }

    @Test
    fun everyByteValueSurvivesARoundTrip() {
        val payload = ByteArray(256) { it.toByte() }
        assertContentEquals(payload, framesOf(RadioAudioFraming.encode(3, payload)).single().payload)
    }

    @Test
    fun decoderHandlesSplitFeedsAndLeadingGarbage() {
        val a = RadioAudioFraming.encode(0, hex(1, 2, 3))
        val b = RadioAudioFraming.encode(1, hex(9))
        val all = hex(0x55, 0x66) + a + b
        val out = ArrayList<AudioFrame>()
        val d = AudioFrameDecoder { out += it }
        for (x in all) d.feed(byteArrayOf(x))
        assertEquals(2, out.size)
        assertContentEquals(hex(1, 2, 3), out[0].payload)
        assertEquals(1, out[1].command)
    }

    @Test
    fun oversizedFrameIsDroppedAndTheNextOneStillArrives() {
        val big = RadioAudioFraming.encode(0, ByteArray(RadioAudioFraming.MAX_FRAME + 100) { 1 })
        val ok = RadioAudioFraming.encode(0, hex(7))
        val out = ArrayList<AudioFrame>()
        val d = AudioFrameDecoder { out += it }
        d.feed(big + ok)
        assertEquals(1, out.size)
        assertEquals(1, d.dropped)
    }
}

class RadioAudioCodecTest {
    private fun sbcPacket(pcm: ShortArray, bitpool: Int = 40, frames: Int = 3): ByteArray {
        val enc = SbcEncoder()
        val frame = RadioAudioFormat.frame(bitpool)
        var all = ByteArray(0)
        for (i in 0 until frames) {
            all += enc.encode(pcm, null, frame, i * RadioAudioFormat.SAMPLES_PER_FRAME)!!
        }
        return all
    }

    @Test
    fun receiverDecodesConcatenatedSbcFramesInOnePacket() {
        val pcm = sine(128 * 3)
        val got = ArrayList<ShortArray>()
        var starts = 0
        var ends = 0
        val r = RadioAudioReceiver({ starts++ }, { got += it }, { ends++ })
        r.onFrame(AudioFrame(RadioAudioFraming.CMD_AUDIO, sbcPacket(pcm)))
        assertEquals(3, got.size)
        assertTrue(got.all { it.size == 128 })
        assertEquals(1, starts)
        r.onFrame(AudioFrame(RadioAudioFraming.CMD_AUDIO_ALT, sbcPacket(pcm)))
        assertEquals(1, starts, "still the same run")
        r.onFrame(AudioFrame(RadioAudioFraming.CMD_END, ByteArray(0)))
        assertEquals(1, ends)
        r.onFrame(AudioFrame(RadioAudioFraming.CMD_END, ByteArray(0)))
        assertEquals(1, ends, "a second END is ignored")
    }

    @Test
    fun receiverIgnoresEchoAckAndGarbage() {
        var pcmCalls = 0
        val r = RadioAudioReceiver({}, { pcmCalls++ }, {})
        r.onFrame(AudioFrame(RadioAudioFraming.CMD_TX_ECHO, sbcPacket(sine(384))))
        r.onFrame(AudioFrame(RadioAudioFraming.CMD_ACK, ByteArray(4)))
        assertEquals(0, pcmCalls)
        assertFalse(r.inRun)
        r.onFrame(AudioFrame(RadioAudioFraming.CMD_AUDIO, hex(1, 2, 3, 0x9C, 0, 0, 0, 0, 0)))
        assertEquals(0, pcmCalls)
        assertTrue(r.badFrames >= 0)
    }

    @Test
    fun transmitterPacksAboutThreeHundredBytesAndEndsWithTheEndFrame() {
        val tx = RadioAudioTransmitter(bitpool = 18)
        val packets = tx.push(sine(128 * 20))
        assertTrue(packets.isNotEmpty())
        for (p in packets) {
            val f = framesOf(p).single()
            assertEquals(RadioAudioFraming.CMD_AUDIO, f.command)
            assertTrue(f.payload.size >= RadioAudioFormat.PACKET_TARGET_BYTES, "packet ${f.payload.size}")
            assertEquals(0, f.payload.size % 44, "whole 44-byte SBC frames")
            assertEquals(0x9C, f.payload[0].toInt() and 0xFF)
        }
        val tail = tx.finish()
        assertContentEquals(RadioAudioFraming.END_FRAME, tail.last())
    }

    @Test
    fun transmittedAudioDecodesBackToTheSameLength() {
        val tx = RadioAudioTransmitter(bitpool = 40)
        val pcm = sine(128 * 9 + 50) // not a multiple of a frame
        val wire = tx.push(pcm, 700) + tx.push(pcm.copyOfRange(700, pcm.size)) + tx.finish()
        var samples = 0
        var ends = 0
        val r = RadioAudioReceiver({}, { samples += it.size }, { ends++ })
        val d = AudioFrameDecoder { r.onFrame(it) }
        for (p in wire) d.feed(p)
        assertEquals(128 * 10, samples, "the partial last frame is padded")
        assertEquals(1, ends)
    }

    @Test
    fun resamplerKeepsDurationAndLevel() {
        for (rate in listOf(16_000, 44_100, 48_000)) {
            val rs = LinearResampler(rate)
            var out = 0
            val chunk = rate / 50
            repeat(50) { out += rs.process(ShortArray(chunk) { 1000 }).size }
            assertTrue(kotlin.math.abs(out - 32_000) <= 3, "rate $rate gave $out")
        }
        val dc = LinearResampler(48_000).process(ShortArray(480) { 1000 })
        assertTrue(dc.all { it.toInt() == 1000 } || dc.drop(1).all { it.toInt() == 1000 })
        assertEquals(480, LinearResampler(32_000).process(ShortArray(480)).size)
    }

    @Test
    fun resamplerIsContinuousAcrossChunks() {
        val src = sine(4800, hz = 300.0, rate = 48_000)
        val whole = LinearResampler(48_000).process(src)
        val rs = LinearResampler(48_000)
        val parts = rs.process(src, 1000) + rs.process(src.copyOfRange(1000, 4800))
        val n = minOf(whole.size, parts.size)
        assertTrue(kotlin.math.abs(whole.size - parts.size) <= 1)
        for (i in 0 until n) assertTrue(kotlin.math.abs(whole[i] - parts[i]) <= 1, "sample $i")
    }
}

private class PipeLink : ByteLink {
    override val description = "pipe"
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val written = ArrayList<ByteArray>()
    var closed = false
    private var pending: ByteArray? = null

    override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
        val chunk = pending ?: run {
            val r = withTimeoutOrNull(timeoutMs) { incoming.receiveCatching() } ?: return 0
            r.getOrNull() ?: return -1
        }
        val n = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, n)
        pending = if (n < chunk.size) chunk.copyOfRange(n, chunk.size) else null
        return n
    }

    override suspend fun write(data: ByteArray) {
        if (closed) throw com.transfer.flash.core.radio.link.LinkException("closed")
        written += data
    }

    override fun close() {
        closed = true
        incoming.close()
    }
}

private class FakeOutput : RadioPcmOutput {
    var started = 0
    var stopped = 0
    var samples = 0
    override fun start(sampleRate: Int) { started++; assertEquals(32_000, sampleRate) }
    override fun write(pcm: ShortArray, length: Int) { samples += length }
    override fun stop() { stopped++ }
}

private class FakeInput(private val rate: Int = 16_000) : RadioPcmInput {
    var starts = 0
    var stops = 0
    private var n = 0
    override fun start(): Int { starts++; return rate }
    override suspend fun read(buffer: ShortArray): Int {
        delay(20)
        val s = sine(rate / 50, rate = rate, from = n)
        n += s.size
        s.copyInto(buffer)
        return s.size
    }
    override fun stop() { stops++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RadioAudioSessionTest {
    private fun audioWire(frames: Int = 4): ByteArray {
        val enc = SbcEncoder()
        val frame = RadioAudioFormat.frame(40)
        var payload = ByteArray(0)
        repeat(frames) { payload += enc.encode(sine(128, from = it * 128), null, frame)!! }
        return RadioAudioFraming.encode(RadioAudioFraming.CMD_AUDIO, payload)
    }

    @Test
    fun receivingPlaysAudioAndGoesBackToListeningOnEnd() = runTest {
        val link = PipeLink(); val out = FakeOutput()
        val s = RadioAudioSession(link, out, FakeInput(), backgroundScope)
        s.start(); runCurrent()
        assertEquals(RadioAudioPhase.LISTENING, s.state.value.phase)
        link.incoming.send(audioWire(4)); runCurrent()
        assertEquals(RadioAudioPhase.RECEIVING, s.state.value.phase)
        assertEquals(512, out.samples)
        assertTrue(s.state.value.rxLevel > 0.05f)
        link.incoming.send(RadioAudioFraming.END_FRAME); runCurrent()
        assertEquals(RadioAudioPhase.LISTENING, s.state.value.phase)
        s.close(); runCurrent()
        assertEquals(RadioAudioPhase.CLOSED, s.state.value.phase)
        assertEquals(out.started, out.stopped)
    }

    @Test
    fun aRunWithoutAnEndFrameEndsAfterTheIdleWatchdog() = runTest {
        val link = PipeLink()
        val s = RadioAudioSession(link, FakeOutput(), FakeInput(), backgroundScope)
        s.start(); runCurrent()
        link.incoming.send(audioWire(2)); runCurrent()
        assertEquals(RadioAudioPhase.RECEIVING, s.state.value.phase)
        advanceTimeBy(500); runCurrent()
        assertEquals(RadioAudioPhase.RECEIVING, s.state.value.phase)
        advanceTimeBy(400); runCurrent()
        assertEquals(RadioAudioPhase.LISTENING, s.state.value.phase)
        s.close(); runCurrent()
    }

    @Test
    fun pushToTalkIsRefusedWhileReceiving() = runTest {
        val link = PipeLink(); val input = FakeInput()
        val s = RadioAudioSession(link, FakeOutput(), input, backgroundScope)
        s.start(); runCurrent()
        link.incoming.send(audioWire(2)); runCurrent()
        assertFalse(s.pushToTalkDown())
        assertEquals(0, input.starts)
        assertTrue(link.written.isEmpty())
        assertTrue(s.state.value.message!!.contains("receiving"))
        s.close(); runCurrent()
    }

    @Test
    fun pushToTalkSendsAudioPacketsThenTheEndFrame() = runTest {
        val link = PipeLink(); val input = FakeInput()
        val s = RadioAudioSession(link, FakeOutput(), input, backgroundScope)
        s.start(); runCurrent()
        assertTrue(s.pushToTalkDown()); runCurrent()
        assertEquals(RadioAudioPhase.TRANSMITTING, s.state.value.phase)
        advanceTimeBy(400); runCurrent()
        s.pushToTalkUp()
        advanceTimeBy(100); runCurrent()
        assertEquals(RadioAudioPhase.LISTENING, s.state.value.phase)
        assertEquals(1, input.starts); assertEquals(1, input.stops)
        assertContentEquals(RadioAudioFraming.END_FRAME, link.written.last())
        val audio = link.written.dropLast(1)
        assertTrue(audio.isNotEmpty())
        for (p in audio) assertEquals(RadioAudioFraming.CMD_AUDIO, framesOf(p).single().command)
        // about 400 ms at 32 kHz is 12800 samples = 100 SBC frames of 44 bytes
        val bytes = audio.sumOf { framesOf(it).single().payload.size }
        assertTrue(bytes in 3500..5000, "sent $bytes bytes")
        s.close(); runCurrent()
    }

    @Test
    fun closingWhileTransmittingUnkeysTheRadioFirst() = runTest {
        val link = PipeLink()
        val s = RadioAudioSession(link, FakeOutput(), FakeInput(), backgroundScope)
        s.start(); runCurrent()
        s.pushToTalkDown(); runCurrent()
        advanceTimeBy(200); runCurrent()
        s.close()
        advanceTimeBy(200); runCurrent()
        assertContentEquals(RadioAudioFraming.END_FRAME, link.written.last())
        assertEquals(RadioAudioPhase.CLOSED, s.state.value.phase)
        assertTrue(link.closed)
    }

    @Test
    fun aClosedLinkFailsTheSessionWithASentence() = runTest {
        val link = PipeLink(); val out = FakeOutput()
        val s = RadioAudioSession(link, out, FakeInput(), backgroundScope)
        s.start(); runCurrent()
        link.incoming.send(audioWire(1)); runCurrent()
        link.incoming.close(); runCurrent()
        assertEquals(RadioAudioPhase.FAILED, s.state.value.phase)
        assertEquals("The radio closed the audio link.", s.state.value.message)
        assertEquals(out.started, out.stopped)
        assertFalse(s.pushToTalkDown())
    }
}
