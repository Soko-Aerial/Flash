package com.transfer.flash.core.swarm.model

/**
 * Contiguous piece index range for HAVE frame run-length encoding.
 */
public data class PieceRange(
    public val start: Int,
    public val count: Int,
) {
    init {
        require(start >= 0) { "start must be >= 0, got $start" }
        require(count > 0) { "count must be > 0, got $count" }
    }
}

/**
 * Compact bitfield tracking verified pieces for a swarm transfer.
 * Backed by a [LongArray] for fast bitwise operations.
 */
public class Bitfield(
    public val size: Int,
    initialBits: LongArray? = null,
) {
    private val words: LongArray

    init {
        require(size >= 0) { "size must be >= 0, got $size" }
        val requiredWords = (size + 63) ushr 6
        if (initialBits != null) {
            require(initialBits.size == requiredWords) {
                "initialBits size ${initialBits.size} does not match required $requiredWords for bitfield size $size"
            }
            words = initialBits.copyOf()
            if (size > 0 && (size and 63) != 0) {
                val validBitsInLastWord = size and 63
                val mask = (1L shl validBitsInLastWord) - 1L
                words[words.size - 1] = words[words.size - 1] and mask
            }
        } else {
            words = LongArray(requiredWords)
        }
    }

    public fun get(index: Int): Boolean {
        checkBounds(index)
        val wordIndex = index ushr 6
        val bitIndex = index and 63
        return (words[wordIndex] and (1L shl bitIndex)) != 0L
    }

    public fun set(index: Int, value: Boolean = true) {
        checkBounds(index)
        val wordIndex = index ushr 6
        val bitIndex = index and 63
        if (value) {
            words[wordIndex] = words[wordIndex] or (1L shl bitIndex)
        } else {
            words[wordIndex] = words[wordIndex] and (1L shl bitIndex).inv()
        }
    }

    public fun count(): Int {
        var c = 0
        for (w in words) {
            c += countBits(w)
        }
        return c
    }

    public fun isComplete(): Boolean = size > 0 && count() == size

    public fun isEmpty(): Boolean = count() == 0

    public fun missingIndices(): Sequence<Int> = sequence {
        for (i in 0 until size) {
            if (!get(i)) yield(i)
        }
    }

    public fun setIndices(): Sequence<Int> = sequence {
        for (i in 0 until size) {
            if (get(i)) yield(i)
        }
    }

    public fun copy(): Bitfield = Bitfield(size, words.copyOf())

    public fun toByteArray(): ByteArray {
        val numBytes = (size + 7) ushr 3
        val out = ByteArray(numBytes)
        for (i in 0 until size) {
            if (get(i)) {
                val b = i ushr 3
                val bit = i and 7
                out[b] = (out[b].toInt() or (1 shl bit)).toByte()
            }
        }
        return out
    }

    public fun toRanges(): List<PieceRange> {
        val ranges = mutableListOf<PieceRange>()
        var inRange = false
        var rangeStart = 0
        for (i in 0 until size) {
            val bit = get(i)
            if (bit && !inRange) {
                inRange = true
                rangeStart = i
            } else if (!bit && inRange) {
                ranges.add(PieceRange(rangeStart, i - rangeStart))
                inRange = false
            }
        }
        if (inRange) {
            ranges.add(PieceRange(rangeStart, size - rangeStart))
        }
        return ranges
    }

    private fun checkBounds(index: Int) {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Bitfield index $index out of bounds [0, $size)")
        }
    }

    private fun countBits(v: Long): Int {
        var x = v
        x -= (x ushr 1) and 0x5555555555555555L
        x = (x and 0x3333333333333333L) + ((x ushr 2) and 0x3333333333333333L)
        x = (x + (x ushr 4)) and 0x0F0F0F0F0F0F0F0FL
        return ((x * 0x0101010101010101L) ushr 56).toInt()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bitfield) return false
        if (size != other.size) return false
        for (i in 0 until size) {
            if (get(i) != other.get(i)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = size
        for (w in words) {
            result = 31 * result + w.hashCode()
        }
        return result
    }

    public companion object {
        public fun fromByteArray(size: Int, bytes: ByteArray): Bitfield {
            val bf = Bitfield(size)
            for (i in 0 until size) {
                val byteIndex = i ushr 3
                if (byteIndex < bytes.size) {
                    val bitIndex = i and 7
                    if ((bytes[byteIndex].toInt() and (1 shl bitIndex)) != 0) {
                        bf.set(i, true)
                    }
                }
            }
            return bf
        }

        public fun fromRanges(size: Int, ranges: List<PieceRange>): Bitfield {
            val bf = Bitfield(size)
            var lastEnd = -1L
            for (range in ranges) {
                require(range.start.toLong() > lastEnd) {
                    "Ranges must be strictly sorted and non-overlapping: start=${range.start} <= lastEnd=$lastEnd"
                }
                val end = range.start.toLong() + range.count.toLong()
                require(end <= size.toLong()) {
                    "Range exceeds bitfield size: ${range.start} + ${range.count} > $size"
                }
                for (i in range.start until (range.start + range.count)) {
                    bf.set(i, true)
                }
                lastEnd = end - 1L
            }
            return bf
        }
    }
}
