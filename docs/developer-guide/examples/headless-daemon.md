# Practical Examples: Headless Daemon & Background Service

Flash is not tied to a graphical user interface. You can run Flash in headless mode as a Linux systemd daemon, Windows service, or headless container to act as an automated file transfer agent, backup receiver, or relay.

---

## 1. Headless Architecture

In headless mode:
* Excludes all `:ui:*` dependencies.
* Relies solely on `:core:engine`, `:core:network`, `:core:transfer`, and `:core:discovery`.
* Automatically receives and saves incoming files to a designated directory.
* Controlled via command-line arguments or standard input.

---

## 2. Headless Daemon Implementation

```kotlin
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.desktop.DesktopEngine
import kotlinx.coroutines.*
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val receiveDir = File(args.getOrNull(0) ?: "./received_files").apply { mkdirs() }
    println("Starting Flash Headless Daemon...")
    println("Destination Directory: ${receiveDir.absolutePath}")

    val daemonScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val engine = DesktopEngine(
        scope = daemonScope,
        peerId = "headless_storage_node",
        displayName = "StorageNode-01"
    )

    engine.start()

    // Monitor active incoming and completed transfers
    daemonScope.launch {
        engine.transfers.transfers.collect { transfers ->
            transfers.filter { it.status.isCompleted }.forEach { transfer ->
                println("[AUTO-STORED] File '${transfer.fileName}' (${transfer.totalBytes} bytes) received successfully.")
            }
        }
    }

    println("Daemon is running on LAN. Press Ctrl+C to terminate.")
    
    // Add JVM shutdown hook for clean engine shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Stopping daemon...")
        engine.stop()
        daemonScope.cancel()
    })

    // Keep main thread alive
    while (isActive) {
        delay(60_000L)
    }
}
```
