package com.transfer.flash.core.engine

/**
 * Shared filesystem path sanitizer and traversal guard (AGENTS.md §19).
 * Preserves directory structure for folder transfers while guarding against path traversal,
 * Windows reserved device names, illegal characters, and excessive length.
 */
public object FlashPathSanitizer {

    private val ILLEGAL_CHARS_REGEX = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

    /**
     * Windows device names. They are reserved with or without an extension and regardless of trailing spaces or dots
     * ("CON", "con.txt", "CON .txt", "CON."). COM0/LPT0 are included although current Windows documentation lists only
     * 1-9, because older builds and several libraries reject them; the superscript digits are listed by Microsoft too.
     */
    private val WINDOWS_RESERVED_NAMES = buildSet {
        add("CON"); add("PRN"); add("AUX"); add("NUL"); add("CONIN$"); add("CONOUT$")
        for (device in listOf("COM", "LPT")) {
            for (digit in "0123456789") add("$device$digit")
            for (digit in "¹²³") add("$device$digit")
        }
    }

    /** Hard cap on characters (code points) in one path component. */
    private const val MAX_COMPONENT_CODE_POINTS = 120

    /**
     * Cap on the UTF-8 length of one component. File systems limit a name to 255 BYTES (ext4, NTFS counts UTF-16 units but
     * Android's FUSE layer and many tools count bytes); 200 leaves room for a " (12)" collision suffix and a ".part".
     */
    private const val MAX_COMPONENT_UTF8_BYTES = 200

    /**
     * Strips anything that could escape the intended directory or cause filesystem errors.
     * Preserves Unicode (e.g. Arabic, CJK, accented chars), spaces, file extensions on truncation,
     * and guards Windows reserved device names.
     */
    public fun sanitize(component: String): String = sanitizeComponent(component, "unnamed")

    /**
     * Sanitizes a single file NAME (not a path) for a storage that picks the name itself: surrounding whitespace is
     * trimmed first and an unusable name becomes `received.bin`. Same rules as [sanitize] otherwise.
     */
    public fun sanitizeFileName(raw: String): String = sanitizeComponent(raw.trim(), "received.bin")

    private fun sanitizeComponent(component: String, fallback: String): String {
        val replaced = replaceLoneSurrogates(component.replace(ILLEGAL_CHARS_REGEX, "_")).trimEnd('.', ' ')
        if (replaced.isBlank() || replaced == "." || replaced == "..") return fallback

        val safe = if (isReservedDeviceName(replaced)) "_$replaced" else replaced
        return truncatePreservingExtension(safe)
    }

    /** A lone surrogate is not valid text and encodes to '?' on most file systems; make the replacement explicit. */
    private fun replaceLoneSurrogates(s: String): String {
        if (s.none { it.isSurrogate() }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                sb.append(c).append(s[i + 1])
                i += 2
            } else {
                sb.append(if (c.isSurrogate()) '_' else c)
                i++
            }
        }
        return sb.toString()
    }

    /** True when [name] (already free of separators) names a Windows device, with or without an extension. */
    internal fun isReservedDeviceName(name: String): Boolean {
        val dotIdx = name.indexOf('.')
        val baseName = if (dotIdx != -1) name.substring(0, dotIdx) else name
        // "CON .txt" and "CON." name the device too: Windows drops trailing spaces and dots from the base name.
        return baseName.trimEnd(' ', '.').uppercase() in WINDOWS_RESERVED_NAMES
    }

    /**
     * Sanitizes a relative file path (potentially with subdirectories from a folder transfer)
     * while strictly guarding against path traversal (AGENTS.md §19).
     */
    public fun sanitizeRelativePath(raw: String, separator: String = "/"): String {
        val normalized = raw.replace('\\', '/').trim().trimStart('/')
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "unnamed"
        val safeSegments = mutableListOf<String>()
        for (seg in segments) {
            if (seg == "." || seg == "..") continue
            val sanitized = sanitize(seg)
            if (sanitized.isNotBlank() && sanitized != "." && sanitized != "..") {
                safeSegments.add(sanitized)
            }
        }
        return if (safeSegments.isEmpty()) "unnamed" else safeSegments.joinToString(separator)
    }

    private fun truncatePreservingExtension(name: String): String {
        if (fits(name)) return name
        val lastDot = name.lastIndexOf('.')
        if (lastDot > 0 && lastDot < name.length - 1 && (name.length - lastDot) <= 16) {
            val ext = name.substring(lastDot)
            val base = cutToBudget(
                name.substring(0, lastDot),
                maxCodePoints = (MAX_COMPONENT_CODE_POINTS - codePointCount(ext)).coerceAtLeast(1),
                maxBytes = (MAX_COMPONENT_UTF8_BYTES - utf8Length(ext)).coerceAtLeast(4),
            ).trimEnd('.', ' ')
            return (if (base.isEmpty()) "unnamed" else base) + ext
        }
        val cut = cutToBudget(name, MAX_COMPONENT_CODE_POINTS, MAX_COMPONENT_UTF8_BYTES).trimEnd('.', ' ')
        return if (cut.isEmpty()) "unnamed" else cut
    }

    private fun fits(name: String): Boolean =
        codePointCount(name) <= MAX_COMPONENT_CODE_POINTS && utf8Length(name) <= MAX_COMPONENT_UTF8_BYTES

    /** The longest prefix of [s] within both budgets that ends on a code point boundary (never splits a surrogate pair). */
    private fun cutToBudget(s: String, maxCodePoints: Int, maxBytes: Int): String {
        var i = 0
        var points = 0
        var bytes = 0
        while (i < s.length) {
            val c = s[i]
            val isPair = c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
            val units = if (isPair) 2 else 1
            val cost = utf8Cost(c, isPair)
            if (points + 1 > maxCodePoints || bytes + cost > maxBytes) break
            points++
            bytes += cost
            i += units
        }
        return s.substring(0, i)
    }

    private fun codePointCount(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    /** UTF-8 length of [s]; a lone surrogate counts as the 3 bytes of its replacement-style encoding. */
    internal fun utf8Length(s: String): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val isPair = c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
            bytes += utf8Cost(c, isPair)
            i += if (isPair) 2 else 1
        }
        return bytes
    }

    private fun utf8Cost(c: Char, isPair: Boolean): Int = when {
        isPair -> 4
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        else -> 3
    }
}
