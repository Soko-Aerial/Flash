package com.transfer.flash.ui.shims

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MIME-to-extension translation behind the desktop file picker.
 *
 * `JFileChooser` filters by file extension; the composer asks in Android MIME terms. That mapping is
 * the only logic in the `jvm` picker actual, and it is the part that can be wrong without failing to
 * compile — a dropped family shows the user no files, and a wrong fallback shows them all. The dialog
 * itself is modal and cannot be driven from a test, which is why [extensionFilterFor] is `internal`
 * rather than buried inside the `remember` block.
 *
 * The rule every case here defends: **an unmappable request produces no filter, never an empty one.**
 * A filter that matches nothing is a picker that appears broken; no filter is merely unfiltered.
 */
class FlashFilePickerJvmTest {

    @Test
    fun `the all-files request produces no filter at all`() {
        // The composer sends this for Files and FlashTransfer attachments.
        assertNull(extensionFilterFor(listOf("*/*")))
    }

    @Test
    fun `an empty request produces no filter`() {
        assertNull(extensionFilterFor(emptyList()))
    }

    @Test
    fun `gallery maps to the union of the image and video families`() {
        val filter = requireNotNull(extensionFilterFor(listOf("image/*", "video/*")))

        val extensions = filter.extensions.toSet()
        // Both families are present and nothing was lost to the de-duplicating set: 8 image + 7 video.
        assertEquals(15, extensions.size)
        assertTrue(extensions.containsAll(listOf("jpg", "jpeg", "png", "heic", "webp")), "images: $extensions")
        assertTrue(extensions.containsAll(listOf("mp4", "mov", "mkv", "webm", "3gp")), "videos: $extensions")
        // The dropdown label is what the user reads, so it echoes the request rather than the
        // 15-entry expansion.
        assertEquals("image/*, video/*", filter.description)
    }

    @Test
    fun `the audio family covers the voice-note format each platform records`() {
        val extensions = requireNotNull(extensionFilterFor(listOf("audio/*"))).extensions.toSet()

        // Android records AAC in an .m4a container; the jvm recorder writes WAV. A user must be able
        // to attach a note that came from either platform, which is the whole reason this family is
        // spelled out instead of being derived from the JRE's MIME table (see EXTENSIONS_BY_MIME).
        assertTrue(extensions.contains("m4a"), "android voice notes: $extensions")
        assertTrue(extensions.contains("wav"), "desktop voice notes: $extensions")
        assertEquals(8, extensions.size)
    }

    @Test
    fun `a concrete mime type falls back to its own subtype`() {
        // Nothing in the composer sends one today, but the seam accepts any Android MIME string, and
        // for every format Flash sends the subtype *is* the extension.
        assertEquals(setOf("png"), requireNotNull(extensionFilterFor(listOf("image/png"))).extensions.toSet())
    }

    @Test
    fun `an unmappable type shows every file rather than none`() {
        // A wildcard family with no extension list, and a type with no subtype at all. Both could
        // plausibly have been implemented as "filter to nothing", which would hide the user's files.
        assertNull(extensionFilterFor(listOf("application/*")))
        assertNull(extensionFilterFor(listOf("image/")))
    }

    @Test
    fun `mime lookup ignores case`() {
        // Without the `lowercase()` in extensionFilterFor this request would miss the table, fall
        // through to the subtype fallback, hit the bare wildcard and return no filter — the Gallery
        // filter silently becoming all-files, which is exactly the class of regression that ruled
        // out `java.awt.FileDialog` for this actual.
        assertEquals(8, requireNotNull(extensionFilterFor(listOf("IMAGE/*"))).extensions.size)
    }
}
