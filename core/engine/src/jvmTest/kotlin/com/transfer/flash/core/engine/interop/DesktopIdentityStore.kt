package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.identity.FlashIdentity
import com.transfer.flash.core.security.identity.FlashIdentityStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock

/**
 * Phase 16 harness only — NEVER shipped. File-backed identity/trust stores for the desktop
 * endpoint, standing in for `androidMain`'s `AndroidPreferencesIdentityStore` /
 * `AndroidPreferencesTrustStore` (which are `SharedPreferences`-backed and Android-only).
 *
 * The wire behaviour this harness must prove (G2 SAS parity, G6 TOFU rejection) depends only on
 * the *stores' contract*, not their backing, so a `Properties` file under the harness's state
 * directory is a faithful stand-in: identity survives restart, trust is a keyed set that can be
 * revoked, and both are visible to the pairing flows through the same interfaces the Android
 * app uses.
 *
 * State layout (one directory per endpoint):
 * - `identity.properties` — `deviceId`, `friendlyName`
 * - `trust.properties`    — `trusted.<deviceId> = friendlyName`
 */
internal class DesktopIdentityStore(private val stateDir: java.io.File) : FlashIdentityStore {

    private val file = java.io.File(stateDir, "identity.properties")
    private val lock = Any()

    init {
        stateDir.mkdirs()
    }

    private fun load(): Properties {
        val props = Properties()
        if (file.isFile) {
            file.inputStream().use { input: java.io.InputStream -> props.load(input) }
        }
        return props
    }

    private fun save(props: Properties, comment: String? = null) {
        file.outputStream().use { output: java.io.OutputStream -> props.store(output, comment) }
    }

    override fun getIdentity(): FlashIdentity {
        val props = synchronized(lock) { load() }
        var id = props.getProperty("deviceId")
        if (id == null) {
            id = UUID.randomUUID().toString()
            props.setProperty("deviceId", id)
            props.setProperty("friendlyName", "Flash Desktop")
            synchronized(lock) { save(props, "Phase 16 harness identity") }
        }
        val name = props.getProperty("friendlyName")?.takeIf { it.isNotBlank() } ?: "Flash Desktop"
        return FlashIdentity(deviceId = FlashDeviceId(id), friendlyName = name)
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

internal class DesktopTrustStore(private val stateDir: java.io.File) : FlashTrustStore {

    private val file = java.io.File(stateDir, "trust.properties")
    private val lock = Any()
    private val cache = ConcurrentHashMap<FlashDeviceId, String>()

    init {
        stateDir.mkdirs()
        if (file.isFile) {
            runCatching {
                val props = Properties()
                file.inputStream().use { input: java.io.InputStream -> props.load(input) }
                props.stringPropertyNames()
                    .filter { it.startsWith("trusted.") }
                    .forEach { key -> cache[FlashDeviceId(key.removePrefix("trusted."))] = "" }
            }
        }
    }

    private fun persist() {
        val props = Properties()
        cache.forEach { (id, name) -> props.setProperty("trusted.${id.value}", name) }
        file.outputStream().use { output: java.io.OutputStream -> props.store(output, "Phase 16 harness trust") }
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
