package com.transfer.flash.core.calling

import java.util.concurrent.locks.ReentrantLock

/** Android actual — JVM-family monitor lock. Byte-identical to the jvmMain actual (R2). */
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
