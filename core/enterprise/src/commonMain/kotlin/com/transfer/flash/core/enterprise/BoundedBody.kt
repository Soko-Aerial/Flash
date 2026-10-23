package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

/**
 * Reads a hub response as text without ever holding more than [maxBytes] + 1 bytes of it (CLI-03, ADR-222).
 *
 * `bodyAsText()` materialises the whole body before [com.transfer.flash.core.hubprotocol.rest.HubRestCodec] gets to refuse an
 * oversized one, so a broken or compromised hub could make a phone allocate without limit. This stops reading at one byte past the
 * limit: a body that is too big comes back as text that is still too big, so the caller's decode refuses it exactly as before, but
 * the rest of the body is never read.
 */
@FlashInternalApi
internal suspend fun HttpResponse.boundedText(maxBytes: Int = HubContract.MAX_REST_BODY_BYTES): String {
    val limit = maxBytes + 1
    val channel = bodyAsChannel()
    // Grown on demand: most answers are a few hundred bytes, a directory page can be megabytes.
    var buffer = ByteArray(minOf(limit, INITIAL_BUFFER_BYTES))
    var filled = 0
    while (filled < limit) {
        if (filled == buffer.size) buffer = buffer.copyOf(minOf(limit, buffer.size * 2))
        val n = channel.readAvailable(buffer, filled, buffer.size - filled)
        if (n < 0) break
        filled += n
    }
    if (filled >= limit) {
        channel.cancel(null)
    }
    return buffer.decodeToString(0, filled)
}

private const val INITIAL_BUFFER_BYTES = 8 * 1024
