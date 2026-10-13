package com.transfer.flash.core.enterprise.relay

internal actual fun <T> withRelayLock(lock: Any, block: () -> T): T = synchronized(lock, block)
