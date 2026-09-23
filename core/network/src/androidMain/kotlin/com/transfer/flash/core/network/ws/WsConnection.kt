@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One live WebSocket connection after the HTTP upgrade handshake completed.
 *
 * Reads run on a Dispatchers.IO coroutine; writes are serialized through a lock
 * so file chunks and control frames from different threads can never interleave.
 * Listener callbacks fire on the read thread — implementations must be thread-safe.
 *
 * ## Liveness (ADR-016 keepalive)
 *
 * A periodic WebSocket PING is sent every [pingIntervalMs] and a watchdog closes the connection
 * once no inbound frame of any kind has arrived for [livenessTimeoutMs] — including half-open TCP
 * after NAT/idle drops on mobile hotspots, where a blocked read never faults. Live peers answer
 * PINGs with PONGs, which keeps healthy idle connections fresh.
 *
 * The watchdog is the *only* liveness authority. A read timeout at a frame boundary is not a
 * failure ([WebSocketCodec.IdleTimeout]); the read loop retries and lets the watchdog decide,
 * because a peer frozen by Doze is silent, not dead.
 *
 * ## Suspension tolerance (ERROR-025)
 *
 * Both the ping loop and the silence measurement live in a process Android freezes at will
 * (screen off, app backgrounded, Doze). A `delay()` that should fire in 10 s can return 60 s
 * later, and judging silence on that first resumed tick condemns a perfectly healthy session:
 * `now - lastInboundAtMs` measures how long the *process* was asleep, not how long the *peer* was
 * quiet. Both ends did this simultaneously, so a backgrounded phone showed up as "offline, then
 * online again" on its peer, and every message and call frame sent in that window failed with no
 * session to carry it.
 *
 * The loop therefore times its own ticks with the same clock: a gap far longer than
 * [pingIntervalMs] means the scheduler did not run, so the silence window is unmeasurable. It is
 * forgiven once, a PING goes out, and the verdict waits for a tick that actually ran on time. A
 * genuinely dead link still dies within ~[livenessTimeoutMs] of the process waking up.
 *
 * Forgiveness is per stall *episode*, not per tick (ERROR-031): while a stall probe is outstanding
 * a further late tick cannot rebase the window again, so a device that throttles this coroutine
 * indefinitely can no longer keep a dead session alive indefinitely. Such a verdict is re-checked
 * after a short awake delay before it closes anything.
 */
public class WsConnection(
    private val socket: Socket,
    private val maskOutboundFrames: Boolean,
    public val remoteLabel: String,
    private val listener: Listener,
    private val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val livenessTimeoutMs: Long = DEFAULT_LIVENESS_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * SPKI fingerprint of the peer's TLS leaf, recorded ONLY when the dial could not name the peer
     * up front (manual IP) and pin evaluation was therefore deferred (ADR-040). Non-null means the
     * caller still owes `FlashPinVerifier.isPinned` against the id from `FLASH_WS_HELLO`.
     */
    public val deferredPeerLeafFingerprintHex: String? = null,
) {
    public interface Listener {
        public fun onTextMessage(connection: WsConnection, text: String)
        public fun onBinaryMessage(connection: WsConnection, data: ByteArray)
        public fun onConnectionClosed(connection: WsConnection, reason: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    /**
     * Liveness bookkeeping. Owns the "last inbound frame" timestamp the watchdog judges on, and
     * the suspension detection that stops it judging across a freeze — see [WsKeepalive].
     */
    private val keepalive = WsKeepalive(
        pingIntervalMs = pingIntervalMs,
        livenessTimeoutMs = livenessTimeoutMs,
        startedAtMs = nowMs(),
    )

    public val isOpen: Boolean
        get() = !closed.get()

    /**
     * Wall clock ([nowMs]) of the most recent inbound frame of any kind, including keepalive PONGs.
     *
     * Exposed so callers can ask whether a session is merely *present* or actually carrying traffic.
     * Every recovery path in [WsFlashNetwork] used to gate on map presence alone, which let a socket
     * that had died without the watchdog noticing block its own replacement (ERROR-031).
     */
    public val lastInboundAtMs: Long
        get() = keepalive.lastInboundAtMs

    public fun start() {
        runCatching { socket.soTimeout = readTimeoutMs }
        // TCP keepalive gives the kernel a second, independent path to notice a dead peer.
        runCatching { socket.keepAlive = true }
        keepalive.onInbound(nowMs())
        scope.launch { readLoop() }
        scope.launch {
            while (scope.isActive) {
                kotlinx.coroutines.delay(pingIntervalMs)
                if (closed.get()) break
                var verdict = keepalive.onTick(nowMs())
                if (verdict is WsKeepalive.Verdict.Close && verdict.needsConfirmation) {
                    // Rendered by a tick that had itself just resumed from a freeze. The read loop
                    // resumes on its own dispatcher, so a PONG already in the socket buffer may not
                    // be stamped yet — stay awake briefly and ask again before condemning the peer.
                    kotlinx.coroutines.delay(STALL_CONFIRM_DELAY_MS)
                    if (closed.get()) break
                    verdict = keepalive.confirmClose(nowMs())
                }
                when (verdict) {
                    is WsKeepalive.Verdict.Close -> {
                        close("${verdict.reason} for ${verdict.silentForMs}ms")
                        return@launch
                    }
                    WsKeepalive.Verdict.Ping -> send(WebSocketCodec.OPCODE_PING, ByteArray(0))
                }
            }
        }
    }

    public fun sendText(text: String): Boolean {
        return send(WebSocketCodec.OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /** Fire-and-forget text send that is safe to call from any thread (including main). */
    public fun sendTextAsync(text: String) {
        scope.launch { sendText(text) }
    }

    public fun sendBinary(data: ByteArray): Boolean {
        return send(WebSocketCodec.OPCODE_BINARY, data)
    }

    /**
     * [sendBinary] for callers that transfer ownership of [data]: on a masking (client) connection
     * the array is masked in place and must not be retained or reused afterwards. Intended for the
     * transfer hot loop, whose frames are single-use `ChunkFrame.serialize` output — copying each
     * 64 KB payload again under the write lock was pure churn (EXP-001).
     */
    public fun sendBinaryConsuming(data: ByteArray): Boolean {
        return send(WebSocketCodec.OPCODE_BINARY, data, consumePayload = true)
    }

    /**
     * Sends one keepalive PING out of band, off the watchdog's own schedule (ERROR-033).
     *
     * Used to *interrogate* a session rather than to keep it alive: after a suspected Wi-Fi roam,
     * a session that is still carried by the new association answers with a PONG within a
     * round-trip, and one that is not answers with nothing. The reply lands on [lastInboundAtMs]
     * like any other inbound frame, so the caller compares that stamp before and after.
     *
     * Deliberately does not touch the watchdog's tick bookkeeping: an out-of-band probe must not
     * be able to rebase the liveness window (that is precisely the stall-forgiveness abuse
     * ERROR-031 closed). Fire-and-forget on the connection scope, so safe from any thread.
     *
     * @return false when the connection is already closed and nothing was sent.
     */
    public fun sendPing(): Boolean {
        if (closed.get()) return false
        scope.launch { send(WebSocketCodec.OPCODE_PING, ByteArray(0)) }
        return true
    }

    /**
     * Closes the connection. The close frame and socket close run on the connection scope
     * so this is safe to call from any thread (StrictMode forbids network writes on main).
     * The listener callback fires immediately; the peer observes the close frame or EOF.
     */
    public fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_CLOSE, ByteArray(0), maskOutboundFrames)
                }
            }
            runCatching { socket.close() }
            scope.cancel()
        }
        listener.onConnectionClosed(this, reason)
    }

    private fun send(opcode: Int, payload: ByteArray, consumePayload: Boolean = false): Boolean {
        if (closed.get()) return false
        return runCatching {
            synchronized(writeLock) {
                WebSocketCodec.writeFrame(output, opcode, payload, maskOutboundFrames, maskPayloadInPlace = consumePayload)
            }
        }.onFailure { error ->
            if (!closed.get()) {
                FlashLog.w(TAG, "WS write failed remote=$remoteLabel", error)
                close("Write failed")
            }
        }.isSuccess
    }

    private suspend fun readLoop() {
        try {
            while (!closed.get() && scope.isActive) {
                val message = try {
                    WebSocketCodec.readMessage(input)
                } catch (idle: WebSocketCodec.IdleTimeout) {
                    // The socket read timeout expired at a frame boundary: no frame arrived, but
                    // the socket is still valid and the stream is still aligned. Silence is the
                    // watchdog's call, not the read loop's — a peer frozen by Doze for longer than
                    // readTimeoutMs is quiet, not dead, and closing here is what used to drop a
                    // healthy session the moment either phone went to sleep (ERROR-025).
                    continue
                }
                // Any inbound frame proves the peer is alive — refresh the watchdog timestamp.
                keepalive.onInbound(nowMs())
                when (message) {
                    is WebSocketCodec.Message.Text -> listener.onTextMessage(this, message.text)
                    is WebSocketCodec.Message.Binary -> listener.onBinaryMessage(this, message.data)
                    is WebSocketCodec.Message.Ping -> send(WebSocketCodec.OPCODE_PONG, message.payload, consumePayload = true)
                    is WebSocketCodec.Message.Pong -> Unit
                    is WebSocketCodec.Message.Close -> {
                        close(if (message.reason.isNotBlank()) message.reason else "Peer closed connection (${message.code})")
                        return
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                FlashLog.i(TAG, "WS read loop ended remote=$remoteLabel (${error.message ?: error::class.java.simpleName})")
                close("Connection error: ${error.message ?: error::class.java.simpleName}")
            }
        } finally {
            if (!closed.get()) {
                close("EOF")
            }
        }
    }

    public companion object {
        private const val TAG = "WS"

        /**
         * Idle connections are refreshed 3x per read-timeout window (ping -> pong traffic).
         *
         * Hoisted to commonMain [WsKeepalive] in Phase 15-2 (the values are wire-behaviour
         * constants the pure [WsKeepaliveTiming] must reference); these aliases keep the
         * declaration site's FQN and visibility unchanged for existing callers.
         */
        public const val DEFAULT_PING_INTERVAL_MS: Long = WsKeepalive.DEFAULT_PING_INTERVAL_MS

        /**
         * Socket read timeout. NOT a liveness rule — its only job is to keep the read loop from
         * blocking forever so it can re-check `closed`/`scope.isActive` and exit promptly. Expiry
         * at a frame boundary surfaces as [WebSocketCodec.IdleTimeout] and the loop reads again.
         */
        public const val DEFAULT_READ_TIMEOUT_MS: Int = 30_000

        /**
         * Watchdog window: if no inbound frame arrives across ticks that ran on schedule, the peer
         * is pruned. Sized to ~2.5 ping intervals so a live peer that misses one PONG is forgiven,
         * but a dead one is dropped in ~25s regardless of where the read loop is parked. Time the
         * process spent frozen does not count against it (see [WsKeepalive]).
         *
         * Hoisted to commonMain [WsKeepalive] in Phase 15-2; alias keeps the FQN unchanged.
         */
        public const val DEFAULT_LIVENESS_TIMEOUT_MS: Long = WsKeepalive.DEFAULT_LIVENESS_TIMEOUT_MS

        /**
         * How long the keepalive loop stays awake before acting on a close verdict that a stalled
         * tick produced. Long enough for the read loop to be dispatched and drain a PONG that was
         * already in the socket buffer; short enough that a genuinely dead session still goes in
         * the same tick (see [WsKeepalive.confirmClose]).
         */
        private const val STALL_CONFIRM_DELAY_MS: Long = 2_000L
    }
}
