package com.transfer.flash.core.common.logging

/** Severity of a [FlashLogEntry]. Mirrors the i/w/e levels of [FlashLogger]. */
internal enum class FlashLogLevel {
    INFO,
    WARN,
    ERROR,
}

/**
 * One structured entry captured in a [FlashLogger] ring buffer (AGENTS.md §24 format:
 * `TAG: message`). Kept as a plain data class so the future debug sheet can render and
 * filter entries without string parsing.
 */
internal data class FlashLogEntry(
    val level: FlashLogLevel,
    val tag: String,
    val message: String,
    val timestampMs: Long,
)
