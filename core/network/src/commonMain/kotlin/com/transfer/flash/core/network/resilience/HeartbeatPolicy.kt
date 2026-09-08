package com.transfer.flash.core.network.resilience

/**
 * Configuration + rationale holder for dead-peer detection (plan C4.3 /
 * upgrade 3). Pure data — the mutable state machine lives in
 * [HeartbeatTracker].
 *
 * Defaults and their research basis:
 * - [intervalMs] = 10 s. Application-level ping/pong is required because TCP
 *   keepalive defaults to a 2-hour first probe on Linux and cannot detect an
 *   application-layer zombie (deadlocked process, NAT drop, silent peer
 *   death); research sources recommend app-level heartbeats for exactly this.
 *   10 s sits at the fast end of the documented chat-app range (chat presence
 *   systems use 10–15 s client heartbeats; proxy-keepalive guidance suggests
 *   up to 25–30 s). Flash is P2P over LAN/Direct with no reverse-proxy idle
 *   timeout in the path, so we bias toward faster detection (worst-case
 *   dead-peer declaration = interval × threshold = 30 s) at negligible
 *   overhead (a few bytes every 10 s).
 * - [missedThreshold] = 3. Both the websocket.org troubleshooting guidance
 *   ("3 missed heartbeats is a reasonable default") and chat-presence designs
 *   ("2–3 missed intervals → offline") converge on ~3; it tolerates losing a
 *   single ping/pong exchange to transient Wi-Fi jitter without falsely
 *   killing a healthy session, while still declaring death well under a
 *   minute.
 *
 * Sources:
 * - https://websocket.org/guides/troubleshooting/timeout/ (missed-count pattern, 25 s / 3 misses)
 * - https://websocket.org/guides/use-cases/chat/ (10–15 s presence heartbeats, 2–3 missed intervals)
 * - https://dev.to/137foundry/why-application-level-heartbeats-beat-tcp-keepalive-for-websockets-1bfl (TCP keepalive inadequacy)
 */
internal data class HeartbeatPolicy(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val missedThreshold: Int = DEFAULT_MISSED_THRESHOLD,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(missedThreshold >= 1) { "missedThreshold must be >= 1" }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 10_000L
        const val DEFAULT_MISSED_THRESHOLD: Int = 3
    }
}
