package com.transfer.flash.core.transfer.policy

import com.transfer.flash.core.transfer.chunked.ChunkSink

/**
 * Bridges [RandomAccessSinkHandle] with [ChunkSink] for a specific transfer and chunk size.
 * Chunks can arrive in any order from multiple parallel streams; this sink computes the
 * exact byte offset `(index * chunkSize)` and writes it via the handle.
 */
public class RandomAccessChunkSink(
    private val handle: RandomAccessSinkHandle,
    private val chunkSize: Int,
) : ChunkSink {

    init {
        require(chunkSize > 0) { "chunkSize must be > 0, was $chunkSize" }
    }

    override fun write(index: Int, data: ByteArray) {
        require(index >= 0) { "index must be >= 0, was $index" }
        val offset = index.toLong() * chunkSize
        handle.writeAt(offset, data)
    }
}
