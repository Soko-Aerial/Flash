package com.transfer.flash.core.engine

import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.swarm.api.FlashSwarm
import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.transfer.FlashTransferRepository

/**
 * Android specialization of [DefaultFlashEngine] holding Android's [FlashSettingsDataStore].
 *
 * Also a [java.io.Closeable], as the pre-1.2 Android `FlashEngine` was: [FlashEngine] itself can only
 * extend the common [AutoCloseable] now, so code that stores the concrete engine in a
 * `Closeable` keeps working, while one that stored a bare `FlashEngine` there must use the concrete type.
 */
public class AndroidFlashEngine(
    chats: FlashChatRepository,
    transfers: FlashTransferRepository,
    discovery: FlashDiscovery,
    network: FlashNetwork,
    trustStore: FlashTrustStore,
    public val settings: FlashSettingsDataStore,
    onClose: () -> Unit = {},
    pttFactory: ((
        hasMicPermission: () -> Boolean,
        isCallActive: () -> Boolean,
        audioRateHz: () -> Int,
    ) -> FlashPtt)? = null,
    onUpdateFriendlyName: ((String) -> Boolean)? = null,
    swarmFactory: ((FlashSwarmConfig) -> FlashSwarm)? = null,
) : DefaultFlashEngine(
    chats = chats,
    transfers = transfers,
    discovery = discovery,
    network = network,
    trustStore = trustStore,
    onClose = onClose,
    pttFactory = pttFactory,
    onUpdateFriendlyName = onUpdateFriendlyName,
    swarmFactory = swarmFactory,
), java.io.Closeable

/**
 * Android compatibility factory preserving the signature that accepts [FlashSettingsDataStore].
 */
public fun DefaultFlashEngine(
    chats: FlashChatRepository,
    transfers: FlashTransferRepository,
    discovery: FlashDiscovery,
    network: FlashNetwork,
    trustStore: FlashTrustStore,
    settings: FlashSettingsDataStore,
    onClose: () -> Unit = {},
    pttFactory: ((
        hasMicPermission: () -> Boolean,
        isCallActive: () -> Boolean,
        audioRateHz: () -> Int,
    ) -> FlashPtt)? = null,
    onUpdateFriendlyName: ((String) -> Boolean)? = null,
    swarmFactory: ((FlashSwarmConfig) -> FlashSwarm)? = null,
): AndroidFlashEngine = AndroidFlashEngine(
    chats = chats,
    transfers = transfers,
    discovery = discovery,
    network = network,
    trustStore = trustStore,
    settings = settings,
    onClose = onClose,
    pttFactory = pttFactory,
    onUpdateFriendlyName = onUpdateFriendlyName,
    swarmFactory = swarmFactory,
)

/**
 * The Android settings store of this engine, source-compatible with the former `FlashEngine.settings`
 * interface member (which no longer exists because the common [FlashEngine] cannot name
 * [FlashSettingsDataStore]). Non-null as before: every engine [Flash.create] and the [DefaultFlashEngine]
 * factory return is an [AndroidFlashEngine], whose own `settings` member wins over this extension.
 *
 * Limitation: a [FlashEngine] that is not an [AndroidFlashEngine] (a test double, or the desktop
 * engine) has no Android settings store, and reading this throws [IllegalStateException]; use
 * [settingsOrNull] to probe.
 */
public val FlashEngine.settings: FlashSettingsDataStore
    get() = settingsOrNull
        ?: throw IllegalStateException("${this::class.simpleName} has no Android FlashSettingsDataStore")

/** The Android settings store, or null for an engine that is not an [AndroidFlashEngine]. */
public val FlashEngine.settingsOrNull: FlashSettingsDataStore?
    get() = (this as? AndroidFlashEngine)?.settings
