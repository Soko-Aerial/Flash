package com.transfer.flash.core.transfer.chunked

/**
 * Destination abstraction: persists one verified chunk at [index].
 *
 * Phase 13B-2 split this out of `ReceivePipeline.kt`, which was `androidMain` until 13B-3e because
 * it depended on `ChunkFrame`, `ResumeBitVector`, `Sha256`, `@Synchronized` and `sortedSetOf` — the
 * first three went common in 13B-3a–3c, the last two in 13B-3e, and the pipeline went with them.
 * Unlike the other three seams D10 names, this one needed **no re-typing**: `Int` and `ByteArray`
 * are already common, so the whole `java.io` problem here was the file it happened to live in.
 */
public fun interface ChunkSink {

    public fun write(index: Int, data: ByteArray)
}
