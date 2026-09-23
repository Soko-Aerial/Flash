@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.perf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.transfer.flash.core.common.concurrent.PlatformLock
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.time.SystemTimeSource

/**
 * Android thermal governor monitoring SoC and battery thermals.
 *
 * Uses API 29+ [PowerManager.OnThermalStatusChangedListener] when available, and falls back to
 * [Intent.ACTION_BATTERY_CHANGED] battery temperature broadcasts with 2.0°C / 15-second
 * hysteresis to prevent rapid status oscillation.
 */
public class AndroidThermalGovernor(
    context: Context,
) : ThermalGovernor {

    private val lock = PlatformLock()
    private val listeners = LinkedHashSet<(FlashThermalStatus) -> Unit>()

    private var _status: FlashThermalStatus = FlashThermalStatus.NONE
    private var lastStatusChangeAtMs: Long = 0L

    override val status: FlashThermalStatus
        get() = lock.withLock { _status }

    init {
        val appContext = context.applicationContext
        var registeredQListener = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                try {
                    val initialStatus = mapPowerManagerStatus(powerManager.currentThermalStatus)
                    updateStatus(initialStatus)

                    powerManager.addThermalStatusListener(appContext.mainExecutor) { statusInt ->
                        val mapped = mapPowerManagerStatus(statusInt)
                        updateStatus(mapped)
                    }
                    registeredQListener = true
                    FlashLog.i("PERFORMANCE", "Registered PowerManager thermal listener (initial: $initialStatus)")
                } catch (e: Throwable) {
                    FlashLog.w("PERFORMANCE", "Failed to register PowerManager thermal listener: ${e.message}")
                }
            }
        }

        // Always register battery temperature receiver as secondary / fallback monitor
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    if (tempTenths > 0) {
                        val tempC = tempTenths / 10.0
                        onBatteryTemperatureSample(tempC, isQListenerActive = registeredQListener)
                    }
                }
            }
            appContext.registerReceiver(receiver, filter)
        } catch (e: Throwable) {
            FlashLog.w("PERFORMANCE", "Failed to register battery temperature receiver: ${e.message}")
        }
    }

    private fun mapPowerManagerStatus(statusInt: Int): FlashThermalStatus =
        when (statusInt) {
            PowerManager.THERMAL_STATUS_NONE -> FlashThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> FlashThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> FlashThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> FlashThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> FlashThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> FlashThermalStatus.EMERGENCY
            else -> FlashThermalStatus.NONE
        }

    /**
     * Updates thermal status using battery temperature heuristics with 2.0°C and 15-second hysteresis.
     */
    internal fun onBatteryTemperatureSample(tempC: Double, isQListenerActive: Boolean) {
        // If API 29+ listener is active and not in battery-critical state, respect SoC sensor
        if (isQListenerActive && tempC < 45.0) return

        val nowMs = SystemTimeSource.nowMs()
        lock.withLock {
            val current = _status
            val nextStatus = calculateHysteresisStatus(tempC, current, nowMs)
            if (nextStatus != current) {
                _status = nextStatus
                lastStatusChangeAtMs = nowMs
                FlashLog.i("PERFORMANCE", "Battery thermal update: ${tempC}°C -> $nextStatus (was $current)")
                dispatchListeners(nextStatus)
            }
        }
    }

    private fun calculateHysteresisStatus(
        tempC: Double,
        current: FlashThermalStatus,
        nowMs: Long,
    ): FlashThermalStatus {
        // Upward transitions trigger promptly
        if (tempC >= 48.0) return FlashThermalStatus.CRITICAL
        if (tempC >= 45.0 && current < FlashThermalStatus.SEVERE) return FlashThermalStatus.SEVERE
        if (tempC >= 42.0 && current < FlashThermalStatus.MODERATE) return FlashThermalStatus.MODERATE
        if (tempC >= 38.0 && current < FlashThermalStatus.LIGHT) return FlashThermalStatus.LIGHT

        // Downward transitions enforce 15s minimum dwell time and 2.0°C delta hysteresis
        val timeSinceChange = nowMs - lastStatusChangeAtMs
        if (timeSinceChange < HYSTERESIS_DWELL_MS && lastStatusChangeAtMs > 0L) {
            return current
        }

        return when (current) {
            FlashThermalStatus.CRITICAL -> if (tempC < (48.0 - HYSTERESIS_DELTA_C)) FlashThermalStatus.SEVERE else current
            FlashThermalStatus.SEVERE -> if (tempC < (45.0 - HYSTERESIS_DELTA_C)) FlashThermalStatus.MODERATE else current
            FlashThermalStatus.MODERATE -> if (tempC < (42.0 - HYSTERESIS_DELTA_C)) FlashThermalStatus.LIGHT else current
            FlashThermalStatus.LIGHT -> if (tempC < (38.0 - HYSTERESIS_DELTA_C)) FlashThermalStatus.NONE else current
            FlashThermalStatus.NONE, FlashThermalStatus.EMERGENCY -> current
        }
    }

    private fun updateStatus(newStatus: FlashThermalStatus) {
        val changed = lock.withLock {
            if (_status != newStatus) {
                val old = _status
                _status = newStatus
                lastStatusChangeAtMs = SystemTimeSource.nowMs()
                FlashLog.i("PERFORMANCE", "Thermal status transition: $old -> $newStatus")
                true
            } else false
        }
        if (changed) {
            dispatchListeners(newStatus)
        }
    }

    private fun dispatchListeners(newStatus: FlashThermalStatus) {
        val targets = lock.withLock { listeners.toList() }
        for (listener in targets) {
            try {
                listener(newStatus)
            } catch (e: Throwable) {
                FlashLog.w("PERFORMANCE", "Thermal listener error: ${e.message}")
            }
        }
    }

    override fun registerListener(listener: (FlashThermalStatus) -> Unit) {
        lock.withLock { listeners.add(listener) }
    }

    override fun unregisterListener(listener: (FlashThermalStatus) -> Unit) {
        lock.withLock { listeners.remove(listener) }
    }

    public companion object {
        private const val HYSTERESIS_DELTA_C = 2.0
        private const val HYSTERESIS_DWELL_MS = 15_000L

        /** Installs this governor as the global [ThermalGovernor] instance. */
        public fun install(context: Context): AndroidThermalGovernor {
            val governor = AndroidThermalGovernor(context)
            ThermalGovernor.setInstance(governor)
            return governor
        }
    }
}
