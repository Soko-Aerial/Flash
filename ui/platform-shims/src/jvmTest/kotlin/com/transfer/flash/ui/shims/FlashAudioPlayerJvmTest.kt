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
 * box does not have. What this suite pins is the behaviour that *is* deterministic and that the chat
 * bubble depends on — a source the JDK cannot decode must leave every method silent and
 * non-throwing, because that is the documented state of a Flash voice note on desktop today (Android
 * records AAC in an `.m4a`; the JDK's sampled SPI reads WAV, AU and AIFF only).
 *
 * That gap is a platform capability limit, not a stub (R2): the implementation plays every format the
 * JDK supports. An AAC decoder is a dependency decision and is on the backlog.
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
}
