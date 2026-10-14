package com.transfer.flash.core.common.text

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.text.Normalizer

@FlashInternalApi
public actual fun foldForSearch(input: String): String {
    val normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
    return normalized.replace("\\p{M}+".toRegex(), "").lowercase()
}
