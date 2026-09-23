package com.transfer.flash.di

import android.app.Application
import android.content.ComponentCallbacks2
import com.transfer.flash.core.common.perf.AndroidThermalGovernor
import com.transfer.flash.core.common.perf.MemoryGovernor
import com.transfer.flash.core.common.perf.MemoryTrimLevel
import com.transfer.flash.debug.FlashKeepaliveWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FlashApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroidThermalGovernor.install(this)
        // ADR-041: safety net for a process the user (or an OEM killer) ended — see the worker's
        // KDoc for what it deliberately does not do.
        FlashKeepaliveWorker.schedule(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val trimLevel = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryTrimLevel.UI_HIDDEN
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> MemoryTrimLevel.RUNNING_MODERATE
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryTrimLevel.RUNNING_LOW
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryTrimLevel.RUNNING_CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryTrimLevel.BACKGROUND
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryTrimLevel.COMPLETE
            else -> if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) MemoryTrimLevel.BACKGROUND else null
        }
        if (trimLevel != null) {
            MemoryGovernor.notifyTrim(trimLevel)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MemoryGovernor.notifyTrim(MemoryTrimLevel.RUNNING_CRITICAL)
    }
}
