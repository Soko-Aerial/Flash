@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.security.identity.IdentityKeyVault
import java.io.File

/**
 * The identity vault the desktop tests give `DesktopEngine`. Windows keeps the real DPAPI vault, so those runs still
 * exercise it; every other OS has no DPAPI (`IdentityKeyVault.Dpapi` throws `UnsatisfiedLinkError` there, which turned
 * the Linux CI job red), so it gets the pass-through vault. Mirrors `fixtureIdentityVault` in `:core:engine`'s tests.
 */
internal fun testIdentityVault(osName: String = System.getProperty("os.name", "")): IdentityKeyVault =
    if (osName.startsWith("Windows", ignoreCase = true)) IdentityKeyVault.Dpapi else IdentityKeyVault.PassThrough

/**
 * A [DesktopEngine] for tests, with [testIdentityVault]. One helper instead of an opt-in in every test file: the
 * vault type is an internal API, and this is the only place that should name it.
 */
internal fun testDesktopEngine(receivedRoot: File? = null, stateDir: File): DesktopEngine =
    DesktopEngine(receivedRoot = receivedRoot, stateDir = stateDir, identityVault = testIdentityVault())
