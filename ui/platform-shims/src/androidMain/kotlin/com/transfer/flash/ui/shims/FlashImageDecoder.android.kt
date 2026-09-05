package com.transfer.flash.ui.shims

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.InputStream

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
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? = FlashMediaDecoder.decode(
        context = context,
        source = source,
        isVideo = isVideo,
        maxLongEdge = maxLongEdge,
        memoize = memoize,
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
 *
 * Blocking work throughout — call from a background dispatcher.
 */
internal object FlashMediaDecoder {

    /**
     * Frame offset for a video thumbnail. Time 0 is frequently a black lead-in frame, ~200 ms
     * almost never is; `OPTION_CLOSEST_SYNC` then snaps to the nearest key frame, so this is cheap.
     */
    private const val VIDEO_FRAME_TIME_US: Long = 200_000L

    private val cache: LruCache<String, ImageBitmap> =
        object : LruCache<String, ImageBitmap>(cacheBytes()) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
        }

    fun decode(
        context: Context,
        source: String?,
        isVideo: Boolean,
        maxLongEdge: Int = FlashImageDecoder.TILE_LONG_EDGE_PX,
        memoize: Boolean = true,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? {
        if (source.isNullOrBlank()) return null
        val key = "$source|$isVideo|$maxLongEdge"
        if (memoize) cache.get(key)?.let { return it }
        // Catches Throwable on purpose: a decode can still fail on an OEM codec or a truncated file,
        // and every caller's contract is "no thumbnail" rather than a crashed composition.
        val decoded = runCatching {
            if (isVideo) {
                decodeVideoFrame(context, source, maxLongEdge)
            } else {
                decodeImage(context, source, maxLongEdge, computeInSampleSize)
            }
        }.getOrNull() ?: return null
        if (memoize) cache.put(key, decoded)
        return decoded
    }

    /**
     * Two-pass decode: bounds first, then the real thing at a power-of-two sample size. Uses the
     * caller-supplied sizing function — `:ui:chat`'s tested `FlashMediaViewerMath.computeInSampleSize`
     * — so both surfaces size identically.
     */
    private fun decodeImage(
        context: Context,
        source: String,
        maxLongEdge: Int,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
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
}
