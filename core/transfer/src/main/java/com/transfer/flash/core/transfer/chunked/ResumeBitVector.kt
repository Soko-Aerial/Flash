package com.transfer.flash.core.transfer.chunked

import java.util.BitSet

/**
 * Persistent per-transfer record of which chunk indexes have been received (and hash-verified),
 * the resume state primitive for C5.6.
 *
 * ## Merge rule (`reconcile`) — monotonic union
 *
 * [reconcile] performs a **union**: an index is "done" if either side reported it done. This is
 * the correct merge for every consumer of this class:
 *
 * - **Receiver-side view:** progress only grows — a chunk once verified on disk can never become
 *   un-verified, so union with any later report cannot regress state.
 * - **Sender-side mirror of receiver progress (ACK reconciliation):** `ACK_BATCH.indexes` are
 *   authoritative facts emitted by the receiver after hash verification; unioning them into the
 *   sender's confirmed-set mirrors how BitTorrent peers merge handshake `bitfield` + incremental
 *   `have` announcements, which are likewise monotonic grow-only sets
 *   (https://github.com/mgp/coding-in-the-real-world/blob/master/manuscript/bittorrent-client-case-study.md;
 *   https://atomashpolskiy.github.io/bt/javadoc/latest/bt/data/Bitfield.html).
 * - **Reconcile-on-reconnect (C5.6):** the sender asks the receiver for its done-set and unions
 *   it with the locally persisted mirror before resuming at the first hole.
 *
 * ## Serialization format (compact bit-vector)
 *
 * Backed by [BitSet] packed via [BitSet.toLongArray]: `int32 LE wordCount` followed by
 * `wordCount` × 8 bytes, each 64-bit word written little-endian. Bit `n` of the vector lives in
 * word `n / 64`, bit value `1L shl (n % 64)`. [fromSerialized] rejects words beyond the expected
 * count and ignores/clears padding bits at or above [totalChunks], so a hostile or stale payload
 * can never resurrect chunks that do not belong to the transfer.
 */
public class ResumeBitVector(public val totalChunks: Int) {

    init {
        require(totalChunks > 0) { "totalChunks must be > 0, was $totalChunks" }
    }

    private val bits = BitSet(totalChunks)

    /** Number of distinct received (marked) chunk indexes. */
    public val receivedCount: Int
        get() = bits.cardinality()

    /**
     * Marks [index] as received.
     * @return true if this call newly marked the index, false if it was already marked.
     * @throws IndexOutOfBoundsException if [index] is outside `[0, totalChunks)`.
     */
    public fun markReceived(index: Int): Boolean {
        require(index in 0 until totalChunks) { "chunk index $index out of range [0,$totalChunks)" }
        val was = bits.get(index)
        bits.set(index)
        return !was
    }

    public fun isReceived(index: Int): Boolean =
        index in 0 until totalChunks && bits.get(index)

    public fun isComplete(): Boolean = receivedCount == totalChunks

    /** Ascending list of not-yet-received chunk indexes (the "holes" to request on resume). */
    public fun missingIndexes(): List<Int> {
        val out = ArrayList<Int>(totalChunks - receivedCount)
        for (i in 0 until totalChunks) {
            if (!bits.get(i)) out.add(i)
        }
        return out
    }

    /** Ascending list of received chunk indexes (what a receiver reports back to a sender). */
    public fun doneIndexes(): List<Int> {
        val out = ArrayList<Int>(receivedCount)
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            out.add(i)
            i = bits.nextSetBit(i + 1)
        }
        return out
    }

    /**
     * Union merge with a remote done-set; see class KDoc. Indexes outside `[0, totalChunks)` are
     * ignored rather than thrown: remote reports arrive over the wire and must never crash the
     * pipeline.
     */
    public fun reconcile(remoteDoneIndexes: Collection<Int>) {
        for (i in remoteDoneIndexes) {
            if (i in 0 until totalChunks) bits.set(i)
        }
    }

    public fun toSerialized(): ByteArray {
        val words = bits.toLongArray()
        val out = ByteArray(4 + words.size * 8)
        writeI32Le(out, 0, words.size)
        for ((w, word) in words.withIndex()) {
            var v = word
            val base = 4 + w * 8
            for (b in 0 until 8) {
                out[base + b] = (v and 0xFFL).toByte()
                v = v ushr 8
            }
        }
        return out
    }

    override fun toString(): String =
        "ResumeBitVector(received=${receivedCount}/$totalChunks)"

    public companion object {

        /** Word length in bits — documented constant so the wire layout stays pinned. */
        public const val WORD_BITS: Int = 64

        /**
         * Restores a vector previously written by [toSerialized].
         *
         * @return null when [bytes] is structurally invalid (too short, declares more words than
         * `ceil(totalChunks / 64)` allows, or trailing garbage). Padding bits at or above
         * [totalChunks] are tolerated and cleared.
         */
        public fun fromSerialized(totalChunks: Int, bytes: ByteArray?): ResumeBitVector? {
            require(totalChunks > 0) { "totalChunks must be > 0, was $totalChunks" }
            if (bytes == null || bytes.size < 4) return null
            val wordCount = readI32Le(bytes, 0)
            val maxWords = (totalChunks + WORD_BITS - 1) / WORD_BITS
            if (wordCount < 0 || wordCount > maxWords) return null
            if (bytes.size != 4 + wordCount * 8) return null
            val words = LongArray(wordCount)
            for (w in 0 until wordCount) {
                var v = 0L
                val base = 4 + w * 8
                for (b in 0 until 8) {
                    v = v or ((bytes[base + b].toLong() and 0xFFL) shl (8 * b))
                }
                words[w] = v
            }
            val vector = ResumeBitVector(totalChunks)
            val restored = BitSet.valueOf(words)
            for (i in 0 until totalChunks) {
                if (restored.get(i)) vector.bits.set(i)
            }
            return vector
        }

        private fun writeI32Le(out: ByteArray, offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        private fun readI32Le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
