package com.transfer.flash.core.discovery.core

/**
 * User-selectable discovery behavior modes (plan P3.5/D-M2).
 *
 * Modes are policy presets, not separate code paths: [DiscoveryModePolicy]
 * translates a mode into concrete knobs (advertise? duty cycle? backoff
 * base? auto-accept?) that the transport/composite layers honor.
 */
public enum class FlashDiscoveryMode {
    /** Advertise + continuous browse. Default behavior. */
    STANDARD,

    /** Browse-only: see others without being advertised yourself. */
    GHOST,

    /** Crowded/flaky networks: aggressive re-announce cadence + fast restart backoff. */
    BOOST,

    /** Battery-conscious: duty-cycled browsing (scan burst, idle gap, repeat). */
    ECO,

    /**
     * Hands-out kiosk: advertise prominently + signal that incoming transfers
     * from TRUSTED peers may be auto-accepted (the auto-accept itself is a C5
     * receive-policy decision; this mode only advertises willingness via caps).
     */
    RECEIVE_KIOSK,
}
