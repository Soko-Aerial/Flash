@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.perf

import com.transfer.flash.core.common.concurrent.PlatformLock
import com.transfer.flash.core.common.logging.FlashLog

/**
 * Memory trim levels corresponding to system memory pressure events (e.g. Android ComponentCallbacks2).
 */
public enum class MemoryTrimLevel {
    /**
     * The process UI is no longer visible (e.g. app backgrounded).
     * Good opportunity to release large UI-bound assets and preview caches.
     */
    UI_HIDDEN,

    /**
     * The system is running moderately low on memory while the app is active.
     * The process should free unneeded resources to keep the system responsive.
     */
    RUNNING_MODERATE,

    /**
     * The system is running critically low on memory while the app is active.
     * The process should aggressively purge disposable caches and shrink page caches.
     */
    RUNNING_LOW,

    /**
     * The system is running extremely low on memory and is preparing to kill background processes.
     * The process should immediately release all non-essential heap allocations and pools.
     */
    RUNNING_CRITICAL,

    /**
     * The device is low on memory and this process is in the background LRU list.
     */
    BACKGROUND,

    /**
     * The process is near the end of the LRU list and will be killed if memory is not freed immediately.
     */
    COMPLETE,
}

/**
 * Listener interface for memory pressure notifications.
 */
public fun interface MemoryTrimListener {
    public fun onTrimMemory(level: MemoryTrimLevel)
}

/**
 * Central coordinator for application-wide memory governance and adaptive cache eviction.
 */
public object MemoryGovernor {
    private val lock = PlatformLock()
    private val listeners = LinkedHashSet<MemoryTrimListener>()

    public fun registerListener(listener: MemoryTrimListener) {
        lock.withLock {
            listeners.add(listener)
        }
    }

    public fun unregisterListener(listener: MemoryTrimListener) {
        lock.withLock {
            listeners.remove(listener)
        }
    }

    public fun notifyTrim(level: MemoryTrimLevel) {
        FlashLog.w("PERFORMANCE", "Memory trim event received: $level")
        val targets: List<MemoryTrimListener> = lock.withLock {
            listeners.toList()
        }
        for (listener in targets) {
            try {
                listener.onTrimMemory(level)
            } catch (e: Throwable) {
                FlashLog.e("PERFORMANCE", "Error dispatching memory trim event $level", e)
            }
        }
    }
}
