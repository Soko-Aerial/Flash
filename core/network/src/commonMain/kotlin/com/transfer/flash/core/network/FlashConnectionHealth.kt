package com.transfer.flash.core.network

/**
 * Aggregated connection health (plan C4.7) — the single input the finished
 * connection banner (UI-030) and network-simulation UI (UI-044) derive their
 * display from, via `FlashNetworkStatusMath.resolveHealth` on the UI side.
 *
 * Lifecycle: OFFLINE → CONNECTING → CONNECTED; DEGRADED marks connected-but-
 * impaired paths (relay/lossy link — populated post-v1 with D5 mesh).
 */
public enum class FlashConnectionHealth {
    /** No reachable peers and no active sessions. */
    Offline,

    /// Discovery sees peers or a connect attempt is in flight.
    Connecting,

    /// At least one healthy session; normal operation.
    Connected,

    /** Connected over an impaired path (high loss / relay hop). */
    Degraded,
}
