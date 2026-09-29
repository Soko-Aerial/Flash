package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.RtcStats
import com.shepeliev.webrtckmp.RtcStatsReport
import kotlin.math.roundToInt

/**
 * Device-test diagnostics for calls (2026-09-29): one `CALL_DIAG` line per connection and one
 * for this process, at most every [periodMs], built from the `getStats()` reports the stats
 * samplers already take, so they cost no extra native work. For reading test runs; never shown
 * in the UI, and nothing in them identifies a person beyond the device ids the logs already carry.
 *
 * A connection line reads (fields are left out when the backend doesn't report them):
 * ```
 * CALL_DIAG leg=<peer> CONNECTED rtt=4ms bwe=2400kbps path=host/host
 *   | vin 640x360 24.0fps dec=libvpx 2.1ms dropped=0 freezes=0 lost=0 jitter=3ms 780kbps
 *   | vout 960x540 30.0fps enc=libvpx 6.8ms limit=none 900kbps | cam 1280x720 30.0fps
 *   | ain 31kbps lost=0 | aout 29kbps
 * ```
 * Rates (`fps`, `kbps`, decode and encode milliseconds) are over the interval since the
 * previous line for that connection; `lost`, `dropped` and `freezes` are call totals.
 *
 * Not thread-safe: each session calls it from its one stats loop.
 */
internal class CallDiagnostics(private val periodMs: Long = PERIOD_MS) {

    private class Totals(val values: Map<String, Double>, val atMs: Long)

    private val previous = HashMap<String, Totals>()
    private var lastLogAtMs = 0L
    private var lastCpuNanos: Long? = null
    private var lastCpuAtMs = 0L

    /** Whether a round of lines is due at [nowMs]; true at most once per [periodMs]. */
    fun due(nowMs: Long): Boolean {
        if (lastLogAtMs != 0L && nowMs - lastLogAtMs < periodMs) return false
        lastLogAtMs = nowMs
        return true
    }

    /** The line for one connection ([label], usually the peer id) from its stats [report]. */
    fun legLine(label: String, state: String, report: RtcStatsReport, nowMs: Long): String {
        val all = report.stats.values
        fun first(type: String, kind: String): RtcStats? = all.firstOrNull {
            it.type == type && (it.members.str("kind") ?: it.members.str("mediaType")) == kind
        }
        val vin = first("inbound-rtp", "video")
        val vout = all.filter { it.type == "outbound-rtp" && (it.members.str("kind") ?: it.members.str("mediaType")) == "video" }
        val ain = first("inbound-rtp", "audio")
        val aout = first("outbound-rtp", "audio")
        val cam = first("media-source", "video")

        val now = HashMap<String, Double>()
        vin?.members?.let { m ->
            now["vin.bytes"] = m.num("bytesReceived") ?: 0.0
            now["vin.frames"] = m.num("framesDecoded") ?: 0.0
            now["vin.decode"] = m.num("totalDecodeTime") ?: 0.0
        }
        if (vout.isNotEmpty()) {
            now["vout.bytes"] = vout.sumOf { it.members.num("bytesSent") ?: 0.0 }
            now["vout.frames"] = vout.sumOf { it.members.num("framesEncoded") ?: 0.0 }
            now["vout.encode"] = vout.sumOf { it.members.num("totalEncodeTime") ?: 0.0 }
        }
        ain?.members?.let { now["ain.bytes"] = it.num("bytesReceived") ?: 0.0 }
        aout?.members?.let { now["aout.bytes"] = it.num("bytesSent") ?: 0.0 }

        val before = previous[label]
        previous[label] = Totals(now, nowMs)
        val seconds = if (before != null) (nowMs - before.atMs) / 1000.0 else 0.0
        fun delta(key: String): Double? {
            val a = now[key] ?: return null
            val b = before?.values?.get(key) ?: return null
            return (a - b).coerceAtLeast(0.0)
        }
        fun kbps(key: String): String? =
            if (seconds <= 0.0) null else delta(key)?.let { "${(it * 8 / 1000 / seconds).roundToInt()}kbps" }
        fun perFrameMs(time: String, frames: String): String? {
            val f = delta(frames) ?: return null
            val t = delta(time) ?: return null
            return if (f <= 0.0) null else "${(t * 1000 / f).r1()}ms"
        }
        fun fps(frames: String, reported: Double?): String? {
            val measured = if (seconds > 0.0) delta(frames)?.let { it / seconds } else null
            return (measured ?: reported)?.let { "${it.r1()}fps" }
        }

        val parts = mutableListOf<String>()
        parts += listOfNotNull("leg=$label", state, pathSummary(all)).joinToString(" ")

        vin?.members?.let { m ->
            parts += listOfNotNull(
                "vin",
                size(m),
                fps("vin.frames", m.num("framesPerSecond")),
                m.str("decoderImplementation")?.let { "dec=$it" },
                perFrameMs("vin.decode", "vin.frames"),
                m.num("framesDropped")?.let { "dropped=${it.toLong()}" },
                m.num("freezeCount")?.let { "freezes=${it.toLong()}" },
                m.num("packetsLost")?.let { "lost=${it.toLong()}" },
                m.num("jitter")?.let { "jitter=${(it * 1000).roundToInt()}ms" },
                m.num("pliCount")?.let { "pli=${it.toLong()}" },
                kbps("vin.bytes"),
            ).joinToString(" ")
        } ?: run { parts += "vin none" }

        if (vout.isNotEmpty()) {
            val m = vout.first().members
            parts += listOfNotNull(
                "vout",
                size(m),
                fps("vout.frames", m.num("framesPerSecond")),
                m.str("encoderImplementation")?.let { "enc=$it" },
                perFrameMs("vout.encode", "vout.frames"),
                m.str("qualityLimitationReason")?.let { "limit=$it" },
                m.bool("active")?.let { "active=$it" },
                kbps("vout.bytes"),
            ).joinToString(" ")
        }
        cam?.members?.let { m ->
            parts += listOfNotNull("cam", size(m), m.num("framesPerSecond")?.let { "${it.r1()}fps" }).joinToString(" ")
        }
        ain?.members?.let { m ->
            parts += listOfNotNull(
                "ain",
                kbps("ain.bytes"),
                m.num("packetsLost")?.let { "lost=${it.toLong()}" },
                m.num("jitter")?.let { "jitter=${(it * 1000).roundToInt()}ms" },
                m.num("concealedSamples")?.let { "concealed=${it.toLong()}" },
            ).joinToString(" ")
        }
        aout?.members?.let { parts += listOfNotNull("aout", kbps("aout.bytes")).joinToString(" ") }
        return parts.joinToString(" | ")
    }

    /** Forgets a connection's totals (it closed), so a new one starts clean. */
    fun forget(label: String) {
        previous.remove(label)
    }

    /**
     * The process line: this process's share of all cores since the previous process line,
     * memory ([processMemorySummary]), plus the session's own [extra] fields.
     */
    fun processLine(nowMs: Long, extra: String): String {
        val cpu = processCpuTimeNanos()
        val prev = lastCpuNanos
        val elapsedMs = nowMs - lastCpuAtMs
        lastCpuNanos = cpu
        lastCpuAtMs = nowMs
        val cores = availableCores()
        val cpuText = if (cpu != null && prev != null && elapsedMs > 0L) {
            val percent = (cpu - prev).coerceAtLeast(0L) / 1_000_000.0 * 100.0 / elapsedMs
            // Both forms: percent of one core (what `top` shows) and of all cores (what Windows
            // Task Manager shows, and what the G6 CPU threshold uses).
            "cpu=${percent.roundToInt()}%core (${(percent / cores).roundToInt()}% of $cores cores)"
        } else {
            "cpu=? cores=$cores"
        }
        return "proc $cpuText ${processMemorySummary()} $extra"
    }

    private fun pathSummary(all: Collection<RtcStats>): String? {
        val selectedId = all.firstOrNull { it.type == "transport" }?.members?.str("selectedCandidatePairId")
        val pairs = all.filter { it.type == "candidate-pair" }
        val pair = pairs.firstOrNull { it.id == selectedId }
            ?: pairs.firstOrNull { it.members.bool("nominated") == true && it.members.str("state") == "succeeded" }
            ?: return null
        val m = pair.members
        val local = all.firstOrNull { it.id == m.str("localCandidateId") }?.members?.str("candidateType")
        val remote = all.firstOrNull { it.id == m.str("remoteCandidateId") }?.members?.str("candidateType")
        return listOfNotNull(
            m.num("currentRoundTripTime")?.let { "rtt=${(it * 1000).roundToInt()}ms" },
            m.num("availableOutgoingBitrate")?.let { "bwe=${(it / 1000).roundToInt()}kbps" },
            if (local != null || remote != null) "path=${local ?: "?"}/${remote ?: "?"}" else null,
        ).joinToString(" ").ifBlank { null }
    }

    private fun size(m: Map<String, Any>): String? {
        val w = m.num("frameWidth") ?: m.num("width") ?: return null
        val h = m.num("frameHeight") ?: m.num("height") ?: return null
        return "${w.toInt()}x${h.toInt()}"
    }

    private fun Double.r1(): Double = (this * 10).roundToInt() / 10.0

    private fun Map<String, Any>.num(key: String): Double? =
        (this[key] as? Number)?.toDouble() ?: (this[key] as? String)?.toDoubleOrNull()

    private fun Map<String, Any>.str(key: String): String? =
        (this[key] as? String) ?: this[key]?.toString()

    private fun Map<String, Any>.bool(key: String): Boolean? =
        (this[key] as? Boolean) ?: (this[key] as? String)?.toBooleanStrictOrNull()

    companion object {
        const val TAG: String = "CALL_DIAG"
        const val PERIOD_MS: Long = 5_000L
    }
}

/**
 * This process's memory for the `CALL_DIAG` process line (2026-09-29). Desktop: JVM heap,
 * committed (on Windows, the process's private bytes, which is where native Skia/WebRTC memory
 * shows), direct buffers, threads, free system memory. Android: JVM heap, native heap, threads.
 * Never throws; "mem=?" when the platform can't tell.
 */
internal expect fun processMemorySummary(): String
