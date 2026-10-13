package com.transfer.flash.server.auth

import com.transfer.flash.core.security.crypto.FlashCrypto
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A key file that cannot be trusted: unreadable, corrupt, mismatching, or unexpectedly present/absent.
 *
 * The hub FAILS CLOSED on this (SEC-04): it never regenerates a key because a file looked wrong, since a silently
 * replaced key invalidates every pin and every certificate it ever signed.
 */
class KeyFileException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Owner-only file helpers (POSIX mode 600, or an owner-only ACL on Windows/NTFS). */
object SecureFiles {

    /** Restricts an existing [path] to its owner. Throws [IOException] when the file system supports neither model. */
    fun restrictToOwner(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            posix.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            return
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw IOException("file system supports neither POSIX permissions nor ACLs: $path")
        acl.acl = privateAcl(Files.getOwner(path), inheritable = false)
    }

    /**
     * Makes [dir] and everything the hub creates in it from now on private to the hub's account (REV-03): POSIX mode 700; on NTFS an
     * inheritable ACL for the owner, SYSTEM and the Administrators group only (SYSTEM and Administrators stay so backup agents and an
     * administrator who has to repair the host keep working; every other user, including `Users` and `Everyone`, loses access).
     * Returns false (and logs nothing) when the file system or the account's rights do not allow it; the caller warns.
     */
    fun restrictDirectory(dir: Path): Boolean = try {
        Files.createDirectories(dir)
        val posix = Files.getFileAttributeView(dir, PosixFileAttributeView::class.java)
        if (posix != null) {
            posix.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            true
        } else {
            val view = Files.getFileAttributeView(dir, AclFileAttributeView::class.java)
            if (view == null) {
                false
            } else {
                view.acl = privateAcl(Files.getOwner(dir), inheritable = true)
                true
            }
        }
    } catch (_: IOException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    /** Owner-only (plus SYSTEM and Administrators on NTFS) for one existing file; false when it could not be done. */
    fun restrictDataFile(path: Path): Boolean = try {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            posix.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            true
        } else {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            if (view == null) {
                false
            } else {
                view.acl = privateAcl(Files.getOwner(path), inheritable = false)
                true
            }
        }
    } catch (_: IOException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    /** True when nobody but the owner (and, on NTFS, SYSTEM and Administrators) is granted access to [path]. */
    fun isPrivateToHubAccount(path: Path): Boolean {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            return posix.readAttributes().permissions().none {
                it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_")
            }
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return false
        val owner = Files.getOwner(path).name.lowercase()
        return acl.acl.none { entry ->
            val name = entry.principal().name.lowercase()
            entry.type() == AclEntryType.ALLOW && name != owner && !name.endsWith("\\system") && !name.endsWith("\\administrators") &&
                name != "system" && name != "administrators"
        }
    }

    private val WINDOWS_ADMIN_PRINCIPALS = listOf("NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators")

    private fun privateAcl(owner: java.nio.file.attribute.UserPrincipal, inheritable: Boolean): List<AclEntry> {
        val lookup = java.nio.file.FileSystems.getDefault().userPrincipalLookupService
        val principals = buildList {
            add(owner)
            for (name in WINDOWS_ADMIN_PRINCIPALS) runCatching { lookup.lookupPrincipalByName(name) }.getOrNull()?.let { add(it) }
        }
        val flags = if (inheritable) setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) else emptySet()
        return principals.map {
            AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(it).setFlags(*flags.toTypedArray())
                .setPermissions(AclEntryPermission.entries.toSet()).build()
        }
    }

    /** True when only the owner can read [path]. */
    fun isOwnerOnly(path: Path): Boolean {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            val perms = posix.readAttributes().permissions()
            return perms.none {
                it == PosixFilePermission.GROUP_READ || it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.GROUP_EXECUTE ||
                    it == PosixFilePermission.OTHERS_READ || it == PosixFilePermission.OTHERS_WRITE || it == PosixFilePermission.OTHERS_EXECUTE
            }
        }
        return isPrivateToHubAccount(path)
    }

    /**
     * Writes [bytes] to [path] atomically: a sibling temp file is created owner-only BEFORE any content is written,
     * flushed, then moved over [path]. A reader never sees a half-written key, and the content is never world-readable.
     */
    fun writeOwnerOnly(path: Path, bytes: ByteArray) {
        val dir = path.toAbsolutePath().parent ?: throw IOException("no parent directory for $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, path.fileName.toString(), ".tmp")
        try {
            restrictToOwner(tmp)
            Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                out.write(bytes)
                out.flush()
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictToOwner(path)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Copies [path] to `<name>.bak-<millis>` (owner-only) and returns the backup, or null when [path] does not exist. */
    fun backup(path: Path, nowMs: Long = System.currentTimeMillis()): Path? {
        if (!Files.exists(path)) return null
        val bak = path.resolveSibling("${path.fileName}.bak-$nowMs")
        writeOwnerOnly(bak, Files.readAllBytes(path))
        return bak
    }
}

/** PKCS#8 / SPKI key files, written and loaded fail-closed. */
object KeyFiles {
    private const val EC = "EC"

    fun writePair(privFile: Path, pubFile: Path, pair: KeyPair) {
        // Back up whatever is being replaced BEFORE the first write (a rotate must never destroy the only copy).
        SecureFiles.backup(privFile)
        SecureFiles.backup(pubFile)
        SecureFiles.writeOwnerOnly(privFile, Base64.getEncoder().encode(pair.private.encoded))
        SecureFiles.writeOwnerOnly(pubFile, Base64.getEncoder().encode(pair.public.encoded))
    }

    /**
     * Loads a key pair. Returns null ONLY when both files are absent. Any other situation that is not a clean,
     * matching pair throws [KeyFileException].
     */
    fun loadPair(privFile: Path, pubFile: Path): KeyPair? {
        val hasPriv = Files.exists(privFile)
        val hasPub = Files.exists(pubFile)
        if (!hasPriv && !hasPub) return null
        if (!hasPriv || !hasPub) {
            throw KeyFileException(
                "key pair is incomplete (${privFile.fileName}: $hasPriv, ${pubFile.fileName}: $hasPub); " +
                    "restore it from backup, the hub will not generate a replacement",
            )
        }
        val pair = try {
            val kf = KeyFactory.getInstance(EC)
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privFile).trim())))
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pubFile).trim())))
            KeyPair(pub, priv)
        } catch (e: Exception) {
            throw KeyFileException(
                "key file ${privFile.fileName} / ${pubFile.fileName} is unreadable or corrupt; the hub will not generate a replacement",
                e,
            )
        }
        if (!matches(pair)) {
            throw KeyFileException("public key ${pubFile.fileName} does not belong to private key ${privFile.fileName}")
        }
        return pair
    }

    /** True when [pair]'s halves belong together (sign with the private key, verify with the public key). */
    fun matches(pair: KeyPair): Boolean {
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val sig = try {
            java.security.Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM).run {
                initSign(pair.private)
                update(probe)
                sign()
            }
        } catch (_: Exception) {
            return false
        }
        return FlashCrypto.verifySignature(pair.public.encoded, probe, sig)
    }
}

/**
 * Pass-phrase protection for the OFFLINE root key (SEC-04): PBKDF2-HMAC-SHA256 (>= 600 000 iterations) derives an
 * AES-256-GCM key. The envelope is plain text so a human can see what a file is: `FLASH-ROOT-KEY-V1` and four lines.
 */
object RootKeyFile {
    const val MIN_ITERATIONS: Int = 600_000
    private const val MAGIC = "FLASH-ROOT-KEY-V1"
    private val random = SecureRandom()

    fun encrypt(pkcs8: ByteArray, passphrase: CharArray, iterations: Int = MIN_ITERATIONS): ByteArray {
        require(iterations >= MIN_ITERATIONS) { "at least $MIN_ITERATIONS PBKDF2 iterations are required" }
        require(passphrase.size >= 8) { "the root key pass-phrase must be at least 8 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = derive(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC.encodeToByteArray())
        val ct = cipher.doFinal(pkcs8)
        val b64 = Base64.getEncoder()
        return "$MAGIC\n$iterations\n${b64.encodeToString(salt)}\n${b64.encodeToString(nonce)}\n${b64.encodeToString(ct)}\n".encodeToByteArray()
    }

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.decodeToString().startsWith(MAGIC)

    /** Decrypts an envelope; a wrong pass-phrase or a tampered file throws [KeyFileException]. */
    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        val lines = envelope.decodeToString().trim().lines()
        if (lines.size != 5 || lines[0] != MAGIC) throw KeyFileException("not a Flash encrypted root key file")
        return try {
            val iterations = lines[1].toInt()
            if (iterations < MIN_ITERATIONS) throw KeyFileException("encrypted root key uses a weak KDF ($iterations iterations)")
            val b64 = Base64.getDecoder()
            val key = derive(passphrase, b64.decode(lines[2]), iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64.decode(lines[3])))
            cipher.updateAAD(MAGIC.encodeToByteArray())
            cipher.doFinal(b64.decode(lines[4]))
        } catch (e: KeyFileException) {
            throw e
        } catch (e: Exception) {
            throw KeyFileException("wrong pass-phrase or corrupt encrypted root key", e)
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, iterations, 256)).encoded
}
