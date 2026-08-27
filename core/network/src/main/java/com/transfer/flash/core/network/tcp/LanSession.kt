@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import android.util.Log
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.FrameAck
import com.transfer.flash.core.network.FrameAckStage
import com.transfer.flash.core.network.resilience.HeartbeatPolicy
import com.transfer.flash.core.network.resilience.HeartbeatState
import com.transfer.flash.core.network.resilience.HeartbeatTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pluggable log sink so JVM unit tests can instantiate [LanSession] without
 * touching `android.util.Log` (which throws "not mocked" on the JVM).
 * Production default routes to [Log.println] exactly like before hardening.
 */
public fun interface LanSessionLogger {
    public fun log(priority: Int, tag: String, message: String, error: Throwable?)

    public companion object {
        public val ANDROID: LanSessionLogger = LanSessionLogger { priority, tag, message, error ->
            val text = if (error != null) "$message - ${Log.getStackTraceString(error)}" else message
            Log.println(priority, tag, text)
        }

        public val DEBUG: Int = Log.DEBUG
        public val INFO: Int = Log.INFO
        public val WARN: Int = Log.WARN
    }
}

/** Thrown into pending sendAwaitAck waiters when the session closes early. */
private class SessionClosedException(message: String) : RuntimeException(message)

public class LanSession(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val writer: PrintWriter,
    private val localDeviceId: String,
    private val localFriendlyName: String,
    public val peerInfo: LanProbeHello,
    private val onDisconnected: (LanProbeHello, String) -> Unit,
    /**
     * Heartbeat ping interval ms (C4.3). Defaults to
     * [HeartbeatPolicy.DEFAULT_INTERVAL_MS] (10 s) rather than the legacy
     * fixed 3 s cadence: tracker math counts one miss per full interval
     * without pong, so 10 s x threshold 3 = documented ~30 s worst-case dead
     * detection while tolerating a single lost exchange to Wi-Fi jitter.
     * Old 3 s cadence remains available by passing 3_000 explicitly.
     */
    public val heartbeatIntervalMs: Long = HeartbeatPolicy.DEFAULT_INTERVAL_MS,
    /** Missed pings before DeclareDead; see [heartbeatIntervalMs]. */
    public val heartbeatMissedThreshold: Int = HeartbeatPolicy.DEFAULT_MISSED_THRESHOLD,
    /** Injected log sink (JVM-test seam); default is android.util.Log. */
    private val logger: LanSessionLogger = LanSessionLogger.ANDROID,
) : FlashSession {

    override val peerDeviceId: FlashDeviceId = FlashDeviceId(peerInfo.deviceId)

    override val peer: FlashDevice = FlashDevice(
        id = peerDeviceId,
        friendlyName = peerInfo.friendlyName,
        transportType = FlashTransportType.LAN,
        presence = FlashPeerPresence.Online,
        protocolVersion = peerInfo.protocolVersion,
    )

    override val transportType: FlashTransportType = FlashTransportType.LAN

    private val _connectionState = MutableStateFlow(FlashConnectionState.Connected)
    override val connectionState: StateFlow<FlashConnectionState> = _connectionState.asStateFlow()

    /**
     * C4.8 delivery-ACK hooks. Hot flow; every successful [send] emits
     * [FrameAckStage.SocketWritten], every parsed FLASH_ACK from the peer
     * emits [FrameAckStage.PeerAcknowledged]. DROP_OLDEST keeps emission
     * non-suspending on the IO hot path while bounding memory.
     */
    private val _frameAcks = MutableSharedFlow<FrameAck>(
        replay = 0,
        extraBufferCapacity = FRAME_ACK_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frameAcks: MutableSharedFlow<FrameAck> = _frameAcks

    /**
     * Additive receive surface for FLASH_DATA-enveloped payloads so two Flash
     * sessions exchange acked frames end-to-end (payloads are single-line
     * UTF-8 text per existing line framing). Not part of [FlashSession].
     */
    private val _incomingFrames = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = FRAME_ACK_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public val incomingFrames: SharedFlow<String> = _incomingFrames.asSharedFlow()

    /**
     * frameId -> waiter for outstanding [sendAwaitAck] calls.
     * remove-before-fire guarantees duplicate FLASH_ACK lines cannot
     * double-complete a waiter or double-emit PeerAcknowledged.
     */
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Serializes tracker access between read loop (pongs) and heartbeat loop (ticks). */
    private val heartbeatLock = Any()
    private val tracker = HeartbeatTracker(
        HeartbeatPolicy(intervalMs = heartbeatIntervalMs, missedThreshold = heartbeatMissedThreshold),
    )

    /** Guards multi-line writes (envelope + payload) against interleaving. */
    private val writeLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null

    public fun start() {
        if (closed.get()) return
        readJob = scope.launch { readLoop() }
        heartbeatJob = scope.launch { heartbeatLoop() }
    }

    override suspend fun send(message: ByteArray): FlashResult<Unit> = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext FlashResult.Failure(FlashError.PeerUnavailable(peerDeviceId.value, "Session closed"))
        return@withContext runCatching {
            writeLine(String(message, Charsets.UTF_8)) || throw java.io.IOException("Socket write checkError failed")
            _frameAcks.tryEmit(FrameAck(frameId = UUID.randomUUID().toString(), stage = FrameAckStage.SocketWritten, atMs = nowMs()))
            FlashResult.Success(Unit)
        }.getOrElse { error ->
            close("Write failed: ${error.message}")
            FlashResult.Failure(FlashError.TransferFailed("session", error.message ?: "Write failed"))
        }
    }

    /**
     * Sends [message] inside a FLASH_DATA envelope carrying a fresh frameId
     * and suspends until the peer echoes FLASH_ACK for that id (C4.8).
     *
     * Semantics: at-most-once per call — no automatic retransmission on
     * timeout (research: chat delivery retries belong to the durable outbox,
     * C6; see docs/protocol.md). Timeout returns
     * [FlashError.ConnectionTimeout] WITHOUT closing the session — the link
     * may simply be slow and later frames still flow. Session death while
     * waiting fails fast with [FlashError.PeerUnavailable].
     *
     * Payload must be single-line UTF-8 text (existing line-framing rule);
     * embedded newlines would corrupt the envelope/payload pairing.
     */
    public suspend fun sendAwaitAck(message: ByteArray, timeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS): FlashResult<Unit> =
        withContext(Dispatchers.IO) {
            if (closed.get()) {
                return@withContext FlashResult.Failure(FlashError.PeerUnavailable(peerDeviceId.value, "Session closed"))
            }
            val frameId = UUID.randomUUID().toString()
            val waiter = CompletableDeferred<Unit>()
            pendingAcks[frameId] = waiter
            val writeOk = runCatching {
                synchronized(writeLock) {
                    writer.println(LanProbeMessages.data(PROTOCOL_VERSION, localDeviceId, localFriendlyName, frameId))
                    if (!writer.checkError()) {
                        writer.println(String(message, Charsets.UTF_8))
                    }
                    !writer.checkError()
                }
            }.getOrElse { false }

            if (!writeOk) {
                pendingAcks.remove(frameId)
                close("Write failed during acked send")
                return@withContext FlashResult.Failure(FlashError.TransferFailed("session", "Write failed"))
            }
            _frameAcks.tryEmit(FrameAck(frameId, FrameAckStage.SocketWritten, nowMs()))

            val acked = try {
                withTimeoutOrNull(timeoutMs) { waiter.await() } != null
            } catch (_: SessionClosedException) {
                pendingAcks.remove(frameId)
                return@withContext FlashResult.Failure(FlashError.PeerUnavailable(peerDeviceId.value, "Session closed"))
            }
            if (acked) {
                FlashResult.Success(Unit)
            } else {
                pendingAcks.remove(frameId)
                // Deliberately NOT closing the session: an ACK timeout is not
                // proof of death (heartbeat tracker owns liveness).
                logger.log(LanSessionLogger.WARN, TAG, "LAN session ack timed out peerId=${peerInfo.deviceId} frameId=$frameId timeoutMs=$timeoutMs", null)
                FlashResult.Failure(FlashError.ConnectionTimeout(timeoutMs, "No FLASH_ACK for frameId=$frameId"))
            }
        }

    override fun disconnect(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        failPendingAcks("disconnect: $reason")
        _connectionState.value = FlashConnectionState.Disconnecting
        runCatching {
            writer.println(LanProbeMessages.disconnect(PROTOCOL_VERSION, localDeviceId, localFriendlyName))
        }
        closeSocket()
        _connectionState.value = FlashConnectionState.Disconnected
        onDisconnected(peerInfo, reason)
    }

    public fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        failPendingAcks(reason)
        closeSocket()
        _connectionState.value = FlashConnectionState.Disconnected
        onDisconnected(peerInfo, reason)
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        // Bump soTimeout to allow heartbeat-driven liveness (10s interval) to work.
        // The probe/handshake path sets 4s which is too aggressive for idle sessions.
        runCatching { socket.soTimeout = IDLE_READ_TIMEOUT_MS }
        var pendingDataFrameId: String? = null
        try {
            while (!closed.get() && scope.isActive) {
                val line = try {
                    reader.readLine() ?: break
                } catch (_: java.net.SocketTimeoutException) {
                    // soTimeout fired — session is NOT dead. Liveness is owned by
                    // HeartbeatTracker (DeclareDead closes the socket). Just loop.
                    if (!closed.get()) {
                        logger.log(LanSessionLogger.DEBUG, TAG, "LAN session read tick peerId=${peerInfo.deviceId}", null)
                    }
                    continue
                }

                val dataHeader = LanProbeMessages.parseData(line)
                if (dataHeader != null) {
                    // Envelope: auto-ACK immediately, payload arrives next line.
                    if (!writeLine(LanProbeMessages.ack(PROTOCOL_VERSION, localDeviceId, localFriendlyName, dataHeader.frameId))) {
                        error("Ack write failed")
                    }
                    pendingDataFrameId = dataHeader.frameId
                    continue
                }

                val ackFrame = LanProbeMessages.parseAck(line)
                if (ackFrame != null) {
                    // remove-before-fire: duplicate ACK lines are no-ops.
                    val waiter = pendingAcks.remove(ackFrame.ackedFrameId)
                    if (waiter != null) {
                        _frameAcks.tryEmit(
                            FrameAck(ackFrame.ackedFrameId, FrameAckStage.PeerAcknowledged, nowMs()),
                        )
                        waiter.complete(Unit)
                    } else {
                        logger.log(
                            LanSessionLogger.DEBUG,
                            TAG,
                            "LAN session duplicate/unknown ack frameId=${ackFrame.ackedFrameId} peerId=${peerInfo.deviceId}",
                            null,
                        )
                    }
                    continue
                }

                when {
                    pendingDataFrameId != null -> {
                        // This line is the payload of the enveloped frame.
                        val delivered = _incomingFrames.tryEmit(line)
                        logger.log(
                            if (delivered) LanSessionLogger.DEBUG else LanSessionLogger.WARN,
                            TAG,
                            "LAN session data frameId=$pendingDataFrameId delivered=$delivered peerId=${peerInfo.deviceId}",
                            null,
                        )
                        pendingDataFrameId = null
                    }

                    LanProbeMessages.parsePing(line) != null -> {
                        if (!writeLine(LanProbeMessages.pong(PROTOCOL_VERSION, localDeviceId, localFriendlyName))) {
                            error("Pong write failed")
                        }
                    }

                    LanProbeMessages.parsePong(line) != null -> {
                        synchronized(heartbeatLock) { tracker.onPongReceived(nowMs()) }
                        logger.log(LanSessionLogger.DEBUG, TAG, "LAN session heartbeat peerId=${peerInfo.deviceId}", null)
                    }

                    LanProbeMessages.parseDisconnect(line) != null -> {
                        close("${peerInfo.friendlyName} disconnected")
                        return@withContext
                    }

                    else -> logger.log(LanSessionLogger.WARN, TAG, "LAN session ignored unknown message from peerId=${peerInfo.deviceId}", null)
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                logger.log(LanSessionLogger.WARN, TAG, "LAN session read failed peerId=${peerInfo.deviceId}", error)
            }
        } finally {
            if (!closed.get()) {
                close("${peerInfo.friendlyName} disconnected")
            }
        }
    }

    /**
     * Tracker-driven dead-peer detection (C4.3 / upgrade 3), replacing the
     * legacy blind 3 s ping loop. Ticks every [tickIntervalMs]; the pure
     * [HeartbeatTracker] decides PingNow / AwaitPong / DeclareDead. Suspect
     * transitions are logged; DeclareDead closes the session from this
     * coroutine with reason "heartbeat timeout".
     */
    private suspend fun heartbeatLoop() = withContext(Dispatchers.IO) {
        val policy = HeartbeatPolicy(intervalMs = heartbeatIntervalMs, missedThreshold = heartbeatMissedThreshold)
        synchronized(heartbeatLock) { tracker.reset(nowMs()) }
        var lastLoggedState = HeartbeatState.Alive
        while (!closed.get() && scope.isActive) {
            delay(tickIntervalMs(policy))
            val action = synchronized(heartbeatLock) {
                val a = tracker.onTick(nowMs())
                if (tracker.state != lastLoggedState) {
                    lastLoggedState = tracker.state
                    if (tracker.state == HeartbeatState.Suspect) {
                        logger.log(LanSessionLogger.WARN, TAG, "LAN session peer suspect missed=${tracker.missedCountValue} peerId=${peerInfo.deviceId}", null)
                    }
                }
                a
            }
            when (action) {
                com.transfer.flash.core.network.resilience.HeartbeatAction.PingNow -> {
                    val ok = runCatching { writeLine(LanProbeMessages.ping(PROTOCOL_VERSION, localDeviceId, localFriendlyName)) }
                        .getOrElse { false }
                    if (ok) {
                        synchronized(heartbeatLock) { tracker.onPingSent(nowMs()) }
                    } else if (!closed.get()) {
                        logger.log(LanSessionLogger.WARN, TAG, "LAN session heartbeat failed peerId=${peerInfo.deviceId}", null)
                        close("heartbeat timeout")
                        return@withContext
                    }
                }

                com.transfer.flash.core.network.resilience.HeartbeatAction.DeclareDead -> {
                    logger.log(LanSessionLogger.WARN, TAG, "LAN session dead peer declared peerId=${peerInfo.deviceId} intervalMs=${policy.intervalMs} threshold=${policy.missedThreshold}", null)
                    close("heartbeat timeout")
                    return@withContext
                }

                com.transfer.flash.core.network.resilience.HeartbeatAction.AwaitPong -> Unit
            }
        }
    }

    /**
     * Writes one line + flush. Returns false on any write error (PrintWriter
     * swallows IOExceptions; checkError is the only reliable signal).
     */
    private fun writeLine(line: String): Boolean {
        synchronized(writeLock) {
            writer.println(line)
            return !writer.checkError()
        }
    }

    private fun failPendingAcks(reason: String) {
        pendingAcks.keys.toList().forEach { id ->
            pendingAcks.remove(id)?.completeExceptionally(SessionClosedException(reason))
        }
    }

    /**
     * Closes the socket so the read thread blocked in readLine() unblocks
     * immediately with SocketException.
     *
     * Why closing (not SoTimeout alone): per the JDK spec, `close()` makes
     * ANY thread blocked in I/O on this socket throw — the only documented
     * way to interrupt a blocked blocking-read from another thread
     * (Thread.interrupt() does not reliably unblock socket reads across
     * platforms). SoTimeout merely converts an infinite block into periodic
     * SocketTimeoutExceptions while keeping the half-open session alive; the
     * tracker needs a hard teardown at DeclareDead, and the read thread must
     * exit promptly so no zombie coroutine lingers on a dead peer.
     */
    private fun closeSocket() {
        readJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { socket.close() }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun tickIntervalMs(policy: HeartbeatPolicy): Long =
        (policy.intervalMs / HEARTBEAT_TICK_DIVISOR).coerceIn(MIN_HEARTBEAT_TICK_MS, policy.intervalMs)

    public companion object {
        public fun create(
            socket: Socket,
            localDeviceId: String,
            localFriendlyName: String,
            peer: LanProbeHello,
            onDisconnected: (LanProbeHello, String) -> Unit,
        ): LanSession {
            return LanSession(
                socket = socket,
                reader = BufferedReader(InputStreamReader(socket.getInputStream())),
                writer = PrintWriter(socket.getOutputStream(), true),
                localDeviceId = localDeviceId,
                localFriendlyName = localFriendlyName,
                peerInfo = peer,
                onDisconnected = onDisconnected,
            )
        }

        public const val PROTOCOL_VERSION: Int = 1

        /** Default per-frame wait for a peer FLASH_ACK in [sendAwaitAck]. */
        public const val DEFAULT_ACK_TIMEOUT_MS: Long = 5_000L
        private const val TAG = "LAN"
        private const val FRAME_ACK_BUFFER = 64
        private const val HEARTBEAT_TICK_DIVISOR = 4L
        private const val MIN_HEARTBEAT_TICK_MS = 5L
        /**
         * Read timeout (ms) for the session after handshake. The probe sets
         * soTimeout=4s for the handshake; once the session is established we
         * raise it so the read loop survives idle intervals between heartbeat
         * pings (default 10s). 30s ≈ 3× heartbeat interval — generous enough
         * to tolerate Wi-Fi jitter while still unblocking the read periodically
         * so the while-loop can check `closed`.
         */
        private const val IDLE_READ_TIMEOUT_MS = 30_000
    }
}
