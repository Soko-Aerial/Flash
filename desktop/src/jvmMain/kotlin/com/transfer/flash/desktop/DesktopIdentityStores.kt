@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceNames
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.identity.FlashIdentity
import com.transfer.flash.core.security.identity.FlashIdentityStore
import com.transfer.flash.core.security.identity.IdentityKeyVault
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.security.trust.VouchRules
import com.transfer.flash.core.security.trust.VouchVerdict
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
 * State layout: `~/.flash/identity.properties` — `deviceId`, `friendlyName`, `nameVersion` (ERROR-077).
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
            var changed = false
            var id = props.getProperty("deviceId")
            if (id == null) {
                id = UUID.randomUUID().toString()
                props.setProperty("deviceId", id)
                changed = true
            }
            // Every PC used to be "Flash Desktop" (ERROR-077). A name still at that old default, or missing, gets
            // this id's animal/fruit name once; a name the owner chose is kept, even if it is "Flash Desktop".
            if (props.getProperty(KEY_NAME_VERSION) != NAME_VERSION) {
                val stored = props.getProperty("friendlyName")
                if (stored.isNullOrBlank() || stored == LEGACY_DEFAULT_NAME) {
                    props.setProperty("friendlyName", FlashDeviceNames.forDeviceId(id))
                }
                props.setProperty(KEY_NAME_VERSION, NAME_VERSION)
                changed = true
            }
            if (changed) save(props)
            val name = props.getProperty("friendlyName")?.takeIf { it.isNotBlank() }
                ?: FlashDeviceNames.forDeviceId(id)
            return FlashIdentity(deviceId = FlashDeviceId(id), friendlyName = name)
        }
    }

    override fun updateFriendlyName(name: String): FlashResult<Unit> {
        val outcome = runCatching {
            synchronized(lock) {
                val props = load()
                props.setProperty("friendlyName", name)
                props.setProperty(KEY_NAME_VERSION, NAME_VERSION)
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

    private companion object {
        /** Set once the default-name migration has run, so it runs once per install. */
        const val KEY_NAME_VERSION = "nameVersion"
        const val NAME_VERSION = "2"
        const val LEGACY_DEFAULT_NAME = "Flash Desktop"
    }
}

/**
 * File-backed trust store for the desktop shell — the shipped re-homing of the Phase 16
 * harness's `DesktopTrustStore`. Stands in for `androidMain`'s
 * `AndroidPreferencesTrustStore`: trust is a keyed set that can be revoked and re-listed,
 * persisted under `~/.flash/trust.properties` as `trusted.<deviceId> = friendlyName`.
 */
internal class DesktopTrustStore(
    private val stateDir: File,
    /**
     * Seals session keys at rest (audit S4): Windows DPAPI in production, the same vault that protects
     * the desktop identity key (ADR-035). Keys used to sit in `trust.properties` as plain Base64.
     */
    private val vault: IdentityKeyVault = DesktopVaults.forCurrentOs(stateDir),
) : FlashTrustStore {

    private val file = File(stateDir, "trust.properties")
    private val lock = Any()
    private val cache = ConcurrentHashMap<FlashDeviceId, String>()
    private val sessionKeys = ConcurrentHashMap<FlashDeviceId, ByteArray>()

    /** What [persist] writes for each session key: only ever a sealed value, never the raw key. */
    private val sealedSessionKeys = ConcurrentHashMap<FlashDeviceId, String>()
    private val pins = ConcurrentHashMap<FlashDeviceId, String>()

    /** Groups whose owner vouched a device's pin (ADR-044 V2); guarded by [lock] for writes. */
    private val vouches = ConcurrentHashMap<FlashDeviceId, Set<String>>()

    /** Peers whose current pairing was made with protocol v2 (ADR-042). */
    private val verified = ConcurrentHashMap.newKeySet<FlashDeviceId>()

    init {
        stateDir.mkdirs()
        if (file.isFile) {
            runCatching {
                val props = Properties()
                file.inputStream().use { input: InputStream -> props.load(input) }
                props.stringPropertyNames()
                    .filter { it.startsWith("trusted.") }
                    .forEach { key ->
                        cache[FlashDeviceId(key.removePrefix("trusted."))] =
                            props.getProperty(key).orEmpty()
                    }
                var sawLegacy = false
                props.stringPropertyNames()
                    .filter { it.startsWith("session_key.") }
                    .forEach { key ->
                        val id = FlashDeviceId(key.removePrefix("session_key."))
                        val stored = props.getProperty(key).orEmpty()
                        if (stored.startsWith(SEALED_PREFIX)) {
                            // Unopenable (another Windows user, a copied profile): dropped, re-pair.
                            runCatching {
                                vault.unprotect(Base64.decode(stored.removePrefix(SEALED_PREFIX)))
                            }.getOrNull()?.let { bytes ->
                                sessionKeys[id] = bytes
                                sealedSessionKeys[id] = stored
                            }
                        } else {
                            runCatching { Base64.decode(stored) }.getOrNull()?.let { bytes ->
                                sessionKeys[id] = bytes
                                seal(bytes)?.let { sealedSessionKeys[id] = it }
                                sawLegacy = true
                            }
                        }
                    }
                props.stringPropertyNames()
                    .filter { it.startsWith("pin.") }
                    .forEach { key ->
                        val id = FlashDeviceId(key.removePrefix("pin."))
                        props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { pinHex ->
                            pins[id] = pinHex.uppercase()
                        }
                    }
                props.stringPropertyNames()
                    .filter { it.startsWith("vouch.") }
                    .forEach { key ->
                        val groups = props.getProperty(key).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                        if (groups.isNotEmpty()) vouches[FlashDeviceId(key.removePrefix("vouch."))] = groups
                    }
                props.stringPropertyNames()
                    .filter { it.startsWith("verified.") && props.getProperty(it) == "true" }
                    .forEach { verified += FlashDeviceId(it.removePrefix("verified.")) }
                // Rewrite once so legacy plaintext keys leave the disk immediately.
                if (sawLegacy) runCatching { persist() }
            }
        }
    }

    /** Sealed file form of [key], or null when the vault cannot seal (then the key is not persisted). */
    private fun seal(key: ByteArray): String? = runCatching {
        SEALED_PREFIX + Base64.encode(vault.protect(key))
    }.onFailure {
        FlashLog.w("SECURITY", "Could not seal a session key; it will not be persisted: ${it.message}")
    }.getOrNull()

    private fun persist() {
        val props = Properties()
        cache.forEach { (id, name) -> props.setProperty("trusted.${id.value}", name) }
        sealedSessionKeys.forEach { (id, sealed) -> props.setProperty("session_key.${id.value}", sealed) }
        pins.forEach { (id, pin) -> props.setProperty("pin.${id.value}", pin) }
        vouches.forEach { (id, groups) -> props.setProperty("vouch.${id.value}", groups.joinToString(",")) }
        verified.forEach { id -> props.setProperty("verified.${id.value}", "true") }
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

    override fun saveSessionKey(deviceId: FlashDeviceId, key: ByteArray): FlashResult<Unit> {
        synchronized(lock) {
            // Usable for this run either way; persisted only in sealed form.
            sessionKeys[deviceId] = key
            val sealed = seal(key)
            if (sealed != null) sealedSessionKeys[deviceId] = sealed else sealedSessionKeys.remove(deviceId)
            runCatching { persist() }
        }
        return FlashResult.Success(Unit)
    }

    override fun getSessionKey(deviceId: FlashDeviceId): ByteArray? = sessionKeys[deviceId]

    override fun savePin(deviceId: FlashDeviceId, fingerprintHex: String): FlashResult<Unit> {
        synchronized(lock) {
            pins[deviceId] = fingerprintHex.uppercase()
            runCatching { persist() }
        }
        return FlashResult.Success(Unit)
    }

    override fun getPin(deviceId: FlashDeviceId): String? = pins[deviceId]

    override fun markVerified(deviceId: FlashDeviceId): FlashResult<Unit> {
        synchronized(lock) {
            verified += deviceId
            runCatching { persist() }
        }
        return FlashResult.Success(Unit)
    }

    override fun isVerified(deviceId: FlashDeviceId): Boolean = deviceId in verified

    override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
        synchronized(lock) {
            cache.remove(deviceId)
            sessionKeys.remove(deviceId)
            sealedSessionKeys.remove(deviceId)
            verified.remove(deviceId)
            // Unpairing must not strand a member of a group behind a first-use pin: while a group still
            // vouches for this key, the pin stays (its source becomes VOUCHED).
            if (vouches[deviceId].isNullOrEmpty()) pins.remove(deviceId)
            runCatching { persist() }
        }
        return FlashResult.Success(Unit)
    }

    override fun vouchingGroups(deviceId: FlashDeviceId): Set<String> = vouches[deviceId].orEmpty()

    override fun applyVouch(deviceId: FlashDeviceId, fingerprintHex: String, groupId: String): VouchVerdict {
        synchronized(lock) {
            val verdict = vouchVerdict(deviceId, fingerprintHex, groupId)
            if (verdict != VouchVerdict.ACCEPT) return verdict
            val fingerprint = VouchRules.normalize(fingerprintHex)
            // A different key replaces the old pin and with it the groups that vouched the old key (`decide` only
            // allows that when they are exactly this group or none). A paired device only ever gets here with its
            // own key (or with no stored pin), so its pairing is never overwritten.
            val kept = if (pins[deviceId]?.let(VouchRules::normalize) == fingerprint) vouches[deviceId].orEmpty() else emptySet()
            pins[deviceId] = fingerprint
            vouches[deviceId] = kept + groupId
            runCatching { persist() }
            return verdict
        }
    }

    override fun revokeVouch(deviceId: FlashDeviceId, groupId: String) {
        synchronized(lock) {
            val remaining = VouchRules.afterRevoke(vouches[deviceId].orEmpty(), groupId) ?: return
            if (remaining.isEmpty()) {
                vouches.remove(deviceId)
                if (!isTrusted(deviceId)) pins.remove(deviceId)
            } else {
                vouches[deviceId] = remaining
            }
            runCatching { persist() }
        }
    }

    override fun getTrustedPeers(): Map<FlashDeviceId, String> = cache.toMap()

    private companion object {
        /** Marks a DPAPI-sealed session key; anything without it is a legacy plaintext entry. */
        const val SEALED_PREFIX = "s1:"
    }
}
