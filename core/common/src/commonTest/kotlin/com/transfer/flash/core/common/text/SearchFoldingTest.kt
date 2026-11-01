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
    fun repeatedCallsReturnCachedResultIdentically() {
        val first = foldForSearch("Béatrice Owusu")
        val second = foldForSearch("Béatrice Owusu")
        assertEquals("beatrice owusu", first)
        assertEquals(first, second)
    }
}
