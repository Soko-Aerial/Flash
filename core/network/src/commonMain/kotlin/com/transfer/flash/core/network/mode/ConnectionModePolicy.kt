package com.transfer.flash.core.network.mode

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashTransportProfile
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.network.presence.PresenceConfig
import com.transfer.flash.core.network.resilience.SessionHardeningPolicy

/**
 * How a device treats its connections (PC5, `docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.4,
 * ADR-048). Picked by the discovery mode the owner already chooses.
 */
public enum class ConnectionStrategy {
    /** Battery first: few sessions, slow keepalive, status up to about a minute late. */
    ECO,

    /** Balanced: every discovered device, tier keepalive. The behaviour before PC5. */
    STANDARD,

    /** Instant: every discovered device, fast keepalive and redial, battery disregarded. */
    BOOST,
    ;

    public companion object {
        /** GHOST and RECEIVE_KIOSK use STANDARD's connection policy (plan §3.4 notes). */
        public fun of(mode: FlashDiscoveryMode): ConnectionStrategy = when (mode) {
            FlashDiscoveryMode.ECO -> ECO
            FlashDiscoveryMode.BOOST -> BOOST
            FlashDiscoveryMode.STANDARD,
            FlashDiscoveryMode.GHOST,
            FlashDiscoveryMode.RECEIVE_KIOSK,
            -> STANDARD
        }
    }
}

/**
 * Everything a connection mode changes, derived from the mode and the hardware tier.
 *
 * The mode picks the strategy; the tier still applies (plan §3.4): only the numbers a mode names are
 * replaced, and a LOW-tier phone in BOOST keeps LOW's liveness timeout, which protects against slow
 * schedulers and long roams rather than saving battery. STANDARD returns the tier's profile
 * unchanged, so STANDARD behaves exactly as before PC5.
 *
 * @property transport keepalive and reconnect numbers for the network (`transportProfile`).
 * @property presence presence-sharing timings (`PresenceExchange`).
 */
public data class ConnectionModePolicy(
    val strategy: ConnectionStrategy,
    val transport: FlashTransportProfile,
    val presence: PresenceConfig,
) {
    /** ECO keeps only the sessions it needs ([EcoLinkSelector]) and parks idle ones it dialed. */
    public val limitsSessions: Boolean get() = strategy == ConnectionStrategy.ECO

    public companion object {
        public fun of(mode: FlashDiscoveryMode, tier: FlashPerformanceMode): ConnectionModePolicy =
            of(ConnectionStrategy.of(mode), tier)

        public fun of(strategy: ConnectionStrategy, tier: FlashPerformanceMode): ConnectionModePolicy {
            val base = tier.transport
            return when (strategy) {
                ConnectionStrategy.STANDARD -> ConnectionModePolicy(strategy, base, PresenceConfig.STANDARD)
                ConnectionStrategy.ECO -> ConnectionModePolicy(
                    strategy,
                    base.copy(
                        pingIntervalMs = ECO_PING_MS,
                        livenessTimeoutMs = maxOf(ECO_LIVENESS_MS, base.livenessTimeoutMs),
                        reconnectBaseMs = ECO_RECONNECT_BASE_MS,
                        reconnectCapMs = ECO_RECONNECT_CAP_MS,
                    ),
                    PresenceConfig.ECO,
                )
                ConnectionStrategy.BOOST -> ConnectionModePolicy(
                    strategy,
                    base.copy(
                        pingIntervalMs = BOOST_PING_MS,
                        livenessTimeoutMs = if (tier == FlashPerformanceMode.LOW) {
                            maxOf(BOOST_LIVENESS_MS, base.livenessTimeoutMs)
                        } else {
                            BOOST_LIVENESS_MS
                        },
                        reconnectBaseMs = BOOST_RECONNECT_BASE_MS,
                        reconnectCapMs = minOf(BOOST_RECONNECT_CAP_MS, base.reconnectCapMs),
                    ),
                    PresenceConfig.BOOST,
                )
            }
        }

        // Plan §3.4. Every value marked "(measure)" there is PC6's to confirm.
        public const val ECO_PING_MS: Long = 30_000L
        public const val ECO_LIVENESS_MS: Long = 75_000L
        public const val ECO_RECONNECT_BASE_MS: Long = 2_000L
        public const val ECO_RECONNECT_CAP_MS: Long = 30_000L
        public const val BOOST_PING_MS: Long = 5_000L
        public const val BOOST_LIVENESS_MS: Long = 15_000L
        public const val BOOST_RECONNECT_BASE_MS: Long = 250L
        public const val BOOST_RECONNECT_CAP_MS: Long = 5_000L

        /** ECO keeps sessions with this many ring neighbours among its contacts. */
        public const val ECO_NEIGHBOURS: Int = 3

        /** ECO keeps a session that carried user traffic within this window. */
        public const val ECO_ACTIVE_WINDOW_MS: Long = 10 * 60_000L

        /** ECO asks to park a session it dialed after this long without user traffic. */
        public const val ECO_IDLE_PARK_MS: Long = 10 * 60_000L

        /**
         * Session slots STANDARD and BOOST never spend on dials they choose to make (ADR-057): they stay
         * free for peers that dial in, dial on demand and a session being replaced. An estimate (FO-05).
         */
        public const val DIAL_HEADROOM: Int = 4

        /**
         * The most sessions STANDARD and BOOST want (held plus newly dialed) once more devices are around
         * than fit: the admission ceiling less [DIAL_HEADROOM]. Below it nothing is filtered ([DialBudget]).
         */
        public const val DIAL_BUDGET: Int = SessionHardeningPolicy.DEFAULT_MAX_CONCURRENT_SESSIONS - DIAL_HEADROOM
    }
}
