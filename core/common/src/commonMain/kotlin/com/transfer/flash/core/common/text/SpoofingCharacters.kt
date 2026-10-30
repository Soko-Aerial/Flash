package com.transfer.flash.core.common.text

/**
 * Characters that change how a NAME is drawn without being drawn themselves (SEC-D04-01).
 *
 * A right-to-left override inside `photo_<RLO>fdp.exe` displays as `photo_exe.pdf`, and a zero-width space lets a peer
 * look like a contact it is not. They are refused in file names and peer display names. Message text is deliberately
 * not filtered: mixed-direction writing legitimately needs the isolates.
 *
 * Zero-width joiner and non-joiner (U+200D, U+200C) are kept: emoji sequences and Persian, Indic and other scripts need
 * them, and neither can reorder text.
 */
public object SpoofingCharacters {

    public fun isSpoofing(c: Char): Boolean = when (c.code) {
        0x200B, // zero-width space
        0x200E, 0x200F, // left-to-right and right-to-left marks
        0x061C, // Arabic letter mark
        in 0x202A..0x202E, // embeddings and overrides
        0x2060, // word joiner
        in 0x2066..0x2069, // isolates
        0xFEFF, // zero-width no-break space / byte order mark
        -> true
        else -> false
    }

    public fun containsSpoofing(s: String): Boolean = s.any(::isSpoofing)

    /** [s] without its spoofing characters. */
    public fun strip(s: String): String = if (containsSpoofing(s)) s.filterNot(::isSpoofing) else s
}
