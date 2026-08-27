package com.transfer.flash.sample.consumer

import android.content.Context
import com.transfer.flash.core.engine.Flash
import com.transfer.flash.core.engine.FlashConfig
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.first

/**
 * Compile-only proof of the Phase 5 README quick-start (Task 5.4 acceptance). This is the
 * SAME code the root `README.md` shows, so if the README drifts from the real API this module
 * stops compiling. Never published; never run on device.
 *
 * The whole point of [Flash.create] is that a consumer needs no hand-assembly and no sibling
 * `core:*` dependency — only `:core:engine`. That includes the coroutine types (`Flow`/`StateFlow`)
 * the engine's public API returns; if this file resolves `discoveredEndpoints`/`activeTransfers`,
 * the engine correctly re-exposes coroutines to consumers.
 */
internal object QuickStart {

    /**
     * End-to-end happy path: build the engine, wait for a peer, send a file, then shut down.
     * Call this off the main thread ([Flash.create] opens the encrypted DB synchronously).
     */
    suspend fun sendFirstFileToAnyPeer(
        context: Context,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<*> {
        // 1. One call wires + starts discovery, advertising, network, and transfers.
        val engine: FlashEngine = Flash.create(
            context,
            FlashConfig(displayName = "My Device"),
        )
        try {
            // 2. Suspend until at least one peer is discovered on the LAN, then take the first.
            val peer = engine.discovery.discoveredEndpoints
                .first { it.isNotEmpty() }
                .first()

            // 3. Send. The receiver sees an OFFER and must accept (autoAcceptIncoming = false).
            return engine.transfers.sendFile(
                targetDevice = peer.device,
                fileUri = fileUri,
                displayName = displayName,
                fileSize = fileSize,
            )
        } finally {
            // 4. One off-switch: cancels the shared scope, stops radios, closes the DB. Idempotent.
            engine.close()
        }
    }

    /** Reads live progress off the engine's [StateFlow] to prove coroutine types are on-classpath. */
    fun activeTransferCount(engine: FlashEngine): Int =
        engine.transfers.activeTransfers.value.size
}
