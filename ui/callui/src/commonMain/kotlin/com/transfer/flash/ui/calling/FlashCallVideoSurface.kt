package com.transfer.flash.ui.calling

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shepeliev.webrtckmp.VideoStreamTrack

/**
 * Live video-track surface seam — Phase 25 S3b.
 *
 * The screen was androidMain precisely because of this: it bound `SurfaceViewRenderer` (an AAR
 * type) through `AndroidView`. The BINDING LOGIC is platform-free and stays in
 * `FlashCallScreen`; what crosses this seam is only "draw [track] into this box".
 *
 * Both actuals must honour the renderer-lifetime invariant the Android version documents at
 * length (`webrtc-renderer-lifetime` memory): the surface is initialised ONCE per composable
 * instance and released only on disposal, while track changes swap sinks and must NEVER release.
 * Releasing on a track change tore down the EGL render thread and left every later frame
 * dropped — the "two black tiles while audio works" bug.
 */
@Composable
internal expect fun FlashCallVideoSurface(
    track: VideoStreamTrack?,
    fit: CallVideoFit,
    modifier: Modifier = Modifier,
    zOrderMediaOverlay: Boolean = false,
    /** Flip the picture left to right (ADR-067: the user's own preview, like a mirror). Never applied to what is sent. */
    mirror: Boolean = false,
)

/** How the frame fills its box. Names are neutral; each actual maps to its own scaling enum. */
internal enum class CallVideoFit {
    /**
     * Fill the box, cropping the excess, when the picture and the box have the same orientation;
     * show the whole picture (bars on the sides or top and bottom) when they don't — the
     * full-screen remote surface and group tiles. Owner request 2026-09-29: a portrait phone
     * picture on a landscape desktop, or a landscape desktop picture on a portrait phone, is
     * shown complete rather than zoomed into its middle.
     */
    Balanced,

    /** Letterbox inside the box — the corner PiP, where cropping would hide the face. */
    Fit,
}

/**
 * The size the picture is drawn at inside a [boxWidth] x [boxHeight] box, for a frame of [frameWidth] x [frameHeight] (already turned
 * upright). The box is the limit; the result never exceeds it and is centred by the caller.
 *
 * Why this exists (ERROR-748): on Android the renderer view fills whatever box it is given and the renderer then CROPS the frame to
 * that box's aspect ratio, so `SCALE_ASPECT_FIT` alone never letterboxed anything: a desktop screen (16:9) shown on a portrait phone
 * lost most of its width. The view is therefore sized to the picture, and the box around it supplies the bars.
 *
 * [CallVideoFit.Fit] always shows the whole picture. [CallVideoFit.Balanced] fills the box (cropping the excess) when the picture and
 * the box have the same orientation, and shows the whole picture when they differ. An unknown frame size (no frame yet) fills the box.
 */
internal fun callVideoSize(fit: CallVideoFit, frameWidth: Int, frameHeight: Int, boxWidth: Int, boxHeight: Int): Pair<Int, Int> {
    if (frameWidth <= 0 || frameHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return boxWidth to boxHeight
    val sameOrientation = (frameWidth >= frameHeight) == (boxWidth >= boxHeight)
    if (fit == CallVideoFit.Balanced && sameOrientation) return boxWidth to boxHeight
    // Integer arithmetic: a floating-point scale could land a pixel over the box.
    val limitedByWidth = boxWidth.toLong() * frameHeight <= boxHeight.toLong() * frameWidth
    return if (limitedByWidth) {
        boxWidth to (boxWidth.toLong() * frameHeight / frameWidth).toInt().coerceAtLeast(1)
    } else {
        (boxHeight.toLong() * frameWidth / frameHeight).toInt().coerceAtLeast(1) to boxHeight
    }
}
