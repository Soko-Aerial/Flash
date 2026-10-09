package com.transfer.flash.core.engine

import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.engine.concurrent.PlatformLock
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.swarm.api.FlashSwarm
import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.transfer.FlashTransferRepository
import kotlin.concurrent.Volatile

/**
 * Top-level facade contract unifying all Flash core subsystems across platforms (C7.0 / ADR-010).
 * Exposes clean domain repository and service accessors for application ViewModels and UI layers.
 *
 * Implements [AutoCloseable]: an engine built by platform factories ([Flash.create] on Android,
 * [FlashDesktop.create] on JVM) owns shared coroutine scopes and transport resources, so [close]
 * must be called to cancel all coroutines and release those resources. [close] is idempotent.
 */
public interface FlashEngine : AutoCloseable {
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

    /**
     * The attached push-to-talk engine, or null when none is attached.
     *
     * PTT is **opt-in**: the module requires platform audio capture and policy that a host
     * configures via [attachPtt].
     */
    public val ptt: FlashPtt?

    /**
     * Builds, attaches and returns a push-to-talk engine wired to this facade's identity, trust
     * store and live sessions.
     */
    public fun attachPtt(
        hasMicPermission: () -> Boolean = { true },
        isCallActive: () -> Boolean = { false },
        audioRateHz: () -> Int = { 16_000 },
    ): FlashPtt?

    /**
     * Attaches a host-constructed [FlashPtt]. Ignored when an engine is already attached; idempotent.
     */
    public fun attachPtt(engine: FlashPtt)

    /**
     * Detaches and shuts the attached PTT engine down. Idempotent; a no-op when nothing is attached.
     */
    public fun detachPtt()

    /**
     * Updates the local device friendly name across the network transport, chat repository, and discovery.
     * Returns false when the name is blank or the operation fails.
     */
    public fun updateFriendlyName(name: String): Boolean = false

    /**
     * The attached voice/video calling engine (ADR-025), or null when none is attached.
     */
    public val calls: FlashCalling?

    /**
     * Attaches a host-constructed [FlashCalling]. Ignored when an engine is already attached; idempotent.
     */
    public fun attachCalling(engine: FlashCalling)

    /**
     * Detaches the calling engine. Idempotent; a no-op when nothing is attached.
     */
    public fun detachCalling()

    /**
     * Routes one inbound text frame that was recognized as calling signaling to the attached [calls] engine.
     * Returns true when the engine consumed the frame, false when there is nothing attached or the engine
     * had nothing to do with it.
     */
    public suspend fun onInboundCallText(peerDeviceId: String, text: String): Boolean

    /**
     * Notifies the attached calling engine that the signaling session to [peerDeviceId] died.
     */
    public fun onCallSignalingLost(peerDeviceId: String)

    /**
     * Notifies the attached calling engine that a signaling session to [peerDeviceId] is live.
     */
    public fun onCallSignalingRestored(peerDeviceId: String)

    /**
     * The devices the attached engine's call in progress needs a session with (the peer of a 1:1 call,
     * the participants of a group call), or empty.
     */
    public fun busyCallPeerIds(): Set<String> = emptySet()

    /**
     * The attached group swarm file transfer engine (SW-8), or null when none is attached.
     */
    public val swarm: FlashSwarm?

    /**
     * Builds, attaches and returns a swarm engine wired to this facade.
     */
    public fun attachSwarm(
        config: FlashSwarmConfig = FlashSwarmConfig(),
    ): FlashSwarm?

    /**
     * Attaches a host-constructed [FlashSwarm].
     */
    public fun attachSwarm(swarm: FlashSwarm)

    /**
     * Detaches the swarm engine. Idempotent; a no-op when nothing is attached.
     */
    public fun detachSwarm()
}

/**
 * Standard cross-platform implementation of [FlashEngine] aggregating the domain subsystems.
 */
public open class DefaultFlashEngine(
    override val chats: FlashChatRepository,
    override val transfers: FlashTransferRepository,
    override val discovery: FlashDiscovery,
    override val network: FlashNetwork,
    override val trustStore: FlashTrustStore,
    private val onClose: () -> Unit = {},
    private val pttFactory: ((
        hasMicPermission: () -> Boolean,
        isCallActive: () -> Boolean,
        audioRateHz: () -> Int,
    ) -> FlashPtt)? = null,
    private val onUpdateFriendlyName: ((String) -> Boolean)? = null,
    private val swarmFactory: ((FlashSwarmConfig) -> FlashSwarm)? = null,
) : FlashEngine {
    private val closeLock = PlatformLock()
    private var isClosed = false

    private val pttLock = PlatformLock()
    private val callingLock = PlatformLock()
    private val swarmLock = PlatformLock()

    @Volatile
    private var attachedSwarm: FlashSwarm? = null

    override val swarm: FlashSwarm? get() = attachedSwarm

    override fun attachSwarm(config: FlashSwarmConfig): FlashSwarm? {
        return swarmLock.withLock {
            attachedSwarm?.let { return@withLock it }
            val factory = swarmFactory ?: return@withLock null
            factory(config).also { attachedSwarm = it }
        }
    }

    override fun attachSwarm(swarm: FlashSwarm) {
        swarmLock.withLock {
            if (attachedSwarm == null) attachedSwarm = swarm
        }
    }

    override fun detachSwarm() {
        val detached = swarmLock.withLock {
            attachedSwarm.also { attachedSwarm = null }
        }
        // A swarm that owns background work (SwarmHostBinding) exposes it as AutoCloseable so that
        // detaching actually stops its collectors and driver instead of only dropping the reference.
        runCatching { (detached as? AutoCloseable)?.close() }
    }

    @Volatile
    private var attachedPtt: FlashPtt? = null

    override val ptt: FlashPtt? get() = attachedPtt

    @Volatile
    private var attachedCalling: FlashCalling? = null

    @Volatile
    private var callingInbound: (suspend (peerId: String, text: String) -> Boolean)? = null

    @Volatile
    private var callingSignalingLost: ((peerId: String) -> Unit)? = null

    @Volatile
    private var callingSignalingRestored: ((peerId: String) -> Unit)? = null

    @Volatile
    private var callingBusyPeers: (() -> Set<String>)? = null

    override fun busyCallPeerIds(): Set<String> = callingBusyPeers?.invoke().orEmpty()

    override val calls: FlashCalling? get() = attachedCalling

    override fun attachPtt(
        hasMicPermission: () -> Boolean,
        isCallActive: () -> Boolean,
        audioRateHz: () -> Int,
    ): FlashPtt? {
        return pttLock.withLock {
            attachedPtt?.let { return@withLock it }
            val factory = pttFactory ?: return@withLock null
            factory(hasMicPermission, isCallActive, audioRateHz).also { attachedPtt = it }
        }
    }

    override fun attachPtt(engine: FlashPtt) {
        pttLock.withLock {
            if (attachedPtt == null) attachedPtt = engine
        }
    }

    override fun detachPtt() {
        val current = pttLock.withLock {
            val held = attachedPtt
            attachedPtt = null
            held
        }
        runCatching { current?.shutdown() }
    }

    override fun attachCalling(engine: FlashCalling) {
        callingLock.withLock {
            if (attachedCalling != null) return@withLock
            attachedCalling = engine
            callingInbound = { peerId, text -> engine.onInboundText(peerId, text) }
            callingSignalingLost = { peerId -> engine.onSignalingLost(peerId) }
            callingSignalingRestored = { peerId -> engine.onSignalingRestored(peerId) }
            callingBusyPeers = { engine.activeCall.value?.busyPeerIds.orEmpty() }
        }
    }

    override fun detachCalling() {
        callingLock.withLock {
            attachedCalling = null
            callingInbound = null
            callingSignalingLost = null
            callingSignalingRestored = null
            callingBusyPeers = null
        }
    }

    override suspend fun onInboundCallText(peerDeviceId: String, text: String): Boolean =
        callingInbound?.invoke(peerDeviceId, text) ?: false

    override fun onCallSignalingLost(peerDeviceId: String) {
        callingSignalingLost?.invoke(peerDeviceId)
    }

    override fun onCallSignalingRestored(peerDeviceId: String) {
        callingSignalingRestored?.invoke(peerDeviceId)
    }

    override fun updateFriendlyName(name: String): Boolean =
        onUpdateFriendlyName?.invoke(name) ?: false

    override fun close() {
        val shouldClose = closeLock.withLock {
            if (isClosed) false else {
                isClosed = true
                true
            }
        }
        if (shouldClose) {
            detachSwarm()
            detachCalling()
            detachPtt()
            onClose()
        }
    }
}
