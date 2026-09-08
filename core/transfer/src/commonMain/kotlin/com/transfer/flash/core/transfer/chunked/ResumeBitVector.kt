package com.transfer.flash.core.transfer.chunked

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
 * `int32 LE wordCount` followed by `wordCount` × 8 bytes, each 64-bit word written little-endian.
 * Bit `n` of the vector lives in word `n / 64`, bit value `1L shl (n % 64)`. **Trailing all-zero
 * words are not written**, so `wordCount` is `(highestSetBit / 64) + 1`, or 0 for an empty vector.
 * [fromSerialized] rejects words beyond the expected count and ignores/clears padding bits at or
 * above [totalChunks], so a hostile or stale payload can never resurrect chunks that do not belong
 * to the transfer.
 *
 * ## Phase 13B-3c: `java.util.BitSet` → a [LongArray] bitset
 *
 * The backing store was `java.util.BitSet`, packed for serialization via `BitSet.toLongArray()`.
 * That is the only `java.*` this file ever used, and the words it produced were already the wire
 * format — so the replacement is the `LongArray` itself, with the bit arithmetic that `BitSet` was
 * doing written out. Three of `BitSet`'s behaviours are load-bearing here and are reproduced
 * deliberately rather than incidentally:
 *
 * - **`toLongArray()` trims trailing zero words.** Its length is `ceil(length() / 64)`, where
 *   `length()` is the highest set bit plus one — not the capacity. A fixed-size dump would emit a
 *   longer array for the same set of bits and change every serialized payload, so
 *   [significantWordCount] reproduces the trim. This is what the golden vectors in
 *   `ResumeBitVectorTest` exist to pin.
 * - **`cardinality()` is a population count**, here `Long.countOneBits()` per word. Kept as a
 *   computed property rather than a maintained counter: [fromSerialized] writes words wholesale,
 *   and a counter would be one more thing that can drift out of step with the bits.
 * - **`nextSetBit` skips empty words.** [doneIndexes] keeps that complexity with
 *   `countTrailingZeroBits()` and the `w and (w - 1)` lowest-set-bit clear, so a mostly-empty
 *   vector over a million chunks still costs one pass over 15,625 words and not a million bit
 *   tests. [missingIndexes] was already O(totalChunks) by nature and is unchanged in shape.
 *
 * `BitSet(totalChunks)` was a capacity *hint* that grew on demand; the [LongArray] is exactly
 * `ceil(totalChunks / 64)` words and cannot grow. Nothing is lost, because every mutator already
 * bounds-checks against `[0, totalChunks)`: [markReceived] throws, [reconcile] filters silently,
 * and [fromSerialized] masks. The wire format did not change — no field, no order, no width, no
 * endianness — and the layout paragraph above is the text that was there before.
 */
public class ResumeBitVector(public val totalChunks: Int) {

    init {
        require(totalChunks > 0) { "totalChunks must be > 0, was $totalChunks" }
    }

    private val words = LongArray((totalChunks + WORD_BITS - 1) / WORD_BITS)

    /** Number of distinct received (marked) chunk indexes. */
    public val receivedCount: Int
        get() {
            var n = 0
            for (word in words) n += word.countOneBits()
            return n
        }

    /**
     * Marks [index] as received.
     * @return true if this call newly marked the index, false if it was already marked.
     * @throws IndexOutOfBoundsException if [index] is outside `[0, totalChunks)`.
     */
    public fun markReceived(index: Int): Boolean {
        require(index in 0 until totalChunks) { "chunk index $index out of range [0,$totalChunks)" }
        val w = index / WORD_BITS
        val mask = 1L shl (index % WORD_BITS)
        val was = (words[w] and mask) != 0L
        words[w] = words[w] or mask
        return !was
    }

    public fun isReceived(index: Int): Boolean =
        index in 0 until totalChunks &&
            (words[index / WORD_BITS] and (1L shl (index % WORD_BITS))) != 0L

    public fun isComplete(): Boolean = receivedCount == totalChunks

    /** Ascending list of not-yet-received chunk indexes (the "holes" to request on resume). */
    public fun missingIndexes(): List<Int> {
        val out = ArrayList<Int>(totalChunks - receivedCount)
        for (i in 0 until totalChunks) {
            if (!isReceived(i)) out.add(i)
        }
        return out
    }

    /** Ascending list of received chunk indexes (what a receiver reports back to a sender). */
    public fun doneIndexes(): List<Int> {
        val out = ArrayList<Int>(receivedCount)
        for (w in words.indices) {
            var word = words[w]
            val base = w * WORD_BITS
            while (word != 0L) {
                out.add(base + word.countTrailingZeroBits())
                // Clears the lowest set bit, so the loop runs once per set bit and empty words cost
                // nothing — this is what `BitSet.nextSetBit` was doing.
                word = word and (word - 1L)
            }
        }
        return out
    }

    /**
     * Ascending list of indexes marked here but **not** in [other] — the delta a caller still has
     * to act on.
     *
     * [doneIndexes] boxes one `Int` per received chunk, so polling it to answer "what is new since
     * last time" costs allocations proportional to the whole done-set on every poll, however small
     * the delta. This computes the difference in [BitSet] words instead — 64 chunks per word — and
     * boxes only what it returns. See EXP-008 for why that mattered on the send path.
     */
    public fun receivedIndexesNotIn(other: ResumeBitVector): List<Int> {
        require(totalChunks == other.totalChunks) {
            "vector size mismatch: $totalChunks != ${other.totalChunks}"
        }
        val out = ArrayList<Int>()
        for (w in words.indices) {
            var word = words[w] and other.words[w].inv()
            val base = w * WORD_BITS
            while (word != 0L) {
                out.add(base + word.countTrailingZeroBits())
                word = word and (word - 1L)
            }
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
            if (i in 0 until totalChunks) {
                val w = i / WORD_BITS
                words[w] = words[w] or (1L shl (i % WORD_BITS))
            }
        }
    }

    public fun toSerialized(): ByteArray {
        val wordCount = significantWordCount()
        val out = ByteArray(4 + wordCount * 8)
        writeI32Le(out, 0, wordCount)
        for (w in 0 until wordCount) {
            var v = words[w]
            val base = 4 + w * 8
            for (b in 0 until 8) {
                out[base + b] = (v and 0xFFL).toByte()
                v = v ushr 8
            }
        }
        return out
    }

    /**
     * Words that must be written, reproducing `BitSet.toLongArray().size` — the highest set bit's
     * word index plus one, and 0 when nothing is set. Trailing zero words are not part of the
     * format; emitting them would change every payload this class has ever written.
     */
    private fun significantWordCount(): Int {
        var i = words.size
        while (i > 0 && words[i - 1] == 0L) i--
        return i
    }

    /**
     * Clears bits at or above [totalChunks] in the final word. `BitSet.valueOf` accepted them and
     * the old [fromSerialized] dropped them by copying only `0 until totalChunks`; masking is the
     * same thing in one operation instead of `totalChunks` of them.
     */
    private fun clearPaddingBits() {
        val used = totalChunks % WORD_BITS
        if (used != 0 && words.isNotEmpty()) {
            words[words.size - 1] = words[words.size - 1] and ((1L shl used) - 1L)
        }
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
            // wordCount <= maxWords == vector.words.size, checked above, so this cannot overrun.
            words.copyInto(vector.words)
            vector.clearPaddingBits()
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
