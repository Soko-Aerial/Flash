package com.transfer.flash.server.relay

import io.ktor.websocket.Frame
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry and lifecycle manager for all active peer WebSocket connections
 * on the Flash Relay hub.
 *
 * Provides duplicate session displacement (so re-connecting devices take over cleanly),
 * targeted frame dispatch, filtered broadcasting, and disconnection cleanup.
 */
public class RelayConnectionPool {

    private val logger = LoggerFactory.getLogger(RelayConnectionPool::class.java)
    private val sessions = ConcurrentHashMap<String, RelaySession>()

    /**
     * Total number of currently connected authenticated devices.
     */
    public val activeCount: Int
        get() = sessions.size

    /**
     * Registers a new authenticated [session].
     *
     * If an existing session for [RelaySession.deviceId] is already active, it is
     * displaced and terminated cleanly to ensure idempotency and prevent duplicate sockets.
     *
     * @return The displaced session if one was replaced, or null.
     */
    public suspend fun register(session: RelaySession): RelaySession? {
        val oldSession = sessions.put(session.deviceId, session)
        if (oldSession != null) {
            logger.info(
                "Displacing prior connection for device '{}' (remote: {}) with new connection (remote: {})",
                session.deviceId,
                oldSession.remoteAddress,
                session.remoteAddress,
            )
            oldSession.close("Displaced by newer connection from same deviceId")
        } else {
            logger.info(
                "Registered new relay connection for device '{}' ('{}', role: {}, remote: {})",
                session.deviceId,
                session.friendlyName,
                session.role,
                session.remoteAddress,
            )
        }
        return oldSession
    }

    /**
     * Unregisters a device session by [deviceId] and closes its resources.
     *
     * @return The removed session if one was present, or null.
     */
    public suspend fun unregister(deviceId: String): RelaySession? {
        val removed = sessions.remove(deviceId)
        if (removed != null) {
            logger.info("Unregistered relay connection for device '{}'", deviceId)
            removed.close("Unregistered from pool")
        }
        return removed
    }

    /**
     * Retrieves the active session for [deviceId], if currently connected.
     */
    public fun get(deviceId: String): RelaySession? {
        return sessions[deviceId]
    }

    /**
     * Returns a snapshot list of all currently active relay sessions.
     */
    public fun getAllActive(): List<RelaySession> {
        return sessions.values.toList()
    }

    /**
     * Checks if a device is currently connected.
     */
    public fun isConnected(deviceId: String): Boolean {
        return sessions.containsKey(deviceId)
    }

    /**
     * Broadcasts a WebSocket [frame] to all active sessions that satisfy [filter].
     *
     * Automatically cleans up any broken connections encountered during transmission.
     *
     * @return Number of sessions that successfully received the frame.
     */
    public suspend fun broadcast(
        frame: Frame,
        filter: (RelaySession) -> Boolean = { true },
    ): Int {
        var delivered = 0
        val targets = sessions.values.filter(filter)
        for (target in targets) {
            try {
                target.send(frame)
                delivered++
            } catch (error: Throwable) {
                logger.warn("Failed broadcasting to device '{}', removing from pool: {}", target.deviceId, error.message)
                unregister(target.deviceId)
            }
        }
        return delivered
    }

    /**
     * Broadcasts a plain text frame to all active sessions that satisfy [filter].
     */
    public suspend fun broadcastText(
        text: String,
        filter: (RelaySession) -> Boolean = { true },
    ): Int {
        return broadcast(Frame.Text(text), filter)
    }

    /**
     * Terminates all active connections and clears the pool.
     */
    public suspend fun closeAll(reason: String = "Server shutting down") {
        logger.info("Closing all active relay connections (count: {}): {}", sessions.size, reason)
        val snapshot = sessions.values.toList()
        sessions.clear()
        for (session in snapshot) {
            session.close(reason)
        }
    }
}
