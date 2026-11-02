@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.logging.FlashLogLevel
import com.transfer.flash.core.common.logging.FlashLogSink
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One worker owns every console/file write, flush and close. Producers only offer to a bounded
 * queue; overflow drops the newest record and the worker reports the count. Shutdown stops
 * admission and waits for that same worker, including a record it has already dequeued.
 * Storage/console failures remain best-effort, not a guarantee of durable logging.
 */
internal class DesktopFileLogSink(
    private val output: Writer,
    private val console: PrintStream = System.err,
    queueCapacity: Int = 4_096,
    private val clock: () -> LocalTime = LocalTime::now,
) : FlashLogSink, AutoCloseable {
    private data class Entry(
        val time: LocalTime,
        val level: FlashLogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private val queue = ArrayBlockingQueue<Entry>(queueCapacity)
    private val admissionLock = Any()
    private val dropped = AtomicLong(0)
    private val wake = Semaphore(0)
    @Volatile private var accepting = true
    private val worker = Thread(::runWriter, "FlashDesktopLogWorker").apply {
        isDaemon = true
        start()
    }

    override fun write(level: FlashLogLevel, tag: String, message: String, throwable: Throwable?) {
        try {
            synchronized(admissionLock) {
                if (!accepting) return
                if (!queue.offer(Entry(clock(), level, tag, message, throwable))) dropped.incrementAndGet()
                wake.release()
            }
        } catch (_: Throwable) {
            // Diagnostics must never stop the operation being described.
        }
    }

    /** False means a blocked output did not finish within the wait, NOT a second writer taking over. */
    internal fun closeAndAwait(timeoutMs: Long = 5_000L): Boolean {
        require(timeoutMs > 0)
        synchronized(admissionLock) {
            accepting = false
            wake.release()
        }
        if (Thread.currentThread() === worker) return false
        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !worker.isAlive
    }

    override fun close() {
        closeAndAwait()
    }

    private fun runWriter() {
        try {
            while (true) {
                if (queue.isEmpty() && accepting && dropped.get() == 0L) {
                    wake.tryAcquire(500L, TimeUnit.MILLISECONDS)
                }
                wake.drainPermits()
                val batch = ArrayList<Entry>(256)
                queue.drainTo(batch, 256)
                for (entry in batch) {
                    try {
                        append(entry)
                    } catch (_: Throwable) {
                        // An unwritable log drops this record, without killing the worker.
                    }
                }
                val lost = dropped.getAndSet(0L)
                if (lost > 0L) {
                    try {
                        append(Entry(clock(), FlashLogLevel.WARN, "LOG",
                            "$lost line(s) dropped (desktop log queue was full)", null))
                    } catch (_: Throwable) {
                    }
                }
                try { output.flush() } catch (_: Throwable) {}
                if (!accepting && queue.isEmpty() && dropped.get() == 0L) break
            }
        } finally {
            try { output.flush() } catch (_: Throwable) {}
            try { output.close() } catch (_: Throwable) {}
        }
    }

    private fun append(entry: Entry) {
        val line = "${entry.level.name.first()}/${entry.tag}: ${entry.message}"
        val trace = entry.throwable?.let {
            val text = StringWriter()
            PrintWriter(text).use { writer -> it.printStackTrace(writer) }
            text.toString()
        }.orEmpty()
        val consoleRecord = "$line\n$trace"
        console.print(consoleRecord)
        output.write("${LOG_TIME.format(entry.time)} $consoleRecord")
    }

    private companion object {
        val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
