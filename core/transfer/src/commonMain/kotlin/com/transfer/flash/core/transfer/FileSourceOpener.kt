package com.transfer.flash.core.transfer

import okio.Source

/**
 * Functional stream source provider returning a [Source] given a source URI / descriptor.
 *
 * Phase 13B-2 (D10 = Option A) moved this out of `RealFlashTransferRepository.kt` — which stays
 * in `androidMain` — and re-typed `open()` from `java.io.InputStream` to [okio.Source].
 *
 * `PHASE-13B-desktop-fileio.md` §4 records this type as having **0 consumers outside
 * `:core:transfer`**. That is true of the type *name* and false of the type: every consumer
 * SAM-converts a lambda, so `grep FileSourceOpener` never sees them. The real external consumers
 * are `core/engine/.../Flash.kt` and `app/.../debug/DiscoveryEngineHolder.kt`, both of which
 * this phase had to edit (one `.source()` bridge call each).
 */
public fun interface FileSourceOpener {

    public fun open(fileUri: String): Source
}
