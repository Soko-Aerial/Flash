package com.transfer.flash.core.common.id

import java.util.UUID

internal actual fun randomUuidString(): String = UUID.randomUUID().toString()
