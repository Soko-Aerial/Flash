package com.transfer.flash.core.transfer.chunked

/**
 * Destination abstraction: persists one verified chunk at [index].
 *
 * Phase 13B-2 split this out of `ReceivePipeline.kt` (which stays in `androidMain` — it depends
 * on `ChunkFrame`, `ResumeBitVector` and `sortedSetOf`, all still 13B-3 scope; `Sha256` was the
 * fourth item on that list until 13B-3 moved it to `commonMain`). Unlike the other three seams
 * D10 names, this one needed **no re-typing**: `Int` and `ByteArray` are already common, so the
 * whole `java.io` problem here was the file it happened to live in.
 */
public fun interface ChunkSink {

    public fun write(index: Int, data: ByteArray)
}
