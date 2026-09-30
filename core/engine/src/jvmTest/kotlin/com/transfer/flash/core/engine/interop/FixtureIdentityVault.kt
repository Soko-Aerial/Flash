@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.security.identity.IdentityKeyVault

/**
 * The identity vault the interop fixtures hand to `PersistedFlashCrypto`.
 *
 * Windows keeps the real DPAPI vault: the fixtures' state directories live in `java.io.tmpdir` and outlive a run
 * (the interactive harness keeps its identity so a phone's pin stays valid), so their key files must stay
 * readable. Every other OS has no DPAPI, and `IdentityKeyVault.Dpapi` throws `UnsatisfiedLinkError` on first use
 * there, which is what turned the Linux CI job red (ADR-035: tests use the pass-through vault off Windows).
 */
internal fun fixtureIdentityVault(osName: String = System.getProperty("os.name", "")): IdentityKeyVault =
    if (osName.startsWith("Windows", ignoreCase = true)) IdentityKeyVault.Dpapi else IdentityKeyVault.PassThrough
