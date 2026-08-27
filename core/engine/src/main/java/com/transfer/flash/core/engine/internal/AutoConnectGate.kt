package com.transfer.flash.core.engine.internal

/**
 * Pure admission gate for the engine's background auto-connector (JVM-testable; no Android types).
 * Ported into `core:engine` for [com.transfer.flash.core.engine.Flash.create]; mirrors the app's gate.
 *
 * The auto-connector proactively dials every discovered peer that has no live session so a
 * full-duplex WebSocket exists in whichever direction the network permits — REQUIRED for Wi-Fi
 * hotspot topologies where the SoftAP/gateway cannot dial its client stations. The gate bounds
 * attempts: at most one per [suppressMs] per peer, never two concurrent for the same peer, and a
 * peer that already has a session is cleared so a later drop re-arms it immediately.
 */
internal class AutoConnectGate(private val suppressMs: Long = DEFAULT_SUPPRESS_MS) {

    private val lastAttemptMs = HashMap<String, Long>()
    private val inFlight = HashSet<String>()

    @Synchronized
    fun tryBegin(deviceId: String, hasSession: Boolean, nowMs: Long): Boolean {
        if (hasSession) {
            lastAttemptMs.remove(deviceId)
            inFlight.remove(deviceId)
            return false
        }
        if (deviceId in inFlight) return false
        val last = lastAttemptMs[deviceId]
        if (last != null && nowMs - last < suppressMs) return false
        lastAttemptMs[deviceId] = nowMs
        inFlight += deviceId
        return true
    }

    @Synchronized
    fun end(deviceId: String) {
        inFlight.remove(deviceId)
    }

    private companion object {
        const val DEFAULT_SUPPRESS_MS = 15_000L
    }
}
