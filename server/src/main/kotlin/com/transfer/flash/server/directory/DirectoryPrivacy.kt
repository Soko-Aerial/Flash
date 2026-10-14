package com.transfer.flash.server.directory

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan A2 helpers that do not belong in [OrganizationDirectory]: opaque cursor
 * codec, query bounds, and the per-device miss limiter for the single lookup.
 *
 * Cursor shape (opaque to clients): Base64url of `v1|<foldedName>|<deviceId>`.
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

    fun encodeCursor(foldedName: String, deviceId: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("v1|$foldedName|$deviceId".toByteArray())

    data class PeopleCursor(val foldedName: String, val deviceId: String)

    fun decodeCursor(raw: String?): PeopleCursor? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(raw))
            val parts = decoded.split("|", limit = 3)
            if (parts.size != 3 || parts[0] != "v1") return null
            if (parts[2].isBlank() || parts[2].length > 128) return null
            PeopleCursor(parts[1], parts[2])
        }.getOrNull()
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
