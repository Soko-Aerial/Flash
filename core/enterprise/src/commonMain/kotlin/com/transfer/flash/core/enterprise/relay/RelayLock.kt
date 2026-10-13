package com.transfer.flash.core.enterprise.relay

/**
 * Runs [block] while holding the monitor of [lock]. `kotlin.synchronized` is JVM-only and `commonMain` is kept strict, so this
 * is a one-line seam with identical `actual`s on the two JVM-family targets (android, jvm). Same shape as the radio code's
 * helper in `:core:network`, copied so that this module never depends on `:core:network`. An `expect fun` needs no
 * `-Xexpect-actual-classes` flag.
 */
internal expect fun <T> withRelayLock(lock: Any, block: () -> T): T
