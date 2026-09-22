package com.transfer.flash.core.common.perf

/**
 * How much work this device is allowed to do (ERROR-033).
 *
 * Flash runs on a wider hardware range than a chat app usually has to care about. The
 * reference phones — a Pixel 7, an Infinix — carry a 5 GHz radio, a hardware video encoder and
 * enough CPU that the stack's defaults are simply correct. The other end of the range is a
 * rugged PTT handset (BelFone SCP810: 2 GB RAM, Android 8.1, a 480x640 screen and a
 * 2.4 GHz-only b/g/n radio) and, below that, a wearable. On those, the same defaults do not
 * merely run slower — they fail differently: capture at 1080p30 saturates the CPU before the
 * link is even tested, 10 ms Opus packets spend airtime the radio does not have, and animation
 * work competes with the media threads for the same two or four cores.
 *
 * This enum is the single place that decides which of those defaults bend. It is deliberately
 * a *policy table* rather than a set of booleans scattered across modules: every consumer reads
 * one of the profiles below, so the whole tiering is auditable in one file and a tier can be
 * retuned from field measurements without touching call, transport or UI code.
 *
 * ## Not a quality setting
 *
 * A tier is a statement about the **device**, not about the user's taste. It is resolved once
 * from hardware ([FlashPerformanceClassifier]) and then persisted, and the user may pin it —
 * but nothing in the app raises or lowers it in reaction to a bad second. That is the adaptive
 * governor's job (`CallQualityGovernor`), which operates *inside* the envelope a tier defines.
 * Keeping the two separate is what stops a device from oscillating between two control loops.
 *
 * ## Why [LOW] and [MEDIUM] share the UI treatment
 *
 * Both disable animation outright (see [reduceMotion]). The threshold for "can this device
 * animate" is far higher than the threshold for "can this device encode video": a 2.4 GHz
 * handset that manages 540p perfectly well still drops frames scrolling a message list,
 * because Compose animation and the encoder want the same cores. So the UI tiering is binary
 * where the media tiering is graded.
 *
 * Pure Kotlin, no platform types: this is `commonMain` so `:ui:theme`, `:core:calling` and
 * `:core:network` can all speak the same vocabulary without depending on each other.
 */
public enum class FlashPerformanceMode {
    /**
     * Rugged/PTT handsets, 2 GB-class devices, wearables, anything the platform itself calls
     * low-RAM. Extreme minimalism: no animation, 360p video, 60 ms voice packets with DTX.
     */
    LOW,

    /**
     * Mid-range and older flagship hardware. Animation still off — the cores are needed
     * elsewhere — but media is only capped, not stripped: 540p at 24 fps and ordinary 20 ms
     * voice packets.
     */
    MEDIUM,

    /**
     * Current phones. Every default in the stack applies unchanged; this tier exists so that
     * "High" is provably a no-op rather than a fourth set of numbers to keep in sync.
     */
    HIGH,
    ;

    /**
     * Whether the UI must render without animation — the same switch the accessibility
     * reduce-motion setting drives, reused rather than duplicated so all existing
     * `FlashMotion` consumers are covered at once.
     */
    public val reduceMotion: Boolean get() = this != HIGH

    /**
     * Whether to drop decorative-but-costly chrome beyond animation: gradient washes, blur
     * layers, elevation shadows, staggered list entry. "Extreme minimalist" in the tier's own
     * terms — the screen still says everything it said before, with fewer draw passes to say it.
     */
    public val minimalChrome: Boolean get() = this != HIGH

    /** Camera capture + video encode envelope for a call placed on this tier. */
    public val video: FlashVideoProfile
        get() = when (this) {
            LOW -> FlashVideoProfile.LOW
            MEDIUM -> FlashVideoProfile.MEDIUM
            HIGH -> FlashVideoProfile.HIGH
        }

    /** Opus packetization + bitrate envelope for a call placed on this tier. */
    public val voice: FlashVoiceProfile
        get() = when (this) {
            LOW -> FlashVoiceProfile.LOW
            MEDIUM -> FlashVoiceProfile.MEDIUM
            HIGH -> FlashVoiceProfile.HIGH
        }

    /** Signaling keepalive + reconnect envelope for this tier. */
    public val transport: FlashTransportProfile
        get() = when (this) {
            LOW -> FlashTransportProfile.LOW
            MEDIUM -> FlashTransportProfile.MEDIUM
            HIGH -> FlashTransportProfile.HIGH
        }

    /** File-transfer chunking, concurrency, and buffer envelope for this tier. */
    public val transfer: FlashTransferProfile
        get() = when (this) {
            LOW -> FlashTransferProfile.LOW
            MEDIUM -> FlashTransferProfile.MEDIUM
            HIGH -> FlashTransferProfile.HIGH
        }

    /** Stable persistence token. Never derived from [name] — see [Keys]. */
    public val key: String
        get() = when (this) {
            LOW -> Keys.LOW
            MEDIUM -> Keys.MEDIUM
            HIGH -> Keys.HIGH
        }

    /**
     * Persistence tokens, spelled out rather than taken from [Enum.name].
     *
     * The stored value is user data: renaming an enum constant must not silently reset a
     * device to auto-detection, and lowercase tokens match the convention the rest of
     * `FlashSettingsDataStore` already uses for its string keys.
     */
    public object Keys {
        /**
         * "Decide from the hardware." The shipped default, and the reason the modes
         * "detect automatically on first run" without a first-run flag anywhere: an
         * unset preference *is* auto, and auto is resolved on every boot.
         */
        public const val AUTO: String = "auto"
        public const val LOW: String = "low"
        public const val MEDIUM: String = "medium"
        public const val HIGH: String = "high"
    }

    public companion object {
        /**
         * Parses a persisted token, or returns null for [Keys.AUTO] **and for anything
         * unrecognised**.
         *
         * Both map to null on purpose: null means "classify the hardware", which is the right
         * answer for a value this build does not understand — a downgrade that meets a token
         * written by a newer build re-detects instead of guessing a tier.
         */
        public fun fromKey(value: String?): FlashPerformanceMode? = when (value?.lowercase()) {
            Keys.LOW -> LOW
            Keys.MEDIUM -> MEDIUM
            Keys.HIGH -> HIGH
            else -> null
        }

        /** Token for a pinned [mode], or [Keys.AUTO] when null (auto-detect). */
        public fun toKey(mode: FlashPerformanceMode?): String = mode?.key ?: Keys.AUTO
    }
}
