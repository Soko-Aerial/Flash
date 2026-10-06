package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Controller to trigger platform camera photo capture. Held across recomposition; safe to call from an event handler.
 */
public interface FlashCameraCaptureLauncher {
    /**
     * True when [launch] takes a photo with a camera. False where the platform has no capture UI (desktop): the chat
     * then hides its Camera action instead of showing a file chooser under that name.
     */
    public val capturesFromCamera: Boolean

    public fun launch()
}

/**
 * Launches the platform camera capture interface and returns the saved photo as a [FlashPickedFile].
 */
@Composable
public expect fun rememberFlashCameraCaptureLauncher(
    onCaptured: (FlashPickedFile) -> Unit,
    /** Called with a sentence for the user when the camera could not be opened (never swallowed silently). */
    onFailure: (String) -> Unit = {},
): FlashCameraCaptureLauncher
