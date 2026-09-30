@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.security.identity.IdentityKeyVault
import org.junit.Assert.assertSame
import org.junit.Test

class FixtureIdentityVaultTest {
    @Test
    fun windowsKeepsTheRealVaultSoExistingKeyFilesStayReadable() {
        assertSame(IdentityKeyVault.Dpapi, fixtureIdentityVault("Windows 11"))
    }

    @Test
    fun anyOtherOsGetsThePassThroughVaultBecauseItHasNoDpapi() {
        assertSame(IdentityKeyVault.PassThrough, fixtureIdentityVault("Linux"))
        assertSame(IdentityKeyVault.PassThrough, fixtureIdentityVault("Mac OS X"))
    }
}
