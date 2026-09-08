package com.transfer.flash.core.discovery.concurrent

internal actual class PlatformLock {
    private val monitor = Any()

    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
