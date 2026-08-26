package com.transfer.flash.net

/**
 * Pure admission gate for the engine's background auto-connector (JVM-testable; no Android types).
 *
 * The auto-connector proactively dials every discovered peer that has no live session, so a
 * full-duplex WebSocket exists in whichever direction the network permits. This is REQUIRED for
 * Wi-Fi-hotspot topologies: the SoftAP/gateway device cannot open a TCP connection to a client
 * station, so only the client can dial. Once the client's dial lands, pairing / chat / transfer
 * all ride that one session regardless of which side initiated — the host never has to dial back.
 *
 * The gate bounds attempts so a permanently-unreachable peer (e.g. the host trying to dial a
 * client) cannot be hammered: at most one attempt per [suppressMs] per peer, and never a second
 * concurrent attempt for the same peer. A peer that already has a session is cleared so a later
 * drop re-arms it immediately (reconnect-after-drop).
 */
class AutoConnectGate(private val suppressMs: Long = DEFAULT_SUPPRESS_MS) {

    private val lastAttemptMs = HashMap<String, Long>()
    private val inFlight = HashSet<String>()

    /**
     * Returns true (and records the attempt) when a dial to [deviceId] should start now. Returns
     * false — and clears any suppression/in-flight bookkeeping — when [hasSession] is true, so the
     * next dropped session re-arms without waiting out the window.
     */
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

    /** Marks the in-flight dial for [deviceId] finished; the [suppressMs] window still applies. */
    @Synchronized
    fun end(deviceId: String) {
        inFlight.remove(deviceId)
    }

    companion object {
        const val DEFAULT_SUPPRESS_MS = 15_000L
    }
}
