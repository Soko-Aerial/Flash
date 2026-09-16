package com.transfer.flash.core.messaging.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [FlashMimeTypes] at the boundaries that matter for the chat repository move.
 *
 * The media rows must match `RealFlashChatRepository.resolveEffectiveMime`'s explicit
 * mappings one-for-one (including the `m4a`/`aac` → `audio/mp4` quirk): this suite is
 * what guarantees the slice-2 swap changed the fallback without changing a single type
 * the repository already knew. The `null` cases pin the "never a guess" half of the
 * contract — unknown input falls through to the stored MIME or the wildcard,
 * identically on both hosts.
 */
class FlashMimeTypesTest {

    @Test
    fun `video extensions resolve including mkv`() {
        assertEquals("video/x-matroska", FlashMimeTypes.fromExtension("mkv"))
        assertEquals("video/mp4", FlashMimeTypes.fromExtension("mp4"))
        assertEquals("video/webm", FlashMimeTypes.fromExtension("webm"))
        assertEquals("video/quicktime", FlashMimeTypes.fromExtension("mov"))
    }

    @Test
    fun `image and audio extensions resolve`() {
        assertEquals("image/jpeg", FlashMimeTypes.fromExtension("jpg"))
        assertEquals("image/png", FlashMimeTypes.fromExtension("png"))
        assertEquals("audio/mpeg", FlashMimeTypes.fromExtension("mp3"))
        assertEquals("application/pdf", FlashMimeTypes.fromExtension("pdf"))
        assertEquals("application/zip", FlashMimeTypes.fromExtension("zip"))
    }

    @Test
    fun `m4a and aac stay audio-mp4 like the repository always answered`() {
        // Quirk, pinned deliberately: the repository's explicit table maps both to
        // `audio/mp4` (unlike Flash.kt's `audio/aac`). The move must not "fix" this
        // silently — a behavior change here swaps which bubbles get audio previews.
        assertEquals("audio/mp4", FlashMimeTypes.fromExtension("m4a"))
        assertEquals("audio/mp4", FlashMimeTypes.fromExtension("aac"))
    }

    @Test
    fun `lookup ignores case and a leading dot`() {
        assertEquals("image/jpeg", FlashMimeTypes.fromExtension("JPG"))
        assertEquals("image/jpeg", FlashMimeTypes.fromExtension(".Jpeg"))
        assertEquals("video/mp4", FlashMimeTypes.fromExtension("MP4"))
    }

    @Test
    fun `document and archive extensions resolve`() {
        assertEquals("text/plain", FlashMimeTypes.fromExtension("txt"))
        assertEquals("text/csv", FlashMimeTypes.fromExtension("csv"))
        assertEquals("application/json", FlashMimeTypes.fromExtension("json"))
        assertEquals("application/vnd.android.package-archive", FlashMimeTypes.fromExtension("apk"))
    }

    @Test
    fun `blank and unknown extensions return null rather than a guess`() {
        assertNull(FlashMimeTypes.fromExtension(""))
        assertNull(FlashMimeTypes.fromExtension("   "))
        assertNull(FlashMimeTypes.fromExtension("zzz-not-a-type"))
    }
}
