package com.transfer.flash.server.announcements

import java.text.Normalizer

/**
 * Sanitisation and validation of announcement title and body (Plan A3 §3.2, ADR-138).
 *
 * All announcement text is plain text: the server never interprets HTML or Markdown.
 * Input is normalised to Unicode NFC, stripped of hostile control and bidi-override characters,
 * zero-width spaces, and whitespace-collapsed.
 * Length limits are strictly counted in Unicode code points (max 120 title, max 4000 body).
 */
public object AnnouncementText {

    public const val MAX_TITLE_CODE_POINTS: Int = 120
    public const val MAX_BODY_CODE_POINTS: Int = 4000

    public data class Cleaned(
        public val title: String,
        public val body: String,
        public val titleCodePoints: Int,
        public val bodyCodePoints: Int,
    )

    public class ValidationException(
        public val field: String,
        public val reason: String,
        public val limit: Int? = null,
    ) : IllegalArgumentException("Invalid announcement $field: $reason")

    /**
     * Sanitises and validates [rawTitle] and [rawBody]. Throws [ValidationException] if empty or over limits.
     */
    public fun clean(rawTitle: String?, rawBody: String?): Cleaned {
        if (rawTitle.isNullOrBlank()) {
            throw ValidationException("title", "Title cannot be empty")
        }
        if (rawBody.isNullOrBlank()) {
            throw ValidationException("body", "Body cannot be empty")
        }

        // 1. NFC normalisation
        val nfcTitle = Normalizer.normalize(rawTitle, Normalizer.Form.NFC)
        val nfcBody = Normalizer.normalize(rawBody, Normalizer.Form.NFC)

        // 2. Clean title: strip ASCII controls, bidi overrides/isolates, zero-width chars
        val cleanedTitle = cleanTitleString(nfcTitle).trim()
        if (cleanedTitle.isEmpty()) {
            throw ValidationException("title", "Title cannot be empty after sanitisation")
        }

        val titleCodePoints = cleanedTitle.codePointCount(0, cleanedTitle.length)
        if (titleCodePoints > MAX_TITLE_CODE_POINTS) {
            throw ValidationException("title", "Title exceeds $MAX_TITLE_CODE_POINTS characters", MAX_TITLE_CODE_POINTS)
        }

        // 3. Clean body: strip ASCII controls (except \n and \t), bidi controls, invisible chars, collapse excessive newlines
        val cleanedBody = cleanBodyString(nfcBody).trim()
        if (cleanedBody.isEmpty()) {
            throw ValidationException("body", "Body cannot be empty after sanitisation")
        }

        val bodyCodePoints = cleanedBody.codePointCount(0, cleanedBody.length)
        if (bodyCodePoints > MAX_BODY_CODE_POINTS) {
            throw ValidationException("body", "Body exceeds $MAX_BODY_CODE_POINTS characters", MAX_BODY_CODE_POINTS)
        }

        return Cleaned(
            title = cleanedTitle,
            body = cleanedBody,
            titleCodePoints = titleCodePoints,
            bodyCodePoints = bodyCodePoints,
        )
    }

    private fun cleanTitleString(input: String): String {
        val sb = java.lang.StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = java.lang.Character.charCount(cp)
            i += charCount

            // ASCII controls (< 0x20 or 0x7F) are forbidden in title (including newlines and tabs)
            if (cp < 0x20 || cp == 0x7F) continue

            // Unicode bidi override / embedding / isolate controls
            // U+202A (LRE), U+202B (RLE), U+202C (PDF), U+202D (LRO), U+202E (RLO)
            // U+2066 (LRI), U+2067 (RLI), U+2068 (FSI), U+2069 (PDI)
            if (cp in 0x202A..0x202E || cp in 0x2066..0x2069) continue

            // Zero-width characters removed from title:
            // U+200B (ZWSP), U+200C (ZWNJ), U+200D (ZWJ), U+2060 (WJ), U+FEFF (BOM)
            if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF) continue

            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun cleanBodyString(input: String): String {
        val sb = java.lang.StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = java.lang.Character.charCount(cp)
            i += charCount

            // ASCII controls: allow \n (0x0A) and \t (0x09); strip all others < 0x20 and 0x7F
            if ((cp < 0x20 && cp != 0x0A && cp != 0x09) || cp == 0x7F) continue

            // Bidi overrides / isolates stripped
            if (cp in 0x202A..0x202E || cp in 0x2066..0x2069) continue

            // Zero-width characters stripped (U+200B, U+2060, U+FEFF), U+200D kept for emoji sequences
            if (cp == 0x200B || cp == 0x2060 || cp == 0xFEFF) continue

            sb.appendCodePoint(cp)
        }

        // Collapse > 3 consecutive newlines to 3: \n\n\n\n+ -> \n\n\n
        return collapseNewlines(sb.toString())
    }

    private fun collapseNewlines(s: String): String {
        val regex = Regex("\n{4,}")
        return regex.replace(s, "\n\n\n")
    }

    /**
     * Extracts http and https links from [text] for preview and display purposes.
     */
    public fun extractLinks(text: String): List<String> {
        val urlRegex = Regex("https?://[^\\s<>\"']+")
        return urlRegex.findAll(text).map { it.value }.toList()
    }
}
