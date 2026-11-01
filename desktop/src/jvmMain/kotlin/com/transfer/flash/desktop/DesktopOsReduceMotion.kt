package com.transfer.flash.desktop

import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects whether the host desktop operating system has requested reduced motion / disabled visual animations (OPT-O24-08).
 *
 * - Windows: Queries desktop property `win.clientareaAnimation.enabled` (false = animations disabled in Windows Settings).
 * - macOS: Queries desktop property `apple.awt.reduceMotion`.
 *
 * Uses a property change listener for real-time reactivity and a cached TTL fallback to avoid overhead on composition frames.
 */
public object DesktopOsReduceMotion {
    private const val CACHE_TTL_MS = 5_000L
    private val cachedResult = AtomicBoolean(false)
    private val lastCheckTime = AtomicLong(0L)

    init {
        try {
            Toolkit.getDefaultToolkit().addPropertyChangeListener("win.clientareaAnimation.enabled") { evt ->
                val anim = evt.newValue
                if (anim is Boolean) {
                    cachedResult.set(!anim)
                    lastCheckTime.set(System.currentTimeMillis())
                }
            }
        } catch (_: Throwable) {
            // Headless or unsupported toolkit
        }
    }

    public fun isReduceMotion(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastCheckTime.get()
        if (now - last < CACHE_TTL_MS && last > 0L) {
            return cachedResult.get()
        }
        val result = queryOsPreference()
        cachedResult.set(result)
        lastCheckTime.set(now)
        return result
    }

    internal fun queryOsPreference(): Boolean {
        try {
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("win")) {
                val animEnabled = Toolkit.getDefaultToolkit().getDesktopProperty("win.clientareaAnimation.enabled")
                if (animEnabled is Boolean) {
                    return !animEnabled
                }
            } else if (os.contains("mac")) {
                val reduceMotion = Toolkit.getDefaultToolkit().getDesktopProperty("apple.awt.reduceMotion")
                if (reduceMotion is Boolean) {
                    return reduceMotion
                }
            }
        } catch (_: Throwable) {
            // Headless, security manager, or unsupported platform
        }
        return false
    }

    internal fun resetCacheForTesting() {
        lastCheckTime.set(0L)
    }
}
