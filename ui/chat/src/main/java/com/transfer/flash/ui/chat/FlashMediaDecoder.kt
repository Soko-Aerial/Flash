package com.transfer.flash.ui.chat

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
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one bitmap-decoding path behind every chat media surface: the in-bubble tiles
 * ([FlashImageTile]) and the full-screen viewer.
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

    /** Long-edge budget for an in-bubble tile: sharp on a 300 dp tile, ~2 MB to hold. */
    const val TILE_LONG_EDGE_PX: Int = 720

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
        maxLongEdge: Int = TILE_LONG_EDGE_PX,
        memoize: Boolean = true,
        lowColorDepth: Boolean = false,
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
                decodeImage(context, source, maxLongEdge, lowColorDepth)
            }
        }.getOrNull() ?: return null
        if (memoize) cache.put(key, decoded)
        return decoded
    }

    /**
     * Two-pass decode: bounds first, then the real thing at a power-of-two sample size. Reuses the
     * viewer's tested [FlashMediaViewerMath.computeInSampleSize] so both surfaces size identically.
     */
    private fun decodeImage(
        context: Context,
        source: String,
        maxLongEdge: Int,
        lowColorDepth: Boolean,
    ): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FlashMediaViewerMath.computeInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxLongEdge = maxLongEdge,
            )
            if (lowColorDepth) inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = openStream(context, source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return applyExifRotation(context, source, bitmap).asImageBitmap()
    }

    /**
     * A representative frame for a video attachment.
     *
     * Rotation is deliberately **not** re-applied from `METADATA_KEY_VIDEO_ROTATION`: the platform
     * retriever already returns an upright frame (the same reason `ThumbnailUtils` needs no rotation
     * step), so doing it here would turn every portrait clip on its side.
     */
    private fun decodeVideoFrame(context: Context, source: String, maxLongEdge: Int): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                source.startsWith("content://") || source.startsWith("file://") ->
                    retriever.setDataSource(context, Uri.parse(source))
                else -> {
                    if (!File(source).exists()) return null
                    retriever.setDataSource(source)
                }
            }
            val frame = scaledFrame(retriever, maxLongEdge) ?: return null
            downscale(frame, maxLongEdge).asImageBitmap()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * `getScaledFrameAtTime` decodes straight into the target size (API 27+); below that, and
     * whenever the clip does not report its dimensions, take the full frame and shrink it after.
     */
    private fun scaledFrame(retriever: MediaMetadataRetriever, maxLongEdge: Int): Bitmap? {
        val unscaled = {
            retriever.getFrameAtTime(VIDEO_FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return unscaled()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (width <= 0 || height <= 0) return unscaled()
        val scale = minOf(1f, maxLongEdge.toFloat() / maxOf(width, height).toFloat())
        return retriever.getScaledFrameAtTime(
            VIDEO_FRAME_TIME_US,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
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
    private fun applyExifRotation(context: Context, source: String, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            openStream(context, source)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: return bitmap
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
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** `file://` goes through the resolver too; a bare path must exist before it is opened. */
    private fun openStream(context: Context, source: String): InputStream? = when {
        source.startsWith("content://") || source.startsWith("file://") ->
            context.contentResolver.openInputStream(Uri.parse(source))
        else -> File(source).takeIf { it.exists() && it.length() > 0L }?.inputStream()
    }

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
