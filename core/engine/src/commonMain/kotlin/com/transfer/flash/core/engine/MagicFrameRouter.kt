package com.transfer.flash.core.engine

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.engine.concurrent.PlatformLock

/**
 * Handler for binary frames matching a 4-byte magic header (SW-2).
 *
 * @param peerDeviceId The ID of the peer that sent the frame, or null if unauthenticated.
 * @param frame The decrypted binary frame bytes, starting with the 4-byte magic.
 * @param reply Function to send an encrypted reply back to the peer.
 * @return True if the frame was consumed, false otherwise.
 */
public typealias MagicFrameHandler = (peerDeviceId: String?, frame: ByteArray, reply: (ByteArray) -> Boolean) -> Boolean

/**
 * Routes inbound binary frames by 4-byte magic after decryption and before the transfer pipeline (SW-2).
 *
 * Reserved magics (such as `"FSW1"`) are always consumed: if no handler is registered,
 * the frame is dropped with a rate-limited log line so stray frames never reach the transfer pipeline.
 */
public class MagicFrameRouter(
    private val timeSource: () -> Long = monotonicMillis(),
) {
    private val lock = PlatformLock()
    private val handlers = mutableMapOf<MagicKey, MagicFrameHandler>()

    /** Null until the first drop is logged, so the first dropped frame is always reported (the clock's origin is arbitrary). */
    private var lastReservedDropLogMs: Long? = null

    public fun register(magic: ByteArray, handler: MagicFrameHandler) {
        val key = MagicKey.of(magic) ?: throw IllegalArgumentException("Magic must be at least 4 bytes")
        lock.withLock {
            handlers[key] = handler
        }
    }

    public fun unregister(magic: ByteArray) {
        val key = MagicKey.of(magic) ?: return
        lock.withLock {
            handlers.remove(key)
        }
    }

    /**
     * Attempts to route [frame] to a registered handler matching its 4-byte magic.
     *
     * @return True if the frame was consumed (either by a registered handler or dropped as a reserved magic),
     *         false if the frame has an unreserved, unrecognized magic and should continue to the transfer pipeline.
     */
    public fun dispatch(peerDeviceId: String?, frame: ByteArray, reply: (ByteArray) -> Boolean): Boolean {
        if (frame.size < 4) return false
        val key = MagicKey.of(frame) ?: return false

        val handler = lock.withLock { handlers[key] }
        if (handler != null) {
            return handler(peerDeviceId, frame, reply)
        }

        if (RESERVED_MAGICS.contains(key)) {
            val now = timeSource()
            val shouldLog = lock.withLock {
                val last = lastReservedDropLogMs
                if (last == null || now - last >= LOG_RATE_LIMIT_MS) {
                    lastReservedDropLogMs = now
                    true
                } else {
                    false
                }
            }
            if (shouldLog) {
                @OptIn(FlashInternalApi::class)
                FlashLog.w(TAG, "Dropped reserved magic frame (${key.toAsciiString()}) from peer $peerDeviceId: no handler registered")
            }
            return true
        }

        return false
    }

    private data class MagicKey(val b0: Byte, val b1: Byte, val b2: Byte, val b3: Byte) {
        fun toAsciiString(): String = byteArrayOf(b0, b1, b2, b3).decodeToString()

        companion object {
            fun of(bytes: ByteArray): MagicKey? {
                if (bytes.size < 4) return null
                return MagicKey(bytes[0], bytes[1], bytes[2], bytes[3])
            }
        }
    }

    public companion object {
        private const val TAG = "SWARM"
        private const val LOG_RATE_LIMIT_MS = 10_000L

        /**
         * A monotonic millisecond clock measured from a single origin captured NOW. The previous default called
         * `markNow().elapsedNow()` on every read, which is always about 0, so the rate-limited drop warning never fired.
         */
        internal fun monotonicMillis(): () -> Long {
            val origin = kotlin.time.TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }

        public val FSW1_MAGIC: ByteArray = byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte())

        private val RESERVED_MAGICS: Set<MagicKey> = setOf(
            MagicKey.of(FSW1_MAGIC)!!,
        )
    }
}
