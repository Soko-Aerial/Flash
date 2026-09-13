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
)

/** How the frame fills its box. Names are neutral; each actual maps to its own scaling enum. */
internal enum class CallVideoFit {
    /** Fill the box, cropping the excess — the full-screen remote surface. */
    Balanced,

    /** Letterbox inside the box — the corner PiP, where cropping would hide the face. */
    Fit,
}
