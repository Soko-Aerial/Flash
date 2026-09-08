package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.io.InputStream
import java.net.URI
import javax.imageio.ImageIO

/**
 * `javax.imageio`, subsampled through the same [FlashImageDecoder.decode] `computeInSampleSize`
 * function the Android side uses — so a still decodes to the same bounded size on both platforms.
 *
 * Two deliberate gaps, both recorded in the phase log rather than papered over:
 * - **No video frames.** Extracting one needs a demuxer the JDK does not ship. `isVideo` returns null,
 *   which every call site already handles: it is the same answer Android gives for a file still in
 *   flight, and the UI shows its placeholder.
 * - **No EXIF rotation.** `ImageIO` exposes the tag only as a raw metadata tree per format, and
 *   reading it correctly for JPEG, HEIF and WebP is a library's worth of work. Desktop screenshots and
 *   downloads are upright; camera-orientation photos arriving from a phone are not, and that is the
 *   visible consequence.
 */
@Composable
public actual fun rememberFlashImageDecoder(): FlashImageDecoder = remember { JvmImageDecoder }

/**
 * `internal`, not `private`, for one reason: `jvmTest` drives [decode] directly. The public factory is
 * `@Composable`, and the repo has no Compose UI-test harness, so a plain JVM test cannot reach the
 * implementation through it — and R3.1 counts an `actual` that is only compiled as unverified.
 */
internal object JvmImageDecoder : FlashImageDecoder {

    /**
     * Bounded by *count*, not bytes — the Android cache sizes itself against `Runtime.maxMemory()/8`
     * because a phone heap is 100–500 MB, while a desktop JVM's default is a quarter of physical RAM.
     * 32 sampled tiles is the same order of memory (~2 MB each at a 720 px long edge) without
     * pretending a desktop heap budget resembles a phone's.
     */
    private const val MAX_CACHED = 32

    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > MAX_CACHED
    }

    override fun decode(
        source: String?,
        isVideo: Boolean,
        maxLongEdge: Int,
        memoize: Boolean,
        lowColorDepth: Boolean,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? {
        if (source.isNullOrBlank()) return null
        if (isVideo) return null
        val key = "$source|$maxLongEdge|$lowColorDepth"
        if (memoize) {
            // `LinkedHashMap` is not thread-safe and this runs on `Dispatchers.IO`, so both the
            // access-order reshuffle on read and the insert have to be guarded.
            synchronized(cache) { cache[key] }?.let { return it }
        }
        val decoded = runCatching { decodeStill(source, maxLongEdge, computeInSampleSize) }
            .getOrNull() ?: return null
        if (memoize) synchronized(cache) { cache[key] = decoded }
        return decoded
    }

    /**
     * Reads the header for dimensions, then decodes with `setSourceSubsampling` — the `ImageIO`
     * equivalent of `BitmapFactory.Options.inSampleSize`, and like it, a true subsampled read rather
     * than a full decode followed by a shrink.
     */
    private fun decodeStill(
        source: String,
        maxLongEdge: Int,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? {
        val stream = openStream(source) ?: return null
        return stream.use { raw ->
            val input = ImageIO.createImageInputStream(raw) ?: return null
            input.use {
                val reader = ImageIO.getImageReaders(it).let { readers ->
                    if (readers.hasNext()) readers.next() else null
                } ?: return null
                try {
                    reader.setInput(it, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) return null
                    val sample = computeInSampleSize(width, height, maxLongEdge).coerceAtLeast(1)
                    val param = reader.defaultReadParam.apply {
                        if (sample > 1) setSourceSubsampling(sample, sample, 0, 0)
                    }
                    reader.read(0, param).toComposeImageBitmap()
                } finally {
                    reader.dispose()
                }
            }
        }
    }

    /**
     * `content://` is an Android scheme with no desktop meaning, so it resolves to nothing rather
     * than being mistaken for a relative path.
     */
    private fun openStream(source: String): InputStream? = when {
        source.startsWith("content://") -> null
        source.startsWith("file:") ->
            runCatching { File(URI(source)) }.getOrNull()
                ?.takeIf { it.isFile && it.length() > 0L }?.inputStream()
        else -> File(source).takeIf { it.isFile && it.length() > 0L }?.inputStream()
    }
}
