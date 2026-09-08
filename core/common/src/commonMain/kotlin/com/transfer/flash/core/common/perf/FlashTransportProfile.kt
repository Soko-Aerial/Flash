package com.transfer.flash.core.common.perf

/**
 * Signaling-keepalive, reconnect and call-recovery timings for one [FlashPerformanceMode].
 *
 * ## What the field report actually said
 *
 * "The Pixel and Infinix recover fine but it doesn't for the BelFone." The mesh in question is
 * several APs presenting one SSID, so a walking user roams between them. The interesting part is
 * the word *recover*: every device loses the link during a roam. What separates them is what
 * happens next.
 *
 * On a 2.4 GHz-only b/g/n client with no 802.11k/v/r fast-transition support, a roam is a full
 * scan, reassociation and DHCP — seconds, not milliseconds. And because the roam keeps the same
 * Android `Network` object, `ConnectivityManager.onAvailable` never fires, so nothing in the app
 * used to learn that the link had moved (see `AndroidNetworkWatcher`). The session was left to
 * die of its own watchdog and then to be redialled by a backoff loop whose ceiling is 30
 * seconds. Two seconds of radio outage therefore turned into up to half a minute of "offline".
 *
 * So the tier knobs here are not mainly about *patience* — the existing 25 s liveness window
 * already survives an ordinary roam. They are about **how fast the stack gets back**, which is
 * [reconnectCapMs], plus giving a call's ICE restart enough room to complete
 * ([callDisconnectGraceMs]).
 *
 * ## Confidence
 *
 * [reconnectCapMs] and [callDisconnectGraceMs] are derived from the mechanism and are the ones
 * this tiering is confident about. [pingIntervalMs] is a battery/airtime judgement rather than a
 * measured one and is the first number to revisit against field data — a longer interval saves
 * radio wake-ups but gives the watchdog fewer inbound frames to see.
 *
 * @param pingIntervalMs application-level keepalive period on a WS session.
 * @param livenessTimeoutMs silence after which a session is declared dead. Must stay comfortably
 *   above `pingIntervalMs * WsKeepalive.STALL_FACTOR`, or a merely-late tick becomes a verdict.
 * @param reconnectCapMs ceiling on the reconnect backoff. The single most load-bearing number
 *   for "does this device come back".
 * @param linkChangeProbeMs after a link change is detected, how long a session gets to prove it
 *   still carries traffic before it is treated as a casualty of the roam and reaped.
 * @param callDisconnectGraceMs how long a call may sit in ICE `Disconnected` before it is
 *   declared lost. Sized to cover an ICE restart, which must in turn wait for signaling to come
 *   back on the new AP.
 * @param callConnectTimeoutMs ceiling on a call's CONNECTING phase.
 * @param callStatsIntervalMs `getStats()` sampling period. Also the adaptive governor's tick, so
 *   a slower sample makes the governor correspondingly slower to react.
 * @param iceRestartMinIntervalMs floor on the gap between two ICE restart attempts, so a link
 *   that flaps cannot turn into an offer/answer storm.
 */
public data class FlashTransportProfile(
    public val pingIntervalMs: Long,
    public val livenessTimeoutMs: Long,
    public val reconnectCapMs: Long,
    public val linkChangeProbeMs: Long,
    public val callDisconnectGraceMs: Long,
    public val callConnectTimeoutMs: Long,
    public val callStatsIntervalMs: Long,
    public val iceRestartMinIntervalMs: Long,
) {
    public companion object {
        /**
         * Slow radio, slow scheduler, long roams.
         *
         * The backoff ceiling drops to 8 s — a device that takes this long to reassociate is
         * exactly the device that must not then wait half a minute for its next attempt. The
         * call grace window is the longest of the three because signaling has to be back before
         * an ICE restart offer can even be sent, and on this hardware that is measured in
         * seconds. Stats sampling halves to 2 s: the governor reacts more slowly, but a
         * once-a-second `getStats()` pass is itself a measurable cost here.
         */
        public val LOW: FlashTransportProfile = FlashTransportProfile(
            pingIntervalMs = 15_000L,
            livenessTimeoutMs = 40_000L,
            reconnectCapMs = 8_000L,
            linkChangeProbeMs = 4_000L,
            callDisconnectGraceMs = 25_000L,
            callConnectTimeoutMs = 45_000L,
            callStatsIntervalMs = 2_000L,
            iceRestartMinIntervalMs = 4_000L,
        )

        public val MEDIUM: FlashTransportProfile = FlashTransportProfile(
            pingIntervalMs = 12_000L,
            livenessTimeoutMs = 32_000L,
            reconnectCapMs = 15_000L,
            linkChangeProbeMs = 3_000L,
            callDisconnectGraceMs = 16_000L,
            callConnectTimeoutMs = 30_000L,
            callStatsIntervalMs = 1_000L,
            iceRestartMinIntervalMs = 3_000L,
        )

        /**
         * The stack's shipped keepalive and reconnect numbers, unchanged.
         *
         * One exception, and it is not a tiering decision: [callDisconnectGraceMs] was 5 s, which
         * predates there being any recovery to wait for — `Disconnected` simply ended the call.
         * Now that ICE is restarted on that transition, a window shorter than the restart makes
         * the restart pointless, so it grows at every tier including this one.
         */
        public val HIGH: FlashTransportProfile = FlashTransportProfile(
            pingIntervalMs = 10_000L,
            livenessTimeoutMs = 25_000L,
            reconnectCapMs = 30_000L,
            linkChangeProbeMs = 2_500L,
            callDisconnectGraceMs = 12_000L,
            callConnectTimeoutMs = 30_000L,
            callStatsIntervalMs = 1_000L,
            iceRestartMinIntervalMs = 3_000L,
        )
    }
}
