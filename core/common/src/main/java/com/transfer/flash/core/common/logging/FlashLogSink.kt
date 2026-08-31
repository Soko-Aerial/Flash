package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Destination for Flash log records. One implementation per platform.
 *
 * Implementations MUST NOT throw. A logging failure must never propagate into a
 * caller's code path.
 */
@FlashInternalApi
public fun interface FlashLogSink {
    public fun write(
        level: FlashLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    )
}