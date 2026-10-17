package com.transfer.flash.core.radio.driver

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Desktop properties-file-backed persistence for [RadioStatePersistence].
 *
 * Saves counter high-water marks and replay windows to `radio_state.properties` in [stateDir].
 * Writes atomically via temp file and atomic move.
 * Zero private keys, session keys, or plaintext data are ever persisted.
 */
public class DesktopFileRadioStatePersistence(
    private val stateDir: File = File(System.getProperty("user.home"), ".flash"),
) : RadioStatePersistence {

    private val file = File(stateDir, "radio_state.properties")

    init {
        runCatching { stateDir.mkdirs() }
    }

    override fun readAll(): Map<String, String> {
        val props = Properties()
        runCatching {
            if (file.isFile) {
                file.inputStream().use { input -> props.load(input) }
            }
        }
        val map = HashMap<String, String>(props.size)
        for (name in props.stringPropertyNames()) {
            map[name] = props.getProperty(name)
        }
        return map
    }

    override fun writeAll(entries: Map<String, String>) {
        runCatching {
            stateDir.mkdirs()
            val tmpFile = File(stateDir, "radio_state.properties.tmp")
            val props = Properties()
            for ((k, v) in entries) {
                props.setProperty(k, v)
            }
            tmpFile.outputStream().use { out ->
                props.store(out, "Flash Radio State (counters & replay windows)")
            }
            val targetPath = file.toPath()
            val tmpPath = tmpFile.toPath()
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
