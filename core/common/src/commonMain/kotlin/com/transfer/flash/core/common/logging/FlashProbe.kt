package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlin.concurrent.Volatile

/**
 * Structured evidence events ("probes"), written to the normal log under the tag [TAG].
 *
 * A probe is one line, `name key=value key=value`, emitted when state CHANGES (a session comes up, a group message is
 * accepted or dropped and why, a swarm offer is sent or ignored), never per chunk or per frame. The fixed vocabulary is
 * in `docs/testing/PROBES.md`; a device test in `docs/testing/TEST-BACKLOG.md` names the probes whose presence (or absence)
 * decides it, so a log export can be read against the backlog by a person or a script.
 *
 * Rules (AGENTS.md section 24): never pass a secret, a key, a fingerprint or message content. Pass ids through [short],
 * counts, enums and reason codes. [sanitize] keeps every value on one line and bounded, but it is not a licence to pass
 * content; the file sink's own redaction is a second line of defence.
 */
@FlashInternalApi
public object FlashProbe {

    /** The log tag every probe is written under. */
    public const val TAG: String = "PROBE"

    private const val VALUE_MAX = 80

    @Volatile
    private var snapshotProvider: (() -> List<String>)? = null

    @Volatile
    private var headerFields: List<Pair<String, Any?>> = emptyList()

    /** Appends sanitized [value] directly to [sb] without intermediate allocations. */
    internal fun appendSanitized(sb: StringBuilder, value: Any?) {
        val text = value?.toString()
        if (text.isNullOrEmpty()) {
            sb.append('-')
            return
        }
        val limit = minOf(text.length, VALUE_MAX)
        for (i in 0 until limit) {
            val c = text[i]
            sb.append(if (c.isWhitespace() || c.isISOControl()) '_' else c)
        }
    }

    /** Appends a key-value field directly to [sb]. */
    internal fun appendField(sb: StringBuilder, key: String, value: Any?) {
        sb.append(' ').append(key).append('=')
        appendSanitized(sb, value)
    }

    /** Emits a zero-argument probe [name]. Never throws. */
    public fun emit(name: String) {
        try {
            FlashLog.i(TAG, name)
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 1 field. Avoids [Pair] and array allocations. */
    public fun emit(name: String, k1: String, v1: Any?) {
        try {
            val sb = StringBuilder(name.length + 32)
            sb.append(name)
            appendField(sb, k1, v1)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 2 fields. Avoids [Pair] and array allocations. */
    public fun emit(name: String, k1: String, v1: Any?, k2: String, v2: Any?) {
        try {
            val sb = StringBuilder(name.length + 64)
            sb.append(name)
            appendField(sb, k1, v1)
            appendField(sb, k2, v2)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 3 fields. Avoids [Pair] and array allocations. */
    public fun emit(name: String, k1: String, v1: Any?, k2: String, v2: Any?, k3: String, v3: Any?) {
        try {
            val sb = StringBuilder(name.length + 96)
            sb.append(name)
            appendField(sb, k1, v1)
            appendField(sb, k2, v2)
            appendField(sb, k3, v3)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 4 fields. Avoids [Pair] and array allocations. */
    public fun emit(
        name: String,
        k1: String, v1: Any?,
        k2: String, v2: Any?,
        k3: String, v3: Any?,
        k4: String, v4: Any?,
    ) {
        try {
            val sb = StringBuilder(name.length + 128)
            sb.append(name)
            appendField(sb, k1, v1)
            appendField(sb, k2, v2)
            appendField(sb, k3, v3)
            appendField(sb, k4, v4)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 5 fields. Avoids [Pair] and array allocations. */
    public fun emit(
        name: String,
        k1: String, v1: Any?,
        k2: String, v2: Any?,
        k3: String, v3: Any?,
        k4: String, v4: Any?,
        k5: String, v5: Any?,
    ) {
        try {
            val sb = StringBuilder(name.length + 160)
            sb.append(name)
            appendField(sb, k1, v1)
            appendField(sb, k2, v2)
            appendField(sb, k3, v3)
            appendField(sb, k4, v4)
            appendField(sb, k5, v5)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 6 fields. Avoids [Pair] and array allocations. */
    public fun emit(
        name: String,
        k1: String, v1: Any?,
        k2: String, v2: Any?,
        k3: String, v3: Any?,
        k4: String, v4: Any?,
        k5: String, v5: Any?,
        k6: String, v6: Any?,
    ) {
        try {
            val sb = StringBuilder(name.length + 192)
            sb.append(name)
            appendField(sb, k1, v1)
            appendField(sb, k2, v2)
            appendField(sb, k3, v3)
            appendField(sb, k4, v4)
            appendField(sb, k5, v5)
            appendField(sb, k6, v6)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 7 fields. Avoids [Pair] and array allocations. */
    public fun emit(
        name: String,
        k1: String, v1: Any?,
        k2: String, v2: Any?,
        k3: String, v3: Any?,
        k4: String, v4: Any?,
        k5: String, v5: Any?,
        k6: String, v6: Any?,
        k7: String, v7: Any?,
    ) {
        try {
            val sb = StringBuilder(name.length + 224)
            sb.append(name)
            appendField(sb, k1, v1)
            appendField(sb, k2, v2)
            appendField(sb, k3, v3)
            appendField(sb, k4, v4)
            appendField(sb, k5, v5)
            appendField(sb, k6, v6)
            appendField(sb, k7, v7)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with 8 fields. Avoids [Pair] and array allocations. */
    public fun emit(
        name: String,
        k1: String, v1: Any?,
        k2: String, v2: Any?,
        k3: String, v3: Any?,
        k4: String, v4: Any?,
        k5: String, v5: Any?,
        k6: String, v6: Any?,
        k7: String, v7: Any?,
        k8: String, v8: Any?,
    ) {
        try {
            val sb = StringBuilder(name.length + 256)
            sb.append(name)
            appendField(sb, k1, v1)
            appendField(sb, k2, v2)
            appendField(sb, k3, v3)
            appendField(sb, k4, v4)
            appendField(sb, k5, v5)
            appendField(sb, k6, v6)
            appendField(sb, k7, v7)
            appendField(sb, k8, v8)
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** Emits probe [name] with [fields] (a `null` value is written as `-`). Never throws. */
    public fun emit(name: String, vararg fields: Pair<String, Any?>) {
        try {
            val sb = StringBuilder(name.length + fields.size * 24)
            sb.append(name)
            for (i in 0 until fields.size) {
                val pair = fields[i]
                appendField(sb, pair.first, pair.second)
            }
            FlashLog.i(TAG, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** `name k=v k=v`, values [sanitize]d. Separate from [emit] so tests can assert the exact line. */
    public fun format(name: String, fields: List<Pair<String, Any?>>): String {
        val sb = StringBuilder(name.length + fields.size * 24)
        sb.append(name)
        for (i in 0 until fields.size) {
            val pair = fields[i]
            appendField(sb, pair.first, pair.second)
        }
        return sb.toString()
    }

    /** One line, no spaces, at most [VALUE_MAX] characters; `null` becomes `-`. */
    public fun sanitize(value: Any?): String {
        val text = value?.toString() ?: return "-"
        if (text.isEmpty()) return "-"
        val clean = StringBuilder(minOf(text.length, VALUE_MAX))
        for (c in text) {
            if (clean.length >= VALUE_MAX) break
            clean.append(if (c.isWhitespace() || c.isISOControl()) '_' else c)
        }
        return clean.toString()
    }

    /** A log-safe id: the first 8 characters (device and group ids are not secrets, but short ones are readable). */
    public fun short(id: String?): String = if (id.isNullOrEmpty()) "-" else sanitize(id.take(8))

    /** Fixed facts about this process for the export header (build, device, local id, switches). Replaces any earlier set. */
    public fun setHeaderFields(fields: List<Pair<String, Any?>>) {
        headerFields = fields
    }

    /** Registers the function that renders the live state (sessions, groups) as probe-formatted lines. */
    public fun setSnapshotProvider(provider: (() -> List<String>)?) {
        snapshotProvider = provider
    }

    /**
     * The block placed at the top of a log export: a `session.header` line from [setHeaderFields] plus the current
     * `session.snapshot` lines. [wallClockMs] is passed in so the line pins the device clock against the log timestamps.
     */
    public fun exportHeader(wallClockMs: Long): List<String> {
        val lines = mutableListOf<String>()
        val headerSb = StringBuilder("session.header exportAtMs=").append(wallClockMs)
        for (i in 0 until headerFields.size) {
            val pair = headerFields[i]
            appendField(headerSb, pair.first, pair.second)
        }
        lines += headerSb.toString()
        try {
            snapshotProvider?.invoke()?.let { lines += it }
        } catch (t: Throwable) {
            lines += format("session.snapshot.failed", listOf("error" to t::class.simpleName))
        }
        return lines
    }
}
