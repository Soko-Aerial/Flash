package com.transfer.flash.core.radio.connect

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Desktop properties-file-backed persistence for remembered radio and authorization confirmation.
 * Follows the pattern of DesktopSettingsStore / DesktopIdentityStores.
 *
 * Safety contract:
 * - Writes are atomic (temp file + atomic move).
 * - Corrupted, partial or unreadable files yield null / defaults; NEVER crash.
 * - Stores Bluetooth MAC address and display name only; NEVER private or session keys.
 */
public class DesktopPropertiesRadioStore(
    private val stateDir: File = File(System.getProperty("user.home"), ".flash"),
) {
    private val file = File(stateDir, "radio.properties")
    private val lock = Any()

    private val KEY_BT_ADDRESS = "remembered.bluetooth_address"
    private val KEY_DISPLAY_NAME = "remembered.display_name"
    private val KEY_LAST_PORT = "remembered.last_known_port"
    private val KEY_AUTH_EPOCH_MS = "authorisation.confirmed_at_epoch_ms"
    private val KEY_AUTH_CALLSIGN = "authorisation.operator_callsign"

    init {
        runCatching { stateDir.mkdirs() }
    }

    private fun loadProperties(): Properties {
        val props = Properties()
        runCatching {
            if (file.isFile) {
                file.inputStream().use { input -> props.load(input) }
            }
        }
        return props
    }

    private fun saveProperties(props: Properties) {
        runCatching {
            stateDir.mkdirs()
            val tmpFile = File(stateDir, "radio.properties.tmp")
            tmpFile.outputStream().use { out ->
                props.store(out, "Flash Radio Gateway configuration")
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

    public val rememberedStore: RememberedRadioStore = object : RememberedRadioStore {
        override suspend fun load(): RememberedRadio? = synchronized(lock) {
            val props = loadProperties()
            val addr = props.getProperty(KEY_BT_ADDRESS)?.takeIf { it.isNotBlank() } ?: return null
            val name = props.getProperty(KEY_DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: return null
            val port = props.getProperty(KEY_LAST_PORT)?.takeIf { it.isNotBlank() }
            RememberedRadio(
                bluetoothAddress = addr,
                displayName = name,
                lastKnownPortName = port,
            )
        }

        override suspend fun save(radio: RememberedRadio): Unit = synchronized(lock) {
            val props = loadProperties()
            props.setProperty(KEY_BT_ADDRESS, radio.bluetoothAddress)
            props.setProperty(KEY_DISPLAY_NAME, radio.displayName)
            if (radio.lastKnownPortName != null) {
                props.setProperty(KEY_LAST_PORT, radio.lastKnownPortName)
            } else {
                props.remove(KEY_LAST_PORT)
            }
            saveProperties(props)
        }

        override suspend fun clear(): Unit = synchronized(lock) {
            val props = loadProperties()
            props.remove(KEY_BT_ADDRESS)
            props.remove(KEY_DISPLAY_NAME)
            props.remove(KEY_LAST_PORT)
            saveProperties(props)
        }
    }

    public val authorisationStore: RadioAuthorisationStore = object : RadioAuthorisationStore {
        override suspend fun load(): AuthorisationConfirmation? = synchronized(lock) {
            val props = loadProperties()
            val epochStr = props.getProperty(KEY_AUTH_EPOCH_MS) ?: return null
            val epochMs = epochStr.toLongOrNull() ?: return null
            if (epochMs <= 0L) return null
            val callsign = props.getProperty(KEY_AUTH_CALLSIGN)?.takeIf { it.isNotBlank() }
            AuthorisationConfirmation(
                confirmedAtEpochMs = epochMs,
                operatorCallsign = callsign,
            )
        }

        override suspend fun save(confirmation: AuthorisationConfirmation): Unit = synchronized(lock) {
            val props = loadProperties()
            props.setProperty(KEY_AUTH_EPOCH_MS, confirmation.confirmedAtEpochMs.toString())
            if (confirmation.operatorCallsign != null) {
                props.setProperty(KEY_AUTH_CALLSIGN, confirmation.operatorCallsign)
            } else {
                props.remove(KEY_AUTH_CALLSIGN)
            }
            saveProperties(props)
        }

        override suspend fun clear(): Unit = synchronized(lock) {
            val props = loadProperties()
            props.remove(KEY_AUTH_EPOCH_MS)
            props.remove(KEY_AUTH_CALLSIGN)
            saveProperties(props)
        }
    }
}
