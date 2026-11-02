package com.transfer.flash.core.radio.wire

/**
 * A growable, compacting byte buffer for a streaming decoder (OPT-O26-04).
 *
 * `StreamFrameDecoder` used to keep its partial frame in a `ByteArray` and do `buf += data.copyOfRange(...)` on every
 * feed. With a byte-at-a-time link that re-copies the whole partial frame on every byte, so an N-byte message cost
 * O(N^2) bytes of copying: the measurement in `RadioAllocationBenchTest` showed 34.5 MB of allocation for one 8 KB
 * message. Appending into spare capacity instead makes it O(N).
 *
 * [head] is an index into the backing array, [tail] is the write position; the unread window is `[head, tail)`.
 * Indices passed to [get], [copyOut] and [discard] are relative to the start of that window.
 *
 * Not thread-safe; one decoder owns one queue.
 */
internal class ByteQueue(initialCapacity: Int = INITIAL_CAPACITY) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(2))
    private var head = 0
    private var tail = 0

    /** Bytes waiting to be consumed. */
    val size: Int get() = tail - head

    /** Appends `length` bytes of [data] starting at [offset]. */
    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensureCapacity(length)
        data.copyInto(buf, tail, offset, offset + length)
        tail += length
    }

    /** The byte [index] places into the unread window. */
    operator fun get(index: Int): Byte = buf[head + index]

    /** Copies `[from, to)` of the unread window out, the way `copyOfRange` did. */
    fun copyOut(from: Int, to: Int): ByteArray {
        require(from >= 0 && to >= from && to <= size) { "bad range [$from, $to) of $size" }
        return buf.copyOfRange(head + from, head + to)
    }

    /** Drops the first [count] bytes of the unread window. */
    fun discard(count: Int) {
        if (count <= 0) return
        head += count
        if (head >= tail) {
            head = 0
            tail = 0
        } else if (head >= COMPACT_AT || head > buf.size / 2) {
            compact()
        }
    }

    /** Empties the queue but keeps the allocated buffer (a protocol error mid-frame). */
    fun clear() {
        head = 0
        tail = 0
    }

    private fun ensureCapacity(extra: Int) {
        if (tail + extra <= buf.size) return
        compact()
        if (tail + extra <= buf.size) return
        var capacity = buf.size
        while (capacity < tail + extra) capacity *= 2
        buf = buf.copyOf(capacity)
    }

    private fun compact() {
        if (head == 0) return
        buf.copyInto(buf, 0, head, tail)
        tail -= head
        head = 0
    }

    private companion object {
        const val INITIAL_CAPACITY: Int = 256

        /** Compact once this many consumed bytes are sitting in front of the unread window. */
        const val COMPACT_AT: Int = 512
    }
}
