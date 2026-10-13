@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Enterprise Outbound Relay Client.
 *
 * Coordinates persistent bidirectional WebSocket communication to an enterprise hub:
 * - Automatic connection and the authenticated handshake of the transport;
 * - Reconnection with jittered exponential backoff (CLI-13): the delay grows on every failed OR short-lived
 *   connection and returns to its start only after a connection stayed up for [HEALTHY_AFTER_MS], so a hub that
 *   accepts and immediately drops cannot be hammered at the minimum interval;
 * - Application-level heartbeat watchdog (ping every 15 s, any inbound frame counts as proof of life);
 * - [FlashEnvelope] send and receive routing;
 * - Hub receipts (`FLASH_ACK`, `FLASH_QUEUE_STATE`) and the receiver's own `FLASH_RECV_ACK` ([acknowledgeReceipt]);
 * - Ephemeral typing/presence with wire padding (`P-REF-1`, `P-REF-4`).
 *
 * Recoverable: [start] after [stop] works, a changed [hubUrl] restarts the session cleanly, and a REVOKED answer stops
 * the retrying but not the object: it resumes when the identity provider hands out a different certificate (the
 * device enrolled again) or on [retryNow].
 */
@FlashInternalApi
public class FlashRelayClient(
    public val deviceId: String,
    public val friendlyName: String,
    hubUrl: String,
    /**
     * Supplies the identity to authenticate with, read on every (re)connect so a renewed certificate is picked up.
     * Null means "not enrolled": the client idles instead of dialling.
     */
    public val identityProvider: () -> RelayIdentity? = { null },
    public val transport: RelayTransport = KtorRelayTransport(),
    public val timeSource: FlashTimeSource = SystemTimeSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    /** Returns a value in `0..maxInclusive`; injectable so tests are deterministic. */
    private val jitter: (Long) -> Long = { max -> if (max <= 0L) 0L else Random.nextLong(max + 1) },
) {

    private val hubUrlState = MutableStateFlow(hubUrl)

    /**
     * The hub to connect to. Changing it while the client runs drops the current session and connects to the new
     * address straight away (CLI-07); the value is read atomically on every attempt.
     */
    public var hubUrl: String
        get() = hubUrlState.value
        set(value) {
            val old = hubUrlState.getAndUpdateCompat(value)
            if (old != value && running) {
                scope.launch { runCatching { transport.disconnect("Hub address changed") } }
                wake.trySend(Unit)
            }
        }

    public val state: StateFlow<RelaySessionState> = transport.state

    /** The latest failure of this client's connection (transport failures and keep-alive timeouts); null while healthy. */
    private val _failure = MutableStateFlow<RelayFailure?>(null)
    public val failure: StateFlow<RelayFailure?> = _failure.asStateFlow()

    /** When the next automatic attempt happens (epoch ms), or null when none is scheduled. */
    private val _nextRetryAtMs = MutableStateFlow<Long?>(null)
    public val nextRetryAtMs: StateFlow<Long?> = _nextRetryAtMs.asStateFlow()

    /** When this client last completed a handshake (epoch ms), or null since this object was created. */
    private val _lastConnectedAtMs = MutableStateFlow<Long?>(null)
    public val lastConnectedAtMs: StateFlow<Long?> = _lastConnectedAtMs.asStateFlow()

    /**
     * Messages delivered by the hub, raw (with the group, replay flag and hub-stamped sender). Reliable and meant for
     * ONE collector (the chat repository): frames that arrive before it collects are buffered. The consumer MUST call
     * [acknowledgeReceipt] once it has durably stored a message, otherwise the hub keeps it in the mailbox and
     * redelivers it on the next connect (REL-02).
     */
    public val incomingDeliveries: Flow<RelayFrame.Deliver> = transport.deliveries

    /** [incomingDeliveries] as envelopes. The sender is the hub-stamped authenticated device id. Same single-collector rule. */
    public val incomingEnvelopes: Flow<FlashEnvelope> = transport.deliveries.map { it.toEnvelope() }

    /** The hub's receipt for each message this device sent (delivered / queued / refused). */
    public val deliveryAcks: Flow<RelayFrame.Ack> = transport.incomingFrames
        .filterIsInstance<RelayFrame.Ack>()

    /** Later state changes of messages the hub reported as queued. */
    public val queueStates: Flow<RelayFrame.QueueState> = transport.incomingFrames
        .filterIsInstance<RelayFrame.QueueState>()

    public val metaEvents: Flow<RelayFrame.Meta> = transport.incomingFrames
        .filterIsInstance<RelayFrame.Meta>()

    private val lifecycleLock = Any()

    @kotlin.concurrent.Volatile
    private var running = false
    private var clientJob = SupervisorJob()
    private var clientScope = CoroutineScope(clientJob + ioDispatcher)
    private var loopJob: Job? = null

    @kotlin.concurrent.Volatile
    private var lastInboundMs: Long = 0L
    private val sendLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Set when the hub revoked the certificate with this serial: no automatic retry until another serial or [retryNow]. */
    private var revokedSerial: Long? = null

    /**
     * Starts the client and begins connecting to the hub. Safe to call again after [stop] and when already started.
     */
    public fun start(): Unit = withRelayLock(lifecycleLock) { startLocked() }

    // The check-then-set below must be atomic: `EnterpriseHub` calls start() from its credentials collector AND from
    // enroll() within microseconds of each other, and two connection loops for one device displace each other on the
    // hub for ever ("superseded" kick loop, found by the client/server contract test, ADR-124).
    private fun startLocked() {
        if (running && loopJob?.isActive == true) return
        running = true
        if (clientJob.isCancelled) {
            clientJob = SupervisorJob()
            clientScope = CoroutineScope(clientJob + ioDispatcher)
        }
        lastInboundMs = timeSource.nowMs()

        // Liveness: any inbound frame proves the hub is there. Started BEFORE the first connect so the WELCOME is seen.
        clientScope.launch {
            transport.incomingFrames.collect { lastInboundMs = timeSource.nowMs() }
        }

        startWatchdog()
        loopJob = startConnectionLoop()
    }

    /**
     * Stops the client and closes any active connection. [start] works again afterwards.
     */
    public fun stop(): Unit = withRelayLock(lifecycleLock) { stopLocked() }

    private fun stopLocked() {
        running = false
        clientJob.cancelChildren()
        loopJob = null
        _nextRetryAtMs.value = null

        scope.launch {
            try {
                transport.disconnect("Client stopped")
            } catch (_: Throwable) {
                // Ignore disconnect error
            }
        }
    }

    /**
     * Connects NOW instead of waiting for the backoff, and also tries again after a REVOKED answer (the hub is asked,
     * it will say so again if the certificate is still revoked). Does nothing when the client is not started.
     */
    public fun retryNow() {
        revokedSerial = null
        if (_failure.value?.kind == RelayFailureKind.REVOKED) _failure.value = null
        wake.trySend(Unit)
    }

    /**
     * Sends an encrypted [envelope] to [recipientDeviceId] via the relay hub.
     *
     * @return true if successfully delivered to the hub WebSocket, false otherwise.
     */
    public suspend fun sendEnvelope(recipientDeviceId: String, envelope: FlashEnvelope): Boolean {
        if (!state.value.canSend) return false
        val frame = RelayFrame.Send.fromEnvelope(recipientDeviceId, envelope)
        return sendLock.withLock {
            transport.sendFrame(frame)
        }
    }

    /**
     * Tells the hub this device has durably stored message [messageId] (`FLASH_RECV_ACK`). Only this lets the hub
     * delete its mailbox copy. Safe to call more than once. Returns false when not connected: the hub will simply
     * redeliver after the next handshake and the consumer must treat the repeat as a duplicate.
     */
    public suspend fun acknowledgeReceipt(messageId: String, from: String? = null): Boolean {
        if (!state.value.canSend) return false
        return sendLock.withLock {
            transport.sendFrame(RelayFrame.RecvAck(msgId = messageId, from = from))
        }
    }

    /**
     * Sends an ephemeral typing indicator with `P-REF-1` wire padding.
     */
    public suspend fun sendTyping(conversationId: String, to: String? = null): Boolean {
        if (!state.value.canSend) return false
        val meta = RelayFrame.Meta(
            action = RelayFrame.Meta.ACTION_TYPING,
            conversationId = conversationId,
            to = to,
            ts = timeSource.nowMs(),
        )
        return sendLock.withLock {
            transport.sendFrame(meta)
        }
    }

    /**
     * Sends an ephemeral presence status with `P-REF-1` wire padding.
     */
    public suspend fun sendPresence(action: String): Boolean {
        if (!state.value.canSend) return false
        val meta = RelayFrame.Meta(
            action = action,
            conversationId = "",
            ts = timeSource.nowMs(),
        )
        return sendLock.withLock {
            transport.sendFrame(meta)
        }
    }

    private suspend fun waitForWake(ms: Long) {
        withTimeoutOrNull(ms.coerceAtLeast(1L)) { wake.receive() }
    }

    private fun startConnectionLoop(): Job = clientScope.launch {
        var backoffMs = INITIAL_BACKOFF_MS
        while (isActive && running) {
            val url = hubUrl
            val identity = identityProvider()
            if (url.isBlank() || identity == null) {
                _nextRetryAtMs.value = null
                waitForWake(IDLE_POLL_MS)
                continue
            }
            if (revokedSerial != null && revokedSerial == identity.deviceCert.serial) {
                // Revoked: terminal for THIS certificate. Wait for a new one (re-enrolment) or an explicit retry.
                waitForWake(IDLE_POLL_MS)
                continue
            }
            if (revokedSerial != null) {
                // A different certificate (the device enrolled again): the old REVOKED answer no longer applies.
                revokedSerial = null
                if (_failure.value?.kind == RelayFailureKind.REVOKED) _failure.value = null
            }
            _nextRetryAtMs.value = null

            val connected = transport.connect(hubUrl = url, identity = identity)
            val connectedAt = timeSource.nowMs()
            if (connected) {
                lastInboundMs = connectedAt
                _lastConnectedAtMs.value = connectedAt
                _failure.value = null
                // Stay here for as long as the session lives; the watchdog or the hub ends it.
                state.first { it != RelaySessionState.CONNECTED }
                if (timeSource.nowMs() - connectedAt >= HEALTHY_AFTER_MS) backoffMs = INITIAL_BACKOFF_MS
            }

            if (!running) break
            val transportFailure = transport.failure.value
            if (transportFailure != null && (_failure.value == null || _failure.value?.kind != RelayFailureKind.KEEPALIVE_TIMEOUT)) {
                _failure.value = transportFailure
            }
            if (state.value == RelaySessionState.REVOKED || transportFailure?.kind == RelayFailureKind.REVOKED) {
                revokedSerial = identity.deviceCert.serial
                _failure.value = transportFailure ?: RelayFailure(RelayFailureKind.REVOKED, atMs = timeSource.nowMs())
                continue
            }
            if (transportFailure?.kind == RelayFailureKind.SERVER_MISMATCH) backoffMs = MAX_BACKOFF_MS

            val wait = backoffWithJitter(backoffMs)
            _nextRetryAtMs.value = timeSource.nowMs() + wait
            waitForWake(wait)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Equal-jitter backoff: half the base fixed plus a random share of the other half, so clients spread out. */
    private fun backoffWithJitter(baseMs: Long): Long = baseMs / 2 + jitter(baseMs / 2)

    private fun startWatchdog() {
        clientScope.launch {
            while (isActive && running) {
                delay(PING_INTERVAL_MS)
                if (state.value == RelaySessionState.CONNECTED) {
                    val now = timeSource.nowMs()
                    // Check if the hub has been silent for too long
                    if (now - lastInboundMs > PONG_TIMEOUT_MS) {
                        // Hub unresponsive; force disconnect to trigger reconnection loop
                        _failure.value = RelayFailure(RelayFailureKind.KEEPALIVE_TIMEOUT, atMs = now)
                        transport.disconnect("Keepalive timeout")
                        continue
                    }

                    // Send periodic ping
                    val ping = RelayFrame.Ping(ts = now)
                    transport.sendFrame(ping)
                }
            }
        }
    }

    public companion object {
        public const val PING_INTERVAL_MS: Long = 15_000L
        public const val PONG_TIMEOUT_MS: Long = 30_000L
        public const val INITIAL_BACKOFF_MS: Long = 1_000L
        public const val MAX_BACKOFF_MS: Long = 60_000L

        /** A connection must stay up this long before the backoff returns to [INITIAL_BACKOFF_MS] (CLI-13). */
        public const val HEALTHY_AFTER_MS: Long = 30_000L

        /** How often an idle client (not enrolled, no address, or revoked) looks again. */
        public const val IDLE_POLL_MS: Long = 5_000L
    }
}

/** `MutableStateFlow.getAndUpdate` without importing the experimental-looking helper everywhere. */
private fun MutableStateFlow<String>.getAndUpdateCompat(value: String): String {
    val old = this.value
    this.value = value
    return old
}
