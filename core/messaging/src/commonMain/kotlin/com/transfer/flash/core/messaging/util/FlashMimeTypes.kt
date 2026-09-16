package com.transfer.flash.core.messaging.util

/**
 * Shared extension → MIME table (Phase 2, slice 2).
 *
 * ## Why this exists
 *
 * Four copies of "guess a MIME type from a filename" grew across the repo
 * (`RealFlashChatRepository.resolveEffectiveMime`, `Flash.kt`, `MainActivity`,
 * `DiscoveryEngineHolder`), and the chat repository's copy ended in
 * `android.webkit.MimeTypeMap` — an Android API that hard-fails a JVM compile the
 * moment the repository moves to `commonMain` (slice 4). This table is the shared,
 * platform-free replacement for that final fallback call. The other three copies are
 * deliberately left alone: Android behavior must not change under an untested refactor
 * ("keep Android clean").
 *
 * ## Contract
 *
 * - Pure Kotlin, no platform APIs: safe in `commonMain`, identical on both hosts.
 * - Input is matched case-insensitively (`lowercase()` in common is locale-independent,
 *   unlike Java's `toLowerCase()` — no Turkish-`I` hazard).
 * - Returns `null` for blank or unknown extensions — never a guess. Callers fall back to
 *   the stored MIME and then the star-slash-star wildcard, so an unknown type renders as
 *   a generic file card rather than a wrong preview.
 * - The media rows mirror `resolveEffectiveMime`'s explicit mappings one-for-one
 *   (including `m4a`/`aac` → `audio/mp4`), so swapping the fallback changes nothing for
 *   types the repository already knew. The document/archive rows cover what
 *   `MimeTypeMap` used to answer for the common cases (`txt`, `html`, `csv`, `json`,
 *   office formats, common archives).
 *
 * Honest delta: Android's `MimeTypeMap` knows hundreds of exotic extensions this table
 * does not. Those now fall through to the stored MIME or the wildcard instead of a
 * system answer — a generic card rather than a wrong one, and the same answer both
 * hosts give.
 */
public object FlashMimeTypes {

    /**
     * MIME type for [extension] (with or without a leading dot, any case), or `null`
     * when it is blank or not in the table.
     */
    public fun fromExtension(extension: String): String? {
        val ext = extension.trim().trimStart('.').lowercase()
        if (ext.isEmpty()) return null
        return TABLE[ext]
    }

    private val TABLE: Map<String, String> = mapOf(
        // Video — mirrors resolveEffectiveMime's explicit rows.
        "mkv" to "video/x-matroska",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        "3gpp" to "video/3gpp",
        "ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "ogv" to "video/ogg",
        // Image.
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "avif" to "image/avif",
        "ico" to "image/vnd.microsoft.icon",
        // Audio.
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
        "opus" to "audio/ogg",
        "m4a" to "audio/mp4",
        "aac" to "audio/mp4",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "mid" to "audio/midi",
        "midi" to "audio/midi",
        "amr" to "audio/amr",
        "weba" to "audio/webm",
        "wma" to "audio/x-ms-wma",
        // Documents.
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "js" to "application/javascript",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "epub" to "application/epub+zip",
        // Archives / packages.
        "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        // Fonts.
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
    )
}
