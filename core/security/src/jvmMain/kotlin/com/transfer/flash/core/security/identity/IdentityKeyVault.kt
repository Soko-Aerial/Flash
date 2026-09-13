package com.transfer.flash.core.security.identity

import com.sun.jna.platform.win32.Crypt32Util
import com.transfer.flash.core.common.annotation.FlashInternalApi

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
