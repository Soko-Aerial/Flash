package com.transfer.flash.core.transfer.chunked

import okio.Source

/**
 * Re-openable byte source for one outgoing file (C5.4).
 *
 * Phase 13B-2 (D10 = Option A) split this out of `Chunker.kt` — which was still `androidMain` then,
 * because [ChunkStream] depended on `ChunkFrame` — and re-typed `open()` from `java.io.InputStream`
 * to [okio.Source]. That single type change is what made the send side of the transfer pipeline
 * expressible in common code at all. `Chunker.kt` itself followed in 13B-3e, at which point the
 * `.buffer().inputStream()` bridges that adapted this interface back to `java.io` disappeared and
 * [ChunkStream] began reading an `okio.BufferedSource` directly.
 */
public fun interface ChunkSource {

    /**
     * Opens a fresh stream over the source bytes. MUST be callable multiple times: the send
     * pipeline opens once for hashing and once for chunking (and again per resume attempt).
     * Implementations over `ContentResolver`/SAF satisfy this naturally.
     *
     * On Android, adapt an existing `InputStream` with okio's `InputStream.source()` bridge; on
     * any target, an in-memory source is `Buffer().write(bytes)`.
     */
    public fun open(): Source
}
