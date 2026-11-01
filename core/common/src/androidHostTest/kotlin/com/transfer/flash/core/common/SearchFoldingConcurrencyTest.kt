@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common

import com.transfer.flash.core.common.text.foldForSearch
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Matches the pre-cache NFKD/marks/lowercase contract, including non-ASCII and concurrent churn. */
class SearchFoldingConcurrencyTest {
    private fun baseline(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()

    @Test
    fun unicode_and_ascii_fast_path_match_baseline_under_turkish_default_locale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            for (text in listOf("I İ ı", "CAFÉ", "Kelvin", "Ａ ① ﬁ", "Café", "ЖУК", "ΜΆΡΙΑ", "東京😀")) {
                assertEquals(text, baseline(text), foldForSearch(text))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun concurrent_hits_misses_and_eviction_preserve_results() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker -> Callable {
                repeat(768) { i ->
                    val text = "CAFÉ $worker $i 東京😀"
                    assertEquals(baseline(text), foldForSearch(text))
                    assertEquals("resume", foldForSearch("résumé"))
                }
            } }
            pool.invokeAll(tasks).forEach { it.get(30L, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
