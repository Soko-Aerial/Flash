package com.transfer.flash.core.engine.internal

import com.transfer.flash.core.engine.concurrent.PlatformLock

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

    // Both methods below carried `@Synchronized` before Phase 12. That annotation is
    // `kotlin.jvm.Synchronized` — JVM-only, so it cannot appear in `commonMain` — and the lock
    // is now explicit instead. The critical section is unchanged in both cases: the whole
    // method body. The only observable difference is the monitor's identity, which moves from
    // `this` to a private object; nothing outside this file ever locked on a gate instance,
    // and both maps are private, so no caller can tell.
    private val lock = PlatformLock()

    private val lastAttemptMs = HashMap<String, Long>()
    private val inFlight = HashSet<String>()

    // `withLock` cannot be `inline` on an `expect class`, so every early exit is a
    // `return@withLock` — a bare `return` would be a non-local return out of a non-inline
    // lambda and would not compile.
    fun tryBegin(deviceId: String, hasSession: Boolean, nowMs: Long): Boolean = lock.withLock {
        if (hasSession) {
            lastAttemptMs.remove(deviceId)
            inFlight.remove(deviceId)
            return@withLock false
        }
        if (deviceId in inFlight) return@withLock false
        val last = lastAttemptMs[deviceId]
        if (last != null && nowMs - last < suppressMs) return@withLock false
        lastAttemptMs[deviceId] = nowMs
        inFlight += deviceId
        true
    }

    fun end(deviceId: String) {
        lock.withLock { inFlight.remove(deviceId) }
    }

    private companion object {
        const val DEFAULT_SUPPRESS_MS = 15_000L
    }
}
