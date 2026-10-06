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

    /** Emits probe [name] with [fields] (a `null` value is written as `-`). Never throws. */
    public fun emit(name: String, vararg fields: Pair<String, Any?>) {
        try {
            FlashLog.i(TAG, format(name, fields.asList()))
        } catch (_: Throwable) {
        }
    }

    /** `name k=v k=v`, values [sanitize]d. Separate from [emit] so tests can assert the exact line. */
    public fun format(name: String, fields: List<Pair<String, Any?>>): String {
        val sb = StringBuilder(name)
        for ((key, value) in fields) {
            sb.append(' ').append(key).append('=').append(sanitize(value))
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
        lines += format("session.header", listOf<Pair<String, Any?>>("exportAtMs" to wallClockMs) + headerFields)
        try {
            snapshotProvider?.invoke()?.let { lines += it }
        } catch (t: Throwable) {
            lines += format("session.snapshot.failed", listOf("error" to t::class.simpleName))
        }
        return lines
    }
}
