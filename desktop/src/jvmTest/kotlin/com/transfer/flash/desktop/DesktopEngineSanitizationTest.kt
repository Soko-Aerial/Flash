package com.transfer.flash.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for path sanitization and directory traversal prevention (AGENTS.md §19).
 */
class DesktopEngineSanitizationTest {

    @Test
    fun singleFile_sanitizedNormally() {
        val result = DesktopEngine.sanitizeRelativePath("normal_file.txt")
        assertEquals("normal_file.txt", result)
    }

    @Test
    fun nestedRelativePath_preservesSubdirectoryStructure() {
        val sep = File.separator
        val result = DesktopEngine.sanitizeRelativePath("folder/subfolder/document.pdf")
        assertEquals("folder${sep}subfolder${sep}document.pdf", result)
    }

    @Test
    fun windowsBackslashes_normalizedCorrectly() {
        val sep = File.separator
        val result = DesktopEngine.sanitizeRelativePath("folder\\subfolder\\image.png")
        assertEquals("folder${sep}subfolder${sep}image.png", result)
    }

    @Test
    fun pathTraversal_dotDotSegmentsStripped() {
        val sep = File.separator
        val result = DesktopEngine.sanitizeRelativePath("../../etc/passwd")
        // Segments ".." and ".." stripped; "etc/passwd" sanitized
        assertFalse(result.contains(".."))
        assertEquals("etc${sep}passwd", result)
    }

    @Test
    fun pathTraversal_complexEscapeAttemptsStripped() {
        val sep = File.separator
        val result = DesktopEngine.sanitizeRelativePath("photos/../../../secret.key")
        assertFalse(result.contains(".."))
        assertEquals("photos${sep}secret.key", result)
    }

    @Test
    fun leadingSlashes_trimmed() {
        val sep = File.separator
        val result = DesktopEngine.sanitizeRelativePath("///folder/file.dat")
        assertEquals("folder${sep}file.dat", result)
    }

    @Test
    fun emptyOrDotOnly_fallsBackToUnnamed() {
        assertEquals("unnamed", DesktopEngine.sanitizeRelativePath(""))
        assertEquals("unnamed", DesktopEngine.sanitizeRelativePath("."))
        assertEquals("unnamed", DesktopEngine.sanitizeRelativePath(".."))
        assertEquals("unnamed", DesktopEngine.sanitizeRelativePath("../.."))
        assertEquals("unnamed", DesktopEngine.sanitizeRelativePath("///"))
    }

    @Test
    fun illegalCharacters_replacedWithUnderscores() {
        val result = DesktopEngine.sanitizeRelativePath("bad:file*name?.txt")
        assertEquals("bad_file_name_.txt", result)
    }

    @Test
    fun unicodeCharacters_preserved() {
        assertEquals("تقرير_2026.pdf", DesktopEngine.sanitizeRelativePath("تقرير_2026.pdf"))
        assertEquals("文档.docx", DesktopEngine.sanitizeRelativePath("文档.docx"))
        assertEquals("Café.png", DesktopEngine.sanitizeRelativePath("Café.png"))
    }

    @Test
    fun spaces_preserved() {
        assertEquals("my vacation photo.jpg", DesktopEngine.sanitizeRelativePath("my vacation photo.jpg"))
    }

    @Test
    fun windowsReservedNames_prefixed() {
        assertEquals("_CON.txt", DesktopEngine.sanitizeRelativePath("CON.txt"))
        assertEquals("_nul", DesktopEngine.sanitizeRelativePath("nul"))
        assertEquals("_com1.png", DesktopEngine.sanitizeRelativePath("com1.png"))
    }

    @Test
    fun longFileName_truncatesPreservingExtension() {
        val longName = "a".repeat(150) + ".pdf"
        val result = DesktopEngine.sanitizeRelativePath(longName)
        assertEquals(120, result.length)
        assertTrue(result.endsWith(".pdf"))
        assertEquals("a".repeat(116) + ".pdf", result)
    }

    @Test
    fun trailingDotsAndSpaces_trimmed() {
        assertEquals("file.txt", DesktopEngine.sanitizeRelativePath("file.txt."))
        assertEquals("file", DesktopEngine.sanitizeRelativePath("file   "))
    }
}
