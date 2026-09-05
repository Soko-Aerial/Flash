package com.transfer.flash.core.transfer.chunked

/**
 * Destination abstraction: persists one verified chunk at [index].
 *
 * Phase 13B-2 split this out of `ReceivePipeline.kt` (which stays in `androidMain` — it depends
 * on `ChunkFrame`, `Sha256`, `ResumeBitVector` and `sortedSetOf`, all 13B-3 scope). Unlike the
 * other three seams D10 names, this one needed **no re-typing**: `Int` and `ByteArray` are
 * already common, so the whole `java.io` problem here was the file it happened to live in.
 */
public fun interface ChunkSink {

    public fun write(index: Int, data: ByteArray)
}
