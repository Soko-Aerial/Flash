package com.transfer.flash.core.calling

/** Android actual: `Process.getElapsedCpuTime()` (ms of CPU this process used). Null in host tests. */
internal actual fun processCpuTimeNanos(): Long? =
    runCatching { android.os.Process.getElapsedCpuTime() * 1_000_000L }.getOrNull()?.takeIf { it > 0L }

internal actual fun availableCores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private const val MB = 1024L * 1024L

internal actual fun processMemorySummary(): String = runCatching {
    val rt = Runtime.getRuntime()
    val heapUsed = (rt.totalMemory() - rt.freeMemory()) / MB
    val native = android.os.Debug.getNativeHeapAllocatedSize() / MB
    "heap=$heapUsed/${rt.maxMemory() / MB}MB native=${native}MB threads=${Thread.activeCount()}"
}.getOrDefault("mem=?")
