package com.transfer.flash.core.engine

import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.transfer.FlashTransferRepository
import java.io.Closeable

/**
 * Top-level facade contract unifying all Flash core subsystems (C7.0 / ADR-010).
 * Exposes clean domain repository and service accessors for application ViewModels and UI layers.
 *
 * Implements [Closeable]: an engine built by [Flash.create] owns a shared [kotlinx.coroutines.CoroutineScope]
 * and transport resources (NSD/Wi-Fi/DB), so [close] must be called (e.g. from `onDestroy` /
 * `ViewModel.onCleared`) to cancel all coroutines and release those resources. [close] is idempotent.
 * Hand-assembled [DefaultFlashEngine] instances default to a no-op [close].
 */
public interface FlashEngine : Closeable {
    /** Real messaging repository managing chats, outbox, read receipts, and drafts. */
    public val chats: FlashChatRepository

    /** Multi-stream chunked file transfer repository and progress telemetry. */
    public val transfers: FlashTransferRepository

    /** Continuous local peer discovery and service advertising across radios. */
    public val discovery: FlashDiscovery

    /** Network connection manager, active sessions, and resilience engine. */
    public val network: FlashNetwork

    /** Cryptographic trust store tracking verified and paired devices. */
    public val trustStore: FlashTrustStore

    /** Persistent user preferences and configuration data store. */
    public val settings: FlashSettingsDataStore
}

/**
 * Standard implementation of [FlashEngine] aggregating the domain subsystems.
 *
 * [onClose] runs the coordinated teardown for engines built by [Flash.create] (cancel the shared
 * scope, stop discovery/network, release the DB). It defaults to a no-op so advanced users who
 * hand-assemble the [Default*][com.transfer.flash.core.transfer.RealFlashTransferRepository] impls
 * — owning their own scopes/lifecycles — are unaffected. [close] is idempotent.
 */
public class DefaultFlashEngine(
    override val chats: FlashChatRepository,
    override val transfers: FlashTransferRepository,
    override val discovery: FlashDiscovery,
    override val network: FlashNetwork,
    override val trustStore: FlashTrustStore,
    override val settings: FlashSettingsDataStore,
    private val onClose: () -> Unit = {},
) : FlashEngine {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
