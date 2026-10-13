package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.crypto.FlashFingerprint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a device keeps its own end-to-end SEALING key pair (ADR-126, CLI-05). One key per device; the PRIVATE half
 * never leaves this store except to be used on this device.
 *
 * Contract for every implementation:
 * - [save] is durable before it returns and throws when it could not store the key (the caller must then NOT publish
 *   the public half: a key that cannot be reopened after a restart would make every message sealed to it unreadable).
 * - [load] returns null when there is no key OR the stored copy cannot be opened (a wiped keystore, a restored backup,
 *   a corrupted file). It never throws and never returns a partial key.
 * - [clear] removes the key and every copy of it.
 *
 * Implementations: `AndroidHubSealingKeyStore` (AES-GCM key in AndroidKeystore wrapping the PKCS#8 bytes), the desktop
 * `FileHubSealingKeyStore` (the identity vault: DPAPI on Windows, the sealed key file elsewhere), and
 * [MemoryHubSealingKeyStore] for tests and previews.
 */
@FlashInternalApi
public interface HubSealingKeyStore {
    public suspend fun load(): HubSealingKeyPair?

    /** Stores [key], replacing any earlier one. Throws when the write fails. */
    public suspend fun save(key: HubSealingKeyPair)

    public suspend fun clear()
}

/** In-memory [HubSealingKeyStore]; [failSaves] simulates a full or broken disk. */
@FlashInternalApi
public class MemoryHubSealingKeyStore(initial: HubSealingKeyPair? = null) : HubSealingKeyStore {
    private val lock = Mutex()
    private var key: HubSealingKeyPair? = initial

    /** When true, [save] throws (tests). */
    public var failSaves: Boolean = false

    override suspend fun load(): HubSealingKeyPair? = lock.withLock { key }

    override suspend fun save(key: HubSealingKeyPair): Unit = lock.withLock {
        check(!failSaves) { "storage unavailable" }
        this.key = key
    }

    override suspend fun clear(): Unit = lock.withLock { key = null }
}

/**
 * The byte form platform stores seal and write: `version(1) || publicLen(2, big endian) || public SPKI || private PKCS#8`.
 * Pure and tested; the platform stores only add the at-rest protection around these bytes.
 */
@FlashInternalApi
public object HubSealingKeyCodec {
    private const val VERSION: Int = 1

    public fun encode(key: HubSealingKeyPair): ByteArray {
        val pub = key.publicKeyEncoded
        val priv = key.exportPrivateKeyPkcs8()
        require(pub.size in 1..0xFFFF) { "public key size out of range" }
        return byteArrayOf(VERSION.toByte(), (pub.size ushr 8).toByte(), pub.size.toByte()) + pub + priv
    }

    /** The key, or null for anything that is not exactly what [encode] writes. Never throws. */
    public fun decode(bytes: ByteArray): HubSealingKeyPair? = runCatching {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != VERSION) return null
        val pubLen = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        if (pubLen == 0 || 3 + pubLen >= bytes.size) return null
        HubSealingKeyPair.restore(bytes.copyOfRange(3, 3 + pubLen), bytes.copyOfRange(3 + pubLen, bytes.size))
    }.getOrNull()
}

/** The short, comparable fingerprint of a sealing public key (SHA-256 of the SPKI, lowercase hex). */
@FlashInternalApi
public object HubSealingFingerprint {
    public fun of(sealingPublicKey: ByteArray): String =
        FlashFingerprint.fingerprint(sealingPublicKey).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** The first 16 hex digits in groups of four, for people to compare out loud ("ab12 cd34 ..."). */
    public fun short(fingerprint: String): String = fingerprint.take(16).chunked(4).joinToString(" ")
}
