@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.perf

import com.transfer.flash.core.common.concurrent.PlatformLock

/**
 * Hardware thermal status representing SoC and device thermal pressure.
 * Ordered monotonically by severity: [NONE] < [LIGHT] < [MODERATE] < [SEVERE] < [CRITICAL] < [EMERGENCY].
 */
public enum class FlashThermalStatus {
    /** Normal operating temperature. No throttling necessary. */
    NONE,

    /** Light thermal throttling. Pacing recommended for continuous background transfers. */
    LIGHT,

    /** Moderate thermal throttling. Concurrency or rate reduction recommended. */
    MODERATE,

    /** Severe thermal throttling. Stepping down parallel streams (e.g. 2 -> 1) required. */
    SEVERE,

    /** Critical thermal throttling. Heavy throttling required to prevent SoC shutdown. */
    CRITICAL,

    /** Emergency thermal condition. Hardware thermal shutdown is imminent. */
    EMERGENCY,
}

/**
 * Public contract for monitoring and reacting to device thermal throttling.
 */
public interface ThermalGovernor {
    public val status: FlashThermalStatus
    public fun registerListener(listener: (FlashThermalStatus) -> Unit)
    public fun unregisterListener(listener: (FlashThermalStatus) -> Unit)

    public companion object {
        private val lock = PlatformLock()
        private var instance: ThermalGovernor = DefaultThermalGovernor

        public fun get(): ThermalGovernor = lock.withLock { instance }

        public fun setInstance(governor: ThermalGovernor) {
            lock.withLock { instance = governor }
        }
    }
}

/**
 * Default no-op governor for non-Android platforms or tests.
 */
public object DefaultThermalGovernor : ThermalGovernor {
    override val status: FlashThermalStatus get() = FlashThermalStatus.NONE
    override fun registerListener(listener: (FlashThermalStatus) -> Unit) {}
    override fun unregisterListener(listener: (FlashThermalStatus) -> Unit) {}
}
