package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Controller to trigger platform camera photo capture. Held across recomposition; safe to call from an event handler.
 */
public interface FlashCameraCaptureLauncher {
    public fun launch()
}

/**
 * Launches the platform camera capture interface and returns the saved photo as a [FlashPickedFile].
 */
@Composable
public expect fun rememberFlashCameraCaptureLauncher(
    onCaptured: (FlashPickedFile) -> Unit,
): FlashCameraCaptureLauncher
