package com.transfer.flash.core.engine.concurrent

internal actual class PlatformLock {
    private val monitor = Any()

    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
