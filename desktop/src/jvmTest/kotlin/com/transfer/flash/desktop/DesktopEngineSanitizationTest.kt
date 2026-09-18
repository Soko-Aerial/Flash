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
}
