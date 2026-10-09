package com.transfer.flash.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashPathSanitizerTest {

    @Test
    fun singleFile_sanitizedNormally() {
        val result = FlashPathSanitizer.sanitizeRelativePath("normal_file.txt")
        assertEquals("normal_file.txt", result)
    }

    @Test
    fun nestedRelativePath_preservesSubdirectoryStructure() {
        val result = FlashPathSanitizer.sanitizeRelativePath("folder/subfolder/document.pdf", separator = "/")
        assertEquals("folder/subfolder/document.pdf", result)
    }

    @Test
    fun windowsBackslashes_normalizedCorrectly() {
        val result = FlashPathSanitizer.sanitizeRelativePath("folder\\subfolder\\image.png", separator = "/")
        assertEquals("folder/subfolder/image.png", result)
    }

    @Test
    fun pathTraversal_dotDotSegmentsStripped() {
        val result = FlashPathSanitizer.sanitizeRelativePath("../../etc/passwd", separator = "/")
        assertFalse(result.contains(".."))
        assertEquals("etc/passwd", result)
    }

    @Test
    fun pathTraversal_complexEscapeAttemptsStripped() {
        val result = FlashPathSanitizer.sanitizeRelativePath("photos/../../../secret.key", separator = "/")
        assertFalse(result.contains(".."))
        assertEquals("photos/secret.key", result)
    }

    @Test
    fun leadingSlashes_trimmed() {
        val result = FlashPathSanitizer.sanitizeRelativePath("///folder/file.dat", separator = "/")
        assertEquals("folder/file.dat", result)
    }

    @Test
    fun emptyOrDotOnly_fallsBackToUnnamed() {
        assertEquals("unnamed", FlashPathSanitizer.sanitizeRelativePath(""))
        assertEquals("unnamed", FlashPathSanitizer.sanitizeRelativePath("."))
        assertEquals("unnamed", FlashPathSanitizer.sanitizeRelativePath(".."))
        assertEquals("unnamed", FlashPathSanitizer.sanitizeRelativePath("../.."))
        assertEquals("unnamed", FlashPathSanitizer.sanitizeRelativePath("///"))
    }

    @Test
    fun illegalCharacters_replacedWithUnderscores() {
        val result = FlashPathSanitizer.sanitizeRelativePath("bad:file*name?.txt")
        assertEquals("bad_file_name_.txt", result)
    }

    @Test
    fun unicodeCharacters_preserved() {
        assertEquals("تقرير_2026.pdf", FlashPathSanitizer.sanitizeRelativePath("تقرير_2026.pdf"))
        assertEquals("文档.docx", FlashPathSanitizer.sanitizeRelativePath("文档.docx"))
        assertEquals("Café.png", FlashPathSanitizer.sanitizeRelativePath("Café.png"))
    }

    @Test
    fun spaces_preserved() {
        assertEquals("my vacation photo.jpg", FlashPathSanitizer.sanitizeRelativePath("my vacation photo.jpg"))
    }

    @Test
    fun windowsReservedNames_prefixed() {
        assertEquals("_CON.txt", FlashPathSanitizer.sanitizeRelativePath("CON.txt"))
        assertEquals("_nul", FlashPathSanitizer.sanitizeRelativePath("nul"))
        assertEquals("_com1.png", FlashPathSanitizer.sanitizeRelativePath("com1.png"))
    }

    @Test
    fun longFileName_truncatesPreservingExtension() {
        val longName = "a".repeat(150) + ".pdf"
        val result = FlashPathSanitizer.sanitizeRelativePath(longName)
        assertEquals(120, result.length)
        assertTrue(result.endsWith(".pdf"))
        assertEquals("a".repeat(116) + ".pdf", result)
    }

    @Test
    fun trailingDotsAndSpaces_trimmed() {
        assertEquals("file.txt", FlashPathSanitizer.sanitizeRelativePath("file.txt."))
        assertEquals("file", FlashPathSanitizer.sanitizeRelativePath("file   "))
    }

    // ---- audit 2026-10-08 gaps ----

    private fun utf8(s: String) = s.encodeToByteArray().size

    @Test
    fun driveLetter_colonReplaced() {
        assertEquals("C_foo", FlashPathSanitizer.sanitizeRelativePath("C:foo"))
        assertEquals("C_/Windows/system32", FlashPathSanitizer.sanitizeRelativePath("C:\\Windows\\system32"))
    }

    @Test
    fun uncPath_becomesPlainRelativeSegments() {
        assertEquals("server/share/file.txt", FlashPathSanitizer.sanitizeRelativePath("\\\\server\\share\\file.txt"))
    }

    @Test
    fun extendedLengthPrefix_neutralised() {
        val result = FlashPathSanitizer.sanitizeRelativePath("\\\\?\\C:\\x")
        assertEquals("_/C_/x", result)
        assertFalse(result.contains(":") || result.contains("?") || result.contains("\\"))
    }

    @Test
    fun alternateDataStream_colonReplaced() {
        assertEquals("file_stream", FlashPathSanitizer.sanitize("file:stream"))
        assertEquals("file_stream_\$DATA", FlashPathSanitizer.sanitize("file:stream:\$DATA"))
    }

    @Test
    fun separatorBackslash_joinsWithBackslash() {
        assertEquals("a\\b\\c.txt", FlashPathSanitizer.sanitizeRelativePath("a/b/c.txt", separator = "\\"))
        assertEquals("a\\b", FlashPathSanitizer.sanitizeRelativePath("a\\..\\b", separator = "\\"))
    }

    @Test
    fun reservedDeviceNames_allForms() {
        val names = listOf(
            "CON", "PRN", "AUX", "NUL", "COM0", "COM1", "COM9", "LPT0", "LPT1", "LPT9",
            "COM\u00B9", "COM\u00B2", "COM\u00B3", "LPT\u00B9", "LPT\u00B2", "LPT\u00B3", "CONIN$", "CONOUT$",
        )
        for (n in names) {
            assertEquals("_$n", FlashPathSanitizer.sanitize(n), "bare $n")
            assertEquals("_$n.txt", FlashPathSanitizer.sanitize("$n.txt"), "with extension $n")
            assertEquals("_${n.lowercase()}", FlashPathSanitizer.sanitize(n.lowercase()), "lower case $n")
        }
        assertEquals("_CON .txt", FlashPathSanitizer.sanitize("CON .txt"))
        assertEquals("_CON", FlashPathSanitizer.sanitize("CON."))
        assertEquals("_CON", FlashPathSanitizer.sanitize("CON  "))
        // Not reserved: only the exact device names are.
        assertEquals("COM10.txt", FlashPathSanitizer.sanitize("COM10.txt"))
        assertEquals("console.txt", FlashPathSanitizer.sanitize("console.txt"))
    }

    @Test
    fun cjkName_isCutByUtf8ByteLengthOnACodePointBoundary() {
        // 100 CJK characters = 300 bytes: under the old 120 UTF-16 unit cap but far over the 255 byte file name limit.
        val name = "\u6587".repeat(100) + ".docx"
        val result = FlashPathSanitizer.sanitize(name)
        assertTrue(utf8(result) <= 200, "was ${utf8(result)} bytes")
        assertTrue(result.endsWith(".docx"))
        assertEquals("\u6587".repeat((200 - 5) / 3) + ".docx", result)
    }

    @Test
    fun surrogatePairs_areNeverSplit() {
        val emoji = "\uD83D\uDE00" // one code point, 2 UTF-16 units, 4 UTF-8 bytes
        val result = FlashPathSanitizer.sanitize(emoji.repeat(100) + ".png")
        assertTrue(utf8(result) <= 200)
        assertTrue(result.endsWith(".png"))
        val body = result.removeSuffix(".png")
        assertEquals(emoji.repeat(body.length / 2), body, "a split pair would not repeat cleanly")
        // Without an extension too.
        val noExt = FlashPathSanitizer.sanitize(emoji.repeat(100))
        assertEquals(emoji.repeat(noExt.length / 2), noExt)
        assertTrue(utf8(noExt) <= 200)
    }

    @Test
    fun loneSurrogate_isReplaced() {
        assertEquals("a_b", FlashPathSanitizer.sanitize("a\uD83Db"))
    }

    @Test
    fun truncation_neverLeavesTrailingSpaceOrDot() {
        // The cut falls right after a space / dots.
        val name = "a".repeat(110) + " ...... " + "b".repeat(40) + ".pdf"
        val result = FlashPathSanitizer.sanitize(name)
        assertTrue(result.endsWith(".pdf"))
        val base = result.removeSuffix(".pdf")
        assertFalse(base.endsWith(" ") || base.endsWith("."), "base ends badly: '$base'")
        val noExt = FlashPathSanitizer.sanitize("a".repeat(119) + " " + "b".repeat(40))
        assertFalse(noExt.endsWith(" ") || noExt.endsWith("."), "'$noExt'")
        assertTrue(noExt.length <= 120)
    }

    @Test
    fun fileName_variantTrimsAndFallsBackToReceivedBin() {
        assertEquals("received.bin", FlashPathSanitizer.sanitizeFileName("   ..   "))
        assertEquals("doc.pdf", FlashPathSanitizer.sanitizeFileName("  doc.pdf...  "))
        assertEquals("_CON.txt", FlashPathSanitizer.sanitizeFileName("CON.txt"))
    }
}
