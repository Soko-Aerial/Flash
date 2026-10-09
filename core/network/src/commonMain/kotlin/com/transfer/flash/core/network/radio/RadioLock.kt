package com.transfer.flash.core.network.radio

/**
 * Runs [block] while holding the monitor of [lock]. `kotlin.synchronized` is JVM-only and `commonMain` is kept strict
 * (D1 = B), so this is a one-line seam with identical `actual`s on the two JVM-family targets. An `expect fun` needs no
 * `-Xexpect-actual-classes` flag, which this module deliberately does not set.
 */
internal expect fun <T> withRadioLock(lock: Any, block: () -> T): T
