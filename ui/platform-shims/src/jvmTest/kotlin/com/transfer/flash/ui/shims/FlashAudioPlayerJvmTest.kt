package com.transfer.flash.ui.shims

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The desktop voice-note player's source resolution and its degrade-to-silence path.
 *
 * Playing a real clip is not testable here: `AudioSystem.getClip()` needs an output mixer, which a CI
 * box does not have. What this suite pins is everything around that call: source resolution, the
 * JDK tier's feed format, and the JCodec AAC fallback's fail-closed shape (garbage in, null out, never
 * a throw). Positive AAC decode of a real `.m4a` is proven live (owner hardware run); headless CI has
 * no AAC fixture and no mixer, so there is deliberately no test that plays one.
 */
class FlashAudioPlayerJvmTest {

    private val tempDir: File = Files.createTempDirectory("flash-audio-player").toFile()

    @AfterTest
    fun deleteTempFiles() {
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
    }

    @Test
    fun `an android content uri has no desktop file`() {
        // Left to fall through, this would be handed to `File(...)` and read as a relative path.
        assertNull(resolveFile("content://media/external/audio/media/7"))
    }

    @Test
    fun `a file uri and a bare path resolve to the same file`() {
        val note = File(tempDir, "note.wav").apply { writeText("pretend audio") }

        // Both shapes are real inputs: the desktop recorder returns `File.toURI().toString()`, while a
        // path can arrive from the transfer layer's own storage.
        val viaUri = assertNotNull(resolveFile(note.toURI().toString()))
        val viaPath = assertNotNull(resolveFile(note.absolutePath))

        assertEquals(note.canonicalPath, viaUri.canonicalPath)
        assertEquals(note.canonicalPath, viaPath.canonicalPath)
    }

    @Test
    fun `a missing, empty or non-file source resolves to nothing`() {
        val empty = File(tempDir, "empty.wav").apply { createNewFile() }

        assertNull(resolveFile(File(tempDir, "absent.wav").absolutePath))
        // A zero-length file is what a voice note whose transfer has not finished looks like.
        assertNull(resolveFile(empty.absolutePath))
        assertNull(resolveFile(tempDir.absolutePath))
    }

    @Test
    fun `a malformed file uri resolves to nothing rather than throwing`() {
        // `File(URI)` rejects an opaque URI with IllegalArgumentException; the runCatching around it is
        // what keeps a malformed attachment from taking down the composition.
        assertNull(resolveFile("file:"))
    }

    @Test
    fun `a source the jdk cannot decode leaves every method silent`() {
        // Named .m4a because that is the real case: an AAC note recorded on Android. The bytes are not
        // AAC either, and it does not matter — `getAudioInputStream` refuses both identically.
        val note = File(tempDir, "voice.m4a").apply { writeText("not decodable audio") }
        val player = JvmAudioPlayer(note.absolutePath)

        // Every one of these is called from the voice bubble's controls and progress animation. The
        // bubble renders the same "nothing plays" state it already shows for an unfinished transfer.
        player.play()
        assertFalse(player.isPlaying())
        assertEquals(0L, player.positionMs())
        player.pause()
        player.seekTo(1_500L)
        // No-op on desktop: `Clip` has no rate control. The shared UI's speed selector must not have
        // to know that.
        player.setSpeed(2f)
        player.play()
        assertFalse(player.isPlaying())
        player.release()
        // Released twice, as a recomposition plus a DisposableEffect teardown can do.
        player.release()
    }

    @Test
    fun `a missing source leaves every method silent`() {
        val player = JvmAudioPlayer(File(tempDir, "never-arrived.wav").absolutePath)

        player.play()
        player.pause()
        player.seekTo(0L)
        assertFalse(player.isPlaying())
        assertEquals(0L, player.positionMs())
        player.release()
    }

    @Test
    fun `aac fallback refuses garbage without throwing`() {
        // Straight into the JCodec tier, past source resolution: container parsing, demux and
        // decode must all fail closed. A throw here would surface from the player's prepare path
        // on a truncated/corrupt download.
        val garbage = File(tempDir, "corrupt.m4a").apply { writeText("not an mp4 container") }
        assertNull(decodeAacM4aToPcm(garbage))
        // A valid non-MP4 container must also refuse (codec guard), not mis-decode.
        assertNull(decodeAacM4aToPcm(sineWav("tone.wav")))
    }

    @Test
    fun `jdk tier opens synthesized wav with the feed format the clip path uses`() {
        // Headless-safe: `getAudioInputStream` only parses (no mixer needed), which pins the
        // sample-rate/channel contract the `Clip.open` call relies on. Real mixing is live-only.
        val wav = sineWav("tone.wav")
        javax.sound.sampled.AudioSystem.getAudioInputStream(wav).use { stream ->
            assertEquals(8000f, stream.format.sampleRate)
            assertEquals(1, stream.format.channels)
            assertEquals(16, stream.format.sampleSizeInBits)
        }
    }

    /** 0.1s 440Hz mono 16-bit 8kHz WAV: the smallest real container the JDK tier accepts. */
    private fun sineWav(name: String): File {
        val rate = 8000
        val samples = (0 until 800).map { i ->
            (Math.sin(2.0 * Math.PI * 440.0 * i / rate) * 10000).toInt().toShort()
        }
        val data = java.nio.ByteBuffer.allocate(samples.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val pcm = data.array()
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2) // block align
        header.putShort(16) // bits
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return File(tempDir, name).apply {
            outputStream().use { out ->
                out.write(header.array())
                out.write(pcm)
            }
        }
    }
}
