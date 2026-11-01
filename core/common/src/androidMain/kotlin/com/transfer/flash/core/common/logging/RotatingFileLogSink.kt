@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Keeps Flash's own log on the device so a failure that happened while nobody was watching `adb logcat` can still be read
 * (owner request 2026-10-06; the ring buffer of logcat holds minutes on a busy phone).
 *
 * Ordinary queue admission never takes the storage lock; explicit flush, crash and export calls can wait on storage, and
 * an optional forwarding sink has its own latency. A full queue drops the newest line and counts it. Files are
 * `flash-0.log` (current) to `flash-<keep-1>.log`, rotated at [maxFileBytes]; each file can overshoot by one encoded line.
 *
 * Redaction happens on the writer before persistence ([redact]); the bounded queue temporarily holds raw messages and
 * throwables. AGENTS.md section 24 still forbids logging secrets. Redaction is defence in depth, not permission to log them.
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

    private class LogEntry(
        val timestampMs: Long,
        val level: FlashLogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private val queue = ArrayBlockingQueue<LogEntry>(queueCapacity)
    private val dropped = java.util.concurrent.atomic.AtomicLong(0)
    private val format = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    // Dequeue, format, persist and export share one lock. In particular, no entry
    // may disappear from the queue before flush can account for its disk write.
    private val lock = Any()
    private val enqueueLock = Any()
    private val wake = Semaphore(0)

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
        // Only queue admission is serialized here; producers never take the disk lock.
        synchronized(enqueueLock) {
            if (stopped) return
            if (!queue.offer(LogEntry(clock(), level, tag, message, throwable))) {
                dropped.incrementAndGet()
            }
            wake.release()
        }
    }

    private fun formatEntry(entry: LogEntry): String {
        val sb = StringBuilder(entry.message.length + 64)
        sb.append(format.get()!!.format(Date(entry.timestampMs)))
            .append(' ')
            .append(entry.level.name.first())
            .append('/')
            .append(entry.tag)
            .append(": ")
        sb.append(redact(entry.message))
        if (entry.throwable != null) {
            sb.append('\n').append(redact(stackTrace(entry.throwable)))
        }
        return sb.toString()
    }

    /** Writes a fatal crash straight to the file (the writer thread may not get another turn), then returns. */
    public fun writeCrashNow(threadName: String, throwable: Throwable) {
        try {
            synchronized(lock) {
                drainQueue()
                val line = "${format.get()!!.format(Date(clock()))} E/CRASH: uncaught in thread $threadName\n${redact(stackTrace(throwable))}"
                appendLines(listOf(line))
            }
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
        synchronized(enqueueLock) {
            stopped = true
            wake.release()
        }
        flush()
        if (Thread.currentThread() !== writer) {
            try {
                writer.join(5_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** The files that make up the log, oldest first. Paths alone are not a rotation-safe export snapshot. */
    public fun files(): List<File> = synchronized(lock) {
        (keep - 1 downTo 0).map { File(dir, "flash-$it.log") }.filter { it.isFile && it.length() > 0 }
    }

    /**
     * Streams the header and a rotation-stable log snapshot into gzip, without an
     * uncompressed export copy. Producers still enqueue; disk writes wait for this copy.
     * Compression ratio depends on the log content and has not been measured here.
     */
    public fun exportGzip(destination: File, headerLines: List<String> = emptyList()) {
        require((0 until keep).none { File(dir, "flash-$it.log").canonicalFile == destination.canonicalFile }) {
            "Export destination must not replace a rotating log file"
        }
        destination.parentFile?.mkdirs()
        synchronized(lock) {
            drainQueue()
            GZIPOutputStream(FileOutputStream(destination).buffered(16384)).use { gzip ->
                val headerWriter = gzip.bufferedWriter(Charsets.UTF_8)
                for (line in headerLines) {
                    headerWriter.write(line)
                    headerWriter.write("\n")
                }
                headerWriter.flush()
                for (file in files()) {
                    file.inputStream().buffered(16384).use { input -> input.copyTo(gzip) }
                }
                gzip.finish()
            }
        }
    }

    private fun runWriter() {
        while (!stopped) {
            try {
                // Wait outside the disk lock. Dequeuing happens inside it so flush,
                // crash and export cannot miss a batch being formatted by this thread.
                if (queue.isEmpty()) wake.tryAcquire(500, TimeUnit.MILLISECONDS)
                wake.drainPermits()
                drainQueue(256)
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // Storage full or unwritable: drop the batch, keep the thread alive.
            }
        }
    }

    private fun drainQueue(maxEntries: Int = Int.MAX_VALUE) = synchronized(lock) {
        val rawBatch = ArrayList<LogEntry>(minOf(maxEntries, 256))
        queue.drainTo(rawBatch, maxEntries)
        val lines = ArrayList<String>(rawBatch.size + 1)
        for (entry in rawBatch) {
            lines += formatEntry(entry)
        }
        val lost = dropped.getAndSet(0)
        if (lost > 0) lines += "${format.get()!!.format(Date(clock()))} W/LOG: $lost line(s) dropped (log queue was full)"
        if (lines.isNotEmpty()) appendLines(lines)
    }

    private fun appendLines(lines: List<String>) = synchronized(lock) {
        val current = File(dir, "flash-0.log")
        // Rotate per LINE, not per batch: the writer appends up to 256 queued lines at once (and flush() drains the whole
        // queue), so checking the size only before the batch let one file grow by a whole batch past maxFileBytes and broke
        // the documented bound of keep * maxFileBytes. A file now overshoots by at most one line.
        var out: BufferedOutputStream? = null
        try {
            var size = current.length()
            for (l in lines) {
                if (size >= maxFileBytes) {
                    out?.flush()
                    out?.close()
                    out = null
                    rotate()
                    size = 0L
                }
                val bytes = (l + '\n').toByteArray(Charsets.UTF_8)
                val stream = out ?: BufferedOutputStream(FileOutputStream(current, true), 8192).also { out = it }
                stream.write(bytes)
                size += bytes.size
            }
            out?.flush()
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
