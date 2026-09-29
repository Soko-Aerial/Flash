package com.transfer.flash.core.calling

import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory

/** Desktop actual: the JVM's process CPU time (HotSpot `com.sun.management` bean); null if unsupported. */
internal actual fun processCpuTimeNanos(): Long? = runCatching {
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.processCpuTime
        ?.takeIf { it >= 0L }
}.getOrNull()

internal actual fun availableCores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private const val MB = 1024L * 1024L

@Suppress("DEPRECATION") // freePhysicalMemorySize: freeMemorySize needs JDK 14 APIs at compile time.
internal actual fun processMemorySummary(): String = runCatching {
    val rt = Runtime.getRuntime()
    val heapUsed = (rt.totalMemory() - rt.freeMemory()) / MB
    val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    val committed = os?.committedVirtualMemorySize?.takeIf { it > 0L }?.div(MB)
    val sysFree = os?.freePhysicalMemorySize?.takeIf { it > 0L }?.div(MB)
    val direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
        .firstOrNull { it.name == "direct" }?.memoryUsed?.div(MB)
    val threads = ManagementFactory.getThreadMXBean().threadCount
    "heap=$heapUsed/${rt.maxMemory() / MB}MB committed=${committed ?: "?"}MB direct=${direct ?: "?"}MB " +
        "threads=$threads sysFree=${sysFree ?: "?"}MB"
}.getOrDefault("mem=?")
