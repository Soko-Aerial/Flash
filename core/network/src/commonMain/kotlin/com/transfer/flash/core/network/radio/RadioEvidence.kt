package com.transfer.flash.core.network.radio

/** Direction of a raw byte dump. */
public enum class DumpDirection(public val label: String) { TX("TX"), RX("RX") }

/**
 * Where the radio layer writes evidence for a hardware run (test BT-00).
 *
 * Two kinds of record. [note] is a state-change event (link up, parameters sent, a burst finished) with `key=value` fields;
 * the driver also emits the same event as a `PROBE` line (`docs/testing/PROBES.md`, `radio.*`). [dump] is a raw byte dump with
 * a timestamp, **only in this sink, never in the app log**: it exists so a human can read exactly what crossed the serial
 * port. Dumps of Profile M traffic are ciphertext; dumps of the KISS/AX.25 test frames contain only the test text.
 */
public interface RadioEvidence {
    /** A state-change event. */
    public fun note(atMs: Long, name: String, fields: List<Pair<String, Any?>> = emptyList())

    /** A raw byte dump. */
    public fun dump(atMs: Long, direction: DumpDirection, data: ByteArray, label: String = "")

    /** Discards everything. */
    public object None : RadioEvidence {
        override fun note(atMs: Long, name: String, fields: List<Pair<String, Any?>>) {}

        override fun dump(atMs: Long, direction: DumpDirection, data: ByteArray, label: String) {}
    }
}

/** Bounded in-memory evidence log with a text rendering for export. Not thread-safe by itself; calls are synchronised. */
public class RadioEvidenceLog(private val maxLines: Int = 50_000) : RadioEvidence {
    private val lines = ArrayDeque<String>()
    private var dropped = 0L
    private val lock = Any()

    override fun note(atMs: Long, name: String, fields: List<Pair<String, Any?>>) {
        val sb = StringBuilder().append(atMs).append(" EVT ").append(name)
        for ((k, v) in fields) sb.append(' ').append(k).append('=').append(v?.toString()?.replace(' ', '_') ?: "-")
        add(sb.toString())
    }

    override fun dump(atMs: Long, direction: DumpDirection, data: ByteArray, label: String) {
        val sb = StringBuilder().append(atMs).append(' ').append(direction.label).append(' ').append(data.size).append("B ")
        if (label.isNotEmpty()) sb.append('[').append(label).append("] ")
        sb.append(toHex(data))
        add(sb.toString())
    }

    /** All lines in order, preceded by a note if older lines were discarded. */
    public fun snapshot(): List<String> = withRadioLock(lock) {
        if (dropped == 0L) lines.toList() else listOf("# $dropped older lines were discarded (limit $maxLines)") + lines
    }

    /** The log as one text, with [header] lines first (each prefixed `# `). */
    public fun render(header: List<String> = emptyList()): String =
        (header.map { "# $it" } + snapshot()).joinToString("\n", postfix = "\n")

    /** Number of retained lines. */
    public val size: Int get() = withRadioLock(lock) { lines.size }

    /** Empties the log. */
    public fun clear(): Unit = withRadioLock(lock) {
        lines.clear()
        dropped = 0
    }

    private fun add(line: String): Unit = withRadioLock(lock) {
        lines.addLast(line)
        if (lines.size > maxLines) {
            lines.removeFirst()
            dropped++
        }
    }

    /** Hex helper. */
    public companion object {
        private const val HEX = "0123456789ABCDEF"

        /** Upper-case, space separated: `C0 00 82`. */
        public fun toHex(data: ByteArray, limit: Int = 600): String {
            val n = minOf(data.size, limit)
            val sb = StringBuilder(n * 3 + 8)
            for (i in 0 until n) {
                if (i > 0) sb.append(' ')
                val v = data[i].toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            if (data.size > n) sb.append(" ...(+").append(data.size - n).append(')')
            return sb.toString()
        }
    }
}
