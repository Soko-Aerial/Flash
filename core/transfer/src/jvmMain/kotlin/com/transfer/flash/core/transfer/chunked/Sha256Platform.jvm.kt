package com.transfer.flash.core.transfer.chunked

import java.security.MessageDigest

private val sha256Digest: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
    MessageDigest.getInstance("SHA-256")
}

internal actual fun platformSha256(bytes: ByteArray, offset: Int, length: Int): ByteArray {
    val md = sha256Digest.get()
    md.reset()
    md.update(bytes, offset, length)
    return md.digest()
}

internal actual fun platformSha256(chunks: Array<out ByteArray>): ByteArray {
    val md = sha256Digest.get()
    md.reset()
    for (chunk in chunks) {
        md.update(chunk)
    }
    return md.digest()
}
