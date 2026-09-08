package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Grants everything, per **D7c**: "desktop returns granted unconditionally. No permissions library."
 *
 * This is not a stub in the R2 sense. Desktop JVM has no runtime-permission model — a process that
 * can open the default recording device simply can, and macOS/Windows raise their own OS-level
 * consent prompt on first capture, outside the application's control. Answering `false` here would
 * disable a feature the platform actually permits; answering `true` and letting the capture attempt
 * fail is both honest and the only behaviour the platform can support.
 *
 * Whether that capture *succeeds* is a separate question, answered by `FlashVoiceRecorder`'s own `jvm`
 * actual, which reports its unsupported state through its `start()` return value.
 */
@Composable
public actual fun rememberFlashPermissionRequester(): FlashPermissionRequester =
    remember { JvmPermissionRequester }

private object JvmPermissionRequester : FlashPermissionRequester {
    override fun isGranted(permission: FlashPermission): Boolean = true

    override suspend fun ensureGranted(permission: FlashPermission): Boolean = true
}
