package com.transfer.flash.core.radio.connect

/**
 * The station identification Flash puts in the AX.25 source address (ERROR-684).
 *
 * Amateur-band rules require every transmission to carry the operator's callsign, so the gateway never invents
 * one: a transmission with no valid callsign is refused instead of going out as `NOCALL`. Accepted form:
 * 3..6 letters or digits with at least one of each, optionally followed by `-0`..`-15` (the AX.25 SSID).
 * Well-known placeholders (`NOCALL`, `N0CALL`, ...) are not accepted.
 */
public object RadioCallsign {
    private val PLACEHOLDERS = setOf("NOCALL", "N0CALL", "MYCALL", "CALL", "XXXXXX")

    /** The upper-case canonical form (`W1AW`, `W1AW-7`), or null when [input] is not an acceptable callsign. */
    public fun normalise(input: String?): String? {
        val t = input?.trim()?.uppercase() ?: return null
        val dash = t.indexOf('-')
        val base = if (dash < 0) t else t.substring(0, dash)
        val ssid = if (dash < 0) 0 else t.substring(dash + 1).toIntOrNull() ?: return null
        if (ssid !in 0..15) return null
        if (base.length !in 3..6) return null
        if (!base.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        if (base.none { it in 'A'..'Z' } || base.none { it in '0'..'9' }) return null
        if (base in PLACEHOLDERS) return null
        return if (ssid == 0) base else "$base-$ssid"
    }

    public fun isValid(input: String?): Boolean = normalise(input) != null
}
