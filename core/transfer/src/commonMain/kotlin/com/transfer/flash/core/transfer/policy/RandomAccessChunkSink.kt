package com.transfer.flash.core.transfer.policy

import com.transfer.flash.core.transfer.chunked.ChunkSink

/**
 * Bridges [RandomAccessSinkHandle] with [ChunkSink] for a specific transfer and chunk size.
 * Chunks can arrive in any order from multiple parallel streams; this sink computes the
 * exact byte offset `(index * chunkSize)` and writes it via the handle.
 *
 * Phase 13B-2 moved this file from `androidMain` to `commonMain` **unchanged** — every type in it
 * (`Int`, `Long`, `ByteArray`) was already common. It comes along because it is the join between
 * the two seams this phase relocates: without it, `commonMain` would hold a
 * [RandomAccessSinkHandle] and a [ChunkSink] and no way to connect them, which is precisely what
 * a desktop receive path needs in Phase 15/16.
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
