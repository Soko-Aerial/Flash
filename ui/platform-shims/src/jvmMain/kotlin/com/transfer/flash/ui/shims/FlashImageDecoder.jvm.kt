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

    /**
     * O02-08: Clears the in-memory thumbnail cache on memory trim or window idle/minimization.
     */
    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }

    /** Returns current number of memoized images in the cache. */
    fun cachedCount(): Int = synchronized(cache) {
        cache.size
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
        val key = "$source|$isVideo|$maxLongEdge|$lowColorDepth"
        if (memoize) {
            // `LinkedHashMap` is not thread-safe and this runs on `Dispatchers.IO`, so both the
            // access-order reshuffle on read and the insert have to be guarded.
            synchronized(cache) { cache[key] }?.let { return it }
        }
        val decoded = runCatching {
            if (isVideo) {
                decodeVideo(source, maxLongEdge, computeInSampleSize)
            } else {
                decodeStill(source, maxLongEdge, computeInSampleSize)
            }
        }.getOrNull() ?: return null
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
        var orientation = 1
        val buffered = stream.use { raw ->
            val input = ImageIO.createImageInputStream(raw) ?: return null
            input.use {
                // O05-04: Parse EXIF orientation directly from the ImageInputStream before decode,
                // seeking back to 0. Eliminates the secondary full-file stream open and header scan.
                orientation = parseExifFromStream(it)
                it.seek(0)
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
                    reader.read(0, param)
                } finally {
                    reader.dispose()
                }
            }
        } ?: return null

        val rotated = if (orientation > 1) rotateImage(buffered, orientation) else buffered
        return rotated.toComposeImageBitmap()
    }

    /**
     * Extracts the first video frame as a thumbnail using JCodec.
     * O05-10: Subsamples during YUV extraction before color conversion to minimize heap allocations,
     * and performs bulk raster data writes to BufferedImage.
     */
    private fun decodeVideo(
        source: String,
        maxLongEdge: Int,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? {
        val file = resolveFile(source) ?: return null
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            org.jcodec.common.io.NIOUtils.readableChannel(file).use { channel ->
                val grab = org.jcodec.api.FrameGrab.createFrameGrab(channel)
                val pic = grab.seekToFramePrecise(0).nativeFrame ?: return@runCatching null
                val width = pic.width
                val height = pic.height
                if (width <= 0 || height <= 0) return@runCatching null

                val sample = computeInSampleSize(width, height, maxLongEdge).coerceAtLeast(1)
                val tw = ((width / sample).coerceAtLeast(2) and 1.inv())
                val th = ((height / sample).coerceAtLeast(2) and 1.inv())

                val (scaledPic, targetW, targetH) = if (sample > 1 && width >= 2 && height >= 2) {
                    val sub = org.jcodec.common.model.Picture.create(tw, th, org.jcodec.common.model.ColorSpace.YUV420)
                    val srcY = pic.getPlaneData(0)
                    val srcU = pic.getPlaneData(1)
                    val srcV = pic.getPlaneData(2)
                    val dstY = sub.getPlaneData(0)
                    val dstU = sub.getPlaneData(1)
                    val dstV = sub.getPlaneData(2)
                    if (srcY != null && srcU != null && srcV != null && dstY != null && dstU != null && dstV != null) {
                        for (y in 0 until th) {
                            val origRow = (y * sample) * width
                            val dstRow = y * tw
                            for (x in 0 until tw) {
                                dstY[dstRow + x] = srcY[origRow + (x * sample)]
                            }
                        }
                        val halfSrcW = width / 2
                        val halfDstW = tw / 2
                        val halfDstH = th / 2
                        for (y in 0 until halfDstH) {
                            val origRow = (y * sample) * halfSrcW
                            val dstRow = y * halfDstW
                            for (x in 0 until halfDstW) {
                                val origOffset = origRow + (x * sample)
                                val dstOffset = dstRow + x
                                dstU[dstOffset] = srcU[origOffset]
                                dstV[dstOffset] = srcV[origOffset]
                            }
                        }
                        Triple(sub, tw, th)
                    } else {
                        Triple(pic, width, height)
                    }
                } else {
                    Triple(pic, width, height)
                }

                val rgbPic = org.jcodec.common.model.Picture.create(targetW, targetH, org.jcodec.common.model.ColorSpace.RGB)
                org.jcodec.scale.Yuv420pToRgb().transform(scaledPic, rgbPic)
                val data = rgbPic.data[0] ?: return@runCatching null

                val img = java.awt.image.BufferedImage(targetW, targetH, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val pixels = (img.raster.dataBuffer as? java.awt.image.DataBufferInt)?.data
                val totalPixels = targetW * targetH
                if (pixels != null && pixels.size >= totalPixels) {
                    for (i in 0 until totalPixels) {
                        val srcIdx = i * 3
                        val r = (data[srcIdx].toInt() + 128).coerceIn(0, 255)
                        val g = (data[srcIdx + 1].toInt() + 128).coerceIn(0, 255)
                        val b = (data[srcIdx + 2].toInt() + 128).coerceIn(0, 255)
                        pixels[i] = (r shl 16) or (g shl 8) or b
                    }
                } else {
                    val rowPixels = IntArray(targetW)
                    for (y in 0 until targetH) {
                        val rowOffset = y * targetW * 3
                        for (x in 0 until targetW) {
                            val srcIdx = rowOffset + x * 3
                            val r = (data[srcIdx].toInt() + 128).coerceIn(0, 255)
                            val g = (data[srcIdx + 1].toInt() + 128).coerceIn(0, 255)
                            val b = (data[srcIdx + 2].toInt() + 128).coerceIn(0, 255)
                            rowPixels[x] = (r shl 16) or (g shl 8) or b
                        }
                        img.setRGB(0, y, targetW, 1, rowPixels, 0, targetW)
                    }
                }
                img.toComposeImageBitmap()
            }
        }.getOrNull()
    }

    /**
     * Extracts EXIF orientation (1..8) from an [javax.imageio.stream.ImageInputStream] without loading the entire image.
     */
    private fun parseExifFromStream(input: javax.imageio.stream.ImageInputStream): Int = runCatching {
        if (input.read() != 0xFF || input.read() != 0xD8) return@runCatching 1
        while (true) {
            val markerPrefix = input.read()
            if (markerPrefix == -1) break
            if (markerPrefix != 0xFF) continue
            var marker = input.read()
            while (marker == 0xFF) marker = input.read()
            if (marker == -1 || marker == 0xDA || marker == 0xD9) break // SOS or EOI
            val lenHigh = input.read()
            val lenLow = input.read()
            if (lenHigh == -1 || lenLow == -1) break
            val segmentLength = ((lenHigh and 0xFF) shl 8) or (lenLow and 0xFF)
            if (segmentLength < 2) break
            val dataLength = segmentLength - 2

            if (marker == 0xE1 && dataLength >= 14) { // APP1 Exif marker
                val data = ByteArray(dataLength)
                var readTotal = 0
                while (readTotal < dataLength) {
                    val count = input.read(data, readTotal, dataLength - readTotal)
                    if (count <= 0) break
                    readTotal += count
                }
                if (readTotal == dataLength &&
                    data[0] == 'E'.code.toByte() && data[1] == 'x'.code.toByte() &&
                    data[2] == 'i'.code.toByte() && data[3] == 'f'.code.toByte() &&
                    data[4] == 0.toByte() && data[5] == 0.toByte()
                ) {
                    return@runCatching parseTiffOrientation(data, 6)
                }
            } else {
                input.seek(input.streamPosition + dataLength)
            }
        }
        1
    }.getOrDefault(1)

    /**
     * Extracts EXIF orientation (1..8) from a JPEG file without loading the entire image.
     * Returns 1 (normal orientation) if absent, non-JPEG, or unreadable.
     */
    internal fun readExifOrientation(source: String): Int {
        val stream = openStream(source) ?: return 1
        return stream.use { raw ->
            val input = ImageIO.createImageInputStream(raw) ?: return@use 1
            input.use { parseExifFromStream(it) }
        }
    }

    private fun parseTiffOrientation(data: ByteArray, offset: Int): Int {
        if (offset + 8 > data.size) return 1
        val isLittleEndian = data[offset] == 'I'.code.toByte() && data[offset + 1] == 'I'.code.toByte()
        val isBigEndian = data[offset] == 'M'.code.toByte() && data[offset + 1] == 'M'.code.toByte()
        if (!isLittleEndian && !isBigEndian) return 1

        fun readShort(pos: Int): Int {
            if (pos + 1 >= data.size) return 0
            val b0 = data[pos].toInt() and 0xFF
            val b1 = data[pos + 1].toInt() and 0xFF
            return if (isLittleEndian) (b1 shl 8) or b0 else (b0 shl 8) or b1
        }

        fun readInt(pos: Int): Int {
            if (pos + 3 >= data.size) return 0
            val b0 = data[pos].toInt() and 0xFF
            val b1 = data[pos + 1].toInt() and 0xFF
            val b2 = data[pos + 2].toInt() and 0xFF
            val b3 = data[pos + 3].toInt() and 0xFF
            return if (isLittleEndian) {
                (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }

        val tag42 = readShort(offset + 2)
        if (tag42 != 42) return 1
        val firstIfdOffset = readInt(offset + 4)
        var ifdPos = offset + firstIfdOffset
        if (ifdPos + 2 > data.size) return 1
        val numEntries = readShort(ifdPos)
        ifdPos += 2
        for (i in 0 until numEntries) {
            if (ifdPos + 12 > data.size) break
            val tag = readShort(ifdPos)
            if (tag == 0x0112) { // Orientation tag
                return readShort(ifdPos + 8)
            }
            ifdPos += 12
        }
        return 1
    }

    private fun rotateImage(img: java.awt.image.BufferedImage, orientation: Int): java.awt.image.BufferedImage {
        val angle = when (orientation) {
            6 -> 90.0
            3 -> 180.0
            8 -> 270.0
            else -> return img
        }
        val radians = Math.toRadians(angle)
        val sin = Math.abs(Math.sin(radians))
        val cos = Math.abs(Math.cos(radians))
        val w = img.width
        val h = img.height
        val newW = Math.floor(w * cos + h * sin).toInt()
        val newH = Math.floor(h * cos + w * sin).toInt()

        val rotated = java.awt.image.BufferedImage(
            newW,
            newH,
            if (img.type != 0) img.type else java.awt.image.BufferedImage.TYPE_INT_ARGB
        )
        val g2d = rotated.createGraphics()
        try {
            g2d.translate((newW - w) / 2.0, (newH - h) / 2.0)
            g2d.rotate(radians, w / 2.0, h / 2.0)
            g2d.drawRenderedImage(img, null)
        } finally {
            g2d.dispose()
        }
        return rotated
    }

    private fun resolveFile(source: String): File? = when {
        source.startsWith("content://") -> null
        source.startsWith("file:", ignoreCase = true) -> runCatching {
            try {
                File(URI(source))
            } catch (_: Exception) {
                try {
                    File(URI(source.replace(" ", "%20")))
                } catch (_: Exception) {
                    var clean = source.replaceFirst(Regex("^file:", RegexOption.IGNORE_CASE), "")
                    if (clean.startsWith("///")) {
                        clean = clean.substring(2)
                    } else if (clean.startsWith("//")) {
                        clean = clean.substring(1)
                    }
                    if (clean.matches(Regex("^/[A-Za-z]:.*"))) {
                        clean = clean.substring(1)
                    }
                    val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
                    File(decoded)
                }
            }
        }.getOrNull()
        else -> File(source)
    }

    /**
     * `content://` is an Android scheme with no desktop meaning, so it resolves to nothing rather
     * than being mistaken for a relative path.
     */
    private fun openStream(source: String): InputStream? =
        resolveFile(source)?.takeIf { it.isFile && it.length() > 0L }?.inputStream()
}
