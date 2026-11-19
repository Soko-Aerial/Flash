package com.transfer.flash.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun isReduceMotionOnPlatform(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember(context) { mutableStateOf(FlashMotion.isReduceMotionEnabled(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = FlashMotion.isReduceMotionEnabled(context)
            }
        }
        runCatching {
            val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
            if (uri != null) resolver.registerContentObserver(uri, false, observer)
        }
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return reduceMotion
}

/**
 * Reduce-motion query, byte-for-byte the pre-KMP `FlashMotion.Companion.isReduceMotionEnabled`.
 *
 * PHASE-18 step 1a says to "move it to the `androidMain` file". A `commonMain` class cannot gain
 * a companion member from a platform source set, so it moves as an **extension on the companion**:
 * every existing call site (`FlashMotion.isReduceMotionEnabled(context)`) still compiles verbatim
 * on Android — only the JVM signature changes, from `FlashMotion$Companion.isReduceMotionEnabled`
 * to a static in `FlashMotion_androidKt`. That is a binary break for a published 1.1.0 consumer
 * and is logged as one; it is not a source break, and it is not reachable from `commonMain`.
 */
fun FlashMotion.Companion.isReduceMotionEnabled(context: Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    if (scale == 0f) {
        return true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager != null && accessibilityManager.isReduceMotionEnabledCompat()) {
            return true
        }
    }
    return false
}

private fun AccessibilityManager.isReduceMotionEnabledCompat(): Boolean {
    return runCatching {
        AccessibilityManager::class.java
            .getMethod("isReduceMotionEnabled")
            .invoke(this) as Boolean
    }.getOrDefault(false)
}
