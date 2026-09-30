@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.security.identity.IdentityKeyVault
import kotlin.test.Test
import kotlin.test.assertSame

class TestIdentityVaultTest {
    @Test
    fun windowsKeepsTheRealVault() {
        assertSame(IdentityKeyVault.Dpapi, testIdentityVault("Windows 11"))
    }

    @Test
    fun anyOtherOsGetsThePassThroughVault() {
        assertSame(IdentityKeyVault.PassThrough, testIdentityVault("Linux"))
        assertSame(IdentityKeyVault.PassThrough, testIdentityVault("Mac OS X"))
    }
}
