package com.transfer.flash.core.engine

import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.ptt.FlashPtt
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

    /**
     * The attached push-to-talk engine, or null when none is attached.
     *
     * PTT is **opt-in**: the module needs runtime mic permission and a foreground service only an
     * app can declare, so nothing is created until a host asks for it with [attachPtt]. Once
     * attached, this facade routes inbound `FLASH_PTT`/`FLASH_PTSS` text frames and `PTT1` binary
     * audio to it; when it is null those frames are recognized and dropped (see [Flash]).
     */
    public val ptt: FlashPtt?

    /**
     * Builds, attaches and returns a push-to-talk engine wired to this facade's identity, trust
     * store and live sessions.
     *
     * The facade supplies everything the module cannot know — this device's id and display name,
     * the trusted-peer predicate, the online member snapshot, and the two transport sinks — so a
     * `Flash.create` consumer only supplies policy:
     *
     * @param hasMicPermission read at press time; returning false refuses the press with
     *   [com.transfer.flash.core.ptt.PttPressOutcome.NO_MIC] instead of opening a recorder.
     * @param isCallActive read at press time; a live call tears PTT down and refuses new capture
     *   (mic exclusivity, ADR-032).
     * @param audioRateHz capture rate for a talk session (8 kHz on a LOW-perf device, 16 kHz
     *   otherwise). Read once per session.
     * @return the attached engine — or the already-attached one — and null when this engine was
     *   hand-assembled rather than built by [Flash.create], so no PTT wiring exists.
     */
    public fun attachPtt(
        hasMicPermission: () -> Boolean = { true },
        isCallActive: () -> Boolean = { false },
        audioRateHz: () -> Int = { 16_000 },
    ): FlashPtt?

    /**
     * Attaches a host-constructed [FlashPtt] — for a host that owns its own transport and builds
     * the engine itself. Ignored when an engine is already attached; idempotent.
     */
    public fun attachPtt(engine: FlashPtt)

    /**
     * Detaches and shuts the attached engine down. Idempotent; a no-op when nothing is attached.
     * [close] calls this, so a host only needs it to stop PTT while keeping the engine alive.
     */
    public fun detachPtt()

    /**
     * The attached voice/video calling engine (ADR-025), or null when none is attached.
     *
     * Calling is **opt-in** like PTT, but for a stronger reason: the module needs a signaling
     * channel the host already owns, runtime `RECORD_AUDIO`/`CAMERA` grants, a platform audio route
     * and a `microphone|camera` foreground service that only an app can declare. Nothing is created
     * until a host constructs its own engine (`CallCoordinator`) and hands it over with
     * [attachCalling]; `Flash.create` wires no calling of its own.
     *
     * Once attached, this facade routes inbound `FLASH_CALL` text frames to it and drives its
     * signaling-recovery window from the live sessions this engine observes; when it is null those
     * frames are recognized and dropped (see [Flash]).
     */
    public val calls: FlashCalling?

    /**
     * Attaches a host-constructed [FlashCalling] — the only way to get calling through the facade.
     *
     * Unlike [attachPtt] there is deliberately **no** lambda overload that builds the engine for
     * you: a `CallCoordinator` is assembled from the host's own `sendFrame` transport seam, scope
     * and audio policy, and it is the host that holds the permissions and the foreground service
     * that make a call audible. A factory here could only pretend, so there is none.
     *
     * Ignored when an engine is already attached; idempotent.
     */
    public fun attachCalling(engine: FlashCalling)

    /**
     * Detaches the calling engine. Idempotent; a no-op when nothing is attached.
     *
     * Detaching is **not** hanging up: [FlashCalling] exposes no shutdown, and the media, the
     * foreground service and the audio route are the host's, so ending a live call stays with the
     * host's own `hangUp()`. A detached engine simply stops receiving frames from this facade.
     * [close] calls this.
     */
    public fun detachCalling()

    /**
     * Routes one inbound text frame that was recognized as calling signaling to the attached
     * [calls] engine. Returns true when the engine consumed the frame, false when there is nothing
     * attached or the engine had nothing to do with it (an unknown/finished call id, a group query
     * with no live call). A recognized call frame must not be handed to any other handler either
     * way — see [Flash] for the ordering.
     *
     * Typed without `FlashCalling` **on purpose.** `:core:engine` depends on `:core:calling` with
     * `compileOnly`, so a consumer that never attaches calling has neither that module nor the
     * native WebRTC it re-exports on its runtime classpath. Resolving a `:core:calling` type on
     * this path — the one every inbound text frame takes — would be a `NoClassDefFoundError`.
     * Every other calling entry point below is typed the same way and answers "nothing attached"
     * instead of throwing.
     */
    public suspend fun onInboundCallText(peerDeviceId: String, text: String): Boolean

    /**
     * Notifies the attached calling engine that the signaling session to [peerDeviceId] died. Opens
     * its call-recovery window rather than ending the call, because a mesh Wi-Fi roam takes the
     * signaling session down as a matter of course (ERROR-033). A no-op when nothing is attached.
     */
    public fun onCallSignalingLost(peerDeviceId: String)

    /**
     * Notifies the attached calling engine that a signaling session to [peerDeviceId] is live —
     * called on every session this facade sees come up, not only after [onCallSignalingLost], so
     * the recovery window closes and the ICE restart offer has a channel to travel on. A no-op when
     * nothing is attached.
     */
    public fun onCallSignalingRestored(peerDeviceId: String)
}

/**
 * Standard implementation of [FlashEngine] aggregating the domain subsystems.
 *
 * [onClose] runs the coordinated teardown for engines built by [Flash.create] (cancel the shared
 * scope, stop discovery/network, release the DB). It defaults to a no-op so advanced users who
 * hand-assemble the [Default*][com.transfer.flash.core.transfer.RealFlashTransferRepository] impls
 * — owning their own scopes/lifecycles — are unaffected. [close] is idempotent.
 *
 * [pttFactory] is the wiring [Flash.create] supplies for [attachPtt]: it constructs the concrete
 * push-to-talk engine from this facade's identity/trust/session state plus the host's policy
 * lambdas. It is null for hand-assembled engines, where [attachPtt] reports that by returning null
 * and a host attaches its own engine instead.
 *
 * There is no counterpart factory for calling — see [attachCalling] — so `calls` behaves the same
 * on an engine from [Flash.create] as on a hand-assembled one: null until the host attaches its own.
 */
public class DefaultFlashEngine(
    override val chats: FlashChatRepository,
    override val transfers: FlashTransferRepository,
    override val discovery: FlashDiscovery,
    override val network: FlashNetwork,
    override val trustStore: FlashTrustStore,
    override val settings: FlashSettingsDataStore,
    private val onClose: () -> Unit = {},
    private val pttFactory: ((
        hasMicPermission: () -> Boolean,
        isCallActive: () -> Boolean,
        audioRateHz: () -> Int,
    ) -> FlashPtt)? = null,
) : FlashEngine {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pttLock = Any()
    private val callingLock = Any()

    @Volatile
    private var attachedPtt: FlashPtt? = null

    override val ptt: FlashPtt? get() = attachedPtt

    /**
     * The attached calling engine. Only the three calling entry points that take or return a
     * `FlashCalling` ([calls], [attachCalling], [detachCalling]) touch this field, so a consumer
     * that never attaches calling never resolves the type (see the `compileOnly` note in
     * `core/engine/build.gradle.kts`).
     */
    @Volatile
    private var attachedCalling: FlashCalling? = null

    /**
     * The attached engine's three seams, captured as lambdas at attach time.
     *
     * The indirection is load-bearing, not stylistic: [onInboundCallText] runs for *every* inbound
     * text frame, including on a consumer that has no `:core:calling` classes at runtime at all.
     * Reading [attachedCalling] there would resolve `FlashCalling` and throw
     * `NoClassDefFoundError`; invoking a function-typed field resolves only `kotlin.jvm.functions`
     * and returns false.
     */
    @Volatile
    private var callingInbound: (suspend (peerId: String, text: String) -> Boolean)? = null

    @Volatile
    private var callingSignalingLost: ((peerId: String) -> Unit)? = null

    @Volatile
    private var callingSignalingRestored: ((peerId: String) -> Unit)? = null

    override val calls: FlashCalling? get() = attachedCalling

    override fun attachPtt(
        hasMicPermission: () -> Boolean,
        isCallActive: () -> Boolean,
        audioRateHz: () -> Int,
    ): FlashPtt? {
        synchronized(pttLock) {
            attachedPtt?.let { return it }
            val factory = pttFactory ?: return null
            return factory(hasMicPermission, isCallActive, audioRateHz).also { attachedPtt = it }
        }
    }

    override fun attachPtt(engine: FlashPtt) {
        synchronized(pttLock) {
            if (attachedPtt == null) attachedPtt = engine
        }
    }

    override fun detachPtt() {
        val current = synchronized(pttLock) {
            val held = attachedPtt
            attachedPtt = null
            held
        }
        // Terminal by contract: the engine cancels its own scope, so a later attach needs a new one.
        runCatching { current?.shutdown() }
    }

    override fun attachCalling(engine: FlashCalling) {
        synchronized(callingLock) {
            if (attachedCalling != null) return
            attachedCalling = engine
            callingInbound = { peerId, text -> engine.onInboundText(peerId, text) }
            callingSignalingLost = { peerId -> engine.onSignalingLost(peerId) }
            callingSignalingRestored = { peerId -> engine.onSignalingRestored(peerId) }
        }
    }

    override fun detachCalling() {
        synchronized(callingLock) {
            attachedCalling = null
            callingInbound = null
            callingSignalingLost = null
            callingSignalingRestored = null
        }
        // No hangUp() here: the engine is the host's (media, foreground service, audio route), and
        // FlashCalling exposes no shutdown. See the interface KDoc — detaching is not hanging up.
    }

    override suspend fun onInboundCallText(peerDeviceId: String, text: String): Boolean =
        callingInbound?.invoke(peerDeviceId, text) ?: false

    override fun onCallSignalingLost(peerDeviceId: String) {
        callingSignalingLost?.invoke(peerDeviceId)
    }

    override fun onCallSignalingRestored(peerDeviceId: String) {
        callingSignalingRestored?.invoke(peerDeviceId)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            detachCalling()
            detachPtt()
            onClose()
        }
    }
}
