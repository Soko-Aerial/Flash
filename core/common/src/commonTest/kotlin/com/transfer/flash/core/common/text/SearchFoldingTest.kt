package com.transfer.flash.core.common.text

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(FlashInternalApi::class)
class SearchFoldingTest {
    @Test
    fun emptyStringFoldsToEmpty() {
        assertEquals("", foldForSearch(""))
    }

    @Test
    fun pureAsciiFoldsToLowercaseWithoutDiacriticStripping() {
        assertEquals("hello world", foldForSearch("Hello World"))
        assertEquals("quick-brown_fox123!", foldForSearch("Quick-Brown_Fox123!"))
    }

    @Test
    fun unicodeAccentsAndDiacriticsAreStripped() {
        assertEquals("beatrice owusu", foldForSearch("Béatrice Owusu"))
        assertEquals("general mensah", foldForSearch("General Mensah"))
        assertEquals("resume", foldForSearch("résumé"))
        assertEquals("cafe", foldForSearch("Café"))
        assertEquals("uber", foldForSearch("Über"))
        assertEquals("facade", foldForSearch("Façade"))
    }

    @Test
    fun compatibility_normalization_combining_marks_and_non_latin_text_are_preserved() {
        val cases = listOf(
            "Ｆｌａｓｈ ① ﬁ" to "flash 1 fi",
            "Café" to "cafe",
            "İSTANBUL" to "istanbul",
            "Straße" to "straße",
            "ΜΆΡΙΑ" to "μαρια",
            "東京😀" to "東京😀",
            "́̈" to "",
        )
        for ((input, expected) in cases) assertEquals(expected, foldForSearch(input), input)
    }

    @Test
    fun eviction_does_not_change_results() {
        val first = foldForSearch("Éviction")
        repeat(1_024) { assertEquals("name $it", foldForSearch("NAME $it")) }
        assertEquals(first, foldForSearch("Éviction"))
    }

    @Test
    fun repeatedCallsReturnCachedResultIdentically() {
        val first = foldForSearch("Béatrice Owusu")
        val second = foldForSearch("Béatrice Owusu")
        assertEquals("beatrice owusu", first)
        assertEquals(first, second)
    }
}
