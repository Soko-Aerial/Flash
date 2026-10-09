# Practical Examples: Headless Daemon & Background Service

Flash is not tied to a graphical user interface. You can run Flash in headless mode as a Linux systemd daemon, Windows service, or headless container to act as an automated file transfer agent, backup receiver, or relay.

---

## 1. Headless Architecture and what is true today

* There is **no headless entry point or daemon in the repository**; this page is a sketch, not a tested recipe.
* The JVM engine implementation, `com.transfer.flash.desktop.DesktopEngine`, lives in the **desktop application module** (`:desktop`), together with its Compose UI. `FlashDesktop.create(config)` in `core-engine` finds it by name, so a headless process must have that class on its classpath (or register its own factory with `FlashDesktop.registerFactory`). It is not published as a library; whether `DesktopEngine` runs without a window has not been tested.
* The libraries needed are `core-engine` and what it `api`s (`core-network`, `core-transfer`, `core-discovery`, `core-messaging`, ...); no `:ui:*` module.
* **Pairing needs a person.** Pairing confirms a 6-digit code on both devices (ADR-042), and `autoAcceptIncoming` only auto-accepts from *paired* peers; an unpaired peer's offer always waits for the user. A headless node therefore has to be paired once through a UI (or restore an existing identity and trust store) before it can receive unattended.

---

## 2. Headless Daemon Sketch

```kotlin
import com.transfer.flash.core.engine.FlashConfig
import com.transfer.flash.core.engine.FlashDesktop
import com.transfer.flash.core.engine.awaitReady
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferState
import kotlinx.coroutines.*
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val receiveDir = File(args.getOrNull(0) ?: "./received_files").apply { mkdirs() }
    println("Destination directory: ${receiveDir.absolutePath}")

    val engine = FlashDesktop.create(
        FlashConfig(
            displayName = "StorageNode-01",
            receivedFilesPath = receiveDir.absolutePath,
            autoAcceptIncoming = true,      // paired peers only
        )
    )
    engine.awaitReady()                      // throws if the engine could not boot

    val seen = mutableSetOf<String>()
    launch {
        engine.transfers.activeTransfers.collect { rows ->
            rows.filter { it.direction == FlashTransferDirection.Receiving && it.state == FlashTransferState.Completed }
                .filter { seen.add(it.id.value) }
                .forEach { println("[STORED] '${it.fileName}' (${it.bytesTotal} bytes)") }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { engine.close() })   // close() is idempotent
    println("Daemon running. Press Ctrl+C to stop.")
    awaitCancellation()
}
```

State lives under the per-user state directory (`DesktopPaths.stateDir()`); on Windows the identity key is sealed with DPAPI, so a service must run as the same user that paired. Linux and headless use are not device-verified (`LNX-*` in `docs/testing/TEST-BACKLOG.md`).
