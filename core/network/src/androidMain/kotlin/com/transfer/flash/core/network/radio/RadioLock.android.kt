package com.transfer.flash.core.network.radio

internal actual fun <T> withRadioLock(lock: Any, block: () -> T): T = synchronized(lock, block)
