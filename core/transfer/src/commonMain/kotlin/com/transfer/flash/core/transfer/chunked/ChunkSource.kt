package com.transfer.flash.core.transfer.chunked

import okio.Source

/**
 * Re-openable byte source for one outgoing file (C5.4).
 *
 * Phase 13B-2 (D10 = Option A) split this out of `Chunker.kt` — which stays in `androidMain`
 * because [ChunkStream] still depends on `ChunkFrame`, which is 13B-3 scope (`Sha256` was the
 * other name in this sentence until 13B-3 moved it here) — and re-typed `open()` from
 * `java.io.InputStream` to [okio.Source]. That single type change is what makes the send side of
 * the transfer pipeline expressible in common code at all.
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
