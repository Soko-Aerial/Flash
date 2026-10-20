package com.transfer.flash.server.files

import com.transfer.flash.core.hubprotocol.HubContract

/**
 * The administrator's file policy, read from the console settings (ADR-215).
 *
 * One place parses the four stored strings, so the store, the route layer and the admin API can never disagree about a
 * default or a bound. A malformed stored value falls back to the default rather than failing a request: the console
 * validates on write, and a hand-edited database must not take file transfer down.
 */
object FileSettings {

    /** Largest single file, in bytes (`maxFileBytes`). */
    const val MAX_FILE_BYTES: String = "maxFileBytes"

    /** How long a completed file is kept, in days (`fileRetentionDays`). */
    const val RETENTION_DAYS: String = "fileRetentionDays"

    /** What one device may keep on the hub, in bytes (`deviceStoredBytes`). */
    const val DEVICE_STORED_BYTES: String = "deviceStoredBytes"

    /** What the whole hub may keep, in bytes (`globalStoredBytes`). */
    const val GLOBAL_STORED_BYTES: String = "globalStoredBytes"

    const val DEFAULT_RETENTION_DAYS: Int = 7
    const val MIN_RETENTION_DAYS: Int = 1
    const val MAX_RETENTION_DAYS: Int = 365

    const val DEFAULT_DEVICE_STORED_BYTES: Long = 500L * 1024 * 1024
    const val DEFAULT_GLOBAL_STORED_BYTES: Long = 5L * 1024 * 1024 * 1024

    const val MIN_DEVICE_STORED_BYTES: Long = HubContract.MIN_FILE_BYTES_FLOOR
    const val MAX_DEVICE_STORED_BYTES: Long = 1024L * 1024 * 1024 * 1024

    const val MIN_GLOBAL_STORED_BYTES: Long = HubContract.MIN_FILE_BYTES_FLOOR
    const val MAX_GLOBAL_STORED_BYTES: Long = 100L * 1024 * 1024 * 1024 * 1024

    /** Everything the store needs, from a settings reader (`admins::getSetting`). */
    fun limits(read: (String) -> String?): FileLimits {
        val global = read(GLOBAL_STORED_BYTES)?.toLongOrNull()
            ?.takeIf { it in MIN_GLOBAL_STORED_BYTES..MAX_GLOBAL_STORED_BYTES }
            ?: DEFAULT_GLOBAL_STORED_BYTES
        // A per-file cap larger than the whole hub's allowance is unachievable, so the number the hub advertises and the
        // number it accepts are the same one: clamped to the global cap (never below the floor, so files stay possible).
        val maxFile = (read(MAX_FILE_BYTES)?.toLongOrNull()
            ?.takeIf { it in HubContract.MIN_FILE_BYTES_FLOOR..HubContract.MAX_FILE_BYTES_CEILING }
            ?: HubContract.DEFAULT_MAX_FILE_BYTES)
            .coerceAtMost(global)
            .coerceAtLeast(HubContract.MIN_FILE_BYTES_FLOOR)
        val retentionDays = read(RETENTION_DAYS)?.toIntOrNull()
            ?.takeIf { it in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS }
            ?: DEFAULT_RETENTION_DAYS
        val device = read(DEVICE_STORED_BYTES)?.toLongOrNull()
            ?.takeIf { it in MIN_DEVICE_STORED_BYTES..MAX_DEVICE_STORED_BYTES }
            ?.coerceAtMost(global)
            ?: minOf(DEFAULT_DEVICE_STORED_BYTES, global)
        return FileLimits(
            maxFileBytes = maxFile,
            deviceStoredBytes = device,
            globalStoredBytes = global,
            retentionMs = retentionDays.toLong() * 24L * 60L * 60L * 1000L,
        )
    }

    /** The largest file the hub currently accepts; 0 would mean "no files", which this hub never reports. */
    fun maxFileBytes(read: (String) -> String?): Long = limits(read).maxFileBytes

    /** The value a setting must satisfy, or null when [value] is acceptable. The message names the bound. */
    fun validate(key: String, value: String): String? = when (key) {
        MAX_FILE_BYTES -> {
            val v = value.toLongOrNull()
            when {
                v == null -> "$MAX_FILE_BYTES must be a number of bytes"
                v < HubContract.MIN_FILE_BYTES_FLOOR ->
                    "$MAX_FILE_BYTES must be at least ${HubContract.MIN_FILE_BYTES_FLOOR / (1024 * 1024)} MB"
                v > HubContract.MAX_FILE_BYTES_CEILING ->
                    "$MAX_FILE_BYTES must be at most ${HubContract.MAX_FILE_BYTES_CEILING / (1024 * 1024)} MB"
                else -> null
            }
        }
        RETENTION_DAYS -> {
            val v = value.toIntOrNull()
            if (v == null || v !in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS) {
                "$RETENTION_DAYS must be a whole number of days between $MIN_RETENTION_DAYS and $MAX_RETENTION_DAYS"
            } else {
                null
            }
        }
        DEVICE_STORED_BYTES -> {
            val v = value.toLongOrNull()
            if (v == null || v !in MIN_DEVICE_STORED_BYTES..MAX_DEVICE_STORED_BYTES) {
                "$DEVICE_STORED_BYTES must be between ${MIN_DEVICE_STORED_BYTES / (1024 * 1024)} MB and " +
                    "${MAX_DEVICE_STORED_BYTES / (1024L * 1024 * 1024)} GB"
            } else {
                null
            }
        }
        GLOBAL_STORED_BYTES -> {
            val v = value.toLongOrNull()
            if (v == null || v !in MIN_GLOBAL_STORED_BYTES..MAX_GLOBAL_STORED_BYTES) {
                "$GLOBAL_STORED_BYTES must be between ${MIN_GLOBAL_STORED_BYTES / (1024 * 1024)} MB and " +
                    "${MAX_GLOBAL_STORED_BYTES / (1024L * 1024 * 1024 * 1024)} TB"
            } else {
                null
            }
        }
        else -> "unknown setting $key"
    }

    /** True when [key] is one of the four this object owns. */
    fun isFileSetting(key: String): Boolean =
        key == MAX_FILE_BYTES || key == RETENTION_DAYS || key == DEVICE_STORED_BYTES || key == GLOBAL_STORED_BYTES
}
