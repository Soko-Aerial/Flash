package com.transfer.flash.core.common.text

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Normalizes text for search indexing and prefix queries:
 * Unicode NFKD decomposition, diacritics stripped, and lowercased.
 */
@FlashInternalApi
public expect fun foldForSearch(input: String): String
