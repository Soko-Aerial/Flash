package com.transfer.flash.ui.shims

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds the process-wide [FlashMediaDecoder] to a `Context`, which is the only thing the decode needed
 * a composition for.
 */
@Composable
public actual fun rememberFlashImageDecoder(): FlashImageDecoder {
    val context = LocalContext.current
    return remember(context) { AndroidImageDecoder(context) }
}

private class AndroidImageDecoder(private val context: Context) : FlashImageDecoder {
    override fun decode(
        source: String?,
        isVideo: Boolean,
        maxLongEdge: Int,
        memoize: Boolean,
        lowColorDepth: Boolean,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? = FlashMediaDecoder.decode(
        context = context,
        source = source,
        isVideo = isVideo,
        maxLongEdge = maxLongEdge,
        memoize = memoize,
        lowColorDepth = lowColorDepth,
        computeInSampleSize = computeInSampleSize,
    )
}

/**
 * The one bitmap-decoding path behind every chat media surface: the in-bubble tiles and the
 * full-screen viewer.
 *
 * Moved here from `:ui:chat` by Phase 19, unchanged except for its package, the `computeInSampleSize`
 * parameter that replaces a direct call into `:ui:chat`, and `TILE_LONG_EDGE_PX` moving to
 * [FlashImageDecoder]'s companion so `commonMain` can name it. PHASE-19 does not list this file at
 * all; it is Android-pinned (nine `android.*` imports) and one of the two things `LocalContext` was
 * still needed for, so it cannot stay behind.
 *
 * Why the call sites no longer reach for `BitmapFactory` directly:
 * - **Downsampling.** A 12 MP photo decodes to ~48 MB as `ARGB_8888`. A grid of those exhausts the
 *   heap, and a `runCatching` around the decode swallows the resulting `OutOfMemoryError` — so the
 *   observable failure was a silent gradient placeholder where a photo should be. Every decode here
 *   is bounded by a long-edge budget.
 * - **Video.** `BitmapFactory` returns null for an mp4, so video attachments had no thumbnail at
 *   all; frames come from [MediaMetadataRetriever] instead.
 * - **Orientation.** Phone cameras store a landscape frame plus an EXIF rotation tag. Ignoring it
 *   renders every portrait photo sideways.
 * - **Re-decoding.** A tile in a `LazyColumn` re-enters composition on every scroll pass, so an
 *   uncached decode re-runs constantly. Results are memoised in a small [LruCache].
 * - **Giving the memory back.** That cache is capped at a share of the heap but was never released
 *   early, so a low-RAM device kept holding thumbnails while backgrounded mid-transfer. It now trims
 *   on `onTrimMemory` — see [cacheTrimFor].
 *
 * Blocking work throughout — call from a background dispatcher.
 */
internal object FlashMediaDecoder {

    /**
     * Tile budget on a device the performance classifier put below HIGH (ERROR-033).
     *
     * 720 px is roughly twice what a ~300 dp tile can show on the 480x640 panels this tier targets,
     * and because sampling is power-of-two the smaller ceiling usually buys a whole extra halving: a
     * 4000 px camera photo lands at 500 px rather than 1000 px, a quarter of the pixels and a quarter
     * of the bytes. HIGH keeps [TILE_LONG_EDGE_PX] unchanged.
     */
    const val TILE_LONG_EDGE_MINIMAL_PX: Int = 480

    /**
     * Frame offset for a video thumbnail. Time 0 is frequently a black lead-in frame, ~200 ms
     * almost never is; `OPTION_CLOSEST_SYNC` then snaps to the nearest key frame, so this is cheap.
     */
    private const val VIDEO_FRAME_TIME_US: Long = 200_000L

    private val cache: LruCache<String, ImageBitmap> =
        object : LruCache<String, ImageBitmap>(cacheBytes()) {
            // Charged at the real cost, not a flat 4 bytes a pixel: an RGB_565 thumbnail occupies
            // half as much, so a minimal-chrome device fits twice as many in the same budget and
            // re-decodes half as often while scrolling.
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                value.width * value.height * value.config.bytesPerPixel()
        }

    private fun ImageBitmapConfig.bytesPerPixel(): Int = when (this) {
        ImageBitmapConfig.Rgb565, ImageBitmapConfig.Alpha8 -> 2
        ImageBitmapConfig.F16 -> 8
        else -> 4
    }

    /**
     * Decodes [source] — a `content://` / `file://` URI or a filesystem path — into an upright,
     * memory-bounded bitmap, or null when there is nothing decodable there: an inbound file still
     * in flight, a revoked content grant, a deleted source, or something that is not media.
     *
     * @param isVideo take a representative frame instead of decoding the bytes as a still.
     * @param maxLongEdge cap for the longer edge of the result, in pixels.
     * @param memoize false for one-off large decodes (the full-screen viewer) that would evict the
     *   whole thumbnail cache to store a single bitmap nobody will ask for twice.
     * @param lowColorDepth decode stills as `RGB_565` — two bytes a pixel instead of four (ERROR-033).
     *   Chat photos are opaque, so the only visible cost is faint banding across a smooth gradient,
     *   and it is off at HIGH. Not applied to the video path: `BitmapParams.setPreferredConfig` is
     *   API 30+, so on the API-27 handsets this targets the frame arrives as `ARGB_8888` regardless,
     *   and converting afterwards would hold both copies at once — the opposite of the point.
     */
    fun decode(
        context: Context,
        source: String?,
        isVideo: Boolean,
        maxLongEdge: Int = FlashImageDecoder.TILE_LONG_EDGE_PX,
        memoize: Boolean = true,
        lowColorDepth: Boolean = false,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? {
        if (source.isNullOrBlank()) return null
        ensureTrimCallbacks(context)
        val key = "$source|$isVideo|$maxLongEdge|$lowColorDepth"
        if (memoize) cache.get(key)?.let { return it }
        // Catches Throwable on purpose: a decode can still fail on an OEM codec or a truncated file,
        // and every caller's contract is "no thumbnail" rather than a crashed composition.
        val decoded = runCatching {
            if (isVideo) {
                decodeVideoFrame(context, source, maxLongEdge)
            } else {
                decodeImage(context, source, maxLongEdge, lowColorDepth, computeInSampleSize)
            }
        }.onFailure { e ->
            android.util.Log.w("FlashMediaDecoder", "Decode failed for source: $source, isVideo: $isVideo", e)
        }.getOrNull() ?: return null
        if (memoize) cache.put(key, decoded)
        return decoded
    }

    private fun resolveLocalFile(source: String): File? = runCatching {
        val file = when {
            source.startsWith("file://") -> {
                val path = Uri.parse(source).path ?: source.removePrefix("file://")
                File(path)
            }
            source.startsWith("file:") -> {
                val path = Uri.parse(source).path ?: source.removePrefix("file:").trimStart('/')
                File(path)
            }
            !source.startsWith("content://") -> File(source)
            else -> null
        }
        file?.takeIf { it.exists() && it.length() > 0L }
    }.getOrNull()

    /**
     * Two-pass decode: bounds first, then the real thing at a power-of-two sample size. Uses the
     * caller-supplied sizing function — `:ui:chat`'s tested `FlashMediaViewerMath.computeInSampleSize`
     * — so both surfaces size identically.
     */
    private fun decodeImage(
        context: Context,
        source: String,
        maxLongEdge: Int,
        lowColorDepth: Boolean,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? {
        val localFile = resolveLocalFile(source)
        if (localFile != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge).coerceAtLeast(1)
            val options = BitmapFactory.Options().apply {
                if (lowColorDepth) inPreferredConfig = Bitmap.Config.RGB_565
            }
            // BOLT: step sample size and fallback to RGB_565 on decode failure/OOM to prevent infinite while-loop thread spin and reduce heap allocation
            var bitmap: Bitmap? = null
            while (bitmap == null && sample <= 32) {
                options.inSampleSize = sample
                try {
                    bitmap = BitmapFactory.decodeFile(localFile.absolutePath, options)
                } catch (oom: OutOfMemoryError) {
                    bitmap = null
                }
                if (bitmap == null) {
                    sample *= 2
                    options.inPreferredConfig = Bitmap.Config.RGB_565
                }
            }
            if (bitmap == null) return null
            return applyExifRotation(context, source, bitmap, localFile).asImageBitmap()
        }

        // For content:// URIs, prefer ParcelFileDescriptor for direct seekable native decoding
        if (source.startsWith("content://")) {
            val uri = Uri.parse(source)
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                val fd = pfd?.fileDescriptor
                if (fd != null) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(fd, null, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        var sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge).coerceAtLeast(1)
                        val options = BitmapFactory.Options().apply {
                            if (lowColorDepth) inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        var bitmap: Bitmap? = null
                        while (bitmap == null && sample <= 32) {
                            options.inSampleSize = sample
                            try {
                                bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options)
                            } catch (oom: OutOfMemoryError) {
                                bitmap = null
                            }
                            if (bitmap == null) {
                                sample *= 2
                                options.inPreferredConfig = Bitmap.Config.RGB_565
                            }
                        }
                        if (bitmap != null) {
                            return applyExifRotation(context, source, bitmap, null, fd).asImageBitmap()
                        }
                    }
                }
            } finally {
                runCatching { pfd?.close() }
            }
        }

        // Fallback: Buffered stream decoding with progressive sample backoff
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            if (lowColorDepth) inPreferredConfig = Bitmap.Config.RGB_565
        }
        var bitmap: Bitmap? = null
        while (bitmap == null && sample <= 32) {
            options.inSampleSize = sample
            bitmap = try {
                openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, options) }
            } catch (oom: OutOfMemoryError) {
                null
            }
            if (bitmap == null) {
                sample *= 2
                options.inPreferredConfig = Bitmap.Config.RGB_565
            }
        }
        if (bitmap == null) return null
        return applyExifRotation(context, source, bitmap).asImageBitmap()
    }

    /**
     * A representative frame for a video attachment (MP4, MKV, WebM, MOV, 3GP, etc.).
     *
     * Content URIs from SAF must be set via [ParcelFileDescriptor] since passing content:// URIs directly
     * into MediaMetadataRetriever fails inside the native mediaserver process when Binder permissions
     * are not propagated.
     *
     * Rotation is deliberately **not** re-applied from `METADATA_KEY_VIDEO_ROTATION`: the platform
     * retriever already returns an upright frame (the same reason `ThumbnailUtils` needs no rotation
     * step), so doing it here would turn every portrait clip on its side.
     */
    private fun decodeVideoFrame(context: Context, source: String, maxLongEdge: Int): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        var fis: FileInputStream? = null
        return try {
            when {
                source.startsWith("content://") -> {
                    val uri = Uri.parse(source)
                    pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                }
                source.startsWith("file://") -> {
                    val path = Uri.parse(source).path ?: source.removePrefix("file://")
                    val file = File(path)
                    if (!file.exists() || file.length() == 0L) return null
                    fis = FileInputStream(file)
                    retriever.setDataSource(fis.fd)
                }
                else -> {
                    val file = File(source)
                    if (!file.exists() || file.length() == 0L) return null
                    fis = FileInputStream(file)
                    retriever.setDataSource(fis.fd)
                }
            }
            val frame = scaledFrame(retriever, maxLongEdge) ?: return null
            downscale(frame, maxLongEdge).asImageBitmap()
        } catch (e: Throwable) {
            android.util.Log.w("FlashMediaDecoder", "Failed to decode video frame for $source: ${e.message}")
            null
        } finally {
            runCatching { fis?.close() }
            runCatching { pfd?.close() }
            runCatching { retriever.release() }
        }
    }

    /**
     * `getScaledFrameAtTime` decodes straight into the target size (API 27+); below that, and
     * whenever the clip does not report its dimensions, take the full frame and shrink it after.
     * Falls back to time 0 with OPTION_CLOSEST for formats like MKV/WebM or short clips where
     * 200ms with OPTION_CLOSEST_SYNC returns null.
     */
    private fun scaledFrame(retriever: MediaMetadataRetriever, maxLongEdge: Int): Bitmap? {
        val unscaled = {
            retriever.getFrameAtTime(VIDEO_FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.frameAtTime
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return unscaled()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (width <= 0 || height <= 0) return unscaled()
        val scale = minOf(1f, maxLongEdge.toFloat() / maxOf(width, height).toFloat())
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return retriever.getScaledFrameAtTime(
            VIDEO_FRAME_TIME_US,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            targetWidth,
            targetHeight,
        ) ?: retriever.getScaledFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST,
            targetWidth,
            targetHeight,
        ) ?: unscaled()
    }

    /** No-op whenever the frame already fits, which is the norm once scaling happened in-decoder. */
    private fun downscale(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxLongEdge || longEdge <= 0) return bitmap
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val scaled = runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull() ?: return bitmap
        // The source frame is ours alone (fresh out of the retriever), so reclaiming it is safe.
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * EXIF orientation, applied by re-drawing through a [Matrix]. Failure to *read* the tag leaves
     * the bitmap alone rather than guessing — a missing tag and an unreadable one both mean
     * "no rotation information", and rotating on a guess is worse than not rotating.
     */
    private fun applyExifRotation(
        context: Context,
        source: String,
        bitmap: Bitmap,
        file: File? = null,
        fd: java.io.FileDescriptor? = null,
    ): Bitmap {
        val orientation = runCatching {
            when {
                file != null -> ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
                fd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> ExifInterface(fd).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
                else -> openStream(context, source)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bitmap
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (oom: OutOfMemoryError) {
            bitmap
        }
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Resolves an input stream from a content:// or file:// URI or local filesystem path.
     * ContentResolver can throw on file:// URIs on modern Android, so file:// is handled directly
     * via File.
     */
    private fun openStream(context: Context, source: String): InputStream? = runCatching {
        when {
            source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))?.let { java.io.BufferedInputStream(it) }
            source.startsWith("file://") -> {
                val path = Uri.parse(source).path ?: source.removePrefix("file://")
                File(path).takeIf { it.exists() && it.length() > 0L }?.inputStream()?.let { java.io.BufferedInputStream(it) }
            }
            source.startsWith("file:") -> {
                val path = Uri.parse(source).path ?: source.removePrefix("file:").trimStart('/')
                File(path).takeIf { it.exists() && it.length() > 0L }?.inputStream()?.let { java.io.BufferedInputStream(it) }
            }
            else -> File(source).takeIf { it.exists() && it.length() > 0L }?.inputStream()?.let { java.io.BufferedInputStream(it) }
        }
    }.onFailure { e ->
        android.util.Log.w("FlashMediaDecoder", "Failed to openStream for $source", e)
    }.getOrNull()

    /**
     * The conventional one-eighth-of-heap bitmap cache share, clamped so a 512 MB-heap tablet does
     * not reserve tens of megabytes and a small device still gets a usable window.
     */
    private fun cacheBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 8L)
            .coerceIn(4L * 1024L * 1024L, 24L * 1024L * 1024L)
            .toInt()

    private val trimRegistered = AtomicBoolean(false)

    /** What a given `onTrimMemory` level should do to the thumbnail cache. */
    internal enum class CacheTrim { None, Halve, EvictAll }

    /**
     * The trim policy, as a pure function so it can be asserted without a running process.
     *
     * Ordered by level rather than matched per constant: which levels the platform actually delivers
     * has narrowed across releases, so thresholds stay correct whichever of them arrive. Against the
     * API 36 `android.jar` only `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND` are still
     * current — every `RUNNING_*` level plus `MODERATE`/`COMPLETE` is deprecated — so on a recent
     * platform every delivered level lands on [CacheTrim.EvictAll] and [CacheTrim.Halve] is the
     * legacy branch. That is not a reason to drop it: the values are frozen API constants, and the
     * API-27 handsets this tier exists for are exactly the devices that still deliver them.
     */
    @Suppress("DEPRECATION") // The RUNNING_* levels still arrive on the old devices this targets.
    internal fun cacheTrimFor(level: Int): CacheTrim = when {
        // UI gone or process backgrounded: nothing on screen needs a thumbnail.
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> CacheTrim.EvictAll
        // Still foreground but the system is asking: halve, do not blank, so scrolling the open
        // conversation does not re-decode every visible tile.
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> CacheTrim.Halve
        else -> CacheTrim.None
    }

    /**
     * Hands the cache back to the system under memory pressure.
     *
     * Without this the cache only ever shrinks by its own LRU rule, so on a 2 GB handset it holds its
     * full share — up to `maxMemory / 8` — for the life of the process, including while the app is in
     * the background with a transfer running as a foreground service and not one thumbnail is on
     * screen. Every entry is reconstructible from the file it came from, so a cache is exactly the
     * thing that should be dropped first when the alternative is the platform killing the process.
     *
     * Registered lazily on the first decode rather than from `FlashApplication`, because the cache is
     * `internal` to this module and a device that never opens a media bubble should not pay for a
     * callback it will never use.
     */
    private fun ensureTrimCallbacks(context: Context) {
        if (!trimRegistered.compareAndSet(false, true)) return
        runCatching {
            context.applicationContext.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        when (cacheTrimFor(level)) {
                            CacheTrim.EvictAll -> cache.evictAll()
                            CacheTrim.Halve -> cache.trimToSize(cache.size() / 2)
                            CacheTrim.None -> Unit
                        }
                    }

                    override fun onLowMemory() {
                        cache.evictAll()
                    }

                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                },
            )
        }
    }
}
