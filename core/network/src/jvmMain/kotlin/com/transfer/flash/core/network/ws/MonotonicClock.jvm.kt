package com.transfer.flash.core.network.ws

/** [monotonicNowMs] on the JVM: [System.nanoTime], scaled to milliseconds (SEC-D26-01). */
internal actual fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L
