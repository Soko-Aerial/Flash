# Practical Examples: Headless Daemon & Background Service

This example shows how a JVM host with its own Flash engine factory can observe completed incoming files in a background process.

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

suspend fun runReceiver(receiveDir: File) {
    check(receiveDir.isDirectory || receiveDir.mkdirs())
    println("Destination directory: ${receiveDir.absolutePath}")

    val engine = FlashDesktop.create(
        FlashConfig(
            displayName = "StorageNode-01",
            receivedFilesPath = receiveDir.absolutePath,
            autoAcceptIncoming = true,      // paired peers only
        )
    )
    try {
        engine.awaitReady()
        val seen = mutableSetOf<String>()
        engine.transfers.activeTransfers.collect { rows ->
            rows.filter { it.direction == FlashTransferDirection.Receiving && it.state == FlashTransferState.Completed }
                .filter { seen.add(it.id.value) }
                .forEach { println("[STORED] '${it.fileName}' (${it.bytesTotal} bytes)") }
        }
    } finally {
        engine.close()
    }
}
```

State lives under the per-user state directory (managed by internal `DesktopPaths.stateDir()`, e.g., `~/.flash` on Windows/macOS or `$XDG_DATA_HOME/flash` / `~/.local/share/flash` on Linux); on Windows the identity key is sealed with DPAPI, so a service must run as the same user that paired. Persistent transfer recovery (`enableResume = true`, the default) is supported on Desktop via encrypted Room transfer rows and validated LAN checkpoints (ADR-362). Linux and headless use are not device-verified (`LNX-*` in `docs/testing/TEST-BACKLOG.md`).
