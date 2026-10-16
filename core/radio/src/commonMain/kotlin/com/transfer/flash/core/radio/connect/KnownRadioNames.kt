package com.transfer.flash.core.radio.connect

/**
 * Data-driven registry of known radio names and models (Plan R §6).
 *
 * Checks names case-insensitively and tolerates Windows/serial port suffixes such as "(COM16)".
 */
public object KnownRadioNames {

    /**
     * Known radio model prefixes and identifiers.
     */
    public val KNOWN_PATTERNS: List<String> = listOf(
        "VR-N76",
        "UV-Pro",
        "UV-PRO",
        "GA-5WB",
        "HG-UV98",
        "TH-D72",
        "TH-D74",
        "FT-1D",
        "FT-2D",
        "FT-3D",
        "FT-5D",
    )

    /**
     * Determines whether [name] corresponds to a known radio model.
     * Tolerates trailing parenthesized expressions like "(COM16)" or " (COM5)".
     */
    public fun isKnownRadio(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        // Strip trailing "(COMn)" or any "(...)" suffix e.g. "VR-N76 (COM16)" -> "VR-N76"
        val stripped = name.replace(Regex("""\s*\([A-Za-z0-9_]+\)$"""), "").trim()
        val upper = stripped.uppercase()
        val rawUpper = name.uppercase()

        return KNOWN_PATTERNS.any { pattern ->
            val p = pattern.uppercase()
            upper.contains(p) || rawUpper.contains(p)
        }
    }
}
