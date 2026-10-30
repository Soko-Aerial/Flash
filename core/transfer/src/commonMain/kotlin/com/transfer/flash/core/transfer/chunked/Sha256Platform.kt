package com.transfer.flash.core.transfer.chunked

internal expect fun platformSha256(bytes: ByteArray, offset: Int, length: Int): ByteArray

internal expect fun platformSha256(chunks: Array<out ByteArray>): ByteArray
