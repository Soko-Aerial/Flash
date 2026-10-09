@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps Flash's own log on the device so a failure that happened while nobody was watching `adb logcat` can still be read
 * (owner request 2026-10-06; the ring buffer of logcat holds minutes on a busy phone).
 *
 * Lines go to a bounded queue and a single daemon thread appends them, so a caller never waits on storage; when the queue is
 * full the newest line is dropped and counted (a logger must never stall the transfer it describes). Files are
 * `flash-0.log` (current) to `flash-<keep-1>.log`, rotated at [maxFileBytes]; total size is bounded by `keep * maxFileBytes`.
 *
 * Secrets are removed before a line is queued ([redact]): AGENTS.md section 24 forbids logging them, this is the second line
 * of defence for a line that slipped through. It is not a licence to log them.
 *
 * Implementations of [FlashLogSink] must not throw: every failure here is swallowed.
 */
public class RotatingFileLogSink(
    private val dir: File,
    private val forward: FlashLogSink? = null,
    private val maxFileBytes: Long = 1_000_000L,
    private val keep: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = 4_096,
) : FlashLogSink {

    private val queue = ArrayBlockingQueue<String>(queueCapacity)
    private val dropped = java.util.concurrent.atomic.AtomicLong(0)
    private val format = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    @Volatile
    private var stopped = false

    private val writer = Thread({ runWriter() }, "flash-file-log").apply { isDaemon = true }

    init {
        try {
            dir.mkdirs()
        } catch (_: Throwable) {
        }
        writer.start()
    }

    override fun write(level: FlashLogLevel, tag: String, message: String, throwable: Throwable?) {
        try {
            forward?.write(level, tag, message, throwable)
        } catch (_: Throwable) {
        }
        try {
            enqueue(level, tag, message, throwable)
        } catch (_: Throwable) {
        }
    }

    private fun enqueue(level: FlashLogLevel, tag: String, message: String, throwable: Throwable?) {
        val sb = StringBuilder(message.length + 48)
        sb.append(format.get()!!.format(Date(clock()))).append(' ').append(level.name.first()).append('/').append(tag).append(": ")
        sb.append(redact(message))
        if (throwable != null) sb.append('\n').append(redact(stackTrace(throwable)))
        if (!queue.offer(sb.toString())) dropped.incrementAndGet()
    }

    /** Writes a fatal crash straight to the file (the writer thread may not get another turn), then returns. */
    public fun writeCrashNow(threadName: String, throwable: Throwable) {
        try {
            drainQueue()
            val line = "${format.get()!!.format(Date(clock()))} E/CRASH: uncaught in thread $threadName\n${redact(stackTrace(throwable))}"
            appendLines(listOf(line))
        } catch (_: Throwable) {
        }
    }

    /** Blocks until everything queued so far is on disk (used before an export and by tests). */
    public fun flush() {
        try {
            drainQueue()
        } catch (_: Throwable) {
        }
    }

    public fun stop() {
        stopped = true
        flush()
    }

    /** The files that make up the log, oldest first. */
    public fun files(): List<File> =
        (keep - 1 downTo 0).map { File(dir, "flash-$it.log") }.filter { it.isFile && it.length() > 0 }

    private val lock = Any()

    private fun runWriter() {
        while (!stopped) {
            try {
                val first = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val batch = ArrayList<String>(64)
                batch += first
                queue.drainTo(batch, 255)
                appendLines(batch)
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // Storage full or unwritable: drop the batch, keep the thread alive.
            }
        }
    }

    private fun drainQueue() {
        val batch = ArrayList<String>()
        queue.drainTo(batch)
        val lost = dropped.getAndSet(0)
        if (lost > 0) batch += "${format.get()!!.format(Date(clock()))} W/LOG: $lost line(s) dropped (log queue was full)"
        if (batch.isNotEmpty()) appendLines(batch)
    }

    private fun appendLines(lines: List<String>) = synchronized(lock) {
        val current = File(dir, "flash-0.log")
        // Rotate per LINE, not per batch: the writer appends up to 256 queued lines at once (and flush() drains the whole
        // queue), so checking the size only before the batch let one file grow by a whole batch past maxFileBytes and broke
        // the documented bound of keep * maxFileBytes. A file now overshoots by at most one line.
        var out: FileOutputStream? = null
        try {
            var size = current.length()
            for (l in lines) {
                if (size >= maxFileBytes) {
                    out?.close()
                    out = null
                    rotate()
                    size = 0L
                }
                val bytes = (l + '\n').toByteArray(Charsets.UTF_8)
                val stream = out ?: FileOutputStream(current, true).also { out = it }
                stream.write(bytes)
                size += bytes.size
            }
        } finally {
            out?.close()
        }
    }

    private fun rotate() {
        File(dir, "flash-${keep - 1}.log").delete()
        for (i in keep - 2 downTo 0) {
            val from = File(dir, "flash-$i.log")
            if (from.exists()) from.renameTo(File(dir, "flash-${i + 1}.log"))
        }
    }

    public companion object {
        private val SECRET_ASSIGN = Regex(
            """(?i)\b(secret|passphrase|password|privatekey|private_key|token|authkey|invite)\s*[=:]\s*\S+""",
        )
        private val INVITE_LINK = Regex("""flash://g/\S+""")
        private val BASE64_BLOB = Regex("[A-Za-z0-9_-]{64,}={0,2}")

        /**
         * Removes what AGENTS.md section 24 says must never be logged. A 64+ character base64 or hex run is treated as key
         * material; device ids in this app are shorter or are log-safe identifiers, so diagnosis still works.
         */
        public fun redact(text: String): String =
            text.replace(INVITE_LINK, "flash://g/<redacted>")
                .replace(SECRET_ASSIGN, "\$1=<redacted>")
                .replace(BASE64_BLOB, "<redacted-blob>")

        private fun stackTrace(t: Throwable): String {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }
    }
}

/**
 * Installs [RotatingFileLogSink] as the process log sink (logcat keeps working) and an uncaught-exception handler that writes
 * the crash to the file before delegating to the previous handler. Call once from `Application.onCreate`.
 */
public fun installAndroidFileLog(dir: File): RotatingFileLogSink {
    val sink = RotatingFileLogSink(dir = dir, forward = platformLogSink())
    FlashLog.installSink(sink)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        sink.writeCrashNow(thread.name, throwable)
        previous?.uncaughtException(thread, throwable)
    }
    return sink
}
