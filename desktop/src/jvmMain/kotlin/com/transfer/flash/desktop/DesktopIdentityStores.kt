package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.identity.FlashIdentity
import com.transfer.flash.core.security.identity.FlashIdentityStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * File-backed identity store for the desktop shell (Phase 21, sub-step 21-2) — the shipped
 * re-homing of the Phase 16 harness's `DesktopIdentityStore`, which lived in `jvmTest` and was
 * never published.
 *
 * Stands in for `androidMain`'s `AndroidPreferencesIdentityStore` (SharedPreferences-backed,
 * Android-only). The wire behaviour pairing and discovery depend on — a stable per-install
 * device id + editable friendly name — is a property of the *contract*, not the backing, so a
 * `Properties` file under `~/.flash/` is a faithful equivalent: identity survives restart.
 *
 * State layout: `~/.flash/identity.properties` — `deviceId`, `friendlyName`.
 */
internal class DesktopIdentityStore(private val stateDir: File) : FlashIdentityStore {

    private val file = File(stateDir, "identity.properties")
    private val lock = Any()

    init {
        stateDir.mkdirs()
    }

    private fun load(): Properties {
        val props = Properties()
        if (file.isFile) {
            file.inputStream().use { input: InputStream -> props.load(input) }
        }
        return props
    }

    private fun save(props: Properties) {
        file.outputStream().use { output: OutputStream -> props.store(output, "Flash desktop identity") }
    }

    override fun getIdentity(): FlashIdentity {
        synchronized(lock) {
            val props = load()
            var id = props.getProperty("deviceId")
            if (id == null) {
                id = UUID.randomUUID().toString()
                props.setProperty("deviceId", id)
                props.setProperty("friendlyName", "Flash Desktop")
                save(props)
            }
            val name = props.getProperty("friendlyName")?.takeIf { it.isNotBlank() } ?: "Flash Desktop"
            return FlashIdentity(deviceId = FlashDeviceId(id), friendlyName = name)
        }
    }

    override fun updateFriendlyName(name: String): FlashResult<Unit> {
        val outcome = runCatching {
            synchronized(lock) {
                val props = load()
                props.setProperty("friendlyName", name)
                save(props)
            }
        }
        return if (outcome.isSuccess) {
            FlashResult.Success(Unit)
        } else {
            FlashResult.Failure(
                com.transfer.flash.core.common.result.FlashError.StorageError(
                    "identity store write failed",
                    outcome.exceptionOrNull(),
                ),
            )
        }
    }
}

/**
 * File-backed trust store for the desktop shell — the shipped re-homing of the Phase 16
 * harness's `DesktopTrustStore`. Stands in for `androidMain`'s
 * `AndroidPreferencesTrustStore`: trust is a keyed set that can be revoked and re-listed,
 * persisted under `~/.flash/trust.properties` as `trusted.<deviceId> = friendlyName`.
 */
internal class DesktopTrustStore(private val stateDir: File) : FlashTrustStore {

    private val file = File(stateDir, "trust.properties")
    private val lock = Any()
    private val cache = ConcurrentHashMap<FlashDeviceId, String>()

    init {
        stateDir.mkdirs()
        if (file.isFile) {
            runCatching {
                val props = Properties()
                file.inputStream().use { input: InputStream -> props.load(input) }
                props.stringPropertyNames()
                    .filter { it.startsWith("trusted.") }
                    .forEach { key ->
                        // The VALUE is the peer's friendly name — `persist()` writes
                        // `trusted.<deviceId> = <name>`. This used to hard-code `""`, so every name
                        // was silently discarded on the way back in: the trust survived a restart and
                        // the name did not, and a paired device came back as an empty labelled row
                        // (a real user hit exactly that, and the row was unrecognisable and
                        // unselectable in any meaningful way). The identity store next door has always
                        // read its property back properly; this one lost the value.
                        cache[FlashDeviceId(key.removePrefix("trusted."))] =
                            props.getProperty(key).orEmpty()
                    }
            }
        }
    }

    private fun persist() {
        val props = Properties()
        cache.forEach { (id, name) -> props.setProperty("trusted.${id.value}", name) }
        file.outputStream().use { output: OutputStream -> props.store(output, "Flash desktop trust") }
    }

    override fun isTrusted(deviceId: FlashDeviceId): Boolean = cache.containsKey(deviceId)

    override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> {
        synchronized(lock) {
            cache[deviceId] = friendlyName
            runCatching { persist() }
        }
        return FlashResult.Success(Unit)
    }

    override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
        synchronized(lock) {
            cache.remove(deviceId)
            runCatching { persist() }
        }
        return FlashResult.Success(Unit)
    }

    override fun getTrustedPeers(): Map<FlashDeviceId, String> = cache.toMap()
}
