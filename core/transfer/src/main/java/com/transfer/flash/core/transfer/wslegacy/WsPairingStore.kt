package com.transfer.flash.core.transfer.wslegacy

import com.transfer.flash.core.security.trust.FlashTrustStore

/**
 * LEGACY relocation (C5.1): pre-chunked whole-file 64KiB-frame engine retained as
 * fallback/reference; superseded by transfer.chunked pipelines (C5.3+) — scheduled for
 * deletion after parity.
 *
 * Remembers devices that completed a WebSocket pairing (hello exchange), so a
 * rediscovered peer is recognized immediately and can reconnect/re-share without
 * a fresh manual pairing step.
 *
 * Delegates to a [FlashTrustStore] (production default: `AndroidPreferencesTrustStore`,
 * constructed by the caller with its `Context` — moved out of this class so the store
 * seam stays JVM-testable per R2).
 */
internal class WsPairingStore(
    private val store: FlashTrustStore,
) {
    fun isPaired(deviceId: String): Boolean = store.isTrusted(deviceId)

    fun markPaired(deviceId: String, friendlyName: String) {
        store.trustPeer(deviceId, friendlyName)
    }

    fun unpair(deviceId: String) {
        store.revokeTrust(deviceId)
    }
}
