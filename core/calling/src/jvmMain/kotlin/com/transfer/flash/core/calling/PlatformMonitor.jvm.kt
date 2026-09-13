package com.transfer.flash.core.calling

import java.util.concurrent.locks.ReentrantLock

/** Desktop actual — JVM-family monitor lock. Byte-identical to the androidMain actual (R2). */
internal actual class PlatformMonitor actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
