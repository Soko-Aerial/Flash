package com.transfer.flash.core.transfer.manifest

import com.transfer.flash.core.common.time.SystemTimeSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Executes [TransferManifest]'s clock seam on **each** target (Phase 13B-1).
 *
 * The file's only pin to `androidMain` was `createdAtMs = System.currentTimeMillis()`, replaced by
 * `:core:common`'s `SystemTimeSource.nowMs()` — which on `jvm()` resolves through a different
 * `actual` than on Android. Compiling that is not the same as reading a clock through it, so
 * CONVENTIONS.md R3.1 wants one behavioural assertion in `commonTest`.
 */
class TransferManifestTest {

    @Test
    fun manifest_defaultsCreatedAtToNow() {
        val before = SystemTimeSource.nowMs()
        val manifest = TransferManifest(
            transferId = "t-1",
            senderDeviceId = "device-a",
            items = listOf(
                ManifestItem(
                    fileId = "f-1",
                    relativePath = "a/b.bin",
                    fileName = "b.bin",
                    totalBytes = 4_096L,
                    fileSha256Hex = "a".repeat(64),
                ),
            ),
        )
        val after = SystemTimeSource.nowMs()
        // A generous window: the assertion is that the default reads the wall clock at all, not
        // that it is precise. A stub returning 0 — or a clock seam wired to the wrong `actual` —
        // fails this on both targets.
        assertTrue(
            manifest.createdAtMs in before..after,
            "createdAtMs ${manifest.createdAtMs} outside [$before, $after]",
        )
    }
}
