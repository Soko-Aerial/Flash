package com.transfer.flash.core.calling.model

/**
 * Where a call's sound comes out (ADR-067, UI-050f). Platform audio routing is the host's job (ADR-025): Android
 * maps its `AudioDeviceInfo` types onto these four, the desktop has no choice to offer and shows none.
 */
public enum class FlashCallAudioRoute {
    /** The phone's earpiece: held against the ear. */
    EARPIECE,

    /** The loudspeaker. */
    SPEAKER,

    /** A Bluetooth headset, earbuds or hearing aid. */
    BLUETOOTH,

    /** A wired or USB headset or headphones. */
    WIRED,
}

/**
 * The routes this device can use right now and the one in force, as the host reports them. [available] is in the order
 * a picker lists them.
 */
public data class FlashCallAudioRoutes(
    public val available: List<FlashCallAudioRoute> = emptyList(),
    public val active: FlashCallAudioRoute? = null,
) {
    /**
     * True when the route button should open a list instead of flipping between two outputs: a headset is in play, so
     * "speaker or earpiece" is no longer the whole question.
     */
    public val needsPicker: Boolean
        get() = available.any { it == FlashCallAudioRoute.BLUETOOTH || it == FlashCallAudioRoute.WIRED }
}

/** The rule that turns what is plugged in and what the user asked for into one route. Pure, so it is unit-tested. */
public object FlashCallAudioRouting {

    /**
     * Picks the route to use.
     *
     * - [chosen], the user's explicit pick, wins while it is [available]. When it disappears (a headset unplugged) the
     *   call falls back to the automatic choice instead of going silent, and the pick is forgotten by the caller.
     * - Automatic: a Bluetooth headset, then a wired one, then the earpiece, and the speaker on a device with no
     *   earpiece (a tablet).
     * - Nothing available at all: null, which the host reads as "leave the platform to decide".
     */
    public fun resolve(available: Collection<FlashCallAudioRoute>, chosen: FlashCallAudioRoute?): FlashCallAudioRoute? {
        if (chosen != null && chosen in available) return chosen
        return AUTOMATIC_ORDER.firstOrNull { it in available }
    }

    /** Whether [chosen] can still be honoured; false means the host should drop it and report the automatic route. */
    public fun stillValid(available: Collection<FlashCallAudioRoute>, chosen: FlashCallAudioRoute?): Boolean =
        chosen == null || chosen in available

    /** The list a picker shows: only what exists, always in the same order. */
    public fun pickerOrder(available: Collection<FlashCallAudioRoute>): List<FlashCallAudioRoute> =
        PICKER_ORDER.filter { it in available }

    /**
     * Whether the screen should turn off while the phone is against the ear (the proximity sensor, ADR-067): a live
     * voice call (nothing to look at) that plays through the earpiece. [active] is the route the host reports; a host that
     * reports none falls back to [speakerOn].
     */
    public fun screenOffAgainstEar(
        video: Boolean,
        live: Boolean,
        active: FlashCallAudioRoute?,
        speakerOn: Boolean,
    ): Boolean = live && !video && (active ?: if (speakerOn) FlashCallAudioRoute.SPEAKER else FlashCallAudioRoute.EARPIECE) == FlashCallAudioRoute.EARPIECE

    private val AUTOMATIC_ORDER = listOf(
        FlashCallAudioRoute.BLUETOOTH,
        FlashCallAudioRoute.WIRED,
        FlashCallAudioRoute.EARPIECE,
        FlashCallAudioRoute.SPEAKER,
    )

    private val PICKER_ORDER = listOf(
        FlashCallAudioRoute.EARPIECE,
        FlashCallAudioRoute.SPEAKER,
        FlashCallAudioRoute.BLUETOOTH,
        FlashCallAudioRoute.WIRED,
    )
}
