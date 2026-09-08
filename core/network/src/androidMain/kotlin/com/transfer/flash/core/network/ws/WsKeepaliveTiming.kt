package com.transfer.flash.core.network.ws

/**
 * The two keepalive numbers a [WsConnection] needs, bundled so they travel together (ERROR-033).
 *
 * They are only meaningful as a pair: [livenessTimeoutMs] must stay comfortably above
 * `pingIntervalMs * WsKeepalive.STALL_FACTOR`, or a merely-late tick — which is the normal
 * condition on a throttled device — becomes a verdict that the peer is dead. Passing them as two
 * loose longs through the client and server was the shape most likely to end up with one of them
 * tiered and the other not.
 *
 * @param pingIntervalMs application-level keepalive period.
 * @param livenessTimeoutMs silence after which the session is declared dead.
 */
public data class WsKeepaliveTiming(
    public val pingIntervalMs: Long,
    public val livenessTimeoutMs: Long,
) {
    init {
        require(livenessTimeoutMs > pingIntervalMs * WsKeepalive.STALL_FACTOR) {
            "livenessTimeoutMs ($livenessTimeoutMs) must exceed " +
                "pingIntervalMs ($pingIntervalMs) * STALL_FACTOR (${WsKeepalive.STALL_FACTOR})"
        }
    }

    public companion object {
        /** [WsConnection]'s shipped numbers: 10 s ping, 25 s liveness. */
        public val DEFAULT: WsKeepaliveTiming = WsKeepaliveTiming(
            pingIntervalMs = WsConnection.DEFAULT_PING_INTERVAL_MS,
            livenessTimeoutMs = WsConnection.DEFAULT_LIVENESS_TIMEOUT_MS,
        )
    }
}
