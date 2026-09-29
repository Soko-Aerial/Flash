package com.transfer.flash.core.calling

/** Android actual: `Process.getElapsedCpuTime()` (ms of CPU this process used). Null in host tests. */
internal actual fun processCpuTimeNanos(): Long? =
    runCatching { android.os.Process.getElapsedCpuTime() * 1_000_000L }.getOrNull()?.takeIf { it > 0L }

internal actual fun availableCores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
