@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.identity

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Somewhere the desktop OS keeps one small secret for the user: the Secret Service keyring on Linux
 * (`org.freedesktop.secrets`, implemented in `:desktop` over D-Bus). [SealedVault] keeps its random 256-bit master
 * key here when it can, so a copy of the app's data folder is not enough to open the identity.
 *
 * Implementations must throw when the store is unreachable (no session bus, locked and dismissed, no service). That
 * throw is what moves the vault on to the next tier.
 */
@FlashInternalApi
public interface SecretKeyStore {
    /** The stored key, or `null` when nothing has been stored yet. Throws when the store cannot be reached. */
    public fun lookup(): ByteArray?

    /** Stores [key], replacing any earlier value. Throws when the store cannot be reached. */
    public fun store(key: ByteArray)
}

/** Where a master key lives. [tag] is written in front of every sealed blob so it can be opened from the right place. */
internal interface MasterKeySource {
    val tag: Byte

    /** The existing key, or `null` if none was created yet. May throw if the source is unavailable. */
    fun load(): ByteArray?

    /** The existing key, or a new random one stored first. May throw if the source is unavailable. */
    fun loadOrCreate(): ByteArray
}

/** A master key in an owner-only file (`0600` on POSIX). Tag [TAG]. */
internal class FileMasterKey(private val keyFile: File) : MasterKeySource {
    override val tag: Byte = TAG
    private val lock = Any()
    private val random = SecureRandom()

    override fun load(): ByteArray? {
        if (!keyFile.isFile) return null
        val bytes = keyFile.readBytes()
        require(bytes.size == SealedVault.KEY_BYTES) { "identity master key file has the wrong size (${bytes.size})" }
        return bytes
    }

    override fun loadOrCreate(): ByteArray = synchronized(lock) {
        load()?.let { return it }
        val key = ByteArray(SealedVault.KEY_BYTES).also { random.nextBytes(it) }
        keyFile.parentFile?.mkdirs()
        val path = keyFile.toPath()
        // Written complete to a private temp file, then moved into place: a crash can never leave an empty or
        // half-written master key behind, and the move fails if another process already created the key.
        val tmp = path.resolveSibling(keyFile.name + "." + java.util.UUID.randomUUID() + ".tmp")
        try {
            createOwnerOnly(tmp)
            Files.write(tmp, key)
            Files.move(tmp, path)
        } catch (_: FileAlreadyExistsException) {
            Files.deleteIfExists(tmp)
            // A concurrent process won the race: use its key.
            return load() ?: error("identity master key file vanished: ${keyFile.path}")
        } catch (failure: Exception) {
            Files.deleteIfExists(tmp)
            throw failure
        }
        key
    }

    /** Creates [path] with owner-only permissions in one step where the file system supports POSIX modes. */
    private fun createOwnerOnly(path: java.nio.file.Path) {
        try {
            Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
            )
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows runs DPAPI, so this only happens in tests): plain create.
            Files.createFile(path)
        }
    }

    companion object {
        const val TAG: Byte = 0x01
    }
}

/** A master key held in the OS keyring through a [SecretKeyStore]. Tag [TAG]. */
internal class KeyringMasterKey(private val store: SecretKeyStore) : MasterKeySource {
    override val tag: Byte = TAG
    private val lock = Any()
    private val random = SecureRandom()

    override fun load(): ByteArray? = store.lookup()?.also {
        require(it.size == SealedVault.KEY_BYTES) { "keyring master key has the wrong size (${it.size})" }
    }

    override fun loadOrCreate(): ByteArray = synchronized(lock) {
        load()?.let { return it }
        val key = ByteArray(SealedVault.KEY_BYTES).also { random.nextBytes(it) }
        store.store(key)
        // Read back what the keyring really holds: a store that silently kept an older value must not let us seal
        // under a key that the next start cannot find.
        load() ?: error("keyring did not keep the master key")
    }

    companion object {
        const val TAG: Byte = 0x02
    }
}

/**
 * Seals identity bytes with AES-256-GCM under a random master key that lives in the first usable [MasterKeySource].
 *
 * Blob layout: `tag(1) || nonce(12) || ciphertext+tag`. The tag names the source that holds the key, so a blob is
 * always opened from the place it was sealed for, even after the machine gains or loses a keyring. A tampered blob
 * fails GCM authentication and throws, as the [IdentityKeyVault] contract requires.
 *
 * **Security tier, stated plainly (Linux plan C7):**
 * - the file source is obfuscation plus file permissions, not a keystore: code running as the same user can read both
 *   files, and a disk copy that includes both is not protected. It beats a lone copy of the blob.
 * - the keyring source keeps the key out of the data folder; code running as the same user in the same login session can
 *   still ask the keyring for it. Windows keeps DPAPI.
 */
internal class SealedVault(private val sources: List<MasterKeySource>) {
    private val random = SecureRandom()

    init {
        require(sources.isNotEmpty()) { "a vault needs at least one master key source" }
        require(sources.map { it.tag }.toSet().size == sources.size) { "master key source tags must be unique" }
    }

    fun protect(plain: ByteArray): ByteArray {
        var lastFailure: Exception? = null
        for (source in sources) {
            val key = try {
                source.loadOrCreate()
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            try {
                val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                return byteArrayOf(source.tag) + nonce + cipher.doFinal(plain)
            } finally {
                key.fill(0)
            }
        }
        throw IllegalStateException("no master key source is usable", lastFailure)
    }

    fun unprotect(blob: ByteArray): ByteArray {
        require(blob.size > 1 + NONCE_BYTES + TAG_BITS / 8) { "sealed identity blob too short" }
        val source = sources.firstOrNull { it.tag == blob[0] }
            ?: error("identity blob was sealed by an unavailable key source (tag ${blob[0]})")
        // Never create a key here: a missing key means the blob can no longer be opened, and silently minting a new
        // key would only fail later with a less useful message.
        val key = source.load() ?: error("identity master key is missing (source tag ${source.tag})")
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + NONCE_BYTES)),
            )
            return cipher.doFinal(blob, 1 + NONCE_BYTES, blob.size - 1 - NONCE_BYTES)
        } finally {
            key.fill(0)
        }
    }

    internal companion object {
        const val KEY_BYTES: Int = 32
        const val NONCE_BYTES: Int = 12
        const val TAG_BITS: Int = 128
        const val TRANSFORM: String = "AES/GCM/NoPadding"
    }
}
