package com.transfer.flash.ui.radio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RADUI-U05: Source code guard asserting that all contentDescription and stateDescription assignments
 * in radio UI screens originate from [FlashRadioCopy] and do not contain inline string literals.
 */
class FlashRadioCopyA11ySourceTest {

    private fun radioScreenFiles(): List<File> {
        val dir = File("src/commonMain/kotlin/com/transfer/flash/ui/radio").takeIf { it.isDirectory }
            ?: File("ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio")
        assertTrue(dir.isDirectory, "Directory must exist: ${dir.absolutePath}")
        return dir.listFiles { f -> f.extension == "kt" && f.name.contains("Screen") || f.name.contains("Components") }!!.toList()
    }

    @Test
    fun testNoInlineStringLiteralsInAccessibilityProperties() {
        val files = radioScreenFiles()
        assertTrue(files.isNotEmpty(), "At least one screen file must be scanned")

        // Regex for contentDescription = "..." or stateDescription = "..." with inline literal
        val literalPattern = Regex("""(contentDescription|stateDescription)\s*=\s*"[^"]+"""")

        for (file in files) {
            val content = file.readText()
            val match = literalPattern.find(content)
            assertFalse(
                match != null,
                "File ${file.name} contains inline string literal in accessibility property: '${match?.value}'. " +
                    "All accessibility strings must reference FlashRadioCopy."
            )
        }
    }

    @Test
    fun testAllAccessibilityReferencesUseFlashRadioCopy() {
        val files = radioScreenFiles()
        for (file in files) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if ((trimmed.contains("contentDescription =") || trimmed.contains("stateDescription =")) &&
                    !trimmed.contains("null")
                ) {
                    val isValid = trimmed.contains("FlashRadioCopy") ||
                        trimmed.contains("a11yLabel") ||
                        trimmed.contains("badgeText") ||
                        trimmed.contains("sentence")
                    assertTrue(
                        isValid,
                        "Line ${index + 1} of ${file.name} defines accessibility description without FlashRadioCopy or parameter: '$line'"
                    )
                }
            }
        }
    }
}
