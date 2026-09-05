package com.transfer.flash.core.discovery.core

/**
 * Pure policy table translating a [FlashDiscoveryMode] into concrete knobs
 * (P3.5/D-M2). Single source of truth so every mode decision is one place,
 * exhaustively unit-testable, with no hidden branching in transports.
 *
 * Knob semantics:
 * - [advertises]: whether this device is visible to others (GHOST = false).
 *   Browsing happens in every mode.
 * - [browseDutyCycleMs]/[idleDutyCycleMs]: ECO duty cycle. Non-ECO modes browse
 *   continuously (duty cycle null).
 * - [restartBackoffBaseMs]: base for the capped restart backoff; BOOST lowers it.
 * - [capabilityFlags]: TXT `caps` tokens advertised for the mode (consumers may
 *   filter peers by them; RECEIVE_KIOSK advertises `kiosk` willingness).
 */
public data class DiscoveryModePolicy(
    val mode: FlashDiscoveryMode,
    val advertises: Boolean,
    val browseDutyCycleMs: Long?,
    val idleDutyCycleMs: Long?,
    val restartBackoffBaseMs: Long,
    val capabilityFlags: Set<String>,
) {
    public companion object {
        public const val CAP_KIOSK: String = "kiosk"

        public fun forMode(mode: FlashDiscoveryMode): DiscoveryModePolicy = when (mode) {
            FlashDiscoveryMode.STANDARD -> DiscoveryModePolicy(
                mode = mode,
                advertises = true,
                browseDutyCycleMs = null,
                idleDutyCycleMs = null,
                restartBackoffBaseMs = DEFAULT_BACKOFF_BASE_MS,
                capabilityFlags = emptySet(),
            )

            FlashDiscoveryMode.GHOST -> DiscoveryModePolicy(
                mode = mode,
                advertises = false,
                browseDutyCycleMs = null,
                idleDutyCycleMs = null,
                restartBackoffBaseMs = DEFAULT_BACKOFF_BASE_MS,
                capabilityFlags = emptySet(),
            )

            FlashDiscoveryMode.BOOST -> DiscoveryModePolicy(
                mode = mode,
                advertises = true,
                browseDutyCycleMs = null,
                idleDutyCycleMs = null,
                restartBackoffBaseMs = BOOST_BACKOFF_BASE_MS,
                capabilityFlags = emptySet(),
            )

            FlashDiscoveryMode.ECO -> DiscoveryModePolicy(
                mode = mode,
                advertises = true,
                // 20 s scan burst / 100 s idle: presence converges < ~2 min worst
                // case while radio-active time drops ~80% vs continuous browsing.
                browseDutyCycleMs = ECO_BROWSE_MS,
                idleDutyCycleMs = ECO_IDLE_MS,
                restartBackoffBaseMs = DEFAULT_BACKOFF_BASE_MS,
                capabilityFlags = emptySet(),
            )

            FlashDiscoveryMode.RECEIVE_KIOSK -> DiscoveryModePolicy(
                mode = mode,
                advertises = true,
                browseDutyCycleMs = null,
                idleDutyCycleMs = null,
                restartBackoffBaseMs = DEFAULT_BACKOFF_BASE_MS,
                capabilityFlags = setOf(CAP_KIOSK),
            )
        }

        public const val DEFAULT_BACKOFF_BASE_MS: Long = 1_000L
        public const val BOOST_BACKOFF_BASE_MS: Long = 250L
        public const val ECO_BROWSE_MS: Long = 20_000L
        public const val ECO_IDLE_MS: Long = 100_000L
    }
}
