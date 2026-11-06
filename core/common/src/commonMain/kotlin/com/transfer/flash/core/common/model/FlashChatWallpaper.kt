package com.transfer.flash.core.common.model

/**
 * The chat wallpaper chosen for this device (ADR-355, option a: one app-wide choice).
 *
 * The enum is the persisted vocabulary only; how each wallpaper is drawn belongs to the UI
 * (`ui/chat/FlashChatWallpaper.kt`), so a future preset is a UI addition plus one enum entry and
 * `:core:common` never learns a colour. [storageKey] is migration-sensitive: renaming one silently
 * resets a user's choice, so treat these strings like `FlashSettingsDataStore`'s keys.
 */
public enum class FlashChatWallpaper(public val storageKey: String) {
    /** Flat `graphite100` / `surface0`: the shipped background, now the default. */
    Graphite("graphite"),

    /** The device mesh as a dot grid. */
    Signal("signal"),

    /** A surveyor's hatch. */
    Blueprint("blueprint"),

    /** Graph paper for long reads. */
    Draft("draft"),

    /** The presence arcs from the corner. */
    Pulse("pulse"),

    /** A flat colour of the user's own (mock: "Yours"), stored in `chat_wallpaper_custom`. */
    Custom("custom"),
    ;

    public companion object {
        public val Default: FlashChatWallpaper = Graphite

        /** The mock's default "Yours" colour (Stone). Kept beside the keys so hosts cannot drift. */
        public const val DefaultCustomColour: String = "#E7E9EC"

        /** An absent, blank or unknown key reads as [Default] rather than failing a launch. */
        public fun fromStorageKey(key: String?): FlashChatWallpaper {
            val trimmed = key?.trim()
            return entries.firstOrNull { it.storageKey == trimmed } ?: Default
        }
    }
}
