package com.transfer.flash.core.common.model

/**
 * What KIND of device a peer is, as the Nearby list needs to show it.
 *
 * ## Why this is not derived from the model string
 *
 * `FlashAdvertisedIdentity.deviceModel` (in `:core:discovery`) already rides every transport — but it is a *display*
 * string, and the two platforms fill it from different worlds: Android sends `Build.MODEL`
 * ("Pixel 9 Pro", "V760", "SM-A546E") and the desktop sends the literal `"Desktop"`. Classifying
 * those would mean pattern-matching marketing names, which is guesswork that silently rots the first
 * time a device is named something unexpected.
 *
 * Instead each host declares itself, using the `caps` field that the cross-radio TXT contract
 * already defines and already carries on all three transports (NSD, JmDNS, UDP multicast). No key is
 * added to any wire format — only a value — so an unmodified peer sends no flag, decodes as
 * [UNKNOWN], and shows no badge rather than a wrong one.
 *
 * That degradation is the point: **an absent flag is honest, a guessed flag is not.**
 */
public enum class FlashDeviceKind {
    /** A desktop/laptop host — `:desktop`, and the interop harness. */
    DESKTOP,

    /** A handheld — the Android app. */
    PHONE,

    /** Not advertised (an older build), or a flag this build does not recognise. */
    UNKNOWN,

    ;

    public companion object {
        /** Capability flag a desktop host advertises. Values are trimmed and case-sensitive. */
        public const val CAP_DESKTOP: String = "desktop"

        /** Capability flag the Android app advertises, for symmetry and for desktop-side display. */
        public const val CAP_MOBILE: String = "mobile"

        /**
         * Classifies a peer from the capability flags it advertised.
         *
         * Total by construction: an empty set, an unrecognised flag, or both flags present all
         * resolve without throwing. When a peer somehow advertises both, [DESKTOP] wins — a desktop
         * that also claims to be mobile is far more likely to be a host running a desktop build than
         * a phone that can host a desktop session.
         */
        public fun fromCapabilities(capabilities: Set<String>): FlashDeviceKind {
            val normalised = capabilities.map(TxtCapabilities::normalise)
            return when {
                CAP_DESKTOP in normalised -> DESKTOP
                CAP_MOBILE in normalised -> PHONE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Normalisation for capability flags, kept private to this file: the wire is peer-controlled, so a
 * flag may arrive padded or in any case, and a classifier that is fussy about that would report
 * [FlashDeviceKind.UNKNOWN] for a correctly-advertised peer.
 */
private object TxtCapabilities {
    fun normalise(raw: String): String = raw.trim().lowercase()
}
