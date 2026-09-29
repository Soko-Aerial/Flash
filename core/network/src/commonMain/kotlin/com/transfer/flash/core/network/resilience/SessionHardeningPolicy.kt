package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.common.model.FlashTransportType

/**
 * Decision when a second session for an already-connected device arrives
 * (plan C4.5 / upgrade 6 duplicate-peer coalescing).
 */
public enum class DuplicateSessionDecision {
    /** Keep the current session; reject/ignore the newcomer. */
    KeepExisting,

    /** The newer connection rides a richer path — migrate to it. */
    PreferNew,
}

/**
 * Pure session-manager hardening rules (plan C4.5 / upgrades 5–6).
 *
 * - [maxConcurrentSessions] = [DEFAULT_MAX_CONCURRENT_SESSIONS] (24): the admission ceiling, the same
 *   in every connection mode and hardware tier (ADR-057). It bounds socket and thread pressure on a
 *   phone (each live session pins one `Dispatchers.IO` read thread) while leaving room for a
 *   20-member group's 19 sessions plus a call or transfer peer outside the group, a pairing peer and
 *   a duplicate that is replacing a session. It was 8 until ADR-057; 8 could not hold a group above 9.
 *   The number is a reasoned estimate, not a measurement (FO-05). A mode does not get its own
 *   ceiling: ECO never refuses an incoming session (ADR-048), so what a mode changes is how many
 *   sessions it asks for (`DialBudget`, `EcoLinkSelector`), not how many it admits.
 * - Transport ranks mirror the discovery priority order (C3.9 "prefers LAN"):
 *   a LOWER rank number = richer path. LAN(0) > Wi-Fi Direct(1) >
 *   WebSocket(2) > relay-class paths(3). BLE presence transport (C3.8,
 *   signals-only, no data in v1) will occupy rank 3 alongside relay-class
 *   paths. [TRANSPORT_RANK_UNKNOWN] = 99 so an unidentified path never wins.
 *
 * Tie behavior: **keep existing**. Equal richness means migration would cost
 * a reconnect handshake and risk frame loss across the swap with zero path
 * improvement; stability wins.
 *
 * For equal-rank duplicate sessions (connect-glare), the owning session manager
 * applies a deterministic originator tiebreaker instead of an arbitrary coin
 * flip: keep the session whose originator device id is lexicographically
 * smaller. Both ends of a TCP pair observe the same two ids, so both compute
 * the same winner and the surviving socket stays live on both sides (see
 * ERROR-023 / `WsFlashNetwork.registerSession`).
 *
 * Pure logic — no sockets, no state; the owning session manager applies these
 * decisions atomically around its own lock.
 */
public class SessionHardeningPolicy(
    public val maxConcurrentSessions: Int = DEFAULT_MAX_CONCURRENT_SESSIONS,
) {
    init {
        require(maxConcurrentSessions >= 1) { "maxConcurrentSessions must be >= 1" }
    }

    /**
     * Admission rule: true when one more session fits under the concurrency
     * limit given [activeCount].
     */
    public fun canAcceptSession(activeCount: Int): Boolean = activeCount < maxConcurrentSessions

    /**
     * Coalescing decision between an existing session's transport rank and a
     * new candidate's. Strictly-lower new rank wins ([DuplicateSessionDecision.PreferNew]);
     * equal or worse keeps the incumbent (documented tie behavior).
     */
    public fun resolveDuplicate(existingTransportRank: Int, newTransportRank: Int): DuplicateSessionDecision =
        if (newTransportRank < existingTransportRank) {
            DuplicateSessionDecision.PreferNew
        } else {
            DuplicateSessionDecision.KeepExisting
        }

    /** Convenience overload resolving ranks from [FlashTransportType]. */
    public fun resolveDuplicate(
        existingTransport: FlashTransportType,
        newTransport: FlashTransportType,
    ): DuplicateSessionDecision =
        resolveDuplicate(transportRank(existingTransport), transportRank(newTransport))

    public companion object {
        /**
         * Admission ceiling for live sessions (ADR-057): 19 group peers of a 20-member group, one call or
         * transfer peer outside it, one pairing peer, and slack for a duplicate being replaced and a
         * reconnect in flight. `ConnectionModePolicy.DIAL_HEADROOM` of these are never spent on dials the
         * device chooses to make, so an incoming contact still fits.
         */
        public const val DEFAULT_MAX_CONCURRENT_SESSIONS: Int = 24

        public const val TRANSPORT_RANK_LAN: Int = 0
        public const val TRANSPORT_RANK_WIFI_DIRECT: Int = 1
        public const val TRANSPORT_RANK_WEBSOCKET: Int = 2
        public const val TRANSPORT_RANK_RELAY_CLASS: Int = 3 // relay/mesh/BLE-presence (post-v1 paths)
        public const val TRANSPORT_RANK_UNKNOWN: Int = 99

        public fun transportRank(transport: FlashTransportType): Int = when (transport) {
            FlashTransportType.LAN -> TRANSPORT_RANK_LAN
            FlashTransportType.WIFI_DIRECT -> TRANSPORT_RANK_WIFI_DIRECT
            FlashTransportType.WEBSOCKET -> TRANSPORT_RANK_WEBSOCKET
            FlashTransportType.RELAY, FlashTransportType.MESH -> TRANSPORT_RANK_RELAY_CLASS
            FlashTransportType.UNKNOWN -> TRANSPORT_RANK_UNKNOWN
        }
    }
}
