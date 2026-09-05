package com.transfer.flash.core.transfer.chunked

/**
 * Destination abstraction: persists one verified chunk at [index].
 *
 * Phase 13B-2 split this out of `ReceivePipeline.kt` (which stays in `androidMain` — it depended
 * on `ChunkFrame`, `ResumeBitVector`, `Sha256` and `sortedSetOf`, all 13B-3 scope; the first three
 * are in `commonMain` as of 13B-3b and 13B-3c, leaving `sortedSetOf` for 13B-3e). Unlike the other
 * three seams D10 names, this one needed **no re-typing**: `Int` and `ByteArray` are already
 * common, so the whole `java.io` problem here was the file it happened to live in.
 */
public fun interface ChunkSink {

    public fun write(index: Int, data: ByteArray)
}
