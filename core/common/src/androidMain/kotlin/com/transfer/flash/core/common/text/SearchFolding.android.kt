package com.transfer.flash.core.common.text

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.text.Normalizer
import java.util.LinkedHashMap

private val DIACRITICS_REGEX = Regex("\\p{M}+")
private const val CACHE_CAPACITY = 512
private val foldCacheLock = Any()
private val foldCache = LinkedHashMap<String, String>(128, 0.75f, true)

@FlashInternalApi
public actual fun foldForSearch(input: String): String {
    if (input.isEmpty()) return ""
    synchronized(foldCacheLock) {
        foldCache[input]?.let { return it }
    }

    var hasNonAscii = false
    for (i in 0 until input.length) {
        if (input[i].code > 0x7F) {
            hasNonAscii = true
            break
        }
    }

    val folded = if (!hasNonAscii) {
        input.lowercase()
    } else {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
        normalized.replace(DIACRITICS_REGEX, "").lowercase()
    }

    synchronized(foldCacheLock) {
        if (foldCache.size >= CACHE_CAPACITY) {
            val it = foldCache.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        foldCache[input] = folded
    }
    return folded
}
