package com.transfer.flash.ui.shims

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives the desktop image decoder against real files on disk.
 *
 * The public seam is a `@Composable` factory and this repo has no Compose UI-test harness, so
 * [JvmImageDecoder] is reached directly — R3.1's "an `actual` that is only compiled is not verified"
 * is precisely about a file like this one. Everything under test is behaviour a compile cannot show:
 * that `setSourceSubsampling` really bounds the result (and is not silently a full-size decode), that
 * the two URI shapes the desktop picker and recorder emit both resolve, that the documented gaps
 * (video, `content://`) return null instead of throwing, and that the memo cache is keyed by size as
 * well as source.
 *
 * `JvmImageDecoder` is an `object` with one process-wide cache, so every test writes its own file.
 */
class FlashImageDecoderJvmTest {

    private val tempDir: File = Files.createTempDirectory("flash-image-decoder").toFile()

    @AfterTest
    fun deleteTempFiles() {
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
    }

    /**
     * The subsampling rule `:ui:chat` passes in — restated here rather than imported, because
     * `FlashMediaViewerMath` lives in the module that depends on this one and reaching for it would be
     * the dependency cycle the `computeInSampleSize` parameter exists to avoid. Halve while the next
     * halving still clears the budget: the classic power-of-two rule both platforms' decoders want.
     */
    private fun sampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    private fun writePng(name: String, width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        // A gradient rather than a flat fill, so a decode that returned the wrong region would not
        // happen to look right.
        for (x in 0 until width) {
            for (y in 0 until height) {
                image.setRGB(x, y, (x * 255 / width shl 16) or (y * 255 / height shl 8))
            }
        }
        val file = File(tempDir, name)
        assertTrue(ImageIO.write(image, "png", file), "ImageIO could not write $name")
        return file
    }

    private fun decode(source: String?, isVideo: Boolean = false, maxLongEdge: Int = 720, memoize: Boolean = true) =
        JvmImageDecoder.decode(
            source = source,
            isVideo = isVideo,
            maxLongEdge = maxLongEdge,
            memoize = memoize,
            lowColorDepth = false,
            computeInSampleSize = ::sampleSize,
        )

    @Test
    fun `a large still is subsampled down to the requested budget`() {
        val file = writePng("wide.png", width = 1600, height = 800)

        val bitmap = assertNotNull(decode(file.absolutePath, maxLongEdge = 400, memoize = false))

        // sampleSize(1600, 800, 400) is 4, and a subsampled read divides both edges by it. If
        // `setSourceSubsampling` were not being applied this would be 1600x800 — a 5 MB bitmap per
        // tile instead of 320 KB.
        assertEquals(400, bitmap.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun `a still already inside the budget decodes at full size`() {
        val file = writePng("small.png", width = 120, height = 90)

        val bitmap = assertNotNull(decode(file.absolutePath, memoize = false))

        // sampleSize returns 1 here, and `decodeStill` must then skip setSourceSubsampling entirely
        // rather than pass a factor of 1 that some readers reject.
        assertEquals(120, bitmap.width)
        assertEquals(90, bitmap.height)
    }

    @Test
    fun `a file uri decodes the same source as a bare path`() {
        val file = writePng("uri.png", width = 200, height = 100)

        // `file:/...` is what the desktop picker and voice recorder both hand across the seam
        // (`File.toURI()`), so this branch of `openStream` is on the real path, not a nicety.
        val viaUri = assertNotNull(decode(file.toURI().toString(), memoize = false))
        val viaPath = assertNotNull(decode(file.absolutePath, memoize = false))

        assertEquals(viaPath.width, viaUri.width)
        assertEquals(viaPath.height, viaUri.height)
    }

    @Test
    fun `a video has no desktop decoder and reports nothing`() {
        // Not a stub to force a compile (R2): the JDK ships no demuxer, and null is the same answer
        // Android gives for a file still in flight, which every call site already renders a
        // placeholder for. Recorded as a gap in the actual's KDoc and on the backlog.
        val file = writePng("frame.png", width = 100, height = 100)

        assertNull(decode(file.absolutePath, isVideo = true, memoize = false))
    }

    @Test
    fun `an android content uri resolves to nothing instead of being read as a path`() {
        // Without the explicit `content://` branch this string would fall through to `File(source)`,
        // which on Windows is a legal-looking relative path — a silent miss rather than a clean null.
        assertNull(decode("content://media/external/images/media/42", memoize = false))
    }

    @Test
    fun `a missing, empty or absent source decodes to nothing`() {
        val empty = File(tempDir, "empty.png").apply { createNewFile() }

        assertNull(decode(File(tempDir, "does-not-exist.png").absolutePath, memoize = false))
        // A zero-length file is what a partially-received transfer looks like on disk.
        assertNull(decode(empty.absolutePath, memoize = false))
        assertNull(decode(null, memoize = false))
        assertNull(decode("   ", memoize = false))
    }

    @Test
    fun `a source that is not an image decodes to nothing rather than throwing`() {
        // No ImageIO reader claims this, so `getImageReaders` is empty. The bubble shows its
        // placeholder; nothing propagates into the producer coroutine.
        val text = File(tempDir, "note.txt").apply { writeText("not an image") }

        assertNull(decode(text.absolutePath, memoize = false))
    }

    @Test
    fun `memoized decodes of the same tile hand back the same bitmap`() {
        val file = writePng("memo.png", width = 300, height = 300)

        val first = assertNotNull(decode(file.absolutePath))
        val second = assertNotNull(decode(file.absolutePath))

        // The grid re-decodes on every scroll pass; without the cache each pass would re-read the
        // file and allocate again.
        assertSame(first, second)
    }

    @Test
    fun `the memo key includes the size budget`() {
        val file = writePng("budget.png", width = 1000, height = 1000)

        val tile = assertNotNull(decode(file.absolutePath, maxLongEdge = 250))
        val full = assertNotNull(decode(file.absolutePath, maxLongEdge = 1000))

        // Keyed on source alone, the viewer's full-size request would be served the 250 px tile — a
        // full-screen image at thumbnail resolution.
        assertNotSame(tile, full)
        assertEquals(250, tile.width)
        assertEquals(1000, full.width)
    }

    @Test
    fun `an unmemoized decode does not populate the cache`() {
        val file = writePng("no-memo.png", width = 400, height = 400)

        val first = assertNotNull(decode(file.absolutePath, memoize = false))
        val second = assertNotNull(decode(file.absolutePath, memoize = false))

        // `memoize = false` is what the full-screen viewer passes so a single 4096 px bitmap cannot
        // evict every thumbnail in the conversation.
        assertNotSame(first, second)
    }
}
