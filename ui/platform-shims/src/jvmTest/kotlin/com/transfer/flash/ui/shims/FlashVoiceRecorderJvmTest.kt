package com.transfer.flash.ui.shims

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The parts of the desktop voice recorder that need no microphone.
 *
 * Capture itself cannot be tested here — `AudioSystem.getLine` needs a real input device, and a CI box
 * has none, which is exactly why `start()` is written to return `false` rather than throw. What *is*
 * testable is the arithmetic the waveform is drawn from and the two lifecycle paths a user can reach
 * without ever recording: stopping something that never started, and cancelling a fresh recorder.
 *
 * [peakOf] was lifted out of `JvmVoiceRecorder.recordPeak` for this: it is pure, it is the one piece
 * of the file that can be silently wrong (sign extension and byte order both have plausible-looking
 * wrong answers), and reading it from a test must not require an open line.
 */
class FlashVoiceRecorderJvmTest {

    /** Little-endian 16-bit PCM: low byte first. */
    private fun pcm(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `peak reads signed little-endian samples`() {
        val buffer = pcm(300, -1200, 5)

        // -1200 is the loudest by magnitude. A reader that forgot the sign extension would see 64336
        // (0xFB50 unsigned); one that swapped the byte order would see 0x50FB = 20731. Both would pin
        // the waveform near full scale on quiet audio.
        assertEquals(1200, peakOf(buffer, buffer.size))
    }

    @Test
    fun `peak only reads the bytes the line actually delivered`() {
        val buffer = pcm(300, -1200, 5)

        // `TargetDataLine.read` returns how many bytes it filled, and the tail of the buffer still
        // holds the previous pass. Honouring `length` is what keeps a stale loud frame from being
        // reported again after the audio goes quiet.
        assertEquals(300, peakOf(buffer, 2))
        // A length past the end is clamped rather than throwing.
        assertEquals(1200, peakOf(buffer, buffer.size + 64))
    }

    @Test
    fun `a trailing odd byte carries no amplitude`() {
        val buffer = pcm(300) + byteArrayOf(0x7F)

        // Half a sample is not a sample. Read as one it would be 0x7F00 = 32512 — near full scale,
        // from a single stray byte.
        assertEquals(300, peakOf(buffer, buffer.size))
    }

    @Test
    fun `silence reports zero`() {
        assertEquals(0, peakOf(ByteArray(16), 16))
        assertEquals(0, peakOf(ByteArray(0), 0))
        assertEquals(0, peakOf(pcm(4000, -4000), 0))
    }

    @Test
    fun `a full-scale negative sample is the loudest magnitude there is`() {
        // -32768 has no positive counterpart in 16-bit PCM, so its magnitude exceeds the 32767 that
        // `maxAmplitude()` divides by. That is fine and deliberate: the division is followed by
        // `coerceIn(0, 100)`, so the waveform saturates rather than overshooting.
        assertEquals(32768, peakOf(pcm(-32768), 2))
    }

    @Test
    fun `an idle recorder reports nothing and stopping it produces no file`() {
        val recorder = JvmVoiceRecorder()

        assertFalse(recorder.isRecording)
        // Guarded by `if (line == null) return 0` — without it, `peak.getAndSet(0)` would report the
        // last recording's peak to the next composer that asked.
        assertEquals(0, recorder.maxAmplitude())
        // The composer calls stop() on gesture release, including releases that never started a
        // capture (no device, permission refused). null means "no note", not "error".
        assertNull(recorder.stop())
    }

    @Test
    fun `cancelling a recorder that never started is a no-op`() {
        val recorder = JvmVoiceRecorder()

        // The screen's DisposableEffect calls cancel() on every departure, recording or not.
        recorder.cancel()
        recorder.cancel()

        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
    }
}
