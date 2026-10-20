package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.enterprise.files.HubFileSource
import com.transfer.flash.core.transfer.FileSourceOpener
import okio.BufferedSource
import okio.buffer
import okio.use

/**
 * A [HubFileSource] over whatever the host's [FileSourceOpener] opens (an Android `content://` document, a desktop path).
 * Those are streams, not random-access files; the upload engine reads front to back (a hash pass, then the chunks in
 * order), so this keeps one stream open and only reopens it when a read goes backwards (the second pass, or a resume
 * that starts again from an earlier chunk). Nothing is copied to app storage first.
 *
 * Not thread-safe; the engine reads one chunk at a time.
 */
@OptIn(FlashInternalApi::class)
internal class StreamingHubFileSource(
    private val opener: FileSourceOpener,
    private val uri: String,
    override val size: Long,
) : HubFileSource, AutoCloseable {

    private var stream: BufferedSource? = null
    private var position = 0L

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0L && length >= 0 && offset + length <= size) { "read outside the source" }
        var s = stream
        if (s == null || offset < position) {
            s?.close()
            s = opener.open(uri).buffer()
            stream = s
            position = 0L
        }
        if (offset > position) {
            s.skip(offset - position)
            position = offset
        }
        // readByteArray throws EOFException when the file is shorter than it said: the engine reports it as a local error.
        val bytes = s.readByteArray(length.toLong())
        position += length
        return bytes
    }

    override fun close() {
        stream?.close()
        stream = null
        position = 0L
    }

    companion object {
        /** The length of [uri] by reading it once, for a picker that could not say (a size of 0 or less). */
        fun measure(opener: FileSourceOpener, uri: String): Long = opener.open(uri).buffer().use { s ->
            var total = 0L
            val scratch = okio.Buffer()
            while (true) {
                val n = s.read(scratch, SCAN_BYTES)
                if (n < 0L) break
                total += n
                scratch.clear()
            }
            total
        }

        private const val SCAN_BYTES = 64L * 1024L
    }
}
