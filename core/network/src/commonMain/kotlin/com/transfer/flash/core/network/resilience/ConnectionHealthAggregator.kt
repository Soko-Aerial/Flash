package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.network.FlashConnectionHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure aggregation of network signals into [FlashConnectionHealth]
 * (plan C4.7). The engine feeds [apply] from discovery / reconnect /
 * session-manager callbacks; the UI banner (UI-030) and network-simulation
 * screen (UI-044) read [health].
 *
 * Dependency note: `kotlinx.coroutines.flow.MutableStateFlow` is available in
 * this module transitively via `androidx.lifecycle:lifecycle-runtime-ktx`
 * (already used by `tcp/LanSession.kt`), so no gradle change was needed and
 * none was made (read-only constraint honored).
 *
 * Mapping + documented precedence (evaluated top-down, first match wins):
 * 1. **Connected** — at least one healthy (non-degraded) online session AND
 *    zero degraded sessions. A single fully-healthy mesh of paths means the
 *    user experience is normal.
 * 2. **Degraded** — at least one online session exists but ALL of them are
 *    degraded. Mixed healthy+degraded collapses to Connected (precedence:
 *    any healthy path dominates — the user can still exchange data normally;
 *    per-session impairment is reported elsewhere, not by flattening global
 *    health).
 * 3. **Connecting** — reconnect/connect attempts are in flight, OR discovery
 *    sees peers but no sessions exist yet ("peers>0-but-no-sessions").
 * 4. **Offline** — no peers visible, no attempts, no sessions.
 */
public class ConnectionHealthAggregator(initial: FlashConnectionHealth = FlashConnectionHealth.Offline) {

    private val _health = MutableStateFlow(initial)

    /** Observable current health; conflated StateFlow semantics. */
    public val health: StateFlow<FlashConnectionHealth> = _health.asStateFlow()

    /**
     * Feeds a fresh snapshot of all four signals and updates [health].
     * Snapshot-based (not delta-based) so dropped events can never wedge the
     * aggregate in a stale state.
     */
    public fun apply(
        peerCountDiscovered: Int,
        connectingAttempts: Int,
        onlineSessions: Int,
        degradedSessions: Int,
    ) {
        _health.value = resolve(
            peerCountDiscovered = peerCountDiscovered,
            connectingAttempts = connectingAttempts,
            onlineSessions = onlineSessions,
            degradedSessions = degradedSessions,
        )
    }

    public companion object {
        /**
         * Pure mapping function exposed for direct deterministic testing.
         */
        public fun resolve(
            peerCountDiscovered: Int,
            connectingAttempts: Int,
            onlineSessions: Int,
            degradedSessions: Int,
        ): FlashConnectionHealth = when {
            onlineSessions > 0 && degradedSessions == 0 -> FlashConnectionHealth.Connected
            onlineSessions > 0 && degradedSessions > 0 ->
                // Mixed: some healthy, some impaired → healthy path dominates.
                FlashConnectionHealth.Connected
            degradedSessions > 0 -> FlashConnectionHealth.Degraded
            connectingAttempts > 0 -> FlashConnectionHealth.Connecting
            peerCountDiscovered > 0 -> FlashConnectionHealth.Connecting
            else -> FlashConnectionHealth.Offline
        }
    }
}
