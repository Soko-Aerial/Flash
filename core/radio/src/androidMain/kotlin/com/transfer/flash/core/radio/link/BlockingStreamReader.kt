package com.transfer.flash.core.radio.link

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a blocking [InputStream] with no read timeout (a `BluetoothSocket`'s) into a timed read that still
 * sees the end of the stream.
 *
 * Polling `available()` cannot do that: it returns 0 both while the peer is quiet and after the peer has
 * gone, so a lost radio looked like silence for ever (RVW22 F22). A single daemon thread blocks in
 * [InputStream.read] instead, which returns -1 on a clean close and throws once the socket is closed
 * locally, and hands what it read to [read] through a channel.
 */
internal class BlockingStreamReader(
    private val input: InputStream,
    private val threadName: String = "rfcomm-rx",
) {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private var leftover: ByteArray? = null
    private var leftoverOffset = 0

    /**
     * Up to [buffer].size bytes, 0 when nothing arrived within [timeoutMs], -1 once the stream has ended.
     * One caller at a time (the driver's read loop).
     *
     * @throws IOException when the underlying read failed.
     */
    suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
        if (started.compareAndSet(false, true)) startPump()
        val pending = leftover
        if (pending != null) return drain(pending, buffer)
        val result = withTimeoutOrNull(timeoutMs) { chunks.receiveCatching() } ?: return 0
        val chunk = result.getOrNull()
        if (chunk == null) {
            val cause = result.exceptionOrNull()
            if (cause != null) throw (cause as? IOException ?: IOException(cause.message, cause))
            return -1
        }
        return drain(chunk, buffer)
    }

    private fun drain(source: ByteArray, buffer: ByteArray): Int {
        val n = minOf(source.size - leftoverOffset, buffer.size)
        System.arraycopy(source, leftoverOffset, buffer, 0, n)
        if (leftoverOffset + n >= source.size) {
            leftover = null
            leftoverOffset = 0
        } else {
            leftover = source
            leftoverOffset += n
        }
        return n
    }

    private fun startPump() {
        Thread({
            val scratch = ByteArray(PUMP_BUFFER_BYTES)
            try {
                while (true) {
                    val n = input.read(scratch)
                    if (n < 0) break
                    if (n > 0) chunks.trySend(scratch.copyOf(n))
                }
                chunks.close()
            } catch (e: IOException) {
                chunks.close(e)
            }
        }, threadName).apply { isDaemon = true }.start()
    }

    private companion object {
        const val PUMP_BUFFER_BYTES = 1024
    }
}
