package com.transfer.flash.core.radio

internal actual fun <T> withRadioLock(lock: Any, block: () -> T): T = synchronized(lock, block)
