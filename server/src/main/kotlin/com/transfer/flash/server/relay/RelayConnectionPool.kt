@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.server.observability.PresenceView
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry of the active, authenticated relay sessions.
 *
 * **Identity-safe removal (REL-01).** A device that reconnects registers a NEW [RelaySession] under the same id and the
 * old one is displaced. The old session's own cleanup later calls [unregister] with the OLD session object, and the
 * pool removes an entry only if it is still that very object (`ConcurrentHashMap.remove(key, value)`), so a delayed
 * cleanup can never close or deregister the connection that replaced it. There is deliberately no "unregister by
 * device id" any more.
 */
class RelayConnectionPool : PresenceView {

    private val logger = LoggerFactory.getLogger(RelayConnectionPool::class.java)
    private val sessions = ConcurrentHashMap<String, RelaySession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Total number of currently connected authenticated devices. */
    val activeCount: Int
        get() = sessions.size

    /**
     * Registers [session]. A session already registered for the same device is displaced: it is told `FLASH_KICK
     * superseded` and closed in the background (never blocking the new connection).
     *
     * @return The displaced session if one was replaced, or null.
     */
    fun register(session: RelaySession): RelaySession? {
        val old = sessions.put(session.deviceId, session)
        if (old != null && old !== session) {
            logger.info(
                "Displacing prior connection for device (remote {}) with a new connection (remote {})",
                old.remoteAddress,
                session.remoteAddress,
            )
            scope.launch {
                old.offer(RelayFrameCodec.encode(RelayFrame.Kick(reason = KICK_SUPERSEDED)))
                old.closeGracefully("superseded", CloseReason.Codes.NORMAL)
            }
        } else {
            logger.info("Registered relay connection for role {} from {}", session.role, session.remoteAddress)
        }
        return old
    }

    /**
     * Removes [session] from the pool IF it is still the registered one. The session is NOT closed here (its owner
     * does that); the result says whether anything was removed.
     */
    fun unregister(session: RelaySession): Boolean = sessions.remove(session.deviceId, session)

    /** The active session for [deviceId], if currently connected. */
    fun get(deviceId: String): RelaySession? = sessions[deviceId]

    /** A snapshot list of all currently active relay sessions. */
    fun getAllActive(): List<RelaySession> = sessions.values.toList()

    /** True when a device is currently connected. */
    fun isConnected(deviceId: String): Boolean = sessions.containsKey(deviceId)

    override fun isOnline(deviceId: String): Boolean = sessions.containsKey(deviceId)

    override fun onlineDeviceIds(): Set<String> = sessions.keys.toSet()

    /**
     * Queues a text frame to every active session that satisfies [filter], without waiting for any of them.
     *
     * @return Number of sessions the frame was queued for.
     */
    fun broadcastText(text: String, filter: (RelaySession) -> Boolean = { true }): Int {
        var queued = 0
        for (target in sessions.values) {
            if (!filter(target)) continue
            when (target.offer(text)) {
                OfferResult.OK -> queued++
                OfferResult.FULL -> target.fail("slow_consumer")
                OfferResult.CLOSED -> Unit
            }
        }
        return queued
    }

    /**
     * Sends every session `FLASH_KICK` ([reason]) and closes them ALL IN PARALLEL, waiting at most [timeoutMs] for the
     * whole group (REL-13: a sequential close let one stuck socket delay every other). Sessions that did not finish in
     * time are failed (aborted). The pool is empty afterwards.
     */
    suspend fun closeAll(reason: String = "server_shutdown", timeoutMs: Long = 5_000L) {
        val snapshot = sessions.values.toList()
        logger.info("Closing all active relay connections (count: {}): {}", snapshot.size, reason)
        val kick = RelayFrameCodec.encode(RelayFrame.Kick(reason = reason))
        val finished = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                snapshot.map { s ->
                    async {
                        s.offer(kick)
                        s.closeGracefully(reason, CloseReason.Codes.GOING_AWAY)
                    }
                }.awaitAll()
            }
            true
        }
        if (finished == null) {
            logger.warn("Shutdown timed out after {} ms; aborting the remaining sessions", timeoutMs)
            snapshot.forEach { it.fail("shutdown_timeout", CloseReason.Codes.GOING_AWAY) }
        }
        snapshot.forEach { sessions.remove(it.deviceId, it) }
    }

    companion object {
        /** `FLASH_KICK.reason` for a session replaced by a newer connection of the same device. */
        const val KICK_SUPERSEDED: String = "superseded"
    }
}
