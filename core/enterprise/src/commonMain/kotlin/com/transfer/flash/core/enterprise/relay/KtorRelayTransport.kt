@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production Ktor CIO-backed implementation of [RelayTransport] for "Hub relay wire v2".
 * Runs purely on Kotlin Multiplatform across Android and Desktop JVM.
 *
 * Handshake (all within [handshakeTimeoutMs], default [HubContract.HANDSHAKE_TIMEOUT_MS] = 10 s):
 * 1. wait for the hub's `FLASH_HUB_CHALLENGE`; refuse when its `serverId` is not the pinned realm
 *    ([RelayFailureKind.SERVER_MISMATCH]);
 * 2. send `FLASH_HUB_HELLO` carrying the device certificate chain and a signature over
 *    `(serverId, serverNonce, clientNonce, deviceId, certSerial)`;
 * 3. wait for `FLASH_HUB_WELCOME` (or `FLASH_ERROR`, after which the hub closes).
 * The connection is [RelaySessionState.CONNECTED] only after WELCOME (CLI-08).
 *
 * Reading never waits for a consumer (CLI-08): the read loop answers the hub's `FLASH_PING` itself, pushes every frame
 * into non-suspending buffers and carries on. Mailbox deliveries go to a bounded channel that also buffers frames
 * received before the consumer starts collecting; if even that overflows, the frame is dropped and the session is
 * closed with [RelayFailureKind.OVERFLOW], so the hub replays everything that was not acknowledged (nothing
 * un-acknowledged is lost).
 */
@FlashInternalApi
public class KtorRelayTransport(
    private val client: HttpClient = createDefaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val handshakeTimeoutMs: Long = HubContract.HANDSHAKE_TIMEOUT_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    deliveryBuffer: Int = DELIVERY_BUFFER,
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
) : RelayTransport {

    private val _state = MutableStateFlow(RelaySessionState.DISCONNECTED)
    override val state: StateFlow<RelaySessionState> = _state.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<RelayFrame>(
        extraBufferCapacity = FRAME_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingFrames: Flow<RelayFrame> = _incomingFrames.asSharedFlow()

    private val deliveryChannel = Channel<RelayFrame.Deliver>(deliveryBuffer)
    override val deliveries: Flow<RelayFrame.Deliver> = deliveryChannel.receiveAsFlow()

    private var activeSession: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var closing: Boolean = false

    private val _failure = MutableStateFlow<RelayFailure?>(null)
    override val failure: StateFlow<RelayFailure?> = _failure.asStateFlow()

    /** Why the last handshake failed, for tests and diagnostics (never shown to the user verbatim). */
    private val _lastFailure = MutableStateFlow<String?>(null)
    public val lastFailureFlow: StateFlow<String?> = _lastFailure.asStateFlow()
    public val lastFailure: String? get() = _lastFailure.value

    private fun recordFailure(kind: RelayFailureKind, code: String? = null) {
        // The first specific failure of an attempt wins; a later generic NETWORK must not overwrite REVOKED.
        val current = _failure.value
        if (current == null || (current.kind == RelayFailureKind.NETWORK && kind != RelayFailureKind.NETWORK)) {
            _failure.value = RelayFailure(kind, code, clock())
        }
    }

    override suspend fun connect(hubUrl: String, identity: RelayIdentity): Boolean = withContext(ioDispatcher) {
        if (_state.value == RelaySessionState.CONNECTED || _state.value == RelaySessionState.CONNECTING) {
            return@withContext true
        }

        val welcomeDeferred = CompletableDeferred<Boolean>()
        val url = normalizeRelayUrl(hubUrl)
        _lastFailure.value = null
        _failure.value = null
        closing = false
        // A new attempt starts here: the REVOKED state of an earlier session no longer applies (the device may have
        // enrolled again; the hub answers a still-revoked certificate with REVOKED once more).
        _state.value = RelaySessionState.CONNECTING

        connectionJob = scope.launch {
            try {
                client.webSocket(urlString = url) {
                    activeSession = this
                    _state.value = RelaySessionState.AUTHENTICATING

                    val welcome = handshake(identity)
                    if (welcome == null) {
                        welcomeDeferred.complete(false)
                        runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Handshake failed")) }
                        return@webSocket
                    }

                    _state.value = RelaySessionState.CONNECTED
                    welcomeDeferred.complete(true)
                    _incomingFrames.tryEmit(welcome)

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val decoded = RelayFrameCodec.decode(frame.readText()) ?: continue
                            if (!dispatch(decoded, this)) break
                        }
                    }
                }
            } catch (_: CancellationException) {
                welcomeDeferred.complete(false)
            } catch (error: Throwable) {
                _lastFailure.value = _lastFailure.value ?: "transport: ${error::class.simpleName}"
                recordFailure(RelayFailureKind.NETWORK)
                welcomeDeferred.complete(false)
            } finally {
                welcomeDeferred.complete(false)
                activeSession = null
                // A session that simply ended (socket closed, no frame said why) is a network drop.
                if (!closing && _failure.value == null) recordFailure(RelayFailureKind.NETWORK)
                if (_state.value != RelaySessionState.REVOKED) {
                    _state.value = RelaySessionState.SUSPENDED
                }
            }
        }

        val result = withTimeoutOrNull(handshakeTimeoutMs + connectTimeoutMs) { welcomeDeferred.await() }
        if (result == null) {
            _lastFailure.value = _lastFailure.value ?: "handshake timeout"
            recordFailure(RelayFailureKind.HANDSHAKE_TIMEOUT)
            connectionJob?.cancel()
            return@withContext false
        }
        result
    }

    /** Runs CHALLENGE, HELLO, WELCOME. Returns the WELCOME, or null with [lastFailure] and [failure] set. */
    private suspend fun DefaultClientWebSocketSession.handshake(identity: RelayIdentity): RelayFrame.Welcome? {
        val outcome = withTimeoutOrNull(handshakeTimeoutMs) {
            val first = receiveHandshakeFrame()
            val challenge = when (first) {
                is RelayFrame.Challenge -> first
                is RelayFrame.Error -> return@withTimeoutOrNull fail("hub refused: ${first.code}", first.code)
                else -> return@withTimeoutOrNull fail("expected challenge")
            }

            val pinned = identity.expectedServerId
            if (pinned.isNotBlank() && !pinned.equals(challenge.serverId, ignoreCase = true)) {
                // This is not the organisation's hub: nothing is signed and nothing is sent (CLI-01).
                return@withTimeoutOrNull fail("serverId does not match the pinned realm", kind = RelayFailureKind.SERVER_MISMATCH)
            }

            val clientNonce = identity.nonceSource()
            val signature = Base64.encode(
                identity.signer.sign(
                    HubAuth.authSigningBytes(
                        serverId = challenge.serverId,
                        serverNonce = challenge.serverNonce,
                        clientNonce = clientNonce,
                        deviceId = identity.deviceId,
                        certSerial = identity.deviceCert.serial,
                    ),
                ),
            )
            val hello = RelayFrame.Hello(
                deviceId = identity.deviceId,
                clientNonce = clientNonce,
                name = identity.friendlyName.take(HubContract.MAX_NAME_LENGTH),
                deviceCert = identity.deviceCert,
                delegationChain = identity.delegationChain,
                signature = signature,
            )
            send(Frame.Text(RelayFrameCodec.encode(hello)))

            when (val reply = receiveHandshakeFrame()) {
                is RelayFrame.Welcome -> {
                    if (reply.principal.deviceId != identity.deviceId) {
                        fail("welcome names a different device", kind = RelayFailureKind.PROTOCOL)
                    } else {
                        reply
                    }
                }
                is RelayFrame.Error -> fail("hub refused: ${reply.code}", reply.code)
                is RelayFrame.Revoked -> fail("hub revoked this certificate", HubErrorCodes.ERR_REVOKED)
                else -> fail("expected welcome")
            }
        }
        if (outcome == null) {
            _lastFailure.value = _lastFailure.value ?: "handshake timeout"
            recordFailure(RelayFailureKind.HANDSHAKE_TIMEOUT, HubErrorCodes.ERR_HANDSHAKE_TIMEOUT)
            return null
        }
        return outcome as? RelayFrame.Welcome
    }

    /** Records a failure and returns a non-Welcome marker so the caller stops. */
    private fun fail(reason: String, code: String? = null, kind: RelayFailureKind? = null): Any {
        _lastFailure.value = reason
        val resolved = kind ?: kindForCode(code)
        if (resolved == RelayFailureKind.REVOKED) _state.value = RelaySessionState.REVOKED
        recordFailure(resolved, code)
        return FailureMarker
    }

    private object FailureMarker

    private suspend fun DefaultClientWebSocketSession.receiveHandshakeFrame(): RelayFrame? {
        while (true) {
            val frame = incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) {
                return RelayFrameCodec.decode(frame.readText(), HubContract.MAX_HANDSHAKE_FRAME_BYTES)
            }
        }
    }

    /**
     * Handles one post-WELCOME frame without ever waiting for a consumer. Returns false when the session must end
     * (the caller leaves the read loop and the socket is closed).
     */
    private suspend fun dispatch(frame: RelayFrame, session: DefaultClientWebSocketSession): Boolean {
        when (frame) {
            is RelayFrame.Ping -> {
                // Answer on the reader, so a slow consumer can never make the hub think this device is dead.
                runCatching { session.send(Frame.Text(RelayFrameCodec.encode(RelayFrame.Pong(ts = frame.ts)))) }
            }
            is RelayFrame.Revoked -> {
                _state.value = RelaySessionState.REVOKED
                recordFailure(RelayFailureKind.REVOKED, HubErrorCodes.ERR_REVOKED)
            }
            is RelayFrame.Error -> {
                val kind = kindForCode(frame.code)
                if (kind == RelayFailureKind.REVOKED) _state.value = RelaySessionState.REVOKED
                recordFailure(kind, frame.code)
            }
            is RelayFrame.Kick -> recordFailure(RelayFailureKind.KICKED)
            else -> Unit
        }
        if (frame is RelayFrame.Deliver && deliveryChannel.trySend(frame).isFailure) {
            // The consumer is far behind. Drop this frame and reconnect: it was never acknowledged, so the hub still holds it.
            recordFailure(RelayFailureKind.OVERFLOW)
            return false
        }
        _incomingFrames.tryEmit(frame)
        return frame !is RelayFrame.Revoked && frame !is RelayFrame.Kick
    }

    override suspend fun sendFrame(frame: RelayFrame): Boolean {
        val text = try {
            RelayFrameCodec.encode(frame)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return sendRaw(text)
    }

    override suspend fun sendRaw(rawText: String): Boolean = withContext(ioDispatcher) {
        val session = activeSession ?: return@withContext false
        if (!_state.value.canSend) return@withContext false

        return@withContext try {
            session.send(Frame.Text(rawText))
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun disconnect(reason: String): Unit = withContext(ioDispatcher) {
        closing = true
        try {
            activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (_: Throwable) {
            // Ignore close exceptions
        } finally {
            activeSession = null
            connectionJob?.cancel()
            connectionJob = null
            if (_state.value != RelaySessionState.REVOKED) {
                _state.value = RelaySessionState.DISCONNECTED
            }
        }
    }

    public companion object {
        /** How long the socket itself may take to open, on top of the handshake budget. */
        public const val CONNECT_TIMEOUT_MS: Long = 10_000L

        /** Frames the shared observer flow keeps for a slow observer (acks, queue states, meta); oldest are dropped first. */
        public const val FRAME_BUFFER: Int = 256

        /** Deliveries buffered for the consumer; beyond it the session is dropped and the hub replays the un-acknowledged ones. */
        public const val DELIVERY_BUFFER: Int = 1024

        /** Maps a hub `ERR_*` code to the failure kind the client acts on. */
        internal fun kindForCode(code: String?): RelayFailureKind = when (code) {
            HubErrorCodes.ERR_REVOKED -> RelayFailureKind.REVOKED
            HubErrorCodes.ERR_EXPIRED -> RelayFailureKind.CERT_EXPIRED
            HubErrorCodes.ERR_AUTH -> RelayFailureKind.AUTH_REJECTED
            HubErrorCodes.ERR_UNSUPPORTED_VERSION -> RelayFailureKind.UNSUPPORTED_VERSION
            HubErrorCodes.ERR_HANDSHAKE_TIMEOUT -> RelayFailureKind.HANDSHAKE_TIMEOUT
            else -> RelayFailureKind.PROTOCOL
        }

        public fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(WebSockets) {
                maxFrameSize = HubContract.MAX_FRAME_BYTES.toLong()
            }
        }

        /** Normalises a hub URL to `ws(s)://host:port/flash-relay`. No query string: identity is proven by the handshake. */
        public fun normalizeRelayUrl(input: String): String {
            var base = input.trim()
            if (base.startsWith("https://")) {
                base = "wss://" + base.removePrefix("https://")
            } else if (base.startsWith("http://")) {
                base = "ws://" + base.removePrefix("http://")
            } else if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
                base = "ws://$base"
            }

            val urlWithoutQuery = if (base.contains("?")) base.substringBefore("?") else base
            return if (!urlWithoutQuery.endsWith(HubContract.PATH_RELAY)) {
                if (urlWithoutQuery.endsWith("/")) "${urlWithoutQuery.removeSuffix("/")}${HubContract.PATH_RELAY}"
                else "$urlWithoutQuery${HubContract.PATH_RELAY}"
            } else {
                urlWithoutQuery
            }
        }
    }
}
