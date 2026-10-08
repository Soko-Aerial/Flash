@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.security.identity.IdentityKeyVault
import com.transfer.flash.core.security.identity.SecretKeyStore
import com.transfer.flash.desktop.linux.DbusSecretKeyStore
import java.io.File

/**
 * Picks the at-rest vault for the identity key and the session keys (ADR-035, ADR-092).
 *
 * - **Windows:** DPAPI, unchanged.
 * - **Linux:** the Secret Service keyring over D-Bus, falling back to the owner-only key file when there is no
 *   keyring (headless, minimal window manager, no service on the bus).
 * - **Anything else (macOS):** the key file until a Keychain provider exists.
 *
 * The keyring is only touched when the vault is first used, and any failure there is a fallback, never a crash.
 */
internal object DesktopVaults {

    fun forCurrentOs(
        stateDir: File,
        osName: String = System.getProperty("os.name", ""),
        keyring: () -> SecretKeyStore = { DbusSecretKeyStore() },
    ): IdentityKeyVault {
        val keyFile = File(File(stateDir, "identity"), "vault.key")
        return when {
            osName.startsWith("Windows", ignoreCase = true) -> IdentityKeyVault.Dpapi
            osName.startsWith("Linux", ignoreCase = true) -> IdentityKeyVault.withKeyring(LazyKeyring(keyring), keyFile)
            else -> IdentityKeyVault.forKeyFile(keyFile)
        }
    }

    /** Builds the real keyring client on first use, so a failure to construct it is a normal fallback. */
    private class LazyKeyring(private val create: () -> SecretKeyStore) : SecretKeyStore {
        private val delegate: SecretKeyStore by lazy(create)
        override fun lookup(): ByteArray? = delegate.lookup()
        override fun store(key: ByteArray) = delegate.store(key)
    }
}
