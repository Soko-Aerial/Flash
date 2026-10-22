package com.transfer.flash.server.directory

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan A2 helpers that do not belong in [OrganizationDirectory]: opaque cursor
 * codec, query bounds, and the per-device miss limiter for the single lookup.
 *
 * Cursor shape (opaque to clients): Base64url of `v1|<length>:<foldedName>|<deviceId>`
 * (the name is length-prefixed because it may contain `|`; 2026-10-22 review F2).
 * A malformed cursor is a 400, never a 500. Search ranking never leaks hidden
 * people: outsiders simply get zero rows with an identical page shape.
 */
object DirectoryPrivacy {
    const val PEOPLE_DEFAULT_LIMIT: Int = 100
    const val PEOPLE_MAX_LIMIT: Int = 500
    const val PEOPLE_MAX_QUERY_CHARS: Int = 64

    fun clampLimit(raw: Int?): Int = (raw ?: PEOPLE_DEFAULT_LIMIT).coerceIn(1, PEOPLE_MAX_LIMIT)

    /** Validates `q`: over-long queries are refused (400), `%`/`_` are treated literally by exact matching. */
    fun cleanQuery(raw: String?): String? {
        if (raw == null) return null
        if (raw.length > PEOPLE_MAX_QUERY_CHARS) return null
        val folded = raw.trim().lowercase()
        if (folded.any { it.code < 0x20 || it.code == 0x7F }) return null
        return folded
    }

    /**
     * Opaque people cursor. The folded display name is LENGTH-PREFIXED inside the payload: a display name may legitimately
     * contain `|` ("Alice | Engineering"), and splitting on `|` cut such a name in half, so the next page started before the
     * row it was meant to follow — the same person came back on every page and a client that follows the cursor never
     * finished (2026-10-22 review F2).
     */
    fun encodeCursor(foldedName: String, deviceId: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("v1|${foldedName.length}:$foldedName|$deviceId".toByteArray(Charsets.UTF_8))

    data class PeopleCursor(val foldedName: String, val deviceId: String)

    /**
     * Decodes [encodeCursor]'s output; a malformed cursor is null (the route answers 400).
     *
     * A cursor written by an older hub build (`v1|name|deviceId`, unframed) is still accepted, so a page in flight when the hub
     * is upgraded does not suddenly fail. Ambiguity there is unavoidable — that unframed shape is exactly the defect.
     */
    fun decodeCursor(raw: String?): PeopleCursor? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
            if (!decoded.startsWith(CURSOR_TAG)) return null
            val rest = decoded.removePrefix(CURSOR_TAG)
            if (looksFramed(rest)) parseFramed(rest) else parseLegacy(decoded)
        }.getOrNull()
    }

    private const val CURSOR_TAG = "v1|"

    /** `<digits>:` at the start: the framing [encodeCursor] writes. */
    private fun looksFramed(rest: String): Boolean {
        val colon = rest.indexOf(':')
        if (colon <= 0 || colon > 9) return false
        return rest.substring(0, colon).all { it in '0'..'9' }
    }

    private fun parseFramed(rest: String): PeopleCursor? {
        val colon = rest.indexOf(':')
        val length = rest.substring(0, colon).toIntOrNull() ?: return null
        val start = colon + 1
        if (length < 0 || start + length >= rest.length) return null
        if (rest[start + length] != '|') return null
        val name = rest.substring(start, start + length)
        val deviceId = rest.substring(start + length + 1)
        return valid(name, deviceId)
    }

    private fun parseLegacy(decoded: String): PeopleCursor? {
        val parts = decoded.split("|", limit = 3)
        if (parts.size != 3 || parts[0] != "v1") return null
        return valid(parts[1], parts[2])
    }

    private fun valid(name: String, deviceId: String): PeopleCursor? {
        if (deviceId.isBlank() || deviceId.length > 128) return null
        return PeopleCursor(name, deviceId)
    }

    /**
     * Miss limiter for `GET /directory/people/{id}`: 60 lookups/min/device, of
     * which at most 10 may be misses (blunts id guessing; ids are random
     * 128-bit so enumeration is infeasible anyway).
     */
    class LookupMissLimiter(
        private val maxLookupsPerMinute: Int = 60,
        private val maxMissesPerMinute: Int = 10,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private data class Window(var lookups: Int, var misses: Int, var windowStartMs: Long)
        private val windows = ConcurrentHashMap<String, Window>()

        @Synchronized
        fun check(key: String, isMiss: Boolean): Boolean {
            val now = clock()
            val w = windows.computeIfAbsent(key) { Window(0, 0, now) }
            if (now - w.windowStartMs >= 60_000L) {
                w.lookups = 0
                w.misses = 0
                w.windowStartMs = now
            }
            if (w.lookups >= maxLookupsPerMinute) return false
            if (isMiss && w.misses >= maxMissesPerMinute) return false
            w.lookups++
            if (isMiss) w.misses++
            return true
        }
    }
}
