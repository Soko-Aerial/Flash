package com.transfer.flash.server.relay

import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Result of a non-blocking hand-off to a session's outbound queue. */
enum class OfferResult { OK, FULL, CLOSED }

/** A live message handed to a session and not yet acknowledged by its `FLASH_RECV_ACK` (REL-02). */
class InFlight(
    val origin: String,
    val type: String,
    val payload: String,
    val sentAt: Long,
    val group: String?,
    val handedAtNanos: Long,
)

/**
 * An active, authenticated WebSocket session on the relay.
 *
 * **Outbound path (REL-05).** Nothing writes to the socket from another client's coroutine. Every frame for this
 * device goes into [outbound], a BOUNDED queue, and ONE writer coroutine of this session writes them in order, each
 * write under [sendTimeoutMs]. A sender therefore never waits for a slow recipient: [offer] either queues the frame or
 * reports [OfferResult.FULL]; a recipient that is full, or whose write times out or fails, is closed ([fail]) and its
 * unacknowledged messages fall back to the mailbox.
 *
 * **Identity.** [deviceId] is what the handshake proved. Session objects compare by IDENTITY, which is what makes
 * "remove THIS session from the pool" ([RelayConnectionPool.unregister]) safe against a reconnect (REL-01).
 *
 * **Delivery bookkeeping** (used only by [RelayFrameRouter]): [draining] / [drainRunning] / [wakeups] / [cursor] order
 * mailbox replay before live frames; [inFlight] holds live messages awaiting `FLASH_RECV_ACK`.
 *
 * @param onClosed called exactly once, with a short reason, when the session ends by any path except an orderly
 *   [closeGracefully] initiated by the router (which reports through the same hook).
 */
class RelaySession(
    val deviceId: String,
    val friendlyName: String,
    val role: String = "member",
    val remoteAddress: String = "unknown",
    val sessionStartTime: Long = System.currentTimeMillis(),
    val websocketSession: WebSocketSession,
    /** The identity proven by the handshake (ADR-109); null only for sessions built by tests that bypass it. */
    val principal: HubPrincipal? = null,
    val features: Set<String> = emptySet(),
    outboundCapacity: Int = 256,
    private val sendTimeoutMs: Long = 10_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** When the credentials of this session expire (epoch ms; null = unknown). Set by the router after the handshake (REV-05). */
    @Volatile
    var validUntilMs: Long? = null

    /** The router's expiry timer for this session; cancelled when the session ends so shutdown never waits on it. */
    @Volatile
    var expiryJob: kotlinx.coroutines.Job? = null

    /** Time of the last frame received from the client (epoch ms). */
    @Volatile
    var lastFrameAt: Long = sessionStartTime

    /** Last `FLASH_PING` received (epoch ms). */
    val lastPingTime: AtomicLong = AtomicLong(sessionStartTime)

    @Volatile
    var onClosed: ((RelaySession, String) -> Unit)? = null

    // ---- delivery bookkeeping (router only) ----

    /** True while mailbox rows may still be waiting: live frames must not overtake them. */
    @Volatile
    var draining: Boolean = false
    val drainRunning: AtomicBoolean = AtomicBoolean(false)
    val wakeups: AtomicLong = AtomicLong()

    /**
     * Makes "is this session caught up with its mailbox?" and "hand a live frame to it" one atomic decision, so a live
     * frame can not slip between the end of a drain and a row that arrived during it (ordering, REL-07). Only
     * non-suspending code runs under it.
     */
    private val gate = Any()

    /** Highest mailbox `seq` already pushed to this connection (so a drain never resends an unacknowledged row twice). */
    @Volatile
    var cursor: Long = 0L
    val inFlight: ConcurrentHashMap<String, InFlight> = ConcurrentHashMap()
    val ended: AtomicBoolean = AtomicBoolean(false)

    /** Frames refused so far on this connection (rate limit, malformed): abuse counter. */
    var refusals: Int = 0

    /** When the last `ERR_RATE_LIMIT` was sent, so a flood does not make the hub echo a flood. */
    var lastRateLimitNoticeAt: Long = 0L

    @Volatile
    var rateBucket: TokenBucket? = null

    /** Bytes this sender may make the hub emit (RLY-02); charged per delivered copy by the router. */
    @Volatile
    var byteBucket: TokenBucket? = null

    // ---- outbound ----

    private val outbound = Channel<String>(outboundCapacity)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var closeReason: String = ""
    private val writer: Job = scope.launch { runWriter() }

    val isOpen: Boolean get() = !closed.get()

    /** Why the session closed (empty while it is open). */
    val reasonClosed: String get() = closeReason

    private suspend fun runWriter() {
        try {
            for (text in outbound) {
                val sent = withTimeoutOrNull(sendTimeoutMs) {
                    websocketSession.send(Frame.Text(text))
                    true
                }
                if (sent != true) {
                    fail("send_timeout")
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            fail("send_failed")
        }
    }

    /** Queues [text] without waiting. Never suspends, so it is safe from any other client's coroutine. */
    fun offer(text: String): OfferResult {
        if (closed.get()) return OfferResult.CLOSED
        val result = outbound.trySend(text)
        return when {
            result.isSuccess -> OfferResult.OK
            result.isClosed -> OfferResult.CLOSED
            else -> OfferResult.FULL
        }
    }

    /**
     * Hands a LIVE frame to this session when, and only when, it is open, caught up with its mailbox and below
     * [maxInFlight] unacknowledged messages. The message is entered in [inFlight] BEFORE it is queued (its
     * `FLASH_RECV_ACK` can arrive before the caller runs again) and removed again if the queue refuses it.
     *
     * @return null when the session is not eligible (the caller must use the mailbox), else the queue answer.
     */
    fun handOffLive(msgId: String, text: String, flight: InFlight, maxInFlight: Int): OfferResult? = synchronized(gate) {
        if (closed.get() || draining || inFlight.size >= maxInFlight) return null
        // REV-08: another sender's live message with the same id is still unacknowledged: this one goes through the mailbox, where
        // the (origin, id, target) key keeps them apart, instead of overwriting (and so losing the ack of) the first.
        if (inFlight[msgId]?.let { it.origin != flight.origin } == true) return null
        inFlight[msgId] = flight
        val result = offer(text)
        if (result != OfferResult.OK) inFlight.remove(msgId)
        result
    }

    /**
     * Ends one drain pass. Returns true when the drain is over (the mailbox was empty and no wake-up is pending) and
     * the session is released to live traffic; false when another pass is needed (the session stays `draining`). The
     * check and the release are atomic with [handOffLive].
     */
    fun finishDrain(seen: Long): Boolean = synchronized(gate) {
        if (wakeups.get() != seen) return false
        draining = false
        drainRunning.set(false)
        if (closed.get() || wakeups.get() == seen) return true
        // A wake-up raced with the release: take the baton back and go again.
        if (drainRunning.compareAndSet(false, true)) {
            draining = true
            return false
        }
        true
    }

    /**
     * Takes the drain baton and marks the session `draining` as ONE step under the gate (RLY-03). Done in two steps outside it,
     * a live frame handed over between the compare-and-set and the flag write would reach the device before older mailbox rows.
     * Returns false when a drain already runs.
     */
    fun beginDrain(): Boolean = synchronized(gate) {
        if (!drainRunning.compareAndSet(false, true)) return false
        draining = true
        true
    }

    /** The drain flags read as one unit (tests): `drainRunning` must imply `draining` at every gate boundary. */
    internal fun drainFlags(): Pair<Boolean, Boolean> = synchronized(gate) { drainRunning.get() to draining }

    /** Releases the drain baton without finishing (the drain coroutine was cancelled). */
    fun abortDrain() {
        synchronized(gate) {
            draining = false
            drainRunning.set(false)
        }
    }

    /**
     * Queues [text], waiting up to [sendTimeoutMs] for room. For the background drain only (it is not on any client's
     * read loop). Returns false when the session closed or stayed full.
     */
    suspend fun enqueue(text: String): Boolean {
        if (closed.get()) return false
        return try {
            withTimeoutOrNull(sendTimeoutMs) {
                outbound.send(text)
                true
            } ?: false
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    /**
     * Ends the session NOW (a slow consumer, a failed write): stops the writer, closes the socket in the background and
     * reports [reason]. Idempotent.
     */
    fun fail(reason: String, code: CloseReason.Codes = CloseReason.Codes.TRY_AGAIN_LATER) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        outbound.close()
        writer.cancel()
        scope.launch {
            try {
                withTimeoutOrNull(sendTimeoutMs) { websocketSession.close(CloseReason(code, reason.take(100))) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Already closed or the socket is gone.
            }
        }
        onClosed?.invoke(this, reason)
    }

    /**
     * Orderly close: lets the writer flush what is queued (an error or kick frame, typically), then closes the socket.
     * Waits at most [sendTimeoutMs] for the flush.
     */
    suspend fun closeGracefully(reason: String, code: CloseReason.Codes = CloseReason.Codes.NORMAL) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        outbound.close()
        try {
            withTimeoutOrNull(sendTimeoutMs) { writer.join() }
            writer.cancel()
            try {
                withTimeoutOrNull(sendTimeoutMs) { websocketSession.close(CloseReason(code, reason.take(100))) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Already closed or the socket is gone.
            }
        } finally {
            // Also on cancellation (a shutdown that gave up on a stuck socket): the writer must stop and the owner's
            // cleanup must still run, or the session's unacknowledged messages would never reach the mailbox.
            writer.cancel()
            onClosed?.invoke(this, reason)
        }
    }

    /** Releases the per-session scope when the session has ended. */
    fun dispose() {
        scope.cancel()
    }

    /** Records a ping timestamp. */
    fun recordPing(timestamp: Long = System.currentTimeMillis()) {
        lastPingTime.set(timestamp)
    }

    /** Compatibility helper for tests and broadcast: queues a text frame, throwing when it can not be queued. */
    suspend fun sendText(text: String) {
        check(enqueue(text)) { "session for '$deviceId' is closed or stalled" }
    }

    override fun toString(): String = "RelaySession(deviceId=$deviceId, remote=$remoteAddress, open=$isOpen)"
}
