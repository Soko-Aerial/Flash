package com.transfer.flash.core.calling

/**
 * Android cannot present yet (ADR-102): MediaProjection needs a consent flow in the activity and a foreground
 * service of type `mediaProjection`, which are not built. Watching a share works on Android.
 */
internal actual fun defaultScreenCaptureProvider(): ScreenCaptureProvider = NoScreenCapture
