package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi

internal val HELLO_FEATURE_REGEX: Regex = Regex("^[a-z0-9]{1,16}$")
private const val MAX_HELLO_FEATURES = 32

/**
 * Parses the raw `caps` field from a HELLO text frame (SW-2).
 * Each token must match `[a-z0-9]{1,16}`; keeps at most 32 tokens; drops anything else; never throws.
 */
@FlashInternalApi
public fun parseHelloFeatures(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(',')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.matches(HELLO_FEATURE_REGEX) }
        .take(MAX_HELLO_FEATURES)
        .toSet()
}

/**
 * Formats local feature tokens for the HELLO `caps` field (SW-2).
 * Tokens are sorted and joined by commas.
 * Returns null if the token set is empty or has no valid tokens, so the field can be omitted.
 */
@FlashInternalApi
public fun formatHelloFeatures(tokens: Set<String>): String? {
    if (tokens.isEmpty()) return null
    val valid = tokens
        .filter { it.matches(HELLO_FEATURE_REGEX) }
        .sorted()
        .take(MAX_HELLO_FEATURES)
    if (valid.isEmpty()) return null
    return valid.joinToString(",")
}
