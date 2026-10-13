package com.transfer.flash.server.observability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * The hub's counters and histograms, rendered as Prometheus text (`/metrics`, REL-13). Everything is lock-free
 * (`LongAdder`) because the relay hot path increments it on every frame.
 *
 * Label values come ONLY from closed sets (error codes, frame kinds, ack statuses); a client-controlled string is
 * never a label, so the series count stays bounded.
 */
class RelayMetrics {

    private class Series(val name: String, val labels: List<Pair<String, String>>) {
        val value = LongAdder()
    }

    private val series = ConcurrentHashMap<String, Series>()

    /** Time from handing a live message to the recipient's queue until its `FLASH_RECV_ACK`, in seconds. */
    val ackLatency: Histogram = Histogram()

    /** Time a message waited in the mailbox until its `FLASH_RECV_ACK`, in seconds. */
    val mailboxResidence: Histogram = Histogram()

    fun inc(name: String, by: Long = 1L, vararg labels: Pair<String, String>) {
        val key = keyOf(name, labels)
        series.computeIfAbsent(key) { Series(name, labels.toList()) }.value.add(by)
    }

    fun count(name: String, vararg labels: Pair<String, String>): Long = series[keyOf(name, labels)]?.value?.sum() ?: 0L

    /** Sum over every label set of [name]. */
    fun total(name: String): Long = series.values.filter { it.name == name }.sumOf { it.value.sum() }

    /** A gauge value computed at scrape time. */
    class Gauge(val name: String, val help: String, val value: Number)

    fun renderPrometheus(gauges: List<Gauge> = emptyList()): String {
        val out = StringBuilder(4096)
        for (g in gauges.sortedBy { it.name }) {
            out.append("# HELP ").append(g.name).append(' ').append(g.help).append('\n')
            out.append("# TYPE ").append(g.name).append(" gauge\n")
            out.append(g.name).append(' ').append(format(g.value)).append('\n')
        }
        val byName = series.values.groupBy { it.name }.toSortedMap()
        for ((name, group) in byName) {
            out.append("# HELP ").append(name).append(' ').append(HELP[name] ?: name).append('\n')
            out.append("# TYPE ").append(name).append(" counter\n")
            for (s in group.sortedBy { keyOf(it.name, it.labels.toTypedArray()) }) {
                out.append(name)
                if (s.labels.isNotEmpty()) {
                    out.append('{')
                    out.append(s.labels.joinToString(",") { (k, v) -> "$k=\"${escape(v)}\"" })
                    out.append('}')
                }
                out.append(' ').append(s.value.sum()).append('\n')
            }
        }
        ackLatency.render(out, "flash_relay_ack_latency_seconds", "Live message hand-off to recipient FLASH_RECV_ACK.")
        mailboxResidence.render(out, "flash_mailbox_residence_seconds", "Mailbox row age when the recipient acknowledged it.")
        return out.toString()
    }

    class Histogram(private val bounds: DoubleArray = DEFAULT_BOUNDS) {
        private val buckets = Array(bounds.size + 1) { LongAdder() }
        private val sumMicros = LongAdder()
        private val total = LongAdder()

        fun observeSeconds(seconds: Double) {
            val s = if (seconds < 0 || seconds.isNaN()) 0.0 else seconds
            var i = 0
            while (i < bounds.size && s > bounds[i]) i++
            buckets[i].increment()
            sumMicros.add((s * 1_000_000).toLong())
            total.increment()
        }

        fun count(): Long = total.sum()

        internal fun render(out: StringBuilder, name: String, help: String) {
            out.append("# HELP ").append(name).append(' ').append(help).append('\n')
            out.append("# TYPE ").append(name).append(" histogram\n")
            var cumulative = 0L
            for (i in bounds.indices) {
                cumulative += buckets[i].sum()
                out.append(name).append("_bucket{le=\"").append(bounds[i]).append("\"} ").append(cumulative).append('\n')
            }
            cumulative += buckets[bounds.size].sum()
            out.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append('\n')
            out.append(name).append("_sum ").append(sumMicros.sum() / 1_000_000.0).append('\n')
            out.append(name).append("_count ").append(total.sum()).append('\n')
        }

        companion object {
            val DEFAULT_BOUNDS = doubleArrayOf(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 300.0, 3600.0)
        }
    }

    private fun keyOf(name: String, labels: Array<out Pair<String, String>>): String =
        if (labels.isEmpty()) name else name + labels.joinToString(",", "{", "}") { "${it.first}=${it.second}" }

    private fun escape(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun format(n: Number): String = if (n is Double || n is Float) n.toString() else n.toLong().toString()

    companion object {
        const val HANDSHAKES = "flash_relay_handshakes_total"
        const val FRAMES_IN = "flash_relay_frames_in_total"
        const val FRAMES_OUT = "flash_relay_frames_out_total"
        const val BYTES_IN = "flash_relay_bytes_in_total"
        const val BYTES_OUT = "flash_relay_bytes_out_total"
        const val MESSAGES = "flash_relay_messages_total"
        const val REDELIVERIES = "flash_relay_redeliveries_total"
        const val RATE_LIMIT_HITS = "flash_relay_rate_limit_hits_total"
        const val SESSIONS_CLOSED = "flash_relay_sessions_closed_total"
        const val DB_ERRORS = "flash_db_errors_total"
        const val MAILBOX_EVENTS = "flash_mailbox_events_total"
        const val REJECTED_CONNECTIONS = "flash_relay_rejected_connections_total"

        private val HELP = mapOf(
            HANDSHAKES to "Relay handshakes by result (ok or the refusal code).",
            FRAMES_IN to "Relay frames received, by frame kind.",
            FRAMES_OUT to "Relay frames queued to clients, by frame kind.",
            BYTES_IN to "Relay bytes received.",
            BYTES_OUT to "Relay bytes queued to clients.",
            MESSAGES to "Messages by outcome: delivered_live, queued, duplicate, quota_exceeded, rejected, error.",
            REDELIVERIES to "Mailbox rows sent to a client again (replay=true).",
            RATE_LIMIT_HITS to "Frames refused by the per-session rate limit.",
            SESSIONS_CLOSED to "Relay sessions ended, by reason.",
            DB_ERRORS to "Storage errors, by operation.",
            MAILBOX_EVENTS to "Mailbox lifecycle events: delivered, expired, dropped, spilled.",
            REJECTED_CONNECTIONS to "Connections refused before the handshake (global or per-IP cap, shutdown).",
        )
    }
}
