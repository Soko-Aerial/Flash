package com.transfer.flash.core.security.identity

import com.sun.jna.platform.win32.Crypt32Util
import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.File

/**
 * The at-rest protection seam for a persisted software identity key — Phase 26 / ADR-035.
 *
 * Desktop JVMs have no hardware keystore; the identity keypair therefore lives in a file and
 * this seam is what stands between the file's bytes and an attacker who can read files. The
 * production JVM implementation is **Windows DPAPI** (`CryptProtectData`): the key blob is
 * encrypted to the current Windows user's login, so a copy stolen from the disk is garbage
 * without the user's session. macOS Keychain / Linux keyring are future instances of this
 * same two-method shape (the `1-byte format-version` header in `PersistedFlashCrypto` is the
 * migration contract for exactly that day).
 *
 * Security tier, stated plainly (ADR-035): DPAPI-protected software identity is strictly
 * better than restart-amnesia and strictly weaker than Android's non-exportable hardware key —
 * code running as the same Windows user CAN unprotect the blob.
 *
 * `public` + [FlashInternalApi] (the UuidIdGenerator precedent): `:desktop` constructs the
 * engine's crypto and passes the vault as its constructor parameter — but this is not a
 * public API invitation.
 */
@FlashInternalApi
public class IdentityKeyVault(
    private val protectFn: (ByteArray) -> ByteArray,
    private val unprotectFn: (ByteArray) -> ByteArray,
) {

    /** Protects [plain] at rest. MUST authenticate (tampered input must fail loudly on read). */
    public fun protect(plain: ByteArray): ByteArray = protectFn(plain)

    /**
     * Reverses [protect]. MUST throw (never return garbage) when the blob was not produced by
     * [protect] under the same user/secret — the caller's fallback path depends on the throw.
     */
    public fun unprotect(blob: ByteArray): ByteArray = unprotectFn(blob)

    public companion object {
        /** The production JVM vault. Kept as a val so call sites read as intent, not mechanics. */
        @FlashInternalApi
        public val Dpapi: IdentityKeyVault = IdentityKeyVault(
            protectFn = { plain -> Crypt32Util.cryptProtectData(plain) },
            unprotectFn = { blob -> Crypt32Util.cryptUnprotectData(blob) },
        )

        /**
         * A vault sealed under a random master key kept in [keyFile] (owner-only on POSIX). The Linux and macOS
         * fallback until their keyring providers exist; a weaker tier than DPAPI, see [KeyFileVault].
         */
        @FlashInternalApi
        public fun forKeyFile(keyFile: File): IdentityKeyVault = sealedBy(listOf(FileMasterKey(keyFile)))

        /**
         * Prefers the OS [keyring] for the master key and falls back to the owner-only [keyFile] when the keyring
         * cannot be reached (no session bus, locked and dismissed, headless). Each blob remembers which one sealed it.
         */
        @FlashInternalApi
        public fun withKeyring(keyring: SecretKeyStore, keyFile: File): IdentityKeyVault =
            sealedBy(listOf(KeyringMasterKey(keyring), FileMasterKey(keyFile)))

        private fun sealedBy(sources: List<MasterKeySource>): IdentityKeyVault {
            val vault = SealedVault(sources)
            return IdentityKeyVault(protectFn = vault::protect, unprotectFn = vault::unprotect)
        }

        /**
         * The vault for the machine this is running on: Windows DPAPI on Windows, otherwise the key-file vault under
         * `<stateDir>/identity/vault.key`. DPAPI is never touched off Windows, because `Crypt32Util` cannot load
         * there and the first-run identity write used to crash the app (Linux plan C4).
         */
        @FlashInternalApi
        public fun defaultForCurrentOs(
            stateDir: File,
            osName: String = System.getProperty("os.name", ""),
        ): IdentityKeyVault =
            if (osName.startsWith("Windows", ignoreCase = true)) {
                Dpapi
            } else {
                forKeyFile(File(File(stateDir, "identity"), "vault.key"))
            }

        /**
         * Test vault: identity transform. Tests use it to exercise `PersistedFlashCrypto`'s
         * file/format logic on any OS without asserting anything about OS-level protection.
         * Never wire it into a shipped engine.
         */
        @FlashInternalApi
        public val PassThrough: IdentityKeyVault = IdentityKeyVault(
            protectFn = { it },
            unprotectFn = { it },
        )
    }
}
