package com.transfer.flash.core.calling

import java.lang.management.ManagementFactory

/** Desktop actual: the JVM's process CPU time (HotSpot `com.sun.management` bean); null if unsupported. */
internal actual fun processCpuTimeNanos(): Long? = runCatching {
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.processCpuTime
        ?.takeIf { it >= 0L }
}.getOrNull()

internal actual fun availableCores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
