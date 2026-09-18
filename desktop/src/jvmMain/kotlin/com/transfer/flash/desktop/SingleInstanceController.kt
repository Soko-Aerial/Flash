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
    private const val RESPONSE_OK = "OK"

    private var lockFileChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var serverSocket: ServerSocket? = null
    private var lockFile: File? = null
    private var portFile: File? = null

    @Volatile
    public var onActivate: (() -> Unit)? = null

    /**
     * Tries to acquire the single-instance lock.
     *
     * @return `true` if this is the primary instance and execution should proceed.
     *         `false` if another instance is already running; in this case, an activation
     *         message has been sent to the existing instance and this process must exit.
     */
    public fun acquireOrActivate(): Boolean {
        val flashDir = File(System.getProperty("user.home", "."), ".flash").apply { mkdirs() }
        lockFile = File(flashDir, "app.lock")
        portFile = File(flashDir, "app.port")

        val acquired = tryAcquireLock()
        if (acquired) {
            startActivationServer()
            return true
        }

        // Another instance is already running; ping it to bring its window to front
        notifyRunningInstance()
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
            portFile?.writeText(port.toString())
            portFile?.deleteOnExit()

            thread(name = "FlashSingleInstanceListener", isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        client.soTimeout = 3000
                        val reader = client.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        if (line != null && line.trim().startsWith(COMMAND_ACTIVATE)) {
                            FlashLog.i(TAG, "Received activation ping from duplicate instance; focusing window.")
                            javax.swing.SwingUtilities.invokeLater {
                                onActivate?.invoke()
                            }
                            client.getOutputStream().bufferedWriter().apply {
                                write(RESPONSE_OK + "\n")
                                flush()
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

    private fun notifyRunningInstance() {
        try {
            val portStr = portFile?.takeIf { it.exists() }?.readText()?.trim()
            val port = portStr?.toIntOrNull()
            if (port != null && port in 1..65535) {
                FlashLog.i(TAG, "Connecting to primary instance on 127.0.0.1:$port to request window activation...")
                Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().bufferedWriter().apply {
                        write(COMMAND_ACTIVATE + "\n")
                        flush()
                    }
                    val resp = socket.getInputStream().bufferedReader().readLine()
                    FlashLog.i(TAG, "Primary instance response: $resp")
                }
            } else {
                FlashLog.w(TAG, "Existing instance running but app.port is missing or invalid.")
            }
        } catch (t: Throwable) {
            FlashLog.w(TAG, "Failed to send activation ping to running instance: ${t.message}")
        }
    }

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
