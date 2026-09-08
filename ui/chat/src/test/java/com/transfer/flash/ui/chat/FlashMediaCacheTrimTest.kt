package com.transfer.flash.ui.chat

import android.content.ComponentCallbacks2
import com.transfer.flash.ui.chat.FlashMediaDecoder.CacheTrim
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The thumbnail cache's memory-pressure policy.
 *
 * The policy itself lives in an anonymous `ComponentCallbacks2` that nothing can reach, which is why
 * the decision is a pure function: without these assertions the only way to find out that a level
 * stopped evicting would be an OOM on a 2 GB handset.
 *
 * The `RUNNING_*`, `MODERATE` and `COMPLETE` constants are deprecated against a recent `android.jar`
 * because a recent platform no longer delivers them — but the API-27 devices this policy exists for
 * do, so the suppression below is deliberate: naming the real constants documents the intent far
 * better than the literals 5 / 10 / 15 / 60 / 80 would.
 */
@Suppress("DEPRECATION")
class FlashMediaCacheTrimTest {

    @Test
    fun `still-foreground moderate pressure keeps the cache intact`() {
        assertEquals(CacheTrim.None, FlashMediaDecoder.cacheTrimFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun `foreground low and critical pressure halve rather than blank`() {
        // Halving, not evicting: the conversation is still on screen, and a blanked cache means every
        // visible tile re-decodes on the next scroll pass — pressure plus a decode storm.
        assertEquals(CacheTrim.Halve, FlashMediaDecoder.cacheTrimFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(CacheTrim.Halve, FlashMediaDecoder.cacheTrimFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    }

    @Test
    fun `hidden UI drops everything`() {
        // The transfer keeps running as a foreground service with no tile on screen; this is the case
        // the cache was silently holding up to maxMemory div 8 through.
        assertEquals(CacheTrim.EvictAll, FlashMediaDecoder.cacheTrimFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun `every background level drops everything`() {
        assertEquals(CacheTrim.EvictAll, FlashMediaDecoder.cacheTrimFor(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(CacheTrim.EvictAll, FlashMediaDecoder.cacheTrimFor(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(CacheTrim.EvictAll, FlashMediaDecoder.cacheTrimFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun `policy is monotonic in level, so a future constant cannot trim less than a lower one`() {
        // Ordered thresholds rather than per-constant matching: the platform has narrowed which levels
        // it delivers more than once, and an unknown level must not fall through to None.
        val order = listOf(CacheTrim.None, CacheTrim.Halve, CacheTrim.EvictAll)
        var lowest = 0
        for (level in 0..100) {
            val rank = order.indexOf(FlashMediaDecoder.cacheTrimFor(level))
            assertEquals(true, rank >= lowest)
            lowest = rank
        }
        assertEquals(CacheTrim.EvictAll, FlashMediaDecoder.cacheTrimFor(Int.MAX_VALUE))
    }

    @Test
    fun `nonsense levels are inert`() {
        assertEquals(CacheTrim.None, FlashMediaDecoder.cacheTrimFor(0))
        assertEquals(CacheTrim.None, FlashMediaDecoder.cacheTrimFor(-1))
        assertEquals(CacheTrim.None, FlashMediaDecoder.cacheTrimFor(Int.MIN_VALUE))
    }
}
