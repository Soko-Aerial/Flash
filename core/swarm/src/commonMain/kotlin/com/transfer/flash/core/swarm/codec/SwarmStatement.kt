package com.transfer.flash.core.swarm.codec

import com.transfer.flash.core.swarm.model.ContentRoot

/**
 * Domain-separated canonical byte statements for swarm cryptographic signatures (SW-3).
 * Follows v2 group canonical rules: 4-byte big-endian length prefix before every field.
 */
public object SwarmStatement {
    public const val TAG_ANNOUNCE: String = "flash-swarm-v1/announce"
    public const val TAG_CANCEL: String = "flash-swarm-v1/cancel"
    public const val TAG_SOURCE: String = "flash-swarm-v1/source"

    public fun announce(
        groupId: String,
        messageId: String,
        originId: String,
        root: ContentRoot,
        sizeBytes: Long,
        fileName: String,
        mimeType: String,
        sentAt: Long,
    ): ByteArray = CanonicalWriter(TAG_ANNOUNCE)
        .text(groupId)
        .text(messageId)
        .text(originId)
        .text(root.hex)
        .long(sizeBytes)
        .text(fileName)
        .text(mimeType)
        .long(sentAt)
        .build()

    public fun cancel(
        groupId: String,
        root: ContentRoot,
        originId: String,
        messageId: String,
        reason: String,
        cancelledAtMs: Long,
    ): ByteArray = CanonicalWriter(TAG_CANCEL)
        .text(groupId)
        .text(root.hex)
        .text(originId)
        .text(messageId)
        .text(reason)
        .long(cancelledAtMs)
        .build()

    public fun source(
        groupId: String,
        root: ContentRoot,
        originId: String,
        messageId: String,
        status: String,
        reason: String,
        atMs: Long,
    ): ByteArray = CanonicalWriter(TAG_SOURCE)
        .text(groupId)
        .text(root.hex)
        .text(originId)
        .text(messageId)
        .text(status)
        .text(reason)
        .long(atMs)
        .build()

    private class CanonicalWriter(tag: String) {
        private val parts = ArrayList<ByteArray>()
        private var total = 0

        init {
            text(tag)
        }

        fun text(value: String): CanonicalWriter = bytes(value.encodeToByteArray())

        fun bytes(value: ByteArray): CanonicalWriter {
            val len = value.size
            parts.add(
                byteArrayOf(
                    (len ushr 24).toByte(),
                    (len ushr 16).toByte(),
                    (len ushr 8).toByte(),
                    len.toByte(),
                ),
            )
            parts.add(value)
            total += 4 + len
            return this
        }

        fun long(value: Long): CanonicalWriter {
            val b = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
            return bytes(b)
        }

        fun build(): ByteArray {
            val out = ByteArray(total)
            var at = 0
            for (p in parts) {
                p.copyInto(out, at)
                at += p.size
            }
            return out
        }
    }
}
