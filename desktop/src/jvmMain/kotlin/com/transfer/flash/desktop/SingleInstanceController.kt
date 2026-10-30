@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.logging.FlashLog
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.concurrent.thread

/**
 * Ensures that only one instance of the Flash desktop application runs at a time.
 *
 * Mechanism:
 * 1. File Lock: Obtains an exclusive OS-level file lock on `~/.flash/app.lock`.
 *    If the primary process terminates or is terminated by the OS, the kernel
 *    automatically releases the file lock immediately.
 * 2. IPC on Local Loopback: The primary instance starts a local [ServerSocket] on an ephemeral
 *    port bound strictly to `127.0.0.1` and writes this port to `~/.flash/app.port`.
 * 3. Forwarding: When a second instance attempts to launch:
 *    - `tryLock()` fails because the primary instance holds the exclusive OS lock.
 *    - The secondary instance reads `~/.flash/app.port`, connects to `127.0.0.1:<port>`,
 *      sends the `ACTIVATE` command, and exits immediately.
 *    - The primary instance receives `ACTIVATE` and invokes [onActivate], bringing
 *      its existing window to the front, un-minimizing it if needed, and focusing it.
 */
public object SingleInstanceController {

    private const val TAG = "SINGLE_INSTANCE"
    private const val COMMAND_ACTIVATE = "ACTIVATE"
    private const val COMMAND_SEND = "SEND"
    private const val END_OF_TRANSMISSION = "END_SEND"
    private const val RESPONSE_OK = "OK"

    private var lockFileChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var serverSocket: ServerSocket? = null
    private var lockFile: File? = null
    private var portFile: File? = null

    private val pendingInitialFiles = java.util.Collections.synchronizedList(mutableListOf<File>())

    @Volatile
    public var onActivate: (() -> Unit)? = null

    @Volatile
    public var onShareFiles: ((List<File>) -> Unit)? = null

    /**
     * Consumes and clears any initial share files received before UI wiring.
     */
    public fun consumeInitialShareFiles(): List<File> {
        synchronized(pendingInitialFiles) {
            val list = pendingInitialFiles.toList()
            pendingInitialFiles.clear()
            return list
        }
    }

    /**
     * Parses valid, existing files or directories from command line arguments.
     */
    public fun parseFilesFromArgs(args: Array<String>): List<File> {
        val files = mutableListOf<File>()
        for (arg in args) {
            val clean = arg.trim().removeSurrounding("\"")
            if (clean.equals("--send", ignoreCase = true)) continue
            if (clean.startsWith("-")) continue
            val file = File(clean)
            if (file.exists()) {
                files.add(file)
            }
        }
        return files
    }

    /**
     * Tries to acquire the single-instance lock.
     *
     * @return `true` if this is the primary instance and execution should proceed.
     *         `false` if another instance is already running; in this case, an activation
     *         message has been sent to the existing instance and this process must exit.
     */
    public fun acquireOrActivate(
        baseDir: File = DesktopPaths.stateDir(),
        args: Array<String> = emptyArray(),
    ): Boolean {
        baseDir.mkdirs()
        DesktopPaths.restrictToOwner(baseDir)
        lockFile = File(baseDir, "app.lock")
        portFile = File(baseDir, "app.port")

        val files = parseFilesFromArgs(args)

        val acquired = tryAcquireLock()
        if (acquired) {
            if (files.isNotEmpty()) {
                pendingInitialFiles.addAll(files)
            }
            startActivationServer()
            return true
        }

        // Another instance is already running; ping it to bring its window to front or transfer files
        notifyRunningInstance(files)
        return false
    }

    private fun tryAcquireLock(): Boolean {
        return try {
            val file = lockFile ?: return true
            val raf = RandomAccessFile(file, "rw")
            val channel = raf.channel
            val lock = channel.tryLock()
            if (lock != null && lock.isValid) {
                lockFileChannel = channel
                fileLock = lock
                file.deleteOnExit()
                true
            } else {
                channel.close()
                raf.close()
                false
            }
        } catch (_: OverlappingFileLockException) {
            false
        } catch (t: Throwable) {
            FlashLog.w(TAG, "File lock probe encountered exception: ${t.message}")
            false
        }
    }

    private fun startActivationServer() {
        try {
            val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            val port = server.localPort
            // SEC-D12-01: the port file also carries a random token; a request without it is dropped, so another local
            // process that merely finds the port cannot stage files or steal focus. The file is created owner-only
            // BEFORE the token is written, so it is never readable by another user even for an instant.
            val token = newToken()
            portFile?.let { file ->
                runCatching { if (!file.exists()) file.createNewFile() }
                restrictToOwner(file)
                file.writeText(port.toString() + "\n" + token)
            }
            portFile?.deleteOnExit()

            thread(name = "FlashSingleInstanceListener", isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        client.soTimeout = 3000
                        val reader = client.getInputStream().bufferedReader()
                        val presented = readBoundedLine(reader)
                        if (presented == null || !tokenMatches(presented.trim(), token)) {
                            FlashLog.w(TAG, "Ignoring an activation request without the instance token.")
                            client.close()
                            continue
                        }
                        val line = readBoundedLine(reader)
                        if (line != null) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith(COMMAND_SEND)) {
                                val paths = mutableListOf<String>()
                                val rest = trimmed.substring(COMMAND_SEND.length).trim()
                                if (rest.isNotEmpty()) paths.add(rest)
                                while (paths.size < MAX_SHARED_PATHS) {
                                    val nextLine = readBoundedLine(reader) ?: break
                                    val t = nextLine.trim()
                                    if (t == END_OF_TRANSMISSION || t.isEmpty()) break
                                    paths.add(t)
                                }
                                val validFiles = paths.map { File(it.removeSurrounding("\"")) }.filter { it.exists() }
                                FlashLog.i(TAG, "Received share files request from duplicate instance: ${validFiles.size} items.")
                                val callback = onShareFiles
                                if (callback != null && validFiles.isNotEmpty()) {
                                    javax.swing.SwingUtilities.invokeLater {
                                        onActivate?.invoke()
                                        callback(validFiles)
                                    }
                                } else {
                                    if (validFiles.isNotEmpty()) pendingInitialFiles.addAll(validFiles)
                                    javax.swing.SwingUtilities.invokeLater { onActivate?.invoke() }
                                }
                                client.getOutputStream().bufferedWriter().apply {
                                    write(RESPONSE_OK + "\n")
                                    flush()
                                }
                            } else if (trimmed.startsWith(COMMAND_ACTIVATE)) {
                                FlashLog.i(TAG, "Received activation ping from duplicate instance; focusing window.")
                                javax.swing.SwingUtilities.invokeLater {
                                    onActivate?.invoke()
                                }
                                client.getOutputStream().bufferedWriter().apply {
                                    write(RESPONSE_OK + "\n")
                                    flush()
                                }
                            }
                        }
                        client.close()
                    } catch (_: Throwable) {
                        // Socket closed or timeout
                    }
                }
            }
        } catch (t: Throwable) {
            FlashLog.w(TAG, "Could not bind single instance activation server: ${t.message}")
        }
    }

    private fun notifyRunningInstance(files: List<File> = emptyList()) {
        try {
            val lines = awaitPortFileLines()
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull()
            val token = lines.getOrNull(1)?.trim().orEmpty()
            if (port != null && port in 1..65535 && token.isNotEmpty()) {
                FlashLog.i(TAG, "Connecting to primary instance on 127.0.0.1:$port to request activation or send files...")
                Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                    socket.soTimeout = 4000
                    socket.getOutputStream().bufferedWriter().apply {
                        write(token + "\n")
                        if (files.isNotEmpty()) {
                            write(COMMAND_SEND + "\n")
                            for (f in files) {
                                write(f.absolutePath + "\n")
                            }
                            write(END_OF_TRANSMISSION + "\n")
                        } else {
                            write(COMMAND_ACTIVATE + "\n")
                        }
                        flush()
                    }
                    val resp = socket.getInputStream().bufferedReader().readLine()
                    FlashLog.i(TAG, "Primary instance response: $resp")
                }
            } else {
                FlashLog.w(TAG, "Existing instance running but app.port is missing or invalid.")
            }
        } catch (t: Throwable) {
            FlashLog.w(TAG, "Failed to send activation/send ping to running instance: ${t.message}")
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The port file, waiting out the primary instance's startup (it writes the file right after it takes the lock).
     * Without this wait, a second launch in that window found no file, logged "app.port is missing or invalid" and
     * exited without activating anything: the user's click did nothing at all.
     */
    private fun awaitPortFileLines(): List<String> {
        val deadline = System.currentTimeMillis() + PORT_FILE_WAIT_MS
        while (true) {
            val lines = portFile?.takeIf { it.exists() }?.readText()?.lines().orEmpty()
            if (lines.size >= 2 && lines[1].isNotBlank()) return lines
            if (System.currentTimeMillis() >= deadline) return lines
            try {
                Thread.sleep(PORT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return lines
            }
        }
    }

    internal fun tokenMatches(presented: String, expected: String): Boolean =
        java.security.MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    /** One line of at most [MAX_LINE_CHARS] characters; a longer line is treated as the end of the input. */
    private fun readBoundedLine(reader: java.io.Reader): String? {
        val sb = StringBuilder()
        while (sb.length <= MAX_LINE_CHARS) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return null
    }

    /** Best effort: `0600` where POSIX permissions exist, owner-only flags elsewhere (SEC-D12-01, D12-03). */
    internal fun restrictToOwner(file: File) {
        try {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        } catch (_: Throwable) {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private const val MAX_LINE_CHARS = 4096
    private const val MAX_SHARED_PATHS = 256

    /** How long a duplicate launch waits for the primary instance to publish `app.port` (SEC-D12-01 ordering). */
    private const val PORT_FILE_WAIT_MS = 3_000L
    private const val PORT_POLL_MS = 50L

    /**
     * Releases locks and sockets on app termination.
     */
    public fun release() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null

        try {
            fileLock?.release()
        } catch (_: Throwable) {}
        fileLock = null

        try {
            lockFileChannel?.close()
        } catch (_: Throwable) {}
        lockFileChannel = null

        try {
            portFile?.delete()
        } catch (_: Throwable) {}
        try {
            lockFile?.delete()
        } catch (_: Throwable) {}
    }
}
