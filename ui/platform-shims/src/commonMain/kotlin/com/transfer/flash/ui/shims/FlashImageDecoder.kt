package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a media source into a memory-bounded [ImageBitmap].
 *
 * **Blocking.** Every method here does real work on the calling thread; both `:ui:chat` call sites
 * invoke it from `withContext(Dispatchers.IO)` inside a `produceState`, and that is the contract.
 */
public interface FlashImageDecoder {

    /**
     * Decodes [source] — a `content://` / `file://` URI or a filesystem path — into an upright,
     * memory-bounded bitmap, or null when there is nothing decodable there: an inbound file still in
     * flight, a revoked content grant, a deleted source, or something that is not media.
     *
     * @param isVideo take a representative frame instead of decoding the bytes as a still.
     * @param maxLongEdge cap for the longer edge of the result, in pixels.
     * @param memoize false for one-off large decodes (the full-screen viewer) that would evict the
     *   whole thumbnail cache to store a single bitmap nobody will ask for twice.
     * @param lowColorDepth prefer a two-byte-per-pixel decode when the platform supports it. Android
     *   uses `RGB_565` for opaque stills below the HIGH performance tier; video is unchanged.
     * @param computeInSampleSize the power-of-two subsampling factor for a source of the given size.
     *   Passed in rather than computed here, and that is the whole reason this parameter exists:
     *   `:ui:chat`'s `FlashMediaViewerMath.computeInSampleSize` is the tested implementation, it is
     *   shared with the viewer's own zoom maths, and it lives in the module that *depends* on this
     *   one. Reaching for it from here would be a dependency cycle; copying it would fork a function
     *   whose test suite would then only cover one of the two copies.
     */
    public fun decode(
        source: String?,
        isVideo: Boolean,
        maxLongEdge: Int = TILE_LONG_EDGE_PX,
        memoize: Boolean = true,
        lowColorDepth: Boolean = false,
        computeInSampleSize: (width: Int, height: Int, maxLongEdge: Int) -> Int,
    ): ImageBitmap?

    public companion object {
        /** Long-edge budget for an in-bubble tile below the HIGH performance tier. */
        public const val TILE_LONG_EDGE_MINIMAL_PX: Int = 480

        /** Long-edge budget for an in-bubble tile: sharp on a 300 dp tile, ~2 MB to hold. */
        public const val TILE_LONG_EDGE_PX: Int = 720
    }
}

/**
 * The image/video decoding seam.
 *
 * Not a `@Composable expect fun rememberDecodeImageBitmap(...)` returning a bitmap, which is how
 * PHASE-19 draws it: both call sites decode inside `produceState`'s producer, and that producer is a
 * **suspend** lambda, not a composable scope. A `@Composable` decode could not be called from either
 * of them. So the seam is a factory for a handle: the `@Composable` part acquires the platform
 * context, and `decode` is an ordinary blocking function the caller runs on `Dispatchers.IO`, exactly
 * as it does today.
 */
@Composable
public expect fun rememberFlashImageDecoder(): FlashImageDecoder
