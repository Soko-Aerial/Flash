@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * The platform's default log destination, installed into [FlashLog] at first use.
 *
 * Phase 06 seam. Before the KMP conversion this was a single `public object
 * FlashPlatformLogSink` in `core/common` that called `android.util.Log` directly —
 * the one and only `android.*` import left in the module after Phase 03. It could not
 * move to `commonMain`, so it became this `expect` plus one `actual` per target:
 * `AndroidLogSink` (android.util.Log) and `JvmLogSink` (stderr).
 *
 * `internal`, not public: the old object was reachable in the published ABI but nothing
 * outside `core/common` ever referenced it, so the conversion narrowed it rather than
 * carrying a platform-shaped name into `commonMain`. Consumers install their own sink
 * through [FlashLog.installSink].
 */
internal expect fun platformLogSink(): FlashLogSink
